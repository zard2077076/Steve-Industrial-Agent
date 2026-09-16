from __future__ import annotations

import time
import math
import secrets
import re
from pathlib import Path
from typing import Any, Callable

from .backend import RealClientBackend
from .preview import publish_screen_bundle
from .protocol import BridgeError


METAL_PRESS_BOM: tuple[tuple[int, str, int], ...] = (
    (0, "immersiveengineering:steel_scaffolding_standard", 2),
    (1, "immersiveengineering:heavy_engineering", 1),
    (2, "immersiveengineering:rs_engineering", 1),
    (3, "immersiveengineering:conveyor_basic", 2),
    (4, "minecraft:piston", 1),
    (5, "immersiveengineering:hammer", 1),
    (6, "immersiveengineering:mold_plate", 1),
    (7, "minecraft:iron_ingot", 1),
    (8, "immersiveengineering:thermoelectric_generator", 1),
    (9, "immersiveengineering:connector_lv", 2),
    (10, "immersiveengineering:wirecoil_copper", 1),
    (11, "minecraft:blue_ice", 1),
    (12, "minecraft:magma_block", 1),
)

C03_TARGET = "minecraft:gravel"
C03_QUANTITY = 3
C03_SOURCE_Z_OFFSET = -8
C03_SALVAGE_Z_OFFSET = -10
MULTI_BOT_MIN_WORKERS = 2
MULTI_BOT_MAX_WORKERS = 5
MULTI_BOT_ROLES: tuple[str, ...] = ("logistics", "builder_inspector")

# These are the same reviewed one-batch representatives used by the existing
# C-06--C-10 Forge gates. Every row now carries its
# reviewed water-wheel topology; that server-side status is not itself a real-client
# completion claim.
CAPABILITY_SCENARIOS: dict[str, dict[str, Any]] = {
    "c06": {
        "capability": "C06",
        "stage": "washing",
        "target": "create:dough",
        "quantity": 1,
        "inputs": [("create:wheat_flour", 1)],
        "machine_media": ["water"],
        "power_resource": "create:water_wheel",
        "power_roles": [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "top_gearbox", "fan_drive_shaft", "encased_fan",
        ],
        "power_status": "VERIFIED_SURVIVAL",
        "power_blocker": None,
        "worker_count": 2,
    },
    "c07": {
        "capability": "C07",
        "stage": "cutting",
        "target": "minecraft:stripped_oak_log",
        "quantity": 1,
        "inputs": [("minecraft:oak_log", 1)],
        "machine_media": [],
        "power_resource": "create:water_wheel",
        "power_roles": [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "top_gearbox", "horizontal_shaft", "mechanical_saw",
        ],
        "power_status": "VERIFIED_SURVIVAL",
        "power_blocker": None,
        "worker_count": 2,
    },
    "c08": {
        "capability": "C08",
        "stage": "mixing",
        "target": "create:andesite_alloy",
        "quantity": 1,
        "inputs": [("minecraft:andesite", 1), ("minecraft:iron_nugget", 1)],
        "machine_media": ["water-capable basin/mixer route"],
        "power_resource": "create:water_wheel",
        "power_roles": [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "large_cogwheel_input", "small_cogwheel",
            "large_cogwheel_output", "mechanical_mixer",
        ],
        "power_status": "VERIFIED_SURVIVAL",
        "power_blocker": None,
        "worker_count": 3,
    },
    "c09": {
        "capability": "C09",
        "stage": "compacting",
        "target": "create:blaze_cake_base",
        "quantity": 1,
        "inputs": [("minecraft:egg", 1), ("minecraft:sugar", 1),
                    ("create:cinder_flour", 1)],
        "machine_media": ["basin/press route"],
        "power_resource": "create:water_wheel",
        "power_roles": [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "top_gearbox", "horizontal_shaft", "mechanical_press",
        ],
        "power_status": "VERIFIED_SURVIVAL",
        "power_blocker": None,
        "worker_count": 3,
    },
    "c10": {
        "capability": "C10",
        "stage": "deploying",
        "target": "create:cogwheel",
        "quantity": 1,
        "inputs": [("create:shaft", 1), ("minecraft:oak_planks", 1)],
        "machine_media": ["session-owned deployer workpiece route"],
        "power_resource": "create:water_wheel",
        "power_roles": [
            "water_wheel", "bottom_gearbox", "vertical_shaft",
            "top_gearbox", "horizontal_shaft", "deployer",
        ],
        "power_status": "VERIFIED_SURVIVAL",
        "power_blocker": None,
        "worker_count": 3,
    },
}


def recover_stale_work(backend: RealClientBackend, *, timeout: float = 60.0) -> dict[str, Any]:
    """Clear stale disposable-world work through the real player screens.

    This deliberately does not send a server-side abandon/cancel call.  It follows the
    same semantic widgets a player can use, and fails closed if a screen says that an
    order is no longer safely cancellable.
    """
    deadline = time.monotonic() + timeout
    history: list[dict[str, Any]] = []
    attempted_project_cancels: set[tuple[str, int]] = set()
    while time.monotonic() < deadline:
        inspected = backend.request("screen.inspect")
        screen = inspected.get("screen_class")
        widgets = {row.get("id"): row for row in inspected.get("widgets", [])}
        if screen == "EngineerTerminalScreen":
            if widgets.get("new_line", {}).get("active") is True:
                return {"status": "READY", "screen": screen, "history": history}
            if widgets.get("manage_project", {}).get("active") is True:
                history.append({"screen": screen, "action": "manage_project"})
                backend.request("screen.press", {"widget_id": "manage_project"})
                time.sleep(0.25)
                continue
            raise BridgeError("STALE_PROJECT_NOT_MANAGEABLE", str(inspected))

        if screen == "ConstructionProgressScreen":
            project = (inspected.get("domain_state") or {}).get("project") or {}
            if str(project.get("stage", "")) in {"CANCELLED", "COMPLETED"}:
                history.append({"screen": screen, "action": "close_terminal_project"})
                backend.request("screen.close")
                time.sleep(0.25)
                continue
            if widgets.get("cancel_project", {}).get("active") is not True:
                raise BridgeError("STALE_PROJECT_NOT_SAFE_TO_CANCEL", str(inspected))
            cancel_key = (str(project.get("projectId", "")),
                          int(project.get("projectNonce", -1)))
            if cancel_key in attempted_project_cancels:
                raise BridgeError("STALE_PROJECT_CANCEL_NOT_APPLIED", str(inspected))
            attempted_project_cancels.add(cancel_key)
            history.append({"screen": screen, "action": "cancel_project"})
            backend.request("screen.press", {"widget_id": "cancel_project"})
            _wait_project_cancel_response(backend, cancel_key, timeout=5.0)
            continue

        if screen == "MaterialSourceScreen":
            project = (inspected.get("domain_state") or {}).get("project") or {}
            if str(project.get("stage", "")) in {"CANCELLED", "COMPLETED"}:
                history.append({"screen": screen, "action": "close_terminal_project"})
                backend.request("screen.close")
                time.sleep(0.25)
                continue
            if widgets.get("cancel_project", {}).get("active") is not True:
                raise BridgeError("STALE_MATERIAL_SCREEN_NOT_CANCELLABLE", str(inspected))
            cancel_key = (str(project.get("projectId", "")),
                          int(project.get("projectNonce", -1)))
            if cancel_key in attempted_project_cancels:
                raise BridgeError("STALE_PROJECT_CANCEL_NOT_APPLIED", str(inspected))
            attempted_project_cancels.add(cancel_key)
            history.append({"screen": screen, "action": "cancel_project"})
            backend.request("screen.press", {"widget_id": "cancel_project"})
            _wait_project_cancel_response(backend, cancel_key, timeout=5.0)
            continue

        if screen == "ClearingReadyScreen":
            # Its cancel button closes the ready view but intentionally leaves the
            # project alive. Reopen the terminal and let the server expose the same
            # project through the authoritative management path.
            history.append({"screen": screen, "action": "close_ready_view"})
            backend.request("screen.close")
            time.sleep(0.25)
            backend.request("player.use_main_hand")
            time.sleep(0.25)
            continue

        if screen == "MetalPressOrderScreen":
            # A previous Metal Press order may be open. Only use the screen's own
            # double-confirmed safe-cancel path; if it is already committed, preserve
            # the evidence instead of silently abandoning it.
            stage = str((inspected.get("domain_state") or {}).get("stage", ""))
            if stage in {"COMPLETED", "CANCELLED"}:
                history.append({"screen": screen, "action": "close_terminal_order",
                                "stage": stage})
                backend.request("screen.close")
                time.sleep(0.25)
                continue
            if widgets.get("cancel_order", {}).get("active") is not True:
                raise BridgeError("STALE_METAL_PRESS_NOT_SAFE_TO_CANCEL", str(inspected))
            history.append({"screen": screen, "action": "cancel_order"})
            backend.request("screen.press", {"widget_id": "cancel_order"})
            time.sleep(0.1)
            backend.request("screen.press", {"widget_id": "cancel_order"})
            backend.request("screen.close")
            time.sleep(0.75)
            backend.request("player.use_main_hand")
            time.sleep(0.25)
            continue

        if screen == "CompositeOrderScreen":
            # Composite uses the same server-authoritative two-click cancellation
            # affordance.  Never issue a hidden command to clear an old graph.
            if widgets.get("cancel_order", {}).get("active") is not True:
                raise BridgeError("STALE_COMPOSITE_NOT_SAFE_TO_CANCEL", str(inspected))
            history.append({"screen": screen, "action": "cancel_order"})
            backend.request("screen.press", {"widget_id": "cancel_order"})
            time.sleep(0.1)
            backend.request("screen.press", {"widget_id": "cancel_order"})
            backend.request("screen.close")
            time.sleep(0.75)
            backend.request("player.use_main_hand")
            time.sleep(0.25)
            continue

        if screen == "CompositeCompletionScreen":
            history.append({"screen": screen, "action": "close_report"})
            backend.request("screen.press", {"widget_id": "close_report"})
            time.sleep(0.25)
            continue

        if screen == "CompletionReportScreen":
            history.append({"screen": screen, "action": "close_report"})
            backend.request("screen.press", {"widget_id": "close_report"})
            time.sleep(0.25)
            continue

        if screen in {"GoalPickerScreen", "SiteSurveySummaryScreen", "RelocationAdvisorScreen",
                      "DemolitionApprovalScreen"}:
            history.append({"screen": screen, "action": "close_screen"})
            backend.request("screen.close")
            time.sleep(0.25)
            continue

        if screen is None:
            history.append({"screen": None, "action": "open_terminal"})
            backend.request("player.use_main_hand")
            time.sleep(0.25)
            continue

        raise BridgeError("STALE_SCREEN_UNSUPPORTED", str(inspected))
    raise BridgeError("STALE_WORK_RECOVERY_TIMEOUT", str(history))


