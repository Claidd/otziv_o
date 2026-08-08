package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ContractorRewardAttributionServiceTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final BadReviewTaskRepository badReviewTaskRepository = mock(BadReviewTaskRepository.class);
    private final ContractorRewardAttributionService service = new ContractorRewardAttributionService(
            reviewRepository,
            badReviewTaskRepository
    );

    @Test
    void keepsCompletedWorkWithOriginalSpecialistAfterCardTransfer() {
        Worker first = worker(11L, 101L, "Первый специалист");
        Worker current = worker(12L, 102L, "Текущий специалист");
        Order order = order(77L, current, "1000.00", 2);

        when(reviewRepository.getAllByOrderId(77L)).thenReturn(List.of(
                published(first, "400.00"),
                published(current, "600.00")
        ));
        when(badReviewTaskRepository.findAllByOrderIdAndStatus(77L, BadReviewTaskStatus.DONE)).thenReturn(List.of(
                done(first, "200.00"),
                done(current, "300.00")
        ));

        Map<Long, ContractorRewardAttributionService.SpecialistShare> shares = service
                .attribute(order, new BigDecimal("1500.00"))
                .stream()
                .collect(Collectors.toMap(ContractorRewardAttributionService.SpecialistShare::workerId, value -> value));

        assertEquals(new BigDecimal("600.00"), shares.get(11L).grossAmount());
        assertEquals(new BigDecimal("900.00"), shares.get(12L).grossAmount());
        assertEquals(2, shares.get(11L).workUnits());
        assertEquals(2, shares.get(12L).workUnits());
    }

    @Test
    void roundsSharesWithoutLosingOrCreatingKopecks() {
        Worker first = worker(21L, 201L, "A");
        Worker second = worker(22L, 202L, "B");
        Worker third = worker(23L, 203L, "C");
        Order order = order(88L, third, "1000.00", 3);
        when(reviewRepository.getAllByOrderId(88L)).thenReturn(List.of(
                published(first, "1.00"),
                published(second, "1.00"),
                published(third, "1.00")
        ));
        when(badReviewTaskRepository.findAllByOrderIdAndStatus(88L, BadReviewTaskStatus.DONE))
                .thenReturn(List.of());

        List<ContractorRewardAttributionService.SpecialistShare> shares =
                service.attributeCompletedBaseWork(order);

        BigDecimal total = shares.stream()
                .map(ContractorRewardAttributionService.SpecialistShare::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("1000.00"), total);
        assertEquals(List.of(new BigDecimal("333.34"), new BigDecimal("333.33"), new BigDecimal("333.33")),
                shares.stream().map(ContractorRewardAttributionService.SpecialistShare::grossAmount).toList());
    }

    @Test
    void strictCompletionRejectsMissingWorkerWithoutCurrentCardFallback() {
        Worker current = worker(31L, 301L, "Текущий специалист");
        Order order = order(91L, current, "1000.00", 1);
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(published(null, "1000.00")));

        assertThrows(ResponseStatusException.class, () -> service.attributeCompletedBaseWork(order));
    }

    @Test
    void strictCompletionRejectsPublishedCountAboveOrderAmount() {
        Worker worker = worker(32L, 302L, "Специалист");
        Order order = order(92L, worker, "1000.00", 1);
        when(reviewRepository.getAllByOrderId(92L)).thenReturn(List.of(
                published(worker, "500.00"),
                published(worker, "500.00")
        ));

        assertThrows(ResponseStatusException.class, () -> service.attributeCompletedBaseWork(order));
    }

    @Test
    void strictCompletionRejectsMixedDatedAndUndatedPublishedEvidence() {
        Worker worker = worker(33L, 303L, "Специалист");
        Order order = order(93L, worker, "1000.00", 2);
        Review dated = published(worker, "500.00");
        Review undated = published(worker, "500.00");
        undated.setPublishedDate(null);
        when(reviewRepository.getAllByOrderId(93L)).thenReturn(List.of(dated, undated));

        assertThrows(ResponseStatusException.class, () -> service.attributeCompletedBaseWork(order));
    }

    private Order order(Long id, Worker currentWorker, String sum, int amount) {
        Order order = new Order();
        order.setId(id);
        order.setWorker(currentWorker);
        order.setSum(new BigDecimal(sum));
        order.setAmount(amount);
        return order;
    }

    private Worker worker(Long workerId, Long userId, String fio) {
        User user = new User();
        user.setId(userId);
        user.setFio(fio);
        Worker worker = new Worker();
        worker.setId(workerId);
        worker.setUser(user);
        return worker;
    }

    private Review published(Worker worker, String price) {
        Review review = new Review();
        review.setWorker(worker);
        review.setPrice(new BigDecimal(price));
        review.setPublish(true);
        review.setPublishedDate(LocalDate.of(2026, 8, 1));
        return review;
    }

    private BadReviewTask done(Worker worker, String price) {
        BadReviewTask task = new BadReviewTask();
        task.setWorker(worker);
        task.setPrice(new BigDecimal(price));
        task.setStatus(BadReviewTaskStatus.DONE);
        return task;
    }
}
