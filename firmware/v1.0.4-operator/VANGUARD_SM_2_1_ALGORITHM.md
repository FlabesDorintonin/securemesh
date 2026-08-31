# VANGUARD-SM 2.1 — реализованный профиль SecureMesh v1.0.4

## Статус документа

Этот документ описывает **фактически реализованный профиль v1.0.4**, а не будущую масштабируемую версию VANGUARD.

Текущий firmware profile ограничен `MAX_LAB_NODES = 5` / `VanguardManifest::MAX_SLOTS = 5`. Формат path mask технически допускает до 32 бит, а wire-vector control protocol — до 8 внутренних slots, но это **не означает**, что v1.0.4 квалифицирована для 8/32 узлов. Масштабирование 5 → 10+ остаётся отдельной research-задачей.

Evidence level: core/protocol/runtime и сценарии маршрутизации — **NATIVE TESTED**. Physical Primary/G2/failover qualification на реальных радиоузлах остаётся открытой и не должна называться HARDWARE TESTED до стендового прогона.

## Цель

VANGUARD-SM 2.1 оптимизируется под небольшую автономную mesh-сеть, где важны:

- отсутствие forwarding loops;
- bounded control traffic;
- deterministic failover при подтверждённом hard-hop failure;
- точный node-disjoint standby, когда он реально существует;
- fail-closed поведение при рассинхронизации состояния;
- возможность объяснить каждое routing decision в тестовой панели.

Алгоритм разделяет Safety и Optimizer. Optimizer никогда не может обойти Safety.

## A. Identity / Scope

Каждый узел имеет 32-bit NodeID. Для exact path reasoning сеть использует:

`NetworkScope = (NetworkEpoch, ManifestDigest)`

Manifest задаёт точное соответствие `NodeSlot -> NodeID`. В текущем v1.0.4 доступно до 5 slots. Slot стабилен только внутри одного NetworkEpoch.

Любой control packet, который претендует на exact path/G2, должен принадлежать тому же scope. При mismatch exact control fail-closed.

KnownRegistry и Manifest сохраняются в NVS. Динамический route state не сохраняется.

## B. Safety state

Для destination D:

`Generation(D) = (DestinationBootEpoch, RouteSeq)`

Внутри generation route хранит:

- `GuardRank` — текущий локальный rank primary;
- `FD` — минимальный локальный rank, достигнутый внутри этой generation;
- `version` — версия route state для observability;
- Primary;
- Backup exact G2;
- Feasible Alternate.

Generic candidate от next hop N допустим только если:

`candidate.advertisedGuardRank < local.FD`

Новый локальный rank:

`localRank = advertisedRank + 1`

FD внутри generation может только уменьшаться. Новая destination generation сбрасывает feasibility history.

Старая generation не может заменить новую.

## C. Neighbor evidence и фактический routing metric v1.0.4

Firmware хранит для direct neighbor следующие измерения/состояние:

- RX age / last seen;
- RSSI EWMA;
- SNR EWMA;
- HELLO reception PDR EWMA;
- cumulative TX-hop attempts / ACK successes;
- TX-hop ACK PDR EWMA;
- scope/manifest compatibility metadata.

Важно различать **измеряемые диагностические данные** и то, что реально входит в optimizer текущей версии.

В v1.0.4 функция `estimateNeighborLinkMetric()` строит routing `LinkMetric` из TX-hop ACK evidence:

1. при отсутствии TX evidence используется prior `perAttempt ≈ 0.75`;
2. при наличии попыток берётся односторонняя нижняя граница Wilson (`z ≈ 1.28`) для `txAckSuccesses / txAttempts`;
3. с учётом `MAX_DATA_ATTEMPTS = 4` рассчитывается transaction reliability `1 - (1-p)^4`;
4. ECA в текущем fixed-radio profile выражает ожидаемое число hop attempts;
5. path reliability агрегируется multiplicatively в Q15, ECA суммируется saturating.

RX freshness используется отдельным admission gate: выбранный next hop должен оставаться fresh (`NEIGHBOR_STALE_MS = 22000`).

**RSSI, SNR и HELLO PDR в текущей v1.0.4 измеряются и доступны для диагностики, но не входят непосредственно в comparator маршрутов.** Их нельзя описывать в ПЗ как уже действующие веса выбора маршрута.

Текущие reliability/ECA — routing estimators, а не физически откалиброванная вероятность доставки. Их необходимо калибровать по реальным traces.

## D. Candidate selection

Сначала кандидат обязан пройти safety/admissibility условия: scope/generation, loop checks и feasibility. Только после этого качество допустимых кандидатов сравнивается lexicographic с hysteresis:

1. если reliability отличается больше примерно чем на 0.5 percentage point — выигрывает более надёжный;
2. внутри hysteresis band выигрывает меньший ECA;
3. затем меньшее число hops.

Это уменьшает route flapping из-за малых шумовых изменений метрики и не вводит непрозрачный weighted score.

