# ChatFlow Project Context — CS6650 Distributed Systems

## What this project is

ChatFlow is a distributed real-time chat system built across three assignments:
- A1: WebSocket server + multithreaded load test client (echo server)
- A2: Added message queuing (AWS SQS), a Consumer service, and AWS ALB load balancing
- A3: Database persistence, analytics Metrics API, write-behind pipeline, load testing ✓ COMPLETE

All code is Java/Spring Boot. Infrastructure runs on AWS EC2, us-west-2.

---

## Current Architecture (A3)

```
[Load Test Client]           local machine, 120 sender threads + 40 warmup threads
        |
   AWS ALB (port 80)         sticky session (LB cookie), routes WS connections
      /        \
[EC2 A]       [EC2 B]        each t3.micro, 2 vCPU, 1GB RAM
Server-v3      Server-v3     Spring Boot, port 8080
:8080          :8080         WebSocket + SQS publish + /metrics endpoint
      \        /
    [SQS x20 FIFO queues]    chatflow-room-01.fifo ... chatflow-room-20.fifo
                             us-west-2, account 449126751631
           |
      [EC2 C]                dedicated consumer EC2, t3.micro, separate from EC2-A/B
      Consumer-v3            Spring Boot, port 8081
      :8081                  20 SQS polling threads (one per room)
           |
    ┌──────┴──────────────────────────┐
    │                                 │
parallel HTTP broadcast        LinkedBlockingQueue (cap 100K)
to EC2-A:8080 and EC2-B:8080         │
(CompletableFuture)           DbWriterService (5 writer threads)
                               drainTo(batch, 500) → multi-row INSERT
                                         │
                               PostgreSQL RDS (db.t3.micro)
                               chatflow DB, us-west-2
                               ~1,500,000+ rows after load tests
```

---

## Component Breakdown

### client-v3 (Load Test Client)
- Extends client-v2; adds Metrics API call at end of test
- `TOTAL_MESSAGES` configurable via `-Dapp.total-messages=N` (default 1,800,000)
- After test completes: calls `GET /metrics?startTime=...&endTime=...` on server
- `MetricsApiClient`: parses JSON response, prints formatted human-readable report:
  - Test window (UTC timestamps)
  - Q1–Q4 core query results (single line each)
  - Messages/minute table (bucket count, peak)
  - Top rooms table (Rank, Room, Messages)
  - Top users table (Rank, UserID, Username, Messages)
- Warmup: 40 threads × 1,000 msgs; Main: 120 threads × remaining messages

### server-v3 (Spring Boot WebSocket + SQS Producer + Metrics API)
Extends server-v2. Adds:
- `MessageQueryService`: 8 DB queries via NamedParameterJdbcTemplate
  - Q1: messages in room+time range (LIMIT 1000)
  - Q2: user message history in time range (LIMIT 1000)
  - Q3: COUNT(DISTINCT user_id) in time range
  - Q4: rooms participated in by a user (GROUP BY room_id)
  - A1: messages per minute bucketed (sent_at / 60000) * 60000
  - A2: top N active users (GROUP BY user_id ORDER BY count DESC)
  - A3: top N active rooms (GROUP BY room_id ORDER BY count DESC)
  - A4: total message count (SELECT COUNT(*))
- `MetricsController`: GET /metrics — fires all 8 queries in parallel via 8-thread QUERY_POOL
  - Params: roomId(default 1), userId(default 1), startTime(default now-1hr), endTime(default now), topN(default 10)
  - Returns 503 on DataAccessException
- HikariCP pool: max 5 (read-only workload, small pool sufficient)

### consumer-v3 (Spring Boot SQS Consumer + DB Write Pipeline)
Extends consumer-v2. Per-message processing order:
1. `broadcastClient.broadcast()` — parallel HTTP to all servers (unchanged from v2)
2. `dbWriterService.enqueue()` — O(1) put to LinkedBlockingQueue
3. `statsAggregator.record()` — O(1) in-memory counter update
4. `sqsClient.deleteMessage()` — after broadcast success

Key new classes:
- `MessageRepository`: builds multi-row VALUES INSERT string dynamically
  - `INSERT INTO messages (...) VALUES (?,?,...),(?,?,...)... ON CONFLICT (message_id) DO NOTHING`
  - Tracks: totalInserted (AtomicLong), totalBatches, totalInsertLatencyMs
