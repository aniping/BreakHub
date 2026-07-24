"""Thread-to-target binding storage for gateway routing."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class BindingStoreError(RuntimeError):
    """Raised when thread target binding cannot be resolved."""


@dataclass(frozen=True)
class ThreadTargetBinding:
    """One persisted conversation-to-target binding."""

    thread_id: str
    user_id: str
    target_id: str

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ThreadTargetBinding:
        """Create a binding from one JSON item."""
        thread_id = str(data.get("thread_id") or "").strip()
        user_id = str(data.get("user_id") or "").strip()
        target_id = str(data.get("target_id") or "").strip()
        if not thread_id:
            raise BindingStoreError("Binding is missing thread_id.")
        if not target_id:
            raise BindingStoreError(f"Binding {thread_id} is missing target_id.")
        return cls(
            thread_id=thread_id,
            user_id=user_id,
            target_id=target_id,
        )

    def to_dict(self) -> dict[str, str]:
        """Serialize the binding to JSON."""
        return {
            "thread_id": self.thread_id,
            "user_id": self.user_id,
            "target_id": self.target_id,
        }


class FileThreadTargetBindingStore:
    """JSON binding store for conversation-to-target routing."""

    def __init__(self, bindings: list[ThreadTargetBinding], path: Path | None = None) -> None:
        """Initialize the store from validated bindings."""
        self._bindings = {binding.thread_id: binding for binding in bindings}
        self._path = path

    @classmethod
    def from_file(cls, path: Path) -> FileThreadTargetBindingStore:
        """Load bindings from a JSON file."""
        if not path.exists():
            raise BindingStoreError(f"Binding store file does not exist: {path}")
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        store = cls.from_dict(data)
        store._path = path
        return store

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> FileThreadTargetBindingStore:
        """Load bindings from a parsed JSON dictionary."""
        raw_bindings = data.get("bindings")
        if not isinstance(raw_bindings, list):
            raise BindingStoreError("Binding store must contain a bindings list.")
        return cls(
            [
                ThreadTargetBinding.from_dict(item)
                for item in raw_bindings
                if isinstance(item, dict)
            ]
        )

    def binding_for_thread(self, thread_id: str, user_id: str = "") -> ThreadTargetBinding:
        """Resolve the binding for one thread."""
        normalized_thread_id = str(thread_id or "").strip()
        if not normalized_thread_id:
            raise BindingStoreError("No thread_id was provided.")
        binding = self._bindings.get(normalized_thread_id)
        if binding is None:
            raise BindingStoreError(
                f"No target binding exists for thread_id: {normalized_thread_id}"
            )
        if binding.user_id and user_id and binding.user_id != user_id:
            raise BindingStoreError(
                f"Thread target binding does not belong to user: {normalized_thread_id}"
            )
        return binding

    def target_id_for_thread(self, thread_id: str, user_id: str = "") -> str:
        """Resolve the target id bound to one thread."""
        return self.binding_for_thread(thread_id, user_id=user_id).target_id

    def set_binding(self, binding: ThreadTargetBinding) -> None:
        """Persist one thread binding."""
        if self._path is None:
            raise BindingStoreError("Binding store path is not configured.")
        self._bindings[binding.thread_id] = binding
        self._persist()

    def find_binding(self, thread_id: str, user_id: str = "") -> ThreadTargetBinding | None:
        """Return one binding without treating absence as an error."""
        normalized_thread_id = str(thread_id or "").strip()
        if not normalized_thread_id:
            return None
        binding = self._bindings.get(normalized_thread_id)
        if binding is None:
            return None
        if binding.user_id and user_id and binding.user_id != user_id:
            raise BindingStoreError(
                f"Thread target binding does not belong to user: {normalized_thread_id}"
            )
        return binding

    def remove_binding(self, thread_id: str, user_id: str = "") -> bool:
        """Remove one owned thread binding and persist the result."""
        binding = self.find_binding(thread_id, user_id=user_id)
        if binding is None:
            return False
        del self._bindings[binding.thread_id]
        self._persist()
        return True

    def _persist(self) -> None:
        """Persist the current binding collection."""
        if self._path is None:
            raise BindingStoreError("Binding store path is not configured.")
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "version": 1,
            "bindings": [
                item.to_dict()
                for item in sorted(self._bindings.values(), key=lambda item: item.thread_id)
            ],
        }
        self._path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