## E. Discovery

Source без подходящего route запускает RREQ:

- уникальный requestId;
- destination;
- source generation context;
- `NetworkScope`;
- `AvoidMask`;
- `excludedFirstHop`;
- accumulated path mask/vector;
- accumulated ECA/reliability.

**Текущий v1.0.4 discovery hop limit = 4.** Это соответствует максимальному простому пути для текущего 5-node profile. `VanguardProto::MAX_PATH_SLOTS = 8` является ёмкостью wire vector, а не текущим routing TTL.

Каждый relay:

1. проверяет scope;
2. не принимает собственный flooded RREQ обратно как новый discovery;
3. проверяет path vector на повтор slot;
4. проверяет consistency `vector -> mask`;
5. отклоняет себя, если находится в AvoidMask;
6. увеличивает hop count;
7. добавляет link metric;
8. сохраняет bounded duplicate/reverse state;
9. forwarding происходит только при новом/улучшенном RREQ.

Destination не отвечает на первый пакет мгновенно. Он держит bounded settle-window и выбирает лучший RREQ, услышанный в окне. Окно не продлевается улучшениями.

В firmware settle/discovery timeout вычисляются из фактического LoRa time-on-air текущего radio profile. Для SF9/BW125/CR4/5/preamble12 расчёт даёт примерно:

- `RREQ hop service = 691 ms`;
- `reliable RREP hop service = 1158 ms`;
- `settle = 941 ms`;
- **`4-hop base attempt timeout = 9537 ms`**.

Максимум 3 attempts. Retry deadline дополнительно увеличивается на bounded step. Последняя обычная попытка может request fresh generation; minimum refresh interval не короче рассчитанного base attempt timeout.

Старое значение `16933 ms` относится к расчёту при 8-hop limit и не является текущим v1.0.4 timing.

## F. RREP и route construction

Destination создаёт ровно один RREP для выбранного requestId/pathTag.

Для exact mode RREP несёт ordered path vector. Каждый relay проверяет:

- что сам находится именно на ожидаемой позиции;
- что previousHop соответствует ожидаемому downstream;
- что upstream соответствует предыдущему элементу vector/source;
- что suffix mask совпадает с фактическим suffix path.

По мере возврата RREP каждый relay строит route к destination и flow-label для конкретного pathTag.

Source получает полный returned-path metric и устанавливает Primary.

## G. Exact G2

После Primary source автоматически запускает второй discovery:

- `AvoidMask = PrimaryInternalMask`;
- `excludedFirstHop = PrimaryNextHop`.

Если найден второй exact path, он может стать G2 только если:

`PrimaryNextHop != BackupNextHop`

и

`PrimaryInternalMask & BackupInternalMask == 0`

Оба exact path должны принадлежать одному NetworkScope.

### Почему G2 может быть длиннее Primary

Обычная feasibility condition защищает свободный hop-by-hop routing graph. Exact G2 — source-private path-pinned standby: DATA идёт по проверенному pathTag/flow-label, а relay не имеет права заменить next hop generic route. Поэтому более длинный exact G2 можно хранить как standby даже если он не является generic feasible successor. Он не экспортируется как свободный generic route.

Exact node-disjointness означает топологическое различие внутренних узлов, но **не доказывает независимость RF failure domains**: одинаковая помеха, питание, частота или физическая зона могут одновременно ухудшить оба пути.

## H. Path pinning

DATA hop header содержит `routeTag`.

`routeTag = 0` — generic hop-by-hop forwarding.

`routeTag != 0` — packet обязан следовать установленному flow-label:

`(origin, originBoot, destination, pathTag, expectedPreviousHop) -> nextHop`

Если label отсутствует, истёк или previousHop неправильный, relay не делает тихий fallback на другой маршрут. Он fail-closed и отправляет path-specific RERR upstream.

Это необходимо, чтобы заявленная exact-disjoint chain совпадала с реальной chain DATA packet.

## I. Failure / Recovery ladder

Подтверждённый hard hop failure возникает после исчерпания bounded hop retries (`MAX_DATA_ATTEMPTS = 4`) либо через Fault Lab.

Действия:

1. invalidate paths/flow labels, зависящие от failed nextHop;
2. отправить path-specific RERR upstream, если packet принадлежит tagged path;
3. если source имеет валидный exact G2 — promote G2 -> Primary;
4. если G2 нет, использовать допустимый Feasible Alternate;
5. если route потерян — bounded rediscovery;
6. после promotion попытаться восполнить новый G2.

Статистика различает G2 promotion, Alternate promotion, expiration и route errors.

### Известная integration gap v1.0.4: passive stale primary

`resolveVanguardNextHop()` отдельно требует `isFreshDirectNeighbor(nextHop)`. Если primary next hop просто стал stale по RX age, resolver возвращает `false`. В текущей интеграции этот passive stale transition **не вызывает автоматически `onRouteFailure()`/G2 promotion**; верхний уровень может начать новое discovery.

