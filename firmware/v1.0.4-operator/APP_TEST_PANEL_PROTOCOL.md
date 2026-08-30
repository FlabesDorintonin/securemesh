# SecureMesh v1.0.4 Operator — инженерный BLE API Commander Console

Документ описывает минимальный BLE API, достаточный для приложения-лаборатории VANGUARD. Все multi-byte integer — little-endian.

## 1. GATT

- Service: `7b7f0001-6b6f-4d65-7368-534543555245`
- Info: `7b7f0002-6b6f-4d65-7368-534543555245`
- Command: `7b7f0003-6b6f-4d65-7368-534543555245`
- Response: `7b7f0004-6b6f-4d65-7368-534543555245`
- Event: `7b7f0005-6b6f-4d65-7368-534543555245`

Команды принимаются только после encrypted+authenticated BLE session и состояния `PROTOCOL_READY`.

## 2. Transport fragment

Каждый application packet фрагментируется поверх GATT.

| offset | size | field |
|---:|---:|---|
| 0 | 2 | magic = `0x4653` (`53 46`) |
| 2 | 1 | fragment version = 1 |
| 3 | 2 | transportId, non-zero |
| 5 | 1 | fragmentIndex |
| 6 | 1 | fragmentCount |
| 7 | 2 | total application-packet length |
| 9 | 2 | byte offset |
| 11 | 1 | fragmentLength |
| 12 | N | data |

Максимум application packet = 384 bytes. Максимум fragment data = 180 bytes, но реальный размер ограничивается negotiated ATT MTU.

RX reassembly требует строгий порядок: fragment index и offset должны идти последовательно.

## 3. Application packet

Header = 10 bytes:

| offset | size | field |
|---:|---:|---|
| 0 | 2 | magic = `0x4D53` (`53 4D`) |
| 2 | 1 | protocol version = 2 |
| 3 | 1 | packetType: 1=Command, 2=Response, 3=Event |
| 4 | 2 | requestId; event использует 0 |
| 6 | 1 | opcode / eventType |
| 7 | 1 | status; command должен посылать 0 |
| 8 | 2 | payloadLength |
| 10 | N | payload |

Status:
`0 OK, 1 INVALID_COMMAND, 2 INVALID_ARGUMENT, 3 NOT_AUTHENTICATED, 4 NOT_SUPPORTED, 5 BUSY, 6 NO_ROUTE, 7 TX_QUEUE_FULL, 8 RADIO_UNAVAILABLE, 9 CRYPTO_UNAVAILABLE, 10 TEST_ALREADY_RUNNING, 11 TEST_NOT_RUNNING, 12 TIMEOUT, 13 INTERNAL_ERROR`.

## 4. Команды панели

| opcode | имя | payload команды |
|---:|---|---|
| 1 | GetInfo | empty |
| 2 | GetStatus | empty |
| 3 | GetNeighbors | empty |
| 4 | GetRoutes | empty |
| 5 | SendMessage | `destination u32, length u8, data[length]` |
| 6 | AddStaticRoute | `destination u32, nextHop u32` |
| 7 | RemoveStaticRoute | `destination u32` |
| 8 | StartFieldTest | `destination u32, packetCount u16, intervalMs u32, payloadSize u8, mode u8` |
| 9 | StopFieldTest | empty |
| 10 | GetFieldTestStatus | empty |
| 11 | PingLocal | empty |
| 12 | ClearStats | empty |
| 13 | GetUiState | empty |
| 14 | UiAction | `action u8` |
| 15 | GetKnownNodes | empty |
| 16 | GetManifest | empty |
| 17 | SetManifest | `epoch u32, count u8, nodeId[count] u32` |
| 18 | DiscoverRoute | `destination u32, flags u8`; bit0=force fresh generation |
| 19 | GetRoutingDiagnostics | empty |
| 20 | InjectLinkFailure | `nodeId u32` for default 30s OR `nodeId u32, durationMs u32`; duration=0 clears |
| 21 | ClearDynamicRoutes | empty |
| 22 | SetLabLinkPolicy | `peer u32, flags u8, durationMs u32, reliabilityQ15 u16, ecaQ16 u32` |
| 23 | GetLabLinkPolicies | empty |
| 24 | GetPositions | empty |
| 25 | RaiseSos | `sosType u8` |
| 26 | AckSos | `origin u32, sosId u32` |
| 27 | SendCommandNotice | `destination u32, kind u8, data[8]` |
| 28 | GetBleRadar | empty |
| 29 | ClearBleRadar | empty |
| 30 | GetOperationalHealth | empty |
| 31 | GetSelfDiagnostics | empty |
| 38 | GetOledFrameChunk | `chunkIndex u8`, 0..3 |