- `DbWriterService`: 5 writer threads, each runs WriterTask loop:
  - `queue.poll(flushIntervalMs=500, MILLISECONDS)` — wait for first message
  - `queue.drainTo(batch, batchSize-1)` — grab up to 499 more (non-blocking)
  - `repository.batchInsert(batch)` — execute single multi-row INSERT
  - Linear backoff retry: up to 3 attempts, 1s base backoff
- `StatsAggregatorService`: ConcurrentHashMap counters for room/user message counts,
  ConcurrentLinkedDeque sliding timestamp window for msg/sec (1s/10s/60s windows),
  1 ScheduledExecutorService thread pruning stale timestamps every 1s
- `QueueMessage`: all-String POJO; roomId zero-padded ("01"–"20"), userId numeric string,
  timestamp ISO-8601 string; parsed at INSERT time

`/health` endpoint exposes:
- `sqs.consumed`, `sqs.broadcasts`
- `db.enqueued`, `db.inserted`, `db.failed`, `db.queueDepth`, `db.batches`, `db.avgBatchSize`, `db.avgInsertMs`
- `stats.totalMessages`, `stats.msgPerSec1s/10s/60s`, `stats.topRooms`, `stats.topUsers`

Configuration (`application.properties`):
```
app.db.batch-size=500
app.db.flush-interval-ms=500
app.db.writer-threads=5
app.db.queue-capacity=100000
spring.datasource.hikari.maximum-pool-size=10
```

### database/
- `schema.sql`: messages table — BIGSERIAL PK, message_id VARCHAR(36) UNIQUE, room_id SMALLINT,
  user_id INT, username VARCHAR(20), message TEXT, message_type VARCHAR(8),
  server_id VARCHAR(50), client_ip VARCHAR(45), sent_at BIGINT (epoch ms), created_at TIMESTAMPTZ
- `indexes.sql`:
  - `idx_room_time ON (room_id, sent_at)` — serves Q1
  - `idx_user_time ON (user_id, sent_at)` — serves Q2
  - `idx_sent_at_uid ON (sent_at, user_id)` — serves Q3 (index-only scan)
  - `idx_user_room_time ON (user_id, room_id, sent_at)` — serves Q4 (index-only scan)

### AWS Infrastructure
- 20 SQS FIFO queues: MessageGroupId=roomId, MessageDeduplicationId=UUID, visibility timeout 120s
- ALB: internet-facing, sticky session 1 day, idle timeout 300s, health check /health
- RDS: db.t3.micro, PostgreSQL 17, 20GB gp2, private VPC (no public access)
- IAM Role on all EC2s: sqs:*, RDS accessed via credentials (env vars: RDS_HOST, RDS_USER, RDS_PASS)
- EC2 SG → RDS SG: inbound TCP 5432 only from EC2 security group

---

## Key Engineering Decisions (A3)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Database | PostgreSQL on RDS db.t3.micro | Native SQL for range queries, ON CONFLICT DO NOTHING, free tier |
| Write pattern | Write-behind (async queue) | DB writes never block broadcast path |
| Batch INSERT | Multi-row VALUES (single SQL statement) | 3–5× faster than JDBC batchUpdate(); single WAL write |
| Idempotency | ON CONFLICT (message_id) DO NOTHING | SQS at-least-once delivery — safe to re-insert duplicate UUIDs |
| put() vs offer() | put() (blocking) | Semantic correctness; in practice queue never fills (DB faster than SQS) |
| Metrics location | server-v3 (not consumer) | Server owns data reads; consumer owns data writes |
| Parallel queries | 8-thread CompletableFuture pool | All 8 queries fire simultaneously; response time = slowest query |

---

## Key Engineering Problems Solved (A2)

1. **Concurrent WebSocket writes** — two threads writing same session simultaneously → ConcurrentWebSocketSessionDecorator
2. **SQS latency blocking client ack** — sync SQS publish added 10-30ms → async publish, ack sent immediately
3. **Tomcat thread pool saturation** — 512 WS sessions exceeded 200-thread default → raised to 500
4. **Unbounded broadcastExecutor queue** — memory exhaustion after ~7 min → bounded ArrayBlockingQueue(500) + DiscardPolicy
5. **Session buffer overflow** — TERMINATE strategy closed sessions → changed to DROP strategy
6. **Serial broadcast** — 2nd server doubled Consumer time → CompletableFuture.sendAsync() (parallel)
7. **Per-room ordering** — shared executor scrambled order → 20 per-room single-thread executors
8. **Silent SQS failures** — messages lost on error → Resilience4j CB + in-memory DLQ with retry

