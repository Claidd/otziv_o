import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../../core/auth.service';
import { CommonManualPaymentAttributionApi } from '../../../core/common-manual-payment-attribution.api';
import {
  CommonManualPaymentAttributionModalComponent,
  commonManualPaymentDefaultReason,
  commonManualPaymentRublesToKopecks,
  commonManualPaymentTotalKopecks
} from './common-manual-payment-attribution-modal.component';

describe('common manual payment attribution amounts', () => {
  it('parses rubles without binary floating point rounding', () => {
    expect(commonManualPaymentRublesToKopecks('1 234,56')).toBe(123456);
    expect(commonManualPaymentRublesToKopecks('1000')).toBe(100000);
    expect(commonManualPaymentRublesToKopecks('0,01')).toBe(1);
  });

  it('rejects zero, negative and fractions below one kopeck', () => {
    expect(commonManualPaymentRublesToKopecks('0')).toBeNull();
    expect(commonManualPaymentRublesToKopecks('-1')).toBeNull();
    expect(commonManualPaymentRublesToKopecks('1,001')).toBeNull();
    expect(commonManualPaymentRublesToKopecks('руб. 10')).toBeNull();
  });

  it('requires every split row to be valid and sums exact kopecks', () => {
    expect(commonManualPaymentTotalKopecks([
      { rowKey: 'a', recipientKey: 'OWNER', amountRubles: '10,01' },
      { rowKey: 'b', recipientKey: 'PROFILE:2', amountRubles: '20,02' }
    ])).toBe(3003);
    expect(commonManualPaymentTotalKopecks([
      { rowKey: 'a', recipientKey: 'OWNER', amountRubles: '' }
    ])).toBeNull();
  });
});

