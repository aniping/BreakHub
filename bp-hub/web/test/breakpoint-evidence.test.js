import assert from 'node:assert/strict'
import test from 'node:test'

import {
  conditionEvidenceFromReference,
  conditionFromReferenceField,
  existingConditionIndex,
  observedCommandSuggestions,
  observedObjectSuggestions,
  referenceChangesTarget,
  referenceEvidence,
  referenceInteractions,
} from '../src/breakpoint-evidence.js'
import { buildConditionPayload, conditionEditor } from '../src/breakpoint-condition.js'
import { parseJson } from '../src/json.js'

const interfaces = [
  { object: 'Ticket14.exercise', command: 'run', interaction_count: 2, last_seen_at: '2026-07-18T01:00:00Z', schema_changed: false, field_schema: [{ path: 'request.mode', type: 'string' }] },
  { object: 'Ticket14.exercise', command: 'stop', interaction_count: 1, last_seen_at: '2026-07-18T00:00:00Z', schema_changed: true, field_schema: [] },
  { object: 'SA', command: 'measure', interaction_count: 4, last_seen_at: '2026-07-17T23:00:00Z', schema_changed: false, field_schema: [] },
]

const interactions = [
  {
    interaction_id: 'newest', object: 'Ticket14.exercise', command: 'run', before_at: '2026-07-18T01:00:00Z',
    lifecycle: 'completed',
    original_params: parseJson('{"request":{"mode":"full","points":9007199254740993},"channels":[1,2]}'),
    result: { response: { mode: 'confirmed', points: 42 }, channels: ['left'], status: 'ready' },
  },
  {
    interaction_id: 'older', object: 'Ticket14.exercise', command: 'run', before_at: '2026-07-18T00:30:00Z',
    lifecycle: 'completed',
    original_params: { request: { mode: 'quick', points: 801 }, channels: [2] },
    result: { response: { mode: 'must-not-be-used' }, other_result_only: true },
  },
  { interaction_id: 'other', object: 'SA', command: 'measure', before_at: '2026-07-17T23:00:00Z', lifecycle: 'running', original_params: { range: 8 } },
]

function evidenceFor(condition, interaction) {
  return conditionEvidenceFromReference(
    condition,
    referenceEvidence(interaction, condition.source),
  )
}

test('builds observed Object and Command suggestions with evidence', () => {
  assert.deepEqual(observedObjectSuggestions(interfaces)[1], {
    object: 'Ticket14.exercise',
    interface_count: 2,
    interaction_count: 3,
    schema_changed_count: 1,
    last_seen_at: '2026-07-18T01:00:00Z',
  })
  assert.deepEqual(observedCommandSuggestions(interfaces, 'Ticket14.exercise').map(item => item.command), ['run', 'stop'])
})

test('filters reference calls by the free-text target and keeps newest first', () => {
  assert.deepEqual(referenceInteractions(interactions, '', '').map(item => item.interaction_id), ['newest', 'older', 'other'])
  assert.deepEqual(referenceInteractions(interactions, 'Ticket14.exercise', 'run').map(item => item.interaction_id), ['newest', 'older'])
})

test('derives params and result evidence only from the same selected reference interaction', () => {
  const paramsEvidence = referenceEvidence(interactions[0], 'params')
  const resultEvidence = referenceEvidence(interactions[0], 'result')
  const fields = paramsEvidence.fields
  const mode = fields.find(field => field.path === 'request.mode')
  const points = fields.find(field => field.path === 'request.points')
  const channels = fields.find(field => field.path === 'channels')

  assert.equal(paramsEvidence.available, true)
  assert.equal(resultEvidence.available, true)
  assert.equal(mode.sample, 'full')
  assert.equal(points.sample.toString(), '9007199254740993')
  assert.deepEqual(conditionFromReferenceField(mode, 'params'), { source: 'params', field_path: 'request.mode', operator: 'eq', value: 'full' })
  assert.deepEqual(conditionFromReferenceField(channels, 'params'), { source: 'params', field_path: 'channels', operator: 'contains_any', value: [1, 2] })
  assert.deepEqual(conditionFromReferenceField(resultEvidence.fields.find(field => field.path === 'response.mode'), 'result'), {
    source: 'result', field_path: 'response.mode', operator: 'eq', value: 'confirmed',
  })
  assert.equal(resultEvidence.fields.some(field => field.path === 'request.mode'), false)
  assert.equal(resultEvidence.fields.some(field => field.path === 'other_result_only'), false)
})

test('distinguishes unavailable running result evidence from an available empty result shape', () => {
  const running = referenceEvidence(interactions[2], 'result')
  const completedEmpty = referenceEvidence({ lifecycle: 'completed', original_params: {}, result: {} }, 'result')

  assert.equal(running.available, false)
  assert.match(running.unavailable_reason, /尚未完成/)
  assert.deepEqual(running.fields, [])
  assert.equal(completedEmpty.available, true)
  assert.deepEqual(completedEmpty.fields, [])
})

test('recalculates non-blocking verification when source or reference changes without clearing the draft', () => {
  const editor = conditionEditor({ source: 'params', field_path: 'request.mode', operator: 'eq', value: 'full' })
  assert.equal(evidenceFor(editor, interactions[0]).verified, true)

  editor.source = 'result'
  assert.equal(editor.field_path, 'request.mode')
  assert.equal(editor.operator, 'eq')
  assert.deepEqual(editor.values, [{ type: 'string', text: 'full' }])
  assert.equal(evidenceFor(editor, interactions[0]).verified, false)
  assert.match(evidenceFor(editor, interactions[0]).warning, /未验证条件/)
  assert.equal(evidenceFor({ ...editor, field_path: 'response.mode' }, interactions[0]).verified, true)
  assert.equal(evidenceFor({ ...editor, field_path: 'response.mode' }, interactions[1]).verified, true)
  const referenceSensitiveCondition = { ...editor, field_path: 'status' }
  assert.equal(evidenceFor(referenceSensitiveCondition, interactions[0]).verified, true)
  assert.equal(evidenceFor(referenceSensitiveCondition, interactions[1]).verified, false)

  const payload = buildConditionPayload([editor])
  assert.equal(payload.error, '')
  assert.equal(payload.conditions[0].source, 'result')
})

test('deduplicates field conditions and requires confirmation only for a different populated target', () => {
  const conditions = [
    { source: 'params', field_path: 'request.mode' },
    { source: 'result', field_path: 'request.mode' },
  ]
  assert.equal(existingConditionIndex(conditions, 'params', 'request.mode'), 0)
  assert.equal(existingConditionIndex(conditions, 'result', 'request.mode'), 1)
  assert.equal(existingConditionIndex(conditions, 'params', 'request.points'), -1)
  assert.equal(referenceChangesTarget({ object: '', command: '' }, interactions[0]), false)
  assert.equal(referenceChangesTarget({ object: 'Ticket14.exercise', command: 'run' }, interactions[0]), false)
  assert.equal(referenceChangesTarget({ object: 'SA', command: 'measure' }, interactions[0]), true)
})
