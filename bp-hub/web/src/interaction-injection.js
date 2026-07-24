import { isJsonNumber, parseJson, stringifyJson } from './json.js'

const JSON_NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/

export function buildInjectionEditors(original, current) {
  if (!isObject(original)) return []
  const editors = []
  appendEditors(editors, [], original, isObject(current) ? current : undefined, false)
  return editors
}

function appendEditors(editors, parentPath, original, current, ancestorLocked) {
  for (const [name, originalValue] of Object.entries(original)) {
    const path = [...parentPath, name]
    const currentValue = isObject(current) ? current[name] : undefined
    const type = jsonType(originalValue)
    const currentIsNull = currentValue === null
    editors.push({
      path,
      pointer: pointer(path),
      type,
      selected: false,
      setNull: originalValue !== null && currentIsNull,
      text: editorText(currentValue === undefined || currentIsNull ? originalValue : currentValue, type),
      locked: ancestorLocked || originalValue === null || (type === 'object' && currentIsNull),
    })
    if (type === 'object') {
      appendEditors(editors, path, originalValue, currentValue, ancestorLocked || currentIsNull)
    }
  }
}

export function injectionEditorRevision(interaction, control) {
  const pause = interaction?.current_pause
  if (!pause) return ''
  return stringifyJson({
    interaction_id: interaction.interaction_id,
    pause_point: pause.pause_point,
    paused_at: pause.paused_at,
    effective_content: pause.effective_content,
    injection_status: pause.injection_status,
    injection_audit_count: pause.injection_audit?.length ?? 0,
    control: {
      held: Boolean(control?.held),
      controller: control?.controller ?? 'none',
      owned_by_requester: Boolean(control?.owned_by_requester),
    },
  })
}

export function buildInjectionChanges(editors) {
  const selected = editors.filter(editor => editor.selected && !editor.locked)
  if (!selected.length) return { changes: {}, error: '请至少选择一个要修改的字段' }

  const nullObjects = selected.filter(editor => editor.type === 'object' && editor.setNull)
  for (const parent of nullObjects) {
    if (selected.some(editor => isDescendant(editor.path, parent.path))) {
      return { changes: {}, error: `${parent.pointer} 的父对象设为 null 时不能同时修改子字段` }
    }
  }

  const changes = {}
  for (const editor of selected) {
    const parsed = editorValue(editor)
    if (parsed.error) return { changes, error: `${editor.pointer}：${parsed.error}` }
    setNested(changes, editor.path, parsed.value)
  }
  return { changes, error: '' }
}

export function changedPointers(original, effective) {
  const changed = []
  appendDifferences(changed, [], original, effective)
  return changed
}

function appendDifferences(changed, path, original, effective) {
  if (isObject(original) && isObject(effective)) {
    const names = new Set([...Object.keys(original), ...Object.keys(effective)])
    for (const name of names) appendDifferences(changed, [...path, name], original[name], effective[name])
    return
  }
  if (stringifyJson(original) !== stringifyJson(effective)) changed.push(path.length ? pointer(path) : '/')
}

function editorValue(editor) {
  if (editor.setNull) return { value: null }
  if (editor.type === 'object') return { error: '对象字段只能整体设为 null，或选择其子字段修改' }
  if (editor.type === 'string') return { value: editor.text }
  if (editor.type === 'boolean') {
    if (!['true', 'false'].includes(editor.text)) return { error: '布尔值只能是 true 或 false' }
    return { value: editor.text === 'true' }
  }
  if (editor.type === 'number') {
    const text = editor.text.trim()
    if (!JSON_NUMBER.test(text)) return { error: '数字必须使用 JSON 数字格式' }
    return { value: parseJson(text) }
  }
  if (editor.type === 'array') {
    try {
      const value = parseJson(editor.text)
      return Array.isArray(value) ? { value } : { error: '请输入合法 JSON 数组' }
    } catch {
      return { error: '请输入合法 JSON 数组' }
    }
  }
  return { error: '原始 null 字段不能写入非 null 值' }
}

function editorText(value, type) {
  if (type === 'object' || value === null || value === undefined) return ''
  if (type === 'string') return value
  if (type === 'boolean') return String(value)
  if (type === 'number') return String(value)
  return stringifyJson(value)
}

function jsonType(value) {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  if (isJsonNumber(value) || typeof value === 'number') return 'number'
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'string') return 'string'
  return 'object'
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) && !isJsonNumber(value)
}

function setNested(target, path, value) {
  let node = target
  for (let index = 0; index < path.length - 1; index += 1) {
    const name = path[index]
    if (!Object.prototype.hasOwnProperty.call(node, name)) {
      Object.defineProperty(node, name, { value: {}, enumerable: true, writable: true })
    }
    node = node[name]
  }
  Object.defineProperty(node, path.at(-1), { value, enumerable: true, writable: true })
}

function isDescendant(path, parent) {
  return path.length > parent.length && parent.every((segment, index) => path[index] === segment)
}

function pointer(path) {
  return `/${path.map(segment => segment.replaceAll('~', '~0').replaceAll('/', '~1')).join('/')}`
}
