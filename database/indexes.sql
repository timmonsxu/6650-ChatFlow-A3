-- ChatFlow A3 — Index Definitions
-- Run AFTER schema.sql.
--
-- Index strategy (4 indexes serving 4 core queries + analytics):
--
--  idx_room_time     → Q1: messages in room X for time range
--                       WHERE room_id = ? AND sent_at BETWEEN ? AND ?
--                       Composite (room_id, sent_at): seek to room then range-scan time.
--
--  idx_user_time     → Q2: user message history in date range
--                       WHERE user_id = ? AND sent_at BETWEEN ? AND ?
--                       Same composite pattern, different leading column.
--
--  idx_sent_at_uid   → Q3: count active users in time window
--                       COUNT(DISTINCT user_id) WHERE sent_at BETWEEN ? AND ?
--                       (sent_at, user_id) enables INDEX-ONLY SCAN — PostgreSQL reads
--                       user_id values directly from the index without touching the heap.
--                       Critical for hitting the < 500ms performance target.
--
--  idx_user_room_time → Q4: rooms a user participated in + last activity
--                        SELECT room_id, MAX(sent_at) WHERE user_id = ? GROUP BY room_id
--                        (user_id, room_id, sent_at) covers the entire query as an
--                        index-only scan: seek user_id, iterate (room_id, sent_at) pairs,
--                        no heap access needed.
--
-- Write-performance trade-off:
--   4 indexes add ~15–20% overhead per INSERT row.
--   With batch multi-row INSERT this overhead is amortized and acceptable.
--   Dropping idx_sent_at_uid (Q3) would save ~5% writes but risk missing Q3's 500ms target.

CREATE INDEX IF NOT EXISTS idx_room_time
    ON messages (room_id, sent_at);

CREATE INDEX IF NOT EXISTS idx_user_time
    ON messages (user_id, sent_at);

-- Covering index for Q3: index-only scan on time range + user_id
CREATE INDEX IF NOT EXISTS idx_sent_at_uid
    ON messages (sent_at, user_id);

-- Covering index for Q4: index-only scan for room participation per user
CREATE INDEX IF NOT EXISTS idx_user_room_time
    ON messages (user_id, room_id, sent_at);
