UPDATE worker_network_violation_episodes
SET access_result = 'INVALIDATED'
WHERE reason_code = 'DESKTOP_OR_UNKNOWN_DEVICE'
  AND client_evidence LIKE 'client=capacitor;%'
  AND client_evidence LIKE '%;virtual=false;%'
  AND client_evidence LIKE '%;network=cellular;%';
