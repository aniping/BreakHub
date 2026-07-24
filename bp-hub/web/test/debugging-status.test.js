import test from 'node:test'
import assert from 'node:assert/strict'

import { debuggingStatusLabel, reportingErrorLabel } from '../src/debugging-status.js'

test('shows idle debugging as not started', () => {
  assert.equal(debuggingStatusLabel({ status: 'idle', reporting: { status: 'idle' } }), '未启动')
})

test('shows healthy reporting while debugging', () => {
  assert.equal(
    debuggingStatusLabel({ status: 'debugging', reporting: { status: 'healthy' } }),
    '运行中 · 业务上报正常',
  )
})

test('shows a renewal failure without exposing lease details', () => {
  assert.equal(
    debuggingStatusLabel({
      status: 'debugging',
      reporting: { status: 'degraded', lease_id: 'must-not-render' },
    }),
    '运行中 · 业务服务续签异常',
  )
})

test('shows an expired reporting lease as idle', () => {
  assert.equal(
    debuggingStatusLabel({ status: 'idle', reporting: { status: 'expired' } }),
    '空闲 · 业务上报租约已失效',
  )
})

test('distinguishes a degraded business channel from a failed renewal', () => {
  assert.equal(
    debuggingStatusLabel({
      status: 'debugging',
      reporting: { status: 'healthy', channel_status: 'degraded' },
    }),
    '运行中 · 业务上报通道异常',
  )
})

test('shows only the sanitized recent error supplied by the reporting snapshot', () => {
  assert.equal(
    reportingErrorLabel({
      reporting: {
        status: 'degraded',
        last_error: 'REPORTING_LEASE_UNAVAILABLE',
        lease_id: 'must-not-render',
      },
    }),
    '最近错误：REPORTING_LEASE_UNAVAILABLE',
  )
  assert.equal(reportingErrorLabel({ reporting: { status: 'healthy' } }), '')
})
