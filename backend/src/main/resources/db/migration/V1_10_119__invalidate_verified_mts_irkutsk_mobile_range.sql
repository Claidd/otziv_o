UPDATE worker_network_violation_episodes
SET access_result = 'INVALIDATED'
WHERE reason_code = 'NON_CELLULAR_NETWORK'
  AND ip_prefix IN (
      '91.78.236.0/24',
      '91.78.237.0/24',
      '91.78.238.0/24',
      '91.78.239.0/24'
  )
  AND access_result <> 'INVALIDATED';
