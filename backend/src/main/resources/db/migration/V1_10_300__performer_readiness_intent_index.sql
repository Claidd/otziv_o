-- This is evidence that a scoped READY intent exists, irrespective of its delivery
-- outcome. UNKNOWN/BLOCKED/CANCELLED do not become eligible for an automatic retry.
-- Drain old performer producers before cutover. Old producers do not maintain this
-- marker; after any rollback to an old producer, repeat the exact-generation
-- backfill below before starting the indexed producer again.
ALTER TABLE review_performer_assignments
    ADD COLUMN readiness_intent_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN ready_notification_pending TINYINT
        GENERATED ALWAYS AS (publication_generation > readiness_intent_generation) STORED,
    ADD INDEX idx_performer_ready_notification
        (status, ready_notification_pending, publish_available_at, assignment_id);

-- Never synthesize a delivery result or attribute an older generation to a new
-- publication cycle. Legacy generation zero remains excluded from new sends.
UPDATE review_performer_assignments a
JOIN performer_notification_intents n
  ON n.operation_key = CONCAT('READY:', a.assignment_id, ':', a.publication_generation)
 AND n.assignment_id = a.assignment_id
 AND n.notification_type = 'READY'
 AND n.offer_id IS NULL
 AND n.generation = a.publication_generation
SET a.readiness_intent_generation = a.publication_generation
WHERE a.publication_generation > a.readiness_intent_generation;
