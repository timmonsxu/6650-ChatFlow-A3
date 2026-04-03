-- ChatFlow A3 — PostgreSQL Schema
-- Run this file ONCE against the chatflow database.

-- ──────────────────────────────────────────────────────────────
-- Messages table
-- ──────────────────────────────────────────────────────────────
--
-- Field notes:
--   message_id  UUID string from server (idempotency key — ON CONFLICT DO NOTHING)
--   room_id     integer 1–20  (stored as SMALLINT, parsed from zero-padded SQS string)
--   user_id     integer 1–100000
--   sent_at     epoch millis from client timestamp (BIGINT, not TIMESTAMPTZ)
--               → enables direct BETWEEN comparisons with client-supplied epoch millis
--   created_at  server-side write time, for debugging / lag measurement only
--
CREATE TABLE IF NOT EXISTS messages (
    id           BIGSERIAL      NOT NULL,
    message_id   VARCHAR(36)    NOT NULL,
    room_id      SMALLINT       NOT NULL,
    user_id      INT            NOT NULL,
    username     VARCHAR(20)    NOT NULL,
    message      TEXT           NOT NULL,
    message_type VARCHAR(8)     NOT NULL,   -- 'TEXT' | 'JOIN' | 'LEAVE'
    server_id    VARCHAR(50),
    client_ip    VARCHAR(45),
    sent_at      BIGINT         NOT NULL,   -- epoch millis
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_messages         PRIMARY KEY (id),
    CONSTRAINT uq_messages_msg_id  UNIQUE      (message_id)
);
