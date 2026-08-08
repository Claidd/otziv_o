CREATE TABLE contractor_payment_rollout_state (
    id INT PRIMARY KEY,
    accounting_authority VARCHAR(16) NOT NULL,
    routing_requested BOOLEAN NOT NULL DEFAULT FALSE,
    attribution_start_date DATE NULL,
    activated_at DATETIME(6) NULL,
    activated_by VARCHAR(150) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(150) NOT NULL DEFAULT 'MIGRATION',
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_contractor_payment_rollout_state_id CHECK (id = 1),
    CONSTRAINT ck_contractor_payment_rollout_authority
        CHECK (accounting_authority IN ('LEGACY', 'COMPLETION')),
    CONSTRAINT ck_contractor_payment_rollout_cutover
        CHECK (accounting_authority = 'LEGACY' OR attribution_start_date IS NOT NULL)
) ENGINE=InnoDB;

-- Preserve an already-started rollout during upgrades. A LIVE allocation or
-- an immutable completion cutover means accounting must never fall back to
-- legacy even if a mutable deployment/app-setting gate is later disabled.
INSERT INTO contractor_payment_rollout_state (
    id,
    accounting_authority,
    routing_requested,
    attribution_start_date,
    activated_at,
    activated_by,
    updated_at,
    updated_by
)
SELECT
    1,
    CASE
        WHEN cutover.attribution_start_date IS NOT NULL
             OR phase.phase = 'LIVE'
             OR COALESCE(attribution_flag.setting_value, 'false') = 'true'
            THEN 'COMPLETION'
        ELSE 'LEGACY'
    END,
    CASE
        WHEN (cutover.attribution_start_date IS NOT NULL
              OR phase.phase = 'LIVE'
              OR COALESCE(attribution_flag.setting_value, 'false') = 'true')
             AND COALESCE(routing_flag.setting_value, 'false') = 'true'
            THEN TRUE
        ELSE FALSE
    END,
    COALESCE(
        cutover.attribution_start_date,
        NULLIF(attribution_date.setting_value, '')
    ),
    CASE
        WHEN cutover.attribution_start_date IS NOT NULL
             OR phase.phase = 'LIVE'
             OR COALESCE(attribution_flag.setting_value, 'false') = 'true'
            THEN CURRENT_TIMESTAMP(6)
        ELSE NULL
    END,
    CASE
        WHEN cutover.attribution_start_date IS NOT NULL
             OR phase.phase = 'LIVE'
             OR COALESCE(attribution_flag.setting_value, 'false') = 'true'
            THEN 'MIGRATION'
        ELSE NULL
    END,
    CURRENT_TIMESTAMP(6),
    'MIGRATION'
FROM contractor_payment_accounting_phase phase
LEFT JOIN contractor_completion_cutover_state cutover ON cutover.id = 1
LEFT JOIN app_settings attribution_flag
    ON attribution_flag.setting_key = 'contractor-payments.reward-attribution-live-enabled'
LEFT JOIN app_settings routing_flag
    ON routing_flag.setting_key = 'contractor-payments.live-routing-enabled'
LEFT JOIN app_settings attribution_date
    ON attribution_date.setting_key = 'contractor-payments.completion-attribution-start-date'
WHERE phase.id = 1;

-- Completion authority makes direct settlements authoritative immediately;
-- it must not wait for the first client invoice route.
UPDATE contractor_payment_accounting_phase phase
JOIN contractor_payment_rollout_state rollout ON rollout.id = phase.id
SET phase.phase = 'LIVE',
    phase.updated_at = CURRENT_TIMESTAMP(6),
    phase.updated_by = 'MIGRATION',
    phase.row_version = phase.row_version + 1
WHERE rollout.accounting_authority = 'COMPLETION'
  AND phase.phase = 'SHADOW';
