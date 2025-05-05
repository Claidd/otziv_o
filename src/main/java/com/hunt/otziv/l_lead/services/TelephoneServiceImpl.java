package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.dto.TelephoneDTO;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelephoneServiceImpl implements TelephoneService {

    private final TelephoneRepository telephoneRepository;
    private final OperatorRepository operatorRepository;

    public Telephone getTelephoneById(Long telephoneId){
        return telephoneRepository.findById(telephoneId).orElse(null);
    }

    public TelephoneDTO getTelephoneDTOById(Long telephoneId){
        return toDTO(Objects.requireNonNull(telephoneRepository.findById(telephoneId).orElse(null)));
    }

    @Override
    public void updatePhone(Long id, TelephoneDTO dto) {
        Telephone existing = telephoneRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Телефон не найден"));

        boolean changed = false;

        if (!Objects.equals(existing.getNumber(), dto.getNumber())) {
            existing.setNumber(dto.getNumber());
            changed = true;
        }

        if (!Objects.equals(existing.getFio(), dto.getFio())) {
            existing.setFio(dto.getFio());
            changed = true;
        }

        if (existing.getAmountAllowed() != dto.getAmountAllowed()) {
            existing.setAmountAllowed(dto.getAmountAllowed());
            changed = true;
        }

        if (existing.getAmountSent() != dto.getAmountSent()) {
            existing.setAmountSent(dto.getAmountSent());
            changed = true;
        }

        if (existing.getBlockTime() != dto.getBlockTime()) {
            existing.setBlockTime(dto.getBlockTime());
            changed = true;
        }

        if (!Objects.equals(existing.getTimer(), dto.getTimer())) {
            existing.setTimer(dto.getTimer());
            changed = true;
        }

        if (!Objects.equals(existing.getGoogleLogin(), dto.getGoogleLogin())) {
            existing.setGoogleLogin(dto.getGoogleLogin());
            changed = true;
        }

        if (!Objects.equals(existing.getGooglePassword(), dto.getGooglePassword())) {
            existing.setGooglePassword(dto.getGooglePassword());
            changed = true;
        }

        if (!Objects.equals(existing.getAvitoPassword(), dto.getAvitoPassword())) {
            existing.setAvitoPassword(dto.getAvitoPassword());
            changed = true;
        }

        if (!Objects.equals(existing.getMailLogin(), dto.getMailLogin())) {
            existing.setMailLogin(dto.getMailLogin());
            changed = true;
        }

        if (!Objects.equals(existing.getMailPassword(), dto.getMailPassword())) {
            existing.setMailPassword(dto.getMailPassword());
            changed = true;
        }

        if (!Objects.equals(existing.getCreateDate(), dto.getCreateDate())) {
            existing.setCreateDate(dto.getCreateDate());
            changed = true;
        }

        if (!Objects.equals(existing.getBirthday(), dto.getBirthday())) {
            existing.setBirthday(dto.getBirthday());
            changed = true;
        }

        if (!Objects.equals(existing.getUpdateStatus(), dto.getUpdateStatus())) {
            existing.setUpdateStatus(dto.getUpdateStatus()); // это поле можно не трогать, если ты хочешь сам выставлять текущее время ниже
            changed = true;
        }

        if (!Objects.equals(existing.getFoto_instagram(), dto.getFoto_instagram())) {
            existing.setFoto_instagram(dto.getFoto_instagram());
            changed = true;
        }

        if (existing.isActive() != dto.isActive()) {
            existing.setActive(dto.isActive());
            changed = true;
        }

        if (dto.getOperator() != null && (
                existing.getTelephoneOperator() == null ||
                        !Objects.equals(existing.getTelephoneOperator().getId(), dto.getOperator().getId()))) {
            Operator operator = operatorRepository.findById(dto.getOperator().getId())
                    .orElseThrow(() -> new EntityNotFoundException("Оператор не найден"));
            existing.setTelephoneOperator(operator);
            changed = true;
        }

        if (changed) {
            existing.setUpdateStatus(LocalDateTime.now());
            telephoneRepository.save(existing);
        }
    }


    @Override
    public List<TelephoneDTO> getAllTelephones() {
        List<Telephone> telephones = (List<Telephone>) telephoneRepository.findAll();
        return telephones.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    private TelephoneDTO toDTO(Telephone tel) {
        return TelephoneDTO.builder()
                .id(tel.getId())
                .number(tel.getNumber())
                .fio(tel.getFio())
                .birthday(tel.getBirthday())
                .amountAllowed(tel.getAmountAllowed())
                .amountSent(tel.getAmountSent())
                .blockTime(tel.getBlockTime())
                .timer(tel.getTimer() != null ? tel.getTimer().withSecond(0).withNano(0) : null) // <-- вот здесь
                .googleLogin(tel.getGoogleLogin())
                .googlePassword(tel.getGooglePassword())
                .avitoPassword(tel.getAvitoPassword())
                .mailLogin(tel.getMailLogin())
                .mailPassword(tel.getMailPassword())
                .createDate(tel.getCreateDate())
                .updateStatus(tel.getUpdateStatus() != null ? tel.getUpdateStatus().withSecond(0).withNano(0) : null)
                .operator(tel.getTelephoneOperator())
                .foto_instagram(tel.getFoto_instagram())
                .active(tel.isActive())
                .build();
    }

    public TelephoneDTO createEmptyDTO() {
        return TelephoneDTO.builder()
                .active(true) // ✅ по умолчанию активен
                .number("+7")
                .amountAllowed(1)
                .amountSent(0)
                .blockTime(3)
                .build();
    }



    public void createTelephone(TelephoneDTO dto) {
        Operator operator = null;
        if (dto.getOperator() != null && dto.getOperator().getId() != null) {
            operator = operatorRepository.findById(dto.getOperator().getId())
                    .orElseThrow(() -> new EntityNotFoundException("Оператор не найден"));
        }

        Telephone tel = Telephone.builder()
                .id(null)
                .number(dto.getNumber())
                .fio(dto.getFio())
                .birthday(dto.getBirthday() != null ? dto.getBirthday() : LocalDate.now())
                .amountAllowed(dto.getAmountAllowed())
                .amountSent(dto.getAmountSent())
                .blockTime(dto.getBlockTime())
                .timer(LocalDateTime.now())
                .googleLogin(dto.getGoogleLogin())
                .googlePassword(dto.getGooglePassword())
                .avitoPassword(dto.getAvitoPassword())
                .mailLogin(dto.getMailLogin())
                .mailPassword(dto.getMailPassword())
                .createDate(dto.getCreateDate() != null ? dto.getCreateDate() : LocalDate.now())
                .updateStatus(LocalDateTime.now())
                .foto_instagram(dto.getFoto_instagram())
                .active(dto.isActive())
                .telephoneOperator(operator)
                .build();

        telephoneRepository.save(tel);
    }

    public Telephone saveTelephone(Telephone telephone){
        return telephoneRepository.save(telephone);
    }
    @Override
    public void deletePhone(Long phoneId) {
        telephoneRepository.deleteById(phoneId);
    }
}
