"""Path sandboxing and command safety checks."""

from __future__ import annotations

import os
from pathlib import Path


class PathSandbox:
    """Ensures all file operations stay within workspace root."""

    def __init__(self, workspace_root: str) -> None:
        self.root = Path(workspace_root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def resolve(self, path: str) -> Path:
        """Resolve a path relative to workspace root, ensuring it stays inside.

        Raises ValueError if the resolved path escapes the sandbox.
        """
        # Handle absolute paths by making them relative
        p = Path(path)
        if p.is_absolute():
            try:
                p = p.relative_to(self.root)
            except ValueError:
                # Absolute path outside workspace - treat as relative
                p = Path(str(path).lstrip("/"))

        resolved = (self.root / p).resolve()

        # Ensure we're still within the sandbox
        try:
            resolved.relative_to(self.root)
        except ValueError:
            raise ValueError(
                f"Path escapes sandbox: {path!r} resolves to {resolved}"
            )

        return resolved

    def resolve_str(self, path: str) -> str:
        return str(self.resolve(path))


def check_command_safety(command: str, blocked_patterns: list[str]) -> str | None:
    """Check if a command matches any blocked pattern.

    Returns the matched pattern if blocked, None if safe.
    """
    cmd_lower = command.lower().strip()
    for pattern in blocked_patterns:
        if pattern.lower() in cmd_lower:
            return pattern
    return None
