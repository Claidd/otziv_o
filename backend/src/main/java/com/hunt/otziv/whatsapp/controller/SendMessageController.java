package com.hunt.otziv.whatsapp.controller;

import com.hunt.otziv.config.legacy.LegacyMvc;



import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import java.util.Map;

@Controller
@LegacyMvc
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/whatsapp")
public class SendMessageController {
    private final WhatsAppService whatsAppService;
    private final com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations operations;


    @GetMapping()
    public ModelAndView showForm(Map<String, Object> model, java.security.Principal principal) {
        model.put("operationId", operations.createManualOperation(principal.getName()));
        return new ModelAndView("lead/layouts/whatsapp", model);// send.html
    }

    @PostMapping("/send")
    public String sendMessage(
            @RequestParam String clientId,
            @RequestParam String phone,
            @RequestParam String message,
            @RequestParam(required=false) String operationId,
            java.security.Principal principal,
            Model model
    ) {
        String result;
        try {
            operations.requireManualOwner(operationId, principal.getName());
            var frozen=operations.freeze(operationId,clientId,"send",phone,message);
            operations.requireMatches(operationId,clientId,"send",phone,message);
            result=whatsAppService.sendMessage(frozen.clientId(),frozen.destination(),frozen.message(),frozen.operationId());
            model.addAttribute("operationId",operationId);
        } catch(IllegalArgumentException error) {
            result=com.hunt.otziv.whatsapp.dto.WhatsAppSendResult.error("operation_invalid", "Откройте новую форму отправки; содержимое существующей операции менять нельзя").toJson();
            model.addAttribute("operationId",operationId==null?operations.createManualOperation(principal.getName()):operationId);
        }
        model.addAttribute("result", result);
        return "lead/layouts/whatsapp";
    }

    @GetMapping("/toChat")
    public ModelAndView toChat(Map<String, Object> model, java.security.Principal principal) {
        return showForm(model,principal);
    }

    @GetMapping("/toGroup")
    public ModelAndView toGroup(Map<String, Object> model, java.security.Principal principal) {
        return showForm(model,principal);
    }



}

