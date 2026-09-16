from __future__ import annotations

import json
import os
import shlex
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import click

from . import __version__
from .core.backend import RealClientBackend, process_alive
from .core.policy import AcceptancePaths, PolicyError, default_paths, validate_paths
from .core.preview import (
    SCREEN_RECIPE,
    latest_screen_bundle,
    png_info,
    publish_screen_bundle,
    verify_bundle,
)
from .core.protocol import BridgeError
from .core.scenarios import (
    c03_plan,
    capability_plan,
    composite_plan,
    inspect_bot_checkpoints,
    metal_press_plan,
    multi_bot_plan,
    recover_stale_work,
    run_c03,
    run_create_player_flow,
    run_composite,
    run_metal_press,
    scenario_catalog,
)
from .core.session import HarnessSession, load_session, remove_authority, save_session
from .utils.repl_skin import ReplSkin


@dataclass
class Context:
    paths: AcceptancePaths
    session_file: Path
    use_json: bool
    dry_run: bool

    def session(self, *, create: bool = False) -> HarnessSession:
        if self.session_file.is_file():
            return load_session(self.session_file)
        if not create:
            raise PolicyError("SESSION_REQUIRED", str(self.session_file))
        session = HarnessSession.create(self.paths)
        if not self.dry_run:
            save_session(self.session_file, session)
        return session

    def backend(self) -> RealClientBackend:
        return RealClientBackend(self.paths, self.session_file, self.session())


def emit(ctx: Context, result: dict[str, Any], human: str | None = None) -> None:
    if ctx.use_json:
        click.echo(json.dumps(result, ensure_ascii=False, sort_keys=True))
    else:
        click.echo(human or json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))


def fail(exc: Exception) -> None:
    code = getattr(exc, "code", type(exc).__name__)
    detail = getattr(exc, "detail", str(exc))
    raise click.ClickException(f"{code}{': ' + detail if detail else ''}") from exc


@click.group(invoke_without_command=True)
@click.option("--json", "use_json", is_flag=True, help="Emit machine-readable JSON.")
@click.option("--session-file", type=click.Path(path_type=Path), default=None,
              help="Locked harness session JSON (defaults outside the repository).")
@click.option("--dry-run", is_flag=True,
              help="Validate and describe a mutation without sending it to Minecraft.")
@click.pass_context
def cli(click_ctx: click.Context, use_json: bool, session_file: Path | None, dry_run: bool) -> None:
    """Operate and verify Steve Agent through its real Forge development client."""
    paths = default_paths()
    click_ctx.obj = Context(paths, (session_file or paths.session_file).expanduser().resolve(),
                            use_json, dry_run)
    if click_ctx.invoked_subcommand is None:
        if use_json:
            emit(click_ctx.obj, {"status": "REPL_REQUIRES_HUMAN_OUTPUT"})
            return
        repl(click_ctx.obj)


@cli.command()
@click.pass_obj
def doctor(ctx: Context) -> None:
    """Validate the mandatory launcher, external game root and exact save."""
    try:
        emit(ctx, validate_paths(ctx.paths), "Steve Agent real-client harness is ready.")
    except Exception as exc:
        fail(exc)


@cli.group()
def session() -> None:
    """Manage random-token bridge authority."""


@session.command("create")
@click.option("--replace", is_flag=True, help="Replace only a stopped harness session.")
@click.pass_obj
def session_create(ctx: Context, replace: bool) -> None:
    try:
        validate_paths(ctx.paths)
        if ctx.session_file.exists() and not replace:
            raise PolicyError("SESSION_ALREADY_EXISTS", str(ctx.session_file))
        if ctx.session_file.exists() and replace:
            prior = load_session(ctx.session_file)
            if prior.launch_pid and process_alive(prior.launch_pid):
                raise PolicyError("CLIENT_ALREADY_RUNNING", str(prior.launch_pid))
        value = HarnessSession.create(ctx.paths)
        if not ctx.dry_run:
            save_session(ctx.session_file, value)
        public = value.public()
        public["status"] = "PLANNED" if ctx.dry_run else "CREATED"
        public["session_file"] = str(ctx.session_file)
        emit(ctx, public)
    except Exception as exc:
        fail(exc)


@session.command("status")
@click.pass_obj
def session_status(ctx: Context) -> None:
    try:
        emit(ctx, ctx.session().public())
    except Exception as exc:
        fail(exc)


