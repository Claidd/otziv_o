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
      'Не удалось загрузить раздел менеджера. На сервере произошла ошибка. Обновите данные через пару минут. Если ошибка повторится, сообщите администратору.'
    );
  });

  it('explains network failures without leaking implementation details', () => {
    expect(apiErrorDetail({ status: 0 })).toBe('Сервер не отвечает. Проверьте, что серверная часть запущена, и попробуйте снова.');
  });

  it('sanitizes missed technical toast text', () => {
    expect(sanitizeErrorText('Http failure response for http://localhost:4200/api/admin/users: 500 Internal Server Error')).toBe(
      'Попробуйте обновить данные или повторить действие позже.'
    );
  });

  it('hides Spring static resource errors caused by access denied redirects', () => {
    expect(apiErrorMessage({ status: 404, error: { message: 'No static resource access-denied.' } }, 'Детали заказа не загрузились')).toBe(
      'Детали заказа не загрузились. Данные не найдены. Обновите страницу и попробуйте снова.'
    );
  });
});