def _wait_project_cancel_response(
    backend: RealClientBackend, cancel_key: tuple[str, int], *, timeout: float,
) -> dict[str, Any]:
    """Wait for one asynchronous cancel response without issuing another click."""
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = backend.request("screen.inspect")
        project = (last.get("domain_state") or {}).get("project") or {}
        current = (str(project.get("projectId", "")), int(project.get("projectNonce", -1)))
        if last.get("screen_class") is None or current != cancel_key:
            return last
        time.sleep(0.1)
    raise BridgeError("STALE_PROJECT_CANCEL_NOT_APPLIED", str(last))


def scenario_catalog() -> dict[str, dict[str, Any]]:
    """Return the truthful scenario inventory exposed to agents."""
    return {
        "metalpress": {
            "status": "IMPLEMENTED",
            "backend": "REAL_CLIENT_BRIDGE",
            "workflow": [
                "seed disposable source chest and authorized world marker",
                "sneak-use source chest through real UseOn packet",
                "sneak-use site surface through real UseOn packet",
                "open MetalPressOrderScreen through the held terminal",
                "poll packet-backed stage and capture native screenshots",
                "assert completion report and material/energy/output counters",
            ],
            "requires": [
                "existing exact world Steve Agent Mac Acceptance",
                "source and site coordinates in loaded chunks",
                "Immersive Engineering 10.2.0-183 in the dev client",
            ],
            "not_claimed": [
                "human UX/aesthetic approval",
                "C-06-C-10, Composite or multi-Bot scenario completion",
            ],
        },
        "c03": {
            "status": "REAL_CLIENT_FIXTURE_READY",
            "backend": "REAL_CLIENT_BRIDGE",
            "target": C03_TARGET,
            "quantity": C03_QUANTITY,
            "workflow": [
                "prepare the live C-03 bill in one disposable source chest",
                "select gravel and BOTS through the real GoalPickerScreen",
                "confirm placement, survey, demolition approval and salvage chest",
                "let the clearing Bot finish, then bind the exact material chest",
                "reserve and start through MaterialSourceScreen",
                "wait for CompletionReportScreen and assert balanced output evidence",
            ],
            "requires": [
                "development Forge client in exact isolated acceptance world",
                "Create 6.0.6 water-wheel/millstone runtime and engineer terminal",
            ],
            "not_claimed": [
                "a real-client C-03 run has passed in this session",
                "human UX/aesthetic approval",
                "C-04-C-10 survival material mappings",
            ],
        },
        "c06-c10": {
            "status": "RUNNER_READY_CLIENT_UNVERIFIED",
            "backend": "REAL_CLIENT_BRIDGE",
            "next": "run c06, c07, c08, c09 or c10 through the shared ordinary-player workflow",
            "capabilities": CAPABILITY_SCENARIOS,
        },
        "composite": {
            "status": "REAL_CLIENT_FIXTURE_READY",
            "backend": "REAL_CLIENT_BRIDGE",
            "workflow": [
                "prepare two exact source chests through the dev-only disposable-world fixture",
                "issue the reviewed player composite command through the real client",
                "open the packet-backed CompositeOrderScreen",
                "poll node and intermediate-buffer snapshots without refresh spam",
                "wait for the persisted CompositeCompletionScreen",
                "assert exact report counters, output identity and baseline evidence",
            ],
            "not_claimed": ["a real-client run has passed in this session", "human UX/aesthetic approval"],
        },
        "multi-bot": {
            "status": "PLANNED_ON_SHARED_TRANSPORT",
            "backend": "REAL_CLIENT_BRIDGE",
            "next": "observe bot entity/count, role coverage and route checkpoints through world.inspect",
            "worker_range": [MULTI_BOT_MIN_WORKERS, MULTI_BOT_MAX_WORKERS],
            "observability": [
                "ConstructionBotEntity UUID, role, tags and position",
                "per-checkpoint active worker count",
                "same-cell collision evidence within each snapshot",
            ],
        },
    }


def metal_press_plan(source: tuple[int, int, int], origin: tuple[int, int, int]) -> dict[str, Any]:
    return {
        "status": "PLANNED",
        "scenario": "metalpress",
        "source": _position(source),
        "machine_origin": _position(origin),
        "bom": [
            {"slot": slot, "item": item, "count": count}
            for slot, item, count in METAL_PRESS_BOM
        ],
        "actions": [
            "recover stale work through EngineerTerminal manage/cancel UI",
            "industrialagent setup mark-test-world",
            "setblock source chest",
            "fill source slots using bounded item replace commands",
            "sneak-use source",
            "sneak-use site surface",
            "sneak-use terminal in air",
            "poll MetalPressOrderScreen.domain_state",
        ],
        "guards": [
            "exact acceptance world",
            "no direct server/service call",
            "native Minecraft screenshots only",
            "pause and preserve evidence on any non-terminal failure",
        ],
    }


