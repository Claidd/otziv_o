package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.c_companies.dto.FilialDeletionPreview;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.FilialRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class FilialServiceImpl implements FilialService{

    private final FilialRepository filialRepository;
    private final CityRepository cityRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;

    public FilialServiceImpl(
            FilialRepository filialRepository,
            CityRepository cityRepository,
            OrderRepository orderRepository,
            ReviewRepository reviewRepository
    ) {
        this.filialRepository = filialRepository;
        this.cityRepository = cityRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
    }

    public Filial save(FilialDTO filialDTO){ // Сохранение филиала в БД
        Filial filial = new Filial();
        filial.setTitle(filialDTO.getTitle());
        filial.setUrl(filialDTO.getUrl());
        filial.setCity(cityRepository.findById(filialDTO.getCity().getId()));
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

    public Filial findFilialByUrl(String url) { // Взять филиал по названию и ссылке
        return filialRepository.findByUrl(url);
    } // Взять филиал по названию и ссылке

    @Override
    public List<Filial> findByCityId(Long id) {
        return filialRepository.findByCityId(id);
    }

    @Transactional
    public void deleteFilial(Long filialId){ // Безопасное удаление филиала для старых вызовов
        Filial filial = requireFilial(filialId);
        deleteOrArchive(companyId(filial), filialId);
    } // Удаление филиала

    @Override
    @Transactional(readOnly = true)
    public FilialDeletionPreview previewDeletion(Long companyId, Long filialId) {
        Filial filial = requireOwnedFilial(companyId, filialId);
        long orderCount = orderRepository.countByFilial_Id(filial.getId());
        long reviewCount = reviewRepository.countByFilial_Id(filial.getId());
        return new FilialDeletionPreview(
                filial.getId(),
                orderCount,
                reviewCount,
                orderCount > 0 || reviewCount > 0
        );
    }

    @Override
    @Transactional
    public FilialDeletionPreview deleteOrArchive(Long companyId, Long filialId) {
        Filial filial = requireOwnedFilial(companyId, filialId);
        FilialDeletionPreview preview = previewDeletion(companyId, filialId);
        if (preview.willArchive()) {
            filial.setArchived(true);
            filial.setArchivedAt(LocalDateTime.now());
            filialRepository.save(filial);
            log.info("Филиал {} компании {} отправлен в архив: orders={}, reviews={}",
                    filialId, companyId, preview.orderCount(), preview.reviewCount());
        } else {
            filialRepository.delete(filial);
            log.info("Неиспользуемый филиал {} компании {} физически удален", filialId, companyId);
        }
        return preview;
    }

    @Override
    @Transactional
    public void restoreFilial(Long companyId, Long filialId) {
        Filial filial = requireOwnedFilial(companyId, filialId);
        filial.setArchived(false);
        filial.setArchivedAt(null);
        filialRepository.save(filial);
        log.info("Филиал {} компании {} восстановлен из архива", filialId, companyId);
    }

    public FilialDTO getFilialByIdToDTO(Long id){ // Взять филиал дто по Id
        return convertToFilialDto(Objects.requireNonNull(filialRepository.findById(id).orElse(null)));
    } // Взять филиал дто по Id
    private FilialDTO convertToFilialDto(Filial filial) { // перевод филиала в дто
        FilialDTO filialDTO = new FilialDTO();
        filialDTO.setId(filial.getId());
        filialDTO.setTitle(filial.getTitle());
        filialDTO.setUrl(filial.getUrl());
        filialDTO.setCity(filial.getCity());
        filialDTO.setArchived(filial.isArchived());
        filialDTO.setArchivedAt(filial.getArchivedAt());
        return filialDTO; // перевод филиала в дто
    }

    private Filial requireOwnedFilial(Long companyId, Long filialId) {
        Filial filial = requireFilial(filialId);
        if (filial.getCompany() == null || !Objects.equals(companyId, filial.getCompany().getId())) {
            throw new UsernameNotFoundException(String.format(
                    "Филиал '%d' не найден у компании '%d'", filialId, companyId));
        }
        return filial;
    }

    private Filial requireFilial(Long filialId) {
        return filialRepository.findById(filialId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Филиал '%d' не найден", filialId)));
    }

    private Long companyId(Filial filial) {
        if (filial.getCompany() == null || filial.getCompany().getId() == null) {
            throw new IllegalStateException("У филиала не указана компания");
        }
        return filial.getCompany().getId();
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

        if (!Objects.equals(filialDTO.getTitle(), saveFilial.getTitle())){ /*Проверка смены названия*/
            log.info("Обновляем названия филиала");
            saveFilial.setTitle(filialDTO.getTitle());
            isChanged = true;
        }
        if (!Objects.equals(filialDTO.getCity().getId(), saveFilial.getCity().getId())){ /*Проверка смены URL*/
            log.info("Обновляем Город");
            saveFilial.setCity(cityRepository.findById(filialDTO.getCity().getId()));
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
