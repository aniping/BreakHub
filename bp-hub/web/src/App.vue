<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import UiIcon from './UiIcon.vue'
import { ApiError, download, login, logout, readSession, request } from './api.js'
import { buildConditionPayload, conditionEditor, conditionsForPausePoint, valueEditor } from './breakpoint-condition.js'
import {
  conditionEvidenceFromReference,
  conditionFromReferenceField,
  existingConditionIndex,
  observedCommandSuggestions,
  observedObjectSuggestions,
  referenceChangesTarget,
  referenceEvidence,
  referenceInteractions,
  targetInterface,
} from './breakpoint-evidence.js'
import { breakpointInterfaceKey, groupBreakpointsByInterface, toggleCollapsedKey } from './breakpoint-hierarchy.js'
import { hitEvidenceRows } from './breakpoint-hit-evidence.js'
import { debuggingStatusLabel, reportingErrorLabel } from './debugging-status.js'
import {
  buildInjectionChanges,
  buildInjectionEditors,
  changedPointers,
  injectionEditorRevision,
} from './interaction-injection.js'
import {
  buildContinueSelectedRequest,
  drawerCandidateInteractions,
  filterAndSortInteractions,
  interactionStatus,
  pauseSelectionKey,
  reconcileBrowsedInteractionId,
  reconcileSelectedPauseKeys,
  selectablePauseTarget,
} from './interaction-workbench.js'
import { parseJson, stringifyJson } from './json.js'
import {
  archiveSummary,
  createSessionWorkbenchClient,
  filterSessions,
  legalSessionActions,
} from './session-workbench.js'

const session = ref(null)
const overview = ref(null)
const settings = ref(null)
const workspaces = ref([])
const browsedSessionArchive = ref(null)
const interfaces = ref([])
const interactions = ref([])
const breakpoints = ref([])
const browsedSessionId = ref(null)
const browsedInterfaceKey = ref(null)
const browsedInteractionId = ref(null)
const interactionDialog = ref(null)
const interactionDrawerClose = ref(null)
const interactionFilterQuery = ref(null)
const advancedFiltersOpen = ref(false)
const interactionDrawerSource = ref(null)
const currentInterfacesOnly = ref(false)
const activeView = ref('overview')
const busy = ref(false)
const error = ref('')
const loginForm = reactive({ username: 'admin', password: '' })
const newSessionName = ref('')
const sessionImportInput = ref(null)
const sessionFilters = reactive({ query: '', source: '' })
const renameDrafts = reactive({})
const editingBreakpointId = ref(null)
const breakpointForm = reactive({ name: '', object: '', command: '', pause_point: 'before', conditions: [] })
const breakpointEditor = ref(null)
const breakpointReferenceInteractionId = ref(null)
const breakpointReferenceSource = ref('params')
const highlightedBreakpointConditionPath = ref('')
const breakpointConditionWarning = ref(null)
const injectionEditors = ref([])
const injectionDraftKey = ref('')
const injectionFeedback = ref(null)
const injectionDraftError = ref('')
const interactionFilters = reactive({ query: '', object: '', status: '', pausePoint: '', from: '', to: '' })
const selectedPauseKeys = ref([])
const interactionDetailTab = ref('overview')
const sessionDetailTab = ref('overview')
const collapsedListGroups = ref(new Set())
const collapsedBreakpointInterfaces = ref(new Set())
const compactDetailViewport = ref(false)
const sessionWorkbenchClient = createSessionWorkbenchClient({
  request,
  download,
  confirm: message => window.confirm(message),
  parseArchive: parseJson,
})

const equipment = computed(() => overview.value?.equipment)
const currentSession = computed(() => overview.value?.current_session)
const currentWorkspace = computed(() => workspaces.value.find(item => item.current))
const control = computed(() => overview.value?.control)
const debugging = computed(() => overview.value?.debugging?.status === 'debugging')
const debuggingLabel = computed(() => debuggingStatusLabel(overview.value?.debugging))
const reportingError = computed(() => reportingErrorLabel(overview.value?.debugging))
const controlledByOther = computed(() => control.value?.held && !control.value?.owned_by_requester)
const browsedWorkspace = computed(() => workspaces.value.find(item => item.session_id === browsedSessionId.value))
const visibleWorkspaces = computed(() => filterSessions(workspaces.value, sessionFilters))
const browsedSessionActions = computed(() => (
  browsedWorkspace.value ? legalSessionActions(browsedWorkspace.value) : []
))
const browsedArchiveSummary = computed(() => archiveSummary(browsedSessionArchive.value))
const visibleInterfaces = computed(() => currentInterfacesOnly.value
  ? interfaces.value.filter(item => item.current_related)
  : interfaces.value)
const groupedInterfaces = computed(() => groupItemsByObject(visibleInterfaces.value))
const browsedInterface = computed(() => interfaces.value.find(item => interfaceKey(item) === browsedInterfaceKey.value))
const browsedInteraction = computed(() => interactions.value.find(item => item.interaction_id === browsedInteractionId.value))
const filteredInteractions = computed(() => filterAndSortInteractions(interactions.value, interactionFilters))
const groupedInteractions = computed(() => groupItemsByObject(filteredInteractions.value).map(group => ({
  ...group,
  summary: summarizeInteractionGroup(group.items),
})))
const recentInteractions = computed(() => filterAndSortInteractions(interactions.value, {}).slice(0, 8))
const latestInteractionByInterface = computed(() => {
  const result = new Map()
  for (const item of interactions.value) {
    const key = interfaceKey(item)
    const current = result.get(key)
    if (!current || new Date(item.before_at || 0).getTime() > new Date(current.before_at || 0).getTime()) {
      result.set(key, item)
    }
  }
  return result
})
const continueSelectedRequest = computed(() => buildContinueSelectedRequest(
  selectedPauseKeys.value,
  interactions.value,
))
const selectedPauseCount = computed(() => continueSelectedRequest.value.targets.length)
const canContinueSelected = computed(() => (
  !busy.value && !controlledByOther.value && selectedPauseCount.value > 0
))
const interactionObjects = computed(() => [...new Set(interactions.value.map(item => item.object))].sort())
const injectionEditorSourceRevision = computed(() => injectionEditorRevision(browsedInteraction.value, control.value))
const injectionDraft = computed(() => buildInjectionChanges(injectionEditors.value))
const injectionChangedPointers = computed(() => {
  const pause = browsedInteraction.value?.current_pause
  return pause ? changedPointers(pause.original_content, pause.effective_content) : []
})
const canInject = computed(() => (
  !busy.value
  && !controlledByOther.value
  && Boolean(browsedInteraction.value?.current_pause)
  && !injectionDraft.value.error
))
const pausedInteractions = computed(() => interactions.value.filter(item => item.current_pause))
const groupedBreakpoints = computed(() => groupBreakpointsByInterface(breakpoints.value))
const groupedWorkspaces = computed(() => [
  { key: 'current', label: 'Current', items: visibleWorkspaces.value.filter(item => item.current) },
  { key: 'local', label: '本机可写', items: visibleWorkspaces.value.filter(item => !item.current && item.source === 'local') },
  { key: 'imported', label: '导入只读', items: visibleWorkspaces.value.filter(item => item.source !== 'local') },
].filter(group => group.items.length))
const breakpointObjectSuggestions = computed(() => observedObjectSuggestions(interfaces.value))
const breakpointCommandSuggestions = computed(() => observedCommandSuggestions(
  interfaces.value,
  breakpointForm.object,
))
const breakpointInterface = computed(() => targetInterface(
  interfaces.value,
  breakpointForm.object,
  breakpointForm.command,
))
const breakpointReferenceCandidates = computed(() => referenceInteractions(
  interactions.value,
  breakpointForm.object,
  breakpointForm.command,
))
const breakpointReferenceInteraction = computed(() => interactions.value.find(
  item => item.interaction_id === breakpointReferenceInteractionId.value,
) || null)
const breakpointReferenceStates = computed(() => ({
  params: referenceEvidence(breakpointReferenceInteraction.value, 'params'),
  result: referenceEvidence(breakpointReferenceInteraction.value, 'result'),
}))
const breakpointReferenceState = computed(() => (
  breakpointReferenceStates.value[breakpointReferenceSource.value]
))
const breakpointReferenceFields = computed(() => breakpointReferenceState.value.fields)
const breakpointParamsReferenceFields = computed(() => breakpointReferenceStates.value.params.fields)
const breakpointResultReferenceFields = computed(() => breakpointReferenceStates.value.result.fields)
const breakpointReferenceFieldMaps = computed(() => ({
  params: new Map(breakpointParamsReferenceFields.value.map(field => [field.path, field])),
  result: new Map(breakpointResultReferenceFields.value.map(field => [field.path, field])),
}))
const breakpointConditionEvidenceStates = computed(() => breakpointForm.conditions.map(condition => (
  conditionEvidenceFromReference(condition, breakpointReferenceStates.value[condition.source])
)))
const breakpointTargetObserved = computed(() => (
  !breakpointForm.object.trim()
  || !breakpointForm.command.trim()
  || Boolean(breakpointInterface.value)
))
const breakpointConditionDraft = computed(() => buildConditionPayload(breakpointForm.conditions))
const canSaveBreakpoint = computed(() => (
  !busy.value
  && !controlledByOther.value
  && Boolean(breakpointForm.object.trim())
  && Boolean(breakpointForm.command.trim())
  && !breakpointConditionDraft.value.error
))
const controlLabel = computed(() => {
  if (!control.value?.held) return '无人控制'
  if (control.value.owned_by_requester) return '当前 Web'
  return control.value.controller === 'mcp' ? 'MCP' : '其他 Web'
})
let heartbeatTimer
let statusRefreshTimer
let interactionDrawerTrigger
let compactDetailMediaQuery

watch(injectionEditorSourceRevision, () => prepareInjectionEditor(browsedInteraction.value))
watch(filteredInteractions, items => {
  const candidates = drawerCandidateInteractions(
    interactionDrawerSource.value,
    interactions.value,
    items,
  )
  const reconciledId = reconcileBrowsedInteractionId(browsedInteractionId.value, candidates)
  if (browsedInteractionId.value && !reconciledId) closeInteraction()
  else browsedInteractionId.value = reconciledId
})
watch(visibleWorkspaces, items => {
  if (!items.some(item => item.session_id === browsedSessionId.value)) {
    browsedSessionId.value = items[0]?.session_id || null
  }
})
watch(browsedSessionId, sessionId => loadBrowsedSessionArchive(sessionId))
watch(activeView, view => {
  if (view === 'sessions') loadBrowsedSessionArchive(browsedSessionId.value)
  if (view !== 'interactions') closeInteraction()
})
watch(() => [breakpointForm.object, breakpointForm.command], () => {
  if (breakpointReferenceInteraction.value
    && referenceChangesTarget(breakpointForm, breakpointReferenceInteraction.value)) {
    breakpointReferenceInteractionId.value = null
  }
})
watch(() => breakpointForm.pause_point, pausePoint => {
  breakpointReferenceSource.value = pausePoint === 'after' ? 'result' : 'params'
  const normalized = conditionsForPausePoint(breakpointForm.conditions, pausePoint)
  if (!normalized.discarded_conditions.length) {
    breakpointConditionWarning.value = null
    return
  }
  breakpointForm.conditions.splice(0, breakpointForm.conditions.length, ...normalized.conditions)
  breakpointConditionWarning.value = {
    count: normalized.discarded_conditions.length,
    becameUnconditional: normalized.became_unconditional,
  }
})

async function loadProduct() {
  const [overviewValue, settingsValue, workspaceValue, interfaceValue, interactionValue, breakpointValue] = await Promise.all([
    request('/api/v1/overview'),
    request('/api/v1/settings'),
    request('/api/v1/sessions'),
    request('/api/v1/interfaces'),
    request('/api/v1/interactions'),
    request('/api/v1/breakpoints'),
  ])
  overview.value = overviewValue
  settings.value = settingsValue
  applyWorkspaces(workspaceValue)
  applyObservations(interfaceValue, interactionValue, breakpointValue)
}

function applyObservations(interfaceResponse, interactionResponse, breakpointResponse = null) {
  interfaces.value = interfaceResponse.items
  interactions.value = interactionResponse.items
  selectedPauseKeys.value = reconcileSelectedPauseKeys(selectedPauseKeys.value, interactions.value)
  if (breakpointResponse) breakpoints.value = breakpointResponse.items
  ensureBrowsedInterface()
}

function ensureBrowsedInterface() {
  if (!visibleInterfaces.value.some(item => interfaceKey(item) === browsedInterfaceKey.value)) {
    browsedInterfaceKey.value = visibleInterfaces.value[0] ? interfaceKey(visibleInterfaces.value[0]) : null
  }
}

function interfaceKey(item) {
  return `${item.object}\u0000${item.command}`
}

function groupItemsByObject(items) {
  const groups = new Map()
  for (const item of items) {
    if (!groups.has(item.object)) groups.set(item.object, [])
    groups.get(item.object).push(item)
  }
  return [...groups.entries()].map(([object, values]) => ({ object, items: values }))
}

