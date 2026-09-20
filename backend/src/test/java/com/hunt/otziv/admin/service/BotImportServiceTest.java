package com.hunt.otziv.admin.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.repository.StatusBotRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import com.hunt.otziv.uploads.service.FileUploadGuard;
import com.hunt.otziv.admin.repository.BotImportOriginRepository;
import com.hunt.otziv.admin.repository.BotImportOriginRepository.Origin;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class BotImportServiceTest {

    private static final String DEFAULT_FIO = "Впиши Имя Фамилию";

    private BotsRepository botsRepository;
    private BotImportService service;
    private BotImportOriginRepository originRepository;
    private BotDuplicateReportService duplicateReportService;
    private Map<String, Origin> origins;
    private long nextId;

    @BeforeEach
    void setUp() {
        botsRepository = mock(BotsRepository.class);
        StatusBotRepository statusBotRepository = mock(StatusBotRepository.class);
        WorkerRepository workerRepository = mock(WorkerRepository.class);
        CityRepository cityRepository = mock(CityRepository.class);
        BusinessAuditService businessAuditService = mock(BusinessAuditService.class);
        FileUploadGuard fileUploadGuard = new FileUploadGuard(
                5_242_880,
                20_000_000,
                8_000,
                8_000,
                5_242_880,
                5_000
        );

        origins = new HashMap<>();
        nextId = 100L;
        originRepository = mock(BotImportOriginRepository.class);
        duplicateReportService = mock(BotDuplicateReportService.class);
        when(originRepository.findOrigins(anyList())).thenAnswer(invocation -> {
            List<String> logins = invocation.getArgument(0);
            return logins.stream().filter(origins::containsKey).map(origins::get).toList();
        });
        when(originRepository.findExistingAccounts(anyList())).thenReturn(List.of());
        doAnswer(invocation -> {
            Origin origin = invocation.getArgument(0);
            assertThat(origins).doesNotContainKey(origin.login());
            origins.put(origin.login(), origin);
            return null;
        }).when(originRepository).save(any());
        when(botsRepository.saveAll(any())).thenAnswer(invocation -> {
            List<Bot> bots = invocation.getArgument(0);
            bots.forEach(bot -> bot.setId(nextId++));
            return bots;
        });
        when(statusBotRepository.findById(1L)).thenReturn(Optional.of(StatusBot.builder()
                .id(1L)
                .botStatusTitle("Новый")
                .build()));
        when(workerRepository.findById(5L)).thenReturn(Optional.of(Worker.builder()
                .id(5L)
                .build()));
        when(cityRepository.findById(325L)).thenReturn(City.builder()
                .id(325L)
                .title("Шаблон")
                .build());
        when(cityRepository.findById(326L)).thenReturn(City.builder()
                .id(326L)
                .title("Фламп")
                .build());

        service = new BotImportService(
                botsRepository,
                statusBotRepository,
                workerRepository,
                cityRepository,
                businessAuditService,
                fileUploadGuard,
                originRepository,
                duplicateReportService
        );
    }

    @Test
    void importBotsKeepsFileFioWithoutCityOverride() {
        List<Bot> saved = importOne(null);

        assertThat(saved).singleElement()
                .satisfies(bot -> {
                    assertThat(bot.getFio()).isEqualTo("Иван Иванов");
                    assertThat(bot.getBotCity().getId()).isEqualTo(325L);
                });
    }

    @Test
    void importBotsForCityOverridesFileFioWithTemplateName() {
        List<Bot> saved = importOne(326L);

        assertThat(saved).singleElement()
                .satisfies(bot -> {
                    assertThat(bot.getFio()).isEqualTo(DEFAULT_FIO);
                    assertThat(bot.getBotCity().getId()).isEqualTo(326L);
                });
    }

    @Test
    void secondPurchaseReportsOriginalFileAndDateEvenWhenPasswordAndCaseChange() {
        var first = service.importBots(csv("first.csv", "Account;old-password\n"));
        var repeat = service.importBots(csv("second.csv", " account ;changed-password\n"));

        assertThat(first.added()).isEqualTo(1);
        assertThat(repeat.added()).isZero();
        assertThat(repeat.skippedDuplicates()).isEqualTo(1);
        assertThat(first.duplicateReportQueued()).isFalse();
        assertThat(repeat.duplicateReportQueued()).isTrue();
        verify(duplicateReportService).enqueue(repeat);
        assertThat(repeat.sourceFileName()).isEqualTo("second.csv");
        assertThat(repeat.duplicates()).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.login()).isEqualTo("account");
            assertThat(duplicate.originalBotId()).isEqualTo(100L);
            assertThat(duplicate.originalFileName()).isEqualTo("first.csv");
            assertThat(duplicate.originalImportedAt()).isEqualTo(first.importedAt());
            assertThat(duplicate.originalRowNumber()).isEqualTo(1);
            assertThat(duplicate.reason()).isEqualTo("EXISTING_ACCOUNT");
        });
        assertThat(origins.get("account").sourceFile()).isEqualTo("first.csv");
    }

    @Test
    void reportsAllDuplicatesAndPreservesRowNumbersAcrossBlankLines() {
        LocalDateTime originalDate = LocalDateTime.of(2026, 8, 5, 12, 30);
        origins.put("old", new Origin("old", 9L, originalDate, "old.xlsx", 12));
        var result = service.importBots(csv("mixed.csv", "\nlogin;password\nold;p\n\nNEW;p\n new ;other\nold;p\nbroken\n"));

        assertThat(result.totalRows()).isEqualTo(5);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.skippedInvalid()).isEqualTo(1);
        assertThat(result.skippedDuplicates()).isEqualTo(3);
        assertThat(result.duplicates()).extracting(BotImportService.BotImportDuplicate::rowNumber)
                .containsExactly(3, 6, 7);
        assertThat(result.duplicates().get(1)).satisfies(duplicate -> {
            assertThat(duplicate.reason()).isEqualTo("DUPLICATE_IN_FILE");
            assertThat(duplicate.originalFileName()).isEqualTo("mixed.csv");
            assertThat(duplicate.originalRowNumber()).isEqualTo(5);
            assertThat(duplicate.originalBotId()).isEqualTo(100L);
        });
        assertThat(result.duplicates().get(2).originalFileName()).isEqualTo("old.xlsx");
        assertThat(result.errors()).singleElement().asString().contains("8");
    }

    @Test
    void invalidFirstRowDoesNotPreventImportingLaterValidAccount() {
        var result = service.importBots(csv("accounts.csv",
                "login;password;status\naccount;p;999\nACCOUNT;p;1\n"));
        assertThat(result.skippedInvalid()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.skippedDuplicates()).isZero();
        assertThat(origins.get("account").sourceRow()).isEqualTo(3);
    }

    @Test
    void legacyAccountsKeepUnknownHistoryAndAreRememberedAfterDeletion() {
        when(originRepository.findExistingAccounts(List.of("legacy")))
                .thenReturn(List.of(new Origin("legacy", 7L, null, null, null)));
        var first = service.importBots(csv("new.csv", "legacy;p\n"));
        when(originRepository.findExistingAccounts(anyList())).thenReturn(List.of());
        var afterDeletion = service.importBots(csv("resold.csv", "legacy;p\n"));

        assertThat(first.added()).isZero();
        assertThat(afterDeletion.added()).isZero();
        assertThat(afterDeletion.duplicates()).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.originalBotId()).isEqualTo(7L);
            assertThat(duplicate.originalFileName()).isNull();
            assertThat(duplicate.originalImportedAt()).isNull();
        });
        verify(botsRepository, never()).saveAll(any());
    }

    @Test
    void workbookReportUsesActualSheetRowsAndStripsClientDirectoryFromFilename() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(2).createCell(0).setCellValue("login");
            sheet.getRow(2).createCell(1).setCellValue("password");
            sheet.createRow(5).createCell(0).setCellValue("account");
            sheet.getRow(5).createCell(1).setCellValue("p");
            sheet.createRow(8).createCell(0).setCellValue("ACCOUNT");
            sheet.getRow(8).createCell(1).setCellValue("p2");
            workbook.write(output);
            var file = new MockMultipartFile("file", "C:\\fakepath\\supply.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
            var result = service.importBots(file);
            assertThat(result.added()).isEqualTo(1);
            assertThat(result.sourceFileName()).isEqualTo("supply.xlsx");
            assertThat(result.duplicates()).singleElement().satisfies(duplicate -> {
                assertThat(duplicate.rowNumber()).isEqualTo(9);
                assertThat(duplicate.originalRowNumber()).isEqualTo(6);
            });
        }
    }

    @Test
    void keepsFullReportBeyondLookupChunkSize() {
        StringBuilder content = new StringBuilder("login;password\n");
        for (int i = 0; i < 1005; i++) {
            origins.put("account" + i, new Origin("account" + i, (long) i, null, "batch.csv", i + 1));
            content.append("account").append(i).append(";p\n");
        }
        var result = service.importBots(csv("repeat.csv", content.toString()));
        assertThat(result.added()).isZero();
        assertThat(result.skippedDuplicates()).isEqualTo(1005);
        assertThat(result.duplicates()).hasSize(1005);
    }

    private MockMultipartFile csv(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void csvReportPreservesPhysicalLinesAfterQuotedMultilineFields() {
        var result = service.importBots(csv("multiline.csv", "login;password\r\naccount;\"first\r\nsecond\"\r\naccount;p\r\n"));
        assertThat(result.duplicates()).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.originalRowNumber()).isEqualTo(2);
            assertThat(duplicate.rowNumber()).isEqualTo(4);
        });
    }

    private List<Bot> importOne(Long cityId) {
        List<Bot> saved = new ArrayList<>();
        doAnswer(invocation -> {
            Iterable<Bot> bots = invocation.getArgument(0);
            bots.forEach(bot -> {
                bot.setId(nextId++);
                saved.add(bot);
            });
            return saved;
        }).when(botsRepository).saveAll(any());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "bots.csv",
                "text/csv",
                ("bot_login;bot_password;bot_fio;bot_counter;bot_active;bot_status;bot_worker;bot_city_id\n" +
                        "79990000000;pass;Иван Иванов;0;1;1;5;325\n")
                        .getBytes(StandardCharsets.UTF_8));

        service.importBots(file, cityId);

        return saved;
    }
}
