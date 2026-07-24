ALTER TABLE worker_network_violation_episodes
    ADD COLUMN client_evidence VARCHAR(500) NULL AFTER ip_prefix;
