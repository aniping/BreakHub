import { createJsonNumber, isJsonNumber } from './json.js'

const JSON_NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/

export function valueEditor(value = '') {
  if (value === null) return { type: 'null', text: '' }
  if (typeof value === 'boolean') return { type: 'boolean', text: String(value) }
  if (typeof value === 'number' || isJsonNumber(value)) return { type: 'number', text: String(value) }
  return { type: 'string', text: String(value) }
}

export function conditionEditor(condition = null, defaultSource = 'params') {
  const operator = condition?.operator || 'eq'
  const singleValue = condition && Object.prototype.hasOwnProperty.call(condition, 'value')
    ? condition.value
    : ''
  const rawValues = operator === 'contains_any' ? condition?.value : [singleValue]
  return {
    source: condition?.source || defaultSource,
    field_path: condition?.field_path || '',
    operator,
    values: (rawValues?.length ? rawValues : ['']).map(valueEditor),
  }
}

export function conditionsForPausePoint(conditions, pausePoint) {
  if (pausePoint !== 'before') {
    return { conditions: [...conditions], discarded_conditions: [], became_unconditional: false }
  }
  const discarded = conditions.filter(condition => condition.source === 'result')
  const remaining = conditions.filter(condition => condition.source !== 'result')
  return {
    conditions: remaining,
    discarded_conditions: discarded,
    became_unconditional: discarded.length > 0 && remaining.length === 0,
  }
}

export function validateFieldPath(fieldPath) {
  if (!fieldPath) return '每条条件都需要 field_path'
  if (fieldPath.length > 500) return 'field_path 不能超过 500 个字符'
  if (['[', ']', '/', '$', '@', '*', '\\'].some(character => fieldPath.includes(character))) {
    return 'field_path 必须使用点分隔的对象字段路径'
  }
  for (const segment of fieldPath.split('.')) {
    if (!segment.trim()) return 'field_path 不支持空字段'
    if (/^\d+$/.test(segment)) return 'field_path 不支持数组索引'
  }
  return ''
}

export function parseEditorValue(editor) {
  if (editor.type === 'null') return { value: null }
  if (editor.type === 'boolean') {
    if (!['true', 'false'].includes(editor.text)) return { error: '布尔值只能是 true 或 false' }
    return { value: editor.text === 'true' }
  }
  if (editor.type === 'string') return { value: editor.text }
  const text = editor.text.trim()
  if (!JSON_NUMBER.test(text)) return { error: '数字值必须使用 JSON 数字格式' }
  return { value: createJsonNumber(text) }
}

export function buildConditionPayload(editors) {
  const conditions = []
  for (const condition of editors) {
    if (!['params', 'result'].includes(condition.source)) {
      return { conditions, error: '每条条件都需要有效的 source' }
    }
    const fieldPath = condition.field_path.trim()
    const fieldPathError = validateFieldPath(fieldPath)
    if (fieldPathError) return { conditions, error: fieldPathError }
    if (!condition.values.length) return { conditions, error: 'contains_any 至少需要一个候选值' }
    const values = []
    for (const editor of condition.values) {
      const parsed = parseEditorValue(editor)
      if (parsed.error) return { conditions, error: parsed.error }
      values.push(parsed.value)
    }
    conditions.push({
      source: condition.source,
      field_path: fieldPath,
      operator: condition.operator,
      value: condition.operator === 'contains_any' ? values : values[0],
    })
  }
  return { conditions, error: '' }
}
