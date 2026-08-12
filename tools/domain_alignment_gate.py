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
CONFIG = (MAIN / "data/ble/BleProtocolConfig.kt").read_text(encoding="utf-8")
FRAG = (MAIN / "data/ble/BleFragmentation.kt").read_text(encoding="utf-8")
REQUESTS = (MAIN / "data/ble/BleRequestManager.kt").read_text(encoding="utf-8")
ENTITIES = (MAIN / "core/database/Entities.kt").read_text(encoding="utf-8")
DATABASE = (MAIN / "core/database/SecureMeshDatabase.kt").read_text(encoding="utf-8")
PAIRING = (MAIN / "data/ble/PairingController.kt").read_text(encoding="utf-8")
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

# Architectural boundaries.
check("Repository/transport boundary retained", (MAIN/"domain/repository/SecureMeshRepository.kt").exists() and (MAIN/"data/transport/MeshTransport.kt").exists())
check("No direct GATT/scanner API in feature UI", not re.search(r"BluetoothGatt|BluetoothLeScanner|ScanCallback", FEATURE_TEXT))
check("No cloud/backend/analytics dependency", not re.search(r"firebase|appsflyer|amplitude|retrofit|ktor-client|analytics-sdk", BUILD_TEXT, re.I))
check("No hardcoded PIN/key", not re.search(r"\bPIN\s*=|123456|SECRET_KEY|PRIVATE_KEY|API_KEY", ALL_TEXT, re.I))
check("System pairing only; no custom PIN submission", "submitCode" not in PAIRING and "createBond()" in BLE)

# Domain truth.
check("NodeIdentity model present", all(x in MODEL for x in ["data class NodeIdentity", "nodeId: NodeId", "displayName: String", "role: NodeRole", "capabilities: Set<DeviceCapability>"]))
check("Role and permission are separate enums", "enum class NodeRole" in MODEL and "enum class SessionPermission" in MODEL and "enum class DeviceCapability" in MODEL)
AUTH_SURFACES = POLICY + "\n" + MOCK + "\n" + (MAIN/"feature/messages/MessagesViewModel.kt").read_text() + "\n" + (MAIN/"feature/routes/RoutesViewModel.kt").read_text() + "\n" + (MAIN/"feature/fieldtest/FieldTestViewModel.kt").read_text()
check("No role-equals-authorization branch", not re.search(r"role\s*==\s*NodeRole\.(COMMANDER|ADMIN)", AUTH_SURFACES))
check("SecureMeshSession separates auth from BLE link", all(x in MODEL for x in ["SecureSessionConnectionState", "BLE_CONNECTED", "SECURE_SESSION_ESTABLISHED", "AuthenticationState"]))
check("UI visibility explicitly not authorization", "UI visibility" in MODEL and "security authority" in POLICY.lower())
check("MeshNode has no intrinsic radio-link metrics", not re.search(r"\b(rssi|snr|pdr|retries|nextHop|route)\b", mesh_node, re.I), mesh_node.splitlines()[0])
check("Directional MeshLink model present", all(x in MODEL for x in ["data class MeshLink", "fromNode: NodeId", "toNode: NodeId", "val rssi: Int?", "val snr: Double?", "val pdr: Double?"]))
check("Topology has no screen coordinates", "val x:" not in topology and "val y:" not in topology and "TopologyNode" not in MODEL)
check("Route metrics remain optional", all(x in route for x in ["hopCount: Int?", "quality: Double?", "updatedAtEpochMs: Long?"]))
check("Hop ACK cannot fabricate ordinary E2E delivery", "finalState = MessageFinalState.UNKNOWN" in BLE and "First-hop ACK" in BLE)

# Exact BLE v0.1 contract.
for value in [
    "7b7f0001-6b6f-4d65-7368-534543555245",
    "7b7f0002-6b6f-4d65-7368-534543555245",
    "7b7f0003-6b6f-4d65-7368-534543555245",
    "7b7f0004-6b6f-4d65-7368-534543555245",
    "7b7f0005-6b6f-4d65-7368-534543555245",
]:
    check(f"Exact protocol UUID {value[:8]}", value in CONFIG)
