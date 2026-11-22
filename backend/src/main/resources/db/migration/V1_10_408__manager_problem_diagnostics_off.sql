-- Owner's decision 22.11.2026: the DeepSeek hint on manager-control remarks repeated the card or was partly wrong in
-- most graded cases (about 6% useful) at about 51 model calls a day. The remark, its reason and its buttons stay;
-- only the AI «Разбор замечания» stops. It can be switched on again on the «Аудит» tab of «Справочники».
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('manager-control.diagnostics.mode', 'OFF', NOW(6))
ON DUPLICATE KEY UPDATE setting_value = 'OFF', updated_at = NOW(6);