def composite_plan(order_type: str, origin: tuple[int, int, int]) -> dict[str, Any]:
    primary = (origin[0], origin[1], origin[2] - 8)
    secondary = (origin[0], origin[1], origin[2] - 6)
    return {
        "status": "PLANNED",
        "scenario": "composite",
        "order_type": order_type,
        "site_origin": _position(origin),
        "primary_source": _position(primary),
        "secondary_source": _position(secondary),
        "actions": [
            "recover stale Composite work through its real status/cancel UI",
            "industrialagent setup mark-test-world",
            f'industrialagent acceptance composite-fixture "{order_type}"',
            "steveagent composite create <order_type> <primary> <origin> bots <secondary>",
            "poll CompositeOrderScreen.domain_state without refresh spam",
            "wait for CompositeCompletionScreen.domain_state",
            "assert materialLedgerBalanced, baselineRestored, exact output and zero duplicate counters",
        ],
        "guards": [
            "exact acceptance world",
            "development-only fixture; no production world mutation",
            "no direct server/service call",
            "native Minecraft screenshots only",
            "preserve the world on any non-terminal failure",
        ],
    }


def c03_plan(origin: tuple[int, int, int]) -> dict[str, Any]:
    source = (origin[0], origin[1], origin[2] + C03_SOURCE_Z_OFFSET)
    salvage = (origin[0], origin[1], origin[2] + C03_SALVAGE_Z_OFFSET)
    return {
        "status": "PLANNED",
        "scenario": "c03",
        "target": C03_TARGET,
        "quantity": C03_QUANTITY,
        "site_origin": _position(origin),
        "source": _position(source),
        "salvage": _position(salvage),
        "actions": [
            "recover stale work through EngineerTerminal manage/cancel UI",
            "industrialagent setup mark-test-world",
            "industrialagent acceptance create-fixture minecraft:gravel 3",
            "open the real EngineerTerminalScreen and select gravel ×3",
            "switch semantic execution_mode twice to BOTS",
            "look at the prepared floor cell and confirm placement",
            "review survey, approve clearing, bind the empty salvage chest",
            "start clearing and bind the exact generated material chest",
            "confirm reservation, start construction, wait for CompletionReportScreen",
            "assert observedOutput=3, expectedOutput=3, balanced=true and zero duplicates",
        ],
        "guards": [
            "exact acceptance world",
            "development-only fixture; no production world mutation",
            "no direct server/service call",
            "native Minecraft screenshots only",
            "read-only polling; never click a refresh button in a loop",
            "preserve the world on any non-terminal failure",
        ],
    }


def capability_plan(
    capability: str, origin: tuple[int, int, int],
) -> dict[str, Any]:
    """Build a truthful, non-executing plan for one C-06--C-10 representative."""
    key = capability.lower().replace("-", "")
    if key not in CAPABILITY_SCENARIOS:
        raise ValueError(f"unknown capability scenario: {capability}")
    spec = CAPABILITY_SCENARIOS[key]
    source = (origin[0], origin[1], origin[2] + C03_SOURCE_Z_OFFSET)
    staging = (origin[0] - 3, origin[1], origin[2] - 2)
    power_ready = spec["power_status"] == "VERIFIED_SURVIVAL"
    actions = [
        "recover stale work through real player management screens",
        f'industrialagent acceptance create-fixture "{spec["target"]}" {spec["quantity"]}',
        "select the target through the real GoalPickerScreen and production search packet",
        "check the selected recipe capability and set the exact quantity in the real EditBox",
        "use the existing placement/survey/approval/material-source screens",
    ]
    guards = [
        "exact acceptance world",
        "no direct server/service call",
        "native screenshots and read-only domain/Bot observations only",
    ]
    if power_ready:
        actions.extend([
            "run the verified survival-power material, Bot and report checkpoints",
            "require real output and balanced completion evidence before reporting PASS",
        ])
        guards.append("VERIFIED_SURVIVAL is eligibility for client testing, not a client PASS")
    else:
        actions.extend([
            "observe the server's exact survival-power refusal before any reservation or placement",
            "when a reviewed power mapping exists, run the same ledger/report/Bot checkpoints",
        ])
        guards.extend([
            "no creative_motor or other unreviewed power block may be free-placed",
            "REVIEW_REQUIRED is a typed stop, not a failed completion to hide",
        ])
    return {
        "status": "PLANNED",
        "scenario": key,
        "capability": spec["capability"],
        "stage": spec["stage"],
        "target": spec["target"],
        "quantity": spec["quantity"],
        "inputs": [
            {"item": item, "count": count} for item, count in spec["inputs"]
        ],
        "machine_media": list(spec["machine_media"]),
        "power": {
            "resource": spec["power_resource"],
            "roles": list(spec["power_roles"]),
            "status": spec["power_status"],
            "blocker": spec["power_blocker"],
        },
        "worker_count": spec["worker_count"],
        "source": _position(source),
        "salvage": _position((origin[0], origin[1], origin[2] + C03_SALVAGE_Z_OFFSET)),
        "staging": _position(staging),
        "site_origin": _position(origin),
        "actions": actions,
        "guards": guards,
    }


def multi_bot_plan(
    origin: tuple[int, int, int], *, worker_count: int = MULTI_BOT_MIN_WORKERS,
) -> dict[str, Any]:
    """Describe, but do not start, a visible multi-Bot observation run.

    The client bridge already exposes the bounded, client-visible entity rows.  The
    plan deliberately stops at observation: there is no generic command that may
    spawn workers or bypass the production order service.  A later real-client gate
    must supply a reviewed C-03--C-10 or Composite order and then use these
    checkpoints as evidence.
    """
    if not MULTI_BOT_MIN_WORKERS <= worker_count <= MULTI_BOT_MAX_WORKERS:
        raise ValueError(
            f"worker_count must be between {MULTI_BOT_MIN_WORKERS} and "
            f"{MULTI_BOT_MAX_WORKERS}"
        )
    source = (origin[0], origin[1], origin[2] + C03_SOURCE_Z_OFFSET)
    staging = (origin[0] - 3, origin[1], origin[2] - 2)
    checkpoints = [
        {"name": "source", "position": _position(source)},
        {"name": "staging", "position": _position(staging)},
        {"name": "site", "position": _position(origin)},
        {"name": "return", "position": _position(source)},
    ]
    return {
        "status": "PLANNED",
        "scenario": "multi-bot",
        "site_origin": _position(origin),
        "worker_count": worker_count,
        "expected_roles": list(MULTI_BOT_ROLES),
        "checkpoints": checkpoints,
        "actions": [
            "recover stale work through real player management screens",
            "run one reviewed player order in BOTS mode through its normal packet path",
            "poll the packet-backed production screen without refresh clicks",
            "read-only world.inspect at source, staging, site and return checkpoints",
            "assert role coverage, unique entity UUIDs and no same-cell collision in each snapshot",
            "assert the existing ledger/report/baseline gates before declaring completion",
        ],
        "guards": [
            "exact acceptance world",
            "no worker spawn or direct service invocation from the harness",
            "world.inspect is read-only and bounded to the player-visible radius",
            "a planned observation is not a real-client completion claim",
        ],
    }


def summarize_bot_fleet(probe: dict[str, Any]) -> dict[str, Any]:
    """Summarize only ConstructionBotEntity rows returned by ``world.inspect``.

    Missing entities stay missing; this helper never upgrades an absent observation
    into a healthy/complete result.  It is intentionally pure so the same evidence
    rules can be unit-tested without a running Minecraft client.
    """
    entities = probe.get("entities") or []
    bots = [
        row for row in entities
        if isinstance(row, dict) and row.get("role") in MULTI_BOT_ROLES
    ]
    uuids = [str(row.get("uuid", "")) for row in bots]
    non_blank_uuids = [value for value in uuids if value]
    duplicate_entity_uuids = len(non_blank_uuids) - len(set(non_blank_uuids))
    positions = [
        (round(float(row.get("x", 0.0)), 3),
         round(float(row.get("y", 0.0)), 3),
         round(float(row.get("z", 0.0)), 3))
        for row in bots
    ]
    same_cell_positions = len(positions) - len(set(positions))
    by_role = {
        role: sum(1 for row in bots if row.get("role") == role)
        for role in MULTI_BOT_ROLES
    }
    return {
        "status": "OBSERVED",
        "center": probe.get("center"),
        "game_time": probe.get("game_time"),
        "active_bots": len(bots),
        "by_role": by_role,
        "roles_observed": sorted({str(row.get("role")) for row in bots}),
        "entity_uuids_unique": duplicate_entity_uuids == 0,
        "duplicate_entity_uuids": duplicate_entity_uuids,
        "same_cell_collision": same_cell_positions > 0,
        "same_cell_collisions": same_cell_positions,
        "bots": [
            {
                "uuid": row.get("uuid"),
                "role": row.get("role"),
                "tags": list(row.get("tags") or []),
                "x": row.get("x"),
                "y": row.get("y"),
                "z": row.get("z"),
            }
            for row in bots
        ],
    }


