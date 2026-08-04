export const INTERACTION_PAGE_SIZE = 100

export function interactionListPath(page, filters = {}) {
  const parameters = new URLSearchParams({
    page: String(Math.max(0, page)),
    size: String(INTERACTION_PAGE_SIZE),
  })
  append(parameters, 'query', filters.query?.trim())
  append(parameters, 'object', filters.object)
  append(parameters, 'command', filters.command)
  append(parameters, 'status', filters.status)
  append(parameters, 'pause_point', filters.pausePoint)
  append(parameters, 'from', isoTime(filters.from))
  append(parameters, 'to', isoTime(filters.to))
  return `/api/v1/interactions?${parameters}`
}

export function validInteractionPage(page, totalPages) {
  if (totalPages <= 0) return 0
  return Math.min(Math.max(0, page), totalPages - 1)
}

function append(parameters, name, value) {
  if (value) parameters.set(name, value)
}

function isoTime(value) {
  if (!value) return ''
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '' : parsed.toISOString()
}
