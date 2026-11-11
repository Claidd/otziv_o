-- A manager may name the person whose card number replaces an unpaid bank link. The choice is kept
-- with the pending change, so the rehearsal before the bank Cancel and the final handover use it.
ALTER TABLE bank_recipient_change
    ADD COLUMN chosen_profile_id BIGINT NULL;
