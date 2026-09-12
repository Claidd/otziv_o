-- Cover the authorized, time-bounded team reads without fetching message bodies or event payloads.
ALTER TABLE manager_site_activity_events ADD INDEX idx_manager_activity_points
    (manager_id, occurred_at, activity_type), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE client_chat_messages ADD INDEX idx_chat_actor_staff_points
    (actor_user_id, sender_role, message_at), ALGORITHM=INPLACE, LOCK=NONE;
-- Both publication branches are disjoint. NULL marked timestamps use the legacy changed date.
ALTER TABLE reviews ADD INDEX idx_reviews_worker_publication_history
    (review_worker, review_publish, review_published_marked_at, review_changed), ALGORITHM=INPLACE, LOCK=NONE;
-- Live financial totals retain their canonical ledger and exact arithmetic.
ALTER TABLE contractor_reward_ledger ADD INDEX idx_reward_profile_active_totals
    (profile_id, active, occurred_on, amount_kopecks, source_zp_id, work_units), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE contractor_payment_allocation_events ADD INDEX idx_allocation_event_totals
    (allocation_id, event_type, effective_at, amount_kopecks), ALGORITHM=INPLACE, LOCK=NONE;
