-- Update the standard notification text while preserving custom templates.
UPDATE app_settings
SET setting_value = '{companyAndFilial}. Опубликован новый текст {progress}.',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE setting_key = 'client.publication-progress-reports.text'
  AND setting_value = '{companyAndFilial}. Опубликован новый отзыв {progress}.';
