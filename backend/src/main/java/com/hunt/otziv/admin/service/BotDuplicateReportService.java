package com.hunt.otziv.admin.service;

import com.hunt.otziv.admin.repository.BotDuplicateReportRepository;
import com.hunt.otziv.admin.service.BotImportService.BotImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BotDuplicateReportService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final BotDuplicateReportRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(BotImportResult result) {
        if (result.duplicates().isEmpty()) return;
        String id = UUID.randomUUID().toString();
        repository.enqueue(id, "account-duplicates-" + id + ".txt", render(id, result));
    }

    static String render(String id, BotImportResult result) {
        long previous = result.duplicates().stream().filter(row -> "EXISTING_ACCOUNT".equals(row.reason())).count();
        StringBuilder text = new StringBuilder("ЗАПРОС ПО ПОВТОРНЫМ АККАУНТАМ\n\n");
        text.append("Здравствуйте! При проверке полученного файла «").append(value(result.sourceFileName()))
                .append("» обнаружены повторные аккаунты.\n")
                .append("Просим проверить поставку и заменить повторные аккаунты на уникальные либо согласовать возврат оплаты за них.\n")
                .append("Ниже приведены все найденные совпадения и сведения об их первоначальном добавлении.\n\n")
                .append("Номер отчёта: ").append(id).append('\n')
                .append("Проверяемый файл: ").append(value(result.sourceFileName())).append('\n')
                .append("Дата загрузки и проверки: ").append(date(result.importedAt())).append('\n')
                .append("Всего строк с аккаунтами: ").append(result.totalRows()).append('\n')
                .append("Добавлено новых аккаунтов: ").append(result.added()).append('\n')
                .append("Пропущено дублирующихся строк: ").append(result.skippedDuplicates()).append('\n')
                .append("Из них: ранее добавлялись — ").append(previous)
                .append(", повторы внутри проверяемого файла — ").append(result.duplicates().size() - previous).append('\n')
                .append("Строк с ошибками: ").append(result.skippedInvalid()).append("\n\n")
                .append("СПИСОК СОВПАДЕНИЙ\n");
        int index = 0;
        for (var row : result.duplicates()) {
            text.append('\n').append(++index).append(") Аккаунт: ").append(value(row.login())).append('\n')
                    .append("   Строка в проверяемом файле: ").append(row.rowNumber()).append('\n')
                    .append("   Причина: ").append("DUPLICATE_IN_FILE".equals(row.reason())
                            ? "повтор внутри проверяемого файла" : "аккаунт уже добавлялся ранее").append('\n')
                    .append("   ID оригинала: ").append(row.originalBotId() == null ? "не сохранён" : row.originalBotId()).append('\n')
                    .append("   Первое добавление: ").append(date(row.originalImportedAt())).append('\n')
                    .append("   Файл первоначального добавления: ").append(value(row.originalFileName())).append('\n')
                    .append("   Строка в первоначальном файле: ")
                    .append(row.originalRowNumber() == null ? "не сохранена" : row.originalRowNumber()).append('\n');
        }
        return text.append("\nПримечания:\n")
                .append("• Совпадения определены по логину без учёта регистра и пробелов по краям; смена пароля не делает аккаунт новым.\n")
                .append("• Для повторов внутри файла оригинал может находиться в этой же поставке.\n")
                .append("• «Не сохранено» означает отсутствие исторических сведений, а не отсутствие совпадения.\n")
                .append("• Даты указаны во времени сервера приложения. Пароли в отчёт не включены.\n")
                .toString();
    }

    private static String date(LocalDateTime date) {
        return date == null ? "Не сохранено" : DATE.format(date);
    }

    private static String value(String value) {
        if (value == null || value.isBlank()) return "Не сохранено";
        return value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