@session.command("close")
@click.pass_obj
def session_close(ctx: Context) -> None:
    try:
        if ctx.session_file.exists():
            prior = load_session(ctx.session_file)
            if prior.launch_pid and process_alive(prior.launch_pid):
                raise PolicyError("CLIENT_STILL_RUNNING", str(prior.launch_pid))
        result = {"status": "PLANNED", "remove": [
            str(ctx.paths.endpoint_file), str(ctx.session_file)
        ]} if ctx.dry_run else remove_authority(ctx.paths)
        emit(ctx, result)
    except Exception as exc:
        fail(exc)


@cli.group()
def client() -> None:
    """Launch, inspect and stop the real Forge client."""


@client.command("launch")
@click.option("--wait/--no-wait", default=True, help="Wait for authenticated world-ready status.")
@click.option("--quick-play/--no-quick-play", default=True,
              help="Open only the exact existing disposable acceptance world.")
@click.option("--timeout", type=float, default=240.0, show_default=True)
@click.pass_obj
def client_launch(ctx: Context, wait: bool, quick_play: bool, timeout: float) -> None:
    try:
        session_value = ctx.session(create=True)
        backend = RealClientBackend(ctx.paths, ctx.session_file, session_value)
        result = backend.launch_plan(quick_play=quick_play) if ctx.dry_run else backend.launch(
            quick_play=quick_play)
        if wait and not ctx.dry_run:
            result["ready"] = backend.wait_ready(timeout, require_world=quick_play)
        emit(ctx, result)
    except Exception as exc:
        fail(exc)


@client.command("wait")
@click.option("--timeout", type=float, default=240.0, show_default=True)
@click.option("--require-world/--allow-menu", default=True)
@click.pass_obj
def client_wait(ctx: Context, timeout: float, require_world: bool) -> None:
    try:
        emit(ctx, ctx.backend().wait_ready(timeout, require_world=require_world))
    except Exception as exc:
        fail(exc)


@client.command("status")
@click.pass_obj
def client_status(ctx: Context) -> None:
    try:
        emit(ctx, ctx.backend().request("client.status"))
    except Exception as exc:
        fail(exc)


@client.command("stop")
@click.option("--timeout", type=float, default=60.0, show_default=True)
@click.pass_obj
def client_stop(ctx: Context, timeout: float) -> None:
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "client.stop"})
        else:
            emit(ctx, ctx.backend().stop(timeout))
    except Exception as exc:
        fail(exc)


@cli.group()
def screen() -> None:
    """Inspect and drive the current real Minecraft Screen."""


@screen.command("inspect")
@click.pass_obj
def screen_inspect(ctx: Context) -> None:
    try:
        emit(ctx, ctx.backend().request("screen.inspect"))
    except Exception as exc:
        fail(exc)


@screen.command("press")
@click.argument("widget_id")
@click.pass_obj
def screen_press(ctx: Context, widget_id: str) -> None:
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "screen.press",
                       "widget_id": widget_id})
        else:
            emit(ctx, ctx.backend().request("screen.press", {"widget_id": widget_id}))
    except Exception as exc:
        fail(exc)


@screen.command("text")
@click.argument("widget_id")
@click.argument("value")
@click.pass_obj
def screen_text(ctx: Context, widget_id: str, value: str) -> None:
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "screen.text",
                       "widget_id": widget_id, "value": value})
        else:
            emit(ctx, ctx.backend().request("screen.text", {
                "widget_id": widget_id, "value": value,
            }))
    except Exception as exc:
        fail(exc)


@screen.command("close")
@click.pass_obj
def screen_close(ctx: Context) -> None:
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "screen.close"})
        else:
            emit(ctx, ctx.backend().request("screen.close"))
    except Exception as exc:
        fail(exc)


@cli.group()
def player() -> None:
    """Send bounded player-originated client actions."""


@player.command("command")
@click.argument("command_text")
@click.pass_obj
def player_command(ctx: Context, command_text: str) -> None:
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.command",
                       "command": command_text})
        else:
            emit(ctx, ctx.backend().request("player.command", {"command": command_text}))
    except Exception as exc:
        fail(exc)


@player.command("teleport")
@click.argument("x", type=int)
@click.argument("y", type=int)
@click.argument("z", type=int)
@click.pass_obj
def player_teleport(ctx: Context, x: int, y: int, z: int) -> None:
    try:
        command_text = f"tp @s {x} {y} {z}"
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.command",
                       "command": command_text})
        else:
            emit(ctx, ctx.backend().request("player.command", {"command": command_text}))
    except Exception as exc:
        fail(exc)


