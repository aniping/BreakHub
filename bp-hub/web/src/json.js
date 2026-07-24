import {
  isLosslessNumber,
  isSafeNumber,
  LosslessNumber,
  parse,
  stringify,
} from 'lossless-json'

export function parseJson(text) {
  return parse(text, undefined, {
    parseNumber: value => (isSafeNumber(value) ? Number(value) : new LosslessNumber(value)),
  })
}

export function stringifyJson(value, space) {
  return stringify(value, undefined, space)
}

export function createJsonNumber(value) {
  return new LosslessNumber(value)
}

export function isJsonNumber(value) {
  return isLosslessNumber(value)
}
