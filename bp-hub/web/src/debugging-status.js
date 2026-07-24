export function debuggingStatusLabel(debugging) {
  const reporting = debugging?.reporting
  if (reporting?.status === 'expired') return '空闲 · 业务上报租约已失效'
  if (debugging?.status !== 'debugging') return '未启动'
  if (reporting?.status === 'degraded') return '运行中 · 业务服务续签异常'
  if (reporting?.status === 'healthy' && reporting?.channel_status === 'degraded') {
    return '运行中 · 业务上报通道异常'
  }
  return reporting?.status === 'healthy'
    ? '运行中 · 业务上报正常'
    : '运行中'
}

export function reportingErrorLabel(debugging) {
  const lastError = debugging?.reporting?.last_error
  return typeof lastError === 'string' && lastError.trim()
    ? `最近错误：${lastError.trim()}`
    : ''
}