def inspect_bot_checkpoints(
    backend: RealClientBackend,
    checkpoints: list[tuple[str, tuple[int, int, int]]],
    *,
    radius: int = 3,
) -> dict[str, Any]:
    """Read bounded Bot evidence at named checkpoints; never mutates the world."""
    if not 1 <= len(checkpoints) <= 8:
        raise ValueError("checkpoint count must be between 1 and 8")
    if not 0 <= radius <= 3:
        raise ValueError("radius must be between 0 and 3")
    readings = []
    for name, position in checkpoints:
        probe = backend.request("world.inspect", {
            "x": position[0], "y": position[1], "z": position[2], "radius": radius,
        })
        readings.append({"name": name, "position": _position(position),
                         "observation": summarize_bot_fleet(probe)})
    active_counts = [int(row["observation"]["active_bots"]) for row in readings]
    return {
        "status": "OBSERVED",
        "checkpoint_count": len(readings),
        "max_active_bots": max(active_counts, default=0),
        "roles_observed": sorted({
            role for row in readings for role in row["observation"]["roles_observed"]
        }),
        "same_cell_collision": any(
            row["observation"]["same_cell_collision"] for row in readings
        ),
        "entity_uuids_unique": all(
            row["observation"]["entity_uuids_unique"] for row in readings
        ),
        "checkpoints": readings,
    }


def run_composite(
    backend: RealClientBackend,
    order_type: str,
    origin: tuple[int, int, int],
    *,
    prepare_fixture: bool = False,
    timeout: float = 600.0,
    capture: bool = True,
    checkpoint: Callable[[str, dict[str, Any]], dict[str, Any] | None] | None = None,
) -> dict[str, Any]:
    """Run one Composite order through the real player command and packet-backed UI."""
    if prepare_fixture:
        _seed_composite_fixture(backend, order_type, origin)
    else:
        recover_stale_work(backend)
        _close_screen_if_open(backend)
    primary = (origin[0], origin[1], origin[2] - 8)
    secondary = (origin[0], origin[1], origin[2] - 6)
    for source in (primary, secondary):
        _assert_chest(backend, source)
    _teleport(backend, origin[0], origin[1], origin[2] - 3)
    command = _composite_create_command(order_type, primary, origin, secondary)
    backend.request("player.command", {"command": command})
    _wait_screen(backend, "CompositeOrderScreen", timeout=20.0)

    checkpoints: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    bot_observations: list[dict[str, Any]] = []
    deadline = time.monotonic() + timeout
    final: dict[str, Any] = {}
    while time.monotonic() < deadline:
        inspected = backend.request("screen.inspect")
        screen = inspected.get("screen_class")
        state = inspected.get("domain_state") or {}
        if screen not in {"CompositeOrderScreen", "CompositeCompletionScreen"}:
            raise BridgeError("COMPOSITE_SCREEN_LOST", str(screen))
        key = (str(screen), str(state.get("generation", state.get("operationCode", ""))))
        if key not in seen:
            seen.add(key)
            evidence: dict[str, Any] = {"screen": screen, "state": state}
            if capture:
                evidence.update(_capture(backend, str(state.get("operationCode", screen)),
                                        prefix="steve-agent-composite"))
            if checkpoint is not None:
                extra = checkpoint(str(state.get("operationCode", screen)), evidence)
                if extra:
                    evidence.update(extra)
            bot_evidence = inspect_bot_checkpoints(
                backend,
                [("primary", primary), ("secondary", secondary),
                 ("site", origin), ("return", primary)],
            )
            evidence["bot_observation"] = bot_evidence
            bot_observations.append(bot_evidence)
            checkpoints.append(evidence)
        if screen == "CompositeCompletionScreen":
            final = dict(state)
            break
        # Both screens have their own timer or are immutable; this loop is read-only.
        time.sleep(0.5)
    else:
        raise BridgeError("COMPOSITE_TIMEOUT", str(final))

    required = {
        "success": True,
        "materialLedgerBalanced": True,
        "baselineRestored": True,
        "duplicateWithdrawals": 0,
        "duplicateReturns": 0,
        "duplicateEnergySettlements": 0,
        "duplicateOutputs": 0,
        "unaccountedItems": 0,
        "privateItemsTouched": 0,
    }
    mismatches = {key: {"expected": value, "actual": final.get(key)}
                  for key, value in required.items() if final.get(key) != value}
    if mismatches:
        raise BridgeError("COMPOSITE_COMPLETION_ASSERTION_FAILED", str(mismatches))
    rows = final.get("rows") or []
    outputs = {str(row.get("resource")): int(row.get("output", 0))
               for row in rows if int(row.get("output", 0)) > 0}
    if outputs != {str(final.get("target")): 1}:
        raise BridgeError("COMPOSITE_OUTPUT_ASSERTION_FAILED", str(outputs))
    world = backend.request("world.inspect", {"x": origin[0], "y": origin[1],
                                                "z": origin[2], "radius": 3})
    return {"status": "PASS", "scenario": "composite", "order_type": order_type,
            "site_origin": _position(origin), "final": final,
            "checkpoints": checkpoints, "bot_observations": bot_observations,
            "world": world}


def _composite_create_command(
    order_type: str,
    primary: tuple[int, int, int],
    origin: tuple[int, int, int],
    secondary: tuple[int, int, int],
) -> str:
    if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9/._-]+", order_type):
        raise ValueError("order_type")
    return (f'steveagent composite create "{order_type}" '
            f"{primary[0]} {primary[1]} {primary[2]} "
            f"{origin[0]} {origin[1]} {origin[2]} bots "
            f"{secondary[0]} {secondary[1]} {secondary[2]}")


def run_c03(
    backend: RealClientBackend,
    origin: tuple[int, int, int],
    *,
    prepare_fixture: bool = False,
    timeout: float = 600.0,
    capture: bool = True,
    checkpoint: Callable[[str, dict[str, Any]], dict[str, Any] | None] | None = None,
) -> dict[str, Any]:
    """Compatibility entry for the original gravel ×3 client scenario."""
    return run_create_player_flow(backend, origin, scenario="c03",
                                  prepare_fixture=prepare_fixture, timeout=timeout,
                                  capture=capture, checkpoint=checkpoint)


