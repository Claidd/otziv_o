package com.hunt.otziv.admin.service;

import com.hunt.otziv.admin.repository.BotDuplicateReportRepository;
import com.hunt.otziv.admin.service.BotImportService.BotImportDuplicate;
import com.hunt.otziv.admin.service.BotImportService.BotImportResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BotDuplicateReportServiceTest {
    private final BotDuplicateReportRepository repository = mock(BotDuplicateReportRepository.class);
    private final BotDuplicateReportService service = new BotDuplicateReportService(repository);

    @Test
    void queuesCompleteSupplierRequestWithBothFilesAndDatesForEveryDuplicate() {
        var duplicates = IntStream.rangeClosed(1, 107).mapToObj(i -> new BotImportDuplicate(i + 1,
                "account-" + i, "EXISTING_ACCOUNT", (long) i,
                LocalDateTime.of(2026, 8, 1, 10, 30), "Первая поставка.xlsx", i)).toList();
        service.enqueue(result(duplicates));
        var name = ArgumentCaptor.forClass(String.class);
        var text = ArgumentCaptor.forClass(String.class);
        verify(repository).enqueue(anyString(), name.capture(), text.capture());
        assertThat(name.getValue()).endsWith(".txt").doesNotContain("..", "/", "\\");
        assertThat(text.getValue()).contains("Здравствуйте!", "заменить повторные аккаунты",
                "Проверяемый файл: Вторая поставка.csv", "15.09.2026 14:00:00",
                "01.08.2026 10:30:00", "Первая поставка.xlsx", "107) Аккаунт: account-107",
                "Строка в проверяемом файле: 108", "ID оригинала: 107", "Строка в первоначальном файле: 107");
    }

    @Test
    void labelsInFileRepeatsAndUnknownHistoryWithoutInventingOriginalData() {
        var duplicate = new BotImportDuplicate(4, "name\nforged-row", "DUPLICATE_IN_FILE", 10L, null, null, null);
        String report = BotDuplicateReportService.render("id", result(List.of(duplicate)));
        assertThat(report).contains("повтор внутри проверяемого файла", "Первое добавление: Не сохранено",
                "Файл первоначального добавления: Не сохранено", "name\\nforged-row");
        assertThat(report).doesNotContain("name\nforged-row");
    }

    @Test
    void doesNotCreateDocumentWhenThereAreNoDuplicates() {
        service.enqueue(result(List.of()));
        verifyNoInteractions(repository);
    }

    private BotImportResult result(List<BotImportDuplicate> duplicates) {
        return new BotImportResult(duplicates.size(), 0, duplicates.size(), 0, List.of(),
                "Вторая поставка.csv", LocalDateTime.of(2026, 9, 15, 14, 0), duplicates, !duplicates.isEmpty());
    }
}
