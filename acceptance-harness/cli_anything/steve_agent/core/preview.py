from __future__ import annotations

import hashlib
import json
import shutil
import struct
from pathlib import Path
from typing import Any

from .. import __version__
from ..utils.preview_bundle import (
    artifact_record,
    finalize_bundle,
    find_latest_manifest,
    fingerprint_data,
    prepare_bundle,
)


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SOFTWARE = "steve-agent"
SCREEN_RECIPE = "screen"


def png_info(path: Path) -> dict[str, Any]:
    resolved = path.expanduser().resolve()
    with open(resolved, "rb") as handle:
        header = handle.read(24)
    if len(header) != 24 or not header.startswith(PNG_SIGNATURE) or header[12:16] != b"IHDR":
        raise ValueError(f"PNG_INVALID: {resolved}")
    width, height = struct.unpack(">II", header[16:24])
    if width < 1 or height < 1:
        raise ValueError(f"PNG_DIMENSIONS_INVALID: {width}x{height}")
    digest = hashlib.sha256(resolved.read_bytes()).hexdigest()
    return {"path": str(resolved), "width": width, "height": height,
            "bytes": resolved.stat().st_size, "sha256": digest}


def publish_screen_bundle(
    screenshot: Path,
    status: dict[str, Any],
    *,
    root_dir: Path,
    command: str,
    force: bool = False,
) -> dict[str, Any]:
    source_info = png_info(screenshot)
    source_fingerprint = fingerprint_data({
        "png_sha256": source_info["sha256"],
        "world_name": status.get("world_name"),
        "screen_class": status.get("screen_class"),
    })
    prepared = prepare_bundle(
        software=SOFTWARE,
        recipe=SCREEN_RECIPE,
        bundle_kind="capture",
        source_fingerprint=source_fingerprint,
        options={"native_minecraft_screenshot": True},
        harness_version=__version__,
        root_dir=str(root_dir),
        force=force,
    )
    if prepared["cached"]:
        manifest = prepared["manifest"]
        manifest["cached"] = True
        return manifest
    artifact_path = Path(prepared["artifacts_dir"]) / "hero.png"
    shutil.copy2(screenshot, artifact_path)
    artifact_info = png_info(artifact_path)
    artifact = artifact_record(
        prepared["bundle_dir"], str(artifact_path), "hero", "hero", "image",
        "Minecraft client framebuffer", "image/png",
        width=artifact_info["width"], height=artifact_info["height"],
    )
    summary = {
        "headline": "Minecraft native client screenshot captured",
        "facts": {
            "world_name": status.get("world_name"),
            "screen_class": status.get("screen_class"),
            "resolution": f"{artifact_info['width']}x{artifact_info['height']}",
            "sha256": artifact_info["sha256"],
        },
        "warnings": [],
        "next_actions": ["Inspect layout and readability; automation is not human UX approval."],
    }
    manifest = finalize_bundle(
        bundle_dir=prepared["bundle_dir"],
        bundle_id=prepared["bundle_id"],
        bundle_kind="capture",
        software=SOFTWARE,
        recipe=SCREEN_RECIPE,
        source={
            "world_name": status.get("world_name"),
            "screen_class": status.get("screen_class"),
            "capture_fingerprint": source_fingerprint,
            "native_screenshot_path": str(screenshot.resolve()),
        },
        artifacts=[artifact],
        summary=summary,
        cache_key=prepared["cache_key"],
        generator={
            "entry_point": "cli-anything-steve-agent",
            "harness_version": __version__,
            "backend": "Minecraft Screenshot.grab",
            "command": command,
        },
        context={"client_status": status},
        metrics={"artifact_count": 1},
        labels=["real-forge-client", "mac-acceptance", "native-screenshot"],
    )
    manifest["cached"] = False
    return manifest


def latest_screen_bundle(root_dir: Path) -> dict[str, Any]:
    manifest = find_latest_manifest(SOFTWARE, SCREEN_RECIPE, root_dir=str(root_dir))
    if manifest is None:
        raise FileNotFoundError("PREVIEW_NOT_FOUND")
    return manifest


def verify_bundle(manifest_path: Path) -> dict[str, Any]:
    with open(manifest_path.expanduser().resolve(), "r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    if manifest.get("protocol_version") != "preview-bundle/v1":
        raise ValueError("PREVIEW_PROTOCOL_INVALID")
    root = manifest_path.expanduser().resolve().parent
    verified = []
    for artifact in manifest.get("artifacts", []):
        path = (root / artifact["path"]).resolve()
        path.relative_to(root)
        if not path.is_file():
            raise FileNotFoundError(path)
        if artifact.get("media_type") == "image/png":
            png_info(path)
        verified.append(str(path))
    if not verified:
        raise ValueError("PREVIEW_ARTIFACTS_EMPTY")
    return {"status": "PASS", "manifest_path": str(manifest_path.resolve()),
            "artifact_count": len(verified), "artifacts": verified}
