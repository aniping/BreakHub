"""Agent-facing field breakpoint input contract."""

from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Annotated, Any

from pydantic import WithJsonSchema

CONDITION_FIELDS = ("source", "field_path", "operator", "value")
BASIC_TYPE_NAMES = ("number", "string", "bool", "null")
OPERATORS = ("eq", "contains_any")
SOURCES = ("params", "result")

_BASIC_VALUE_SCHEMA = {
    "anyOf": [
        {"type": "boolean"},
        {"type": "integer"},
        {"type": "number"},
        {"type": "string"},
        {"type": "null"},
    ]
}
_CONDITIONS_SCHEMA = {
    "anyOf": [
        {
            "type": "array",
            "items": {
                "oneOf": [
                    {
                        "type": "object",
                        "additionalProperties": False,
                        "properties": {
                            "source": {"type": "string", "enum": list(SOURCES)},
                            "field_path": {"type": "string"},
                            "operator": {"type": "string", "const": "eq"},
                            "value": _BASIC_VALUE_SCHEMA,
                        },
                        "required": ["source", "field_path", "operator", "value"],
                    },
                    {
                        "type": "object",
                        "additionalProperties": False,
                        "properties": {
                            "source": {"type": "string", "enum": list(SOURCES)},
                            "field_path": {"type": "string"},
                            "operator": {"type": "string", "const": "contains_any"},
                            "value": {
                                "type": "array",
                                "minItems": 1,
                                "items": _BASIC_VALUE_SCHEMA,
                            },
                        },
                        "required": ["source", "field_path", "operator", "value"],
                    },
                ]
            },
        },
        {"type": "null"},
    ]
}


type BreakpointConditionsInput = Annotated[
    Any,
    WithJsonSchema(_CONDITIONS_SCHEMA),
]


class BreakpointContractError(ValueError):
    """Report one correctable MCP breakpoint declaration error."""

    def __init__(self, message: str, details: dict[str, Any]) -> None:
        """Initialize the user-facing message and structured correction details."""
        super().__init__(message)
        self.details = details


def normalize_breakpoint_conditions(
    match_type: str,
    raw_conditions: Any,
) -> list[dict[str, Any]]:
    """Validate and normalize conditions before contacting BreakHub."""
    if match_type not in ("interface", "parameters"):
        raise BreakpointContractError(
            "不支持的断点匹配类型。",
            {
                "code": "INVALID_MATCH_TYPE",
                "field": "match_type",
                "reason": "unsupported_value",
                "allowed_values": ["interface", "parameters"],
            },
        )
    if match_type == "interface":
        if raw_conditions not in (None, []):
            raise BreakpointContractError(
                "接口级断点不能包含字段条件。",
                {
                    "code": "INVALID_INTERFACE_CONDITIONS",
                    "field": "conditions",
                    "reason": "must_be_empty",
                    "allowed_values": [],
                },
            )
        return []
    return _normalize_field_conditions(raw_conditions)


def normalize_create_breakpoint_arguments(
    arguments: dict[str, Any],
) -> dict[str, Any]:
    """Canonicalize the current create contract before HITL displays it."""
    normalized = dict(arguments)
    conditions = normalized.get("conditions")
    normalized["conditions"] = normalize_breakpoint_conditions(
        "interface" if conditions in (None, []) else "parameters",
        conditions,
    )
    return normalized


def breakpoint_contract_error(error: BreakpointContractError) -> dict[str, Any]:
    """Return a stable error response that an Agent can correct and retry."""
    return {
        "ok": False,
        "status": "invalid_request",
        "message": str(error),
        "entities": [],
        "error": error.details,
    }


