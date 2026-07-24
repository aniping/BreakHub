import assert from 'node:assert/strict'
import test from 'node:test'

import { createJsonNumber, isJsonNumber, parseJson, stringifyJson } from '../src/json.js'

test('preserves unsafe integers and precise decimals across a JSON round trip', () => {
  const source = '{"hit_count":1,"conditions":[{"value":9007199254740993},{"value":0.12345678901234567890123456789}]}'

  const parsed = parseJson(source)

  assert.equal(parsed.hit_count, 1)
  assert.equal(isJsonNumber(parsed.conditions[0].value), true)
  assert.equal(parsed.conditions[0].value.toString(), '9007199254740993')
  assert.equal(parsed.conditions[1].value.toString(), '0.12345678901234567890123456789')
  assert.equal(stringifyJson(parsed), source)
})

test('serializes an edited numeric lexeme as a JSON number', () => {
  const payload = { value: createJsonNumber('9007199254740993.0') }

  assert.equal(stringifyJson(payload), '{"value":9007199254740993.0}')
})