---

## Performance Results

### A2 Baseline
| Config | Messages | Main Phase | Overall | Failures |
|--------|----------|------------|---------|----------|
| Single EC2 | 200,000 | 2,190 msg/s | 1,653 msg/s | 0 |
| 2 EC2s via ALB | 500,000 | 5,100 msg/s | 4,419 msg/s | 0 |

### A3 Load Tests (with DB persistence)
| Test | Messages | Duration | Avg Rate | db.inserted | db.failed | db.avgBatchSize | db.avgInsertMs |
|------|----------|----------|----------|-------------|-----------|-----------------|----------------|
| Test 1 Baseline | 500,000 | ~8m 15s | ~1,008 msg/s | 500,000 | 0 | 1.2 | 3.2 ms |
| Test 2 Stress | 1,000,000 | ~17m 02s | ~978 msg/s | 1,000,000 | 0 | 1.4 | 3.9 ms |
| Test 3 Endurance | 1,800,000 | ~30 min | ~1,000 msg/s | (in progress) | — | — | — |

### Batch Tuning (50K messages each, flush-interval-ms=500)
| Experiment | batch-size | db.avgBatchSize | db.avgInsertMs |
|------------|-----------|-----------------|----------------|
| E1 | 100 | 1.1 | 2.9 ms |
| E2 | 500 | 1.1 | 3.2 ms |
| E3 | 1000 | 1.2 | 3.5 ms |
| E4 | 5000 | 1.2 | 3.4 ms |

**Finding**: configured batch-size has no effect — real avgBatchSize is always ~1.1–1.4 because SQS consumption rate (~1,000 msg/s) is the bottleneck, and DB writers drain the queue faster than it fills. Multi-row batching optimization would only activate at sustained ingestion rates > ~5,000 msg/s.

---

## Known Limitations / Shortcomings

### 1. Low Consumer Throughput (~1,000 msg/s) — Queue Depth Spikes
**Symptom**: During Test 2 and Test 3, `db.queueDepth` periodically spikes to 1,000–2,000+ briefly before resolving.
**Root cause**: SQS FIFO queue constraints limit throughput. Each of the 20 consumer threads long-polls its queue (max 10 messages per call). With FIFO per-group ordering, throughput is bounded by SQS API rate per queue. At ~1,000 msg/s overall, the system is near its natural ceiling without adding more consumer EC2s or sharding queues.
**Impact**: Messages are not lost (queue drains quickly), but real-time delivery has brief bursts of latency.
**Production fix**: Horizontal scaling of Consumer EC2s, or switching from FIFO to Standard queues (higher throughput at the cost of ordering guarantees).

### 2. Consumer is Single Point of Failure (No Redundancy)
**Symptom**: The ALB routes WebSocket connections to EC2-A and EC2-B (both running server-v3). Consumer-v3 runs on a dedicated EC2-C. If EC2-C crashes, all SQS consumption and DB writes stop — there is no standby consumer.
**Impact**: No consumer redundancy. SQS messages accumulate until EC2-C is manually restarted.
**Production fix**: Auto Scaling Group on EC2-C (min 1, max 3). Competing consumer model: multiple consumer instances each polling a subset of SQS queues. Alternatively, use AWS Lambda or ECS for stateless consumer scaling.

### 3. Batch INSERT Efficiency Never Realized
**Symptom**: avgBatchSize is consistently 1.1–1.4 regardless of configured batch-size (100–5000).
**Root cause**: SQS consumer throughput (~1,000 msg/s ÷ 5 writer threads = ~200 msg/thread/s) is too low to fill batches before the 500ms flush timer fires.
**Impact**: Each DB write is effectively a single-row INSERT wrapped in a multi-row VALUES statement. The optimization code is correct but idle.
**Production fix**: Batch writes would activate at higher ingestion rates (e.g., 10,000+ msg/s with Standard SQS queues + more consumer threads).

### 4. sent_at is Client Timestamp (Not Server-Assigned)
**Symptom**: Q1/Q2/Q3 time-range queries using `testStartTime`/`testEndTime` from the client sometimes return 0 results if client clock differs from server clock.
**Root cause**: `sent_at` is populated from the message payload's ISO-8601 timestamp (set by the client), not from `created_at` (set by RDS at INSERT time). Small clock skew or test timing can cause mismatches.
**Production fix**: Use `created_at` (server-side) for range queries, or enforce NTP sync across all EC2s.

