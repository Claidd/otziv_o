ALTER TABLE manager_report_review_sessions
    ADD COLUMN questions_context LONGTEXT NULL AFTER questions_json;
