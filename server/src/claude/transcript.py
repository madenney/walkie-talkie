"""Reconstruct a displayable chat history from a Claude SDK transcript.

The Agent SDK records every conversation as a JSONL transcript on disk (under
``~/.claude/projects/<encoded-cwd>/<session_id>.jsonl``). When we resume a
session the SDK restores Claude's *memory* but does not re-emit the old
messages, so the phone's scrollback would be blank. This reads that transcript
and rebuilds the user/assistant/tool messages the app already knows how to
render.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

log = logging.getLogger(__name__)

_PROJECTS_DIR = Path.home() / ".claude" / "projects"
# Mirror the live handler's per-tool-result cap so history matches the stream.
_OUTPUT_CAP = 2000


def _transcript_path(session_id: str) -> Path | None:
    """Find the transcript file for a session id (UUID → unique filename)."""
    if not session_id or not _PROJECTS_DIR.exists():
        return None
    matches = list(_PROJECTS_DIR.glob(f"*/{session_id}.jsonl"))
    return matches[0] if matches else None


def _strip_speak(text: str) -> str:
    return text.replace("<speak>", "").replace("</speak>", "").strip()


def _stringify_result(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for b in content:
            if isinstance(b, dict):
                parts.append(b.get("text", "") or str(b.get("content", "")))
            else:
                parts.append(str(b))
        return "\n".join(p for p in parts if p)
    return str(content)


def load_history(session_id: str) -> list[dict]:
    """Return a flat list of display messages for a resumed session.

    Each item: {"role": "user"|"assistant"|"tool", "text": str,
                "tool_name": str|None, "tool_output": str|None, "success": bool|None}
    Returns [] if no transcript exists (e.g. a brand-new session).
    """
    path = _transcript_path(session_id)
    if path is None:
        return []

    messages: list[dict] = []
    try:
        lines = path.read_text().splitlines()
    except Exception:
        log.exception("Could not read transcript %s", path)
        return []

    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            o = json.loads(line)
        except json.JSONDecodeError:
            continue

        # Skip subagent (Task tool) side conversations — only the main thread.
        if o.get("isSidechain"):
            continue
        mtype = o.get("type")
        if mtype not in ("user", "assistant"):
            continue
        msg = o.get("message")
        if not isinstance(msg, dict):
            continue
        content = msg.get("content")

        if mtype == "user":
            # A user-role message is either a real prompt or a tool result.
            if isinstance(content, str):
                text = content.strip()
                if text and not text.startswith("[The user attached an image"):
                    messages.append(_mk("user", text))
            elif isinstance(content, list):
                has_tool_result = any(
                    isinstance(b, dict) and b.get("type") == "tool_result" for b in content
                )
                if has_tool_result:
                    for b in content:
                        if isinstance(b, dict) and b.get("type") == "tool_result":
                            out = _stringify_result(b.get("content", ""))[:_OUTPUT_CAP]
                            success = not b.get("is_error", False)
                            messages.append(_mk(
                                "tool", ("Done" if success else "Failed"),
                                tool_output=out, success=success,
                            ))
                else:
                    text = " ".join(
                        b.get("text", "") for b in content
                        if isinstance(b, dict) and b.get("type") == "text"
                    ).strip()
                    if text and not text.startswith("[The user attached an image"):
                        messages.append(_mk("user", text))

        else:  # assistant
            if not isinstance(content, list):
                continue
            for b in content:
                if not isinstance(b, dict):
                    continue
                bt = b.get("type")
                if bt == "text":
                    text = _strip_speak(b.get("text", ""))
                    if text:
                        messages.append(_mk("assistant", text))
                elif bt == "tool_use":
                    messages.append(_mk(
                        "tool", f"Using {b.get('name', 'tool')}...",
                        tool_name=b.get("name"),
                    ))
                # thinking / other blocks are not shown

    return messages


def _mk(role, text, tool_name=None, tool_output=None, success=None) -> dict:
    return {
        "role": role,
        "text": text,
        "tool_name": tool_name,
        "tool_output": tool_output,
        "success": success,
    }
