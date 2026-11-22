import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OperatorPhone, OperatorPhonesApi, PhoneOperatorOption } from '../../../core/operator-phones.api';
import { AdminPhonesFacade } from './admin-phones.facade';

describe('phone card: operator counters, required operator, validation and late results', () => {
  let http: HttpTestingController;
  let active: boolean;
  const selectedId = signal<number | null>(null);
  const toast = { success: vi.fn(), error: vi.fn() };
  const operators: PhoneOperatorOption[] = [{ id: 3, title: 'Оператор Иванова' }];
  const phone: OperatorPhone = { id: 8, number: '+79990000008', fio: 'Первый', amountAllowed: 2, amountSent: 1,
    blockTime: 30, timer: '2026-11-21T09:00:00', googleLoginPresent: false, googlePasswordPresent: false,
    avitoPasswordPresent: false, mailLoginPresent: false, mailPasswordPresent: false, active: true,
    createDate: '2026-01-10', operator: operators[0], deviceTokens: [] };
  const other: OperatorPhone = { ...phone, id: 9, number: '+79990000009', fio: 'Второй' };
  const isList = (request: HttpRequest<unknown>) => request.method === 'GET' && request.url.endsWith('/phones');
  const isWrite = (method: string) => (request: HttpRequest<unknown>) => request.method === method;

  beforeEach(() => {
    // 02:00 UTC is 10:00 in Irkutsk, the zone of the server clock.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-21T02:00:00Z'));
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    selectedId.set(null);
    active = true;
    vi.clearAllMocks();
  });
  afterEach(() => {
    http.verify({ ignoreCancelled: true });
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function create(options: { requestedPhoneId?: number; operatorOptional?: () => boolean } = {}) {
    return new AdminPhonesFacade({ api: TestBed.inject(OperatorPhonesApi), toast, isActive: () => active, selectedId,
      search: () => '', requestedPhoneId: options.requestedPhoneId ?? Number.NaN,
      operatorOptional: options.operatorOptional });
  }

  it('an edit leaves «Отправлено» and the pause to the server unless they were changed in the card', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.phoneForm.controls.fio.setValue('Новое ФИО');
    facade.phoneForm.controls.fio.markAsDirty();
    facade.savePhone();
    const untouched = http.expectOne((request) => request.method === 'PUT' && request.url.endsWith('/phones/8'));
    expect(untouched.request.body).toEqual(expect.objectContaining({ fio: 'Новое ФИО', amountSent: null, timer: null }));
    // The operator sent meanwhile: the server kept its counter and pause, and the card shows them after the save.
    untouched.flush({ ...phone, fio: 'Новое ФИО', amountSent: 0, timer: '2026-11-21T10:30:00' });
    expect(facade.phoneForm.controls.amountSent.value).toBe(0);
    expect(facade.phoneForm.controls.timer.value).toBe('2026-11-21T10:30');

    facade.phoneForm.controls.amountSent.setValue(4);
    facade.phoneForm.controls.amountSent.markAsDirty();
    facade.phoneForm.controls.timer.setValue('2026-11-21T12:00');
    facade.phoneForm.controls.timer.markAsDirty();
    facade.savePhone();
    const typed = http.expectOne(isWrite('PUT'));
    expect(typed.request.body).toEqual(expect.objectContaining({ amountSent: 4, timer: '2026-11-21T12:00' }));
    typed.flush({ ...phone, amountSent: 4, timer: '2026-11-21T12:00:00' });
    facade.destroy();
  });

  it('sends a changed counter even after the page marked the card clean; an emptied pause ends now', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.phoneForm.controls.amountSent.setValue(0);
    facade.phoneForm.markAsPristine();
    facade.phoneForm.controls.timer.setValue('');
    facade.phoneForm.controls.timer.markAsDirty();
    facade.savePhone();
    const write = http.expectOne(isWrite('PUT'));
    expect(write.request.body.amountSent).toBe(0);
    expect(write.request.body.timer).toBe('2026-11-21T10:00');
    write.flush({ ...phone, amountSent: 0, timer: '2026-11-21T10:00:00' });
    expect(facade.isTimerReady(facade.selectedPhone()!)).toBe(true);
    facade.destroy();
  });

  it('a new phone sends the counter and the pause as filled in', () => {
    const facade = create();
    facade.startNewPhone();
    facade.phoneForm.controls.number.setValue('+79990000010');
    facade.savePhone();
    const created = http.expectOne(isWrite('POST'));
    expect(created.request.body).toEqual(expect.objectContaining({ amountSent: 0, timer: '2026-11-21T10:00', operatorId: null }));
    created.flush({ ...phone, id: 10, number: '+79990000010', amountSent: 0, timer: '2026-11-21T10:00:00', operator: null });
    facade.destroy();
  });

  it('a counter confirmed by a save is not sent again when the card kept changing during that save', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.phoneForm.controls.amountSent.setValue(5);
    facade.phoneForm.controls.amountSent.markAsDirty();
    facade.savePhone();
    const first = http.expectOne(isWrite('PUT'));
    expect(first.request.body.amountSent).toBe(5);
    facade.phoneForm.controls.fio.setValue('Печатает дальше');
    first.flush({ ...phone, amountSent: 5 });
    expect(facade.phoneForm.controls.fio.value).toBe('Печатает дальше');
    facade.savePhone();
    const second = http.expectOne(isWrite('PUT'));
    expect(second.request.body.amountSent).toBeNull();
    expect(second.request.body.fio).toBe('Печатает дальше');
    second.flush({ ...phone, fio: 'Печатает дальше', amountSent: 5 });
    facade.destroy();
  });

  it('an untouched counter takes the value of a save the user kept typing through and is not sent back stale', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.phoneForm.controls.fio.setValue('Новое ФИО');
    facade.phoneForm.controls.fio.markAsDirty();
    facade.savePhone();
    const first = http.expectOne(isWrite('PUT'));
    expect(first.request.body.amountSent).toBeNull();
    facade.phoneForm.controls.fio.setValue('Новое ФИО, печатает дальше');
    // The operator sent meanwhile: the server answers with the moved counter and a new pause.
    first.flush({ ...phone, fio: 'Новое ФИО', amountSent: 0, timer: '2026-11-21T10:30:00' });
    expect(facade.phoneForm.controls.fio.value).toBe('Новое ФИО, печатает дальше');
    expect(facade.phoneForm.controls.amountSent.value).toBe(0);
    expect(facade.phoneForm.controls.timer.value).toBe('2026-11-21T10:30');
    facade.savePhone();
    const second = http.expectOne(isWrite('PUT'));
    expect(second.request.body).toEqual(expect.objectContaining({ amountSent: null, timer: null }));
    second.flush({ ...phone, fio: 'Новое ФИО, печатает дальше', amountSent: 0, timer: '2026-11-21T10:30:00' });
    facade.destroy();
  });

  it('a reload refreshes the counter and the pause the user did not change and keeps typed fields', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.phoneForm.controls.fio.setValue('Правка');
    facade.phoneForm.controls.fio.markAsDirty();
    facade.load();
    http.expectOne(isList).flush({ phones: [{ ...phone, amountSent: 0, timer: '2026-11-21T10:40:00' }], operators });
    expect(facade.phoneForm.controls.fio.value).toBe('Правка');
    expect(facade.phoneForm.controls.amountSent.value).toBe(0);
    expect(facade.phoneForm.controls.timer.value).toBe('2026-11-21T10:40');

    facade.phoneForm.controls.amountSent.setValue(7);
    facade.phoneForm.controls.amountSent.markAsDirty();
    facade.load();
    http.expectOne(isList).flush({ phones: [{ ...phone, amountSent: 1 }], operators });
    expect(facade.phoneForm.controls.amountSent.value).toBe(7);
    expect(facade.phoneForm.controls.timer.value).toBe('2026-11-21T09:00');
    facade.savePhone();
    const write = http.expectOne(isWrite('PUT'));
    expect(write.request.body).toEqual(expect.objectContaining({ fio: 'Правка', amountSent: 7, timer: null }));
    write.flush({ ...phone, fio: 'Правка', amountSent: 7 });
    facade.destroy();
  });

  it('outside the administrator role the card asks for an operator instead of a server «Оператор не найден»', () => {
    const facade = create({ operatorOptional: () => false });
    facade.load();
    http.expectOne(isList).flush({ phones: [], operators });
    facade.phoneForm.controls.number.setValue('+79990000011');
    facade.savePhone();
    http.expectNone(isWrite('POST'));
    expect(facade.phoneForm.touched).toBe(true);
    expect(facade.phoneForm.errors?.['problem'])
      .toBe('Выберите оператора: телефон без оператора может вести только администратор.');
    facade.phoneForm.controls.operatorId.setValue(3);
    expect(facade.phoneForm.errors).toBeNull();
    facade.savePhone();
    const created = http.expectOne(isWrite('POST'));
    expect(created.request.body.operatorId).toBe(3);
    created.flush({ ...phone, id: 11, number: '+79990000011' });
    facade.destroy();
  });

  it('says so when a non-administrator has no operator to choose', () => {
    const withoutOperators = create({ operatorOptional: () => false });
    withoutOperators.load();
    http.expectOne(isList).flush({ phones: [], operators: [] });
    withoutOperators.phoneForm.controls.number.setValue('+79990000012');
    expect(withoutOperators.phoneForm.errors?.['problem'])
      .toBe('Нет операторов для выбора: телефон без оператора может вести только администратор.');
    withoutOperators.destroy();
  });

  it('needs ten digits and at most twenty characters in a number while a stored shorter number stays editable', () => {
    const facade = create();
    facade.startNewPhone();
    expect(facade.phoneFormProblem()).toBe('В номере должно быть не меньше 10 цифр.');
    facade.savePhone();
    http.expectNone(isWrite('POST'));
    facade.phoneForm.controls.number.setValue('+7 999 000 00 11 22 33 44');
    expect(facade.phoneForm.errors?.['problem']).toBe('Номер длиннее 20 символов.');
    facade.phoneForm.controls.number.setValue('   ');
    expect(facade.phoneForm.errors?.['problem']).toBe('Укажите номер телефона.');

    facade.selectPhone({ ...phone, number: '12345' });
    expect(facade.phoneForm.valid).toBe(true);
    facade.phoneForm.controls.number.setValue('12346');
    expect(facade.phoneForm.errors?.['problem']).toBe('В номере должно быть не меньше 10 цифр.');
    facade.phoneForm.controls.number.setValue('12345');
    facade.savePhone();
    http.expectOne(isWrite('PUT')).flush({ ...phone, number: '12345' });
    facade.destroy();
  });

  it('names negative limits and long texts instead of «Номер и числовые поля обязательны»', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.phoneForm.controls.blockTime.setValue(-1);
    expect(facade.phoneForm.errors?.['problem'])
      .toBe('Заполните «Разрешено отправлять», «Отправлено» и «Блокировка, мин» числами от 0.');
    facade.phoneForm.controls.blockTime.setValue(3);
    facade.phoneForm.controls.fio.setValue('Я'.repeat(101));
    expect(facade.phoneForm.errors?.['problem']).toBe('ФИО длиннее 100 символов.');
    facade.phoneForm.controls.fio.setValue('Первый');
    expect(facade.phoneForm.errors).toBeNull();
    facade.destroy();
  });

  it('reports a save that fails after another phone was opened, with its number, and keeps the open draft', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.savePhone();
    const write = http.expectOne(isWrite('PUT'));
    facade.selectPhone(other);
    facade.phoneForm.controls.fio.setValue('Черновик второго');
    write.flush({ message: 'Оператор не найден' }, { status: 404, statusText: 'Not Found' });
    expect(toast.error).toHaveBeenCalledWith('Телефон не сохранен', '+79990000008: Оператор не найден');
    expect(facade.error()).toBe('Телефон +79990000008 не сохранен. Оператор не найден');
    expect(facade.selectedPhone()?.id).toBe(9);
    expect(facade.phoneForm.controls.fio.value).toBe('Черновик второго');
    expect(facade.saving()).toBe(false);
    facade.destroy();
  });

  it('removes a deleted row even when the editor moved on, and keeps the new draft', () => {
    const facade = create();
    facade.load();
    http.expectOne(isList).flush({ phones: [phone, other], operators });
    facade.selectPhone(phone);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    facade.deleteSelectedPhone();
    const removal = http.expectOne((request) => request.method === 'DELETE' && request.url.endsWith('/phones/8'));
    facade.selectPhone(other);
    facade.phoneForm.controls.fio.setValue('Черновик второго');
    removal.flush(null);
    expect(facade.phones().map((item) => item.id)).toEqual([9]);
    expect(facade.selectedPhone()?.id).toBe(9);
    expect(facade.phoneForm.controls.fio.value).toBe('Черновик второго');
    http.expectNone(() => true);
    facade.destroy();
  });

  it('closes a deleted phone that was opened again while the deletion ran', () => {
    const facade = create();
    facade.selectPhone(phone);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    facade.deleteSelectedPhone();
    const removal = http.expectOne(isWrite('DELETE'));
    facade.selectPhone(phone);
    removal.flush(null);
    expect(facade.selectedPhone()).toBeNull();
    expect(toast.success).toHaveBeenCalledWith('Телефон удален', '+79990000008');
    http.expectOne(isList);
    facade.destroy();
  });

  it('reads the list again after a write that cancelled the read, also when the save fails', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.load();
    const before = http.expectOne(isList);
    facade.savePhone();
    expect(before.cancelled).toBe(true);
    const write = http.expectOne(isWrite('PUT'));
    facade.selectPhone(other);
    write.flush({ ...phone });
    http.expectOne(isList).flush({ phones: [phone, other], operators });
    expect(facade.phones()).toHaveLength(2);

    facade.load();
    const again = http.expectOne(isList);
    facade.savePhone();
    expect(again.cancelled).toBe(true);
    http.expectOne(isWrite('PUT')).flush({ message: 'Номер занят' }, { status: 400, statusText: 'Bad Request' });
    http.expectOne(isList).flush({ phones: [phone, other], operators });
    expect(facade.selectedPhone()?.id).toBe(9);
    expect(facade.error()).toBe('Телефон +79990000009 не сохранен. Номер занят');
    facade.destroy();
  });

  it('leaves a read cancelled by hiding the tab to the page', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.load();
    http.expectOne(isList);
    facade.savePhone();
    const write = http.expectOne(isWrite('PUT'));
    active = false;
    facade.deactivate();
    write.flush({ ...phone });
    expect(facade.saving()).toBe(false);
    http.expectNone(isList);
    facade.destroy();
  });

  it('shows the pause by the Irkutsk clock, not by the browser time zone', () => {
    const facade = create();
    expect(facade.isTimerReady({ ...phone, timer: '2026-11-21T09:59:00' })).toBe(true);
    expect(facade.isTimerReady({ ...phone, timer: '2026-11-21T10:00:00' })).toBe(true);
    expect(facade.isTimerReady({ ...phone, timer: '2026-11-21T10:01:00' })).toBe(false);
    expect(facade.timerState({ ...phone, timer: '2026-11-21T10:01' })).toBe('пауза');
    expect(facade.timerState({ ...phone, timer: null })).toBe('готов');
    facade.destroy();
  });

  it('says when a linked phone is missing and opens a new card; an existing one opens silently', () => {
    const missing = create({ requestedPhoneId: 77 });
    missing.load();
    http.expectOne(isList).flush({ phones: [phone], operators });
    expect(toast.error).toHaveBeenCalledWith('Телефон не найден',
      'Телефон #77 не найден или недоступен. Открыта карточка нового телефона.');
    expect(missing.selectedPhone()).toBeNull();
    expect(missing.phoneForm.controls.number.value).toBe('+7');
    missing.destroy();

    const found = create({ requestedPhoneId: 8 });
    found.load();
    http.expectOne(isList).flush({ phones: [phone], operators });
    expect(found.selectedPhone()?.id).toBe(8);
    expect(toast.error).toHaveBeenCalledTimes(1);
    found.destroy();
  });

  it('keeps the open phone through a search while the new list still has it', () => {
    const facade = create();
    facade.selectPhone(phone);
    facade.clearSelection();
    facade.load();
    http.expectOne(isList).flush({ phones: [phone, other], operators });
    expect(facade.selectedPhone()?.id).toBe(8);

    facade.clearSelection();
    facade.load();
    http.expectOne(isList).flush({ phones: [other], operators });
    expect(facade.selectedPhone()).toBeNull();

    facade.selectPhone(other);
    active = false;
    facade.deactivate();
    facade.clearSelection();
    active = true;
    facade.load();
    http.expectOne(isList).flush({ phones: [other], operators });
    expect(facade.selectedPhone()).toBeNull();
    facade.destroy();
  });

  it('names the consequences before deleting and explains a refusal by the lead import pool', () => {
    const facade = create();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    facade.selectPhone(phone);
    facade.deleteSelectedPhone();
    expect(confirm.mock.calls[0][0]).toContain('Лиды этого номера останутся без телефона');
    expect(confirm.mock.calls[0][0]).toContain('снимите «Телефон активен»');
    http.expectOne(isWrite('DELETE')).flush(
      { message: 'Изменение не сохранено: запись связана с рабочими данными. История сохранена, ничего не удалено.' },
      { status: 400, statusText: 'Bad Request' });
    expect(facade.error()).toBe('Телефон +79990000008 не удален. Номер входит в пул импорта лидов менеджера, '
      + 'поэтому удалить его нельзя. Чтобы убрать номер из работы, снимите «Телефон активен» и сохраните.');
    expect(toast.error).toHaveBeenCalledWith('Телефон не удален', expect.stringContaining('пул импорта лидов'));

    const withDevice: OperatorPhone = { ...phone, deviceTokens: [{ token: 'device-hash', active: true }] };
    facade.selectPhone(withDevice);
    facade.deleteDeviceToken(withDevice, withDevice.deviceTokens[0]);
    expect(confirm.mock.calls[1][0]).toContain('сразу потеряет доступ');
    http.expectOne((request) => request.method === 'DELETE' && request.url.endsWith('/device-tokens/device-hash')).flush(null);
    expect(toast.success).toHaveBeenCalledWith('Устройство отвязано', '+79990000008');
    expect(facade.selectedPhone()?.deviceTokens).toEqual([]);
    facade.destroy();
  });
});
