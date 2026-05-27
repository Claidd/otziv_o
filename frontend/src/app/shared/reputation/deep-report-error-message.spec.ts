import { deepReportErrorMessage } from './deep-report-error-message';

describe('deepReportErrorMessage', () => {
  it('explains AI provider quota and billing failures', () => {
    expect(deepReportErrorMessage('insufficient_quota: check your plan and billing details')).toBe(
      'AI-провайдер не запустил отчёт из-за оплаты или квоты. Проверьте баланс и лимиты проекта активного провайдера, затем запустите отчёт повторно.'
    );
  });

  it('keeps retry hint for rate limits', () => {
    expect(deepReportErrorMessage('Rate limit reached. Please try again in 12.5s')).toContain(
      'API просит повторить примерно через 12.5 с.'
    );
  });

  it('explains queue failures', () => {
    expect(deepReportErrorMessage('Не удалось поставить глубокий отчёт в фоновую очередь')).toBe(
      'Сервер не смог поставить отчёт в фоновую очередь. Освободите очередь задач или перезапустите backend, затем запустите отчёт вручную.'
    );
  });
});
