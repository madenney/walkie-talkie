"""Claude API streaming client with tool-use loop."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, AsyncIterator

import anthropic

from .system_prompt import SYSTEM_PROMPT, workspace_system_prompt
from .tools import TOOLS
from .tool_executor import ToolExecutor

if TYPE_CHECKING:
    from ..ws.session import Session

log = logging.getLogger(__name__)

# Max tool-use loop iterations to prevent infinite loops
MAX_TOOL_ROUNDS = 15


class ClaudeClient:
    """Manages Claude API calls with streaming and tool use."""

    def __init__(
        self,
        api_key: str,
        model: str,
        max_tokens: int,
        tool_executor: ToolExecutor,
    ) -> None:
        self.client = anthropic.AsyncAnthropic(api_key=api_key)
        self.model = model
        self.max_tokens = max_tokens
        self.executor = tool_executor

    async def stream_response(
        self, session: Session, executor: ToolExecutor | None = None
    ) -> AsyncIterator[dict]:
        """Stream a response from Claude, handling tool use automatically.

        Args:
            session: The current session with conversation history.
            executor: Optional per-session executor. Falls back to self.executor.

        Yields event dicts:
          {"type": "text_delta", "text": "..."}
          {"type": "text_done"}
          {"type": "tool_use", "tool_name": ..., "tool_id": ..., "input": ...}
          {"type": "tool_result", "tool_id": ..., "tool_name": ..., "success": ..., "output": ...}
          {"type": "response_complete"}
        """
        active_executor = executor or self.executor
        messages = list(session.conversation)
        system_prompt = workspace_system_prompt(session.workspace_name)

        for _round in range(MAX_TOOL_ROUNDS):
            if session.interrupted:
                return

            # Collect the full response (text blocks + tool_use blocks)
            assistant_content: list[dict] = []
            text_so_far = ""
            tool_uses: list[dict] = []

            async with self.client.messages.stream(
                model=self.model,
                max_tokens=self.max_tokens,
                system=system_prompt,
                messages=messages,
                tools=TOOLS,
            ) as stream:
                async for event in stream:
                    if session.interrupted:
                        return

                    if event.type == "content_block_start":
                        if event.content_block.type == "text":
                            text_so_far = ""
                        elif event.content_block.type == "tool_use":
                            tool_uses.append({
                                "id": event.content_block.id,
                                "name": event.content_block.name,
                                "input_json": "",
                            })

                    elif event.type == "content_block_delta":
                        if event.delta.type == "text_delta":
                            text_so_far += event.delta.text
                            yield {"type": "text_delta", "text": event.delta.text}
                        elif event.delta.type == "input_json_delta":
                            if tool_uses:
                                tool_uses[-1]["input_json"] += event.delta.partial_json

                    elif event.type == "content_block_stop":
                        pass

                # Get the final message
                final = await stream.get_final_message()

            # Build assistant_content from final message
            has_tool_use = False
            for block in final.content:
                if block.type == "text" and block.text:
                    assistant_content.append({
                        "type": "text",
                        "text": block.text,
                    })
                elif block.type == "tool_use":
                    has_tool_use = True
                    assistant_content.append({
                        "type": "tool_use",
                        "id": block.id,
                        "name": block.name,
                        "input": block.input,
                    })

            if text_so_far:
                yield {"type": "text_done"}

            if not has_tool_use:
                # No tools — we're done
                if assistant_content:
                    messages.append({"role": "assistant", "content": assistant_content})
                    session.conversation.append({"role": "assistant", "content": assistant_content})
                yield {"type": "response_complete"}
                return

            # Execute tools and continue the loop
            messages.append({"role": "assistant", "content": assistant_content})
            session.conversation.append({"role": "assistant", "content": assistant_content})

            tool_results = []
            for block in final.content:
                if block.type != "tool_use":
                    continue

                yield {
                    "type": "tool_use",
                    "tool_name": block.name,
                    "tool_id": block.id,
                    "input": block.input,
                }

                result = await active_executor.execute(block.name, block.input)

                yield {
                    "type": "tool_result",
                    "tool_id": block.id,
                    "tool_name": block.name,
                    "success": result["success"],
                    "output": result["output"],
                }

                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": result["output"],
                    "is_error": not result["success"],
                })

            messages.append({"role": "user", "content": tool_results})
            session.conversation.append({"role": "user", "content": tool_results})

            # Loop continues — Claude will respond to tool results

        # If we exhausted tool rounds
        yield {"type": "text_delta", "text": "\n\n(Reached maximum tool-use iterations)"}
        yield {"type": "text_done"}
        yield {"type": "response_complete"}
