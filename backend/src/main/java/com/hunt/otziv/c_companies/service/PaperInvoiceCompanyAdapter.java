package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_companies.api.PaperInvoiceCompanyData;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class PaperInvoiceCompanyAdapter implements PaperInvoiceCompanyData {
    private final JdbcTemplate jdbc;
    public Map<String,Object> read(long id) {
        return jdbc.queryForMap("""
            SELECT c.company_title,c.company_url_chat,c.company_group_id,c.company_telegram_group_chat_id,
            c.company_max_group_chat_id,c.paper_billing_automatic,c.paper_billing_payer_choice_id,m.client_id FROM companies c
            LEFT JOIN managers m ON m.manager_id=c.company_manager WHERE company_id=?
            """,id);
    }
    @org.springframework.transaction.annotation.Transactional
    public void automatic(long company,boolean enabled,String choiceId) {
        jdbc.update("UPDATE companies SET paper_billing_automatic=?,paper_billing_payer_choice_id=? WHERE company_id=?",enabled,choiceId,company);
    }
}
