"""Scripted runner tests, deliberately not labeled real-client acceptance."""
from types import SimpleNamespace

import pytest

from cli_anything.steve_agent.core import scenarios as s
from cli_anything.steve_agent.core.protocol import BridgeError


ORIGIN = (-606, 124, 0)


def screen(name, stage="", **state):
    return {"screen_class": name, "domain_state": {"project": {"stage": stage}, **state}}


@pytest.fixture
def scripted(monkeypatch):
    """Drive the actual runner; only the absent client/clock are substituted."""
    now = [0.0]
    monkeypatch.setattr(s.time, "monotonic", lambda: now[0])
    monkeypatch.setattr(s.time, "sleep", lambda seconds: now.__setitem__(0, now[0] + seconds))
    for name in ("recover_stale_work", "_close_screen_if_open", "_wait_player_horizontal",
                 "_confirm_placement_when_selected", "_wait_for_predicate"):
        monkeypatch.setattr(s, name, lambda *a, **kw: {})

    class Backend:
        def __init__(self, scenario="c06"):
            self.plan = s.c03_plan(ORIGIN) if scenario == "c03" else s.capability_plan(scenario, ORIGIN)
            self.calls = []
            self.anchor = ORIGIN
            self.goal = {"target": self.plan["target"], "available": True,
                         "capability": "create:milling" if scenario == "c03" else
                         "create:splashing" if scenario == "c06" else f'create:{self.plan["stage"]}'}
            self.report = {"balanced": True, "duplicateWithdrawals": 0, "duplicateReturns": 0,
                           "unaccountedItems": 0, "privateItemsTouched": 0,
                           "observedOutput": self.plan["quantity"], "expectedOutput": self.plan["quantity"]}
            self.project = {"projectId": "this-project", "target": self.plan["target"],
                            "quantity": self.plan["quantity"], "stage": "COMPLETED"}
            self.rows = [
                {"screen_class": "GoalPickerScreen", "target_rows": [self.goal]},
                screen("ClearingReadyScreen", "CLEARING"),
                screen("MaterialSourceScreen", "MATERIAL_SOURCE_SELECTION", sourceCount=0),
                screen("MaterialSourceScreen", "MATERIAL_SOURCE_SELECTION", sourceCount=0),
                screen("MaterialSourceScreen", "MATERIAL_SOURCE_SELECTION", sourceCount=1, sufficient=True),
                screen("MaterialSourceScreen", "MATERIAL_RESERVED", sourceCount=1, sufficient=True),
                screen("ConstructionProgressScreen", "BUILDING"),
                screen("CompletionReportScreen", project=self.project, report=self.report),
            ]

        def request(self, command, args=None):
            self.calls.append((command, args))
            if command == "screen.inspect":
                assert self.rows, "runner read past scripted evidence"
                return self.rows.pop(0)
            if command == "world.inspect":
                return {"blocks": [{"block": "minecraft:chest"}], "entities": []}
            if command == "screen.select_target":
                return {"status": "TARGET_SELECTED"}
            return {}

    def wait_screen(backend, name, **kw):
        return screen(name, projectId="this-project", anchorX=backend.anchor[0],
                      anchorY=backend.anchor[1], anchorZ=backend.anchor[2])

    monkeypatch.setattr(s, "_wait_screen", wait_screen)
    return Backend


@pytest.mark.parametrize("scenario", ["c03", "c06", "c07", "c08", "c09", "c10"])
def test_shared_runner_uses_exact_goal_quantity_and_confirms_only_once_per_phase(scripted, scenario):
    backend = scripted(scenario)
    result = (s.run_c03(backend, ORIGIN, capture=False) if scenario == "c03" else
              s.run_create_player_flow(backend, ORIGIN, scenario=scenario, capture=False))
    assert result["status"] == "PASS"  # Only the scripted state machine passed.
    assert (result["scenario"], result["target"], result["quantity"]) == (
        scenario, backend.plan["target"], backend.plan["quantity"])
    assert ("screen.text", {"widget_id": "quantity", "value": str(backend.plan["quantity"])}) in backend.calls
    assert backend.calls.count(("screen.press", {"widget_id": "confirm_materials"})) == 2
    assert backend.calls.count(("player.bind_material_source", {"x": -606, "y": 124, "z": -8, "face": "up"})) == 1
    assert all((args or {}).get("widget_id") not in {"refresh_order", "cancel_project"}
               for _, args in backend.calls)


