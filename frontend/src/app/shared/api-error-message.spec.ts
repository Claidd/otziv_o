import { apiErrorDetail, apiErrorMessage, sanitizeErrorText } from './api-error-message';

describe('apiErrorMessage', () => {
  it('keeps readable backend messages', () => {
    expect(apiErrorMessage({ error: { message: 'Название уже занято' } }, 'Не удалось сохранить')).toBe('Название уже занято');
    expect(apiErrorMessage({ error: 'Проверьте телефон' }, 'Не удалось сохранить')).toBe('Проверьте телефон');
  });

  it('hides Angular technical HTTP messages', () => {
    const error = {
      status: 500,
      message: 'Http failure response for http://localhost:4200/api/manager/board?page=0: 500 Internal Server Error'
    };

    expect(apiErrorMessage(error, 'Не удалось загрузить раздел менеджера')).toBe(
      'Не удалось загрузить раздел менеджера. Ошибка: на сервере произошёл внутренний сбой. Как исправить: повторите действие через минуту; если ошибка сохранится, сообщите администратору время попытки.'
    );
  });

  it('does not show access-denied text for server errors', () => {
    expect(apiErrorMessage({ status: 500, error: { message: 'У вас нет доступа к этому действию.' } }, 'Менеджер не загрузился')).toBe(
      'Менеджер не загрузился. Ошибка: на сервере произошёл внутренний сбой. Как исправить: повторите действие через минуту; если ошибка сохранится, сообщите администратору время попытки.'
    );
  });

  it('keeps readable service-unavailable messages from backend', () => {
    expect(apiErrorMessage({
      status: 503,
      error: { message: 'AI-провайдер не запустил отчёт из-за оплаты или квоты. Проверьте баланс проекта.' }
    }, 'Отчёт не запущен')).toBe('AI-провайдер не запустил отчёт из-за оплаты или квоты. Проверьте баланс проекта.');
  });

  it('keeps readable backend instructions with external links', () => {
    expect(apiErrorMessage({
      status: 409,
      error: { message: 'Telegram-группа пока не привязана. Откройте ссылку добавления Telegram-бота: https://t.me/O_Company_Bot?startgroup=c348.' }
    }, 'Автоматическая починка не сработала')).toBe(
      'Telegram-группа пока не привязана. Откройте ссылку добавления Telegram-бота: https://t.me/O_Company_Bot?startgroup=c348.'
    );
  });

  it('explains network failures without leaking implementation details', () => {
    expect(apiErrorDetail({ status: 0 })).toBe('Ошибка: сервер не отвечает. Как исправить: проверьте интернет-соединение, подождите минуту и повторите действие.');
  });

  it('sanitizes missed technical toast text', () => {
    expect(sanitizeErrorText('Http failure response for http://localhost:4200/api/admin/users: 500 Internal Server Error')).toBe(
      'Попробуйте обновить данные или повторить действие позже.'
    );
  });

  it('hides Spring static resource errors caused by access denied redirects', () => {
    expect(apiErrorMessage({ status: 404, error: { message: 'No static resource access-denied.' } }, 'Детали заказа не загрузились')).toBe(
      'Детали заказа не загрузились. Ошибка: нужные данные не найдены или уже удалены. Как исправить: обновите страницу, выберите данные заново и повторите действие.'
    );
  });
});
