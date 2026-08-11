#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/dev/securemesh/commander"
FEATURE = MAIN / "feature"
MODEL = (MAIN / "domain/model/MeshModels.kt").read_text(encoding="utf-8")
MOCK = (MAIN / "data/mock/MockTransport.kt").read_text(encoding="utf-8")
REPO = (MAIN / "data/repository/SecureMeshRepositoryImpl.kt").read_text(encoding="utf-8")
POLICY = (MAIN / "domain/service/UiAccessPolicy.kt").read_text(encoding="utf-8")
BLE = (MAIN / "data/ble/BleTransport.kt").read_text(encoding="utf-8")
CODEC = (MAIN / "data/ble/SecureMeshBleCodec.kt").read_text(encoding="utf-8")
ENTITIES = (MAIN / "core/database/Entities.kt").read_text(encoding="utf-8")
WELCOME = (MAIN / "feature/welcome/WelcomeScreen.kt").read_text(encoding="utf-8")
NODES_VM = (MAIN / "feature/nodes/NodesViewModel.kt").read_text(encoding="utf-8")
DASHBOARD_VM = (MAIN / "feature/dashboard/DashboardViewModel.kt").read_text(encoding="utf-8")
DIAGNOSTICS_VM = (MAIN / "feature/diagnostics/DiagnosticsViewModel.kt").read_text(encoding="utf-8")
ALL_KT = list(MAIN.rglob("*.kt"))
ALL_TEXT = "\n".join(p.read_text(encoding="utf-8") for p in ALL_KT)
FEATURE_TEXT = "\n".join(p.read_text(encoding="utf-8") for p in FEATURE.rglob("*.kt"))
BUILD_TEXT = (ROOT / "app/build.gradle.kts").read_text() + "\n" + (ROOT / "gradle/libs.versions.toml").read_text()
TEST_TEXT = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "app/src/test").rglob("*.kt"))

passes=[]; failures=[]
def check(name, ok, detail=""):
    (passes if ok else failures).append((name, detail))

def class_block(text: str, declaration: str, next_decl: str | None = None) -> str:
    start = text.index(declaration)
    if next_decl and next_decl in text[start:]:
        end = text.index(next_decl, start)
        return text[start:end]
    return text[start:]

mesh_node = class_block(MODEL, "data class MeshNode(", "enum class LinkQuality")
topology = class_block(MODEL, "data class MeshTopology(", "enum class MessagePriority")
route = class_block(MODEL, "data class MeshRoute(", "data class MeshTopology")

# Core boundaries / no regression.
check("Repository/transport boundary retained", (MAIN/"domain/repository/SecureMeshRepository.kt").exists() and (MAIN/"data/transport/MeshTransport.kt").exists())
check("No direct GATT/scanner API in feature UI", not re.search(r"BluetoothGatt|BluetoothLeScanner|ScanCallback", FEATURE_TEXT))
check("No cloud/backend/analytics dependency", not re.search(r"firebase|appsflyer|amplitude|retrofit|ktor-client|analytics-sdk", BUILD_TEXT, re.I))
check("No hardcoded PIN/key", not re.search(r"\bPIN\s*=|123456|SECRET_KEY|PRIVATE_KEY|API_KEY", ALL_TEXT, re.I))

# Identity, role, capability, permission, session.
check("NodeIdentity model present", all(x in MODEL for x in ["data class NodeIdentity", "nodeId: NodeId", "displayName: String", "role: NodeRole", "capabilities: Set<DeviceCapability>"]))
check("Role and permission are separate enums", "enum class NodeRole" in MODEL and "enum class SessionPermission" in MODEL and "enum class DeviceCapability" in MODEL)
AUTH_SURFACES = POLICY + "\n" + MOCK + "\n" + (MAIN/"feature/messages/MessagesViewModel.kt").read_text() + "\n" + (MAIN/"feature/routes/RoutesViewModel.kt").read_text() + "\n" + (MAIN/"feature/fieldtest/FieldTestViewModel.kt").read_text() + "\n" + (MAIN/"navigation/SecureMeshRoot.kt").read_text()
check("No role-equals-authorization branch", not re.search(r"role\s*==\s*NodeRole\.(COMMANDER|ADMIN)", AUTH_SURFACES))
check("SecureMeshSession model present", all(x in MODEL for x in ["data class SecureMeshSession", "localNodeIdentity", "authenticationState", "grantedPermissions", "connectedSinceEpochMs"]))
check("BLE link and authenticated session distinct", "SecureSessionConnectionState" in MODEL and "BLE_CONNECTED" in MODEL and "SECURE_SESSION_ESTABLISHED" in MODEL)
check("UI visibility explicitly not authorization", "UI visibility" in MODEL and "security authority" in POLICY.lower())

