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
    private final com.hunt.otziv.whatsapp.service.WhatsAppInboundReplyOutbox queue;
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
            var delivery=queue.enqueueManual(operationId,principal.getName(),clientId,phone,message);
            model.addAttribute("delivery",delivery);
            result=deliveryMessage(delivery.status());
            model.addAttribute("operationId",operationId);
        } catch(IllegalArgumentException error) {
            result=com.hunt.otziv.whatsapp.dto.WhatsAppSendResult.error("operation_invalid", "Откройте новую форму отправки; содержимое существующей операции менять нельзя").toJson();
            model.addAttribute("operationId",operationId==null?operations.createManualOperation(principal.getName()):operationId);
        }
        model.addAttribute("result", result);
        return "lead/layouts/whatsapp";
    }

    @GetMapping("/operations/{operationId}")
    @org.springframework.web.bind.annotation.ResponseBody
    public com.hunt.otziv.client_messages.api.DeliveryOperation deliveryStatus(
            @org.springframework.web.bind.annotation.PathVariable String operationId, java.security.Principal principal) {
        try {
            var status=queue.manualStatus(operationId,principal.getName());
            if(status==null) throw new IllegalArgumentException("operation_missing");
            return status;
        } catch(IllegalArgumentException missing) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
    }

    private static String deliveryMessage(String state) {
        return switch(state) {
            case "SENT" -> "Отправка подтверждена.";
            case "UNKNOWN" -> "Исход отправки уточняется. Повторная рассылка заблокирована.";
            case "FAILED" -> "Сообщение не отправлено. Требуется проверка.";
            case "SENDING" -> "Сообщение отправляется. Можно продолжать работу.";
            default -> "Сообщение сохранено в очереди.";
        };
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

