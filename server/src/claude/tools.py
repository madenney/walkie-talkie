"""Claude tool definitions (JSON schemas)."""

from __future__ import annotations

TOOLS = [
    {
        "name": "read_file",
        "description": "Read the contents of a file. Returns the file text. Paths are relative to the workspace root.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File path to read",
                },
                "offset": {
                    "type": "integer",
                    "description": "Line number to start reading from (1-based). Optional.",
                },
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of lines to read. Optional.",
                },
            },
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Create or overwrite a file with the given content. Paths are relative to the workspace root.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File path to write",
                },
                "content": {
                    "type": "string",
                    "description": "Content to write to the file",
                },
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "edit_file",
        "description": "Replace an exact text match in a file. The old_text must appear exactly once in the file.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "File path to edit",
                },
                "old_text": {
                    "type": "string",
                    "description": "Exact text to find and replace",
                },
                "new_text": {
                    "type": "string",
                    "description": "Replacement text",
                },
            },
            "required": ["path", "old_text", "new_text"],
        },
    },
    {
        "name": "bash",
        "description": "Run a shell command and return its output. Commands run in the workspace root directory.",
        "input_schema": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "Shell command to execute",
                },
                "timeout": {
                    "type": "integer",
                    "description": "Timeout in seconds (default 30)",
                },
            },
            "required": ["command"],
        },
    },
    {
        "name": "glob",
        "description": "Find files matching a glob pattern. Returns a list of matching file paths.",
        "input_schema": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Glob pattern (e.g. '**/*.py', 'src/*.ts')",
                },
                "path": {
                    "type": "string",
                    "description": "Directory to search in (default: workspace root)",
                },
            },
            "required": ["pattern"],
        },
    },
    {
        "name": "grep",
        "description": "Search file contents for a regex pattern. Returns matching lines with file paths and line numbers.",
        "input_schema": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Regex pattern to search for",
                },
                "path": {
                    "type": "string",
                    "description": "File or directory to search in (default: workspace root)",
                },
                "include": {
                    "type": "string",
                    "description": "Glob pattern to filter files (e.g. '*.py')",
                },
            },
            "required": ["pattern"],
        },
    },
    {
        "name": "list_directory",
        "description": "List the contents of a directory. Returns file and directory names.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Directory path (default: workspace root)",
                },
            },
            "required": [],
        },
    },
]
