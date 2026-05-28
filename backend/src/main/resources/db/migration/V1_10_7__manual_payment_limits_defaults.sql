UPDATE payment_profiles
SET manual_monthly_soft_limit_kopecks = 19100000
WHERE manual_monthly_soft_limit_kopecks IS NULL;

UPDATE payment_profiles
SET manual_monthly_hard_limit_kopecks = 19100000
WHERE manual_monthly_hard_limit_kopecks IS NULL;

ALTER TABLE payment_profiles
    MODIFY COLUMN manual_monthly_soft_limit_kopecks BIGINT NULL DEFAULT 19100000,
    MODIFY COLUMN manual_monthly_hard_limit_kopecks BIGINT NULL DEFAULT 19100000;