---

## A4 Analysis

### A4 Requirements Breakdown

A4 is a group assignment (4 people). One member's A3 is selected as the base, then the group implements 2 material optimizations and measures the impact with JMeter. Total 35 points, delivered as a single PDF (max 8 pages).

**Task 1 — Architecture Selection (5 pts)**
Review all group members' A3 implementations, select the best-performing one as base, and document the selection criteria (performance, scalability, maintainability).

**Task 2 — 2 Material Optimizations (20 pts, 7.5 pts each + 5 pts rationale)**
Choose from: indexing, connection pooling, query optimization, materialized views, caching layer, load balancing, horizontal scaling, queue partitioning. Each optimization requires: what was changed and why, implementation details, and measurable before/after performance data.

**Task 3 — JMeter Performance Measurement (10 pts)**
Two required test scenarios:
- Baseline: 1,000 concurrent users, 100K API calls, 5 minutes, 70% reads / 30% writes
- Stress: 500 concurrent users, 200K–500K API calls, 30 minutes, mixed read/write
Required metrics: avg response time, p95/p99 latency, throughput (req/s), error rate, CPU/memory/DB connection utilization.

---

### Gap Analysis: Current Progress vs. A4 Requirements

| A4 Requirement | Status | Notes |
|---|---|---|
| Stable A3 base with real perf data | ✅ Done | ~1,008 msg/s, 0 failures, full test results in PROJECT_CONTEXT |
| JMeter installed and configured | ❌ Not started | WebSocket plugin (Peter Doornbosch) needs installation; no .jmx test plans yet |
| JMeter Baseline test (1K users, 100K calls) | ❌ Not started | Need to build .jmx for WebSocket + /metrics endpoint |
| JMeter Stress test (500 users, 200K–500K calls) | ❌ Not started | Same |
| Optimization 1 implemented + measured | ❌ Not started | Candidate: parallel per-room message processing in pollLoop |
| Optimization 2 implemented + measured | ❌ Not started | Candidate: application-level caching for /metrics analytics queries |
| PDF report (max 8 pages) | ❌ Not started | Needs arch rationale, 2× optimization sections, future opts, JMeter results |

**Biggest gap: JMeter.** Everything else (code, infra, baseline data) is ready. The entire A4 testing pipeline needs to be built from scratch around JMeter, including WebSocket plugin setup and test plan authoring.

---

### Selecting ChatFlow (This Repo) as A4 Base

#### Pros

**Complete, production-ready codebase.** server-v3, consumer-v3, client-v3, schema + 4 indexes, monitoring scripts, and load test results are all in place. Other group members may not have this level of completeness, which reduces setup risk for A4.

**Real baseline data already exists.** A3 test results (~1,008 msg/s, avgInsertMs 3.2ms, 0 failures across 500K and 1M message tests) can be used directly as the A4 "before" numbers. No need to re-establish a baseline from scratch.

**Known Limitations are well-documented and optimization-ready.** Each limitation in the section above maps to a concrete, implementable optimization with a clear expected impact — the hardest part of A4 (deciding *what* to optimize) is already done.

**Clean modular architecture makes optimization low-risk.** Write-behind pipeline, stats aggregator, and metrics API are independent modules. Modifying one does not break others, reducing regression risk during A4 implementation.

**Parallel metrics query pattern already proven.** The 8-query CompletableFuture pattern in MetricsController demonstrates the team understands parallel execution — this transfers directly to the consumer-side parallel optimization.

#### Cons

**Consumer throughput ceiling is structurally low (~1,000 msg/s).** The SQS FIFO per-group ordering constraint limits each room's poll thread to sequential processing. JMeter stress tests (200K–500K calls, 30 min) will expose this ceiling clearly. It is difficult to dramatically improve this number without architectural changes.

**Batch INSERT optimization is effectively idle.** avgBatchSize is always 1.1–1.4 regardless of configured batch-size (100–5000), because SQS consumption rate is the bottleneck, not DB write rate. Choosing DB batch size as an A4 optimization would produce no measurable before/after difference — a poor choice for a graded deliverable.