@player.command("use-main-hand")
@click.pass_obj
def player_use_main_hand(ctx: Context) -> None:
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.use_main_hand"})
        else:
            emit(ctx, ctx.backend().request("player.use_main_hand"))
    except Exception as exc:
        fail(exc)


@player.command("sneak-use-main-hand")
@click.pass_obj
def player_sneak_use_main_hand(ctx: Context) -> None:
    """Use the real main-hand item with the client sneak bit set."""
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.sneak_use_main_hand"})
        else:
            emit(ctx, ctx.backend().request("player.sneak_use_main_hand"))
    except Exception as exc:
        fail(exc)


@player.command("confirm-placement")
@click.pass_obj
def player_confirm_placement(ctx: Context) -> None:
    """Confirm the selected placement anchor through the production input path."""
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.confirm_placement"})
        else:
            emit(ctx, ctx.backend().request("player.confirm_placement"))
    except Exception as exc:
        fail(exc)


@player.command("look-at")
@click.argument("x", type=int)
@click.argument("y", type=int)
@click.argument("z", type=int)
@click.pass_obj
def player_look_at(ctx: Context, x: int, y: int, z: int) -> None:
    """Aim the real client player at a bounded block position and refresh hit result."""
    try:
        args = {"x": x, "y": y, "z": z}
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.look_at", **args})
        else:
            emit(ctx, ctx.backend().request("player.look_at", args))
    except Exception as exc:
        fail(exc)


@player.command("bind-salvage")
@click.argument("x", type=int)
@click.argument("y", type=int)
@click.argument("z", type=int)
@click.pass_obj
def player_bind_salvage(ctx: Context, x: int, y: int, z: int) -> None:
    """Bind a real container through the active salvage-selection controller."""
    try:
        args = {"x": x, "y": y, "z": z}
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.bind_salvage", **args})
        else:
            emit(ctx, ctx.backend().request("player.bind_salvage", args))
    except Exception as exc:
        fail(exc)


@player.command("bind-material-source")
@click.argument("x", type=int)
@click.argument("y", type=int)
@click.argument("z", type=int)
@click.option("--face", type=click.Choice(["UP", "DOWN", "NORTH", "SOUTH", "WEST", "EAST"]),
              default="UP", show_default=True)
@click.pass_obj
def player_bind_material_source(ctx: Context, x: int, y: int, z: int, face: str) -> None:
    """Bind a material source through the active server-authoritative controller."""
    try:
        args = {"x": x, "y": y, "z": z, "face": face}
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.bind_material_source",
                       **args})
        else:
            emit(ctx, ctx.backend().request("player.bind_material_source", args))
    except Exception as exc:
        fail(exc)


@player.command("sneak-use-on")
@click.argument("x", type=int)
@click.argument("y", type=int)
@click.argument("z", type=int)
@click.option("--face", type=click.Choice(["UP", "DOWN", "NORTH", "SOUTH", "WEST", "EAST"]),
              default="UP", show_default=True)
@click.pass_obj
def player_sneak_use_on(ctx: Context, x: int, y: int, z: int, face: str) -> None:
    """Use the held item on a real block through the normal UseOn packet path."""
    try:
        args = {"x": x, "y": y, "z": z, "face": face}
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.sneak_use_on",
                       **args})
        else:
            emit(ctx, ctx.backend().request("player.sneak_use_on", args))
    except Exception as exc:
        fail(exc)


@cli.group()
def world() -> None:
    """Read-only probes of blocks, containers and entities visible to the client."""


@world.command("inspect")
@click.argument("x", type=int)
@click.argument("y", type=int)
@click.argument("z", type=int)
@click.option("--radius", type=click.IntRange(0, 3), default=0, show_default=True,
              help="Probe a compact client-visible cube (0-3); larger payloads are refused.")
@click.pass_obj
def world_inspect(ctx: Context, x: int, y: int, z: int, radius: int) -> None:
    try:
        args = {"x": x, "y": y, "z": z, "radius": radius}
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "world.inspect", **args})
        else:
            emit(ctx, ctx.backend().request("world.inspect", args))
    except Exception as exc:
        fail(exc)