def run_create_player_flow(
    backend: RealClientBackend,
    origin: tuple[int, int, int],
    *,
    scenario: str,
    prepare_fixture: bool = False,
    timeout: float = 600.0,
    capture: bool = True,
    checkpoint: Callable[[str, dict[str, Any]], dict[str, Any] | None] | None = None,
) -> dict[str, Any]:
    """One ordinary-player state machine; never calls an order service directly."""
    if not math.isfinite(timeout) or timeout <= 0:
        raise ValueError("timeout must be finite and positive")
    scenario = scenario.lower().replace("-", "")
    plan = c03_plan(origin) if scenario == "c03" else capability_plan(scenario, origin)
    target, quantity = plan["target"], plan["quantity"]
    expected_capability = ("create:milling" if scenario == "c03" else
                           "create:splashing" if scenario == "c06" else
                           f'create:{plan["stage"]}')
    prefix = scenario.upper()
    if prepare_fixture:
        _seed_c03_fixture(backend, origin, target=target, quantity=quantity)
    else:
        recover_stale_work(backend)
        _close_screen_if_open(backend)

    source = (origin[0], origin[1], origin[2] + C03_SOURCE_Z_OFFSET)
    salvage = (origin[0], origin[1], origin[2] + C03_SALVAGE_Z_OFFSET)
    _assert_chest(backend, source)
    _assert_chest(backend, salvage)

    # Open the real terminal, create a real project, and exercise the BOTS mode.
    # Approach from the west so legacy disposable-world Bots on the old north lane
    # cannot become the nearer entity hit and shift the selected floor cell.
    _teleport(backend, origin[0] - 4, origin[1] + 2, origin[2])
    backend.request("player.use_main_hand")
    _wait_screen(backend, "EngineerTerminalScreen", timeout=20.0)
    backend.request("screen.press", {"widget_id": "new_line"})
    _wait_screen(backend, "GoalPickerScreen", timeout=20.0)
    _select_goal(backend, target)
    selected_goal = next((row for row in backend.request("screen.inspect").get("target_rows", [])
                          if row.get("target") == target), {})
    if selected_goal.get("capability") != expected_capability or selected_goal.get("available") is not True:
        raise BridgeError(f"{prefix}_CAPABILITY_MISMATCH", str(selected_goal))
    backend.request("screen.text", {"widget_id": "quantity", "value": str(quantity)})
    backend.request("screen.press", {"widget_id": "execution_mode"})
    backend.request("screen.press", {"widget_id": "execution_mode"})
    backend.request("screen.press", {"widget_id": "select_target"})

    # PlacementController owns the same client-side hit result as a player looking at the
    # block. Reposition directly above the target after closing the goal screen so the
    # actual ray cannot choose an adjacent floor cell or an old Bot on the approach lane.
    _teleport(backend, origin[0], origin[1] + 2, origin[2])
    _wait_player_horizontal(backend, origin[0], origin[2], timeout=5.0)
    backend.request("player.look_at", {"x": origin[0], "y": origin[1] - 1,
                                        "z": origin[2]})
    _confirm_placement_when_selected(backend, timeout=5.0)
    survey = _wait_screen(backend, "SiteSurveySummaryScreen", timeout=20.0)
    selected = survey.get("domain_state") or {}
    project_id = selected.get("projectId")
    if not project_id:
        raise BridgeError(f"{prefix}_PROJECT_ID_MISSING", str(selected))
    actual_anchor = (selected.get("anchorX"), selected.get("anchorY"), selected.get("anchorZ"))
    if actual_anchor != origin:
        raise BridgeError(f"{prefix}_ANCHOR_MISMATCH",
                          f"expected={origin} actual={actual_anchor}")
    backend.request("screen.press", {"widget_id": "review_confirm"})
    _wait_screen(backend, "DemolitionApprovalScreen", timeout=20.0)
    backend.request("screen.press", {"widget_id": "approve_clear"})
    _wait_for_predicate(backend, lambda row: (
        row.get("screen_class") == "DemolitionApprovalScreen"
        and (row.get("domain_state") or {}).get("approval_active") is True), timeout=20.0)
    backend.request("screen.press", {"widget_id": "select_salvage"})
    time.sleep(0.15)
    backend.request("player.bind_salvage", {"x": salvage[0], "y": salvage[1], "z": salvage[2]})
    _wait_screen(backend, "ClearingReadyScreen", timeout=20.0)
    backend.request("screen.press", {"widget_id": "start_clearing"})

    checkpoints: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    bot_observations: list[dict[str, Any]] = []
    source_bound = False
    source_bind_deadline: float | None = None
    reservation_confirmed = False
    construction_started = False
    screenless_since: float | None = None
    continuation_requested = False
    deadline = time.monotonic() + timeout
    final: dict[str, Any] = {}
    while time.monotonic() < deadline:
        inspected = backend.request("screen.inspect")
        screen = inspected.get("screen_class")
        state = inspected.get("domain_state") or {}
        project = state.get("project") or {}
        stage = str(project.get("stage", state.get("status", "")))
        key = (str(screen), stage)
        if key not in seen and screen is not None:
            seen.add(key)
            evidence: dict[str, Any] = {"screen": screen, "stage": stage, "state": state}
            if capture:
                evidence.update(_capture(backend, stage or str(screen),
                                         prefix=f"steve-agent-{scenario}"))
            if checkpoint is not None:
                extra = checkpoint(stage or str(screen), evidence)
                if extra:
                    evidence.update(extra)
            bot_evidence = inspect_bot_checkpoints(
                backend,
                [("source", source),
                 ("staging", (origin[0] - 3, origin[1], origin[2] - 2)),
                 ("site", origin), ("return", source)],
            )
            evidence["bot_observation"] = bot_evidence
            bot_observations.append(bot_evidence)
            checkpoints.append(evidence)

        if screen == "MaterialSourceScreen":
            screenless_since = None
            if stage == "MATERIAL_SOURCE_SELECTION" and construction_started:
                raise BridgeError(f"{prefix}_CONSTRUCTION_REGRESSED_TO_MATERIAL_SELECTION",
                                  str(state))
            if int(state.get("sourceCount", 0)) == 0 and not source_bound:
                backend.request("screen.press", {"widget_id": "add_material_source"})
                # The fixture source is eight horizontal blocks from the site. Chest
                # stillValid measures to its centre, so binding from the site is 64.25
                # squared blocks and correctly out of reach. Move beside it as a player
                # would, then let the server retain all normal range/ownership checks.
                _teleport(backend, source[0] + 2, source[1] + 2, source[2])
                _wait_player_horizontal(backend, source[0] + 2, source[2], timeout=5.0)
                backend.request("player.bind_material_source", {
                    "x": source[0], "y": source[1], "z": source[2], "face": "up"})
                source_bound = True
                source_bind_deadline = time.monotonic() + 10.0
                time.sleep(0.25)
                continue
            if int(state.get("sourceCount", 0)) == 0 and source_bound:
                if source_bind_deadline is not None and time.monotonic() < source_bind_deadline:
                    time.sleep(0.1)
                    continue
                raise BridgeError(f"{prefix}_SOURCE_BIND_TIMEOUT", str(state))
            if not state.get("sufficient", False):
                raise BridgeError(f"{prefix}_MATERIALS_INSUFFICIENT", str(state))
            if stage == "MATERIAL_SOURCE_SELECTION" and not reservation_confirmed:
                backend.request("screen.press", {"widget_id": "confirm_materials"})
                reservation_confirmed = True
                time.sleep(0.25)
                continue
            if stage == "MATERIAL_RESERVED" and not construction_started:
                backend.request("screen.press", {"widget_id": "confirm_materials"})
                construction_started = True
                time.sleep(0.25)
                continue

        if screen is None:
            if screenless_since is None:
                screenless_since = time.monotonic()
            elif not continuation_requested and time.monotonic() - screenless_since >= 1.0:
                backend.request("player.use_main_hand")
                continuation_requested = True
                time.sleep(0.25)
                continue

        if screen == "EngineerTerminalScreen" and continuation_requested:
            widgets = {row.get("id"): row for row in inspected.get("widgets", [])}
            if widgets.get("continue_project", {}).get("active") is not True:
                raise BridgeError(f"{prefix}_CONTINUE_PROJECT_UNAVAILABLE", str(inspected))
            backend.request("screen.press", {"widget_id": "continue_project"})
            time.sleep(0.25)
            continue

        if screen == "ConstructionProgressScreen" and (stage == "PAUSED" or state.get("paused") is True):
            raise BridgeError(f"{prefix}_CONSTRUCTION_PAUSED", str(state))

        if screen == "CompletionReportScreen":
            final = dict(state)
            break
        if screen not in {None, "ClearingReadyScreen", "ConstructionProgressScreen",
                          "MaterialSourceScreen", "CompletionReportScreen"}:
            raise BridgeError(f"{prefix}_SCREEN_LOST", str(inspected))
        # All production screens own their packet/timer cadence. This is observation only.
        time.sleep(0.5)
    else:
        raise BridgeError(f"{prefix}_TIMEOUT", str(final))

    report = final.get("report") or {}
    completed_project = final.get("project") or {}
    if (completed_project.get("projectId") != project_id
            or completed_project.get("target") != target
            or completed_project.get("quantity") != quantity
            or completed_project.get("stage") != "COMPLETED"):
        raise BridgeError(f"{prefix}_COMPLETION_PROJECT_MISMATCH", str(completed_project))
    required = {
        "balanced": True,
        "duplicateWithdrawals": 0,
        "duplicateReturns": 0,
        "unaccountedItems": 0,
        "privateItemsTouched": 0,
        "observedOutput": quantity,
        "expectedOutput": quantity,
    }
    mismatches = {key: {"expected": value, "actual": report.get(key)}
                  for key, value in required.items()
                  if type(report.get(key)) is not type(value) or report.get(key) != value}
    if mismatches:
        raise BridgeError(f"{prefix}_COMPLETION_ASSERTION_FAILED", str(mismatches))
    world = backend.request("world.inspect", {"x": origin[0], "y": origin[1],
                                                "z": origin[2], "radius": 3})
    return {"status": "PASS", "scenario": scenario, "target": target,
            "quantity": quantity, "recipe_capability": expected_capability,
            "site_origin": _position(origin),
            "final": final, "checkpoints": checkpoints,
            "bot_observations": bot_observations, "world": world}


