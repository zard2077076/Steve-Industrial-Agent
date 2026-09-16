from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
import zipfile
from pathlib import Path

import pytest

from cli_anything.steve_agent.core.backend import (
    RealClientBackend,
    crash_report_snapshot,
    read_new_crash_reports,
    terminate_failed_launch,
)
from cli_anything.steve_agent.core.policy import EXPECTED_WORLD, default_paths
from cli_anything.steve_agent.core.preview import publish_screen_bundle, verify_bundle
from cli_anything.steve_agent.core.session import HarnessSession, remove_authority, save_session


def _resolve_cli(name: str) -> list[str]:
    force = os.environ.get("CLI_ANYTHING_FORCE_INSTALLED", "").strip() == "1"
    path = shutil.which(name)
    if path:
        print(f"[_resolve_cli] Using installed command: {path}")
        return [path]
    if force:
        raise RuntimeError(f"{name} not found in PATH. Install with: pip install -e .")
    return ["python3", "-m", "cli_anything.steve_agent"]


CLI = _resolve_cli("cli-anything-steve-agent")
REPO = Path(__file__).resolve().parents[4]
HARNESS = REPO / "acceptance-harness"
JAR = REPO / "forge-create-1.20.1" / "build" / "libs" / "steve-industrial-agent-0.1.0-alpha.1.jar"


