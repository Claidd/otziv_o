import { DatePipe, formatDate } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { BotImportResponse } from '../../../core/admin-dictionaries.api';

function csvCell(value: string | number | null | undefined): string {
  let text = String(value ?? '');
  // Treat numeric logins as text so Excel preserves leading zeroes and every digit.
  if (/^[=+\-@\t\r\n]/.test(text.trimStart()) || (typeof value === 'string' && /^\d+$/.test(text))) text = `'${text}`;
  return `"${text.replace(/"/g, '""')}"`;
}

export function botImportDuplicateCsv(result: BotImportResponse): string {
  const rows: (string | number | null | undefined)[][] = [[
    'Загруженный файл', 'Дата проверки', 'Строка', 'Логин', 'Причина', 'ID оригинала',
    'Первое добавление', 'Исходный файл', 'Строка оригинала'
  ]];
  for (const duplicate of result.duplicates ?? []) {
    rows.push([
      result.sourceFileName, result.importedAt, duplicate.rowNumber, duplicate.login,
      duplicate.reason === 'DUPLICATE_IN_FILE' ? 'Повтор внутри файла' : 'Уже добавлялся ранее',
      duplicate.originalBotId,
      duplicate.originalImportedAt
        ? formatDate(duplicate.originalImportedAt, 'dd.MM.yyyy HH:mm:ss', 'en-US') : 'Не сохранено',
      duplicate.originalFileName ?? 'Не сохранено', duplicate.originalRowNumber
    ]);
  }
  return '\uFEFF' + rows.map(row => row.map(csvCell).join(';')).join('\r\n');
}

@Component({
  selector: 'app-bot-import-report',
  imports: [DatePipe],
  templateUrl: './bot-import-report.component.html',
  styleUrl: './bot-import-report.component.scss'
})
export class BotImportReportComponent {
  readonly result = input.required<BotImportResponse>();
  readonly duplicates = computed(() => this.result().duplicates ?? []);
  readonly visibleDuplicates = computed(() => this.duplicates().slice(0, 100));
  readonly existingCount = computed(() => this.duplicates().filter(row => row.reason === 'EXISTING_ACCOUNT').length);
  readonly inFileCount = computed(() => this.duplicates().filter(row => row.reason === 'DUPLICATE_IN_FILE').length);

  download(): void {
    const url = URL.createObjectURL(new Blob([botImportDuplicateCsv(this.result())], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `Дубли — ${this.result().sourceFileName || 'аккаунты'}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
