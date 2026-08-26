# VANGUARD-SM 2.1 — алгоритм SecureMesh v0.8

## Цель

VANGUARD-SM 2.1 оптимизируется не под максимальный размер сети, а под небольшую автономную mesh-сеть (профиль до 32 provisioned identities), где важны:

- отсутствие forwarding loops;
- bounded control traffic;
- быстрый deterministic failover;
- точный node-disjoint standby, когда он реально существует;
- directional link evidence;
- fail-closed поведение при рассинхронизации состояния;
- возможность объяснить каждое routing decision в тестовой панели.

Алгоритм разделён на четыре слоя. Optimizer никогда не может обойти Safety.

## A. Identity / Scope

Каждый узел имеет 32-bit NodeID. Для exact path reasoning сеть использует:

`NetworkScope = (NetworkEpoch, ManifestDigest)`

Manifest задаёт точное соответствие `NodeSlot 0..31 -> NodeID`. Slot стабилен только внутри одного NetworkEpoch.

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

## C. Link evidence

Для каждого direct neighbor отдельно оцениваются:

- RX age;
- RSSI EWMA;
- SNR EWMA;
- HELLO reception PDR;
- TX-hop ACK PDR;
- ECA — expected channel airtime cost;
- reliability estimator.

Неизвестный link получает консервативный prior reliability ~75%, а не идеальные 100%.

Path reliability агрегируется multiplicatively в Q15. ECA суммируется saturating. Эти величины являются routing estimators; они не выдаются за физически доказанную вероятность без калибровки по реальным traces.

## D. Candidate selection

Сравнение кандидатов lexicographic с hysteresis:

1. если reliability отличается больше примерно чем на 0.5 percentage point — выигрывает более надёжный;
2. внутри hysteresis band выигрывает меньший ECA;
3. затем меньшее число hops.

Это уменьшает route flapping из-за малых шумовых изменений метрики.

## E. Discovery

Source без подходящего route запускает RREQ:

- уникальный requestId;
- destination;
- source generation context;
- TTL по умолчанию 8;
- NetworkScope;
- AvoidMask;
- excludedFirstHop;
- accumulated path mask/vector;
- accumulated ECA/reliability.

Каждый relay:

1. проверяет scope;
2. не принимает собственный flooded RREQ обратно как новый discovery;
3. проверяет path vector на повтор slot;
4. проверяет consistency `vector -> mask`;
5. отклоняет себя, если находится в AvoidMask;
6. увеличивает hop count;
7. добавляет свою link metric;
8. сохраняет bounded duplicate/reverse state;
9. forwarding происходит только при новом/улучшенном RREQ.

Destination не отвечает на первый пакет мгновенно. Он держит bounded settle-window и выбирает лучший RREQ, услышанный в окне. Окно не продлевается улучшениями. В firmware длительность окна и discovery timeout вычисляются из фактического LoRa time-on-air текущего radio profile, поэтому алгоритм не предполагает физически невозможный multi-hop RTT.

Для текущего профиля SF9/BW125/CR4/5/preamble12 расчёт даёт примерно `RREQ hop service=691 ms`, `reliable RREP hop service=1158 ms`, `settle=941 ms`, `8-hop attempt timeout=16933 ms`. Максимум 3 attempts. Последняя обычная попытка может request fresh generation; minimum refresh interval не короче полного рассчитанного attempt timeout.

## F. RREP и route construction

Destination создаёт ровно один RREP для выбранного requestId/pathTag.

Для exact mode RREP несёт ordered path vector. Каждый relay проверяет:

- что сам находится именно на ожидаемой позиции;
- что previousHop соответствует ожидаемому downstream;
- что upstream соответствует предыдущему элементу vector/source;
- что suffix mask совпадает с фактическим suffix path.

По мере возврата RREP каждый relay строит route к destination и flow-label для конкретного pathTag.

Source получает полный path metric и устанавливает Primary.

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

## H. Path pinning

DATA hop header содержит `routeTag`.

`routeTag = 0` — generic hop-by-hop forwarding.

`routeTag != 0` — packet обязан следовать установленному flow-label:

`(origin, originBoot, destination, pathTag, expectedPreviousHop) -> nextHop`

Если label отсутствует, истёк или previousHop неправильный, relay не делает тихий fallback на другой маршрут. Он fail-closed и отправляет path-specific RERR upstream.

Это необходимо, чтобы заявленная exact-disjoint chain совпадала с реальной chain DATA packet.

## I. Failure / Recovery ladder

Hard hop failure возникает после исчерпания bounded hop retries либо через Fault Lab.

Действия:

1. invalidate paths/flow labels, зависящие от failed nextHop;
2. отправить path-specific RERR upstream, если packet принадлежит tagged path;
3. если source имеет валидный exact G2 — promote G2 -> Primary немедленно;
4. если G2 нет, использовать допустимый Feasible Alternate;
5. если route потерян — bounded rediscovery;
6. после promotion попытаться восполнить новый G2.

Статистика различает G2 promotion, Alternate promotion, expiration и route errors.

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

## N. Что должно доказываться тестами, а не словами

Нельзя заранее объявлять алгоритм «лучше военных систем» или задавать красивую цифру recovery без стенда. После приложения-лаборатории надо измерять:

- route availability;
- E2E delivery ratio;
- p50/p95/p99 discovery time;
- p50/p95/p99 failover blackout;
- control airtime per useful delivered byte;
- route churn/hour;
- false failovers;
- heap/stack high-water marks;
- reset/watchdog events;
- поведение при correlated burst loss и partition/merge.

Сильный результат — когда эти данные воспроизводимы и показывают преимущество конкретного механизма VANGUARD над baseline, а не когда преимущество просто заявлено.
