-- These observed mobile carrier ranges were confirmed on 2026-10-26.
-- Preserve the evidence while removing only the affected workers' false denials
-- from violation statistics. Other users, networks and VPN findings stay intact.
UPDATE worker_network_violation_episodes
SET access_result = 'INVALIDATED'
WHERE reason_code = 'NON_CELLULAR_NETWORK'
  AND access_result <> 'INVALIDATED'
  AND (
      (worker_username = 'tatu'
       AND provider = 'MTS PJSC'
       AND ip_prefix = '91.78.234.0/24')
      OR
      (worker_username = 'maks'
       AND provider = 'PJSC MegaFon'
       AND ip_prefix IN ('188.162.5.0/24', '188.162.12.0/24'))
  );
