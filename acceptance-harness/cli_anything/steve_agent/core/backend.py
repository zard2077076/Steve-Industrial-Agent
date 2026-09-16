from __future__ import annotations

import json
import os
import signal
import subprocess
import time
from pathlib import Path
from typing import Any

from .policy import AcceptancePaths, PolicyError, validate_paths
from .protocol import BridgeClient, BridgeError, load_endpoint
from .session import HarnessSession, save_session, utc_now


class RealClientBackend:
    """Invokes the mandatory launcher and the authenticated real-client bridge."""

    def __init__(self, paths: AcceptancePaths, session_file: Path, session: HarnessSession) -> None:
        self.paths = paths
        self.session_file = session_file.expanduser().resolve()
        self.session = session

    def launch_plan(self, *, quick_play: bool = True) -> dict[str, Any]:
        validate_paths(self.paths, require_save=quick_play)
        return {
            "status": "PLANNED",
            "backend": "REAL_FORGE_CLIENT",
            "command": [str(self.paths.launch_script)],
            "direct_run_client": False,
            "quick_play": self.paths.world_name if quick_play else None,
            "game_root": str(self.paths.game_root),
            "endpoint_file": str(self.paths.endpoint_file),
            "bridge_source_set": "clientAcceptanceHarness",
        }

    def launch(self, *, quick_play: bool = True) -> dict[str, Any]:
        plan = self.launch_plan(quick_play=quick_play)
        if self.session.launch_pid and process_alive(self.session.launch_pid):
            raise PolicyError("CLIENT_ALREADY_RUNNING", str(self.session.launch_pid))
        self.paths.state_root.mkdir(parents=True, exist_ok=True)
        try:
            self.paths.endpoint_file.unlink()
        except FileNotFoundError:
            pass
        self._write_one_time_token()
        log_root = self.paths.state_root / "logs"
        log_root.mkdir(parents=True, exist_ok=True)
        log_path = log_root / f"client-{time.strftime('%Y%m%d-%H%M%S')}.log"
        environment = os.environ.copy()
        environment.update({
            "STEVE_ACCEPTANCE_ROOT": str(self.paths.game_root),
            "STEVE_ACCEPTANCE_HARNESS": "1",
            "STEVE_ACCEPTANCE_TOKEN_FILE": str(self.paths.token_file),
            "STEVE_ACCEPTANCE_ENDPOINT": str(self.paths.endpoint_file),
            "STEVE_ACCEPTANCE_PORT": "0",
            "STEVE_ACCEPTANCE_QUICK_PLAY_WORLD": self.paths.world_name if quick_play else "",
        })
        log_handle = open(log_path, "ab", buffering=0)
        try:
            try:
                process = subprocess.Popen(
                    [str(self.paths.launch_script)],
                    cwd=str(self.paths.repository_root),
                    env=environment,
                    stdin=subprocess.DEVNULL,
                    stdout=log_handle,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
            except BaseException:
                self.paths.token_file.unlink(missing_ok=True)
                raise
        finally:
            log_handle.close()
        self.session.launch_pid = process.pid
        self.session.client_log = str(log_path)
        self.session.record("client.launch", "STARTED", quick_play=quick_play,
                            launch_pid=process.pid, client_log=str(log_path))
        save_session(self.session_file, self.session)
        return {**plan, "status": "STARTED", "launch_pid": process.pid,
                "client_log": str(log_path), "started_at": utc_now()}

    def _write_one_time_token(self) -> None:
        """Pass authority without exposing it in ps output or the endpoint file."""
        self.paths.token_file.unlink(missing_ok=True)
        flags = (os.O_WRONLY | os.O_CREAT | os.O_EXCL
                 | getattr(os, "O_NOFOLLOW", 0))
        descriptor = os.open(self.paths.token_file, flags, 0o600)
        try:
            os.fchmod(descriptor, 0o600)
            os.write(descriptor, (self.session.token + "\n").encode("ascii"))
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def wait_ready(self, timeout: float = 180.0, *, require_world: bool = True) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        last_error = "BRIDGE_ENDPOINT_UNAVAILABLE"
        while time.monotonic() < deadline:
            if self.session.launch_pid and not process_alive(self.session.launch_pid):
                raise BridgeError("CLIENT_EXITED_BEFORE_READY", self.session.client_log or "")
            if self.paths.endpoint_file.is_file():
                try:
                    endpoint = load_endpoint(self.paths.endpoint_file)
                    self.session.client_pid = endpoint.get("pid")
                    result = self.request("client.status")
                    screen_ready = result.get("screen_class") not in {
                        "ReceivingLevelScreen", "LoadingOverlay"
                    }
                    if (not require_world
                            or (result.get("world_name") == self.paths.world_name
                                and result.get("player_ready") is True
                                and screen_ready)):
                        self.session.record("client.wait", "READY",
                                            screen=result.get("screen_class"),
                                            world_name=result.get("world_name"))
                        save_session(self.session_file, self.session)
                        return result
                    last_error = ("PLAYER_NOT_READY"
                                  if result.get("world_name") == self.paths.world_name
                                  else "ACCEPTANCE_WORLD_NOT_LOADED")
                except BridgeError as exc:
                    last_error = exc.code
            time.sleep(0.25)
        raise BridgeError("CLIENT_READY_TIMEOUT", last_error)

    def request(self, command: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        result = BridgeClient(self.paths.endpoint_file, self.session.token).request(command, args)
        self.session.record(command, "OK")
        save_session(self.session_file, self.session)
        return result

    def stop(self, timeout: float = 60.0) -> dict[str, Any]:
        result = self.request("client.stop")
        deadline = time.monotonic() + timeout
        while (self.session.launch_pid and process_alive(self.session.launch_pid)
               and time.monotonic() < deadline):
            time.sleep(0.25)
        if self.session.launch_pid and process_alive(self.session.launch_pid):
            raise BridgeError("CLIENT_STOP_TIMEOUT", str(self.session.launch_pid))
        self.session.record("client.stop", "STOPPED")
        save_session(self.session_file, self.session)
        return {**result, "process_exited": True, "launch_pid": self.session.launch_pid}


def process_alive(pid: int) -> bool:
    try:
        reaped, _ = os.waitpid(int(pid), os.WNOHANG)
        if reaped == int(pid):
            return False
    except (ChildProcessError, OSError, ValueError):
        # A launcher resumed by another CLI process is no longer our child.
        pass
    try:
        os.kill(int(pid), 0)
        return True
    except (OSError, ValueError):
        return False


def terminate_failed_launch(session: HarnessSession, timeout: float = 10.0) -> None:
    """Best-effort cleanup used only when a newly launched disposable client never attached."""
    if not session.launch_pid or not process_alive(session.launch_pid):
        return
    try:
        os.killpg(session.launch_pid, signal.SIGTERM)
    except OSError:
        return
    deadline = time.monotonic() + timeout
    while process_alive(session.launch_pid) and time.monotonic() < deadline:
        time.sleep(0.1)


def read_new_crash_reports(game_root: Path, before: set[str]) -> list[str]:
    crash_root = game_root / "crash-reports"
    current = {str(path.resolve()) for path in crash_root.glob("*.txt")} if crash_root.exists() else set()
    return sorted(current - before)


def crash_report_snapshot(game_root: Path) -> set[str]:
    crash_root = game_root / "crash-reports"
    return {str(path.resolve()) for path in crash_root.glob("*.txt")} if crash_root.exists() else set()
