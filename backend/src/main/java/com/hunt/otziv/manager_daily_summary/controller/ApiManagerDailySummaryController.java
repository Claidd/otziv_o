package com.hunt.otziv.manager_daily_summary.controller;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerSummaryPreviewResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerSummaryTelegramSendResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewTestStartResponse;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewQueryService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewTelegramService;
import com.hunt.otziv.manager_daily_summary.service.ManagerDailySummaryService;
import com.hunt.otziv.manager_daily_summary.service.ManagerSummaryFormatter;
import com.hunt.otziv.manager_daily_summary.service.ManagerSummaryNotificationService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/manager-daily-summary")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiManagerDailySummaryController {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");

    private final ManagerDailySummaryService summaryService;
    private final ManagerSummaryFormatter formatter;
    private final ManagerSummaryNotificationService notificationService;
    private final ManagerReportReviewQueryService reportReviewQueryService;
    private final ManagerReportReviewTelegramService reportReviewTelegramService;
    private final ManagerRepository managerRepository;
    private final UserRepository userRepository;

    @Autowired
    public ApiManagerDailySummaryController(
            ManagerDailySummaryService summaryService,
            ManagerSummaryFormatter formatter,
            ManagerSummaryNotificationService notificationService,
            ManagerReportReviewQueryService reportReviewQueryService,
            ManagerReportReviewTelegramService reportReviewTelegramService,
            ManagerRepository managerRepository,
            UserRepository userRepository
    ) {
        this.summaryService = summaryService;
        this.formatter = formatter;
        this.notificationService = notificationService;
        this.reportReviewQueryService = reportReviewQueryService;
        this.reportReviewTelegramService = reportReviewTelegramService;
        this.managerRepository = managerRepository;
        this.userRepository = userRepository;
    }

    ApiManagerDailySummaryController(
            ManagerDailySummaryService summaryService,
            ManagerSummaryFormatter formatter,
            ManagerSummaryNotificationService notificationService,
            UserRepository userRepository
    ) {
        this(summaryService, formatter, notificationService, null, null, null, userRepository);
    }

    @GetMapping
    public List<ManagerDailySummaryResponse> summaries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return summaryService.summaries(date);
    }

    @PostMapping("/calculate")
    public List<ManagerDailySummaryResponse> calculate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean finalizeDay
    ) {
        return summaryService.calculate(date, finalizeDay);
    }

    @GetMapping("/preview")
    public ManagerSummaryPreviewResponse preview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate selected = date == null ? LocalDate.now(BUSINESS_ZONE) : date;
        List<ManagerDailySummaryResponse> rows = summaryService.summaries(selected);
        return new ManagerSummaryPreviewResponse(selected, formatter.format(rows, true), rows);
    }

    @PostMapping("/send-test")
    public ManagerSummaryTelegramSendResponse sendTest(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Principal principal
    ) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не авторизован");
        }
        User recipient = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        if (recipient.getTelegramChatId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сначала привяжите Telegram в личном кабинете"
            );
        }

        LocalDate selected = date == null ? LocalDate.now(BUSINESS_ZONE) : date;
        List<ManagerDailySummaryResponse> rows = summaryService.calculate(selected, false);
        try {
            int sentMessages = notificationService.sendTest(recipient, rows);
            return new ManagerSummaryTelegramSendResponse(
                    selected,
                    rows.size(),
                    sentMessages,
                    recipient.getFio() == null || recipient.getFio().isBlank()
                            ? recipient.getUsername()
                            : recipient.getFio()
            );
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Не удалось отправить аудит в Telegram",
                    exception
            );
        }
    }

    @PostMapping("/review-test")
    public ManagerReportReviewTestStartResponse startReviewTest(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long managerId,
            Principal principal
    ) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не авторизован");
        }
        User tester = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        if (tester.getTelegramChatId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сначала привяжите личный Telegram в личном кабинете"
            );
        }
        LocalDate selected = date == null ? LocalDate.now(BUSINESS_ZONE) : date;
        List<ManagerDailySummaryResponse> rows = summaryService.calculate(selected, false);
        ManagerDailySummaryResponse source = selectTestSource(rows, managerId);
        if (source == null || source.managerId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    managerId == null
                            ? "Нет отчётов менеджеров для тестового прохождения"
                            : "Отчёт выбранного менеджера за эту дату не найден"
            );
        }
        var manager = managerRepository.findById(source.managerId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Менеджер для тестового отчёта не найден"
                ));
        var review = reportReviewTelegramService.deliverTest(tester, manager, source)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Telegram не подтвердил отправку тестового аудита"
                ));
        return new ManagerReportReviewTestStartResponse(
                review.getId(),
                selected,
                source.managerId(),
                source.managerName(),
                displayName(tester),
                review.getIssueCount()
        );
    }

    @GetMapping("/review-sessions")
    public List<ManagerReportReviewResponse> reviewSessions(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return reportReviewQueryService.reviews(date);
    }

    private ManagerDailySummaryResponse selectTestSource(
            List<ManagerDailySummaryResponse> rows,
            Long managerId
    ) {
        if (rows == null) return null;
        return rows.stream()
                .filter(row -> row != null && row.managerId() != null)
                .filter(row -> managerId == null || managerId.equals(row.managerId()))
                .max(Comparator
                        .comparingLong(this::testIssuePriority)
                        .thenComparingLong(ManagerDailySummaryResponse::taskOpen))
                .orElse(null);
    }

    private long testIssuePriority(ManagerDailySummaryResponse row) {
        return Math.max(
                Math.max(0, row.problemCount()),
                Math.max(0, row.overdueCount())
                        + Math.max(0, row.riskCount())
                        + Math.max(0, row.unansweredCount())
                        + Math.max(0, row.taskOtherOpen())
                        + Math.max(0, row.hardSlaBreachCount())
        );
    }

    private String displayName(User user) {
        if (user.getFio() != null && !user.getFio().isBlank()) return user.getFio();
        return user.getUsername();
    }
}
