-- The owner confirmed Victoria's T-Mobile SIM; RIPE identifies the observed
-- prefixes as RU-TINKOFF-MOBILE-LIR-20100315. Preserve evidence while excluding
-- only her verified legacy-browser false negatives from violation statistics.
UPDATE worker_network_violation_episodes
SET access_result = 'INVALIDATED'
WHERE worker_user_id = 24
  AND worker_username = 'toria'
  AND reason_code = 'NON_CELLULAR_NETWORK'
  AND provider = 'T-MOB LLC'
  AND ip_prefix IN ('178.130.142.0/24', '178.130.143.0/24')
  AND client_evidence = 'client=web-or-legacy'
  AND first_seen_at >= '2026-10-26 00:00:00'
  AND access_result <> 'INVALIDATED';
