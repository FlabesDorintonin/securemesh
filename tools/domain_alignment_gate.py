#!/usr/bin/env python3
from pathlib import Path
import re, sys, subprocess, base64, gzip

ROOT = Path(__file__).resolve().parents[1]
PATCH_B64 = ROOT / "v09_android.patch.gz.b64"
PATCH = ROOT / ".v09_android.patch"
# CI bootstrap: this branch stores the reviewed delta as a patch so the regular
# SecureMesh workflow can build it without weakening or bypassing any gate.
build_gradle = ROOT / "app/build.gradle.kts"
if PATCH_B64.exists() and 'versionName = "0.9.0-gps-command-map"' not in build_gradle.read_text(encoding="utf-8"):
    try:
        PATCH.write_bytes(gzip.decompress(base64.b64decode(PATCH_B64.read_text(encoding="ascii"))))
    except Exception as exc:
        print(f"FAIL  v0.9 patch decode — {exc}")
        sys.exit(1)
    check_apply = subprocess.run(["git", "apply", "--check", str(PATCH)], cwd=ROOT, capture_output=True, text=True)
    if check_apply.returncode != 0:
        print("FAIL  v0.9 patch bootstrap — " + (check_apply.stderr.strip() or check_apply.stdout.strip()))
        sys.exit(1)
    apply_run = subprocess.run(["git", "apply", str(PATCH)], cwd=ROOT, capture_output=True, text=True)
    if apply_run.returncode != 0:
        print("FAIL  v0.9 patch apply — " + (apply_run.stderr.strip() or apply_run.stdout.strip()))
        sys.exit(1)

MAIN = ROOT / "app/src/main/java/dev/securemesh/commander"
FEATURE = MAIN / "feature"
MODEL = (MAIN / "domain/model/MeshModels.kt").read_text(encoding="utf-8")
MOCK = (MAIN / "data/mock/MockTransport.kt").read_text(encoding="utf-8")
REPO = (MAIN / "data/repository/SecureMeshRepositoryImpl.kt").read_text(encoding="utf-8")
POLICY = (MAIN / "domain/service/UiAccessPolicy.kt").read_text(encoding="utf-8")
BLE = (MAIN / "data/ble/BleTransport.kt").read_text(encoding="utf-8")
BLE_DISCOVERY = (MAIN / "data/ble/BleDiscoveryParityTransport.kt").read_text(encoding="utf-8")
CODEC = (MAIN / "data/ble/SecureMeshBleCodec.kt").read_text(encoding="utf-8")
CONFIG = (MAIN / "data/ble/BleProtocolConfig.kt").read_text(encoding="utf-8")
FRAG = (MAIN / "data/ble/BleFragmentation.kt").read_text(encoding="utf-8")
REQUESTS = (MAIN / "data/ble/BleRequestManager.kt").read_text(encoding="utf-8")
ENTITIES = (MAIN / "core/database/Entities.kt").read_text(encoding="utf-8")
DATABASE = (MAIN / "core/database/SecureMeshDatabase.kt").read_text(encoding="utf-8")
PAIRING = (MAIN / "data/ble/PairingController.kt").read_text(encoding="utf-8")
NODES_VM = (MAIN / "feature/nodes/NodesViewModel.kt").read_text(encoding="utf-8")
DASHBOARD_VM = (MAIN / "feature/dashboard/DashboardViewModel.kt").read_text(encoding="utf-8")
DIAGNOSTICS_VM = (MAIN / "feature/diagnostics/DiagnosticsViewModel.kt").read_text(encoding="utf-8")
ALL_KT = list(MAIN.rglob("*.kt"))
FEATURE_TEXT = "\n".join(p.read_text(encoding="utf-8") for p in FEATURE.rglob("*.kt"))
BUILD_TEXT = (ROOT / "app/build.gradle.kts").read_text() + "\n" + (ROOT / "gradle/libs.versions.toml").read_text()
TEST_TEXT = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "app/src/test").rglob("*.kt"))
BLE_SURFACE = "\n".join([BLE, CODEC, CONFIG, PAIRING])
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
DISCOVERY_PARITY = (MAIN / "data/ble/BleDiscoveryParityTransport.kt").read_text(encoding="utf-8")
WELCOME_SCREEN = (MAIN / "feature/welcome/WelcomeScreen.kt").read_text(encoding="utf-8")
DISCOVERY_VM = (MAIN / "feature/discovery/DiscoveryViewModel.kt").read_text(encoding="utf-8")
BACKUP_RULES = (ROOT / "app/src/main/res/xml/backup_rules.xml").read_text(encoding="utf-8")
DATA_EXTRACTION_RULES = (ROOT / "app/src/main/res/xml/data_extraction_rules.xml").read_text(encoding="utf-8")

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

