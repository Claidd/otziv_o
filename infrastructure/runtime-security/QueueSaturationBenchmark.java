import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

/** Manual opt-in repository benchmark. Never discovered by JUnit/Surefire. No provider transport. */
public class QueueSaturationBenchmark {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String MYSQL="mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";
    static final ThreadLocal<String> PHASE=ThreadLocal.withInitial(()->"setup");
    static final Map<String,SqlMeasure> SQL=new ConcurrentHashMap<>();
    static final Samples CONNECTIONS=new Samples();
    static final Map<String,AtomicLong> ROLLBACK_RETRIES=new ConcurrentHashMap<>();
    static HikariDataSource pool;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static DataSourceTransactionManager manager;
    static LeadCommandRepository leads;
    static PerformerNotificationRepository performers;
    static Path output,root;
    static int initial,arrivals,rate,providerDelay,history;
    static String source;

    public static void main(String[] args) throws Exception {
        root=Path.of(args[0]);output=Path.of(args[1]);boolean quick=args.length>2&&args[2].equals("quick");
        initial=quick?40:400;arrivals=quick?100:1200;rate=200;providerDelay=quick?5:20;history=quick?1000:20000;
        var results=new LinkedHashMap<String,Object>();results.put("schema","otziv-queue-saturation-v1");
        results.put("startedAt",Instant.now().toString());results.put("mysqlImage",MYSQL);
        results.put("workload",Map.of("initial",initial,"arrivals",arrivals,"admissionPerSecond",rate,"providerDelayMs",providerDelay,"terminalHistoryPerQueue",history,"leadScopes",256,"seed",20260907));
        results.put("limits",List.of("Actual repository/JDBC/Spring transactions; not whole business workflows or production SLO",
                "Provider is a bounded local delay outside transactions; no HTTP or real sends",
                "No heartbeat exists in these repositories; performer owns() is a probe, never a lease extension",
                "Expired lease injection is explicit fixture SQL; production fencing/recovery is exercised afterwards",
                "Assignment/offer fixture omits unrelated business fields/FKs; production queue migrations/indexes are applied",
                "Confirmed MySQL1213/40001 transaction rollback is retried by the benchmark driver with 20ms backoff (max100); no provider action is repeated, and this is not the production scheduler retry policy",
                "Shared host can have concurrent workloads; timings are descriptive"));
        results.put("resources",Map.of("mysqlMemoryBytes",805306368,"mysqlCpus",2,"mysqlBufferPoolBytes",134217728,"hikariMax",4,"jvmMaxHeapBytes",Runtime.getRuntime().maxMemory()));
        var scenarios=new ArrayList<Map<String,Object>>();results.put("scenarios",scenarios);
        try(var mysql=new MySQLContainer(MYSQL).withDatabaseName("queue_saturation").withUsername("root")
                .withPassword(UUID.randomUUID().toString()).withCommand("--innodb-buffer-pool-size=134217728","--max-connections=24")
                .withCreateContainerCmdModifier(command->command.getHostConfig().withMemory(805306368L).withNanoCPUs(2_000_000_000L))) {
            mysql.start();results.put("ownedContainer",mysql.getContainerId());
            var configuration=new HikariConfig();configuration.setJdbcUrl(mysql.getJdbcUrl());configuration.setUsername(mysql.getUsername());
            configuration.setPassword(mysql.getPassword());configuration.setMaximumPoolSize(4);configuration.setMinimumIdle(4);configuration.setConnectionTimeout(10000);configuration.addDataSourceProperty("rewriteBatchedStatements","true");configuration.addDataSourceProperty("socketTimeout","15000");
            try(var hikari=new HikariDataSource(configuration)) {
                pool=hikari;var tracked=new TrackedDataSource(hikari);jdbc=new JdbcTemplate(tracked);manager=new DataSourceTransactionManager(tracked);tx=new TransactionTemplate(manager);tx.setTimeout(10);
                leads=proxy(new LeadCommandRepository(jdbc));performers=proxy(new PerformerNotificationRepository(jdbc));
                schema(tracked);source=leads.sourceIdentity();results.put("mysqlVersion",jdbc.queryForObject("SELECT VERSION()",String.class));
                // Warm actual claim/ack code before timed scenarios, then discard only this owned fixture.
                seed("lead",10,0);while(consume("lead",new Counters())){}
                seed("performer",10,0);while(consume("performer",new Counters())){}
                for(String domain:List.of("lead","performer"))for(int concurrency:new int[]{1,4,8}) {
                    var scenario=scenario(domain,concurrency,mysql);scenarios.add(scenario);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(domain+"-c"+concurrency+".json").toFile(),scenario);
                    System.out.println(JSON.writeValueAsString(Map.of("domain",domain,"concurrency",concurrency,"result",scenario.get("result"),"terminal",scenario.get("terminalCounts"),"saturatedWindow",scenario.get("saturatedWindow"))));
                    JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(),results);
                }
            }
            results.put("result","PASS");
        } catch(Throwable failure) {
            results.put("result","FAIL");results.put("error",failure.getClass().getName()+": "+failure.getMessage());throw failure;
        } finally {
            results.put("finishedAt",Instant.now().toString());results.put("cleanup","OWNED_TESTCONTAINERS_CLOSE_REQUESTED");
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(),results);
        }
    }

    static Map<String,Object> scenario(String domain,int concurrency,MySQLContainer mysql) throws Exception {
        PHASE.set("seed");seed(domain,initial,history);SQL.clear();CONNECTIONS.clear();ROLLBACK_RETRIES.clear();
        var counters=new Counters();var admitted=new AtomicLong(initial);var stop=new AtomicBoolean();var failures=new ConcurrentLinkedQueue<Throwable>();
        var monitor=new ArrayList<Map<String,Object>>();var workerTimes=new Samples();var insertTimes=new Samples();
        var started=System.nanoTime();var before=serverStatus();var monitorStop=new AtomicBoolean();
        var monitoring=Thread.ofPlatform().start(()->{
            PHASE.set("monitor");while(!monitorStop.get()) {
                var bean=pool.getHikariPoolMXBean();monitor.add(Map.of("elapsedMs",elapsed(started),"completed",counters.handled.get(),
                    "admitted",admitted.get(),"unfinished",admitted.get()-counters.handled.get(),"activeConnections",bean.getActiveConnections(),"idleConnections",bean.getIdleConnections(),"awaitingConnection",bean.getThreadsAwaitingConnection(),"poolTotal",bean.getTotalConnections()));
                LockSupport.parkNanos(50_000_000);
            }
        });
        var workers=Executors.newFixedThreadPool(concurrency);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<concurrency;i++)futures.add(workers.submit(()->{
                try {while(!stop.get()) {long at=System.nanoTime();if(consume(domain,counters))workerTimes.add(System.nanoTime()-at);else LockSupport.parkNanos(2_000_000);}}
                catch(Throwable failure){failures.add(failure);stop.set(true);}
            }));
            long admittedAt=System.nanoTime(),maxLag=0;
            PHASE.set("producer");
            for(int n=0;n<arrivals;n++) {
                if(!failures.isEmpty())throw new IllegalStateException("Consumer failed",failures.peek());
                long due=admittedAt+n*1_000_000_000L/rate;LockSupport.parkNanos(Math.max(0,due-System.nanoTime()));
                maxLag=Math.max(maxLag,System.nanoTime()-due);long at=System.nanoTime();enqueue(domain,initial+n);admitted.incrementAndGet();insertTimes.add(System.nanoTime()-at);
            }
            long admittedEnd=System.nanoTime(),handledAtEnd=counters.handled.get();
            PHASE.set("validation");var windowCounts=states(domain);long expectedHandled=expected(domain,initial+arrivals).get("attempted");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);
            while(counters.handled.get()<expectedHandled&&failures.isEmpty()&&System.nanoTime()<deadline)LockSupport.parkNanos(20_000_000);
            stop.set(true);for(var future:futures)future.get(15,TimeUnit.SECONDS);
            require(failures.isEmpty(),"repository_worker_failed: "+(failures.peek()==null?"":failures.peek().getClass().getName()));
            require(counters.handled.get()==expectedHandled,"drain_deadline_or_count_mismatch");
            long drained=System.nanoTime();monitorStop.set(true);monitoring.join();
            var beforeReplay=states(domain);long attemptSum=attemptSum(domain);
            for(int i=0;i<32;i++)require(!consume(domain,counters),"unknown_or_fifo_blocked_command_replayed");
            require(beforeReplay.equals(states(domain))&&attemptSum==attemptSum(domain),"unresolved_state_changed_during_empty_claims");
            validate(domain,initial+arrivals,counters);
            var result=new LinkedHashMap<String,Object>();result.put("domain",domain);result.put("concurrency",concurrency);result.put("result","PASS");
            result.put("totalRows",initial+arrivals);result.put("historyRows",history);result.put("expected",expected(domain,initial+arrivals));
            result.put("terminalCounts",states(domain));result.put("attemptSum",attemptSum(domain));
            result.put("saturatedWindow",Map.of("durationMs",(admittedEnd-admittedAt)/1e6,"requestedAdmissionPerSecond",rate,"actualAdmissionPerSecond",arrivals*1e9/(admittedEnd-admittedAt),
                    "handled",handledAtEnd,"statesAtAdmissionEnd",windowCounts,"maxAdmissionLagMs",maxLag/1e6));
            result.put("drainDurationMs",(drained-admittedEnd)/1e6);result.put("handledPerSecond",expectedHandled*1e9/(drained-started));
            result.put("claimLatency",counters.claim.summary());result.put("ackLatency",counters.ack.summary());result.put("ownershipProbeLatency",counters.owns.summary());
            result.put("workerCycleLatency",workerTimes.summary());result.put("producerLatency",insertTimes.summary());result.put("connectionAcquisitionLatency",CONNECTIONS.summary());
            result.put("confirmedRollbackRetries",ROLLBACK_RETRIES.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->e.getValue().get())));
            result.put("peakUnfinished",monitor.stream().mapToLong(sample->((Number)sample.get("unfinished")).longValue()).max().orElse(0));
            result.put("peakPoolActive",monitor.stream().mapToInt(sample->((Number)sample.get("activeConnections")).intValue()).max().orElse(0));
            result.put("peakPoolAwaiting",monitor.stream().mapToInt(sample->((Number)sample.get("awaitingConnection")).intValue()).max().orElse(0));
            result.put("fencedLateAcks",counters.fenced.get());result.put("emptyClaims",counters.empty.get());result.put("unknownUnreplayedProbeCount",32);
            result.put("poolSamples",monitor);result.put("serverStatusBefore",before);result.put("serverStatusAfter",serverStatus());
            result.put("dockerStatsAfter",dockerStats(mysql.getContainerId()));result.put("sql",SQL.values().stream().map(SqlMeasure::summary).sorted(Comparator.comparing(m->m.get("phase")+":"+m.get("sha256"))).toList());
            return result;
        } catch(Throwable failure) {
            stop.set(true);
            var failed=new LinkedHashMap<String,Object>();failed.put("result","FAIL");failed.put("domain",domain);failed.put("concurrency",concurrency);
            failed.put("errorClass",failure.getClass().getName());failed.put("handled",counters.handled.get());failed.put("admitted",admitted.get());
            failed.put("sql",SQL.values().stream().map(SqlMeasure::summary).toList());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(domain+"-c"+concurrency+".json").toFile(),failed);
            throw failure;
        } finally {stop.set(true);workers.shutdown();if(!workers.awaitTermination(15,TimeUnit.SECONDS))workers.shutdownNow();monitorStop.set(true);monitoring.join(2000);}
    }

    static boolean consume(String domain,Counters count) {
        PHASE.set("worker-claim");long at=System.nanoTime();Object claim=retryRollback(()->domain.equals("lead")?leads.claim(5).orElse(null):tx.execute(s->performers.claim(120).orElse(null)));
        count.claim.add(System.nanoTime()-at);if(claim==null){count.empty.incrementAndGet();return false;}
        int sequence;
        if(claim instanceof LeadCommandRepository.Claim item)sequence=Integer.parseInt(item.json());
        else {var item=(PerformerNotificationRepository.Intent)claim;sequence=Math.toIntExact(item.assignmentId()-1);PHASE.set("worker-owns");at=System.nanoTime();require(performers.owns(item),"claim_ownership_lost_before_provider");count.owns.add(System.nanoTime()-at);}
        require(count.seen.add(sequence),"same_operation_claimed_twice");
        require(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"provider_delay_inside_transaction");
        LockSupport.parkNanos(providerDelay*1_000_000L);int outcome=sequence%20;PHASE.set("worker-ack");at=System.nanoTime();
        if(claim instanceof LeadCommandRepository.Claim item) {
            if(outcome==7) {
                PHASE.set("fixture-expiry");jdbc.update("UPDATE lead_command_queue SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",item.id());PHASE.set("worker-ack");
                require(!retryRollback(()->leads.complete(item,"SUCCEEDED",null,0)),"expired_lead_ack_was_accepted");count.fenced.incrementAndGet();
                PHASE.set("recovery");retryRollback(()->leads.recoverExpired(5));
            } else require(retryRollback(()->leads.complete(item,outcome==3?"UNKNOWN":outcome==11?"DEAD":"SUCCEEDED",outcome==3?"SIMULATED_TIMEOUT":outcome==11?"SIMULATED_REJECTION":null,0)),"lead_ack_lost");
        } else {
            var item=(PerformerNotificationRepository.Intent)claim;
            if(outcome==7) {
                PHASE.set("fixture-expiry");jdbc.update("UPDATE performer_notification_intents SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE notification_id=?",item.id());PHASE.set("worker-ack");
                require(!retryRollback(()->tx.execute(s->performers.finish(item,"SENT",1,null))),"expired_performer_ack_was_accepted");count.fenced.incrementAndGet();
                PHASE.set("recovery");retryRollback(()->tx.execute(s->performers.expireClaims(100)));
            } else require(retryRollback(()->tx.execute(s->performers.finish(item,outcome==3?"UNKNOWN":outcome==11?"BLOCKED":"SENT",outcome==3||outcome==11?null:sequence+1,"SIMULATED_PROVIDER"))),"performer_ack_lost");
        }
        count.ack.add(System.nanoTime()-at);count.handled.incrementAndGet();return true;
    }

    static void enqueue(String domain,int sequence) {
        retryRollback(()->tx.execute(status->{
            if(domain.equals("lead")) {
                long leadId=sequence%256+1;var stream=leads.lockStream(leadId);
                leads.enqueueVersioned(leadId,"fixture","SYNC",Integer.toString(sequence),stream,UUID.nameUUIDFromBytes(("seed20260907:"+sequence).getBytes(StandardCharsets.UTF_8)).toString(),sha(Integer.toString(sequence)));
            } else {
                long id=sequence+1;jdbc.update("INSERT INTO review_performer_assignments(assignment_id,status,publish_available_at,publication_generation) VALUES(?,'WAITING_PUBLICATION',CURRENT_TIMESTAMP(6),1)",id);
                require(performers.enqueue(id,null,"READY",1,performers.now().minusSeconds(1)),"fixture_duplicate_enqueue");
            }
            return null;
        }));
    }
    static void seed(String domain,int rows,int historyCount) {
        jdbc.execute("TRUNCATE TABLE lead_command_queue");jdbc.execute("TRUNCATE TABLE lead_command_stream");
        jdbc.execute("TRUNCATE TABLE performer_notification_intents");jdbc.execute("TRUNCATE TABLE review_performer_assignments");
        var batch=new ArrayList<Object[]>();
        for(int i=0;i<historyCount;i++)batch.add(new Object[]{i%256+1,"history",Integer.toString(-i-1),UUID.nameUUIDFromBytes(("history:"+i).getBytes(StandardCharsets.UTF_8)).toString()});
        jdbc.batchUpdate("INSERT INTO lead_command_queue(lead_id,telephone_lead,payload_json,command_id,delivery_state,completed_at,next_attempt_at,created_at) VALUES(?,?,?,?,'SUCCEEDED',UTC_TIMESTAMP(6),'2020-01-01','2020-01-01')",batch);
        batch.clear();for(int i=0;i<historyCount;i++)batch.add(new Object[]{"history:"+i,i+1000000});
        jdbc.batchUpdate("INSERT INTO performer_notification_intents(operation_key,assignment_id,notification_type,status,due_at) VALUES(?,?,'READY','SENT','2020-01-01')",batch);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM lead_command_queue",Long.class)==historyCount,"lead_history_fixture_count_mismatch");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM performer_notification_intents",Long.class)==historyCount,"performer_history_fixture_count_mismatch");
        for(int n=0;n<rows;n++)enqueue(domain,n);
    }
    static Map<String,Long> expected(String domain,int total) {
        var expected=new TreeMap<String,Long>();var blocked=new HashSet<Integer>();long attempts=0,expired=0;
        for(int i=0;i<total;i++) {
            String status;if(domain.equals("lead")&&blocked.contains(i%256))status="READY";
            else {attempts++;status=i%20==3||i%20==7?"UNKNOWN":i%20==11?(domain.equals("lead")?"DEAD":"BLOCKED"):(domain.equals("lead")?"SUCCEEDED":"SENT");
                if(i%20==7)expired++;if(domain.equals("lead")&&!status.equals("SUCCEEDED"))blocked.add(i%256);}
            expected.merge(status,1L,Long::sum);
        }
        expected.put("attempted",attempts);expected.put("fenced",expired);return expected;
    }
    static void validate(String domain,int total,Counters count) {
        var expected=expected(domain,total);require(count.fenced.get()==expected.remove("fenced"),"fence_total_mismatch");
        require(attemptSum(domain)==expected.remove("attempted"),"attempt_total_mismatch");require(states(domain).equals(expected),"terminal_sql_counts_mismatch");
        require(count.seen.size()==count.handled.get(),"claim_ack_causality_mismatch");
    }
    static Map<String,Long> states(String domain) {
        String table=domain.equals("lead")?"lead_command_queue":"performer_notification_intents",state=domain.equals("lead")?"delivery_state":"status";
        String filter=domain.equals("lead")?"payload_json NOT LIKE '-%'":"operation_key NOT LIKE 'history:%'";
        var values=new TreeMap<String,Long>();jdbc.query("SELECT "+state+",COUNT(*) FROM "+table+" WHERE "+filter+" GROUP BY "+state,rs->{values.put(rs.getString(1),rs.getLong(2));});return values;
    }
    static long attemptSum(String domain) {return jdbc.queryForObject(domain.equals("lead")?"SELECT COALESCE(SUM(retry_count),0) FROM lead_command_queue":"SELECT COALESCE(SUM(attempts),0) FROM performer_notification_intents",Long.class);}
    static List<Map<String,Object>> serverStatus(){return jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Max_used_connections','Innodb_row_lock_waits','Innodb_row_lock_time','Queries','Slow_queries')");}
    static String dockerStats(String id) throws Exception {
        var process=new ProcessBuilder("docker","stats","--no-stream","--format","{{json .}}",id).redirectErrorStream(true).start();
        require(process.waitFor(10,TimeUnit.SECONDS),"docker_stats_timeout");return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
    }
    static void schema(javax.sql.DataSource ds) {
        jdbc.execute("CREATE TABLE review_performer_assignments(assignment_id BIGINT PRIMARY KEY,status VARCHAR(32),publish_available_at DATETIME(6))");
        jdbc.execute("CREATE TABLE review_performer_offers(offer_id BIGINT PRIMARY KEY,assignment_id BIGINT,status VARCHAR(32),offered_at DATETIME(6),telegram_message_id INT)");
        var migration=new ResourceDatabasePopulator();for(String file:List.of("V1_2_9__lead_sync_queue.sql","V1_2_91__lead_sync_queue.sql","V1_2_92__lead_sync_queue.sql","V1_10_293__lead_command_delivery.sql","V1_10_298__lead_command_consumer_compatibility_fence.sql","V1_10_299__lead_blocking_scope_index.sql","V1_10_306__lead_versioned_delivery_protocol.sql","V1_10_288__performer_scoped_delivery.sql","V1_10_300__performer_readiness_intent_index.sql"))migration.addScript(new FileSystemResource(root.resolve("backend/src/main/resources/db/migration").resolve(file)));
        migration.execute(ds);
    }
    @SuppressWarnings("unchecked") static <T>T proxy(T target){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    static double elapsed(long start){return (System.nanoTime()-start)/1e6;}
    static <T>T retryRollback(Supplier<T> action) {
        for(int attempt=0;;attempt++)try{return action.get();}catch(RuntimeException failure){
            boolean rolledBack=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&sql.getErrorCode()==1213&&"40001".equals(sql.getSQLState()))rolledBack=true;
            if(!rolledBack||attempt>=100)throw failure;
            ROLLBACK_RETRIES.computeIfAbsent(PHASE.get(),ignored->new AtomicLong()).incrementAndGet();
            LockSupport.parkNanos(20_000_000);
        }
    }
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static class Counters {
        final AtomicLong handled=new AtomicLong(),empty=new AtomicLong(),fenced=new AtomicLong();final Set<Integer> seen=ConcurrentHashMap.newKeySet();
        final Samples claim=new Samples(),ack=new Samples(),owns=new Samples();
    }
    static class Samples {
        final ConcurrentLinkedQueue<Long> samples=new ConcurrentLinkedQueue<>();void add(long value){samples.add(value);}void clear(){samples.clear();}
        Map<String,Object> summary(){var data=samples.stream().mapToLong(Long::longValue).sorted().toArray();if(data.length==0)return Map.of("count",0);
            return Map.of("count",data.length,"p50Ms",data[data.length/2]/1e6,"p95Ms",data[Math.min(data.length-1,(int)(data.length*.95))]/1e6,"p99Ms",data[Math.min(data.length-1,(int)(data.length*.99))]/1e6,"maxMs",data[data.length-1]/1e6,"meanMs",Arrays.stream(data).average().orElse(0)/1e6);}
    }
    static class SqlMeasure {
        final String phase,sql;final AtomicLong count=new AtomicLong(),errors=new AtomicLong(),nanos=new AtomicLong();
        SqlMeasure(String phase,String sql){this.phase=phase;this.sql=sql;}
        Map<String,Object> summary(){return Map.of("phase",phase,"sql",sql,"sha256",sha(sql),"executions",count.get(),"errors",errors.get(),"totalMs",nanos.get()/1e6);}
    }
    static class TrackedDataSource extends DelegatingDataSource {
        TrackedDataSource(javax.sql.DataSource target){super(target);}
        @Override public Connection getConnection() throws SQLException {long start=System.nanoTime();var raw=super.getConnection();CONNECTIONS.add(System.nanoTime()-start);
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                try {var value=m.invoke(raw,args);if(value instanceof Statement statement)return statement(statement,args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:null);return value;}
                catch(InvocationTargetException e){throw e.getCause();}
            });}
        Object statement(Statement raw,String preparedSql) {
            Class<?> type=raw instanceof PreparedStatement?PreparedStatement.class:Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
                boolean execute=m.getName().startsWith("execute");String sql=preparedSql!=null?preparedSql:args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:"BATCH";
                var measure=execute?SQL.computeIfAbsent(PHASE.get()+":"+sha(sql),key->new SqlMeasure(PHASE.get(),sql)):null;long start=System.nanoTime();
                try{return m.invoke(raw,args);}catch(InvocationTargetException e){if(measure!=null)measure.errors.incrementAndGet();throw e.getCause();}
                finally{if(measure!=null){measure.count.incrementAndGet();measure.nanos.addAndGet(System.nanoTime()-start);}}
            });
        }
    }
}
