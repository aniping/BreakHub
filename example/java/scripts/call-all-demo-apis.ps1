param(
    [string]$BaseUrl = "http://127.0.0.1:18622"
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")

function Invoke-DemoCurl {
    param(
        [string]$Title,
        [string]$Method,
        [string]$Path,
        [string]$JsonBody = ""
    )

    Write-Host ""
    Write-Host "=== $Title ==="

    $curlArgs = @(
        "-sS",
        "--fail"
    )

    if ($Method -eq "POST") {
        $curlArgs += @(
            "-X", "POST",
            "$BaseUrl$Path",
            "-H", "Content-Type: application/json"
        )
    } else {
        $curlArgs += @("$BaseUrl$Path")
    }

    $payloadFile = $null
    if ($JsonBody.Length -gt 0) {
        $payloadFile = Join-Path ([System.IO.Path]::GetTempPath()) ("breakhub-" + [System.Guid]::NewGuid().ToString("N") + ".json")
        [System.IO.File]::WriteAllText($payloadFile, $JsonBody, [System.Text.Encoding]::UTF8)
        $curlArgs += @("--data-binary", "@$payloadFile")
    }

    & curl.exe @curlArgs
    $exitCode = $LASTEXITCODE

    if ($payloadFile -and (Test-Path -LiteralPath $payloadFile)) {
        Remove-Item -LiteralPath $payloadFile
    }

    if ($exitCode -ne 0) {
        throw "curl failed: $Title"
    }
    Write-Host ""
}

Invoke-DemoCurl `
    -Title "GET /api/demo/ping" `
    -Method "GET" `
    -Path "/api/demo/ping"

Invoke-DemoCurl `
    -Title "POST /api/demo/initialize - VNA slot 1" `
    -Method "POST" `
    -Path "/api/demo/initialize" `
    -JsonBody '{"instType":"VNA","slotId":1}'

Invoke-DemoCurl `
    -Title "POST /api/demo/initialize - VNA slot 2" `
    -Method "POST" `
    -Path "/api/demo/initialize" `
    -JsonBody '{"instType":"VNA","slotId":2}'

Invoke-DemoCurl `
    -Title "POST /api/demo/initialize - SA slot 1" `
    -Method "POST" `
    -Path "/api/demo/initialize" `
    -JsonBody '{"instType":"SA","slotId":1}'

Invoke-DemoCurl `
    -Title "POST /api/demo/initialize - SA slot 2" `
    -Method "POST" `
    -Path "/api/demo/initialize" `
    -JsonBody '{"instType":"SA","slotId":2}'

Invoke-DemoCurl `
    -Title "POST /api/demo/initialize - DMM slot 2" `
    -Method "POST" `
    -Path "/api/demo/initialize" `
    -JsonBody '{"instType":"DMM","slotId":2}'

Invoke-DemoCurl `
    -Title "POST /api/demo/initialize - DMM slot 3" `
    -Method "POST" `
    -Path "/api/demo/initialize" `
    -JsonBody '{"instType":"DMM","slotId":3}'

Invoke-DemoCurl `
    -Title "POST /api/demo/initialize - PSU slot 4" `
    -Method "POST" `
    -Path "/api/demo/initialize" `
    -JsonBody '{"instType":"PSU","slotId":4}'

Invoke-DemoCurl `
    -Title "POST /api/demo/initialize - OSC slot 5" `
    -Method "POST" `
    -Path "/api/demo/initialize" `
    -JsonBody '{"instType":"OSC","slotId":5}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - VNA start sample 1" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"VNA","cmdName":"start","slotId":1,"params":{"mode":"AUTO","durationMs":1000,"operator":"curl-demo"}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - VNA start sample 2" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"VNA","cmdName":"start","slotId":1,"params":{"mode":"MANUAL","durationMs":1500,"operator":"curl-demo","trace":"S11"}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - VNA start sample 3" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"VNA","cmdName":"start","slotId":2,"params":{"mode":"MANUAL","durationMs":1500,"operator":"curl-demo","trace":"S11"}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - VNA start sample 3" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"VNA","cmdName":"start","slotId":1,"params":{"mode":"AUTO","durationMs":2000,"operator":"curl-demo","trace":"S21","powerDbm":-10}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - VNA calibrate" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"VNA","cmdName":"calibrate","slotId":1,"params":{"kit":"SOLT","ports":[1,2],"temperatureC":25.4}}'

$LargeText = "breakhub-large-text-" * 4096
$LargeTextBody = @{
    instType = "VNA"
    cmdName = "largeText"
    slotId = 1
    params = @{
        scenario = "large-text-payload"
        text = $LargeText
        repeatCount = 4096
        expectedChars = $LargeText.Length
    }
} | ConvertTo-Json -Compress -Depth 6

Invoke-DemoCurl `
    -Title "POST /api/demo/control - VNA large text payload" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody $LargeTextBody

Invoke-DemoCurl `
    -Title "POST /api/demo/control - SA measure sample 1" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"SA","cmdName":"measure","slotId":2,"params":{"frequencyHz":1000000000,"spanHz":10000000,"points":201}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - SA measure sample 2" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"SA","cmdName":"measure","slotId":2,"params":{"frequencyHz":2400000000,"spanHz":20000000,"points":401,"detector":"PEAK"}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - SA measure sample 3" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"SA","cmdName":"measure","slotId":2,"params":{"frequencyHz":5800000000,"spanHz":40000000,"points":801,"detector":"RMS"}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - DMM readVoltage sample 1" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"DMM","cmdName":"readVoltage","slotId":3,"params":{"range":"10V","samples":5,"nplc":1}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - DMM readVoltage sample 2" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"DMM","cmdName":"readVoltage","slotId":3,"params":{"range":"1V","samples":20,"nplc":10}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - PSU setOutput sample 1" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"PSU","cmdName":"setOutput","slotId":4,"params":{"channel":1,"voltage":3.3,"currentLimit":0.5,"enabled":true}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - PSU setOutput sample 2" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"PSU","cmdName":"setOutput","slotId":4,"params":{"channel":2,"voltage":5.0,"currentLimit":1.2,"enabled":true}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - OSC capture sample 1" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"OSC","cmdName":"capture","slotId":5,"params":{"channel":1,"timebaseUs":20,"trigger":"rising","sampleRateHz":1000000000}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - OSC capture sample 2" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"OSC","cmdName":"capture","slotId":5,"params":{"channel":2,"timebaseUs":50,"trigger":"falling","sampleRateHz":500000000}}'

Invoke-DemoCurl `
    -Title "POST /api/demo/control - VNA stop" `
    -Method "POST" `
    -Path "/api/demo/control" `
    -JsonBody '{"instType":"VNA","cmdName":"stop","slotId":1,"params":{"reason":"script-finished"}}'

Write-Host ""
Write-Host "All java-demo API calls finished."
