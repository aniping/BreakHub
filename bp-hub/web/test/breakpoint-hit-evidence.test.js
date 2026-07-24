import assert from 'node:assert/strict'
import test from 'node:test'

import { hitEvidenceRows } from '../src/breakpoint-hit-evidence.js'
import { parseJson } from '../src/json.js'

test('projects immutable eq and compact contains_any hit evidence for display', () => {
  const snapshot = parseJson(`{
    "condition_evidence":[
      {"source":"params","field_path":"request.mode","operator":"eq","expected_value":"safe","actual_value":"safe"},
      {"source":"result","field_path":"amount","operator":"eq","expected_value":9007199254740993.0,"actual_value":9007199254740993},
      {"source":"result","field_path":"tags","operator":"contains_any","expected_value":[2,"red"],"actual_value":["red"]}
    ]
  }`)

  assert.deepEqual(hitEvidenceRows(snapshot), [
    { source: 'params', field_path: 'request.mode', operator: 'eq', expected: '"safe"', actual: '"safe"' },
    { source: 'result', field_path: 'amount', operator: 'eq', expected: '9007199254740993.0', actual: '9007199254740993' },
    { source: 'result', field_path: 'tags', operator: 'contains_any', expected: '[2,"red"]', actual: '["red"]' },
  ])
  assert.equal(hitEvidenceRows({ condition_evidence: [] }).length, 0)
})
