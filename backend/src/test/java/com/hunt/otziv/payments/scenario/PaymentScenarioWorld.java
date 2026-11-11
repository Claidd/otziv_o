package com.hunt.otziv.payments.scenario;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRoutingCommandRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSystemActivationRequest;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentBusinessClock;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRolloutStateService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentSystemAdminService;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankInitCommand;
import com.hunt.otziv.payments.dto.TbankInitResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.TbankTokenSigner;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Seed builder of the payment scenario suite: the people, companies, orders, payment profiles and the
 * common-billing link one scenario plays on, written straight into the migrated MySQL schema.
 *
 * <p>It only prepares the starting state. Routing, reservations, payments and notifications are always
 * produced by the production services the scenario calls afterwards; nothing here decides who receives
 * money. The single production command it issues is the owner's own activation of the new accounting and
 * of contractor requisites ({@link #activateLiveRouting}).</p>
 */
final class PaymentScenarioWorld {

    static final String STATUS_PUBLISHED = "Опубликовано";
    static final String STATUS_INVOICED = "Выставлен счет";
    static final String STATUS_PAID = "Оплачено";
    static final String STATUS_UNPAID = "Не оплачено";

    private static final String OWNER_USERNAME = "scenario-owner";
    private static final String ADMIN_USERNAME = "scenario-admin";
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager em;
    private final ContractorPaymentBusinessClock clock;
    private final String tag;

    /** Owner and administrator are shared by every scenario of one database; the manager is the scenario's own. */
    final Person owner;
    final Person admin;
    final Person manager;

    private PaymentScenarioWorld(ApplicationContext context) {
        this.jdbc = context.getBean(JdbcTemplate.class);
        this.tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        this.em = context.getBean(EntityManager.class);
        this.clock = context.getBean(ContractorPaymentBusinessClock.class);
        this.tag = "scn" + SEQUENCE.incrementAndGet();
        this.owner = sharedStaff(OWNER_USERNAME, "Владелец Сценариев", "ROLE_OWNER");
        this.admin = sharedStaff(ADMIN_USERNAME, "Администратор Сценариев", "ROLE_ADMIN");
        this.manager = newManager();
    }

    /** A fresh cast for one scenario: its own manager, with the shared owner and administrator above him. */
    static PaymentScenarioWorld open(ApplicationContext context) {
        return new PaymentScenarioWorld(context);
    }

    // ------------------------------------------------------------------------------------------------
    // Switches: what makes contractor LIVE routing and typed payment attribution active
    // ------------------------------------------------------------------------------------------------

    /**
     * Brings an empty migrated database to the production state «новый учёт включён, реквизиты сотрудников
     * выдаются». The app settings are the same rows the settings screens write; the accounting boundary and
     * the routing command go through the owner's production workflow, so every gate of
     * {@link ContractorPaymentRuntimeSwitch#liveRoutingEnabled()} is opened the way it is opened on prod.
     * Idempotent: a database that is already live is left untouched.
     */
    static void activateLiveRouting(ApplicationContext context) {
        ContractorPaymentRuntimeSwitch runtimeSwitch = context.getBean(ContractorPaymentRuntimeSwitch.class);
        if (settledLiveRouting(runtimeSwitch)) {
            return;
        }
        AppSettingService settings = context.getBean(AppSettingService.class);
        // Routing prerequisites checked by ContractorPaymentRuntimeSwitch.routingConfigurationBlockers().
        settings.setBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true);
        settings.setBoolean(AppSettingService.PAYMENTS_TBANK_ENABLED, true);
        settings.setBoolean(AppSettingService.PAYMENTS_TBANK_PAYMENT_LINKS_ENABLED, true);
        settings.setString(AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE, "BANK_LINK");
        settings.setBoolean(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED, true);
        settings.setBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true);
        settings.setBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true);

        PaymentScenarioWorld bootstrap = new PaymentScenarioWorld(context);
        ContractorPaymentSystemAdminService systemAdmin = context.getBean(ContractorPaymentSystemAdminService.class);
        ContractorPaymentRolloutStateService rollout = context.getBean(ContractorPaymentRolloutStateService.class);
        ContractorPaymentBusinessClock clock = context.getBean(ContractorPaymentBusinessClock.class);
        bootstrap.as(bootstrap.owner, () -> {
            systemAdmin.activate(new ContractorPaymentSystemActivationRequest(
                    clock.today(),
                    ContractorPaymentSystemAdminService.ACTIVATE_CONFIRMATION,
                    "Сценарный набор: включение нового учёта",
                    rollout.freshSnapshot().revision()
            ));
            systemAdmin.updateRouting(new ContractorPaymentRoutingCommandRequest(
                    true,
                    ContractorPaymentSystemAdminService.ENABLE_ROUTING_CONFIRMATION,
                    "Сценарный набор: включение реквизитов сотрудников",
                    rollout.freshSnapshot().revision()
            ));
        });
        if (!settledLiveRouting(runtimeSwitch)) {
            throw new IllegalStateException("LIVE-маршрутизация не включилась: " + runtimeSwitch.liveRoutingBlockers());
        }
    }

    /**
     * Every gate of the switch is read fresh and fails closed, so one slow read on a loaded machine answers
     * «off» for a moment. A scenario must not start, or re-run the activation, on such a blip.
     */
    private static boolean settledLiveRouting(ContractorPaymentRuntimeSwitch runtimeSwitch) {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (runtimeSwitch.liveRoutingEnabled()) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // People
    // ------------------------------------------------------------------------------------------------

    /** One user of the scenario with the ids of his manager/worker rows and his payment profiles. */
    static final class Person {
        final long userId;
        final String username;
        final String fio;
        final List<String> roles;
        /** Personal Telegram chat (positive) of the user. */
        final long telegramChatId;
        Long managerId;
        Long workerId;
        /** Work group of a specialist, or the audit group of a manager (negative Telegram chat id). */
        Long groupChatId;
        Long specialistProfileId;
        Long managerProfileId;

        private Person(long userId, String username, String fio, long telegramChatId, List<String> roles) {
            this.userId = userId;
            this.username = username;
            this.fio = fio;
            this.telegramChatId = telegramChatId;
            this.roles = roles;
        }

        /** What Spring Security would hold for this user after login. */
        Authentication auth() {
            return new TestingAuthenticationToken(username, "unused", roles.toArray(String[]::new));
        }
    }

    /** A specialist of the scenario manager's team (role, worker row, team link, work group). */
    Person specialist(String name) {
        Person person = user("spec" + SEQUENCE.incrementAndGet(), name, "0.20", "ROLE_WORKER");
        person.workerId = insert("INSERT INTO workers(user_id) VALUES (?)", person.userId);
        person.groupChatId = -(800_000_000L + person.userId);
        jdbc.update("UPDATE users SET worker_telegram_group_chat_id = ? WHERE id = ?", person.groupChatId, person.userId);
        // The specialist belongs to the manager's team and is listed among the manager's workers.
        jdbc.update("INSERT INTO managers_users(user_id, manager_id) VALUES (?, ?)", person.userId, manager.managerId);
        jdbc.update("INSERT INTO workers_users(user_id, worker_id) VALUES (?, ?)", manager.userId, person.workerId);
        return person;
    }

    /** A specialist who works for another manager: not in the team and not attached to the scenario companies. */
    Person outsiderSpecialist(String name) {
        Person person = user("out" + SEQUENCE.incrementAndGet(), name, "0.20", "ROLE_WORKER");
        person.workerId = insert("INSERT INTO workers(user_id) VALUES (?)", person.userId);
        person.groupChatId = -(800_000_000L + person.userId);
        jdbc.update("UPDATE users SET worker_telegram_group_chat_id = ? WHERE id = ?", person.groupChatId, person.userId);
        return person;
    }

    private Person newManager() {
        Person person = user("manager", "Менеджер " + tag, "0.10", "ROLE_MANAGER");
        person.groupChatId = -(900_000_000L + person.userId);
        person.managerId = insert(
                "INSERT INTO managers(user_id, client_id, group_id, audit_telegram_group_chat_id) VALUES (?, ?, ?, ?)",
                person.userId, tag + "-client", tag + "-group", person.groupChatId);
        // Owners see the invoices of the managers linked to them.
        jdbc.update("INSERT INTO managers_users(user_id, manager_id) VALUES (?, ?)", owner.userId, person.managerId);
        return person;
    }

    private Person sharedStaff(String username, String fio, String role) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, telegram_chat_id FROM users WHERE username = ?", username);
        if (!existing.isEmpty()) {
            return new Person(((Number) existing.getFirst().get("id")).longValue(), username, fio,
                    ((Number) existing.getFirst().get("telegram_chat_id")).longValue(), List.of(role));
        }
        // users.coefficient is NOT NULL; the owner and the administrator earn no share of an order.
        return insertUser(username, fio, "0.00", role);
    }

    private Person user(String label, String fio, String coefficient, String role) {
        return insertUser(tag + "-" + label, fio, coefficient, role);
    }

    private Person insertUser(String username, String fio, String coefficient, String role) {
        long telegramChatId = 700_000_000L + SEQUENCE.incrementAndGet();
        long userId = insert("""
                INSERT INTO users(username, password, fio, email, phone_number, active, create_time, coefficient, telegram_chat_id)
                VALUES (?, 'unused', ?, ?, ?, 1, CURRENT_DATE, ?, ?)
                """, username, fio, username + "@scenario.test", "+7900" + String.format("%07d", telegramChatId % 10_000_000L),
                coefficient == null ? null : new BigDecimal(coefficient), telegramChatId);
        jdbc.update("INSERT INTO users_roles(user_id, role_id) VALUES (?, ?)", userId, roleId(role));
        return new Person(userId, username, fio, telegramChatId, List.of(role));
    }

    private long roleId(String name) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM roles WHERE name = ? ORDER BY id LIMIT 1", Long.class, name);
        return ids.isEmpty() ? insert("INSERT INTO roles(name) VALUES (?)", name) : ids.getFirst();
    }

    // ------------------------------------------------------------------------------------------------
    // Contractor payment profiles: admission, requisites, available balance
    // ------------------------------------------------------------------------------------------------

    /** An admitted profile (расчёт и выдача клиентам включены) with full requisites and this available balance. */
    long payable(Person person, ContractorRole role, long balanceKopecks) {
        return profile(person, role, balanceKopecks, true, true);
    }

    /**
     * A contractor payment profile. The available balance («резерв») is given as the opening balance of
     * the profile: with no accruals, adjustments and reservations it is exactly what the person may accept.
     * {@code tracking_started_at} is the first day of the business month, i.e. the profile has already
     * passed the initial month synchronisation; a younger enabled profile is a global routing blocker.
     */
    long profile(Person person, ContractorRole role, long balanceKopecks, boolean enabled, boolean liveEnabled) {
        Long id = tx.execute(status -> {
            ContractorPaymentProfile profile = new ContractorPaymentProfile();
            profile.setUser(em.getReference(User.class, person.userId));
            profile.setRole(role);
            profile.setEnabled(enabled);
            profile.setLiveEnabled(liveEnabled);
            profile.setRecipientName(person.fio);
            profile.setPaymentPhone(transferNumber(person));
            profile.setBankName("Сбербанк");
            profile.setOpeningBalanceKopecks(balanceKopecks);
            profile.setTrackingStartedAt(clock.today().withDayOfMonth(1).atStartOfDay());
            em.persist(profile);
            em.flush();
            return profile.getId();
        });
        if (role == ContractorRole.SPECIALIST) {
            person.specialistProfileId = id;
        } else {
            person.managerProfileId = id;
        }
        return id;
    }

    /** The phone number the world puts into the person's payment profiles; the client must receive exactly it. */
    static String transferNumber(Person person) {
        return "+7901" + String.format("%07d", person.userId % 10_000_000L);
    }

    /** OWNER/ADMIN switches the admission of a profile off (both «расчёт» and «выдача клиентам»). */
    void switchAdmissionOff(long profileId) {
        jdbc.update("UPDATE contractor_payment_profiles SET enabled = 0, live_enabled = 0, row_version = row_version + 1 WHERE id = ?",
                profileId);
    }

    // ------------------------------------------------------------------------------------------------
    // Companies and completed orders
    // ------------------------------------------------------------------------------------------------

    /** A client company of the scenario manager with a chat the invoice can be delivered to. */
    long company(String title) {
        long n = SEQUENCE.incrementAndGet();
        return insert("""
                INSERT INTO companies(company_title, company_phone, company_city, company_active, company_user, company_manager,
                                      create_date, company_group_id, company_status, company_counter_no_pay, company_counter_pay, company_sum)
                VALUES (?, ?, 'Иркутск', 1, ?, ?, CURRENT_DATE, ?, ?, 0, 0, 0)
                """, title + " " + tag, "79" + String.format("%09d", 100_000_000L + n), manager.userId, manager.managerId,
                tag + "-chat-" + n, companyStatusId("В работе"));
    }

    /**
     * A finished order: one review, published today (business date) by the given specialist, in the given
     * status. This is what the payment transaction requires before it accepts money for the order.
     *
     * @param worker the order's specialist; {@code null} leaves the order without one (the review is then
     *               published by {@code reviewedBy})
     */
    long order(long companyId, Person worker, long amountKopecks, String statusTitle) {
        return order(companyId, worker, worker, amountKopecks, statusTitle, LocalDateTime.now());
    }

    long order(long companyId, Person worker, Person reviewedBy, long amountKopecks, String statusTitle,
               LocalDateTime statusChangedAt) {
        BigDecimal sum = BigDecimal.valueOf(amountKopecks, 2);
        LocalDate businessToday = clock.today();
        long orderId = insert("""
                INSERT INTO orders(order_created, order_changed, order_status_changed_at, order_status, order_amount, order_counter,
                                   order_sum, order_complete, order_waiting_for_client, order_manager, order_worker, order_company)
                VALUES (?, ?, ?, ?, 1, 1, ?, 0, 0, ?, ?, ?)
                """, businessToday, businessToday, statusChangedAt, orderStatusId(statusTitle), sum, manager.managerId,
                worker == null ? null : worker.workerId, companyId);
        String detailId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO order_details(order_detail_id, order_detail_order, order_detail_amount, order_detail_price)
                VALUES (UUID_TO_BIN(?), ?, 1, ?)
                """, detailId, orderId, sum);
        jdbc.update("""
                INSERT INTO reviews(review_text, review_created, review_created_at, review_changed, review_publish,
                                    review_publish_date, review_order_details, review_worker, review_vigul, review_price)
                VALUES ('Отзыв сценария', ?, ?, ?, 1, ?, UUID_TO_BIN(?), ?, 0, ?)
                """, businessToday, statusChangedAt, businessToday, businessToday, detailId,
                reviewedBy == null ? null : reviewedBy.workerId, sum);
        if (worker != null) {
            jdbc.update("INSERT IGNORE INTO workers_companies(worker_id, company_id) VALUES (?, ?)", worker.workerId, companyId);
        }
        return orderId;
    }

    private long orderStatusId(String title) {
        List<Long> ids = jdbc.queryForList(
                "SELECT order_status_id FROM order_statuses WHERE order_status_title = ? ORDER BY order_status_id LIMIT 1",
                Long.class, title);
        return ids.isEmpty() ? insert("INSERT INTO order_statuses(order_status_title) VALUES (?)", title) : ids.getFirst();
    }

    private Long companyStatusId(String title) {
        List<Long> ids = jdbc.queryForList(
                "SELECT company_status_id FROM company_status WHERE status_title = ? ORDER BY company_status_id LIMIT 1",
                Long.class, title);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    // ------------------------------------------------------------------------------------------------
    // Common billing: the link («связь») and its ready invoice
    // ------------------------------------------------------------------------------------------------

    /** A common invoice of one link, ready to be issued. */
    record CommonInvoiceSeed(long accountId, long invoiceId, String token, long amountKopecks, List<Long> orderIds) {
    }

    /**
     * A common-billing link of the scenario manager over the companies of the given orders, with one
     * invoice in {@code READY}: every order is a ready position, the amount is the sum of the orders.
     * Automatic repeat orders are off so a payment does not open next orders.
     */
    CommonInvoiceSeed commonInvoice(String title, long... orderIds) {
        if (orderIds.length == 0) {
            throw new IllegalArgumentException("A common invoice needs at least one order");
        }
        List<Long> orders = new ArrayList<>();
        Map<Long, Long> amountByOrder = new LinkedHashMap<>();
        List<Long> companies = new ArrayList<>();
        for (long orderId : orderIds) {
            Map<String, Object> order = jdbc.queryForMap("SELECT order_company, order_sum FROM orders WHERE order_id = ?", orderId);
            long companyId = ((Number) order.get("order_company")).longValue();
            if (!companies.contains(companyId)) {
                companies.add(companyId);
            }
            orders.add(orderId);
            amountByOrder.put(orderId, ((BigDecimal) order.get("order_sum")).movePointRight(2).longValueExact());
        }
        long accountId = insert("""
                INSERT INTO common_billing_accounts(account_name, enabled, auto_repeat_orders, manager_id, invoice_company_id)
                VALUES (?, 1, 0, ?, ?)
                """, title + " " + tag, manager.managerId, companies.getFirst());
        for (long companyId : companies) {
            jdbc.update("INSERT INTO common_billing_account_companies(account_id, company_id, enabled) VALUES (?, ?, 1)",
                    accountId, companyId);
        }
        long total = amountByOrder.values().stream().mapToLong(Long::longValue).sum();
        String token = tag + "-" + UUID.randomUUID();
        long invoiceId = insert("""
                INSERT INTO common_invoices(account_id, token, title, status, amount_kopecks)
                VALUES (?, ?, ?, 'READY', ?)
                """, accountId, token, title + " " + tag, total);
        for (long orderId : orders) {
            jdbc.update("INSERT INTO common_invoice_orders(invoice_id, order_id, amount_kopecks, ready) VALUES (?, ?, ?, 1)",
                    invoiceId, orderId, amountByOrder.get(orderId));
        }
        return new CommonInvoiceSeed(accountId, invoiceId, token, total, List.copyOf(orders));
    }

    // ------------------------------------------------------------------------------------------------
    // Acting as a user
    // ------------------------------------------------------------------------------------------------

    /** Runs the action with this person in the security context, as a controller call would. */
    void as(Person person, Runnable action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(person.auth());
        SecurityContextHolder.setContext(context);
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private long insert(String sql, Object... arguments) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert returned no generated key: " + sql);
        }
        return key.longValue();
    }

    // ------------------------------------------------------------------------------------------------
    // External bank: the only thing that is not real
    // ------------------------------------------------------------------------------------------------

    /**
     * In-memory T-Bank acquiring behind the mocked {@link TbankClient}: Init opens a session, GetState
     * reports it, Cancel closes an unpaid one. It answers with the identifiers of the request it was given,
     * exactly as the bank does, so every production consistency check of the responses stays in force.
     */
    static final class FakeTbank {
        private record Session(String terminalKey, String orderId, long amountKopecks, String status) {
        }

        private final Map<String, Session> sessions = new ConcurrentHashMap<>();
        // One sequence for the whole class: every scenario gets a new bank, but they share the database,
        // and the application refuses a PaymentId it has already seen.
        private static final AtomicLong PAYMENT_IDS = new AtomicLong(770_000_000L);

        void install(TbankClient client) {
            when(client.init(any(TbankPaymentProfile.class), any(TbankInitCommand.class))).thenAnswer(call -> {
                TbankPaymentProfile profile = call.getArgument(0);
                TbankInitCommand command = call.getArgument(1);
                String paymentId = String.valueOf(PAYMENT_IDS.incrementAndGet());
                sessions.put(paymentId, new Session(profile.terminalKey(), command.orderId(), command.amountKopecks(), "NEW"));
                return new TbankInitResponse(true, "0", null, null, profile.terminalKey(), "NEW", paymentId,
                        command.orderId(), command.amountKopecks(), "https://securepay.tinkoff.ru/new/" + paymentId);
            });
            when(client.getState(any(TbankPaymentProfile.class), anyString())).thenAnswer(call -> {
                String paymentId = call.getArgument(1);
                Session session = sessions.get(paymentId);
                if (session == null) {
                    return new TbankGetStateResponse(false, "255", "Платеж не найден", null, null, null, paymentId, null, null);
                }
                return new TbankGetStateResponse(true, "0", null, null, session.terminalKey(), session.status(), paymentId,
                        session.orderId(), session.amountKopecks());
            });
            when(client.cancel(any(TbankPaymentProfile.class), any(TbankCancelCommand.class))).thenAnswer(call -> {
                TbankCancelCommand command = call.getArgument(1);
                Session session = sessions.get(command.paymentId());
                if (session == null) {
                    return new TbankCancelResponse(false, "255", "Платеж не найден", null, null, null, command.paymentId(),
                            null, null, null, null);
                }
                sessions.put(command.paymentId(), new Session(session.terminalKey(), session.orderId(), session.amountKopecks(), "CANCELED"));
                return new TbankCancelResponse(true, "0", null, null, session.terminalKey(), "CANCELED", command.paymentId(),
                        session.orderId(), session.amountKopecks(), session.amountKopecks(), 0L);
            });
        }

        /**
         * The client paid by the link: the session becomes CONFIRMED and the bank's notification is returned,
         * signed with the terminal password as the real bank signs it, for the production webhook handler.
         */
        Map<String, String> confirmed(String paymentId, String terminalPassword) {
            Session session = sessions.get(paymentId);
            if (session == null) {
                throw new IllegalStateException("The bank has no payment " + paymentId);
            }
            sessions.put(paymentId, new Session(session.terminalKey(), session.orderId(), session.amountKopecks(), "CONFIRMED"));
            Map<String, String> notice = new LinkedHashMap<>();
            notice.put("TerminalKey", session.terminalKey());
            notice.put("OrderId", session.orderId());
            notice.put("Success", "true");
            notice.put("Status", "CONFIRMED");
            notice.put("PaymentId", paymentId);
            notice.put("ErrorCode", "0");
            notice.put("Amount", String.valueOf(session.amountKopecks()));
            notice.put("Token", new TbankTokenSigner().sign(notice, terminalPassword));
            return notice;
        }

        /** Bank status of a session, for assertions about what the bank was told. */
        String status(String paymentId) {
            Session session = sessions.get(paymentId);
            return session == null ? null : session.status();
        }
    }
}