@cli.group()
def terminal() -> None:
    """Drive Steve Agent's real terminal screens."""


@terminal.command("open")
@click.option("--timeout", type=float, default=10.0, show_default=True)
@click.pass_obj
def terminal_open(ctx: Context, timeout: float) -> None:
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "player.use_main_hand",
                       "expected_screen": "EngineerTerminalScreen"})
            return
        backend = ctx.backend()
        backend.request("player.use_main_hand")
        emit(ctx, wait_for_screen(backend, "EngineerTerminalScreen", timeout))
    except Exception as exc:
        fail(exc)


@cli.group()
def scenario() -> None:
    """Run evidence-producing real-client acceptance scenarios."""


@scenario.command("catalog")
@click.pass_obj
def scenario_catalog_command(ctx: Context) -> None:
    emit(ctx, {"status": "OK", "scenarios": scenario_catalog()})


@scenario.command("recover")
@click.option("--timeout", type=float, default=60.0, show_default=True)
@click.pass_obj
def scenario_recover_command(ctx: Context, timeout: float) -> None:
    """Recover stale disposable work through real player management screens."""
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "workflow": [
                "screen.inspect", "screen.press manage_project",
                "screen.press cancel_project", "screen.close", "terminal reopen",
            ], "timeout": timeout})
        else:
            emit(ctx, recover_stale_work(ctx.backend(), timeout=timeout))
    except Exception as exc:
        fail(exc)


@scenario.command("plan")
@click.argument("name", type=click.Choice(["metalpress", "c03", "c06-c10", "composite", "multi-bot"]))
@click.option("--capability", type=click.Choice(["c06", "c07", "c08", "c09", "c10"]),
              default="c06", show_default=True,
              help="Representative capability when planning c06-c10.")
@click.option("--order-type", default="steve_industrial:composite/01", show_default=True)
@click.option("--source-x", type=int, default=-600, show_default=True)
@click.option("--source-y", type=int, default=124, show_default=True)
@click.option("--source-z", type=int, default=-4, show_default=True)
@click.option("--origin-x", type=int, default=-606, show_default=True)
@click.option("--origin-y", type=int, default=124, show_default=True)
@click.option("--origin-z", type=int, default=0, show_default=True)
@click.option("--worker-count", type=click.IntRange(2, 5), default=2, show_default=True,
              help="Planned visible workers; does not spawn Bots.")
@click.pass_obj
def scenario_plan_command(ctx: Context, name: str, capability: str, order_type: str, source_x: int,
                          source_y: int, source_z: int, origin_x: int, origin_y: int,
                          origin_z: int, worker_count: int) -> None:
    if name == "metalpress":
        emit(ctx, metal_press_plan((source_x, source_y, source_z),
                                   (origin_x, origin_y, origin_z)))
    elif name == "c03":
        emit(ctx, c03_plan((origin_x, origin_y, origin_z)))
    elif name == "composite":
        emit(ctx, composite_plan(order_type, (origin_x, origin_y, origin_z)))
    elif name == "multi-bot":
        emit(ctx, multi_bot_plan((origin_x, origin_y, origin_z),
                                 worker_count=worker_count))
    elif name == "c06-c10":
        emit(ctx, capability_plan(capability, (origin_x, origin_y, origin_z)))
    else:
        emit(ctx, {"status": "PLANNED", "scenario": name,
                   "catalog": scenario_catalog()[name]})


@scenario.command("inspect-bots")
@click.option("--origin-x", type=int, default=-606, show_default=True)
@click.option("--origin-y", type=int, default=124, show_default=True)
@click.option("--origin-z", type=int, default=0, show_default=True)
@click.option("--radius", type=click.IntRange(0, 3), default=3, show_default=True)
@click.pass_obj
def scenario_inspect_bots_command(ctx: Context, origin_x: int, origin_y: int,
                                   origin_z: int, radius: int) -> None:
    """Read visible Bot/role/collision evidence at the bounded route checkpoints."""
    try:
        plan = multi_bot_plan((origin_x, origin_y, origin_z))
        checkpoints = [
            (row["name"], (row["position"]["x"], row["position"]["y"], row["position"]["z"]))
            for row in plan["checkpoints"]
        ]
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "scenario": "multi-bot",
                       "checkpoints": plan["checkpoints"], "radius": radius})
            return
        emit(ctx, inspect_bot_checkpoints(ctx.backend(), checkpoints, radius=radius))
    except Exception as exc:
        fail(exc)


