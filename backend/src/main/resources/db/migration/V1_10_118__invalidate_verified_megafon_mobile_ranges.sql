UPDATE worker_network_violation_episodes
SET access_result = 'INVALIDATED'
WHERE reason_code = 'NON_CELLULAR_NETWORK'
  AND provider = 'PJSC MegaFon'
  AND ip_prefix IN (
      '178.177.216.0/24',
      '178.177.217.0/24',
      '178.177.218.0/24',
      '178.177.219.0/24',
      '178.177.220.0/24',
      '178.177.221.0/24',
      '178.177.222.0/24',
      '178.177.223.0/24'
  )
  AND access_result <> 'INVALIDATED';
