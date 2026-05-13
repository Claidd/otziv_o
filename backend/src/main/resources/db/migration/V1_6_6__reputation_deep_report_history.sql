CREATE INDEX idx_reputation_deep_report_job_company_created
    ON reputation_deep_report_jobs (company_id, created_at);

ALTER TABLE reputation_deep_report_jobs
    DROP INDEX uk_reputation_deep_report_job_company;