def run_cli(args: list[str], *, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(CLI + args, cwd=str(cwd) if cwd else None,
                          text=True, capture_output=True, check=True)


def test_installed_help_from_outside_repository(tmp_path: Path) -> None:
    result = run_cli(["--help"], cwd=tmp_path)
    assert "real Forge" in result.stdout


def test_installed_doctor_json() -> None:
    data = json.loads(run_cli(["--json", "doctor"]).stdout)
    assert data["status"] == "OK"
    assert data["world_name"] == EXPECTED_WORLD
    assert data["launch_policy"] == "MANDATORY_SCRIPT_ONLY"


def test_installed_session_create_json(tmp_path: Path) -> None:
    session_file = tmp_path / "session.json"
    data = json.loads(run_cli([
        "--json", "--session-file", str(session_file), "session", "create",
    ]).stdout)
    assert data["status"] == "CREATED"
    assert data["token_present"] is True
    assert "token" not in data
    assert "token" in json.loads(session_file.read_text(encoding="utf-8"))


def test_steve_agent_test_alias_is_installed() -> None:
    alias = shutil.which("steve-agent-test")
    assert alias is not None
    result = subprocess.run([alias, "--help"], text=True, capture_output=True, check=True)
    assert "real Forge" in result.stdout


@pytest.mark.parametrize(("scenario", "target"), [
    ("c06", "create:dough"), ("c07", "minecraft:stripped_oak_log"),
    ("c08", "create:andesite_alloy"), ("c09", "create:blaze_cake_base"),
    ("c10", "create:cogwheel"),
])
def test_installed_create_scenario_dry_run(tmp_path: Path, scenario: str, target: str) -> None:
    session_file = tmp_path / "unused-session.json"
    data = json.loads(run_cli([
        "--json", "--dry-run", "--session-file", str(session_file),
        "scenario", "run", scenario, "--prepare-fixture",
    ], cwd=tmp_path).stdout)
    assert data["target"] == target
    assert data["quantity"] == 1
    assert data["prepare_fixture"] is True
    assert data.get("status") != "PASS"
    assert not session_file.exists(), "dry-run must not create client authority"


def test_dry_run_launch_uses_mandatory_script(tmp_path: Path) -> None:
    session_file = tmp_path / "session.json"
    data = json.loads(run_cli([
        "--json", "--dry-run", "--session-file", str(session_file),
        "client", "launch", "--no-wait",
    ]).stdout)
    assert data["direct_run_client"] is False
    assert data["command"][0].endswith("scripts/new-player-acceptance-client.sh")
    assert "runClient" not in " ".join(data["command"])


def test_production_jar_has_no_acceptance_bridge_metadata() -> None:
    assert JAR.is_file(), f"Production JAR missing: {JAR}"
    with zipfile.ZipFile(JAR) as archive:
        mods = archive.read("META-INF/mods.toml").decode("utf-8")
    assert "steve_acceptance_bridge" not in mods


def test_production_jar_has_no_acceptance_bridge_classes() -> None:
    with zipfile.ZipFile(JAR) as archive:
        names = archive.namelist()
    assert not any(name.startswith("dev/stevecreate/agent/acceptance/client/") for name in names)


@pytest.fixture(scope="module")
def real_client() -> RealClientBackend:
    paths = default_paths()
    before_crashes = crash_report_snapshot(paths.game_root)
    remove_authority(paths)
    session = HarnessSession.create(paths)
    save_session(paths.session_file, session)
    backend = RealClientBackend(paths, paths.session_file, session)
    backend.launch(quick_play=True)
    try:
        status = backend.wait_ready(300, require_world=True)
        assert status["world_name"] == EXPECTED_WORLD
        assert status["player_ready"] is True
        yield backend
        stopped = backend.stop(90)
        assert stopped["process_exited"] is True
    except BaseException:
        try:
            backend.stop(30)
        except Exception:
            terminate_failed_launch(session)
        raise
    finally:
        assert read_new_crash_reports(paths.game_root, before_crashes) == []
        remove_authority(paths)


def test_real_client_exact_world(real_client: RealClientBackend) -> None:
    status = real_client.request("client.status")
    assert status["world_name"] == EXPECTED_WORLD
    assert status["singleplayer"] is True
    assert status["player_ready"] is True


def test_real_terminal_and_packet_backed_derived_search(real_client: RealClientBackend) -> None:
    command = "item replace entity @s weapon.mainhand with steve_create_agent:engineer_terminal"
    real_client.request("player.command", {"command": command})
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if real_client.request("client.status").get("main_hand_item") == \
                "steve_create_agent:engineer_terminal":
            break
        time.sleep(0.1)
    else:
        raise AssertionError("engineer terminal never reached the real main hand")
    real_client.request("player.use_main_hand")
    wait_screen(real_client, "EngineerTerminalScreen")
    inspected = ensure_terminal_can_start_new_line(real_client)
    real_client.request("screen.press", {"widget_id": "new_line"})
    wait_screen(real_client, "GoalPickerScreen")
    real_client.request("screen.text", {"widget_id": "search", "value": "concrete"})
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        inspected = real_client.request("screen.inspect")
        rows = inspected.get("target_rows", [])
        if any(row["target"] == "minecraft:blue_concrete" and row["visible"] for row in rows):
            break
        time.sleep(0.1)
    else:
        raise AssertionError("minecraft:blue_concrete did not become visible through the real search packet")


def test_real_native_screenshot_preview_bundle(real_client: RealClientBackend) -> None:
    filename = f"steve-agent-e2e-{int(time.time())}.png"
    capture = real_client.request("screenshot.capture", {"filename": filename})
    status = real_client.request("client.status")
    manifest = publish_screen_bundle(
        Path(capture["path"]), status,
        root_dir=real_client.paths.state_root / "previews",
        command="pytest real-client preview", force=True,
    )
    verification = verify_bundle(Path(manifest["_manifest_path"]))
    assert verification["status"] == "PASS"
    print(f"\n  Minecraft PNG: {capture['path']} ({capture['bytes']:,} bytes)")
    print(f"  Preview bundle: {manifest['_bundle_dir']}")


def wait_screen(backend: RealClientBackend, name: str, timeout: float = 15.0) -> dict:
    deadline = time.monotonic() + timeout
    last = {}
    while time.monotonic() < deadline:
        last = backend.request("screen.inspect")
        if last.get("screen_class") == name:
            return last
        time.sleep(0.1)
    raise AssertionError(f"screen timeout expected={name} actual={last.get('screen_class')}")


def ensure_terminal_can_start_new_line(backend: RealClientBackend) -> dict:
    """Cancel stale disposable-world work through the same visible player controls."""
    inspected = backend.request("screen.inspect")
    if widget_active(inspected, "new_line"):
        return inspected
    assert widget_active(inspected, "manage_project"), json.dumps(inspected, ensure_ascii=False)
    backend.request("screen.press", {"widget_id": "manage_project"})
    progress = wait_screen(backend, "ConstructionProgressScreen")
    assert widget_active(progress, "cancel_project"), json.dumps(progress, ensure_ascii=False)
    backend.request("screen.press", {"widget_id": "cancel_project"})
    backend.request("screen.close")

    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        backend.request("player.use_main_hand")
        terminal = wait_screen(backend, "EngineerTerminalScreen", 3)
        if widget_active(terminal, "new_line"):
            return terminal
        backend.request("screen.close")
        time.sleep(0.2)
    raise AssertionError("stale disposable acceptance project did not cancel through its real UI")


def widget_active(inspected: dict, widget_id: str) -> bool:
    return any(widget.get("id") == widget_id and widget.get("active") is True
               for widget in inspected.get("widgets", []))
