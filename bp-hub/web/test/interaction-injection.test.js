import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildInjectionChanges,
  buildInjectionEditors,
  changedPointers,
  injectionEditorRevision,
} from '../src/interaction-injection.js'
import { parseJson, stringifyJson } from '../src/json.js'

test('builds nested changes only from selected existing fields', () => {
  const original = parseJson(`{
    "request":{"amount":9007199254740993.0,"enabled":true},
    "items":[1,{"any":"json"}],
    "nullable":null
  }`)
  const editors = buildInjectionEditors(original, original)

  assert.deepEqual(editors.map(editor => editor.pointer), [
    '/request',
    '/request/amount',
    '/request/enabled',
    '/items',
    '/nullable',
  ])
  assert.equal(editors.find(editor => editor.pointer === '/items').type, 'array')
  assert.equal(editors.find(editor => editor.pointer === '/nullable').locked, true)
  assert.equal(editors.some(editor => editor.pointer.includes('/0')), false)

  const amount = editors.find(editor => editor.pointer === '/request/amount')
  amount.selected = true
  amount.text = '9007199254740995.0'
  const items = editors.find(editor => editor.pointer === '/items')
  items.selected = true
  items.text = '[{"replacement":true},null]'

  const built = buildInjectionChanges(editors)
  assert.equal(built.error, '')
  assert.equal(stringifyJson(built.changes), `{"request":{"amount":9007199254740995.0},"items":[{"replacement":true},null]}`)
})

test('supports explicit null but rejects invalid structured values and conflicting object edits', () => {
  const original = parseJson('{"request":{"mode":"safe"},"items":[1]}')
  const editors = buildInjectionEditors(original, original)
  const request = editors.find(editor => editor.pointer === '/request')
  const mode = editors.find(editor => editor.pointer === '/request/mode')
  request.selected = true
  request.setNull = true
  mode.selected = true
  assert.match(buildInjectionChanges(editors).error, /父对象/)

  request.selected = false
  mode.setNull = true
  assert.equal(stringifyJson(buildInjectionChanges(editors).changes), '{"request":{"mode":null}}')

  mode.selected = false
  const items = editors.find(editor => editor.pointer === '/items')
  items.selected = true
  items.text = '{"not":"an-array"}'
  assert.match(buildInjectionChanges(editors).error, /JSON 数组/)
})

test('reports leaf and whole-array differences without exposing array indexes', () => {
  const original = parseJson('{"request":{"mode":"safe","count":1},"items":[1,2]}')
  const effective = parseJson('{"request":{"mode":"fast","count":1},"items":[2,3]}')

  assert.deepEqual(changedPointers(original, effective), ['/request/mode', '/items'])
})

test('locks descendants when an effective parent object is null', () => {
  const original = parseJson('{"request":{"mode":"safe","nested":{"count":1}}}')
  const effective = parseJson('{"request":null}')
  const editors = buildInjectionEditors(original, effective)

  assert.equal(editors.find(editor => editor.pointer === '/request').locked, true)
  assert.equal(editors.find(editor => editor.pointer === '/request/mode').locked, true)
  assert.equal(editors.find(editor => editor.pointer === '/request/nested/count').locked, true)
})

test('changes editor revision after external injection or control ownership changes', () => {
  const interaction = {
    interaction_id: 'interaction-1',
    current_pause: {
      pause_point: 'before',
      paused_at: '2026-07-15T10:00:00Z',
      effective_content: parseJson('{"amount":1}'),
      injection_status: 'none',
      injection_audit: [],
    },
  }
  const owned = { held: true, controller: 'web', owned_by_requester: true }
  const originalRevision = injectionEditorRevision(interaction, owned)

  interaction.current_pause.effective_content = parseJson('{"amount":2}')
  interaction.current_pause.injection_status = 'pending'
  interaction.current_pause.injection_audit = [{ sequence: 1 }]
  assert.notEqual(injectionEditorRevision(interaction, owned), originalRevision)

  const externallyControlled = { held: true, controller: 'mcp', owned_by_requester: false }
  assert.notEqual(
    injectionEditorRevision(interaction, externallyControlled),
    injectionEditorRevision(interaction, owned),
  )
})