function listGroupKey(scope, key) {
  return `${scope}\u0000${key}`
}

function isListGroupCollapsed(scope, key) {
  return collapsedListGroups.value.has(listGroupKey(scope, key))
}

function toggleListGroup(scope, key) {
  collapsedListGroups.value = toggleCollapsedKey(collapsedListGroups.value, listGroupKey(scope, key))
}

function isBreakpointInterfaceCollapsed(object, command) {
  return collapsedBreakpointInterfaces.value.has(breakpointInterfaceKey(object, command))
}

function toggleBreakpointInterface(object, command) {
  const key = breakpointInterfaceKey(object, command)
  collapsedBreakpointInterfaces.value = toggleCollapsedKey(collapsedBreakpointInterfaces.value, key)
}

async function refreshOverview() {
  overview.value = await request('/api/v1/overview')
}

function applyWorkspaces(response) {
  workspaces.value = response.items
  for (const item of response.items) {
    if (renameDrafts[item.session_id] === undefined) renameDrafts[item.session_id] = item.name
  }
  if (!response.items.some(item => item.session_id === browsedSessionId.value)) {
    browsedSessionId.value = response.current_session_id
  }
}

async function restoreSession() {
  try {
    session.value = await readSession()
    await loadProduct()
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) session.value = null
    else error.value = reason.message || String(reason)
  }
}

async function submitLogin() {
  busy.value = true
  error.value = ''
  try {
    session.value = await login(loginForm.username, loginForm.password)
    loginForm.password = ''
    await loadProduct()
  } catch (reason) {
    error.value = reason.message || String(reason)
  } finally {
    busy.value = false
  }
}

async function doLogout() {
  busy.value = true
  try {
    await logout()
    session.value = null
    overview.value = null
    settings.value = null
    workspaces.value = []
    browsedSessionArchive.value = null
    interfaces.value = []
    interactions.value = []
    breakpoints.value = []
    browsedSessionId.value = null
    browsedInterfaceKey.value = null
    browsedInteractionId.value = null
    selectedPauseKeys.value = []
  } catch (reason) {
    error.value = reason.message || String(reason)
  } finally {
    busy.value = false
  }
}

async function runControlAction(path) {
  busy.value = true
  error.value = ''
  try {
    await request(path, { method: 'POST' })
    await loadProduct()
  } catch (reason) {
    error.value = reason.message || String(reason)
    await refreshOverview().catch(() => {})
  } finally {
    busy.value = false
  }
}

function startDebugging() {
  return runControlAction('/api/v1/debugging/start')
}

function stopDebugging() {
  return runControlAction('/api/v1/debugging/stop')
}

function releaseControl() {
  return runControlAction('/api/v1/control/release')
}

async function heartbeat() {
  if (!session.value || !control.value?.owned_by_requester) return
  try {
    await request('/api/v1/control/heartbeat', { method: 'POST' })
    await refreshOverview()
  } catch (reason) {
    error.value = reason.message || String(reason)
    await refreshOverview().catch(() => {})
  }
}

async function refreshStatus() {
  if (!session.value || busy.value) return
  try {
    const [overviewValue, workspaceValue, interfaceValue, interactionValue, breakpointValue] = await Promise.all([
      request('/api/v1/overview'),
      request('/api/v1/sessions'),
      request('/api/v1/interfaces'),
      request('/api/v1/interactions'),
      request('/api/v1/breakpoints'),
    ])
    overview.value = overviewValue
    applyWorkspaces(workspaceValue)
    applyObservations(interfaceValue, interactionValue, breakpointValue)
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 401) {
      session.value = null
      overview.value = null
      settings.value = null
      workspaces.value = []
      interfaces.value = []
      interactions.value = []
      breakpoints.value = []
    } else {
      error.value = reason.message || String(reason)
    }
  }
}

async function runWorkspaceAction(pathOrOperation, options = {}) {
  busy.value = true
  error.value = ''
  try {
    const result = typeof pathOrOperation === 'function'
      ? await pathOrOperation()
      : await request(pathOrOperation, options)
    if (result === undefined) return null
    await loadProduct()
    await loadBrowsedSessionArchive(browsedSessionId.value)
    return result
  } catch (reason) {
    error.value = reason.message || String(reason)
    await refreshStatus().catch(() => {})
    return null
  } finally {
    busy.value = false
  }
}

async function loadBrowsedSessionArchive(sessionId) {
  return sessionWorkbenchClient.loadArchive(
    session.value ? sessionId : null,
    archive => { browsedSessionArchive.value = archive },
    reason => { error.value = reason.message || String(reason) },
  )
}

async function createWorkspace() {
  const name = newSessionName.value.trim()
  if (!name) return
  const created = await runWorkspaceAction('/api/v1/sessions', { method: 'POST', body: { name } })
  if (created) {
    newSessionName.value = ''
    browsedSessionId.value = created.session_id
  }
}

async function renameWorkspace(item) {
  const name = (renameDrafts[item.session_id] || '').trim()
  if (!name || name === item.name) return
  await runWorkspaceAction(`/api/v1/sessions/${item.session_id}`, { method: 'PATCH', body: { name } })
}

async function selectCurrentWorkspace(item) {
  await runWorkspaceAction(`/api/v1/sessions/${item.session_id}/current`, { method: 'POST' })
}

async function deleteWorkspace(item) {
  await runWorkspaceAction(() => sessionWorkbenchClient.deleteSession(item))
}

async function clearCurrentInteractions(item) {
  const pauseCount = interactions.value.reduce((count, interaction) => count + (interaction.pauses?.length || 0), 0)
  await runWorkspaceAction(() => sessionWorkbenchClient.clearCurrent(item, {
    interactionCount: interactions.value.length,
    pauseCount,
  }))
}

async function exportWorkspace(item) {
  busy.value = true
  error.value = ''
  try {
    await sessionWorkbenchClient.exportArchive(item)
  } catch (reason) {
    error.value = reason.message || String(reason)
  } finally {
    busy.value = false
  }
}

function chooseSessionArchive() {
  sessionImportInput.value?.click()
}

async function importSessionArchive(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    const imported = await sessionWorkbenchClient.importArchive(file)
    await loadProduct()
    browsedSessionId.value = imported.session_id
  } catch (reason) {
    error.value = reason.message || String(reason)
    await refreshStatus().catch(() => {})
  } finally {
    busy.value = false
  }
}

function releaseOnPageExit() {
  if (control.value?.owned_by_requester) {
    request('/api/v1/control/release', { method: 'POST', keepalive: true }).catch(() => {})
  }
}

function formatTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function formatJson(value) {
  return stringifyJson(value, 2)
}

function formatBytes(value) {
  if (!Number.isFinite(value)) return '—'
  if (value < 1024) return `${value} B`
  return `${(value / 1024).toFixed(1)} KiB`
}

function interactionPhaseLabel(item) {
  if (item.current_pause?.pause_point) return item.current_pause.pause_point
  return item.phase === 'after' || item.lifecycle === 'completed' ? 'after' : 'business'
}

function interactionDuration(item) {
  if (!item.before_at) return '—'
  const end = item.after_at || item.current_pause?.paused_at
  if (!end) return '运行中'
  const milliseconds = Math.max(0, new Date(end).getTime() - new Date(item.before_at).getTime())
  return formatDurationMilliseconds(milliseconds)
}

function formatDurationMilliseconds(milliseconds) {
  if (!Number.isFinite(milliseconds)) return '—'
  if (milliseconds < 1000) return `${milliseconds} ms`
  return `${(milliseconds / 1000).toFixed(milliseconds < 10000 ? 2 : 1)} s`
}

function interactionHitCount(item) {
  return (item.pauses || []).reduce((count, pause) => count + (pause.breakpoint_snapshots?.length || 0), 0)
}

function interactionInjectionCount(item) {
  return (item.pauses || []).reduce((count, pause) => count + (pause.injection_audit?.length || 0), 0)
}

function latestInterfaceInteraction(item) {
  return latestInteractionByInterface.value.get(interfaceKey(item))
}

function interfaceRecentStatus(item) {
  const latest = latestInterfaceInteraction(item)
  return latest ? `${interactionStatusLabel(latest)} · ${interactionPhaseLabel(latest)}` : '暂无调用'
}

function interfaceRecentDuration(item) {
  const latest = latestInterfaceInteraction(item)
  return latest ? interactionDuration(latest) : '—'
}

function summarizeInteractionGroup(items) {
  const durations = items
    .filter(item => item.before_at && item.after_at)
    .map(item => Math.max(0, new Date(item.after_at).getTime() - new Date(item.before_at).getTime()))
    .filter(Number.isFinite)
  return {
    completed: items.filter(item => interactionStatus(item) === 'completed').length,
    running: items.filter(item => interactionStatus(item) === 'in_progress').length,
    paused: items.filter(item => interactionStatus(item) === 'paused').length,
    hits: items.reduce((sum, item) => sum + interactionHitCount(item), 0),
    injections: items.reduce((sum, item) => sum + interactionInjectionCount(item), 0),
    averageDuration: durations.length
      ? formatDurationMilliseconds(Math.round(durations.reduce((sum, value) => sum + value, 0) / durations.length))
      : '—',
  }
}

function latestPause(item) {
  return item.current_pause || item.pauses?.at(-1) || null
}

function interactionRuleEvidence(item) {
  const pause = latestPause(item)
  const names = [...new Set((pause?.breakpoint_snapshots || []).map(snapshot => snapshot.name).filter(Boolean))]
  if (!names.length) return '未命中规则'
  return [names.join('、'), pause?.pause_point].filter(Boolean).join(' · ')
}

function interactionResultEvidence(item) {
  const pause = latestPause(item)
  if (item.current_pause?.has_pending_injection) return '待提交注入'
  if (item.current_pause) return `等待继续 · ${item.current_pause.pause_point}`
  const result = item.result
  const resultCode = result && typeof result === 'object' ? (result.code ?? result.status ?? result.result) : null
  const resultMessage = result && typeof result === 'object' ? (result.message ?? result.error) : null
  const readableMessage = ['string', 'number'].includes(typeof resultMessage) ? resultMessage : null
  if (resultCode !== null || readableMessage) return [resultCode, readableMessage].filter(value => value !== null && value !== '').join(' · ')
  if (interactionStatus(item) === 'in_progress') return '等待 after 回报'
  const resolutionLabels = {
    continued_by_controller: '控制方已继续',
    reporting_lease_expired: '报告租约到期释放',
    safe_released: '已安全释放',
  }
  return resolutionLabels[pause?.resolution] || pause?.resolution || '已完成'
}

function interactionPayloadEvidence(item) {
  const metadata = item.payload_metadata || {}
  const state = (name, pendingLabel) => {
    if (!metadata[name]) return pendingLabel
    return metadata[name].truncated ? '截断' : '完整'
  }
  return [
    `B 入参${state('params', '未捕获')}`,
    `A 返回${state('result', interactionStatus(item) === 'in_progress' ? '待回报' : '未捕获')}`,
  ].join(' · ')
}

function interactionEvidenceSummary(item) {
  const currentPause = item.current_pause
  const pauses = item.pauses || []
  const lastPause = pauses.at(-1)
  const snapshot = currentPause?.breakpoint_snapshots?.[0] || lastPause?.breakpoint_snapshots?.[0]
  if (currentPause) {
    return [
      currentPause.has_pending_injection ? '待提交注入' : null,
      `${currentPause.pause_point} 暂停`,
      snapshot?.name,
    ].filter(Boolean).join(' · ')
  }

  const result = item.result
  const resultCode = result && typeof result === 'object'
    ? (result.code ?? result.status ?? result.result)
    : null
  const resultMessage = result && typeof result === 'object'
    ? (result.message ?? result.error)
    : null
  const readableResultMessage = ['string', 'number'].includes(typeof resultMessage)
    ? resultMessage
    : null
  const truncatedPayload = Object.values(item.payload_metadata || {}).some(metadata => metadata?.truncated)
  if (resultCode || readableResultMessage) return [resultCode, readableResultMessage].filter(Boolean).join(' · ')
  if (interactionStatus(item) === 'in_progress') return [snapshot?.name, '等待 after 回报'].filter(Boolean).join(' · ')
  return [
    snapshot?.name,
    lastPause?.resolution,
    truncatedPayload ? 'Payload 已截断' : 'Payload 完整',
  ].filter(Boolean).join(' · ')
}

function workspaceEvidenceSummary(item) {
  if (item.current) return `${breakpoints.value.length} 断点 · ${interactions.value.length} 调用 · ${pausedInteractions.value.length} 暂停`
  if (item.session_id === browsedSessionId.value && browsedSessionArchive.value) {
    return `${browsedArchiveSummary.value.breakpointCount} 断点 · ${browsedArchiveSummary.value.interactionCount} 调用 · ${browsedArchiveSummary.value.pauseCount} Pause`
  }
  return item.read_only ? '只读证据 · 按需加载' : '本机证据 · 按需加载'
}

