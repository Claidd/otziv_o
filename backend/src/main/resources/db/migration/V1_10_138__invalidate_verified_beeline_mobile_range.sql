UPDATE worker_network_violation_episodes
SET access_result = 'INVALIDATED'
WHERE reason_code = 'NON_CELLULAR_NETWORK'
  AND provider = 'PJSC "Vimpelcom"'
  AND ip_prefix IN (
      '89.113.30.0/24',
      '89.113.31.0/24'
  )
  AND access_result <> 'INVALIDATED';
