package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.services.DeviceTokenService;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.l_lead.services.PromoTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.security.Principal;
import java.util.Map;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/operators")
public class OperatorController {

    private final LeadService leadService;
    private final PromoTextService promoTextService;
    private final DeviceTokenService deviceTokenService;

    @GetMapping("")
    public ModelAndView leadToOperator(
            Map<String, Object> model,
            @CookieValue(name = "device_token", required = false) String token,
            Principal principal,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber
    ) {

        boolean requireDeviceId = false;


        if (token == null || token.isEmpty() || deviceTokenService.getTelephoneIdByToken(token) == null) {
            requireDeviceId = true;
        }

        if (token != null && !token.isEmpty()) {
            Long telephoneId = deviceTokenService.getTelephoneIdByToken(token);
            System.out.println("ИД телефона: " + telephoneId);
        }

        // добавляем флаг в модель
        model.put("requireDeviceId", requireDeviceId);

        Page<LeadDTO> leadsNew = leadService.getAllLeads(LeadStatus.NEW.title, keyword, principal, pageNumber, 10);
        model.put("promoTexts", promoTextService.getAllPromoTexts());
        model.put("leadListNew", leadsNew);

        return new ModelAndView("lead/layouts/operators", model);
    }

//    @GetMapping()
//    public ModelAndView leadToOperator(final Map<String, Object> model, @RequestParam(defaultValue = "") String keyword,
//                                       Principal principal, @RequestParam(defaultValue = "0") int pageNumber,
//                                       @CookieValue(name = "device_token", required = false) String token
//    ) {
//
//        log.info("Зашли в контроллер Оператора");
//        System.out.println(token);
//        if (token != null && !token.isEmpty()) {
//            Long telephoneId = deviceTokenService.getTelephoneIdByToken(token);
//            System.out.println("ИД телефона: " + telephoneId);
//        }
//
//
//        // желаемый размер страницы
//        long startTime = System.nanoTime();
//        int pageSize = 10; // желаемый размер страницы
//
//
//        // Подготовка списка
//        Page<LeadDTO> leadsNew = leadService.getAllLeads(LeadStatus.NEW.title, keyword, principal, pageNumber, pageSize);
//
//        // Установка списков в Модель
//        model.put("promoTexts", promoTextService.getAllPromoTexts());
//        model.put("leadListNew", leadsNew);
//
//        // Установка возвращаемой страницы с моделью
//        checkTimeMethod("Время выполнения OperatorController/operator: ", startTime);
//        return new ModelAndView("lead/layouts/operators", model);
//    }










    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        log.info(text + "%.4f сек%n", timeElapsed);
    }

}