`StartFieldTest.mode`: `0=Routed`, `1=DirectOnly`.

Field Test limits v1.0.4: `packetCount=1..500`, `intervalMs=250..60000`, `payloadSize=16..70`; **max payload = 70 bytes**. Commander обязан использовать тот же максимум, что firmware.

### Contract policy v1.0.4

- fixed-size response принимается только при точной длине payload;
- variable-size response принимается только если `actual = header + count × record`;
- Response обязан совпадать с ожидаемым `requestId + opcode`;
- fragment transport version и application protocol version являются разными контрактами (`v1` и `v2` соответственно);
- любое изменение wire layout требует одновременного обновления firmware, Android/Commander, этого документа и cross-contract gates.

## 5. Главные response payloads

### GetInfo

`protocol u8, meshVersion u8, messageVersion u8, fwMajor u8, fwMinor u8, fwPatch u8, localNodeId u32, role u8, capabilities u32, networkId u16, bleState u8, securityFlags u8, permissions u32`

Для этой сборки firmware = `1.0.4`, mesh wire version = 8.

### GetStatus

`localNodeId u32, uptimeMs u32, radioReady u8, cryptoReady u8, bleState u8, freshNeighborCount u8, staticRouteCount u8, txQueueUsed u8, rxValid u32, txFrames u32, ackSuccess u32, ackTimeout u32, authFail u32, freeHeap u32, largestFreeHeap u32`

### GetKnownNodes

`count u8`, затем `nodeId u32[count]`.

Эта таблица сохраняется в NVS. Она отвечает на вопрос «какие authenticated identities устройство уже знает», а не «кто сейчас находится в радиоэфире».

### GetManifest

`valid u8, networkEpoch u32, digest u32, count u8`, затем записи:

`slot u8, nodeId u32`

Панель должна сравнить `networkEpoch + digest` на всех узлах перед exact-G2 тестом. Несовпадение — красный статус `MANIFEST MISMATCH`.

### GetNeighbors

`count u8`, затем до вместимости response по 29 bytes:

`nodeId u32, ageMs u32, rssiTenths i16, snrTenths i16, helloPdrTenthsPercent u16, txAckPdrTenthsPercent u16, rxFrames u32, txAttempts u32, txAckSuccesses u32, fresh u8`

Важно: detailed response может быть усечён bounded BLE packet. Полный список identity смотри через GetKnownNodes.

### GetRoutes

`count u8`, затем records 9 bytes:

`destination u32, nextHop u32, routeSource u8`

RouteSource: `0=None, 1=DirectNeighbor, 2=VanguardDynamic, 3=VanguardBackup, 4=StaticTable`.

Это компактный полный routing view. Для глубокой информации вызывай GetRoutingDiagnostics.


### GetOperationalHealth (v1)

Payload 17 bytes:

`version u8, score u8, level u8, flags u16, radioScore u8, meshScore u8, routingScore u8, memoryScore u8, queueScore u8, gpsScore u8, bleScore u8, freshNeighbors u8, routeCount u8, exactG2Count u8, queueUsed u8, queueCapacity u8`

`level`: `0=Critical, 1=Degraded, 2=Good, 3=Excellent`.

Health flags: radio down, crypto down, no fresh peer, queue pressure, low heap, manifest missing, GPS no fix, BLE down, radio recovery history, ACK loss. Это read-only слой: score **не имеет права сам менять маршрут или RF-профиль**.

### GetSelfDiagnostics (v1)

Payload 43 bytes:

`version u8, score u8, level u8, flags u16, radioReady u8, cryptoReady u8, bleReady u8, gpsState u8, oledReady u8, freshNeighbors u8, routeCount u8, exactG2Count u8, queueUsed u8, queueCapacity u8, freeHeap u32, largestFreeHeap u32, ackSuccess u32, ackTimeout u32, txErrors u32, radioRecoveries u32, authFails u32`

`gpsState`: `0=UART unavailable, 1=no fresh fix, 2=fresh fix`.

### GetOledFrameChunk (v1.0.4 framebuffer extension)

Команда доступна только если `GetInfo.capabilities` содержит bit15 `OLED_FRAMEBUFFER`. Она читает **реальный 1-bit framebuffer Adafruit_SSD1306 128×64**, а не реконструированное UI-состояние. Четыре последовательных запроса собирают 1024 bytes экрана. `chunkIndex=0` атомарно фиксирует новый snapshot; chunks 1..3 возвращаются из того же кэша и поэтому не рвутся между OLED redraw.

