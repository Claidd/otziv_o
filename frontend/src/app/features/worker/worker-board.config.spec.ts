import {
  WORKER_ORDER_STATUS_ACTIONS,
  WORKER_SECTIONS,
  trackWorkerAction,
  trackWorkerMetric,
  trackWorkerOption,
  trackWorkerOrder,
  trackWorkerReview,
  trackWorkerSection,
  workerBotChangeMessage,
  workerErrorMessage,
  workerReviewCopyLabel,
  workerSectionLabel
} from './worker-board.config';

describe('worker-board config helpers', () => {
  it('keeps labels and status action track keys stable', () => {
    expect(workerSectionLabel('new')).toBe('Новые');
    expect(workerSectionLabel('bad')).toBe('Плохие');
    expect(workerReviewCopyLabel('password')).toBe('Пароль');
    expect(trackWorkerSection(0, WORKER_SECTIONS[0])).toBe('new');
    expect(trackWorkerAction(0, WORKER_ORDER_STATUS_ACTIONS[0])).toBe('На проверке');
  });

  it('builds readable bot change messages for missing and assigned bots', () => {
    expect(workerBotChangeMessage(null, 42)).toBe('Аккаунт изменен с ID не назначен на ID 42');
    expect(workerBotChangeMessage(7, null)).toBe('Аккаунт изменен с ID 7 на ID не назначен');
  });

  it('extracts API error messages with fallback support', () => {
    expect(workerErrorMessage({ error: { message: 'Ошибка API' } }, 'fallback')).toBe('Ошибка API');
    expect(workerErrorMessage({ error: 'plain error' }, 'fallback')).toBe('plain error');
    expect(workerErrorMessage({}, 'fallback')).toBe('fallback');
  });

  it('tracks list items by stable identifiers', () => {
    expect(trackWorkerOrder(0, { id: 12 } as never)).toBe(12);
    expect(trackWorkerReview(0, { id: 13 } as never)).toBe(13);
    expect(trackWorkerMetric(0, { section: 'publish' } as never)).toBe('publish');
    expect(trackWorkerOption(0, { id: 14 } as never)).toBe(14);
  });
});
