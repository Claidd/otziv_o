import { TestBed } from '@angular/core/testing';
import { BotImportResponse } from '../../../core/admin-dictionaries.api';
import { BotImportReportComponent, botImportDuplicateCsv } from './bot-import-report.component';

describe('account import duplicate report', () => {
  const result: BotImportResponse = {
    totalRows: 3, added: 1, skippedDuplicates: 2, skippedInvalid: 0, errors: [],
    sourceFileName: 'Повторная поставка.csv', importedAt: '2026-09-13T12:00:00', duplicateReportQueued: true,
    duplicates: [
      { rowNumber: 2, login: 'original', reason: 'EXISTING_ACCOUNT', originalBotId: 25,
        originalImportedAt: '2026-08-05T10:30:00', originalFileName: 'Первая поставка.xlsx', originalRowNumber: 8 },
      { rowNumber: 3, login: 'legacy', reason: 'DUPLICATE_IN_FILE', originalBotId: 7,
        originalImportedAt: null, originalFileName: null, originalRowNumber: null }
    ]
  };

  it('shows original provenance, distinguishes in-file repeats and labels unknown history', () => {
    const fixture = TestBed.createComponent(BotImportReportComponent);
    fixture.componentRef.setInput('result', result);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Ранее добавлялись: 1');
    expect(text).toContain('Повторы внутри файла: 1');
    expect(text).toContain('Первая поставка.xlsx');
    expect(text).toContain('05.08.2026 10:30');
    expect(text).toContain('ID оригинала: 25');
    expect(text).toContain('Строка 8');
    expect(text).toContain('Не сохранено');
    expect(text).toContain('поставлен в очередь отправки администратору в Telegram');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
  });

  it('exports every duplicate even when the visible table is limited', () => {
    const many = { ...result, duplicates: Array.from({ length: 107 }, (_, i) => ({
      ...result.duplicates![0], login: `account-${i}`, rowNumber: i + 1
    })) };
    const fixture = TestBed.createComponent(BotImportReportComponent);
    fixture.componentRef.setInput('result', many);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(100);
    expect(fixture.nativeElement.textContent).toContain('Показаны первые 100 из 107');
    const csv = botImportDuplicateCsv(many);
    expect(csv.startsWith('\uFEFF')).toBe(true);
    expect(csv.split('\r\n')).toHaveLength(108);
    expect(csv).toContain('account-106');
    expect(csv).toContain('Повторная поставка.csv');
    expect(csv).toContain('Первая поставка.xlsx');
  });

  it('escapes spreadsheet formulas and quotes while keeping long logins intact', () => {
    const csv = botImportDuplicateCsv({ ...result, sourceFileName: '=1+1.csv', duplicates: [
      { ...result.duplicates![0], login: '+79990000000', originalFileName: 'a;"b".xlsx' },
      { ...result.duplicates![0], login: '12345678901234567890', originalFileName: '@SUM(1).csv' },
      { ...result.duplicates![0], login: '0012345' }
    ] });
    expect(csv).toContain('"\'=1+1.csv"');
    expect(csv).toContain('"\'+79990000000"');
    expect(csv).toContain('"\'12345678901234567890"');
    expect(csv).toContain('"\'0012345"');
    expect(csv).toContain('"\'@SUM(1).csv"');
    expect(csv).toContain('"a;""b"".xlsx"');
    expect(csv).not.toContain('password');
  });
});