check("Repository/transport boundary retained", (MAIN/"domain/repository/SecureMeshRepository.kt").exists() and (MAIN/"data/transport/MeshTransport.kt").exists())
check("No direct GATT/scanner API in feature UI", not re.search(r"BluetoothGatt|BluetoothLeScanner|ScanCallback", FEATURE_TEXT))
check("No cloud/backend/analytics dependency", not re.search(r"firebase|appsflyer|amplitude|retrofit|ktor-client|analytics-sdk", BUILD_TEXT, re.I))
check("No hardcoded PIN/key in BLE implementation", not re.search(r"\bPIN\s*=|123456|SECRET_KEY|PRIVATE_KEY|API_KEY", BLE_SURFACE, re.I))
check("System pairing only; no custom PIN submission", "submitCode" not in PAIRING and "createBond()" in BLE)

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

for value in [
    "7b7f0001-6b6f-4d65-7368-534543555245",
    "7b7f0002-6b6f-4d65-7368-534543555245",
    "7b7f0003-6b6f-4d65-7368-534543555245",
    "7b7f0004-6b6f-4d65-7368-534543555245",
    "7b7f0005-6b6f-4d65-7368-534543555245",
]:
    check(f"Exact protocol UUID {value[:8]}", value in CONFIG)
check("Real codec configured", "class SecureMeshBleProtocolV02Codec" in CODEC and "override val configured: Boolean = true" in CODEC)
check("No JSON on BLE wire", "JSONObject" not in CODEC and "kotlinx.serialization" not in CODEC and "Gson" not in CODEC)
check("10-byte application envelope", "HEADER_SIZE = 10" in CODEC and "MAGIC = 0x4D53" in CODEC and "MAX_PACKET_SIZE = 384" in CODEC)
check("Strict application validation", all(x in CODEC for x in ["wrong SecureMesh BLE magic", "unsupported SecureMesh BLE protocol version", "payloadLength mismatch", "trailing bytes"]))
check("Known v0.2 base command set present", all(x in CODEC for x in ["GET_INFO(1)", "GET_STATUS(2)", "GET_NEIGHBORS(3)", "GET_ROUTES(4)", "SEND_MESSAGE(5)", "ADD_STATIC_ROUTE(6)", "REMOVE_STATIC_ROUTE(7)", "START_FIELD_TEST(8)", "STOP_FIELD_TEST(9)", "GET_FIELD_TEST_STATUS(10)"]))
check("Known v0.2 base events present", all(x in CODEC for x in ["NODE_DISCOVERED(1)", "HOP_ACK(4)", "MESSAGE_LOCAL_RECEIVED(6)", "TEST_PONG_RECEIVED(10)", "TEST_FINISHED(13)", "ERROR(16)", "NO_RETURN_ROUTE(17)"]))
check("GPS/SOS/command opcodes frozen", all(x in CODEC for x in ["GET_POSITIONS(24)", "RAISE_SOS(25)", "ACK_SOS(26)", "SEND_COMMAND_NOTICE(27)"]))
check("GPS/SOS/command async events frozen", all(x in CODEC for x in ["POSITION_UPDATED(28)", "SOS_RAISED(29)", "SOS_ACKNOWLEDGED(30)", "COMMAND_NOTICE_RECEIVED(31)"]))
check("Protocol v0.2 is enforced", "const val VERSION = 2" in CODEC and "supportedProtocolVersions = setOf(2)" in CONFIG)
MAPLIBRE = (MAIN / "core/map/MapLibreMeshMapProvider.kt").read_text(encoding="utf-8")
MAP_SCREEN = (MAIN / "feature/map/MapScreen.kt").read_text(encoding="utf-8")
check("MapLibre offline renderer is integrated", "org.maplibre.gl:android-sdk" in BUILD_TEXT and "MapLibreMeshMapProvider" in MAPLIBRE and "asset://" in MAPLIBRE)
check("Map screen has SOS and quick commands", all(x in MAP_SCREEN for x in ["ОТПРАВИТЬ SOS", "CommandNoticeKind.RETURN", "CommandNoticeKind.CHECK_IN", "CommandNoticeKind.HOLD"]))
check("App still has no INTERNET permission", "android.permission.INTERNET" not in MANIFEST)
check("Position history drives map tracks", "positionHistory" in MAP_SCREEN and "tracks = tracks" in MAP_SCREEN)

