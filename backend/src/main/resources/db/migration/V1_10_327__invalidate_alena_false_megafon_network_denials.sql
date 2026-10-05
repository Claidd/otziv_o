UPDATE worker_network_violation_episodes
SET access_result = 'INVALIDATED'
WHERE worker_username = 'alena'
  AND reason_code = 'NON_CELLULAR_NETWORK'
  AND provider = 'PJSC MegaFon'
  AND ip_prefix = '178.177.229.0/24'
  AND access_result <> 'INVALIDATED';