**t3.micro hardware limits headroom.** 2 vCPU, 1GB RAM on each EC2. JMeter's 1,000 concurrent users scenario may saturate the instance before the optimization's effect is visible, making it harder to isolate the optimization's contribution.

**sent_at is client-side timestamp.** Core queries Q1–Q3 use `sent_at BETWEEN :start AND :end`. If JMeter generates load from a machine with clock skew, range queries may return 0 results, making the metrics endpoint appear broken during A4 demos.

#### Recommended Optimizations if Selected as Base

**Optimization 1 — Parallel per-room message processing (Consumer)**
Change the `for (Message msg : messages)` sequential loop in `pollLoop` to `CompletableFuture.runAsync()` per message with a dedicated thread pool. This removes the broadcast-latency bottleneck (~100ms × 10 messages = 1s per poll batch) and should raise consumer throughput from ~1,000 msg/s to ~3,000–5,000 msg/s. Before/after comparison is clean and easy to measure via `sqs.consumed` rate in `/health`.

**Optimization 2 — Application-level caching for /metrics analytics queries**
Add an in-memory cache (Caffeine or ConcurrentHashMap + TTL) for the three all-table analytics queries (A2 topActiveUsers, A3 topActiveRooms, A4 totalMessages). These are full-table scans with no time filter, expensive under concurrent JMeter load. A 30-second TTL cache reduces DB query load and dramatically improves p99 response time for `/metrics`. Before/after is directly measurable with JMeter's Aggregate Report on the `/metrics` endpoint.

---

---

## A4 Analysis

### A4 Requirements Summary

A4 is a group assignment (groups of 4). The deliverable is a single PDF report (max 8 pages, 35 points total).

**Task 1 — Architecture Selection (5 pts)**
Review all group members' A3 implementations, select the best-performing one as the group base, and document selection criteria (performance, scalability, maintainability).

**Task 2 — 2 Material Optimizations (20 pts)**
Choose and implement 2 significant optimizations from:
- Database: indexing, connection pooling, query optimization, materialized views
- Caching: application-level cache, cache invalidation
- Load Balancing & Scaling: load balancer, horizontal scaling
- Message Queue: queue partitioning / throughput improvements

Each optimization must document: what was changed and why (tradeoffs), implementation details, and measurable performance impact.

**Task 3 — JMeter Performance Measurement (10 pts)**
- Baseline Performance Test: 1,000 concurrent users, 100K API calls, 5 min, 70% reads / 30% writes
- Stress Test: 500 concurrent users, 200K–500K API calls, 30 min, mixed read/write
- Required metrics per test: avg response time, p95/p99 latency, throughput (req/s), error rate, CPU/memory/DB connections
- WebSocket testing requires Peter Doornbosch JMeter plugin
- Before/after comparison tables showing improvement percentages

**Future Optimizations section (5 pts)**
3–5 additional ideas with expected impact and implementation complexity.

---

### Current Progress vs A4 Requirements

| A4 Requirement | Status | Gap |
|---|---|---|
| Working A3 base with real perf data | ✅ Complete | None — 3 load tests done, full metrics recorded |
| DB schema + indexes | ✅ Complete | None |
| Metrics API (/metrics endpoint) | ✅ Complete | None — all 8 queries working, parallel execution |
| Write-behind pipeline | ✅ Complete | None |
| Monitoring scripts | ✅ Complete | None |
| JMeter test plans (.jmx) | ❌ Not started | Need to build from scratch; WebSocket plugin required |
| Optimization 1 implemented | ❌ Not started | Candidate: parallel per-room message processing in consumer |
| Optimization 2 implemented | ❌ Not started | Candidate: application-level caching for /metrics analytics queries |
| Before/after performance data | ❌ Not started | Depends on JMeter + optimizations being done first |
| PDF report | ❌ Not started | Depends on all above |

**Biggest gap**: JMeter. No `.jmx` test plans exist. This is also the most time-consuming piece because WebSocket JMeter testing requires plugin setup and careful test plan design to simulate realistic load (open connection → send N messages → close).

**Second gap**: Neither optimization has been implemented yet. The candidates are well-identified (see below) but need coding + measurement.

---

### Using ChatFlow as A4 Base: Pros and Cons

#### Pros

**Most complete implementation in likely the entire group.**
server-v3, consumer-v3, client-v3, database schema + indexes, monitoring scripts, and 3 completed load tests are all present. Many A3 submissions will be missing the full analytics pipeline, DB monitoring, or load test results.