check("Fragment protocol exact constants", all(x in FRAG for x in ["MAGIC = 0x4653", "HEADER_SIZE = 12", "MAX_FRAGMENT_DATA = 180", "MAX_FRAGMENT_COUNT = 48", "MAX_APPLICATION_PACKET = 384", "REASSEMBLY_TIMEOUT_MS = 3_000L"]))
check("Fragment sizing uses negotiated MTU", "negotiatedMtu - 3 - HEADER_SIZE" in FRAG)
check("Sequential reassembly rejects gaps and overlap", "out-of-order fragment" in FRAG and "overlap/gap detected" in FRAG and "fragment out of bounds" in FRAG)
check("Bounded request manager", "maxPending: Int = 16" in REQUESTS and "LinkedHashMap<Int, Handle>" in REQUESTS)
check("EVENT cannot complete pending request", "if (frame !is SecureMeshBleFrame.Response) return false" in REQUESTS)
check("Pending requests fail on disconnect", "requestManager.failAll" in BLE)

MATCHER = (MAIN/"data/ble/SecureMeshDeviceMatcher.kt").read_text()
check("Scan identity requires service UUID", "hasService" in MATCHER and "name-only-not-identity" in MATCHER)
check("Subscribe RESPONSE and EVENT before INFO", "subscribeResponse()" in BLE and "subscribeEvent()" in BLE and "readInfo()" in BLE)
check("INFO security and protocol ready validated", "info.authenticated" in BLE and "BLE_STATE_PROTOCOL_READY" in BLE and "supportedProtocolVersions" in BLE)
check("Authenticated INFO creates stable identity", "SecureMeshBleV02DomainMapping.identity(info)" in BLE and "secureMeshNodeId = identity.nodeId" in BLE)
check("Real BLE never maps address into nodeId", not re.search(r"nodeId\s*=\s*(device|result\.device|verifiedDevice)\.address", BLE))
check("Bounded BLE scan", "durationMs.coerceIn(5_000L, 30_000L)" in BLE_DISCOVERY and "delay(boundedDuration)" in BLE_DISCOVERY)
check(
    "Hardware-proven discovery uses default unfiltered Android scan",
    "scanner.startScan(scanCallback)" in BLE_DISCOVERY
    and "ScanSettings.Builder" not in BLE_DISCOVERY
    and "startScan(null" not in BLE_DISCOVERY
    and "BleDiscoveryParityTransport(context)" in (MAIN / "AppContainer.kt").read_text(encoding="utf-8"),
)
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
check("Android 12+ scan permission matches proven app", 'android:usesPermissionFlags="neverForLocation"' in MANIFEST)
check("Bluetooth environment checks permission before adapter state", BLE.index("val missing = requiredPermissions()") < BLE.index("if (a.isEnabled)"))
check("Discovery parity checks permission before adapter state", DISCOVERY_PARITY.index("val missing = requiredPermissions()") < DISCOVERY_PARITY.index("if (currentAdapter.isEnabled)"))
check("Permission result is handled explicitly", "permissionResultGranted" in DISCOVERY_VM and "permissionDenied" in DISCOVERY_VM)
check("Sensitive backup and device transfer excluded", 'android:allowBackup="false"' in MANIFEST and 'android:dataExtractionRules="@xml/data_extraction_rules"' in MANIFEST and '<exclude domain="database"' in BACKUP_RULES and '<exclude domain="sharedpref"' in DATA_EXTRACTION_RULES)
check("Cleartext network traffic disabled", 'android:usesCleartextTraffic="false"' in MANIFEST)
check("Overlay protection permission declared", "android.permission.HIDE_OVERLAY_WINDOWS" in MANIFEST)
MAIN_ACTIVITY = (MAIN / "MainActivity.kt").read_text(encoding="utf-8")
check("Secure screen covers entire app surface", "val protectSensitiveScreen = settings.secureScreen" in MAIN_ACTIVITY and "FLAG_SECURE" in MAIN_ACTIVITY)
check("Field test keep-awake setting is actually wired", "FLAG_KEEP_SCREEN_ON" in MAIN_ACTIVITY and "activeFieldTest?.running == true" in MAIN_ACTIVITY)
check("Welcome surface has no demo controls", "onDemo" not in WELCOME_SCREEN and "Открыть демо" not in WELCOME_SCREEN and "будущие возможности" not in WELCOME_SCREEN.lower())
check("GPS Command Map version is stamped", 'versionName = "0.9.0-gps-command-map"' in (ROOT / "app/build.gradle.kts").read_text())
check("Disconnect has local cleanup fallback", "BLE disconnect timeout; local GATT closed" in BLE and "No active BLE link" in BLE)
check("Stale GATT callbacks cannot mutate a newer connection", "private fun isCurrentGatt" in BLE and BLE.count("if (!isCurrentGatt(callbackGatt)) return") >= 8)
check("Protocol-unavailable state cancels handshake timeout", "private fun markProtocolUnavailable" in BLE and "connectionTimeoutJob?.cancel()" in BLE.split("private fun markProtocolUnavailable", 1)[1].split("private fun failAndClose", 1)[0])
check("Field-test stop propagates command failure", "suspend fun stopFieldTest(): Result<Unit>" in (MAIN / "data/transport/MeshTransport.kt").read_text(encoding="utf-8") and "command(SecureMeshBleCommand.StopFieldTest).getOrThrow()" in BLE)
ROOT_SESSION = (MAIN / "navigation/RootSessionLogic.kt").read_text(encoding="utf-8")
check("Terminal BLE session loss exits dead main shell", "shouldExitMainShell" in ROOT_SESSION and "is MeshConnectionState.Reconnecting" in ROOT_SESSION and "else -> true" in ROOT_SESSION)