check("Real codec configured", "class SecureMeshBleProtocolV01Codec" in CODEC and "override val configured: Boolean = true" in CODEC)
check("No JSON on BLE wire", "JSONObject" not in CODEC and "kotlinx.serialization" not in CODEC and "Gson" not in CODEC)
check("10-byte application envelope", "HEADER_SIZE = 10" in CODEC and "MAGIC = 0x4D53" in CODEC and "MAX_PACKET_SIZE = 384" in CODEC)
check("Strict application validation", all(x in CODEC for x in ["wrong SecureMesh BLE magic", "unsupported SecureMesh BLE protocol version", "payloadLength mismatch", "trailing bytes"]))
check("Known v0.1 command set present", all(x in CODEC for x in ["GET_INFO(1)", "GET_STATUS(2)", "GET_NEIGHBORS(3)", "GET_ROUTES(4)", "SEND_MESSAGE(5)", "ADD_STATIC_ROUTE(6)", "REMOVE_STATIC_ROUTE(7)", "START_FIELD_TEST(8)", "STOP_FIELD_TEST(9)", "GET_FIELD_TEST_STATUS(10)"]))
check("Known v0.1 events present", all(x in CODEC for x in ["NODE_DISCOVERED(1)", "HOP_ACK(4)", "MESSAGE_LOCAL_RECEIVED(6)", "TEST_PONG_RECEIVED(10)", "TEST_FINISHED(13)", "ERROR(16)", "NO_RETURN_ROUTE(17)"]))

# Transport fragmentation / requests.
check("Fragment protocol exact constants", all(x in FRAG for x in ["MAGIC = 0x4653", "HEADER_SIZE = 12", "MAX_FRAGMENT_DATA = 180", "MAX_FRAGMENT_COUNT = 48", "MAX_APPLICATION_PACKET = 384", "REASSEMBLY_TIMEOUT_MS = 3_000L"]))
check("Fragment sizing uses negotiated MTU", "negotiatedMtu - 3 - HEADER_SIZE" in FRAG)
check("Sequential reassembly rejects gaps and overlap", "out-of-order fragment" in FRAG and "overlap/gap detected" in FRAG and "fragment out of bounds" in FRAG)
check("Bounded request manager", "maxPending: Int = 16" in REQUESTS and "LinkedHashMap<Int, Handle>" in REQUESTS)
check("EVENT cannot complete pending request", "if (frame !is SecureMeshBleFrame.Response) return false" in REQUESTS)
check("Pending requests fail on disconnect", "requestManager.failAll" in BLE)

# Secure session flow / discovery.
check("Scan identity requires service UUID", "hasService" in (MAIN/"data/ble/SecureMeshDeviceMatcher.kt").read_text() and "name-only-not-identity" in (MAIN/"data/ble/SecureMeshDeviceMatcher.kt").read_text())
check("Subscribe RESPONSE and EVENT before INFO", "subscribeResponse()" in BLE and "subscribeEvent()" in BLE and "readInfo()" in BLE)
check("INFO security and protocol ready validated", "info.authenticated" in BLE and "BLE_STATE_PROTOCOL_READY" in BLE and "supportedProtocolVersions" in BLE)
check("Authenticated INFO creates stable identity", "SecureMeshBleV01DomainMapping.identity(info)" in BLE and "secureMeshNodeId = identity.nodeId" in BLE)
check("Real BLE never maps address into nodeId", not re.search(r"nodeId\s*=\s*(device|result\.device|verifiedDevice)\.address", BLE))
check("Bounded BLE scan", "durationMs.coerceIn(5_000L, 30_000L)" in BLE and "delay(boundedDurationMs)" in BLE)
check("Disconnect has local cleanup fallback", "BLE disconnect timeout; local GATT closed" in BLE and "No active BLE link" in BLE)

# Trust identity / migration.
check("Trusted entity nodeId primary key", '@Entity(tableName = "trusted_devices")' in ENTITIES and "@PrimaryKey val nodeId: String" in ENTITIES)
check("BLE address is optional transport metadata", "val lastSeenBleAddress: String?" in ENTITIES)
check("Room migration 1 to 2 exists", "Migration(1, 2)" in DATABASE and "addMigrations(MIGRATION_1_2)" in DATABASE)
check("Legacy MAC-shaped trust is not migrated", "length(`address`) = 8" in DATABASE and "`address` NOT LIKE '%:%'" in DATABASE)
check("Reconnect uses BLE address only as hint", "addressHint = trusted.lastSeenBleAddress" in REPO and "authenticated INFO must still prove" in REPO)
check("Reconnect verifies authenticated nodeId", "verified.localNodeIdentity.nodeId != trusted.nodeId" in REPO)
check("Local history remains scoped by authenticated nodeId", "localHistoryOwnerNodeId" in REPO and "session.localNodeIdentity.nodeId == ownerNodeId" in REPO)

