package com.hunt.otziv.paper_billing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class PaperBillingModels {
    private PaperBillingModels() {}
    public record Target(String kind, Long id) {
        public Target {
            if (!List.of("ORDER","COMMON_INVOICE").contains(kind == null ? "" : kind) || id == null || id <= 0)
                throw problem("Некорректный заказ или общий счёт");
        }
    }
    public record Payer(String type, String name, String inn, String kpp, String address) {
        public Payer {
            type=clean(type); name=clean(name); inn=clean(inn); kpp=clean(kpp); address=clean(address);
        }
        public void validate() {
            if (!List.of("ip","company").contains(type)) throw problem("Выберите организацию или ИП");
            if (!validInn(inn) || (type.equals("company") ? inn.length()!=10 : inn.length()!=12))
                throw problem("Проверьте ИНН и тип плательщика");
            if (name.isBlank() || name.length()>300) throw problem("Укажите полное наименование плательщика");
            if (!kpp.isBlank() && (!type.equals("company") || !kpp.matches("[0-9A-Z]{9}")))
                throw problem("Проверьте КПП; для ИП КПП не заполняется");
            if (address.length()>1000) throw problem("Адрес слишком длинный");
        }
    }
    public record IssueRequest(Payer payer, boolean confirmedUnpaid) {}
    public record CancelRequest(String reason, boolean confirmed) {}
    public record Context(boolean enabled, boolean configured, boolean lookupAvailable, String accountLast4,
            long companyId, String companyTitle, long amountKopecks, Payer payer, Document document) {}
    public record Document(String id, String number, long amountKopecks, String stage,
            String invoiceDelivery, String actDelivery, String error, LocalDateTime paidAt,String cancellationDelivery,String cancellationReason) {}
    public record BankReceipt(String customerCode, String accountId, String paymentId, String payerInn,
            long amountKopecks, String purpose) {}
    public record Lookup(List<Payer> candidates) {}
    static String clean(String value) { return value==null ? "" : value.trim(); }
    public static boolean validInn(String value) {
        if (value==null || !value.matches("(?:\\d{10}|\\d{12})") || value.chars().allMatch(c -> c=='0')) return false;
        if (value.length()==10) return checksum(value,new int[]{2,4,10,3,5,9,4,6,8})==value.charAt(9)-'0';
        return checksum(value,new int[]{7,2,4,10,3,5,9,4,6,8})==value.charAt(10)-'0'
                && checksum(value,new int[]{3,7,2,4,10,3,5,9,4,6,8})==value.charAt(11)-'0';
    }
    private static int checksum(String value,int[] weights) {
        int sum=0; for (int i=0;i<weights.length;i++) sum+=(value.charAt(i)-'0')*weights[i];
        return sum%11%10;
    }
    static ResponseStatusException problem(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
