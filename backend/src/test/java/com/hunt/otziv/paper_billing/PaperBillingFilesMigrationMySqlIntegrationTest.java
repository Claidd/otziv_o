package com.hunt.otziv.paper_billing;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PaperBillingFilesMigrationMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("files_fixture").withUsername("root").withPassword(UUID.randomUUID().toString());
    @Test void migrationPreservesAllFourFilesIncludingCancelledDocumentsAndAmbiguousDelivery() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE payment_links(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE common_invoices(invoice_id BIGINT PRIMARY KEY)");
        for(String migration:new String[]{"348__tochka_paper_documents","350__paper_document_visual_signature","351__paper_document_intro_delivery"})
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_"+migration+".sql")).execute(ds);
        // Include nulls, binary zeroes and bytes that are not UTF-8.
        byte[] invoice={0,-1,1},act={2,0,-128},originalInvoice={4,0,-1},originalAct={5,-128,0};
        for(int n=1;n<=2;n++) jdbc.update("""
            INSERT INTO paper_invoice_document(id,target_kind,target_id,company_id,number,amount_kopecks,payer_json,
              supplier_account,customer_code,composition_hash,client_id,group_id,created_by,created_at,updated_at,
              invoice_pdf,act_pdf,invoice_original_pdf,act_original_pdf,stage,invoice_delivery)
            VALUES(?,'ORDER',?,1,?,100,'{}','account','customer','hash','client','group','test',NOW(6),NOW(6),?,?,?,?,?,?)
            """,UUID.randomUUID().toString(),n,"fixture-"+n,invoice,n==1?act:null,originalInvoice,originalAct,
                n==1?"FILES_READY":"CANCELLED",n==1?"SENDING":"SENT");
        // Production inherited 0900 IDs before the database default became unicode_ci.
        jdbc.execute("ALTER DATABASE files_fixture CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_364__paper_files_and_delivery_backoff.sql")).execute(ds);
        var rows=jdbc.queryForList("SELECT d.*,f.invoice_pdf,f.act_pdf,f.invoice_original_pdf,f.act_original_pdf FROM paper_invoice_document d JOIN paper_invoice_files f ON f.document_id=d.id ORDER BY target_id");
        assertEquals(2,rows.size());
        for(var row:rows) {
            assertArrayEquals(invoice,(byte[])row.get("invoice_pdf"));
            assertArrayEquals(originalInvoice,(byte[])row.get("invoice_original_pdf"));
            assertArrayEquals(originalAct,(byte[])row.get("act_original_pdf"));
        }
        assertArrayEquals(act,(byte[])rows.getFirst().get("act_pdf"));
        assertEquals("SENDING",rows.getFirst().get("invoice_delivery"));
        assertNotNull(rows.getFirst().get("delivery_attempted_at"));
        assertNull(rows.getLast().get("act_pdf"));
        assertEquals("CANCELLED",rows.getLast().get("stage"));
    }
}
