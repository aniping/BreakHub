export function filterAndSortInteractions(items, filters = {}) {
  const query = filters.query?.trim().toLocaleLowerCase() || ''
  const from = timestamp(filters.from)
  const to = timestamp(filters.to)
  return items
    .filter(item => {
      if (query && ![item.interaction_id, item.object, item.command]
        .some(value => String(value ?? '').toLocaleLowerCase().includes(query))) return false
      if (filters.object && item.object !== filters.object) return false
      if (filters.status && interactionStatus(item) !== filters.status) return false
      if (filters.pausePoint && !item.pauses?.some(pause => pause.pause_point === filters.pausePoint)) return false
      const calledAt = timestamp(item.before_at)
      if (from !== null && (calledAt === null || calledAt < from)) return false
      if (to !== null && (calledAt === null || calledAt > to)) return false
      return true
    })
    .toSorted((left, right) => {
      const pauseOrder = Number(Boolean(right.current_pause)) - Number(Boolean(left.current_pause))
      if (pauseOrder) return pauseOrder
      const timeOrder = (activityTimestamp(right) ?? 0) - (activityTimestamp(left) ?? 0)
      return timeOrder || left.interaction_id.localeCompare(right.interaction_id)
    })
}

export function interactionStatus(item) {
  if (item.current_pause) return 'paused'
  return item.lifecycle === 'completed' ? 'completed' : 'in_progress'
}

export function reconcileBrowsedInteractionId(currentId, visibleItems) {
  if (!currentId) return null
  return visibleItems.some(item => item.interaction_id === currentId) ? currentId : null
}

export function drawerCandidateInteractions(source, allItems, filteredItems) {
  return source === 'external' ? allItems : filteredItems
}

export function selectablePauseTarget(item) {
  const pause = item?.current_pause
  const interactionId = typeof item?.interaction_id === 'string' ? item.interaction_id.trim() : ''
  if (item?.status !== 'paused'
      || pause?.status !== 'paused'
      || pause?.has_pending_injection !== false
      || !interactionId
      || !['before', 'after'].includes(pause.pause_point)) return null
  return { interaction_id: interactionId, pause_point: pause.pause_point }
}

export function pauseSelectionKey(target) {
  return `${target.interaction_id}\u0000${target.pause_point}`
}

export function reconcileSelectedPauseKeys(selectedKeys, items) {
  const selectableKeys = new Set(items
    .map(selectablePauseTarget)
    .filter(Boolean)
    .map(pauseSelectionKey))
  return [...new Set(selectedKeys)].filter(key => selectableKeys.has(key))
}

export function buildContinueSelectedRequest(selectedKeys, items) {
  const selected = new Set(selectedKeys)
  const emitted = new Set()
  const targets = []
  for (const item of items) {
    const target = selectablePauseTarget(item)
    if (!target) continue
    const key = pauseSelectionKey(target)
    if (!selected.has(key) || emitted.has(key)) continue
    emitted.add(key)
    targets.push(target)
  }
  return { targets }
}

function activityTimestamp(item) {
  return timestamp(item.current_pause?.paused_at ?? item.after_at ?? item.before_at)
}

function timestamp(value) {
  if (!value) return null
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? null : parsed
}
