-- Preserve invalid legacy links for audit before removing them. These rows
-- cannot be represented once the foreign keys in the next migration exist.
INSERT IGNORE INTO association_orphan_quarantine (
    association_table, left_column, left_id,
    right_column, right_id, orphan_side
)
SELECT
    'users_roles', 'user_id', link.user_id,
    'role_id', link.role_id,
    CASE
        WHEN user_row.id IS NULL AND role_row.id IS NULL THEN 'BOTH'
        WHEN user_row.id IS NULL THEN 'LEFT'
        ELSE 'RIGHT'
    END
FROM users_roles link
LEFT JOIN users user_row ON user_row.id = link.user_id
LEFT JOIN roles role_row ON role_row.id = link.role_id
WHERE user_row.id IS NULL OR role_row.id IS NULL;

DELETE link
FROM users_roles link
LEFT JOIN users user_row ON user_row.id = link.user_id
LEFT JOIN roles role_row ON role_row.id = link.role_id
WHERE user_row.id IS NULL OR role_row.id IS NULL;

INSERT IGNORE INTO association_orphan_quarantine (
    association_table, left_column, left_id,
    right_column, right_id, orphan_side
)
SELECT
    'workers_companies', 'company_id', link.company_id,
    'worker_id', link.worker_id,
    CASE
        WHEN company_row.company_id IS NULL AND worker_row.worker_id IS NULL THEN 'BOTH'
        WHEN company_row.company_id IS NULL THEN 'LEFT'
        ELSE 'RIGHT'
    END
FROM workers_companies link
LEFT JOIN companies company_row ON company_row.company_id = link.company_id
LEFT JOIN workers worker_row ON worker_row.worker_id = link.worker_id
WHERE company_row.company_id IS NULL OR worker_row.worker_id IS NULL;

DELETE link
FROM workers_companies link
LEFT JOIN companies company_row ON company_row.company_id = link.company_id
LEFT JOIN workers worker_row ON worker_row.worker_id = link.worker_id
WHERE company_row.company_id IS NULL OR worker_row.worker_id IS NULL;

INSERT IGNORE INTO association_orphan_quarantine (
    association_table, left_column, left_id,
    right_column, right_id, orphan_side
)
SELECT
    'workers_users', 'user_id', link.user_id,
    'worker_id', link.worker_id,
    CASE
        WHEN user_row.id IS NULL AND worker_row.worker_id IS NULL THEN 'BOTH'
        WHEN user_row.id IS NULL THEN 'LEFT'
        ELSE 'RIGHT'
    END
FROM workers_users link
LEFT JOIN users user_row ON user_row.id = link.user_id
LEFT JOIN workers worker_row ON worker_row.worker_id = link.worker_id
WHERE user_row.id IS NULL OR worker_row.worker_id IS NULL;

DELETE link
FROM workers_users link
LEFT JOIN users user_row ON user_row.id = link.user_id
LEFT JOIN workers worker_row ON worker_row.worker_id = link.worker_id
WHERE user_row.id IS NULL OR worker_row.worker_id IS NULL;
