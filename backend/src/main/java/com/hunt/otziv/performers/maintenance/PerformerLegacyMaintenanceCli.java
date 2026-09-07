package com.hunt.otziv.performers.maintenance;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;

/** Explicit offline entry point. Never invokes Spring, schedulers, providers, repair or baseline. */
public final class PerformerLegacyMaintenanceCli {
    private PerformerLegacyMaintenanceCli() {}

    public static void main(String[] args) throws Exception {
        if(args.length==0) throw new IllegalArgumentException("Expected inspect-conflicts|begin|stage|status|migrate|restore|abort|reconcile");
        String url=required("OTZIV_MAINTENANCE_JDBC_URL"), user=required("OTZIV_MAINTENANCE_USER"), password=required("OTZIV_MAINTENANCE_PASSWORD");
        try(var connection=DriverManager.getConnection(url,user,password)) {
            var tool=new PerformerLegacyMaintenance(connection,System.getenv("OTZIV_MAINTENANCE_WRITER_USER"),System.getenv("OTZIV_MAINTENANCE_WRITER_HOST"));
            Object result=switch(args[0]) {
                case "inspect-conflicts"->tool.inspectConflicts(Long.parseLong(args[1]),"new".equals(args[2])?null:Long.valueOf(args[2]),Integer.parseInt(args[3]));
                case "preview-stage"->tool.previewStage(args[1],Long.parseLong(args[2]),"new".equals(args[3])?null:Long.valueOf(args[3]),Integer.parseInt(args[4]));
                case "begin"->tool.begin(required("OTZIV_MAINTENANCE_ACTOR"));
                case "status"->tool.progress(args[1]);
                case "stage"->tool.stage(args[1],Integer.parseInt(args[2]));
                case "restore"->tool.restore(args[1],Integer.parseInt(args[2]),false);
                case "abort"->tool.restore(args[1],Integer.parseInt(args[2]),true);
                case "migrate"->{
                    tool.assertWriteFence();
                    if(!"MIGRATE_READY".equals(tool.progress(args[1]).phase())) throw new IllegalStateException("Staging is incomplete");
                    // Use this very connection so the verified drain has no extra writer connection.
                    Flyway flyway=Flyway.configure().dataSource(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true))
                            .locations("classpath:db/migration").target("1.10.300").cleanDisabled(true).load();
                    flyway.migrate(); flyway.validate();
                    tool.migrationFinished(args[1]);
                    yield tool.progress(args[1]);
                }
                case "reconcile"->tool.reconcile(args[1],Boolean.parseBoolean(args[2]),Long.parseLong(args[3]),
                        "new".equals(args[4])?null:Long.valueOf(args[4]),Integer.parseInt(args[5]));
                default->throw new IllegalArgumentException("Unknown maintenance command");
            };
            // Structured checkpoints contain IDs/status/counts only. Never serialize JDBC configuration.
            System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
        }
    }

    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank()) throw new IllegalArgumentException("Required environment variable: "+name);
        return value;
    }
}
