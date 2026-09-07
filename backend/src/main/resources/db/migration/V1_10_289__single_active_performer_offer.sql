-- Prerequisite: drain old writers and review this query before applying the constraint:
-- SELECT assignment_id, COUNT(*) FROM review_performer_offers WHERE status='OFFERED'
-- GROUP BY assignment_id HAVING COUNT(*) > 1;
-- Existing conflicts deliberately fail the migration. Do not auto-select a winner or penalize performers.
ALTER TABLE review_performer_offers
    ADD COLUMN active_assignment_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'OFFERED' THEN assignment_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uq_performer_offer_active_assignment (active_assignment_id);
