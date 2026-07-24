export function filterSessions(items, filters = {}) {
  const source = filters.source || ''
  const query = (filters.query || '').trim().toLocaleLowerCase()
  return items.filter(item => {
    if (source && item.source !== source) return false
    if (!query) return true
    return [item.name, item.session_id]
      .filter(Boolean)
      .some(value => String(value).toLocaleLowerCase().includes(query))
  })
}

export function legalSessionActions(item) {
  if (item.read_only || item.source === 'imported') return ['view', 'export', 'delete']
  if (item.current) return ['view', 'rename', 'export', 'clear']
  return ['view', 'rename', 'select', 'export', 'delete']
}

export function archiveSummary(archive) {
  const pauses = Array.isArray(archive?.pauses) ? archive.pauses : []
  return {
    breakpointCount: Array.isArray(archive?.breakpoints) ? archive.breakpoints.length : 0,
    interactionCount: Array.isArray(archive?.interactions) ? archive.interactions.length : 0,
    pauseCount: pauses.length,
    resolvedPauseCount: pauses.filter(item => item.status !== 'paused').length,
  }
}

export function createSessionWorkbenchClient({ request, download, confirm, parseArchive = JSON.parse }) {
  let archiveLoadSequence = 0

  return {
    async loadArchive(sessionId, onLoaded, onError = () => {}) {
      const sequence = ++archiveLoadSequence
      onLoaded(null)
      if (!sessionId) return null
      try {
        const archive = await request(`/api/v1/sessions/${sessionId}/archive`)
        if (sequence !== archiveLoadSequence) return null
        onLoaded(archive)
        return archive
      } catch (error) {
        if (sequence === archiveLoadSequence) onError(error)
        return null
      }
    },

    async importArchive(file) {
      if (!file?.name?.toLocaleLowerCase().endsWith('.mbsession')) {
        throw new Error('只支持 .mbsession 文件，不兼容旧 .mbrec。')
      }
      const archive = parseArchive(await file.text())
      return request('/api/v1/sessions/import', { method: 'POST', body: archive })
    },

    exportArchive(item) {
      return download(
        `/api/v1/sessions/${item.session_id}/export`,
        `${item.session_id}.mbsession`,
      )
    },

    async clearCurrent(item, { interactionCount, pauseCount }) {
      const accepted = confirm(
        `清空 Current Session“${item.name}”的 ${interactionCount} 条调用与 ${pauseCount} 条 Pause 审计？\n\n`
        + 'Breakpoint 会全部保留；如果仍有暂停调用，后端会拒绝本次操作。',
      )
      if (!accepted) return undefined
      return request('/api/v1/sessions/current/interactions/clear', { method: 'POST' })
    },

    async deleteSession(item) {
      if (!confirm(`删除 Session“${item.name}”？此操作不能撤销。`)) return undefined
      return request(`/api/v1/sessions/${item.session_id}`, { method: 'DELETE' })
    },
  }
}
