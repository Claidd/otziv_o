package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.services.serv.DeviceTokenService;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.l_lead.dto.TelephoneIDAndTimeDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Map;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/operators")
public class OperatorController {

    private final LeadService leadService;
    private final PromoTextService promoTextService;
    private final DeviceTokenService deviceTokenService;

    @GetMapping
    public ModelAndView leadToOperator(
            Map<String, Object> model,
            @CookieValue(name = "device_token", required = false) String token,
            Principal principal,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber) {

        long startTime = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        String userRole = getRole(principal);

        TelephoneIDAndTimeDTO telephone = resolveTelephoneId(token);
        boolean requireDeviceId = (telephone == null || telephone.getTelephoneID() == null);
        boolean hasKeyword = !keyword.isEmpty();
        boolean isTimerExpired = (telephone != null && (now.isAfter(telephone.getTime()) || now.isEqual(telephone.getTime())));

        Page<LeadDTO> leadsNew = Page.empty();


        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole)) {
            if (hasKeyword && telephone != null) {
                // С ключом — всегда показываем лиды
                leadsNew = leadService.getAllLeadsNoStatus(keyword, principal, pageNumber, 10);
                logExecutionTime("С ключом — загрузка лидов без проверки таймера", startTime);

            } else if (!hasKeyword && telephone != null && isTimerExpired) {
                // Без ключа — только если таймер истёк
                leadsNew = leadService.getAllLeads(LeadStatus.NEW.title, keyword, principal, pageNumber, 10);
                logExecutionTime("Без ключа и с истекшим таймером — загрузка лидов", startTime);

            }
        }


        if ("ROLE_OPERATOR".equals(userRole)){
            if (hasKeyword && telephone != null) {
                // С ключом — всегда показываем лиды
            leadsNew = leadService.getAllLeadsToOperator(
                    telephone.getTelephoneID(), LeadStatus.NEW.title, keyword, principal, pageNumber, 10);
                logExecutionTime("С ключом — загрузка лидов без проверки таймера", startTime);

            } else if (!hasKeyword && telephone != null && isTimerExpired) {
                // Без ключа — только если таймер истёк
                leadsNew = leadService.getAllLeadsToOperator(
                        telephone.getTelephoneID(), LeadStatus.NEW.title, "", principal, pageNumber, 10);
                logExecutionTime("Без ключа и с истекшим таймером — загрузка лидов", startTime);

            }
        }
         else {
            logExecutionTime("Условия не выполнены — лиды не загружены", startTime);
        }

        model.put("requireDeviceId", requireDeviceId);
        model.put("promoTexts", promoTextService.getAllPromoTexts());
        model.put("text", deviceTokenService.getText(token));
        model.put("leadListNew", leadsNew);

        return new ModelAndView("lead/layouts/operators", model);
    }



    // меняем статус с нового на отправленное - начало
    @PostMapping("/status_send/{leadId}")
    public String changeStatusLeadOnSend(Model model, RedirectAttributes rm,  @PathVariable final Long leadId, Principal principal){
        log.info("вход в меняем статус с нового на отправленное");
        leadService.changeStatusLeadOnSendAndTelephone(leadId);
        log.info("статус успешно сменен с нового на отправленного" );
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/operators";
    }
    // меняем статус с нового на отправленное - конец

    // меняем статус с нового на отправленное - начало
    @PostMapping("lead/status_to_work/{leadId}")
    public String changeStatusLeadToWork(Model model, RedirectAttributes rm, @PathVariable final Long leadId,
                                         @RequestParam(required = false) String commentsLead, Principal principal){
        log.info("вход в меняем статус с нового на В Работу");
        leadService.changeStatusLeadToWork(leadId, commentsLead);
        log.info("статус успешно сменен с нового на В Работу" );
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/operators";
    }
    // меняем статус с нового на отправленное - конец



    private TelephoneIDAndTimeDTO resolveTelephoneId(String token) {
        if (token == null || token.isBlank()) {
            log.warn("device_token отсутствует в cookies");
            return null;
        }
        TelephoneIDAndTimeDTO telephone = deviceTokenService.getTelephoneIdByToken(token);
        if (telephone == null) {
            log.warn("Токен [{}] не найден в базе", token);
        } else {
            log.debug("Получен telephoneId из токена: {}", telephone.getTelephoneID());
        }
        return telephone;
    }


    private void logExecutionTime(String label, long startTime) {
        long endTime = System.nanoTime();
        double seconds = (endTime - startTime) / 1_000_000_000.0;
        log.info("{} {} сек", label, String.format("%.4f", seconds));
    }

    private String getRole(Principal principal) {
        if (principal == null) return "anonymous";
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    }// Берем роль пользователя
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