# Node/link truth.
check("MeshNode has no intrinsic radio-link metrics", not re.search(r"\b(rssi|snr|pdr|retries|nextHop|route)\b", mesh_node, re.I), mesh_node.splitlines()[0])
check("Directional MeshLink model present", all(x in MODEL for x in ["data class MeshLink", "fromNode: NodeId", "toNode: NodeId", "val rssi: Int?", "val snr: Double?", "val pdr: Double?"]))
check("Topology domain has no screen coordinates", "val x:" not in topology and "val y:" not in topology and "TopologyNode" not in MODEL)
check("Route metrics are optional", all(x in route for x in ["hopCount: Int?", "quality: Double?", "updatedAtEpochMs: Long?"]))

# Messaging truth.
check("Message and TransmissionHop separated", "data class MeshMessage" in MODEL and "data class TransmissionHop" in MODEL)
check("Final confirmation pending state exists", "FINAL_CONFIRMATION_PENDING" in MODEL and "MessageFinalState" in MODEL)
check("Hop ACK cannot fabricate delivered", "finalStateAfterHopAck(): MessageFinalState = MessageFinalState.UNKNOWN" in (MAIN/"domain/model/MessageStateMachine.kt").read_text())
check("Current firmware branch uses UNKNOWN final state", "DemoProfile.CURRENT_FIRMWARE_V05" in MOCK and "finalStateAfterHopAck()" in MOCK)

# Local node / trust.
check("Field test source enforced as local node", "config.source != session.localNodeIdentity.nodeId" in MOCK)
check("Trusted entity keyed by nodeId property", "val nodeId: String" in ENTITIES and "data class TrustedDeviceEntity" in ENTITIES)
check("BLE MAC is not assigned as SecureMesh nodeId", not re.search(r"nodeId\s*=\s*(device|result\.device)\.address", ALL_TEXT))
check("Legacy BLE-MAC trust discarded", "isLegacyBleMac" in REPO and "clearTrustedDevices" in REPO)
check("Auto reconnect matches SecureMesh identity", "it.secureMeshNodeId == trusted.nodeId" in REPO)
check("Local history is scoped by authenticated SecureMesh identity", "localHistoryOwnerNodeId" in REPO and "session.localNodeIdentity.nodeId == ownerNodeId" in REPO and "settingsStore.localHistoryOwnerNodeId.first()" in REPO)
check("Sensitive Room writes require authenticated history owner", REPO.count("historyOwnedByCurrentSession(currentSession, owner)") >= 4 and "combine(liveEvents, session, localHistoryOwnerNodeId)" in REPO and "combine(messages, session, localHistoryOwnerNodeId)" in REPO and "combine(nodes, session, localHistoryOwnerNodeId)" in REPO and "combine(activeFieldTest, session, localHistoryOwnerNodeId)" in REPO)
check("Cross-identity history regression test present", "local history is cleared when authenticated local node identity changes" in TEST_TEXT)

# Mock profile separation / domain truth.
check("Two explicit demo profiles", "CURRENT_FIRMWARE_V05" in MODEL and "FUTURE_DEMO" in MODEL)
check("Repository demo launch waits for coherent projection", "withTimeout(2_000L)" in REPO and "combine(demoProfile, session, nodes, connectionState)" in REPO and "activeProfile == profile" in REPO)
check("Current v0.5 does not create GPS positions", "if (profile == DemoProfile.FUTURE_DEMO) NodePosition" in MOCK)
check("Current v0.5 node telemetry can be UNKNOWN", "if (profile == DemoProfile.FUTURE_DEMO) Triple(uptime, battery, voltage) else Triple(null, null, null)" in MOCK)
check("Current v0.5 aggregate link PDR/retries can be UNKNOWN", "if (future) pdr else null" in MOCK and "if (future) retries else null" in MOCK)
check("Future demo contains dynamic routing", "if (future) RouteType.DYNAMIC else RouteType.STATIC" in MOCK)
check("Mock scan is bounded inside transport", "durationMs.coerceIn(5_000L, 30_000L)" in MOCK)
check("Unknown mock BLE cannot become authenticated SecureMesh", "DeviceClassification.UNKNOWN_BLE || device.secureMeshNodeId != LOCAL_ID" in MOCK and "SecureSessionState.NOT_CONFIGURED" in MOCK)
check("Offline mock node does not refresh lastSeen forever", "do not refresh an already-offline node" in MOCK)

