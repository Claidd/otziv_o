INSERT INTO payment_profiles (
    code,
    provider,
    name,
    terminal_key,
    password_env_key,
    enabled,
    is_default,
    test_mode
)
SELECT
    'tochka-primary',
    'TOCHKA',
    'Точка Банк',
    'tochka-profile-placeholder',
    NULL,
    FALSE,
    FALSE,
    FALSE
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM payment_profiles
    WHERE code = 'tochka-primary'
       OR terminal_key = 'tochka-profile-placeholder'
);
