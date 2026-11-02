package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.payments.tochka.config.TochkaPaymentProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class PaperBillingBankClient {
    private final RestTemplate rest;
    private final ObjectMapper json;
    private final TochkaPaymentProperties tochka;
    private final PaperBillingSettings settings;
    private final Environment environment;
    public PaperBillingBankClient(@Qualifier("tochkaRestTemplate") RestTemplate rest,ObjectMapper json,
            TochkaPaymentProperties tochka,PaperBillingSettings settings,Environment environment) {
        this.rest=rest; this.json=json; this.tochka=tochka; this.settings=settings; this.environment=environment;
    }
    public boolean fixture() {
        if (settings.getFixtureUrl().isBlank()) return false;
        if (!environment.acceptsProfiles(Profiles.of("paper-invoice-fixture"))
                || !settings.getFixtureUrl().equals("http://paper-invoice-fixture:8099")
                || (!tochka.getJwtToken().isBlank() && !tochka.getJwtToken().equals("local-fixture-only")))
            throw new IllegalStateException("Изолированный тестовый сервис документов настроен неверно");
        return true;
    }
    public boolean configured() {
        return settings.isEnabled() && settings.getAccountLast4().matches("\\d{4}")
                && (fixture() || (!tochka.getJwtToken().isBlank() && !tochka.getCustomerCode().isBlank() && !tochka.isTestMode()));
    }
    String base() { return fixture()?settings.getFixtureUrl():"https://enter.tochka.com/uapi"; }
    HttpHeaders headers() {
        var h=new HttpHeaders(); h.setBearerAuth(fixture()?"local-fixture":tochka.getJwtToken()); h.setContentType(MediaType.APPLICATION_JSON); return h;
    }
    JsonNode call(String path,HttpMethod method,Object body) {
        if (!configured()) throw problem("Подключение счетов Точки не настроено владельцем");
        var response=rest.exchange(URI.create(base()+path),method,new HttpEntity<>(body,headers()),String.class);
        try { return json.readTree(response.getBody()); }
        catch(Exception e) { throw problem("Банк вернул некорректный ответ"); }
    }
    public String customer() { return fixture()?"LOCAL0001":tochka.getCustomerCode(); }
    public String serviceName() { return tochka.getReceiptItemName(); }
    public String vatKind() {
        String vat=tochka.getVatType().equals("none")?"without_nds":tochka.getVatType().replace("vat","nds_");
        if (!vat.equals("without_nds") && !vat.matches("nds_(0|5|7|10|22)")) throw problem("Ставка НДС для документов не поддерживается");
        return vat;
    }
    public String supplierAccount() {
        var data=call("/open-banking/v1.0/accounts",HttpMethod.GET,null).path("Data").path("Account");
        var matches=new ArrayList<String>();
        for (var a:data) {
            String id=a.path("accountId").asText();
            if (a.path("customerCode").asText().equals(customer()) && a.path("status").asText().equals("Enabled")
                    && a.path("currency").asText().equals("RUB") && a.path("accountType").asText().equals("Business")
                    && id.split("/")[0].endsWith(settings.getAccountLast4())) matches.add(id);
        }
        if (matches.size()!=1) throw problem("Не удалось однозначно выбрать расчётный счёт владельца");
        return matches.getFirst();
    }
    public Lookup lookup(String inn) {
        if (!validInn(inn)) throw problem("Неверный ИНН");
        boolean local=fixture() && settings.getDadataKey().isBlank();
        if (!local && settings.getDadataKey().isBlank()) throw problem("Автозаполнение по ИНН ещё не подключено. Можно заполнить реквизиты вручную.");
        var h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization","Token "+(local?"fixture":settings.getDadataKey()));
        String url=local?base()+"/lookup":"https://suggestions.dadata.ru/suggestions/api/4_1/rs/findById/party";
        var response=rest.postForObject(url,new HttpEntity<>(Map.of("query",inn,"count",20),h),String.class);
        try {
            var candidates=new ArrayList<Payer>();
            for(var s:json.readTree(response).path("suggestions")) {
                var d=s.path("data");
                if (!d.path("inn").asText().equals(inn) || !d.path("state").path("status").asText().equals("ACTIVE")) continue;
                String type=d.path("type").asText().equals("LEGAL")?"company":d.path("type").asText().equals("INDIVIDUAL")?"ip":"";
                if (type.isEmpty()) continue;
                var p=new Payer(type,d.path("name").path("full_with_opf").asText(),inn,
                        type.equals("ip")?"":d.path("kpp").asText(""),
                        type.equals("ip")?"":d.path("address").path("data").path("source").asText(d.path("address").path("value").asText("")));
                p.validate(); candidates.add(p);
            }
            return new Lookup(candidates.stream().distinct().toList());
        } catch (org.springframework.web.server.ResponseStatusException e) { throw e; }
        catch(Exception e) { throw problem("Не удалось прочитать данные плательщика"); }
    }
    public String create(Map<String,Object> r,Payer payer,boolean act) {
        var side=new LinkedHashMap<String,Object>();
        side.put("type",payer.type()); side.put("taxCode",payer.inn()); side.put("secondSideName",payer.name());
        if (!payer.kpp().isBlank()) side.put("kpp",payer.kpp());
        if (!payer.address().isBlank()) side.put("legalAddress",payer.address());
        BigDecimal total=BigDecimal.valueOf(((Number)r.get("amount_kopecks")).longValue(),2);
        String vat=(String)r.get("vat_kind");
        var line=new LinkedHashMap<String,Object>();
        line.put("positionName",r.get("service_name")); line.put("unitCode","услуга."); line.put("quantity",1);
        line.put("price",total); line.put("totalAmount",total); line.put("ndsKind",vat);
        if (!vat.equals("without_nds")) {
            int rate=Integer.parseInt(vat.substring(4));
            line.put("totalNds",total.multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100+rate),2,java.math.RoundingMode.HALF_UP));
        }
        var content=new LinkedHashMap<String,Object>();
        content.put("Positions",List.of(line)); content.put("number",r.get("number")+(act?"-ACT":""));
        Object created=r.get("created_at");
        content.put("date",(created instanceof java.sql.Timestamp ts?ts.toLocalDateTime():(java.time.LocalDateTime)created).toLocalDate().toString());
        // Keep the bank's suggested purpose consistent with our unique reference.
        content.put("basedOn","Счёт № "+r.get("number")); content.put("totalAmount",total);
        if (line.containsKey("totalNds")) content.put("totalNds",line.get("totalNds"));
        var data=new LinkedHashMap<String,Object>();
        data.put("accountId",r.get("supplier_account")); data.put("customerCode",r.get("customer_code"));
        data.put("SecondSide",side); data.put("Content",Map.of(act?"Act":"Invoice",content));
        if (act) data.put("documentId",r.get("bank_invoice_id"));
        String id=call("/invoice/v1.0/"+(act?"closing-documents":"bills"),HttpMethod.POST,Map.of("Data",data)).path("Data").path("documentId").asText();
        if (!id.matches("[A-Za-z0-9-]{1,96}")) throw problem("Банк не вернул идентификатор документа; нужна сверка");
        return id;
    }
    public byte[] pdf(Map<String,Object> r,boolean act) {
        String id=(String)r.get(act?"bank_act_id":"bank_invoice_id");
        String path="/invoice/v1.0/"+(act?"closing-documents":"bills")+"/"+r.get("customer_code")+"/"+id+"/file";
        var response=rest.exchange(URI.create(base()+path),HttpMethod.GET,new HttpEntity<>(headers()),byte[].class);
        byte[] bytes=response.getBody();
        if (bytes==null || bytes.length<5 || bytes.length>5*1024*1024 || !new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            throw problem("Банк не вернул корректный PDF");
        return bytes;
    }
    private String documentPath(Map<String,Object> row,boolean act) {
        String customer=Objects.toString(row.get("customer_code"),"");
        String id=Objects.toString(row.get(act?"bank_act_id":"bank_invoice_id"),"");
        if(!customer.equals(customer()) || !customer.matches("[A-Za-z0-9]{1,20}") || !id.matches("[A-Za-z0-9-]{1,96}"))
            throw problem("Банковский документ не соответствует подключению");
        return "/invoice/v1.0/"+(act?"closing-documents":"bills")+"/"+customer+"/"+id;
    }
    public String invoicePaymentStatus(Map<String,Object> row) {
        return call(documentPath(row,false)+"/payment-status",HttpMethod.GET,null).path("Data").path("paymentStatus").asText();
    }
    /** DELETE is retried only for the same immutable ID. 404 confirms the desired absence. */
    public void deleteDocument(Map<String,Object> row,boolean act) {
        try {
            var result=call(documentPath(row,act),HttpMethod.DELETE,null).path("Data").path("result");
            if(!result.isBoolean() || !result.booleanValue()) throw problem("Банк не подтвердил удаление документа");
        } catch(org.springframework.web.client.HttpClientErrorException.NotFound absent) {
            // The previous DELETE may have succeeded before its response was lost.
        }
    }
    public JsonNode fixtureCall(String path,Object body) {
        if (!fixture()) throw new IllegalStateException("Fixture is unavailable");
        return call(path,HttpMethod.POST,body);
    }
}
