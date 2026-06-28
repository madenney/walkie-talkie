"""Claude Agent SDK engine.

Wraps `ClaudeSDKClient` so the server runs the *real* Claude Code agent — its
actual tools and permission machinery — instead of a hand-rolled tool loop.

Key properties:
- **Subscription auth**: with ANTHROPIC_API_KEY unset (see main.py), the SDK
  authenticates through the logged-in `claude` CLI, drawing on the user's
  subscription rather than pay-as-you-go API credits.
- **Per-workspace**: one client per session, bound to the workspace `cwd`.
- **Voice persona**: the claude_code preset + an appended <speak> persona, so
  the existing TTS extraction keeps working.
- **Permission gate**: a PreToolUse hook intercepts the configured tools (e.g.
  Bash) and defers to an async callback (the phone approval round-trip). Returning
  a "deny" decision actually blocks execution.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, AsyncIterator, Awaitable, Callable

from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    HookMatcher,
    ResultMessage,
    StreamEvent,
    SystemMessage,
    TextBlock,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
)

from .system_prompt import VOICE_PERSONA

log = logging.getLogger(__name__)

# (tool_name, tool_input) -> True to allow, False to deny.
PermissionCallback = Callable[[str, dict[str, Any]], Awaitable[bool]]


def _stringify(content: Any) -> str:
    """Flatten a tool_result content (str or list of blocks) to text."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            if isinstance(block, dict):
                parts.append(block.get("text", "") or str(block.get("content", "")))
            else:
                parts.append(getattr(block, "text", "") or str(block))
        return "\n".join(p for p in parts if p)
    return str(content)


class AgentSession:
    """One Claude Agent SDK session bound to a single workspace directory."""

    def __init__(
        self,
        cwd: str,
        gated_tools: list[str],
        on_permission: PermissionCallback,
        model: str | None = None,
        resume: str | None = None,
    ) -> None:
        self._cwd = cwd
        self._gated = [t for t in gated_tools if t]
        self._on_permission = on_permission
        self._model = model
        self._resume = resume
        self._client: ClaudeSDKClient | None = None
        # The SDK's id for this conversation, learned from the init/result
        # messages. Persisted by the caller so the session can be resumed later.
        self.session_id: str | None = resume

    async def start(self) -> None:
        # Install ONE PreToolUse authority over *all* tools (matcher=None ==
        # match everything). Gated tools (e.g. Bash) round-trip to the phone for
        # approval; every other tool is allowed immediately. This explicit allow
        # is load-bearing: without it, non-gated write tools (Write/Edit/...)
        # fall through to the SDK's default permission path, which has no
        # responder in this headless setup and silently denies them
        # ("...but you haven't granted it yet"), so file edits never reach disk.
        hooks = {"PreToolUse": [HookMatcher(matcher=None, hooks=[self._pretool_hook])]}

        options = ClaudeAgentOptions(
            cwd=self._cwd,
            model=self._model,
            system_prompt={
                "type": "preset",
                "preset": "claude_code",
                "append": VOICE_PERSONA,
            },
            hooks=hooks,
            include_partial_messages=True,  # stream text deltas for low-latency TTS
            resume=self._resume,  # continue a prior conversation if we have its id
        )
        self._client = ClaudeSDKClient(options=options)
        await self._client.connect()
        log.info(
            "AgentSession connected (cwd=%s, gated=%s, resume=%s)",
            self._cwd, self._gated, self._resume,
        )

    @staticmethod
    def _decision(decision: str, reason: str) -> dict:
        return {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": decision,
                "permissionDecisionReason": reason,
            }
        }

    async def _pretool_hook(self, input_data, tool_use_id, context):
        """Fires before *every* tool. Gated tools (e.g. Bash) defer to the phone
        approval callback; all other tools are allowed straight through so they
        never stall on the SDK's unanswerable default permission prompt."""
        tool = input_data.get("tool_name", "?")
        tool_input = input_data.get("tool_input") or {}

        if tool not in self._gated:
            return self._decision("allow", "Tool not gated")

        try:
            approved = await self._on_permission(tool, tool_input)
        except Exception:
            log.exception("Permission callback failed; denying")
            approved = False

        if approved:
            return self._decision("allow", "Approved by user")
        return self._decision("deny", "Denied by user on phone")

    async def query(self, text: str) -> None:
        if self._client is None:
            raise RuntimeError("AgentSession not started")
        await self._client.query(text)

    async def stream(self) -> AsyncIterator[dict]:
        """Yield normalized events for the current turn:

        {"type": "text_delta", "text": ...}
        {"type": "tool_use", "tool_name": ..., "tool_id": ..., "input": ...}
        {"type": "tool_result", "tool_id": ..., "success": ..., "output": ...}
        {"type": "response_complete"}
        """
        assert self._client is not None
        async for message in self._client.receive_response():
            if isinstance(message, SystemMessage):
                # The init message carries the (possibly newly-minted) session id.
                if message.subtype == "init":
                    sid = (message.data or {}).get("session_id")
                    if sid:
                        self.session_id = sid

            elif isinstance(message, StreamEvent):
                ev = message.event
                if ev.get("type") == "content_block_delta":
                    delta = ev.get("delta", {})
                    if delta.get("type") == "text_delta" and delta.get("text"):
                        yield {"type": "text_delta", "text": delta["text"]}

            elif isinstance(message, AssistantMessage):
                # Text already streamed via StreamEvent; only surface tool calls here.
                for block in message.content:
                    if isinstance(block, ToolUseBlock):
                        yield {
                            "type": "tool_use",
                            "tool_name": block.name,
                            "tool_id": block.id,
                            "input": block.input,
                        }

            elif isinstance(message, UserMessage):
                content = message.content if isinstance(message.content, list) else []
                for block in content:
                    if isinstance(block, ToolResultBlock):
                        yield {
                            "type": "tool_result",
                            "tool_id": block.tool_use_id,
                            "success": not block.is_error,
                            "output": _stringify(block.content),
                        }

            elif isinstance(message, ResultMessage):
                if message.session_id:
                    self.session_id = message.session_id
                yield {"type": "response_complete", "session_id": self.session_id}

    async def interrupt(self) -> None:
        if self._client is not None:
            try:
                await self._client.interrupt()
            except Exception:
                log.exception("interrupt failed")

    async def close(self) -> None:
        if self._client is not None:
            try:
                # Bound it: the SDK subprocess teardown can otherwise hang for
                # ~10s, stalling cleanup on a dropped connection.
                await asyncio.wait_for(self._client.disconnect(), timeout=3)
            except (asyncio.TimeoutError, Exception):
                log.warning("agent disconnect slow/failed; abandoning")
            self._client = None