# Privacy / adaptive UI.
check("Central UI access policy", "object UiAccessPolicy" in POLICY)
check("Permission projections cover stored data", all(x in POLICY for x in ["visibleNodes", "visibleTopology", "visibleMessages", "visibleRoutes", "visibleEvents"]))
check("Map requires capability plus position permission", "supports(DeviceCapability.GPS)" in POLICY and "VIEW_OWN_POSITION" in POLICY and "VIEW_TEAM_POSITIONS" in POLICY)
check("Map position projection is independent from full node-list permission", "visiblePositionNodes" in POLICY and "visiblePositionNodes(session, nodes)" in (MAIN/"feature/network/NetworkViewModel.kt").read_text())
check("Dynamic primary navigation uses access policy", "itemsFor" in (MAIN/"navigation/SecureMeshRoot.kt").read_text() and "UiAccessPolicy" in (MAIN/"navigation/SecureMeshRoot.kt").read_text())
check("App surface is named SecureMesh", '<string name="app_name">SecureMesh</string>' in (ROOT/"app/src/main/res/values/strings.xml").read_text())

# BLE honesty.
check("Central BLE protocol config", (MAIN/"data/ble/BleProtocolConfig.kt").exists())
check("Codec explicitly reports unconfigured", "override val configured: Boolean = false" in CODEC)
check("BLE protocol readiness requires codec", "serviceDetected && characteristicsConfigured && codec.configured" in BLE)
check("Real BLE transport never fabricates SecureMeshSession", "_session.value = SecureMeshSession" not in BLE)
check("Bounded BLE scan", "durationMs.coerceIn(5_000L, 30_000L)" in BLE and "delay(boundedDurationMs)" in BLE and "stopScanInternal" in BLE)
check("Connection flow has future identification/sync stages", "IdentifyingSecureMesh" in MODEL and "SyncingSession" in MODEL)
check("BLE disconnect has local cleanup fallback", "BLE disconnect timeout; local GATT closed" in BLE and "No active BLE link" in BLE)
check("Welcome auto-connect reacts only to BLE transport", "mode==TransportMode.BLE&&connection is MeshConnectionState.Connected" in WELCOME)

# Field-test correctness.
FIELD_SCREEN=(MAIN/"feature/fieldtest/FieldTestScreen.kt").read_text()
check("RSSI and SNR use separate chart surfaces", bool(re.search(r'Chart\(\s*\"RSSI dBm\"', FIELD_SCREEN)) and bool(re.search(r'Chart\(\s*\"SNR dB\"', FIELD_SCREEN)))
check("Per-hop field telemetry model", "data class HopTestTelemetry" in MODEL and "hopResults" in MODEL)

# Anti-hardcoding and maintainability.
check("No A/B/C/COMMANDER A identity assumptions in source", not re.search(r'COMMANDER A|"A"|"B"|"C"', ALL_TEXT))
max_lines, max_file = max((len(p.read_text().splitlines()), p.name) for p in ALL_KT)
check("No giant Kotlin god file", max_lines < 650, f"{max_file}: {max_lines} lines")
check("No unsafe 6-flow typed combine in NodesViewModel", "private val controls = combine(query, filters, sort)" in NODES_VM and "repository.session, controls" in NODES_VM)
check("High-arity dashboard/diagnostics combine avoids unchecked casts", "UNCHECKED_CAST" not in DASHBOARD_VM and "UNCHECKED_CAST" not in DIAGNOSTICS_VM)
check("Required alignment tests present", all(x in TEST_TEXT for x in ["role is not permission", "directional link metrics", "hop ack alone never manufactures", "current v05 demo", "future demo", "trusted device metadata"]))
check("Compose smoke test retained", any((ROOT/"app/src/androidTest").rglob("*Test.kt")))

print("SecureMesh Android Domain Alignment quality gate")
for name, detail in passes:
    print(f"PASS  {name}" + (f" — {detail}" if detail else ""))
for name, detail in failures:
    print(f"FAIL  {name}" + (f" — {detail}" if detail else ""))
print(f"\n{len(passes)} passed, {len(failures)} failed")
sys.exit(1 if failures else 0)
