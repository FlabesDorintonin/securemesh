# SecureMesh v1.0.4 OPERATOR — план стенда с 3 LoRa-модулями

Цель — проверить routing/recovery без необходимости физически разносить E22 на большое расстояние.

## Узлы
- A — источник;
- B — relay;
- C — destination.

Все три устройства могут лежать на одном столе. Реальная RF-доступность не считается заданной топологией: Lab Link Policy может симметрично блокировать или искусственно ухудшать конкретную пару.

## Test 1 — baseline triangle
1. Clear all lab rules.
2. Clear dynamic routes.
3. Убедиться, что A видит B и C, B видит A и C.
4. Field Test A -> C DirectOnly, затем Routed.

Критерий: RF/BLE/crypto regression работает, нет auth/replay storm.

## Test 2 — искусственная цепь A-B-C
1. На A и C задать `A<->C BLOCK`.
2. Clear dynamic routes.
3. Force fresh discovery A -> C.

Ожидание: `Primary A -> B -> C`; прямой A->C не используется ни DATA, ни discovery.

## Test 3 — exact G2 на трёх физических узлах
1. Clear all lab rules.
2. На A и C задать `A<->C SOFT_WEAK`, не BLOCK.
3. Clear dynamic routes.
4. Force fresh discovery A -> C.

Ожидание:
- Primary: A -> B -> C;
- G2: A -> C;
- internal masks disjoint: `(PrimaryMask & BackupMask) == 0`;
- G2 имеет другой first-hop.

Soft-weak влияет только на routing metric. RF-пакеты A<->C по-прежнему разрешены, поэтому этот линк может служить запасным.

## Test 4 — failover без перемещения плат
1. Не останавливая Routed Field Test A -> C, нажать `Убить Primary`.
2. Панель симметрично блокирует текущий A<->PrimaryNextHop на 30 секунд.

Ожидание:
- RoutePromoted;
- Primary меняется на бывший G2;
- E2E replies продолжаются после ограниченного blackout;
- promotionsG2 увеличивается;
- после восстановления линка система может заново пополнить standby.

Измерять: blackout, E2E PDR, RTT p50/avg/max, first-hop retries, controlBudgetDrops.

## Test 5 — persistence/reboot
1. Синхронизировать manifest A/B/C.
2. Дождаться, чтобы Known Registry содержал остальные authenticated identities.
3. Перезагрузить A.

Ожидание после reboot:
- NodeID тот же;
- Known Registry не потерян;
- NetworkEpoch + ManifestDigest не потеряны;
- динамический маршрут НЕ восстанавливается из flash;
- neighbors/evidence подтверждаются заново;
- A способен заново открыть A -> C.

Это намеренное поведение: identity/membership сохраняются, физическая топология маршрутов — нет.
