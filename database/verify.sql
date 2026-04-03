-- ChatFlow A3 — Verification Queries
-- Run these manually after setup to confirm schema + indexes are correct.
-- All queries should succeed on an empty table (return 0 rows / 0 count).

-- ── 1. Confirm table and columns exist ────────────────────────
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_name = 'messages'
ORDER BY ordinal_position;
-- Expected: 11 rows (id, message_id, room_id, user_id, username, message,
--           message_type, server_id, client_ip, sent_at, created_at)

-- ── 2. Confirm all 4 indexes exist ────────────────────────────
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'messages'
ORDER BY indexname;
-- Expected: 5 rows (pk_messages + 4 named indexes)

-- ── 3. Smoke-test INSERT (idempotency) ────────────────────────
INSERT INTO messages
    (message_id, room_id, user_id, username, message, message_type,
     server_id, client_ip, sent_at)
VALUES
    ('test-uuid-0001', 1, 42, 'alice', 'hello world', 'TEXT',
     'server-8080', '127.0.0.1', 1743638400000);

-- Re-insert same message_id — must silently succeed (0 rows inserted, no error)
INSERT INTO messages
    (message_id, room_id, user_id, username, message, message_type,
     server_id, client_ip, sent_at)
VALUES
    ('test-uuid-0001', 1, 42, 'alice', 'hello world', 'TEXT',
     'server-8080', '127.0.0.1', 1743638400000)
ON CONFLICT (message_id) DO NOTHING;

-- ── 4. Confirm Q1 uses the right index ────────────────────────
EXPLAIN (FORMAT TEXT)
SELECT message_id, room_id, user_id, username, message, message_type, sent_at
FROM messages
WHERE room_id = 1 AND sent_at BETWEEN 1743638400000 AND 1743638460000
ORDER BY sent_at;
-- Expected plan: Index Scan using idx_room_time

-- ── 5. Confirm Q3 uses index-only scan ────────────────────────
EXPLAIN (FORMAT TEXT)
SELECT COUNT(DISTINCT user_id)
FROM messages
WHERE sent_at BETWEEN 1743638400000 AND 1743638460000;
-- Expected plan: Index Only Scan using idx_sent_at_uid

-- ── 6. Confirm Q4 uses index-only scan ────────────────────────
EXPLAIN (FORMAT TEXT)
SELECT room_id, MAX(sent_at) AS last_active
FROM messages
WHERE user_id = 42
GROUP BY room_id
ORDER BY last_active DESC;
-- Expected plan: Index Only Scan using idx_user_room_time

-- ── 7. Clean up smoke-test row ────────────────────────────────
DELETE FROM messages WHERE message_id = 'test-uuid-0001';

-- ── 8. Confirm table is empty after cleanup ───────────────────
SELECT COUNT(*) AS row_count FROM messages;
-- Expected: 0