function interactionStatusLabel(item) {
  const status = interactionStatus(item)
  if (status === 'paused') return `${item.current_pause?.pause_point || ''} 暂停`.trim()
  if (status === 'completed') return '已完成'
  return '业务执行中'
}

function setActiveView(view) {
  activeView.value = view
  nextTick(() => {
    document.querySelector('.content')?.scrollTo({ top: 0 })
    window.scrollTo({ top: 0 })
  })
}

function clearInteractionFilters() {
  Object.assign(interactionFilters, { query: '', object: '', status: '', pausePoint: '', from: '', to: '' })
}

function syncCompactDetailViewport(event) {
  const compact = event.matches
  if (!compact && compactDetailViewport.value && interactionDialog.value?.open) {
    interactionDialog.value.close()
  }
  compactDetailViewport.value = compact
  if (compact && browsedInteraction.value) {
    nextTick(() => {
      if (!interactionDialog.value?.open) interactionDialog.value?.showModal()
      interactionDrawerClose.value?.focus()
    })
  }
}

function timelineLabel(event) {
  return ({
    before_reported: 'Before 已上报',
    pause_started: `${event.phase} 开始暂停`,
    pause_resolved: `${event.phase} 暂停已释放`,
    after_reported: 'After 已上报',
  })[event.event] || event.event
}

function openSample(interactionId) {
  openRevealedInteraction(interactionId)
}

function openRevealedInteraction(interactionId) {
  openInteraction(interactionId, 'external')
}

function openInteraction(interactionId, source = 'list') {
  advancedFiltersOpen.value = false
  interactionDrawerTrigger = document.activeElement instanceof HTMLElement
    ? document.activeElement
    : null
  interactionDrawerSource.value = source
  browsedInteractionId.value = interactionId
  setActiveView('interactions')
  nextTick(() => {
    if (compactDetailViewport.value) {
      if (!interactionDialog.value?.open) interactionDialog.value?.showModal()
      interactionDrawerClose.value?.focus()
    }
  })
}

function closeInteraction() {
  const trigger = interactionDrawerTrigger
  interactionDrawerTrigger = null
  interactionDrawerSource.value = null
  if (compactDetailViewport.value && interactionDialog.value?.open) interactionDialog.value.close()
  browsedInteractionId.value = null
  nextTick(() => {
    if (trigger?.isConnected) trigger.focus()
    else interactionFilterQuery.value?.focus()
  })
}

function prepareInjectionEditor(item, force = false) {
  const pause = item?.current_pause
  const key = injectionEditorRevision(item, control.value)
  if (!force && key === injectionDraftKey.value) return
  injectionDraftKey.value = key
  injectionEditors.value = pause
    ? buildInjectionEditors(pause.original_content, pause.effective_content)
    : []
  injectionFeedback.value = null
  injectionDraftError.value = ''
}

function changeInjectionSelection(editor) {
  if (editor.selected && editor.type === 'object') editor.setNull = true
  injectionDraftError.value = ''
}

async function injectInteraction(item) {
  const draft = buildInjectionChanges(injectionEditors.value)
  if (draft.error) {
    injectionDraftError.value = draft.error
    return
  }
  const pausePoint = item.current_pause?.pause_point
  if (!pausePoint) return
  const result = await runWorkspaceAction(`/api/v1/interactions/${item.interaction_id}/inject`, {
    method: 'POST',
    body: { pause_point: pausePoint, changes: draft.changes },
  })
  if (result) {
    browsedInteractionId.value = item.interaction_id
    prepareInjectionEditor(
      interactions.value.find(value => value.interaction_id === item.interaction_id),
      true,
    )
    injectionFeedback.value = result
  }
}

function newBreakpoint(prefill = null, referenceInteractionId = null) {
  editingBreakpointId.value = null
  breakpointForm.name = ''
  breakpointForm.object = prefill?.object || ''
  breakpointForm.command = prefill?.command || ''
  breakpointForm.pause_point = 'before'
  breakpointForm.conditions.splice(0)
  highlightedBreakpointConditionPath.value = ''
  breakpointConditionWarning.value = null
  breakpointReferenceInteractionId.value = referenceInteractionId || null
  breakpointReferenceSource.value = 'params'
  setActiveView('breakpoints')
}

function editBreakpoint(item, referenceInteractionId = null) {
  if (!item) return
  editingBreakpointId.value = item.breakpoint_id
  breakpointForm.name = item.name
  breakpointForm.object = item.object
  breakpointForm.command = item.command
  breakpointForm.pause_point = item.pause_point
  breakpointForm.conditions.splice(0, breakpointForm.conditions.length, ...item.conditions.map(conditionEditor))
  highlightedBreakpointConditionPath.value = ''
  breakpointConditionWarning.value = null
  breakpointReferenceInteractionId.value = referenceInteractionId
  breakpointReferenceSource.value = item.pause_point === 'after'
    ? item.conditions[0]?.source || 'result'
    : 'params'
}

function addCondition() {
  const defaultSource = breakpointForm.pause_point === 'after' ? 'result' : 'params'
  breakpointForm.conditions.push(conditionEditor(null, defaultSource))
}

function confirmBreakpointTargetChange(object, command) {
  if (referenceChangesTarget(breakpointForm, { object, command }) && breakpointForm.conditions.length) {
    return window.confirm(
      `切换到 ${object}.${command}？现有 ${breakpointForm.conditions.length} 条条件会保留，请确认字段仍适用于新目标。`,
    )
  }
  return true
}

function chooseBreakpointObject(object) {
  if (!confirmBreakpointTargetChange(object, breakpointForm.command)) return
  breakpointForm.object = object
}

function chooseBreakpointCommand(command) {
  if (!confirmBreakpointTargetChange(breakpointForm.object, command)) return
  breakpointForm.command = command
}

function breakpointObjectEvidence(item) {
  return `${item.interface_count} 接口 · ${item.interaction_count} 调用 · ${item.schema_changed_count ? `${item.schema_changed_count} 结构变化` : '结构稳定'} · 最近 ${formatTime(item.last_seen_at)}`
}

function breakpointCommandEvidence(item) {
  return `${item.interaction_count} 调用 · ${item.field_count} 字段 · ${item.schema_changed ? '结构变化' : '结构稳定'} · ${formatTime(item.last_seen_at)}`
}

function shortInteractionId(item) {
  return item?.interaction_id?.length > 12 ? `${item.interaction_id.slice(0, 8)}…` : item?.interaction_id || '—'
}

function breakpointReferenceStructure(item) {
  const observed = targetInterface(interfaces.value, item.object, item.command)
  return `${observed?.field_schema?.length || 0} 字段 · ${observed?.schema_changed ? '结构变化' : '结构稳定'}`
}

function breakpointSampleValue(field) {
  const value = stringifyJson(field.sample)
  return value.length > 100 ? `${value.slice(0, 97)}…` : value
}

function focusBreakpointCondition(index) {
  nextTick(() => {
    const row = breakpointEditor.value?.querySelectorAll('.condition-row')[index]
    row?.querySelector('input')?.focus()
    row?.scrollIntoView({ block: 'nearest' })
  })
}

function setBreakpointConditionFromField(field) {
  const existingIndex = existingConditionIndex(
    breakpointForm.conditions,
    breakpointReferenceSource.value,
    field.path,
  )
  if (existingIndex >= 0) {
    highlightedBreakpointConditionPath.value = field.path
    focusBreakpointCondition(existingIndex)
    return
  }
  const evidenceCondition = conditionFromReferenceField(field, breakpointReferenceSource.value)
  if (!evidenceCondition) return
  breakpointForm.conditions.push(conditionEditor(evidenceCondition))
  highlightedBreakpointConditionPath.value = field.path
  focusBreakpointCondition(breakpointForm.conditions.length - 1)
}

function selectBreakpointReference(item) {
  if (!confirmBreakpointTargetChange(item.object, item.command)) return
  breakpointForm.object = item.object
  breakpointForm.command = item.command
  breakpointReferenceInteractionId.value = item.interaction_id
}

function removeCondition(index) {
  breakpointForm.conditions.splice(index, 1)
}

function addConditionValue(condition) {
  condition.values.push(valueEditor())
  applySuggestedType(condition, condition.values.at(-1))
}

function removeConditionValue(condition, index) {
  if (condition.values.length > 1) condition.values.splice(index, 1)
}

function changeConditionOperator(condition) {
  if (!condition.values.length) condition.values.push(valueEditor())
  if (condition.operator === 'eq' && condition.values.length > 1) condition.values.splice(1)
  applySuggestedType(condition)
}

function suggestedField(condition) {
  return breakpointReferenceFieldMaps.value[condition.source]?.get(condition.field_path.trim())
}

function applySuggestedType(condition, target = null) {
  const field = suggestedField(condition)
  if (!field) return
  const sourceType = condition.operator === 'contains_any' ? field.item_types?.[0] : field.type
  const editorType = sourceType === 'integer' || sourceType === 'number' ? 'number' : sourceType
  if (!['string', 'number', 'boolean', 'null'].includes(editorType)) return
  const editors = target ? [target] : condition.values
  for (const editor of editors) {
    editor.type = editorType
    if (editorType === 'boolean' && !['true', 'false'].includes(editor.text)) editor.text = 'true'
    if (editorType === 'null') editor.text = ''
  }
}

function changeEditorType(editor) {
  if (editor.type === 'boolean' && !['true', 'false'].includes(editor.text)) editor.text = 'true'
  if (editor.type === 'null') editor.text = ''
}

async function saveBreakpoint() {
  if (!canSaveBreakpoint.value) return
  const path = editingBreakpointId.value
    ? `/api/v1/breakpoints/${editingBreakpointId.value}`
    : '/api/v1/breakpoints'
  const saved = await runWorkspaceAction(path, {
    method: editingBreakpointId.value ? 'PATCH' : 'POST',
    body: {
      name: breakpointForm.name,
      object: breakpointForm.object,
      command: breakpointForm.command,
      pause_point: breakpointForm.pause_point,
      conditions: breakpointConditionDraft.value.conditions,
    },
  })
  if (saved) editBreakpoint(saved, breakpointReferenceInteractionId.value)
}

function cancelBreakpointEdit() {
  const persisted = breakpoints.value.find(item => item.breakpoint_id === editingBreakpointId.value)
  if (persisted) editBreakpoint(persisted)
  else newBreakpoint()
}

async function toggleBreakpoint(item) {
  if (!item) return
  const action = item.enabled ? 'disable' : 'enable'
  await runWorkspaceAction(`/api/v1/breakpoints/${item.breakpoint_id}/${action}`, { method: 'POST' })
  const refreshed = breakpoints.value.find(value => value.breakpoint_id === item.breakpoint_id)
  if (refreshed) editBreakpoint(refreshed)
}

async function deleteBreakpoint(item) {
  if (!item) return
  if (!window.confirm(`删除断点“${item.name}”？已保存的命中快照不会改变。`)) return
  const deleted = await runWorkspaceAction(`/api/v1/breakpoints/${item.breakpoint_id}`, { method: 'DELETE' })
  if (deleted) newBreakpoint()
}

async function continueInteraction(item) {
  const pausePoint = item.current_pause?.pause_point
  if (!pausePoint) return
  await runWorkspaceAction(`/api/v1/interactions/${item.interaction_id}/continue`, {
    method: 'POST',
    body: { pause_point: pausePoint },
  })
  const candidates = drawerCandidateInteractions(
    interactionDrawerSource.value,
    interactions.value,
    filteredInteractions.value,
  )
  const reconciledId = reconcileBrowsedInteractionId(item.interaction_id, candidates)
  if (reconciledId) {
    browsedInteractionId.value = reconciledId
    nextTick(() => {
      if (compactDetailViewport.value) interactionDrawerClose.value?.focus()
    })
  } else {
    closeInteraction()
  }
  setActiveView('interactions')
}

function isPauseSelected(item) {
  const target = selectablePauseTarget(item)
  return target ? selectedPauseKeys.value.includes(pauseSelectionKey(target)) : false
}

function setPauseSelected(item, selected) {
  const target = selectablePauseTarget(item)
  if (!target) return
  const key = pauseSelectionKey(target)
  const keys = new Set(selectedPauseKeys.value)
  if (selected) keys.add(key)
  else keys.delete(key)
  selectedPauseKeys.value = [...keys]
}

async function continueSelectedInteractions() {
  const body = buildContinueSelectedRequest(selectedPauseKeys.value, interactions.value)
  if (!body.targets.length) return
  const result = await runWorkspaceAction('/api/v1/interactions/continue-selected', {
    method: 'POST',
    body,
  })
  if (result) selectedPauseKeys.value = []
  else await refreshStatus()
}

onMounted(() => {
  compactDetailMediaQuery = window.matchMedia('(max-width: 1179px)')
  compactDetailViewport.value = compactDetailMediaQuery.matches
  compactDetailMediaQuery.addEventListener('change', syncCompactDetailViewport)
  restoreSession()
  heartbeatTimer = window.setInterval(heartbeat, 5 * 60 * 1000)
  statusRefreshTimer = window.setInterval(refreshStatus, 5 * 1000)
  window.addEventListener('pagehide', releaseOnPageExit)
})

