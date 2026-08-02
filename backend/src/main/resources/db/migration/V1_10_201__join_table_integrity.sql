-- Association tables must represent sets. Legacy workers_companies contained
-- duplicate pairs and allowed NULL rows, while the four user/profile tables
-- had no database-level duplicate protection at all.

DELETE FROM workers_companies
WHERE company_id IS NULL OR worker_id IS NULL;

ALTER TABLE workers_companies
    ADD COLUMN migration_dedupe_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

DELETE duplicate_link
FROM workers_companies duplicate_link
JOIN workers_companies canonical_link
  ON canonical_link.company_id = duplicate_link.company_id
 AND canonical_link.worker_id = duplicate_link.worker_id
 AND canonical_link.migration_dedupe_id < duplicate_link.migration_dedupe_id;

ALTER TABLE workers_companies
    DROP PRIMARY KEY,
    DROP COLUMN migration_dedupe_id,
    MODIFY company_id BIGINT NOT NULL,
    MODIFY worker_id BIGINT NOT NULL,
    ADD CONSTRAINT pk_workers_companies PRIMARY KEY (company_id, worker_id);

DELETE FROM operators_users
WHERE user_id IS NULL OR operator_id IS NULL;

ALTER TABLE operators_users
    ADD COLUMN migration_dedupe_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

DELETE duplicate_link
FROM operators_users duplicate_link
JOIN operators_users canonical_link
  ON canonical_link.user_id = duplicate_link.user_id
 AND canonical_link.operator_id = duplicate_link.operator_id
 AND canonical_link.migration_dedupe_id < duplicate_link.migration_dedupe_id;

ALTER TABLE operators_users
    DROP PRIMARY KEY,
    DROP COLUMN migration_dedupe_id,
    ADD CONSTRAINT pk_operators_users PRIMARY KEY (user_id, operator_id);

DELETE FROM managers_users
WHERE user_id IS NULL OR manager_id IS NULL;

ALTER TABLE managers_users
    ADD COLUMN migration_dedupe_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

DELETE duplicate_link
FROM managers_users duplicate_link
JOIN managers_users canonical_link
  ON canonical_link.user_id = duplicate_link.user_id
 AND canonical_link.manager_id = duplicate_link.manager_id
 AND canonical_link.migration_dedupe_id < duplicate_link.migration_dedupe_id;

ALTER TABLE managers_users
    DROP PRIMARY KEY,
    DROP COLUMN migration_dedupe_id,
    ADD CONSTRAINT pk_managers_users PRIMARY KEY (user_id, manager_id);

DELETE FROM workers_users
WHERE user_id IS NULL OR worker_id IS NULL;

ALTER TABLE workers_users
    ADD COLUMN migration_dedupe_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

DELETE duplicate_link
FROM workers_users duplicate_link
JOIN workers_users canonical_link
  ON canonical_link.user_id = duplicate_link.user_id
 AND canonical_link.worker_id = duplicate_link.worker_id
 AND canonical_link.migration_dedupe_id < duplicate_link.migration_dedupe_id;

ALTER TABLE workers_users
    DROP PRIMARY KEY,
    DROP COLUMN migration_dedupe_id,
    ADD CONSTRAINT pk_workers_users PRIMARY KEY (user_id, worker_id);

DELETE FROM marketologs_users
WHERE user_id IS NULL OR marketolog_id IS NULL;

ALTER TABLE marketologs_users
    ADD COLUMN migration_dedupe_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

DELETE duplicate_link
FROM marketologs_users duplicate_link
JOIN marketologs_users canonical_link
  ON canonical_link.user_id = duplicate_link.user_id
 AND canonical_link.marketolog_id = duplicate_link.marketolog_id
 AND canonical_link.migration_dedupe_id < duplicate_link.migration_dedupe_id;

ALTER TABLE marketologs_users
    DROP PRIMARY KEY,
    DROP COLUMN migration_dedupe_id,
    ADD CONSTRAINT pk_marketologs_users PRIMARY KEY (user_id, marketolog_id);
