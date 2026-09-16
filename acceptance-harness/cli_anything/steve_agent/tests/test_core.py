from __future__ import annotations

import json
import inspect
import os
import socket
import stat
import struct
from types import SimpleNamespace
from pathlib import Path

import pytest

from cli_anything.steve_agent.core.backend import RealClientBackend
from cli_anything.steve_agent.core.policy import (
    ENDPOINT_SCHEMA,
    EXPECTED_WORLD,
    AcceptancePaths,
    PolicyError,
    validate_endpoint_path,
    validate_paths,
    validate_token,
)
from cli_anything.steve_agent.core.preview import png_info, publish_screen_bundle, verify_bundle
from cli_anything.steve_agent.core.protocol import BridgeClient, BridgeError, encode_request, load_endpoint
from cli_anything.steve_agent.core.scenarios import (
    C03_QUANTITY,
    C03_TARGET,
    METAL_PRESS_BOM,
    c03_plan,
    capability_plan,
    composite_plan,
    inspect_bot_checkpoints,
    metal_press_plan,
    multi_bot_plan,
    recover_stale_work,
    run_metal_press,
    scenario_catalog,
    summarize_bot_fleet,
    _composite_create_command,
    _seed_c03_fixture,
    _seed_composite_fixture,
    _confirm_placement_when_selected,
    _wait_player_horizontal,
    _wait_composite_fixture_markers,
    _wait_c03_fixture_markers,
)
from cli_anything.steve_agent.core.session import HarnessSession, load_session, save_session


def fixture_paths(tmp_path: Path, *, save: bool = True) -> AcceptancePaths:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / ".git").mkdir()
    script = repo / "scripts" / "new-player-acceptance-client.sh"
    script.parent.mkdir()
    script.write_text("#!/bin/sh\n", encoding="utf-8")
    script.chmod(0o755)
    game = tmp_path / "external" / "game"
    game.mkdir(parents=True)
    if save:
        world = game / "saves" / EXPECTED_WORLD
        world.mkdir(parents=True)
        (world / "level.dat").write_bytes(b"fixture")
    return AcceptancePaths(repo, game)


def write_png(path: Path, width: int = 8, height: int = 6) -> None:
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR"
                     + struct.pack(">II", width, height))


def test_policy_accepts_exact_external_world(tmp_path: Path) -> None:
    assert validate_paths(fixture_paths(tmp_path))["world_name"] == EXPECTED_WORLD


