package com.hunt.otziv.performers.maintenance;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Offline maintenance only: all mutation steps require locked application accounts and a drained DB. */
public final class PerformerLegacyMaintenance {
    private final Connection connection;
    private final JdbcTemplate jdbc;
    private final String writerUser;
    private final String writerHost;

    public PerformerLegacyMaintenance(Connection connection, String writerUser, String writerHost) {
        this.connection = connection;
        this.jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        this.writerUser = writerUser;
        this.writerHost = writerHost;
    }

    /** Checks real MySQL account lock and connections, not an operator boolean. */
    public void assertWriteFence() {
        String current = jdbc.queryForObject("SELECT CURRENT_USER()", String.class);
        if (writerUser == null || writerHost == null || current.equals(writerUser + "@" + writerHost))
            throw new IllegalStateException("Use a separate maintenance account and explicitly name the locked application account");
        var locked = jdbc.queryForList("SELECT account_locked FROM mysql.user WHERE user=? AND host=?", String.class, writerUser, writerHost);
        if (locked.size() != 1 || !"Y".equals(locked.getFirst())) throw new IllegalStateException("Application account is not locked");
        if (!jdbc.queryForObject("SELECT @@GLOBAL.read_only", Boolean.class))
            throw new IllegalStateException("Enable MySQL read_only; maintenance needs a separate privileged connection");
        long bypassAccounts=jdbc.queryForObject("""
                SELECT COUNT(*) FROM mysql.user u WHERE u.account_locked='N'
                  AND CONCAT(u.user,'@',u.host) <> CURRENT_USER()
                  AND (u.Super_priv='Y' OR EXISTS(SELECT 1 FROM mysql.global_grants g
                    WHERE g.USER=u.user AND g.HOST=u.host AND g.PRIV='CONNECTION_ADMIN'))
                """,Long.class);
        if(bypassAccounts!=0) throw new IllegalStateException("Other unlocked accounts can bypass the database write fence");
        long other = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.processlist
                WHERE ID <> CONNECTION_ID() AND (DB=DATABASE() OR USER=?)
                  AND USER NOT IN ('event_scheduler','system user')
                """, Long.class, writerUser);
        if (other != 0) throw new IllegalStateException("Database writers/connections have not drained");
        if (jdbc.queryForObject("SELECT @@GLOBAL.event_scheduler='ON'", Boolean.class))
            throw new IllegalStateException("Disable the MySQL event scheduler during maintenance");
    }

    public List<Map<String,Object>> activeOfferConflicts(int limit) {
        bounded(limit);
        return jdbc.queryForList("""
                SELECT assignment_id,COUNT(*) AS conflicting_offers FROM review_performer_offers
                WHERE status='OFFERED' GROUP BY assignment_id HAVING COUNT(*)>1
                ORDER BY assignment_id LIMIT ?
                """, limit);
    }

    /** Complete conflict inventory with a fixed assignment high-water mark; no PII or mutation. */
    public Batch inspectConflicts(long after,Long through,int limit) {
        bounded(limit);
        if(after<0||(through!=null&&through<after))throw new IllegalArgumentException("Invalid conflict cursor");
        long upper=through==null?jdbc.queryForObject("SELECT COALESCE(MAX(assignment_id),0) FROM review_performer_assignments",Long.class):through;
        var ids=jdbc.queryForList("SELECT assignment_id FROM review_performer_assignments WHERE assignment_id>? AND assignment_id<=? ORDER BY assignment_id LIMIT ?",Long.class,after,upper,limit);
        var rows=new ArrayList<Item>();
        for(long id:ids) {
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM review_performer_offers WHERE assignment_id=? AND status='OFFERED'",Long.class,id);
            if(count>1)rows.add(new Item(id,"ACTIVE_OFFER_CONFLICT:"+count));
        }
        long next=ids.isEmpty()?after:ids.getLast();
        boolean complete=jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM review_performer_assignments WHERE assignment_id>? AND assignment_id<=? LIMIT 1)",Boolean.class,next,upper);
        return new Batch("ACTIVE_OFFER_CONFLICTS",true,upper,next,complete,ids.size(),0,rows.size(),List.copyOf(rows));
    }

    public String begin(String actor) {
        assertWriteFence();
        if (actor == null || actor.isBlank() || actor.length()>255) throw new IllegalArgumentException("Maintenance actor required");
        if (!activeOfferConflicts(1).isEmpty()) throw new IllegalStateException("Resolve active offer conflicts before staging; use inspect-conflicts for IDs");
        if (column("review_performer_assignments", "readiness_intent_generation"))
            throw new IllegalStateException("Already upgraded; use bounded reconcile, not staging");
        createCheckpointTables();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO performer_legacy_maintenance_runs(run_id,phase,before_v288,assignment_upper,offer_upper,actor)
                SELECT ?,'STAGING',?,(SELECT COALESCE(MAX(assignment_id),0) FROM review_performer_assignments),
                (SELECT COALESCE(MAX(offer_id),0) FROM review_performer_offers),?
                """, id, !column("review_performer_assignments", "publication_generation"), actor);
        return id;
    }

    public Batch previewStage(String kind,long after,Long through,int limit) {
        bounded(limit);
        if(!List.of("ASSIGNMENT","OFFER").contains(kind)||after<0||(through!=null&&through<after)) throw new IllegalArgumentException("Invalid preview cursor/kind");
        boolean offer="OFFER".equals(kind), old=!column("review_performer_assignments","publication_generation");
        String table=offer?"review_performer_offers":"review_performer_assignments", pk=offer?"offer_id":"assignment_id";
        long upper=through==null?jdbc.queryForObject("SELECT COALESCE(MAX("+pk+"),0) FROM "+table,Long.class):through;
        String predicate=offer?(old?"status='OFFERED' OR telegram_message_id IS NOT NULL":"FALSE"):
                old?"status='WAITING_PUBLICATION'":column("review_performer_assignments","readiness_intent_generation")?"FALSE":"publication_generation>0";
        var rows=jdbc.query("SELECT "+pk+",("+predicate+") FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? ORDER BY "+pk+" LIMIT ?",
                (rs,n)->new Item(rs.getLong(1),rs.getBoolean(2)?"WOULD_STAGE":"UNCHANGED"),after,upper,limit);
        long next=rows.isEmpty()?after:rows.getLast().id();
        boolean done=jdbc.queryForObject("SELECT COUNT(*)=0 FROM (SELECT "+pk+" FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? LIMIT 1) remaining",Boolean.class,next,upper);
        return new Batch(kind,true,upper,next,done,rows.size(),0,0,List.copyOf(rows));
    }

    public Progress stage(String id, int limit) {
        bounded(limit); assertWriteFence();
        return transaction(() -> {
            var run = lockedRun(id);
            if (!"STAGING".equals(run.phase())) return progress(id);
            int scanned=0, changed=0;
            List<MaintenanceItem> report=new ArrayList<>();
            long ac=run.assignmentCursor(), oc=run.offerCursor();
            if (ac < run.assignmentUpper()) {
                String generation = run.beforeV288() ? "NULL" : "publication_generation";
                var rows = jdbc.query("SELECT assignment_id,status,"+generation+" FROM review_performer_assignments WHERE assignment_id>? AND assignment_id<=? ORDER BY assignment_id LIMIT ? FOR UPDATE",
                        (rs,n)->new Original("ASSIGNMENT",rs.getLong(1),rs.getString(2),null,(Long)rs.getObject(3)),ac,run.assignmentUpper(),limit);
                for (var row:rows) {
                    scanned++; ac=row.id();
                    boolean needsStage = run.beforeV288() ? "WAITING_PUBLICATION".equals(row.status()) : row.generation()!=null && row.generation()>0;
                    if (!needsStage) {report.add(new MaintenanceItem(row.type(),row.id(),"UNCHANGED"));continue;}
                    saveOriginal(id,row);
                    if (run.beforeV288()) jdbc.update("UPDATE review_performer_assignments SET status='MAINTENANCE_WAITING' WHERE assignment_id=?",row.id());
                    else jdbc.update("UPDATE review_performer_assignments SET publication_generation=0 WHERE assignment_id=?",row.id());
                    changed++;report.add(new MaintenanceItem(row.type(),row.id(),"STAGED"));
                }
                if (rows.size()<limit) ac=run.assignmentUpper();
            } else if (oc < run.offerUpper()) {
                var rows = jdbc.query("SELECT offer_id,status,telegram_message_id FROM review_performer_offers WHERE offer_id>? AND offer_id<=? ORDER BY offer_id LIMIT ? FOR UPDATE",
                        (rs,n)->new Original("OFFER",rs.getLong(1),rs.getString(2),(Integer)rs.getObject(3),null),oc,run.offerUpper(),limit);
                for (var row:rows) {
                    scanned++; oc=row.id();
                    if (!run.beforeV288() || (!"OFFERED".equals(row.status()) && row.messageId()==null)) {report.add(new MaintenanceItem(row.type(),row.id(),"UNCHANGED"));continue;}
                    saveOriginal(id,row);
                    jdbc.update("UPDATE review_performer_offers SET status=CASE WHEN status='OFFERED' THEN 'MAINTENANCE_OFFER' ELSE status END,telegram_message_id=NULL WHERE offer_id=?",row.id());
                    changed++;report.add(new MaintenanceItem(row.type(),row.id(),"STAGED"));
                }
                if (rows.size()<limit) oc=run.offerUpper();
            }
            jdbc.update("UPDATE performer_legacy_maintenance_runs SET assignment_cursor=?,offer_cursor=?,scanned=scanned+?,changed=changed+?,phase=? WHERE run_id=?",
                    ac,oc,scanned,changed,ac>=run.assignmentUpper()&&oc>=run.offerUpper()?"MIGRATE_READY":"STAGING",id);
            return progress(id).withStep(scanned,changed,report);
        });
    }

    /** Call only after ordinary Flyway migrate/validate through V300 succeeds. */
    public void migrationFinished(String id) {
        assertWriteFence();
        if (!column("review_performer_assignments","readiness_intent_generation")) throw new IllegalStateException("V300 is not applied");
        jdbc.update("UPDATE performer_legacy_maintenance_runs SET phase='RESTORING' WHERE run_id=? AND phase='MIGRATE_READY'",id);
    }

    public Progress restore(String id, int limit, boolean abortBeforeMigration) {
        bounded(limit); assertWriteFence();
        return transaction(() -> {
            var run=lockedRun(id);
            if (abortBeforeMigration) {
                if (column("review_performer_assignments","readiness_intent_generation") || (run.beforeV288() && column("review_performer_assignments","publication_generation")))
                    throw new IllegalStateException("Migrations have started; finish ordinary Flyway validation/migration and restore instead");
            } else if (!"RESTORING".equals(run.phase())) throw new IllegalStateException("Migration has not been confirmed");
            var rows=jdbc.query("SELECT entity_type,entity_id,original_status,original_message_id,original_generation FROM performer_legacy_maintenance_rows WHERE run_id=? AND restored=FALSE ORDER BY entity_type,entity_id LIMIT ? FOR UPDATE",
                    (rs,n)->new Original(rs.getString(1),rs.getLong(2),rs.getString(3),(Integer)rs.getObject(4),(Long)rs.getObject(5)),id,limit);
            for(var row:rows) {
                if ("ASSIGNMENT".equals(row.type())) {
                    int changed;
                    if(run.beforeV288()) changed=jdbc.update("UPDATE review_performer_assignments SET status=? WHERE assignment_id=? AND status='MAINTENANCE_WAITING'",row.status(),row.id());
                    else changed=jdbc.update("UPDATE review_performer_assignments SET publication_generation=? WHERE assignment_id=? AND publication_generation=0 AND status <=> ?",row.generation(),row.id(),row.status());
                    if(changed!=1) throw new IllegalStateException("Staged assignment changed: "+row.id());
                    if(!abortBeforeMigration) { readyHistory(row.id()); repairMarker(row.id()); }
                } else {
                    int changed=jdbc.update("UPDATE review_performer_offers SET status=?,telegram_message_id=? WHERE offer_id=? AND telegram_message_id IS NULL AND status <=> ?",
                            row.status(),row.messageId(),row.id(),"OFFERED".equals(row.status())?"MAINTENANCE_OFFER":row.status());
                    if(changed!=1) throw new IllegalStateException("Staged offer changed: "+row.id());
                    if(!abortBeforeMigration) {
                        if(row.messageId()!=null) jdbc.update("UPDATE review_performer_offers SET delivery_state='LEGACY_CONFIRMED' WHERE offer_id=?",row.id());
                        offerHistory(row.id());
                    }
                }
                jdbc.update("UPDATE performer_legacy_maintenance_rows SET restored=TRUE WHERE run_id=? AND entity_type=? AND entity_id=?",id,row.type(),row.id());
            }
            boolean done=jdbc.queryForObject("SELECT COUNT(*) FROM performer_legacy_maintenance_rows WHERE run_id=? AND restored=FALSE",Long.class,id)==0;
            if(done) jdbc.update("UPDATE performer_legacy_maintenance_runs SET phase=? WHERE run_id=?",abortBeforeMigration?"ABORTED":"COMPLETE",id);
            return progress(id).withStep(rows.size(),rows.size(),rows.stream()
                    .map(row->new MaintenanceItem(row.type(),row.id(),abortBeforeMigration?"RESTORED_ABORT":"RESTORED")).toList());
        });
    }

    /** Cursor is the pause/resume checkpoint; dry-run never writes even maintenance tables. */
    public Batch reconcile(String kind, boolean dryRun, long after, Long through, int limit) {
        bounded(limit);
        if(after<0 || (through!=null && through<after)) throw new IllegalArgumentException("Invalid cursor");
        if(!column("review_performer_assignments","readiness_intent_generation")) throw new IllegalStateException("Apply V300 before reconciliation");
        if(!List.of("READY_HISTORY","OFFER_HISTORY","MARKER").contains(kind)) throw new IllegalArgumentException("Unknown reconciliation kind");
        if(!dryRun) assertWriteFence();
        String table="OFFER_HISTORY".equals(kind)?"review_performer_offers":"review_performer_assignments";
        String pk="OFFER_HISTORY".equals(kind)?"offer_id":"assignment_id";
        long upper=through==null?jdbc.queryForObject("SELECT COALESCE(MAX("+pk+"),0) FROM "+table,Long.class):through;
        var ids=jdbc.queryForList("SELECT "+pk+" FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? ORDER BY "+pk+" LIMIT ?",Long.class,after,upper,limit);
        List<Item> results=new ArrayList<>();
        int changed=0;
        for(long entityId:ids) {
            int eligible=eligible(kind,entityId);
            int applied=0;
            if(!dryRun && eligible>0) applied=transaction(()-> switch(kind) {case "READY_HISTORY"->readyHistory(entityId);case "OFFER_HISTORY"->offerHistory(entityId);default->repairMarker(entityId);});
            changed+=applied;
            results.add(new Item(entityId,eligible==0?"UNCHANGED":dryRun?"WOULD_CHANGE":applied>0?"CHANGED":"CONFLICT"));
        }
        long next=ids.isEmpty()?after:ids.getLast();
        boolean complete=jdbc.queryForObject("SELECT COUNT(*)=0 FROM (SELECT "+pk+" FROM "+table+" WHERE "+pk+">? AND "+pk+"<=? LIMIT 1) remaining",Boolean.class,next,upper);
        return new Batch(kind,dryRun,upper,next,complete,ids.size(),changed,(int)results.stream().filter(r->r.outcome().equals("CONFLICT")).count(),List.copyOf(results));
    }

    private int eligible(String kind,long id) {
        String sql=switch(kind) {
            case "READY_HISTORY"->"SELECT COUNT(*) FROM review_performer_assignments a WHERE assignment_id=? AND status='WAITING_PUBLICATION' AND publication_generation=0 AND NOT EXISTS(SELECT 1 FROM performer_notification_intents n WHERE n.operation_key=CONCAT('READY:',a.assignment_id,':0'))";
            case "OFFER_HISTORY"->"SELECT COUNT(*) FROM review_performer_offers o WHERE offer_id=? AND status='OFFERED' AND NOT EXISTS(SELECT 1 FROM performer_notification_intents n WHERE n.operation_key=CONCAT('OFFER:',o.offer_id,':0'))";
            default->"SELECT COUNT(*) FROM review_performer_assignments a JOIN performer_notification_intents n ON n.operation_key=CONCAT('READY:',a.assignment_id,':',a.publication_generation) AND n.assignment_id=a.assignment_id AND n.notification_type='READY' AND n.offer_id IS NULL AND n.generation=a.publication_generation WHERE a.assignment_id=? AND a.publication_generation>a.readiness_intent_generation";
        };
        return jdbc.queryForObject(sql,Integer.class,id);
    }

    private int readyHistory(long id) {
        return jdbc.update("""
                INSERT IGNORE INTO performer_notification_intents(operation_key,assignment_id,notification_type,generation,status,due_at,outcome_code)
                SELECT CONCAT('READY:',assignment_id,':0'),assignment_id,'READY',0,'LEGACY_UNKNOWN',COALESCE(publish_available_at,CURRENT_TIMESTAMP(6)),'legacy_delivery_unverified'
                FROM review_performer_assignments WHERE assignment_id=? AND status='WAITING_PUBLICATION' AND publication_generation=0
                """,id);
    }

    private int offerHistory(long id) {
        return jdbc.update("""
                INSERT IGNORE INTO performer_notification_intents(operation_key,assignment_id,offer_id,notification_type,status,due_at,telegram_message_id,outcome_code)
                SELECT CONCAT('OFFER:',offer_id,':0'),assignment_id,offer_id,'OFFER',CASE WHEN telegram_message_id IS NULL THEN 'LEGACY_UNKNOWN' ELSE 'SENT' END,
                offered_at,telegram_message_id,'legacy_delivery_evidence' FROM review_performer_offers WHERE offer_id=? AND status='OFFERED'
                """,id);
    }

    private int repairMarker(long id) {
        return jdbc.update("""
                UPDATE review_performer_assignments a JOIN performer_notification_intents n
                ON n.operation_key=CONCAT('READY:',a.assignment_id,':',a.publication_generation)
                AND n.assignment_id=a.assignment_id AND n.notification_type='READY' AND n.offer_id IS NULL AND n.generation=a.publication_generation
                SET a.readiness_intent_generation=a.publication_generation WHERE a.assignment_id=? AND a.publication_generation>a.readiness_intent_generation
                """,id);
    }

    private void saveOriginal(String run,Original row) {
        jdbc.update("INSERT INTO performer_legacy_maintenance_rows(run_id,entity_type,entity_id,original_status,original_message_id,original_generation) VALUES(?,?,?,?,?,?)",
                run,row.type(),row.id(),row.status(),row.messageId(),row.generation());
    }

    private Run lockedRun(String id) {
        return jdbc.queryForObject("SELECT phase,before_v288,assignment_upper,offer_upper,assignment_cursor,offer_cursor FROM performer_legacy_maintenance_runs WHERE run_id=? FOR UPDATE",
                (rs,n)->new Run(rs.getString(1),rs.getBoolean(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6)),id);
    }

    public Progress progress(String id) {
        return jdbc.queryForObject("SELECT phase,assignment_cursor,offer_cursor,scanned,changed FROM performer_legacy_maintenance_runs WHERE run_id=?",
                (rs,n)->new Progress(id,rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),0,0,0,List.of()),id);
    }

    public boolean column(String table,String name) {
        return jdbc.queryForObject("SELECT COUNT(*)>0 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",Boolean.class,table,name);
    }

    private void createCheckpointTables() {
        // Same immutable resource used by Flyway later; IF NOT EXISTS has no history side effects.
        try {
            var resource=new org.springframework.core.io.ClassPathResource("db/migration/V1_10_302__maintenance_checkpoints_and_legacy_send_operations.sql");
            String prefix=resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("-- END_PERFORMER_MAINTENANCE_SCHEMA",2)[0];
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.io.IOException error) {throw new IllegalStateException("Maintenance schema resource unavailable",error);}
    }

    private <T>T transaction(java.util.function.Supplier<T> action) {
        try {
            if(!connection.getAutoCommit()) throw new IllegalStateException("Maintenance owns its transaction");
            connection.setAutoCommit(false);
            try { T value=action.get();connection.commit();return value; }
            catch(RuntimeException failure) {connection.rollback();throw failure;}
            finally {connection.setAutoCommit(true);}
        } catch(SQLException error) {throw new IllegalStateException("Maintenance transaction failed",error);}
    }

    private static void bounded(int limit) {if(limit<1||limit>500)throw new IllegalArgumentException("limit must be 1..500");}
    private record Original(String type,long id,String status,Integer messageId,Long generation) {}
    private record Run(String phase,boolean beforeV288,long assignmentUpper,long offerUpper,long assignmentCursor,long offerCursor) {}
    public record Progress(String runId,String phase,long assignmentCursor,long offerCursor,long scanned,long changed,
            int stepScanned,int stepChanged,int stepConflicts,List<MaintenanceItem> rows) {
        Progress withStep(int scanned,int changed,List<MaintenanceItem> rows) {
            return new Progress(runId,phase,assignmentCursor,offerCursor,this.scanned,this.changed,scanned,changed,0,List.copyOf(rows));
        }
    }
    public record MaintenanceItem(String entityType,long id,String outcome) {}
    public record Item(long id,String outcome) {}
    public record Batch(String kind,boolean dryRun,long throughId,long nextAfterId,boolean complete,int scanned,int changed,int conflicts,List<Item> rows) {}
}