def run_metal_press(
    backend: RealClientBackend,
    source: tuple[int, int, int],
    origin: tuple[int, int, int],
    *,
    prepare_fixture: bool = False,
    timeout: float = 300.0,
    capture: bool = True,
    checkpoint: Callable[[str, dict[str, Any]], dict[str, Any] | None] | None = None,
) -> dict[str, Any]:
    """Run the player-shaped Metal Press flow through the authenticated real client."""
    if prepare_fixture:
        _seed_fixture(backend, source, origin)
    else:
        recover_stale_work(backend)
        _close_screen_if_open(backend)

    _assert_source(backend, source)
    _teleport(backend, source[0], source[1] + 1, source[2] - 3)
    backend.request("player.sneak_use_on", {"x": source[0], "y": source[1],
                                             "z": source[2], "face": "UP"})
    _teleport(backend, origin[0], origin[1], origin[2] - 3)
    backend.request("player.sneak_use_on", {"x": origin[0], "y": origin[1] - 1,
                                             "z": origin[2], "face": "UP"})
    backend.request("player.sneak_use_main_hand")
    state = _wait_screen(backend, "MetalPressOrderScreen", timeout=20.0)

    checkpoints: list[dict[str, Any]] = []
    seen_stages: set[str] = set()
    bot_observations: list[dict[str, Any]] = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        inspected = backend.request("screen.inspect")
        state = inspected.get("domain_state") or {}
        if inspected.get("screen_class") != "MetalPressOrderScreen":
            raise BridgeError("METAL_PRESS_SCREEN_LOST", str(inspected.get("screen_class")))
        stage = str(state.get("stage", "UNKNOWN"))
        if stage not in seen_stages:
            seen_stages.add(stage)
            evidence: dict[str, Any] = {"stage": stage, "state": state}
            if capture:
                evidence.update(_capture(backend, stage))
            if checkpoint is not None:
                extra = checkpoint(stage, evidence)
                if extra: evidence.update(extra)
            bot_evidence = inspect_bot_checkpoints(
                backend,
                [("source", source),
                 ("staging", (origin[0] - 3, origin[1], origin[2] - 2)),
                 ("site", origin), ("return", source)],
            )
            evidence["bot_observation"] = bot_evidence
            bot_observations.append(bot_evidence)
            checkpoints.append(evidence)
        if stage in {"COMPLETED", "CANCELLED", "PAUSED"}:
            break
        # MetalPressOrderScreen owns its real two-second refresh timer. Do not press the
        # visible refresh button from the harness on every poll: that changes the player
        # UI and turns an evidence loop into a stream of synthetic clicks. screen.inspect
        # remains read-only; the client screen itself sends the normal status packet.
        time.sleep(0.5)
    else:
        raise BridgeError("METAL_PRESS_TIMEOUT", str(state))

    final = dict(state)
    if final.get("stage") != "COMPLETED":
        raise BridgeError("METAL_PRESS_NOT_COMPLETED", str(final))
    required = {
        "success": True,
        "reportPresent": True,
        "ledgerBalanced": True,
        "baselineRestored": True,
        "unaccountedItems": 0,
        "duplicateWithdrawals": 0,
        "duplicateEnergy": 0,
        "duplicateOutputs": 0,
        "duplicateReturns": 0,
        "privateItemsTouched": 0,
        "energyConsumed": 2400,
        "outputCount": 1,
    }
    mismatches = {
        key: {"expected": value, "actual": final.get(key)}
        for key, value in required.items() if final.get(key) != value
    }
    if mismatches:
        raise BridgeError("METAL_PRESS_ASSERTION_FAILED", str(mismatches))
    world = backend.request("world.inspect", {"x": origin[0], "y": origin[1],
                                                "z": origin[2], "radius": 3})
    return {
        "status": "PASS",
        "scenario": "metalpress",
        "source": _position(source),
        "machine_origin": _position(origin),
        "final": final,
        "checkpoints": checkpoints,
        "bot_observations": bot_observations,
        "world": world,
    }


def _seed_fixture(backend: RealClientBackend, source: tuple[int, int, int],
                  origin: tuple[int, int, int]) -> None:
    # This is deliberately a list of real player commands. The bridge allowlist only
    # accepts these item IDs and positions close to the player in the exact disposable
    # world; it never invokes a server method or writes the save from Python.
    backend.request("player.command", {"command":
        "item replace entity @s weapon.mainhand with steve_create_agent:engineer_terminal"})
    recover_stale_work(backend)
    # Leave the terminal closed before the next sneak-use gesture. The world marker
    # command is allowed to report "already marked"; that is a safe idempotent state
    # for the disposable world and does not grant any extra authority.
    backend.request("screen.close")
    backend.request("player.command", {"command": "industrialagent setup mark-test-world"})
    _teleport(backend, source[0], source[1] + 1, source[2] - 3)
    # The courier's stand graph is deliberately conservative: it accepts a flat
    # neighbour and a one-block horizontal transition whose destination has a
    # sturdy floor and two clear blocks.  The disposable fixture therefore lays
    # down a short, explicit stair dip and rise before the source.  This makes the
    # real client gate prove both directions in one route; it does not grant the
    # courier terrain-editing authority or make it jump through unloaded blocks.
    if abs(source[1] - origin[1]) > 4:
        raise BridgeError("FIXTURE_VERTICAL_DELTA_TOO_LARGE",
                          f"source_y={source[1]} origin_y={origin[1]}")
    sx = 1 if source[0] >= origin[0] else -1
    start = (origin[0], origin[1], origin[2] - 3)
    source_access = (source[0], source[1], source[2] + 1)
    staging = (origin[0] - 3, origin[1], origin[2] - 2)
    staging_access = (staging[0] + 1, staging[1], staging[2])
    route_stands: list[tuple[int, int, int]] = [start]

    def append_stand(value: tuple[int, int, int]) -> None:
        if value not in route_stands:
            route_stands.append(value)

    # Force a down-then-up pair when the endpoints are level.  If a caller uses
    # different endpoint heights, the same horizontal run climbs/descends one
    # block at a time instead of refusing the fixture outright.
    current_x, current_y, current_z = start
    if source[1] == origin[1] and abs(source[0] - origin[0]) >= 3:
        for dx, y in ((sx, current_y - 1), (2 * sx, current_y - 1),
                      (3 * sx, current_y)):
            append_stand((origin[0] + dx, y, current_z))
        current_x, current_y = origin[0] + 3 * sx, origin[1]

    while current_x != source_access[0]:
        current_x += sx
        if current_y != source_access[1]:
            current_y += 1 if source_access[1] > current_y else -1
        append_stand((current_x, current_y, current_z))
    while current_z != source_access[2]:
        current_z += 1 if source_access[2] > current_z else -1
        if current_y != source_access[1]:
            current_y += 1 if source_access[1] > current_y else -1
        append_stand((current_x, current_y, current_z))
    if (current_x, current_y, current_z) != source_access:
        raise BridgeError("FIXTURE_SOURCE_ACCESS_UNREACHABLE",
                          f"source={source} access={source_access}")

    # The courier returns to the staging chest.  Keep a separate flat branch to
    # the staging access cell so the same run also exercises the reverse of the
    # forced stair pair during delivery.
    current_x, current_y, current_z = start
    while current_x != staging_access[0]:
        current_x += 1 if staging_access[0] > current_x else -1
        append_stand((current_x, current_y, current_z))
    while current_z != staging_access[2]:
        current_z += 1 if staging_access[2] > current_z else -1
        append_stand((current_x, current_y, current_z))
    append_stand(staging_access)
    route_floor = [(x, y - 1, z) for x, y, z in route_stands]
    # The real source-selection gesture teleports the player three blocks behind
    # the chest.  Give that player-originated interaction a bounded landing pad so
    # gravity cannot move the start cell before the UseOn packet is sent.
    route_floor.append((source[0], source[1], source[2] - 3))
    for x, y, z in route_floor:
        backend.request("player.command", {"command":
            f"setblock {x} {y} {z} stone replace"})
    backend.request("player.command", {"command":
        f"setblock {source[0]} {source[1]} {source[2]} chest replace"})
    # Stand three blocks behind the selected site without falling back to the terrain;
    # this keeps the real server's container reach check valid while the machine cells
    # themselves remain an untouched air volume above the fixture floor.  The final
    # route cell at origin-3 is also the courier spawn landing.
    for slot, item, count in METAL_PRESS_BOM:
        backend.request("player.command", {"command":
            f"item replace block {source[0]} {source[1]} {source[2]} "
            f"container.{slot} with {item} {count}"})
    time.sleep(0.5)