describe('common manual payment dialog', () => {
  const candidates = [
    { key: 'OWNER', recipientType: 'OWNER', label: 'Владелец', originalRecipient: false, currentParticipant: false, profileEnabled: true },
    { key: 'PROFILE:7', recipientType: 'MANAGER', recipientProfileId: 7, label: 'Менеджер · Вика Ц.', originalRecipient: true,
      currentParticipant: true, profileEnabled: true, availableKopecks: 500_000 },
    { key: 'PROFILE:9', recipientType: 'SPECIALIST', recipientProfileId: 9, label: 'Специалист · Виктория Ц.', originalRecipient: false,
      currentParticipant: true, profileEnabled: true, availableKopecks: 500_000 }
  ];

  async function open(mode: 'STANDARD' | 'TBANK_ROUTE_FALLBACK', roles: string[], list = candidates, others: unknown[] = []) {
    const confirm = vi.fn((_invoiceId: number, _mode: string, _request: unknown) => of({ summary: { status: 'PAID' } }));
    const otherRecipients = vi.fn((_invoiceId: number) => of({ invoiceId: 318, recipients: others }));
    TestBed.configureTestingModule({
      imports: [CommonManualPaymentAttributionModalComponent],
      providers: [
        { provide: CommonManualPaymentAttributionApi, useValue: {
          options: () => of({ invoiceId: 318, remainingKopecks: 100_000, defaultRecipientKey: 'PROFILE:7', candidates: list, history: [] }),
          otherRecipients,
          confirm
        } },
        { provide: AuthService, useValue: { tokenParsed: () => undefined, hasAnyRealmRole: (wanted: string[]) => wanted.some(role => roles.includes(role)) } }
      ]
    });
    const fixture = TestBed.createComponent(CommonManualPaymentAttributionModalComponent);
    fixture.componentInstance.invoiceId = 318;
    fixture.componentInstance.mode = mode;
    fixture.detectChanges(); // ngOnInit starts the first load
    await fixture.componentInstance.load();
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, confirm, otherRecipients };
  }

  it('opens the manual of the common invoice at «Оплата» and keeps the window open when the manual is closed', async () => {
    const { fixture, component } = await open('STANDARD', ['MANAGER']);
    const element: HTMLElement = fixture.nativeElement;
    const closed = vi.fn();
    component.closed.subscribe(closed);

    (element.querySelector('.pay-guide') as HTMLButtonElement).click();
    fixture.detectChanges();
    const guide = element.querySelector('app-common-invoice-guide') as HTMLElement;
    expect(guide.querySelector('h2')?.textContent).toContain('Работа с общим счётом');
    expect(guide.querySelector('.guide-toc button.active')?.textContent).toContain('Оплата');
    (guide.querySelector('.guide-close') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(element.querySelector('app-common-invoice-guide')).toBeNull();
    expect(element.querySelector('.pay-modal')).not.toBeNull();
    expect(closed).not.toHaveBeenCalled();
  });

  it('opens with the invoice recipient chosen and closes the invoice in one click without checkboxes', async () => {
    const { fixture, component, confirm } = await open('STANDARD', ['MANAGER']);
    const element: HTMLElement = fixture.nativeElement;
    expect(component.selectedKey()).toBe('PROFILE:7');
    expect(element.querySelectorAll('input[type="checkbox"]').length).toBe(0);
    expect(element.querySelector('.pay-recipient.selected')?.textContent).toContain('Вика Ц.');
    expect(element.querySelector('.pay-button--primary')?.textContent).toContain('Оплачен — закрыть счёт');
    expect(component.canSubmit()).toBe(true);

    await component.submit();
    const request = confirm.mock.calls[0][2] as { reason: string; paymentReceived: boolean; finalAccountingAcknowledged: boolean;
      attributions: { recipientKey: string; amountKopecks: number }[] };
    expect(request.attributions).toEqual([expect.objectContaining({ recipientKey: 'PROFILE:7', amountKopecks: 100_000 })]);
    expect(request.reason).toBe('Оплата поступила получателю из счёта');
    expect(request.paymentReceived && request.finalAccountingAcknowledged).toBe(true);
  });

  it('keeps an explicit choice when the client did not pay by the bank link', async () => {
    const { component } = await open('TBANK_ROUTE_FALLBACK', ['MANAGER'], candidates.filter(candidate => candidate.key !== 'OWNER'));
    expect(component.selectedKey()).toBe('');
    expect(component.canSubmit()).toBe(false);
    expect(component.consequence()).toBe('Выберите, кому пришли деньги.');
    component.selectRecipient('PROFILE:9');
    expect(component.canSubmit()).toBe(true);
  });

  it('tells a manager that a receipt by the owner waits for the owner, and lets the owner credit it at once', async () => {
    const manager = await open('STANDARD', ['MANAGER']);
    manager.component.selectRecipient('OWNER');
    expect(manager.component.submitLabel()).toBe('Отправить владельцу на подтверждение');
    expect(manager.component.consequence()).toContain('после его подтверждения');
    TestBed.resetTestingModule();
    const owner = await open('STANDARD', ['OWNER']);
    owner.component.selectRecipient('OWNER');
    expect(owner.component.submitLabel()).toBe('Оплачен — закрыть счёт');
  });

  it('offers people outside the invoice only behind «+» and credits the picked one for this receipt', async () => {
    const teamMember = { key: 'PROFILE:11', recipientType: 'SPECIALIST', recipientProfileId: 11, label: 'Специалист · Олег К.',
      originalRecipient: false, currentParticipant: false, profileEnabled: true, availableKopecks: 0 };
    const former = { key: 'PROFILE:12', recipientType: 'SPECIALIST', recipientProfileId: 12, label: 'Специалист · Анна П.',
      originalRecipient: false, currentParticipant: false, profileEnabled: false, availableKopecks: 0 };
    const { fixture, component, confirm, otherRecipients } = await open('STANDARD', ['MANAGER'], [...candidates, teamMember], [
      { candidate: former, previousPerformer: true, teamMember: false },
      { candidate: teamMember, previousPerformer: false, teamMember: true }
    ]);
    const element: HTMLElement = fixture.nativeElement;
    // A team member who takes no part in the invoice stays out of the ordinary list.
    expect(component.listedCandidates().map(candidate => candidate.key)).toEqual(['OWNER', 'PROFILE:7', 'PROFILE:9']);
    expect(otherRecipients).not.toHaveBeenCalled();
    expect(element.querySelector('.pay-add')?.textContent).toContain('Другой получатель');

    await component.openPicker();
    fixture.detectChanges();
    expect(otherRecipients).toHaveBeenCalledTimes(1);
    expect(element.querySelectorAll('.pay-picker-item').length).toBe(2);
    expect(component.pickerResults().map(item => component.otherTag(item)))
      .toEqual(['работал с этим клиентом', 'сотрудник менеджера']);
    component.pickerQuery.set('анна');
    expect(component.pickerResults().map(item => item.candidate.key)).toEqual(['PROFILE:12']);

    component.pickOther(component.pickerResults()[0]);
    fixture.detectChanges();
    expect(component.selectedKey()).toBe('PROFILE:12');
    expect(component.pickerOpen()).toBe(false);
    expect(element.querySelector('.pay-recipient.selected')?.textContent).toContain('только эта оплата');
    expect(component.consequence()).toContain('Резерв получателя из счёта при этом снимется');

    // Another pick replaces the one-off recipient instead of growing the list.
    await component.openPicker();
    component.pickOther(component.pickerResults()[0]);
    expect(component.listedCandidates().map(candidate => candidate.key)).toEqual(['OWNER', 'PROFILE:7', 'PROFILE:9', 'PROFILE:11']);
    expect(otherRecipients).toHaveBeenCalledTimes(1);

    await component.submit();
    const request = confirm.mock.calls[0][2] as { reason: string;
      attributions: { recipientKey: string; recipientProfileId: number; amountKopecks: number }[] };
    expect(request.attributions).toEqual([
      expect.objectContaining({ recipientKey: 'PROFILE:11', recipientProfileId: 11, amountKopecks: 100_000 })
    ]);
    expect(request.reason).toBe('Клиент перевёл деньги не получателю из счёта');
  });

  it('writes a default reason that matches the chosen recipients', () => {
    expect(commonManualPaymentDefaultReason([{ originalRecipient: true }])).toBe('Оплата поступила получателю из счёта');
    expect(commonManualPaymentDefaultReason([{ originalRecipient: false }])).toBe('Клиент перевёл деньги не получателю из счёта');
    expect(commonManualPaymentDefaultReason([{ originalRecipient: true }, { originalRecipient: false }]))
      .toBe('Оплата распределена между несколькими получателями');
  });
});
