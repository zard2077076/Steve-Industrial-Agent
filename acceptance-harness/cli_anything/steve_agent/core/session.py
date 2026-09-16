from __future__ import annotations

import json
import os
import secrets
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .policy import SESSION_SCHEMA, AcceptancePaths, PolicyError, validate_token


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _locked_save_json(path: Path, data: dict[str, Any]) -> None:
    path = path.expanduser().absolute()
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise PolicyError("SESSION_SYMLINK_REFUSED", str(path))
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    os.fchmod(descriptor, 0o600)
    handle = os.fdopen(descriptor, "r+", encoding="utf-8")
    with handle:
        locked = False
        try:
            import fcntl

            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
            locked = True
        except (ImportError, OSError):
            pass
        try:
            handle.seek(0)
            handle.truncate()
            json.dump(data, handle, indent=2, ensure_ascii=False, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        finally:
            if locked:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


@dataclass
class HarnessSession:
    schema: str
    session_id: str
    token: str
    repository_root: str
    game_root: str
    world_name: str
    endpoint_file: str
    created_at: str
    updated_at: str
    launch_pid: int | None = None
    client_pid: int | None = None
    client_log: str | None = None
    history: list[dict[str, Any]] = field(default_factory=list)

    @classmethod
    def create(cls, paths: AcceptancePaths) -> "HarnessSession":
        now = utc_now()
        return cls(
            schema=SESSION_SCHEMA,
            session_id=secrets.token_hex(16),
            token=secrets.token_hex(32),
            repository_root=str(paths.repository_root.resolve()),
            game_root=str(paths.game_root.resolve()),
            world_name=paths.world_name,
            endpoint_file=str(paths.endpoint_file.resolve()),
            created_at=now,
            updated_at=now,
        )

    def validate(self) -> None:
        if self.schema != SESSION_SCHEMA:
            raise PolicyError("SESSION_SCHEMA_MISMATCH", self.schema)
        validate_token(self.token)
        if self.world_name != "Steve Agent Mac Acceptance":
            raise PolicyError("CURRENT_WORLD_NOT_ALLOWLISTED", self.world_name)

    def record(self, command: str, result: str, **facts: Any) -> None:
        safe_facts = {key: value for key, value in facts.items() if key.lower() != "token"}
        self.history.append({
            "at": utc_now(),
            "command": command,
            "result": result,
            "facts": safe_facts,
        })
        self.history = self.history[-200:]
        self.updated_at = utc_now()

    def public(self) -> dict[str, Any]:
        data = asdict(self)
        data.pop("token", None)
        data["token_present"] = True
        return data


def save_session(path: Path, session: HarnessSession) -> None:
    session.validate()
    session.updated_at = utc_now()
    _locked_save_json(path, asdict(session))


def load_session(path: Path) -> HarnessSession:
    try:
        with open(path.expanduser().resolve(), "r", encoding="utf-8") as handle:
            raw = json.load(handle)
        session = HarnessSession(**raw)
    except (OSError, json.JSONDecodeError, TypeError) as exc:
        raise PolicyError("SESSION_UNREADABLE", str(path)) from exc
    session.validate()
    return session


def remove_authority(paths: AcceptancePaths) -> dict[str, Any]:
    removed: list[str] = []
    for candidate in (paths.endpoint_file, paths.token_file, paths.session_file):
        try:
            candidate.unlink()
            removed.append(str(candidate))
        except FileNotFoundError:
            pass
    return {"status": "CLOSED", "removed": removed}
