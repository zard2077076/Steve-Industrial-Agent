from __future__ import annotations

import json
import socket
import uuid
from pathlib import Path
from typing import Any

from .policy import ENDPOINT_SCHEMA, PolicyError, validate_token


MAX_WIRE_BYTES = 65_536
MAX_COMMAND_CHARS = 96
MAX_ARGUMENT_CHARS = 8_192


class BridgeError(RuntimeError):
    def __init__(self, code: str, detail: str = "") -> None:
        super().__init__(f"{code}{': ' + detail if detail else ''}")
        self.code = code
        self.detail = detail


def load_endpoint(path: Path) -> dict[str, Any]:
    try:
        with open(path.expanduser().resolve(), "r", encoding="utf-8") as handle:
            endpoint = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise BridgeError("BRIDGE_ENDPOINT_UNAVAILABLE", str(path)) from exc
    if endpoint.get("protocol_version") != ENDPOINT_SCHEMA:
        raise BridgeError("BRIDGE_PROTOCOL_MISMATCH", str(endpoint.get("protocol_version")))
    if endpoint.get("host") != "127.0.0.1":
        raise BridgeError("BRIDGE_NOT_LOOPBACK", str(endpoint.get("host")))
    if "token" in endpoint:
        raise BridgeError("BRIDGE_ENDPOINT_LEAKS_TOKEN")
    port = endpoint.get("port")
    if not isinstance(port, int) or not 1 <= port <= 65_535:
        raise BridgeError("BRIDGE_PORT_INVALID", str(port))
    return endpoint


def encode_request(token: str, command: str, args: dict[str, Any] | None = None) -> bytes:
    validate_token(token)
    if not command or len(command) > MAX_COMMAND_CHARS or "\n" in command or "\r" in command:
        raise PolicyError("BRIDGE_COMMAND_INVALID", command[:128])
    arguments = args or {}
    encoded_args = json.dumps(arguments, ensure_ascii=False, separators=(",", ":"))
    if len(encoded_args) > MAX_ARGUMENT_CHARS or "\n" in encoded_args or "\r" in encoded_args:
        raise PolicyError("BRIDGE_ARGUMENTS_INVALID")
    payload = json.dumps({
        "request_id": str(uuid.uuid4()),
        "token": token,
        "command": command,
        "args": arguments,
    }, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
    if len(payload) > MAX_WIRE_BYTES:
        raise PolicyError("BRIDGE_REQUEST_TOO_LARGE")
    return payload


class BridgeClient:
    def __init__(self, endpoint_file: Path, token: str, timeout: float = 8.0) -> None:
        self.endpoint_file = endpoint_file
        self.token = token
        self.timeout = timeout

    def request(self, command: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        endpoint = load_endpoint(self.endpoint_file)
        payload = encode_request(self.token, command, args)
        try:
            with socket.create_connection(("127.0.0.1", endpoint["port"]), self.timeout) as channel:
                channel.settimeout(self.timeout)
                channel.sendall(payload)
                reader = channel.makefile("rb")
                response_line = reader.readline(MAX_WIRE_BYTES + 1)
        except (OSError, TimeoutError) as exc:
            raise BridgeError("BRIDGE_UNREACHABLE", str(exc)) from exc
        if not response_line or len(response_line) > MAX_WIRE_BYTES or not response_line.endswith(b"\n"):
            raise BridgeError("BRIDGE_RESPONSE_INVALID")
        try:
            response = json.loads(response_line)
        except json.JSONDecodeError as exc:
            raise BridgeError("BRIDGE_RESPONSE_INVALID", str(exc)) from exc
        if not response.get("ok"):
            raise BridgeError(str(response.get("code", "BRIDGE_COMMAND_FAILED")),
                              str(response.get("detail", "")))
        result = response.get("result")
        if not isinstance(result, dict):
            raise BridgeError("BRIDGE_RESPONSE_INVALID", "result must be an object")
        return result
