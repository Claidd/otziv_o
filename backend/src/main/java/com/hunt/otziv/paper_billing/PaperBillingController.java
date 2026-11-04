package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/paper-invoices")
@PreAuthorize("hasAnyRole('ADMIN','OWNER','MANAGER')")
@RequiredArgsConstructor
public class PaperBillingController {
    private final PaperBillingService service;
    private final PaperBillingBankClient bank;
    private final PaperBillingStore store;
    private final PaperBillingReconciliation reconciliation;
    private final PaperBillingCancellation cancellation;
    @PostMapping("/documents/{id}/cancel")
    public Document cancel(@PathVariable String id,@RequestBody CancelRequest request,Authentication auth) {
        return cancellation.request(id,request,auth);
    }
    @PostMapping("/{kind}/{targetId}/reconcile")
    public void reconcile(@PathVariable String kind,@PathVariable Long targetId,Authentication auth) {
        service.context(new Target(kind,targetId),auth);
        reconciliation.checkRequestedByUser();
    }
    @GetMapping("/{kind}/{targetId}/context")
    public Context context(@PathVariable String kind,@PathVariable Long targetId,Authentication auth) {
        return service.context(new Target(kind,targetId),auth);
    }
    @GetMapping("/{kind}/{targetId}/documents")
    public java.util.List<Document> documents(@PathVariable String kind,@PathVariable Long targetId,Authentication auth) {return service.documents(new Target(kind,targetId),auth);}
    @GetMapping("/{kind}/{targetId}/lookup")
    public Lookup lookup(@PathVariable String kind,@PathVariable Long targetId,@RequestParam String inn,Authentication auth) {
        service.context(new Target(kind,targetId),auth);
        return bank.lookup(inn);
    }
    @PutMapping("/{kind}/{targetId}/payer")
    public void save(@PathVariable String kind,@PathVariable Long targetId,@RequestBody Payer payer,Authentication auth) {
        service.savePayer(new Target(kind,targetId),payer,auth);
    }
    @PostMapping("/{kind}/{targetId}/issue")
    public Document issue(@PathVariable String kind,@PathVariable Long targetId,@RequestBody IssueRequest request,Authentication auth) {
        return service.issue(new Target(kind,targetId),request,auth);
    }
    @GetMapping("/documents/{id}")
    public Document document(@PathVariable String id,Authentication auth) { return store.document(service.authorized(id,auth)); }
    @GetMapping("/documents/{id}/{type}.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id,@PathVariable String type,Authentication auth) {
        if (!java.util.Set.of("invoice","act").contains(type)) throw problem("Документ не найден");
        var r=service.authorized(id,auth);
        byte[] bytes=(byte[])r.get(type.equals("act")?"act_pdf":"invoice_pdf");
        if (bytes==null) throw problem("Документ ещё формируется");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+type+"-"+r.get("number")+".pdf\"").body(bytes);
    }
}