Response header 11 bytes + data:

`snapshotVersion u8 (=1), width u8 (=128), height u8 (=64), snapshotId u32, chunkIndex u8, chunkCount u8 (=4), dataLength u16, data[dataLength]`

Каждый полный chunk содержит 256 bytes. Раскладка совпадает с `Adafruit_SSD1306::getBuffer()`: byte index `x + (y/8)*128`, pixel bit `1 << (y & 7)`. Android обязан собирать только chunks с одинаковым `snapshotId`.

BLE maintenance opcodes 32..37 остаются serial-only и недоступны приложению; opcode 38 является authenticated read-only extension.

### Operational health event

Event type `32` отправляется только при смене health-level либо важных fault flags. Payload: `score u8, level u8, flags u16`. Это ограничивает BLE/event spam.

## 6. RoutingDiagnostics v2

Header, последовательно:

| field | type |
|---|---|
| diagVersion=2 | u8 |
| manifestValid | u8 |
| networkEpoch | u32 |
| manifestDigest | u32 |
| localRouteSeq | u32 |
| acceptedPrimary | u32 |
| acceptedBackup | u32 |
| acceptedAlternate | u32 |
| rejectedOldGeneration | u32 |
| rejectedLoop | u32 |
| rejectedInfeasible | u32 |
| rejectedWorse | u32 |
| rejectedSamePath | u32 |
| promotionsG2 | u32 |
| promotionsAlternate | u32 |
| expirations | u32 |
| routeErrors | u32 |
| controlBudgetDrops | u32 |
| controlBudgetTokensUs | u32 |
| deferredQueued | u32 |
| deferredDrops | u32 |
| activeDeferred | u8 |
| labFaultRxDrops | u32 |
| labFaultTxDrops | u32 |
| activeLabFaults | u8 |
| detailedRouteCount | u8 |

Размер header = 89 bytes.

После него до 5 detailed route records по 56 bytes:

| field | type |
|---|---|
| destination | u32 |
| primaryNextHop | u32 |
| backupNextHop | u32 |
| alternateNextHop | u32 |
| generationBootEpoch | u32 |
| generationRouteSeq | u32 |
| guardRank | u32 |
| feasibleDistance | u32 |
| primaryInternalMask | u32 |
| backupInternalMask | u32 |
| primaryPathTag | u32 |
| backupPathTag | u32 |
| primaryEcaQ16 | u32 |
| primaryReliabilityQ15 | u16 |
| flags | u8 |
| backupLease | u8 |

Flags:
- bit0 = primary exact mask;
- bit1 = exact G2 currently available;
- bit2 = current primary was promoted from backup;
- bit3 = primary is path-tagged/pinned;
- bit4 = backup is path-tagged/pinned.

`reliabilityQ15 / 32767.0` — нормированная оценка модели, не лабораторно доказанная физическая вероятность доставки.

`ECA Q16` — fixed-point expected channel-airtime cost; панель может показывать raw и decoded `value/65536.0`.

## 7. VANGUARD events

Events 19–25 имеют payload 17 bytes:

`eventType u8, destination u32, nextHop u32, requestId_or_pathTag u32, routeVersion u32`

Типы:
- 19 DiscoveryStarted
- 20 DiscoveryRetry
- 21 RouteReady
- 22 G2Ready
- 23 G2Unavailable
- 24 RoutePromoted
- 25 RouteLost
- 26 ManifestChanged
- 27 KnownNodeAdded

Панель должна хранить timeline событий с timestamp телефона. Для RoutePromoted показывать старый/новый path через немедленный повторный GetRoutingDiagnostics.

## 8. Как панель должна выглядеть

### Screen: Nodes
Для каждого node:
- NodeID;
- known / present / stale;
- age;
- RSSI, SNR;
- HELLO PDR;
- TX ACK PDR;
- manifest slot;
- manifest digest status.

### Screen: Routing
Для destination:
- Primary next hop;
- Primary pathTag;
- generation;
- FD / GuardRank;
- exact flag;
- internal mask;
- reliability/ECA;
- G2 present;
- G2 next hop/pathTag/mask/lease;
- `primaryMask & backupMask` в hex — при exact G2 должно быть `0`.

### Screen: Fault Lab
- выбрать соседний NodeID;
- `Block 5s / 30s / 120s / Manual`;
- `Clear`;
- показать RX/TX drops;
- кнопка `ClearDynamicRoutes`;
- кнопка `ForceFreshDiscovery`.

