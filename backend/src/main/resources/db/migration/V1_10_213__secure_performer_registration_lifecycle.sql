-- Public performer applications are intentionally not phone-verified. Keep
-- their account disabled until a privileged operator performs an out-of-band
-- check and explicitly activates it. Consent evidence stores both timestamp
-- and immutable policy version; no historical consent is fabricated here.
ALTER TABLE performer_profiles
    ADD COLUMN personal_data_consent_version VARCHAR(64) NULL AFTER personal_data_accepted_at,
    ADD COLUMN rules_consent_version VARCHAR(64) NULL AFTER rules_accepted_at,
    ADD COLUMN honest_review_consent_version VARCHAR(64) NULL AFTER honest_review_accepted_at,
    ADD COLUMN registration_expires_at DATETIME(6) NULL AFTER honest_review_consent_version,
    ADD COLUMN phone_verified_at DATETIME(6) NULL AFTER registration_expires_at,
    ADD COLUMN phone_verification_method VARCHAR(32) NULL AFTER phone_verified_at,
    ADD COLUMN phone_verification_note VARCHAR(500) NULL AFTER phone_verification_method,
    ADD COLUMN legacy_approved_before_secure_lifecycle TINYINT(1) NOT NULL DEFAULT 0 AFTER phone_verification_note,
    ADD INDEX idx_performer_profiles_pending_expiry (status, registration_expires_at);

-- This marker states only a factual migration condition: the profile was
-- ACTIVE immediately before the protected lifecycle was introduced. It does
-- not claim that any consent or phone verification happened in the past.
UPDATE performer_profiles
SET legacy_approved_before_secure_lifecycle = 1
WHERE status = 'ACTIVE';

-- Legacy NEW rows had a disclosed credential and no verifiable consent trail.
-- They cannot be promoted safely, so expire them without inventing evidence.
UPDATE performer_profiles
SET status = 'REJECTED',
    registration_expires_at = CURRENT_TIMESTAMP(6),
    telegram_link_token = NULL,
    block_reason = COALESCE(NULLIF(TRIM(block_reason), ''), 'Заявка создана до защищённого жизненного цикла; требуется повторная регистрация')
WHERE status = 'NEW';

UPDATE users u
JOIN performer_profiles p ON p.user_id = u.id
SET u.active = 0,
    u.auth_epoch = u.auth_epoch + 1,
    u.deactivated_at = COALESCE(u.deactivated_at, CURRENT_TIMESTAMP(6)),
    u.deactivation_reason = 'PERFORMER_REGISTRATION_EXPIRED'
WHERE p.status = 'REJECTED'
  AND p.registration_expires_at IS NOT NULL
  AND p.registration_expires_at <= CURRENT_TIMESTAMP(6)
  AND u.active = 1;
