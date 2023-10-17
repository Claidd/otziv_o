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

    public Filial save(FilialDTO filialDTO){ // Сохранение филиала в БД
        Filial filial = new Filial();
        filial.setTitle(filialDTO.getTitle());
        filial.setUrl(filialDTO.getUrl());
        return filialRepository.save(filial);
    } // Сохранение филиала в БД

    public Filial save(Filial filial2){ // Сохранение филиала в БД2
        return filialRepository.save(filial2);
    } // Сохранение филиала в БД2

    public Filial getFilial(Long filialId){ // Взять филиал по Id
        return filialRepository.findById(filialId).orElse(null);
    } // Взять филиал по Id

    public Filial findFilialByTitleAndUrl(String title, String url) { // Взять филиал по названию и ссылке
        return filialRepository.findByTitleAndUrl(title, url);
    } // Взять филиал по названию и ссылке

    public void deleteFilial(Long filialId){ // Удаление филиала
        filialRepository.deleteById(filialId);
    } // Удаление филиала

    public FilialDTO getFilialByIdToDTO(Long id){ // Взять филиал дто по Id
        return convertToFilialDto(Objects.requireNonNull(filialRepository.findById(id).orElse(null)));
    } // Взять филиал дто по Id
    private FilialDTO convertToFilialDto(Filial filial) { // перевод филиала в дто
        FilialDTO filialDTO = new FilialDTO();
        filialDTO.setId(filial.getId());
        filialDTO.setTitle(filial.getTitle());
        filialDTO.setUrl(filial.getUrl());
        return filialDTO; // перевод филиала в дто
    }

    //    ======================================== FILIAL UPDATE =========================================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateFilial(FilialDTO filialDTO) { // Обновление филиала
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
    } // Обновление филиала

//    =====================================================================================================
}
