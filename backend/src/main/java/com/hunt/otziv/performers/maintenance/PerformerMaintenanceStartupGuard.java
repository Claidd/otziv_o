package com.hunt.otziv.performers.maintenance;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs when the DataSource is created, before Flyway/JPA or any application writer can start. */
@Configuration(proxyBeanMethods=false)
public class PerformerMaintenanceStartupGuard {
    @Bean
    static BeanPostProcessor refuseIncompletePerformerMaintenance() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if(bean instanceof DataSource source) assertReady(source);
                return bean;
            }
        };
    }

    public static void assertReady(DataSource source) {
        JdbcTemplate jdbc=new JdbcTemplate(source);
        boolean present=jdbc.queryForObject("SELECT COUNT(*)>0 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='performer_legacy_maintenance_runs'",Boolean.class);
        if(present && jdbc.queryForObject("SELECT COUNT(*) FROM performer_legacy_maintenance_runs WHERE active_slot=1",Long.class)>0)
            throw new IllegalStateException("Incomplete offline performer maintenance: application startup and automatic migration are blocked");
        if(!table(jdbc,"review_performer_assignments")||!table(jdbc,"review_performer_offers"))return;
        boolean beforeV288=!column(jdbc,"review_performer_assignments","publication_generation");
        boolean historicalMutation=beforeV288 && (jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM review_performer_assignments WHERE status='WAITING_PUBLICATION' LIMIT 1)",Boolean.class)
                ||jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM review_performer_offers WHERE status='OFFERED' OR telegram_message_id IS NOT NULL LIMIT 1)",Boolean.class));
        if(!beforeV288&&!column(jdbc,"review_performer_assignments","readiness_intent_generation")) {
            historicalMutation=jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM review_performer_assignments a JOIN performer_notification_intents n
                    ON n.operation_key=CONCAT('READY:',a.assignment_id,':',a.publication_generation)
                    AND n.assignment_id=a.assignment_id AND n.notification_type='READY'
                    AND n.offer_id IS NULL AND n.generation=a.publication_generation
                    WHERE a.publication_generation>0 LIMIT 1)
                    """,Boolean.class);
        }
        if(historicalMutation)throw new IllegalStateException(
                "Offline performer legacy maintenance required before pending V288/V300 history mutation. "
                +"Run PerformerLegacyMaintenanceCli preview-stage/begin/stage/migrate/restore with a drained write-fenced database; "
                +"then restart this same database. See docs/LEGACY_QUEUE_MAINTENANCE.md. Flyway has not started.");
    }

    private static boolean table(JdbcTemplate jdbc,String name) {
        return jdbc.queryForObject("SELECT COUNT(*)>0 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Boolean.class,name);
    }
    private static boolean column(JdbcTemplate jdbc,String table,String name) {
        return jdbc.queryForObject("SELECT COUNT(*)>0 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",Boolean.class,table,name);
    }
}