onUnmounted(() => {
  compactDetailMediaQuery?.removeEventListener('change', syncCompactDetailViewport)
  window.clearInterval(heartbeatTimer)
  window.clearInterval(statusRefreshTimer)
  window.removeEventListener('pagehide', releaseOnPageExit)
})
</script>

<template>
  <main v-if="!session" class="login-page">
    <section class="login-card">
      <div class="product-mark">BH</div>
      <p class="eyebrow">组件化装备调试</p>
      <h1>BreakHub</h1>
      <p class="lead">一个清晰、可验证的装备调试工作台</p>
      <form @submit.prevent="submitLogin">
        <label>管理员账号<input v-model.trim="loginForm.username" autocomplete="username" /></label>
        <label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password" /></label>
        <button class="primary" :disabled="busy">{{ busy ? '正在登录…' : '登录产品' }}</button>
      </form>
      <p v-if="error" class="notice error">{{ error }}</p>
    </section>
  </main>

  <div v-else class="app-shell">
    <header class="app-titlebar">
      <div class="app-title">
        <div class="product-mark small">M</div>
        <div><strong>组件化断点调试工具</strong><span>BreakHub</span></div>
      </div>
      <div class="titlebar-account">
        <span>{{ session?.username || '管理员' }}</span>
        <button class="toolbar-button quiet" :disabled="busy" @click="doLogout"><UiIcon name="logout" />退出</button>
      </div>
    </header>

    <section class="command-toolbar" aria-label="全局操作">
      <div class="toolbar-group">
        <span class="toolbar-group-title">会话</span>
        <div class="toolbar-actions">
          <button class="toolbar-button" @click="setActiveView('sessions')"><UiIcon name="add" />会话管理</button>
          <button class="toolbar-button" :disabled="busy || controlledByOther || !currentWorkspace" @click="clearCurrentInteractions(currentWorkspace)"><UiIcon name="clear" />清空调用</button>
        </div>
      </div>
      <div class="toolbar-group">
        <span class="toolbar-group-title">调试</span>
        <div class="toolbar-actions">
          <button v-if="!debugging" class="toolbar-button primary-action" :disabled="busy || controlledByOther" @click="startDebugging"><UiIcon name="play" />开始调试</button>
          <button v-else class="toolbar-button danger-action" :disabled="busy || controlledByOther" @click="stopDebugging"><UiIcon name="stop" />停止调试</button>
          <button class="toolbar-button" :disabled="!control?.owned_by_requester || busy" @click="releaseControl"><UiIcon name="release" />释放控制</button>
        </div>
      </div>
      <div class="toolbar-group">
        <span class="toolbar-group-title">执行</span>
        <div class="toolbar-actions">
          <button class="toolbar-button success-action" :disabled="!canContinueSelected" @click="continueSelectedInteractions"><UiIcon name="play" />继续所选</button>
        </div>
      </div>
      <div class="toolbar-group">
        <span class="toolbar-group-title">视图</span>
        <div class="toolbar-actions">
          <button class="toolbar-button" :disabled="busy" @click="loadProduct"><UiIcon name="refresh" />刷新</button>
          <button class="toolbar-button" :disabled="activeView !== 'interactions'" @click="clearInteractionFilters"><UiIcon name="filter" />清空筛选</button>
        </div>
      </div>
    </section>

    <section v-if="overview" class="status-strip" aria-label="全局调试状态">
      <div><span>状态</span><strong :class="{ healthy: overview.connection?.label }">● {{ overview.connection.label }}</strong></div>
      <div><span>Current Session</span><strong>{{ currentSession?.name }}</strong></div>
      <div><span>调用</span><strong>{{ interactions.length }}</strong></div>
      <div><span>接口</span><strong>{{ interfaces.length }}</strong></div>
      <div><span>断点</span><strong>{{ breakpoints.length }}</strong></div>
      <div><span>暂停</span><strong :class="{ warning: pausedInteractions.length }">{{ pausedInteractions.length }}</strong></div>
      <div><span>调试 / 控制</span><strong>{{ debuggingLabel }} · {{ controlLabel }}</strong><small v-if="reportingError" :title="reportingError">{{ reportingError }}</small></div>
    </section>

    <section v-if="pausedInteractions.length" class="pause-action-strip" aria-label="活动暂停">
      <div class="pause-action-message">
        <strong>当前 Session 有 {{ pausedInteractions.length }} 个请求命中断点并暂停</strong>
        <span>{{ pausedInteractions[0].object }}.{{ pausedInteractions[0].command }} · {{ pausedInteractions[0].current_pause?.pause_point }}</span>
      </div>
      <div class="pause-action-buttons">
        <button @click="openRevealedInteraction(pausedInteractions[0].interaction_id)">打开暂停记录</button>
        <button class="pause-continue" :disabled="!canContinueSelected" @click="continueSelectedInteractions">继续所选（{{ selectedPauseCount }}）</button>
      </div>
    </section>

    <div class="app-work-area">
      <aside class="sidebar">
        <nav aria-label="产品导航">
          <button aria-label="概览" :class="{ active: activeView === 'overview' }" @click="setActiveView('overview')"><UiIcon name="overview" /><span>概览</span></button>
          <button aria-label="接口列表" :class="{ active: activeView === 'interfaces' }" @click="setActiveView('interfaces')"><UiIcon name="interfaces" /><span>接口列表</span><b>{{ interfaces.length }}</b></button>
          <button aria-label="断点规则" :class="{ active: activeView === 'breakpoints' }" @click="setActiveView('breakpoints')"><UiIcon name="breakpoints" /><span>断点规则</span><b>{{ breakpoints.length }}</b></button>
          <button aria-label="调用记录" :class="{ active: activeView === 'interactions' }" @click="setActiveView('interactions')"><UiIcon name="interactions" /><span>调用记录</span><b>{{ interactions.length }}</b></button>
          <button aria-label="会话列表" :class="{ active: activeView === 'sessions' }" @click="setActiveView('sessions')"><UiIcon name="sessions" /><span>会话列表</span><b>{{ workspaces.length }}</b></button>
          <button aria-label="设置" :class="{ active: activeView === 'settings' }" @click="setActiveView('settings')"><UiIcon name="settings" /><span>设置</span></button>
        </nav>
      </aside>

      <main class="content" :class="{ 'interaction-content': activeView === 'interactions', 'split-content': ['interfaces', 'breakpoints', 'sessions'].includes(activeView) }">
        <p v-if="error" class="notice error">{{ error }}</p>
        <p v-if="controlledByOther" class="notice readonly">当前由 {{ controlLabel }} 控制，Web 保持完整可读；所有写操作已禁用且不会抢占控制权。</p>

      <template v-if="activeView === 'overview' && overview">
        <header class="workspace-page-header">
          <div><h1>调试概览</h1><p>{{ equipment.display_name }} · 当前运行与证据摘要</p></div>
          <code>{{ equipment.equipment_id }}</code>
        </header>

        <section class="overview-workbench">
          <article class="panel overview-activity">
            <div class="panel-heading">
              <div><h2>最近调用</h2><p>暂停与最新事件优先显示</p></div>
              <button class="text-button" @click="setActiveView('interactions')">查看全部调用</button>
            </div>
            <div class="overview-table-head"><span>时间</span><span>Object.Command</span><span>阶段</span><span>状态</span><span class="overview-wide-evidence overview-duration">耗时</span><span class="overview-wide-evidence">命中</span><span class="overview-wide-evidence">注入</span><span class="overview-wide-evidence overview-result">结果 / 释放</span></div>
            <button v-for="item in recentInteractions" :key="item.interaction_id" class="overview-event-row" @click="openRevealedInteraction(item.interaction_id)">
              <time>{{ formatTime(item.before_at) }}</time>
              <strong>{{ item.object }}.{{ item.command }}</strong>
              <span>{{ interactionPhaseLabel(item) }}</span>
              <span :class="['status-text', interactionStatus(item)]">{{ interactionStatusLabel(item) }}</span>
              <span class="overview-wide-evidence overview-duration">{{ interactionDuration(item) }}</span>
              <span class="overview-wide-evidence overview-metric">{{ interactionHitCount(item) }}</span>
              <span class="overview-wide-evidence overview-metric">{{ interactionInjectionCount(item) }}</span>
              <span class="overview-wide-evidence overview-result" :title="interactionResultEvidence(item)">{{ interactionResultEvidence(item) }}</span>
            </button>
            <p v-if="!recentInteractions.length" class="empty-state">Current Session 暂无调用记录。</p>
          </article>

          <aside class="overview-side">
            <article class="panel">
              <div class="panel-heading"><h2>Current Session</h2><span class="pill">当前</span></div>
              <dl class="detail-list">
                <div><dt>名称</dt><dd>{{ currentSession.name }}</dd></div>
                <div><dt>Session ID</dt><dd><code>{{ currentSession.session_id }}</code></dd></div>
                <div><dt>权限</dt><dd>{{ currentSession.read_only ? '只读' : '可写' }}</dd></div>
                <div><dt>创建时间</dt><dd>{{ formatTime(currentSession.created_at) }}</dd></div>
              </dl>
            </article>
            <article class="panel scope-summary">
              <div class="panel-heading"><h2>调试范围</h2></div>
              <button @click="setActiveView('interfaces')"><span>已观察接口</span><strong>{{ interfaces.length }}</strong></button>
              <button @click="setActiveView('breakpoints')"><span>断点规则</span><strong>{{ breakpoints.length }}</strong></button>
              <button @click="setActiveView('sessions')"><span>Session</span><strong>{{ workspaces.length }}</strong></button>
            </article>
          </aside>
        </section>
      </template>

      <template v-if="activeView === 'interfaces'">
        <section class="settings-intro observation-intro">
          <div><span class="status-badge neutral">由调用与断点派生</span><h2>接口列表</h2><p>Interface 没有独立 CRUD；字段结构来自最近一次完整 params 样本，未调用目标也可先建断点。</p></div>
          <label class="toggle-filter"><input v-model="currentInterfacesOnly" type="checkbox" @change="ensureBrowsedInterface" />仅显示当前相关</label>
        </section>

        <section v-if="interfaces.length" class="session-layout observation-layout">
          <div class="session-list panel" aria-label="Interface 列表">
            <div class="interface-list-head"><span>Command</span><span>调用</span><span>断点</span><span>最近观察</span><span class="interface-wide-evidence interface-recent-state">最近状态</span><span class="interface-wide-evidence interface-recent-duration">最近耗时</span><span>结构摘要</span></div>
            <template v-for="group in groupedInterfaces" :key="group.object">
              <button type="button" class="object-group-header list-group-toggle" :aria-expanded="!isListGroupCollapsed('interfaces', group.object)" @click="toggleListGroup('interfaces', group.object)" @keydown.enter.prevent="toggleListGroup('interfaces', group.object)" @keydown.space.prevent="toggleListGroup('interfaces', group.object)">
                <span class="list-group-title"><UiIcon name="chevron" /><strong>{{ group.object }}</strong></span>
                <span class="list-group-summary"><span>{{ group.items.length }} 接口</span><span>{{ group.items.reduce((sum, item) => sum + item.interaction_count, 0) }} 调用</span><span>{{ group.items.reduce((sum, item) => sum + item.enabled_breakpoint_count, 0) }} 启用断点</span></span>
              </button>
              <template v-if="!isListGroupCollapsed('interfaces', group.object)">
                <button
                  v-for="item in group.items"
                  :key="interfaceKey(item)"
                  class="interface-list-row"
                  :class="{ selected: interfaceKey(item) === browsedInterfaceKey }"
                  @click="browsedInterfaceKey = interfaceKey(item)"
                >
                  <span class="interface-command"><span class="interface-command-title"><strong>{{ item.command }}</strong><small v-if="item.current_related" class="interface-current">当前</small></span><small class="interface-summary-inline">{{ item.schema_changed ? '字段结构已变化' : '结构稳定' }} · {{ item.field_schema?.length || 0 }} 字段</small></span>
                  <span>{{ item.interaction_count }}</span>
                  <span>{{ item.enabled_breakpoint_count }} / {{ item.breakpoint_count }}</span>
                  <time>{{ formatTime(item.last_seen_at) }}</time>
                  <span class="interface-wide-evidence interface-recent-state">{{ interfaceRecentStatus(item) }}</span>
                  <span class="interface-wide-evidence interface-recent-duration">{{ interfaceRecentDuration(item) }}</span>
                  <span class="interface-summary">{{ item.schema_changed ? '字段结构已变化' : '结构稳定' }} · {{ item.field_schema?.length || 0 }} 字段</span>
                </button>
              </template>
            </template>
            <p v-if="!visibleInterfaces.length" class="empty-state">本轮调试还没有观察到 Interface；可切换为全部历史。</p>
          </div>

          <article v-if="browsedInterface" class="panel session-detail observation-detail">
            <div class="panel-heading">
              <div><p class="eyebrow">接口详情</p><h2>{{ browsedInterface.object }}.{{ browsedInterface.command }}</h2></div>
              <div class="heading-actions"><span :class="['pill', { success: !browsedInterface.schema_changed }]">{{ browsedInterface.schema_changed ? '结构已变化' : '结构稳定' }}</span><button class="primary compact" :disabled="controlledByOther" @click="newBreakpoint(browsedInterface)">新建断点</button></div>
            </div>
            <dl class="detail-list">
              <div><dt>Object</dt><dd><code>{{ browsedInterface.object }}</code></dd></div>
              <div><dt>Command</dt><dd><code>{{ browsedInterface.command }}</code></dd></div>
              <div><dt>最近观察</dt><dd>{{ formatTime(browsedInterface.last_seen_at) }}</dd></div>
              <div><dt>调用数量</dt><dd>{{ browsedInterface.interaction_count }}</dd></div>
              <div><dt>断点规则</dt><dd>{{ browsedInterface.enabled_breakpoint_count }} 启用 / {{ browsedInterface.breakpoint_count }} 总计</dd></div>
            </dl>
            <div class="field-schema">
              <div class="section-heading"><strong>最新完整字段结构</strong><button v-if="browsedInterface.sample_ref" class="text-button" @click="openSample(browsedInterface.sample_ref.interaction_id)">查看样本调用</button></div>
              <div v-if="browsedInterface.field_schema.length" class="field-table">
                <div v-for="field in browsedInterface.field_schema" :key="field.path"><code>{{ field.path }}</code><span>{{ field.type }}</span></div>
              </div>
              <p v-else class="empty-state">{{ browsedInterface.sample_ref ? '最新 params 是空对象。' : '尚无调用样本；断点来自手工目标。' }}</p>
            </div>
            <div v-if="browsedInterface.breakpoints.length" class="breakpoint-summary">
              <strong>关联断点</strong>
              <button v-for="item in browsedInterface.breakpoints" :key="item.breakpoint_id" @click="setActiveView('breakpoints'); editBreakpoint(breakpoints.find(value => value.breakpoint_id === item.breakpoint_id))">
                <span>{{ item.name }}</span><span :class="['pill', { success: item.enabled }]">{{ item.enabled ? '启用' : '屏蔽' }}</span>
              </button>
            </div>
          </article>
        </section>
        <section v-else class="panel empty-panel"><h2>Current Session 还没有 Interface</h2><p>可以先手工输入 object 与 command 创建断点，也可以启动调试捕捉真实调用。</p><button class="primary" :disabled="controlledByOther" @click="newBreakpoint()">手工新建断点</button></section>
      </template>

      <template v-if="activeView === 'breakpoints'">
        <section class="settings-intro observation-intro">
          <div><span class="status-badge neutral">持久规则</span><h2>断点规则</h2><p>同一 Interface 可保存多条 before 或 after 规则；字段条件全部按 AND 匹配各自选择的原始入参或出参。</p></div>
          <button class="primary" :disabled="controlledByOther" @click="newBreakpoint()">手工新建</button>
        </section>

        <section class="session-layout breakpoint-layout" :class="{ 'breakpoint-layout-empty': !breakpoints.length }">
          <div class="session-list panel" aria-label="Breakpoint 列表">
            <div class="breakpoint-list-head"><span>状态</span><span>规则</span><span>暂停点</span><span>条件</span><span>命中</span><span>最近命中</span></div>
            <template v-for="group in groupedBreakpoints" :key="group.object">
              <button
                type="button"
                class="object-group-header list-group-toggle breakpoint-object-header"
                :aria-expanded="!isListGroupCollapsed('breakpoints', group.object)"
                @click="toggleListGroup('breakpoints', group.object)"
                @keydown.enter.prevent="toggleListGroup('breakpoints', group.object)"
                @keydown.space.prevent="toggleListGroup('breakpoints', group.object)"
              >
                <span class="list-group-title"><UiIcon name="chevron" /><strong>{{ group.object }}</strong></span>
                <span class="list-group-summary"><span>{{ group.interfaces.length }} 接口</span><span>{{ group.rule_count }} 规则</span><span>{{ group.enabled_count }} 启用</span><span>{{ group.hit_count }} 命中</span></span>
              </button>
              <template v-if="!isListGroupCollapsed('breakpoints', group.object)">
                <template v-for="interfaceGroup in group.interfaces" :key="`${group.object}.${interfaceGroup.command}`">
                  <button
                    type="button"
                    class="breakpoint-interface-header list-group-toggle"
                    :aria-expanded="!isBreakpointInterfaceCollapsed(group.object, interfaceGroup.command)"
                    @click="toggleBreakpointInterface(group.object, interfaceGroup.command)"
                    @keydown.enter.prevent="toggleBreakpointInterface(group.object, interfaceGroup.command)"
                    @keydown.space.prevent="toggleBreakpointInterface(group.object, interfaceGroup.command)"
                  >
                    <span class="breakpoint-interface-title list-group-title"><UiIcon name="chevron" /><strong>{{ group.object }}.{{ interfaceGroup.command }}</strong></span>
                    <span class="list-group-summary"><span>{{ interfaceGroup.rule_count }} 规则</span><span>{{ interfaceGroup.enabled_count }} 启用</span><span>{{ interfaceGroup.hit_count }} 命中</span><span>最近 {{ interfaceGroup.last_hit_at ? formatTime(interfaceGroup.last_hit_at) : '未命中' }}</span></span>
                  </button>
                  <template v-if="!isBreakpointInterfaceCollapsed(group.object, interfaceGroup.command)">
                    <button
                      v-for="item in interfaceGroup.items"
                      :key="item.breakpoint_id"
                      class="breakpoint-rule-row"
                      :class="{ selected: item.breakpoint_id === editingBreakpointId }"
                      @click="editBreakpoint(item)"
                    >
                      <span class="breakpoint-state-cell"><span :class="['breakpoint-state', { enabled: item.enabled }]">{{ item.enabled ? '启用' : '屏蔽' }}</span></span>
                      <strong class="breakpoint-rule-name"><span>{{ item.name }}</span></strong>
                      <span>{{ item.pause_point }}</span>
                      <span>{{ item.conditions.length ? `${item.conditions.length} 条` : '无条件' }}</span>
                      <span>{{ item.hit_count }}</span>
                      <time>{{ item.last_hit_at ? formatTime(item.last_hit_at) : '未命中' }}</time>
                    </button>
                  </template>
                </template>
              </template>
            </template>
            <p v-if="!breakpoints.length" class="empty-state">尚无断点。右侧可直接输入尚未观察到的目标。</p>
          </div>

          <form ref="breakpointEditor" class="panel breakpoint-editor" @submit.prevent="saveBreakpoint">
            <div class="panel-heading">
              <div><p class="eyebrow">断点编辑器</p><h2>{{ editingBreakpointId ? '编辑断点' : '新建断点' }}</h2></div>
              <span v-if="editingBreakpointId" class="pill">保留原 ID</span>
            </div>
            <div class="breakpoint-fields">
              <label>名称（留空自动生成）<input v-model="breakpointForm.name" maxlength="200" placeholder="例如：电源设置前暂停" /></label>
              <datalist id="breakpoint-object-options">
                <option v-for="item in breakpointObjectSuggestions" :key="item.object" :value="item.object">{{ breakpointObjectEvidence(item) }}</option>
              </datalist>
              <datalist id="breakpoint-command-options">
                <option v-for="item in breakpointCommandSuggestions" :key="`${item.object}.${item.command}`" :value="item.command">{{ breakpointCommandEvidence(item) }}</option>
              </datalist>
              <div class="form-grid">
                <label>Object<input v-model.trim="breakpointForm.object" list="breakpoint-object-options" maxlength="200" placeholder="例如 Power" /></label>
                <label>Command<input v-model.trim="breakpointForm.command" list="breakpoint-command-options" maxlength="200" placeholder="例如 set" /></label>
              </div>
              <section class="breakpoint-draft-workspace">
                <section class="breakpoint-reference-evidence" aria-label="参考调用与字段证据">
                  <div class="section-heading"><div><strong>参考调用</strong><small>明确选择一条调用，params 与 result 证据都只取自该调用</small></div><span>{{ breakpointReferenceCandidates.length }} 条可选</span></div>
                  <div v-if="breakpointReferenceCandidates.length" class="breakpoint-reference-list">
                    <button v-for="item in breakpointReferenceCandidates" :key="item.interaction_id" type="button" :class="{ selected: item.interaction_id === breakpointReferenceInteractionId }" @click="selectBreakpointReference(item)">
                      <span><strong>{{ item.object }}.{{ item.command }}</strong><small>{{ formatTime(item.before_at) }} · {{ interactionStatusLabel(item) }} / {{ interactionPhaseLabel(item) }}</small></span>
                      <span><small>{{ breakpointReferenceStructure(item) }}</small><code>{{ shortInteractionId(item) }}</code></span>
                    </button>
                  </div>
                  <p v-else class="empty-state">当前目标没有已加载调用；仍可创建未验证条件并保存。</p>
                  <div class="breakpoint-target-suggestions" aria-label="已观察目标建议">
                    <div v-if="breakpointObjectSuggestions.length"><strong>已观察 Object</strong><span><button v-for="item in breakpointObjectSuggestions" :key="item.object" type="button" :class="{ selected: item.object === breakpointForm.object.trim() }" :title="breakpointObjectEvidence(item)" @click="chooseBreakpointObject(item.object)"><b>{{ item.object }}</b><small>{{ breakpointObjectEvidence(item) }}</small></button></span></div>
                    <div v-if="breakpointCommandSuggestions.length"><strong>Command 建议</strong><span><button v-for="item in breakpointCommandSuggestions.slice(0, 5)" :key="`${item.object}.${item.command}`" type="button" :class="{ selected: item.object === breakpointForm.object.trim() && item.command === breakpointForm.command.trim() }" :title="breakpointCommandEvidence(item)" @click="chooseBreakpointCommand(item.command)"><b>{{ item.command }}</b><small>{{ breakpointCommandEvidence(item) }}</small></button></span></div>
                  </div>
                  <p v-if="!breakpointTargetObserved" class="breakpoint-unobserved-note">未观察到 {{ breakpointForm.object }}.{{ breakpointForm.command }}，仍可按当前文本提前配置并保存。</p>
                  <div v-if="breakpointReferenceInteraction" class="breakpoint-reference-fields">
                    <label>证据来源<select v-model="breakpointReferenceSource" :disabled="breakpointForm.pause_point === 'before'"><option value="params">params · 原始入参</option><option value="result">result · 原始成功出参</option></select></label>
                    <p v-if="!breakpointReferenceState.available" class="breakpoint-unobserved-note">{{ breakpointReferenceState.unavailable_reason }}仍可创建未验证条件并保存。</p>
                    <div class="breakpoint-reference-field-head"><span>field_path</span><span>类型</span><span>本次样本值</span><span></span></div>
                    <div v-for="field in breakpointReferenceFields" :key="field.path" class="breakpoint-reference-field-row">
                      <code :title="field.path">{{ field.path }}</code><span>{{ field.type }}</span><span :title="breakpointSampleValue(field)">{{ breakpointSampleValue(field) }}<small>选中参考调用的 {{ breakpointReferenceSource }}</small></span><button type="button" class="secondary compact" :disabled="!field.usable" @click="setBreakpointConditionFromField(field)">{{ existingConditionIndex(breakpointForm.conditions, breakpointReferenceSource, field.path) >= 0 ? '定位条件' : '设为条件' }}</button>
                    </div>
                    <p v-if="breakpointReferenceState.available && !breakpointReferenceFields.length" class="empty-state">这条参考调用的 {{ breakpointReferenceSource }} 已可用，但没有可列出的对象字段。</p>
                  </div>
                </section>
              <section class="condition-builder">
                <label>暂停点<select v-model="breakpointForm.pause_point"><option value="before">before · 业务执行前</option><option value="after">after · 业务执行后</option></select></label>
                <p v-if="breakpointConditionWarning" class="notice warning">已移除 {{ breakpointConditionWarning.count }} 条仅能在 after 求值的 result 条件。<template v-if="breakpointConditionWarning.becameUnconditional">没有剩余 params 条件；保存后将成为每次调用都暂停的无条件 before 断点。</template></p>
                <div class="section-heading">
                  <div><strong>断点条件</strong><small>全部条件按 AND 匹配各自选择的原始调用内容</small></div>
                  <button type="button" class="secondary compact" @click="addCondition">添加条件</button>
                </div>
                <div class="condition-editor-pane">
                <datalist id="breakpoint-params-field-options">
                  <option v-for="field in breakpointParamsReferenceFields" :key="field.path" :value="field.path">{{ field.type }}</option>
                </datalist>
                <datalist id="breakpoint-result-field-options">
                  <option v-for="field in breakpointResultReferenceFields" :key="field.path" :value="field.path">{{ field.type }}</option>
                </datalist>
                <p v-if="!breakpointForm.conditions.length" class="empty-state">当前是无条件接口断点。可从字段建议中选择，也可手工输入嵌套对象路径。</p>
                <article v-for="(condition, conditionIndex) in breakpointForm.conditions" :key="conditionIndex" class="condition-row" :class="{ 'evidence-highlight': condition.field_path.trim() === highlightedBreakpointConditionPath }">
                  <div class="condition-target-grid">
                    <label>来源<select v-model="condition.source" :disabled="breakpointForm.pause_point === 'before'"><option value="params">params · 原始入参</option><option value="result">result · 原始出参</option></select></label>
                    <label>field_path<input v-model="condition.field_path" :list="`breakpoint-${condition.source}-field-options`" placeholder="例如 request.mode" @change="applySuggestedType(condition)" /></label>
                    <label>操作符<select v-model="condition.operator" @change="changeConditionOperator(condition)"><option value="eq">eq · 精确相等</option><option value="contains_any">contains_any · 数组包含任一值</option></select></label>
                    <span class="field-type-hint">{{ suggestedField(condition)?.type || '手工路径' }}</span>
                    <button type="button" class="text-button danger-text" @click="removeCondition(conditionIndex)">删除条件</button>
                  </div>
                  <p v-if="!breakpointConditionEvidenceStates[conditionIndex].verified" class="breakpoint-unobserved-note">{{ breakpointConditionEvidenceStates[conditionIndex].warning }}仍可保存并启用。</p>
                  <div class="condition-values">
                    <div v-for="(editor, valueIndex) in condition.values" :key="valueIndex" class="condition-value-row">
                      <select v-model="editor.type" @change="changeEditorType(editor)">
                        <option value="string">字符串</option>
                        <option value="number">数字</option>
                        <option value="boolean">布尔</option>
                        <option value="null">null</option>
                      </select>
                      <select v-if="editor.type === 'boolean'" v-model="editor.text"><option value="true">true</option><option value="false">false</option></select>
                      <input v-else-if="editor.type === 'null'" value="null" disabled />
                      <input v-else v-model="editor.text" :inputmode="editor.type === 'number' ? 'decimal' : 'text'" :placeholder="editor.type === 'number' ? '例如 12.5' : '输入字符串值'" />
                      <button v-if="condition.operator === 'contains_any'" type="button" class="text-button danger-text" :disabled="condition.values.length === 1" @click="removeConditionValue(condition, valueIndex)">移除值</button>
                    </div>
                    <button v-if="condition.operator === 'contains_any'" type="button" class="text-button" @click="addConditionValue(condition)">＋ 添加候选值</button>
                  </div>
                </article>
                </div>
                <div class="condition-preview">
                  <div class="section-heading"><strong>只读 JSON 预览</strong><small>保存时由结构化表单生成</small></div>
                  <pre>{{ formatJson(breakpointConditionDraft.conditions) }}</pre>
                  <small v-if="breakpointConditionDraft.error" class="form-error">{{ breakpointConditionDraft.error }}</small>
                </div>
              </section>
              </section>
              <code v-if="editingBreakpointId" class="stable-id">breakpoint_id · {{ editingBreakpointId }}</code>
              <div class="session-actions">
                <button class="primary" :disabled="!canSaveBreakpoint">{{ editingBreakpointId ? '保存修改' : '创建并启用' }}</button>
                <template v-if="editingBreakpointId">
                  <button type="button" class="secondary" :disabled="busy" @click="cancelBreakpointEdit">取消修改</button>
                  <button type="button" class="secondary" :disabled="busy || controlledByOther" @click="toggleBreakpoint(breakpoints.find(item => item.breakpoint_id === editingBreakpointId))">{{ breakpoints.find(item => item.breakpoint_id === editingBreakpointId)?.enabled ? '屏蔽断点' : '启用断点' }}</button>
                  <button type="button" class="secondary danger" :disabled="busy || controlledByOther" @click="deleteBreakpoint(breakpoints.find(item => item.breakpoint_id === editingBreakpointId))">删除断点</button>
                </template>
              </div>
            </div>
          </form>
        </section>
      </template>

      <template v-if="activeView === 'interactions'">
        <header class="workspace-page-header interaction-page-header">
          <div><h1>调用记录</h1><p>Before → Business → After · 完整业务调用证据</p></div>
          <span class="count-label">{{ filteredInteractions.length }} / {{ interactions.length }} 条记录</span>
        </header>

        <section v-if="interactions.length" class="interaction-filters list-toolbar" :class="{ 'advanced-open': advancedFiltersOpen }" aria-label="Interaction 筛选">
          <label>关键词<input ref="interactionFilterQuery" v-model="interactionFilters.query" placeholder="ID、Object 或 Command" /></label>
          <label>Object<select v-model="interactionFilters.object"><option value="">全部 Object</option><option v-for="value in interactionObjects" :key="value" :value="value">{{ value }}</option></select></label>
          <label>状态<select v-model="interactionFilters.status"><option value="">全部状态</option><option value="paused">暂停</option><option value="in_progress">业务执行中</option><option value="completed">已完成</option></select></label>
          <label>阶段<select v-model="interactionFilters.pausePoint"><option value="">全部阶段</option><option value="before">before</option><option value="after">after</option></select></label>
          <button type="button" class="advanced-filter-toggle" :aria-expanded="advancedFiltersOpen" @click="advancedFiltersOpen = !advancedFiltersOpen"><UiIcon name="filter" />高级筛选</button>
          <button type="button" class="filter-reset" @click="clearInteractionFilters">清空</button>
          <div v-if="advancedFiltersOpen" class="advanced-filter-fields"><label>调用时间从<input v-model="interactionFilters.from" type="datetime-local" step="1" /></label><label>调用时间至<input v-model="interactionFilters.to" type="datetime-local" step="1" /></label></div>
        </section>

        <section v-if="interactions.length" class="interaction-selection-toolbar list-toolbar list-toolbar-selection" aria-label="继续所选暂停">
          <span>已选择 <strong>{{ selectedPauseCount }}</strong> 条可继续记录</span>
          <button class="primary compact list-toolbar-action" :disabled="!canContinueSelected" @click="continueSelectedInteractions">{{ busy ? '正在继续…' : '继续所选' }}</button>
        </section>

        <section v-if="interactions.length" class="interaction-workspace" :class="{ 'detail-open': browsedInteraction }">
          <div class="interaction-master" aria-label="Interaction 列表">
            <div class="interaction-table-head">
              <span aria-label="选择"></span><span>时间</span><span>Object.Command</span><span>生命周期</span><span>状态</span><span>耗时</span><span>命中</span><span>注入</span><span class="evidence-head">证据摘要</span><span class="evidence-rule-head">命中规则</span><span class="evidence-result-head">结果 / 释放</span><span class="evidence-payload-head">Payload</span>
            </div>
            <template v-for="group in groupedInteractions" :key="group.object">
              <button type="button" class="interaction-object-group list-group-toggle" :aria-expanded="!isListGroupCollapsed('interactions', group.object)" @click="toggleListGroup('interactions', group.object)" @keydown.enter.prevent="toggleListGroup('interactions', group.object)" @keydown.space.prevent="toggleListGroup('interactions', group.object)">
                <span class="interaction-group-summary"><UiIcon name="chevron" /><strong>{{ group.object }}</strong><span>{{ group.items.length }} 调用</span><span>{{ group.summary.completed }} 完成</span><span>{{ group.summary.running }} 运行</span><span>{{ group.summary.paused }} Pause</span><span>均耗时 {{ group.summary.averageDuration }}</span><span>{{ group.summary.hits }} 命中</span><span>{{ group.summary.injections }} 注入</span></span>
              </button>
              <div
                v-for="item in isListGroupCollapsed('interactions', group.object) ? [] : group.items"
                :key="item.interaction_id"
                class="interaction-table-row"
                :class="{ checked: isPauseSelected(item), selected: item.interaction_id === browsedInteractionId, paused: item.status === 'paused' }"
                role="button"
                tabindex="0"
                :aria-label="`查看 ${item.object}.${item.command} 调用详情`"
                :aria-current="item.interaction_id === browsedInteractionId ? 'true' : undefined"
                @click="openInteraction(item.interaction_id)"
                @keydown.enter.prevent="openInteraction(item.interaction_id)"
                @keydown.space.prevent="openInteraction(item.interaction_id)"
              >
                <label v-if="selectablePauseTarget(item)" class="pause-selection-control" @click.stop @keydown.stop>
                  <input
                    type="checkbox"
                    :checked="isPauseSelected(item)"
                    :aria-label="`选择 ${item.object}.${item.command} ${item.current_pause.pause_point} 暂停`"
                    @change="setPauseSelected(item, $event.target.checked)"
                  />
                </label>
                <span v-else class="pause-selection-placeholder" aria-hidden="true"></span>
                <span class="interaction-cell time-cell"><time>{{ formatTime(item.before_at) }}</time></span>
                <span class="interaction-cell target-cell"><strong>{{ item.object }}.{{ item.command }}</strong><small class="target-meta" :title="`${item.interaction_id} · ${interactionEvidenceSummary(item)}`"><code>{{ item.interaction_id }}</code><span class="target-evidence-inline"> · {{ interactionEvidenceSummary(item) }}</span></small></span>
                <span class="interaction-cell phase-cell"><span class="phase-track" :data-phase="interactionPhaseLabel(item)"><i></i><i></i><i></i></span><small>{{ interactionPhaseLabel(item) }}</small></span>
                <span class="interaction-cell"><span :class="['status-text', interactionStatus(item)]">{{ interactionStatusLabel(item) }}</span><small v-if="item.current_pause?.has_pending_injection" class="pending-review-note">待提交</small></span>
                <span class="interaction-cell metric-cell">{{ interactionDuration(item) }}</span>
                <span class="interaction-cell metric-cell">{{ interactionHitCount(item) }}</span>
                <span class="interaction-cell metric-cell">{{ interactionInjectionCount(item) }}</span>
                <span class="interaction-cell evidence-cell" :title="interactionEvidenceSummary(item)">{{ interactionEvidenceSummary(item) }}</span>
                <span class="interaction-cell evidence-subcell rule-evidence" :title="interactionRuleEvidence(item)">{{ interactionRuleEvidence(item) }}</span>
                <span class="interaction-cell evidence-subcell result-evidence" :title="interactionResultEvidence(item)">{{ interactionResultEvidence(item) }}</span>
                <span class="interaction-cell evidence-subcell payload-evidence" :title="interactionPayloadEvidence(item)">{{ interactionPayloadEvidence(item) }}</span>
              </div>
            </template>
            <section v-if="!filteredInteractions.length" class="empty-panel"><h2>没有符合筛选条件的调用</h2><p>调整关键词、状态、阶段或调用时间后再查看。</p></section>
          </div>

          <dialog
            v-if="browsedInteraction"
            ref="interactionDialog"
            :open="!compactDetailViewport"
            class="interaction-drawer-dialog"
            aria-labelledby="interaction-detail-title"
            @cancel.prevent="closeInteraction"
            @click.self="closeInteraction"
            @keydown.esc.prevent.stop="closeInteraction"
          >
          <article
            class="panel session-detail observation-detail interaction-drawer detail-tab-shell"
            @click.stop
          >
            <div class="panel-heading interaction-detail-heading">
              <div class="interaction-detail-title">
                <p class="eyebrow">调用详情</p>
                <div class="interaction-detail-title-row">
                  <h2 id="interaction-detail-title">{{ browsedInteraction.object }}.{{ browsedInteraction.command }}</h2>
                  <span :class="['pill', { success: interactionStatus(browsedInteraction) === 'completed', warning: interactionStatus(browsedInteraction) === 'paused' }]">{{ interactionStatus(browsedInteraction) === 'paused' ? `${browsedInteraction.current_pause.pause_point} 暂停` : interactionStatus(browsedInteraction) === 'completed' ? '已完成' : '业务执行中' }}</span>
                </div>
              </div>
              <div class="heading-actions interaction-detail-actions">
                <button type="button" class="secondary compact" :disabled="controlledByOther" @click="newBreakpoint(browsedInteraction, browsedInteraction.interaction_id)">据此新建断点</button>
                <button v-if="browsedInteraction.current_pause" type="button" class="primary compact interaction-continue-action" :disabled="busy || controlledByOther" @click="continueInteraction(browsedInteraction)">继续执行</button>
                <button ref="interactionDrawerClose" type="button" class="drawer-close interaction-detail-close" aria-label="关闭调用详情" @click="closeInteraction"><UiIcon name="close" /><span>关闭</span></button>
              </div>
            </div>
            <nav class="detail-tabs" role="tablist" aria-label="调用详情分类">
              <button role="tab" :aria-selected="interactionDetailTab === 'overview'" :class="{ active: interactionDetailTab === 'overview' }" @click="interactionDetailTab = 'overview'">总览</button>
              <button role="tab" :aria-selected="interactionDetailTab === 'pauses'" :class="{ active: interactionDetailTab === 'pauses' }" @click="interactionDetailTab = 'pauses'">Pause 审计 ({{ browsedInteraction.pauses.length }})</button>
              <button role="tab" :aria-selected="interactionDetailTab === 'payload'" :class="{ active: interactionDetailTab === 'payload' }" @click="interactionDetailTab = 'payload'">Payload</button>
              <button role="tab" :aria-selected="interactionDetailTab === 'raw'" :class="{ active: interactionDetailTab === 'raw' }" @click="interactionDetailTab = 'raw'">原始 JSON</button>
            </nav>
            <div class="detail-tab-body">
            <template v-if="interactionDetailTab === 'overview'">
              <div class="interaction-overview-grid">
                <dl class="detail-list interaction-overview-summary">
                <div><dt>Interaction ID</dt><dd><code>{{ browsedInteraction.interaction_id }}</code></dd></div>
                <div><dt>当前阶段</dt><dd>{{ browsedInteraction.phase === 'after' ? 'after 已上报' : '等待业务 after' }}</dd></div>
                <div><dt>Before</dt><dd>{{ formatTime(browsedInteraction.before_at) }}</dd></div>
                <div><dt>After</dt><dd>{{ formatTime(browsedInteraction.after_at) }}</dd></div>
                </dl>
                <section class="interaction-timeline">
                <div class="section-heading"><strong>阶段时间线</strong><small>{{ browsedInteraction.timeline?.length || 0 }} 个事件</small></div>
                <ol>
                  <li v-for="event in browsedInteraction.timeline" :key="`${event.event}-${event.phase}-${event.at}`">
                    <span class="timeline-dot"></span>
                    <div><strong>{{ timelineLabel(event) }}</strong><small>{{ formatTime(event.at) }}<template v-if="event.resolution"> · {{ event.resolution }}</template></small></div>
                  </li>
                </ol>
                </section>
              </div>
              <div v-if="browsedInteraction.current_pause" class="pause-evidence">
              <div class="section-heading"><div><strong>命中证据</strong><small>{{ browsedInteraction.current_pause.pause_point }} · {{ formatTime(browsedInteraction.current_pause.paused_at) }}</small></div></div>
              <article v-for="snapshot in browsedInteraction.current_pause.breakpoint_snapshots" :key="snapshot.breakpoint_id">
                <span><strong>{{ snapshot.name }}</strong><code>{{ snapshot.breakpoint_id }}</code></span>
                <span>{{ snapshot.object }}.{{ snapshot.command }} · {{ snapshot.pause_point }} · {{ snapshot.conditions.length ? `${snapshot.conditions.length} 条件` : '无条件' }}</span>
                <div class="hit-evidence-list">
                  <div v-for="(evidence, evidenceIndex) in hitEvidenceRows(snapshot)" :key="`${evidence.source}-${evidence.field_path}-${evidence.operator}-${evidenceIndex}`" class="hit-evidence-row">
                    <span><b>{{ evidence.source }}</b><code>{{ evidence.field_path }}</code><small>{{ evidence.operator }}</small></span>
                    <span><small>期望值</small><code>{{ evidence.expected }}</code></span>
                    <span><small>实际命中值</small><code>{{ evidence.actual }}</code></span>
                  </div>
                </div>
              </article>
              <small>这里保存的是命中时不可变快照和精简条件证据，不是当前规则或完整 payload 副本；之后修改或删除规则不会改变它。</small>
              </div>
            </template>
            <template v-else-if="interactionDetailTab === 'pauses'">
              <section v-if="browsedInteraction.current_pause" class="injection-workbench">
              <div class="pause-injection-actions">
                <div><strong>当前仍在 {{ browsedInteraction.current_pause.pause_point }} 暂停</strong><small>先应用修改（仍保持暂停），确认后再继续；不修改可直接继续。</small></div>
                <div>
                  <button class="secondary" :disabled="!canInject" @click="injectInteraction(browsedInteraction)">应用修改（保持暂停）</button>
                  <button class="primary" :disabled="busy || controlledByOther" @click="continueInteraction(browsedInteraction)">继续执行</button>
                </div>
              </div>
              <div class="section-heading">
                <div><strong>修改当前暂停内容</strong><small>{{ browsedInteraction.current_pause.pause_point === 'before' ? '修改本次请求参数' : '修改本次返回结果' }}；先应用修改，再决定是否继续执行。</small></div>
                <span class="pill warning">{{ browsedInteraction.current_pause.injection_status === 'pending' ? '有待提交修改' : '尚未修改' }}</span>
              </div>

              <div class="payload-grid injection-comparison">
                <section><strong>原始内容</strong><pre>{{ formatJson(browsedInteraction.current_pause.original_content) }}</pre></section>
                <section><strong>当前有效内容</strong><pre>{{ formatJson(browsedInteraction.current_pause.effective_content) }}</pre></section>
              </div>
              <div class="injection-diff-summary">
                <strong>当前差异</strong>
                <span v-if="!injectionChangedPointers.length">尚无有效差异</span>
                <code v-for="pointer in injectionChangedPointers" :key="pointer">{{ pointer }}</code>
              </div>

              <div v-if="injectionEditors.length" class="injection-fields">
                <div v-for="editor in injectionEditors" :key="editor.pointer" class="injection-field-row">
                  <label class="injection-select">
                    <input v-model="editor.selected" type="checkbox" :disabled="editor.locked || controlledByOther" @change="changeInjectionSelection(editor)" />
                    <span><code>{{ editor.pointer }}</code><small>{{ editor.type }}</small></span>
                  </label>
                  <div class="injection-value">
                    <span v-if="editor.type === 'null'" class="locked-field">原始值为 null，不能写入非 null 值</span>
                    <span v-else-if="editor.type === 'object' && editor.locked" class="locked-field">当前对象已设为 null，本次暂停内不能恢复</span>
                    <label v-else-if="editor.type === 'object'" class="null-toggle"><input v-model="editor.setNull" type="checkbox" :aria-label="`${editor.pointer} 整体设为 null`" :disabled="!editor.selected || controlledByOther" />整体设为 null</label>
                    <template v-else>
                      <label class="null-toggle"><input v-model="editor.setNull" type="checkbox" :aria-label="`${editor.pointer} 设为 null`" :disabled="!editor.selected || controlledByOther" />设为 null</label>
                      <select v-if="editor.type === 'boolean'" v-model="editor.text" :aria-label="`${editor.pointer} 新值`" :disabled="!editor.selected || editor.setNull || controlledByOther"><option value="true">true</option><option value="false">false</option></select>
                      <textarea v-else-if="editor.type === 'array'" v-model="editor.text" rows="3" :aria-label="`${editor.pointer} 新值`" :disabled="!editor.selected || editor.setNull || controlledByOther" spellcheck="false" />
                      <input v-else v-model="editor.text" :aria-label="`${editor.pointer} 新值`" :inputmode="editor.type === 'number' ? 'decimal' : 'text'" :disabled="!editor.selected || editor.setNull || controlledByOther" />
                    </template>
                  </div>
                </div>
              </div>
              <p v-else class="empty-state">当前内容不是 JSON 对象，不能直接注入；仍可继续或安全释放。</p>

              <div class="injection-submit">
                <div>
                  <strong>本次 changes 预览</strong>
                  <pre>{{ formatJson(injectionDraft.changes) }}</pre>
                  <small v-if="injectionDraftError || (injectionEditors.some(editor => editor.selected) && injectionDraft.error)" class="form-error">{{ injectionDraftError || injectionDraft.error }}</small>
                  <small v-else>只会提交已勾选字段，不接受路径字符串或数组索引。</small>
                </div>
              </div>
              <div v-if="injectionFeedback" class="injection-feedback">
                <strong>最近一次注入：{{ injectionFeedback.result }}</strong>
                <span>修改 {{ injectionFeedback.modified.length }} · 未变 {{ injectionFeedback.unchanged.length }} · 有效记录累计 {{ injectionFeedback.effective_change_count }}</span>
              </div>
              </section>
              <section class="interaction-history">
              <div class="section-heading"><div><strong>Pause 与继续审计</strong><small>命中快照、注入尝试和最终释放内容均来自权威后端</small></div><span class="pill">{{ browsedInteraction.pauses.length }} 个 Pause</span></div>
              <article v-for="pause in browsedInteraction.pauses" :key="pause.pause_point" class="pause-audit-card">
                <header>
                  <div><strong>{{ pause.pause_point }} Pause</strong><small>{{ formatTime(pause.paused_at) }} → {{ pause.resolved_at ? formatTime(pause.resolved_at) : '仍在暂停' }}</small></div>
                  <span :class="['pill', { warning: pause.status === 'paused', success: pause.status === 'continued' }]">{{ pause.status }}<template v-if="pause.resolution"> · {{ pause.resolution }}</template></span>
                </header>
                <div class="audit-snapshots">
                  <div v-for="snapshot in pause.breakpoint_snapshots" :key="snapshot.breakpoint_id">
                    <strong>{{ snapshot.name }}</strong><code>{{ snapshot.breakpoint_id }}</code><small>{{ snapshot.conditions.length ? `${snapshot.conditions.length} 条 AND 条件` : '无条件' }}</small>
                    <div class="hit-evidence-list">
                      <div v-for="(evidence, evidenceIndex) in hitEvidenceRows(snapshot)" :key="`${evidence.source}-${evidence.field_path}-${evidence.operator}-${evidenceIndex}`" class="hit-evidence-row">
                        <span><b>{{ evidence.source }}</b><code>{{ evidence.field_path }}</code><small>{{ evidence.operator }}</small></span>
                        <span><small>期望值</small><code>{{ evidence.expected }}</code></span>
                        <span><small>实际命中值</small><code>{{ evidence.actual }}</code></span>
                      </div>
                    </div>
                  </div>
                  <span v-if="!pause.breakpoint_snapshots.length" class="empty-inline">无 Breakpoint 快照</span>
                </div>
                <div class="audit-payloads">
                  <section><strong>最初捕获</strong><pre>{{ formatJson(pause.original_content) }}</pre></section>
                  <section><strong>当前有效</strong><pre>{{ formatJson(pause.effective_content) }}</pre></section>
                  <section><strong>最终放行</strong><pre>{{ pause.resolved_at ? formatJson(pause.released_content) : '尚未放行' }}</pre></section>
                </div>
                <div class="injection-audit-list">
                  <strong>注入审计（{{ pause.injection_audit.length }}）</strong>
                  <div v-for="(entry, index) in pause.injection_audit" :key="`${entry.injected_at}-${index}`">
                    <span><b>{{ entry.result }}</b><small>{{ formatTime(entry.injected_at) }} · {{ entry.effective_changed ? '产生有效修改' : '未改变有效内容' }}</small></span>
                    <pre>{{ formatJson(entry.changes) }}</pre>
                  </div>
                  <span v-if="!pause.injection_audit.length" class="empty-inline">本次 Pause 没有注入尝试</span>
                </div>
              </article>
              </section>
              <p v-if="!browsedInteraction.current_pause && !browsedInteraction.pauses.length" class="empty-state detail-tab-empty">此调用没有 Pause 或注入审计。</p>
            </template>
            <template v-else-if="interactionDetailTab === 'payload'">
              <div class="payload-grid">
              <section><strong>原始 params</strong><pre>{{ formatJson(browsedInteraction.original_params) }}</pre></section>
              <section><strong>业务 result</strong><pre>{{ browsedInteraction.phase === 'after' ? formatJson(browsedInteraction.result) : '尚未上报' }}</pre></section>
              </div>
              <section class="payload-metadata">
              <div class="section-heading"><strong>Payload 捕获边界</strong><small>超限 payload 会在上报时拒绝，不会静默丢失字段</small></div>
              <dl>
                <div v-for="(metadata, name) in browsedInteraction.payload_metadata" :key="name">
                  <dt>{{ name }}</dt>
                  <dd>{{ metadata.truncated ? '已截断' : '完整捕获' }} · 原始 {{ formatBytes(metadata.original_size_bytes) }} · 捕获 {{ formatBytes(metadata.captured_size_bytes) }}</dd>
                </div>
              </dl>
              </section>
            </template>
            <section v-else class="raw-interaction detail-raw-panel" aria-label="原始 Interaction JSON"><pre>{{ formatJson(browsedInteraction) }}</pre></section>
            </div>
          </article>
          </dialog>
        </section>
        <section v-else class="panel empty-panel"><h2>Current Session 暂无调用</h2><p>未启动调试时业务保持 fail-open，也不会在这里产生脏 Interaction。</p></section>
      </template>

      <template v-if="activeView === 'sessions'">
        <section class="settings-intro session-intro">
          <div><span class="status-badge neutral">持久工作区</span><h2>会话列表</h2><p>查看任意 Session 不会改变调试使用的 Current Session。</p></div>
          <div class="session-create-tools">
            <form class="create-session" @submit.prevent="createWorkspace">
              <input v-model="newSessionName" maxlength="120" placeholder="新 Session 名称" aria-label="新 Session 名称" />
              <button class="primary" :disabled="busy || controlledByOther || !newSessionName.trim()">新建 Session</button>
            </form>
            <button class="secondary" :disabled="busy || controlledByOther" @click="chooseSessionArchive">导入 .mbsession</button>
            <input ref="sessionImportInput" class="hidden-input" type="file" accept=".mbsession,application/json" @change="importSessionArchive" />
          </div>
        </section>

        <p v-if="debugging" class="notice readonly">调试运行期间可以浏览和整理其他 Session，但必须先停止调试才能切换 Current Session。</p>

        <section class="session-filters panel list-toolbar" aria-label="Session 筛选">
          <label>查找<input v-model="sessionFilters.query" placeholder="名称或 Session ID" /></label>
          <label>来源<select v-model="sessionFilters.source"><option value="">全部 Session</option><option value="local">本机 Session</option><option value="imported">导入证据</option></select></label>
          <span>{{ visibleWorkspaces.length }} / {{ workspaces.length }} 项</span>
        </section>

        <section class="session-layout">
          <div class="session-list panel" aria-label="Session 列表">
            <template v-for="group in groupedWorkspaces" :key="group.key">
              <button type="button" class="object-group-header session-group-header list-group-toggle" :aria-expanded="!isListGroupCollapsed('sessions', group.key)" @click="toggleListGroup('sessions', group.key)" @keydown.enter.prevent="toggleListGroup('sessions', group.key)" @keydown.space.prevent="toggleListGroup('sessions', group.key)">
                <span class="list-group-title"><UiIcon name="chevron" /><strong>{{ group.label }}</strong></span>
                <span class="list-group-summary"><span>{{ group.items.length }} 项</span></span>
              </button>
              <button
                v-for="item in isListGroupCollapsed('sessions', group.key) ? [] : group.items"
                :key="item.session_id"
                class="session-row grouped-session-row"
                :class="{ selected: item.session_id === browsedSessionId }"
                @click="browsedSessionId = item.session_id"
              >
                <span class="session-row-title"><strong>{{ item.name }}</strong><span v-if="item.current" class="pill success">Current</span><span v-else-if="item.read_only" class="pill">只读导入</span></span>
                <small>{{ item.source === 'local' ? '本机 Session' : '导入证据' }} · {{ item.read_only ? '只读' : '可写' }} · 更新于 {{ formatTime(item.updated_at) }} · {{ workspaceEvidenceSummary(item) }}</small>
              </button>
            </template>
            <p v-if="!visibleWorkspaces.length" class="empty-state">没有符合筛选条件的 Session。</p>
          </div>

          <article v-if="browsedWorkspace" class="panel session-detail detail-tab-shell">
            <div class="panel-heading">
              <div><p class="eyebrow">会话详情</p><h2>{{ browsedWorkspace.name }}</h2></div>
              <span :class="['pill', { success: browsedWorkspace.current }]">{{ browsedWorkspace.current ? 'Current' : browsedWorkspace.read_only ? '只读导入' : '仅查看' }}</span>
            </div>
            <nav class="detail-tabs" role="tablist" aria-label="会话详情分类">
              <button role="tab" :aria-selected="sessionDetailTab === 'overview'" :class="{ active: sessionDetailTab === 'overview' }" @click="sessionDetailTab = 'overview'">概览</button>
              <button role="tab" :aria-selected="sessionDetailTab === 'breakpoints'" :class="{ active: sessionDetailTab === 'breakpoints' }" @click="sessionDetailTab = 'breakpoints'">Breakpoint ({{ browsedArchiveSummary.breakpointCount }})</button>
              <button role="tab" :aria-selected="sessionDetailTab === 'interactions'" :class="{ active: sessionDetailTab === 'interactions' }" @click="sessionDetailTab = 'interactions'">Interaction ({{ browsedArchiveSummary.interactionCount }})</button>
              <button role="tab" :aria-selected="sessionDetailTab === 'pauses'" :class="{ active: sessionDetailTab === 'pauses' }" @click="sessionDetailTab = 'pauses'">Pause ({{ browsedArchiveSummary.pauseCount }})</button>
              <button role="tab" :aria-selected="sessionDetailTab === 'raw'" :class="{ active: sessionDetailTab === 'raw' }" @click="sessionDetailTab = 'raw'">原始归档</button>
            </nav>
            <div class="detail-tab-body">
            <template v-if="sessionDetailTab === 'overview'">
              <dl class="detail-list session-overview-list">
              <div><dt>Session ID</dt><dd><code>{{ browsedWorkspace.session_id }}</code></dd></div>
              <div><dt>运行上下文</dt><dd>{{ browsedWorkspace.current ? '当前调试使用此 Session' : '浏览不会改变 Current Session' }}</dd></div>
              <div><dt>来源与权限</dt><dd>{{ browsedWorkspace.source === 'local' ? '本机创建 · 可写' : '外部导入 · 只读' }}</dd></div>
              <div><dt>创建时间</dt><dd>{{ formatTime(browsedWorkspace.created_at) }}</dd></div>
              <div><dt>更新时间</dt><dd>{{ formatTime(browsedWorkspace.updated_at) }}</dd></div>
              </dl>
              <div class="session-actions archive-actions">
              <button v-if="browsedSessionActions.includes('export')" class="secondary" :disabled="busy" @click="exportWorkspace(browsedWorkspace)">导出 .mbsession</button>
              <button v-if="browsedSessionActions.includes('select')" class="primary" :disabled="busy || controlledByOther || debugging" @click="selectCurrentWorkspace(browsedWorkspace)">设为 Current</button>
              <button v-if="browsedSessionActions.includes('clear')" class="secondary danger" :disabled="busy || controlledByOther" @click="clearCurrentInteractions(browsedWorkspace)">清空调用记录</button>
              <button v-if="browsedSessionActions.includes('delete')" class="secondary danger" :disabled="busy || controlledByOther" @click="deleteWorkspace(browsedWorkspace)">删除</button>
              </div>
              <div v-if="browsedSessionActions.includes('rename')" class="session-editor">
              <label>Session 名称<input v-model="renameDrafts[browsedWorkspace.session_id]" maxlength="120" /></label>
              <div class="session-actions">
                <button class="secondary" :disabled="busy || controlledByOther || !renameDrafts[browsedWorkspace.session_id]?.trim() || renameDrafts[browsedWorkspace.session_id] === browsedWorkspace.name" @click="renameWorkspace(browsedWorkspace)">保存名称</button>
              </div>
              <small v-if="browsedWorkspace.current" class="action-reason">清空只删除调用、Pause、注入与继续审计，所有 Breakpoint 都会保留。</small>
              <small v-else-if="debugging" class="action-reason">停止调试后才能把该项设为 Current Session。</small>
              </div>
              <div v-else class="session-editor readonly-copy">导入 Session 是不可修改、不可设为 Current 的只读证据，只能查看、再次导出或删除。</div>

              <section v-if="browsedSessionArchive" class="archive-evidence archive-overview">
              <div class="section-heading"><div><strong>Session 证据</strong><small>{{ browsedSessionArchive.format }} · 导出于 {{ formatTime(browsedSessionArchive.exported_at) }}</small></div><div class="heading-actions"><span class="pill">完整归档</span><button class="secondary compact" :disabled="busy" @click="loadBrowsedSessionArchive(browsedWorkspace.session_id)">刷新证据</button></div></div>
              <div class="archive-metrics">
                <div><strong>{{ browsedArchiveSummary.breakpointCount }}</strong><span>Breakpoint</span></div>
                <div><strong>{{ browsedArchiveSummary.interactionCount }}</strong><span>Interaction</span></div>
                <div><strong>{{ browsedArchiveSummary.pauseCount }}</strong><span>Pause</span></div>
                <div><strong>{{ browsedArchiveSummary.resolvedPauseCount }}</strong><span>已释放</span></div>
              </div>
              <dl class="detail-list archive-source">
                <div><dt>来源装备</dt><dd>{{ browsedSessionArchive.source_equipment.display_name }} · <code>{{ browsedSessionArchive.source_equipment.equipment_id }}</code></dd></div>
                <div><dt>来源 Session ID</dt><dd><code>{{ browsedSessionArchive.session.session_id }}</code></dd></div>
              </dl>
              </section>
            </template>
            <section v-else-if="sessionDetailTab === 'breakpoints'" class="archive-tab-list" aria-label="Breakpoint 归档">
              <div v-for="item in browsedSessionArchive?.breakpoints || []" :key="item.breakpoint_id"><strong>{{ item.name }}</strong><span>{{ item.object }}.{{ item.command }}</span><span>{{ item.pause_point }} · {{ item.enabled ? '启用' : '屏蔽' }}</span><code>{{ item.breakpoint_id }}</code></div>
              <p v-if="!browsedSessionArchive?.breakpoints?.length" class="empty-state">此 Session 没有 Breakpoint 证据。</p>
            </section>
            <section v-else-if="sessionDetailTab === 'interactions'" class="archive-tab-list" aria-label="Interaction 归档">
              <div v-for="item in browsedSessionArchive?.interactions || []" :key="item.interaction_id"><strong>{{ item.object }}.{{ item.command }}</strong><span>{{ item.lifecycle }}</span><time>{{ formatTime(item.before_at) }}</time><code>{{ item.interaction_id }}</code></div>
              <p v-if="!browsedSessionArchive?.interactions?.length" class="empty-state">此 Session 没有 Interaction 证据。</p>
            </section>
            <section v-else-if="sessionDetailTab === 'pauses'" class="archive-tab-list" aria-label="Pause 归档">
              <div v-for="item in browsedSessionArchive?.pauses || []" :key="`${item.interaction_id}-${item.pause_point}`"><strong>{{ item.pause_point }} · {{ item.status }}</strong><span>注入 {{ item.injection_audit.length }} 次</span><span>{{ item.resolution || '尚未释放' }}</span><code>{{ item.interaction_id }}</code></div>
              <p v-if="!browsedSessionArchive?.pauses?.length" class="empty-state">此 Session 没有 Pause 审计。</p>
            </section>
            <section v-else class="raw-interaction detail-raw-panel" aria-label="原始 Session 归档">
              <pre v-if="browsedSessionArchive">{{ formatJson(browsedSessionArchive) }}</pre>
              <p v-else class="empty-state">尚未加载 Session 归档。</p>
            </section>
            </div>
          </article>
        </section>
      </template>

      <template v-if="activeView === 'settings' && settings">
        <section class="settings-intro">
          <div><span class="status-badge neutral">只读</span><h2>生效配置</h2><p>配置文件是唯一来源，修改后需要重启产品。</p></div>
          <button class="secondary" @click="loadProduct">刷新诊断</button>
        </section>

        <section class="grid-two settings-grid">
          <article class="panel">
            <div class="panel-heading"><h2>服务与存储</h2><span class="pill success">健康</span></div>
            <dl class="detail-list">
              <div><dt>服务绑定</dt><dd><code>{{ settings.server.address }}:{{ settings.server.port }}</code></dd></div>
              <div><dt>数据目录</dt><dd><code>{{ settings.storage.data_directory }}</code></dd></div>
              <div><dt>数据库</dt><dd>{{ settings.health.database }}</dd></div>
              <div><dt>业务开关</dt><dd>{{ settings.health.debugger_switch }}</dd></div>
            </dl>
          </article>

          <article class="panel">
            <div class="panel-heading"><h2>装备接入</h2><span class="pill success">已配置</span></div>
            <dl class="detail-list">
              <div><dt>装备</dt><dd>{{ settings.equipment.display_name }}</dd></div>
              <div><dt>Equipment ID</dt><dd><code>{{ settings.equipment.equipment_id }}</code></dd></div>
              <div><dt>开关地址</dt><dd><code>{{ settings.equipment.debugger_switch.url }}</code></dd></div>
            </dl>
          </article>

          <article class="panel">
            <div class="panel-heading"><h2>认证状态</h2><span class="pill success">全部已配置</span></div>
            <dl class="detail-list">
              <div><dt>Web 管理员</dt><dd>{{ settings.security.web_username }}</dd></div>
              <div><dt>Web 密码</dt><dd>{{ settings.security.web_password }}</dd></div>
              <div><dt>Gateway 密钥</dt><dd>{{ settings.security.gateway_token }}</dd></div>
              <div><dt>业务接入密钥</dt><dd>{{ settings.security.business_client_token }}</dd></div>
            </dl>
          </article>

          <article class="panel">
            <div class="panel-heading"><h2>运行限制</h2><span class="pill">重启生效</span></div>
            <dl class="detail-list">
              <div><dt>控制租约</dt><dd>{{ settings.limits.control_lease_timeout_seconds / 60 }} 分钟</dd></div>
              <div><dt>Pause 超时</dt><dd>{{ settings.limits.pause_timeout_seconds / 60 }} 分钟</dd></div>
              <div><dt>Payload 上限</dt><dd>{{ settings.limits.max_payload_bytes / 1024 / 1024 }} MB</dd></div>
              <div><dt>配置来源</dt><dd>{{ settings.configuration_source }}</dd></div>
            </dl>
          </article>
        </section>
      </template>
    </main>
    </div>
    <footer class="app-footer">
      <span><i class="health-dot"></i>{{ overview?.connection?.label || '连接状态未知' }}</span>
      <span>装备：{{ equipment?.display_name || '—' }}</span>
      <span>Session：{{ currentSession?.name || '—' }}</span>
      <span>调试：{{ debuggingLabel }}</span>
      <span class="footer-right">{{ busy ? '正在处理…' : '就绪' }}</span>
    </footer>
  </div>
</template>