**Real baseline performance data already exists.**
~1,008 msg/s throughput, avgInsertMs 3.2ms, 0 failures across 500K and 1M message tests. A4's "before" data is ready to use immediately without re-running tests.

**Known Limitations are already documented**, which maps directly to A4's optimization targets. Each limitation is a concrete, measurable problem with a known fix — ideal for the "what was optimized and why" section of the report.

**Clean modular architecture.**
Write-behind pipeline, stats aggregator, and metrics API are fully decoupled. Adding a caching layer to MetricsController or parallelizing processMessage in SqsConsumerService are self-contained changes that won't break existing functionality.

**Parallel /metrics already works.**
The 8-query parallel execution (CompletableFuture pool) means the server is already well-optimized on the read side, giving a strong foundation for further caching improvements.

#### Cons

**Consumer throughput ceiling is hard to break without architecture changes.**
~1,000 msg/s is the natural ceiling given SQS FIFO per-group ordering constraints and synchronous broadcast in the poll loop. This will be visible in JMeter stress tests and may not look impressive compared to Standard-queue implementations.

**Batch INSERT optimization is effectively idle.**
avgBatchSize of 1.1–1.4 (regardless of configured batch-size 100–5000) means the multi-row VALUES optimization never activates at A3-level load. If a group member chose this as their A4 Optimization 1, it would be hard to demonstrate a before/after improvement.

**t3.micro hardware ceiling.**
2 vCPU, 1GB RAM. JMeter at 1,000 concurrent users will saturate these instances quickly. Performance numbers will look lower than implementations on larger instances or with multiple consumer EC2s.

**Consumer is a single point of failure.**
consumer-v3 runs only on EC2-A. No redundancy. In a 30-minute JMeter stress test, a consumer crash means all DB writes stop until manual restart — a risk for the endurance test.

---

### Recommended A4 Optimizations (if ChatFlow is selected as base)

**Optimization 1 — Parallel per-room message processing in consumer**
- What: Change `pollLoop`'s sequential `for` loop over 10 SQS messages to parallel `CompletableFuture` dispatch using a per-room thread pool
- Why: Each broadcast call blocks ~100ms; 10 serial calls = 1,000ms per poll batch; parallel = ~100ms per poll batch → ~10× throughput increase
- Tradeoff: Sacrifices strict per-room message ordering within a single poll batch (acceptable given relaxed ordering semantics already in place)
- Expected before/after: ~1,000 msg/s → ~3,000–5,000 msg/s (limited by broadcast latency and SQS API rate)
- JMeter signal: higher throughput in Stress Test, lower SQS queue depth

**Optimization 2 — Application-level caching for /metrics analytics queries**
- What: Cache results of A2 (topActiveUsers), A3 (topActiveRooms), A4 (totalMessages) in ConcurrentHashMap with TTL (e.g., 30s); serve from cache on subsequent calls within TTL window
- Why: These are full-table GROUP BY / COUNT queries that scan the entire messages table (500K–1.8M rows). Under concurrent JMeter load hitting /metrics repeatedly, they will bottleneck on RDS CPU
- Tradeoff: Stale data up to TTL duration; acceptable for analytics (not real-time transactional data)
- Expected before/after: /metrics p99 response time drops from ~600ms (cold) to <20ms (cache hit); RDS CPU drops under concurrent read load
- JMeter signal: dramatically lower p99/p95 for /metrics endpoint in Baseline Performance Test

---

## Test Infrastructure

### EC2 Layout
| Instance | Role | Services |
|---|---|---|
| EC2-A | Server + ALB target | server-v3 :8080 |
| EC2-B | Server + ALB target | server-v3 :8080 |
| EC2-C | Consumer (dedicated) | consumer-v3 :8081 |
| RDS | Database | PostgreSQL 17, db.t3.micro |

### Monitoring Scripts (`monitoring/`)
- `monitor-consumer.sh <label>` — polls `/health` every 10s on EC2-C → CSV
- `collect-final.sh <label>` — one-shot end-of-test snapshot (stdout, copy to results.md)

### Load Test Results (`load-tests/`)
- `batch-tuning/results.md` — E1–E4 experiment results and analysis
- `test1-baseline/` — CSV from 500K test
- `test2-stress/` — CSV from 1M test
- `test3-endurance/` — CSV from 1.8M / 30-min test (in progress)