@pytest.mark.parametrize(("change", "code"), [
    ("anchor", "ANCHOR_MISMATCH"), ("capability", "CAPABILITY_MISMATCH"),
    ("insufficient", "MATERIALS_INSUFFICIENT"), ("bind_timeout", "SOURCE_BIND_TIMEOUT"),
    ("paused", "CONSTRUCTION_PAUSED"), ("regressed", "CONSTRUCTION_REGRESSED_TO_MATERIAL_SELECTION"),
    ("screen_lost", "SCREEN_LOST"), ("wrong_project", "COMPLETION_PROJECT_MISMATCH"),
    ("wrong_target", "COMPLETION_PROJECT_MISMATCH"), ("incomplete", "COMPLETION_PROJECT_MISMATCH"),
    ("output", "COMPLETION_ASSERTION_FAILED"), ("duplicate", "COMPLETION_ASSERTION_FAILED"),
    ("missing_counter", "COMPLETION_ASSERTION_FAILED"), ("bool_counter", "COMPLETION_ASSERTION_FAILED"),
])
def test_failure_is_typed_and_scene_is_retained(scripted, change, code):
    backend = scripted()
    if change == "anchor": backend.anchor = (0, 124, 0)
    elif change == "capability": backend.goal["capability"] = "create:mixing"
    elif change == "insufficient": backend.rows[4]["domain_state"]["sufficient"] = False
    elif change == "bind_timeout": backend.rows[3:4] = [screen("MaterialSourceScreen", sourceCount=0) for _ in range(110)]
    elif change == "paused": backend.rows[6] = screen("ConstructionProgressScreen", "BUILDING", paused=True)
    elif change == "regressed": backend.rows[6] = screen("MaterialSourceScreen", "MATERIAL_SOURCE_SELECTION", sourceCount=1, sufficient=True)
    elif change == "screen_lost": backend.rows[6] = screen("PauseScreen")
    elif change == "wrong_project": backend.project["projectId"] = "old-project"
    elif change == "wrong_target": backend.project["target"] = "minecraft:gravel"
    elif change == "incomplete": backend.project["stage"] = "BUILDING"
    elif change == "output": backend.report["observedOutput"] = 2
    elif change == "duplicate": backend.report["duplicateWithdrawals"] = 1
    elif change == "missing_counter": del backend.report["privateItemsTouched"]
    elif change == "bool_counter": backend.report["duplicateReturns"] = False
    with pytest.raises(BridgeError) as failure:
        s.run_create_player_flow(backend, ORIGIN, scenario="c06", capture=False)
    assert failure.value.code == f"C06_{code}"
    assert not any((args or {}).get("widget_id") == "cancel_project" for _, args in backend.calls)


def test_async_screenless_clearing_reopens_terminal_once(scripted):
    backend = scripted()
    continuation = {"screen_class": "EngineerTerminalScreen", "widgets": [{"id": "continue_project", "active": True}]}
    # Stay screenless beyond the one-second deadline, not exactly on a float boundary.
    backend.rows[2:2] = [screen(None) for _ in range(4)] + [continuation]
    s.run_create_player_flow(backend, ORIGIN, scenario="c06", capture=False)
    assert backend.calls.count(("player.use_main_hand", None)) == 2
    assert backend.calls.count(("screen.press", {"widget_id": "continue_project"})) == 1


def test_fixture_command_uses_same_target_quantity_origin_and_fresh_nonce(scripted, monkeypatch):
    backend = scripted("c08")
    observed = []
    monkeypatch.setattr(s.secrets, "token_hex", lambda n: "a" * 32)
    monkeypatch.setattr(s, "_wait_c03_fixture_markers", lambda *args, **kw: observed.append(kw))
    s._seed_c03_fixture(backend, ORIGIN, target="create:andesite_alloy", quantity=1)
    commands = [args["command"] for command, args in backend.calls if command == "player.command"]
    assert commands[-1] == 'industrialagent acceptance create-fixture "create:andesite_alloy" 1 ' + "a" * 32
    assert observed == [{"target": "create:andesite_alloy", "quantity": 1, "nonce": "a" * 32, "timeout": 10.0}]


@pytest.mark.parametrize("field", ["target", "quantity", "nonce"])
def test_other_fixture_marker_never_satisfies_this_run(tmp_path, field):
    logs = tmp_path / "logs"
    logs.mkdir()
    log = logs / "client.log"
    row = {"target": "create:dough", "quantity": 1, "nonce": "a" * 32}
    row[field] = {"target": "minecraft:gravel", "quantity": 3, "nonce": "b" * 32}[field]
    log.write_text(f'C03_CLIENT_FIXTURE_PREPARED target={row["target"]} quantity={row["quantity"]} '
                   'source=BlockPos{x=-606, y=124, z=-8} salvage=BlockPos{x=-606, y=124, z=-10} '
                   f'planHash={"c" * 64} nonce={row["nonce"]}\n')
    backend = SimpleNamespace(session=SimpleNamespace(client_log=str(log)), paths=SimpleNamespace(state_root=tmp_path),
                              request=lambda *a, **kw: {"blocks": [{"block": "minecraft:chest"}]})
    with pytest.raises(BridgeError, match="C03_FIXTURE_NOT_PREPARED"):
        s._wait_c03_fixture_markers(backend, ORIGIN, nonce="a" * 32, target="create:dough", quantity=1, timeout=0.01)
