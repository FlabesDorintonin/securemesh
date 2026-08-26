# Примечание v1.0.4 Operator

v1.0.4 наследует все границы Foundation v1.0.3. Пользовательский слой дополнительно обязан соблюдать `OPERATOR_UI_RULES_RU.md`.

# SecureMesh v1.0.3 Foundation — архитектурные правила

Этот файл фиксирует границы, которые нельзя случайно размывать при добавлении новых функций. Цель v1.0.3 — стабильная база, где новая функция добавляется локально и проверяемо, а не меняет скрытые контракты всей системы.

## 1. Слои прошивки

1. **Radio / frame transport** — E22/SX1268, RX/TX, airtime, radio recovery.
2. **Crypto / integrity** — AES-256-GCM и проверка защищённых кадров.
3. **Neighbor state** — HELLO, свежесть, RSSI/SNR/PDR/ACK statistics.
4. **VANGUARD routing** — Primary, exact G2, generation, feasibility, loop prevention, promotion/expiration.
5. **Application services** — messages, Field Test, GPS/SOS/commands, BLE Radar.
6. **Operational Intelligence** — health/self-diagnostics; read-only относительно routing/radio policy.
7. **BLE command API** — граница firmware ↔ Commander.

VANGUARD Core/Protocol/Runtime нельзя менять из-за потребности UI. Сначала расширяется snapshot/API, затем UI его читает.

## 2. Два BLE-протокола

- fragment transport: **v1**;
- application packet: **v2**.

Их нельзя снова объединять одной константой. Application header = 10 bytes, max application packet = 384 bytes. Response обязан совпасть с запросом по `requestId + opcode`.

## 3. Wire-contract

Fixed payload — только точная длина:

- Info: 23 B;
- Status: 42 B;
- Operational Health: 17 B;
- Self Diagnostics: 43 B;
- Field Status: 67 B.

Variable payload: `actual_length == header_length + count * record_length`.

Record sizes: Neighbor 29, Route 9, Known Node 4, Manifest 5, Routing Diagnostics route 56, Lab policy 15, BLE Radar record 30.

Новый opcode считается стабильным только после одновременного обновления firmware, Commander decoder, `APP_TEST_PANEL_PROTOCOL.md`, `ble_contract_check.py` и behavioural decoder test.

## 4. Field Test

Источник истины — firmware:

- payload 16–70 B;
- packets 1–500;
- interval 250–60000 ms.

Commander не должен предлагать значение, которое firmware заведомо отвергнет.

## 5. Commander state model

`MeshNode` владеет GATT/session state конкретного узла: characteristics/listeners, command queue, pending requests, reassembly и telemetry snapshots.

Глобальный `state` содержит только cross-node/UI state: pairwise Link Quality, Field Recorder, events, radar history, fault/model state.

Команды одного узла сериализуются через `commandChain`. Disconnect/reconnect очищает pending requests и partial reassembly. Удаление узла из текущей сессии снимает listeners, чтобы повторное добавление не создавало дублирующиеся callbacks.

## 6. Human Link Quality

RSSI не является пользовательским качеством связи. Human Link Quality объединяет RSSI, SNR, HELLO PDR, ACK PDR, freshness и evidence; поверх них применяются smoothing, hysteresis и trend confidence.

UI: `ОТЛИЧНО / ХОРОШО / НЕСТАБИЛЬНО / ПЛОХО / НЕТ СВЯЗИ` + `усиливается / стабильно / ослабевает`.

Raw RSSI/SNR остаются engineering diagnostics. UI-score не имеет права напрямую менять маршрут, SF/BW/CR или частоту.

## 7. Network Readiness

Readiness — операторская оценка, не доказательство работоспособности mesh.

- 0 узлов: 0;
- 1 узел: max 45;
- 2 узла: max 78;
- полная готовность/резервирование требуют 3 узлов и фактического manifest/exact G2 state.

Radio/Crypto failure жёстко ограничивает общий score.

## 8. Polling

- fast telemetry ~5 s;
- extended diagnostics ~15 s;
- BLE Radar только на открытом экране;
- hidden page не создаёт background polling.

Новая тяжёлая telemetry-функция должна быть extended/on-demand.

## 9. Persisted vs transient

Persisted: alias, pin, trusted BLE hashes, event history.

Transient: GATT objects, partial fragments, pending requests, pairwise smoothing history, live radar state.

Удаление карточки из текущей сессии не стирает alias/pin: NodeID остаётся идентичностью устройства.

## 10. Release gate

База: `./tests/run_native_tests.sh`.

Обязательно: VANGUARD core/protocol/runtime/safety, invariant fuzz, airtime, structural, BLE contract, decoder/reassembly behaviour, UI feature/safety/resilience, Signal/Operational Intelligence, three-radio scenario, JS/Python syntax и browser responsive smoke.

После изменений C++ core/runtime — ASan/UBSan native tests.

Native tests не заменяют Arduino/PlatformIO build. Последний gate: реальный ESP32-S3 build + три платы + LoRa/BLE soak test.

## 11. Следующее модульное разделение

v1.0.3 сохраняет совместимый монолит `.ino` и `app.js`, чтобы не делать большой refactor без Arduino toolchain. При следующем крупном блоке функций рекомендуется вынести firmware-модули `BleApi`, `OperationalHealth`, `BleRadar`, `FieldTest`, а в Commander — `ble-transport`, `wire-codec`, `mesh-node`, `link-quality` и render-модули экранов.

Сам refactor должен быть отдельным release без изменения wire behaviour: одинаковые regression tests до и после.