def _normalize_field_conditions(
    raw_conditions: Any,
) -> list[dict[str, Any]]:
    if not isinstance(raw_conditions, list) or not raw_conditions:
        _raise_condition_error(None, "conditions", "must_be_non_empty_list")

    normalized_by_key: dict[tuple[Any, ...], dict[str, Any]] = {}
    for index, raw_condition in enumerate(raw_conditions):
        condition = raw_condition
        if not isinstance(condition, dict):
            _raise_condition_error(index, "condition", "must_be_object")
        for field_name in CONDITION_FIELDS:
            if field_name not in condition:
                _raise_condition_error(index, field_name, "missing_field")
        unknown_fields = sorted(set(condition) - set(CONDITION_FIELDS))
        if unknown_fields:
            _raise_condition_error(index, unknown_fields[0], "unknown_field")

        source = condition["source"]
        if source not in SOURCES:
            _raise_condition_error(index, "source", "unsupported_source", SOURCES)
        field_path = condition["field_path"]
        if not _valid_field_path(field_path):
            _raise_condition_error(index, "field_path", "invalid_field_path")
        operator = condition["operator"]
        if operator not in OPERATORS:
            _raise_condition_error(index, "operator", "unsupported_operator", OPERATORS)
        if operator == "eq":
            value = condition["value"]
            if not _is_basic_value(value):
                _raise_condition_error(
                    index,
                    "value",
                    "unsupported_value_type",
                    BASIC_TYPE_NAMES,
                )
            value = _canonical_candidate(value)
        else:
            value = _normalize_candidate_values(condition["value"], index)

        normalized_condition = {
            "source": source,
            "field_path": field_path,
            "operator": operator,
            "value": value,
        }
        normalized_by_key.setdefault(
            _condition_identity(normalized_condition),
            normalized_condition,
        )

    return [normalized_by_key[key] for key in sorted(normalized_by_key)]


def _raise_condition_error(
    condition_index: int | None,
    field_name: str,
    reason: str,
    allowed_values: tuple[str, ...] | None = None,
) -> None:
    details: dict[str, Any] = {
        "code": "INVALID_FIELD_CONDITION",
        "condition_index": condition_index,
        "field": field_name,
        "reason": reason,
    }
    if allowed_values is not None:
        details["allowed_values"] = list(allowed_values)
    raise BreakpointContractError("字段参数断点条件无效。", details)


def _valid_field_path(value: Any) -> bool:
    if not isinstance(value, str) or not value:
        return False
    if value == "@" or value.startswith("@."):
        return False
    if any(character in value for character in "[]/$*\\"):
        return False
    segments = value.split(".")
    return all(
        segment and not all("0" <= character <= "9" for character in segment)
        for segment in segments
    )


def _is_basic_value(value: Any) -> bool:
    return (
        value is None
        or isinstance(value, (bool, str))
        or _decimal_number(value) is not None
    )


def _normalize_candidate_values(value: Any, condition_index: int) -> list[Any]:
    if not isinstance(value, list) or not value:
        _raise_condition_error(condition_index, "value", "must_be_non_empty_list")

    unique = {}
    for candidate_index, candidate in enumerate(value):
        if not _is_basic_value(candidate):
            _raise_condition_error(
                condition_index,
                f"value[{candidate_index}]",
                "unsupported_value_type",
                BASIC_TYPE_NAMES,
            )
        identity = _candidate_identity(candidate)
        unique[identity] = _canonical_candidate(candidate)
    return [unique[key] for key in sorted(unique, key=_candidate_sort_key)]


def _candidate_identity(value: Any) -> tuple[str, Any]:
    if value is None:
        return "null", None
    if isinstance(value, bool):
        return "bool", value
    if isinstance(value, str):
        return "string", value
    return "number", _decimal_number(value)


def _condition_identity(condition: dict[str, Any]) -> tuple[Any, ...]:
    value = condition["value"]
    value_identity: tuple[Any, ...]
    if condition["operator"] == "contains_any":
        value_identity = tuple(_candidate_identity(candidate) for candidate in value)
    else:
        value_identity = _candidate_identity(value)
    return (
        condition["source"],
        condition["field_path"],
        condition["operator"],
        value_identity,
    )


def _candidate_sort_key(identity: tuple[str, Any]) -> tuple[int, Any]:
    category, value = identity
    return {"number": 0, "string": 1, "bool": 2, "null": 3}[category], value


def _canonical_candidate(value: Any) -> Any:
    if value is None or isinstance(value, (bool, str)):
        return value
    number = _decimal_number(value)
    if number is None:
        raise AssertionError("validated candidate must be a basic value")
    if number == number.to_integral_value():
        return int(number)
    return float(number)


def _decimal_number(value: Any) -> Decimal | None:
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        return None
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return number if number.is_finite() else None
