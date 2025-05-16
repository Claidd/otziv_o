package com.hunt.otziv.whatsapp.controller;



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
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/whatsapp")
public class SendMessageController {
    private final WhatsAppService whatsAppService;


    @GetMapping()
    public ModelAndView showForm(Map<String, Object> model) {
        return new ModelAndView("lead/layouts/whatsapp", model);// send.html
    }

    @PostMapping("/send")
    public String sendMessage(
            @RequestParam String clientId,
            @RequestParam String phone,
            @RequestParam String message,
            Model model
    ) {
        String result = whatsAppService.sendMessage(clientId, phone, message);
        model.addAttribute("result", result);
        return "lead/layouts/whatsapp";

    }

}