@scenario.command("run")
@click.argument("name", type=click.Choice(["metalpress", "c03", "c06", "c07", "c08", "c09", "c10", "composite"]))
@click.option("--order-type", default="steve_industrial:composite/01", show_default=True)
@click.option("--source-x", type=int, default=-600, show_default=True)
@click.option("--source-y", type=int, default=124, show_default=True)
@click.option("--source-z", type=int, default=-4, show_default=True)
@click.option("--origin-x", type=int, default=-606, show_default=True)
@click.option("--origin-y", type=int, default=124, show_default=True)
@click.option("--origin-z", type=int, default=0, show_default=True)
@click.option("--prepare-fixture/--no-prepare-fixture", default=False,
              help="Seed only the exact disposable world using bounded player commands.")
@click.option("--timeout", type=float, default=300.0, show_default=True)
@click.option("--no-capture", is_flag=True, help="Skip native checkpoint screenshots.")
@click.pass_obj
def scenario_run_command(ctx: Context, name: str, order_type: str, source_x: int,
                         source_y: int, source_z: int, origin_x: int, origin_y: int, origin_z: int,
                         prepare_fixture: bool, timeout: float, no_capture: bool) -> None:
    try:
        source = (source_x, source_y, source_z)
        origin = (origin_x, origin_y, origin_z)
        if ctx.dry_run:
            result = (metal_press_plan(source, origin) if name == "metalpress"
                      else c03_plan(origin) if name == "c03"
                      else composite_plan(order_type, origin) if name == "composite"
                      else capability_plan(name, origin))
            result["prepare_fixture"] = prepare_fixture
            result["timeout"] = timeout
            emit(ctx, result)
            return
        if name == "metalpress":
            result = run_metal_press(ctx.backend(), source, origin,
                                     prepare_fixture=prepare_fixture, timeout=timeout,
                                     capture=not no_capture)
        elif name == "c03":
            result = run_c03(ctx.backend(), origin,
                             prepare_fixture=prepare_fixture, timeout=timeout,
                             capture=not no_capture)
        elif name == "composite":
            result = run_composite(ctx.backend(), order_type, origin,
                                   prepare_fixture=prepare_fixture, timeout=timeout,
                                   capture=not no_capture)
        else:
            result = run_create_player_flow(ctx.backend(), origin, scenario=name,
                                            prepare_fixture=prepare_fixture, timeout=timeout,
                                            capture=not no_capture)
        emit(ctx, result)
    except Exception as exc:
        fail(exc)


@terminal.command("select-product")
@click.argument("target")
@click.option("--timeout", type=float, default=10.0, show_default=True)
@click.pass_obj
def terminal_select_product(ctx: Context, target: str, timeout: float) -> None:
    try:
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "screen.select_target",
                       "target": target})
            return
        backend = ctx.backend()
        result = backend.request("screen.select_target", {"target": target})
        deadline = time.monotonic() + timeout
        while result.get("status") == "TARGET_RESULTS_PENDING" and time.monotonic() < deadline:
            time.sleep(0.1)
            result = backend.request("screen.select_target", {"target": target})
        if result.get("status") != "TARGET_SELECTED":
            raise BridgeError("TARGET_SELECTION_TIMEOUT", target)
        emit(ctx, result)
    except Exception as exc:
        fail(exc)


@cli.group()
def preview() -> None:
    """Publish truthful native-client preview bundles."""


@preview.command("recipes")
@click.pass_obj
def preview_recipes(ctx: Context) -> None:
    emit(ctx, {"recipes": [{
        "name": SCREEN_RECIPE,
        "backend": "Minecraft Screenshot.grab",
        "artifacts": ["hero:image/png"],
    }]})


@preview.command("capture")
@click.option("--filename", default=None, help="Safe filename inside Minecraft screenshots/.")
@click.option("--force", is_flag=True, help="Bypass bundle cache.")
@click.pass_obj
def preview_capture(ctx: Context, filename: str | None, force: bool) -> None:
    try:
        safe_name = filename or f"steve-agent-{time.strftime('%Y%m%d-%H%M%S')}.png"
        if ctx.dry_run:
            emit(ctx, {"status": "PLANNED", "bridge_command": "screenshot.capture",
                       "filename": safe_name, "recipe": SCREEN_RECIPE})
            return
        backend = ctx.backend()
        screenshot = backend.request("screenshot.capture", {"filename": safe_name})
        status = backend.request("client.status")
        manifest = publish_screen_bundle(
            Path(screenshot["path"]), status,
            root_dir=ctx.paths.state_root / "previews",
            command=f"cli-anything-steve-agent preview capture --filename {safe_name}",
            force=force,
        )
        emit(ctx, manifest)
    except Exception as exc:
        fail(exc)


