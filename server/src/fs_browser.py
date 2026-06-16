"""Read-only file browser scoped to the configured workspace roots.

Backs the phone's "Files" explorer over plain HTTP — deliberately separate from
the realtime WebSocket, so a big file read can't stall audio. Every path is
resolved to its real location and checked to live inside one of the workspace
directories: no ``..`` traversal, no symlink escape, no arbitrary filesystem
read. It only ever *reads* — there's no write/delete surface here.
"""

from __future__ import annotations

import mimetypes
import os
from pathlib import Path

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse

from .config import Settings


def make_fs_router(settings: Settings) -> APIRouter:
    router = APIRouter(prefix="/fs", tags=["files"])

    def roots() -> list[tuple[str, Path]]:
        return [(ws.name, Path(ws.path).resolve()) for ws in settings.workspaces]

    def resolve_within_roots(path: str) -> Path:
        """Resolve ``path`` and confirm it sits inside a workspace root, else 403.
        ``resolve()`` collapses ``..`` and follows symlinks, so a link pointing
        out of the project can't be used to escape."""
        target = Path(path).expanduser().resolve()
        for _, root in roots():
            if target == root or root in target.parents:
                return target
        raise HTTPException(status_code=403, detail="Path is outside the project folders")

    @router.get("/roots")
    def list_roots():
        """The top-level entries: one per configured workspace."""
        return {"roots": [{"name": name, "path": str(root)} for name, root in roots()]}

    @router.get("/list")
    def list_dir(path: str = Query(...)):
        target = resolve_within_roots(path)
        if not target.is_dir():
            raise HTTPException(status_code=404, detail="Not a directory")
        entries = []
        try:
            for entry in os.scandir(target):
                # Skip dotfiles — rarely what you want to open while walking.
                if entry.name.startswith("."):
                    continue
                try:
                    is_dir = entry.is_dir()
                    st = entry.stat()
                except OSError:
                    continue
                entries.append({
                    "name": entry.name,
                    "path": str(Path(entry.path).resolve()),
                    "is_dir": is_dir,
                    "size": 0 if is_dir else st.st_size,
                    "mime": "" if is_dir else (mimetypes.guess_type(entry.name)[0] or "application/octet-stream"),
                    "mtime": st.st_mtime,
                })
        except OSError as e:
            raise HTTPException(status_code=500, detail=f"Couldn't read directory: {e}")
        # Folders first, then files; alphabetical within each.
        entries.sort(key=lambda e: (not e["is_dir"], e["name"].lower()))
        return {"path": str(target), "entries": entries}

    @router.get("/read")
    def read_file(path: str = Query(...)):
        target = resolve_within_roots(path)
        if not target.is_file():
            raise HTTPException(status_code=404, detail="Not a file")
        mime = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        return FileResponse(str(target), media_type=mime, filename=target.name)

    return router
