"""Persistent map of workspace -> last Claude SDK session id.

The Agent SDK writes each conversation's transcript to disk (under
``~/.claude/projects/...``), keyed by a session id. By remembering the last id
we used for a workspace, we can ``resume`` that same conversation after the app
(or the whole server) restarts — so each project keeps one continuous session.
"""

from __future__ import annotations

import json
import logging
import threading
from pathlib import Path

log = logging.getLogger(__name__)


class SessionStore:
    """Thread-safe JSON-backed ``workspace_path -> session_id`` store."""

    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)
        self._lock = threading.Lock()
        self._map: dict[str, str] = {}
        self._load()

    def _load(self) -> None:
        try:
            if self._path.exists():
                self._map = json.loads(self._path.read_text()) or {}
                log.info("Loaded %d persisted session(s) from %s", len(self._map), self._path)
        except Exception:
            log.exception("Could not read session store %s; starting empty", self._path)
            self._map = {}

    def _save(self) -> None:
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self._path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self._map, indent=2))
            tmp.replace(self._path)
        except Exception:
            log.exception("Could not write session store %s", self._path)

    def get(self, workspace_path: str) -> str | None:
        with self._lock:
            return self._map.get(workspace_path)

    def set(self, workspace_path: str, session_id: str) -> None:
        if not session_id:
            return
        with self._lock:
            if self._map.get(workspace_path) == session_id:
                return
            self._map[workspace_path] = session_id
            self._save()

    def clear(self, workspace_path: str) -> None:
        """Forget a workspace's session (used to start fresh / recover)."""
        with self._lock:
            if self._map.pop(workspace_path, None) is not None:
                self._save()
