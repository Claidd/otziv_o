package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.FilialRepository;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Worker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
public class FilialServiceImpl implements FilialService{

    private final FilialRepository filialRepository;

    public FilialServiceImpl(FilialRepository filialRepository) {
        this.filialRepository = filialRepository;
    }

    public Filial save(FilialDTO filialDTO){
        Filial filial = new Filial();
        filial.setTitle(filialDTO.getTitle());
        filial.setUrl(filialDTO.getUrl());
        return filialRepository.save(filial);
    }

    public Filial save(Filial filial2){

        return filialRepository.save(filial2);
    }

    public Filial getFilial(Long id){
        return filialRepository.findById(id).orElse(null);
    }


    public Filial findFilialByTitleAndUrl(String title, String url) {
        return filialRepository.findByTitleAndUrl(title, url);
    }

    public void deleteFilial(Long filialId){
        filialRepository.deleteById(filialId);
    }

    public FilialDTO getFilialByIdToDTO(Long id){
        return convertToFilialDto(Objects.requireNonNull(filialRepository.findById(id).orElse(null)));
    }
    private FilialDTO convertToFilialDto(Filial filial) {
        FilialDTO filialDTO = new FilialDTO();
        filialDTO.setId(filial.getId());
        filialDTO.setTitle(filial.getTitle());
        filialDTO.setUrl(filial.getUrl());
        // Other fields if needed
        return filialDTO;
    }

    //    ======================================== FILIAL UPDATE =========================================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateFilial(FilialDTO filialDTO) {
        log.info("2. Вошли в обновление данных филиала");
        Filial saveFilial = filialRepository.findById(filialDTO.getId()).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%s' не найден", filialDTO.getTitle())));
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        System.out.println("title: " + !Objects.equals(filialDTO.getTitle(), saveFilial.getTitle()));
        System.out.println("url: " + !Objects.equals(filialDTO.getUrl(), saveFilial.getUrl()));

        if (!Objects.equals(filialDTO.getTitle(), saveFilial.getTitle())){ /*Проверка смены названия*/
            log.info("Обновляем названия филиала");
            saveFilial.setTitle(filialDTO.getTitle());
            isChanged = true;
        }
        if (!Objects.equals(filialDTO.getUrl(), saveFilial.getUrl())){ /*Проверка смены URL*/
            log.info("Обновляем URL");
            saveFilial.setUrl(filialDTO.getUrl());
            isChanged = true;
        }

        if  (isChanged){
            log.info("3. Начали сохранять обновленный филиал в БД");
            filialRepository.save(saveFilial);
            log.info("4. Сохранили обновленный филиал в БД");
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    }

//    =====================================================================================================
}
