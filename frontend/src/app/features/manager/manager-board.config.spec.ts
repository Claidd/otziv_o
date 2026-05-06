import type { CompanyCardItem, ManagerMetric, ManagerOption, OrderCardItem } from '../../core/manager.api';
import {
  MANAGER_ORDER_ACTIONS,
  MANAGER_SECTIONS,
  managerBoardTitle,
  managerCompanyChatUrl,
  managerCompanyFilialUrl,
  managerCompanyOrderUrl,
  managerErrorMessage,
  managerFilialEditUrl,
  managerHasMeaningfulNote,
  managerLayoutTitle,
  managerOptionLabel,
  managerOrderActions,
  managerOrderChatUrl,
  managerOrderDetailsUrl,
  managerOrderInfoUrl,
  managerOrderReviewCopyText,
  managerOrderReviewUrl,
  managerPayableOrderSum,
  managerProgress,
  managerPromoItems,
  managerReviewCheckPath,
  managerShowBadReviewSummary,
  managerStatusOptionLabel,
  trackManagerAction,
  trackManagerCompany,
  trackManagerMetric,
  trackManagerOption,
  trackManagerOrder,
  trackManagerProduct,
  trackManagerSection,
  trackManagerStatus
} from './manager-board.config';

function company(overrides: Partial<CompanyCardItem> = {}): CompanyCardItem {
  return {
    id: 7,
    title: 'Company',
    telephone: '+7999',
    countFilials: 1,
    status: 'Новая',
    ...overrides
  };
}

function order(overrides: Partial<OrderCardItem> = {}): OrderCardItem {
  return {
    id: 12,
    companyId: 7,
    companyTitle: 'Company',
    status: 'На проверке',
    sum: 100,
    ...overrides
  };
}