### Screen: Events
Живая временная шкала: Discovery → RouteReady → G2Ready → Failure → Promotion → Replenishment.

### Screen: Field Test
- target;
- Routed/DirectOnly;
- packet count / interval / payload;
- sent;
- first-hop acked/failed/retries;
- E2E replies/timeouts;
- PDR;
- RTT min/avg/max;
- current next-hop/source.

## 9. Правильный provisioning manifest

1. Через GetInfo собери NodeID всех участвующих устройств.
2. Выбери одну фиксированную последовательность NodeID. Порядок — это NodeSlot.
3. Для первого лабораторного состава используй один epoch, например 1.
4. Отправь абсолютно одинаковый SetManifest на КАЖДЫЙ узел.
5. Считай GetManifest обратно с каждого устройства.
6. Только если epoch+digest совпадают, помечай сеть `EXACT SCOPE READY`.
7. При изменении состава узлов увеличивай epoch и заново провижь весь состав.

Не переставляй существующие NodeSlot внутри того же epoch.

## 10. Обязательные тесты

### T0 — persistence
- дай узлам увидеть друг друга;
- GetKnownNodes;
- SetManifest;
- reboot;
- GetKnownNodes и GetManifest должны совпасть;
- dynamic routes могут быть пустыми и должны строиться заново. Это правильное поведение.

### T1 — 3-node line
Топология `A -> B -> C`, прямой A-C должен быть недоступен/заблокирован.

- A: DiscoverRoute(C).
- ожидай RouteReady;
- GetRoutes: C via B;
- FieldTest A→C должен давать E2E PONG.

### T2 — 4-node diamond / exact G2
Топология:

`A-B-D`

`A-C-D`

Сделай одинаковый manifest. Если A-D напрямую слышен, блокируй A↔D на обоих endpoints либо физически изолируй линк.

- DiscoverRoute A→D;
- дождись RouteReady;
- дождись G2Ready;
- primaryNextHop и backupNextHop должны отличаться;
- `primaryMask & backupMask == 0`;
- оба route должны быть tagged.

### T3 — primary failure
Во время Routed FieldTest:
- InjectLinkFailure на first-hop Primary;
- ожидай RoutePromoted;
- немедленно считай diagnostics;
- promoted route должен совпасть с бывшим G2;
- сеть не должна образовать forwarding loop;
- после promotion должен начаться новый G2 discovery/replenishment.

Запиши blackout каждого failure и считай p50/p95/p99, а не только average.

### T4 — partition
Заблокируй все доступные пути к destination.

Ожидается:
- bounded retries;
- RouteLost/G2Unavailable;
- отсутствие бесконечного RREQ storm;
- control-budget/deferred counters могут увеличиться, но очередь должна оставаться bounded;
- устройство не должно reset/freeze.

### T5 — manifest mismatch
На одном узле задай другой epoch/digest.

Ожидается fail-closed для exact scoped control: такой узел не должен помогать создать ложный `exact G2`.

### T6 — reboot destination
После существующего route reboot destination.

Ожидается новая boot incarnation/generation; stale state не должен восстановиться как будто это всё тот же route epoch.

## 11. Критерии, которые приложение должно считать автоматически

- `loopsObserved == 0`;
- при exact G2: `primaryNextHop != backupNextHop`;
- при exact G2: `(primaryMask & backupMask) == 0`;
- tagged path никогда не перескакивает на generic next-hop без нового route/pathTag;
- manifest digest одинаков у всей тестируемой группы;
- discovery attempts bounded;
- deferred control queue bounded;
- no device reset / no heap collapse;
- после fault измеряются p50/p95/p99 recovery, а не заявляется заранее произвольный SLA.

## 12. Что присылать после теста

Самый полезный экспорт из приложения — JSON/CSV timeline:

`phoneTime, nodeId, eventType, destination, nextHop, pathTag, routeVersion, primaryMask, backupMask, FD, rank, reliabilityQ15, ECAQ16, rssi, snr, helloPdr, ackPdr, controlBudgetDrops, deferredDrops, freeHeap`

С таким логом можно уже не гадать по экрану: сравнить варианты алгоритма, построить CDF recovery, увидеть route churn, control storm и реальные слабые места.

---

# Дополнение v0.8.1 — 5-node Lab Profile

