package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/paper-invoices/companies/{company}")
@PreAuthorize("hasAnyRole('ADMIN','OWNER','MANAGER')") @RequiredArgsConstructor
public class BillingPayerController {
    private final BillingPayerService payers;
    private final PaperBillingBankClient bank;
    @GetMapping("/payers") public List<BillingPayerService.Choice> list(@PathVariable long company,Authentication actor) {return payers.list(company,actor);}
    @GetMapping("/lookup") public Lookup lookup(@PathVariable long company,@RequestParam String inn,Authentication actor) {payers.list(company,actor);return bank.lookup(inn);}
    @PostMapping("/payers") public BillingPayerService.Choice add(@PathVariable long company,@RequestBody BillingPayerService.Save request,Authentication actor) {return payers.save(company,null,request,actor);}
    @PutMapping("/payers/{id}") public BillingPayerService.Choice edit(@PathVariable long company,@PathVariable String id,@RequestBody BillingPayerService.Save request,Authentication actor) {return payers.save(company,id,request,actor);}
    @DeleteMapping("/payers/{id}") public void delete(@PathVariable long company,@PathVariable String id,@RequestParam long version,Authentication actor) {payers.delete(company,id,version,actor);}
    @GetMapping("/automatic") public BillingPayerService.Preference preference(@PathVariable long company,Authentication actor) {return payers.preference(company,actor);}
    @PutMapping("/automatic") public void preference(@PathVariable long company,@RequestBody BillingPayerService.Preference request,Authentication actor) {payers.preference(company,request,actor);}
}
