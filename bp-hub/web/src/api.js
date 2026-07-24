import { parseJson, stringifyJson } from './json.js'

let csrfToken = ''

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `请求失败（${status}）`)
    this.status = status
    this.code = body?.code || 'REQUEST_FAILED'
  }
}

async function parseResponse(response) {
  if (response.status === 204) return null
  const text = await response.text()
  if (!text) return null
  try {
    return parseJson(text)
  } catch {
    return { message: text }
  }
}

export async function request(path, options = {}) {
  const method = options.method || 'GET'
  const headers = { ...(options.headers || {}) }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfToken) {
    headers['X-MBP-XSRF-TOKEN'] = csrfToken
  }
  const response = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers,
    keepalive: options.keepalive === true,
    body: options.body === undefined ? undefined : stringifyJson(options.body),
  })
  const body = await parseResponse(response)
  if (!response.ok) throw new ApiError(response.status, body)
  return body
}

export async function download(path, filename) {
  const response = await fetch(path, { credentials: 'same-origin' })
  if (!response.ok) throw new ApiError(response.status, await parseResponse(response))
  const href = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = href
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(href), 0)
}

export async function readSession() {
  const session = await request('/api/auth/session')
  csrfToken = session.csrf_token
  return session
}

export async function login(username, password) {
  await request('/api/auth/login', { method: 'POST', body: { username, password } })
  return readSession()
}

export async function logout() {
  await request('/api/auth/logout', { method: 'POST' })
  csrfToken = ''
}
