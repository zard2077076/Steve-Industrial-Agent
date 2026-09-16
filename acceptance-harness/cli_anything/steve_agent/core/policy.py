from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path


EXPECTED_WORLD = "Steve Agent Mac Acceptance"
SESSION_SCHEMA = "steve-agent-client-acceptance-session/v1"
ENDPOINT_SCHEMA = "steve-agent-client-acceptance-bridge/v1"
TOKEN_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class PolicyError(RuntimeError):
    """A typed refusal that preserves the production acceptance boundary."""

    def __init__(self, code: str, detail: str = "") -> None:
        super().__init__(f"{code}{': ' + detail if detail else ''}")
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class AcceptancePaths:
    repository_root: Path
    game_root: Path
    world_name: str = EXPECTED_WORLD

    @property
    def launch_script(self) -> Path:
        return self.repository_root / "scripts" / "new-player-acceptance-client.sh"

    @property
    def state_root(self) -> Path:
        return self.game_root / ".steve-agent-harness"

    @property
    def session_file(self) -> Path:
        return self.state_root / "session.json"

    @property
    def endpoint_file(self) -> Path:
        return self.state_root / "bridge.json"

    @property
    def token_file(self) -> Path:
        return self.state_root / "bridge-token"

    @property
    def save_root(self) -> Path:
        return self.game_root / "saves" / self.world_name


def repository_root() -> Path:
    return Path(__file__).resolve().parents[4]


def default_game_root() -> Path:
    return Path(os.environ.get(
        "STEVE_ACCEPTANCE_ROOT", str(Path.home() / "SteveAgentPlayerAcceptance")
    )).expanduser().resolve()


def default_paths() -> AcceptancePaths:
    return AcceptancePaths(repository_root().resolve(), default_game_root())


def git_ancestor(path: Path) -> Path | None:
    current = path.expanduser().resolve()
    while True:
        if (current / ".git").exists():
            return current
        if current.parent == current:
            return None
        current = current.parent


def is_within(child: Path, parent: Path) -> bool:
    try:
        child.expanduser().resolve().relative_to(parent.expanduser().resolve())
        return True
    except ValueError:
        return False


def validate_token(token: str) -> None:
    if not TOKEN_PATTERN.fullmatch(token or ""):
        raise PolicyError("INVALID_SESSION_TOKEN", "expected 64 lowercase hexadecimal characters")


def validate_paths(paths: AcceptancePaths, *, require_save: bool = True) -> dict[str, object]:
    repo = paths.repository_root.resolve()
    game = paths.game_root.resolve()
    if not (repo / ".git").exists():
        raise PolicyError("REPOSITORY_ROOT_INVALID", str(repo))
    if paths.world_name != EXPECTED_WORLD:
        raise PolicyError("CURRENT_WORLD_NOT_ALLOWLISTED", paths.world_name)
    if is_within(game, repo):
        raise PolicyError("SOURCE_TREE_GAME_DIR_REFUSED", str(game))
    ancestor = git_ancestor(game)
    if ancestor is not None:
        raise PolicyError("SOURCE_TREE_GAME_DIR_REFUSED", str(ancestor))
    script = paths.launch_script
    if not script.is_file() or not os.access(script, os.X_OK):
        raise PolicyError("MANDATORY_LAUNCH_SCRIPT_UNAVAILABLE", str(script))
    if require_save and not (paths.save_root / "level.dat").is_file():
        raise PolicyError("ACCEPTANCE_WORLD_MISSING", str(paths.save_root))
    if not is_within(paths.endpoint_file, game):
        raise PolicyError("ENDPOINT_OUTSIDE_ACCEPTANCE_ROOT", str(paths.endpoint_file))
    if not is_within(paths.token_file, game):
        raise PolicyError("TOKEN_FILE_OUTSIDE_ACCEPTANCE_ROOT", str(paths.token_file))
    return {
        "status": "OK",
        "repository_root": str(repo),
        "game_root": str(game),
        "world_name": paths.world_name,
        "world_save": str(paths.save_root),
        "world_save_present": (paths.save_root / "level.dat").is_file(),
        "launch_script": str(script),
        "launch_policy": "MANDATORY_SCRIPT_ONLY",
        "source_tree_guard": "PRESERVED",
    }


def validate_endpoint_path(paths: AcceptancePaths, endpoint: Path) -> Path:
    resolved = endpoint.expanduser().resolve()
    if resolved != paths.endpoint_file.resolve() or not is_within(resolved, paths.game_root):
        raise PolicyError("ENDPOINT_OUTSIDE_ACCEPTANCE_ROOT", str(resolved))
    return resolved
