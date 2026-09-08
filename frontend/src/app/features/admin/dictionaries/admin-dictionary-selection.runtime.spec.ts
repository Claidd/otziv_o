import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OperatorPhone, OperatorPhonesApi } from '../../../core/operator-phones.api';
import { AdminAccountsApi } from '../../../core/admin-accounts.api';
import type { AdminBot } from '../../../core/admin-dictionaries.api';
import { AdminPhonesFacade } from './admin-phones.facade';
import { AdminAccountsFacade } from './admin-accounts.facade';

describe('dictionary editor identity and unsaved drafts', () => {
  let http: HttpTestingController;
  const selectedId = signal<number | null>(null);
  const toast = { success: vi.fn(), error: vi.fn() };
  const phone: OperatorPhone = { id: 8, number: '+79990000000', fio: 'Первый', amountAllowed: 1,
    amountSent: 0, blockTime: 3, googleLoginPresent: true, googlePasswordPresent: true,
    avitoPasswordPresent: false, mailLoginPresent: false, mailPasswordPresent: true, active: true, deviceTokens: [] };
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    selectedId.set(null);
    vi.clearAllMocks();
  });
  afterEach(() => { http.verify({ ignoreCancelled: true }); vi.restoreAllMocks(); });
  function phones() {
    return new AdminPhonesFacade({ api: TestBed.inject(OperatorPhonesApi), toast, isActive: () => true,
      selectedId, search: () => '', requestedPhoneId: Number.NaN });
  }
  function accounts() {
    return new AdminAccountsFacade({ api: TestBed.inject(AdminAccountsApi), toast, isActive: () => true,
      selectedId, search: () => '' });
  }

  for (const reopen of [false, true]) {
    it(`ignores phone save A after selection ${reopen ? 'A→B→A' : 'A→B'}`, () => {
      const facade = phones();
      facade.selectPhone(phone);
      facade.savePhone();
      const write = http.expectOne(request => request.method === 'PUT' && request.url.endsWith('/phones/8'));
      const second = { ...phone, id: 9, fio: 'Второй' };
      facade.selectPhone(second);
      if (reopen) facade.selectPhone(phone);
      facade.phoneForm.controls.fio.setValue('Новый черновик');
      write.flush({ ...phone, fio: 'Сохранен первый' });
      expect(selectedId()).toBe(reopen ? 8 : 9);
      expect(facade.phoneForm.controls.fio.value).toBe('Новый черновик');
      expect(facade.saving()).toBe(false);
      expect(toast.success).not.toHaveBeenCalled();
      http.expectNone(() => true);
      facade.destroy();
    });
  }

  it('preserves a new phone draft after delete A and preserves fields after device-token deletion', () => {
    const facade = phones();
    facade.selectPhone(phone);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    facade.deleteSelectedPhone();
    const write = http.expectOne(request => request.method === 'DELETE' && request.url.endsWith('/phones/8'));
    facade.startNewPhone();
    facade.phoneForm.controls.fio.setValue('Новый телефон');
    write.flush(null);
    expect(facade.phoneForm.controls.fio.value).toBe('Новый телефон');
    expect(selectedId()).toBeNull();
    http.expectNone(() => true);
    facade.selectPhone({ ...phone, deviceTokens: [{ token: 'device-token', active: true }] });
    facade.phoneForm.controls.fio.setValue('Несохраненные изменения');
    facade.removeDeviceToken(8, 'device-token');
    expect(facade.selectedPhone()?.deviceTokens).toEqual([]);
    expect(facade.phoneForm.controls.fio.value).toBe('Несохраненные изменения');
    facade.destroy();
  });

  it('retains a newly created phone ID while preserving further edits in that same form', () => {
    const facade = phones();
    facade.startNewPhone();
    facade.phoneForm.controls.number.setValue(phone.number);
    facade.savePhone();
    const create = http.expectOne(request => request.method === 'POST' && request.url.endsWith('/phones'));
    facade.phoneForm.controls.fio.setValue('Продолжает редактировать');
    create.flush(phone);
    expect(selectedId()).toBe(8);
    expect(facade.phoneForm.controls.fio.value).toBe('Продолжает редактировать');
    facade.savePhone();
    const update = http.expectOne(request => request.method === 'PUT' && request.url.endsWith('/phones/8'));
    expect(update.request.body.fio).toBe('Продолжает редактировать');
    facade.destroy();
    expect(update.cancelled).toBe(false);
    update.flush(phone);
    http.expectNone(() => true);
  });

  it('updates phone rows after GET without resetting a dirty or later selected form', () => {
    const facade = phones();
    facade.selectPhone(phone);
    facade.load();
    const first = http.expectOne(request => request.url.endsWith('/phones'));
    facade.phoneForm.controls.fio.setValue('Редактируется');
    first.flush({ phones: [{ ...phone, fio: 'Данные сервера' }], operators: [] });
    expect(facade.phones()[0].fio).toBe('Данные сервера');
    expect(facade.phoneForm.controls.fio.value).toBe('Редактируется');
    facade.phoneForm.markAsDirty();
    facade.load();
    http.expectOne(request => request.url.endsWith('/phones')).flush({ phones: [phone], operators: [] });
    expect(facade.phoneForm.controls.fio.value).toBe('Редактируется');
    facade.destroy();
  });

  it('account save cannot turn a cleared new form back into an update of the old account', () => {
    const facade = accounts();
    selectedId.set(8);
    facade.botForm.setValue({ login: 'first', password: '', fio: 'Первый', workerId: 1,
      cityId: null, statusId: 1, counter: '0', active: true });
    facade.saveBot();
    const write = http.expectOne(request => request.method === 'PUT' && request.url.endsWith('/bots/8'));
    facade.cancelDetail();
    selectedId.set(null);
    facade.botForm.controls.login.setValue('new-draft');
    write.flush({ id: 8 });
    expect(selectedId()).toBeNull();
    expect(facade.botForm.controls.login.value).toBe('new-draft');
    expect(facade.saving()).toBe(false);
    expect(toast.success).not.toHaveBeenCalled();
    http.expectNone(() => true);
    facade.destroy();
  });

  it('does not save the old account while the newly selected account is still loading', () => {
    const facade = accounts();
    selectedId.set(8);
    facade.botForm.setValue({ login: 'first', password: '', fio: 'Первый', workerId: 1,
      cityId: null, statusId: 1, counter: '0', active: true });
    facade.selectBot({ id: 9 } as AdminBot);
    const read = http.expectOne(request => request.url.endsWith('/bots/9'));
    facade.saveBot();
    http.expectNone(request => request.method !== 'GET');
    facade.destroy();
    expect(read.cancelled).toBe(true);
  });
});
