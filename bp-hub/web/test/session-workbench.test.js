import test from 'node:test'
import assert from 'node:assert/strict'

import {
  archiveSummary,
  createSessionWorkbenchClient,
  filterSessions,
  legalSessionActions,
} from '../src/session-workbench.js'

const sessions = [
  { session_id: 'current', name: '版本 A', source: 'local', read_only: false, current: true },
  { session_id: 'history', name: '版本 B', source: 'local', read_only: false, current: false },
  { session_id: 'evidence', name: '现场证据', source: 'imported', read_only: true, current: false },
]

test('filters local and imported sessions by type and keyword', () => {
  assert.deepEqual(filterSessions(sessions, { source: 'local' }).map(item => item.session_id), [
    'current',
    'history',
  ])
  assert.deepEqual(filterSessions(sessions, { source: 'imported', query: '现场' }).map(item => item.session_id), [
    'evidence',
  ])
  assert.deepEqual(filterSessions(sessions, { query: 'HISTORY' }).map(item => item.session_id), ['history'])
})

test('only exposes management actions legal for each session kind', () => {
  assert.deepEqual(legalSessionActions(sessions[0]), ['view', 'rename', 'export', 'clear'])
  assert.deepEqual(legalSessionActions(sessions[1]), ['view', 'rename', 'select', 'export', 'delete'])
  assert.deepEqual(legalSessionActions(sessions[2]), ['view', 'export', 'delete'])
})

test('summarizes complete archive evidence without interpreting payloads', () => {
  assert.deepEqual(archiveSummary({
    breakpoints: [{}, {}],
    interactions: [{}],
    pauses: [{ status: 'continued' }, { status: 'timed_out' }],
  }), {
    breakpointCount: 2,
    interactionCount: 1,
    pauseCount: 2,
    resolvedPauseCount: 2,
  })
})

test('ignores a stale archive response after the user browses another session', async () => {
  const pending = new Map()
  const request = path => new Promise(resolve => pending.set(path, resolve))
  const client = createSessionWorkbenchClient({ request, download: async () => {}, confirm: () => true })
  let displayed = null

  const first = client.loadArchive('first', archive => { displayed = archive })
  const second = client.loadArchive('second', archive => { displayed = archive })
  pending.get('/api/v1/sessions/second/archive')({ session: { session_id: 'second' } })
  await second
  pending.get('/api/v1/sessions/first/archive')({ session: { session_id: 'first' } })
  await first

  assert.equal(displayed.session.session_id, 'second')
})

test('imports only mbsession files and sends the parsed archive to the Web API', async () => {
  const calls = []
  const client = createSessionWorkbenchClient({
    request: async (...args) => {
      calls.push(args)
      return { session_id: 'imported' }
    },
    download: async () => {},
    confirm: () => true,
    parseArchive: text => JSON.parse(text),
  })

  await assert.rejects(
    () => client.importArchive({ name: 'legacy.mbrec', text: async () => '{}' }),
    /只支持 \.mbsession/,
  )
  assert.equal(calls.length, 0)
  assert.deepEqual(
    await client.importArchive({ name: 'evidence.mbsession', text: async () => '{"format":"v1"}' }),
    { session_id: 'imported' },
  )
  assert.deepEqual(calls, [[
    '/api/v1/sessions/import',
    { method: 'POST', body: { format: 'v1' } },
  ]])
})

test('browses, re-exports and deletes imported evidence through legal Web operations', async () => {
  const requests = []
  const downloads = []
  const imported = sessions[2]
  const client = createSessionWorkbenchClient({
    request: async (...args) => {
      requests.push(args)
      return args[1]?.method === 'DELETE' ? { deleted: true } : { format: 'breakhub-session-v1' }
    },
    download: async (...args) => downloads.push(args),
    confirm: () => true,
  })
  let archive = null

  await client.loadArchive(imported.session_id, value => { archive = value })
  await client.exportArchive(imported)
  await client.deleteSession(imported)

  assert.equal(archive.format, 'breakhub-session-v1')
  assert.deepEqual(downloads, [[
    '/api/v1/sessions/evidence/export',
    'evidence.mbsession',
  ]])
  assert.deepEqual(requests, [
    ['/api/v1/sessions/evidence/archive'],
    ['/api/v1/sessions/evidence', { method: 'DELETE' }],
  ])
})

test('confirms clear details and propagates a paused-interaction conflict for feedback', async () => {
  const conflict = Object.assign(new Error('请先处理暂停调用'), { code: 'SESSION_HAS_PAUSED_INTERACTIONS' })
  const prompts = []
  const client = createSessionWorkbenchClient({
    request: async path => {
      assert.equal(path, '/api/v1/sessions/current/interactions/clear')
      throw conflict
    },
    download: async () => {},
    confirm: prompt => {
      prompts.push(prompt)
      return true
    },
  })

  await assert.rejects(
    () => client.clearCurrent(sessions[0], { interactionCount: 3, pauseCount: 2 }),
    error => error === conflict,
  )
  assert.match(prompts[0], /3 条调用与 2 条 Pause/)
  assert.match(prompts[0], /Breakpoint 会全部保留/)
  assert.match(prompts[0], /暂停调用/)
})
