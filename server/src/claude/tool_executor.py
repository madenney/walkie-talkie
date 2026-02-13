"""Sandboxed tool execution."""

from __future__ import annotations

import asyncio
import glob as glob_module
import json
import logging
import os
import re
import urllib.parse
from pathlib import Path

from ..utils.safety import PathSandbox, check_command_safety

log = logging.getLogger(__name__)

# Max output size to return to Claude (chars)
MAX_OUTPUT = 50_000

# Default headers for outgoing HTTP requests (many servers reject bare aiohttp UA)
_HTTP_HEADERS = {
    "User-Agent": "Mozilla/5.0 (compatible; WalkieTalkie/1.0)",
}


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

        max_results = 200
        root = self.sandbox.root

        def _search_sync() -> list[str]:
            results = []

            def _search_file(fpath: Path) -> list[str]:
                hits = []
                try:
                    text = fpath.read_text(errors="replace")
                    for i, line in enumerate(text.splitlines(), 1):
                        if regex.search(line):
                            rel = str(fpath.relative_to(root))
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
            return results

        loop = asyncio.get_event_loop()
        results = await loop.run_in_executor(None, _search_sync)

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

    async def _tool_web_fetch(self, inp: dict) -> dict:
        import aiohttp
        url = inp["url"]
        try:
            async with aiohttp.ClientSession(headers=_HTTP_HEADERS) as session:
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=15)) as resp:
                    if resp.content_type and "html" in resp.content_type:
                        html = await resp.text()
                        text = self._html_to_text(html)
                    else:
                        text = await resp.text()
                    if len(text) > MAX_OUTPUT:
                        text = text[:MAX_OUTPUT] + "\n... (truncated)"
                    return {"success": True, "output": text}
        except Exception as e:
            return {"success": False, "output": f"Fetch failed: {e}"}

    async def _tool_web_search(self, inp: dict) -> dict:
        import aiohttp
        query = inp["query"]
        # Use DuckDuckGo HTML lite (no API key needed)
        url = "https://lite.duckduckgo.com/lite/"
        try:
            async with aiohttp.ClientSession(headers=_HTTP_HEADERS) as session:
                async with session.post(
                    url,
                    data={"q": query},
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    html = await resp.text()
                    text = self._html_to_text(html)
                    if len(text) > MAX_OUTPUT:
                        text = text[:MAX_OUTPUT] + "\n... (truncated)"
                    return {"success": True, "output": text}
        except Exception as e:
            return {"success": False, "output": f"Search failed: {e}"}

    async def _tool_download_file(self, inp: dict) -> dict:
        import aiohttp
        url = inp["url"]
        path = self.sandbox.resolve(inp["path"])
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            async with aiohttp.ClientSession(headers=_HTTP_HEADERS) as session:
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=60)) as resp:
                    if resp.status != 200:
                        return {"success": False, "output": f"HTTP {resp.status}"}
                    data = await resp.read()
                    path.write_bytes(data)
                    return {"success": True, "output": f"Downloaded {len(data)} bytes to {inp['path']}"}
        except Exception as e:
            return {"success": False, "output": f"Download failed: {e}"}

    @staticmethod
    def _html_to_text(html: str) -> str:
        """Quick and dirty HTML to text conversion."""
        import re as _re
        # Remove script/style blocks
        text = _re.sub(r"<(script|style)[^>]*>.*?</\1>", "", html, flags=_re.DOTALL | _re.IGNORECASE)
        # Convert br/p/div to newlines
        text = _re.sub(r"<br\s*/?>", "\n", text, flags=_re.IGNORECASE)
        text = _re.sub(r"</(p|div|h[1-6]|li|tr)>", "\n", text, flags=_re.IGNORECASE)
        # Strip remaining tags
        text = _re.sub(r"<[^>]+>", "", text)
        # Decode common entities
        import html as _html
        text = _html.unescape(text)
        # Collapse whitespace
        lines = [line.strip() for line in text.splitlines()]
        text = "\n".join(line for line in lines if line)
        return text
