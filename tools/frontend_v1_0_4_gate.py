#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/v1.0.4/source"
APP = ANDROID / "app/src/main/java/dev/securemesh/commander"

failures: list[str] = []
passes = 0


def ok(name: str, cond: bool, detail: str = "") -> None:
    global passes
    if cond:
        passes += 1
        print(f"PASS  {name}")
    else:
        failures.append(f"{name}: {detail}".rstrip(": "))
        print(f"FAIL  {name}" + (f" — {detail}" if detail else ""))


def text(rel: str) -> str:
    p = APP / rel
    return p.read_text(encoding="utf-8") if p.is_file() else ""


def main() -> int:
    build = (ANDROID / "app/build.gradle.kts").read_text(encoding="utf-8")
    root_nav = text("navigation/SecureMeshRoot.kt")
    more = text("feature/more/MoreScreen.kt")
    codec = text("data/ble/SecureMeshBleCodec.kt")
    transport = text("data/ble/BleTransport.kt")
    repo = text("domain/repository/SecureMeshRepository.kt")
    control = text("feature/deviceui/DeviceControlScreen.kt")
    control_vm = text("feature/deviceui/DeviceControlViewModel.kt")
    vanguard = text("feature/vanguard/VanguardControlScreen.kt")

    ok("APK versionName is 1.0.4", 'versionName = "1.0.4"' in build)
    ok("APK versionCode is 20", "versionCode = 20" in build)

    production_text = "\n".join(
        p.read_text(encoding="utf-8", errors="ignore")
        for p in (APP / "feature").rglob("*.kt")
    )
    for stale in ("v0.8.2", "0.6.3", "v0.5", "0.9-demo", "v0.9.0"):
        ok(f"No stale frontend identity {stale}", stale not in production_text)

    # VANGUARD engineering tooling remains compiled and versioned, but is no longer
    # an operator navigation surface. This preserves lab capability without exposing
    # Fault Lab / manifest / forced route controls as a normal user-facing "remote".
    ok("VANGUARD engineering screen retained", "Управление SecureMesh v1.0.4" in vanguard and "Fault Lab" in vanguard)

    required_routes = {
        "home", "nodes", "node/{id}", "messages", "messages/{peer}", "map", "more",
        "devicecontrol", "topology", "routes", "fieldtest", "events",
        "diagnostics", "bleradar", "security", "settings", "search",
    }
    found_routes = set(re.findall(r'composable\("([^"]+)"\)', root_nav))
    for route in sorted(required_routes):
        ok(f"Frontend route {route}", route in found_routes)
    ok("Operator navigation hides VANGUARD engineering route", "vanguard" not in found_routes)
    ok("Primary operator navigation exposes Map", 'NavItem("map", "Карта"' in root_nav)
    ok("Primary operator navigation has no legacy Pult", '"Пульт"' not in root_nav)

    surface_checks = {
        "Messaging UI": ("feature/messages/MessagesScreen.kt", "send"),
        "Nodes UI": ("feature/nodes/NodesScreen.kt", "NodesScreen"),
        "Topology UI": ("feature/network/TopologyScreen.kt", "Topology"),
        "Routes UI": ("feature/routes/RoutesScreen.kt", "RoutesScreen"),
        "Field Test UI": ("feature/fieldtest/FieldTestScreen.kt", "FieldTest"),
        "Offline map UI": ("feature/map/MapScreen.kt", "MapScreen"),
        "SOS UI": ("feature/sos/SosOverlay.kt", "Sos"),
        "VANGUARD engineering UI": ("feature/vanguard/VanguardControlScreen.kt", "Fault Lab"),
        "Current node screen UI": ("feature/deviceui/DeviceControlScreen.kt", "Кнопки управления"),
        "BLE Radar UI": ("feature/radar/BleRadarScreen.kt", "Радар"),
        "Diagnostics UI": ("feature/diagnostics/DiagnosticsScreen.kt", "Diagnostics"),
        "Security UI": ("feature/security/SecurityCenterScreen.kt", "Security"),
        "Search UI": ("feature/search/SearchScreen.kt", "Search"),
        "Settings UI": ("feature/settings/SettingsScreen.kt", "Settings"),
    }
    for name, (rel, token) in surface_checks.items():
        body = text(rel)
        ok(name, bool(body) and token.lower() in body.lower(), rel)

    expected_base_commands = [
        "GET_INFO", "GET_STATUS", "GET_NEIGHBORS", "GET_ROUTES", "SEND_MESSAGE",
        "ADD_STATIC_ROUTE", "REMOVE_STATIC_ROUTE", "START_FIELD_TEST", "STOP_FIELD_TEST",
        "GET_FIELD_TEST_STATUS", "PING_LOCAL", "CLEAR_STATS", "GET_UI_STATE", "UI_ACTION",
        "GET_KNOWN_NODES", "GET_MANIFEST", "SET_MANIFEST", "DISCOVER_ROUTE",
        "GET_ROUTING_DIAGNOSTICS", "INJECT_LINK_FAILURE", "CLEAR_DYNAMIC_ROUTES",
        "SET_LAB_LINK_POLICY", "GET_LAB_LINK_POLICIES", "GET_POSITIONS", "RAISE_SOS",
        "ACK_SOS", "SEND_COMMAND_NOTICE", "GET_BLE_RADAR", "CLEAR_BLE_RADAR",
        "GET_OPERATIONAL_HEALTH", "GET_SELF_DIAGNOSTICS",
    ]
    for command in expected_base_commands:
        ok(f"BLE command wired: {command}", codec.count(command) >= 1 and command in (codec + transport))
    ok("Exact OLED command wired: GET_OLED_FRAME_CHUNK=38", "GET_OLED_FRAME_CHUNK(38)" in codec and "GetOledFrameChunk" in transport)

    repository_actions = [
        "sendMessage", "addStaticRoute", "removeRoute", "startFieldTest", "stopFieldTest",
        "raiseSos", "acknowledgeSos", "sendCommandNotice", "refreshDeviceUiState",
        "sendDeviceUiAction", "refreshOledFramebuffer", "refreshVanguardState", "setManifest",
        "discoverRoute", "clearDynamicRoutes", "injectLinkFailure", "setLabLinkPolicy", "clearBleRadar",
    ]
    for action in repository_actions:
        ok(f"Repository action exposed: {action}", action in repo)

    for action in ("UP", "DOWN", "SELECT", "BACK", "HOME"):
        ok(f"Node screen button {action}", f"DeviceUiAction.{action}" in control)
    ok("Current node screen has exact framebuffer renderer", "ScreenPixelCanvas" in control and "pixelOn" in text("domain/model/DeviceUiModels.kt"))
    ok("Current node screen has state fallback", "СОСТОЯНИЕ МЕНЮ" in control and "exactMirrorAvailable" in control)
    ok("Exact mirror is capability-gated", "DeviceCapability.OLED_FRAMEBUFFER" in control_vm and "OLED_FRAMEBUFFER" in transport)
    ok("Exact framebuffer refresh is screen-scoped", "SCREEN_REFRESH_INTERVAL_MS = 1500L" in control and "delay(SCREEN_REFRESH_INTERVAL_MS)" in control and "refreshMirror" in control)
    ok("Remote action queue remains bounded", "Channel<DeviceUiAction>(capacity = 16)" in control_vm)
    ok("Remote actions still use repository boundary", "repository.sendDeviceUiAction(action)" in control_vm)

    forbidden_operator_terms = (
        "OLED CONTROL", "OLED MIRROR", "GET_UI_STATE", "PIXEL MIRROR", "STATE SYNC",
        "BLE Protocol", "UI_ACTION", "UI OS",
    )
    for token in forbidden_operator_terms:
        ok(f"Current node screen hides engineering term {token}", token not in control)

    more_routes = ["devicecontrol", "fieldtest", "events", "bleradar", "diagnostics", "security", "settings", "map", "search"]
    for route in more_routes:
        ok(f"More menu exposes {route}", f'"{route}"' in more)
    ok("More menu hides VANGUARD engineering panel", '"vanguard"' not in more)
    ok("More menu identifies modern node controls", "Экран и кнопки" in more)

    print(f"\n{passes} passed, {len(failures)} failed")
    if failures:
        for failure in failures:
            print(" -", failure)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