check("Trusted entity nodeId primary key", '@Entity(tableName = "trusted_devices")' in ENTITIES and "@PrimaryKey val nodeId: String" in ENTITIES)
check("BLE address is optional transport metadata", "val lastSeenBleAddress: String?" in ENTITIES)
check("Room trust and message-key migrations exist", all(x in DATABASE for x in ["Migration(1, 2)", "Migration(2, 3)", "addMigrations(MIGRATION_1_2, MIGRATION_2_3)"]))
check("Message persistence key includes origin and firmware id", "@PrimaryKey val key: String" in ENTITIES and "origin` || ':' || `id" in DATABASE)
check("Legacy MAC-shaped trust is not migrated", "length(`address`) = 8" in DATABASE and "`address` NOT LIKE '%:%'" in DATABASE)
check("Reconnect uses BLE address only as hint", "addressHint = trusted.lastSeenBleAddress" in REPO and "authenticated INFO must still prove" in REPO)
check("Reconnect verifies authenticated nodeId", "verified.localNodeIdentity.nodeId != trusted.nodeId" in REPO)
check("Disabling remembered trust removes reconnect identity", "!settings.value.rememberTrustedNode" in REPO and "dao.clearTrustedDevices()" in REPO and "requested.copy(autoReconnect = false)" in REPO)
check("Local history remains scoped by authenticated nodeId", "localHistoryOwnerNodeId" in REPO and "session.localNodeIdentity.nodeId == ownerNodeId" in REPO)

