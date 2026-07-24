export function breakpointInterfaceKey(object, command) {
  return `${object}\u0000${command}`
}

export function toggleCollapsedKey(keys, key) {
  const next = new Set(keys)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  return next
}

function latestHitAt(items) {
  return items.reduce((latest, item) => {
    if (!item.last_hit_at) return latest
    if (!latest) return item.last_hit_at
    return Date.parse(item.last_hit_at) > Date.parse(latest) ? item.last_hit_at : latest
  }, null)
}

function summarize(items) {
  return {
    rule_count: items.length,
    enabled_count: items.filter(item => item.enabled).length,
    hit_count: items.reduce((sum, item) => sum + (Number(item.hit_count) || 0), 0),
    last_hit_at: latestHitAt(items),
  }
}

export function groupBreakpointsByInterface(items) {
  const objects = new Map()
  for (const item of items) {
    if (!objects.has(item.object)) objects.set(item.object, new Map())
    const interfaces = objects.get(item.object)
    if (!interfaces.has(item.command)) interfaces.set(item.command, [])
    interfaces.get(item.command).push(item)
  }

  return [...objects.entries()].map(([object, interfaceMap]) => {
    const interfaces = [...interfaceMap.entries()].map(([command, values]) => ({
      command,
      items: values,
      ...summarize(values),
    }))
    const objectItems = interfaces.flatMap(group => group.items)
    return {
      object,
      items: objectItems,
      interfaces,
      ...summarize(objectItems),
    }
  })
}
