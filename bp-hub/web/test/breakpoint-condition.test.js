import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildConditionPayload,
  conditionEditor,
  conditionsForPausePoint,
  parseEditorValue,
  validateFieldPath,
} from '../src/breakpoint-condition.js'
import { parseJson, stringifyJson } from '../src/json.js'

test('keeps an existing high precision condition editable without changing its value', () => {
  const condition = parseJson('{"source":"result","field_path":"amount","operator":"eq","value":9007199254740993.0}')
  const editor = conditionEditor(condition)

  assert.equal(editor.source, 'result')
  assert.deepEqual(editor.values, [{ type: 'number', text: '9007199254740993.0' }])
  const payload = buildConditionPayload([editor])
  assert.equal(payload.error, '')
  assert.equal(stringifyJson(payload.conditions), '[{"source":"result","field_path":"amount","operator":"eq","value":9007199254740993.0}]')
})

test('defaults new before and after conditions and always submits source explicitly', () => {
  const beforeEditor = conditionEditor()
  const afterEditor = conditionEditor(null, 'result')

  assert.equal(beforeEditor.source, 'params')
  assert.equal(afterEditor.source, 'result')
  afterEditor.field_path = 'status'
  const payload = buildConditionPayload([afterEditor])
  assert.equal(payload.error, '')
  assert.equal(payload.conditions[0].source, 'result')
})

test('discards result conditions immediately when an after draft changes to before', () => {
  const conditions = [
    conditionEditor({ source: 'params', field_path: 'mode', operator: 'eq', value: 'safe' }),
    conditionEditor({ source: 'result', field_path: 'status', operator: 'eq', value: 'ready' }),
  ]

  const partial = conditionsForPausePoint(conditions, 'before')
  assert.equal(partial.conditions.length, 1)
  assert.equal(partial.conditions[0].source, 'params')
  assert.equal(partial.discarded_conditions.length, 1)
  assert.equal(partial.became_unconditional, false)
  assert.equal(conditions.length, 2)

  const total = conditionsForPausePoint([conditions[1]], 'before')
  assert.equal(total.conditions.length, 0)
  assert.equal(total.discarded_conditions.length, 1)
  assert.equal(total.became_unconditional, true)

  const noDiscard = conditionsForPausePoint([conditions[0]], 'before')
  assert.equal(noDiscard.conditions.length, 1)
  assert.equal(noDiscard.discarded_conditions.length, 0)
  assert.equal(noDiscard.became_unconditional, false)
  assert.deepEqual(conditionsForPausePoint(conditions, 'before'), partial)
})

test('accepts only explicit boolean editor values', () => {
  assert.equal(parseEditorValue({ type: 'boolean', text: '' }).error, '布尔值只能是 true 或 false')
  assert.deepEqual(parseEditorValue({ type: 'boolean', text: 'false' }), { value: false })
})

test('validates field paths before submitting them to the backend', () => {
  assert.equal(validateFieldPath('request.mode'), '')
  assert.match(validateFieldPath('items.0.id'), /数组索引/)
  assert.match(validateFieldPath('request..mode'), /空字段/)
  assert.match(validateFieldPath('request/value'), /点分隔/)
  assert.match(validateFieldPath('$.request'), /点分隔/)
  assert.match(validateFieldPath('@'), /点分隔/)
  assert.match(validateFieldPath('request\\mode'), /点分隔/)
  assert.match(validateFieldPath('request.*'), /点分隔/)
  assert.match(validateFieldPath('x'.repeat(501)), /500/)

  const payload = buildConditionPayload([{
    source: 'params',
    field_path: 'items.0.id',
    operator: 'eq',
    values: [{ type: 'number', text: '1' }],
  }])
  assert.match(payload.error, /数组索引/)
})