describe('manager-board config helpers', () => {
  it('keeps titles and status labels stable', () => {
    expect(managerBoardTitle('companies', 'Все', null)).toBe('Компании');
    expect(managerBoardTitle('companies', 'Бан', null)).toBe('Бан');
    expect(managerBoardTitle('orders', 'Все', { id: 2, title: 'Acme' })).toBe('Заказы - Acme');
    expect(managerLayoutTitle('orders', 'Оплачено', null)).toBe('Заказы - Оплачено');
    expect(managerStatusOptionLabel('Все', null)).toBe('Все');
    expect(managerStatusOptionLabel('Оплачено', 3)).toBe('Оплачено: 3');
  });

  it('maps promo text slots by active section', () => {
    const texts = Array.from({ length: 11 }, (_, index) => `text-${index}`);

    expect(managerPromoItems('companies', texts)).toEqual([
      { label: 'предложение', text: 'text-0' },
      { label: 'пояснение', text: 'text-10' },
      { label: 'рассылка', text: 'text-9' }
    ]);
    expect(managerPromoItems('orders', texts)).toEqual([
      { label: 'пояснение', text: 'text-10' },
      { label: 'напоминание', text: 'text-5' },
      { label: 'угроза', text: 'text-6' }
    ]);
  });

  it('builds order actions from status without changing the legacy flow', () => {
    expect(managerOrderActions(order({ status: 'В проверку' }), false).map((action) => action.status)).toEqual(['На проверке']);
    expect(managerOrderActions(order({ status: 'На проверке' }), false).map((action) => action.status)).toEqual([
      'Коррекция',
      'Архив',
      'Публикация'
    ]);
    expect(managerOrderActions(order({ status: 'Выставлен счет' }), false).map((action) => action.status)).toEqual([
      'Напоминание',
      'Не оплачено',
      'Оплачено'
    ]);
    expect(managerOrderActions(order({ status: 'Новый' }), true)).toBe(MANAGER_ORDER_ACTIONS);
  });

  it('calculates order labels, amounts, progress and review links', () => {
    expect(managerPayableOrderSum(order({ sum: 100, totalSumWithBadReviews: 130 }))).toBe(130);
    expect(managerShowBadReviewSummary(order({ status: 'Опубликовано', badReviewTasksTotal: 1 }))).toBe(true);
    expect(managerShowBadReviewSummary(order({ status: 'Оплачено', badReviewTasksTotal: 1 }))).toBe(false);
    expect(managerProgress(order({ amount: 4, counter: 3 }))).toBe(75);
    expect(managerProgress(order({ amount: 0, counter: 3 }))).toBe(0);
    expect(managerReviewCheckPath('uuid-1')).toBe('/uuid-1');
    expect(managerOrderReviewUrl(order({ orderDetailsId: 'uuid-2' }))).toBe('/uuid-2');
    expect(managerOrderReviewUrl(order({ orderDetailsId: undefined }))).toBe('');
    expect(managerOptionLabel({ id: 5, label: '' })).toBe('ID 5');
  });

  it('builds review check copy text for first and repeat orders', () => {
    const promoTexts = Array.from({ length: 12 }, () => '');
    promoTexts[4] = 'Здравствуйте, это тексты на проверку. Нажмите «РАЗРЕШИТЬ ПУБЛИКАЦИЮ».';
    promoTexts[11] = 'Повторный текст из промо';

    const firstText = managerOrderReviewCopyText(
      order({ orderDetailsId: 'uuid-1', firstOrderForCompany: true }),
      promoTexts
    );
    const repeatText = managerOrderReviewCopyText(
      order({ orderDetailsId: 'uuid-2', firstOrderForCompany: false }),
      promoTexts
    );

    expect(firstText).toBe([
      'Здравствуйте, это тексты на проверку. Нажмите «РАЗРЕШИТЬ ПУБЛИКАЦИЮ».',
      '',
      'Ссылка на проверку отзывов: https://o-ogo.ru/uuid-1'
    ].join('\n'));
    expect(repeatText).toBe([
      'Повторный текст из промо',
      '',
      'Ссылка на проверку отзывов: https://o-ogo.ru/uuid-2'
    ].join('\n'));
  });

  it('keeps note, chat and legacy URL helpers stable', () => {
    expect(managerHasMeaningfulNote('нет заметок')).toBe(false);
    expect(managerHasMeaningfulNote(' полезно ')).toBe(true);
    expect(managerCompanyChatUrl(company({ urlChat: 'https://chat' }))).toBe('https://chat');
    expect(managerCompanyChatUrl(company({ urlChat: undefined, telephone: '+7000' }))).toBe('tel:+7000');
    expect(managerOrderChatUrl(order({ companyUrlChat: undefined, companyTelephone: '+7111' }))).toBe('tel:+7111');
    expect(managerCompanyFilialUrl(company({ id: 9, urlFilial: 'https://filial' }))).toBe('https://filial');
    expect(managerCompanyFilialUrl(company({ id: 9, urlFilial: undefined }))).toContain('/companies/editCompany/9');
    expect(managerCompanyOrderUrl(company({ id: 9 }))).toContain('/ordersCompany/9');
    expect(managerFilialEditUrl(4)).toContain('/filial/edit/4');
    expect(managerOrderDetailsUrl(order({ companyId: 9, id: 4 }))).toContain('/ordersCompany/ordersDetails/9/4');
    expect(managerOrderInfoUrl(order({ companyId: 9, id: 4 }))).toContain('/ordersDetails/9/4');
  });

  it('extracts API error messages and tracks lists by stable identifiers', () => {
    expect(managerErrorMessage({ error: { message: 'Ошибка API' } }, 'fallback')).toBe('Ошибка API');
    expect(managerErrorMessage({ error: 'plain error' }, 'fallback')).toBe('plain error');
    expect(managerErrorMessage({}, 'fallback')).toBe('fallback');
    expect(trackManagerSection(0, MANAGER_SECTIONS[1])).toBe('orders');
    expect(trackManagerStatus(0, 'Все')).toBe('Все');
    expect(trackManagerCompany(0, company({ id: 3 }))).toBe(3);
    expect(trackManagerOrder(0, order({ id: 4 }))).toBe(4);
    expect(trackManagerMetric(0, { section: 'orders', status: 'Все' } as ManagerMetric)).toBe('orders-Все');
    expect(trackManagerAction(0, MANAGER_ORDER_ACTIONS[0])).toBe('На проверке');
    expect(trackManagerOption(0, { id: 8 } as ManagerOption)).toBe(8);
    expect(trackManagerProduct(0, { id: 11 })).toBe(11);
  });
});
