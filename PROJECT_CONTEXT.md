# ChatFlow Project Context — CS6650 Distributed Systems

## What this project is

ChatFlow is a distributed real-time chat system built across three assignments:
- A1: WebSocket server + multithreaded load test client (echo server)
- A2: Added message queuing (AWS SQS), a Consumer service, and AWS ALB load balancing
- A3: Adding database persistence, analytics, and further optimization (in progress)

All code is Java/Spring Boot. Infrastructure runs on AWS EC2, us-west-2.

---

## Current Architecture (A2, now being extended into A3)

```
[Load Test Client]           local machine, 120 sender threads + 40 warmup threads
        |
   AWS ALB (port 80)         sticky session (LB cookie), routes WS connections
      /        \
[EC2 A]       [EC2 B]        each t3.micro, 2 vCPU, 1GB RAM
Server-v2      Server-v2     Spring Boot, port 8080, Tomcat max 500 threads
:8080          :8080
      \        /
    [SQS x20 FIFO queues]    chatflow-room-01.fifo ... chatflow-room-20.fifo
                             us-west-2, account 449126751631
           |
      [EC2 C]                separate t3.micro
      Consumer               Spring Boot, port 8081
      :8081                  20 polling threads (one per room)
           |
    parallel HTTP broadcast  calls EC2 A and EC2 B simultaneously
    to both Server EC2s      via private IP (172.31.25.72 and 172.31.24.104)
```

---

## Component Breakdown

### client-v2 (Load Test Client)
- Java application, not Spring Boot
- MessageGenerator: generates 500K messages, random roomId (1-20), 90% TEXT / 5% JOIN / 5% LEAVE
- Per-room sub-queues: 20 BlockingQueues, generator routes each message to correct sub-queue
- SenderThread: each thread owns a fixed roomId, creates its own WebSocket session to /chat/{roomId}, drains its sub-queue
- 40 warmup threads × 1000 msgs, then 120 main phase threads
- sendAndWait uses LinkedBlockingQueue (not CountDownLatch) — filters for RECEIVED ack, discards broadcast messages
- Retry: max 5 retries with exponential backoff (10ms base)

### server-v2 (Spring Boot WebSocket + SQS Producer)
Key classes:
- `ChatWebSocketHandler`: receives WS messages, validates, registers session in roomSessions map, sends RECEIVED ack, publishes to SQS async
- `SqsPublisher`: async publish (20-thread pool), Circuit Breaker via Resilience4j, in-memory Dead Letter Queue (10K capacity) with 5s retry loop
- `InternalBroadcastController`: POST /internal/broadcast/{roomId} — returns 200 immediately, submits to per-room single-thread executor (20 executors, one per room) to preserve FIFO ordering within each room
- `ConcurrentWebSocketSessionDecorator`: wraps every session at connection time, OverflowStrategy.DROP, 30s send time limit, 512KB buffer

Key decisions:
- Ack is sent BEFORE SQS publish (async) — client latency not blocked by SQS round-trip
- Session registration uses payload roomId, not URL roomId — session belongs to the room its messages are for
- Relaxed MessageType semantics: first message = implicit JOIN, LEAVE removes session
- Per-room single-thread executor in broadcast controller preserves per-room message ordering

Configuration:
- server.tomcat.threads.max=500
- Resilience4j CB: sliding window 10, failure threshold 50%, open for 30s, half-open allows 3 calls
- Health endpoint: GET /health returns status, serverId, dlqSize, dlqRetried, dlqDropped

### consumer (Spring Boot SQS Consumer)
Key classes:
- `SqsConsumerService`: 20 threads, one per room, long-polls SQS (waitTimeSeconds=20, maxMessages=10), calls BroadcastClient, deletes message only after broadcast succeeds
- `BroadcastClient`: parallel HTTP calls to all Server URLs via CompletableFuture.sendAsync(), throws BroadcastException if ALL servers fail (message stays in SQS for retry), connect timeout 200ms (fast-fail for offline servers)

Key decisions:
- Delivery guarantee: deleteMessage only called after at least one server returns HTTP 200
- Parallel broadcast: total time = max(call times), not sum — adding more servers doesn't slow Consumer
- SQS HTTP connection pool = numThreads + 20 (avoids pool exhaustion)

### AWS Infrastructure
- 20 SQS FIFO queues: MessageGroupId=roomId (per-room ordering), MessageDeduplicationId=UUID (dedup on retry), visibility timeout 120s
- ALB: internet-facing, sticky session 1 day, idle timeout 300s, health check /health
- IAM Role on all EC2s: sqs:SendMessage, sqs:ReceiveMessage, sqs:DeleteMessage, sqs:GetQueueUrl
- EC2 Security Groups: ALB SG → Server EC2s port 8080; Consumer EC2 private IP → Server EC2s port 8080

---

## Key Engineering Problems Solved (A2)

1. **Concurrent WebSocket writes** — two threads (Tomcat WS thread for ack + broadcastExecutor thread for broadcast) could write same session simultaneously → solved with ConcurrentWebSocketSessionDecorator

2. **SQS latency blocking client ack** — synchronous SQS publish added 10-30ms to every message round-trip → solved with async publish (fire-and-forget), ack sent immediately

3. **Tomcat thread pool saturation** — 512 WS sessions + Consumer HTTP calls exceeded Tomcat default 200 threads → raised to 500, reduced client threads to 120

4. **Unbounded broadcastExecutor queue** — memory exhaustion after ~7 min on 500K test → replaced with bounded ArrayBlockingQueue(500 per room) + DiscardPolicy

5. **Session buffer overflow closing sessions** — ConcurrentWebSocketSessionDecorator default TERMINATE strategy closed sessions under broadcast backpressure → changed to DROP strategy

6. **Serial broadcast to multiple servers** — adding a 2nd server doubled Consumer processing time → rewrote BroadcastClient to use CompletableFuture.sendAsync() (parallel)

7. **Per-room message ordering** — shared 40-thread broadcastExecutor could process same room's messages out of order → replaced with 20 per-room single-thread executors

8. **Circuit Breaker missing** — SQS failures were silently dropped, message lost → added Resilience4j CB + in-memory DLQ with retry loop

---

## Performance Results (A2 final)

| Config | Messages | Main Phase | Overall | Failures |
|--------|----------|------------|---------|----------|
| Single EC2 (200K) | 200,000 | 2,190 msg/s | 1,653 msg/s | 0 |
| 2 EC2s via ALB (500K) | 500,000 | 5,100 msg/s | 4,419 msg/s | 0 |

---

## A3 Additions (in progress)

Assignment 3 adds database persistence to the Consumer pipeline. New requirements:
- Persist all messages to a database
- Support queries: messages by room+time range, user history, active user counts, room participation
- Metrics API on server-v2 returning analytics results
- Write-behind pattern: separate Consumer threads from DB writer threads
- Batch writes with tunable batch size (test 100/500/1000/5000) and flush interval
- Idempotent writes using messageId as unique key
- Dead letter queue for failed DB writes
- Circuit breaker for database failures

New components to build:
- `/database` — schema files and setup scripts
- `/consumer-v3` — updated consumer with persistence layer added to existing broadcast pipeline

The existing broadcast pipeline (SQS → Consumer → Server broadcast) stays intact.
DB writes are added as a parallel path, not blocking the broadcast path.