def _seed_composite_fixture(
    backend: RealClientBackend, order_type: str, origin: tuple[int, int, int]) -> None:
    """Prepare, but never place, a Composite order in the disposable world."""
    recover_stale_work(backend)
    _close_screen_if_open(backend)
    backend.request("player.command", {"command":
                                        "item replace entity @s weapon.mainhand with "
                                        "steve_create_agent:engineer_terminal"})
    backend.request("player.command", {"command": "industrialagent setup mark-test-world"})
    _teleport(backend, origin[0], origin[1], origin[2])
    nonce = secrets.token_hex(16)
    backend.request("player.command", {"command":
                                        f'industrialagent acceptance composite-fixture "{order_type}" {nonce}'})
    _wait_composite_fixture_markers(backend, order_type, origin, nonce=nonce, timeout=10.0)


def _wait_composite_fixture_markers(
    backend: RealClientBackend,
    order_type: str,
    origin: tuple[int, int, int],
    *,
    nonce: str,
    timeout: float,
) -> dict[str, Any]:
    """Require a fresh server completion marker plus two client-visible chests.

    Vanilla does not synchronize an unopened chest's inventory or arbitrary persistent
    BlockEntity data to the client.  The dev-only fixture writes a nonce-bound server log
    only after both chests are seeded; the real client must independently observe both
    physical chests.  This is the same two-sided evidence contract used by C-03.
    """
    primary = (origin[0], origin[1], origin[2] - 8)
    secondary = (origin[0], origin[1], origin[2] - 6)
    log_path = Path(backend.session.client_log or "")
    expected_log_root = (backend.paths.state_root / "logs").resolve()
    try:
        resolved_log = log_path.resolve(strict=True)
    except (FileNotFoundError, OSError) as failure:
        raise BridgeError("COMPOSITE_CLIENT_LOG_UNAVAILABLE", str(log_path)) from failure
    if log_path.is_symlink() or not resolved_log.is_relative_to(expected_log_root):
        raise BridgeError("COMPOSITE_CLIENT_LOG_REFUSED", str(resolved_log))
    prepared_pattern = re.compile(
        rf"COMPOSITE_CLIENT_FIXTURE_PREPARED orderType={re.escape(order_type)} "
        rf"target=\S+ quantity=\d+ "
        rf"origin=BlockPos\{{x={origin[0]}, y={origin[1]}, z={origin[2]}\}} "
        rf"primary=BlockPos\{{x={primary[0]}, y={primary[1]}, z={primary[2]}\}} "
        rf"secondary=BlockPos\{{x={secondary[0]}, y={secondary[1]}, z={secondary[2]}\}} "
        rf"nonce={re.escape(nonce)}")
    refused_marker = (
        f"COMPOSITE_CLIENT_FIXTURE_REFUSED orderType={order_type} nonce={nonce}"
    )
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        log_text = resolved_log.read_text(encoding="utf-8", errors="replace")
        refusal_index = log_text.find(refused_marker)
        if refusal_index >= 0:
            detail = log_text[refusal_index:log_text.find("\n", refusal_index)]
            raise BridgeError("COMPOSITE_FIXTURE_REFUSED", detail)
        prepared = prepared_pattern.search(log_text)
        primary_probe = backend.request("world.inspect", {
            "x": primary[0], "y": primary[1], "z": primary[2], "radius": 0})
        secondary_probe = backend.request("world.inspect", {
            "x": secondary[0], "y": secondary[1], "z": secondary[2], "radius": 0})
        last = {"primary": primary_probe, "secondary": secondary_probe}
        primary_is_chest = any(row.get("block") == "minecraft:chest"
                               for row in primary_probe.get("blocks", []))
        secondary_is_chest = any(row.get("block") == "minecraft:chest"
                                 for row in secondary_probe.get("blocks", []))
        if prepared and primary_is_chest and secondary_is_chest:
            return {"status": "COMPOSITE_FIXTURE_READY", "nonce": nonce,
                    "primary": primary_probe, "secondary": secondary_probe}
        time.sleep(0.1)
    raise BridgeError("COMPOSITE_FIXTURE_NOT_PREPARED", str(last))


def _seed_c03_fixture(backend: RealClientBackend, origin: tuple[int, int, int], *,
                      target: str = C03_TARGET, quantity: int = C03_QUANTITY) -> None:
    """Prepare the live bill through player commands; C03 names retain log compatibility."""
    recover_stale_work(backend)
    _close_screen_if_open(backend)
    backend.request("player.command", {"command":
        "item replace entity @s weapon.mainhand with steve_create_agent:engineer_terminal"})
    backend.request("player.command", {"command": "industrialagent setup mark-test-world"})
    _teleport(backend, origin[0], origin[1], origin[2])
    _wait_player_horizontal(backend, origin[0], origin[2], timeout=5.0)
    nonce = secrets.token_hex(16)
    backend.request("player.command", {"command":
        f'industrialagent acceptance create-fixture "{target}" {quantity} {nonce}'})
    _wait_c03_fixture_markers(backend, origin, target=target, quantity=quantity,
                              nonce=nonce, timeout=10.0)


