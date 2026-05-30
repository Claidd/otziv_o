INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
  ('publication.health-monitor.enabled', 'true', CURRENT_TIMESTAMP(6)),
  ('publication.health-monitor.zone', 'Asia/Irkutsk', CURRENT_TIMESTAMP(6)),
  ('publication.health-monitor.last-run-key', '', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_value = setting_value;
