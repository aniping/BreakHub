import assert from 'node:assert/strict'
import test from 'node:test'

import {
  breakpointInterfaceKey,
  groupBreakpointsByInterface,
  toggleCollapsedKey,
} from '../src/breakpoint-hierarchy.js'

const fixture = [
  { breakpoint_id: 'before-1', object: 'VNA', command: 'start', enabled: true, hit_count: 3, last_hit_at: '2026-07-17T10:00:00Z' },
  { breakpoint_id: 'after-1', object: 'VNA', command: 'start', enabled: false, hit_count: 2, last_hit_at: '2026-07-17T11:00:00Z' },
  { breakpoint_id: 'error-1', object: 'VNA', command: 'error', enabled: true, hit_count: 1, last_hit_at: '2026-07-17T09:00:00Z' },
  { breakpoint_id: 'measure-1', object: 'SA', command: 'measure', enabled: true, hit_count: 0, last_hit_at: null },
]

test('groups breakpoint leaves under independent Object and Interface containers', () => {
  const groups = groupBreakpointsByInterface(fixture)
  assert.deepEqual(groups.map(group => group.object), ['VNA', 'SA'])
  assert.deepEqual(groups[0].interfaces.map(group => group.command), ['start', 'error'])
  assert.deepEqual(groups[0].interfaces[0].items.map(item => item.breakpoint_id), ['before-1', 'after-1'])
  assert.deepEqual(groups[0].interfaces[1].items.map(item => item.breakpoint_id), ['error-1'])
  assert.deepEqual(groups[1].interfaces[0].items.map(item => item.breakpoint_id), ['measure-1'])
  assert.deepEqual(
    { rules: groups[0].rule_count, enabled: groups[0].enabled_count, hits: groups[0].hit_count },
    { rules: 3, enabled: 2, hits: 6 },
  )
  assert.equal(groups[0].interfaces[0].last_hit_at, '2026-07-17T11:00:00Z')
})

test('collapses one Interface without changing its siblings or Object state', () => {
  const startKey = breakpointInterfaceKey('VNA', 'start')
  const errorKey = breakpointInterfaceKey('VNA', 'error')
  const collapsedInterfaces = toggleCollapsedKey(new Set(), startKey)
  assert.equal(collapsedInterfaces.has(startKey), true)
  assert.equal(collapsedInterfaces.has(errorKey), false)
  assert.equal(toggleCollapsedKey(collapsedInterfaces, startKey).size, 0)
})
