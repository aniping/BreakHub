import assert from 'node:assert/strict'
import test from 'node:test'

import { interactionListPath, validInteractionPage } from '../src/interaction-list.js'

test('builds a bounded server-side interaction query', () => {
  assert.equal(interactionListPath(2, {
    query: 'Power set',
    object: 'Power',
    command: 'set',
    status: 'paused',
    pausePoint: 'before',
    from: '2026-08-04T08:00',
    to: '2026-08-04T09:00',
  }), '/api/v1/interactions?page=2&size=100&query=Power+set&object=Power&command=set&status=paused&pause_point=before&from=2026-08-04T00%3A00%3A00.000Z&to=2026-08-04T01%3A00%3A00.000Z')
})

test('keeps the current page inside the available result range', () => {
  assert.equal(validInteractionPage(3, 5), 3)
  assert.equal(validInteractionPage(3, 2), 1)
  assert.equal(validInteractionPage(1, 0), 0)
})