@preview.command("latest")
@click.pass_obj
def preview_latest(ctx: Context) -> None:
    try:
        emit(ctx, latest_screen_bundle(ctx.paths.state_root / "previews"))
    except Exception as exc:
        fail(exc)


@cli.group(name="assert")
def assert_group() -> None:
    """Machine-check concrete client and artifact evidence."""


@assert_group.command("world")
@click.argument("world_name")
@click.pass_obj
def assert_world(ctx: Context, world_name: str) -> None:
    try:
        status = ctx.backend().request("client.status")
        if status.get("world_name") != world_name:
            raise BridgeError("WORLD_ASSERTION_FAILED", str(status.get("world_name")))
        emit(ctx, {"status": "PASS", "world_name": world_name})
    except Exception as exc:
        fail(exc)


@assert_group.command("screen")
@click.argument("screen_name")
@click.pass_obj
def assert_screen(ctx: Context, screen_name: str) -> None:
    try:
        inspected = ctx.backend().request("screen.inspect")
        if inspected.get("screen_class") != screen_name:
            raise BridgeError("SCREEN_ASSERTION_FAILED", str(inspected.get("screen_class")))
        emit(ctx, {"status": "PASS", "screen_class": screen_name})
    except Exception as exc:
        fail(exc)


@assert_group.command("target-visible")
@click.argument("target")
@click.pass_obj
def assert_target_visible(ctx: Context, target: str) -> None:
    try:
        inspected = ctx.backend().request("screen.inspect")
        targets = [row.get("target") for row in inspected.get("target_rows", [])]
        if target not in targets:
            raise BridgeError("TARGET_NOT_VISIBLE", target)
        emit(ctx, {"status": "PASS", "target": target, "visible_targets": targets})
    except Exception as exc:
        fail(exc)


@assert_group.command("png")
@click.argument("path", type=click.Path(path_type=Path, exists=True))
@click.pass_obj
def assert_png(ctx: Context, path: Path) -> None:
    try:
        emit(ctx, {"status": "PASS", **png_info(path)})
    except Exception as exc:
        fail(exc)


@assert_group.command("preview-bundle")
@click.argument("manifest", type=click.Path(path_type=Path, exists=True))
@click.pass_obj
def assert_preview_bundle(ctx: Context, manifest: Path) -> None:
    try:
        emit(ctx, verify_bundle(manifest))
    except Exception as exc:
        fail(exc)


def wait_for_screen(backend: RealClientBackend, suffix: str, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = backend.request("screen.inspect")
        if last.get("screen_class") == suffix:
            return last
        time.sleep(0.1)
    raise BridgeError("SCREEN_WAIT_TIMEOUT", f"expected={suffix}, actual={last.get('screen_class')}")


def repl(ctx: Context) -> None:
    skin = ReplSkin("steve-agent", version=__version__)
    skin.print_banner()
    prompt = skin.create_prompt_session()
    while True:
        try:
            line = skin.get_input(prompt, project_name=ctx.paths.world_name)
        except (EOFError, KeyboardInterrupt):
            break
        if not line.strip():
            continue
        if line.strip() in {"exit", "quit"}:
            break
        if line.strip() == "help":
            skin.help({
                "doctor": "validate the real-client boundary",
                "session": "manage token authority",
                "client": "launch/wait/status/stop",
                "screen": "inspect/press/text",
                "terminal": "open/select-product",
                "preview": "recipes/capture/latest",
                "assert": "verify world/screen/target/png/bundle",
            })
            continue
        try:
            args = shlex.split(line)
            if ctx.use_json:
                args.insert(0, "--json")
            if ctx.dry_run:
                args.insert(0, "--dry-run")
            cli.main(args=args, standalone_mode=False)
        except Exception as exc:
            skin.error(str(exc))
    skin.print_goodbye()


def main() -> None:
    try:
        cli()
    except (PolicyError, BridgeError) as exc:
        fail(exc)


if __name__ == "__main__":
    main()