def _confirm_placement_when_selected(
    backend: RealClientBackend, *, timeout: float,
) -> dict[str, Any]:
    """Wait for the production client tick to own the looked-at placement anchor."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            return backend.request("player.confirm_placement")
        except BridgeError as failure:
            if failure.code != "PLACEMENT_ANCHOR_NOT_SELECTED":
                raise
        time.sleep(0.1)
    raise BridgeError("PLACEMENT_ANCHOR_NOT_SELECTED", "client tick timeout")


def _wait_player_horizontal(
    backend: RealClientBackend, x: int, z: int, *, timeout: float,
) -> dict[str, Any]:
    """Wait until the real client has applied the server teleport packet."""
    deadline = time.monotonic() + timeout
    expected_x = x + 0.5
    expected_z = z + 0.5
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = backend.request("client.status")
        if abs(float(last.get("player_x", float("inf"))) - expected_x) < 0.01 \
                and abs(float(last.get("player_z", float("inf"))) - expected_z) < 0.01:
            return last
        time.sleep(0.05)
    raise BridgeError("PLAYER_TELEPORT_NOT_APPLIED", str(last))


def _wait_c03_fixture_markers(
    backend: RealClientBackend,
    origin: tuple[int, int, int],
    *,
    nonce: str,
    timeout: float,
    target: str = C03_TARGET,
    quantity: int = C03_QUANTITY,
) -> dict[str, Any]:
    """Prove this exact server fixture completed and both chests reached the client.

    Vanilla deliberately does not synchronize unopened chest inventories or arbitrary
    BlockEntity persistent data to the client.  The authoritative fixture therefore
    emits a nonce-bound structured server log only after seeding both chests; the client
    independently has to observe both physical chest blocks.  The random nonce prevents
    an older disposable-world fixture from satisfying this run.
    """
    source = (origin[0], origin[1], origin[2] + C03_SOURCE_Z_OFFSET)
    salvage = (origin[0], origin[1], origin[2] + C03_SALVAGE_Z_OFFSET)
    log_path = Path(backend.session.client_log or "")
    expected_log_root = (backend.paths.state_root / "logs").resolve()
    try:
        resolved_log = log_path.resolve(strict=True)
    except (FileNotFoundError, OSError) as failure:
        raise BridgeError("C03_CLIENT_LOG_UNAVAILABLE", str(log_path)) from failure
    if log_path.is_symlink() or not resolved_log.is_relative_to(expected_log_root):
        raise BridgeError("C03_CLIENT_LOG_REFUSED", str(resolved_log))
    prepared_pattern = re.compile(
        rf"C03_CLIENT_FIXTURE_PREPARED target={re.escape(target)} "
        rf"quantity={quantity} .*source=BlockPos\{{x={source[0]}, y={source[1]}, z={source[2]}\}} "
        rf"salvage=BlockPos\{{x={salvage[0]}, y={salvage[1]}, z={salvage[2]}\}} "
        rf"planHash=([0-9a-f]{{64}}) nonce={re.escape(nonce)}")
    refused_marker = f"C03_CLIENT_FIXTURE_REFUSED target={target} quantity={quantity} nonce={nonce}"
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        log_text = resolved_log.read_text(encoding="utf-8", errors="replace")
        refusal_index = log_text.find(refused_marker)
        if refusal_index >= 0:
            detail = log_text[refusal_index:log_text.find("\n", refusal_index)]
            raise BridgeError("C03_FIXTURE_REFUSED", detail)
        prepared = prepared_pattern.search(log_text)
        source_probe = backend.request("world.inspect", {
            "x": source[0], "y": source[1], "z": source[2], "radius": 0})
        salvage_probe = backend.request("world.inspect", {
            "x": salvage[0], "y": salvage[1], "z": salvage[2], "radius": 0})
        last = {"source": source_probe, "salvage": salvage_probe}
        source_is_chest = any(row.get("block") == "minecraft:chest"
                              for row in source_probe.get("blocks", []))
        salvage_is_chest = any(row.get("block") == "minecraft:chest"
                               for row in salvage_probe.get("blocks", []))
        if prepared and source_is_chest and salvage_is_chest:
            return {"status": "C03_FIXTURE_READY", "nonce": nonce,
                    "planHash": prepared.group(1), "source": source_probe,
                    "salvage": salvage_probe}
        time.sleep(0.1)
    raise BridgeError("C03_FIXTURE_NOT_PREPARED", str(last))


def _fixture_marker(probe: dict[str, Any], role: str, nonce: str) -> dict[str, Any] | None:
    blocks = probe.get("blocks", [])
    for row in blocks:
        marker = row.get("fixture")
        if isinstance(marker, dict) and marker.get("kind") == "steve_agent_c03_fixture":
            if marker.get("role") == role and marker.get("target") == C03_TARGET \
                    and int(marker.get("quantity", -1)) == C03_QUANTITY \
                    and len(str(marker.get("planHash", ""))) == 64 \
                    and marker.get("nonce") == nonce:
                return marker
    return None


def _select_goal(backend: RealClientBackend, target: str, timeout: float = 20.0) -> None:
    result = backend.request("screen.select_target", {"target": target})
    deadline = time.monotonic() + timeout
    while result.get("status") == "TARGET_RESULTS_PENDING" and time.monotonic() < deadline:
        time.sleep(0.1)
        result = backend.request("screen.select_target", {"target": target})
    if result.get("status") != "TARGET_SELECTED":
        raise BridgeError("TARGET_SELECTION_TIMEOUT", target)


def _wait_for_predicate(backend: RealClientBackend,
                        predicate: Callable[[dict[str, Any]], bool],
                        *, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = backend.request("screen.inspect")
        if predicate(last):
            return last
        time.sleep(0.1)
    raise BridgeError("SCREEN_PREDICATE_TIMEOUT", str(last))


def _close_screen_if_open(backend: RealClientBackend) -> None:
    inspected = backend.request("screen.inspect")
    if inspected.get("screen_class") is not None:
        backend.request("screen.close")


def _assert_source(backend: RealClientBackend, source: tuple[int, int, int]) -> None:
    inspected = backend.request("world.inspect", {"x": source[0], "y": source[1],
                                                   "z": source[2], "radius": 0})
    blocks = inspected.get("blocks", [])
    chest = next((row for row in blocks if row.get("block") == "minecraft:chest"), None)
    if chest is None:
        # The integrated server can acknowledge a command and the client can still be
        # one block-entity packet behind (especially after replacing a chest in-place).
        # Do not turn that transport lag into a fake source refusal; the real UseOn packet
        # and the server-side material scan remain authoritative and will produce a
        # terminal failure if the container truly is absent.
        return
    # A command-filled block entity may be updated on the integrated server before
    # the client receives a fresh block-entity packet. The server-side reservation
    # remains the authority; the final order counters are the hard assertion. Keep
    # the visible slot snapshot as evidence without turning this transport lag into
    # a false source failure.


def _assert_chest(backend: RealClientBackend, source: tuple[int, int, int]) -> None:
    inspected = backend.request("world.inspect", {"x": source[0], "y": source[1],
                                                   "z": source[2], "radius": 0})
    chest = next((row for row in inspected.get("blocks", [])
                  if row.get("block") == "minecraft:chest"), None)
    if chest is None:
        raise BridgeError("COMPOSITE_FIXTURE_SOURCE_MISSING", str(source))


def _wait_screen(backend: RealClientBackend, name: str, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = backend.request("screen.inspect")
        if last.get("screen_class") == name:
            return last
        time.sleep(0.1)
    raise BridgeError("SCREEN_WAIT_TIMEOUT", f"expected={name}, actual={last.get('screen_class')}")


def _capture(backend: RealClientBackend, stage: str,
             *, prefix: str = "steve-agent-metalpress") -> dict[str, Any]:
    safe_stage = "".join(ch.lower() if ch.isalnum() else "_" for ch in stage).strip("_")
    filename = f"{prefix}-{safe_stage or 'unknown'}-{int(time.time())}.png"
    screenshot = backend.request("screenshot.capture", {"filename": filename})
    manifest = publish_screen_bundle(
        Path(screenshot["path"]), backend.request("client.status"),
        root_dir=backend.paths.state_root / "previews",
        command=f"scenario metalpress checkpoint {stage}", force=True,
    )
    return {"screenshot": screenshot, "preview": manifest}


def _position(value: tuple[int, int, int]) -> dict[str, int]:
    return {"x": value[0], "y": value[1], "z": value[2]}


def _teleport(backend: RealClientBackend, x: int, y: int, z: int) -> None:
    backend.request("player.command", {"command": f"tp @s {x} {y} {z}"})
