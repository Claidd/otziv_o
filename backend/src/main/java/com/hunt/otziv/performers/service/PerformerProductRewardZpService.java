package com.hunt.otziv.performers.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformerProductRewardZpService {

    public static final String SOURCE = "PERFORMER_PRODUCT_REWARD";

    private final AppSettingService appSettingService;
    private final OrderRepository orderRepository;
    private final ZpRepository zpRepository;

    @Transactional
    public int accrueForPaidOrder(Long orderId) {
        if (!appSettingService.getBoolean(AppSettingService.ZP_PRODUCT_REWARD_PERCENT_ENABLED, false)) {
            log.debug("Начисления по продуктам с исполнителями выключены: orderId={}", orderId);
            return 0;
        }
        if (orderId == null || zpRepository.existsByOrderIdAndSourceAndActiveTrue(orderId, SOURCE)) {
            return 0;
        }

        Order order = orderRepository.findByIdForOrderDto(orderId).orElse(null);
        if (order == null || !isPaid(order)) {
            return 0;
        }

        RewardTotals totals = rewardTotals(order.getDetails());
        int saved = 0;
        if (totals.managerAmount().compareTo(BigDecimal.ZERO) > 0) {
            saved += saveManagerReward(order, totals);
        }
        if (totals.specialistAmount().compareTo(BigDecimal.ZERO) > 0) {
            saved += saveSpecialistReward(order, totals);
        }
        if (saved > 0) {
            log.info(
                    "Начислена ЗП по продуктам с исполнителями: orderId={}, manager={}, specialist={}, rows={}",
                    orderId,
                    totals.managerAmount(),
                    totals.specialistAmount(),
                    saved
            );
        }
        return saved;
    }

    private int saveManagerReward(Order order, RewardTotals totals) {
        Manager manager = order.getManager();
        if (manager == null || manager.getUser() == null) {
            log.debug("ЗП менеджера по продуктам с исполнителями не начислена: у заказа {} нет менеджера", order.getId());
            return 0;
        }
        zpRepository.save(toZp(order, manager.getUser(), manager.getId(), totals.managerAmount(), totals.amount()));
        return 1;
    }

    private int saveSpecialistReward(Order order, RewardTotals totals) {
        Worker worker = order.getWorker();
        if (worker == null || worker.getUser() == null) {
            log.debug("ЗП специалиста по продуктам с исполнителями не начислена: у заказа {} нет специалиста", order.getId());
            return 0;
        }
        zpRepository.save(toZp(order, worker.getUser(), worker.getId(), totals.specialistAmount(), totals.amount()));
        return 1;
    }

    private Zp toZp(Order order, User user, Long professionId, BigDecimal sum, int amount) {
        Zp zp = new Zp();
        zp.setFio(user.getFio());
        zp.setSum(money(sum));
        zp.setOrderId(order.getId());
        zp.setUserId(user.getId());
        zp.setProfessionId(professionId);
        zp.setAmount(amount);
        zp.setActive(true);
        zp.setSource(SOURCE);
        return zp;
    }

    private RewardTotals rewardTotals(List<OrderDetails> details) {
        BigDecimal manager = BigDecimal.ZERO;
        BigDecimal specialist = BigDecimal.ZERO;
        int amount = 0;
        if (details == null) {
            return new RewardTotals(manager, specialist, amount);
        }
        for (OrderDetails detail : details) {
            Product product = detail != null ? detail.getProduct() : null;
            if (product == null || !product.isRequiresPerformer()) {
                continue;
            }
            BigDecimal base = detailBase(detail, product);
            manager = manager.add(percent(base, product.getManagerRewardPercent()));
            specialist = specialist.add(percent(base, product.getSpecialistRewardPercent()));
            amount += Math.max(0, detail.getAmount());
        }
        return new RewardTotals(money(manager), money(specialist), amount);
    }

    private BigDecimal detailBase(OrderDetails detail, Product product) {
        if (detail != null && detail.getPrice() != null) {
            return detail.getPrice();
        }
        BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
        int amount = detail == null ? 0 : Math.max(0, detail.getAmount());
        return price.multiply(BigDecimal.valueOf(amount));
    }

    private BigDecimal percent(BigDecimal base, BigDecimal percent) {
        if (base == null || percent == null || base.compareTo(BigDecimal.ZERO) <= 0 || percent.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return base.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isPaid(Order order) {
        return order != null && order.getStatus() != null && "Оплачено".equals(order.getStatus().getTitle());
    }

    private record RewardTotals(BigDecimal managerAmount, BigDecimal specialistAmount, int amount) {
    }
}
