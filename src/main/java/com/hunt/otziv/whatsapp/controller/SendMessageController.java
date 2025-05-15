package com.hunt.otziv.whatsapp.controller;



import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;
import java.util.Optional;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/whatsapp")
public class SendMessageController {
    private final WhatsAppProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;


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
        Optional<WhatsAppProperties.ClientConfig> clientOpt = properties.getClients()
                .stream()
                .filter(c -> c.getId().equals(clientId))
                .findFirst();

        if (clientOpt.isEmpty()) {
            model.addAttribute("result", "❌ Неизвестный клиент: " + clientId);
            return "lead/layouts/whatsapp";
        }

        String url = clientOpt.get().getUrl();

        try {
            // ✅ Формируем JSON-строку вручную
            ObjectMapper mapper = new ObjectMapper();
            String jsonBody = mapper.writeValueAsString(Map.of(
                    "client", clientId,
                    "phone", phone,
                    "message", message
            ));

            // ✅ Заголовки
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // ✅ Объединяем
            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            // ✅ Отправка POST-запроса
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            model.addAttribute("result", "⏩ Ответ: " + response.getBody());
        } catch (Exception e) {
            model.addAttribute("result", "❌ Ошибка: " + e.getMessage());
        }

        return "lead/layouts/whatsapp";
    }

}

