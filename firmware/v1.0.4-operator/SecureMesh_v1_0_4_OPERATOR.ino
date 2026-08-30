/*
  SecureMesh v1.0.4 - OPERATOR
  ESP32-S3 + EBYTE E22-400M30S (SX1268) + optional SSD1306 128x64

  Purpose of this build:
  - preserve the qualified v0.6.7 radio/BLE/UI baseline where possible;
  - provide a complete laboratory routing/control plane for VANGUARD-SM;
  - persist trusted node identity knowledge and an exact NetworkManifest;
  - discover multi-hop routes automatically with bounded RREQ/RREP/RERR;
  - construct an exact node-disjoint G2 standby route when a common manifest
    allows exact NodeSlot path masks;
  - pin discovered paths with a pathTag + ordered NodeSlot vector so the
    actual forwarding chain matches the route whose disjointness was tested;
  - perform bounded failover, local error propagation, route replenishment,
    store-and-forward, airtime budgeting and reproducible lab fault injection;
  - expose a read-only Operational Health + Self Diagnostics layer so the
    commander application can explain failures without changing RF behaviour;
  - monitor health transitions with bounded, non-spamming BLE events;
  - preserve human Link Quality / signal-trend telemetry for the OLED and app.

  Important safety boundaries:
  - known identities and the NetworkManifest persist; dynamic radio routes do
    NOT persist because topology/evidence becomes stale across reboot;
  - the development group AES-256-GCM key is LAB ONLY and is not a production
    per-node identity/security model;
  - normal USER_DATA still has hop ACK reliability, not a production-grade
    end-to-end delivery receipt. DIAG_PING/PONG provides an E2E routing test;
  - exact G2 is asserted only when every participating node agrees on the same
    NetworkEpoch + manifest digest. Otherwise the implementation fails closed.
*/

#include <Arduino.h>
#include <SPI.h>
#include <Wire.h>
#include <RadioLib.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Preferences.h>
#include <mbedtls/gcm.h>
#include <esp_mac.h>
#include <esp_system.h>
#include <math.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <NimBLEDevice.h>
#include <TinyGPSPlus.h>
#include <esp_heap_caps.h>
#include "VanguardCore.h"
#include "VanguardProtocol.h"
#include "VanguardManifest.h"
#include "VanguardRuntime.h"
#include "VanguardAirtime.h"
#include "SecureMeshSecrets.h"

// ============================================================
// 1. HARDWARE
// ============================================================

constexpr int PIN_RADIO_NRST = 4;
constexpr int PIN_RADIO_RXEN = 5;
constexpr int PIN_RADIO_DIO1 = 6;
constexpr int PIN_RADIO_BUSY = 7;
constexpr int PIN_RADIO_TXEN = 8;
constexpr int PIN_RADIO_NSS  = 10;
constexpr int PIN_RADIO_MOSI = 11;
constexpr int PIN_RADIO_SCK  = 12;
constexpr int PIN_RADIO_MISO = 13;

constexpr int PIN_OLED_SCL = 15;
constexpr int PIN_OLED_SDA = 16;

// NEO-7M UART. GPS TX -> ESP32 RX (GPIO17). GPS RX from GPIO18 is optional.
constexpr int PIN_GPS_RX = 17;
constexpr int PIN_GPS_TX = 18;
constexpr uint32_t GPS_BAUD = 9600;

HardwareSerial gpsSerial(1);
TinyGPSPlus gps;

// ============================================================
// 2. FIXED RADIO BASELINE
// ============================================================

constexpr float RADIO_FREQUENCY_MHZ = 433.92f;
constexpr float RADIO_BANDWIDTH_KHZ = 125.0f;
constexpr uint8_t RADIO_SPREADING_FACTOR = 9;
constexpr uint8_t RADIO_CODING_RATE = 5;  // 4/5
constexpr uint16_t RADIO_PREAMBLE_LENGTH = 12;
constexpr uint8_t RADIO_SYNC_WORD = RADIOLIB_SX126X_SYNC_WORD_PRIVATE;

// This is the SX1268 driver setting, NOT a calibrated antenna-port dBm value
// for the E22 module with its external PA/LNA. Keep low while qualifying link.
constexpr int8_t RADIO_DRIVER_POWER_DBM = 10;

// E22-400M30S uses DIO3 to power its 32 MHz TCXO at 2.2 V.
constexpr float RADIO_TCXO_VOLTAGE = 2.2f;
constexpr bool RADIO_USE_LDO = false;

constexpr uint32_t RF_SWITCH_SETTLE_US = 2200;
constexpr uint32_t RADIO_RETRY_MS = 3000;
constexpr uint32_t TX_WATCHDOG_MS = 3500;

// ============================================================
// 3. NETWORK / CRYPTO
// ============================================================

constexpr uint16_t MESH_MAGIC = 0x534D;  // "SM"
constexpr uint8_t MESH_VERSION = 8;
constexpr uint16_t NETWORK_ID = 0x2608;
constexpr uint32_t BROADCAST_ID = 0xFFFFFFFFUL;

// LAB ONLY. The development group key is injected from a local, untracked SecureMeshSecrets.h.
// See SecureMeshSecrets.example.h. Never commit the real LAB key.

constexpr size_t AES_GCM_NONCE_SIZE = 12;
constexpr size_t AES_GCM_TAG_SIZE = 16;
constexpr size_t HOP_ACK_PAYLOAD_SIZE = 12;

// ============================================================
// 4. PROTOCOL
// ============================================================

// v0.5 deliberately separates the immutable logical message from the
// authenticated hop envelope used for one radio transmission.
//
// HOP HEADER (AES-GCM AAD for each radio hop)
//  0  magic            u16
//  2  version          u8
//  3  type             u8
//  4  networkId        u16
//  6  previousHop      u32
// 10  nextHop          u32
// 14  bootCounter      u32
// 18  frameCounter     u32
// 22  messageId        u32
// 26  payloadLength    u8
// 27  hopLimit         u8
// 28  routeTag         u32  (0=hop-by-hop, nonzero=VANGUARD pinned path)
// total: 32 bytes
//
// For DATA, the encrypted hop payload is an authenticated Message envelope.
// Relays verify it, preserve its bytes unchanged, decrement hopLimit, then
// create a NEW hop frame with their own NodeID+BootCounter+FrameCounter nonce.

constexpr size_t HEADER_SIZE = 32;
constexpr size_t OFF_MAGIC = 0;
constexpr size_t OFF_VERSION = 2;
constexpr size_t OFF_TYPE = 3;
constexpr size_t OFF_NETWORK = 4;
constexpr size_t OFF_PREVIOUS_HOP = 6;
constexpr size_t OFF_NEXT_HOP = 10;
constexpr size_t OFF_BOOT_COUNTER = 14;
constexpr size_t OFF_FRAME_COUNTER = 18;
constexpr size_t OFF_MESSAGE_ID = 22;
constexpr size_t OFF_PAYLOAD_LENGTH = 26;
constexpr size_t OFF_HOP_LIMIT = 27;
constexpr size_t OFF_ROUTE_TAG = 28;

// INNER MESSAGE HEADER v2 (AES-GCM AAD)
//  0  messageMagic      u16
//  2  messageVersion    u8
//  3  networkId         u16
//  5  messageType       u8
//  6  origin            u32
// 10  destination       u32
// 14  bootCounter       u32
// 18  messageCounter    u32
// 22  messageId         u32
// 26  payloadLength     u8
// total: 27 bytes

constexpr uint16_t MESSAGE_MAGIC = 0x4D53;  // "MS"
constexpr uint8_t MESSAGE_VERSION = 2;
constexpr size_t MESSAGE_HEADER_SIZE = 27;
constexpr size_t MOFF_MAGIC = 0;
constexpr size_t MOFF_VERSION = 2;
constexpr size_t MOFF_NETWORK = 3;
constexpr size_t MOFF_MESSAGE_TYPE = 5;
constexpr size_t MOFF_ORIGIN = 6;
constexpr size_t MOFF_DESTINATION = 10;
constexpr size_t MOFF_BOOT_COUNTER = 14;
constexpr size_t MOFF_MESSAGE_COUNTER = 18;
constexpr size_t MOFF_MESSAGE_ID = 22;
constexpr size_t MOFF_PAYLOAD_LENGTH = 26;

constexpr size_t MAX_APP_PAYLOAD = 70;
constexpr size_t MAX_MESSAGE_WIRE =
  MESSAGE_HEADER_SIZE + MAX_APP_PAYLOAD + AES_GCM_TAG_SIZE;
constexpr size_t MAX_HOP_PAYLOAD = MAX_MESSAGE_WIRE;
constexpr size_t MAX_WIRE_PACKET =
  HEADER_SIZE + MAX_HOP_PAYLOAD + AES_GCM_TAG_SIZE;

static_assert(MAX_MESSAGE_WIRE == 113, "v0.6 inner message wire size changed unexpectedly");
static_assert(MAX_WIRE_PACKET == 161, "v0.8 hop wire size changed unexpectedly");
static_assert(MAX_HOP_PAYLOAD <= UINT8_MAX, "hop payload length must fit u8");
static_assert(MAX_WIRE_PACKET <= 255, "SX126x LoRa packet must fit FIFO");

constexpr uint8_t DEFAULT_HOP_LIMIT = 4;

enum class FrameType : uint8_t {
  Hello = 1,
  Data = 2,
  Ack = 3,
  Control = 4
};

enum class MessageType : uint8_t {
  UserData = 1,
  DiagPing = 2,
  DiagPong = 3,
  Position = 4,
  Sos = 5,
  SosAck = 6,
  CommandNotice = 7
};

constexpr uint8_t POSITION_PAYLOAD_VERSION = 1;
constexpr size_t POSITION_PAYLOAD_SIZE = 27;
constexpr uint8_t POSITION_FLAG_FIX = 1U << 0;
constexpr uint8_t POSITION_FLAG_ALTITUDE = 1U << 1;
constexpr uint8_t POSITION_FLAG_SPEED = 1U << 2;
constexpr uint8_t POSITION_FLAG_HDOP = 1U << 3;
constexpr uint8_t POSITION_FLAG_UTC = 1U << 4;

constexpr uint8_t SOS_PAYLOAD_VERSION = 1;
constexpr size_t SOS_PAYLOAD_SIZE = 25;
constexpr uint8_t SOS_FLAG_POSITION_VALID = 1U << 0;
constexpr uint8_t SOS_FLAG_LAST_KNOWN = 1U << 1;

constexpr uint8_t COMMAND_NOTICE_VERSION = 1;
constexpr size_t COMMAND_NOTICE_PAYLOAD_SIZE = 16;

enum class CommandNoticeKind : uint8_t {
  Return = 1,
  CheckIn = 2,
  Hold = 3,
  MoveToWaypoint = 4
};

struct FrameView {
  FrameType type = FrameType::Hello;
  uint32_t previousHop = 0;
  uint32_t nextHop = 0;
  uint32_t bootCounter = 0;
  uint32_t frameCounter = 0;
  uint32_t messageId = 0;
  uint8_t payloadLength = 0;
  uint8_t hopLimit = 0;
  uint32_t routeTag = 0;
};

struct MessageView {
  MessageType type = MessageType::UserData;
  uint32_t origin = 0;
  uint32_t destination = 0;
  uint32_t bootCounter = 0;
  uint32_t messageCounter = 0;
  uint32_t messageId = 0;
  uint8_t payloadLength = 0;
};

// ============================================================
// 5. LIMITS / TIMINGS
// ============================================================

constexpr size_t MAX_LAB_NODES = 5;
constexpr size_t MAX_NEIGHBORS = MAX_LAB_NODES;
constexpr size_t MAX_REPLAY_PEERS = MAX_LAB_NODES;
constexpr size_t MAX_TX_QUEUE = 10;

constexpr uint32_t HELLO_INTERVAL_MIN_MS = 4500;
constexpr uint32_t HELLO_INTERVAL_MAX_MS = 6500;
constexpr uint32_t NEIGHBOR_STALE_MS = 22000;

constexpr uint32_t ACK_TIMEOUT_FLOOR_MS = 1800;
uint32_t ackTimeoutMs = ACK_TIMEOUT_FLOOR_MS;
constexpr uint8_t MAX_DATA_ATTEMPTS = 4;

constexpr uint32_t OLED_REFRESH_MS = 450;
constexpr uint32_t UI_ANIMATION_FRAME_MS = 55;
constexpr uint32_t UI_FAST_FRAME_MS = 110;
constexpr uint32_t UI_TRANSITION_MS = 150;
constexpr uint32_t UI_BOOT_DURATION_MS = 1250;
constexpr uint32_t UI_TOAST_DEFAULT_MS = 1500;
constexpr uint32_t UI_CRITICAL_MAX_DEFER_MS = 45;
constexpr uint32_t UI_CRITICAL_FRAME_MS = 80;
constexpr uint32_t UI_SUCCESS_ANIMATION_MS = 1250;
constexpr uint32_t UI_TOAST_SLIDE_MS = 180;
constexpr uint32_t SERIAL_STATUS_INTERVAL_MS = 15000;
constexpr size_t CONSOLE_LINE_SIZE = 220;

constexpr uint32_t DIAG_PONG_TIMEOUT_MS = 12000;
constexpr size_t MAX_DIAG_PENDING = 8;
constexpr uint16_t FIELD_TEST_MAX_PACKETS = 500;
constexpr uint32_t FIELD_TEST_MIN_INTERVAL_MS = 250;
constexpr uint32_t FIELD_TEST_MAX_INTERVAL_MS = 60000;

// ============================================================
// ARDUINO .INO PREPROCESSOR TYPE FORWARD DECLARATIONS
// ============================================================
// Arduino auto-generates free-function prototypes before the first function.
// Keep every user-defined type that may appear in those generated prototypes
// visible here, even when its full definition lives in a later section.
// This is declaration-only: no layout, behavior, or wire format changes.

enum class ReplayDecision : uint8_t;
struct ReplayPeer;
struct NeighborEntry;
struct StaticRouteEntry;
enum class RouteSource : uint8_t;
enum class RoutePolicy : uint8_t;
struct TxEntry;
enum BleEventType : uint8_t;
enum class FieldTestState : uint8_t;
enum class FieldTestMode : uint8_t;
struct DiagPendingProbe;
struct FieldTestContext;
struct LabLinkFault;
struct PositionRecord;
struct ActiveSosRecord;

enum class QueueMessageResult : uint8_t;
struct QueuedMessageMeta;
enum class CommandType : uint8_t;
enum class CommandSource : uint8_t;
enum class CommandStatus : uint8_t;
struct CommandRequest;
struct CommandResult;
struct BinaryWriter;
enum class BlePacketType : uint8_t;
enum class BleState : uint8_t;
struct BleRawPacketSlot;
struct BleRingState;
struct BleReassemblyState;
struct BleOutTransportState;
class SecureMeshBleServerCallbacks;
class SecureMeshBleCommandCallbacks;
enum class UiScene : uint8_t;
enum class UiAction : uint8_t;
enum class UiMenuId : uint8_t;
enum class UiFeatureId : uint8_t;
enum class UiItemKind : uint8_t;
enum class UiFeatureState : uint8_t;
enum class UiIcon : uint8_t;
enum class UiOverlayKind : uint8_t;
struct UiMenuItem;
struct UiMenuDefinition;
struct UiMessageEntry;
struct UiRuntimeState;
struct UiRouteView;

// Explicit UI public API prototypes. This avoids relying on Arduino's generated
// prototypes for UI functions that are called by earlier radio/command sections.
void initializeOled();
void initializeUi();
void processUi();
void uiMarkDirty();
void uiStoreIncomingMessage(uint32_t origin, const uint8_t* payload, uint8_t length);
void uiNotifyMessageQueued(uint32_t destination);
void uiShowToast(const char* title, const char* body, uint32_t durationMs);
size_t uiCountVisibleRoutes();
uint8_t uiGetSceneCode();
uint8_t uiGetMenuIndex();
uint8_t uiGetInboxCount();
void emitBleEvent(uint8_t eventType, const uint8_t* payload, uint16_t length);

// ============================================================
// 6. BASIC HELPERS
// ============================================================

bool timeReached(uint32_t now, uint32_t deadline) {
  return static_cast<int32_t>(now - deadline) >= 0;
}

uint32_t randomBetween(uint32_t minimum, uint32_t maximum) {
  if (maximum <= minimum) return minimum;
  return minimum + (esp_random() % (maximum - minimum + 1));
}

float clampFloat(float value, float minimum, float maximum) {
  if (value < minimum) return minimum;
  if (value > maximum) return maximum;
  return value;
}

void writeU16(uint8_t* data, size_t offset, uint16_t value) {
  data[offset] = static_cast<uint8_t>(value & 0xFFU);
  data[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xFFU);
}

void writeU32(uint8_t* data, size_t offset, uint32_t value) {
  data[offset] = static_cast<uint8_t>(value & 0xFFUL);
  data[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xFFUL);
  data[offset + 2] = static_cast<uint8_t>((value >> 16) & 0xFFUL);
  data[offset + 3] = static_cast<uint8_t>((value >> 24) & 0xFFUL);
}

uint16_t readU16(const uint8_t* data, size_t offset) {
  return static_cast<uint16_t>(data[offset]) |
         (static_cast<uint16_t>(data[offset + 1]) << 8);
}

uint32_t readU32(const uint8_t* data, size_t offset) {
  return static_cast<uint32_t>(data[offset]) |
         (static_cast<uint32_t>(data[offset + 1]) << 8) |
         (static_cast<uint32_t>(data[offset + 2]) << 16) |
         (static_cast<uint32_t>(data[offset + 3]) << 24);
}

void formatNodeId(uint32_t nodeId, char out[9]) {
  snprintf(out, 9, "%08lX", static_cast<unsigned long>(nodeId));
}

// ============================================================
// 7. IDENTITY / PERSISTENT NONCE DOMAIN
// ============================================================

Preferences preferences;
uint32_t localNodeId = 0;
uint32_t localBootCounter = 0;
uint32_t nextFrameCounter = 1;
uint32_t nextMessageId = 1;
uint32_t helloSequence = 1;
char localIdText[9] = "00000000";

bool identityReady = false;
bool cryptoReady = false;

// Deterministic laboratory link overlay.  It is deliberately NOT persisted:
// a reboot must always return the radio to the physical topology.  A rule can
// either hard-block one neighbour or override only the routing metric.  The
// latter is useful with three radios on one desk: the RF link still exists,
// while VANGUARD can be forced to prefer A->B->C and keep A->C as a standby.
constexpr size_t MAX_LAB_LINK_FAULTS = MAX_LAB_NODES - 1;
constexpr uint32_t DEFAULT_LAB_LINK_FAULT_MS = 30000UL;
constexpr uint32_t LAB_RULE_MANUAL_DURATION = 0xFFFFFFFFu;
constexpr uint8_t LAB_RULE_BLOCK = 1u << 0;
constexpr uint8_t LAB_RULE_METRIC_OVERRIDE = 1u << 1;
struct LabLinkFault {
  bool used = false;
  uint32_t nodeId = 0;
  uint8_t flags = 0;
  bool manual = false;
  uint32_t expiresAtMs = 0;
  uint16_t reliabilityQ15 = 24575; // ~= 75%
  uint32_t ecaQ16 = 1u << 16;
};
LabLinkFault labLinkFaults[MAX_LAB_LINK_FAULTS];
uint32_t statLabFaultRxDrops = 0;
uint32_t statLabFaultTxDrops = 0;

bool labRuleExpired(const LabLinkFault& rule, uint32_t now) {
  return rule.used && !rule.manual && timeReached(now, rule.expiresAtMs);
}

LabLinkFault* findLabLinkRule(uint32_t nodeId, uint32_t now) {
  if (nodeId == 0 || nodeId == BROADCAST_ID) return nullptr;
  for (auto& rule : labLinkFaults) {
    if (!rule.used || rule.nodeId != nodeId) continue;
    if (labRuleExpired(rule, now)) {
      rule = LabLinkFault{};
      return nullptr;
    }
    return &rule;
  }
  return nullptr;
}

bool isLabLinkBlockedAt(uint32_t nodeId, uint32_t now) {
  LabLinkFault* rule = findLabLinkRule(nodeId, now);
  return rule != nullptr && (rule->flags & LAB_RULE_BLOCK) != 0;
}

bool isLabLinkBlocked(uint32_t nodeId) {
  return isLabLinkBlockedAt(nodeId, millis());
}

bool getLabMetricOverride(uint32_t nodeId, uint16_t& reliabilityQ15, uint32_t& ecaQ16) {
  LabLinkFault* rule = findLabLinkRule(nodeId, millis());
  if (rule == nullptr || (rule->flags & LAB_RULE_METRIC_OVERRIDE) == 0) return false;
  reliabilityQ15 = rule->reliabilityQ15;
  ecaQ16 = rule->ecaQ16;
  return true;
}

uint8_t countActiveLabLinkFaults(uint32_t now) {
  uint8_t count = 0;
  for (auto& rule : labLinkFaults) {
    if (!rule.used) continue;
    if (labRuleExpired(rule, now)) {
      rule = LabLinkFault{};
      continue;
    }
    count++;
  }
  return count;
}

bool setLabLinkPolicy(
  uint32_t nodeId,
  uint8_t flags,
  uint32_t durationMs,
  uint16_t reliabilityQ15 = 24575,
  uint32_t ecaQ16 = (1u << 16)
) {
  if (nodeId == 0 || nodeId == BROADCAST_ID || nodeId == localNodeId) return false;
  if ((flags & ~(LAB_RULE_BLOCK | LAB_RULE_METRIC_OVERRIDE)) != 0) return false;
  if (flags == 0 || durationMs == 0) {
    for (auto& rule : labLinkFaults) {
      if (rule.used && rule.nodeId == nodeId) { rule = LabLinkFault{}; return true; }
    }
    return true;
  }
  if ((flags & LAB_RULE_METRIC_OVERRIDE) != 0) {
    if (reliabilityQ15 == 0 || ecaQ16 < (1u << 16)) return false;
  }
  LabLinkFault* target = nullptr;
  for (auto& rule : labLinkFaults) {
    if (rule.used && rule.nodeId == nodeId) { target = &rule; break; }
  }
  if (target == nullptr) {
    for (auto& rule : labLinkFaults) {
      if (!rule.used) { target = &rule; break; }
    }
  }
  if (target == nullptr) return false;
  target->used = true;
  target->nodeId = nodeId;
  target->flags = flags;
  target->manual = durationMs == LAB_RULE_MANUAL_DURATION;
  target->expiresAtMs = target->manual ? 0u : millis() + durationMs;
  target->reliabilityQ15 = reliabilityQ15;
  target->ecaQ16 = ecaQ16;
  return true;
}

bool setLabLinkFault(uint32_t nodeId, uint32_t durationMs) {
  return setLabLinkPolicy(nodeId, durationMs == 0 ? 0 : LAB_RULE_BLOCK, durationMs);
}

uint32_t allocateMessageId() {
  uint32_t value = nextMessageId++;
  if (value == 0) {
    value = nextMessageId++;
  }
  return value;
}

bool allocateFrameCounter(uint32_t& value) {
  // Fail closed instead of wrapping and reusing a nonce in the same boot.
  if (nextFrameCounter == 0 || nextFrameCounter == UINT32_MAX) {
    return false;
  }

  value = nextFrameCounter++;
  return value != 0;
}

bool initializeIdentity() {
  uint8_t mac[6] = {0};
  if (esp_read_mac(mac, ESP_MAC_WIFI_STA) != ESP_OK) {
    return false;
  }

  localNodeId =
    (static_cast<uint32_t>(mac[2]) << 24) |
    (static_cast<uint32_t>(mac[3]) << 16) |
    (static_cast<uint32_t>(mac[4]) << 8) |
    static_cast<uint32_t>(mac[5]);

  if (localNodeId == 0 || localNodeId == BROADCAST_ID) {
    return false;
  }

  formatNodeId(localNodeId, localIdText);

  if (!preferences.begin("smesh", false)) {
    return false;
  }

  const uint32_t stored = preferences.getUInt("bootctr", 0);
  if (stored == UINT32_MAX) {
    preferences.end();
    return false;
  }

  localBootCounter = stored + 1;
  if (localBootCounter == 0) {
    preferences.end();
    return false;
  }

  const size_t written = preferences.putUInt("bootctr", localBootCounter);
  preferences.end();

  if (written != sizeof(uint32_t)) {
    localBootCounter = 0;
    return false;
  }

  // Deterministic start is easier to reason about because bootCounter changes
  // the nonce domain on every successful boot.
  nextFrameCounter = 1;
  nextMessageId = esp_random();
  if (nextMessageId == 0) nextMessageId = 1;

  identityReady = true;
  return true;
}

// ============================================================
// 8. AES-256-GCM
// ============================================================

mbedtls_gcm_context gcmContext;

bool initializeCrypto() {
  if (!identityReady) return false;

  mbedtls_gcm_init(&gcmContext);
  const int state = mbedtls_gcm_setkey(
    &gcmContext,
    MBEDTLS_CIPHER_ID_AES,
    DEVELOPMENT_GROUP_KEY,
    256
  );

  cryptoReady = (state == 0);
  return cryptoReady;
}

void buildNonce(
  uint32_t origin,
  uint32_t bootCounter,
  uint32_t frameCounter,
  uint8_t nonce[AES_GCM_NONCE_SIZE]
) {
  writeU32(nonce, 0, origin);
  writeU32(nonce, 4, bootCounter);
  writeU32(nonce, 8, frameCounter);
}

bool encryptAuthenticatedPayload(
  uint32_t nonceOrigin,
  uint32_t bootCounter,
  uint32_t counter,
  const uint8_t* aad,
  size_t aadLength,
  const uint8_t* plaintext,
  size_t payloadLength,
  uint8_t* ciphertext,
  uint8_t tag[AES_GCM_TAG_SIZE]
) {
  if (!cryptoReady || aad == nullptr || tag == nullptr) return false;

  uint8_t nonce[AES_GCM_NONCE_SIZE];
  buildNonce(nonceOrigin, bootCounter, counter, nonce);

  uint8_t dummyIn = 0;
  uint8_t dummyOut = 0;
  const uint8_t* input =
    (payloadLength > 0 && plaintext != nullptr) ? plaintext : &dummyIn;
  uint8_t* output =
    (payloadLength > 0 && ciphertext != nullptr) ? ciphertext : &dummyOut;

  const int state = mbedtls_gcm_crypt_and_tag(
    &gcmContext,
    MBEDTLS_GCM_ENCRYPT,
    payloadLength,
    nonce,
    sizeof(nonce),
    aad,
    aadLength,
    input,
    output,
    AES_GCM_TAG_SIZE,
    tag
  );

  return state == 0;
}

bool decryptAuthenticatedPayload(
  uint32_t nonceOrigin,
  uint32_t bootCounter,
  uint32_t counter,
  const uint8_t* aad,
  size_t aadLength,
  const uint8_t* ciphertext,
  size_t payloadLength,
  const uint8_t tag[AES_GCM_TAG_SIZE],
  uint8_t* plaintext
) {
  if (!cryptoReady || aad == nullptr || tag == nullptr) return false;

  uint8_t nonce[AES_GCM_NONCE_SIZE];
  buildNonce(nonceOrigin, bootCounter, counter, nonce);

  uint8_t dummyIn = 0;
  uint8_t dummyOut = 0;
  const uint8_t* input =
    (payloadLength > 0 && ciphertext != nullptr) ? ciphertext : &dummyIn;
  uint8_t* output =
    (payloadLength > 0 && plaintext != nullptr) ? plaintext : &dummyOut;

  const int state = mbedtls_gcm_auth_decrypt(
    &gcmContext,
    payloadLength,
    nonce,
    sizeof(nonce),
    aad,
    aadLength,
    tag,
    AES_GCM_TAG_SIZE,
    input,
    output
  );

  return state == 0;
}

uint16_t buildMessageEnvelope(
  MessageType messageType,
  uint32_t destination,
  uint32_t messageId,
  const uint8_t* payload,
  uint8_t payloadLength,
  uint8_t* output,
  MessageView* builtView = nullptr
) {
  if (!cryptoReady || output == nullptr || payloadLength > MAX_APP_PAYLOAD) {
    return 0;
  }

  // Message and hop frames draw from the same monotonic counter space for
  // this NodeID+BootCounter, so the shared GCM key never sees nonce reuse.
  uint32_t messageCounter = 0;
  if (!allocateFrameCounter(messageCounter)) {
    return 0;
  }

  MessageView view;
  view.type = messageType;
  view.origin = localNodeId;
  view.destination = destination;
  view.bootCounter = localBootCounter;
  view.messageCounter = messageCounter;
  view.messageId = messageId;
  view.payloadLength = payloadLength;

  memset(output, 0, MAX_MESSAGE_WIRE);
  writeU16(output, MOFF_MAGIC, MESSAGE_MAGIC);
  output[MOFF_VERSION] = MESSAGE_VERSION;
  writeU16(output, MOFF_NETWORK, NETWORK_ID);
  output[MOFF_MESSAGE_TYPE] = static_cast<uint8_t>(view.type);
  writeU32(output, MOFF_ORIGIN, view.origin);
  writeU32(output, MOFF_DESTINATION, view.destination);
  writeU32(output, MOFF_BOOT_COUNTER, view.bootCounter);
  writeU32(output, MOFF_MESSAGE_COUNTER, view.messageCounter);
  writeU32(output, MOFF_MESSAGE_ID, view.messageId);
  output[MOFF_PAYLOAD_LENGTH] = view.payloadLength;

  uint8_t* ciphertext = output + MESSAGE_HEADER_SIZE;
  uint8_t* tag = ciphertext + payloadLength;

  if (!encryptAuthenticatedPayload(
        view.origin,
        view.bootCounter,
        view.messageCounter,
        output,
        MESSAGE_HEADER_SIZE,
        payload,
        payloadLength,
        ciphertext,
        tag)) {
    return 0;
  }

  if (builtView != nullptr) *builtView = view;
  return static_cast<uint16_t>(
    MESSAGE_HEADER_SIZE + payloadLength + AES_GCM_TAG_SIZE
  );
}

bool parseAndAuthenticateMessage(
  const uint8_t* input,
  size_t length,
  MessageView& view,
  uint8_t* plaintext
) {
  if (!cryptoReady || input == nullptr ||
      length < MESSAGE_HEADER_SIZE + AES_GCM_TAG_SIZE ||
      length > MAX_MESSAGE_WIRE) {
    return false;
  }

  if (readU16(input, MOFF_MAGIC) != MESSAGE_MAGIC ||
      input[MOFF_VERSION] != MESSAGE_VERSION ||
      readU16(input, MOFF_NETWORK) != NETWORK_ID) {
    return false;
  }

  const uint8_t rawMessageType = input[MOFF_MESSAGE_TYPE];
  if (rawMessageType < static_cast<uint8_t>(MessageType::UserData) ||
      rawMessageType > static_cast<uint8_t>(MessageType::DiagPong)) {
    return false;
  }

  view.type = static_cast<MessageType>(rawMessageType);
  view.origin = readU32(input, MOFF_ORIGIN);
  view.destination = readU32(input, MOFF_DESTINATION);
  view.bootCounter = readU32(input, MOFF_BOOT_COUNTER);
  view.messageCounter = readU32(input, MOFF_MESSAGE_COUNTER);
  view.messageId = readU32(input, MOFF_MESSAGE_ID);
  view.payloadLength = input[MOFF_PAYLOAD_LENGTH];

  if (view.origin == 0 ||
      view.origin == BROADCAST_ID ||
      view.destination == 0 ||
      view.bootCounter == 0 ||
      view.messageCounter == 0 ||
      view.payloadLength == 0 ||
      view.payloadLength > MAX_APP_PAYLOAD ||
      MESSAGE_HEADER_SIZE + view.payloadLength + AES_GCM_TAG_SIZE != length) {
    return false;
  }

  const uint8_t* ciphertext = input + MESSAGE_HEADER_SIZE;
  const uint8_t* tag = ciphertext + view.payloadLength;

  return decryptAuthenticatedPayload(
    view.origin,
    view.bootCounter,
    view.messageCounter,
    input,
    MESSAGE_HEADER_SIZE,
    ciphertext,
    view.payloadLength,
    tag,
    plaintext
  );
}

uint16_t buildFrame(
  FrameType type,
  uint32_t nextHop,
  uint32_t messageId,
  const uint8_t* payload,
  uint8_t payloadLength,
  uint8_t hopLimit,
  uint8_t* output,
  FrameView* builtView = nullptr,
  uint32_t routeTag = 0
) {
  if (!cryptoReady || output == nullptr || payloadLength > MAX_HOP_PAYLOAD) {
    return 0;
  }

  const bool routedFrame = (type == FrameType::Data || type == FrameType::Control);
  if ((routedFrame && hopLimit == 0) ||
      (!routedFrame && hopLimit != 0)) {
    return 0;
  }

  uint32_t frameCounter = 0;
  if (!allocateFrameCounter(frameCounter)) {
    return 0;
  }

  FrameView view;
  view.type = type;
  view.previousHop = localNodeId;
  view.nextHop = nextHop;
  view.bootCounter = localBootCounter;
  view.frameCounter = frameCounter;
  view.messageId = messageId;
  view.payloadLength = payloadLength;
  view.hopLimit = hopLimit;
  view.routeTag = routeTag;

  memset(output, 0, MAX_WIRE_PACKET);
  writeU16(output, OFF_MAGIC, MESH_MAGIC);
  output[OFF_VERSION] = MESH_VERSION;
  output[OFF_TYPE] = static_cast<uint8_t>(view.type);
  writeU16(output, OFF_NETWORK, NETWORK_ID);
  writeU32(output, OFF_PREVIOUS_HOP, view.previousHop);
  writeU32(output, OFF_NEXT_HOP, view.nextHop);
  writeU32(output, OFF_BOOT_COUNTER, view.bootCounter);
  writeU32(output, OFF_FRAME_COUNTER, view.frameCounter);
  writeU32(output, OFF_MESSAGE_ID, view.messageId);
  output[OFF_PAYLOAD_LENGTH] = view.payloadLength;
  output[OFF_HOP_LIMIT] = view.hopLimit;
  writeU32(output, OFF_ROUTE_TAG, view.routeTag);

  uint8_t* ciphertext = output + HEADER_SIZE;
  uint8_t* tag = ciphertext + payloadLength;

  if (!encryptAuthenticatedPayload(
        view.previousHop,
        view.bootCounter,
        view.frameCounter,
        output,
        HEADER_SIZE,
        payload,
        payloadLength,
        ciphertext,
        tag)) {
    return 0;
  }

  if (builtView != nullptr) *builtView = view;
  return static_cast<uint16_t>(
    HEADER_SIZE + payloadLength + AES_GCM_TAG_SIZE
  );
}

bool parseAndAuthenticateFrame(
  const uint8_t* input,
  size_t length,
  FrameView& view,
  uint8_t* plaintext
) {
  if (!cryptoReady || input == nullptr ||
      length < HEADER_SIZE + AES_GCM_TAG_SIZE ||
      length > MAX_WIRE_PACKET) {
    return false;
  }

  if (readU16(input, OFF_MAGIC) != MESH_MAGIC ||
      input[OFF_VERSION] != MESH_VERSION ||
      readU16(input, OFF_NETWORK) != NETWORK_ID) {
    return false;
  }

  const uint8_t rawType = input[OFF_TYPE];
  if (rawType < static_cast<uint8_t>(FrameType::Hello) ||
      rawType > static_cast<uint8_t>(FrameType::Control)) {
    return false;
  }

  view.type = static_cast<FrameType>(rawType);
  view.previousHop = readU32(input, OFF_PREVIOUS_HOP);
  view.nextHop = readU32(input, OFF_NEXT_HOP);
  view.bootCounter = readU32(input, OFF_BOOT_COUNTER);
  view.frameCounter = readU32(input, OFF_FRAME_COUNTER);
  view.messageId = readU32(input, OFF_MESSAGE_ID);
  view.payloadLength = input[OFF_PAYLOAD_LENGTH];
  view.hopLimit = input[OFF_HOP_LIMIT];
  view.routeTag = readU32(input, OFF_ROUTE_TAG);

  if (view.previousHop == 0 ||
      view.previousHop == BROADCAST_ID ||
      view.nextHop == 0 ||
      view.bootCounter == 0 ||
      view.frameCounter == 0 ||
      view.payloadLength > MAX_HOP_PAYLOAD ||
      (view.type != FrameType::Data && view.routeTag != 0) ||
      ((view.type == FrameType::Data || view.type == FrameType::Control) && view.hopLimit == 0) ||
      ((view.type != FrameType::Data && view.type != FrameType::Control) && view.hopLimit != 0) ||
      HEADER_SIZE + view.payloadLength + AES_GCM_TAG_SIZE != length) {
    return false;
  }

  const uint8_t* ciphertext = input + HEADER_SIZE;
  const uint8_t* tag = ciphertext + view.payloadLength;

  return decryptAuthenticatedPayload(
    view.previousHop,
    view.bootCounter,
    view.frameCounter,
    input,
    HEADER_SIZE,
    ciphertext,
    view.payloadLength,
    tag,
    plaintext
  );
}

// ============================================================
// 9. ANTI-REPLAY WINDOWS
// ============================================================

enum class ReplayDecision : uint8_t {
  Fresh,
  Duplicate,
  TooOld
};

struct ReplayPeer {
  bool used = false;
  uint32_t origin = 0;
  uint32_t bootCounter = 0;
  uint32_t highestFrameCounter = 0;
  uint64_t bitmap = 0;
  uint32_t lastSeenAtMs = 0;
};

// Hop replay state protects authenticated radio-hop frames. Message replay
// state independently protects authenticated logical messages at delivery.
ReplayPeer hopReplayPeers[MAX_REPLAY_PEERS];
ReplayPeer messageReplayPeers[MAX_REPLAY_PEERS];
ReplayPeer relayMessageReplayPeers[MAX_REPLAY_PEERS];

int findReplayPeer(
  ReplayPeer* table,
  size_t tableSize,
  uint32_t origin
) {
  for (size_t i = 0; i < tableSize; ++i) {
    if (table[i].used && table[i].origin == origin) {
      return static_cast<int>(i);
    }
  }
  return -1;
}

int getReplayPeer(
  ReplayPeer* table,
  size_t tableSize,
  uint32_t origin
) {
  int existing = findReplayPeer(table, tableSize, origin);
  if (existing >= 0) return existing;

  // 5-node laboratory profile: every authenticated member has a stable slot. Never evict
  // replay state: LRU eviction turns table pressure into a replay bypass.
  for (size_t i = 0; i < tableSize; ++i) {
    if (!table[i].used) {
      table[i] = ReplayPeer{};
      table[i].used = true;
      table[i].origin = origin;
      table[i].lastSeenAtMs = millis();
      return static_cast<int>(i);
    }
  }
  return -1;
}

ReplayDecision checkAndMarkReplayValues(
  ReplayPeer* table,
  size_t tableSize,
  uint32_t origin,
  uint32_t bootCounter,
  uint32_t frameCounter
) {
  const int index = getReplayPeer(table, tableSize, origin);
  if (index < 0) return ReplayDecision::TooOld; // fail closed on capacity exhaustion
  ReplayPeer& peer = table[index];
  peer.lastSeenAtMs = millis();

  if (peer.bootCounter == 0 || bootCounter > peer.bootCounter) {
    peer.bootCounter = bootCounter;
    peer.highestFrameCounter = frameCounter;
    peer.bitmap = 1ULL;
    return ReplayDecision::Fresh;
  }

  if (bootCounter < peer.bootCounter) {
    return ReplayDecision::TooOld;
  }

  if (frameCounter > peer.highestFrameCounter) {
    const uint32_t shift = frameCounter - peer.highestFrameCounter;
    peer.bitmap = (shift >= 64)
      ? 1ULL
      : ((peer.bitmap << shift) | 1ULL);
    peer.highestFrameCounter = frameCounter;
    return ReplayDecision::Fresh;
  }

  const uint32_t distance = peer.highestFrameCounter - frameCounter;
  if (distance >= 64) {
    return ReplayDecision::TooOld;
  }

  const uint64_t bit = 1ULL << distance;
  if ((peer.bitmap & bit) != 0) {
    return ReplayDecision::Duplicate;
  }

  peer.bitmap |= bit;
  return ReplayDecision::Fresh;
}

ReplayDecision checkAndMarkHopReplay(const FrameView& view) {
  return checkAndMarkReplayValues(
    hopReplayPeers,
    MAX_REPLAY_PEERS,
    view.previousHop,
    view.bootCounter,
    view.frameCounter
  );
}

ReplayDecision checkAndMarkMessageReplay(const MessageView& view) {
  return checkAndMarkReplayValues(
    messageReplayPeers,
    MAX_REPLAY_PEERS,
    view.origin,
    view.bootCounter,
    view.messageCounter
  );
}

ReplayDecision checkReplayValuesWithoutMark(
  ReplayPeer* table,
  size_t tableSize,
  uint32_t origin,
  uint32_t bootCounter,
  uint32_t frameCounter
) {
  const int index = findReplayPeer(table, tableSize, origin);
  if (index < 0) return ReplayDecision::Fresh;

  const ReplayPeer& peer = table[index];
  if (peer.bootCounter == 0 || bootCounter > peer.bootCounter) {
    return ReplayDecision::Fresh;
  }
  if (bootCounter < peer.bootCounter) {
    return ReplayDecision::TooOld;
  }
  if (frameCounter > peer.highestFrameCounter) {
    return ReplayDecision::Fresh;
  }

  const uint32_t distance = peer.highestFrameCounter - frameCounter;
  if (distance >= 64) return ReplayDecision::TooOld;
  return (peer.bitmap & (1ULL << distance)) != 0
    ? ReplayDecision::Duplicate
    : ReplayDecision::Fresh;
}

ReplayDecision checkRelayMessageReplay(const MessageView& view) {
  return checkReplayValuesWithoutMark(
    relayMessageReplayPeers,
    MAX_REPLAY_PEERS,
    view.origin,
    view.bootCounter,
    view.messageCounter
  );
}

void commitRelayMessageReplay(const MessageView& view) {
  (void)checkAndMarkReplayValues(
    relayMessageReplayPeers,
    MAX_REPLAY_PEERS,
    view.origin,
    view.bootCounter,
    view.messageCounter
  );
}

bool rememberKnownNode(uint32_t nodeId);

// ============================================================
// 10. NEIGHBORS - MEASURED VALUES, NO MAGIC ROUTE SCORE
// ============================================================

struct NeighborEntry {
  bool used = false;
  uint32_t nodeId = 0;
  uint32_t remoteBootCounter = 0;
  uint32_t lastSeenAtMs = 0;

  float rssiEwma = -120.0f;
  float snrEwma = -20.0f;
  uint32_t rxFrames = 0;

  bool hasHelloSequence = false;
  uint32_t lastHelloSequence = 0;
  float helloRxPdrEwma = 100.0f;

  uint32_t txAttempts = 0;
  uint32_t txAckSuccesses = 0;
  float txAckPdrEwma = 100.0f;

  uint32_t remoteNetworkEpoch = 0;
  uint32_t remoteManifestDigest = 0;
  uint8_t remoteNodeSlot = 0xFF;
  bool manifestCompatible = false;
};

NeighborEntry neighbors[MAX_NEIGHBORS];

int findNeighborIndex(uint32_t nodeId) {
  for (size_t i = 0; i < MAX_NEIGHBORS; ++i) {
    if (neighbors[i].used && neighbors[i].nodeId == nodeId) {
      return static_cast<int>(i);
    }
  }
  return -1;
}

int getOrCreateNeighborIndex(uint32_t nodeId) {
  if (nodeId == 0 || nodeId == BROADCAST_ID || nodeId == localNodeId) {
    return -1;
  }

  const int existing = findNeighborIndex(nodeId);
  if (existing >= 0) return existing;

  // Do not evict a live authenticated neighbor merely because a new identity
  // appears. Stable slots are required for routing evidence and fault analysis.
  for (size_t i = 0; i < MAX_NEIGHBORS; ++i) {
    if (!neighbors[i].used) {
      neighbors[i] = NeighborEntry{};
      neighbors[i].used = true;
      neighbors[i].nodeId = nodeId;
      neighbors[i].lastSeenAtMs = millis();
      return static_cast<int>(i);
    }
  }
  return -1;
}

extern VanguardManifest::KnownRegistry<MAX_LAB_NODES> knownNodeRegistry;

void updateNeighborReception(
  const FrameView& view,
  int16_t rssiDbm,
  float snrDb
) {
  const int index = getOrCreateNeighborIndex(view.previousHop);
  if (index < 0) return;

  NeighborEntry& entry = neighbors[index];
  const bool wasKnown = knownNodeRegistry.contains(view.previousHop);
  const bool knownPersisted = rememberKnownNode(view.previousHop);
  if (!wasKnown && knownPersisted) {
    uint8_t event[4];
    writeU32(event, 0, view.previousHop);
    emitBleEvent(27 /* EVT_KNOWN_NODE_ADDED */, event, sizeof(event));
  }

  if (entry.remoteBootCounter != view.bootCounter) {
    entry.remoteBootCounter = view.bootCounter;
    entry.hasHelloSequence = false;
    entry.lastHelloSequence = 0;
    entry.helloRxPdrEwma = 100.0f;
  }

  const float alpha = (entry.rxFrames == 0) ? 1.0f : 0.25f;
  entry.rssiEwma = entry.rssiEwma * (1.0f - alpha) + rssiDbm * alpha;
  entry.snrEwma = entry.snrEwma * (1.0f - alpha) + snrDb * alpha;
  entry.rxFrames++;
  entry.lastSeenAtMs = millis();
}

void updateNeighborHello(
  const FrameView& view,
  uint32_t sequence
) {
  const int index = getOrCreateNeighborIndex(view.previousHop);
  if (index < 0) return;

  NeighborEntry& entry = neighbors[index];

  if (!entry.hasHelloSequence) {
    entry.lastHelloSequence = sequence;
    entry.hasHelloSequence = true;
    return;
  }

  if (sequence > entry.lastHelloSequence) {
    const uint32_t delta = sequence - entry.lastHelloSequence;
    const float samplePdr = clampFloat(100.0f / static_cast<float>(delta), 0.0f, 100.0f);
    entry.helloRxPdrEwma =
      entry.helloRxPdrEwma * 0.75f + samplePdr * 0.25f;
    entry.lastHelloSequence = sequence;
  }
}

void recordNeighborTxResult(uint32_t nodeId, bool success) {
  const int index = getOrCreateNeighborIndex(nodeId);
  if (index < 0) return;

  NeighborEntry& entry = neighbors[index];
  entry.txAttempts++;
  if (success) entry.txAckSuccesses++;

  const float sample = success ? 100.0f : 0.0f;
  entry.txAckPdrEwma =
    entry.txAckPdrEwma * 0.75f + sample * 0.25f;
}

size_t countFreshNeighbors() {
  size_t count = 0;
  const uint32_t now = millis();
  for (const auto& entry : neighbors) {
    if (entry.used && now - entry.lastSeenAtMs <= NEIGHBOR_STALE_MS) {
      count++;
    }
  }
  return count;
}

// ============================================================
// 11. STATIC ROUTING LAYER
// ============================================================

constexpr size_t MAX_STATIC_ROUTES = 8;

struct StaticRouteEntry {
  bool active = false;
  uint32_t destinationNodeId = 0;
  uint32_t nextHopNodeId = 0;
};

StaticRouteEntry staticRoutes[MAX_STATIC_ROUTES];

enum class RouteSource : uint8_t {
  None,
  DirectNeighbor,
  VanguardDynamic,
  VanguardBackup,
  StaticTable
};

enum class RoutePolicy : uint8_t {
  Routed = 0,
  DirectOnly = 1
};

const char* routeSourceText(RouteSource source) {
  switch (source) {
    case RouteSource::DirectNeighbor: return "DIRECT";
    case RouteSource::VanguardDynamic: return "VANGUARD";
    case RouteSource::VanguardBackup: return "VANGUARD_G2";
    case RouteSource::StaticTable: return "STATIC";
    default: return "NONE";
  }
}

bool isFreshDirectNeighbor(uint32_t nodeId) {
  if (isLabLinkBlocked(nodeId)) return false;
  const int index = findNeighborIndex(nodeId);
  if (index < 0) return false;
  return millis() - neighbors[index].lastSeenAtMs <= NEIGHBOR_STALE_MS;
}

int findStaticRouteIndex(uint32_t destination) {
  for (size_t i = 0; i < MAX_STATIC_ROUTES; ++i) {
    if (staticRoutes[i].active &&
        staticRoutes[i].destinationNodeId == destination) {
      return static_cast<int>(i);
    }
  }
  return -1;
}

bool setStaticRoute(uint32_t destination, uint32_t nextHop) {
  if (destination == 0 || destination == BROADCAST_ID ||
      destination == localNodeId || nextHop == 0 ||
      nextHop == BROADCAST_ID || nextHop == localNodeId) {
    return false;
  }

  int index = findStaticRouteIndex(destination);
  if (index < 0) {
    for (size_t i = 0; i < MAX_STATIC_ROUTES; ++i) {
      if (!staticRoutes[i].active) {
        index = static_cast<int>(i);
        break;
      }
    }
  }

  if (index < 0) return false;

  staticRoutes[index].active = true;
  staticRoutes[index].destinationNodeId = destination;
  staticRoutes[index].nextHopNodeId = nextHop;
  return true;
}

bool removeStaticRoute(uint32_t destination) {
  const int index = findStaticRouteIndex(destination);
  if (index < 0) return false;
  staticRoutes[index] = StaticRouteEntry{};
  return true;
}

constexpr size_t MAX_VANGUARD_ROUTES = MAX_LAB_NODES;
Vanguard::Engine<MAX_VANGUARD_ROUTES> vanguardRouter;
VanguardManifest::Manifest<MAX_LAB_NODES> networkManifest;
VanguardManifest::KnownRegistry<MAX_LAB_NODES> knownNodeRegistry;
VanguardRuntime::State<5, 12, 16, 12, 16, 8> vanguardRuntime;

constexpr uint32_t ROUTING_STORE_MAGIC = 0x38564D53u; // "SMV8"
constexpr uint8_t ROUTING_STORE_VERSION = 2;

struct PersistedKnownNodes {
  uint32_t magic = ROUTING_STORE_MAGIC;
  uint8_t version = ROUTING_STORE_VERSION;
  uint8_t count = 0;
  uint16_t reserved = 0;
  uint32_t nodes[MAX_LAB_NODES] {};
  uint32_t digest = 0;
};

struct PersistedManifest {
  uint32_t magic = ROUTING_STORE_MAGIC;
  uint8_t version = ROUTING_STORE_VERSION;
  uint8_t count = 0;
  uint16_t reserved = 0;
  uint32_t networkEpoch = 0;
  uint32_t nodes[MAX_LAB_NODES] {};
  uint32_t digest = 0;
};

// Read-only v0.8 migration layouts.  They let the 5-node profile shrink RAM/
// NVS without making an upgrade look like identity loss.  A manifest larger
// than the new profile is rejected rather than silently truncated.
struct LegacyPersistedKnownNodesV1 {
  uint32_t magic = ROUTING_STORE_MAGIC;
  uint8_t version = 1;
  uint8_t count = 0;
  uint16_t reserved = 0;
  uint32_t nodes[32] {};
  uint32_t digest = 0;
};
struct LegacyPersistedManifestV1 {
  uint32_t magic = ROUTING_STORE_MAGIC;
  uint8_t version = 1;
  uint8_t count = 0;
  uint16_t reserved = 0;
  uint32_t networkEpoch = 0;
  uint32_t nodes[32] {};
  uint32_t digest = 0;
};

uint32_t legacyKnownDigest(const uint32_t* nodes, uint8_t count) {
  uint32_t h = VanguardManifest::mixU32(2166136261u, count);
  for (uint8_t i = 0; i < count; ++i) h = VanguardManifest::mixU32(h, nodes[i]);
  return h ? h : 1u;
}

bool saveKnownNodeRegistry() {
  PersistedKnownNodes blob;
  blob.count = knownNodeRegistry.count;
  for (size_t i = 0; i < MAX_LAB_NODES; ++i) blob.nodes[i] = knownNodeRegistry.nodes[i];
  blob.digest = knownNodeRegistry.digest();
  if (!preferences.begin("smesh-net", false)) return false;
  const size_t written = preferences.putBytes("known", &blob, sizeof(blob));
  preferences.end();
  return written == sizeof(blob);
}

bool loadKnownNodeRegistry() {
  knownNodeRegistry = VanguardManifest::KnownRegistry<MAX_LAB_NODES>{};
  if (!preferences.begin("smesh-net", true)) return false;
  const size_t length = preferences.getBytesLength("known");

  if (length == sizeof(PersistedKnownNodes)) {
    PersistedKnownNodes blob;
    const size_t read = preferences.getBytes("known", &blob, sizeof(blob));
    preferences.end();
    if (read != sizeof(blob) || blob.magic != ROUTING_STORE_MAGIC ||
        blob.version != ROUTING_STORE_VERSION || blob.count > MAX_LAB_NODES) return false;
    for (uint8_t i = 0; i < blob.count; ++i) {
      if (!knownNodeRegistry.add(blob.nodes[i])) {
        knownNodeRegistry = VanguardManifest::KnownRegistry<MAX_LAB_NODES>{};
        return false;
      }
    }
    if (knownNodeRegistry.digest() != blob.digest) {
      knownNodeRegistry = VanguardManifest::KnownRegistry<MAX_LAB_NODES>{};
      return false;
    }
    return true;
  }

  if (length == sizeof(LegacyPersistedKnownNodesV1)) {
    LegacyPersistedKnownNodesV1 old;
    const size_t read = preferences.getBytes("known", &old, sizeof(old));
    preferences.end();
    if (read != sizeof(old) || old.magic != ROUTING_STORE_MAGIC ||
        old.version != 1 || old.count > 32 ||
        legacyKnownDigest(old.nodes, old.count) != old.digest) return false;
    const uint8_t keep = old.count > MAX_LAB_NODES
      ? static_cast<uint8_t>(MAX_LAB_NODES) : old.count;
    for (uint8_t i = 0; i < keep; ++i) {
      if (!knownNodeRegistry.add(old.nodes[i])) {
        knownNodeRegistry = VanguardManifest::KnownRegistry<MAX_LAB_NODES>{};
        return false;
      }
    }
    // Best-effort atomic migration to the compact v2 layout.  RAM state is
    // still usable if flash writing fails; rememberKnownNode will retry later.
    (void)saveKnownNodeRegistry();
    return true;
  }

  preferences.end();
  return false;
}

bool saveNetworkManifest() {
  PersistedManifest blob;
  blob.count = networkManifest.count;
  blob.networkEpoch = networkManifest.networkEpoch;
  for (size_t i = 0; i < MAX_LAB_NODES; ++i) blob.nodes[i] = networkManifest.nodeBySlot[i];
  blob.digest = networkManifest.digest;
  if (!preferences.begin("smesh-net", false)) return false;
  const size_t written = preferences.putBytes("manifest", &blob, sizeof(blob));
  preferences.end();
  return written == sizeof(blob);
}

bool loadNetworkManifest() {
  networkManifest.clear();
  if (!preferences.begin("smesh-net", true)) return false;
  const size_t length = preferences.getBytesLength("manifest");

  if (length == sizeof(PersistedManifest)) {
    PersistedManifest blob;
    const size_t read = preferences.getBytes("manifest", &blob, sizeof(blob));
    preferences.end();
    if (read != sizeof(blob) || blob.magic != ROUTING_STORE_MAGIC ||
        blob.version != ROUTING_STORE_VERSION || blob.count == 0 ||
        blob.count > MAX_LAB_NODES) return false;
    if (!networkManifest.configure(blob.networkEpoch, blob.nodes, blob.count, localNodeId)) {
      return false;
    }
    if (networkManifest.digest != blob.digest) {
      networkManifest.clear();
      return false;
    }
    return true;
  }

  if (length == sizeof(LegacyPersistedManifestV1)) {
    LegacyPersistedManifestV1 old;
    const size_t read = preferences.getBytes("manifest", &old, sizeof(old));
    preferences.end();
    if (read != sizeof(old) || old.magic != ROUTING_STORE_MAGIC ||
        old.version != 1 || old.count == 0 || old.count > MAX_LAB_NODES) return false;
    // configure() recomputes the old digest because v0.8 hashed all 32 slots;
    // the legacy layout therefore needs an explicit legacy verification first.
    uint32_t h = 2166136261u;
    h = VanguardManifest::mixU32(h, old.networkEpoch);
    h = VanguardManifest::mixU32(h, old.count);
    for (size_t i = 0; i < 32; ++i) h = VanguardManifest::mixU32(h, old.nodes[i]);
    h = h ? h : 1u;
    if (h != old.digest ||
        !networkManifest.configure(old.networkEpoch, old.nodes, old.count, localNodeId)) {
      networkManifest.clear();
      return false;
    }
    (void)saveNetworkManifest();
    return true;
  }

  preferences.end();
  return false;
}

bool rememberKnownNode(uint32_t nodeId) {
  if (nodeId == 0 || nodeId == BROADCAST_ID || nodeId == localNodeId) return false;
  if (knownNodeRegistry.contains(nodeId)) return true;
  if (knownNodeRegistry.count >= VanguardManifest::MAX_SLOTS) return false;

  // add() appends, so a failed NVS commit can be rolled back exactly.  We do
  // not claim an identity is persistently known until the blob is committed.
  const uint8_t oldCount = knownNodeRegistry.count;
  if (!knownNodeRegistry.add(nodeId)) return false;
  if (!saveKnownNodeRegistry()) {
    knownNodeRegistry.nodes[oldCount] = 0;
    knownNodeRegistry.count = oldCount;
    Serial.printf("[PERSIST] known-node commit failed for %08lX\r\n",
      static_cast<unsigned long>(nodeId));
    return false;
  }
  return true;
}

void applyNetworkManifest() {
  const uint8_t localSlot = networkManifest.valid
    ? networkManifest.slotFor(localNodeId)
    : VanguardManifest::INVALID_SLOT;
  const bool exactReady = networkManifest.valid && localSlot < MAX_LAB_NODES;
  vanguardRouter.setIdentity(
    localNodeId,
    localSlot,
    exactReady ? networkManifest.networkEpoch : 0,
    exactReady);
  vanguardRuntime.reset(localNodeId, localBootCounter);
}

void initializeVanguardRouter() {
  (void)loadKnownNodeRegistry();
  (void)loadNetworkManifest();
  applyNetworkManifest();
}

VanguardRuntime::LinkMetric estimateNeighborLinkMetric(uint32_t nodeId) {
  VanguardRuntime::LinkMetric metric;
  const int idx = findNeighborIndex(nodeId);
  if (idx < 0) return metric;
  const NeighborEntry& n = neighbors[idx];

  float perAttempt = 0.75f;
  if (n.txAttempts > 0) {
    const float trials = static_cast<float>(n.txAttempts);
    const float successes = static_cast<float>(n.txAckSuccesses);
    const float phat = successes / trials;
    // One-sided Wilson lower bound (z ~= 1.28). It intentionally penalises
    // links with very little evidence instead of trusting a lucky 1/1 ACK.
    constexpr float z = 1.28155f;
    const float z2 = z * z;
    const float denom = 1.0f + z2 / trials;
    const float center = phat + z2 / (2.0f * trials);
    const float margin = z * sqrtf(
      (phat * (1.0f - phat) + z2 / (4.0f * trials)) / trials);
    perAttempt = clampFloat((center - margin) / denom, 0.05f, 0.999f);
  }

  constexpr uint8_t attempts = MAX_DATA_ATTEMPTS;
  const float fail = 1.0f - perAttempt;
  const float transactionSuccess = 1.0f - powf(fail, attempts);
  const float expectedAttempts = perAttempt > 0.0001f
    ? (1.0f - powf(fail, attempts)) / perAttempt
    : static_cast<float>(attempts);
  metric.reliabilityQ15 = static_cast<uint16_t>(
    clampFloat(transactionSuccess, 0.01f, 0.99997f) * 32767.0f);
  metric.ecaQ16 = static_cast<uint32_t>(
    clampFloat(expectedAttempts, 1.0f, static_cast<float>(attempts)) * 65536.0f);

  // Lab-only soft topology control.  Measurements remain untouched; only the
  // metric consumed by route selection is overridden, and the rule disappears
  // automatically or on explicit clear.
  uint16_t labReliability = 0;
  uint32_t labEca = 0;
  if (getLabMetricOverride(nodeId, labReliability, labEca)) {
    metric.reliabilityQ15 = labReliability;
    metric.ecaQ16 = labEca;
  }
  return metric;
}

void learnVanguardDirectNeighbor(const FrameView& view) {
  Vanguard::Candidate c;
  c.destination = view.previousHop;
  c.nextHop = view.previousHop;
  c.generation.bootEpoch = view.bootCounter;
  c.generation.routeSeq = 1;
  c.advertisedGuardRank = 0;
  c.internalPathMask = 0;
  c.exactMask = networkManifest.valid &&
    networkManifest.slotFor(view.previousHop) < MAX_LAB_NODES;
  const auto metric = estimateNeighborLinkMetric(view.previousHop);
  c.ecaQ16 = metric.ecaQ16;
  c.reliabilityQ15 = metric.reliabilityQ15;
  c.hopCount = 1;
  c.learnedAtMs = millis();
  (void)vanguardRouter.install(c, millis(), false);
}

bool resolveVanguardNextHop(
  uint32_t destination,
  uint32_t& nextHop,
  RouteSource& source,
  uint32_t* pathTag = nullptr,
  bool genericOnly = false
) {
  bool fromBackup = false;
  uint32_t tag = 0;
  bool ok = false;
  if (genericOnly) {
    ok = vanguardRouter.resolveGeneric(destination, millis(), nextHop);
  } else {
    ok = vanguardRouter.resolve(destination, millis(), nextHop, fromBackup, &tag);
  }
  if (!ok || !isFreshDirectNeighbor(nextHop)) return false;
  if (pathTag != nullptr) *pathTag = genericOnly ? 0 : tag;
  source = fromBackup ? RouteSource::VanguardBackup : RouteSource::VanguardDynamic;
  return true;
}

bool requestVanguardRoute(uint32_t destination, bool forceFresh = false);
bool requestVanguardG2(uint32_t destination);
void processVanguardRuntime();
void handleVanguardControl(const FrameView& view, const uint8_t* payload);

bool resolveNextHop(
  uint32_t destination,
  uint32_t& nextHop,
  RouteSource& source,
  RoutePolicy policy = RoutePolicy::Routed,
  uint32_t* pathTag = nullptr
) {
  nextHop = 0;
  source = RouteSource::None;
  if (pathTag != nullptr) *pathTag = 0;

  if (destination == 0 || destination == BROADCAST_ID ||
      destination == localNodeId) {
    return false;
  }

  if (policy == RoutePolicy::DirectOnly) {
    if (!isFreshDirectNeighbor(destination)) return false;
    nextHop = destination;
    source = RouteSource::DirectNeighbor;
    return true;
  }

  // In routed mode VANGUARD owns route selection. Direct neighbours are also
  // learned into the VANGUARD core, so a promoted G2 must not be accidentally
  // overridden by a still-fresh neighbour-table entry after a hop failure.
  if (resolveVanguardNextHop(destination, nextHop, source, pathTag, false)) {
    return true;
  }

  // Direct fallback before VANGUARD has learned this neighbour.
  if (isFreshDirectNeighbor(destination)) {
    nextHop = destination;
    source = RouteSource::DirectNeighbor;
    return true;
  }

  // Compatibility fallback: preserves the proven v0.6.7 field workflow while
  // VANGUARD discovery/control-plane integration is qualified.
  const int staticIndex = findStaticRouteIndex(destination);
  if (staticIndex >= 0 && isFreshDirectNeighbor(staticRoutes[staticIndex].nextHopNodeId)) {
    nextHop = staticRoutes[staticIndex].nextHopNodeId;
    source = RouteSource::StaticTable;
    return true;
  }

  return false;
}

bool resolveRelayNextHop(
  uint32_t destination,
  uint32_t& nextHop,
  RouteSource& source
) {
  nextHop = 0;
  source = RouteSource::None;
  if (destination == 0 || destination == BROADCAST_ID ||
      destination == localNodeId) return false;

  if (isFreshDirectNeighbor(destination)) {
    nextHop = destination;
    source = RouteSource::DirectNeighbor;
    return true;
  }

  // Relayed hop-by-hop traffic may only use a route that remains feasible as
  // a generic successor. A source-private pinned G2 can be longer than FD and
  // is therefore intentionally invisible here.
  if (resolveVanguardNextHop(destination, nextHop, source, nullptr, true)) {
    return true;
  }

  const int staticIndex = findStaticRouteIndex(destination);
  if (staticIndex >= 0 &&
      isFreshDirectNeighbor(staticRoutes[staticIndex].nextHopNodeId)) {
    nextHop = staticRoutes[staticIndex].nextHopNodeId;
    source = RouteSource::StaticTable;
    return true;
  }
  return false;
}

size_t countStaticRoutes() {
  size_t count = 0;
  for (const auto& route : staticRoutes) {
    if (route.active) count++;
  }
  return count;
}

void printRoutingTable() {
  Serial.println("DESTINATION NEXT_HOP SOURCE");
  bool any = false;
  for (const auto& route : staticRoutes) {
    if (!route.active) continue;
    any = true;
    Serial.printf(
      "%08lX    %08lX STATIC\r\n",
      static_cast<unsigned long>(route.destinationNodeId),
      static_cast<unsigned long>(route.nextHopNodeId)
    );
  }
  if (!any) Serial.println("(no static routes)");
}

// ============================================================
// 11. RADIO OBJECT / RF SWITCH
// ============================================================

SX1268 radio = new Module(
  PIN_RADIO_NSS,
  PIN_RADIO_DIO1,
  PIN_RADIO_NRST,
  PIN_RADIO_BUSY
);

volatile bool radioIrqFlag = false;
bool radioReady = false;
bool radioTransmitting = false;
int activeTxIndex = -1;
uint32_t txStartedAtMs = 0;
uint32_t lastRadioRetryAtMs = 0;
int16_t lastRadioError = RADIOLIB_ERR_NONE;

void initializeRfSwitchPins() {
  pinMode(PIN_RADIO_RXEN, OUTPUT);
  pinMode(PIN_RADIO_TXEN, OUTPUT);
  digitalWrite(PIN_RADIO_RXEN, LOW);
  digitalWrite(PIN_RADIO_TXEN, LOW);
}

void setRfIdle() {
  digitalWrite(PIN_RADIO_RXEN, LOW);
  digitalWrite(PIN_RADIO_TXEN, LOW);
}

void setRfReceive() {
  digitalWrite(PIN_RADIO_TXEN, LOW);
  digitalWrite(PIN_RADIO_RXEN, HIGH);
}

void setRfTransmit() {
  digitalWrite(PIN_RADIO_RXEN, LOW);
  digitalWrite(PIN_RADIO_TXEN, HIGH);
  delayMicroseconds(RF_SWITCH_SETTLE_US);
}

void IRAM_ATTR onRadioDio1() {
  radioIrqFlag = true;
}

bool startReceiveMode() {
  if (!radioReady) return false;

  setRfReceive();
  const int16_t state = radio.startReceive();
  if (state != RADIOLIB_ERR_NONE) {
    lastRadioError = state;
    radioReady = false;
    setRfIdle();
    return false;
  }

  return true;
}

// ============================================================
// 12. TX QUEUE / RELIABILITY
// ============================================================

struct TxEntry {
  bool used = false;
  uint8_t bytes[MAX_WIRE_PACKET] {};
  uint16_t length = 0;
  uint8_t priority = 5;  // 0 = ACK, 3 = DATA, 7 = HELLO
  uint32_t dueAtMs = 0;

  bool requiresAck = false;
  uint32_t ackFromNode = 0;
  uint32_t frameOrigin = 0;
  uint32_t frameBootCounter = 0;
  uint32_t frameCounter = 0;
  uint32_t messageId = 0;
  uint8_t attempts = 0;

  // Relay logical-message suppression is committed only after the next hop
  // actually ACKs the forwarded frame. Queue admission alone is not delivery.
  bool relayCommitOnAck = false;
  uint32_t relayOrigin = 0;
  uint32_t relayBootCounter = 0;
  uint32_t relayMessageCounter = 0;

  // Optional origin metadata for FieldTestService. It never changes radio wire.
  bool fieldTestOrigin = false;
  uint32_t fieldTestId = 0;
  uint32_t fieldTestSequence = 0;
};

TxEntry txQueue[MAX_TX_QUEUE];

constexpr size_t MAX_PENDING_RELAYS = 4;
constexpr uint32_t PENDING_RELAY_MAX_AGE_MS = 30000;
struct PendingRelayMessage {
  bool used = false;
  uint32_t queuedAtMs = 0;
  uint32_t origin = 0;
  uint32_t bootCounter = 0;
  uint32_t messageCounter = 0;
  uint32_t messageId = 0;
  uint32_t destination = 0;
  uint32_t previousHop = 0;
  uint32_t routeTag = 0;
  uint8_t hopLimit = 0;
  uint8_t wireLength = 0;
  uint8_t messageWire[MAX_MESSAGE_WIRE] {};
};
PendingRelayMessage pendingRelays[MAX_PENDING_RELAYS];

bool ackWaiting = false;
int ackQueueIndex = -1;
uint32_t ackStartedAtMs = 0;
uint32_t expectedAckFromNode = 0;
uint32_t expectedAckOrigin = 0;
uint32_t expectedAckBootCounter = 0;
uint32_t expectedAckFrameCounter = 0;

size_t countUsedTxEntries() {
  size_t count = 0;
  for (const auto& entry : txQueue) {
    if (entry.used) count++;
  }
  return count;
}

int findFreeTxIndex() {
  for (size_t i = 0; i < MAX_TX_QUEUE; ++i) {
    if (!txQueue[i].used) return static_cast<int>(i);
  }
  return -1;
}

uint32_t retryBackoffMs(uint8_t attempt) {
  const uint8_t bounded = (attempt > 3) ? 3 : attempt;
  const uint32_t base = 220UL << bounded;
  return base + randomBetween(40, 260);
}

bool enqueueWireFrame(
  const uint8_t* bytes,
  uint16_t length,
  uint8_t priority,
  uint32_t dueAtMs,
  bool requiresAck,
  uint32_t ackFromNode,
  const FrameView& view,
  int* queuedIndex = nullptr
) {
  if (bytes == nullptr || length == 0 || length > MAX_WIRE_PACKET) {
    return false;
  }

  // Reserve one slot for immediate ACK traffic.
  if (priority > 0 && countUsedTxEntries() >= MAX_TX_QUEUE - 1) {
    return false;
  }

  const int index = findFreeTxIndex();
  if (index < 0) return false;

  TxEntry& entry = txQueue[index];
  entry = TxEntry{};
  entry.used = true;
  memcpy(entry.bytes, bytes, length);
  entry.length = length;
  entry.priority = priority;
  entry.dueAtMs = dueAtMs;
  entry.requiresAck = requiresAck;
  entry.ackFromNode = ackFromNode;
  entry.frameOrigin = view.previousHop;
  entry.frameBootCounter = view.bootCounter;
  entry.frameCounter = view.frameCounter;
  entry.messageId = view.messageId;
  if (queuedIndex != nullptr) *queuedIndex = index;
  return true;
}

int selectTxEntry() {
  const uint32_t now = millis();
  int selected = -1;
  uint8_t bestPriority = 255;
  uint32_t bestDue = 0;

  for (size_t i = 0; i < MAX_TX_QUEUE; ++i) {
    const TxEntry& entry = txQueue[i];
    if (!entry.used || !timeReached(now, entry.dueAtMs)) continue;

    // During ACK wait, only ACK frames may interrupt.
    if (ackWaiting && entry.priority != 0) continue;

    if (selected < 0 ||
        entry.priority < bestPriority ||
        (entry.priority == bestPriority &&
         static_cast<int32_t>(entry.dueAtMs - bestDue) < 0)) {
      selected = static_cast<int>(i);
      bestPriority = entry.priority;
      bestDue = entry.dueAtMs;
    }
  }

  return selected;
}

void completeTxEntry(int index) {
  if (index < 0 || index >= static_cast<int>(MAX_TX_QUEUE)) return;
  txQueue[index].used = false;
}

bool relayLogicalInFlight(const MessageView& message) {
  for (const auto& entry : txQueue) {
    if (!entry.used || !entry.relayCommitOnAck) continue;
    if (entry.relayOrigin == message.origin &&
        entry.relayBootCounter == message.bootCounter &&
        entry.relayMessageCounter == message.messageCounter) return true;
  }
  for (const auto& pending : pendingRelays) {
    if (!pending.used) continue;
    if (pending.origin == message.origin &&
        pending.bootCounter == message.bootCounter &&
        pending.messageCounter == message.messageCounter) return true;
  }
  return false;
}

// ============================================================
// 13. STATISTICS / UI STATE
// ============================================================

uint32_t statRxValid = 0;
uint32_t statRxAuthFail = 0;
uint32_t statRxMalformed = 0;
uint32_t statRxDuplicate = 0;
uint32_t statRxTooOld = 0;
uint32_t statTxFrames = 0;
uint32_t statTxErrors = 0;
uint32_t statAckSuccess = 0;
uint32_t statAckTimeout = 0;
uint32_t statMessagesReceived = 0;
uint32_t statMessagesDelivered = 0;
uint32_t statMessagesForwarded = 0;
uint32_t statMessageAuthFail = 0;
uint32_t statMessageDuplicate = 0;
uint32_t statMessageTooOld = 0;
uint32_t statRadioRecoveries = 0;
uint32_t statRelayLogicalDuplicate = 0;

int16_t lastRxRssiDbm = -127;
float lastRxSnrDb = -20.0f;
char lastEventText[24] = "BOOT";

// ============================================================
// 13. GPS / POSITION / SOS RUNTIME
// ============================================================

constexpr size_t MAX_POSITION_CACHE = MAX_LAB_NODES;
constexpr uint32_t GPS_STALE_MS = 15000;
constexpr uint32_t GPS_LOCAL_EVENT_MIN_MS = 5000;
constexpr uint32_t GPS_MESH_MOVING_INTERVAL_MS = 15000;
constexpr uint32_t GPS_MESH_STATIONARY_INTERVAL_MS = 45000;
constexpr uint32_t GPS_MESH_MIN_INTERVAL_MS = 5000;
constexpr uint32_t GPS_MESH_JITTER_MS = 3000;
constexpr double GPS_SIGNIFICANT_MOVE_METERS = 25.0;
constexpr double GPS_MOVING_SPEED_MPS = 0.8;

struct PositionRecord {
  bool used = false;
  uint32_t nodeId = 0;
  uint16_t sequence = 0;
  uint8_t flags = 0;
  uint32_t gpsEpochSec = 0;
  int32_t latitudeE7 = 0;
  int32_t longitudeE7 = 0;
  int32_t altitudeCm = 0;
  uint16_t speedCms = 0;
  uint16_t hdopX100 = 0;
  uint8_t satellites = 0;
  uint16_t fixAgeMs = 0xFFFF;
  uint32_t receivedAtMs = 0;
};

PositionRecord positionCache[MAX_POSITION_CACHE];
uint16_t localPositionSequence = 0;
uint32_t lastGpsMeshPublishAtMs = 0;
uint32_t nextGpsMeshPublishAtMs = 0;
uint32_t lastGpsBleEventAtMs = 0;
int32_t lastPublishedLatitudeE7 = 0;
int32_t lastPublishedLongitudeE7 = 0;
bool gpsSerialReady = false;

struct ActiveSosRecord {
  bool active = false;
  uint32_t sosId = 0;
  uint32_t originNodeId = 0;
  uint8_t sosType = 0;
  uint8_t flags = 0;
  uint32_t raisedEpochSec = 0;
  int32_t latitudeE7 = 0;
  int32_t longitudeE7 = 0;
  uint32_t positionAgeMs = UINT32_MAX;
  uint8_t batteryPercent = 0xFF;
  uint32_t receivedAtMs = 0;
  bool acknowledged = false;
  uint32_t acknowledgedBy = 0;
};

ActiveSosRecord activeSos;

int64_t daysFromCivil(int year, unsigned month, unsigned day) {
  year -= month <= 2;
  const int era = (year >= 0 ? year : year - 399) / 400;
  const unsigned yoe = static_cast<unsigned>(year - era * 400);
  const unsigned doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return static_cast<int64_t>(era) * 146097 + static_cast<int64_t>(doe) - 719468;
}

uint32_t gpsUtcEpochSeconds() {
  if (!gps.date.isValid() || !gps.time.isValid()) return 0;
  const int year = gps.date.year();
  const unsigned month = gps.date.month();
  const unsigned day = gps.date.day();
  if (year < 2020 || month < 1 || month > 12 || day < 1 || day > 31) return 0;
  const int64_t days = daysFromCivil(year, month, day);
  const int64_t seconds = days * 86400LL + gps.time.hour() * 3600LL + gps.time.minute() * 60LL + gps.time.second();
  if (seconds <= 0 || seconds > UINT32_MAX) return 0;
  return static_cast<uint32_t>(seconds);
}

int findPositionRecord(uint32_t nodeId) {
  for (size_t i = 0; i < MAX_POSITION_CACHE; ++i) {
    if (positionCache[i].used && positionCache[i].nodeId == nodeId) return static_cast<int>(i);
  }
  return -1;
}

int positionSlotFor(uint32_t nodeId) {
  const int existing = findPositionRecord(nodeId);
  if (existing >= 0) return existing;
  for (size_t i = 0; i < MAX_POSITION_CACHE; ++i) {
    if (!positionCache[i].used) return static_cast<int>(i);
  }
  size_t oldest = 0;
  for (size_t i = 1; i < MAX_POSITION_CACHE; ++i) {
    if (positionCache[i].receivedAtMs < positionCache[oldest].receivedAtMs) oldest = i;
  }
  return static_cast<int>(oldest);
}

bool positionSequenceIsNewer(uint16_t candidate, uint16_t current) {
  return candidate != current && static_cast<uint16_t>(candidate - current) < 0x8000U;
}

void encodePositionPayload(const PositionRecord& pos, uint8_t out[POSITION_PAYLOAD_SIZE]) {
  memset(out, 0, POSITION_PAYLOAD_SIZE);
  out[0] = POSITION_PAYLOAD_VERSION;
  out[1] = pos.flags;
  writeU16(out, 2, pos.sequence);
  writeU32(out, 4, pos.gpsEpochSec);
  writeU32(out, 8, static_cast<uint32_t>(pos.latitudeE7));
  writeU32(out, 12, static_cast<uint32_t>(pos.longitudeE7));
  writeU32(out, 16, static_cast<uint32_t>(pos.altitudeCm));
  writeU16(out, 20, pos.speedCms);
  writeU16(out, 22, pos.hdopX100);
  out[24] = pos.satellites;
  writeU16(out, 25, pos.fixAgeMs);
}

bool decodePositionPayload(uint32_t nodeId, const uint8_t* data, size_t length, PositionRecord& out) {
  if (data == nullptr || length != POSITION_PAYLOAD_SIZE || data[0] != POSITION_PAYLOAD_VERSION) return false;
  PositionRecord decoded;
  decoded.used = true;
  decoded.nodeId = nodeId;
  decoded.flags = data[1];
  decoded.sequence = readU16(data, 2);
  decoded.gpsEpochSec = readU32(data, 4);
  decoded.latitudeE7 = static_cast<int32_t>(readU32(data, 8));
  decoded.longitudeE7 = static_cast<int32_t>(readU32(data, 12));
  decoded.altitudeCm = static_cast<int32_t>(readU32(data, 16));
  decoded.speedCms = readU16(data, 20);
  decoded.hdopX100 = readU16(data, 22);
  decoded.satellites = data[24];
  decoded.fixAgeMs = readU16(data, 25);
  decoded.receivedAtMs = millis();
  if (decoded.latitudeE7 < -900000000 || decoded.latitudeE7 > 900000000 ||
      decoded.longitudeE7 < -1800000000 || decoded.longitudeE7 > 1800000000) return false;
  out = decoded;
  return true;
}

void emitPositionBleEvent(const PositionRecord& pos) {
  uint8_t event[35] {};
  writeU32(event, 0, pos.nodeId);
  encodePositionPayload(pos, event + 4);
  const uint32_t age = millis() - pos.receivedAtMs;
  writeU32(event, 31, age);
  emitBleEvent(28 /* EVT_POSITION_UPDATED */, event, sizeof(event));
}

double approxDistanceMetersE7(int32_t lat1E7, int32_t lon1E7, int32_t lat2E7, int32_t lon2E7) {
  const double lat1 = static_cast<double>(lat1E7) / 1e7;
  const double lat2 = static_cast<double>(lat2E7) / 1e7;
  const double dLat = (lat2 - lat1) * 111320.0;
  const double avgLatRad = ((lat1 + lat2) * 0.5) * (M_PI / 180.0);
  const double dLon = (static_cast<double>(lon2E7 - lon1E7) / 1e7) * 111320.0 * cos(avgLatRad);
  return sqrt(dLat * dLat + dLon * dLon);
}

PositionRecord makeLocalPositionRecord() {
  PositionRecord pos;
  pos.used = true;
  pos.nodeId = localNodeId;
  pos.sequence = ++localPositionSequence;
  pos.receivedAtMs = millis();
  const bool fix = gps.location.isValid() && gps.location.age() <= GPS_STALE_MS;
  if (fix) {
    pos.flags |= POSITION_FLAG_FIX;
    pos.latitudeE7 = static_cast<int32_t>(llround(gps.location.lat() * 1e7));
    pos.longitudeE7 = static_cast<int32_t>(llround(gps.location.lng() * 1e7));
    const uint32_t age = gps.location.age();
    pos.fixAgeMs = static_cast<uint16_t>(age > 0xFFFF ? 0xFFFF : age);
  }
  if (gps.altitude.isValid()) {
    pos.flags |= POSITION_FLAG_ALTITUDE;
    pos.altitudeCm = static_cast<int32_t>(llround(gps.altitude.meters() * 100.0));
  }
  if (gps.speed.isValid()) {
    pos.flags |= POSITION_FLAG_SPEED;
    const double cms = gps.speed.mps() * 100.0;
    pos.speedCms = static_cast<uint16_t>(cms < 0 ? 0 : (cms > 65535 ? 65535 : llround(cms)));
  }
  if (gps.hdop.isValid()) {
    pos.flags |= POSITION_FLAG_HDOP;
    const double h = gps.hdop.hdop() * 100.0;
    pos.hdopX100 = static_cast<uint16_t>(h > 65535 ? 65535 : llround(h));
  }
  if (gps.satellites.isValid()) {
    pos.satellites = static_cast<uint8_t>(gps.satellites.value() > 255 ? 255 : gps.satellites.value());
  }
  pos.gpsEpochSec = gpsUtcEpochSeconds();
  if (pos.gpsEpochSec != 0) pos.flags |= POSITION_FLAG_UTC;
  return pos;
}

void updatePositionCache(const PositionRecord& incoming, bool emitEvent = true) {
  const int slot = positionSlotFor(incoming.nodeId);
  if (slot < 0) return;
  PositionRecord& current = positionCache[slot];
  if (current.used && current.nodeId == incoming.nodeId &&
      incoming.nodeId != localNodeId && !positionSequenceIsNewer(incoming.sequence, current.sequence)) return;
  current = incoming;
  if (emitEvent) emitPositionBleEvent(current);
}

void initializeGps() {
  gpsSerial.begin(GPS_BAUD, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
  gpsSerialReady = true;
  nextGpsMeshPublishAtMs = millis() + 2500 + randomBetween(0, GPS_MESH_JITTER_MS);
  Serial.printf("GPS: UART1 %lu baud RX=%d TX=%d\r\n", static_cast<unsigned long>(GPS_BAUD), PIN_GPS_RX, PIN_GPS_TX);
}


// ============================================================
// 13A. FIELD TEST STATE (non-blocking, application-level diagnostics)
// ============================================================

enum BleEventType : uint8_t {
  EVT_NODE_DISCOVERED = 1,
  EVT_NODE_STALE = 2,
  EVT_MESSAGE_QUEUED = 3,
  EVT_HOP_ACK = 4,
  EVT_RETRY = 5,
  EVT_MESSAGE_LOCAL_RECEIVED = 6,
  EVT_ROUTE_CHANGED = 7,
  EVT_TEST_STARTED = 8,
  EVT_TEST_PACKET_SENT = 9,
  EVT_TEST_PONG_RECEIVED = 10,
  EVT_TEST_PACKET_TIMEOUT = 11,
  EVT_TEST_PROGRESS = 12,
  EVT_TEST_FINISHED = 13,
  EVT_RADIO_RECOVERY = 14,
  EVT_BLE_STATE = 15,
  EVT_ERROR = 16,
  EVT_NO_RETURN_ROUTE = 17,
  EVT_UI_CHANGED = 18,
  EVT_ROUTE_DISCOVERY_STARTED = 19,
  EVT_ROUTE_DISCOVERY_RETRY = 20,
  EVT_ROUTE_READY = 21,
  EVT_G2_READY = 22,
  EVT_G2_UNAVAILABLE = 23,
  EVT_ROUTE_PROMOTED = 24,
  EVT_ROUTE_LOST = 25,
  EVT_MANIFEST_CHANGED = 26,
  EVT_KNOWN_NODE_ADDED = 27,
  EVT_POSITION_UPDATED = 28,
  EVT_SOS_RAISED = 29,
  EVT_SOS_ACKNOWLEDGED = 30,
  EVT_COMMAND_NOTICE_RECEIVED = 31,
  EVT_OPERATIONAL_HEALTH_CHANGED = 32
};

enum class FieldTestState : uint8_t {
  Idle = 0,
  Running = 1,
  Finished = 2,
  Cancelled = 3,
  Error = 4
};

enum class FieldTestMode : uint8_t {
  Routed = 0,
  DirectOnly = 1
};

struct DiagPendingProbe {
  bool used = false;
  uint32_t sequence = 0;
  uint32_t token = 0;
  uint32_t queuedAtMs = 0;
  uint32_t actualTxStartedAtMs = 0;
  uint32_t actualTxCompletedAtMs = 0;
  uint32_t pongReceivedAtMs = 0;
  uint32_t deadlineAtMs = 0;
};

struct FieldTestContext {
  FieldTestState state = FieldTestState::Idle;
  FieldTestMode mode = FieldTestMode::Routed;
  uint32_t testId = 0;
  uint32_t targetNodeId = 0;
  uint16_t requestedPackets = 0;
  uint32_t intervalMs = 1000;
  uint8_t payloadSize = 16;
  uint32_t startedAtMs = 0;
  uint32_t finishedAtMs = 0;
  uint32_t nextSendAtMs = 0;
  uint32_t currentSequence = 0;
  uint32_t sent = 0;
  uint32_t firstHopAcked = 0;
  uint32_t firstHopFailed = 0;
  uint32_t firstHopRetries = 0;
  uint32_t endToEndReplies = 0;
  uint32_t endToEndTimeouts = 0;
  uint32_t lastNextHop = 0;
  RouteSource lastRouteSource = RouteSource::None;
  uint64_t rttSumMs = 0;
  uint32_t rttMinMs = UINT32_MAX;
  uint32_t rttMaxMs = 0;
  int64_t localRssiSum = 0;
  int64_t localSnrTenthsSum = 0;
  uint32_t localLinkSamples = 0;
  uint32_t lastProgressEventAtMs = 0;
};

FieldTestContext fieldTest;
DiagPendingProbe diagPending[MAX_DIAG_PENDING];

// Implemented in the control-plane section. These hooks never block.
void onFieldTestTxStarted(const TxEntry& entry, uint32_t startedAtMs);
void onFieldTestTxCompleted(const TxEntry& entry, uint32_t completedAtMs);
void onFieldTestHopAck(const TxEntry& entry, uint32_t neighborId);
void onFieldTestHopTimeout(const TxEntry& entry, bool finalFailure);
void handleLocalDiagPing(const MessageView& message, const uint8_t* payload);
void handleLocalDiagPong(const MessageView& message, const uint8_t* payload);
void emitBleEvent(uint8_t eventType, const uint8_t* payload, uint16_t length);

void setLastEvent(const char* text) {
  if (text == nullptr) return;
  strncpy(lastEventText, text, sizeof(lastEventText) - 1);
  lastEventText[sizeof(lastEventText) - 1] = '\0';
  uiMarkDirty();
}

// ============================================================
// 14. RADIO INIT / RECOVERY
// ============================================================

bool initializeRadio() {
  radioReady = false;
  radioTransmitting = false;
  radioIrqFlag = false;
  activeTxIndex = -1;

  initializeRfSwitchPins();
  setRfIdle();

  SPI.begin(
    PIN_RADIO_SCK,
    PIN_RADIO_MISO,
    PIN_RADIO_MOSI,
    PIN_RADIO_NSS
  );

  int16_t state = radio.begin(
    RADIO_FREQUENCY_MHZ,
    RADIO_BANDWIDTH_KHZ,
    RADIO_SPREADING_FACTOR,
    RADIO_CODING_RATE,
    RADIO_SYNC_WORD,
    RADIO_DRIVER_POWER_DBM,
    RADIO_PREAMBLE_LENGTH,
    RADIO_TCXO_VOLTAGE,
    RADIO_USE_LDO
  );

  if (state != RADIOLIB_ERR_NONE) {
    lastRadioError = state;
    return false;
  }

  // E22 RXEN/TXEN are controlled directly by ESP32 GPIO in this wiring.
  state = radio.setDio2AsRfSwitch(false);
  if (state != RADIOLIB_ERR_NONE) {
    lastRadioError = state;
    return false;
  }

  state = radio.explicitHeader();
  if (state != RADIOLIB_ERR_NONE) {
    lastRadioError = state;
    return false;
  }

  state = radio.setCRC(2);
  if (state != RADIOLIB_ERR_NONE) {
    lastRadioError = state;
    return false;
  }

  state = radio.autoLDRO();
  if (state != RADIOLIB_ERR_NONE) {
    lastRadioError = state;
    return false;
  }

  // Keep boosted gain explicit for this radio-qualification baseline.
  state = radio.setRxBoostedGainMode(true);
  if (state != RADIOLIB_ERR_NONE) {
    lastRadioError = state;
    return false;
  }

  radio.setDio1Action(onRadioDio1);
  radioReady = true;
  lastRadioError = RADIOLIB_ERR_NONE;
  return startReceiveMode();
}

void recoverRadio(int16_t errorCode) {
  lastRadioError = errorCode;
  statRadioRecoveries++;
  uint8_t recoveryEvent[6];
  writeU16(recoveryEvent, 0, static_cast<uint16_t>(errorCode));
  writeU32(recoveryEvent, 2, statRadioRecoveries);
  emitBleEvent(EVT_RADIO_RECOVERY, recoveryEvent, sizeof(recoveryEvent));

  if (radioTransmitting) {
    radio.finishTransmit();
  }

  if (activeTxIndex >= 0 &&
      activeTxIndex < static_cast<int>(MAX_TX_QUEUE) &&
      txQueue[activeTxIndex].used) {
    txQueue[activeTxIndex].dueAtMs = millis() + 500;
  }

  radioTransmitting = false;
  activeTxIndex = -1;
  radioIrqFlag = false;
  radioReady = false;
  setRfIdle();
}

void processRadioRecovery() {
  if (!cryptoReady) return;
  if (radioReady) return;
  if (millis() - lastRadioRetryAtMs < RADIO_RETRY_MS) return;

  lastRadioRetryAtMs = millis();
  initializeRadio();
}

// ============================================================
// 15. FRAME / MESSAGE BUILDERS
// ============================================================

bool queueHello() {
  // HELLO v8: sequence + queue/capability + manifest identity summary.
  // The manifest digest is not an authentication primitive; the outer GCM
  // authenticates the frame. It only detects configuration disagreement.
  uint8_t payload[16] {};
  writeU32(payload, 0, helloSequence);
  payload[4] = 0x08;  // VANGUARD dynamic-routing capability marker
  payload[5] = static_cast<uint8_t>(countUsedTxEntries());
  writeU32(payload, 6, networkManifest.valid ? networkManifest.networkEpoch : 0);
  writeU32(payload, 10, networkManifest.valid ? networkManifest.digest : 0);
  payload[14] = networkManifest.valid
    ? networkManifest.slotFor(localNodeId)
    : VanguardManifest::INVALID_SLOT;
  payload[15] = vanguardRouter.exactDiversityEnabled() ? 0x01 : 0x00;

  uint8_t wire[MAX_WIRE_PACKET];
  FrameView view;
  const uint16_t length = buildFrame(
    FrameType::Hello,
    BROADCAST_ID,
    helloSequence,
    payload,
    sizeof(payload),
    0,
    wire,
    &view
  );

  if (length == 0) return false;

  const bool queued = enqueueWireFrame(
    wire,
    length,
    7,
    millis() + randomBetween(0, 140),
    false,
    0,
    view
  );

  if (queued) helloSequence++;
  return queued;
}

bool queueAck(const FrameView& received) {
  if (received.previousHop == 0 ||
      received.previousHop == BROADCAST_ID ||
      received.previousHop == localNodeId) {
    return false;
  }

  // ACK binds to the exact authenticated hop frame, not merely messageId.
  uint8_t payload[HOP_ACK_PAYLOAD_SIZE];
  writeU32(payload, 0, received.previousHop);
  writeU32(payload, 4, received.bootCounter);
  writeU32(payload, 8, received.frameCounter);

  uint8_t wire[MAX_WIRE_PACKET];
  FrameView view;
  const uint16_t length = buildFrame(
    FrameType::Ack,
    received.previousHop,
    received.messageId,
    payload,
    sizeof(payload),
    0,
    wire,
    &view
  );

  if (length == 0) return false;

  return enqueueWireFrame(
    wire,
    length,
    0,
    millis() + randomBetween(20, 60),
    false,
    0,
    view
  );
}


// VANGUARD control traffic is bounded by estimated LoRa airtime, not packet
// count. A protected reserve is available to route-error traffic so ordinary
// discovery cannot starve failure propagation.
constexpr uint32_t VANGUARD_CONTROL_BUCKET_CAPACITY_US = 1500000UL;
constexpr uint32_t VANGUARD_CONTROL_REPAIR_RESERVE_US = 350000UL;
constexpr uint32_t VANGUARD_CONTROL_REFILL_US_PER_MS = 150UL; // ~15% mean airtime
const VanguardAirtime::RadioProfile VANGUARD_RADIO_PROFILE {
  static_cast<uint32_t>(RADIO_BANDWIDTH_KHZ * 1000.0f),
  RADIO_SPREADING_FACTOR,
  RADIO_CODING_RATE,
  RADIO_PREAMBLE_LENGTH,
  true,
  true
};
VanguardAirtime::Bucket vanguardControlBudget(
  VANGUARD_CONTROL_BUCKET_CAPACITY_US,
  VANGUARD_CONTROL_REPAIR_RESERVE_US,
  VANGUARD_CONTROL_REFILL_US_PER_MS);

void configureVanguardTimingFromRadioProfile() {
  const auto recommendation = VanguardAirtime::deriveRoutingTiming(
    HEADER_SIZE + VanguardProto::RREQ_LEN + AES_GCM_TAG_SIZE,
    HEADER_SIZE + VanguardProto::RREP_LEN + AES_GCM_TAG_SIZE,
    HEADER_SIZE + HOP_ACK_PAYLOAD_SIZE + AES_GCM_TAG_SIZE,
    VanguardRuntime::DEFAULT_DISCOVERY_HOPS,
    VANGUARD_RADIO_PROFILE,
    ACK_TIMEOUT_FLOOR_MS);
  if (!recommendation.valid) return;

  VanguardRuntime::TimingConfig timing;
  timing.discoveryTimeoutMs = recommendation.discoveryTimeoutMs;
  timing.rreqSettleMs = recommendation.rreqSettleMs;
  timing.retryExtraStepMs = recommendation.retryExtraStepMs;
  timing.refreshMinIntervalMs = recommendation.refreshMinIntervalMs;
  vanguardRuntime.configureTiming(timing);
  ackTimeoutMs = recommendation.ackTimeoutMs;
}


constexpr size_t MAX_DEFERRED_VANGUARD_CONTROL = 8;
constexpr uint32_t DEFERRED_VANGUARD_MAX_AGE_MS = 12000UL;
struct DeferredVanguardControl {
  bool used = false;
  VanguardRuntime::TxControl control {};
  uint32_t firstQueuedAtMs = 0;
  uint32_t retryAtMs = 0;
  uint8_t attempts = 0;
};
DeferredVanguardControl deferredVanguardControl[MAX_DEFERRED_VANGUARD_CONTROL];
uint32_t statVanguardDeferredDrops = 0;
uint32_t statVanguardDeferredQueued = 0;

bool queueVanguardControlTx(const VanguardRuntime::TxControl& control) {
  if (!control.valid || control.length == 0 ||
      control.length > MAX_HOP_PAYLOAD || control.hopLimit == 0 ||
      control.nextHop == 0 || control.nextHop == localNodeId) {
    return false;
  }

  const VanguardProto::ControlType controlType =
    VanguardProto::typeOf(control.payload, control.length);
  if (controlType == VanguardProto::ControlType::Invalid) return false;
  const bool repairCritical = controlType == VanguardProto::ControlType::RouteError;
  const uint32_t now = millis();

  // The encrypted hop frame has deterministic length. Check airtime budget
  // BEFORE allocating a nonce/frame counter, so deferred control traffic does
  // not burn persistent counter space merely while waiting for tokens.
  const size_t predictedWireLength = HEADER_SIZE + control.length + AES_GCM_TAG_SIZE;
  const uint32_t chargedUs = VanguardAirtime::estimateLoRaAirtimeUs(
    predictedWireLength, VANGUARD_RADIO_PROFILE);
  if (!vanguardControlBudget.consume(chargedUs, repairCritical, now)) return false;

  uint8_t wire[MAX_WIRE_PACKET] {};
  FrameView view;
  uint32_t controlMessageId = allocateMessageId();
  if (controlMessageId == 0) controlMessageId = 1;
  const uint16_t wireLength = buildFrame(
    FrameType::Control,
    control.nextHop,
    controlMessageId,
    control.payload,
    static_cast<uint8_t>(control.length),
    control.hopLimit,
    wire,
    &view);
  if (wireLength == 0) {
    vanguardControlBudget.refund(chargedUs);
    return false;
  }

  const bool unicastAck = control.requiresAck && control.nextHop != BROADCAST_ID;
  const bool queued = enqueueWireFrame(
    wire,
    wireLength,
    control.priority,
    now + randomBetween(10, 65),
    unicastAck,
    unicastAck ? control.nextHop : 0,
    view);
  if (!queued) vanguardControlBudget.refund(chargedUs);
  return queued;
}

bool sameVanguardControl(
  const VanguardRuntime::TxControl& a,
  const VanguardRuntime::TxControl& b
) {
  return a.valid == b.valid && a.nextHop == b.nextHop &&
    a.requiresAck == b.requiresAck && a.hopLimit == b.hopLimit &&
    a.priority == b.priority && a.length == b.length &&
    a.length <= sizeof(a.payload) &&
    memcmp(a.payload, b.payload, a.length) == 0;
}

bool deferVanguardControl(const VanguardRuntime::TxControl& control) {
  if (!control.valid || control.length == 0) return false;
  const uint32_t now = millis();
  for (auto& item : deferredVanguardControl) {
    if (item.used && sameVanguardControl(item.control, control)) return true;
  }
  for (auto& item : deferredVanguardControl) {
    if (item.used) continue;
    item = DeferredVanguardControl{};
    item.used = true;
    item.control = control;
    item.firstQueuedAtMs = now;
    item.retryAtMs = now + 80;
    statVanguardDeferredQueued++;
    return true;
  }
  statVanguardDeferredDrops++;
  return false;
}

uint8_t countDeferredVanguardControls() {
  uint8_t count = 0;
  for (const auto& item : deferredVanguardControl) if (item.used) count++;
  return count;
}

void processDeferredVanguardControls() {
  const uint32_t now = millis();
  for (auto& item : deferredVanguardControl) {
    if (!item.used || !timeReached(now, item.retryAtMs)) continue;
    if (now - item.firstQueuedAtMs > DEFERRED_VANGUARD_MAX_AGE_MS ||
        item.attempts >= 12) {
      item = DeferredVanguardControl{};
      statVanguardDeferredDrops++;
      continue;
    }
    if (queueVanguardControlTx(item.control)) {
      item = DeferredVanguardControl{};
      continue;
    }
    item.attempts++;
    const uint32_t backoff = 100UL +
      static_cast<uint32_t>(item.attempts) * 90UL + randomBetween(0, 120);
    item.retryAtMs = now + (backoff > 1200UL ? 1200UL : backoff);
  }
}

void emitVanguardRuntimeEvent(const VanguardRuntime::Event& event) {
  if (event.type == VanguardRuntime::EventType::None) return;
  uint8_t payload[17] {};
  payload[0] = static_cast<uint8_t>(event.type);
  writeU32(payload, 1, event.destination);
  writeU32(payload, 5, event.nextHop);
  writeU32(payload, 9, event.requestId);
  writeU32(payload, 13, event.routeVersion);

  uint8_t bleEvent = EVT_ROUTE_CHANGED;
  switch (event.type) {
    case VanguardRuntime::EventType::DiscoveryStarted:
      bleEvent = EVT_ROUTE_DISCOVERY_STARTED; break;
    case VanguardRuntime::EventType::DiscoveryRetry:
      bleEvent = EVT_ROUTE_DISCOVERY_RETRY; break;
    case VanguardRuntime::EventType::RouteReady:
      bleEvent = EVT_ROUTE_READY; break;
    case VanguardRuntime::EventType::G2Ready:
      bleEvent = EVT_G2_READY; break;
    case VanguardRuntime::EventType::G2Unavailable:
      bleEvent = EVT_G2_UNAVAILABLE; break;
    case VanguardRuntime::EventType::RoutePromotedG2:
    case VanguardRuntime::EventType::RoutePromotedAlternate:
      bleEvent = EVT_ROUTE_PROMOTED; break;
    case VanguardRuntime::EventType::RouteLost:
    case VanguardRuntime::EventType::DiscoveryFailed:
      bleEvent = EVT_ROUTE_LOST; break;
    default:
      bleEvent = EVT_ROUTE_CHANGED; break;
  }
  emitBleEvent(bleEvent, payload, sizeof(payload));

  Serial.printf(
    "[VANGUARD EVT] type=%u dest=%08lX next=%08lX req=%08lX ver=%lu\r\n",
    static_cast<unsigned>(event.type),
    static_cast<unsigned long>(event.destination),
    static_cast<unsigned long>(event.nextHop),
    static_cast<unsigned long>(event.requestId),
    static_cast<unsigned long>(event.routeVersion));
}

void dispatchVanguardOutputs(
  VanguardRuntime::TxControl* outputs,
  size_t outputCount,
  VanguardRuntime::Event* events,
  size_t eventCapacity
) {
  for (size_t i = 0; i < outputCount; ++i) {
    if (outputs[i].valid && !queueVanguardControlTx(outputs[i])) {
      if (!deferVanguardControl(outputs[i])) {
        Serial.println("[VANGUARD] control deferred queue full");
      }
    }
  }
  for (size_t i = 0; i < eventCapacity; ++i) {
    if (events[i].type == VanguardRuntime::EventType::None) continue;
    emitVanguardRuntimeEvent(events[i]);
  }
}

bool requestVanguardRoute(uint32_t destination, bool forceFresh) {
  if (destination == 0 || destination == BROADCAST_ID || destination == localNodeId) {
    return false;
  }
  if (vanguardRuntime.discoveryFor(destination, false) != nullptr) return true;

  VanguardRuntime::TxControl output;
  VanguardRuntime::Event event;
  if (!vanguardRuntime.beginDiscovery(
        destination,
        false,
        0,
        millis(),
        networkManifest,
        output,
        &event,
        forceFresh)) {
    return false;
  }

  const bool accepted = queueVanguardControlTx(output) || deferVanguardControl(output);
  if (accepted) emitVanguardRuntimeEvent(event);
  return accepted;
}

bool requestVanguardG2(uint32_t destination) {
  if (!networkManifest.valid || destination == 0 ||
      destination == BROADCAST_ID || destination == localNodeId ||
      vanguardRouter.hasExactG2(destination) ||
      vanguardRuntime.discoveryFor(destination, true) != nullptr) {
    return false;
  }

  const auto* route = vanguardRouter.find(destination);
  if (route == nullptr || !route->primary.valid || !route->primary.exactMask) {
    return false;
  }

  VanguardRuntime::TxControl output;
  VanguardRuntime::Event event;
  if (!vanguardRuntime.beginDiscovery(
        destination,
        true,
        route->primary.internalPathMask,
        millis(),
        networkManifest,
        output,
        &event,
        false,
        route->primary.nextHop)) {
    return false;
  }

  const bool accepted = queueVanguardControlTx(output) || deferVanguardControl(output);
  if (accepted) emitVanguardRuntimeEvent(event);
  return accepted;
}

void processVanguardRuntime() {
  vanguardRouter.expire(millis());
  VanguardRuntime::TxControl outputs[4] {};
  VanguardRuntime::Event events[4] {};
  const size_t count = vanguardRuntime.tick(
    millis(), networkManifest, outputs, 4, events, 4);
  dispatchVanguardOutputs(outputs, count, events, 4);
}

void handleVanguardControl(const FrameView& view, const uint8_t* payload) {
  if (payload == nullptr || view.payloadLength < 2) {
    statRxMalformed++;
    return;
  }

  if (view.nextHop == localNodeId) {
    // Unicast RREP/RERR gets the same authenticated hop receipt semantics as DATA.
    (void)queueAck(view);
  }

  VanguardRuntime::TxControl outputs[6] {};
  VanguardRuntime::Event events[6] {};
  const auto linkMetric = estimateNeighborLinkMetric(view.previousHop);
  const size_t count = vanguardRuntime.onControl(
    view.previousHop,
    view.hopLimit,
    payload,
    view.payloadLength,
    linkMetric,
    millis(),
    networkManifest,
    vanguardRouter,
    outputs,
    6,
    events,
    6);
  dispatchVanguardOutputs(outputs, count, events, 6);
}

void notifyVanguardHopFailure(uint32_t failedNeighbor) {
  VanguardRuntime::TxControl outputs[8] {};
  VanguardRuntime::Event events[8] {};
  const size_t count = vanguardRuntime.onLocalHopFailure(
    failedNeighbor,
    millis(),
    networkManifest,
    vanguardRouter,
    outputs,
    8,
    events,
    8);
  dispatchVanguardOutputs(outputs, count, events, 8);
}

bool queueForwardedData(
  const FrameView& receivedHop,
  const MessageView& message,
  const uint8_t* authenticatedMessageWire,
  size_t authenticatedMessageLength
) {
  if (authenticatedMessageWire == nullptr ||
      authenticatedMessageLength == 0 ||
      authenticatedMessageLength > MAX_MESSAGE_WIRE) {
    return false;
  }

  if (receivedHop.hopLimit <= 1) {
    Serial.printf(
      "[TTL DROP] destination=%08lX msg=%08lX ttl=0\r\n",
      static_cast<unsigned long>(message.destination),
      static_cast<unsigned long>(message.messageId)
    );
    return false;
  }

  uint32_t nextHop = 0;
  const uint32_t routeTag = receivedHop.routeTag;
  RouteSource routeSource = RouteSource::None;

  if (routeTag != 0) {
    const bool labelOk = vanguardRuntime.resolveFlowLabel(
      message.origin,
      message.bootCounter,
      message.destination,
      routeTag,
      receivedHop.previousHop,
      millis(),
      nextHop);
    if (!labelOk || !isFreshDirectNeighbor(nextHop)) {
      VanguardRuntime::TxControl errorTx;
      if (vanguardRuntime.makePathErrorToUpstream(
            message.origin,
            message.bootCounter,
            message.destination,
            routeTag,
            receivedHop.previousHop,
            millis(),
            networkManifest,
            vanguardRouter,
            errorTx)) {
        if (!queueVanguardControlTx(errorTx)) {
          (void)deferVanguardControl(errorTx);
        }
      }
      Serial.printf(
        "[PINNED PATH LOST] destination=%08lX msg=%08lX tag=%08lX\r\n",
        static_cast<unsigned long>(message.destination),
        static_cast<unsigned long>(message.messageId),
        static_cast<unsigned long>(routeTag));
      return false;
    }
    routeSource = RouteSource::VanguardDynamic;
  } else if (!resolveRelayNextHop(message.destination, nextHop, routeSource)) {
    Serial.printf(
      "[NO ROUTE] destination=%08lX msg=%08lX\r\n",
      static_cast<unsigned long>(message.destination),
      static_cast<unsigned long>(message.messageId)
    );
    return false;
  }

  const uint8_t forwardedHopLimit = receivedHop.hopLimit - 1;

  uint8_t wire[MAX_WIRE_PACKET];
  FrameView view;
  const uint16_t wireLength = buildFrame(
    FrameType::Data,
    nextHop,
    message.messageId,
    authenticatedMessageWire,
    static_cast<uint8_t>(authenticatedMessageLength),
    forwardedHopLimit,
    wire,
    &view,
    routeTag
  );

  if (wireLength == 0) return false;

  // Preserve service priority across relay hops. Lower numeric value means
  // higher TX priority in the bounded queue.
  const uint8_t txPriority =
    message.type == MessageType::SosAck ? 1 :
    message.type == MessageType::CommandNotice ? 2 :
    (message.type == MessageType::DiagPing || message.type == MessageType::DiagPong) ? 2 :
    3;

  int queuedIndex = -1;
  if (!enqueueWireFrame(
        wire,
        wireLength,
        txPriority,
        millis() + randomBetween(20, 100),
        true,
        nextHop,
        view,
        &queuedIndex)) {
    Serial.printf(
      "[FORWARD FAILED] destination=%08lX nextHop=%08lX reason=TX_QUEUE\r\n",
      static_cast<unsigned long>(message.destination),
      static_cast<unsigned long>(nextHop)
    );
    return false;
  }

  if (queuedIndex >= 0 && queuedIndex < static_cast<int>(MAX_TX_QUEUE)) {
    TxEntry& queued = txQueue[queuedIndex];
    queued.relayCommitOnAck = true;
    queued.relayOrigin = message.origin;
    queued.relayBootCounter = message.bootCounter;
    queued.relayMessageCounter = message.messageCounter;
  }

  Serial.printf(
    "[ROUTE] destination=%08lX nextHop=%08lX source=%s\r\n",
    static_cast<unsigned long>(message.destination),
    static_cast<unsigned long>(nextHop),
    routeSourceText(routeSource)
  );
  Serial.printf(
    "[FORWARD] destination=%08lX nextHop=%08lX ttl=%u msg=%08lX tag=%08lX\r\n",
    static_cast<unsigned long>(message.destination),
    static_cast<unsigned long>(nextHop),
    static_cast<unsigned>(forwardedHopLimit),
    static_cast<unsigned long>(message.messageId),
    static_cast<unsigned long>(routeTag)
  );
  Serial.printf(
    "[TX DATA] previousHop=%08lX nextHop=%08lX destination=%08lX frame=%08lX ttl=%u\r\n",
    static_cast<unsigned long>(view.previousHop),
    static_cast<unsigned long>(view.nextHop),
    static_cast<unsigned long>(message.destination),
    static_cast<unsigned long>(view.frameCounter),
    static_cast<unsigned>(view.hopLimit)
  );
  return true;
}

bool storePendingRelay(
  const FrameView& receivedHop,
  const MessageView& message,
  const uint8_t* authenticatedMessageWire,
  size_t authenticatedMessageLength
) {
  if (receivedHop.hopLimit <= 1 || authenticatedMessageWire == nullptr ||
      authenticatedMessageLength == 0 ||
      authenticatedMessageLength > MAX_MESSAGE_WIRE) {
    return false;
  }
  for (const auto& pending : pendingRelays) {
    if (pending.used && pending.origin == message.origin &&
        pending.bootCounter == message.bootCounter &&
        pending.messageCounter == message.messageCounter) return true;
  }

  int freeIndex = -1;
  uint32_t oldestAge = 0;
  int oldestIndex = -1;
  const uint32_t now = millis();
  for (size_t i = 0; i < MAX_PENDING_RELAYS; ++i) {
    if (!pendingRelays[i].used) {
      freeIndex = static_cast<int>(i);
      break;
    }
    const uint32_t age = now - pendingRelays[i].queuedAtMs;
    if (age > oldestAge) {
      oldestAge = age;
      oldestIndex = static_cast<int>(i);
    }
  }
  if (freeIndex < 0 && oldestIndex >= 0 &&
      oldestAge > PENDING_RELAY_MAX_AGE_MS) {
    freeIndex = oldestIndex;
  }
  if (freeIndex < 0) return false;

  PendingRelayMessage& pending = pendingRelays[freeIndex];
  pending = PendingRelayMessage{};
  pending.used = true;
  pending.queuedAtMs = now;
  pending.origin = message.origin;
  pending.bootCounter = message.bootCounter;
  pending.messageCounter = message.messageCounter;
  pending.messageId = message.messageId;
  pending.destination = message.destination;
  pending.previousHop = receivedHop.previousHop;
  pending.routeTag = receivedHop.routeTag;
  pending.hopLimit = receivedHop.hopLimit;
  pending.wireLength = static_cast<uint8_t>(authenticatedMessageLength);
  memcpy(pending.messageWire, authenticatedMessageWire, authenticatedMessageLength);
  return true;
}

void processPendingRelays() {
  const uint32_t now = millis();
  for (auto& pending : pendingRelays) {
    if (!pending.used) continue;
    if (now - pending.queuedAtMs > PENDING_RELAY_MAX_AGE_MS) {
      pending = PendingRelayMessage{};
      continue;
    }

    if (pending.routeTag != 0) {
      uint32_t pinnedNextHop = 0;
      if (!vanguardRuntime.resolveFlowLabel(
            pending.origin,
            pending.bootCounter,
            pending.destination,
            pending.routeTag,
            pending.previousHop,
            now,
            pinnedNextHop) ||
          !isFreshDirectNeighbor(pinnedNextHop)) {
        VanguardRuntime::TxControl errorTx;
        if (vanguardRuntime.makePathErrorToUpstream(
              pending.origin,
              pending.bootCounter,
              pending.destination,
              pending.routeTag,
              pending.previousHop,
              now,
              networkManifest,
              vanguardRouter,
              errorTx)) {
          if (!queueVanguardControlTx(errorTx)) {
            (void)deferVanguardControl(errorTx);
          }
        }
        pending = PendingRelayMessage{};
        continue;
      }
    } else {
      uint32_t nextHop = 0;
      RouteSource source = RouteSource::None;
      if (!resolveRelayNextHop(pending.destination, nextHop, source)) {
        (void)requestVanguardRoute(pending.destination, false);
        continue;
      }
    }

    MessageView message;
    message.origin = pending.origin;
    message.destination = pending.destination;
    message.bootCounter = pending.bootCounter;
    message.messageCounter = pending.messageCounter;
    message.messageId = pending.messageId;

    FrameView hop;
    hop.previousHop = pending.previousHop;
    hop.hopLimit = pending.hopLimit;
    hop.routeTag = pending.routeTag;

    if (queueForwardedData(
          hop,
          message,
          pending.messageWire,
          pending.wireLength)) {
      statMessagesForwarded++;
      pending = PendingRelayMessage{};
      setLastEvent("STORE-FWD");
    }
  }
}

enum class QueueMessageResult : uint8_t {
  Ok,
  InvalidArgument,
  NoRoute,
  RouteDiscoveryStarted,
  TxQueueFull,
  CryptoUnavailable,
  RadioUnavailable
};

struct QueuedMessageMeta {
  uint32_t messageId = 0;
  uint32_t nextHop = 0;
  uint32_t routeTag = 0;
  RouteSource routeSource = RouteSource::None;
  int txQueueIndex = -1;
};

QueueMessageResult queueApplicationMessage(
  MessageType messageType,
  uint32_t destination,
  const uint8_t* data,
  size_t length,
  bool broadcast,
  RoutePolicy routePolicy,
  QueuedMessageMeta* meta = nullptr,
  uint8_t txPriority = 3,
  uint8_t broadcastHopLimit = 1
) {
  if (!cryptoReady) return QueueMessageResult::CryptoUnavailable;
  if (!radioReady) return QueueMessageResult::RadioUnavailable;
  if (data == nullptr || length == 0 || length > MAX_APP_PAYLOAD) {
    return QueueMessageResult::InvalidArgument;
  }

  if (!broadcast &&
      (destination == 0 || destination == BROADCAST_ID ||
       destination == localNodeId)) {
    return QueueMessageResult::InvalidArgument;
  }

  uint32_t nextHop = BROADCAST_ID;
  uint32_t routeTag = 0;
  RouteSource routeSource = RouteSource::None;
  if (!broadcast &&
      !resolveNextHop(destination, nextHop, routeSource, routePolicy, &routeTag)) {
    Serial.printf(
      "[NO ROUTE] destination=%08lX policy=%s\r\n",
      static_cast<unsigned long>(destination),
      routePolicy == RoutePolicy::DirectOnly ? "DIRECT_ONLY" : "ROUTED"
    );
    if (routePolicy == RoutePolicy::Routed && requestVanguardRoute(destination, false)) {
      return QueueMessageResult::RouteDiscoveryStarted;
    }
    return QueueMessageResult::NoRoute;
  }

  const uint32_t messageId = allocateMessageId();
  uint8_t messageWire[MAX_MESSAGE_WIRE];
  MessageView messageView;
  const uint16_t messageLength = buildMessageEnvelope(
    messageType,
    broadcast ? BROADCAST_ID : destination,
    messageId,
    data,
    static_cast<uint8_t>(length),
    messageWire,
    &messageView
  );
  if (messageLength == 0) return QueueMessageResult::CryptoUnavailable;

  uint8_t wire[MAX_WIRE_PACKET];
  FrameView view;
  const uint8_t hopLimit = broadcast ? broadcastHopLimit : DEFAULT_HOP_LIMIT;
  const uint16_t wireLength = buildFrame(
    FrameType::Data,
    nextHop,
    messageId,
    messageWire,
    static_cast<uint8_t>(messageLength),
    hopLimit,
    wire,
    &view,
    broadcast ? 0 : routeTag
  );
  if (wireLength == 0) return QueueMessageResult::CryptoUnavailable;

  int queuedIndex = -1;
  if (!enqueueWireFrame(
        wire,
        wireLength,
        3,
        millis() + randomBetween(20, 100),
        !broadcast,
        broadcast ? 0 : nextHop,
        view,
        &queuedIndex)) {
    return QueueMessageResult::TxQueueFull;
  }

  if (meta != nullptr) {
    meta->messageId = messageId;
    meta->nextHop = nextHop;
    meta->routeTag = routeTag;
    meta->routeSource = routeSource;
    meta->txQueueIndex = queuedIndex;
  }

  if (!broadcast && routePolicy == RoutePolicy::Routed && networkManifest.valid &&
      !vanguardRouter.hasExactG2(destination)) {
    (void)requestVanguardG2(destination);
  }

  if (broadcast) {
    Serial.printf(
      "[TX DATA] broadcast type=%u msg=%08lX frame=%08lX\r\n",
      static_cast<unsigned>(messageType),
      static_cast<unsigned long>(messageId),
      static_cast<unsigned long>(view.frameCounter)
    );
  } else {
    Serial.printf(
      "[ROUTE] destination=%08lX nextHop=%08lX source=%s\r\n",
      static_cast<unsigned long>(destination),
      static_cast<unsigned long>(nextHop),
      routeSourceText(routeSource)
    );
    Serial.printf(
      "[TX DATA] type=%u previousHop=%08lX nextHop=%08lX destination=%08lX frame=%08lX ttl=%u msg=%08lX tag=%08lX\r\n",
      static_cast<unsigned>(messageType),
      static_cast<unsigned long>(view.previousHop),
      static_cast<unsigned long>(view.nextHop),
      static_cast<unsigned long>(destination),
      static_cast<unsigned long>(view.frameCounter),
      static_cast<unsigned>(view.hopLimit),
      static_cast<unsigned long>(messageId),
      static_cast<unsigned long>(routeTag)
    );
  }

  return QueueMessageResult::Ok;
}

bool queueData(
  uint32_t destination,
  const uint8_t* data,
  size_t length,
  bool broadcast
) {
  return queueApplicationMessage(
    MessageType::UserData,
    destination,
    data,
    length,
    broadcast,
    RoutePolicy::Routed,
    nullptr
  ) == QueueMessageResult::Ok;
}

// ============================================================
// 16. RX FRAME HANDLERS
// ============================================================

void handleHello(const FrameView& view, const uint8_t* payload) {
  if (view.nextHop != BROADCAST_ID || payload == nullptr ||
      (view.payloadLength != 6 && view.payloadLength != 16)) {
    statRxMalformed++;
    return;
  }

  const uint32_t sequence = readU32(payload, 0);
  updateNeighborHello(view, sequence);

  if (view.payloadLength >= 16) {
    const int idx = findNeighborIndex(view.previousHop);
    if (idx >= 0) {
      NeighborEntry& neighbor = neighbors[idx];
      neighbor.remoteNetworkEpoch = readU32(payload, 6);
      neighbor.remoteManifestDigest = readU32(payload, 10);
      neighbor.remoteNodeSlot = payload[14];
      neighbor.manifestCompatible = networkManifest.valid &&
        neighbor.remoteNetworkEpoch == networkManifest.networkEpoch &&
        neighbor.remoteManifestDigest == networkManifest.digest &&
        networkManifest.slotFor(view.previousHop) == neighbor.remoteNodeSlot;
    }
  }
}

void handleAck(const FrameView& view, const uint8_t* payload) {
  if (view.nextHop != localNodeId ||
      view.payloadLength != 12 ||
      payload == nullptr ||
      !ackWaiting ||
      ackQueueIndex < 0) {
    return;
  }

  const uint32_t ackedPreviousHop = readU32(payload, 0);
  const uint32_t ackedBoot = readU32(payload, 4);
  const uint32_t ackedFrame = readU32(payload, 8);

  if (view.previousHop != expectedAckFromNode ||
      ackedPreviousHop != expectedAckOrigin ||
      ackedBoot != expectedAckBootCounter ||
      ackedFrame != expectedAckFrameCounter) {
    return;
  }

  const int completedIndex = ackQueueIndex;
  const uint32_t neighborId = expectedAckFromNode;
  const uint32_t messageId =
    (completedIndex >= 0 && completedIndex < static_cast<int>(MAX_TX_QUEUE))
      ? txQueue[completedIndex].messageId
      : 0;

  ackWaiting = false;
  ackQueueIndex = -1;
  statAckSuccess++;
  recordNeighborTxResult(neighborId, true);
  vanguardRouter.validateNextHop(neighborId, millis());
  if (completedIndex >= 0 &&
      completedIndex < static_cast<int>(MAX_TX_QUEUE) &&
      txQueue[completedIndex].used) {
    TxEntry& completed = txQueue[completedIndex];
    if (completed.relayCommitOnAck) {
      (void)checkAndMarkReplayValues(
        relayMessageReplayPeers, MAX_REPLAY_PEERS,
        completed.relayOrigin, completed.relayBootCounter, completed.relayMessageCounter);
    }
    onFieldTestHopAck(completed, neighborId);
  }
  completeTxEntry(completedIndex);

  Serial.printf(
    "[HOP ACK] msg=%08lX from=%08lX frame=%08lX\r\n",
    static_cast<unsigned long>(messageId),
    static_cast<unsigned long>(neighborId),
    static_cast<unsigned long>(ackedFrame)
  );
  uint8_t ackEvent[12];
  writeU32(ackEvent, 0, messageId);
  writeU32(ackEvent, 4, neighborId);
  writeU32(ackEvent, 8, ackedFrame);
  emitBleEvent(EVT_HOP_ACK, ackEvent, sizeof(ackEvent));
  setLastEvent("HOP ACK OK");
}

bool deliverLocalMessage(
  const MessageView& message,
  const uint8_t* applicationPayload,
  bool broadcast
) {
  const ReplayDecision messageReplay = checkAndMarkMessageReplay(message);
  if (messageReplay != ReplayDecision::Fresh) {
    if (messageReplay == ReplayDecision::Duplicate) {
      statMessageDuplicate++;
      Serial.printf(
        "[MESSAGE DUPLICATE] origin=%08lX msg=%08lX\r\n",
        static_cast<unsigned long>(message.origin),
        static_cast<unsigned long>(message.messageId)
      );
    } else {
      statMessageTooOld++;
      Serial.printf(
        "[MESSAGE REPLAY DROP] origin=%08lX msg=%08lX\r\n",
        static_cast<unsigned long>(message.origin),
        static_cast<unsigned long>(message.messageId)
      );
    }
    return false;
  }

  statMessagesReceived++;
  statMessagesDelivered++;

  if (message.type == MessageType::DiagPing) {
    handleLocalDiagPing(message, applicationPayload);
    return true;
  }

  if (message.type == MessageType::DiagPong) {
    handleLocalDiagPong(message, applicationPayload);
    return true;
  }

  if (message.type == MessageType::Position) {
    PositionRecord position;
    if (!decodePositionPayload(message.origin, applicationPayload, message.payloadLength, position)) return false;
    updatePositionCache(position, true);
    Serial.printf("[POSITION] origin=%08lX lat=%.7f lon=%.7f sats=%u hdop=%.2f\r\n",
      static_cast<unsigned long>(message.origin),
      static_cast<double>(position.latitudeE7) / 1e7,
      static_cast<double>(position.longitudeE7) / 1e7,
      static_cast<unsigned>(position.satellites),
      static_cast<double>(position.hdopX100) / 100.0);
    return true;
  }

  if (message.type == MessageType::Sos) {
    if (message.payloadLength != SOS_PAYLOAD_SIZE || applicationPayload[0] != SOS_PAYLOAD_VERSION) return false;
    ActiveSosRecord sos;
    sos.active = true;
    sos.originNodeId = message.origin;
    sos.sosType = applicationPayload[1];
    sos.flags = applicationPayload[2];
    sos.sosId = readU32(applicationPayload, 4);
    sos.raisedEpochSec = readU32(applicationPayload, 8);
    sos.latitudeE7 = static_cast<int32_t>(readU32(applicationPayload, 12));
    sos.longitudeE7 = static_cast<int32_t>(readU32(applicationPayload, 16));
    sos.positionAgeMs = readU32(applicationPayload, 20);
    sos.batteryPercent = applicationPayload[24];
    sos.receivedAtMs = millis();
    activeSos = sos;
    uint8_t event[29] {};
    writeU32(event, 0, sos.originNodeId);
    memcpy(event + 4, applicationPayload, SOS_PAYLOAD_SIZE);
    emitBleEvent(EVT_SOS_RAISED, event, sizeof(event));
    Serial.printf("[SOS] origin=%08lX id=%08lX type=%u\r\n", static_cast<unsigned long>(sos.originNodeId), static_cast<unsigned long>(sos.sosId), static_cast<unsigned>(sos.sosType));
    uiShowToast("SOS", "ТРЕВОГА В СЕТИ", 2500);
    setLastEvent("SOS RECEIVED");
    return true;
  }

  if (message.type == MessageType::SosAck) {
    if (message.payloadLength != 8) return false;
    const uint32_t sosId = readU32(applicationPayload, 0);
    const uint32_t ackBy = readU32(applicationPayload, 4);
    if (activeSos.active && activeSos.sosId == sosId && activeSos.originNodeId == localNodeId) {
      activeSos.acknowledged = true;
      activeSos.acknowledgedBy = ackBy;
    }
    uint8_t event[12];
    writeU32(event, 0, message.origin);
    writeU32(event, 4, sosId);
    writeU32(event, 8, ackBy);
    emitBleEvent(EVT_SOS_ACKNOWLEDGED, event, sizeof(event));
    uiShowToast("SOS", "ПОДТВЕРЖДЕН", 1800);
    return true;
  }

  if (message.type == MessageType::CommandNotice) {
    if (message.payloadLength != COMMAND_NOTICE_PAYLOAD_SIZE || applicationPayload[0] != COMMAND_NOTICE_VERSION) return false;
    const uint8_t commandKind = applicationPayload[1];
    const uint32_t commandId = readU32(applicationPayload, 4);
    uint8_t event[24] {};
    writeU32(event, 0, message.origin);
    memcpy(event + 4, applicationPayload, COMMAND_NOTICE_PAYLOAD_SIZE);
    writeU32(event, 20, message.messageId);
    emitBleEvent(EVT_COMMAND_NOTICE_RECEIVED, event, sizeof(event));
    const char* label = commandKind == static_cast<uint8_t>(CommandNoticeKind::Return) ? "ВЕРНИСЬ" :
      commandKind == static_cast<uint8_t>(CommandNoticeKind::CheckIn) ? "ДАЙ СТАТУС" :
      commandKind == static_cast<uint8_t>(CommandNoticeKind::Hold) ? "ОСТАВАЙСЯ" : "ИДИ К ТОЧКЕ";
    Serial.printf("[COMMAND NOTICE] from=%08lX command=%lu kind=%u\r\n", static_cast<unsigned long>(message.origin), static_cast<unsigned long>(commandId), static_cast<unsigned>(commandKind));
    uiShowToast("КОМАНДА", label, 3000);
    setLastEvent("COMMAND RX");
    return true;
  }

  char text[MAX_APP_PAYLOAD + 1];
  memcpy(text, applicationPayload, message.payloadLength);
  text[message.payloadLength] = '\0';

  Serial.printf(
    "[DELIVER LOCAL] origin=%08lX destination=%08lX msg=%08lX%s text=%s\r\n",
    static_cast<unsigned long>(message.origin),
    static_cast<unsigned long>(message.destination),
    static_cast<unsigned long>(message.messageId),
    broadcast ? " BROADCAST" : "",
    text
  );
  uint8_t localEvent[14 + MAX_APP_PAYLOAD] {};
  writeU32(localEvent, 0, message.origin);
  writeU32(localEvent, 4, message.destination);
  writeU32(localEvent, 8, message.messageId);
  localEvent[12] = static_cast<uint8_t>(message.type);
  localEvent[13] = message.payloadLength;
  memcpy(localEvent + 14, applicationPayload, message.payloadLength);
  emitBleEvent(
    EVT_MESSAGE_LOCAL_RECEIVED,
    localEvent,
    static_cast<uint16_t>(14 + message.payloadLength)
  );
  uiStoreIncomingMessage(message.origin, applicationPayload, message.payloadLength);
  setLastEvent("NEW MESSAGE");
  return true;
}

bool isFloodableBroadcastType(MessageType type) {
  return type == MessageType::Position || type == MessageType::Sos;
}

bool queueFloodForward(const FrameView& receivedFrame, const MessageView& message, const uint8_t* protectedMessageWire, uint8_t protectedMessageLength) {
  if (protectedMessageWire == nullptr || protectedMessageLength == 0 || receivedFrame.hopLimit <= 1) return false;
  uint8_t wire[MAX_WIRE_PACKET];
  FrameView forwarded;
  const uint16_t length = buildFrame(
    FrameType::Data, BROADCAST_ID, message.messageId, protectedMessageWire, protectedMessageLength,
    static_cast<uint8_t>(receivedFrame.hopLimit - 1), wire, &forwarded, 0);
  if (length == 0) return false;
  const uint8_t priority = message.type == MessageType::Sos ? 1 : 4;
  return enqueueWireFrame(wire, length, priority, millis() + randomBetween(45, 240), false, 0, forwarded, nullptr);
}

void handleData(
  const FrameView& view,
  const uint8_t* hopPayload,
  ReplayDecision hopReplayDecision
) {
  const bool physicalForUs = view.nextHop == localNodeId;
  const bool hopBroadcast = view.nextHop == BROADCAST_ID;

  if (!physicalForUs && !hopBroadcast) return;

  // Retry after a lost ACK reuses the exact same authenticated hop frame.
  // Re-ACK it, but never decrypt/deliver/forward it a second time.
  if (hopReplayDecision == ReplayDecision::Duplicate) {
    if (physicalForUs) queueAck(view);
    return;
  }

  if (hopReplayDecision != ReplayDecision::Fresh ||
      hopPayload == nullptr ||
      view.payloadLength < MESSAGE_HEADER_SIZE + AES_GCM_TAG_SIZE) {
    return;
  }

  MessageView message;
  uint8_t applicationPayload[MAX_APP_PAYLOAD + 1] {};
  if (!parseAndAuthenticateMessage(
        hopPayload,
        view.payloadLength,
        message,
        applicationPayload)) {
    statMessageAuthFail++;
    Serial.printf(
      "[MESSAGE AUTH FAIL] previousHop=%08lX frame=%08lX\r\n",
      static_cast<unsigned long>(view.previousHop),
      static_cast<unsigned long>(view.frameCounter)
    );
    return;
  }

  if (message.messageId != view.messageId ||
      (hopBroadcast && message.destination != BROADCAST_ID) ||
      (!hopBroadcast && message.destination == BROADCAST_ID)) {
    statRxMalformed++;
    return;
  }

  // Hop ACK acknowledges receipt of this authenticated hop frame. It is not
  // an end-to-end delivery receipt and does not assert forwarding succeeded.
  if (physicalForUs) queueAck(view);

  Serial.printf(
    "[RX DATA] previousHop=%08lX destination=%08lX msg=%08lX frame=%08lX ttl=%u RSSI=%d SNR=%.1f\r\n",
    static_cast<unsigned long>(view.previousHop),
    static_cast<unsigned long>(message.destination),
    static_cast<unsigned long>(message.messageId),
    static_cast<unsigned long>(view.frameCounter),
    static_cast<unsigned>(view.hopLimit),
    lastRxRssiDbm,
    lastRxSnrDb
  );

  if (message.origin == localNodeId) return;

  if (message.destination == localNodeId) {
    (void)deliverLocalMessage(message, applicationPayload, false);
    return;
  }

  if (message.destination == BROADCAST_ID) {
    const bool freshLogical = deliverLocalMessage(message, applicationPayload, true);
    if (freshLogical && isFloodableBroadcastType(message.type) && view.hopLimit > 1) {
      if (queueFloodForward(view, message, hopPayload, view.payloadLength)) {
        statMessagesForwarded++;
        Serial.printf("[FLOOD RELAY] type=%u origin=%08lX msg=%08lX ttl=%u->%u\r\n",
          static_cast<unsigned>(message.type), static_cast<unsigned long>(message.origin),
          static_cast<unsigned long>(message.messageId), static_cast<unsigned>(view.hopLimit),
          static_cast<unsigned>(view.hopLimit - 1));
      }
    }
    return;
  }

  if (hopBroadcast) return;

  // A fresh outer hop frame can still carry a logical Message already seen
  // through another fresh hop frame. ACK the hop above, but do not relay the
  // same authenticated logical Message twice.
  if (relayLogicalInFlight(message)) {
    statRelayLogicalDuplicate++;
    Serial.printf(
      "[RELAY COALESCE] origin=%08lX msg=%08lX already pending next-hop ACK\r\n",
      static_cast<unsigned long>(message.origin),
      static_cast<unsigned long>(message.messageId));
    return;
  }

  const ReplayDecision relayReplay = checkRelayMessageReplay(message);
  if (relayReplay != ReplayDecision::Fresh) {
    statRelayLogicalDuplicate++;
    Serial.printf(
      "[RELAY LOGICAL DUPLICATE] origin=%08lX msg=%08lX decision=%s\r\n",
      static_cast<unsigned long>(message.origin),
      static_cast<unsigned long>(message.messageId),
      relayReplay == ReplayDecision::Duplicate ? "DUP" : "OLD"
    );
    return;
  }

  if (view.routeTag == 0) {
    vanguardRuntime.notePrecursor(message.destination, view.previousHop);

    uint32_t relayNextHop = 0;
    RouteSource relaySource = RouteSource::None;
    if (!resolveRelayNextHop(message.destination, relayNextHop, relaySource)) {
      if (storePendingRelay(view, message, hopPayload, view.payloadLength)) {
        (void)requestVanguardRoute(message.destination, false);
        Serial.printf(
          "[STORE-FORWARD] msg=%08lX destination=%08lX waiting for route\r\n",
          static_cast<unsigned long>(message.messageId),
          static_cast<unsigned long>(message.destination));
        setLastEvent("WAIT ROUTE");
      } else {
        Serial.println("[STORE-FORWARD DROP] pending relay buffer full");
      }
      return;
    }
  } else {
    // Validate the source-pinned label before consuming TX queue space. If the
    // label disappeared (e.g. this relay rebooted), queueForwardedData will
    // send a path-specific RERR upstream instead of silently rerouting.
    uint32_t pinnedNextHop = 0;
    if (!vanguardRuntime.resolveFlowLabel(
          message.origin,
          message.bootCounter,
          message.destination,
          view.routeTag,
          view.previousHop,
          millis(),
          pinnedNextHop) ||
        !isFreshDirectNeighbor(pinnedNextHop)) {
      (void)queueForwardedData(view, message, hopPayload, view.payloadLength);
      return;
    }
  }

  if (queueForwardedData(
        view,
        message,
        hopPayload,
        view.payloadLength)) {
    // Logical replay state is committed only after the forwarded hop is ACKed.
    statMessagesForwarded++;
    setLastEvent("FORWARDED");
  } else if (storePendingRelay(view, message, hopPayload, view.payloadLength)) {
    // Route exists, but the bounded TX queue may be temporarily full. Preserve
    // the immutable logical message and retry without acknowledging logical
    // forwarding success prematurely.
    setLastEvent("TX DEFER");
  } else {
    Serial.println("[STORE-FORWARD DROP] pending relay buffer full");
  }
}

void processAuthenticatedFrame(
  const FrameView& view,
  const uint8_t* payload,
  int16_t rssiDbm,
  float snrDb
) {
  // A node only consumes frames physically addressed to this hop or broadcast.
  if (view.nextHop != localNodeId &&
      view.nextHop != BROADCAST_ID) {
    return;
  }

  if (view.previousHop == localNodeId) return;

  const ReplayDecision replay = checkAndMarkHopReplay(view);

  // Only fresh authenticated hop frames refresh neighbor presence/quality.
  if (replay == ReplayDecision::Fresh) {
    updateNeighborReception(view, rssiDbm, snrDb);
    learnVanguardDirectNeighbor(view);
  }

  if (replay == ReplayDecision::Duplicate) {
    statRxDuplicate++;
  } else if (replay == ReplayDecision::TooOld) {
    statRxTooOld++;
  }

  switch (view.type) {
    case FrameType::Hello:
      if (replay == ReplayDecision::Fresh) {
        handleHello(view, payload);
      }
      break;

    case FrameType::Data:
      handleData(view, payload, replay);
      break;

    case FrameType::Ack:
      if (replay == ReplayDecision::Fresh) {
        handleAck(view, payload);
      }
      break;

    case FrameType::Control:
      if (replay == ReplayDecision::Duplicate) {
        if (view.nextHop == localNodeId) (void)queueAck(view);
      } else if (replay == ReplayDecision::Fresh) {
        handleVanguardControl(view, payload);
      }
      break;
  }
}

// ============================================================
// 17. RADIO RX/TX ENGINE
// ============================================================

bool startQueuedTransmission(int index) {
  if (!radioReady || radioTransmitting ||
      index < 0 || index >= static_cast<int>(MAX_TX_QUEUE) ||
      !txQueue[index].used) {
    return false;
  }

  TxEntry& entry = txQueue[index];
  const uint32_t entryNextHop = entry.length >= OFF_NEXT_HOP + 4
    ? readU32(entry.bytes, OFF_NEXT_HOP) : 0;
  if (entryNextHop != 0 && entryNextHop != BROADCAST_ID &&
      isLabLinkBlocked(entryNextHop)) {
    statLabFaultTxDrops++;
    const FrameType entryType = entry.length > OFF_TYPE
      ? static_cast<FrameType>(entry.bytes[OFF_TYPE]) : FrameType::Hello;
    completeTxEntry(index);
    if (entryType == FrameType::Data || entryType == FrameType::Control) {
      notifyVanguardHopFailure(entryNextHop);
    }
    return false;
  }

  const int16_t standbyState = radio.standby();
  if (standbyState != RADIOLIB_ERR_NONE) {
    statTxErrors++;
    recoverRadio(standbyState);
    return false;
  }

  setRfTransmit();
  radioIrqFlag = false;
  radioTransmitting = true;
  activeTxIndex = index;
  txStartedAtMs = millis();

  const int16_t state = radio.startTransmit(entry.bytes, entry.length);
  if (state != RADIOLIB_ERR_NONE) {
    radioTransmitting = false;
    activeTxIndex = -1;
    setRfIdle();
    statTxErrors++;
    entry.dueAtMs = millis() + retryBackoffMs(entry.attempts);
    recoverRadio(state);
    return false;
  }

  onFieldTestTxStarted(entry, txStartedAtMs);
  return true;
}

void finishQueuedTransmission() {
  const int finishedIndex = activeTxIndex;
  const int16_t state = radio.finishTransmit();

  radioTransmitting = false;
  activeTxIndex = -1;
  setRfIdle();

  if (state != RADIOLIB_ERR_NONE) {
    statTxErrors++;
    if (finishedIndex >= 0 &&
        finishedIndex < static_cast<int>(MAX_TX_QUEUE) &&
        txQueue[finishedIndex].used) {
      txQueue[finishedIndex].dueAtMs = millis() + 500;
    }
    recoverRadio(state);
    return;
  }

  statTxFrames++;

  if (finishedIndex >= 0 &&
      finishedIndex < static_cast<int>(MAX_TX_QUEUE) &&
      txQueue[finishedIndex].used) {
    TxEntry& entry = txQueue[finishedIndex];
    const uint32_t completedAtMs = millis();
    onFieldTestTxCompleted(entry, completedAtMs);

    if (entry.requiresAck) {
      ackWaiting = true;
      ackQueueIndex = finishedIndex;
      ackStartedAtMs = completedAtMs;
      expectedAckFromNode = entry.ackFromNode;
      expectedAckOrigin = entry.frameOrigin;
      expectedAckBootCounter = entry.frameBootCounter;
      expectedAckFrameCounter = entry.frameCounter;
    } else {
      completeTxEntry(finishedIndex);
    }
  }

  startReceiveMode();
}

void handleReceivedRadioPacket() {
  const size_t packetLength = radio.getPacketLength();

  if (packetLength < HEADER_SIZE + AES_GCM_TAG_SIZE ||
      packetLength > MAX_WIRE_PACKET) {
    uint8_t discard[MAX_WIRE_PACKET];
    const size_t safeLength =
      (packetLength > MAX_WIRE_PACKET) ? MAX_WIRE_PACKET : packetLength;
    if (safeLength > 0) {
      radio.readData(discard, safeLength);
    }
    statRxMalformed++;
    startReceiveMode();
    return;
  }

  uint8_t raw[MAX_WIRE_PACKET] {};
  const int16_t readState = radio.readData(raw, packetLength);
  const int16_t packetRssi = static_cast<int16_t>(lround(radio.getRSSI()));
  const float packetSnr = radio.getSNR();

  startReceiveMode();

  if (readState != RADIOLIB_ERR_NONE) {
    statRxMalformed++;
    return;
  }

  FrameView view;
  uint8_t plaintext[MAX_HOP_PAYLOAD + 1] {};

  if (!parseAndAuthenticateFrame(raw, packetLength, view, plaintext)) {
    statRxAuthFail++;
    return;
  }

  // Fault-lab blocks are applied only after successful authentication so a
  // malformed unauthenticated frame cannot influence the injected-link state.
  if (isLabLinkBlocked(view.previousHop)) {
    statLabFaultRxDrops++;
    return;
  }

  statRxValid++;
  lastRxRssiDbm = packetRssi;
  lastRxSnrDb = packetSnr;

  processAuthenticatedFrame(view, plaintext, packetRssi, packetSnr);
}

void processRadioInterrupt() {
  if (!radioIrqFlag) return;
  radioIrqFlag = false;

  if (radioTransmitting) {
    finishQueuedTransmission();
  } else {
    handleReceivedRadioPacket();
  }
}

void processAckTimeout() {
  if (!ackWaiting || ackQueueIndex < 0) return;
  if (millis() - ackStartedAtMs <= ackTimeoutMs) return;

  const int index = ackQueueIndex;
  const uint32_t failedNeighbor = expectedAckFromNode;

  ackWaiting = false;
  ackQueueIndex = -1;
  statAckTimeout++;
  recordNeighborTxResult(failedNeighbor, false);

  if (index < 0 ||
      index >= static_cast<int>(MAX_TX_QUEUE) ||
      !txQueue[index].used) {
    return;
  }

  TxEntry& entry = txQueue[index];
  entry.attempts++;
  const bool finalFailure = entry.attempts >= MAX_DATA_ATTEMPTS;
  onFieldTestHopTimeout(entry, finalFailure);
  if (!finalFailure) {
    uint8_t retryEvent[9];
    writeU32(retryEvent, 0, entry.messageId);
    writeU32(retryEvent, 4, failedNeighbor);
    retryEvent[8] = entry.attempts;
    emitBleEvent(EVT_RETRY, retryEvent, sizeof(retryEvent));
  }

  if (finalFailure) {
    Serial.printf(
      "[HOP FAILED] msg=%08lX nextHop=%08lX after %u attempts\r\n",
      static_cast<unsigned long>(entry.messageId),
      static_cast<unsigned long>(failedNeighbor),
      static_cast<unsigned>(entry.attempts)
    );
    setLastEvent("HOP FAILED");
    notifyVanguardHopFailure(failedNeighbor);
    completeTxEntry(index);
  } else {
    entry.dueAtMs = millis() + retryBackoffMs(entry.attempts);
  }
}

void processTxScheduler() {
  // Never transition SX1268 to TX while an RX/TX DIO1 event is pending.
  // Otherwise a just-received packet can be overwritten before readData().
  if (!radioReady || radioTransmitting || radioIrqFlag) return;
  const int index = selectTxEntry();
  if (index >= 0) startQueuedTransmission(index);
}

void processTxWatchdog() {
  if (!radioTransmitting) return;
  if (millis() - txStartedAtMs <= TX_WATCHDOG_MS) return;

  statTxErrors++;
  recoverRadio(-9001);
}

// ============================================================
// 18. HELLO SCHEDULER
// ============================================================

uint32_t nextHelloAtMs = 0;

void processHelloScheduler() {
  const uint32_t now = millis();
  if (!timeReached(now, nextHelloAtMs)) return;

  if (queueHello()) {
    nextHelloAtMs = now + randomBetween(
      HELLO_INTERVAL_MIN_MS,
      HELLO_INTERVAL_MAX_MS
    );
  } else {
    nextHelloAtMs = now + 600;
  }
}

// ============================================================
// 19. COMMAND API / FIELD TEST / BLE CONTROL PLANE
// ============================================================

constexpr uint8_t FIRMWARE_VERSION_MAJOR = 1;
constexpr uint8_t FIRMWARE_VERSION_MINOR = 0;
constexpr uint8_t FIRMWARE_VERSION_PATCH = 4;

constexpr uint32_t CAP_MESSAGING      = 1UL << 0;
constexpr uint32_t CAP_STATIC_ROUTING = 1UL << 1;
constexpr uint32_t CAP_RELAY          = 1UL << 2;
constexpr uint32_t CAP_FIELD_TEST     = 1UL << 3;
constexpr uint32_t CAP_BLE_CONTROL    = 1UL << 4;
constexpr uint32_t CAP_UI_OS          = 1UL << 5;
constexpr uint32_t CAP_VANGUARD       = 1UL << 6;
constexpr uint32_t CAP_MANIFEST       = 1UL << 7;
constexpr uint32_t CAP_FAULT_LAB      = 1UL << 8;
constexpr uint32_t CAP_GPS            = 1UL << 9;
constexpr uint32_t CAP_SOS            = 1UL << 10;
constexpr uint32_t CAP_COMMAND_MAP    = 1UL << 11;
constexpr uint32_t CAP_BLE_RADAR      = 1UL << 12;
constexpr uint32_t CAP_OPERATIONAL_HEALTH = 1UL << 13;
constexpr uint32_t CAP_SELF_DIAGNOSTICS  = 1UL << 14;
constexpr uint32_t CAP_OLED_FRAMEBUFFER = 1UL << 15;
constexpr uint32_t LOCAL_CAPABILITIES =
  CAP_MESSAGING | CAP_STATIC_ROUTING | CAP_RELAY |
  CAP_FIELD_TEST | CAP_BLE_CONTROL | CAP_UI_OS |
  CAP_VANGUARD | CAP_MANIFEST | CAP_FAULT_LAB |
  CAP_GPS | CAP_SOS | CAP_COMMAND_MAP | CAP_BLE_RADAR |
  CAP_OPERATIONAL_HEALTH | CAP_SELF_DIAGNOSTICS | CAP_OLED_FRAMEBUFFER;

constexpr uint8_t DEVICE_ROLE_DEVELOPMENT = 1;
constexpr uint32_t DEV_PERMISSION_READ       = 1UL << 0;
constexpr uint32_t DEV_PERMISSION_SEND       = 1UL << 1;
constexpr uint32_t DEV_PERMISSION_ROUTE_EDIT = 1UL << 2;
constexpr uint32_t DEV_PERMISSION_FIELD_TEST = 1UL << 3;
constexpr uint32_t DEV_PERMISSION_POSITION   = 1UL << 4;
constexpr uint32_t DEV_PERMISSION_SOS        = 1UL << 5;
constexpr uint32_t DEVELOPMENT_PERMISSION_MASK =
  DEV_PERMISSION_READ | DEV_PERMISSION_SEND |
  DEV_PERMISSION_ROUTE_EDIT | DEV_PERMISSION_FIELD_TEST |
  DEV_PERMISSION_POSITION | DEV_PERMISSION_SOS;

enum class CommandType : uint8_t {
  GetInfo = 1,
  GetStatus = 2,
  GetNeighbors = 3,
  GetRoutes = 4,
  SendMessage = 5,
  AddStaticRoute = 6,
  RemoveStaticRoute = 7,
  StartFieldTest = 8,
  StopFieldTest = 9,
  GetFieldTestStatus = 10,
  PingLocal = 11,
  ClearStats = 12,
  GetUiState = 13,
  UiAction = 14,
  GetKnownNodes = 15,
  GetManifest = 16,
  SetManifest = 17,
  DiscoverRoute = 18,
  GetRoutingDiagnostics = 19,
  InjectLinkFailure = 20,
  ClearDynamicRoutes = 21,
  SetLabLinkPolicy = 22,
  GetLabLinkPolicies = 23,
  GetPositions = 24,
  RaiseSos = 25,
  AckSos = 26,
  SendCommandNotice = 27,
  GetBleRadar = 28,
  ClearBleRadar = 29,
  GetOperationalHealth = 30,
  GetSelfDiagnostics = 31,
  BleStatus = 32,
  BleAdvertise = 33,
  BleBonds = 34,
  BleBondsClear = 35,
  Broadcast = 36,
  Reboot = 37,
  GetOledFrameChunk = 38
};

enum class CommandSource : uint8_t {
  Serial = 1,
  Ble = 2,
  Internal = 3
};

enum class CommandStatus : uint8_t {
  Ok = 0,
  InvalidCommand = 1,
  InvalidArgument = 2,
  NotAuthenticated = 3,
  NotSupported = 4,
  Busy = 5,
  NoRoute = 6,
  TxQueueFull = 7,
  RadioUnavailable = 8,
  CryptoUnavailable = 9,
  TestAlreadyRunning = 10,
  TestNotRunning = 11,
  Timeout = 12,
  InternalError = 13
};

struct CommandRequest {
  CommandType type = CommandType::GetInfo;
  CommandSource source = CommandSource::Serial;
  uint16_t requestId = 0;
  uint32_t destination = 0;
  uint32_t nextHop = 0;
  uint16_t packetCount = 0;
  uint32_t intervalMs = 0;
  uint8_t payloadSize = 0;
  FieldTestMode testMode = FieldTestMode::Routed;
  bool flag = false;
  uint8_t dataLength = 0;
  uint8_t uiAction = 0;
  uint8_t oledChunkIndex = 0;
  uint8_t data[180] {};
};

constexpr uint16_t COMMAND_RESULT_MAX_PAYLOAD = 370;

// v1.0.4 OPERATOR: one source of truth for BLE wire sizes.
// Fixed snapshots must serialize to exactly these lengths; variable records
// are bounded separately so firmware and Commander cannot silently drift.
constexpr size_t BLE_INFO_PAYLOAD_BYTES = 23;
constexpr size_t BLE_STATUS_PAYLOAD_BYTES = 42;
constexpr size_t BLE_OPERATIONAL_HEALTH_PAYLOAD_BYTES = 17;
constexpr size_t BLE_SELF_DIAG_PAYLOAD_BYTES = 43;
constexpr size_t BLE_FIELD_STATUS_PAYLOAD_BYTES = 67;
constexpr size_t BLE_NEIGHBOR_RECORD_BYTES = 29;
constexpr size_t BLE_ROUTE_RECORD_BYTES = 9;
constexpr size_t BLE_MANIFEST_HEADER_BYTES = 10;
constexpr size_t BLE_MANIFEST_RECORD_BYTES = 5;
constexpr size_t BLE_ROUTING_DIAG_HEADER_BYTES = 89;
constexpr size_t BLE_ROUTING_DIAG_RECORD_BYTES = 56;
constexpr size_t BLE_LAB_POLICY_RECORD_BYTES = 15;
constexpr size_t BLE_RADAR_HEADER_BYTES = 12;
constexpr size_t BLE_RADAR_RECORD_BYTES = 30;
constexpr size_t BLE_OLED_FRAME_WIDTH = 128;
constexpr size_t BLE_OLED_FRAME_HEIGHT = 64;
constexpr size_t BLE_OLED_FRAME_BYTES = (BLE_OLED_FRAME_WIDTH * BLE_OLED_FRAME_HEIGHT) / 8;
constexpr uint16_t BLE_OLED_FRAME_CHUNK_BYTES = 256;
constexpr uint8_t BLE_OLED_FRAME_CHUNK_COUNT = static_cast<uint8_t>((BLE_OLED_FRAME_BYTES + BLE_OLED_FRAME_CHUNK_BYTES - 1) / BLE_OLED_FRAME_CHUNK_BYTES);
constexpr size_t BLE_OLED_FRAME_HEADER_BYTES = 11;

static_assert(BLE_OLED_FRAME_BYTES == 1024, "OLED framebuffer must be 128x64 monochrome");
static_assert(BLE_OLED_FRAME_HEADER_BYTES + BLE_OLED_FRAME_CHUNK_BYTES <= COMMAND_RESULT_MAX_PAYLOAD,
              "OLED framebuffer chunk no longer fits BLE command result");

static_assert(MAX_APP_PAYLOAD <= UINT8_MAX,
              "application payload length field is uint8_t");
static_assert(BLE_ROUTING_DIAG_HEADER_BYTES +
                MAX_LAB_NODES * BLE_ROUTING_DIAG_RECORD_BYTES <=
              COMMAND_RESULT_MAX_PAYLOAD,
              "routing diagnostics no longer fit BLE command result");
static_assert(1 + MAX_NEIGHBORS * BLE_NEIGHBOR_RECORD_BYTES <=
              COMMAND_RESULT_MAX_PAYLOAD,
              "neighbor snapshot no longer fits BLE command result");
static_assert(BLE_MANIFEST_HEADER_BYTES +
                MAX_LAB_NODES * BLE_MANIFEST_RECORD_BYTES <=
              COMMAND_RESULT_MAX_PAYLOAD,
              "manifest snapshot no longer fits BLE command result");
struct CommandResult {
  CommandStatus status = CommandStatus::Ok;
  uint16_t payloadLength = 0;
  uint8_t payload[COMMAND_RESULT_MAX_PAYLOAD] {};
};

struct BinaryWriter {
  uint8_t* data = nullptr;
  size_t capacity = 0;
  size_t length = 0;
  bool ok = true;

  bool putU8(uint8_t v) {
    if (!ok || length + 1 > capacity) return ok = false;
    data[length++] = v;
    return true;
  }
  bool putU16(uint16_t v) {
    if (!ok || length + 2 > capacity) return ok = false;
    writeU16(data, length, v); length += 2; return true;
  }
  bool putI16(int16_t v) { return putU16(static_cast<uint16_t>(v)); }
  bool putU32(uint32_t v) {
    if (!ok || length + 4 > capacity) return ok = false;
    writeU32(data, length, v); length += 4; return true;
  }
  bool putBytes(const uint8_t* src, size_t n) {
    if (!ok || src == nullptr || length + n > capacity) return ok = false;
    memcpy(data + length, src, n); length += n; return true;
  }
};

extern bool oledReady;

uint32_t largestFreeHeapBytes() {
  return static_cast<uint32_t>(
    heap_caps_get_largest_free_block(MALLOC_CAP_8BIT)
  );
}

CommandStatus mapQueueResult(QueueMessageResult result) {
  switch (result) {
    case QueueMessageResult::Ok: return CommandStatus::Ok;
    case QueueMessageResult::InvalidArgument: return CommandStatus::InvalidArgument;
    case QueueMessageResult::NoRoute: return CommandStatus::NoRoute;
    case QueueMessageResult::RouteDiscoveryStarted: return CommandStatus::Busy;
    case QueueMessageResult::TxQueueFull: return CommandStatus::TxQueueFull;
    case QueueMessageResult::CryptoUnavailable: return CommandStatus::CryptoUnavailable;
    case QueueMessageResult::RadioUnavailable: return CommandStatus::RadioUnavailable;
  }
  return CommandStatus::InternalError;
}

uint16_t buildInfoPayload(uint8_t* out, size_t capacity);
uint16_t buildStatusPayload(uint8_t* out, size_t capacity);
uint16_t buildUiStatePayload(uint8_t* out, size_t capacity);
uint16_t buildOledFrameChunkPayload(uint8_t chunkIndex, uint8_t* out, size_t capacity);
bool uiHandleRemoteAction(uint8_t rawAction);
uint16_t buildNeighborsPayload(uint8_t* out, size_t capacity);
uint16_t buildRoutesPayload(uint8_t* out, size_t capacity);
uint16_t buildKnownNodesPayload(uint8_t* out, size_t capacity);
uint16_t buildManifestPayload(uint8_t* out, size_t capacity);
uint16_t buildRoutingDiagnosticsPayload(uint8_t* out, size_t capacity);
uint16_t buildLabLinkPoliciesPayload(uint8_t* out, size_t capacity);
uint16_t buildFieldTestStatusPayload(uint8_t* out, size_t capacity);
uint16_t buildBleRadarPayload(uint8_t* out, size_t capacity);
uint16_t buildOperationalHealthPayload(uint8_t* out, size_t capacity);
uint16_t buildSelfDiagnosticsPayload(uint8_t* out, size_t capacity);
void clearBleRadar();

// -------------------- Field Test Service --------------------

constexpr uint8_t DIAG_HEADER_SIZE = 16;

int findFreeDiagPending() {
  for (size_t i = 0; i < MAX_DIAG_PENDING; ++i) {
    if (!diagPending[i].used) return static_cast<int>(i);
  }
  return -1;
}

int findDiagPending(uint32_t sequence, uint32_t token) {
  for (size_t i = 0; i < MAX_DIAG_PENDING; ++i) {
    if (diagPending[i].used &&
        diagPending[i].sequence == sequence &&
        diagPending[i].token == token) {
      return static_cast<int>(i);
    }
  }
  return -1;
}

int findDiagPendingBySequence(uint32_t sequence) {
  for (size_t i = 0; i < MAX_DIAG_PENDING; ++i) {
    if (diagPending[i].used && diagPending[i].sequence == sequence) {
      return static_cast<int>(i);
    }
  }
  return -1;
}

size_t countDiagPending() {
  size_t count = 0;
  for (const auto& p : diagPending) if (p.used) count++;
  return count;
}

void resetFieldTestPending() {
  for (auto& pending : diagPending) pending = DiagPendingProbe{};
}

CommandStatus startFieldTest(
  uint32_t target,
  uint16_t packetCount,
  uint32_t intervalMs,
  uint8_t payloadSize,
  FieldTestMode mode
) {
  if (fieldTest.state == FieldTestState::Running) {
    return CommandStatus::TestAlreadyRunning;
  }
  if (!cryptoReady) return CommandStatus::CryptoUnavailable;
  if (!radioReady) return CommandStatus::RadioUnavailable;
  if (target == 0 || target == BROADCAST_ID || target == localNodeId ||
      packetCount == 0 || packetCount > FIELD_TEST_MAX_PACKETS ||
      intervalMs < FIELD_TEST_MIN_INTERVAL_MS ||
      intervalMs > FIELD_TEST_MAX_INTERVAL_MS ||
      payloadSize < DIAG_HEADER_SIZE || payloadSize > MAX_APP_PAYLOAD) {
    return CommandStatus::InvalidArgument;
  }

  uint32_t nextHop = 0;
  RouteSource source = RouteSource::None;
  const RoutePolicy policy = mode == FieldTestMode::DirectOnly
    ? RoutePolicy::DirectOnly : RoutePolicy::Routed;
  if (!resolveNextHop(target, nextHop, source, policy)) {
    if (policy == RoutePolicy::Routed && requestVanguardRoute(target, false)) {
      return CommandStatus::Busy;
    }
    return CommandStatus::NoRoute;
  }

  fieldTest = FieldTestContext{};
  resetFieldTestPending();
  fieldTest.state = FieldTestState::Running;
  fieldTest.mode = mode;
  fieldTest.testId = esp_random();
  if (fieldTest.testId == 0) fieldTest.testId = 1;
  fieldTest.targetNodeId = target;
  fieldTest.requestedPackets = packetCount;
  fieldTest.intervalMs = intervalMs;
  fieldTest.payloadSize = payloadSize;
  fieldTest.startedAtMs = millis();
  fieldTest.nextSendAtMs = fieldTest.startedAtMs;
  fieldTest.lastNextHop = nextHop;
  fieldTest.lastRouteSource = source;
  fieldTest.rttMinMs = UINT32_MAX;

  uint8_t event[11];
  writeU32(event, 0, fieldTest.testId);
  writeU32(event, 4, target);
  writeU16(event, 8, packetCount);
  event[10] = static_cast<uint8_t>(mode);
  emitBleEvent(EVT_TEST_STARTED, event, sizeof(event));

  Serial.printf(
    "[TEST START] id=%08lX target=%08lX packets=%u interval=%lums size=%u mode=%s firstHop=%08lX\r\n",
    static_cast<unsigned long>(fieldTest.testId),
    static_cast<unsigned long>(target),
    static_cast<unsigned>(packetCount),
    static_cast<unsigned long>(intervalMs),
    static_cast<unsigned>(payloadSize),
    mode == FieldTestMode::DirectOnly ? "DIRECT_ONLY" : "ROUTED",
    static_cast<unsigned long>(nextHop)
  );
  setLastEvent("TEST RUNNING");
  return CommandStatus::Ok;
}

void emitFieldTestFinishedEvent(CommandStatus reason) {
  uint8_t event[18];
  writeU32(event, 0, fieldTest.testId);
  event[4] = static_cast<uint8_t>(fieldTest.state);
  event[5] = static_cast<uint8_t>(reason);
  writeU32(event, 6, fieldTest.sent);
  writeU32(event, 10, fieldTest.endToEndReplies);
  writeU32(event, 14, fieldTest.endToEndTimeouts);
  emitBleEvent(EVT_TEST_FINISHED, event, sizeof(event));
}

CommandStatus stopFieldTest() {
  if (fieldTest.state != FieldTestState::Running) {
    return CommandStatus::TestNotRunning;
  }
  fieldTest.state = FieldTestState::Cancelled;
  fieldTest.finishedAtMs = millis();
  resetFieldTestPending();
  emitFieldTestFinishedEvent(CommandStatus::Ok);
  Serial.println("[TEST STOP] cancelled by command");
  setLastEvent("TEST CANCELLED");
  return CommandStatus::Ok;
}

void finishFieldTest() {
  if (fieldTest.state != FieldTestState::Running) return;
  fieldTest.state = FieldTestState::Finished;
  fieldTest.finishedAtMs = millis();
  emitFieldTestFinishedEvent(CommandStatus::Ok);

  const float pdr = fieldTest.sent == 0 ? 0.0f :
    100.0f * static_cast<float>(fieldTest.endToEndReplies) /
    static_cast<float>(fieldTest.sent);
  Serial.printf(
    "[TEST FINISHED] id=%08lX sent=%lu e2e=%lu timeout=%lu PDR=%.1f%%\r\n",
    static_cast<unsigned long>(fieldTest.testId),
    static_cast<unsigned long>(fieldTest.sent),
    static_cast<unsigned long>(fieldTest.endToEndReplies),
    static_cast<unsigned long>(fieldTest.endToEndTimeouts),
    pdr
  );
  setLastEvent("TEST FINISHED");
}

void onFieldTestTxStarted(const TxEntry& entry, uint32_t startedAtMs) {
  if (!entry.fieldTestOrigin || entry.fieldTestId != fieldTest.testId) return;
  const int index = findDiagPendingBySequence(entry.fieldTestSequence);
  if (index < 0) return;
  DiagPendingProbe& pending = diagPending[index];
  if (pending.actualTxStartedAtMs == 0) {
    pending.actualTxStartedAtMs = startedAtMs;
  }
}

void onFieldTestTxCompleted(const TxEntry& entry, uint32_t completedAtMs) {
  if (!entry.fieldTestOrigin || entry.fieldTestId != fieldTest.testId) return;
  const int index = findDiagPendingBySequence(entry.fieldTestSequence);
  if (index < 0) return;
  DiagPendingProbe& pending = diagPending[index];
  if (pending.actualTxCompletedAtMs != 0) return;

  pending.actualTxCompletedAtMs = completedAtMs;
  pending.deadlineAtMs = completedAtMs + DIAG_PONG_TIMEOUT_MS;
  fieldTest.sent++;

  uint8_t event[16];
  writeU32(event, 0, fieldTest.testId);
  writeU32(event, 4, entry.fieldTestSequence);
  writeU32(event, 8, entry.ackFromNode);
  writeU32(event, 12, fieldTest.sent);
  emitBleEvent(EVT_TEST_PACKET_SENT, event, sizeof(event));
}

void onFieldTestHopAck(const TxEntry& entry, uint32_t neighborId) {
  if (!entry.fieldTestOrigin ||
      entry.fieldTestId != fieldTest.testId) return;

  fieldTest.firstHopAcked++;
  const int n = findNeighborIndex(neighborId);
  if (n >= 0) {
    fieldTest.localRssiSum += static_cast<int64_t>(lroundf(neighbors[n].rssiEwma * 10.0f));
    fieldTest.localSnrTenthsSum += static_cast<int64_t>(lroundf(neighbors[n].snrEwma * 10.0f));
    fieldTest.localLinkSamples++;
  }
}

void onFieldTestHopTimeout(const TxEntry& entry, bool finalFailure) {
  if (!entry.fieldTestOrigin || entry.fieldTestId != fieldTest.testId) return;
  fieldTest.firstHopRetries++;
  if (finalFailure) fieldTest.firstHopFailed++;
}

void handleLocalDiagPing(const MessageView& message, const uint8_t* payload) {
  if (payload == nullptr || message.payloadLength < DIAG_HEADER_SIZE) {
    Serial.println("[DIAG PING] malformed payload");
    return;
  }

  const uint32_t testId = readU32(payload, 0);
  const uint32_t sequence = readU32(payload, 4);
  Serial.printf(
    "[DIAG PING] from=%08lX test=%08lX seq=%lu\r\n",
    static_cast<unsigned long>(message.origin),
    static_cast<unsigned long>(testId),
    static_cast<unsigned long>(sequence)
  );

  QueuedMessageMeta meta;
  const QueueMessageResult result = queueApplicationMessage(
    MessageType::DiagPong,
    message.origin,
    payload,
    DIAG_HEADER_SIZE,
    false,
    RoutePolicy::Routed,
    &meta
  );

  if (result == QueueMessageResult::NoRoute) {
    uint8_t event[12];
    writeU32(event, 0, message.origin);
    writeU32(event, 4, testId);
    writeU32(event, 8, sequence);
    emitBleEvent(EVT_NO_RETURN_ROUTE, event, sizeof(event));
    Serial.printf(
      "[DIAG] NO RETURN ROUTE origin=%08lX test=%08lX seq=%lu\r\n",
      static_cast<unsigned long>(message.origin),
      static_cast<unsigned long>(testId),
      static_cast<unsigned long>(sequence)
    );
  }
}

void handleLocalDiagPong(const MessageView& message, const uint8_t* payload) {
  if (payload == nullptr || message.payloadLength < DIAG_HEADER_SIZE) return;

  const uint32_t testId = readU32(payload, 0);
  const uint32_t sequence = readU32(payload, 4);
  const uint32_t token = readU32(payload, 12);
  if (fieldTest.state != FieldTestState::Running ||
      testId != fieldTest.testId ||
      message.origin != fieldTest.targetNodeId) {
    return;
  }

  const int index = findDiagPending(sequence, token);
  if (index < 0) return;

  DiagPendingProbe& pending = diagPending[index];
  if (pending.actualTxStartedAtMs == 0 || pending.actualTxCompletedAtMs == 0) return;
  pending.pongReceivedAtMs = millis();
  const uint32_t rtt = pending.pongReceivedAtMs - pending.actualTxStartedAtMs;
  pending.used = false;
  fieldTest.endToEndReplies++;
  fieldTest.rttSumMs += rtt;
  if (rtt < fieldTest.rttMinMs) fieldTest.rttMinMs = rtt;
  if (rtt > fieldTest.rttMaxMs) fieldTest.rttMaxMs = rtt;

  uint8_t event[16];
  writeU32(event, 0, testId);
  writeU32(event, 4, sequence);
  writeU32(event, 8, rtt);
  writeU32(event, 12, fieldTest.endToEndReplies);
  emitBleEvent(EVT_TEST_PONG_RECEIVED, event, sizeof(event));

  Serial.printf(
    "[DIAG PONG] from=%08lX test=%08lX seq=%lu RTT=%lums\r\n",
    static_cast<unsigned long>(message.origin),
    static_cast<unsigned long>(testId),
    static_cast<unsigned long>(sequence),
    static_cast<unsigned long>(rtt)
  );
}

void processFieldTest() {
  if (fieldTest.state != FieldTestState::Running) return;
  const uint32_t now = millis();

  for (auto& pending : diagPending) {
    if (!pending.used || pending.actualTxCompletedAtMs == 0 ||
        pending.deadlineAtMs == 0 || !timeReached(now, pending.deadlineAtMs)) continue;
    const uint32_t seq = pending.sequence;
    pending.used = false;
    fieldTest.endToEndTimeouts++;
    uint8_t event[12];
    writeU32(event, 0, fieldTest.testId);
    writeU32(event, 4, seq);
    writeU32(event, 8, fieldTest.endToEndTimeouts);
    emitBleEvent(EVT_TEST_PACKET_TIMEOUT, event, sizeof(event));
  }

  if (fieldTest.currentSequence < fieldTest.requestedPackets &&
      timeReached(now, fieldTest.nextSendAtMs)) {
    const int pendingIndex = findFreeDiagPending();
    if (pendingIndex >= 0 && countUsedTxEntries() < MAX_TX_QUEUE - 2) {
      uint32_t nextHop = 0;
      RouteSource source = RouteSource::None;
      const RoutePolicy policy = fieldTest.mode == FieldTestMode::DirectOnly
        ? RoutePolicy::DirectOnly : RoutePolicy::Routed;

      if (!resolveNextHop(fieldTest.targetNodeId, nextHop, source, policy)) {
        fieldTest.state = FieldTestState::Error;
        fieldTest.finishedAtMs = now;
        emitFieldTestFinishedEvent(CommandStatus::NoRoute);
        uint8_t errorEvent[6];
        errorEvent[0] = 1; // context: FIELD_TEST
        errorEvent[1] = static_cast<uint8_t>(CommandStatus::NoRoute);
        writeU32(errorEvent, 2, fieldTest.testId);
        emitBleEvent(EVT_ERROR, errorEvent, sizeof(errorEvent));
        Serial.println("[TEST ERROR] route disappeared");
        setLastEvent("TEST NO ROUTE");
        return;
      }

      const uint32_t sequence = fieldTest.currentSequence + 1;
      uint32_t token = esp_random();
      if (token == 0) token = 1;
      uint8_t payload[MAX_APP_PAYLOAD] {};
      writeU32(payload, 0, fieldTest.testId);
      writeU32(payload, 4, sequence);
      writeU32(payload, 8, now);
      writeU32(payload, 12, token);
      for (uint8_t i = DIAG_HEADER_SIZE; i < fieldTest.payloadSize; ++i) {
        payload[i] = static_cast<uint8_t>((sequence + i * 31U) & 0xFFU);
      }

      QueuedMessageMeta meta;
      const QueueMessageResult result = queueApplicationMessage(
        MessageType::DiagPing,
        fieldTest.targetNodeId,
        payload,
        fieldTest.payloadSize,
        false,
        policy,
        &meta
      );

      if (result == QueueMessageResult::Ok) {
        fieldTest.currentSequence = sequence;
        fieldTest.lastNextHop = meta.nextHop;
        fieldTest.lastRouteSource = meta.routeSource;
        fieldTest.nextSendAtMs = now + fieldTest.intervalMs;

        DiagPendingProbe& pending = diagPending[pendingIndex];
        pending.used = true;
        pending.sequence = sequence;
        pending.token = token;
        pending.queuedAtMs = now;
        pending.actualTxStartedAtMs = 0;
        pending.actualTxCompletedAtMs = 0;
        pending.pongReceivedAtMs = 0;
        pending.deadlineAtMs = 0;

        if (meta.txQueueIndex >= 0 &&
            meta.txQueueIndex < static_cast<int>(MAX_TX_QUEUE)) {
          TxEntry& entry = txQueue[meta.txQueueIndex];
          entry.fieldTestOrigin = true;
          entry.fieldTestId = fieldTest.testId;
          entry.fieldTestSequence = sequence;
        }

      } else if (result != QueueMessageResult::TxQueueFull) {
        fieldTest.state = FieldTestState::Error;
        fieldTest.finishedAtMs = now;
        const CommandStatus commandError = mapQueueResult(result);
        emitFieldTestFinishedEvent(commandError);
        uint8_t errorEvent[6];
        errorEvent[0] = 1; // context: FIELD_TEST
        errorEvent[1] = static_cast<uint8_t>(commandError);
        writeU32(errorEvent, 2, fieldTest.testId);
        emitBleEvent(EVT_ERROR, errorEvent, sizeof(errorEvent));
        Serial.printf("[TEST ERROR] queue result=%u\r\n", static_cast<unsigned>(result));
        setLastEvent("TEST ERROR");
        return;
      }
    }
  }

  if (now - fieldTest.lastProgressEventAtMs >= 1000) {
    fieldTest.lastProgressEventAtMs = now;
    uint8_t event[16];
    writeU32(event, 0, fieldTest.testId);
    writeU32(event, 4, fieldTest.sent);
    writeU32(event, 8, fieldTest.endToEndReplies);
    writeU32(event, 12, fieldTest.endToEndTimeouts);
    emitBleEvent(EVT_TEST_PROGRESS, event, sizeof(event));
  }

  if (fieldTest.currentSequence >= fieldTest.requestedPackets && countDiagPending() == 0) {
    finishFieldTest();
  }
}

// -------------------- BLE Protocol v2 --------------------

constexpr uint16_t BLE_PROTOCOL_MAGIC = 0x4D53; // LE bytes 53 4D = "SM"
constexpr uint8_t BLE_PROTOCOL_VERSION = 2;
constexpr uint16_t BLE_FRAGMENT_MAGIC = 0x4653; // LE bytes 53 46 = "SF"
constexpr uint8_t BLE_FRAGMENT_VERSION = 1;
constexpr uint16_t BLE_APP_HEADER_SIZE = 10;
constexpr uint16_t BLE_FRAGMENT_HEADER_SIZE = 12;
constexpr uint16_t BLE_MAX_APP_PACKET = 384;
constexpr uint16_t BLE_MAX_FRAGMENT_DATA = 180;
constexpr uint8_t BLE_MAX_FRAGMENTS = 48;
constexpr uint32_t BLE_REASSEMBLY_TIMEOUT_MS = 3000;
constexpr uint32_t BLE_PASSKEY_LIFETIME_MS = 45000;
constexpr uint8_t BLE_PAIR_FAILURE_LIMIT = 3;
constexpr uint32_t BLE_PAIR_COOLDOWN_MS = 30000;
constexpr uint32_t BLE_PAIRING_UI_FALLBACK_MS = 420;
constexpr uint32_t BLE_SECURITY_RECONCILE_MS = 80;
constexpr uint16_t BLE_PREFERRED_MTU = 185;
constexpr char BLE_DEVICE_NAME[] = "SecureMesh";

static_assert(BLE_APP_HEADER_SIZE + COMMAND_RESULT_MAX_PAYLOAD <= BLE_MAX_APP_PACKET,
              "BLE command result cannot fit in one application packet");

uint16_t bleMinU16(uint16_t a, uint16_t b) { return a < b ? a : b; }

constexpr char BLE_SERVICE_UUID[]  = "7b7f0001-6b6f-4d65-7368-534543555245";
constexpr char BLE_INFO_UUID[]     = "7b7f0002-6b6f-4d65-7368-534543555245";
constexpr char BLE_COMMAND_UUID[]  = "7b7f0003-6b6f-4d65-7368-534543555245";
constexpr char BLE_RESPONSE_UUID[] = "7b7f0004-6b6f-4d65-7368-534543555245";
constexpr char BLE_EVENT_UUID[]    = "7b7f0005-6b6f-4d65-7368-534543555245";

enum class BlePacketType : uint8_t {
  Command = 1,
  Response = 2,
  Event = 3
};

enum class BleState : uint8_t {
  Off = 0,
  Advertising = 1,
  Connected = 2,
  Pairing = 3,
  SecureLink = 4,
  ProtocolReady = 5,
  Disconnecting = 6,
  Error = 7
};

const char* bleStateText(BleState state) {
  switch (state) {
    case BleState::Off: return "OFF";
    case BleState::Advertising: return "ADVERTISING";
    case BleState::Connected: return "CONNECTED";
    case BleState::Pairing: return "PAIRING";
    case BleState::SecureLink: return "SECURE_LINK";
    case BleState::ProtocolReady: return "PROTOCOL_READY";
    case BleState::Disconnecting: return "DISCONNECTING";
    case BleState::Error: return "ERROR";
  }
  return "UNKNOWN";
}

struct BleRawPacketSlot {
  bool used = false;
  uint16_t length = 0;
  uint8_t bytes[BLE_MAX_APP_PACKET] {};
};

constexpr size_t BLE_COMMAND_QUEUE_SIZE = 4;
constexpr size_t BLE_OUT_QUEUE_SIZE = 6;
BleRawPacketSlot bleCommandQueue[BLE_COMMAND_QUEUE_SIZE];
BleRawPacketSlot bleOutQueue[BLE_OUT_QUEUE_SIZE];

struct BleRingState {
  size_t head = 0;
  size_t tail = 0;
  size_t count = 0;
};

BleRingState bleCommandRing;
BleRingState bleOutRing;

struct BleReassemblyState {
  bool active = false;
  uint16_t transportId = 0;
  uint8_t fragmentCount = 0;
  uint8_t nextFragmentIndex = 0;
  uint16_t totalLength = 0;
  uint16_t bytesReceived = 0;
  uint32_t startedAtMs = 0;
  uint8_t buffer[BLE_MAX_APP_PACKET] {};
};
BleReassemblyState bleRxAssembly;

struct BleOutTransportState {
  bool active = false;
  int queueIndex = -1;
  uint16_t transportId = 1;
  uint16_t offset = 0;
  uint8_t fragmentIndex = 0;
  uint8_t fragmentCount = 0;
  uint32_t nextFragmentAtMs = 0;
};
BleOutTransportState bleOutTransport;
uint16_t nextBleTransportId = 1;

portMUX_TYPE bleQueueMux = portMUX_INITIALIZER_UNLOCKED;
NimBLEServer* bleServer = nullptr;
NimBLECharacteristic* bleInfoCharacteristic = nullptr;
NimBLECharacteristic* bleCommandCharacteristic = nullptr;
NimBLECharacteristic* bleResponseCharacteristic = nullptr;
NimBLECharacteristic* bleEventCharacteristic = nullptr;

bool bleInitialized = false;
bool bleAdvertisingEnabled = true;
volatile bool bleConnectedFlag = false;
volatile bool bleAuthCompleteFlag = false;
volatile bool bleAuthSuccessFlag = false;
volatile bool bleDisconnectFlag = false;
volatile bool bleMtuChangedFlag = false;
volatile uint16_t bleConnectionHandle = BLE_HS_CONN_HANDLE_NONE;
volatile uint16_t bleRejectConnectionHandle = BLE_HS_CONN_HANDLE_NONE;
volatile uint16_t bleNegotiatedMtu = 23;
volatile uint32_t bleActivePasskey = 0;
volatile uint32_t blePasskeyDeadlineAtMs = 0;
volatile bool bleCurrentBondedFlag = false;
volatile bool blePasskeyPreparedFlag = false;
volatile bool blePasskeyDisplayRequestedFlag = false;
volatile bool blePairingUiVisibleFlag = false;
volatile bool blePairingUiRefreshFlag = false;
volatile bool bleIdentityResolvedFlag = false;
volatile bool bleIdentityBondedFlag = false;
volatile uint32_t blePairingSecurityStartedAtMs = 0;
volatile BleState bleState = BleState::Off;
bool bleSecurityRequested = false;
uint32_t bleLastSecurityReconcileAtMs = 0;
uint8_t blePairFailureCount = 0;
uint32_t bleCooldownUntilMs = 0;
uint32_t bleConnectedBannerUntilMs = 0;
uint32_t statBleMalformed = 0;
uint32_t statBleDropped = 0;
uint32_t statBleCommands = 0;

// -------------------- Passive BLE activity radar --------------------
// This is presence/activity sensing only. It never connects to discovered
// devices and does not attempt to identify a person. Addresses are reduced to
// a short non-reversible session hash before they leave the firmware.
constexpr size_t BLE_RADAR_MAX_DEVICES = 10;
static_assert(BLE_RADAR_HEADER_BYTES +
                BLE_RADAR_MAX_DEVICES * BLE_RADAR_RECORD_BYTES <=
              COMMAND_RESULT_MAX_PAYLOAD,
              "BLE radar snapshot no longer fits command result");
constexpr uint32_t BLE_RADAR_SCAN_DURATION_MS = 1100;
constexpr uint32_t BLE_RADAR_SCAN_PERIOD_MS = 5200;
constexpr uint32_t BLE_RADAR_STALE_MS = 45000;
constexpr uint16_t BLE_RADAR_SCAN_INTERVAL_MS = 120;
constexpr uint16_t BLE_RADAR_SCAN_WINDOW_MS = 45;

struct BleRadarEntry {
  bool used = false;
  uint32_t addressHash = 0;
  uint32_t firstSeenAtMs = 0;
  uint32_t lastSeenAtMs = 0;
  int16_t rssiTenths = -1270;
  int16_t baselineTenths = -1270;
  int8_t peakRssi = -127;
  uint16_t hits = 0;
  char name[13] {};
};

BleRadarEntry bleRadarEntries[BLE_RADAR_MAX_DEVICES];
portMUX_TYPE bleRadarMux = portMUX_INITIALIZER_UNLOCKED;
uint32_t bleRadarNextScanAtMs = 0;
uint32_t bleRadarScanCycle = 0;
uint32_t bleRadarTotalDetections = 0;
bool bleRadarConfigured = false;

uint32_t bleRadarHashAddress(const std::string& address) {
  uint32_t h = 2166136261UL;
  for (const char c : address) {
    h ^= static_cast<uint8_t>(c);
    h *= 16777619UL;
  }
  return h == 0 ? 1 : h;
}

void recordBleRadarDevice(const NimBLEAdvertisedDevice* device) {
  if (device == nullptr) return;
  const std::string address = device->getAddress().toString();
  const std::string advertisedName = device->getName();
  const uint32_t hash = bleRadarHashAddress(address);
  const int8_t sampleRssi = device->getRSSI();
  const uint32_t now = millis();

  char safeName[13] {};
  size_t nameLen = 0;
  for (size_t i = 0; i < advertisedName.size() && nameLen < sizeof(safeName) - 1; ++i) {
    const uint8_t c = static_cast<uint8_t>(advertisedName[i]);
    if (c >= 32 && c <= 126) safeName[nameLen++] = static_cast<char>(c);
  }
  safeName[nameLen] = '\0';

  portENTER_CRITICAL(&bleRadarMux);
  int slot = -1;
  uint32_t oldestAge = 0;
  int oldestSlot = 0;
  for (size_t i = 0; i < BLE_RADAR_MAX_DEVICES; ++i) {
    BleRadarEntry& e = bleRadarEntries[i];
    if (e.used && e.addressHash == hash) { slot = static_cast<int>(i); break; }
    const uint32_t age = e.used ? now - e.lastSeenAtMs : UINT32_MAX;
    if (!e.used) { oldestSlot = static_cast<int>(i); oldestAge = UINT32_MAX; }
    else if (age > oldestAge && oldestAge != UINT32_MAX) { oldestAge = age; oldestSlot = static_cast<int>(i); }
  }
  if (slot < 0) slot = oldestSlot;

  BleRadarEntry& e = bleRadarEntries[slot];
  const bool isNew = !e.used || e.addressHash != hash;
  if (isNew) {
    e = BleRadarEntry{};
    e.used = true;
    e.addressHash = hash;
    e.firstSeenAtMs = now;
    e.rssiTenths = static_cast<int16_t>(sampleRssi) * 10;
    e.baselineTenths = e.rssiTenths;
    e.peakRssi = sampleRssi;
  } else {
    const int16_t sampleTenths = static_cast<int16_t>(sampleRssi) * 10;
    e.rssiTenths = static_cast<int16_t>((e.rssiTenths * 3 + sampleTenths) / 4);
    e.baselineTenths = static_cast<int16_t>((e.baselineTenths * 9 + sampleTenths) / 10);
    if (sampleRssi > e.peakRssi) e.peakRssi = sampleRssi;
  }
  e.lastSeenAtMs = now;
  if (e.hits < 65535) e.hits++;
  if (safeName[0] != '\0') {
    strncpy(e.name, safeName, sizeof(e.name) - 1);
    e.name[sizeof(e.name) - 1] = '\0';
  }
  bleRadarTotalDetections++;
  portEXIT_CRITICAL(&bleRadarMux);
}

class SecureMeshBleRadarCallbacks : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* device) override {
    recordBleRadarDevice(device);
  }
};

SecureMeshBleRadarCallbacks secureMeshBleRadarCallbacks;

bool configureBleRadarScanner() {
  NimBLEScan* scan = NimBLEDevice::getScan();
  if (scan == nullptr) return false;
  scan->setScanCallbacks(&secureMeshBleRadarCallbacks, true);
  scan->setActiveScan(false);
  scan->setInterval(BLE_RADAR_SCAN_INTERVAL_MS);
  scan->setWindow(BLE_RADAR_SCAN_WINDOW_MS);
  scan->setMaxResults(0);
  scan->setDuplicateFilter(0);
  bleRadarConfigured = true;
  bleRadarNextScanAtMs = millis() + 1200;
  return true;
}

void clearBleRadar() {
  portENTER_CRITICAL(&bleRadarMux);
  for (auto& entry : bleRadarEntries) entry = BleRadarEntry{};
  bleRadarTotalDetections = 0;
  portEXIT_CRITICAL(&bleRadarMux);
}

void processBleRadar() {
  if (!bleInitialized || !bleRadarConfigured) return;
  NimBLEScan* scan = NimBLEDevice::getScan();
  if (scan == nullptr) return;

  // Keep pairing/advertising deterministic. Passive scanning is enabled only
  // after the commander app has an authenticated BLE session.
  if (bleState != BleState::ProtocolReady || !bleAuthSuccessFlag) {
    if (scan->isScanning()) scan->stop();
    return;
  }

  const uint32_t now = millis();
  if (scan->isScanning()) return;
  if (!timeReached(now, bleRadarNextScanAtMs)) return;

  bleRadarScanCycle++;
  const bool started = scan->start(BLE_RADAR_SCAN_DURATION_MS, false, true);
  bleRadarNextScanAtMs = now + (started ? BLE_RADAR_SCAN_PERIOD_MS : 1800);
}


void clearBlePasskey() {
  bleActivePasskey = 0;
  blePasskeyDeadlineAtMs = 0;
  blePasskeyPreparedFlag = false;
  blePasskeyDisplayRequestedFlag = false;
  blePairingUiVisibleFlag = false;
  blePairingSecurityStartedAtMs = 0;
  blePairingUiRefreshFlag = true;
}

void resetBleSessionTransport() {
  portENTER_CRITICAL(&bleQueueMux);
  bleRxAssembly = BleReassemblyState{};
  for (auto& slot : bleCommandQueue) slot = BleRawPacketSlot{};
  for (auto& slot : bleOutQueue) slot = BleRawPacketSlot{};
  bleCommandRing = BleRingState{};
  bleOutRing = BleRingState{};
  portEXIT_CRITICAL(&bleQueueMux);
  bleOutTransport = BleOutTransportState{};
}

void setBleState(BleState newState) {
  if (bleState == newState) return;
  bleState = newState;
  uint8_t stateByte = static_cast<uint8_t>(newState);
  emitBleEvent(EVT_BLE_STATE, &stateByte, 1);
}

bool enqueueBleRawPacket(
  BleRawPacketSlot* queue,
  BleRingState& ring,
  size_t queueSize,
  const uint8_t* data,
  uint16_t length
) {
  if (queue == nullptr || data == nullptr || queueSize == 0 ||
      length == 0 || length > BLE_MAX_APP_PACKET) return false;

  bool queued = false;
  portENTER_CRITICAL(&bleQueueMux);
  if (ring.count < queueSize) {
    BleRawPacketSlot& slot = queue[ring.tail];
    slot.used = true;
    slot.length = length;
    memcpy(slot.bytes, data, length);
    ring.tail = (ring.tail + 1U) % queueSize;
    ring.count++;
    queued = true;
  }
  portEXIT_CRITICAL(&bleQueueMux);
  if (!queued) statBleDropped++;
  return queued;
}

bool dequeueBleRawPacket(
  BleRawPacketSlot* queue,
  BleRingState& ring,
  size_t queueSize,
  uint8_t* out,
  uint16_t& outLength
) {
  if (queue == nullptr || out == nullptr || queueSize == 0) return false;
  bool dequeued = false;
  portENTER_CRITICAL(&bleQueueMux);
  if (ring.count > 0) {
    BleRawPacketSlot& slot = queue[ring.head];
    if (slot.used && slot.length > 0 && slot.length <= BLE_MAX_APP_PACKET) {
      outLength = slot.length;
      memcpy(out, slot.bytes, slot.length);
      slot = BleRawPacketSlot{};
      ring.head = (ring.head + 1U) % queueSize;
      ring.count--;
      dequeued = true;
    }
  }
  portEXIT_CRITICAL(&bleQueueMux);
  return dequeued;
}

int peekBleOutQueueIndex() {
  int index = -1;
  portENTER_CRITICAL(&bleQueueMux);
  if (bleOutRing.count > 0 && bleOutQueue[bleOutRing.head].used) {
    index = static_cast<int>(bleOutRing.head);
  }
  portEXIT_CRITICAL(&bleQueueMux);
  return index;
}

void releaseBleOutQueueHead(int expectedIndex) {
  if (expectedIndex < 0 || expectedIndex >= static_cast<int>(BLE_OUT_QUEUE_SIZE)) return;
  portENTER_CRITICAL(&bleQueueMux);
  if (bleOutRing.count > 0 &&
      bleOutRing.head == static_cast<size_t>(expectedIndex) &&
      bleOutQueue[bleOutRing.head].used) {
    bleOutQueue[bleOutRing.head] = BleRawPacketSlot{};
    bleOutRing.head = (bleOutRing.head + 1U) % BLE_OUT_QUEUE_SIZE;
    bleOutRing.count--;
  }
  portEXIT_CRITICAL(&bleQueueMux);
}

uint16_t buildBleApplicationPacket(
  BlePacketType packetType,
  uint16_t requestId,
  uint8_t opcode,
  CommandStatus status,
  const uint8_t* payload,
  uint16_t payloadLength,
  uint8_t* out,
  size_t capacity
) {
  if (out == nullptr || payloadLength > COMMAND_RESULT_MAX_PAYLOAD ||
      static_cast<size_t>(BLE_APP_HEADER_SIZE + payloadLength) > capacity) return 0;
  writeU16(out, 0, BLE_PROTOCOL_MAGIC);
  out[2] = BLE_PROTOCOL_VERSION;
  out[3] = static_cast<uint8_t>(packetType);
  writeU16(out, 4, requestId);
  out[6] = opcode;
  out[7] = static_cast<uint8_t>(status);
  writeU16(out, 8, payloadLength);
  if (payloadLength > 0 && payload != nullptr) {
    memcpy(out + BLE_APP_HEADER_SIZE, payload, payloadLength);
  }
  return static_cast<uint16_t>(BLE_APP_HEADER_SIZE + payloadLength);
}

void emitBleEvent(uint8_t eventType, const uint8_t* payload, uint16_t length) {
  if (!bleInitialized || bleState != BleState::ProtocolReady ||
      length > COMMAND_RESULT_MAX_PAYLOAD) return;
  uint8_t packet[BLE_MAX_APP_PACKET];
  const uint16_t packetLength = buildBleApplicationPacket(
    BlePacketType::Event, 0, eventType, CommandStatus::Ok,
    payload, length, packet, sizeof(packet)
  );
  if (packetLength > 0) enqueueBleRawPacket(bleOutQueue, bleOutRing, BLE_OUT_QUEUE_SIZE, packet, packetLength);
}

bool parseBleCommandPacket(const uint8_t* packet, uint16_t length, CommandRequest& request) {
  if (packet == nullptr || length < BLE_APP_HEADER_SIZE ||
      length > BLE_MAX_APP_PACKET ||
      readU16(packet, 0) != BLE_PROTOCOL_MAGIC ||
      packet[2] != BLE_PROTOCOL_VERSION ||
      packet[3] != static_cast<uint8_t>(BlePacketType::Command)) {
    return false;
  }
  const uint16_t payloadLength = readU16(packet, 8);
  if (BLE_APP_HEADER_SIZE + payloadLength != length) return false;
  request = CommandRequest{};
  request.source = CommandSource::Ble;
  request.requestId = readU16(packet, 4);
  request.type = static_cast<CommandType>(packet[6]);
  if (packet[7] != 0) return false;

  const uint8_t* payload = packet + BLE_APP_HEADER_SIZE;
  switch (request.type) {
    case CommandType::GetInfo:
    case CommandType::GetStatus:
    case CommandType::GetNeighbors:
    case CommandType::GetRoutes:
    case CommandType::StopFieldTest:
    case CommandType::GetFieldTestStatus:
    case CommandType::PingLocal:
    case CommandType::ClearStats:
    case CommandType::GetUiState:
    case CommandType::GetKnownNodes:
    case CommandType::GetManifest:
    case CommandType::GetRoutingDiagnostics:
    case CommandType::ClearDynamicRoutes:
    case CommandType::GetLabLinkPolicies:
    case CommandType::GetPositions:
    case CommandType::GetBleRadar:
    case CommandType::ClearBleRadar:
    case CommandType::GetOperationalHealth:
    case CommandType::GetSelfDiagnostics:
      return payloadLength == 0;

    case CommandType::UiAction:
      if (payloadLength != 1) return false;
      request.uiAction = payload[0];
      return true;

    case CommandType::GetOledFrameChunk:
      if (payloadLength != 1 || payload[0] >= BLE_OLED_FRAME_CHUNK_COUNT) return false;
      request.oledChunkIndex = payload[0];
      return true;

    case CommandType::SendMessage:
      if (payloadLength < 6) return false;
      request.destination = readU32(payload, 0);
      request.dataLength = payload[4];
      if (request.dataLength == 0 || request.dataLength > MAX_APP_PAYLOAD ||
          static_cast<uint16_t>(5 + request.dataLength) != payloadLength) return false;
      memcpy(request.data, payload + 5, request.dataLength);
      return true;

    case CommandType::AddStaticRoute:
      if (payloadLength != 8) return false;
      request.destination = readU32(payload, 0);
      request.nextHop = readU32(payload, 4);
      return true;

    case CommandType::RemoveStaticRoute:
      if (payloadLength != 4) return false;
      request.destination = readU32(payload, 0);
      return true;

    case CommandType::StartFieldTest:
      if (payloadLength != 12) return false;
      request.destination = readU32(payload, 0);
      request.packetCount = readU16(payload, 4);
      request.intervalMs = readU32(payload, 6);
      request.payloadSize = payload[10];
      if (payload[11] > static_cast<uint8_t>(FieldTestMode::DirectOnly)) return false;
      request.testMode = static_cast<FieldTestMode>(payload[11]);
      return true;

    case CommandType::SetManifest:
      if (payloadLength < 9 || payloadLength > sizeof(request.data)) return false;
      request.dataLength = static_cast<uint8_t>(payloadLength);
      memcpy(request.data, payload, payloadLength);
      return true;

    case CommandType::DiscoverRoute:
      if (payloadLength != 5) return false;
      request.destination = readU32(payload, 0);
      request.flag = (payload[4] & 0x01) != 0;
      return true;

    case CommandType::InjectLinkFailure:
      if (payloadLength != 4 && payloadLength != 8) return false;
      request.nextHop = readU32(payload, 0);
      request.intervalMs = payloadLength == 8
        ? readU32(payload, 4) : DEFAULT_LAB_LINK_FAULT_MS;
      return true;

    case CommandType::SetLabLinkPolicy:
      // peer u32, flags u8, durationMs u32, reliabilityQ15 u16, ecaQ16 u32
      if (payloadLength != 15 || payloadLength > sizeof(request.data)) return false;
      request.dataLength = static_cast<uint8_t>(payloadLength);
      memcpy(request.data, payload, payloadLength);
      return true;


    case CommandType::RaiseSos:
      if (payloadLength != 1) return false;
      request.data[0] = payload[0]; request.dataLength = 1;
      return true;

    case CommandType::AckSos:
      if (payloadLength != 8) return false;
      request.dataLength = 8; memcpy(request.data, payload, 8);
      return true;

    case CommandType::SendCommandNotice:
      if (payloadLength != 13) return false;
      request.destination = readU32(payload, 0);
      request.dataLength = 9; memcpy(request.data, payload + 4, 9);
      return true;

    default:
      return true; // dispatcher returns INVALID_COMMAND for unknown opcode.
  }
}

void acceptBleFragment(
  uint16_t connHandle,
  bool encrypted,
  bool authenticated,
  const uint8_t* data,
  size_t length
) {
  if (!bleConnectedFlag || connHandle != bleConnectionHandle ||
      bleConnectionHandle == BLE_HS_CONN_HANDLE_NONE ||
      !bleAuthSuccessFlag || bleState != BleState::ProtocolReady ||
      !encrypted || !authenticated) {
    statBleDropped++;
    return;
  }

  if (data == nullptr || length < BLE_FRAGMENT_HEADER_SIZE ||
      length > BLE_FRAGMENT_HEADER_SIZE + BLE_MAX_FRAGMENT_DATA) {
    statBleMalformed++;
    return;
  }

  if (readU16(data, 0) != BLE_FRAGMENT_MAGIC || data[2] != BLE_FRAGMENT_VERSION) {
    statBleMalformed++;
    return;
  }

  const uint16_t transportId = readU16(data, 3);
  const uint8_t fragmentIndex = data[5];
  const uint8_t fragmentCount = data[6];
  const uint16_t totalLength = readU16(data, 7);
  const uint16_t offset = readU16(data, 9);
  const uint8_t fragmentLength = data[11];

  if (transportId == 0 || fragmentCount == 0 || fragmentCount > BLE_MAX_FRAGMENTS ||
      fragmentIndex >= fragmentCount || totalLength < BLE_APP_HEADER_SIZE ||
      totalLength > BLE_MAX_APP_PACKET || fragmentLength == 0 ||
      static_cast<size_t>(BLE_FRAGMENT_HEADER_SIZE + fragmentLength) != length ||
      offset + fragmentLength > totalLength) {
    statBleMalformed++;
    return;
  }

  const uint32_t now = millis();
  portENTER_CRITICAL(&bleQueueMux);
  if (bleRxAssembly.active &&
      now - bleRxAssembly.startedAtMs > BLE_REASSEMBLY_TIMEOUT_MS) {
    bleRxAssembly = BleReassemblyState{};
  }

  if (!bleRxAssembly.active) {
    if (fragmentIndex != 0 || offset != 0) {
      portEXIT_CRITICAL(&bleQueueMux);
      statBleMalformed++;
      return;
    }
    bleRxAssembly.active = true;
    bleRxAssembly.transportId = transportId;
    bleRxAssembly.fragmentCount = fragmentCount;
    bleRxAssembly.nextFragmentIndex = 0;
    bleRxAssembly.totalLength = totalLength;
    bleRxAssembly.bytesReceived = 0;
    bleRxAssembly.startedAtMs = now;
  }

  if (bleRxAssembly.transportId != transportId ||
      bleRxAssembly.fragmentCount != fragmentCount ||
      bleRxAssembly.totalLength != totalLength ||
      fragmentIndex != bleRxAssembly.nextFragmentIndex ||
      offset != bleRxAssembly.bytesReceived) {
    portEXIT_CRITICAL(&bleQueueMux);
    statBleMalformed++;
    return;
  }

  memcpy(bleRxAssembly.buffer + offset, data + BLE_FRAGMENT_HEADER_SIZE, fragmentLength);
  bleRxAssembly.bytesReceived += fragmentLength;
  bleRxAssembly.nextFragmentIndex++;

  const bool complete = bleRxAssembly.nextFragmentIndex == fragmentCount;
  uint16_t completedLength = 0;
  uint8_t completed[BLE_MAX_APP_PACKET];
  if (complete) {
    if (bleRxAssembly.bytesReceived == bleRxAssembly.totalLength) {
      completedLength = bleRxAssembly.totalLength;
      memcpy(completed, bleRxAssembly.buffer, completedLength);
    }
    bleRxAssembly = BleReassemblyState{};
  }
  portEXIT_CRITICAL(&bleQueueMux);

  if (complete) {
    if (completedLength == 0 ||
        !enqueueBleRawPacket(bleCommandQueue, bleCommandRing, BLE_COMMAND_QUEUE_SIZE, completed, completedLength)) {
      statBleMalformed++;
    }
  }
}

bool isActiveBleConnection(const NimBLEConnInfo& connInfo) {
  return bleConnectedFlag &&
    bleConnectionHandle != BLE_HS_CONN_HANDLE_NONE &&
    connInfo.getConnHandle() == bleConnectionHandle;
}

bool isReadySecureBleConnection(const NimBLEConnInfo& connInfo) {
  return isActiveBleConnection(connInfo) &&
    bleAuthSuccessFlag &&
    bleState == BleState::ProtocolReady &&
    connInfo.isEncrypted() &&
    connInfo.isAuthenticated();
}

uint32_t generateBlePasskey() {
  // Uniform 100000..999999 from ESP32 hardware RNG. Keeping the value non-zero
  // also avoids using 0 as the local "no active passkey" sentinel.
  constexpr uint32_t RANGE = 900000UL;
  constexpr uint32_t LIMIT = UINT32_MAX - (UINT32_MAX % RANGE);
  uint32_t randomValue = 0;
  do { randomValue = esp_random(); } while (randomValue >= LIMIT);
  return 100000UL + (randomValue % RANGE);
}

bool prepareBlePasskeyForActiveConnection(uint32_t now) {
  if (!bleConnectedFlag || bleConnectionHandle == BLE_HS_CONN_HANDLE_NONE) return false;

  const uint32_t passkey = generateBlePasskey();
  bleActivePasskey = passkey;
  // The countdown starts only when the user actually needs to see the code.
  // This prevents a bonded reconnect from flashing/sticking an unused passkey.
  blePasskeyDeadlineAtMs = 0;
  blePasskeyPreparedFlag = true;
  blePasskeyDisplayRequestedFlag = false;
  blePairingUiVisibleFlag = false;
  blePairingUiRefreshFlag = true;
  blePairingSecurityStartedAtMs = now;

  // One source of truth: the code configured in the Security Manager is the
  // same code the OLED will render if passkey entry is actually required.
  NimBLEDevice::setSecurityPasskey(passkey);
  return true;
}

void activateBlePairingUi(uint32_t now) {
  if (!blePasskeyPreparedFlag || bleActivePasskey == 0 ||
      bleState != BleState::Pairing || bleAuthSuccessFlag || bleAuthCompleteFlag) return;
  blePairingUiVisibleFlag = true;
  if (blePasskeyDeadlineAtMs == 0) blePasskeyDeadlineAtMs = now + BLE_PASSKEY_LIFETIME_MS;
  blePairingUiRefreshFlag = true;
}

void reconcileBleSecurityState(uint32_t now) {
  if (!bleConnectedFlag || bleServer == nullptr ||
      bleConnectionHandle == BLE_HS_CONN_HANDLE_NONE) return;
  if (now - bleLastSecurityReconcileAtMs < BLE_SECURITY_RECONCILE_MS) return;
  bleLastSecurityReconcileAtMs = now;

  NimBLEConnInfo info = bleServer->getPeerInfoByHandle(bleConnectionHandle);
  if (info.getConnHandle() != bleConnectionHandle) return;

  bleNegotiatedMtu = info.getMTU();
  if (info.isEncrypted() && info.isAuthenticated()) {
    bleCurrentBondedFlag = info.isBonded();
    // Reconcile callback ordering/races only while security establishment is
    // pending. Never re-fire auth-complete after PROTOCOL_READY.
    if (bleState == BleState::Connected || bleState == BleState::Pairing ||
        bleState == BleState::SecureLink) {
      bleAuthSuccessFlag = true;
      bleAuthCompleteFlag = true;
      blePairingUiVisibleFlag = false;
      blePairingUiRefreshFlag = true;
    }
  }
}

class SecureMeshBleServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    (void)server;
    if (bleConnectedFlag || bleConnectionHandle != BLE_HS_CONN_HANDLE_NONE) {
      bleRejectConnectionHandle = connInfo.getConnHandle();
      return;
    }
    bleConnectionHandle = connInfo.getConnHandle();
    bleNegotiatedMtu = connInfo.getMTU();
    // Do not use connInfo.isBonded() here as an authorization decision. On
    // Android with resolvable private addresses the identity may not be resolved
    // yet at onConnect(). The authoritative bonded state is captured after auth.
    bleCurrentBondedFlag = false;
    bleIdentityResolvedFlag = false;
    bleIdentityBondedFlag = false;
    blePasskeyPreparedFlag = false;
    blePasskeyDisplayRequestedFlag = false;
    blePairingUiVisibleFlag = false;
    blePairingUiRefreshFlag = true;
    blePairingSecurityStartedAtMs = 0;
    bleConnectedFlag = true;
    bleDisconnectFlag = false;
    bleAuthCompleteFlag = false;
    bleAuthSuccessFlag = false;
    bleSecurityRequested = false;
    clearBlePasskey();
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& connInfo, int reason) override {
    (void)server; (void)reason;
    if (connInfo.getConnHandle() != bleConnectionHandle) return;
    bleDisconnectFlag = true;
    bleConnectedFlag = false;
    bleAuthSuccessFlag = false;
    bleAuthCompleteFlag = false;
    clearBlePasskey();
  }

  void onIdentity(NimBLEConnInfo& connInfo) override {
    if (!isActiveBleConnection(connInfo)) return;
    bleIdentityResolvedFlag = true;
    bleIdentityBondedFlag = connInfo.isBonded();
    bleCurrentBondedFlag = connInfo.isBonded();
    blePairingUiRefreshFlag = true;
  }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo& connInfo) override {
    if (!isActiveBleConnection(connInfo)) return;
    bleNegotiatedMtu = mtu;
    bleMtuChangedFlag = true;
  }

  uint32_t onPassKeyDisplay() override {
    // No connInfo is supplied by NimBLE for this callback. We accept it only
    // while exactly one active SecureMesh connection is in PAIRING.
    if (!bleConnectedFlag || bleConnectionHandle == BLE_HS_CONN_HANDLE_NONE ||
        bleState != BleState::Pairing || bleAuthSuccessFlag) {
      statBleDropped++;
      return NimBLEDevice::getSecurityPasskey();
    }

    if (!blePasskeyPreparedFlag || bleActivePasskey == 0) {
      const uint32_t now = millis();
      const uint32_t passkey = generateBlePasskey();
      bleActivePasskey = passkey;
      blePasskeyPreparedFlag = true;
      blePairingSecurityStartedAtMs = now;
      NimBLEDevice::setSecurityPasskey(passkey);
    }

    // This callback is the strongest evidence that the peer actually needs
    // user passkey entry. The main loop starts the visible countdown.
    blePasskeyDisplayRequestedFlag = true;
    blePairingUiRefreshFlag = true;
    return bleActivePasskey;
  }

  void onAuthenticationComplete(NimBLEConnInfo& connInfo) override {
    if (!isActiveBleConnection(connInfo)) return;
    bleAuthSuccessFlag = connInfo.isEncrypted() && connInfo.isAuthenticated();
    bleCurrentBondedFlag = connInfo.isBonded();
    bleIdentityBondedFlag = connInfo.isBonded();
    bleAuthCompleteFlag = true;
    // Callbacks never render. They only invalidate the modal immediately so a
    // successful/bonded connection cannot leave a stale code on the OLED.
    blePairingUiVisibleFlag = false;
    blePairingUiRefreshFlag = true;
  }
};

class SecureMeshBleCommandCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& connInfo) override {
    if (!isReadySecureBleConnection(connInfo)) {
      statBleDropped++;
      return;
    }
    const NimBLEAttValue value = characteristic->getValue();
    if (value.size() == 0 || value.size() > BLE_FRAGMENT_HEADER_SIZE + BLE_MAX_FRAGMENT_DATA) {
      statBleMalformed++;
      return;
    }
    acceptBleFragment(
      connInfo.getConnHandle(),
      connInfo.isEncrypted(),
      connInfo.isAuthenticated(),
      value.data(),
      value.size()
    );
  }
};

SecureMeshBleServerCallbacks secureMeshBleServerCallbacks;
SecureMeshBleCommandCallbacks secureMeshBleCommandCallbacks;

bool startBleAdvertising() {
  if (!bleInitialized || !bleAdvertisingEnabled || bleConnectedFlag ||
      !timeReached(millis(), bleCooldownUntilMs)) return false;
  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  if (advertising == nullptr) return false;
  if (advertising->isAdvertising()) {
    setBleState(BleState::Advertising);
    return true;
  }
  const bool ok = advertising->start();
  setBleState(ok ? BleState::Advertising : BleState::Error);
  return ok;
}

void stopBleAdvertising() {
  if (!bleInitialized) return;
  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  if (advertising != nullptr && advertising->isAdvertising()) advertising->stop();
  if (!bleConnectedFlag) setBleState(BleState::Off);
}

void updateBleInfoCharacteristic();

bool initializeBle() {
  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setMTU(BLE_PREFERRED_MTU);
  NimBLEDevice::setSecurityAuth(true, true, true); // bonding + MITM + Secure Connections
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  if (!configureBleRadarScanner()) return false;

  bleServer = NimBLEDevice::createServer();
  if (bleServer == nullptr) return false;
  bleServer->setCallbacks(&secureMeshBleServerCallbacks, false);
  bleServer->advertiseOnDisconnect(false);

  NimBLEService* service = bleServer->createService(BLE_SERVICE_UUID);
  if (service == nullptr) return false;

  bleInfoCharacteristic = service->createCharacteristic(
    BLE_INFO_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_AUTHEN,
    64
  );
  bleCommandCharacteristic = service->createCharacteristic(
    BLE_COMMAND_UUID,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_AUTHEN,
    BLE_FRAGMENT_HEADER_SIZE + BLE_MAX_FRAGMENT_DATA
  );
  bleResponseCharacteristic = service->createCharacteristic(
    BLE_RESPONSE_UUID,
    NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ_AUTHEN,
    BLE_FRAGMENT_HEADER_SIZE + BLE_MAX_FRAGMENT_DATA
  );
  bleEventCharacteristic = service->createCharacteristic(
    BLE_EVENT_UUID,
    NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ_AUTHEN,
    BLE_FRAGMENT_HEADER_SIZE + BLE_MAX_FRAGMENT_DATA
  );
  if (bleInfoCharacteristic == nullptr || bleCommandCharacteristic == nullptr ||
      bleResponseCharacteristic == nullptr || bleEventCharacteristic == nullptr) return false;

  bleCommandCharacteristic->setCallbacks(&secureMeshBleCommandCallbacks);
  updateBleInfoCharacteristic();
  if (!bleServer->start()) return false;

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  if (advertising == nullptr) return false;
  advertising->enableScanResponse(true);
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setName(BLE_DEVICE_NAME);

  bleInitialized = true;
  bleAdvertisingEnabled = true;
  return startBleAdvertising();
}

void updateBleInfoCharacteristic() {
  if (bleInfoCharacteristic == nullptr) return;
  uint8_t payload[64];
  const uint16_t payloadLength = buildInfoPayload(payload, sizeof(payload));
  uint8_t packet[BLE_MAX_APP_PACKET];
  const uint16_t packetLength = buildBleApplicationPacket(
    BlePacketType::Response, 0, static_cast<uint8_t>(CommandType::GetInfo),
    CommandStatus::Ok, payload, payloadLength, packet, sizeof(packet)
  );
  if (packetLength > 0) bleInfoCharacteristic->setValue(packet, packetLength);
}

void processBleOutgoing() {
  if (!bleInitialized || bleState != BleState::ProtocolReady ||
      bleConnectionHandle == BLE_HS_CONN_HANDLE_NONE) return;
  const uint32_t now = millis();
  if (bleOutTransport.active && !timeReached(now, bleOutTransport.nextFragmentAtMs)) return;

  if (!bleOutTransport.active) {
    const int index = peekBleOutQueueIndex();
    if (index < 0) return;
    bleOutTransport = BleOutTransportState{};
    bleOutTransport.active = true;
    bleOutTransport.queueIndex = index;
    bleOutTransport.transportId = nextBleTransportId++;
    if (nextBleTransportId == 0) nextBleTransportId = 1;

    const uint16_t mtu = bleNegotiatedMtu < 23 ? 23 : bleNegotiatedMtu;
    const uint16_t attPayload = mtu > 3 ? mtu - 3 : 20;
    if (attPayload <= BLE_FRAGMENT_HEADER_SIZE) {
      releaseBleOutQueueHead(index);
      bleOutTransport = BleOutTransportState{};
      return;
    }
    const uint16_t dataPerFragment = bleMinU16(
      BLE_MAX_FRAGMENT_DATA,
      attPayload - BLE_FRAGMENT_HEADER_SIZE
    );
    const uint16_t total = bleOutQueue[index].length;
    bleOutTransport.fragmentCount = static_cast<uint8_t>(
      (total + dataPerFragment - 1) / dataPerFragment
    );
    if (bleOutTransport.fragmentCount == 0 || bleOutTransport.fragmentCount > BLE_MAX_FRAGMENTS) {
      releaseBleOutQueueHead(index);
      bleOutTransport = BleOutTransportState{};
      return;
    }
  }

  const int index = bleOutTransport.queueIndex;
  if (index < 0 || index >= static_cast<int>(BLE_OUT_QUEUE_SIZE) || !bleOutQueue[index].used) {
    bleOutTransport = BleOutTransportState{};
    return;
  }

  const uint16_t mtu = bleNegotiatedMtu < 23 ? 23 : bleNegotiatedMtu;
  const uint16_t dataCap = bleMinU16(
    BLE_MAX_FRAGMENT_DATA,
    (mtu - 3) - BLE_FRAGMENT_HEADER_SIZE
  );
  const uint16_t remaining = bleOutQueue[index].length - bleOutTransport.offset;
  const uint8_t fragmentLength = static_cast<uint8_t>(bleMinU16(remaining, dataCap));

  uint8_t fragment[BLE_FRAGMENT_HEADER_SIZE + BLE_MAX_FRAGMENT_DATA];
  writeU16(fragment, 0, BLE_FRAGMENT_MAGIC);
  fragment[2] = BLE_FRAGMENT_VERSION;
  writeU16(fragment, 3, bleOutTransport.transportId);
  fragment[5] = bleOutTransport.fragmentIndex;
  fragment[6] = bleOutTransport.fragmentCount;
  writeU16(fragment, 7, bleOutQueue[index].length);
  writeU16(fragment, 9, bleOutTransport.offset);
  fragment[11] = fragmentLength;
  memcpy(
    fragment + BLE_FRAGMENT_HEADER_SIZE,
    bleOutQueue[index].bytes + bleOutTransport.offset,
    fragmentLength
  );

  const uint8_t packetType = bleOutQueue[index].bytes[3];
  NimBLECharacteristic* characteristic = packetType == static_cast<uint8_t>(BlePacketType::Event)
    ? bleEventCharacteristic : bleResponseCharacteristic;
  const bool notified = characteristic != nullptr && characteristic->notify(
    fragment,
    BLE_FRAGMENT_HEADER_SIZE + fragmentLength,
    bleConnectionHandle
  );
  if (!notified) {
    // Notification buffers/subscription can be temporarily unavailable. Do not
    // lose protocol data or spin; retry this same fragment from the main loop.
    bleOutTransport.nextFragmentAtMs = now + 25;
    return;
  }

  bleOutTransport.offset += fragmentLength;
  bleOutTransport.fragmentIndex++;
  bleOutTransport.nextFragmentAtMs = now + 4;
  if (bleOutTransport.offset >= bleOutQueue[index].length) {
    releaseBleOutQueueHead(index);
    bleOutTransport = BleOutTransportState{};
  }
}

void processBle() {
  if (!bleInitialized) return;
  const uint32_t now = millis();

  reconcileBleSecurityState(now);

  if (bleRejectConnectionHandle != BLE_HS_CONN_HANDLE_NONE && bleServer != nullptr) {
    const uint16_t reject = bleRejectConnectionHandle;
    bleRejectConnectionHandle = BLE_HS_CONN_HANDLE_NONE;
    bleServer->disconnect(reject);
  }

  if (bleDisconnectFlag) {
    bleDisconnectFlag = false;
    bleSecurityRequested = false;
    clearBlePasskey();
    bleCurrentBondedFlag = false;
    bleIdentityResolvedFlag = false;
    bleIdentityBondedFlag = false;
    bleConnectionHandle = BLE_HS_CONN_HANDLE_NONE;
    resetBleSessionTransport();
    if (bleAdvertisingEnabled && timeReached(now, bleCooldownUntilMs)) startBleAdvertising();
    else setBleState(BleState::Off);
  }

  if (bleConnectedFlag && bleState == BleState::Advertising) {
    resetBleSessionTransport();
    setBleState(BleState::Connected);
  }

  if (bleConnectedFlag && !bleSecurityRequested &&
      timeReached(now, bleCooldownUntilMs) &&
      bleConnectionHandle != BLE_HS_CONN_HANDLE_NONE) {
    bleSecurityRequested = true;
    setBleState(BleState::Pairing);

    // Always prepare one random passkey before starting the Security Manager.
    // If this is a bonded reconnect, NimBLE will normally resume with stored
    // keys and ignore the prepared passkey; the OLED code is only exposed after
    // an actual passkey callback or a short fallback delay.
    if (!prepareBlePasskeyForActiveConnection(now)) {
      setBleState(BleState::Error);
      if (bleServer != nullptr) bleServer->disconnect(bleConnectionHandle);
      return;
    }
    Serial.println("[BLE] Security start: dynamic passkey prepared");

    if (!NimBLEDevice::startSecurity(bleConnectionHandle)) {
      clearBlePasskey();
      setBleState(BleState::Error);
      if (bleServer != nullptr) bleServer->disconnect(bleConnectionHandle);
    }
  }

  // Pairing UI ownership is explicit. Show the code immediately when NimBLE
  // asks for it. If that callback is delayed, use a bounded fallback only for
  // an unresolved/non-bonded peer that is still genuinely unauthenticated.
  if (bleState == BleState::Pairing && blePasskeyPreparedFlag &&
      !bleAuthSuccessFlag && !bleAuthCompleteFlag) {
    if (blePasskeyDisplayRequestedFlag && !blePairingUiVisibleFlag) {
      activateBlePairingUi(now);
      setLastEvent("BLE PAIR CODE");
    } else if (!blePairingUiVisibleFlag && blePairingSecurityStartedAtMs != 0 &&
               now - blePairingSecurityStartedAtMs >= BLE_PAIRING_UI_FALLBACK_MS &&
               !(bleIdentityResolvedFlag && bleIdentityBondedFlag)) {
      activateBlePairingUi(now);
      setLastEvent("BLE PAIR FALLBACK");
    }
  }

  if (blePairingUiVisibleFlag && bleState == BleState::Pairing &&
      !bleAuthSuccessFlag && bleActivePasskey != 0 &&
      blePasskeyDeadlineAtMs != 0 &&
      timeReached(now, blePasskeyDeadlineAtMs)) {
    uiShowToast("СОПРЯЖЕНИЕ", "ВРЕМЯ ИСТЕКЛО", 1600);
    clearBlePasskey();
    if (bleServer != nullptr && bleConnectionHandle != BLE_HS_CONN_HANDLE_NONE) {
      setBleState(BleState::Disconnecting);
      bleServer->disconnect(bleConnectionHandle);
    }
  }

  if (bleAuthCompleteFlag) {
    bleAuthCompleteFlag = false;
    blePairingUiVisibleFlag = false;
    clearBlePasskey();
    if (bleAuthSuccessFlag) {
      blePairFailureCount = 0;
      setBleState(BleState::SecureLink);
      setBleState(BleState::ProtocolReady);
      updateBleInfoCharacteristic();
      bleConnectedBannerUntilMs = now + UI_SUCCESS_ANIMATION_MS;
      setLastEvent("BLE CONNECTED");
    } else {
      uiShowToast("СОПРЯЖЕНИЕ", "КОД НЕ ПРИНЯТ", 1800);
      blePairFailureCount++;
      if (blePairFailureCount >= BLE_PAIR_FAILURE_LIMIT) {
        bleCooldownUntilMs = now + BLE_PAIR_COOLDOWN_MS;
        blePairFailureCount = 0;
        uiShowToast("BLE", "ПАУЗА 30 СЕК", 1800);
      }
      if (bleServer != nullptr && bleConnectionHandle != BLE_HS_CONN_HANDLE_NONE) {
        setBleState(BleState::Disconnecting);
        bleServer->disconnect(bleConnectionHandle);
      }
    }
  }

  if (bleRxAssembly.active && now - bleRxAssembly.startedAtMs > BLE_REASSEMBLY_TIMEOUT_MS) {
    portENTER_CRITICAL(&bleQueueMux);
    bleRxAssembly = BleReassemblyState{};
    portEXIT_CRITICAL(&bleQueueMux);
  }

  if (!bleConnectedFlag && bleAdvertisingEnabled &&
      bleState == BleState::Off && timeReached(now, bleCooldownUntilMs)) {
    startBleAdvertising();
  }

  processBleOutgoing();
}

// -------------------- Shared snapshot builders --------------------

uint16_t buildInfoPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  w.putU8(BLE_PROTOCOL_VERSION);
  w.putU8(MESH_VERSION);
  w.putU8(MESSAGE_VERSION);
  w.putU8(FIRMWARE_VERSION_MAJOR);
  w.putU8(FIRMWARE_VERSION_MINOR);
  w.putU8(FIRMWARE_VERSION_PATCH);
  w.putU32(localNodeId);
  w.putU8(DEVICE_ROLE_DEVELOPMENT);
  w.putU32(LOCAL_CAPABILITIES);
  w.putU16(NETWORK_ID);
  w.putU8(static_cast<uint8_t>(bleState));
  uint8_t securityFlags = 0;
  if (bleConnectedFlag) securityFlags |= 1U << 0;
  if (bleAuthSuccessFlag) securityFlags |= 1U << 1;
  if (bleCurrentBondedFlag) securityFlags |= 1U << 2;
  w.putU8(securityFlags);
  w.putU32(DEVELOPMENT_PERMISSION_MASK);
  return w.ok && w.length == BLE_INFO_PAYLOAD_BYTES ? static_cast<uint16_t>(w.length) : 0;
}

uint16_t buildStatusPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  w.putU32(localNodeId);
  w.putU32(millis());
  w.putU8(radioReady ? 1 : 0);
  w.putU8(cryptoReady ? 1 : 0);
  w.putU8(static_cast<uint8_t>(bleState));
  w.putU8(static_cast<uint8_t>(countFreshNeighbors()));
  w.putU8(static_cast<uint8_t>(countStaticRoutes()));
  w.putU8(static_cast<uint8_t>(countUsedTxEntries()));
  w.putU32(statRxValid);
  w.putU32(statTxFrames);
  w.putU32(statAckSuccess);
  w.putU32(statAckTimeout);
  w.putU32(statRxAuthFail + statMessageAuthFail);
  w.putU32(static_cast<uint32_t>(ESP.getFreeHeap()));
  w.putU32(largestFreeHeapBytes());
  return w.ok && w.length == BLE_STATUS_PAYLOAD_BYTES ? static_cast<uint16_t>(w.length) : 0;
}

uint16_t buildNeighborsPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  const size_t countOffset = w.length;
  w.putU8(0);
  uint8_t count = 0;
  const uint32_t now = millis();
  for (const auto& n : neighbors) {
    if (!n.used) continue;
    if (w.length + BLE_NEIGHBOR_RECORD_BYTES > capacity) break;
    const bool fresh = now - n.lastSeenAtMs <= NEIGHBOR_STALE_MS;
    w.putU32(n.nodeId);
    w.putU32(now - n.lastSeenAtMs);
    w.putI16(static_cast<int16_t>(lroundf(n.rssiEwma * 10.0f)));
    w.putI16(static_cast<int16_t>(lroundf(n.snrEwma * 10.0f)));
    w.putU16(static_cast<uint16_t>(lroundf(clampFloat(n.helloRxPdrEwma, 0, 100) * 10.0f)));
    w.putU16(static_cast<uint16_t>(lroundf(clampFloat(n.txAckPdrEwma, 0, 100) * 10.0f)));
    w.putU32(n.rxFrames);
    w.putU32(n.txAttempts);
    w.putU32(n.txAckSuccesses);
    w.putU8(fresh ? 1 : 0);
    if (!w.ok) return 0;
    count++;
  }
  out[countOffset] = count;
  return static_cast<uint16_t>(w.length);
}


uint16_t buildBleRadarPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  if (capacity < BLE_RADAR_HEADER_BYTES) return 0;

  BleRadarEntry snapshot[BLE_RADAR_MAX_DEVICES];
  uint32_t totalDetections = 0;
  portENTER_CRITICAL(&bleRadarMux);
  memcpy(snapshot, bleRadarEntries, sizeof(snapshot));
  totalDetections = bleRadarTotalDetections;
  portEXIT_CRITICAL(&bleRadarMux);

  w.putU8(1); // payload version
  w.putU8(bleRadarConfigured ? 1 : 0);
  NimBLEScan* scan = bleInitialized ? NimBLEDevice::getScan() : nullptr;
  w.putU8(scan != nullptr && scan->isScanning() ? 1 : 0);
  const size_t countOffset = w.length;
  w.putU8(0);
  w.putU32(bleRadarScanCycle);
  w.putU32(totalDetections);

  const uint32_t now = millis();
  uint8_t count = 0;
  // Serialize freshest devices first without exposing their raw BLE address.
  for (size_t pass = 0; pass < BLE_RADAR_MAX_DEVICES; ++pass) {
    int selected = -1;
    uint32_t selectedAge = UINT32_MAX;
    for (size_t i = 0; i < BLE_RADAR_MAX_DEVICES; ++i) {
      if (!snapshot[i].used) continue;
      const uint32_t age = now - snapshot[i].lastSeenAtMs;
      if (age > BLE_RADAR_STALE_MS || age >= selectedAge) continue;
      selected = static_cast<int>(i);
      selectedAge = age;
    }
    if (selected < 0 || w.length + BLE_RADAR_RECORD_BYTES > capacity) break;

    BleRadarEntry& e = snapshot[selected];
    const uint32_t presenceMs = e.lastSeenAtMs - e.firstSeenAtMs;
    const int8_t rssi = static_cast<int8_t>(clampFloat(e.rssiTenths / 10.0f, -127.0f, 20.0f));
    const int16_t trendTenths = static_cast<int16_t>(e.rssiTenths - e.baselineTenths);
    const int8_t trend = static_cast<int8_t>(clampFloat(trendTenths / 10.0f, -40.0f, 40.0f));
    const uint8_t hits = static_cast<uint8_t>(min(static_cast<uint16_t>(255), e.hits));
    const uint8_t nameLen = static_cast<uint8_t>(min(static_cast<size_t>(12), strlen(e.name)));

    w.putU32(e.addressHash);
    w.putU32(selectedAge);
    w.putU32(presenceMs);
    w.putU8(static_cast<uint8_t>(rssi));
    w.putU8(static_cast<uint8_t>(e.peakRssi));
    w.putU8(static_cast<uint8_t>(trend));
    w.putU8(hits);
    w.putU8(nameLen > 0 ? 1U : 0U);
    w.putU8(nameLen);
    uint8_t nameBytes[12] {};
    if (nameLen > 0) memcpy(nameBytes, e.name, nameLen);
    w.putBytes(nameBytes, sizeof(nameBytes));
    e.used = false; // prevents duplicate selection in the next pass
    count++;
  }
  out[countOffset] = count;
  return w.ok ? static_cast<uint16_t>(w.length) : 0;
}


// -------------------- v1.0 Operational Intelligence --------------------
// This is deliberately a read-only layer over the qualified radio/routing core.
// It never rewrites a route or changes RF parameters merely because a score moved.
enum class OperationalHealthLevel : uint8_t {
  Critical = 0,
  Degraded = 1,
  Good = 2,
  Excellent = 3
};

enum OperationalHealthFlag : uint16_t {
  HEALTH_RADIO_DOWN       = 1U << 0,
  HEALTH_CRYPTO_DOWN      = 1U << 1,
  HEALTH_NO_FRESH_PEER    = 1U << 2,
  HEALTH_QUEUE_PRESSURE   = 1U << 3,
  HEALTH_LOW_HEAP         = 1U << 4,
  HEALTH_MANIFEST_MISSING = 1U << 5,
  HEALTH_GPS_NO_FIX       = 1U << 6,
  HEALTH_BLE_DOWN         = 1U << 7,
  HEALTH_RADIO_RECOVERY   = 1U << 8,
  HEALTH_ACK_LOSS         = 1U << 9
};

struct OperationalHealthSnapshot {
  uint8_t score = 0;
  OperationalHealthLevel level = OperationalHealthLevel::Critical;
  uint16_t flags = 0;
  uint8_t radioScore = 0;
  uint8_t meshScore = 0;
  uint8_t routingScore = 0;
  uint8_t memoryScore = 0;
  uint8_t queueScore = 0;
  uint8_t gpsScore = 0;
  uint8_t bleScore = 0;
  uint8_t freshNeighbors = 0;
  uint8_t routeCount = 0;
  uint8_t exactG2Count = 0;
  uint8_t queueUsed = 0;
};

// Arduino's sketch preprocessor can emit generated prototypes before custom
// type declarations. Keep an explicit prototype here, after the type exists,
// so captureOperationalHealth() is not auto-prototyped too early.
OperationalHealthSnapshot captureOperationalHealth();

uint8_t scoreFreeHeap(uint32_t freeHeap) {
  if (freeHeap >= 80000UL) return 100;
  if (freeHeap >= 50000UL) return 88;
  if (freeHeap >= 32000UL) return 72;
  if (freeHeap >= 22000UL) return 48;
  if (freeHeap >= 15000UL) return 25;
  return 8;
}

uint8_t scoreTxQueue(size_t used) {
  if (MAX_TX_QUEUE == 0) return 0;
  const float ratio = clampFloat(static_cast<float>(used) / static_cast<float>(MAX_TX_QUEUE), 0.0f, 1.0f);
  return static_cast<uint8_t>(lroundf(100.0f * (1.0f - ratio * ratio)));
}

OperationalHealthSnapshot captureOperationalHealth() {
  OperationalHealthSnapshot h;
  const uint32_t now = millis();
  h.freshNeighbors = static_cast<uint8_t>(min(static_cast<size_t>(255), countFreshNeighbors()));
  h.queueUsed = static_cast<uint8_t>(min(static_cast<size_t>(255), countUsedTxEntries()));

  h.radioScore = radioReady && cryptoReady ? 100 : (radioReady || cryptoReady ? 35 : 0);
  if (!radioReady) h.flags |= HEALTH_RADIO_DOWN;
  if (!cryptoReady) h.flags |= HEALTH_CRYPTO_DOWN;
  if (statRadioRecoveries > 0) h.flags |= HEALTH_RADIO_RECOVERY;

  float meshAccumulator = 0.0f;
  uint8_t meshSamples = 0;
  for (const auto& n : neighbors) {
    if (!n.used || now - n.lastSeenAtMs > NEIGHBOR_STALE_MS) continue;
    const float hello = clampFloat(n.helloRxPdrEwma, 0.0f, 100.0f);
    const float ack = n.txAttempts >= 2 ? clampFloat(n.txAckPdrEwma, 0.0f, 100.0f) : hello;
    const float freshness = clampFloat(1.0f - static_cast<float>(now - n.lastSeenAtMs) /
      static_cast<float>(NEIGHBOR_STALE_MS), 0.35f, 1.0f);
    meshAccumulator += (hello * 0.55f + ack * 0.45f) * freshness;
    meshSamples++;
  }
  h.meshScore = meshSamples ? static_cast<uint8_t>(lroundf(clampFloat(meshAccumulator / meshSamples, 0.0f, 100.0f))) : 28;
  if (!meshSamples) h.flags |= HEALTH_NO_FRESH_PEER;

  uint8_t primaryRoutes = 0;
  for (size_t i = 0; i < vanguardRouter.capacity(); ++i) {
    const auto& route = vanguardRouter.routes()[i];
    if (!route.used || !route.primary.valid) continue;
    primaryRoutes++;
    if (vanguardRouter.hasExactG2(route.destination)) h.exactG2Count++;
  }
  h.routeCount = primaryRoutes;
  if (!networkManifest.valid) {
    h.flags |= HEALTH_MANIFEST_MISSING;
    h.routingScore = primaryRoutes ? 48 : 24;
  } else {
    const uint8_t redundancyBonus = primaryRoutes == 0 ? 0 :
      static_cast<uint8_t>(min(30, static_cast<int>((100 * h.exactG2Count) / primaryRoutes * 30 / 100)));
    h.routingScore = static_cast<uint8_t>(min(100, 70 + redundancyBonus));
  }

  const uint32_t freeHeap = static_cast<uint32_t>(ESP.getFreeHeap());
  h.memoryScore = scoreFreeHeap(freeHeap);
  if (freeHeap < 22000UL) h.flags |= HEALTH_LOW_HEAP;

  h.queueScore = scoreTxQueue(h.queueUsed);
  if (h.queueUsed >= MAX_TX_QUEUE - 2) h.flags |= HEALTH_QUEUE_PRESSURE;

  const bool gpsFix = gps.location.isValid() && gps.location.age() <= GPS_STALE_MS;
  h.gpsScore = !gpsSerialReady ? 0 : (gpsFix ? 100 : 45);
  if (gpsSerialReady && !gpsFix) h.flags |= HEALTH_GPS_NO_FIX;

  h.bleScore = bleInitialized ? 100 : 0;
  if (!bleInitialized) h.flags |= HEALTH_BLE_DOWN;

  const uint32_t ackTotal = statAckSuccess + statAckTimeout;
  if (ackTotal >= 5 && (statAckSuccess * 100UL) / ackTotal < 80UL) h.flags |= HEALTH_ACK_LOSS;

  float score =
    h.radioScore * 0.22f +
    h.meshScore * 0.20f +
    h.routingScore * 0.16f +
    h.memoryScore * 0.12f +
    h.queueScore * 0.12f +
    h.gpsScore * 0.08f +
    h.bleScore * 0.10f;

  if (!radioReady || !cryptoReady) score = min(score, 25.0f);
  if ((h.flags & HEALTH_LOW_HEAP) != 0) score = min(score, 55.0f);
  h.score = static_cast<uint8_t>(lroundf(clampFloat(score, 0.0f, 100.0f)));
  h.level = h.score >= 86 ? OperationalHealthLevel::Excellent :
            h.score >= 68 ? OperationalHealthLevel::Good :
            h.score >= 42 ? OperationalHealthLevel::Degraded :
                            OperationalHealthLevel::Critical;
  return h;
}

uint16_t buildOperationalHealthPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  const OperationalHealthSnapshot h = captureOperationalHealth();
  w.putU8(1); // payload version
  w.putU8(h.score);
  w.putU8(static_cast<uint8_t>(h.level));
  w.putU16(h.flags);
  w.putU8(h.radioScore);
  w.putU8(h.meshScore);
  w.putU8(h.routingScore);
  w.putU8(h.memoryScore);
  w.putU8(h.queueScore);
  w.putU8(h.gpsScore);
  w.putU8(h.bleScore);
  w.putU8(h.freshNeighbors);
  w.putU8(h.routeCount);
  w.putU8(h.exactG2Count);
  w.putU8(h.queueUsed);
  w.putU8(static_cast<uint8_t>(MAX_TX_QUEUE));
  return w.ok && w.length == BLE_OPERATIONAL_HEALTH_PAYLOAD_BYTES ? static_cast<uint16_t>(w.length) : 0;
}

// Full read-only diagnostics for the commander app.  The phone translates these
// engineering facts to human language; normal users never need to read raw RSSI.
uint16_t buildSelfDiagnosticsPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  const OperationalHealthSnapshot h = captureOperationalHealth();
  const bool gpsFix = gps.location.isValid() && gps.location.age() <= GPS_STALE_MS;
  w.putU8(1); // payload version
  w.putU8(h.score);
  w.putU8(static_cast<uint8_t>(h.level));
  w.putU16(h.flags);
  w.putU8(radioReady ? 1 : 0);
  w.putU8(cryptoReady ? 1 : 0);
  w.putU8(bleInitialized ? 1 : 0);
  w.putU8(!gpsSerialReady ? 0 : (gpsFix ? 2 : 1));
  w.putU8(oledReady ? 1 : 0);
  w.putU8(h.freshNeighbors);
  w.putU8(h.routeCount);
  w.putU8(h.exactG2Count);
  w.putU8(h.queueUsed);
  w.putU8(static_cast<uint8_t>(MAX_TX_QUEUE));
  w.putU32(static_cast<uint32_t>(ESP.getFreeHeap()));
  w.putU32(largestFreeHeapBytes());
  w.putU32(statAckSuccess);
  w.putU32(statAckTimeout);
  w.putU32(statTxErrors);
  w.putU32(statRadioRecoveries);
  w.putU32(statRxAuthFail + statMessageAuthFail);
  return w.ok && w.length == BLE_SELF_DIAG_PAYLOAD_BYTES ? static_cast<uint16_t>(w.length) : 0;
}


constexpr uint32_t OPERATIONAL_HEALTH_POLL_MS = 5000;
uint32_t lastOperationalHealthPollAtMs = 0;
bool operationalHealthBaselineReady = false;
uint8_t lastOperationalHealthLevel = 0xFF;
uint16_t lastOperationalCriticalFlags = 0;

void processOperationalHealthMonitor() {
  const uint32_t now = millis();
  if (now - lastOperationalHealthPollAtMs < OPERATIONAL_HEALTH_POLL_MS) return;
  lastOperationalHealthPollAtMs = now;
  const OperationalHealthSnapshot h = captureOperationalHealth();
  const uint16_t importantFlags = h.flags & (
    HEALTH_RADIO_DOWN | HEALTH_CRYPTO_DOWN | HEALTH_QUEUE_PRESSURE |
    HEALTH_LOW_HEAP | HEALTH_ACK_LOSS | HEALTH_BLE_DOWN);
  const uint8_t level = static_cast<uint8_t>(h.level);
  if (!operationalHealthBaselineReady) {
    operationalHealthBaselineReady = true;
    lastOperationalHealthLevel = level;
    lastOperationalCriticalFlags = importantFlags;
    return;
  }
  if (level == lastOperationalHealthLevel && importantFlags == lastOperationalCriticalFlags) return;
  lastOperationalHealthLevel = level;
  lastOperationalCriticalFlags = importantFlags;
  uint8_t event[4];
  event[0] = h.score;
  event[1] = level;
  writeU16(event, 2, h.flags);
  emitBleEvent(EVT_OPERATIONAL_HEALTH_CHANGED, event, sizeof(event));
  if (level == static_cast<uint8_t>(OperationalHealthLevel::Critical)) {
    setLastEvent("HEALTH CRITICAL");
  } else if (level == static_cast<uint8_t>(OperationalHealthLevel::Degraded)) {
    setLastEvent("HEALTH DEGRADED");
  }
}

uint16_t buildRoutesPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  const size_t countOffset = w.length;
  w.putU8(0);
  uint8_t count = 0;

  auto alreadyWritten = [&](uint32_t destination) -> bool {
    size_t offset = 1;
    for (uint8_t i = 0; i < count; ++i) {
      if (offset + 9 > w.length) break;
      if (readU32(out, offset) == destination) return true;
      offset += 9;
    }
    return false;
  };

  // Dynamic VANGUARD routes are emitted first because they are the routing
  // state the test panel needs to inspect. The compact 9-byte record remains
  // backward compatible with the previous GetRoutes layout.
  for (size_t i = 0; i < vanguardRouter.capacity(); ++i) {
    const auto& route = vanguardRouter.routes()[i];
    if (!route.used || !route.primary.valid) continue;
    if (w.length + 9 > capacity) break;
    w.putU32(route.destination);
    w.putU32(route.primary.nextHop);
    w.putU8(static_cast<uint8_t>(
      route.selectedFromBackup ? RouteSource::VanguardBackup
                               : RouteSource::VanguardDynamic));
    if (!w.ok) return 0;
    count++;
  }

  for (const auto& route : staticRoutes) {
    if (!route.active || alreadyWritten(route.destinationNodeId)) continue;
    if (w.length + 9 > capacity) break;
    w.putU32(route.destinationNodeId);
    w.putU32(route.nextHopNodeId);
    w.putU8(static_cast<uint8_t>(RouteSource::StaticTable));
    if (!w.ok) return 0;
    count++;
  }

  for (const auto& neighbor : neighbors) {
    if (!neighbor.used || !isFreshDirectNeighbor(neighbor.nodeId) ||
        alreadyWritten(neighbor.nodeId)) continue;
    if (w.length + 9 > capacity) break;
    w.putU32(neighbor.nodeId);
    w.putU32(neighbor.nodeId);
    w.putU8(static_cast<uint8_t>(RouteSource::DirectNeighbor));
    if (!w.ok) return 0;
    count++;
  }

  out[countOffset] = count;
  return static_cast<uint16_t>(w.length);
}

uint16_t buildKnownNodesPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  w.putU8(knownNodeRegistry.count);
  for (uint8_t i = 0; i < knownNodeRegistry.count; ++i) {
    w.putU32(knownNodeRegistry.nodes[i]);
  }
  return w.ok ? static_cast<uint16_t>(w.length) : 0;
}

uint16_t buildManifestPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  w.putU8(networkManifest.valid ? 1 : 0);
  w.putU32(networkManifest.networkEpoch);
  w.putU32(networkManifest.digest);
  w.putU8(networkManifest.count);
  for (uint8_t slot = 0; slot < networkManifest.count; ++slot) {
    w.putU8(slot);
    w.putU32(networkManifest.nodeBySlot[slot]);
  }
  return w.ok ? static_cast<uint16_t>(w.length) : 0;
}

uint16_t buildRoutingDiagnosticsPayload(uint8_t* out, size_t capacity) {
  vanguardControlBudget.refill(millis());
  BinaryWriter w{out, capacity};
  const auto& stats = vanguardRouter.stats();
  // Diagnostics wire format v2.  The first byte allows the phone app to
  // evolve independently from firmware without guessing record layout.
  w.putU8(2);
  w.putU8(networkManifest.valid ? 1 : 0);
  w.putU32(networkManifest.networkEpoch);
  w.putU32(networkManifest.digest);
  w.putU32(vanguardRuntime.routeSeq());
  w.putU32(stats.acceptedPrimary);
  w.putU32(stats.acceptedBackup);
  w.putU32(stats.acceptedAlternate);
  w.putU32(stats.rejectedOldGeneration);
  w.putU32(stats.rejectedLoop);
  w.putU32(stats.rejectedInfeasible);
  w.putU32(stats.rejectedWorse);
  w.putU32(stats.rejectedSamePath);
  w.putU32(stats.promotionsG2);
  w.putU32(stats.promotionsAlternate);
  w.putU32(stats.expirations);
  w.putU32(stats.routeErrors);
  w.putU32(vanguardControlBudget.drops());
  w.putU32(vanguardControlBudget.tokensUs());
  w.putU32(statVanguardDeferredQueued);
  w.putU32(statVanguardDeferredDrops);
  w.putU8(countDeferredVanguardControls());
  w.putU32(statLabFaultRxDrops);
  w.putU32(statLabFaultTxDrops);
  w.putU8(countActiveLabLinkFaults(millis()));

  const size_t routeCountOffset = w.length;
  w.putU8(0);
  uint8_t count = 0;
  // Detailed route record v2 = 56 bytes:
  // destination, primary/backup/alternate next-hop, generation, guard/FD,
  // primary/backup exact masks, primary/backup path tags, primary ECA,
  // reliability, flags, backup lease.  The compact GetRoutes command remains
  // the complete table; this command intentionally returns the first records
  // that fit in one bounded BLE application packet.
  constexpr size_t ROUTE_DIAG_RECORD_V2 = 56;
  for (size_t i = 0; i < vanguardRouter.capacity(); ++i) {
    const auto& route = vanguardRouter.routes()[i];
    if (!route.used || !route.primary.valid) continue;
    if (w.length + ROUTE_DIAG_RECORD_V2 > capacity) break;
    w.putU32(route.destination);
    w.putU32(route.primary.nextHop);
    w.putU32(route.backup.valid ? route.backup.nextHop : 0);
    w.putU32(route.alternate.valid ? route.alternate.nextHop : 0);
    w.putU32(route.generation.bootEpoch);
    w.putU32(route.generation.routeSeq);
    w.putU32(route.guardRank);
    w.putU32(route.feasibleDistance);
    w.putU32(route.primary.internalPathMask);
    w.putU32(route.backup.valid ? route.backup.internalPathMask : 0);
    w.putU32(route.primary.pathTag);
    w.putU32(route.backup.valid ? route.backup.pathTag : 0);
    w.putU32(route.primary.ecaQ16);
    w.putU16(route.primary.reliabilityQ15);
    uint8_t flags = 0;
    if (route.primary.exactMask) flags |= 0x01;
    if (vanguardRouter.hasExactG2(route.destination)) flags |= 0x02;
    if (route.selectedFromBackup) flags |= 0x04;
    if (route.primary.pathTag != 0) flags |= 0x08;
    if (route.backup.valid && route.backup.pathTag != 0) flags |= 0x10;
    w.putU8(flags);
    w.putU8(route.backup.valid ? static_cast<uint8_t>(route.backup.lease) : 0);
    if (!w.ok) return 0;
    count++;
  }
  out[routeCountOffset] = count;
  return w.ok ? static_cast<uint16_t>(w.length) : 0;
}

uint16_t buildLabLinkPoliciesPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  const size_t countOffset = w.length;
  w.putU8(0);
  uint8_t count = 0;
  const uint32_t now = millis();
  for (auto& rule : labLinkFaults) {
    if (!rule.used) continue;
    if (labRuleExpired(rule, now)) { rule = LabLinkFault{}; continue; }
    // peer u32, flags u8, remainingMs u32, reliabilityQ15 u16, ecaQ16 u32
    if (w.length + 15 > capacity) break;
    w.putU32(rule.nodeId);
    w.putU8(rule.flags);
    w.putU32(rule.manual ? LAB_RULE_MANUAL_DURATION : (rule.expiresAtMs - now));
    w.putU16(rule.reliabilityQ15);
    w.putU32(rule.ecaQ16);
    if (!w.ok) return 0;
    count++;
  }
  out[countOffset] = count;
  return static_cast<uint16_t>(w.length);
}

uint16_t buildFieldTestStatusPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  const uint32_t endAt = fieldTest.state == FieldTestState::Running
    ? millis() : fieldTest.finishedAtMs;
  const uint32_t elapsed = fieldTest.startedAtMs == 0 ? 0 : endAt - fieldTest.startedAtMs;
  const uint32_t avgRtt = fieldTest.endToEndReplies == 0 ? 0 :
    static_cast<uint32_t>(fieldTest.rttSumMs / fieldTest.endToEndReplies);
  const uint16_t pdrTenths = fieldTest.sent == 0 ? 0 :
    static_cast<uint16_t>((fieldTest.endToEndReplies * 1000UL) / fieldTest.sent);
  const int16_t avgRssiTenths = fieldTest.localLinkSamples == 0 ? 0 :
    static_cast<int16_t>(fieldTest.localRssiSum / static_cast<int64_t>(fieldTest.localLinkSamples));
  const int16_t avgSnrTenths = fieldTest.localLinkSamples == 0 ? 0 :
    static_cast<int16_t>(fieldTest.localSnrTenthsSum / static_cast<int64_t>(fieldTest.localLinkSamples));

  w.putU8(static_cast<uint8_t>(fieldTest.state));
  w.putU8(static_cast<uint8_t>(fieldTest.mode));
  w.putU32(fieldTest.testId);
  w.putU32(fieldTest.targetNodeId);
  w.putU32(elapsed);
  w.putU16(fieldTest.requestedPackets);
  w.putU32(fieldTest.sent);
  w.putU32(fieldTest.firstHopAcked);
  w.putU32(fieldTest.firstHopFailed);
  w.putU32(fieldTest.firstHopRetries);
  w.putU32(fieldTest.endToEndReplies);
  w.putU32(fieldTest.endToEndTimeouts);
  w.putU32(fieldTest.currentSequence);
  w.putU32(fieldTest.lastNextHop);
  w.putU8(static_cast<uint8_t>(fieldTest.lastRouteSource));
  w.putU32(avgRtt);
  w.putU32(fieldTest.rttMinMs == UINT32_MAX ? 0 : fieldTest.rttMinMs);
  w.putU32(fieldTest.rttMaxMs);
  w.putU16(pdrTenths);
  w.putI16(avgRssiTenths);
  w.putI16(avgSnrTenths);
  return w.ok && w.length == BLE_FIELD_STATUS_PAYLOAD_BYTES ? static_cast<uint16_t>(w.length) : 0;
}

uint16_t buildPositionsPayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  const size_t countOffset = w.length;
  w.putU8(0);
  uint8_t count = 0;
  const uint32_t now = millis();
  for (size_t i = 0; i < MAX_POSITION_CACHE; ++i) {
    const PositionRecord& pos = positionCache[i];
    if (!pos.used) continue;
    uint8_t encoded[POSITION_PAYLOAD_SIZE];
    encodePositionPayload(pos, encoded);
    if (!w.putU32(pos.nodeId) || !w.putBytes(encoded, sizeof(encoded)) || !w.putU32(now - pos.receivedAtMs)) break;
    count++;
  }
  if (!w.ok || countOffset >= w.length) return 0;
  out[countOffset] = count;
  return static_cast<uint16_t>(w.length);
}

void buildSosPayload(uint8_t sosType, uint32_t sosId, uint8_t out[SOS_PAYLOAD_SIZE]) {
  memset(out, 0, SOS_PAYLOAD_SIZE);
  out[0] = SOS_PAYLOAD_VERSION;
  out[1] = sosType;
  uint8_t flags = 0;
  int32_t lat = 0, lon = 0;
  uint32_t positionAge = UINT32_MAX;
  uint32_t raisedEpoch = gpsUtcEpochSeconds();
  const int localIndex = findPositionRecord(localNodeId);
  if (localIndex >= 0) {
    const PositionRecord& pos = positionCache[localIndex];
    const uint32_t age = millis() - pos.receivedAtMs + pos.fixAgeMs;
    if ((pos.flags & POSITION_FLAG_FIX) != 0 && age <= GPS_STALE_MS) flags |= SOS_FLAG_POSITION_VALID;
    else flags |= SOS_FLAG_LAST_KNOWN;
    lat = pos.latitudeE7; lon = pos.longitudeE7; positionAge = age;
    if (raisedEpoch == 0) raisedEpoch = pos.gpsEpochSec;
  }
  out[2] = flags;
  writeU32(out, 4, sosId);
  writeU32(out, 8, raisedEpoch);
  writeU32(out, 12, static_cast<uint32_t>(lat));
  writeU32(out, 16, static_cast<uint32_t>(lon));
  writeU32(out, 20, positionAge);
  out[24] = 0xFF; // battery sensing is not calibrated in this hardware baseline.
}

void clearRuntimeStatisticsOnly() {
  statRxValid = 0;
  statRxAuthFail = 0;
  statRxMalformed = 0;
  statRxDuplicate = 0;
  statRxTooOld = 0;
  statTxFrames = 0;
  statTxErrors = 0;
  statAckSuccess = 0;
  statAckTimeout = 0;
  statMessagesReceived = 0;
  statMessagesDelivered = 0;
  statMessagesForwarded = 0;
  statMessageAuthFail = 0;
  statMessageDuplicate = 0;
  statMessageTooOld = 0;
  statRadioRecoveries = 0;
  statRelayLogicalDuplicate = 0;
  statLabFaultRxDrops = 0;
  statLabFaultTxDrops = 0;
  statVanguardDeferredQueued = 0;
  statVanguardDeferredDrops = 0;
  vanguardRouter.clearStats();
  vanguardControlBudget.reset(millis());
}

void dispatchCommand(const CommandRequest& request, CommandResult& result) {
  result = CommandResult{};

  // Defense in depth: GATT permissions and the BLE queue already require an
  // authenticated link, but the common dispatcher enforces it again.
  if (request.source == CommandSource::Ble) {
    if (!bleAuthSuccessFlag || bleState != BleState::ProtocolReady) {
      result.status = CommandStatus::NotAuthenticated;
      return;
    }
    const uint8_t rawCommand = static_cast<uint8_t>(request.type);
    if (rawCommand >= static_cast<uint8_t>(CommandType::BleStatus) &&
        rawCommand <= static_cast<uint8_t>(CommandType::Reboot)) {
      result.status = CommandStatus::NotSupported;
      return;
    }
    // Opcodes 30/31 are v1.0 read-only operational intelligence commands.
    // Privileged maintenance opcodes 32..37 stay serial-only; opcode 38 is the
    // authenticated read-only OLED framebuffer extension used by the phone remote.
  }

  switch (request.type) {
    case CommandType::GetInfo:
      result.payloadLength = buildInfoPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::GetStatus:
      result.payloadLength = buildStatusPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::GetNeighbors:
      result.payloadLength = buildNeighborsPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::GetRoutes:
      result.payloadLength = buildRoutesPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::SendMessage: {
      if (request.dataLength == 0 || request.dataLength > MAX_APP_PAYLOAD) {
        result.status = CommandStatus::InvalidArgument; return;
      }
      QueuedMessageMeta meta;
      const QueueMessageResult qr = queueApplicationMessage(
        MessageType::UserData, request.destination,
        request.data, request.dataLength, false,
        RoutePolicy::Routed, &meta
      );
      result.status = mapQueueResult(qr);
      if (result.status == CommandStatus::Ok) {
        BinaryWriter w{result.payload, sizeof(result.payload)};
        w.putU32(meta.messageId); w.putU32(meta.nextHop);
        w.putU8(static_cast<uint8_t>(meta.routeSource));
        result.payloadLength = static_cast<uint16_t>(w.length);
        uint8_t event[12];
        writeU32(event, 0, meta.messageId);
        writeU32(event, 4, request.destination);
        writeU32(event, 8, meta.nextHop);
        emitBleEvent(EVT_MESSAGE_QUEUED, event, sizeof(event));
        uiNotifyMessageQueued(request.destination);
      }
      return;
    }

    case CommandType::Broadcast: {
      const QueueMessageResult qr = queueApplicationMessage(
        MessageType::UserData, BROADCAST_ID,
        request.data, request.dataLength, true,
        RoutePolicy::Routed, nullptr
      );
      result.status = mapQueueResult(qr);
      return;
    }

    case CommandType::AddStaticRoute:
      if (!setStaticRoute(request.destination, request.nextHop)) {
        result.status = CommandStatus::InvalidArgument;
      } else {
        uint8_t event[9];
        writeU32(event, 0, request.destination);
        writeU32(event, 4, request.nextHop);
        event[8] = 1;
        emitBleEvent(EVT_ROUTE_CHANGED, event, sizeof(event));
        uiShowToast("МАРШРУТ", "СОХРАНЕН", 1400);
      }
      return;

    case CommandType::RemoveStaticRoute:
      if (!removeStaticRoute(request.destination)) {
        result.status = CommandStatus::InvalidArgument;
      } else {
        uint8_t event[9] {};
        writeU32(event, 0, request.destination);
        event[8] = 0;
        emitBleEvent(EVT_ROUTE_CHANGED, event, sizeof(event));
        uiShowToast("МАРШРУТ", "УДАЛЕН", 1400);
      }
      return;

    case CommandType::StartFieldTest:
      result.status = startFieldTest(
        request.destination, request.packetCount, request.intervalMs,
        request.payloadSize, request.testMode
      );
      if (result.status == CommandStatus::Ok) {
        result.payloadLength = buildFieldTestStatusPayload(result.payload, sizeof(result.payload));
      }
      return;

    case CommandType::StopFieldTest:
      result.status = stopFieldTest();
      return;

    case CommandType::GetFieldTestStatus:
      result.payloadLength = buildFieldTestStatusPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::PingLocal:
      result.payloadLength = buildStatusPayload(result.payload, sizeof(result.payload));
      return;

    case CommandType::ClearStats:
      clearRuntimeStatisticsOnly();
      return;

    case CommandType::GetUiState:
      result.payloadLength = buildUiStatePayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::UiAction:
      if (!uiHandleRemoteAction(request.uiAction)) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      result.payloadLength = buildUiStatePayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::GetOledFrameChunk:
      if (!oledReady) {
        result.status = CommandStatus::NotSupported;
        return;
      }
      result.payloadLength = buildOledFrameChunkPayload(request.oledChunkIndex, result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::GetKnownNodes:
      result.payloadLength = buildKnownNodesPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::GetManifest:
      result.payloadLength = buildManifestPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::SetManifest: {
      if (request.dataLength < 9) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      const uint32_t epoch = readU32(request.data, 0);
      const uint8_t count = request.data[4];
      if (count == 0 || count > MAX_LAB_NODES || request.dataLength != 5 + count * 4) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      uint32_t nodes[MAX_LAB_NODES] {};
      for (uint8_t i = 0; i < count; ++i) nodes[i] = readU32(request.data, 5 + i * 4);
      VanguardManifest::Manifest<MAX_LAB_NODES> candidate;
      if (!candidate.configure(epoch, nodes, count, localNodeId)) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      const VanguardManifest::Manifest<MAX_LAB_NODES> previousManifest = networkManifest;
      networkManifest = candidate;
      if (!saveNetworkManifest()) {
        // Keep RAM and NVS semantics aligned.  A failed commit must not leave
        // the live router scoped to a manifest the controller was told failed.
        networkManifest = previousManifest;
        (void)saveNetworkManifest();
        result.status = CommandStatus::InternalError;
        setLastEvent("MANIFEST SAVE FAIL");
        return;
      }
      vanguardRouter.clearRoutes();
      applyNetworkManifest();
      result.payloadLength = buildManifestPayload(result.payload, sizeof(result.payload));
      emitBleEvent(EVT_MANIFEST_CHANGED, result.payload, result.payloadLength);
      setLastEvent("MANIFEST OK");
      return;
    }

    case CommandType::DiscoverRoute:
      if (!requestVanguardRoute(request.destination, request.flag)) {
        uint32_t hop = 0;
        RouteSource source = RouteSource::None;
        if (!resolveNextHop(request.destination, hop, source)) {
          result.status = CommandStatus::Busy;
          return;
        }
      }
      result.payloadLength = buildRoutingDiagnosticsPayload(
        result.payload, sizeof(result.payload));
      return;

    case CommandType::GetRoutingDiagnostics:
      result.payloadLength = buildRoutingDiagnosticsPayload(
        result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::InjectLinkFailure: {
      const int idx = findNeighborIndex(request.nextHop);
      if (idx < 0 && request.intervalMs != 0) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      if (!setLabLinkFault(request.nextHop, request.intervalMs)) {
        result.status = CommandStatus::Busy;
        return;
      }
      if (request.intervalMs != 0) {
        if (idx >= 0) neighbors[idx].lastSeenAtMs = millis() - NEIGHBOR_STALE_MS - 1;
        notifyVanguardHopFailure(request.nextHop);
        setLastEvent("FAULT INJECT");
      } else {
        setLastEvent("FAULT CLEAR");
      }
      result.payloadLength = buildRoutingDiagnosticsPayload(
        result.payload, sizeof(result.payload));
      return;
    }

    case CommandType::ClearDynamicRoutes:
      vanguardRouter.clearRoutes();
      vanguardRuntime.reset(localNodeId, localBootCounter);
      result.payloadLength = buildRoutingDiagnosticsPayload(
        result.payload, sizeof(result.payload));
      setLastEvent("ROUTES CLEARED");
      return;

    case CommandType::SetLabLinkPolicy: {
      if (request.dataLength != 15) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      const uint32_t peer = readU32(request.data, 0);
      const uint8_t flags = request.data[4];
      const uint32_t duration = readU32(request.data, 5);
      const uint16_t reliability = readU16(request.data, 9);
      const uint32_t eca = readU32(request.data, 11);
      const int idx = findNeighborIndex(peer);
      if (idx < 0 && flags != 0) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      if (!setLabLinkPolicy(peer, flags, duration, reliability, eca)) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      if ((flags & LAB_RULE_BLOCK) != 0) {
        if (idx >= 0) neighbors[idx].lastSeenAtMs = millis() - NEIGHBOR_STALE_MS - 1;
        notifyVanguardHopFailure(peer);
      }
      // A metric change must not leave an already selected route pretending it
      // was evaluated under the new lab topology.  Re-discovery remains under
      // explicit panel control so a test can mark its exact start time.
      result.payloadLength = buildLabLinkPoliciesPayload(
        result.payload, sizeof(result.payload));
      setLastEvent(flags == 0 ? "LAB LINK CLEAR" : "LAB LINK POLICY");
      return;
    }

    case CommandType::GetLabLinkPolicies:
      result.payloadLength = buildLabLinkPoliciesPayload(
        result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::GetPositions:
      result.payloadLength = buildPositionsPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;


    case CommandType::GetBleRadar:
      result.payloadLength = buildBleRadarPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::ClearBleRadar:
      clearBleRadar();
      return;

    case CommandType::GetOperationalHealth:
      result.payloadLength = buildOperationalHealthPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::GetSelfDiagnostics:
      result.payloadLength = buildSelfDiagnosticsPayload(result.payload, sizeof(result.payload));
      if (result.payloadLength == 0) result.status = CommandStatus::InternalError;
      return;

    case CommandType::RaiseSos: {
      const uint8_t sosType = request.dataLength == 1 ? request.data[0] : 0;
      if (request.dataLength != 1 || sosType > 15) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      const uint32_t sosId = esp_random() | 1U;
      uint8_t payload[SOS_PAYLOAD_SIZE];
      buildSosPayload(sosType, sosId, payload);
      const QueueMessageResult qr = queueApplicationMessage(
        MessageType::Sos, BROADCAST_ID, payload, sizeof(payload), true,
        RoutePolicy::Routed, nullptr, 1, DEFAULT_HOP_LIMIT);
      result.status = mapQueueResult(qr);
      if (result.status == CommandStatus::Ok) {
        ActiveSosRecord local;
        local.active = true;
        local.sosId = sosId;
        local.originNodeId = localNodeId;
        local.sosType = sosType;
        local.flags = payload[2];
        local.raisedEpochSec = readU32(payload, 8);
        local.latitudeE7 = static_cast<int32_t>(readU32(payload, 12));
        local.longitudeE7 = static_cast<int32_t>(readU32(payload, 16));
        local.positionAgeMs = readU32(payload, 20);
        local.batteryPercent = payload[24];
        local.receivedAtMs = millis();
        activeSos = local;
        writeU32(result.payload, 0, sosId);
        result.payloadLength = 4;
        uint8_t event[29] {};
        writeU32(event, 0, localNodeId);
        memcpy(event + 4, payload, sizeof(payload));
        emitBleEvent(EVT_SOS_RAISED, event, sizeof(event));
        uiShowToast("SOS", "ОТПРАВЛЕН", 2200);
        setLastEvent("SOS SENT");
      }
      return;
    }

    case CommandType::AckSos: {
      if (request.dataLength != 8) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      const uint32_t origin = readU32(request.data, 0);
      const uint32_t sosId = readU32(request.data, 4);
      if (origin == 0 || origin == BROADCAST_ID || sosId == 0) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      uint8_t payload[8];
      writeU32(payload, 0, sosId);
      writeU32(payload, 4, localNodeId);
      const QueueMessageResult qr = queueApplicationMessage(
        MessageType::SosAck, origin, payload, sizeof(payload), false,
        RoutePolicy::Routed, nullptr, 1);
      result.status = mapQueueResult(qr);
      if (result.status == CommandStatus::Ok &&
          activeSos.active && activeSos.originNodeId == origin && activeSos.sosId == sosId) {
        activeSos.acknowledged = true;
        activeSos.acknowledgedBy = localNodeId;
        uint8_t event[12];
        writeU32(event, 0, origin);
        writeU32(event, 4, sosId);
        writeU32(event, 8, localNodeId);
        emitBleEvent(EVT_SOS_ACKNOWLEDGED, event, sizeof(event));
      }
      return;
    }

    case CommandType::SendCommandNotice: {
      if (request.destination == 0 || request.destination == BROADCAST_ID ||
          request.dataLength != 9) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      const uint8_t kind = request.data[0];
      if (kind < static_cast<uint8_t>(CommandNoticeKind::Return) ||
          kind > static_cast<uint8_t>(CommandNoticeKind::MoveToWaypoint)) {
        result.status = CommandStatus::InvalidArgument;
        return;
      }
      uint8_t payload[COMMAND_NOTICE_PAYLOAD_SIZE] {};
      payload[0] = COMMAND_NOTICE_VERSION;
      payload[1] = kind;
      payload[2] = 0;
      const uint32_t commandId = esp_random() | 1U;
      writeU32(payload, 4, commandId);
      memcpy(payload + 8, request.data + 1, 8);
      QueuedMessageMeta meta;
      const QueueMessageResult qr = queueApplicationMessage(
        MessageType::CommandNotice, request.destination, payload, sizeof(payload), false,
        RoutePolicy::Routed, &meta, 2);
      result.status = mapQueueResult(qr);
      if (result.status == CommandStatus::Ok) {
        BinaryWriter w{result.payload, sizeof(result.payload)};
        w.putU32(commandId);
        w.putU32(meta.messageId);
        w.putU32(meta.nextHop);
        result.payloadLength = static_cast<uint16_t>(w.length);
      }
      return;
    }

    case CommandType::BleStatus:
      result.payloadLength = buildInfoPayload(result.payload, sizeof(result.payload));
      return;

    case CommandType::BleAdvertise:
      bleAdvertisingEnabled = request.flag;
      if (request.flag) startBleAdvertising(); else stopBleAdvertising();
      return;

    case CommandType::BleBonds:
      if (!bleInitialized) { result.status = CommandStatus::NotSupported; return; }
      result.payload[0] = static_cast<uint8_t>(NimBLEDevice::getNumBonds() > 255 ? 255 : NimBLEDevice::getNumBonds());
      result.payloadLength = 1;
      return;

    case CommandType::BleBondsClear:
      if (!bleInitialized) { result.status = CommandStatus::NotSupported; return; }
      result.status = NimBLEDevice::deleteAllBonds() ? CommandStatus::Ok : CommandStatus::InternalError;
      return;

    case CommandType::Reboot:
      return;

    default:
      result.status = CommandStatus::InvalidCommand;
      return;
  }
}

void sendBleCommandResponse(const CommandRequest& request, const CommandResult& result) {
  uint8_t packet[BLE_MAX_APP_PACKET];
  const uint16_t length = buildBleApplicationPacket(
    BlePacketType::Response,
    request.requestId,
    static_cast<uint8_t>(request.type),
    result.status,
    result.payload,
    result.payloadLength,
    packet,
    sizeof(packet)
  );
  if (length > 0) enqueueBleRawPacket(bleOutQueue, bleOutRing, BLE_OUT_QUEUE_SIZE, packet, length);
}

void processBleCommandQueue() {
  if (!bleInitialized || bleState != BleState::ProtocolReady) return;
  uint16_t length = 0;
  uint8_t packet[BLE_MAX_APP_PACKET];
  if (!dequeueBleRawPacket(
        bleCommandQueue,
        bleCommandRing,
        BLE_COMMAND_QUEUE_SIZE,
        packet,
        length)) return;

  CommandRequest request;
  if (!parseBleCommandPacket(packet, length, request)) {
    statBleMalformed++;
    if (length >= BLE_APP_HEADER_SIZE &&
        readU16(packet, 0) == BLE_PROTOCOL_MAGIC &&
        packet[2] != BLE_PROTOCOL_VERSION) {
      request = CommandRequest{};
      request.source = CommandSource::Ble;
      request.requestId = readU16(packet, 4);
      request.type = static_cast<CommandType>(packet[6]);
      CommandResult result;
      result.status = CommandStatus::NotSupported;
      sendBleCommandResponse(request, result);
      return;
    }
    // If the base header is readable, correlate the malformed request.
    if (length >= BLE_APP_HEADER_SIZE && readU16(packet, 0) == BLE_PROTOCOL_MAGIC) {
      request = CommandRequest{};
      request.source = CommandSource::Ble;
      request.requestId = readU16(packet, 4);
      request.type = static_cast<CommandType>(packet[6]);
      CommandResult result;
      result.status = CommandStatus::InvalidArgument;
      sendBleCommandResponse(request, result);
    }
    return;
  }

  statBleCommands++;
  CommandResult result;
  dispatchCommand(request, result);
  sendBleCommandResponse(request, result);
}

bool neighborLifecycleFresh[MAX_NEIGHBORS] {};
uint32_t nextNeighborLifecycleAtMs = 0;

void processNeighborLifecycleEvents() {
  const uint32_t now = millis();
  if (!timeReached(now, nextNeighborLifecycleAtMs)) return;
  nextNeighborLifecycleAtMs = now + 1000;
  for (size_t i = 0; i < MAX_NEIGHBORS; ++i) {
    const bool fresh = neighbors[i].used && now - neighbors[i].lastSeenAtMs <= NEIGHBOR_STALE_MS;
    if (fresh == neighborLifecycleFresh[i]) continue;
    neighborLifecycleFresh[i] = fresh;
    if (!neighbors[i].used) continue;
    uint8_t event[4];
    writeU32(event, 0, neighbors[i].nodeId);
    emitBleEvent(fresh ? EVT_NODE_DISCOVERED : EVT_NODE_STALE, event, sizeof(event));
  }
}


// ============================================================
// 20. SERIAL CONSOLE
// ============================================================

char serialLineBuffer[CONSOLE_LINE_SIZE] {};
size_t serialLineLength = 0;

bool parseNodeIdText(const char* text, uint32_t& nodeId) {
  if (text == nullptr || strlen(text) != 8) return false;

  char* end = nullptr;
  const unsigned long parsed = strtoul(text, &end, 16);
  if (end == nullptr || *end != '\0') return false;

  nodeId = static_cast<uint32_t>(parsed);
  return nodeId != 0 && nodeId != BROADCAST_ID;
}

void printHelp() {
  Serial.println("SecureMesh v1.0.4 OPERATOR commands:");
  Serial.println("  id                            - local node ID");
  Serial.println("  status                        - core/radio/BLE counters");
  Serial.println("  nodes                         - measured direct neighbors");
  Serial.println("  routes                        - static + direct routes");
  Serial.println("  route add <DEST> <NEXTHOP>    - add/update static route");
  Serial.println("  route del <DEST>              - delete static route");
  Serial.println("  send <DEST> <text>            - routed USER_DATA");
  Serial.println("  broadcast <text>              - authenticated one-hop broadcast");
  Serial.println("  test start <DEST> <COUNT> <INTERVAL_MS> <SIZE> [routed|direct]");
  Serial.println("  test stop                     - cancel current field test");
  Serial.println("  test status                   - field test counters");
  Serial.println("  ble status                    - BLE/session/memory state");
  Serial.println("  ble advertise on|off          - advertising control");
  Serial.println("  ble bonds                     - list stored bonds");
  Serial.println("  ble bonds clear               - delete stored BLE bonds");
  Serial.println("  clear                         - clear runtime peer stats (replay preserved)");
  Serial.println("  reboot                        - restart ESP32");
}

void printStatus() {
  Serial.printf(
    "ID=%s boot=%lu crypto=%s radio=%s err=%d\r\n",
    localIdText,
    static_cast<unsigned long>(localBootCounter),
    cryptoReady ? "OK" : "FAIL",
    radioReady ? "OK" : "FAIL",
    lastRadioError
  );

  Serial.printf(
    "neighbors=%u routes=%u txq=%u ackWait=%s\r\n",
    static_cast<unsigned>(countFreshNeighbors()),
    static_cast<unsigned>(countStaticRoutes()),
    static_cast<unsigned>(countUsedTxEntries()),
    ackWaiting ? "YES" : "NO"
  );

  Serial.printf(
    "RX valid=%lu authFail=%lu malformed=%lu duplicate=%lu old=%lu\r\n",
    static_cast<unsigned long>(statRxValid),
    static_cast<unsigned long>(statRxAuthFail),
    static_cast<unsigned long>(statRxMalformed),
    static_cast<unsigned long>(statRxDuplicate),
    static_cast<unsigned long>(statRxTooOld)
  );

  Serial.printf(
    "TX=%lu err=%lu hopAckOK=%lu hopAckTO=%lu local=%lu fwd=%lu recover=%lu\r\n",
    static_cast<unsigned long>(statTxFrames),
    static_cast<unsigned long>(statTxErrors),
    static_cast<unsigned long>(statAckSuccess),
    static_cast<unsigned long>(statAckTimeout),
    static_cast<unsigned long>(statMessagesDelivered),
    static_cast<unsigned long>(statMessagesForwarded),
    static_cast<unsigned long>(statRadioRecoveries)
  );
  Serial.printf(
    "MSG authFail=%lu duplicate=%lu old=%lu\r\n",
    static_cast<unsigned long>(statMessageAuthFail),
    static_cast<unsigned long>(statMessageDuplicate),
    static_cast<unsigned long>(statMessageTooOld)
  );

  Serial.printf(
    "BLE=%s bonds=%d mtu=%u heap=%u largest=%u bleMalformed=%lu bleDrop=%lu\r\n",
    bleStateText(bleState),
    bleInitialized ? NimBLEDevice::getNumBonds() : 0,
    static_cast<unsigned>(bleNegotiatedMtu),
    static_cast<unsigned>(ESP.getFreeHeap()),
    static_cast<unsigned>(largestFreeHeapBytes()),
    static_cast<unsigned long>(statBleMalformed),
    static_cast<unsigned long>(statBleDropped)
  );
}

void printNeighbors() {
  Serial.println("ID       AGE  RSSI   SNR  HELLO% TXACK% RXFRAMES TXOK/TX");
  const uint32_t now = millis();

  for (const auto& entry : neighbors) {
    if (!entry.used) continue;

    Serial.printf(
      "%08lX %4lus %5.1f %5.1f %6.1f %6.1f %8lu %lu/%lu\r\n",
      static_cast<unsigned long>(entry.nodeId),
      static_cast<unsigned long>((now - entry.lastSeenAtMs) / 1000UL),
      entry.rssiEwma,
      entry.snrEwma,
      entry.helloRxPdrEwma,
      entry.txAckPdrEwma,
      static_cast<unsigned long>(entry.rxFrames),
      static_cast<unsigned long>(entry.txAckSuccesses),
      static_cast<unsigned long>(entry.txAttempts)
    );
  }
}

void printFieldTestStatus() {
  const uint32_t endAt = fieldTest.state == FieldTestState::Running ? millis() : fieldTest.finishedAtMs;
  const uint32_t elapsed = fieldTest.startedAtMs == 0 ? 0 : endAt - fieldTest.startedAtMs;
  const float pdr = fieldTest.sent == 0 ? 0.0f :
    100.0f * static_cast<float>(fieldTest.endToEndReplies) / static_cast<float>(fieldTest.sent);
  const uint32_t avgRtt = fieldTest.endToEndReplies == 0 ? 0 :
    static_cast<uint32_t>(fieldTest.rttSumMs / fieldTest.endToEndReplies);
  Serial.printf(
    "TEST state=%u id=%08lX target=%08lX mode=%s elapsed=%lums\r\n",
    static_cast<unsigned>(fieldTest.state),
    static_cast<unsigned long>(fieldTest.testId),
    static_cast<unsigned long>(fieldTest.targetNodeId),
    fieldTest.mode == FieldTestMode::DirectOnly ? "DIRECT_ONLY" : "ROUTED",
    static_cast<unsigned long>(elapsed)
  );
  Serial.printf(
    "requested=%u sent=%lu firstHopAck=%lu failed=%lu retries=%lu e2ePong=%lu timeout=%lu PDR=%.1f%% avgRTT=%lums\r\n",
    static_cast<unsigned>(fieldTest.requestedPackets),
    static_cast<unsigned long>(fieldTest.sent),
    static_cast<unsigned long>(fieldTest.firstHopAcked),
    static_cast<unsigned long>(fieldTest.firstHopFailed),
    static_cast<unsigned long>(fieldTest.firstHopRetries),
    static_cast<unsigned long>(fieldTest.endToEndReplies),
    static_cast<unsigned long>(fieldTest.endToEndTimeouts),
    pdr,
    static_cast<unsigned long>(avgRtt)
  );
  Serial.printf(
    "firstHop=%08lX route=%s pending=%u\r\n",
    static_cast<unsigned long>(fieldTest.lastNextHop),
    routeSourceText(fieldTest.lastRouteSource),
    static_cast<unsigned>(countDiagPending())
  );
}

void printBleStatus() {
  Serial.printf(
    "BLE init=%s state=%s advertise=%s connected=%s secure=%s mtu=%u cooldown=%lums\r\n",
    bleInitialized ? "YES" : "NO",
    bleStateText(bleState),
    bleAdvertisingEnabled ? "ON" : "OFF",
    bleConnectedFlag ? "YES" : "NO",
    bleAuthSuccessFlag ? "YES" : "NO",
    static_cast<unsigned>(bleNegotiatedMtu),
    timeReached(millis(), bleCooldownUntilMs) ? 0UL :
      static_cast<unsigned long>(bleCooldownUntilMs - millis())
  );
  Serial.printf(
    "bonds=%d commands=%lu malformed=%lu dropped=%lu heap=%u largest=%u\r\n",
    bleInitialized ? NimBLEDevice::getNumBonds() : 0,
    static_cast<unsigned long>(statBleCommands),
    static_cast<unsigned long>(statBleMalformed),
    static_cast<unsigned long>(statBleDropped),
    static_cast<unsigned>(ESP.getFreeHeap()),
    static_cast<unsigned>(largestFreeHeapBytes())
  );
}

void clearRuntimeState() {
  for (auto& neighbor : neighbors) neighbor = NeighborEntry{};
  // Deliberately DO NOT clear hop/message/relay replay windows here. Resetting them
  // from a diagnostics command would reopen acceptance of captured old frames.

  statRxValid = 0;
  statRxAuthFail = 0;
  statRxMalformed = 0;
  statRxDuplicate = 0;
  statRxTooOld = 0;
  statTxFrames = 0;
  statTxErrors = 0;
  statAckSuccess = 0;
  statAckTimeout = 0;
  statMessagesReceived = 0;
  statMessagesDelivered = 0;
  statMessagesForwarded = 0;
  statMessageAuthFail = 0;
  statMessageDuplicate = 0;
  statMessageTooOld = 0;
  statRadioRecoveries = 0;
  statRelayLogicalDuplicate = 0;

  Serial.println("[OK] runtime neighbor/statistics cleared; routes and replay windows preserved");
}

void processConsoleCommand(char* command) {
  if (command == nullptr) return;
  while (*command == ' ' || *command == '\t') command++;
  size_t length = strlen(command);
  while (length > 0 && (command[length - 1] == ' ' || command[length - 1] == '\t')) {
    command[--length] = '\0';
  }
  if (length == 0) return;

  if (strcasecmp(command, "help") == 0) { printHelp(); return; }

  CommandRequest request;
  request.source = CommandSource::Serial;
  CommandResult result;

  if (strcasecmp(command, "id") == 0) {
    request.type = CommandType::GetInfo;
    dispatchCommand(request, result);
    Serial.printf("Node ID: %s protocol=%u firmware=%u.%u.%u\r\n", localIdText, BLE_PROTOCOL_VERSION, FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
    return;
  }
  if (strcasecmp(command, "status") == 0) {
    request.type = CommandType::GetStatus;
    dispatchCommand(request, result);
    printStatus(); return;
  }
  if (strcasecmp(command, "nodes") == 0) {
    request.type = CommandType::GetNeighbors;
    dispatchCommand(request, result);
    printNeighbors(); return;
  }
  if (strcasecmp(command, "routes") == 0) {
    request.type = CommandType::GetRoutes;
    dispatchCommand(request, result);
    printRoutingTable(); return;
  }
  if (strcasecmp(command, "test status") == 0) {
    request.type = CommandType::GetFieldTestStatus;
    dispatchCommand(request, result);
    printFieldTestStatus(); return;
  }
  if (strcasecmp(command, "test stop") == 0) {
    request.type = CommandType::StopFieldTest;
    dispatchCommand(request, result);
    Serial.printf("[TEST STOP] status=%u\r\n", static_cast<unsigned>(result.status));
    return;
  }
  if (strncasecmp(command, "test start ", 11) == 0) {
    char* save = nullptr;
    char* token = strtok_r(command + 11, " ", &save);
    char* countText = strtok_r(nullptr, " ", &save);
    char* intervalText = strtok_r(nullptr, " ", &save);
    char* sizeText = strtok_r(nullptr, " ", &save);
    char* modeText = strtok_r(nullptr, " ", &save);
    if (token == nullptr || countText == nullptr || intervalText == nullptr || sizeText == nullptr ||
        !parseNodeIdText(token, request.destination)) {
      Serial.println("[ERROR] test start <DEST> <COUNT> <INTERVAL_MS> <SIZE> [routed|direct]");
      return;
    }
    request.packetCount = static_cast<uint16_t>(strtoul(countText, nullptr, 10));
    request.intervalMs = static_cast<uint32_t>(strtoul(intervalText, nullptr, 10));
    request.payloadSize = static_cast<uint8_t>(strtoul(sizeText, nullptr, 10));
    request.testMode = (modeText != nullptr && strcasecmp(modeText, "direct") == 0)
      ? FieldTestMode::DirectOnly : FieldTestMode::Routed;
    request.type = CommandType::StartFieldTest;
    dispatchCommand(request, result);
    Serial.printf("[TEST START RESULT] status=%u\r\n", static_cast<unsigned>(result.status));
    return;
  }
  if (strcasecmp(command, "ble status") == 0) {
    request.type = CommandType::BleStatus;
    dispatchCommand(request, result);
    printBleStatus(); return;
  }
  if (strcasecmp(command, "ble advertise on") == 0 ||
      strcasecmp(command, "ble advertise off") == 0) {
    request.type = CommandType::BleAdvertise;
    request.flag = strcasecmp(command, "ble advertise on") == 0;
    dispatchCommand(request, result);
    Serial.printf("[BLE ADVERTISE] %s status=%u\r\n", request.flag ? "ON" : "OFF", static_cast<unsigned>(result.status));
    return;
  }
  if (strcasecmp(command, "ble bonds") == 0) {
    request.type = CommandType::BleBonds;
    dispatchCommand(request, result);
    if (!bleInitialized) { Serial.println("[BLE] not initialized"); return; }
    const int count = NimBLEDevice::getNumBonds();
    Serial.printf("BLE bonds: %d\r\n", count);
    for (int i = 0; i < count; ++i) {
      Serial.printf("  %d: %s\r\n", i, NimBLEDevice::getBondedAddress(i).toString().c_str());
    }
    return;
  }
  if (strcasecmp(command, "ble bonds clear") == 0) {
    request.type = CommandType::BleBondsClear;
    dispatchCommand(request, result);
    Serial.printf("[BLE BONDS CLEAR] status=%u\r\n", static_cast<unsigned>(result.status));
    return;
  }

  if (strncasecmp(command, "route add ", 10) == 0) {
    char* destinationText = command + 10;
    while (*destinationText == ' ') destinationText++;
    char* nextHopText = strchr(destinationText, ' ');
    if (nextHopText == nullptr) { Serial.println("[ERROR] use: route add <DEST> <NEXTHOP>"); return; }
    *nextHopText++ = '\0'; while (*nextHopText == ' ') nextHopText++;
    char* extra = strchr(nextHopText, ' '); if (extra != nullptr) *extra = '\0';
    if (!parseNodeIdText(destinationText, request.destination) ||
        !parseNodeIdText(nextHopText, request.nextHop)) {
      Serial.println("[ERROR] invalid route"); return;
    }
    request.type = CommandType::AddStaticRoute;
    dispatchCommand(request, result);
    if (result.status == CommandStatus::Ok) {
      Serial.printf("[ROUTE SET] destination=%08lX nextHop=%08lX\r\n",
        static_cast<unsigned long>(request.destination), static_cast<unsigned long>(request.nextHop));
    } else Serial.printf("[ERROR] route add status=%u\r\n", static_cast<unsigned>(result.status));
    return;
  }

  if (strncasecmp(command, "route del ", 10) == 0) {
    char* destinationText = command + 10; while (*destinationText == ' ') destinationText++;
    if (!parseNodeIdText(destinationText, request.destination)) {
      Serial.println("[ERROR] use: route del <DEST>"); return;
    }
    request.type = CommandType::RemoveStaticRoute;
    dispatchCommand(request, result);
    Serial.printf("[ROUTE DEL] destination=%08lX status=%u\r\n",
      static_cast<unsigned long>(request.destination), static_cast<unsigned>(result.status));
    return;
  }

  if (strncasecmp(command, "send ", 5) == 0) {
    char* idStart = command + 5; while (*idStart == ' ') idStart++;
    char* textStart = strchr(idStart, ' ');
    if (textStart == nullptr) { Serial.println("[ERROR] use: send <DEST> <text>"); return; }
    *textStart++ = '\0'; while (*textStart == ' ') textStart++;
    if (!parseNodeIdText(idStart, request.destination) || request.destination == localNodeId) {
      Serial.println("[ERROR] invalid/self destination"); return;
    }
    const size_t textLength = strlen(textStart);
    if (textLength == 0 || textLength > MAX_APP_PAYLOAD) {
      Serial.printf("[ERROR] payload limit 1..%u bytes\r\n", static_cast<unsigned>(MAX_APP_PAYLOAD)); return;
    }
    request.type = CommandType::SendMessage;
    request.dataLength = static_cast<uint8_t>(textLength);
    memcpy(request.data, textStart, textLength);
    dispatchCommand(request, result);
    Serial.printf("[SEND RESULT] destination=%08lX status=%u\r\n",
      static_cast<unsigned long>(request.destination), static_cast<unsigned>(result.status));
    return;
  }

  if (strncasecmp(command, "broadcast ", 10) == 0) {
    char* textStart = command + 10; while (*textStart == ' ') textStart++;
    const size_t textLength = strlen(textStart);
    if (textLength == 0 || textLength > MAX_APP_PAYLOAD) {
      Serial.printf("[ERROR] broadcast payload 1..%u bytes\r\n", static_cast<unsigned>(MAX_APP_PAYLOAD)); return;
    }
    request.type = CommandType::Broadcast;
    request.dataLength = static_cast<uint8_t>(textLength);
    memcpy(request.data, textStart, textLength);
    dispatchCommand(request, result);
    Serial.printf("[BROADCAST RESULT] status=%u\r\n", static_cast<unsigned>(result.status));
    return;
  }

  if (strcasecmp(command, "ui state") == 0) {
    request.type = CommandType::GetUiState;
    dispatchCommand(request, result);
    Serial.printf("[UI] scene=%u menu=%u inbox=%u neighbors=%u routes=%u\r\n",
      static_cast<unsigned>(uiGetSceneCode()),
      static_cast<unsigned>(uiGetMenuIndex()),
      static_cast<unsigned>(uiGetInboxCount()),
      static_cast<unsigned>(countFreshNeighbors()),
      static_cast<unsigned>(uiCountVisibleRoutes()));
    return;
  }
  if (strncasecmp(command, "ui ", 3) == 0) {
    const char* action = command + 3;
    uint8_t rawAction = 0;
    if (strcasecmp(action, "up") == 0) rawAction = 1;
    else if (strcasecmp(action, "down") == 0) rawAction = 2;
    else if (strcasecmp(action, "ok") == 0) rawAction = 3;
    else if (strcasecmp(action, "back") == 0) rawAction = 4;
    else if (strcasecmp(action, "home") == 0) rawAction = 5;
    else { Serial.println("[ERROR] ui up|down|ok|back|home"); return; }
    request.type = CommandType::UiAction;
    request.uiAction = rawAction;
    dispatchCommand(request, result);
    Serial.printf("[UI ACTION] status=%u scene=%u\r\n",
      static_cast<unsigned>(result.status), static_cast<unsigned>(uiGetSceneCode()));
    return;
  }

  if (strcasecmp(command, "clear") == 0) { clearRuntimeState(); return; }
  if (strcasecmp(command, "reboot") == 0) {
    request.type = CommandType::Reboot;
    dispatchCommand(request, result);
    Serial.println("Rebooting..."); Serial.flush(); ESP.restart(); return;
  }

  Serial.println("[ERROR] unknown command. Type: help");
}

void processSerialInput() {
  while (Serial.available() > 0) {
    const char c = static_cast<char>(Serial.read());

    if (c == '\r' || c == '\n') {
      if (serialLineLength > 0) {
        serialLineBuffer[serialLineLength] = '\0';
        processConsoleCommand(serialLineBuffer);
        serialLineLength = 0;
      }
      continue;
    }

    if (serialLineLength < CONSOLE_LINE_SIZE - 1) {
      serialLineBuffer[serialLineLength++] = c;
    } else {
      serialLineLength = 0;
      Serial.println("[ERROR] console line too long");
    }
  }
}

// ============================================================
// 21. SECUREMESH UI OS v0.2 - HIERARCHICAL DEVICE SHELL
// ============================================================
// The OLED is a local device shell, not a second networking stack. UI actions
// only navigate/read existing services. Planned modules are visible in the
// information architecture but explicitly marked as PLAN until their backend
// exists. This keeps the product model stable without pretending future
// SecureMesh features are already implemented.

constexpr int OLED_WIDTH = static_cast<int>(BLE_OLED_FRAME_WIDTH);
constexpr int OLED_HEIGHT = static_cast<int>(BLE_OLED_FRAME_HEIGHT);
constexpr int OLED_RESET = -1;
static_assert(OLED_WIDTH == 128 && OLED_HEIGHT == 64, "OLED geometry must match BLE framebuffer contract");
constexpr size_t UI_INBOX_SIZE = 6;
constexpr uint8_t UI_MODEL_VERSION = 4;
constexpr uint8_t UI_NAV_DEPTH = 5;
constexpr uint8_t UI_MENU_VISIBLE_ROWS = 3;
constexpr int16_t UI_MENU_FIRST_Y = 21;
constexpr int16_t UI_MENU_ROW_H = 11;
constexpr size_t UI_NOTIFICATION_QUEUE_SIZE = 4;

Adafruit_SSD1306 display(OLED_WIDTH, OLED_HEIGHT, &Wire, OLED_RESET);
bool oledReady = false;

enum class UiScene : uint8_t {
  Home = 0,
  Menu = 1,
  Feature = 2
};

enum class UiAction : uint8_t {
  Up = 1,
  Down = 2,
  Select = 3,
  Back = 4,
  Home = 5
};

enum class UiMenuId : uint8_t {
  Root = 0,
  Messaging = 1,
  Network = 2,
  Radio = 3,
  Navigation = 4,
  Sos = 5,
  Security = 6,
  Diagnostics = 7,
  System = 8,
  Quick = 9
};

enum class UiFeatureId : uint8_t {
  None = 0,

  Inbox,
  Compose,
  Delivery,
  DeferredDelivery,
  Fragmentation,
  TrafficPriority,

  Neighbors,
  Routes,
  Topology,
  DynamicRouting,
  RouteForecast,
  OpportunisticRelay,
  LoadManagement,

  RadioStatus,
  Spectrum,
  ChannelControl,
  RadioProfiles,
  AdaptiveLink,
  PowerControl,
  RadioSilence,
  ShortPackets,

  Gps,
  Positions,
  OfflineMap,
  Geozones,
  NetworkHistory,

  SosStatus,
  SosSend,
  SosTypes,
  EmergencyProfile,

  BleSession,
  AccessLock,
  Fingerprint,
  RolesPermissions,
  KeyManagement,
  NodeManagement,
  CryptoWipe,
  FirmwareProtection,

  FieldTest,
  SelfTest,
  LinkMetrics,
  EventLog,
  Memory,

  SystemOverview,
  Power,
  NetworkTime,
  PowerSaving,
  Firmware,
  Ota,
  About,
  BleRadar
};

enum class UiItemKind : uint8_t {
  Submenu = 0,
  Feature = 1
};

enum class UiFeatureState : uint8_t {
  Live = 0,
  Planned = 1
};

enum class UiIcon : uint8_t {
  Message = 0,
  Network,
  Radio,
  Navigation,
  Alert,
  Shield,
  Diagnostics,
  Settings,
  Route,
  Ble,
  Memory,
  Power,
  Key,
  Log,
  Update,
  Clock,
  Map,
  Lock,
  Test
};

enum class UiOverlayKind : uint8_t {
  None = 0,
  Pairing,
  Connected
};

struct UiMenuItem {
  const char* label = "";
  UiItemKind kind = UiItemKind::Feature;
  UiMenuId submenu = UiMenuId::Root;
  UiFeatureId feature = UiFeatureId::None;
  UiFeatureState state = UiFeatureState::Planned;
  UiIcon icon = UiIcon::Settings;
  const char* hint = "";
};

struct UiMenuDefinition {
  const char* title = "";
  const UiMenuItem* items = nullptr;
  uint8_t count = 0;
};

struct UiMessageEntry {
  bool used = false;
  uint32_t origin = 0;
  uint32_t receivedAtMs = 0;
  uint8_t length = 0;
  char text[49] {};
};

struct UiNotification {
  bool used = false;
  uint32_t durationMs = UI_TOAST_DEFAULT_MS;
  char title[28] {};
  char body[36] {};
};

struct UiRuntimeState {
  bool initialized = false;
  bool bootFinished = false;
  bool dirty = true;
  UiScene scene = UiScene::Home;
  UiFeatureId feature = UiFeatureId::None;
  uint8_t navDepth = 1;
  UiMenuId navMenus[UI_NAV_DEPTH] {UiMenuId::Root, UiMenuId::Root, UiMenuId::Root, UiMenuId::Root};
  uint8_t navIndex[UI_NAV_DEPTH] {};
  uint8_t navScroll[UI_NAV_DEPTH] {};
  uint8_t messageIndex = 0;
  uint8_t neighborIndex = 0;
  uint8_t routeIndex = 0;
  uint8_t positionIndex = 0;
  uint8_t unreadCount = 0;
  int16_t menuCursorY = UI_MENU_FIRST_Y;
  uint32_t bootStartedAtMs = 0;
  uint32_t nextFrameAtMs = 0;
  uint32_t transitionStartedAtMs = 0;
  uint32_t lastFlushAtMs = 0;
  uint32_t criticalPendingSinceMs = 0;
  UiOverlayKind overlay = UiOverlayKind::None;
  UiOverlayKind previousOverlay = UiOverlayKind::None;
  uint32_t overlayEnteredAtMs = 0;
  uint32_t toastStartedAtMs = 0;
  uint32_t toastUntilMs = 0;
  char toastTitle[32] {};
  char toastBody[40] {};
  FieldTestState lastFieldTestState = FieldTestState::Idle;
};

struct UiRouteView {
  bool valid = false;
  uint32_t destination = 0;
  uint32_t nextHop = 0;
  RouteSource source = RouteSource::None;
};

UiRuntimeState uiState;
UiMessageEntry uiInbox[UI_INBOX_SIZE];
uint8_t uiInboxWriteIndex = 0;
uint8_t uiInboxCount = 0;
UiNotification uiNotificationQueue[UI_NOTIFICATION_QUEUE_SIZE];
uint8_t uiNotificationHead = 0;
uint8_t uiNotificationTail = 0;
uint8_t uiNotificationCount = 0;

// 5x7 uppercase Cyrillic. Lowercase UTF-8 is mapped to uppercase while drawing.
// Row bits use 0b10000 as the left-most pixel. Keeping this font local avoids a
// second GUI/font dependency and makes the firmware self-contained.
static const uint8_t UI_CYRILLIC_5X7[33][7] = {
  {0x0E,0x11,0x11,0x1F,0x11,0x11,0x11}, // А
  {0x1F,0x10,0x10,0x1E,0x11,0x11,0x1E}, // Б
  {0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E}, // В
  {0x1F,0x10,0x10,0x10,0x10,0x10,0x10}, // Г
  {0x0E,0x0A,0x0A,0x0A,0x1F,0x11,0x11}, // Д
  {0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F}, // Е
  {0x0A,0x00,0x1F,0x10,0x1E,0x10,0x1F}, // Ё
  {0x15,0x15,0x0E,0x04,0x0E,0x15,0x15}, // Ж
  {0x1E,0x01,0x01,0x0E,0x01,0x01,0x1E}, // З
  {0x11,0x13,0x15,0x15,0x19,0x11,0x11}, // И
  {0x0A,0x04,0x11,0x13,0x15,0x19,0x11}, // Й
  {0x11,0x12,0x14,0x18,0x14,0x12,0x11}, // К
  {0x07,0x09,0x09,0x09,0x09,0x09,0x11}, // Л
  {0x11,0x1B,0x15,0x15,0x11,0x11,0x11}, // М
  {0x11,0x11,0x11,0x1F,0x11,0x11,0x11}, // Н
  {0x0E,0x11,0x11,0x11,0x11,0x11,0x0E}, // О
  {0x1F,0x11,0x11,0x11,0x11,0x11,0x11}, // П
  {0x1E,0x11,0x11,0x1E,0x10,0x10,0x10}, // Р
  {0x0F,0x10,0x10,0x10,0x10,0x10,0x0F}, // С
  {0x1F,0x04,0x04,0x04,0x04,0x04,0x04}, // Т
  {0x11,0x11,0x0A,0x04,0x04,0x08,0x10}, // У
  {0x04,0x0E,0x15,0x15,0x0E,0x04,0x04}, // Ф
  {0x11,0x11,0x0A,0x04,0x0A,0x11,0x11}, // Х
  {0x12,0x12,0x12,0x12,0x12,0x1F,0x01}, // Ц
  {0x11,0x11,0x11,0x0F,0x01,0x01,0x01}, // Ч
  {0x15,0x15,0x15,0x15,0x15,0x15,0x1F}, // Ш
  {0x15,0x15,0x15,0x15,0x15,0x1F,0x01}, // Щ
  {0x18,0x08,0x08,0x0E,0x09,0x09,0x0E}, // Ъ
  {0x11,0x11,0x1D,0x15,0x15,0x15,0x1D}, // Ы
  {0x10,0x10,0x10,0x1E,0x11,0x11,0x1E}, // Ь
  {0x1E,0x01,0x01,0x0F,0x01,0x01,0x1E}, // Э
  {0x17,0x15,0x15,0x1D,0x15,0x15,0x17}, // Ю
  {0x0F,0x11,0x11,0x0F,0x05,0x09,0x11}  // Я
};

// -------------------- Product information architecture --------------------
// LIVE means the backend already exists in this firmware. PLAN means the
// section is intentionally reserved for the roadmap but no command is emitted.

static const UiMenuItem UI_QUICK_ITEMS[] = {
  {"ВХОДЯЩИЕ", UiItemKind::Feature, UiMenuId::Quick, UiFeatureId::Inbox, UiFeatureState::Live, UiIcon::Message, "ПРОЧИТАТЬ ПОСЛЕДНИЕ СООБЩЕНИЯ"},
  {"СЕТЬ", UiItemKind::Feature, UiMenuId::Quick, UiFeatureId::Neighbors, UiFeatureState::Live, UiIcon::Network, "КТО РЯДОМ И КАЧЕСТВО СВЯЗИ"},
  {"ТЕСТ СВЯЗИ", UiItemKind::Feature, UiMenuId::Quick, UiFeatureId::FieldTest, UiFeatureState::Live, UiIcon::Test, "ПРОВЕРИТЬ, ДОХОДИТ ЛИ СВЯЗЬ"},
  {"ПУТИ СВЯЗИ", UiItemKind::Feature, UiMenuId::Quick, UiFeatureId::Routes, UiFeatureState::Live, UiIcon::Route, "КАК СВЯЗЬ ДОХОДИТ ДО ДРУГИХ УЗЛОВ"},
  {"ВСЕ ФУНКЦИИ", UiItemKind::Submenu, UiMenuId::Root, UiFeatureId::None, UiFeatureState::Live, UiIcon::Settings, "ВСЕ ВОЗМОЖНОСТИ УСТРОЙСТВА"}
};

static const UiMenuItem UI_ROOT_ITEMS[] = {
  {"СООБЩЕНИЯ", UiItemKind::Submenu, UiMenuId::Messaging, UiFeatureId::None, UiFeatureState::Live, UiIcon::Message, "ПЕРЕПИСКА И ДОСТАВКА"},
  {"СЕТЬ", UiItemKind::Submenu, UiMenuId::Network, UiFeatureId::None, UiFeatureState::Live, UiIcon::Network, "УЗЛЫ И ПУТИ СВЯЗИ"},
  {"СВЯЗЬ", UiItemKind::Submenu, UiMenuId::Radio, UiFeatureId::None, UiFeatureState::Live, UiIcon::Radio, "НАСТРОЙКИ РАДИОСВЯЗИ"},
  {"НАВИГАЦИЯ", UiItemKind::Submenu, UiMenuId::Navigation, UiFeatureId::None, UiFeatureState::Live, UiIcon::Navigation, "ГДЕ Я И ГДЕ ДРУГИЕ"},
  {"SOS", UiItemKind::Submenu, UiMenuId::Sos, UiFeatureId::None, UiFeatureState::Live, UiIcon::Alert, "ТРЕВОГА И ПОДТВЕРЖДЕНИЕ"},
  {"БЕЗОПАСНОСТЬ", UiItemKind::Submenu, UiMenuId::Security, UiFeatureId::None, UiFeatureState::Live, UiIcon::Shield, "ЗАЩИТА И ДОСТУП"},
  {"ПРОВЕРКА", UiItemKind::Submenu, UiMenuId::Diagnostics, UiFeatureId::None, UiFeatureState::Live, UiIcon::Diagnostics, "СОСТОЯНИЕ И ПРОВЕРКА СВЯЗИ"},
  {"СИСТЕМА", UiItemKind::Submenu, UiMenuId::System, UiFeatureId::None, UiFeatureState::Live, UiIcon::Settings, "УСТРОЙСТВО И ВЕРСИЯ"}
};

static const UiMenuItem UI_MESSAGING_ITEMS[] = {
  {"ВХОДЯЩИЕ", UiItemKind::Feature, UiMenuId::Messaging, UiFeatureId::Inbox, UiFeatureState::Live, UiIcon::Message, "ПОСЛЕДНИЕ СООБЩЕНИЯ"},
  {"ОТПРАВИТЬ", UiItemKind::Feature, UiMenuId::Messaging, UiFeatureId::Compose, UiFeatureState::Live, UiIcon::Message, "НАПИСАТЬ УЗЛУ ИЗ ПРИЛОЖЕНИЯ"}
};

static const UiMenuItem UI_NETWORK_ITEMS[] = {
  {"СОСЕДИ", UiItemKind::Feature, UiMenuId::Network, UiFeatureId::Neighbors, UiFeatureState::Live, UiIcon::Network, "КТО РЯДОМ И КАЧЕСТВО СВЯЗИ"},
  {"ПУТИ СВЯЗИ", UiItemKind::Feature, UiMenuId::Network, UiFeatureId::Routes, UiFeatureState::Live, UiIcon::Route, "КАК ДОХОДИТ СВЯЗЬ ДО ДРУГИХ УЗЛОВ"}
};

static const UiMenuItem UI_RADIO_ITEMS[] = {
  {"СТАТУС СВЯЗИ", UiItemKind::Feature, UiMenuId::Radio, UiFeatureId::RadioStatus, UiFeatureState::Live, UiIcon::Radio, "ГОТОВА ЛИ РАДИОСВЯЗЬ"}
};

static const UiMenuItem UI_NAVIGATION_ITEMS[] = {
  {"МОЯ ПОЗИЦИЯ", UiItemKind::Feature, UiMenuId::Navigation, UiFeatureId::Gps, UiFeatureState::Live, UiIcon::Navigation, "КООРДИНАТЫ И СПУТНИКИ"},
  {"ГДЕ УЗЛЫ", UiItemKind::Feature, UiMenuId::Navigation, UiFeatureId::Positions, UiFeatureState::Live, UiIcon::Network, "КООРДИНАТЫ УЗЛОВ"},
  {"КАРТА", UiItemKind::Feature, UiMenuId::Navigation, UiFeatureId::OfflineMap, UiFeatureState::Live, UiIcon::Map, "КАРТА В ПРИЛОЖЕНИИ"}
};

static const UiMenuItem UI_SOS_ITEMS[] = {
  {"СТАТУС SOS", UiItemKind::Feature, UiMenuId::Sos, UiFeatureId::SosStatus, UiFeatureState::Live, UiIcon::Alert, "СОСТОЯНИЕ ТРЕВОГИ"},
  {"ОТПРАВИТЬ SOS", UiItemKind::Feature, UiMenuId::Sos, UiFeatureId::SosSend, UiFeatureState::Live, UiIcon::Alert, "ПРИОРИТЕТНАЯ ТРЕВОГА"}
};

static const UiMenuItem UI_SECURITY_ITEMS[] = {
  {"ТЕЛЕФОН", UiItemKind::Feature, UiMenuId::Security, UiFeatureId::BleSession, UiFeatureState::Live, UiIcon::Ble, "ПОДКЛЮЧЕНИЕ К ПРИЛОЖЕНИЮ"},
  {"РАДАР УСТРОЙСТВ", UiItemKind::Feature, UiMenuId::Security, UiFeatureId::BleRadar, UiFeatureState::Live, UiIcon::Ble, "УСТРОЙСТВА BLUETOOTH РЯДОМ"}
};

static const UiMenuItem UI_DIAGNOSTICS_ITEMS[] = {
  {"ТЕСТ СВЯЗИ", UiItemKind::Feature, UiMenuId::Diagnostics, UiFeatureId::FieldTest, UiFeatureState::Live, UiIcon::Test, "ПРОВЕРИТЬ ДОСТАВКУ И ОТВЕТ"},
  {"ПРОВЕРКА УЗЛА", UiItemKind::Feature, UiMenuId::Diagnostics, UiFeatureId::SelfTest, UiFeatureState::Live, UiIcon::Diagnostics, "СВЯЗЬ, ЗАЩИТА, ЭКРАН, ТЕЛЕФОН"},
  {"КАЧЕСТВО СВЯЗИ", UiItemKind::Feature, UiMenuId::Diagnostics, UiFeatureId::LinkMetrics, UiFeatureState::Live, UiIcon::Diagnostics, "ДОСТАВКА, ПОТЕРИ И ОШИБКИ"},
  {"СОСТОЯНИЕ УЗЛА", UiItemKind::Feature, UiMenuId::Diagnostics, UiFeatureId::Memory, UiFeatureState::Live, UiIcon::Memory, "ХВАТАЕТ ЛИ УСТРОЙСТВУ РЕСУРСОВ"}
};

static const UiMenuItem UI_SYSTEM_ITEMS[] = {
  {"ОБЗОР", UiItemKind::Feature, UiMenuId::System, UiFeatureId::SystemOverview, UiFeatureState::Live, UiIcon::Settings, "УЗЕЛ, ВРЕМЯ РАБОТЫ, СОСТОЯНИЕ"},
  {"ВЕРСИЯ", UiItemKind::Feature, UiMenuId::System, UiFeatureId::Firmware, UiFeatureState::Live, UiIcon::Update, "SECUREMESH И VANGUARD"},
  {"ОБ УСТРОЙСТВЕ", UiItemKind::Feature, UiMenuId::System, UiFeatureId::About, UiFeatureState::Live, UiIcon::Settings, "SECUREMESH"}
};

uint8_t detectOledAddress() {
  const uint8_t addresses[] = {0x3C, 0x3D};
  for (uint8_t address : addresses) {
    Wire.beginTransmission(address);
    if (Wire.endTransmission() == 0) return address;
  }
  return 0;
}

void initializeOled() {
  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  Wire.setClock(400000);

  const uint8_t address = detectOledAddress();
  if (address == 0) return;

  oledReady = display.begin(SSD1306_SWITCHCAPVCC, address);
  if (!oledReady) return;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextWrap(false);
  display.display();
}

void uiMarkDirty() {
  uiState.dirty = true;
}

uint32_t uiReadCodepoint(const char*& cursor) {
  if (cursor == nullptr || *cursor == '\0') return 0;
  const uint8_t c0 = static_cast<uint8_t>(*cursor++);
  if ((c0 & 0x80U) == 0) return c0;
  const uint8_t c1 = static_cast<uint8_t>(*cursor);
  if ((c0 & 0xE0U) == 0xC0U && (c1 & 0xC0U) == 0x80U) {
    cursor++;
    return ((static_cast<uint32_t>(c0 & 0x1FU) << 6) | (c1 & 0x3FU));
  }
  return '?';
}

int uiCyrillicGlyphIndex(uint32_t codepoint) {
  if (codepoint == 0x0451) codepoint = 0x0401; // ё -> Ё
  if (codepoint >= 0x0430 && codepoint <= 0x044F) codepoint -= 0x20;
  if (codepoint == 0x0401) return 6;
  if (codepoint >= 0x0410 && codepoint <= 0x0415) return static_cast<int>(codepoint - 0x0410);
  if (codepoint >= 0x0416 && codepoint <= 0x042F) return static_cast<int>(codepoint - 0x0410 + 1);
  return -1;
}

void uiDrawCodepoint(int16_t x, int16_t y, uint32_t codepoint, uint8_t scale, uint16_t color) {
  if (scale == 0) scale = 1;
  if (codepoint >= 32 && codepoint <= 126) {
    display.drawChar(x, y, static_cast<unsigned char>(codepoint), color, color, scale);
    return;
  }

  const int glyph = uiCyrillicGlyphIndex(codepoint);
  if (glyph < 0 || glyph >= 33) {
    display.drawChar(x, y, '?', color, color, scale);
    return;
  }

  for (uint8_t row = 0; row < 7; ++row) {
    const uint8_t bits = UI_CYRILLIC_5X7[glyph][row];
    for (uint8_t col = 0; col < 5; ++col) {
      if ((bits & (1U << (4U - col))) == 0) continue;
      if (scale == 1) display.drawPixel(x + col, y + row, color);
      else display.fillRect(x + col * scale, y + row * scale, scale, scale, color);
    }
  }
}

int16_t uiTextWidth(const char* text, uint8_t scale = 1) {
  if (text == nullptr || *text == '\0') return 0;
  int16_t glyphs = 0;
  const char* cursor = text;
  while (*cursor != '\0') {
    const uint32_t cp = uiReadCodepoint(cursor);
    if (cp == 0) break;
    glyphs++;
  }
  return glyphs == 0 ? 0 : static_cast<int16_t>(glyphs * 6 * scale - scale);
}

int16_t uiDrawText(int16_t x, int16_t y, const char* text, uint8_t scale = 1, uint16_t color = SSD1306_WHITE) {
  if (text == nullptr) return x;
  const char* cursor = text;
  while (*cursor != '\0') {
    const uint32_t cp = uiReadCodepoint(cursor);
    if (cp == 0) break;
    uiDrawCodepoint(x, y, cp, scale, color);
    x += 6 * scale;
    if (x >= OLED_WIDTH) break;
  }
  return x;
}

int16_t uiDrawTextClipped(int16_t x, int16_t y, const char* text, int16_t maxX, uint8_t scale = 1, uint16_t color = SSD1306_WHITE) {
  if (text == nullptr) return x;
  const char* cursor = text;
  while (*cursor != '\0') {
    const uint32_t cp = uiReadCodepoint(cursor);
    if (cp == 0 || x + 5 * scale > maxX) break;
    uiDrawCodepoint(x, y, cp, scale, color);
    x += 6 * scale;
  }
  return x;
}

void uiDrawCenteredText(int16_t y, const char* text, uint8_t scale = 1, uint16_t color = SSD1306_WHITE) {
  const int16_t width = uiTextWidth(text, scale);
  uiDrawText((OLED_WIDTH - width) / 2, y, text, scale, color);
}

void uiDrawWrappedText(const char* text, int16_t x, int16_t y, int16_t width, uint8_t maxLines, uint16_t color = SSD1306_WHITE) {
  if (text == nullptr || maxLines == 0 || width < 6) return;
  const int16_t startX = x;
  const int16_t maxX = x + width;
  uint8_t line = 0;
  const char* cursor = text;
  while (*cursor != '\0' && line < maxLines) {
    const uint32_t cp = uiReadCodepoint(cursor);
    if (cp == 0) break;
    if (cp == '\n') {
      line++;
      x = startX;
      y += 8;
      continue;
    }
    if (x + 5 > maxX) {
      line++;
      if (line >= maxLines) break;
      x = startX;
      y += 8;
      if (cp == ' ') continue;
    }
    uiDrawCodepoint(x, y, cp, 1, color);
    x += 6;
  }
}

void uiDrawBleIcon(int16_t x, int16_t y, bool active) {
  display.drawLine(x + 3, y, x + 3, y + 10, SSD1306_WHITE);
  display.drawLine(x + 3, y, x + 7, y + 3, SSD1306_WHITE);
  display.drawLine(x + 7, y + 3, x + 3, y + 5, SSD1306_WHITE);
  display.drawLine(x + 3, y + 5, x + 7, y + 8, SSD1306_WHITE);
  display.drawLine(x + 7, y + 8, x + 3, y + 10, SSD1306_WHITE);
  display.drawLine(x, y + 2, x + 6, y + 8, SSD1306_WHITE);
  display.drawLine(x, y + 8, x + 6, y + 2, SSD1306_WHITE);
  if (!active) display.drawLine(x, y + 10, x + 8, y, SSD1306_WHITE);
}

void uiDrawRadioIcon(int16_t x, int16_t y, bool active) {
  if (!active) {
    display.drawCircle(x + 4, y + 5, 2, SSD1306_WHITE);
    display.drawLine(x, y + 9, x + 8, y + 1, SSD1306_WHITE);
    return;
  }
  display.fillCircle(x + 4, y + 7, 1, SSD1306_WHITE);
  display.drawLine(x + 4, y + 6, x + 4, y + 2, SSD1306_WHITE);
  display.drawLine(x + 1, y + 5, x + 2, y + 3, SSD1306_WHITE);
  display.drawLine(x + 7, y + 5, x + 6, y + 3, SSD1306_WHITE);
  display.drawPixel(x, y + 2, SSD1306_WHITE);
  display.drawPixel(x + 8, y + 2, SSD1306_WHITE);
}

void uiDrawNodesIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.fillCircle(x + 2, y + 2, 1, color);
  display.fillCircle(x + 8, y + 2, 1, color);
  display.fillCircle(x + 5, y + 7, 1, color);
  display.drawLine(x + 3, y + 2, x + 7, y + 2, color);
  display.drawLine(x + 3, y + 3, x + 5, y + 6, color);
  display.drawLine(x + 7, y + 3, x + 5, y + 6, color);
}

void uiDrawEnvelopeIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawRect(x, y + 1, 10, 7, color);
  display.drawLine(x, y + 1, x + 5, y + 5, color);
  display.drawLine(x + 10, y + 1, x + 5, y + 5, color);
}

void uiDrawRouteIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.fillCircle(x + 1, y + 7, 1, color);
  display.drawLine(x + 2, y + 7, x + 7, y + 2, color);
  display.drawLine(x + 7, y + 2, x + 6, y + 5, color);
  display.drawLine(x + 7, y + 2, x + 4, y + 3, color);
}

void uiDrawTestIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawLine(x, y + 7, x + 2, y + 7, color);
  display.drawLine(x + 2, y + 7, x + 4, y + 2, color);
  display.drawLine(x + 4, y + 2, x + 6, y + 8, color);
  display.drawLine(x + 6, y + 8, x + 9, y + 4, color);
}

void uiDrawGearIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawCircle(x + 5, y + 5, 4, color);
  display.fillCircle(x + 5, y + 5, 1, color);
  display.drawPixel(x + 5, y, color);
  display.drawPixel(x + 5, y + 10, color);
  display.drawPixel(x, y + 5, color);
  display.drawPixel(x + 10, y + 5, color);
}

void uiDrawShieldIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawLine(x + 5, y, x + 9, y + 2, color);
  display.drawLine(x + 5, y, x + 1, y + 2, color);
  display.drawLine(x + 1, y + 2, x + 2, y + 7, color);
  display.drawLine(x + 9, y + 2, x + 8, y + 7, color);
  display.drawLine(x + 2, y + 7, x + 5, y + 10, color);
  display.drawLine(x + 8, y + 7, x + 5, y + 10, color);
}

void uiDrawAlertIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawTriangle(x + 5, y, x, y + 10, x + 10, y + 10, color);
  display.drawFastVLine(x + 5, y + 3, 4, color);
  display.drawPixel(x + 5, y + 8, color);
}

void uiDrawNavigationIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawTriangle(x + 5, y, x + 1, y + 10, x + 5, y + 7, color);
  display.drawLine(x + 5, y, x + 9, y + 10, color);
  display.drawLine(x + 9, y + 10, x + 5, y + 7, color);
}

void uiDrawClockIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawCircle(x + 5, y + 5, 4, color);
  display.drawLine(x + 5, y + 5, x + 5, y + 2, color);
  display.drawLine(x + 5, y + 5, x + 8, y + 6, color);
}

void uiDrawMemoryIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawRect(x + 2, y + 2, 7, 7, color);
  for (uint8_t i = 0; i < 3; ++i) {
    display.drawPixel(x, y + 3 + i * 2, color);
    display.drawPixel(x + 11, y + 3 + i * 2, color);
  }
}

void uiDrawPowerIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawCircle(x + 5, y + 6, 4, color);
  display.fillRect(x + 4, y, 3, 6, SSD1306_BLACK);
  display.drawFastVLine(x + 5, y, 6, color);
}

void uiDrawKeyIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawCircle(x + 3, y + 4, 3, color);
  display.drawLine(x + 6, y + 4, x + 11, y + 9, color);
  display.drawLine(x + 9, y + 7, x + 9, y + 10, color);
}

void uiDrawLogIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawRect(x + 1, y, 9, 11, color);
  display.drawFastHLine(x + 3, y + 3, 5, color);
  display.drawFastHLine(x + 3, y + 6, 5, color);
  display.drawFastHLine(x + 3, y + 9, 4, color);
}

void uiDrawUpdateIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawCircle(x + 5, y + 6, 4, color);
  display.fillTriangle(x + 7, y, x + 11, y + 2, x + 7, y + 4, color);
}

void uiDrawMapIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawLine(x, y + 2, x + 3, y, color);
  display.drawLine(x + 3, y, x + 7, y + 2, color);
  display.drawLine(x + 7, y + 2, x + 10, y, color);
  display.drawLine(x, y + 2, x, y + 10, color);
  display.drawLine(x + 3, y, x + 3, y + 8, color);
  display.drawLine(x + 7, y + 2, x + 7, y + 10, color);
  display.drawLine(x + 10, y, x + 10, y + 8, color);
  display.drawLine(x, y + 10, x + 3, y + 8, color);
  display.drawLine(x + 3, y + 8, x + 7, y + 10, color);
  display.drawLine(x + 7, y + 10, x + 10, y + 8, color);
}

void uiDrawLockIcon(int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  display.drawRect(x + 1, y + 5, 9, 6, color);
  display.drawCircle(x + 5, y + 5, 3, color);
  display.fillRect(x + 2, y + 5, 7, 3, SSD1306_BLACK);
}

void uiDrawIcon(UiIcon icon, int16_t x, int16_t y, uint16_t color = SSD1306_WHITE) {
  switch (icon) {
    case UiIcon::Message: uiDrawEnvelopeIcon(x, y, color); break;
    case UiIcon::Network: uiDrawNodesIcon(x, y, color); break;
    case UiIcon::Radio: {
      if (color == SSD1306_WHITE) uiDrawRadioIcon(x, y, true);
      else {
        display.fillCircle(x + 4, y + 7, 1, color);
        display.drawLine(x + 4, y + 6, x + 4, y + 2, color);
        display.drawLine(x + 1, y + 5, x + 2, y + 3, color);
        display.drawLine(x + 7, y + 5, x + 6, y + 3, color);
      }
      break;
    }
    case UiIcon::Navigation: uiDrawNavigationIcon(x, y, color); break;
    case UiIcon::Alert: uiDrawAlertIcon(x, y, color); break;
    case UiIcon::Shield: uiDrawShieldIcon(x, y, color); break;
    case UiIcon::Diagnostics: uiDrawTestIcon(x, y, color); break;
    case UiIcon::Settings: uiDrawGearIcon(x, y, color); break;
    case UiIcon::Route: uiDrawRouteIcon(x, y, color); break;
    case UiIcon::Ble:
      if (color == SSD1306_WHITE) uiDrawBleIcon(x, y, true);
      else {
        display.drawLine(x + 3, y, x + 3, y + 10, color);
        display.drawLine(x + 3, y, x + 7, y + 3, color);
        display.drawLine(x + 7, y + 3, x + 3, y + 5, color);
        display.drawLine(x + 3, y + 5, x + 7, y + 8, color);
        display.drawLine(x + 7, y + 8, x + 3, y + 10, color);
      }
      break;
    case UiIcon::Memory: uiDrawMemoryIcon(x, y, color); break;
    case UiIcon::Power: uiDrawPowerIcon(x, y, color); break;
    case UiIcon::Key: uiDrawKeyIcon(x, y, color); break;
    case UiIcon::Log: uiDrawLogIcon(x, y, color); break;
    case UiIcon::Update: uiDrawUpdateIcon(x, y, color); break;
    case UiIcon::Clock: uiDrawClockIcon(x, y, color); break;
    case UiIcon::Map: uiDrawMapIcon(x, y, color); break;
    case UiIcon::Lock: uiDrawLockIcon(x, y, color); break;
    case UiIcon::Test: uiDrawTestIcon(x, y, color); break;
  }
}

UiMenuDefinition uiGetMenuDefinition(UiMenuId menu) {
  switch (menu) {
    case UiMenuId::Quick: return {"БЫСТРЫЕ ДЕЙСТВИЯ", UI_QUICK_ITEMS, static_cast<uint8_t>(sizeof(UI_QUICK_ITEMS) / sizeof(UI_QUICK_ITEMS[0]))};
    case UiMenuId::Root: return {"МЕНЮ", UI_ROOT_ITEMS, static_cast<uint8_t>(sizeof(UI_ROOT_ITEMS) / sizeof(UI_ROOT_ITEMS[0]))};
    case UiMenuId::Messaging: return {"СООБЩЕНИЯ", UI_MESSAGING_ITEMS, static_cast<uint8_t>(sizeof(UI_MESSAGING_ITEMS) / sizeof(UI_MESSAGING_ITEMS[0]))};
    case UiMenuId::Network: return {"СЕТЬ", UI_NETWORK_ITEMS, static_cast<uint8_t>(sizeof(UI_NETWORK_ITEMS) / sizeof(UI_NETWORK_ITEMS[0]))};
    case UiMenuId::Radio: return {"РАДИО", UI_RADIO_ITEMS, static_cast<uint8_t>(sizeof(UI_RADIO_ITEMS) / sizeof(UI_RADIO_ITEMS[0]))};
    case UiMenuId::Navigation: return {"НАВИГАЦИЯ", UI_NAVIGATION_ITEMS, static_cast<uint8_t>(sizeof(UI_NAVIGATION_ITEMS) / sizeof(UI_NAVIGATION_ITEMS[0]))};
    case UiMenuId::Sos: return {"SOS", UI_SOS_ITEMS, static_cast<uint8_t>(sizeof(UI_SOS_ITEMS) / sizeof(UI_SOS_ITEMS[0]))};
    case UiMenuId::Security: return {"БЕЗОПАСНОСТЬ", UI_SECURITY_ITEMS, static_cast<uint8_t>(sizeof(UI_SECURITY_ITEMS) / sizeof(UI_SECURITY_ITEMS[0]))};
    case UiMenuId::Diagnostics: return {"ПРОВЕРКА", UI_DIAGNOSTICS_ITEMS, static_cast<uint8_t>(sizeof(UI_DIAGNOSTICS_ITEMS) / sizeof(UI_DIAGNOSTICS_ITEMS[0]))};
    case UiMenuId::System: return {"СИСТЕМА", UI_SYSTEM_ITEMS, static_cast<uint8_t>(sizeof(UI_SYSTEM_ITEMS) / sizeof(UI_SYSTEM_ITEMS[0]))};
  }
  return {"МЕНЮ", UI_ROOT_ITEMS, static_cast<uint8_t>(sizeof(UI_ROOT_ITEMS) / sizeof(UI_ROOT_ITEMS[0]))};
}

const UiMenuItem* uiFindFeatureItem(UiFeatureId feature) {
  for (uint8_t menuRaw = static_cast<uint8_t>(UiMenuId::Root);
       menuRaw <= static_cast<uint8_t>(UiMenuId::System); ++menuRaw) {
    const UiMenuDefinition def = uiGetMenuDefinition(static_cast<UiMenuId>(menuRaw));
    for (uint8_t i = 0; i < def.count; ++i) {
      if (def.items[i].kind == UiItemKind::Feature && def.items[i].feature == feature) return &def.items[i];
    }
  }
  return nullptr;
}

UiMenuId uiCurrentMenu() {
  if (uiState.navDepth == 0 || uiState.navDepth > UI_NAV_DEPTH) return UiMenuId::Root;
  return uiState.navMenus[uiState.navDepth - 1U];
}

uint8_t& uiCurrentMenuIndexRef() {
  if (uiState.navDepth == 0 || uiState.navDepth > UI_NAV_DEPTH) uiState.navDepth = 1;
  return uiState.navIndex[uiState.navDepth - 1U];
}

uint8_t& uiCurrentMenuScrollRef() {
  if (uiState.navDepth == 0 || uiState.navDepth > UI_NAV_DEPTH) uiState.navDepth = 1;
  return uiState.navScroll[uiState.navDepth - 1U];
}

void uiResetNavigation() {
  uiState.navDepth = 1;
  for (uint8_t i = 0; i < UI_NAV_DEPTH; ++i) {
    uiState.navMenus[i] = UiMenuId::Quick;
    uiState.navIndex[i] = 0;
    uiState.navScroll[i] = 0;
  }
  uiState.feature = UiFeatureId::None;
  uiState.menuCursorY = UI_MENU_FIRST_Y;
}

void uiEnsureMenuWindow() {
  const UiMenuDefinition def = uiGetMenuDefinition(uiCurrentMenu());
  uint8_t& index = uiCurrentMenuIndexRef();
  uint8_t& scroll = uiCurrentMenuScrollRef();
  if (def.count == 0) { index = 0; scroll = 0; return; }
  if (index >= def.count) index = static_cast<uint8_t>(def.count - 1U);
  if (index < scroll) scroll = index;
  if (index >= static_cast<uint8_t>(scroll + UI_MENU_VISIBLE_ROWS)) {
    scroll = static_cast<uint8_t>(index - UI_MENU_VISIBLE_ROWS + 1U);
  }
  const uint8_t maxScroll = def.count > UI_MENU_VISIBLE_ROWS
    ? static_cast<uint8_t>(def.count - UI_MENU_VISIBLE_ROWS) : 0;
  if (scroll > maxScroll) scroll = maxScroll;
}

void uiDrawStatusBar() {
  const bool criticalFault = !radioReady || !cryptoReady;
  if (criticalFault) {
    display.fillRect(0, 0, OLED_WIDTH, 10, SSD1306_WHITE);
    uiDrawAlertIcon(2, 0, SSD1306_BLACK);
    uiDrawText(16, 1, !cryptoReady ? "ОШИБКА ЗАЩИТЫ" : "ОШИБКА СВЯЗИ", 1, SSD1306_BLACK);
    return;
  }

  uiDrawText(1, 1, "SM", 1);
  uiDrawNodesIcon(31, 0);
  char n[5];
  snprintf(n, sizeof(n), "%u", static_cast<unsigned>(countFreshNeighbors()));
  uiDrawText(43, 1, n, 1);
  if (uiState.unreadCount > 0) {
    uiDrawEnvelopeIcon(59, 0);
    char unread[4];
    snprintf(unread, sizeof(unread), "%u", static_cast<unsigned>(uiState.unreadCount));
    uiDrawText(71, 1, unread, 1);
  }
  uiDrawBleIcon(96, 0, bleState == BleState::ProtocolReady);
  uiDrawRadioIcon(116, 0, true);
  display.drawFastHLine(0, 10, OLED_WIDTH, SSD1306_WHITE);
}

size_t uiCountVisibleRoutes() {
  size_t count = countStaticRoutes();
  for (const auto& neighbor : neighbors) {
    if (!neighbor.used || !isFreshDirectNeighbor(neighbor.nodeId)) continue;
    if (findStaticRouteIndex(neighbor.nodeId) >= 0) continue;
    count++;
  }
  return count;
}

const NeighborEntry* uiGetFreshNeighborByOrdinal(uint8_t ordinal) {
  uint8_t seen = 0;
  for (const auto& neighbor : neighbors) {
    if (!neighbor.used || !isFreshDirectNeighbor(neighbor.nodeId)) continue;
    if (seen++ == ordinal) return &neighbor;
  }
  return nullptr;
}

UiRouteView uiGetRouteByOrdinal(uint8_t ordinal) {
  UiRouteView view;
  uint8_t seen = 0;
  for (const auto& route : staticRoutes) {
    if (!route.active) continue;
    if (seen++ == ordinal) {
      view.valid = true;
      view.destination = route.destinationNodeId;
      view.nextHop = route.nextHopNodeId;
      view.source = RouteSource::StaticTable;
      return view;
    }
  }
  for (const auto& neighbor : neighbors) {
    if (!neighbor.used || !isFreshDirectNeighbor(neighbor.nodeId) || findStaticRouteIndex(neighbor.nodeId) >= 0) continue;
    if (seen++ == ordinal) {
      view.valid = true;
      view.destination = neighbor.nodeId;
      view.nextHop = neighbor.nodeId;
      view.source = RouteSource::DirectNeighbor;
      return view;
    }
  }
  return view;
}

const UiMessageEntry* uiGetMessageByNewestOrdinal(uint8_t ordinal) {
  if (ordinal >= uiInboxCount || uiInboxCount == 0) return nullptr;
  const int index = (static_cast<int>(uiInboxWriteIndex) - 1 - ordinal + UI_INBOX_SIZE * 2) % UI_INBOX_SIZE;
  return uiInbox[index].used ? &uiInbox[index] : nullptr;
}

uint8_t uiGetSceneCode() { return static_cast<uint8_t>(uiState.scene); }
uint8_t uiGetMenuIndex() { return uiCurrentMenuIndexRef(); }
uint8_t uiGetInboxCount() { return uiInboxCount; }

bool uiStartNextNotification(uint32_t now) {
  if (uiNotificationCount == 0 || uiState.toastUntilMs != 0) return false;
  UiNotification& note = uiNotificationQueue[uiNotificationHead];
  if (!note.used) {
    uiNotificationHead = static_cast<uint8_t>((uiNotificationHead + 1U) % UI_NOTIFICATION_QUEUE_SIZE);
    uiNotificationCount--;
    return false;
  }
  strncpy(uiState.toastTitle, note.title, sizeof(uiState.toastTitle) - 1);
  uiState.toastTitle[sizeof(uiState.toastTitle) - 1] = '\0';
  strncpy(uiState.toastBody, note.body, sizeof(uiState.toastBody) - 1);
  uiState.toastBody[sizeof(uiState.toastBody) - 1] = '\0';
  uiState.toastStartedAtMs = now;
  uiState.toastUntilMs = now + note.durationMs;
  note = UiNotification{};
  uiNotificationHead = static_cast<uint8_t>((uiNotificationHead + 1U) % UI_NOTIFICATION_QUEUE_SIZE);
  uiNotificationCount--;
  uiMarkDirty();
  return true;
}

void uiShowToast(const char* title, const char* body, uint32_t durationMs) {
  if (title == nullptr) title = "";
  if (body == nullptr) body = "";

  // Bounded FIFO. When saturated, keep the older user-visible sequence and
  // drop only the newest low-priority notification instead of reordering it.
  if (uiNotificationCount >= UI_NOTIFICATION_QUEUE_SIZE) return;
  UiNotification& note = uiNotificationQueue[uiNotificationTail];
  note = UiNotification{};
  note.used = true;
  note.durationMs = durationMs == 0 ? UI_TOAST_DEFAULT_MS : durationMs;
  strncpy(note.title, title, sizeof(note.title) - 1);
  note.title[sizeof(note.title) - 1] = '\0';
  strncpy(note.body, body, sizeof(note.body) - 1);
  note.body[sizeof(note.body) - 1] = '\0';
  uiNotificationTail = static_cast<uint8_t>((uiNotificationTail + 1U) % UI_NOTIFICATION_QUEUE_SIZE);
  uiNotificationCount++;
  if (uiState.toastUntilMs == 0) uiStartNextNotification(millis());
  uiMarkDirty();
}

void uiStoreIncomingMessage(uint32_t origin, const uint8_t* payload, uint8_t length) {
  if (payload == nullptr || length == 0) return;
  UiMessageEntry& entry = uiInbox[uiInboxWriteIndex];
  entry = UiMessageEntry{};
  entry.used = true;
  entry.origin = origin;
  entry.receivedAtMs = millis();
  const size_t copyLength = min(static_cast<size_t>(length), sizeof(entry.text) - 1);
  memcpy(entry.text, payload, copyLength);
  size_t safeLength = copyLength;
  if (safeLength > 0) {
    const uint8_t last = static_cast<uint8_t>(entry.text[safeLength - 1]);
    if (last == 0xD0 || last == 0xD1) safeLength--;
  }
  entry.text[safeLength] = '\0';
  entry.length = static_cast<uint8_t>(safeLength);
  uiInboxWriteIndex = static_cast<uint8_t>((uiInboxWriteIndex + 1U) % UI_INBOX_SIZE);
  if (uiInboxCount < UI_INBOX_SIZE) uiInboxCount++;
  if (uiState.unreadCount < 99) uiState.unreadCount++;
  uiState.messageIndex = 0;
  char sender[9]; formatNodeId(origin, sender);
  char body[32];
  snprintf(body, sizeof(body), "ОТ УЗЛА %s", sender + 4);
  uiShowToast("НОВОЕ СООБЩЕНИЕ", body, 2300);
}

void uiNotifyMessageQueued(uint32_t destination) {
  char id[9]; formatNodeId(destination, id);
  char body[32];
  snprintf(body, sizeof(body), "В ОЧЕРЕДИ > %s", id + 4);
  uiShowToast("ОТПРАВКА", body, 1500);
}

void uiSetScene(UiScene scene) {
  if (scene == uiState.scene) { uiMarkDirty(); return; }
  uiState.scene = scene;
  uiState.transitionStartedAtMs = millis();
  uiMarkDirty();
}

bool uiPushMenu(UiMenuId menu) {
  if (uiState.navDepth >= UI_NAV_DEPTH) return false;
  uiState.navMenus[uiState.navDepth] = menu;
  uiState.navIndex[uiState.navDepth] = 0;
  uiState.navScroll[uiState.navDepth] = 0;
  uiState.navDepth++;
  uiState.menuCursorY = UI_MENU_FIRST_Y;
  uiSetScene(UiScene::Menu);
  return true;
}

void uiOpenFeature(UiFeatureId feature) {
  uiState.feature = feature;
  if (feature == UiFeatureId::Inbox) uiState.unreadCount = 0;
  uiSetScene(UiScene::Feature);
}

void uiBack() {
  if (uiState.scene == UiScene::Feature) {
    uiState.feature = UiFeatureId::None;
    uiSetScene(UiScene::Menu);
    return;
  }
  if (uiState.scene == UiScene::Menu) {
    if (uiState.navDepth > 1) {
      uiState.navDepth--;
      uiState.menuCursorY = UI_MENU_FIRST_Y + static_cast<int16_t>(uiCurrentMenuIndexRef() - uiCurrentMenuScrollRef()) * UI_MENU_ROW_H;
      uiSetScene(UiScene::Menu);
    } else {
      uiSetScene(UiScene::Home);
    }
    return;
  }
  uiSetScene(UiScene::Home);
}

uint8_t bleOledSnapshot[BLE_OLED_FRAME_BYTES] {};
uint32_t bleOledSnapshotId = 0;
bool bleOledSnapshotValid = false;

void uiEmitStateChanged() {
  uint8_t payload[40];
  const uint16_t length = buildUiStatePayload(payload, sizeof(payload));
  if (length > 0) emitBleEvent(EVT_UI_CHANGED, payload, length);
}

void uiCommitRemoteAction() {
  // A remote press must become visible before Android requests the next exact
  // framebuffer. Drop the short post-connect banner, schedule an immediate UI
  // redraw, and invalidate any cached multi-chunk OLED snapshot.
  bleConnectedBannerUntilMs = 0;
  bleOledSnapshotValid = false;
  uiState.dirty = true;
  uiState.nextFrameAtMs = millis();
  uiEmitStateChanged();
}

void uiMoveFeatureSelection(UiAction action) {
  if (uiState.feature == UiFeatureId::Inbox && uiInboxCount > 0) {
    if (action == UiAction::Up) uiState.messageIndex = static_cast<uint8_t>((uiState.messageIndex + uiInboxCount - 1U) % uiInboxCount);
    else if (action == UiAction::Down) uiState.messageIndex = static_cast<uint8_t>((uiState.messageIndex + 1U) % uiInboxCount);
    uiMarkDirty();
    return;
  }
  if (uiState.feature == UiFeatureId::Neighbors) {
    const uint8_t count = static_cast<uint8_t>(countFreshNeighbors());
    if (count > 0 && action == UiAction::Up) uiState.neighborIndex = static_cast<uint8_t>((uiState.neighborIndex + count - 1U) % count);
    else if (count > 0 && action == UiAction::Down) uiState.neighborIndex = static_cast<uint8_t>((uiState.neighborIndex + 1U) % count);
    uiMarkDirty();
    return;
  }
  if (uiState.feature == UiFeatureId::Routes) {
    const uint8_t count = static_cast<uint8_t>(min(static_cast<size_t>(255), uiCountVisibleRoutes()));
    if (count > 0 && action == UiAction::Up) uiState.routeIndex = static_cast<uint8_t>((uiState.routeIndex + count - 1U) % count);
    else if (count > 0 && action == UiAction::Down) uiState.routeIndex = static_cast<uint8_t>((uiState.routeIndex + 1U) % count);
    uiMarkDirty();
    return;
  }
  if (uiState.feature == UiFeatureId::Positions) {
    uint8_t count = 0;
    for (size_t i = 0; i < MAX_POSITION_CACHE; ++i) if (positionCache[i].used) count++;
    if (count > 0 && action == UiAction::Up) uiState.positionIndex = static_cast<uint8_t>((uiState.positionIndex + count - 1U) % count);
    else if (count > 0 && action == UiAction::Down) uiState.positionIndex = static_cast<uint8_t>((uiState.positionIndex + 1U) % count);
    uiMarkDirty();
  }
}

bool uiHandleRemoteAction(uint8_t rawAction) {
  if (rawAction < static_cast<uint8_t>(UiAction::Up) || rawAction > static_cast<uint8_t>(UiAction::Home)) return false;
  const UiAction action = static_cast<UiAction>(rawAction);

  if (action == UiAction::Home) {
    uiResetNavigation();
    uiSetScene(UiScene::Home);
    uiCommitRemoteAction();
    return true;
  }

  if (uiState.scene == UiScene::Home) {
    if (action == UiAction::Select) {
      // Home is intentionally contextual: one press opens the thing that currently
      // matters most. Otherwise it opens the small Quick menu.
      if (uiState.unreadCount > 0 && uiInboxCount > 0) {
        uiResetNavigation();
        uiOpenFeature(UiFeatureId::Inbox);
      } else if (fieldTest.state == FieldTestState::Running) {
        uiResetNavigation();
        uiOpenFeature(UiFeatureId::FieldTest);
      } else {
        uiResetNavigation();
        uiSetScene(UiScene::Menu);
      }
    } else if (action == UiAction::Up || action == UiAction::Down) {
      uiResetNavigation();
      uiSetScene(UiScene::Menu);
    } else if (action == UiAction::Back) {
      uiSetScene(UiScene::Home);
    }
    uiCommitRemoteAction();
    return true;
  }

  if (uiState.scene == UiScene::Menu) {
    const UiMenuDefinition def = uiGetMenuDefinition(uiCurrentMenu());
    uint8_t& index = uiCurrentMenuIndexRef();
    if (def.count > 0 && action == UiAction::Up) {
      index = static_cast<uint8_t>((index + def.count - 1U) % def.count);
      uiEnsureMenuWindow();
      uiMarkDirty();
    } else if (def.count > 0 && action == UiAction::Down) {
      index = static_cast<uint8_t>((index + 1U) % def.count);
      uiEnsureMenuWindow();
      uiMarkDirty();
    } else if (def.count > 0 && action == UiAction::Select) {
      const UiMenuItem& item = def.items[index];
      if (item.kind == UiItemKind::Submenu) uiPushMenu(item.submenu);
      else uiOpenFeature(item.feature);
    } else if (action == UiAction::Back) {
      uiBack();
    }
    uiCommitRemoteAction();
    return true;
  }

  if (uiState.scene == UiScene::Feature) {
    if (action == UiAction::Back) uiBack();
    else if (action == UiAction::Up || action == UiAction::Down) uiMoveFeatureSelection(action);
    uiCommitRemoteAction();
    return true;
  }

  return false;
}
uint16_t buildUiStatePayload(uint8_t* out, size_t capacity) {
  BinaryWriter w{out, capacity};
  uint8_t flags = 0;
  if (oledReady) flags |= 1U << 0;
  if (bleState == BleState::ProtocolReady) flags |= 1U << 1;
  if (fieldTest.state == FieldTestState::Running) flags |= 1U << 2;
  if (uiState.toastUntilMs != 0 && !timeReached(millis(), uiState.toastUntilMs)) flags |= 1U << 3;
  const UiMenuItem* featureItem = uiFindFeatureItem(uiState.feature);
  if (featureItem != nullptr && featureItem->state == UiFeatureState::Planned) flags |= 1U << 4;
  if (uiState.unreadCount > 0) flags |= 1U << 5;

  w.putU8(UI_MODEL_VERSION);
  w.putU8(static_cast<uint8_t>(uiState.scene));
  w.putU8(static_cast<uint8_t>(uiCurrentMenu()));
  w.putU8(uiCurrentMenuIndexRef());
  w.putU8(uiCurrentMenuScrollRef());
  w.putU8(uiState.navDepth);
  w.putU8(static_cast<uint8_t>(uiState.feature));
  w.putU8(flags);
  w.putU8(uiInboxCount);
  w.putU8(uiState.unreadCount);
  w.putU8(static_cast<uint8_t>(countFreshNeighbors()));
  w.putU8(static_cast<uint8_t>(min(static_cast<size_t>(255), uiCountVisibleRoutes())));
  w.putU8(static_cast<uint8_t>(fieldTest.state));
  w.putU8(static_cast<uint8_t>(bleState));
  w.putU8(uiState.messageIndex);
  w.putU8(uiState.neighborIndex);
  w.putU8(uiState.routeIndex);
  w.putU32(localNodeId);
  w.putU32(fieldTest.testId);
  w.putU32(fieldTest.targetNodeId);
  return w.ok ? static_cast<uint16_t>(w.length) : 0;
}

uint16_t buildOledFrameChunkPayload(uint8_t chunkIndex, uint8_t* out, size_t capacity) {
  if (!oledReady || out == nullptr || chunkIndex >= BLE_OLED_FRAME_CHUNK_COUNT) return 0;
  const uint8_t* live = display.getBuffer();
  if (live == nullptr) return 0;

  // Chunk 0 atomically starts a new logical snapshot. Remaining chunks are served
  // from the cached 1024-byte buffer so a redraw between GATT requests cannot tear
  // the image seen by Android.
  if (chunkIndex == 0 || !bleOledSnapshotValid) {
    memcpy(bleOledSnapshot, live, BLE_OLED_FRAME_BYTES);
    bleOledSnapshotId = (bleOledSnapshotId == UINT32_MAX) ? 1U : (bleOledSnapshotId + 1U);
    if (bleOledSnapshotId == 0) bleOledSnapshotId = 1U;
    bleOledSnapshotValid = true;
  }

  const size_t offset = static_cast<size_t>(chunkIndex) * BLE_OLED_FRAME_CHUNK_BYTES;
  const size_t remaining = BLE_OLED_FRAME_BYTES - offset;
  const uint16_t dataLength = static_cast<uint16_t>(min(remaining, static_cast<size_t>(BLE_OLED_FRAME_CHUNK_BYTES)));
  BinaryWriter w{out, capacity};
  w.putU8(1); // snapshot payload version
  w.putU8(static_cast<uint8_t>(OLED_WIDTH));
  w.putU8(static_cast<uint8_t>(OLED_HEIGHT));
  w.putU32(bleOledSnapshotId);
  w.putU8(chunkIndex);
  w.putU8(BLE_OLED_FRAME_CHUNK_COUNT);
  w.putU16(dataLength);
  w.putBytes(bleOledSnapshot + offset, dataLength);
  return w.ok ? static_cast<uint16_t>(w.length) : 0;
}

void initializeUi() {
  if (!oledReady) return;
  uiState = UiRuntimeState{};
  uiNotificationHead = uiNotificationTail = uiNotificationCount = 0;
  for (auto& note : uiNotificationQueue) note = UiNotification{};
  uiResetNavigation();
  uiState.initialized = true;
  uiState.bootStartedAtMs = millis();
  uiState.nextFrameAtMs = uiState.bootStartedAtMs;
  uiState.lastFieldTestState = fieldTest.state;
  display.clearDisplay();
  display.display();
}

void uiDrawBoot(uint32_t now) {
  const uint32_t elapsed = now - uiState.bootStartedAtMs;
  const uint32_t bounded = min(elapsed, UI_BOOT_DURATION_MS);
  const int16_t cx = 64;
  const int16_t cy = 24;

  // Phase 1: a scanning ring discovers the mesh around the local node.
  const uint8_t scanPhase = static_cast<uint8_t>((elapsed / 55UL) % 8UL);
  display.drawCircle(cx, cy, 3, SSD1306_WHITE);
  display.fillCircle(cx, cy, 1, SSD1306_WHITE);
  display.drawCircle(cx, cy, 7 + (scanPhase < 4 ? scanPhase : 7 - scanPhase), SSD1306_WHITE);

  const int16_t nodes[6][2] = {{21,18},{107,18},{28,40},{100,40},{46,9},{82,9}};
  for (uint8_t i = 0; i < 6; ++i) {
    const uint32_t revealAt = 120UL + i * 95UL;
    if (elapsed < revealAt) continue;
    const uint32_t age = elapsed - revealAt;
    const int16_t travel = age >= 170UL ? 100 : static_cast<int16_t>((age * (200UL - (age < 170UL ? age : 170UL))) / 340UL);
    const int16_t nx = cx + ((nodes[i][0] - cx) * travel) / 100;
    const int16_t ny = cy + ((nodes[i][1] - cy) * travel) / 100;
    display.drawLine(cx, cy, nx, ny, SSD1306_WHITE);
    display.fillCircle(nx, ny, age > 100 ? 2 : 1, SSD1306_WHITE);
  }

  // Phase 2: logo reveal. A clean left-to-right underline gives motion without
  // hiding information or burning CPU on full-screen effects.
  if (elapsed > 630UL) {
    uiDrawCenteredText(44, "SECUREMESH", 1);
    const uint32_t logoAge = elapsed - 630UL;
    const uint8_t underline = static_cast<uint8_t>(min(74UL, (logoAge * 74UL) / 260UL));
    display.drawFastHLine(27, 53, underline, SSD1306_WHITE);
  }
  if (elapsed > 900UL) uiDrawCenteredText(56, "ЗАЩИЩЕННАЯ СЕТЬ", 1);

  const uint8_t progress = static_cast<uint8_t>((bounded * 120UL) / UI_BOOT_DURATION_MS);
  display.drawRoundRect(3, 61, 122, 3, 1, SSD1306_WHITE);
  if (progress > 0) display.fillRect(4, 62, progress, 1, SSD1306_WHITE);
}

const char* uiSystemHealthText() {
  if (!cryptoReady) return "ОШИБКА ЗАЩИТЫ";
  if (!radioReady) return "РАДИО ОШИБКА";
  if (fieldTest.state == FieldTestState::Running) return "ТЕСТ";
  if (countFreshNeighbors() == 0) return "АВТОНОМНО";
  return "ГОТОВ";
}

void uiDrawHome() {
  // 128x64 is too small for a dashboard. Home therefore answers only three
  // questions: "is it OK?", "am I connected?", and "what should I do now?".
  // Detailed radio/BLE/queue counters intentionally live deeper in the UI.
  const uint16_t W = SSD1306_WHITE;
  const bool fault = !cryptoReady || !radioReady;
  const uint8_t neighborCount = static_cast<uint8_t>(countFreshNeighbors());
  const bool phoneReady = bleState == BleState::ProtocolReady;

  uiDrawCenteredText(1, "SECUREMESH", 1);
  display.drawFastHLine(18, 10, 92, W);

  // 1) Critical fault always wins. No icons, no counters, no ambiguity.
  if (fault) {
    uiDrawCenteredText(18, "НУЖНО ВНИМАНИЕ", 1);
    uiDrawCenteredText(31, !cryptoReady ? "ЗАЩИТА" : "СВЯЗЬ", 2);
    uiDrawCenteredText(50, "ОШИБКА", 1);
    uiDrawCenteredText(58, "ОК > МЕНЮ", 1);
    return;
  }

  // 2) A new message is more important than passive status. One OK opens Inbox.
  if (uiState.unreadCount > 0 && uiInboxCount > 0) {
    char line[24];
    uiDrawCenteredText(18, "НОВОЕ", 2);
    snprintf(line, sizeof(line), "%u %s",
      static_cast<unsigned>(uiState.unreadCount),
      uiState.unreadCount == 1 ? "СООБЩЕНИЕ" : "СООБЩЕНИЯ");
    uiDrawCenteredText(38, line, 1);
    uiDrawCenteredText(49, "ОК > ОТКРЫТЬ", 1);
    return;
  }

  // 3) During a field test the operator cares about progress, not idle status.
  if (fieldTest.state == FieldTestState::Running) {
    const uint32_t done = min(static_cast<uint32_t>(fieldTest.requestedPackets), fieldTest.sent);
    const uint16_t pct = fieldTest.requestedPackets == 0 ? 0 :
      static_cast<uint16_t>((done * 100UL) / fieldTest.requestedPackets);
    char line[24];
    uiDrawCenteredText(16, "ТЕСТ СВЯЗИ", 1);
    snprintf(line, sizeof(line), "%u%%", static_cast<unsigned>(pct));
    uiDrawCenteredText(28, line, 2);
    snprintf(line, sizeof(line), "%lu / %u", static_cast<unsigned long>(done),
      static_cast<unsigned>(fieldTest.requestedPackets));
    uiDrawCenteredText(48, line, 1);
    uiDrawCenteredText(58, "ОК > ПОДРОБНО", 1);
    return;
  }

  // 4) Normal idle state: one large word and two plain-language lines.
  if (neighborCount == 0) {
    uiDrawCenteredText(18, "АВТОНОМНО", 2);
    uiDrawCenteredText(39, "СОСЕДЕЙ НЕТ", 1);
  } else {
    uiDrawCenteredText(18, "ГОТОВ", 2);
    char network[22];
    snprintf(network, sizeof(network), "СЕТЬ: %u %s", static_cast<unsigned>(neighborCount),
      neighborCount == 1 ? "УЗЕЛ" : "УЗЛА");
    uiDrawCenteredText(39, network, 1);
  }

  uiDrawCenteredText(49, phoneReady ? "ТЕЛЕФОН: ПОДКЛЮЧЕН" : "ТЕЛЕФОН: НЕ ПОДКЛ.", 1);
  uiDrawCenteredText(58, "ОК > МЕНЮ", 1);
}

void uiDrawMenuScrollBar(uint8_t count, uint8_t scroll) {
  if (count <= UI_MENU_VISIBLE_ROWS) return;
  display.drawFastVLine(126, UI_MENU_FIRST_Y, UI_MENU_VISIBLE_ROWS * UI_MENU_ROW_H - 1, SSD1306_WHITE);
  const uint8_t trackH = UI_MENU_VISIBLE_ROWS * UI_MENU_ROW_H - 2;
  uint8_t thumbH = static_cast<uint8_t>((static_cast<uint16_t>(trackH) * UI_MENU_VISIBLE_ROWS) / count);
  if (thumbH < 5) thumbH = 5;
  const uint8_t maxScroll = static_cast<uint8_t>(count - UI_MENU_VISIBLE_ROWS);
  const uint8_t travel = static_cast<uint8_t>(trackH - thumbH);
  const uint8_t y = static_cast<uint8_t>(UI_MENU_FIRST_Y + 1 + (maxScroll == 0 ? 0 : (static_cast<uint16_t>(travel) * scroll) / maxScroll));
  display.drawFastVLine(125, y, thumbH, SSD1306_WHITE);
}

void uiDrawPlannedMark(int16_t x, int16_t y, uint16_t color) {
  display.drawCircle(x + 3, y + 3, 3, color);
  display.drawLine(x + 3, y + 3, x + 3, y + 1, color);
  display.drawLine(x + 3, y + 3, x + 5, y + 4, color);
}

void uiDrawMenu() {
  uiDrawStatusBar();
  uiEnsureMenuWindow();
  const UiMenuDefinition def = uiGetMenuDefinition(uiCurrentMenu());
  uint8_t& index = uiCurrentMenuIndexRef();
  uint8_t& scroll = uiCurrentMenuScrollRef();

  uiDrawTextClipped(2, 12, def.title, 99, 1);
  char page[12];
  snprintf(page, sizeof(page), "%u/%u", static_cast<unsigned>(index + 1U), static_cast<unsigned>(def.count));
  uiDrawText(102, 12, page, 1);

  const int16_t targetY = UI_MENU_FIRST_Y + static_cast<int16_t>(index - scroll) * UI_MENU_ROW_H;
  if (uiState.menuCursorY < targetY) uiState.menuCursorY += ((targetY - uiState.menuCursorY) > 5 ? 5 : (targetY - uiState.menuCursorY));
  else if (uiState.menuCursorY > targetY) uiState.menuCursorY -= ((uiState.menuCursorY - targetY) > 5 ? 5 : (uiState.menuCursorY - targetY));

  display.fillRoundRect(1, uiState.menuCursorY, 124, 10, 2, SSD1306_WHITE);
  for (uint8_t row = 0; row < UI_MENU_VISIBLE_ROWS; ++row) {
    const uint8_t itemIndex = static_cast<uint8_t>(scroll + row);
    if (itemIndex >= def.count) break;
    const int16_t y = UI_MENU_FIRST_Y + static_cast<int16_t>(row) * UI_MENU_ROW_H;
    const bool highlighted = abs(static_cast<int>(y - uiState.menuCursorY)) <= 2;
    const uint16_t color = highlighted ? SSD1306_BLACK : SSD1306_WHITE;
    const UiMenuItem& item = def.items[itemIndex];
    uiDrawIcon(item.icon, 4, y, color);
    const int16_t labelMax = item.state == UiFeatureState::Planned ? 97 : 113;
    uiDrawTextClipped(18, y + 1, item.label, labelMax, 1, color);
    if (item.state == UiFeatureState::Planned) {
      uiDrawText(101, y + 1, "ПЛАН", 1, color);
    } else if (item.kind == UiItemKind::Submenu) {
      display.drawLine(117, y + 3, 121, y + 5, color);
      display.drawLine(121, y + 5, 117, y + 7, color);
    }
  }

  if (def.count > 0) {
    display.drawFastHLine(1, 54, 124, SSD1306_WHITE);
    uiDrawTextClipped(2, 56, def.items[index].hint, 124, 1);
  }
  uiDrawMenuScrollBar(def.count, scroll);
}

void uiDrawFeatureHeader(const char* title, UiIcon icon, bool planned = false) {
  uiDrawStatusBar();
  uiDrawIcon(icon, 2, 12, SSD1306_WHITE);
  uiDrawTextClipped(16, 12, title, planned ? 116 : 126, 1, SSD1306_WHITE);
  if (planned) uiDrawPlannedMark(118, 13, SSD1306_WHITE);
}

void uiDrawMessages() {
  uiDrawFeatureHeader("ВХОДЯЩИЕ", UiIcon::Message);
  char countText[8];
  snprintf(countText, sizeof(countText), "%u", static_cast<unsigned>(uiInboxCount));
  uiDrawText(115, 21, countText, 1);

  if (uiInboxCount == 0) {
    uiDrawEnvelopeIcon(58, 27);
    uiDrawCenteredText(40, "ПОКА ПУСТО", 1);
    uiDrawCenteredText(52, "СООБЩЕНИЯ ИЗ APP", 1);
    return;
  }

  if (uiState.messageIndex >= uiInboxCount) uiState.messageIndex = uiInboxCount - 1;
  const UiMessageEntry* msg = uiGetMessageByNewestOrdinal(uiState.messageIndex);
  if (msg == nullptr) return;
  char id[9]; formatNodeId(msg->origin, id);
  char line[28];
  snprintf(line, sizeof(line), "ОТ %s  %u/%u", id + 4,
    static_cast<unsigned>(uiState.messageIndex + 1U), static_cast<unsigned>(uiInboxCount));
  uiDrawText(2, 23, line, 1);
  const uint32_t ageSec = (millis() - msg->receivedAtMs) / 1000UL;
  snprintf(line, sizeof(line), "%lus НАЗАД", static_cast<unsigned long>(ageSec));
  uiDrawText(2, 32, line, 1);
  display.drawFastHLine(2, 40, 124, SSD1306_WHITE);
  uiDrawWrappedText(msg->text, 2, 44, 124, 2);
}

void uiDrawCompose() {
  uiDrawFeatureHeader("ОТПРАВИТЬ", UiIcon::Message);
  uiDrawEnvelopeIcon(58, 25);
  uiDrawCenteredText(39, "ТЕКСТ + АДРЕСАТ", 1);
  uiDrawCenteredText(48, "ВЫБИРАЮТСЯ В APP", 1);
  char line[40];
  const uint8_t queued = static_cast<uint8_t>(countUsedTxEntries());
  if (queued == 0) snprintf(line, sizeof(line), "ПЕРЕДАЧА: ГОТОВА");
  else snprintf(line, sizeof(line), "В ОЧЕРЕДИ: %u", static_cast<unsigned>(queued));
  uiDrawCenteredText(57, line, 1);
}


enum class UiLinkGrade : uint8_t { Lost = 0, Poor = 1, Unstable = 2, Good = 3, Excellent = 4 };

struct UiLinkQuality {
  uint8_t score = 0;
  UiLinkGrade grade = UiLinkGrade::Lost;
};

// Explicit prototypes prevent Arduino from generating prototypes before the
// UiLinkGrade/UiLinkQuality declarations above.
UiLinkQuality uiEvaluateLink(const NeighborEntry& n);
const char* uiLinkGradeText(UiLinkGrade grade);

float uiScaleQuality(float value, float low, float high) {
  if (high <= low) return 0.0f;
  return clampFloat((value - low) * 100.0f / (high - low), 0.0f, 100.0f);
}

float uiConfidencePdr(float pdr, uint32_t observations) {
  const float n = static_cast<float>(min(static_cast<uint32_t>(40), observations));
  constexpr float prior = 72.0f;
  constexpr float priorWeight = 4.0f;
  return (clampFloat(pdr, 0.0f, 100.0f) * n + prior * priorWeight) / (n + priorWeight);
}

UiLinkQuality uiEvaluateLink(const NeighborEntry& n) {
  UiLinkQuality q;
  const uint32_t age = millis() - n.lastSeenAtMs;
  if (!n.used || age > NEIGHBOR_STALE_MS) return q;

  const float rssi = uiScaleQuality(n.rssiEwma, -125.0f, -67.0f);
  const float snr = uiScaleQuality(n.snrEwma, -15.0f, 10.0f);
  const float hello = uiConfidencePdr(n.helloRxPdrEwma, n.rxFrames);
  float sum = rssi * 0.31f + snr * 0.20f + hello * 0.27f;
  float weight = 0.78f;
  if (n.txAttempts >= 2) {
    const float ack = uiConfidencePdr(n.txAckPdrEwma, n.txAttempts);
    sum += ack * 0.22f;
    weight += 0.22f;
  }
  float score = sum / weight;
  if (age > 3500UL) {
    if (age <= 9000UL) score *= 1.0f - static_cast<float>(age - 3500UL) / 11000.0f;
    else if (age <= 15000UL) score *= 0.50f;
    else score *= 0.25f;
  }
  const float evidence = clampFloat(static_cast<float>(n.rxFrames + n.txAttempts) / 18.0f, 0.0f, 1.0f);
  score *= 0.90f + 0.10f * evidence;
  q.score = static_cast<uint8_t>(lroundf(clampFloat(score, 0.0f, 100.0f)));

  if (q.score >= 84) q.grade = UiLinkGrade::Excellent;
  else if (q.score >= 66) q.grade = UiLinkGrade::Good;
  else if (q.score >= 45) q.grade = UiLinkGrade::Unstable;
  else if (q.score >= 24) q.grade = UiLinkGrade::Poor;
  else q.grade = UiLinkGrade::Lost;
  return q;
}

const char* uiLinkGradeText(UiLinkGrade grade) {
  switch (grade) {
    case UiLinkGrade::Excellent: return "ОТЛИЧНО";
    case UiLinkGrade::Good: return "ХОРОШО";
    case UiLinkGrade::Unstable: return "НЕСТАБИЛЬНО";
    case UiLinkGrade::Poor: return "ПЛОХО";
    case UiLinkGrade::Lost: return "НЕТ СВЯЗИ";
  }
  return "НЕТ ДАННЫХ";
}

void uiDrawQualityBars(uint8_t score, int16_t x, int16_t y) {
  const uint8_t active = score >= 84 ? 4 : score >= 66 ? 3 : score >= 45 ? 2 : score >= 24 ? 1 : 0;
  const uint8_t shimmer = static_cast<uint8_t>((millis() / 220UL) % 4UL);
  for (uint8_t i = 0; i < 4; ++i) {
    const int16_t h = 3 + i * 2;
    const int16_t bx = x + i * 7;
    display.drawRect(bx, y + 9 - h, 5, h, SSD1306_WHITE);
    if (i < active) {
      const bool pulse = active >= 3 && i == shimmer;
      display.fillRect(bx + 1, y + 10 - h, 3, max(1, h - 2 + (pulse ? 1 : 0)), SSD1306_WHITE);
    }
  }
}


struct UiSignalTrendState {
  bool used = false;
  uint32_t nodeId = 0;
  float fast = 0.0f;
  float slow = 0.0f;
  int8_t direction = 0; // -1 weaker, 0 stable, +1 stronger
  uint32_t lastSampleAtMs = 0;
};
UiSignalTrendState uiSignalTrend[MAX_NEIGHBORS];

int8_t uiUpdateSignalTrend(uint32_t nodeId, uint8_t score) {
  UiSignalTrendState* slot = nullptr;
  for (auto& s : uiSignalTrend) if (s.used && s.nodeId == nodeId) { slot = &s; break; }
  if (slot == nullptr) for (auto& s : uiSignalTrend) if (!s.used) { slot = &s; break; }
  if (slot == nullptr) slot = &uiSignalTrend[0];
  const uint32_t now = millis();
  if (!slot->used || slot->nodeId != nodeId) {
    *slot = UiSignalTrendState{}; slot->used = true; slot->nodeId = nodeId; slot->fast = slot->slow = score; slot->lastSampleAtMs = now; return 0;
  }
  if (now - slot->lastSampleAtMs < 1800UL) return slot->direction;
  slot->lastSampleAtMs = now;
  slot->fast = slot->fast * 0.60f + static_cast<float>(score) * 0.40f;
  slot->slow = slot->slow * 0.88f + static_cast<float>(score) * 0.12f;
  const float delta = slot->fast - slot->slow;
  slot->direction = delta >= 3.5f ? 1 : (delta <= -3.5f ? -1 : 0);
  return slot->direction;
}

const char* uiSignalTrendText(int8_t direction) {
  if (direction > 0) return "СВЯЗЬ УСИЛИВАЕТСЯ";
  if (direction < 0) return "СВЯЗЬ ОСЛАБЕВАЕТ";
  return "СВЯЗЬ СТАБИЛЬНА";
}

void uiDrawNetwork() {
  uiDrawFeatureHeader("СОСЕДИ", UiIcon::Network);
  const uint8_t count = static_cast<uint8_t>(countFreshNeighbors());
  if (count == 0) {
    uiDrawNodesIcon(58, 25);
    uiDrawCenteredText(39, "СОСЕДЕЙ НЕТ", 1);
    uiDrawCenteredText(50, "УЗЕЛ РАБОТАЕТ САМ", 1);
    return;
  }
  if (uiState.neighborIndex >= count) uiState.neighborIndex = 0;
  const NeighborEntry* n = uiGetFreshNeighborByOrdinal(uiState.neighborIndex);
  if (n == nullptr) return;
  const UiLinkQuality q = uiEvaluateLink(*n);

  uiDrawQualityBars(q.score, 49, 19);
  uiDrawCenteredText(33, uiLinkGradeText(q.grade), 1);

  char id[9]; formatNodeId(n->nodeId, id);
  char line[34];
  snprintf(line, sizeof(line), "УЗЕЛ %s  %u/%u", id + 4,
    static_cast<unsigned>(uiState.neighborIndex + 1U), static_cast<unsigned>(count));
  uiDrawCenteredText(43, line, 1);
  const int8_t trend = uiUpdateSignalTrend(n->nodeId, q.score);
  uiDrawCenteredText(54, uiSignalTrendText(trend), 1);
}

void uiDrawRoutes() {
  uiDrawFeatureHeader("ПУТИ СВЯЗИ", UiIcon::Route);
  const uint8_t count = static_cast<uint8_t>(min(static_cast<size_t>(255), uiCountVisibleRoutes()));
  if (count == 0) {
    uiDrawRouteIcon(59, 27);
    uiDrawCenteredText(40, "ПУТЕЙ НЕТ", 1);
    uiDrawCenteredText(52, "СЕТЬ ИЩЕТ ПУТЬ", 1);
    return;
  }
  if (uiState.routeIndex >= count) uiState.routeIndex = 0;
  const UiRouteView route = uiGetRouteByOrdinal(uiState.routeIndex);
  if (!route.valid) return;
  char dest[9], hop[9];
  formatNodeId(route.destination, dest);
  formatNodeId(route.nextHop, hop);
  char line[32];
  snprintf(line, sizeof(line), "ПУТЬ %u/%u",
    static_cast<unsigned>(uiState.routeIndex + 1U), static_cast<unsigned>(count));
  uiDrawText(2, 24, line, 1);
  snprintf(line, sizeof(line), "ЦЕЛЬ  %s", dest + 4);
  uiDrawText(2, 36, line, 1);
  snprintf(line, sizeof(line), "ЧЕРЕЗ %s", hop + 4);
  uiDrawText(2, 48, line, 1);
}


void uiDrawBleRadar() {
  uiDrawFeatureHeader("РАДАР", UiIcon::Ble);
  BleRadarEntry snapshot[BLE_RADAR_MAX_DEVICES];
  portENTER_CRITICAL(&bleRadarMux);
  memcpy(snapshot, bleRadarEntries, sizeof(snapshot));
  portEXIT_CRITICAL(&bleRadarMux);

  const uint32_t now = millis();
  uint8_t count = 0;
  int8_t strongest = -127;
  for (const auto& e : snapshot) {
    if (!e.used || now - e.lastSeenAtMs > BLE_RADAR_STALE_MS) continue;
    count++;
    const int8_t rssi = static_cast<int8_t>(e.rssiTenths / 10);
    if (rssi > strongest) strongest = rssi;
  }

  // Animated sweep is deliberately simple: it gives immediate feedback that
  // the passive detector is alive without spending a full frame on decoration.
  const int16_t cx = 64, cy = 34;
  display.drawCircle(cx, cy, 7, SSD1306_WHITE);
  display.drawCircle(cx, cy, 15, SSD1306_WHITE);
  display.drawCircle(cx, cy, 23, SSD1306_WHITE);
  const uint8_t phase = static_cast<uint8_t>((millis() / 150UL) % 16UL);
  const float angle = static_cast<float>(phase) * 0.3926991f;
  display.drawLine(cx, cy, cx + static_cast<int16_t>(22.0f * cosf(angle)), cy + static_cast<int16_t>(22.0f * sinf(angle)), SSD1306_WHITE);
  display.fillCircle(cx, cy, 2, SSD1306_WHITE);

  char line[30];
  snprintf(line, sizeof(line), "УСТРОЙСТВ %u", static_cast<unsigned>(count));
  uiDrawText(2, 53, line, 1);
  uiDrawText(84, 53, strongest >= -126 ? (strongest >= -70 ? "РЯДОМ" : "СЛАБО") : "ТИХО", 1);
}


void uiDrawTest() {
  uiDrawFeatureHeader("ТЕСТ СВЯЗИ", UiIcon::Test);
  if (fieldTest.state != FieldTestState::Running) {
    if (fieldTest.state == FieldTestState::Finished && fieldTest.sent > 0) {
      const float pdr = 100.0f * static_cast<float>(fieldTest.endToEndReplies) / static_cast<float>(fieldTest.sent);
      char big[12];
      snprintf(big, sizeof(big), "%.0f%%", pdr);
      uiDrawCenteredText(24, big, 2);
      uiDrawCenteredText(43, "ДОСТАВКА ДО ЦЕЛИ", 1);
      const uint32_t avgRtt = fieldTest.endToEndReplies == 0 ? 0 : static_cast<uint32_t>(fieldTest.rttSumMs / fieldTest.endToEndReplies);
      const char* response = avgRtt == 0 ? "НЕТ ОТВЕТА" : (avgRtt < 350 ? "ОТВЕТ БЫСТРЫЙ" : (avgRtt < 900 ? "ОТВЕТ НОРМАЛЬНЫЙ" : "ОТВЕТ МЕДЛЕННЫЙ"));
      uiDrawCenteredText(54, response, 1);
    } else {
      uiDrawTestIcon(58, 25);
      uiDrawCenteredText(39, "ТЕСТ НЕ ЗАПУЩЕН", 1);
      uiDrawCenteredText(50, "ЗАПУСК С ТЕЛЕФОНА", 1);
    }
    return;
  }

  const float pdr = fieldTest.sent == 0 ? 0.0f : 100.0f * static_cast<float>(fieldTest.endToEndReplies) / static_cast<float>(fieldTest.sent);
  char big[12]; snprintf(big, sizeof(big), "%.0f%%", pdr);
  uiDrawCenteredText(21, big, 2);
  char target[9]; formatNodeId(fieldTest.targetNodeId, target);
  char line[34];
  snprintf(line, sizeof(line), "ДО УЗЛА %s", target + 4);
  uiDrawCenteredText(40, line, 1);
  snprintf(line, sizeof(line), "ОТПРАВЛЕНО %lu/%u", static_cast<unsigned long>(fieldTest.sent), static_cast<unsigned>(fieldTest.requestedPackets));
  uiDrawCenteredText(50, line, 1);
  const uint8_t progress = fieldTest.requestedPackets == 0 ? 0 : static_cast<uint8_t>(min(120UL, (fieldTest.sent * 120UL) / fieldTest.requestedPackets));
  display.drawRoundRect(3, 60, 122, 4, 1, SSD1306_WHITE);
  if (progress > 0) display.fillRect(4, 61, progress, 2, SSD1306_WHITE);
}

void uiDrawRadioStatus() {
  uiDrawFeatureHeader("РАДИОСВЯЗЬ", UiIcon::Radio);
  uiDrawCenteredText(22, radioReady ? "СВЯЗЬ ГОТОВА" : "ЕСТЬ ОШИБКА", 1);
  char line[34];
  snprintf(line, sizeof(line), "РЕЖИМ: ШТАТНЫЙ");
  uiDrawCenteredText(34, line, 1);
  snprintf(line, sizeof(line), "РЯДОМ УЗЛОВ: %u", static_cast<unsigned>(countFreshNeighbors()));
  uiDrawCenteredText(44, line, 1);
  uiDrawCenteredText(55, lastRadioError == RADIOLIB_ERR_NONE ? "ОШИБОК НЕТ" : "ПРОВЕРЬ РАДИО", 1);
}

void uiDrawBleSession() {
  uiDrawFeatureHeader("ТЕЛЕФОН", UiIcon::Ble);
  const bool ready = bleState == BleState::ProtocolReady && bleAuthSuccessFlag;
  uiDrawCenteredText(22, ready ? "ЗАЩИЩЕНО" : (bleConnectedFlag ? "ПОДКЛЮЧЕНИЕ" : "ОЖИДАНИЕ"), 1);
  char line[34];
  uiDrawCenteredText(34, ready ? "ПРИЛОЖЕНИЕ ГОТОВО" : "ЖДУ ПРИЛОЖЕНИЕ", 1);
  uiDrawCenteredText(44, bleCurrentBondedFlag ? "ТЕЛЕФОН ПОДТВЕРЖДЕН" : "ПОДТВЕРДИ ТЕЛЕФОН", 1);
  uiDrawCenteredText(55, "ДОСТУП ЗАЩИЩЕН", 1);
}

void uiDrawSelfTest() {
  uiDrawFeatureHeader("ПРОВЕРКА УЗЛА", UiIcon::Diagnostics);
  const bool bleOk = bleInitialized;
  const bool queueOk = countUsedTxEntries() <= MAX_TX_QUEUE;
  uiDrawText(2, 23, radioReady ? "РАДИО: ГОТОВО" : "РАДИО: ОШИБКА", 1);
  uiDrawText(2, 33, cryptoReady ? "ЗАЩИТА: ГОТОВА" : "ЗАЩИТА: ОШИБКА", 1);
  uiDrawText(2, 43, (oledReady && bleOk && queueOk) ? "ЭКРАН+ТЕЛ: ГОТОВО" : "ЭКРАН+ТЕЛ: ПРОВЕРИТЬ", 1);
  const bool allOk = radioReady && cryptoReady && oledReady && bleOk && queueOk;
  uiDrawText(2, 54, allOk ? "ИТОГ: ГОТОВ" : "ИТОГ: ПРОВЕРИТЬ", 1);
}

void uiDrawLinkMetrics() {
  uiDrawFeatureHeader("КАЧЕСТВО СВЯЗИ", UiIcon::Diagnostics);
  const uint32_t attempts = statAckSuccess + statAckTimeout;
  const uint8_t delivery = attempts == 0 ? 0 : static_cast<uint8_t>(min(100UL, (statAckSuccess * 100UL) / attempts));
  const uint32_t protectionErrors = statRxAuthFail + statMessageAuthFail;
  char line[56];
  snprintf(line, sizeof(line), "ДОСТАВКА %u%%", static_cast<unsigned>(delivery));
  uiDrawText(2, 23, line, 1);
  uiDrawText(2, 33, delivery >= 95 ? "ПОТЕРИ: НИЗКИЕ" : (delivery >= 80 ? "ПОТЕРИ: ЕСТЬ" : "ПОТЕРИ: ВЫСОКИЕ"), 1);
  uiDrawText(2, 43, protectionErrors == 0 ? "ЗАЩИТА: В НОРМЕ" : "ЗАЩИТА: ПРОВЕРИТЬ", 1);
  snprintf(line, sizeof(line), "ПЕРЕДАНО ДАЛЬШЕ %lu", static_cast<unsigned long>(statMessagesForwarded));
  uiDrawText(2, 53, line, 1);
}

void uiDrawMemory() {
  uiDrawFeatureHeader("СОСТОЯНИЕ УЗЛА", UiIcon::Memory);
  const uint32_t freeKb = ESP.getFreeHeap() / 1024UL;
  const uint8_t queueUsed = static_cast<uint8_t>(countUsedTxEntries());
  uiDrawCenteredText(23, freeKb >= 80 ? "ПАМЯТЬ: НОРМА" : (freeKb >= 40 ? "ПАМЯТЬ: МАЛО" : "ПАМЯТЬ: КРИТИЧНО"), 1);
  uiDrawCenteredText(36, queueUsed < MAX_TX_QUEUE / 2 ? "ПЕРЕДАЧА: СВОБОДНА" : (queueUsed < MAX_TX_QUEUE ? "ПЕРЕДАЧА: ЗАНЯТА" : "ПЕРЕДАЧА: ПЕРЕГРУЗКА"), 1);
  uiDrawCenteredText(49, bleOutRing.count < 4 ? "ТЕЛЕФОН: НОРМА" : "ТЕЛЕФОН: ЕСТЬ ОЧЕРЕДЬ", 1);
}

void uiDrawSystemOverview() {
  uiDrawFeatureHeader("ОБЗОР", UiIcon::Settings);
  char line[48];
  snprintf(line, sizeof(line), "УЗЕЛ %s", localIdText + 4);
  uiDrawText(2, 23, line, 1);
  snprintf(line, sizeof(line), "РАБОТАЕТ %lu МИН", static_cast<unsigned long>(millis() / 60000UL));
  uiDrawText(2, 33, line, 1);
  snprintf(line, sizeof(line), "РЯДОМ %u  ПУТЕЙ %u", static_cast<unsigned>(countFreshNeighbors()), static_cast<unsigned>(uiCountVisibleRoutes()));
  uiDrawText(2, 43, line, 1);
  uiDrawText(2, 53, radioReady && cryptoReady ? "СИСТЕМА ГОТОВА" : "СИСТЕМУ ПРОВЕРИТЬ", 1);
}

void uiDrawFirmware() {
  uiDrawFeatureHeader("ВЕРСИЯ", UiIcon::Update);
  char line[34];
  snprintf(line, sizeof(line), "SECUREMESH %u.%u.%u", FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
  uiDrawCenteredText(25, line, 1);
  uiDrawCenteredText(39, "ПРОТОКОЛ VANGUARD", 1);
  uiDrawCenteredText(53, "ОПЕРАТОРСКИЙ РЕЖИМ", 1);
}

void uiDrawAbout() {
  uiDrawFeatureHeader("ОБ УСТРОЙСТВЕ", UiIcon::Settings);
  uiDrawCenteredText(27, "SECUREMESH", 1);
  uiDrawCenteredText(38, "АВТОНОМНЫЙ УЗЕЛ СВЯЗИ", 1);
  uiDrawCenteredText(49, "ПРОТОКОЛ VANGUARD", 1);
  uiDrawCenteredText(56, "SECUREMESH", 1);
}

void uiDrawGps() {
  uiDrawFeatureHeader("МОЯ ПОЗИЦИЯ", UiIcon::Navigation);
  const uint32_t chars = gps.charsProcessed();
  const bool freshFix = gps.location.isValid() && gps.location.age() <= GPS_STALE_MS;
  char line[40];

  if (chars < 10) {
    uiDrawCenteredText(25, "GPS НЕ ОТВЕЧАЕТ", 1);
    uiDrawCenteredText(39, "ПРОВЕРЬ ПОДКЛЮЧЕНИЕ", 1);
    uiDrawCenteredText(53, "И ПИТАНИЕ МОДУЛЯ", 1);
    return;
  }

  if (!freshFix) {
    uiDrawCenteredText(24, "ИЩУ СПУТНИКИ", 1);
    snprintf(line, sizeof(line), "НАЙДЕНО: %lu", static_cast<unsigned long>(gps.satellites.isValid() ? gps.satellites.value() : 0));
    uiDrawCenteredText(39, line, 1);
    uiDrawCenteredText(53, "ЛУЧШЕ ВЫЙТИ НА УЛИЦУ", 1);
    return;
  }

  uiDrawCenteredText(15, "МЕСТО НАЙДЕНО", 1);
  snprintf(line, sizeof(line), "ШИР %.6f", gps.location.lat());
  uiDrawTextClipped(2, 28, line, 124, 1);
  snprintf(line, sizeof(line), "ДОЛ %.6f", gps.location.lng());
  uiDrawTextClipped(2, 40, line, 124, 1);
  snprintf(line, sizeof(line), "СПУТНИКОВ: %lu", static_cast<unsigned long>(gps.satellites.isValid() ? gps.satellites.value() : 0));
  uiDrawTextClipped(2, 52, line, 124, 1);
}

uint8_t uiPositionCount() {
  uint8_t count = 0;
  for (size_t i = 0; i < MAX_POSITION_CACHE; ++i) if (positionCache[i].used) count++;
  return count;
}

const PositionRecord* uiPositionAt(uint8_t logicalIndex) {
  uint8_t seen = 0;
  for (size_t i = 0; i < MAX_POSITION_CACHE; ++i) {
    if (!positionCache[i].used) continue;
    if (seen == logicalIndex) return &positionCache[i];
    seen++;
  }
  return nullptr;
}

void uiDrawPositions() {
  uiDrawFeatureHeader("ГДЕ УЗЛЫ", UiIcon::Network);
  const uint8_t count = uiPositionCount();
  if (count == 0) {
    uiDrawCenteredText(27, "ПОКА НЕТ КООРДИНАТ", 1);
    uiDrawCenteredText(41, "ЖДУ ДАННЫЕ ОТ УЗЛОВ", 1);
    uiDrawCenteredText(54, "ОНИ ПОЯВЯТСЯ САМИ", 1);
    return;
  }
  if (uiState.positionIndex >= count) uiState.positionIndex = 0;
  const PositionRecord* pos = uiPositionAt(uiState.positionIndex);
  if (pos == nullptr) return;
  char id[9]; formatNodeId(pos->nodeId, id);
  char line[56];
  const bool current = (pos->flags & POSITION_FLAG_FIX) != 0;
  snprintf(line, sizeof(line), "%u/%u УЗЕЛ %s", static_cast<unsigned>(uiState.positionIndex + 1U), static_cast<unsigned>(count), id);
  uiDrawTextClipped(2, 15, line, 124, 1);
  snprintf(line, sizeof(line), "ШИР %.6f", static_cast<double>(pos->latitudeE7) / 1e7);
  uiDrawTextClipped(2, 27, line, 124, 1);
  snprintf(line, sizeof(line), "ДОЛ %.6f", static_cast<double>(pos->longitudeE7) / 1e7);
  uiDrawTextClipped(2, 39, line, 124, 1);
  const uint32_t age = millis() - pos->receivedAtMs;
  if (current && age < 15000UL) snprintf(line, sizeof(line), "ОБНОВЛЕНО СЕЙЧАС");
  else snprintf(line, sizeof(line), "ОБНОВЛЕНО %lus НАЗАД", static_cast<unsigned long>(age / 1000UL));
  uiDrawTextClipped(2, 51, line, 124, 1);
}

void uiDrawSosStatus() {
  uiDrawFeatureHeader("СОСТОЯНИЕ SOS", UiIcon::Alert);
  if (!activeSos.active) {
    uiDrawCenteredText(28, "ТРЕВОГ НЕТ", 1);
    uiDrawCenteredText(43, "SOS ГОТОВ К РАБОТЕ", 1);
    return;
  }
  char id[9]; formatNodeId(activeSos.originNodeId, id);
  char line[40];
  snprintf(line, sizeof(line), "ТРЕВОГА ОТ %s", id);
  uiDrawTextClipped(2, 20, line, 124, 1);
  uiDrawTextClipped(2, 35, activeSos.acknowledged ? "ПОДТВЕРЖДЕНА" : "ЖДЕТ ПОДТВЕРЖДЕНИЯ", 124, 1);
  snprintf(line, sizeof(line), "%lus НАЗАД", static_cast<unsigned long>((millis() - activeSos.receivedAtMs) / 1000UL));
  uiDrawTextClipped(2, 50, line, 124, 1);
}

void uiDrawSosSend() {
  uiDrawFeatureHeader("ОТПРАВИТЬ SOS", UiIcon::Alert);
  uiDrawCenteredText(22, "SOS ГОТОВ", 1);
  const int local = findPositionRecord(localNodeId);
  const bool hasFix = local >= 0 && (positionCache[local].flags & POSITION_FLAG_FIX) != 0;
  uiDrawCenteredText(38, hasFix ? "МЕСТО ДОБАВЛЕНО" : "ПОСЛЕДНЯЯ ТОЧКА", 1);
  uiDrawCenteredText(54, "ОТПРАВКА С ТЕЛЕФОНА", 1);
}

void uiDrawOfflineMap() {
  uiDrawFeatureHeader("КАРТА", UiIcon::Map);
  uiDrawCenteredText(21, "КАРТА В ПРИЛОЖЕНИИ", 1);
  uiDrawCenteredText(35, "СКАЧАЙ НУЖНЫЙ РЕГИОН", 1);
  uiDrawCenteredText(49, "ОДИН РАЗ", 1);
  uiDrawCenteredText(59, "ДАЛЬШЕ БЕЗ СЕТИ", 1);
}

void uiDrawPlannedFeature(const UiMenuItem& item) {
  uiDrawFeatureHeader(item.label, item.icon, true);
  display.drawRoundRect(46, 25, 36, 10, 2, SSD1306_WHITE);
  uiDrawText(52, 27, "ПЛАН", 1);
  uiDrawCenteredText(39, "ЕЩЕ НЕ ДОСТУПНО", 1);
  uiDrawWrappedText(item.hint, 4, 49, 120, 2);
}

void uiDrawFeature() {
  const UiMenuItem* item = uiFindFeatureItem(uiState.feature);
  if (item == nullptr) {
    uiDrawFeatureHeader("НЕИЗВЕСТНО", UiIcon::Settings, true);
    uiDrawCenteredText(38, "НЕТ ОПИСАНИЯ", 1);
    return;
  }
  if (item->state == UiFeatureState::Planned) {
    uiDrawPlannedFeature(*item);
    return;
  }

  switch (uiState.feature) {
    case UiFeatureId::Inbox: uiDrawMessages(); break;
    case UiFeatureId::Compose: uiDrawCompose(); break;
    case UiFeatureId::Neighbors: uiDrawNetwork(); break;
    case UiFeatureId::Routes: uiDrawRoutes(); break;
    case UiFeatureId::RadioStatus: uiDrawRadioStatus(); break;
    case UiFeatureId::Gps: uiDrawGps(); break;
    case UiFeatureId::Positions: uiDrawPositions(); break;
    case UiFeatureId::OfflineMap: uiDrawOfflineMap(); break;
    case UiFeatureId::SosStatus: uiDrawSosStatus(); break;
    case UiFeatureId::SosSend: uiDrawSosSend(); break;
    case UiFeatureId::BleSession: uiDrawBleSession(); break;
    case UiFeatureId::BleRadar: uiDrawBleRadar(); break;
    case UiFeatureId::FieldTest: uiDrawTest(); break;
    case UiFeatureId::SelfTest: uiDrawSelfTest(); break;
    case UiFeatureId::LinkMetrics: uiDrawLinkMetrics(); break;
    case UiFeatureId::Memory: uiDrawMemory(); break;
    case UiFeatureId::SystemOverview: uiDrawSystemOverview(); break;
    case UiFeatureId::Firmware: uiDrawFirmware(); break;
    case UiFeatureId::About: uiDrawAbout(); break;
    default: uiDrawPlannedFeature(*item); break;
  }
}

void uiDrawPairing(uint32_t now) {
  const uint32_t remaining = (blePasskeyDeadlineAtMs > now) ? (blePasskeyDeadlineAtMs - now + 999UL) / 1000UL : 0;
  const uint32_t age = now - uiState.overlayEnteredAtMs;
  const uint8_t pulse = static_cast<uint8_t>((age / 90UL) % 8UL);
  const uint8_t ringR = static_cast<uint8_t>(8 + (pulse < 4 ? pulse : 7 - pulse));

  // Critical modal owns the entire screen. The subtle pulse says "action is
  // required" without competing with the six digits the user must copy.
  display.drawCircle(64, 8, ringR, SSD1306_WHITE);
  uiDrawShieldIcon(58, 2);
  uiDrawCenteredText(16, "ПОДКЛЮЧЕНИЕ", 1);
  uiDrawCenteredText(25, "КОД ДЛЯ ТЕЛЕФОНА", 1);

  char code[7];
  snprintf(code, sizeof(code), "%06lu", static_cast<unsigned long>(bleActivePasskey % 1000000UL));
  const int16_t startX = 25;
  for (uint8_t i = 0; i < 6; ++i) {
    const int16_t x = startX + i * 14;
    display.drawRoundRect(x, 34, 12, 17, 2, SSD1306_WHITE);
    char digit[2] = {code[i], '\0'};
    uiDrawText(x + 3, 38, digit, 1);
  }

  char timeText[16];
  snprintf(timeText, sizeof(timeText), "%luс", static_cast<unsigned long>(remaining));
  uiDrawText(107, 53, timeText, 1);
  uiDrawText(2, 53, "КОД АКТИВЕН", 1);
  const uint32_t totalSeconds = BLE_PASSKEY_LIFETIME_MS / 1000UL;
  const uint8_t bar = totalSeconds == 0 ? 0 : static_cast<uint8_t>(min(124UL, (remaining * 124UL) / totalSeconds));
  display.drawRoundRect(1, 61, 126, 3, 1, SSD1306_WHITE);
  if (bar > 0) display.fillRect(2, 62, bar, 1, SSD1306_WHITE);
}

void uiDrawConnectedBanner() {
  const uint32_t now = millis();
  const uint32_t age = now - uiState.overlayEnteredAtMs;
  uiDrawStatusBar();

  // Animated confirmation check: quick and unambiguous, then the UI returns
  // automatically. This is feedback, not a modal that can get stuck.
  display.drawCircle(64, 31, 13, SSD1306_WHITE);
  if (age > 100) display.drawLine(56, 31, 62, 37, SSD1306_WHITE);
  if (age > 220) display.drawLine(62, 37, 73, 24, SSD1306_WHITE);
  if (age > 360) uiDrawCenteredText(48, "ТЕЛЕФОН ЗАЩИЩЕН", 1);
  if (age > 520) uiDrawCenteredText(57, bleCurrentBondedFlag ? "ДОВЕРЕННЫЙ ТЕЛЕФОН" : "СЕССИЯ ГОТОВА", 1);
}

void uiDrawToast(uint32_t now) {
  if (uiState.toastUntilMs == 0 || timeReached(now, uiState.toastUntilMs)) return;
  const uint32_t age = now - uiState.toastStartedAtMs;
  const uint32_t remaining = uiState.toastUntilMs - now;
  int16_t y = 52;
  if (age < UI_TOAST_SLIDE_MS) {
    const uint32_t t = age;
    const uint32_t eased = (t * (2UL * UI_TOAST_SLIDE_MS - t)) / UI_TOAST_SLIDE_MS;
    y = static_cast<int16_t>(64 - min(12UL, (eased * 12UL) / UI_TOAST_SLIDE_MS));
  } else if (remaining < UI_TOAST_SLIDE_MS) {
    const uint32_t t = UI_TOAST_SLIDE_MS - remaining;
    y = static_cast<int16_t>(52 + min(12UL, (t * t * 12UL) / (UI_TOAST_SLIDE_MS * UI_TOAST_SLIDE_MS)));
  }
  display.fillRoundRect(0, y, OLED_WIDTH, 12, 2, SSD1306_WHITE);
  uiDrawTextClipped(3, y + 1, uiState.toastTitle, 55, 1, SSD1306_BLACK);
  uiDrawTextClipped(59, y + 1, uiState.toastBody, 125, 1, SSD1306_BLACK);
}

void uiDrawCurrentScene() {
  switch (uiState.scene) {
    case UiScene::Home: uiDrawHome(); break;
    case UiScene::Menu: uiDrawMenu(); break;
    case UiScene::Feature: uiDrawFeature(); break;
  }
}

void uiApplyTransition(uint32_t now) {
  if (uiState.transitionStartedAtMs == 0) return;
  const uint32_t elapsed = now - uiState.transitionStartedAtMs;
  if (elapsed >= UI_TRANSITION_MS) {
    uiState.transitionStartedAtMs = 0;
    return;
  }
  // Never hide content during navigation. A short edge marker provides motion
  // feedback without delaying comprehension in a stressful situation.
  const uint8_t x = static_cast<uint8_t>(min(127UL, (elapsed * 127UL) / UI_TRANSITION_MS));
  display.drawFastVLine(x, 11, OLED_HEIGHT - 11, SSD1306_WHITE);
}

UiOverlayKind uiResolveOverlay(uint32_t now) {
  // Authentication result always outranks the historical Pairing state. BLE
  // callbacks can occur between loop iterations, so this prevents a one-frame
  // or persistent stale code even before processBle() consumes the callback.
  const bool pairing = bleState == BleState::Pairing &&
    blePairingUiVisibleFlag && blePasskeyPreparedFlag && bleActivePasskey != 0 &&
    !bleAuthSuccessFlag && !bleAuthCompleteFlag &&
    blePasskeyDeadlineAtMs != 0 && !timeReached(now, blePasskeyDeadlineAtMs);
  if (pairing) return UiOverlayKind::Pairing;

  const bool connected = bleState == BleState::ProtocolReady && bleAuthSuccessFlag &&
    bleConnectedBannerUntilMs != 0 && !timeReached(now, bleConnectedBannerUntilMs);
  if (connected) return UiOverlayKind::Connected;
  return UiOverlayKind::None;
}

void uiUpdateOverlayState(uint32_t now) {
  const UiOverlayKind resolved = uiResolveOverlay(now);
  if (resolved == uiState.overlay) return;
  uiState.previousOverlay = uiState.overlay;
  uiState.overlay = resolved;
  uiState.overlayEnteredAtMs = now;
  uiState.dirty = true;
  uiState.nextFrameAtMs = now;
  if (resolved == UiOverlayKind::Pairing && uiState.criticalPendingSinceMs == 0) {
    uiState.criticalPendingSinceMs = now;
  } else if (resolved != UiOverlayKind::Pairing) {
    uiState.criticalPendingSinceMs = 0;
  }
}

bool uiFeatureIsDynamic(UiFeatureId feature) {
  switch (feature) {
    case UiFeatureId::Inbox:
    case UiFeatureId::Neighbors:
    case UiFeatureId::Routes:
    case UiFeatureId::RadioStatus:
    case UiFeatureId::Gps:
    case UiFeatureId::Positions:
    case UiFeatureId::SosStatus:
    case UiFeatureId::SosSend:
    case UiFeatureId::BleSession:
    case UiFeatureId::BleRadar:
    case UiFeatureId::FieldTest:
    case UiFeatureId::SelfTest:
    case UiFeatureId::LinkMetrics:
    case UiFeatureId::Memory:
    case UiFeatureId::SystemOverview:
      return true;
    default:
      return false;
  }
}

void processUi() {
  if (!oledReady || !uiState.initialized) return;
  const uint32_t now = millis();

  if (blePairingUiRefreshFlag) {
    blePairingUiRefreshFlag = false;
    uiState.dirty = true;
    uiState.nextFrameAtMs = now;
    if (uiState.criticalPendingSinceMs == 0) uiState.criticalPendingSinceMs = now;
  }

  if (uiState.toastUntilMs != 0 && timeReached(now, uiState.toastUntilMs)) {
    uiState.toastUntilMs = 0;
    uiState.toastStartedAtMs = 0;
    uiState.toastTitle[0] = '\0';
    uiState.toastBody[0] = '\0';
    uiStartNextNotification(now);
    uiState.dirty = true;
  } else if (uiState.toastUntilMs == 0 && uiNotificationCount > 0) {
    uiStartNextNotification(now);
  }

  if (!uiState.bootFinished && now - uiState.bootStartedAtMs >= UI_BOOT_DURATION_MS) {
    uiState.bootFinished = true;
    uiResetNavigation();
    uiState.scene = UiScene::Home;
    uiState.transitionStartedAtMs = now;
    uiState.dirty = true;
  }

  if (fieldTest.state != uiState.lastFieldTestState) {
    if (fieldTest.state == FieldTestState::Running) {
      uiState.feature = UiFeatureId::FieldTest;
      uiSetScene(UiScene::Feature);
      uiShowToast("ТЕСТ", "ЗАПУЩЕН", 1000);
    } else if (uiState.lastFieldTestState == FieldTestState::Running && fieldTest.state == FieldTestState::Finished) {
      uiShowToast("ТЕСТ", "ЗАВЕРШЕН", 1300);
    }
    uiState.lastFieldTestState = fieldTest.state;
    uiState.dirty = true;
  }

  // System overlays are derived from authoritative BLE state every frame; they
  // are never sticky booleans owned by drawing code.
  uiUpdateOverlayState(now);
  const bool pairing = uiState.overlay == UiOverlayKind::Pairing;
  const bool connectedBanner = uiState.overlay == UiOverlayKind::Connected;

  uiEnsureMenuWindow();
  const int16_t menuTarget = UI_MENU_FIRST_Y + static_cast<int16_t>(uiCurrentMenuIndexRef() - uiCurrentMenuScrollRef()) * UI_MENU_ROW_H;
  const bool cursorAnimating = uiState.scene == UiScene::Menu && uiState.menuCursorY != menuTarget;
  const bool transitionAnimating = uiState.transitionStartedAtMs != 0 && now - uiState.transitionStartedAtMs < UI_TRANSITION_MS;
  const bool toastAnimating = uiState.toastUntilMs != 0 && !timeReached(now, uiState.toastUntilMs);
  const bool dynamicScene = uiState.scene == UiScene::Home ||
    (uiState.scene == UiScene::Feature && uiFeatureIsDynamic(uiState.feature));
  const bool critical = pairing;
  const bool animating = !uiState.bootFinished || pairing || connectedBanner || cursorAnimating || transitionAnimating || toastAnimating;


  const uint32_t frameInterval = critical ? UI_CRITICAL_FRAME_MS :
    (animating ? UI_ANIMATION_FRAME_MS : (fieldTest.state == FieldTestState::Running ? UI_FAST_FRAME_MS : OLED_REFRESH_MS));

  if (!uiState.dirty && !timeReached(now, uiState.nextFrameAtMs)) return;
  if (!uiState.dirty && !dynamicScene && !animating) return;

  // A pending radio IRQ is always handled first. Normal UI also yields while TX
  // is active. A pairing screen may defer for at most a small bounded interval;
  // after that one OLED flush is preferable to leaving the user without the PIN.
  if (radioIrqFlag) {
    uiState.nextFrameAtMs = now + 3;
    return;
  }
  if (radioTransmitting && !critical) {
    uiState.nextFrameAtMs = now + 15;
    return;
  }
  if (radioTransmitting && critical && uiState.criticalPendingSinceMs != 0 &&
      now - uiState.criticalPendingSinceMs < UI_CRITICAL_MAX_DEFER_MS) {
    uiState.nextFrameAtMs = now + 5;
    return;
  }

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setTextWrap(false);

  if (pairing) uiDrawPairing(now);
  else if (!uiState.bootFinished) uiDrawBoot(now);
  else if (connectedBanner) uiDrawConnectedBanner();
  else {
    uiDrawCurrentScene();
    uiApplyTransition(now);
    uiDrawToast(now);
  }

  display.display();
  uiState.lastFlushAtMs = now;
  if (critical) uiState.criticalPendingSinceMs = 0;
  uiState.dirty = false;
  uiState.nextFrameAtMs = now + frameInterval;
}

// ============================================================
// 21. PERIODIC STATUS
// ============================================================

uint32_t nextStatusAtMs = 0;

void processPeriodicStatus() {
  const uint32_t now = millis();
  if (!timeReached(now, nextStatusAtMs)) return;

  nextStatusAtMs = now + SERIAL_STATUS_INTERVAL_MS;
  Serial.printf(
    "[V08] N=%u R=%u Q=%u RX=%lu TX=%lu ACK=%lu/%lu AUTHFAIL=%lu\r\n",
    static_cast<unsigned>(countFreshNeighbors()),
    static_cast<unsigned>(countStaticRoutes()),
    static_cast<unsigned>(countUsedTxEntries()),
    static_cast<unsigned long>(statRxValid),
    static_cast<unsigned long>(statTxFrames),
    static_cast<unsigned long>(statAckSuccess),
    static_cast<unsigned long>(statAckTimeout),
    static_cast<unsigned long>(statRxAuthFail)
  );
}

void processGps() {
  if (!gpsSerialReady) return;
  while (gpsSerial.available() > 0) gps.encode(static_cast<char>(gpsSerial.read()));
  const uint32_t now = millis();
  if (!gps.location.isUpdated()) return;

  PositionRecord local = makeLocalPositionRecord();
  const int slot = positionSlotFor(localNodeId);
  if (slot >= 0) {
    // Keep the last known coordinates when the receiver temporarily loses a fresh FIX.
    // The FIX flag still stays clear, so Android/OLED can label the point as stale instead
    // of making the node disappear from the map.
    if ((local.flags & POSITION_FLAG_FIX) == 0 && positionCache[slot].used &&
        (positionCache[slot].latitudeE7 != 0 || positionCache[slot].longitudeE7 != 0)) {
      local.latitudeE7 = positionCache[slot].latitudeE7;
      local.longitudeE7 = positionCache[slot].longitudeE7;
      local.altitudeCm = positionCache[slot].altitudeCm;
      local.receivedAtMs = positionCache[slot].receivedAtMs;
    }
    positionCache[slot] = local;
    if (now - lastGpsBleEventAtMs >= GPS_LOCAL_EVENT_MIN_MS) {
      emitPositionBleEvent(local);
      lastGpsBleEventAtMs = now;
    }
  }

  if ((local.flags & POSITION_FLAG_FIX) == 0 || !radioReady || !cryptoReady) return;
  const double movedMeters = (lastPublishedLatitudeE7 == 0 && lastPublishedLongitudeE7 == 0)
    ? GPS_SIGNIFICANT_MOVE_METERS + 1.0
    : approxDistanceMetersE7(lastPublishedLatitudeE7, lastPublishedLongitudeE7, local.latitudeE7, local.longitudeE7);
  const bool moving = (local.flags & POSITION_FLAG_SPEED) != 0 && local.speedCms >= static_cast<uint16_t>(GPS_MOVING_SPEED_MPS * 100.0);
  const uint32_t desiredInterval = moving ? GPS_MESH_MOVING_INTERVAL_MS : GPS_MESH_STATIONARY_INTERVAL_MS;
  const bool significantMove = movedMeters >= GPS_SIGNIFICANT_MOVE_METERS && now - lastGpsMeshPublishAtMs >= GPS_MESH_MIN_INTERVAL_MS;
  const bool scheduled = timeReached(now, nextGpsMeshPublishAtMs);
  if (!significantMove && !scheduled) return;

  uint8_t payload[POSITION_PAYLOAD_SIZE]; encodePositionPayload(local, payload);
  const QueueMessageResult qr = queueApplicationMessage(
    MessageType::Position, BROADCAST_ID, payload, sizeof(payload), true,
    RoutePolicy::Routed, nullptr, 4, DEFAULT_HOP_LIMIT);
  if (qr == QueueMessageResult::Ok) {
    lastGpsMeshPublishAtMs = now;
    lastPublishedLatitudeE7 = local.latitudeE7;
    lastPublishedLongitudeE7 = local.longitudeE7;
    nextGpsMeshPublishAtMs = now + desiredInterval + randomBetween(0, GPS_MESH_JITTER_MS);
    Serial.printf("[GPS TX] seq=%u lat=%.7f lon=%.7f move=%.1fm next=%lums\r\n",
      static_cast<unsigned>(local.sequence), static_cast<double>(local.latitudeE7) / 1e7,
      static_cast<double>(local.longitudeE7) / 1e7, movedMeters, static_cast<unsigned long>(desiredInterval));
  } else {
    nextGpsMeshPublishAtMs = now + 2000 + randomBetween(0, 1000);
  }
}

// ============================================================
// 22. SETUP / LOOP
// ============================================================

void setup() {
  Serial.begin(115200);
  delay(500);

  const bool identityOk = initializeIdentity();
  if (identityOk) initializeVanguardRouter();
  configureVanguardTimingFromRadioProfile();
  const bool cryptoOk = identityOk && initializeCrypto();
  initializeOled();
  initializeGps();
  const bool radioOk = cryptoOk && initializeRadio();

  const uint32_t heapBeforeBle = static_cast<uint32_t>(ESP.getFreeHeap());
  const uint32_t largestBeforeBle = largestFreeHeapBytes();
  const bool bleOk = initializeBle();
  const uint32_t heapAfterBle = static_cast<uint32_t>(ESP.getFreeHeap());
  const uint32_t largestAfterBle = largestFreeHeapBytes();

  const uint32_t now = millis();
  nextHelloAtMs = now + randomBetween(700, 1600);
  nextStatusAtMs = now + SERIAL_STATUS_INTERVAL_MS;
  nextNeighborLifecycleAtMs = now + 1000;
  lastRadioRetryAtMs = now;

  Serial.println();
  Serial.println("SecureMesh v1.0.4 OPERATOR");
  Serial.printf("Node ID: %s\r\n", localIdText);
  Serial.printf("Identity/nonce state: %s\r\n", identityOk ? "OK" : "FAIL-CLOSED");
  Serial.printf("AES-256-GCM: %s\r\n", cryptoOk ? "OK" : "FAIL-CLOSED");
  Serial.printf("Radio: %s\r\n", radioOk ? "OK" : "ERROR");
  Serial.printf("BLE: %s stack=NimBLE app-protocol=2\r\n", bleOk ? "OK" : "ERROR");
  Serial.printf(
    "VANGUARD timing: discovery=%lums settle=%lums ackTimeout=%lums\r\n",
    static_cast<unsigned long>(vanguardRuntime.timing().discoveryTimeoutMs),
    static_cast<unsigned long>(vanguardRuntime.timing().rreqSettleMs),
    static_cast<unsigned long>(ackTimeoutMs));
  Serial.printf(
    "[MEM BLE] heap %lu -> %lu (delta=%ld), largest %lu -> %lu\r\n",
    static_cast<unsigned long>(heapBeforeBle),
    static_cast<unsigned long>(heapAfterBle),
    static_cast<long>(heapAfterBle) - static_cast<long>(heapBeforeBle),
    static_cast<unsigned long>(largestBeforeBle),
    static_cast<unsigned long>(largestAfterBle)
  );
  Serial.printf(
    "[FLASH] sketch=%lu freeSketch=%lu\r\n",
    static_cast<unsigned long>(ESP.getSketchSize()),
    static_cast<unsigned long>(ESP.getFreeSketchSpace())
  );
  Serial.println("BLE passkeys are intentionally shown on OLED only.");
  Serial.println("First pairing: OLED shows the exact random 6-digit Security Manager code.");
  Serial.println("Bonded reconnect may skip PIN entry and go directly to TRUSTED BLE.");
  Serial.println("To force a clean first-pairing test: run 'ble bonds clear' and forget SecureMesh on Android.");
  Serial.println("Type: help");

  if (!identityOk || !cryptoOk) setLastEvent("CRYPTO FAIL-CLOSED");
  else if (!radioOk) setLastEvent("RADIO ERROR");
  else if (!bleOk) setLastEvent("BLE ERROR");
  else setLastEvent("CONTROL READY");

  initializeUi();
}

void loop() {
  processSerialInput();
  processGps();

  processBle();
  processBleCommandQueue();
  processBleRadar();

  processRadioInterrupt();
  processTxWatchdog();
  processRadioRecovery();
  processAckTimeout();
  processDeferredVanguardControls();

  processVanguardRuntime();
  processPendingRelays();
  processFieldTest();
  processNeighborLifecycleEvents();
  processOperationalHealthMonitor();

  if (cryptoReady) {
    if (radioReady) processHelloScheduler();
    processTxScheduler();
  }

  processUi();

  processPeriodicStatus();
  delay(1);
}
