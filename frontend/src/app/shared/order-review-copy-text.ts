import type { OrderCardItem } from '../core/manager.api';

export const FIRST_ORDER_REVIEW_PROMO_INDEX = 4;
export const REPEAT_ORDER_REVIEW_PROMO_INDEX = 11;

const DEFAULT_REPEAT_ORDER_REVIEW_TEXT = [
  'Здравствуйте, Текст отзывов для новых отзывов на следующий месяц готов.',
  'Если замечаний нет, нажмите кнопку «РАЗРЕШИТЬ ПУБЛИКАЦИЮ».'
].join('\n');

export function reviewCheckPath(orderDetailsId: string): string {
  return `/${orderDetailsId}`;
}

export function absoluteAppUrl(path: string): string {
  const origin = typeof window === 'undefined' || window.location.hostname === 'localhost'
    ? 'https://o-ogo.ru'
    : window.location.origin;
  return new URL(path, origin).toString();
}

export function orderReviewCopyText(order: OrderCardItem, promoTexts: string[]): string {
  const promoText = order.firstOrderForCompany
    ? firstOrderReviewPromoText(promoTexts) || defaultFirstOrderReviewText(order)
    : repeatOrderReviewPromoText(promoTexts) || DEFAULT_REPEAT_ORDER_REVIEW_TEXT;

  return buildReviewCopyText(orderHeading(order), promoText, reviewLink(order));
}

export function orderPaymentCopyText(order: OrderCardItem, sum: number): string {
  const paymentText = [
    (order.managerPayText ?? '').trim(),
    `К оплате: ${sum} руб.`
  ].filter(Boolean).join(' ');

  return [orderHeading(order), paymentText]
    .filter(Boolean)
    .join('\n\n');
}

function reviewLink(order: OrderCardItem): string {
  const url = order.orderDetailsId
    ? absoluteAppUrl(reviewCheckPath(order.orderDetailsId))
    : '';
  return `Ссылка на проверку отзывов: ${url}`;
}

function buildReviewCopyText(heading: string, text: string, link: string): string {
  return [heading, text.trim(), link]
    .filter(Boolean)
    .join('\n\n');
}

function orderHeading(order: OrderCardItem): string {
  return [order.companyTitle, order.filialTitle]
    .map((value) => (value ?? '').trim())
    .filter(Boolean)
    .join(' - ');
}

function repeatOrderReviewPromoText(texts: string[]): string {
  const directText = promoTextAt(texts, REPEAT_ORDER_REVIEW_PROMO_INDEX);
  if (directText) {
    return directText;
  }

  return texts
    .map((value) => (value ?? '').trim())
    .find((value) => {
      const lower = value.toLocaleLowerCase('ru-RU');
      return lower.includes('текст отзывов для новых отзывов') || lower.includes('следующий месяц готов');
    }) ?? '';
}

function firstOrderReviewPromoText(texts: string[]): string {
  const directText = promoTextAt(texts, FIRST_ORDER_REVIEW_PROMO_INDEX);
  if (directText) {
    return directText;
  }

  return [texts[0]]
    .map((value) => (value ?? '').trim())
    .find((value) => {
      const lower = value.toLocaleLowerCase('ru-RU');
      return lower.includes('разрешить публикацию') || lower.includes('тексты на проверку');
    }) ?? '';
}

function promoTextAt(texts: string[], index: number): string {
  return (texts[index] ?? '').trim();
}

function defaultFirstOrderReviewText(order: OrderCardItem): string {
  return [
    'Здравствуйте, это новые тексты на проверку. Проверьте, пожалуйста, их в течение трёх дней. Если проверка не будет завершена за этот срок, публикация начнётся АВТОМАТИЧЕСКИ. Для просмотра всех карточек сделайте свайп влево.',
    '',
    '    - Если замечаний нет, нажмите кнопку «РАЗРЕШИТЬ ПУБЛИКАЦИЮ».',
    '',
    '    - Если есть небольшие замечания, и вы можете отредактировать их вручную, внесите изменения и нажмите кнопку «СОХРАНИТЬ», а затем кнопку «РАЗРЕШИТЬ ПУБЛИКАЦИЮ».',
    '',
    '    - Если замечания существенные, опишите их в разделе «замечания» и нажмите кнопку «СОХРАНИТЬ», а затем кнопку «КОРРЕКТИРОВАТЬ».'
  ].join('\n');
}
