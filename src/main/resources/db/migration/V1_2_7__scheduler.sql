CREATE TABLE dispatch_settings (
                                   id INT PRIMARY KEY,
                                   cron_expression VARCHAR(255),
                                   last_run TIMESTAMP
);

INSERT INTO dispatch_settings (id, cron_expression) VALUES (1, '0 0 14 * * *');