Поэтому deterministic immediate G2 promotion сейчас доказан native-сценариями для explicit/hard failure path, но **не должен заявляться для любого passive disappearance соседа** до отдельного исправления и теста.

## J. Store-and-forward

Relay не помечает logical message окончательно forwarded в момент admission в TX queue.

Lifecycle:

`received -> in-flight/pending -> downstream hop ACK -> commit logical replay`

Если route временно исчез, immutable message помещается в bounded pending-relay buffer и запускает discovery.

Если packet pinned и его flow-label потерян, он не перенаправляется generic route: генерируется RERR.

## K. Control storm containment

Control traffic использует token bucket в реальном оценённом LoRa airtime.

Профиль текущей лабораторной сборки:

- capacity: 1.5 s airtime;
- protected repair reserve: 350 ms;
- refill: ~150 us airtime per 1 ms wall time (~15% long-term local control allowance).

Обычный discovery не может расходовать reserve. Repair/RERR может.

Если control packet временно не помещается из-за TX pressure/budget, он попадает в bounded deferred-control queue:

- capacity 8;
- duplicate coalescing;
- bounded retry backoff;
- max age 12 s;
- max 12 attempts.

## L. Persistence semantics

### Persist
- local BootCounter;
- KnownRegistry;
- NetworkManifest.

### Do not persist
- neighbor freshness;
- RSSI/SNR/PDR evidence;
- dynamic routes;
- flow labels;
- discovery/reverse caches;
- Fault Lab overlay.

Причина: эти данные описывают текущую физическую топологию и после reboot могут стать ложными.

## M. Инварианты

Реализация и тесты должны постоянно защищать следующие инварианты:

1. stale generation не заменяет newer generation;
2. generic FD не увеличивается внутри одной generation;
3. local node не появляется повторно в exact path;
4. exact path vector не содержит duplicate slots;
5. exact vector-derived mask равен transmitted mask;
6. exact G2 masks не пересекаются;
7. exact G2 first hop отличается от Primary;
8. pinned DATA не меняет path без нового pathTag;
9. replay/known identity state не вытесняется LRU;
10. discovery/control queues bounded;
11. failed persistence commit не считается успешным persistent state;
12. dynamic route after reboot должен быть rediscovered, а не восстановлен слепо.

## N. Известные ограничения optimizer v1.0.4

### 1. Cumulative ACK evidence медленно забывает старую среду

`txAttempts` и `txAckSuccesses` накопительные. После большого числа наблюдений резкая смена помех/положения может медленно менять Wilson estimator. `txAckPdrEwma` существует, но текущий routing metric его напрямую не использует.

Перед расширением optimizer нужен recent/decaying routing-evidence estimator, при этом cumulative counters можно сохранить как диагностику.

### 2. Unknown prior и малая выборка требуют калибровки

При нуле TX samples используется prior `p ≈ 0.75`, а после появления нескольких samples применяется Wilson lower bound. Это даёт преднамеренно осторожное отношение к малой выборке, но переход между prior и evidence должен быть проверен на hardware traces, чтобы неизвестная связь не получала необоснованное преимущество перед связью с небольшим числом успешных измерений.

### 3. Directionality RREQ settle требует отдельной проверки

Incoming RREQ обогащается через `estimateNeighborLinkMetric(previousHop)`, а текущий estimator основан на локальной TX→previousHop hop-ACK истории. Для асимметричного радио-линка это не обязательно равно предыдущему hop previousHop→local, по которому RREQ фактически пришёл.

Поэтому destination settle preselection не следует пока описывать как полностью directional-correct в асимметричной среде. Нужен отдельный design/test: либо RX-direction evidence для RREQ, либо иной механизм, не смешивающий направления.

### 4. RSSI/SNR нельзя просто добавить произвольными весами

RSSI/SNR/HELLO PDR уже измеряются, но добавлять их в optimizer через «магическую» взвешенную сумму без field calibration нельзя. Сначала нужны raw traces, затем проверяемая модель и regression tests.

## O. Что должно доказываться тестами, а не словами

Нельзя заранее объявлять алгоритм «лучше военных систем» или задавать красивую цифру recovery без стенда. Необходимо измерять:

- route availability;
- E2E delivery ratio;
- p50/p95/p99 discovery time;
- p50/p95/p99 failover blackout;
- control airtime per useful delivered byte;
- route churn/hour;
- false failovers;
- heap/stack high-water marks;
- reset/watchdog events;
- поведение при asymmetric links;
- correlated burst loss;
- partition/merge;
- passive stale primary при уже готовом exact G2;
- destination reboot/generation change;
- G2 replenishment после promotion.

Сильный результат — когда эти данные воспроизводимы и показывают преимущество конкретного механизма VANGUARD над baseline, а не когда преимущество просто заявлено.
