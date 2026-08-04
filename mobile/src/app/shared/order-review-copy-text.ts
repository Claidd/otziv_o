export type OrderReviewCopySource = {
  companyTitle?: string | null;
  filialTitle?: string | null;
  firstOrderForCompany?: boolean | null;
};

const FIRST_ORDER_REVIEW_TEXT =
  'Здравствуйте, это новые тексты на проверку. Проверьте, пожалуйста, их в течение трёх дней.';
const REPEAT_ORDER_REVIEW_TEXT =
  'Здравствуйте, текст отзывов для новых отзывов на следующий месяц готов.';

export function orderReviewCopyText(order: OrderReviewCopySource, reviewUrl: string): string {
  const url = reviewUrl.trim();
  const title = [order.companyTitle, order.filialTitle]
    .map((value) => (value ?? '').trim())
    .filter(Boolean)
    .join(' - ');
  const message = order.firstOrderForCompany
    ? FIRST_ORDER_REVIEW_TEXT
    : REPEAT_ORDER_REVIEW_TEXT;
  const action = url ? `Перейти к проверке отзывов:\n${url}` : '';

  return [title, message, action]
    .filter(Boolean)
    .join('\n\n');
}
