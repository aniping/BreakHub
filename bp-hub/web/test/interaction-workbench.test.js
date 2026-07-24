import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildContinueSelectedRequest,
  drawerCandidateInteractions,
  filterAndSortInteractions,
  interactionStatus,
  pauseSelectionKey,
  reconcileBrowsedInteractionId,
  reconcileSelectedPauseKeys,
  selectablePauseTarget,
} from '../src/interaction-workbench.js'

const interactions = [
  {
    interaction_id: 'completed-1',
    object: 'Power',
    command: 'read',
    lifecycle: 'completed',
    before_at: '2026-07-15T09:00:00Z',
    after_at: '2026-07-15T09:00:01Z',
    pauses: [],
  },
  {
    interaction_id: 'paused-after',
    object: 'Power',
    command: 'write',
    lifecycle: 'completed',
    before_at: '2026-07-15T08:00:00Z',
    current_pause: { pause_point: 'after', paused_at: '2026-07-15T08:00:02Z' },
    pauses: [{ pause_point: 'after', status: 'paused' }],
  },
  {
    interaction_id: 'running-1',
    object: 'Sensor',
    command: 'sample',
    lifecycle: 'running',
    before_at: '2026-07-15T10:00:00Z',
    pauses: [{ pause_point: 'before', status: 'continued' }],
  },
]

test('keeps paused interactions first and maps lifecycle status explicitly', () => {
  assert.deepEqual(
    filterAndSortInteractions(interactions, {}).map(item => item.interaction_id),
    ['paused-after', 'running-1', 'completed-1'],
  )
  assert.equal(interactionStatus(interactions[0]), 'completed')
  assert.equal(interactionStatus(interactions[1]), 'paused')
  assert.equal(interactionStatus(interactions[2]), 'in_progress')
})

test('filters by keyword, object, status, pause point and inclusive call time', () => {
  assert.deepEqual(
    filterAndSortInteractions(interactions, { query: 'WRITE' }).map(item => item.interaction_id),
    ['paused-after'],
  )
  assert.deepEqual(
    filterAndSortInteractions(interactions, { object: 'Sensor', status: 'in_progress' })
      .map(item => item.interaction_id),
    ['running-1'],
  )
  assert.deepEqual(
    filterAndSortInteractions(interactions, { pausePoint: 'before' }).map(item => item.interaction_id),
    ['running-1'],
  )
  assert.deepEqual(
    filterAndSortInteractions(interactions, {
      from: '2026-07-15T08:30:00Z',
      to: '2026-07-15T09:30:00Z',
    }).map(item => item.interaction_id),
    ['completed-1'],
  )
})

test('keeps the opened interaction when it remains visible after list reordering', () => {
  assert.equal(
    reconcileBrowsedInteractionId('completed-1', [interactions[1], interactions[0]]),
    'completed-1',
  )
})

test('closes the drawer when the opened interaction is no longer visible', () => {
  assert.equal(reconcileBrowsedInteractionId('completed-1', [interactions[1]]), null)
})

test('does not auto-open a newly paused interaction when the drawer is closed', () => {
  assert.equal(reconcileBrowsedInteractionId(null, [interactions[1], interactions[0]]), null)
})

test('closes a resumed interaction after it leaves the paused filter', () => {
  const resumed = { ...interactions[1], current_pause: null }
  const visible = filterAndSortInteractions([resumed], { status: 'paused' })

  assert.deepEqual(visible, [])
  assert.equal(reconcileBrowsedInteractionId(resumed.interaction_id, visible), null)
})

test('keeps an externally opened interaction stable without changing retained filters', () => {
  const filters = { status: 'completed' }
  const filtered = filterAndSortInteractions(interactions, filters)
  const refreshed = structuredClone(interactions)
  const externalCandidates = drawerCandidateInteractions('external', refreshed, filtered)
  const listCandidates = drawerCandidateInteractions('list', refreshed, filtered)

  assert.deepEqual(filtered.map(item => item.interaction_id), ['completed-1'])
  assert.deepEqual(filters, { status: 'completed' })
  assert.equal(externalCandidates, refreshed)
  assert.equal(listCandidates, filtered)
  assert.equal(reconcileBrowsedInteractionId('paused-after', externalCandidates), 'paused-after')
  assert.equal(reconcileBrowsedInteractionId('paused-after', listCandidates), null)
})

test('only exposes an active pause without pending injection as selectable', () => {
  const selectable = {
    interaction_id: 'before-ready',
    status: 'paused',
    current_pause: {
      status: 'paused',
      pause_point: 'before',
      has_pending_injection: false,
    },
  }

  assert.deepEqual(selectablePauseTarget(selectable), {
    interaction_id: 'before-ready',
    pause_point: 'before',
  })
  assert.equal(selectablePauseTarget({ ...selectable, status: 'completed' }), null)
  assert.equal(selectablePauseTarget({
    ...selectable,
    current_pause: { ...selectable.current_pause, status: 'continued' },
  }), null)
  assert.equal(selectablePauseTarget({
    ...selectable,
    current_pause: { ...selectable.current_pause, has_pending_injection: true },
  }), null)
  assert.equal(selectablePauseTarget({
    ...selectable,
    current_pause: { ...selectable.current_pause, has_pending_injection: undefined },
  }), null)
  assert.equal(selectablePauseTarget({
    ...selectable,
    current_pause: { ...selectable.current_pause, pause_point: 'during' },
  }), null)
})

test('reconciles explicit selection without selecting new or changed pauses', () => {
  const before = readyPause('same', 'before')
  const after = readyPause('after-ready', 'after')
  const selected = [pauseSelectionKey(selectablePauseTarget(before))]

  assert.deepEqual(reconcileSelectedPauseKeys(selected, [before, after]), selected)
  assert.deepEqual(reconcileSelectedPauseKeys([], [before, after]), [])
  assert.deepEqual(reconcileSelectedPauseKeys(selected, [readyPause('same', 'after'), after]), [])
  assert.deepEqual(reconcileSelectedPauseKeys(selected, [{
    ...before,
    current_pause: { ...before.current_pause, has_pending_injection: true },
  }]), [])
  assert.deepEqual(reconcileSelectedPauseKeys(selected, []), [])
})

test('builds a stable deduplicated mixed-stage request from current selectable pauses', () => {
  const items = [
    readyPause('before-selected', 'before'),
    readyPause('unselected', 'before'),
    readyPause('after-selected', 'after'),
  ]
  const beforeKey = pauseSelectionKey(selectablePauseTarget(items[0]))
  const afterKey = pauseSelectionKey(selectablePauseTarget(items[2]))

  assert.deepEqual(buildContinueSelectedRequest([afterKey, beforeKey, beforeKey], items), {
    targets: [
      { interaction_id: 'before-selected', pause_point: 'before' },
      { interaction_id: 'after-selected', pause_point: 'after' },
    ],
  })
  assert.deepEqual(buildContinueSelectedRequest([], items), { targets: [] })
})

function readyPause(interactionId, pausePoint) {
  return {
    interaction_id: interactionId,
    status: 'paused',
    current_pause: {
      status: 'paused',
      pause_point: pausePoint,
      has_pending_injection: false,
    },
  }
}
