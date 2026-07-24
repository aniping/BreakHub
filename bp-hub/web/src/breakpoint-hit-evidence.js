import { stringifyJson } from './json.js'

export function hitEvidenceRows(snapshot) {
  return (snapshot.condition_evidence || []).map(evidence => ({
    source: evidence.source,
    field_path: evidence.field_path,
    operator: evidence.operator,
    expected: stringifyJson(evidence.expected_value),
    actual: stringifyJson(evidence.actual_value),
  }))
}
