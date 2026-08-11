package com.hunt.otziv.p_products.editing.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.service.FilialService;
import com.hunt.otziv.contractor_payments.service.ContractorRouteAssignmentGuard;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderEditService {

    private final OrderRepository orderRepository;
    private final WorkerService workerService;
    private final ManagerService managerService;
    private final FilialService filialService;
    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;
    private final ContractorRouteAssignmentGuard contractorRouteAssignmentGuard;

    @Transactional
    public void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId) {
        log.info("2. Вошли в обновление данных Заказа");

        Order saveOrder = orderAggregateMutationLockService.lock(orderId);
        requireCanonicalCompany(saveOrder, companyId);

        log.info("Достали Заказ");
        boolean isChanged = false;

        Filial currentFilial = saveOrder.getFilial();
        Worker currentWorker = saveOrder.getWorker();
        Manager currentManager = saveOrder.getManager();
        Company company = saveOrder.getCompany();

        Long dtoFilialId = orderDTO.getFilial() != null ? orderDTO.getFilial().getId() : null;
        Long currentFilialId = currentFilial != null ? currentFilial.getId() : null;
        if (dtoFilialId != null && !Objects.equals(dtoFilialId, currentFilialId)) {
            log.info("Обновляем филиал заказа");

            Filial newFilial = convertFilialDTOToFilial(orderDTO);
            requireFilialBelongsToCompany(newFilial, company);
            saveOrder.setFilial(newFilial);

            for (Review review : reviewRepository.getAllByOrderId(saveOrder.getId())) {
                review.setFilial(newFilial);
                reviewService.save(review);
                log.info("Сменили филиал у отзыва в заказе");
            }

            isChanged = true;
        }

        Long dtoWorkerId = orderDTO.getWorker() != null ? orderDTO.getWorker().getWorkerId() : null;
        Long currentWorkerId = currentWorker != null ? currentWorker.getId() : null;
        boolean unpublishedReviewMismatch =
                reviewRepository.existsUnpublishedReviewAssignedToAnotherWorker(
                        saveOrder.getId(),
                        dtoWorkerId
                );

        if (!Objects.equals(dtoWorkerId, currentWorkerId) || unpublishedReviewMismatch) {

            log.info("Обновляем работника заказа");
            contractorRouteAssignmentGuard.requireWorkerReassignmentAllowed(saveOrder.getId());
            Worker newWorker = convertWorkerDTOToWorker(orderDTO);
            requireWorkerBelongsToCompanyManager(newWorker, company);
            saveOrder.setWorker(newWorker);

            reviewRepository.reassignWorkerByOrderId(saveOrder.getId(), newWorker);
            badReviewTaskService.reassignPendingTasksForOrder(saveOrder.getId(), newWorker);
            reviewRecoveryTaskService.reassignPendingTasksForOrder(saveOrder.getId(), newWorker);
            if (company != null && newWorker != null) {
                if (company.getWorkers() == null) {
                    company.setWorkers(new HashSet<>());
                }
                company.getWorkers().add(newWorker);
            }

            isChanged = true;
        }

        Long dtoManagerId = orderDTO.getManager() != null ? orderDTO.getManager().getManagerId() : null;
        Long currentManagerId = currentManager != null ? currentManager.getId() : null;
        if (dtoManagerId != null && !Objects.equals(dtoManagerId, currentManagerId)) {
            log.info("Обновляем менеджера заказа");
            contractorRouteAssignmentGuard.requireManagerReassignmentAllowed(saveOrder.getId());
            Manager newManager = convertManagerDTOToManager(orderDTO);
            if (newManager == null) {
                throw new IllegalArgumentException("Менеджер не найден");
            }
            saveOrder.setManager(newManager);
            isChanged = true;
        }

        if (!Objects.equals(orderDTO.isComplete(), saveOrder.isComplete())) {
            log.info("Обновляем статус выполнения Заказа");
            contractorRouteAssignmentGuard.requirePayableMutationAllowed(saveOrder.getId());
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

        int actualPublished = reviewRepository.countPublishedByOrderId(saveOrder.getId());
        if (actualPublished != saveOrder.getCounter()) {
            log.warn("Исправляем счетчик заказа {} по фактическим отзывам: {} -> {}",
                    saveOrder.getId(), saveOrder.getCounter(), actualPublished);
            saveOrder.setCounter(actualPublished);
            isChanged = true;
        }

        saveIfChanged(saveOrder, isChanged);
    }

    @Transactional
    public void updateOrderToWorker(OrderDTO orderDTO, Long companyId, Long orderId) {
        log.info("2. Вошли в обновление данных Заказа Для работника");

        Order saveOrder = orderAggregateMutationLockService.lock(orderId);
        requireCanonicalCompany(saveOrder, companyId);

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

    private void requireCanonicalCompany(Order order, Long companyId) {
        Long canonicalCompanyId = order.getCompany() == null ? null : order.getCompany().getId();
        if (canonicalCompanyId == null || !Objects.equals(canonicalCompanyId, companyId)) {
            throw new IllegalArgumentException("Заказ не принадлежит указанной компании");
        }
    }

    private void requireFilialBelongsToCompany(Filial filial, Company company) {
        if (filial == null || filial.getCompany() == null || company == null
                || !Objects.equals(filial.getCompany().getId(), company.getId())) {
            throw new IllegalArgumentException("Филиал не принадлежит компании заказа");
        }
        if (filial.isArchived()) {
            throw new IllegalArgumentException("Архивный филиал нельзя назначить заказу");
        }
    }

    private void requireWorkerBelongsToCompanyManager(Worker worker, Company company) {
        if (worker == null || company == null || company.getManager() == null) {
            throw new IllegalArgumentException("Специалист или менеджер компании не найден");
        }
        boolean assigned = workerService.getAllWorkersByManagerId(company.getManager().getId()).stream()
                .anyMatch(candidate -> Objects.equals(candidate.getWorkerId(), worker.getId()));
        if (!assigned) {
            throw new IllegalArgumentException("Специалист не закреплен за менеджером компании");
        }
    }
}
