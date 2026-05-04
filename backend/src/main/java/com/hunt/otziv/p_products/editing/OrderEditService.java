package com.hunt.otziv.p_products.editing;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getAllReviews;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getFirstReview;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderEditService {

    private final OrderRepository orderRepository;
    private final WorkerService workerService;
    private final ManagerService managerService;
    private final FilialService filialService;
    private final ReviewService reviewService;

    @Transactional
    public void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId) {
        log.info("2. Вошли в обновление данных Заказа");

        Order saveOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));

        log.info("Достали Заказ");
        boolean isChanged = false;

        Filial currentFilial = saveOrder.getFilial();
        Worker currentWorker = saveOrder.getWorker();
        Manager currentManager = saveOrder.getManager();
        Review firstReview = getFirstReview(saveOrder);
        Company company = saveOrder.getCompany();

        if (orderDTO.getFilial() != null && currentFilial != null &&
                !Objects.equals(orderDTO.getFilial().getId(), currentFilial.getId())) {
            log.info("Обновляем филиал заказа");

            Filial newFilial = convertFilialDTOToFilial(orderDTO);
            saveOrder.setFilial(newFilial);

            for (Review review : getAllReviews(saveOrder)) {
                review.setFilial(newFilial);
                reviewService.save(review);
                log.info("Сменили филиал у отзыва в заказе");
            }

            isChanged = true;
        }

        try {
            Long dtoWorkerId = orderDTO.getWorker() != null ? orderDTO.getWorker().getWorkerId() : null;
            Long currentWorkerId = currentWorker != null ? currentWorker.getId() : null;
            Long firstReviewWorkerId = firstReview != null && firstReview.getWorker() != null
                    ? firstReview.getWorker().getId()
                    : null;

            if (!Objects.equals(dtoWorkerId, currentWorkerId) ||
                    (firstReview != null && !Objects.equals(dtoWorkerId, firstReviewWorkerId))) {

                log.info("Обновляем работника заказа");
                Worker newWorker = convertWorkerDTOToWorker(orderDTO);
                saveOrder.setWorker(newWorker);

                for (OrderDetails detail : Optional.ofNullable(saveOrder.getDetails()).orElse(Collections.emptyList())) {
                    for (Review review : Optional.ofNullable(detail.getReviews()).orElse(Collections.emptyList())) {
                        review.setWorker(newWorker);
                    }
                }

                isChanged = true;
            }
        } catch (Exception e) {
            log.error("Ошибка при обновлении работника заказа: ", e);
        }

        if (orderDTO.getManager() != null && currentManager != null &&
                !Objects.equals(orderDTO.getManager().getManagerId(), currentManager.getId())) {
            log.info("Обновляем менеджера заказа");
            saveOrder.setManager(convertManagerDTOToManager(orderDTO));
            isChanged = true;
        }

        if (!Objects.equals(orderDTO.isComplete(), saveOrder.isComplete())) {
            log.info("Обновляем статус выполнения Заказа");
            saveOrder.setComplete(orderDTO.isComplete());
            isChanged = true;
        }

        if (!Objects.equals(orderDTO.getOrderComments(), saveOrder.getZametka())) {
            log.info("Обновляем комментарий заказа");
            saveOrder.setZametka(orderDTO.getOrderComments());
            isChanged = true;
        }

        if (company != null && !Objects.equals(orderDTO.getCommentsCompany(), company.getCommentsCompany())) {
            log.info("Обновляем комментарий компании");
            company.setCommentsCompany(orderDTO.getCommentsCompany());
            isChanged = true;
        }

        if (orderDTO.getCounter() != null && !Objects.equals(orderDTO.getCounter(), saveOrder.getCounter())) {
            log.info("Обновляем счетчик опубликованных текстов в заказе");
            saveOrder.setCounter(orderDTO.getCounter());
            isChanged = true;
        }

        saveIfChanged(saveOrder, isChanged);
    }

    @Transactional
    public void updateOrderToWorker(OrderDTO orderDTO, Long companyId, Long orderId) {
        log.info("2. Вошли в обновление данных Заказа Для работника");

        Order saveOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));

        log.info("Достали Заказ");
        boolean isChanged = false;

        Company company = saveOrder.getCompany();
        if (!Objects.equals(orderDTO.getOrderComments(), saveOrder.getZametka())) {
            log.info("Обновляем комментарий заказа");
            saveOrder.setZametka(orderDTO.getOrderComments());
            isChanged = true;
        }

        if (company != null && !Objects.equals(orderDTO.getCommentsCompany(), company.getCommentsCompany())) {
            log.info("Обновляем комментарий компании");
            company.setCommentsCompany(orderDTO.getCommentsCompany());
            isChanged = true;
        }

        saveIfChanged(saveOrder, isChanged);
    }

    private void saveIfChanged(Order saveOrder, boolean isChanged) {
        if (isChanged) {
            log.info("3. Начали сохранять обновленный Заказ в БД");
            orderRepository.save(saveOrder);
            log.info("4. Сохранили обновленный Заказ в БД");
        } else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    }

    private Worker convertWorkerDTOToWorker(OrderDTO orderDTO) {
        if (orderDTO.getWorker() == null || orderDTO.getWorker().getWorkerId() == null) {
            return null;
        }
        return workerService.getWorkerById(orderDTO.getWorker().getWorkerId());
    }

    private Manager convertManagerDTOToManager(OrderDTO orderDTO) {
        if (orderDTO.getManager() == null || orderDTO.getManager().getManagerId() == null) {
            return null;
        }
        return managerService.getManagerById(orderDTO.getManager().getManagerId());
    }

    private Filial convertFilialDTOToFilial(OrderDTO orderDTO) {
        if (orderDTO.getFilial() == null || orderDTO.getFilial().getId() == null) {
            return null;
        }
        return filialService.getFilial(orderDTO.getFilial().getId());
    }
}
