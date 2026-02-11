"""Sandboxed tool execution."""

from __future__ import annotations

import asyncio
import glob as glob_module
import logging
import os
import re
from pathlib import Path

from ..utils.safety import PathSandbox, check_command_safety

log = logging.getLogger(__name__)

# Max output size to return to Claude (chars)
MAX_OUTPUT = 50_000


class ToolExecutor:
    """Execute Claude tools within a sandboxed workspace."""

    def __init__(self, sandbox: PathSandbox, blocked_commands: list[str], command_timeout: int = 30) -> None:
        self.sandbox = sandbox
        self.blocked_commands = blocked_commands
        self.command_timeout = command_timeout

    async def execute(self, tool_name: str, tool_input: dict) -> dict:
        """Execute a tool and return {"success": bool, "output": str}."""
        try:
            handler = getattr(self, f"_tool_{tool_name}", None)
            if handler is None:
                return {"success": False, "output": f"Unknown tool: {tool_name}"}
            result = await handler(tool_input)
            return result
        except ValueError as e:
            return {"success": False, "output": f"Safety error: {e}"}
        except Exception as e:
            log.exception("Tool %s failed", tool_name)
            return {"success": False, "output": f"Error: {e}"}

    async def _tool_read_file(self, inp: dict) -> dict:
        path = self.sandbox.resolve(inp["path"])
        if not path.is_file():
            return {"success": False, "output": f"File not found: {inp['path']}"}

        text = path.read_text(errors="replace")
        lines = text.splitlines(keepends=True)

        offset = inp.get("offset")
        limit = inp.get("limit")
        if offset is not None:
            start = max(0, offset - 1)  # 1-based to 0-based
            lines = lines[start:]
        if limit is not None:
            lines = lines[:limit]

        output = "".join(lines)
        if len(output) > MAX_OUTPUT:
            output = output[:MAX_OUTPUT] + f"\n... (truncated, {len(text)} total chars)"
        return {"success": True, "output": output}

    async def _tool_write_file(self, inp: dict) -> dict:
        path = self.sandbox.resolve(inp["path"])
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(inp["content"])
        return {"success": True, "output": f"Wrote {len(inp['content'])} chars to {inp['path']}"}

    async def _tool_edit_file(self, inp: dict) -> dict:
        path = self.sandbox.resolve(inp["path"])
        if not path.is_file():
            return {"success": False, "output": f"File not found: {inp['path']}"}

        text = path.read_text()
        old = inp["old_text"]
        new = inp["new_text"]

        count = text.count(old)
        if count == 0:
            return {"success": False, "output": "old_text not found in file"}
        if count > 1:
            return {"success": False, "output": f"old_text found {count} times — must be unique"}

        text = text.replace(old, new, 1)
        path.write_text(text)
        return {"success": True, "output": "Edit applied"}

    async def _tool_bash(self, inp: dict) -> dict:
        command = inp["command"]
        timeout = inp.get("timeout", self.command_timeout)

        blocked = check_command_safety(command, self.blocked_commands)
        if blocked:
            return {"success": False, "output": f"Blocked command pattern: {blocked}"}

        try:
            proc = await asyncio.create_subprocess_shell(
                command,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
                cwd=str(self.sandbox.root),
                env={**os.environ, "HOME": str(Path.home())},
            )
            stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout)
            output = stdout.decode(errors="replace")

            if len(output) > MAX_OUTPUT:
                output = output[:MAX_OUTPUT] + "\n... (truncated)"

            if proc.returncode != 0:
                output = f"Exit code {proc.returncode}\n{output}"
                return {"success": False, "output": output}

            return {"success": True, "output": output}

        except asyncio.TimeoutError:
            proc.kill()
            return {"success": False, "output": f"Command timed out after {timeout}s"}

    async def _tool_glob(self, inp: dict) -> dict:
        pattern = inp["pattern"]
        base = inp.get("path", "")
        if base:
            search_dir = self.sandbox.resolve(base)
        else:
            search_dir = self.sandbox.root

        matches = sorted(
            str(p.relative_to(self.sandbox.root))
            for p in search_dir.glob(pattern)
            if p.is_file()
        )

        if not matches:
            return {"success": True, "output": "No matches found"}

        output = "\n".join(matches[:500])
        if len(matches) > 500:
            output += f"\n... ({len(matches)} total matches)"
        return {"success": True, "output": output}

    async def _tool_grep(self, inp: dict) -> dict:
        pattern = inp["pattern"]
        base = inp.get("path", "")
        include = inp.get("include")

        if base:
            search_path = self.sandbox.resolve(base)
        else:
            search_path = self.sandbox.root

        try:
            regex = re.compile(pattern)
        except re.error as e:
            return {"success": False, "output": f"Invalid regex: {e}"}

        results = []
        max_results = 200

        def _search_file(fpath: Path) -> list[str]:
            hits = []
            try:
                text = fpath.read_text(errors="replace")
                for i, line in enumerate(text.splitlines(), 1):
                    if regex.search(line):
                        rel = str(fpath.relative_to(self.sandbox.root))
                        hits.append(f"{rel}:{i}: {line}")
            except (OSError, UnicodeDecodeError):
                pass
            return hits

        if search_path.is_file():
            results = _search_file(search_path)
        else:
            glob_pat = include or "**/*"
            for fpath in search_path.glob(glob_pat):
                if fpath.is_file() and not any(
                    part.startswith(".") for part in fpath.parts
                ):
                    results.extend(_search_file(fpath))
                    if len(results) >= max_results:
                        break

        if not results:
            return {"success": True, "output": "No matches found"}

        output = "\n".join(results[:max_results])
        if len(results) > max_results:
            output += f"\n... ({len(results)} total matches)"
        return {"success": True, "output": output}

    async def _tool_list_directory(self, inp: dict) -> dict:
        base = inp.get("path", "")
        if base:
            dir_path = self.sandbox.resolve(base)
        else:
            dir_path = self.sandbox.root

        if not dir_path.is_dir():
            return {"success": False, "output": f"Not a directory: {base or '.'}"}

        entries = []
        for item in sorted(dir_path.iterdir()):
            if item.name.startswith("."):
                continue
            suffix = "/" if item.is_dir() else ""
            entries.append(f"{item.name}{suffix}")

        return {"success": True, "output": "\n".join(entries) if entries else "(empty directory)"}