def test_policy_rejects_repository_game_root(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    inside = AcceptancePaths(paths.repository_root, paths.repository_root / "run")
    with pytest.raises(PolicyError, match="SOURCE_TREE_GAME_DIR_REFUSED"):
        validate_paths(inside, require_save=False)


def test_policy_rejects_git_ancestor(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    ancestor = tmp_path / "other"
    (ancestor / ".git").mkdir(parents=True)
    game = ancestor / "nested" / "game"
    game.mkdir(parents=True)
    with pytest.raises(PolicyError, match="SOURCE_TREE_GAME_DIR_REFUSED"):
        validate_paths(AcceptancePaths(paths.repository_root, game), require_save=False)


def test_policy_rejects_wrong_world(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    with pytest.raises(PolicyError, match="CURRENT_WORLD_NOT_ALLOWLISTED"):
        validate_paths(AcceptancePaths(paths.repository_root, paths.game_root, "Almost right"))


def test_policy_rejects_missing_save(tmp_path: Path) -> None:
    with pytest.raises(PolicyError, match="ACCEPTANCE_WORLD_MISSING"):
        validate_paths(fixture_paths(tmp_path, save=False))


def test_policy_rejects_missing_mandatory_script(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    paths.launch_script.unlink()
    with pytest.raises(PolicyError, match="MANDATORY_LAUNCH_SCRIPT_UNAVAILABLE"):
        validate_paths(paths)


def test_policy_endpoint_is_exact(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    assert validate_endpoint_path(paths, paths.endpoint_file) == paths.endpoint_file.resolve()
    with pytest.raises(PolicyError, match="ENDPOINT_OUTSIDE_ACCEPTANCE_ROOT"):
        validate_endpoint_path(paths, tmp_path / "bridge.json")


@pytest.mark.parametrize("token,valid", [("a" * 64, True), ("A" * 64, False), ("f" * 63, False)])
def test_token_shape(token: str, valid: bool) -> None:
    if valid:
        validate_token(token)
    else:
        with pytest.raises(PolicyError, match="INVALID_SESSION_TOKEN"):
            validate_token(token)


def test_launch_plan_uses_only_mandatory_script(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    session = HarnessSession.create(paths)
    plan = RealClientBackend(paths, paths.session_file, session).launch_plan()
    assert plan["command"] == [str(paths.launch_script)]
    assert plan["direct_run_client"] is False
    assert "runClient" not in " ".join(plan["command"])


def test_one_time_token_file_is_locked_and_not_part_of_launch_plan(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    session = HarnessSession.create(paths)
    backend = RealClientBackend(paths, paths.session_file, session)
    paths.state_root.mkdir(parents=True)
    backend._write_one_time_token()
    assert paths.token_file.read_text(encoding="ascii").strip() == session.token
    assert stat.S_IMODE(paths.token_file.stat().st_mode) == 0o600
    assert session.token not in json.dumps(backend.launch_plan())


def test_one_time_token_replaces_symlink_without_touching_target(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    session = HarnessSession.create(paths)
    backend = RealClientBackend(paths, paths.session_file, session)
    paths.state_root.mkdir(parents=True)
    victim = tmp_path / "victim"
    victim.write_text("unchanged", encoding="utf-8")
    paths.token_file.symlink_to(victim)
    backend._write_one_time_token()
    assert not paths.token_file.is_symlink()
    assert victim.read_text(encoding="utf-8") == "unchanged"
    assert stat.S_IMODE(paths.token_file.stat().st_mode) == 0o600


def test_session_token_is_unique_and_256_bit(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    first = HarnessSession.create(paths)
    second = HarnessSession.create(paths)
    assert first.token != second.token
    assert len(first.token) == 64


def test_session_locked_round_trip_and_permissions(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    session = HarnessSession.create(paths)
    save_session(paths.session_file, session)
    assert load_session(paths.session_file).session_id == session.session_id
    assert stat.S_IMODE(paths.session_file.stat().st_mode) & 0o077 == 0


def test_session_repeated_write_truncates_inside_lock(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    session = HarnessSession.create(paths)
    session.record("x" * 100, "OK", large="z" * 1000)
    save_session(paths.session_file, session)
    session.history.clear()
    save_session(paths.session_file, session)
    raw = json.loads(paths.session_file.read_text(encoding="utf-8"))
    assert raw["history"] == []
    assert "z" * 100 not in paths.session_file.read_text(encoding="utf-8")


def test_session_write_refuses_symlink_without_touching_target(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    paths.state_root.mkdir(parents=True)
    victim = tmp_path / "victim-session"
    victim.write_text("unchanged", encoding="utf-8")
    paths.session_file.symlink_to(victim)
    with pytest.raises(PolicyError, match="SESSION_SYMLINK_REFUSED"):
        save_session(paths.session_file, HarnessSession.create(paths))
    assert victim.read_text(encoding="utf-8") == "unchanged"


def test_session_history_never_records_token_fact(tmp_path: Path) -> None:
    session = HarnessSession.create(fixture_paths(tmp_path))
    session.record("client.status", "OK", token=session.token, screen="TitleScreen")
    assert "token" not in session.history[0]["facts"]
    assert "token" not in session.public()


def test_session_public_view_retains_only_token_presence(tmp_path: Path) -> None:
    session = HarnessSession.create(fixture_paths(tmp_path))
    public = session.public()
    assert public["token_present"] is True
    assert session.token not in json.dumps(public)


def test_session_rejects_schema_mismatch(tmp_path: Path) -> None:
    paths = fixture_paths(tmp_path)
    session = HarnessSession.create(paths)
    raw = session.__dict__.copy()
    raw["schema"] = "old"
    paths.session_file.parent.mkdir(parents=True)
    paths.session_file.write_text(json.dumps(raw), encoding="utf-8")
    with pytest.raises(PolicyError, match="SESSION_SCHEMA_MISMATCH"):
        load_session(paths.session_file)


def test_recover_closes_terminal_metal_press_without_cancelling() -> None:
    class FakeBackend:
        def __init__(self) -> None:
            self.responses = iter([
                {"screen_class": "MetalPressOrderScreen",
                 "domain_state": {"stage": "COMPLETED"}, "widgets": []},
                {"screen_class": None, "widgets": []},
                {"screen_class": "EngineerTerminalScreen",
                 "widgets": [{"id": "new_line", "active": True}]},
            ])
            self.calls: list[tuple[str, dict[str, object]]] = []

        def request(self, command: str, args: dict[str, object] | None = None) -> dict[str, object]:
            self.calls.append((command, args or {}))
            if command == "screen.inspect":
                return next(self.responses)
            return {"status": "OK"}

    backend = FakeBackend()
    result = recover_stale_work(backend, timeout=1)
    assert result["status"] == "READY"
    assert result["history"][0]["action"] == "close_terminal_order"
    assert not any(command == "screen.press" for command, _ in backend.calls)


def test_endpoint_accepts_loopback_without_token(tmp_path: Path) -> None:
    endpoint = tmp_path / "bridge.json"
    endpoint.write_text(json.dumps({
        "protocol_version": ENDPOINT_SCHEMA, "host": "127.0.0.1", "port": 32123,
    }), encoding="utf-8")
    assert load_endpoint(endpoint)["port"] == 32123


def test_endpoint_rejects_token_leak(tmp_path: Path) -> None:
    endpoint = tmp_path / "bridge.json"
    endpoint.write_text(json.dumps({
        "protocol_version": ENDPOINT_SCHEMA, "host": "127.0.0.1", "port": 32123,
        "token": "a" * 64,
    }), encoding="utf-8")
    with pytest.raises(BridgeError, match="BRIDGE_ENDPOINT_LEAKS_TOKEN"):
        load_endpoint(endpoint)


def test_wire_request_is_bounded_json_line() -> None:
    payload = encode_request("a" * 64, "client.status", {"probe": True})
    assert payload.endswith(b"\n")
    assert json.loads(payload)["command"] == "client.status"


def test_wire_rejects_newlines_and_oversize() -> None:
    with pytest.raises(PolicyError, match="BRIDGE_COMMAND_INVALID"):
        encode_request("a" * 64, "client\nstop")
    with pytest.raises(PolicyError, match="BRIDGE_ARGUMENTS_INVALID"):
        encode_request("a" * 64, "screen.text", {"value": "x" * 9000})


def test_bridge_absence_fails_loudly(tmp_path: Path) -> None:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    endpoint = tmp_path / "bridge.json"
    endpoint.write_text(json.dumps({
        "protocol_version": ENDPOINT_SCHEMA, "host": "127.0.0.1", "port": port,
    }), encoding="utf-8")
    with pytest.raises(BridgeError, match="BRIDGE_UNREACHABLE"):
        BridgeClient(endpoint, "a" * 64, timeout=0.1).request("client.status")


def test_png_validation(tmp_path: Path) -> None:
    path = tmp_path / "screen.png"
    write_png(path, 17, 9)
    assert png_info(path)["width"] == 17
    path.write_bytes(b"not png")
    with pytest.raises(ValueError, match="PNG_INVALID"):
        png_info(path)


def test_preview_bundle_uses_real_png_and_verifies_paths(tmp_path: Path) -> None:
    screenshot = tmp_path / "real.png"
    write_png(screenshot, 32, 18)
    manifest = publish_screen_bundle(
        screenshot, {"world_name": EXPECTED_WORLD, "screen_class": "GoalPickerScreen"},
        root_dir=tmp_path / "previews", command="test", force=True,
    )
    assert manifest["protocol_version"] == "preview-bundle/v1"
    result = verify_bundle(Path(manifest["_manifest_path"]))
    assert result["status"] == "PASS"
    assert result["artifact_count"] == 1


def test_scenario_catalog_keeps_unimplemented_scenarios_truthful() -> None:
    catalog = scenario_catalog()
    assert catalog["metalpress"]["status"] == "IMPLEMENTED"
    assert catalog["c03"]["status"] == "REAL_CLIENT_FIXTURE_READY"
    assert catalog["c03"]["target"] == C03_TARGET
    assert catalog["c03"]["quantity"] == C03_QUANTITY
    assert catalog["c06-c10"]["status"] == "RUNNER_READY_CLIENT_UNVERIFIED"
    assert catalog["composite"]["status"] == "REAL_CLIENT_FIXTURE_READY"
    assert catalog["multi-bot"]["status"] == "PLANNED_ON_SHARED_TRANSPORT"
    assert catalog["multi-bot"]["worker_range"] == [2, 5]


def test_metal_press_plan_contains_the_exact_reviewed_bom() -> None:
    plan = metal_press_plan((-600, 65, -4), (-600, 65, 0))
    assert plan["status"] == "PLANNED"
    assert [(row["slot"], row["item"], row["count"]) for row in plan["bom"]] == list(METAL_PRESS_BOM)
    assert "poll MetalPressOrderScreen.domain_state" in plan["actions"]
    assert "recover stale work through EngineerTerminal manage/cancel UI" in plan["actions"]


def test_metal_press_plan_does_not_claim_a_backend_run() -> None:
    plan = metal_press_plan((1, 2, 3), (4, 5, 6))
    assert plan["status"] == "PLANNED"
    assert "PASS" not in json.dumps(plan)


def test_composite_plan_keeps_fixture_and_two_sources_explicit() -> None:
    plan = composite_plan("steve_industrial:composite/01", (-606, 124, 0))
    assert plan["status"] == "PLANNED"
    assert plan["primary_source"] == {"x": -606, "y": 124, "z": -8}
    assert plan["secondary_source"] == {"x": -606, "y": 124, "z": -6}
    assert "wait for CompositeCompletionScreen.domain_state" in plan["actions"]
    assert "PASS" not in json.dumps(plan)


def test_composite_fixture_quotes_resource_id_without_weakening_freshness(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, dict[str, str]]] = []

    class FakeBackend:
        def request(self, command: str, args: dict[str, str]) -> dict[str, object]:
            calls.append((command, args))
            return {}

    monkeypatch.setattr(
        "cli_anything.steve_agent.core.scenarios.recover_stale_work", lambda backend: {},
    )
    monkeypatch.setattr(
        "cli_anything.steve_agent.core.scenarios._close_screen_if_open", lambda backend: None,
    )
    monkeypatch.setattr(
        "cli_anything.steve_agent.core.scenarios._teleport", lambda backend, x, y, z: None,
    )
    monkeypatch.setattr(
        "cli_anything.steve_agent.core.scenarios.secrets.token_hex",
        lambda byte_count: "0123456789abcdef0123456789abcdef",
    )
    observed: dict[str, object] = {}

    def record_wait(backend: object, order_type: str, origin: tuple[int, int, int],
                    *, nonce: str, timeout: float) -> dict[str, object]:
        observed.update(order_type=order_type, origin=origin, nonce=nonce, timeout=timeout)
        return {}

    monkeypatch.setattr(
        "cli_anything.steve_agent.core.scenarios._wait_composite_fixture_markers", record_wait,
    )
    _seed_composite_fixture(
        FakeBackend(), "steve_industrial:composite/01", (-606, 124, 0),
    )

    fixture_commands = [
        args["command"] for command, args in calls
        if command == "player.command" and "composite-fixture" in args["command"]
    ]
    assert fixture_commands == [
        'industrialagent acceptance composite-fixture '
        '"steve_industrial:composite/01" 0123456789abcdef0123456789abcdef',
    ]
    assert observed == {
        "order_type": "steve_industrial:composite/01",
        "origin": (-606, 124, 0),
        "nonce": "0123456789abcdef0123456789abcdef",
        "timeout": 10.0,
    }


def test_composite_create_command_quotes_and_validates_resource_id() -> None:
    assert _composite_create_command(
        "steve_industrial:composite/01",
        (-700, 124, -8), (-700, 124, 0), (-700, 124, -6),
    ) == (
        'steveagent composite create "steve_industrial:composite/01" '
        '-700 124 -8 -700 124 0 bots -700 124 -6'
    )
    with pytest.raises(ValueError, match="order_type"):
        _composite_create_command(
            'steve_industrial:composite/01" run bad',
            (-700, 124, -8), (-700, 124, 0), (-700, 124, -6),
        )


def test_composite_fixture_ready_requires_nonce_log_and_two_client_chests(
    tmp_path: Path,
) -> None:
    log_root = tmp_path / "state" / "logs"
    log_root.mkdir(parents=True)
    log_path = log_root / "client.log"
    nonce = "0123456789abcdef0123456789abcdef"
    log_path.write_text(
        "COMPOSITE_CLIENT_FIXTURE_PREPARED "
        "orderType=steve_industrial:composite/01 target=create:cogwheel quantity=1 "
        "origin=BlockPos{x=-700, y=124, z=0} "
        "primary=BlockPos{x=-700, y=124, z=-8} "
        "secondary=BlockPos{x=-700, y=124, z=-6} "
        f"nonce={nonce}\n",
        encoding="utf-8",
    )

    class FakeBackend:
        session = SimpleNamespace(client_log=str(log_path))
        paths = SimpleNamespace(state_root=tmp_path / "state")

        def request(self, command: str, args: dict[str, int]) -> dict[str, object]:
            assert command == "world.inspect"
            return {"blocks": [{"block": "minecraft:chest"}], "center": args}

    result = _wait_composite_fixture_markers(
        FakeBackend(), "steve_industrial:composite/01", (-700, 124, 0),
        nonce=nonce, timeout=0.1,
    )
    assert result["status"] == "COMPOSITE_FIXTURE_READY"
    assert result["nonce"] == nonce


def test_composite_fixture_refusal_marker_fails_closed(tmp_path: Path) -> None:
    log_root = tmp_path / "state" / "logs"
    log_root.mkdir(parents=True)
    log_path = log_root / "client.log"
    nonce = "0123456789abcdef0123456789abcdef"
    log_path.write_text(
        "COMPOSITE_CLIENT_FIXTURE_REFUSED "
        "orderType=steve_industrial:composite/01 "
        f"nonce={nonce} type=IllegalStateException detail=occupied\n",
        encoding="utf-8",
    )

    class FakeBackend:
        session = SimpleNamespace(client_log=str(log_path))
        paths = SimpleNamespace(state_root=tmp_path / "state")

        def request(self, command: str, args: dict[str, int]) -> dict[str, object]:
            raise AssertionError("a server refusal must stop before client world probes")

    with pytest.raises(BridgeError) as refusal:
        _wait_composite_fixture_markers(
            FakeBackend(), "steve_industrial:composite/01", (-700, 124, 0),
            nonce=nonce, timeout=0.1,
        )
    assert refusal.value.code == "COMPOSITE_FIXTURE_REFUSED"
    assert "IllegalStateException" in refusal.value.detail


def test_c03_plan_keeps_real_fixture_and_material_flow_explicit() -> None:
    plan = c03_plan((-606, 124, 0))
    assert plan["status"] == "PLANNED"
    assert plan["target"] == C03_TARGET
    assert plan["quantity"] == C03_QUANTITY
    assert plan["source"] == {"x": -606, "y": 124, "z": -8}
    assert plan["salvage"] == {"x": -606, "y": 124, "z": -10}
    assert "industrialagent acceptance create-fixture minecraft:gravel 3" in plan["actions"]
    assert "read-only polling; never click a refresh button in a loop" in plan["guards"]
    assert "PASS" not in json.dumps(plan)


def test_multi_bot_plan_is_observation_only_and_bounded() -> None:
    plan = multi_bot_plan((-606, 124, 0), worker_count=3)
    assert plan["status"] == "PLANNED"
    assert plan["worker_count"] == 3
    assert plan["expected_roles"] == ["logistics", "builder_inspector"]
    assert [row["name"] for row in plan["checkpoints"]] == [
        "source", "staging", "site", "return",
    ]
    assert "no worker spawn or direct service invocation from the harness" in plan["guards"]
    assert "PASS" not in json.dumps(plan)


@pytest.mark.parametrize(
    ("capability", "target", "roles"),
    [
        ("c06", "create:dough", [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "top_gearbox", "fan_drive_shaft", "encased_fan",
        ]),
        ("c07", "minecraft:stripped_oak_log", [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "top_gearbox", "horizontal_shaft", "mechanical_saw",
        ]),
        ("c08", "create:andesite_alloy", [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "large_cogwheel_input", "small_cogwheel",
            "large_cogwheel_output", "mechanical_mixer",
        ]),
        ("c09", "create:blaze_cake_base", [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "top_gearbox", "horizontal_shaft", "mechanical_press",
        ]),
        ("c10", "create:cogwheel", [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "top_gearbox", "horizontal_shaft", "deployer",
        ]),
    ],
)
def test_verified_capability_plan_exposes_survival_power_without_claiming_client_pass(
    capability: str, target: str, roles: list[str],
) -> None:
    plan = capability_plan(capability, (-606, 124, 0))
    assert plan["status"] == "PLANNED"
    assert plan["target"] == target
    assert plan["power"] == {
        "resource": "create:water_wheel",
        "roles": roles,
        "status": "VERIFIED_SURVIVAL",
        "blocker": None,
    }
    assert "require real output and balanced completion evidence before reporting PASS" in plan["actions"]
    assert "VERIFIED_SURVIVAL is eligibility for client testing, not a client PASS" in plan["guards"]
    assert "REVIEW_REQUIRED is a typed stop, not a failed completion to hide" not in plan["guards"]


@pytest.mark.parametrize("worker_count", [1, 6])
def test_multi_bot_plan_rejects_unbounded_worker_count(worker_count: int) -> None:
    with pytest.raises(ValueError, match="worker_count"):
        multi_bot_plan((0, 64, 0), worker_count=worker_count)


def test_bot_observation_is_read_only_and_detects_role_and_cell_collisions() -> None:
    probe = {
        "center": {"x": 0, "y": 64, "z": 0},
        "game_time": 42,
        "entities": [
            {"uuid": "a", "type": "steve_create_agent:construction_bot",
             "role": "logistics", "tags": ["project-a"], "x": 0.5, "y": 64.0, "z": 0.5},
            {"uuid": "b", "type": "steve_create_agent:construction_bot",
             "role": "builder_inspector", "tags": [], "x": 0.5, "y": 64.0, "z": 0.5},
            {"uuid": "item", "type": "minecraft:item", "item": {"item": "minecraft:stone", "count": 1}},
        ],
    }
    observed = summarize_bot_fleet(probe)
    assert observed["status"] == "OBSERVED"
    assert observed["active_bots"] == 2
    assert observed["by_role"] == {"logistics": 1, "builder_inspector": 1}
    assert observed["roles_observed"] == ["builder_inspector", "logistics"]
    assert observed["entity_uuids_unique"] is True
    assert observed["same_cell_collision"] is True
    assert observed["game_time"] == 42


def test_bot_observation_does_not_infer_workers_when_probe_is_empty() -> None:
    observed = summarize_bot_fleet({"entities": [], "game_time": 99})
    assert observed["status"] == "OBSERVED"
    assert observed["active_bots"] == 0
    assert observed["roles_observed"] == []
    assert observed["same_cell_collision"] is False


def test_bot_checkpoint_sampling_only_uses_world_inspect() -> None:
    class FakeBackend:
        def __init__(self) -> None:
            self.calls: list[tuple[str, dict[str, int]]] = []

        def request(self, command: str, args: dict[str, int]) -> dict[str, object]:
            self.calls.append((command, args))
            return {"center": args, "game_time": 1, "entities": []}

    backend = FakeBackend()
    result = inspect_bot_checkpoints(
        backend, [("site", (0, 64, 0)), ("return", (1, 64, 0))], radius=2,
    )
    assert result["status"] == "OBSERVED"
    assert result["checkpoint_count"] == 2
    assert result["max_active_bots"] == 0
    assert [command for command, _ in backend.calls] == ["world.inspect", "world.inspect"]
    assert all(args["radius"] == 2 for _, args in backend.calls)


def test_c03_fixture_runner_requires_fresh_server_marker() -> None:
    source = inspect.getsource(_wait_c03_fixture_markers)
    assert "C03_FIXTURE_NOT_PREPARED" in source
    assert "C03_CLIENT_FIXTURE_PREPARED" in source
    assert "planHash" in source
    assert "nonce" in source
    assert "minecraft:chest" in source
    assert "world.inspect" in source


def test_c03_fixture_ready_requires_nonce_log_and_two_client_chests(tmp_path: Path) -> None:
    log_root = tmp_path / "state" / "logs"
    log_root.mkdir(parents=True)
    log_path = log_root / "client.log"
    nonce = "0123456789abcdef0123456789abcdef"
    plan_hash = "a" * 64
    log_path.write_text(
        "C03_CLIENT_FIXTURE_PREPARED target=minecraft:gravel quantity=3 "
        "origin=BlockPos{x=-606, y=124, z=0} "
        "source=BlockPos{x=-606, y=124, z=-8} "
        "salvage=BlockPos{x=-606, y=124, z=-10} "
        f"planHash={plan_hash} nonce={nonce}\n",
        encoding="utf-8",
    )

    class FakeBackend:
        session = SimpleNamespace(client_log=str(log_path))
        paths = SimpleNamespace(state_root=tmp_path / "state")

        def request(self, command: str, args: dict[str, int]) -> dict[str, object]:
            assert command == "world.inspect"
            return {"blocks": [{"block": "minecraft:chest"}], "center": args}

    result = _wait_c03_fixture_markers(
        FakeBackend(), (-606, 124, 0), nonce=nonce, timeout=0.1,
    )
    assert result["status"] == "C03_FIXTURE_READY"
    assert result["planHash"] == plan_hash


def test_c03_placement_waits_for_production_anchor_without_refresh_spam() -> None:
    class FakeBackend:
        def __init__(self) -> None:
            self.calls = 0

        def request(self, command: str) -> dict[str, str]:
            assert command == "player.confirm_placement"
            self.calls += 1
            if self.calls < 3:
                raise BridgeError("PLACEMENT_ANCHOR_NOT_SELECTED")
            return {"status": "PLACEMENT_CONFIRM_REQUESTED"}

    backend = FakeBackend()
    result = _confirm_placement_when_selected(backend, timeout=1.0)
    assert result["status"] == "PLACEMENT_CONFIRM_REQUESTED"
    assert backend.calls == 3


def test_c03_waits_until_real_client_applies_teleport() -> None:
    class FakeBackend:
        def __init__(self) -> None:
            self.rows = iter((
                {"player_x": -609.5, "player_z": 0.5},
                {"player_x": -605.5, "player_z": 0.5},
            ))

        def request(self, command: str) -> dict[str, float]:
            assert command == "client.status"
            return next(self.rows)

    result = _wait_player_horizontal(FakeBackend(), -606, 0, timeout=1.0)
    assert result["player_x"] == -605.5


def test_stale_recovery_never_repeats_the_same_project_cancel() -> None:
    source = inspect.getsource(recover_stale_work)

    assert "attempted_project_cancels" in source
    assert "STALE_PROJECT_CANCEL_NOT_APPLIED" in source


def test_metal_press_runner_never_spins_on_refresh_clicks() -> None:
    source = inspect.getsource(run_metal_press)
    assert "screen.inspect" in source
    assert "refresh_order" not in source
    assert "time.sleep(0.5)" in source
