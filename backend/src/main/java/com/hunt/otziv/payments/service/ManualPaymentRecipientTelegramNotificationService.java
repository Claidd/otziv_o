package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManualPaymentRecipientTelegramNotificationService {

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat(
            "#,##0.##",
            DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru-RU"))
    );

    private final UserRepository userRepository;
    private final ManagerRepository managerRepository;
    private final TelegramService telegramService;
    private final PaymentLinkRepository paymentLinkRepository;

    public void notifyAfterCommit(PaymentLink link) {
        buildRequest(link).ifPresent(this::notifyAfterCommit);
    }

    @Transactional(readOnly = true)
    public void notifyAfterCommit(Long paymentLinkId) {
        if (paymentLinkId == null) {
            return;
        }
        paymentLinkRepository.findByIdWithOrder(paymentLinkId)
                .ifPresent(this::notifyAfterCommit);
    }

    void notifyAfterCommit(NotificationRequest request) {
        Runnable send = () -> notifyNow(request);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
            return;
        }
        send.run();
    }

    void notifyNow(NotificationRequest request) {
        try {
            if (request == null || request.recipientUserId() == null || request.recipientType() == null) {
                return;
            }
            Optional<User> userOpt = userRepository.findById(request.recipientUserId());
            if (userOpt.isEmpty()) {
                log.warn(
                        "Не отправлено уведомление получателю ручной оплаты: пользователь не найден linkId={} userId={}",
                        request.linkId(),
                        request.recipientUserId()
                );
                return;
            }
            User user = userOpt.get();
            Long chatId = resolveChatId(request.recipientType(), user);
            if (chatId == null) {
                log.info(
                        "Не отправлено уведомление получателю ручной оплаты: нет внутреннего Telegram-чата linkId={} userId={} type={}",
                        request.linkId(),
                        request.recipientUserId(),
                        request.recipientType()
                );
                return;
            }
            boolean sent = telegramService.sendMessage(chatId, buildMessage(request, user));
            if (!sent) {
                log.warn(
                        "Telegram не подтвердил отправку уведомления получателю ручной оплаты linkId={} chatId={}",
                        request.linkId(),
                        chatId
                );
            }
        } catch (Exception exception) {
            log.warn(
                    "Не удалось отправить уведомление получателю ручной оплаты linkId={}: {}",
                    request == null ? null : request.linkId(),
                    exception.getMessage()
            );
        }
    }

    private Optional<NotificationRequest> buildRequest(PaymentLink link) {
        if (link == null
                || link.getId() == null
                || link.getStatus() != PaymentLinkStatus.CONFIRMED
                || !isManualPayment(link)
                || link.getManualActualRecipientType() == null
                || link.getManualActualRecipientUserId() == null) {
            return Optional.empty();
        }
        Long amountKopecks = link.getConfirmedAmountKopecks() == null
                ? link.getAmountKopecks()
                : link.getConfirmedAmountKopecks();
        var order = link.getOrder();
        String companyTitle = order == null || order.getCompany() == null ? null : order.getCompany().getTitle();
        String filialTitle = order == null || order.getFilial() == null ? null : order.getFilial().getTitle();
        return Optional.of(new NotificationRequest(
                link.getId(),
                order == null ? null : order.getId(),
                companyTitle,
                filialTitle,
                amountKopecks == null ? 0L : amountKopecks,
                link.getManualActualRecipientType(),
                link.getManualActualRecipientUserId(),
                link.getManualConfirmedBy(),
                link.getManualConfirmedAt() == null ? link.getPaidAt() : link.getManualConfirmedAt()
        ));
    }

    private static boolean isManualPayment(PaymentLink link) {
        return link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK
                || link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK;
    }

    private Long resolveChatId(ContractorRecipientType recipientType, User user) {
        if (recipientType == ContractorRecipientType.SPECIALIST) {
            return firstNonNull(user.getWorkerTelegramGroupChatId(), user.getTelegramChatId());
        }
        if (recipientType == ContractorRecipientType.MANAGER) {
            Long managerGroupChatId = managerRepository.findByUserId(user.getId())
                    .map(Manager::getAuditTelegramGroupChatId)
                    .orElse(null);
            return firstNonNull(managerGroupChatId, user.getTelegramChatId());
        }
        return user.getTelegramChatId();
    }

    private String buildMessage(NotificationRequest request, User user) {
        StringBuilder message = new StringBuilder();
        message.append("💳 Оплата по реквизитам подтверждена\n\n");
        message.append("Получатель: ").append(displayName(user)).append('\n');
        message.append("Сумма: ").append(formatMoney(request.amountKopecks())).append('\n');
        if (request.orderId() != null) {
            message.append("Заказ №").append(request.orderId()).append('\n');
        }
        String company = companyLine(request.companyTitle(), request.filialTitle());
        if (!company.isBlank()) {
            message.append("Компания: ").append(company).append('\n');
        }
        String actorDisplayName = resolveActorDisplayName(request.actor());
        if (hasText(actorDisplayName)) {
            message.append("Подтвердил: ").append(actorDisplayName).append('\n');
        }
        if (request.confirmedAt() != null) {
            message.append("Время: ").append(DATE_TIME_FORMAT.format(request.confirmedAt())).append('\n');
        }
        message.append('\n').append("Зачтено в расчёт получателя.");
        return message.toString();
    }

    private String resolveActorDisplayName(String actor) {
        if (!hasText(actor)) {
            return null;
        }
        String identity = actor.trim();
        try {
            Optional<User> byUsername = userRepository.findByUsername(identity);
            if (byUsername.isPresent()) {
                return displayName(byUsername.get());
            }
            User byEmail = userRepository.findByEmail(identity);
            return byEmail == null ? identity : displayName(byEmail);
        } catch (RuntimeException exception) {
            log.warn("Не удалось определить имя подтвердившего оплату actor={}: {}", identity, exception.getMessage());
            return identity;
        }
    }

    private static String displayName(User user) {
        if (user == null) {
            return "—";
        }
        if (hasText(user.getFio())) {
            return user.getFio().trim();
        }
        if (hasText(user.getUsername())) {
            return user.getUsername().trim();
        }
        return "Пользователь #" + user.getId();
    }

    private static String companyLine(String companyTitle, String filialTitle) {
        String company = hasText(companyTitle) ? companyTitle.trim() : "";
        String filial = hasText(filialTitle) ? filialTitle.trim() : "";
        if (!company.isBlank() && !filial.isBlank()) {
            return company + " — " + filial;
        }
        return company.isBlank() ? filial : company;
    }

    private static String formatMoney(long kopecks) {
        BigDecimal rubles = BigDecimal.valueOf(kopecks, 2).stripTrailingZeros();
        return MONEY_FORMAT.format(rubles) + " ₽";
    }

    private static Long firstNonNull(Long first, Long second) {
        return first == null ? second : first;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    record NotificationRequest(
            Long linkId,
            Long orderId,
            String companyTitle,
            String filialTitle,
            long amountKopecks,
            ContractorRecipientType recipientType,
            Long recipientUserId,
            String actor,
            LocalDateTime confirmedAt
    ) {
    }
}
