-- Async ingestion pipeline puts a pre-generated UUID on the order at
-- request time, before Kafka publishes the event. The consumer INSERTs
-- with that UUID, and the unique constraint absorbs at-least-once
-- redeliveries (effectively-once semantics: at-least-once delivery +
-- idempotent consumer keyed on external_id).
--
-- Internal id BIGSERIAL stays the canonical PK — ECPay MerchantTradeNo,
-- payment_event.order_id FK, all interior code use it unchanged. The
-- external UUID is the public-facing handle that the API returns in the
-- 202 response and the polling endpoint accepts.

ALTER TABLE orders
    ADD COLUMN external_id VARCHAR(64);

-- Backfill any existing rows (test data, prior runs) with a synthetic UUID
-- so the NOT NULL + UNIQUE constraints below don't reject them.
UPDATE orders
   SET external_id = REPLACE(gen_random_uuid()::text, '-', '')
 WHERE external_id IS NULL;

ALTER TABLE orders
    ALTER COLUMN external_id SET NOT NULL,
    ADD CONSTRAINT orders_external_id_unique UNIQUE (external_id);

CREATE INDEX idx_orders_external_id ON orders (external_id);
