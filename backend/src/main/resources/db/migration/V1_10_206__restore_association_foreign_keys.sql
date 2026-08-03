-- Legacy installations may already have constraints under old names. An FK
-- is equivalent only when its source/target schemas and columns plus both
-- referential actions match. Drop a drifted constraint before restoring the
-- canonical CASCADE/CASCADE constraint; merely adding a second FK would leave
-- a stricter legacy rule active.

SET @drift_fks = (
    SELECT GROUP_CONCAT(
        CONCAT('DROP FOREIGN KEY `', REPLACE(kcu.constraint_name, '`', '``'), '`')
        ORDER BY kcu.constraint_name SEPARATOR ', '
    )
    FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema
     AND rc.table_name = kcu.table_name
     AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'users_roles'
      AND (kcu.column_name = 'user_id' OR kcu.constraint_name = 'fk_users_roles_user')
      AND NOT (kcu.column_name = 'user_id' AND kcu.referenced_table_schema = DATABASE()
        AND kcu.referenced_table_name = 'users' AND kcu.referenced_column_name = 'id'
        AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE')
);
SET @sql = IF(@drift_fks IS NULL, 'SELECT ''users_roles.user_id foreign key has no drift''',
    CONCAT('ALTER TABLE users_roles ', @drift_fks));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @fk_exists = (
    SELECT COUNT(*) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'users_roles' AND kcu.column_name = 'user_id'
      AND kcu.referenced_table_schema = DATABASE() AND kcu.referenced_table_name = 'users' AND kcu.referenced_column_name = 'id'
      AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE users_roles ADD CONSTRAINT fk_users_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''users_roles.user_id foreign key already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @drift_fks = (
    SELECT GROUP_CONCAT(
        CONCAT('DROP FOREIGN KEY `', REPLACE(kcu.constraint_name, '`', '``'), '`')
        ORDER BY kcu.constraint_name SEPARATOR ', '
    ) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'users_roles'
      AND (kcu.column_name = 'role_id' OR kcu.constraint_name = 'fk_users_roles_role')
      AND NOT (kcu.column_name = 'role_id' AND kcu.referenced_table_schema = DATABASE()
        AND kcu.referenced_table_name = 'roles' AND kcu.referenced_column_name = 'id'
        AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE')
);
SET @sql = IF(@drift_fks IS NULL, 'SELECT ''users_roles.role_id foreign key has no drift''',
    CONCAT('ALTER TABLE users_roles ', @drift_fks));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @fk_exists = (
    SELECT COUNT(*) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'users_roles' AND kcu.column_name = 'role_id'
      AND kcu.referenced_table_schema = DATABASE() AND kcu.referenced_table_name = 'roles' AND kcu.referenced_column_name = 'id'
      AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE users_roles ADD CONSTRAINT fk_users_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''users_roles.role_id foreign key already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @drift_fks = (
    SELECT GROUP_CONCAT(
        CONCAT('DROP FOREIGN KEY `', REPLACE(kcu.constraint_name, '`', '``'), '`')
        ORDER BY kcu.constraint_name SEPARATOR ', '
    ) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'workers_companies'
      AND (kcu.column_name = 'company_id' OR kcu.constraint_name = 'fk_workers_companies_company')
      AND NOT (kcu.column_name = 'company_id' AND kcu.referenced_table_schema = DATABASE()
        AND kcu.referenced_table_name = 'companies' AND kcu.referenced_column_name = 'company_id'
        AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE')
);
SET @sql = IF(@drift_fks IS NULL, 'SELECT ''workers_companies.company_id foreign key has no drift''',
    CONCAT('ALTER TABLE workers_companies ', @drift_fks));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @fk_exists = (
    SELECT COUNT(*) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'workers_companies' AND kcu.column_name = 'company_id'
      AND kcu.referenced_table_schema = DATABASE() AND kcu.referenced_table_name = 'companies' AND kcu.referenced_column_name = 'company_id'
      AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE workers_companies ADD CONSTRAINT fk_workers_companies_company FOREIGN KEY (company_id) REFERENCES companies (company_id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''workers_companies.company_id foreign key already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @drift_fks = (
    SELECT GROUP_CONCAT(
        CONCAT('DROP FOREIGN KEY `', REPLACE(kcu.constraint_name, '`', '``'), '`')
        ORDER BY kcu.constraint_name SEPARATOR ', '
    ) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'workers_companies'
      AND (kcu.column_name = 'worker_id' OR kcu.constraint_name = 'fk_workers_companies_worker')
      AND NOT (kcu.column_name = 'worker_id' AND kcu.referenced_table_schema = DATABASE()
        AND kcu.referenced_table_name = 'workers' AND kcu.referenced_column_name = 'worker_id'
        AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE')
);
SET @sql = IF(@drift_fks IS NULL, 'SELECT ''workers_companies.worker_id foreign key has no drift''',
    CONCAT('ALTER TABLE workers_companies ', @drift_fks));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @fk_exists = (
    SELECT COUNT(*) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'workers_companies' AND kcu.column_name = 'worker_id'
      AND kcu.referenced_table_schema = DATABASE() AND kcu.referenced_table_name = 'workers' AND kcu.referenced_column_name = 'worker_id'
      AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE workers_companies ADD CONSTRAINT fk_workers_companies_worker FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''workers_companies.worker_id foreign key already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @drift_fks = (
    SELECT GROUP_CONCAT(
        CONCAT('DROP FOREIGN KEY `', REPLACE(kcu.constraint_name, '`', '``'), '`')
        ORDER BY kcu.constraint_name SEPARATOR ', '
    ) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'workers_users'
      AND (kcu.column_name = 'user_id' OR kcu.constraint_name = 'fk_workers_users_user')
      AND NOT (kcu.column_name = 'user_id' AND kcu.referenced_table_schema = DATABASE()
        AND kcu.referenced_table_name = 'users' AND kcu.referenced_column_name = 'id'
        AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE')
);
SET @sql = IF(@drift_fks IS NULL, 'SELECT ''workers_users.user_id foreign key has no drift''',
    CONCAT('ALTER TABLE workers_users ', @drift_fks));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @fk_exists = (
    SELECT COUNT(*) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'workers_users' AND kcu.column_name = 'user_id'
      AND kcu.referenced_table_schema = DATABASE() AND kcu.referenced_table_name = 'users' AND kcu.referenced_column_name = 'id'
      AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE workers_users ADD CONSTRAINT fk_workers_users_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''workers_users.user_id foreign key already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @drift_fks = (
    SELECT GROUP_CONCAT(
        CONCAT('DROP FOREIGN KEY `', REPLACE(kcu.constraint_name, '`', '``'), '`')
        ORDER BY kcu.constraint_name SEPARATOR ', '
    ) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'workers_users'
      AND (kcu.column_name = 'worker_id' OR kcu.constraint_name = 'fk_workers_users_worker')
      AND NOT (kcu.column_name = 'worker_id' AND kcu.referenced_table_schema = DATABASE()
        AND kcu.referenced_table_name = 'workers' AND kcu.referenced_column_name = 'worker_id'
        AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE')
);
SET @sql = IF(@drift_fks IS NULL, 'SELECT ''workers_users.worker_id foreign key has no drift''',
    CONCAT('ALTER TABLE workers_users ', @drift_fks));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @fk_exists = (
    SELECT COUNT(*) FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema AND rc.table_name = kcu.table_name AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE() AND kcu.table_name = 'workers_users' AND kcu.column_name = 'worker_id'
      AND kcu.referenced_table_schema = DATABASE() AND kcu.referenced_table_name = 'workers' AND kcu.referenced_column_name = 'worker_id'
      AND rc.delete_rule = 'CASCADE' AND rc.update_rule = 'CASCADE'
);
SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE workers_users ADD CONSTRAINT fk_workers_users_worker FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON DELETE CASCADE ON UPDATE CASCADE',
    'SELECT ''workers_users.worker_id foreign key already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
