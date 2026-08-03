package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.config.legacy.LegacyMvc;

import com.hunt.otziv.l_lead.dto.TelephoneDTO;
import com.hunt.otziv.l_lead.services.LeadAccessService;
import com.hunt.otziv.l_lead.services.serv.TelephoneService;
import com.hunt.otziv.u_users.model.Operator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@LegacyMvc
@RequestMapping("/phone")
@RequiredArgsConstructor
public class TelephoneController {

    private final TelephoneService telephoneService;
    private final LeadAccessService leadAccessService;

    @GetMapping("")
    public String allPhones(Model model, Authentication authentication){
        LeadAccessService.LeadAssignmentOptions options = leadAccessService.assignmentOptions(authentication);
        Set<Long> allowedOperatorIds = options.operators().stream()
                .map(Operator::getId)
                .collect(Collectors.toSet());
        boolean admin = hasRole(authentication, "ROLE_ADMIN");
        List<TelephoneDTO> phones = telephoneService.getAllTelephones().stream()
                .filter(phone -> admin || (phone.getOperator() != null
                        && allowedOperatorIds.contains(phone.getOperator().getId())))
                .map(this::sanitizedForList)
                .toList();
        model.addAttribute("all_phones", phones);
        return "telephone/phone_list";
    }

    @GetMapping("/{phoneId}/edit")
    public String editPhone(Model model, @PathVariable final Long phoneId, Authentication authentication){
        leadAccessService.requireTelephoneAccess(phoneId, authentication);
        TelephoneDTO current = telephoneService.getTelephoneDTOById(phoneId);
        addCredentialPresence(model, current);
        model.addAttribute("phone", sanitizedForEdit(current));
        model.addAttribute("operators", leadAccessService.assignmentOptions(authentication).operators());
        return "telephone/phone_edit";
    }

    // CONTROLLER
    @PostMapping("/{phoneId}/edit")
    public String updatePhone(@PathVariable Long phoneId,
                              @ModelAttribute("phone") TelephoneDTO dto,
                              Authentication authentication) {
        leadAccessService.requireTelephoneAccess(phoneId, authentication);
        requireOperatorAssignment(dto, authentication);
        TelephoneDTO current = telephoneService.getTelephoneDTOById(phoneId);
        preserveBlankCredentials(dto, current);
        telephoneService.updatePhone(phoneId, dto);
        return "redirect:/phone";
    }

    @GetMapping("/add")
    public String showAddForm(Model model, Authentication authentication) {
        model.addAttribute("phone", telephoneService.createEmptyDTO());
        model.addAttribute("operators", leadAccessService.assignmentOptions(authentication).operators());
        return "telephone/phone_add";
    }

    @PostMapping("/add")
    public String addPhone(@ModelAttribute("phone") TelephoneDTO dto, Authentication authentication) {
        requireOperatorAssignment(dto, authentication);
        telephoneService.createTelephone(dto);
        return "redirect:/phone";
    }

    @PostMapping("/{phoneId}/delete")
    public String deletePhone(@PathVariable Long phoneId, Authentication authentication) {
        leadAccessService.requireTelephoneAccess(phoneId, authentication);
        telephoneService.deletePhone(phoneId);
        return "redirect:/phone";
    }

    private TelephoneDTO sanitizedForList(TelephoneDTO phone) {
        return copyWithCredentials(
                phone,
                maskedCredential(phone.getGoogleLogin()),
                presenceLabel(phone.getGooglePassword()),
                presenceLabel(phone.getAvitoPassword()),
                maskedCredential(phone.getMailLogin()),
                presenceLabel(phone.getMailPassword())
        );
    }

    private TelephoneDTO sanitizedForEdit(TelephoneDTO phone) {
        return copyWithCredentials(phone, null, null, null, null, null);
    }

    private TelephoneDTO copyWithCredentials(
            TelephoneDTO phone,
            String googleLogin,
            String googlePassword,
            String avitoPassword,
            String mailLogin,
            String mailPassword
    ) {
        return TelephoneDTO.builder()
                .id(phone.getId())
                .number(phone.getNumber())
                .fio(phone.getFio())
                .birthday(phone.getBirthday())
                .amountAllowed(phone.getAmountAllowed())
                .amountSent(phone.getAmountSent())
                .blockTime(phone.getBlockTime())
                .timer(phone.getTimer())
                .googleLogin(googleLogin)
                .googlePassword(googlePassword)
                .avitoPassword(avitoPassword)
                .mailLogin(mailLogin)
                .mailPassword(mailPassword)
                .createDate(phone.getCreateDate())
                .updateStatus(phone.getUpdateStatus())
                .operator(phone.getOperator())
                .foto_instagram(phone.getFoto_instagram())
                .active(phone.isActive())
                .build();
    }

    private void addCredentialPresence(Model model, TelephoneDTO phone) {
        model.addAttribute("googleLoginPresent", hasText(phone.getGoogleLogin()));
        model.addAttribute("googlePasswordPresent", hasText(phone.getGooglePassword()));
        model.addAttribute("avitoPasswordPresent", hasText(phone.getAvitoPassword()));
        model.addAttribute("mailLoginPresent", hasText(phone.getMailLogin()));
        model.addAttribute("mailPasswordPresent", hasText(phone.getMailPassword()));
    }

    private void preserveBlankCredentials(TelephoneDTO requested, TelephoneDTO current) {
        requested.setGoogleLogin(writeOnlyValue(requested.getGoogleLogin(), current.getGoogleLogin()));
        requested.setGooglePassword(writeOnlyValue(requested.getGooglePassword(), current.getGooglePassword()));
        requested.setAvitoPassword(writeOnlyValue(requested.getAvitoPassword(), current.getAvitoPassword()));
        requested.setMailLogin(writeOnlyValue(requested.getMailLogin(), current.getMailLogin()));
        requested.setMailPassword(writeOnlyValue(requested.getMailPassword(), current.getMailPassword()));
    }

    private void requireOperatorAssignment(TelephoneDTO dto, Authentication authentication) {
        Long operatorId = dto == null || dto.getOperator() == null ? null : dto.getOperator().getId();
        if ((operatorId == null && !hasRole(authentication, "ROLE_ADMIN"))
                || (operatorId != null && !leadAccessService.canAccessOperator(operatorId, authentication))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Оператор не найден");
        }
    }

    private String writeOnlyValue(String requested, String current) {
        return hasText(requested) ? requested : current;
    }

    private String maskedCredential(String value) {
        return hasText(value) ? "••••••" : "—";
    }

    private String presenceLabel(String value) {
        return hasText(value) ? "Сохранён" : "—";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication != null
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> role.equalsIgnoreCase(authority.getAuthority()));
    }
}
