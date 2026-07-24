param(
    [string]$BindAddress = "127.0.0.1",

    [ValidateRange(1, 65535)]
    [int]$Port = 18622
)

$ErrorActionPreference = "Stop"

function Write-JsonEvent {
    param([System.Collections.IDictionary]$Event)

    [Console]::Out.WriteLine(($Event | ConvertTo-Json -Compress))
    [Console]::Out.Flush()
}

function Read-HttpRequest {
    param([System.Net.Sockets.TcpClient]$Client)

    $stream = $Client.GetStream()
    $stream.ReadTimeout = 5000
    $buffer = New-Object byte[] 4096
    $requestBytes = New-Object System.IO.MemoryStream
    $headerEnd = -1

    while ($headerEnd -lt 0) {
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -eq 0) {
            throw "connection closed before request headers were complete"
        }
        $requestBytes.Write($buffer, 0, $read)
        if ($requestBytes.Length -gt 65536) {
            throw "request headers exceed 64 KiB"
        }
        $raw = $requestBytes.ToArray()
        $headerEnd = [System.Text.Encoding]::ASCII.GetString($raw).IndexOf("`r`n`r`n", [System.StringComparison]::Ordinal)
    }

    $headerText = [System.Text.Encoding]::ASCII.GetString($raw, 0, $headerEnd)
    $headerLines = $headerText -split "`r`n"
    $requestLine = $headerLines[0] -split " "
    if ($requestLine.Count -ne 3) {
        throw "invalid HTTP request line"
    }

    $contentLength = $null
    foreach ($headerLine in $headerLines | Select-Object -Skip 1) {
        $separator = $headerLine.IndexOf(":", [System.StringComparison]::Ordinal)
        if ($separator -lt 1) {
            throw "invalid HTTP header"
        }
        $name = $headerLine.Substring(0, $separator).Trim()
        if ($name -ieq "Content-Length") {
            if ($null -ne $contentLength) {
                throw "duplicate Content-Length header"
            }
            $parsedLength = 0
            if (-not [int]::TryParse($headerLine.Substring($separator + 1).Trim(), [ref]$parsedLength) -or $parsedLength -lt 0) {
                throw "invalid Content-Length header"
            }
            $contentLength = $parsedLength
        }
    }
    if ($null -eq $contentLength) {
        throw "Content-Length header is required"
    }

    $bodyStart = $headerEnd + 4
    $expectedLength = $bodyStart + $contentLength
    while ($requestBytes.Length -lt $expectedLength) {
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -eq 0) {
            throw "connection closed before request body was complete"
        }
        $requestBytes.Write($buffer, 0, $read)
        if ($requestBytes.Length -gt 1048576) {
            throw "request exceeds 1 MiB"
        }
    }

    $raw = $requestBytes.ToArray()
    [pscustomobject]@{
        Method = $requestLine[0]
        Path = $requestLine[1]
        Body = [System.Text.Encoding]::UTF8.GetString($raw, $bodyStart, $contentLength)
        Stream = $stream
    }
}

function Test-PropertySet {
    param(
        [pscustomobject]$Payload,
        [string[]]$Expected
    )

    $actual = @($Payload.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    $actual.Count -eq $wanted.Count -and ($actual -join "`n") -ceq ($wanted -join "`n")
}

function Read-JsonPayload {
    param([string]$Body)

    $payload = $Body | ConvertFrom-Json
    if ($null -eq $payload -or $payload -isnot [pscustomobject]) {
        throw "request body must be a JSON object"
    }
    $payload
}

$ipAddress = [System.Net.IPAddress]::Parse($BindAddress)
if (-not [System.Net.IPAddress]::IsLoopback($ipAddress)) {
    throw "BindAddress must be a loopback address"
}

$listener = [System.Net.Sockets.TcpListener]::new($ipAddress, $Port)
$heldClients = New-Object System.Collections.Generic.List[System.Net.Sockets.TcpClient]

try {
    $listener.Start()
    Write-JsonEvent ([ordered]@{
        event = "listening"
        at = [System.DateTimeOffset]::UtcNow.ToString("o")
        address = $ipAddress.ToString()
        port = $Port
    })

    $createClient = $listener.AcceptTcpClient()
    try {
        $createRequest = Read-HttpRequest $createClient
        $createPayload = Read-JsonPayload $createRequest.Body
        $validCreate = $createRequest.Method -ceq "POST" `
            -and $createRequest.Path -ceq "/api/demo/debugger/enabled" `
            -and (Test-PropertySet $createPayload @("enabled")) `
            -and $createPayload.enabled -is [bool] `
            -and $createPayload.enabled
        if (-not $validCreate) {
            throw "first request is not a valid Reporting Lease create"
        }

        $leaseId = [System.Guid]::NewGuid().ToString("N")
        $responseBody = "{`"success`":true,`"result`":`"created`",`"changed`":true,`"enabled`":true,`"lease_timeout_seconds`":30,`"reporting_status`":`"healthy`",`"lease_id`":`"$leaseId`"}"
        $responseBodyBytes = [System.Text.Encoding]::UTF8.GetBytes($responseBody)
        $responseHead = "HTTP/1.1 200 OK`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($responseBodyBytes.Length)`r`nConnection: close`r`n`r`n"
        $responseHeadBytes = [System.Text.Encoding]::ASCII.GetBytes($responseHead)
        $createRequest.Stream.Write($responseHeadBytes, 0, $responseHeadBytes.Length)
        $createRequest.Stream.Write($responseBodyBytes, 0, $responseBodyBytes.Length)
        $createRequest.Stream.Flush()

        Write-JsonEvent ([ordered]@{
            event = "create_ack"
            at = [System.DateTimeOffset]::UtcNow.ToString("o")
        })
    } finally {
        $createClient.Close()
    }

    $sequence = 0
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $sequence++
        try {
            $request = Read-HttpRequest $client
        } catch {
            Write-JsonEvent ([ordered]@{
                event = "blackhole_request"
                at = [System.DateTimeOffset]::UtcNow.ToString("o")
                sequence = $sequence
                request_kind = "unknown"
                contract_valid = $false
            })
            $client.Close()
            continue
        }

        $requestKind = "unknown"
        $contractValid = $false
        try {
            $payload = Read-JsonPayload $request.Body
            $baseContractValid = $request.Method -ceq "POST" `
                -and $request.Path -ceq "/api/demo/debugger/enabled" `
                -and (Test-PropertySet $payload @("enabled", "lease_id")) `
                -and $payload.enabled -is [bool] `
                -and $payload.lease_id -is [string] `
                -and $payload.lease_id -ceq $leaseId
            if ($baseContractValid -and $payload.enabled) {
                $requestKind = "renew"
                $contractValid = $true
            } elseif ($baseContractValid -and -not $payload.enabled) {
                $requestKind = "stop"
                $contractValid = $true
            }
        } catch {
            $contractValid = $false
        }

        Write-JsonEvent ([ordered]@{
            event = "blackhole_request"
            at = [System.DateTimeOffset]::UtcNow.ToString("o")
            sequence = $sequence
            request_kind = $requestKind
            contract_valid = $contractValid
        })

        $request.Stream.ReadTimeout = [System.Threading.Timeout]::Infinite
        $heldClients.Add($client)
    }
} finally {
    foreach ($client in $heldClients) {
        $client.Close()
    }
    $listener.Stop()
}
