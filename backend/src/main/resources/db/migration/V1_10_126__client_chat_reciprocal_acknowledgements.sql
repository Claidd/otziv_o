INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES (
    'manager-control.unanswered-client-messages.auto-ignore-phrases',
    'ок,окей,хорошо,спасибо,спасибо большое,вам спасибо,и вам спасибо,взаимно,благодарю,да,нет,понял,поняла,поняли,принято,договорились,отлично,супер,ясно,ладно,хорошо спасибо,спс',
    CURRENT_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE setting_key = setting_key;

UPDATE app_settings
SET setting_value = CONCAT(setting_value, ',вам спасибо'),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'manager-control.unanswered-client-messages.auto-ignore-phrases'
  AND FIND_IN_SET('вам спасибо', REPLACE(setting_value, ', ', ',')) = 0;

UPDATE app_settings
SET setting_value = CONCAT(setting_value, ',и вам спасибо'),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'manager-control.unanswered-client-messages.auto-ignore-phrases'
  AND FIND_IN_SET('и вам спасибо', REPLACE(setting_value, ', ', ',')) = 0;

UPDATE app_settings
SET setting_value = CONCAT(setting_value, ',взаимно'),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'manager-control.unanswered-client-messages.auto-ignore-phrases'
  AND FIND_IN_SET('взаимно', REPLACE(setting_value, ', ', ',')) = 0;