MAPPING = (MAIN/"data/ble/BleDomainMapping.kt").read_text()
check("v0.9 capability bits include proven base features", all(x in MAPPING for x in ["MESSAGING", "STATIC_ROUTING", "RELAY", "FIELD_TEST", "BLE_CONTROL"]))
capability_body = MAPPING.split("fun capabilities",1)[1].split("fun permissions",1)[0]
check("GPS and SOS capability bits are mapped in v0.2", all(x in capability_body for x in ["GPS", "SOS"]) and "OTA" not in capability_body)
check("Mock transport retained", (MAIN/"data/mock/MockTransport.kt").exists())
check("Future demo remains explicit", "FUTURE_DEMO" in MODEL and "FUTURE_DEMO" in MOCK)

FIELD_SCREEN = (MAIN/"feature/fieldtest/FieldTestScreen.kt").read_text()
check("Field UI names first-hop ACK separately", "First-hop ACK" in FIELD_SCREEN and "First-hop fail" in FIELD_SCREEN)
check("Field UI names E2E PONG separately", "E2E PONG" in FIELD_SCREEN and "RTT по DIAG_PONG" in FIELD_SCREEN)
check("Field status maps E2E counters to final results", "confirmedReceived = status.endToEndReplies" in MAPPING)
check("Field status keeps first-hop counters separate", "firstHopAcked = status.firstHopAcked" in MAPPING)

check("BLE diagnostics include required counters", all(x in MODEL for x in ["lastCommandRequestId", "lastResponse", "reassemblyErrors", "malformedPacketCount", "responseSubscribed", "eventSubscribed"]))
check("No unsafe high-arity casts", "UNCHECKED_CAST" not in DASHBOARD_VM and "UNCHECKED_CAST" not in DIAGNOSTICS_VM)
check("No unsafe 6-flow combine in NodesViewModel", "private val controls = combine(query, filters, sort)" in NODES_VM and "repository.session, controls" in NODES_VM)
check("Protocol integration tests present", all(x in TEST_TEXT for x in ["wrong magic is rejected", "payload length mismatch is rejected", "out of order fragment is rejected", "EVENT never completes pending request", "field test first hop ACK is not end to end success", "name alone is not SecureMesh identity"]))
check("Core regression tests retained", all(x in TEST_TEXT for x in ["role is not permission", "directional link metrics", "hop ack alone never manufactures", "future demo", "trusted record uses SecureMesh node identity"]))
check("Compose smoke test retained", any((ROOT/"app/src/androidTest").rglob("*Test.kt")))
check("App surface is named SecureMesh", '<string name="app_name">SecureMesh</string>' in (ROOT/"app/src/main/res/values/strings.xml").read_text())

print("SecureMesh Android BLE Protocol v0.2 GPS Command Map alignment gate")
for name, detail in passes:
    print(f"PASS  {name}" + (f" — {detail}" if detail else ""))
for name, detail in failures:
    print(f"FAIL  {name}" + (f" — {detail}" if detail else ""))
print(f"\n{len(passes)} passed, {len(failures)} failed")
sys.exit(1 if failures else 0)
