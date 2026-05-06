package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.TelephoneDTO;
import com.hunt.otziv.l_lead.dto.api.DeviceTokenResponse;
import com.hunt.otziv.l_lead.dto.api.PhoneListResponse;
import com.hunt.otziv.l_lead.dto.api.PhoneOperatorOptionResponse;
import com.hunt.otziv.l_lead.dto.api.PhoneResponse;
import com.hunt.otziv.l_lead.dto.api.PhoneUpsertRequest;
import com.hunt.otziv.l_lead.model.DeviceToken;
import com.hunt.otziv.l_lead.repository.DeviceTokenRepository;
import com.hunt.otziv.l_lead.services.serv.TelephoneService;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.services.service.OperatorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/phones")
public class ApiAdminPhoneController {

    private final TelephoneService telephoneService;
    private final OperatorService operatorService;
    private final DeviceTokenRepository deviceTokenRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public PhoneListResponse getPhones(@RequestParam(defaultValue = "") String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        List<PhoneResponse> phones = telephoneService.getAllTelephones().stream()
                .filter(phone -> matchesKeyword(phone, normalizedKeyword))
                .map(this::toResponse)
                .toList();

        return new PhoneListResponse(phones, operatorOptions());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public PhoneResponse createPhone(@Valid @RequestBody PhoneUpsertRequest request) {
        TelephoneDTO dto = toDto(request, telephoneService.createEmptyDTO());
        telephoneService.createTelephone(dto);

        return telephoneService.getAllTelephones().stream()
                .filter(phone -> phone.getNumber() != null && phone.getNumber().equals(dto.getNumber()))
                .max(Comparator.comparing(TelephoneDTO::getId))
                .map(this::toResponse)
                .orElseGet(() -> toResponse(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public PhoneResponse updatePhone(
            @PathVariable Long id,
            @Valid @RequestBody PhoneUpsertRequest request
    ) {
        TelephoneDTO current = telephoneService.getTelephoneDTOById(id);
        TelephoneDTO dto = toDto(request, current);
        telephoneService.updatePhone(id, dto);
        return toResponse(telephoneService.getTelephoneDTOById(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void deletePhone(@PathVariable Long id) {
        telephoneService.deletePhone(id);
    }

    @DeleteMapping("/{id}/device-tokens/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void deleteDeviceToken(
            @PathVariable Long id,
            @PathVariable String token
    ) {
        DeviceToken deviceToken = deviceTokenRepository.findByTokenAndTelephone_Id(token, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Токен устройства не найден"));

        deviceTokenRepository.delete(deviceToken);
    }

    private TelephoneDTO toDto(PhoneUpsertRequest request, TelephoneDTO defaults) {
        Operator operator = null;
        if (request.operatorId() != null) {
            operator = new Operator();
            operator.setId(request.operatorId());
        }

        return TelephoneDTO.builder()
                .id(defaults.getId())
                .number(request.number())
                .fio(request.fio())
                .birthday(request.birthday() != null ? request.birthday() : defaults.getBirthday())
                .amountAllowed(request.amountAllowed() != null ? request.amountAllowed() : defaults.getAmountAllowed())
                .amountSent(request.amountSent() != null ? request.amountSent() : defaults.getAmountSent())
                .blockTime(request.blockTime() != null ? request.blockTime() : defaults.getBlockTime())
                .timer(request.timer() != null ? request.timer() : defaults.getTimer())
                .googleLogin(request.googleLogin())
                .googlePassword(request.googlePassword())
                .avitoPassword(request.avitoPassword())
                .mailLogin(request.mailLogin())
                .mailPassword(request.mailPassword())
                .createDate(request.createDate() != null ? request.createDate() : defaultCreateDate(defaults))
                .updateStatus(defaults.getUpdateStatus())
                .operator(operator)
                .foto_instagram(request.fotoInstagram())
                .active(request.active() != null ? request.active() : defaults.isActive())
                .build();
    }

    private LocalDate defaultCreateDate(TelephoneDTO defaults) {
        return defaults.getCreateDate() != null ? defaults.getCreateDate() : LocalDate.now();
    }

    private PhoneResponse toResponse(TelephoneDTO phone) {
        return new PhoneResponse(
                phone.getId(),
                phone.getNumber(),
                phone.getFio(),
                phone.getBirthday(),
                phone.getAmountAllowed(),
                phone.getAmountSent(),
                phone.getBlockTime(),
                phone.getTimer(),
                phone.getGoogleLogin(),
                phone.getGooglePassword(),
                phone.getAvitoPassword(),
                phone.getMailLogin(),
                phone.getMailPassword(),
                phone.getFoto_instagram(),
                phone.isActive(),
                phone.getCreateDate(),
                phone.getUpdateStatus(),
                toOperatorOption(phone.getOperator()),
                deviceTokens(phone.getId())
        );
    }

    private List<DeviceTokenResponse> deviceTokens(Long telephoneId) {
        if (telephoneId == null) {
            return List.of();
        }

        return deviceTokenRepository.findByTelephone_IdOrderByCreatedAtDesc(telephoneId).stream()
                .map(deviceToken -> new DeviceTokenResponse(
                        deviceToken.getToken(),
                        deviceToken.getCreatedAt(),
                        deviceToken.isActive()
                ))
                .toList();
    }

    private List<PhoneOperatorOptionResponse> operatorOptions() {
        return operatorService.getAllOperators().stream()
                .map(this::toOperatorOption)
                .filter(option -> option.id() != null)
                .sorted(Comparator.comparing(PhoneOperatorOptionResponse::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private PhoneOperatorOptionResponse toOperatorOption(Operator operator) {
        if (operator == null) {
            return new PhoneOperatorOptionResponse(null, "-");
        }

        String title = operator.getUser() != null && operator.getUser().getFio() != null
                ? operator.getUser().getFio()
                : "Оператор #" + operator.getId();

        return new PhoneOperatorOptionResponse(operator.getId(), title);
    }

    private boolean matchesKeyword(TelephoneDTO phone, String keyword) {
        if (keyword.isBlank()) {
            return true;
        }

        return contains(phone.getNumber(), keyword)
                || contains(phone.getFio(), keyword)
                || contains(phone.getGoogleLogin(), keyword)
                || contains(phone.getMailLogin(), keyword)
                || contains(phone.getFoto_instagram(), keyword)
                || contains(phone.getOperator() != null && phone.getOperator().getUser() != null
                ? phone.getOperator().getUser().getFio()
                : null, keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }
}
