import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminAccountsApi } from '../../../core/admin-accounts.api';
import { AdminWorkSettingsApi } from '../../../core/admin-work-settings.api';
import { AdminGamificationApi } from '../../../core/admin-gamification.api';
import { AdminGamificationRewardsApi } from '../../../core/admin-gamification-rewards.api';
import { AdminContractorSystemApi } from '../../../core/admin-contractor-system.api';
import { OperatorPhonesApi, OperatorPhone } from '../../../core/operator-phones.api';
import { AdminBot } from '../../../core/admin-dictionaries.api';
import { AdminAccountsFacade } from './admin-accounts.facade';
import { AdminPhonesFacade } from './admin-phones.facade';
import { AdminWorkSettingsFacade } from './admin-work-settings.facade';
import { AdminGamificationFacade } from './admin-gamification.facade';
import { AdminContractorSystemFacade } from './admin-contractor-system.facade';

describe('independent dictionary domain lifetimes', () => {
  let http: HttpTestingController;
  const toast = { success: vi.fn(), error: vi.fn() };
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    vi.clearAllMocks();
  });
  afterEach(() => { http.verify({ ignoreCancelled: true }); });

  it('cancels account list, detail and city snapshot on leave; stale selection cannot return', () => {
    const selectedId = signal<number | null>(null);
    const facade = new AdminAccountsFacade({ api: TestBed.inject(AdminAccountsApi), toast,
      isActive: () => true, selectedId, search: () => '' });
    facade.load();
    const reads = http.match(request => request.method === 'GET');
    expect(reads).toHaveLength(2);
    facade.selectBot({ id: 8 } as AdminBot);
    const detail = http.expectOne(request => request.url.endsWith('/bots/8'));
    facade.cancelDetail();
    expect(detail.cancelled).toBe(true);
    facade.deactivate();
    expect(reads.every(request => request.cancelled)).toBe(true);
    expect(selectedId()).toBeNull();
    expect(facade.loading()).toBe(false);
  });

  it('keeps phone credentials write-only and does not abort/replay saving when hidden', () => {
    let active = true;
    const selectedId = signal<number | null>(null);
    const facade = new AdminPhonesFacade({ api: TestBed.inject(OperatorPhonesApi), toast,
      isActive: () => active, selectedId, search: () => '', requestedPhoneId: Number.NaN });
    const phone: OperatorPhone = { id: 8, number: '+79990000000', amountAllowed: 1, amountSent: 0,
      blockTime: 3, googleLoginPresent: true, googlePasswordPresent: true, avitoPasswordPresent: false,
      mailLoginPresent: false, mailPasswordPresent: true, active: true, deviceTokens: [] };
    facade.selectPhone(phone);
    expect(facade.phoneForm.controls.googlePassword.value).toBe('');
    expect(facade.phoneForm.controls.mailPassword.value).toBe('');
    facade.savePhone();
    facade.savePhone();
    const write = http.expectOne(request => request.method === 'PUT' && request.url.endsWith('/phones/8'));
    expect(write.request.body.googlePassword).toBeNull();
    expect(write.request.body.mailPassword).toBeNull();
    active = false;
    facade.deactivate();
    selectedId.set(99);
    expect(write.cancelled).toBe(false);
    write.flush(phone);
    expect(facade.saving()).toBe(false);
    expect(selectedId()).toBe(99);
    expect(toast.success).not.toHaveBeenCalled();
    http.expectNone(request => request.method === 'GET');
  });

  it('settings preserve worker cooldown endpoint, sequence partial writes and re-read on failure', () => {
    const facade = new AdminWorkSettingsFacade({ api: TestBed.inject(AdminWorkSettingsApi), toast,
      isActive: () => true, selectedId: signal(null), search: () => '', canApplyMaintenance: () => false });
    facade.settingsForm.controls.workerAccountActionCooldownSeconds.setValue(75);
    facade.saveSettings();
    const first = http.expectOne(request => request.url.endsWith('/dictionaries/worker-account-action-settings'));
    expect(first.request.method).toBe('PUT');
    expect(first.request.body).toEqual({ enabled: true, cooldownSeconds: 75 });
    http.expectNone(request => request.url.endsWith('/settings/nagul'));
    first.flush({ enabled: true, cooldownSeconds: 75 });
    http.expectOne(request => request.method === 'PUT' && request.url.endsWith('/settings/nagul'))
      .flush({ message: 'rejected' }, { status: 409, statusText: 'Conflict' });
    expect(toast.error).toHaveBeenCalledWith('Настройки сохранены частично', expect.stringContaining('1 из 5'));
    expect(facade.saving()).toBe(false);
    const reloads = http.match(request => request.method === 'GET');
    expect(reloads).toHaveLength(5);
    expect(reloads.some(request => request.request.url.endsWith('/worker-cellular-access'))).toBe(false);
    facade.deactivate();
    expect(reloads.every(request => request.cancelled)).toBe(true);
  });

  it('does not hide a duplicate maintenance command behind two confirmation dialogs', () => {
    const facade = new AdminWorkSettingsFacade({ api: TestBed.inject(AdminWorkSettingsApi), toast,
      isActive: () => true, selectedId: signal(null), search: () => '', canApplyMaintenance: () => true });
    const confirm = vi.spyOn(window, 'confirm').mockImplementation(() => { facade.saveSettings(); return false; });
    facade.saveSettings();
    expect(confirm).toHaveBeenCalledOnce();
    expect(facade.saving()).toBe(false);
    http.expectNone(() => true);
    confirm.mockRestore();
  });

  it('changing gamification period cancels stale initial responses without losing settings load', () => {
    const facade = new AdminGamificationFacade({ api: TestBed.inject(AdminGamificationApi),
      rewardsApi: TestBed.inject(AdminGamificationRewardsApi), toast, isActive: () => true,
      selectedId: signal(null), search: () => '' });
    facade.load();
    const old = http.match(() => true);
    expect(old).toHaveLength(8);
    facade.setGamificationProgressDays(7);
    expect(old.every(request => request.cancelled)).toBe(true);
    const current = http.match(() => true);
    expect(current).toHaveLength(8);
    expect(current.filter(request => request.request.params.has('days'))
      .every(request => request.request.params.get('days') === '7')).toBe(true);
    facade.destroy();
    expect(current.every(request => request.cancelled)).toBe(true);
  });

  it('contractor settings read scope is independent and never loads legacy reconciliation while hidden', () => {
    let active = true;
    const facade = new AdminContractorSystemFacade({ api: TestBed.inject(AdminContractorSystemApi), toast,
      isActive: () => active, ownerAllowed: () => false });
    facade.loadContractorPaymentSystemStatus();
    const reads = http.match(() => true);
    expect(reads).toHaveLength(2);
    active = false;
    facade.deactivate();
    facade.loadContractorLegacyReconciliation();
    expect(reads.every(request => request.cancelled)).toBe(true);
    http.expectNone(() => true);
  });
});