# Capabilities / current vs future honesty.
check("v0.6 capability bits map only defined features", all(x in (MAIN/"data/ble/BleDomainMapping.kt").read_text() for x in ["MESSAGING", "STATIC_ROUTING", "RELAY", "FIELD_TEST", "BLE_CONTROL"]))
check("Real capability mapper does not add GPS SOS OTA", all(x not in (MAIN/"data/ble/BleDomainMapping.kt").read_text().split("fun capabilities",1)[1].split("}",1)[0] for x in ["GPS", "SOS", "OTA"]))
check("Mock transport retained", (MAIN/"data/mock/MockTransport.kt").exists())
check("Future demo remains explicit", "FUTURE_DEMO" in MODEL and "FUTURE_DEMO" in MOCK)

# Field test semantics.
FIELD_SCREEN = (MAIN/"feature/fieldtest/FieldTestScreen.kt").read_text()
check("Field UI names first-hop ACK separately", "First-hop ACK" in FIELD_SCREEN and "First-hop fail" in FIELD_SCREEN)
check("Field UI names E2E PONG separately", "E2E PONG" in FIELD_SCREEN and "RTT по DIAG_PONG" in FIELD_SCREEN)
check("Field status maps E2E counters to final results", "confirmedReceived = status.endToEndReplies" in (MAIN/"data/ble/BleDomainMapping.kt").read_text())
check("Field status keeps first-hop counters separate", "firstHopAcked = status.firstHopAcked" in (MAIN/"data/ble/BleDomainMapping.kt").read_text())

# Diagnostics and maintainability.
check("BLE diagnostics include required counters", all(x in MODEL for x in ["lastCommandRequestId", "lastResponse", "reassemblyErrors", "malformedPacketCount", "responseSubscribed", "eventSubscribed"]))
check("No unsafe high-arity casts", "UNCHECKED_CAST" not in DASHBOARD_VM and "UNCHECKED_CAST" not in DIAGNOSTICS_VM)
check("No unsafe 6-flow combine in NodesViewModel", "private val controls = combine(query, filters, sort)" in NODES_VM and "repository.session, controls" in NODES_VM)
max_lines, max_file = max((len(p.read_text().splitlines()), p.name) for p in ALL_KT if p.name != "BleTransport.kt")
check("No giant Kotlin god file outside BLE state machine", max_lines < 650, f"{max_file}: {max_lines} lines")
ble_lines = len((MAIN/"data/ble/BleTransport.kt").read_text().splitlines())
check("BLE state machine remains bounded", ble_lines < 1000, f"BleTransport.kt: {ble_lines} lines")
check("Protocol integration tests present", all(x in TEST_TEXT for x in ["wrong magic is rejected", "payload length mismatch is rejected", "out of order fragment is rejected", "EVENT never completes pending request", "field test first hop ACK is not end to end success", "name alone is not SecureMesh identity"]))
check("Existing alignment tests retained", all(x in TEST_TEXT for x in ["role is not permission", "directional link metrics", "hop ack alone never manufactures", "future demo", "trusted device metadata"]))
check("Compose smoke test retained", any((ROOT/"app/src/androidTest").rglob("*Test.kt")))
check("App surface is named SecureMesh", '<string name="app_name">SecureMesh</string>' in (ROOT/"app/src/main/res/values/strings.xml").read_text())

print("SecureMesh Android BLE Protocol v0.1 alignment gate")
for name, detail in passes:
    print(f"PASS  {name}" + (f" — {detail}" if detail else ""))
for name, detail in failures:
    print(f"FAIL  {name}" + (f" — {detail}" if detail else ""))
print(f"\n{len(passes)} passed, {len(failures)} failed")
sys.exit(1 if failures else 0)
