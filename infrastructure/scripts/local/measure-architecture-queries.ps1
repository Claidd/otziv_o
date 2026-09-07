[CmdletBinding()]
param([string]$Container = 'otziv-prod-local-mysql-1')

$ErrorActionPreference = 'Stop'
# This diagnostic reads only the sanitized local database restored by prod-like-smoke.
$endpoint = (& docker context inspect --format '{{.Endpoints.docker.Host}}') -join ''
if ($LASTEXITCODE -ne 0 -or $endpoint -notmatch '^(npipe:|unix:)') {
    throw 'A local Docker context is required.'
}
if ($env:DOCKER_HOST -and $env:DOCKER_HOST -notmatch '^(npipe:|unix:)') {
    throw 'Remote DOCKER_HOST is not permitted for this local diagnostic.'
}
$metadata = (& docker inspect $Container | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0 -or $metadata.Count -ne 1 -or
    $metadata[0].Config.Labels.'com.docker.compose.project' -ne 'otziv-prod-local' -or
    $metadata[0].Config.Labels.'com.docker.compose.service' -ne 'mysql') {
    throw 'Expected the MySQL service of the otziv-prod-local Compose project.'
}

# EXPLAIN ANALYZE emits plan, cardinality and timing, never customer rows or payloads.
# Lock acquisition and external provider capacity require separate concurrency tests.
$sql = @'
SET SESSION max_execution_time=10000;
SET TRANSACTION READ ONLY;
START TRANSACTION;
SELECT 'lead_queue_rows' AS metric, COUNT(*) AS value FROM lead_command_queue
UNION ALL SELECT 'performer_intent_rows', COUNT(*) FROM performer_notification_intents
UNION ALL SELECT 'performer_assignment_rows', COUNT(*) FROM review_performer_assignments
UNION ALL SELECT 'active_offer_duplicate_groups', COUNT(*) FROM (
  SELECT assignment_id FROM review_performer_offers WHERE status='OFFERED'
  GROUP BY assignment_id HAVING COUNT(*) > 1
) duplicate_groups;
SELECT 'lead_claim_candidate_plan' AS diagnostic;
EXPLAIN ANALYZE
SELECT q.id FROM lead_command_queue q
WHERE q.delivery_state='READY' AND q.retry_count<5 AND q.next_attempt_at<=UTC_TIMESTAMP(6)
AND NOT EXISTS (SELECT 1 FROM lead_command_queue earlier
 WHERE earlier.blocking_lead_id=q.lead_id AND earlier.id<q.id)
ORDER BY q.next_attempt_at,q.id LIMIT 1;
SELECT 'performer_claim_candidate_plan' AS diagnostic;
EXPLAIN ANALYZE SELECT notification_id FROM performer_notification_intents
WHERE status='PENDING' AND due_at<=CURRENT_TIMESTAMP(6)
ORDER BY due_at,notification_id LIMIT 1;
SELECT 'performer_readiness_plan' AS diagnostic;
EXPLAIN ANALYZE SELECT a.assignment_id FROM review_performer_assignments a
WHERE a.status='WAITING_PUBLICATION' AND a.ready_notification_pending=1
AND a.publication_generation>0
AND a.publish_available_at<=CURRENT_TIMESTAMP(6)
AND NOT EXISTS (SELECT 1 FROM performer_notification_intents n
 WHERE n.operation_key=CONCAT('READY:',a.assignment_id,':',a.publication_generation))
ORDER BY a.publish_available_at,a.assignment_id LIMIT 100;
SELECT 'lead_metric_snapshot_plan' AS diagnostic;
EXPLAIN ANALYZE SELECT delivery_state,COUNT(*),
GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),UTC_TIMESTAMP(6)))
FROM lead_command_queue GROUP BY delivery_state;
ROLLBACK;
'@
$sql | & docker exec -i $Container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --raw --user=root "$MYSQL_DATABASE"'
if ($LASTEXITCODE -ne 0) { throw 'Local query measurement failed.' }