## Ограничение стенда
Текущая лабораторная сборка намеренно ограничена пятью authenticated identities. `NetworkManifest`, replay-state, neighbor table и основной dynamic route table имеют максимум 5 участников. Это не ограничение алгоритма VANGUARD как идеи, а контролируемый профиль для exhaustive/fault тестов.

## Opcode 22 — SetLabLinkPolicy
Payload, 15 bytes:

`peerNodeId u32, flags u8, durationMs u32, reliabilityQ15 u16, ecaQ16 u32`

Flags:
- bit0 (`0x01`) = hard BLOCK RX/TX;
- bit1 (`0x02`) = routing metric override;
- `0` = clear rule.

`durationMs = 0xFFFFFFFF` означает правило до ручной отмены. `durationMs = 0` очищает правило.

Metric override НЕ подменяет измеренные RSSI/SNR/PDR в neighbor history. Он меняет только метрику, которую получает routing engine. Это позволяет на столе оставить прямой RF-линк физически рабочим, но сделать его менее привлекательным для Primary.

Рекомендуемые профили панели:
- Soft weak: reliabilityQ15 ~= 0.72 * 32767, ECA ~= 2.8;
- Very weak: reliabilityQ15 ~= 0.48 * 32767, ECA ~= 3.8.

Для симметричного искусственного линка приложение обязано послать правило на ОБЕ стороны пары.

## Opcode 23 — GetLabLinkPolicies
Payload ответа:

`count u8`, затем records по 15 bytes:

`peerNodeId u32, flags u8, remainingMs u32, reliabilityQ15 u16, ecaQ16 u32`

`remainingMs = 0xFFFFFFFF` — manual rule.

## Главный 3-radio exact-G2 сценарий
При трёх физических узлах A/B/C four-node diamond не обязателен.

1. Все три реальных RF-линка разрешены.
2. A<->C получает только metric override `SOFT_WEAK`.
3. A делает fresh discovery к C.
4. При нормальных A-B и B-C Primary должен предпочесть A-B-C.
5. Прямой A-C остаётся физически рабочим и может стать exact G2, потому что у него нет внутренних узлов, пересекающихся с внутренним B у Primary.
6. Hard BLOCK A<->B проверяет promotion G2 без перемещения устройств.


# Дополнение v0.8.2 — 3-Radio Console Autopilot

Firmware BLE opcodes не изменены относительно v0.8.1. Автотест реализован на стороне панели как оркестратор уже существующих команд. Это намеренно: test UI не получает скрытого backdoor API и проверяет тот же публичный BLE control plane, которым будет пользоваться дальнейшее приложение.

Autopilot PASS требует одновременно:
- подключены A/B/C;
- radio+crypto READY;
- manifest epoch+digest совпадают;
- Primary A→C имеет first-hop B;
- backup A→C имеет first-hop C;
- exact-G2 flag установлен;
- `primaryMask & backupMask == 0`;
- после BLOCK A↔B `promotionsG2` увеличивается и Primary становится C.

Панель экспортирует JSON snapshot с nodes/manifest/neighbors/routes/diagnostics/field test/events и временем promotion.


## 13. Дополнение v0.9.3 — понятное качество связи и BLE-радар

### GetBleRadar (opcode 28)

Пассивный BLE-детектор запускается только после защищённого подключения командирского приложения. Он не подключается к обнаруженным устройствам и не передаёт их исходные BLE-адреса.

Response header (12 bytes):

`version u8, enabled u8, scanning u8, count u8, scanCycle u32, totalDetections u32`

Далее до 10 записей по 30 bytes:

`addressHash u32, ageMs u32, presenceMs u32, rssi i8, peakRssi i8, trend i8, hits u8, flags u8, nameLen u8, name[12]`

`addressHash` — краткий сессионный хеш BLE-адреса. Он нужен только для группировки повторных обнаружений и локального trusted-list в приложении.

`trend` — разница текущей сглаженной силы сигнала и более медленной базовой оценки. Это индикатор тенденции, а не измерение скорости или расстояния.

### Human Link Quality

Обычный UI не показывает RSSI/SNR как главный пользовательский сигнал. Они остаются в диагностике. Для карточек и топологии приложение строит оценку `0..100` из:
- сглаженного RSSI;
- SNR;
- HELLO PDR;
- ACK PDR при достаточном числе попыток;
- свежести данных;
- объёма накопленных наблюдений.

Категории: `ОТЛИЧНО / ХОРОШО / НЕСТАБИЛЬНО / ПЛОХО / НЕТ СВЯЗИ`.

В приложении применяется EWMA и гистерезис между категориями, чтобы статус не мигал около порога.
