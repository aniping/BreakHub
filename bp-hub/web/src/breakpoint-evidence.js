import { isJsonNumber } from './json.js'

function timestamp(value) {
  const result = new Date(value || 0).getTime()
  return Number.isFinite(result) ? result : 0
}

function interfaceKey(item) {
  return `${item.object}\u0000${item.command}`
}

function valueType(value) {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  if (isJsonNumber(value) || typeof value === 'number') return 'number'
  return typeof value
}

function scalar(value) {
  return value === null
    || ['string', 'number', 'boolean'].includes(typeof value)
    || isJsonNumber(value)
}

function readPath(value, path) {
  let current = value
  for (const segment of path.split('.')) {
    if (!current || typeof current !== 'object'
      || !Object.prototype.hasOwnProperty.call(current, segment)) return { found: false }
    current = current[segment]
  }
  return { found: true, value: current }
}

function flattenPayload(value, path = '', result = []) {
  if (isJsonNumber(value) || value === null || typeof value !== 'object') {
    if (path) result.push({ path, sample: value, type: valueType(value) })
    return result
  }
  if (Array.isArray(value)) {
    if (path) result.push({ path, sample: value, type: 'array' })
    return result
  }
  for (const [key, child] of Object.entries(value)) {
    flattenPayload(child, path ? `${path}.${key}` : key, result)
  }
  return result
}

export function observedObjectSuggestions(interfaces) {
  const groups = new Map()
  for (const item of interfaces) {
    const group = groups.get(item.object) || {
      object: item.object,
      interface_count: 0,
      interaction_count: 0,
      schema_changed_count: 0,
      last_seen_at: null,
    }
    group.interface_count += 1
    group.interaction_count += item.interaction_count || 0
    group.schema_changed_count += item.schema_changed ? 1 : 0
    if (timestamp(item.last_seen_at) > timestamp(group.last_seen_at)) group.last_seen_at = item.last_seen_at
    groups.set(item.object, group)
  }
  return [...groups.values()].sort((left, right) => left.object.localeCompare(right.object))
}

export function observedCommandSuggestions(interfaces, object) {
  const normalizedObject = object.trim()
  return interfaces
    .filter(item => !normalizedObject || item.object === normalizedObject)
    .map(item => ({
      object: item.object,
      command: item.command,
      interaction_count: item.interaction_count || 0,
      field_count: item.field_schema?.length || 0,
      schema_changed: Boolean(item.schema_changed),
      last_seen_at: item.last_seen_at,
    }))
    .sort((left, right) => timestamp(right.last_seen_at) - timestamp(left.last_seen_at))
}

export function referenceInteractions(interactions, object, command, limit = 5) {
  const normalizedObject = object.trim()
  const normalizedCommand = command.trim()
  return interactions
    .filter(item => (!normalizedObject || item.object === normalizedObject)
      && (!normalizedCommand || item.command === normalizedCommand))
    .sort((left, right) => timestamp(right.before_at) - timestamp(left.before_at))
    .slice(0, limit)
}

export function referenceEvidence(interaction, source) {
  if (!interaction) {
    return { source, available: false, unavailable_reason: '尚未选择参考调用。', fields: [] }
  }
  if (source === 'result' && (interaction.lifecycle !== 'completed'
    || !Object.prototype.hasOwnProperty.call(interaction, 'result'))) {
    return { source, available: false, unavailable_reason: '参考调用尚未完成，没有 result 证据。', fields: [] }
  }
  const key = source === 'result' ? 'result' : 'original_params'
  if (!Object.prototype.hasOwnProperty.call(interaction, key)) {
    return { source, available: false, unavailable_reason: `参考调用没有 ${source} 证据。`, fields: [] }
  }
  const payload = interaction[key]
  const fields = flattenPayload(payload).map(field => {
    const usable = field.type === 'array'
      ? field.sample.length > 0 && field.sample.every(scalar)
      : scalar(field.sample)
    return {
      ...field,
      item_types: field.type === 'array' ? [...new Set(field.sample.map(valueType))] : [],
      usable,
    }
  })
  return { source, available: true, unavailable_reason: '', payload, fields }
}

export function conditionFromReferenceField(field, source) {
  if (!field?.usable) return null
  if (Array.isArray(field.sample)) {
    return { source, field_path: field.path, operator: 'contains_any', value: field.sample }
  }
  return { source, field_path: field.path, operator: 'eq', value: field.sample }
}

export function conditionEvidenceFromReference(condition, evidence) {
  const source = condition.source
  if (!evidence.available) {
    return { verified: false, warning: `未验证条件：${evidence.unavailable_reason}` }
  }
  const fieldPath = condition.field_path.trim()
  if (fieldPath && readPath(evidence.payload, fieldPath).found) {
    return { verified: true, warning: '' }
  }
  return {
    verified: false,
    warning: `未验证条件：参考调用的 ${source} 中找不到字段路径 ${fieldPath || '（空）'}。`,
  }
}

export function existingConditionIndex(conditions, source, fieldPath) {
  return conditions.findIndex(condition => condition.source === source
    && condition.field_path.trim() === fieldPath)
}

export function referenceChangesTarget(form, interaction) {
  const object = form.object.trim()
  const command = form.command.trim()
  if (!object && !command) return false
  return object !== interaction.object || command !== interaction.command
}

export function targetInterface(interfaces, object, command) {
  const key = `${object.trim()}\u0000${command.trim()}`
  return interfaces.find(item => interfaceKey(item) === key) || null
}
