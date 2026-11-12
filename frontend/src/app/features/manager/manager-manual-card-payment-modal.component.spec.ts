import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../core/auth.service';
import { CommonManualPaymentAttributionApi } from '../../core/common-manual-payment-attribution.api';
import {
  ManagerApi,
  type ManualCardPaymentContext,
  type ManualCardPaymentRecipientOption,
  type OrderCardItem
} from '../../core/manager.api';
import { CommonManualPaymentAttributionModalComponent } from '../admin/common-billing/common-manual-payment-attribution-modal.component';
import {
  ManagerManualCardPaymentModalComponent,
  manualCardPaymentWindowOptions,
  manualCardRecipientKey,
  manualCardPaymentSelectionRecipient,
  manualCardPaymentSubmission,
  originalManualCardRecipient
} from './manager-manual-card-payment-modal.component';

describe('manager manual card payment recipient selection', () => {
  const owner: ManualCardPaymentRecipientOption = {
    recipientType: 'OWNER',
    recipientProfileId: null,
    recipientUserId: 1,
    displayName: 'Владелец',
    availableKopecks: 0,
    projectedOverrunKopecks: 0
  };
  const specialist: ManualCardPaymentRecipientOption = {
    recipientType: 'SPECIALIST',
    recipientProfileId: 25,
    recipientUserId: 5,
    displayName: 'Елена',
    availableKopecks: 8_580_00,
    projectedOverrunKopecks: 0
  };
  const context = (overrides: Partial<ManualCardPaymentContext> = {}): ManualCardPaymentContext => ({
    orderId: 123,
    amountKopecks: 1_000_00,
    originalRecipient: specialist,
    candidates: [owner, specialist],
    anomalyWarning: null,
    recipientSelectionFrozen: false,
    preparedRecipient: null,
    preparedReason: null,
    preparedReceiptUrl: null,
    ...overrides
  });

  it('uses the explicit backend JSON field names', () => {
    const fixture = context();

    expect(fixture.originalRecipient.recipientType).toBe('SPECIALIST');
    expect(fixture.originalRecipient.recipientProfileId).toBe(25);
    expect(manualCardRecipientKey(fixture.originalRecipient)).toBe('SPECIALIST:25');
  });

  it('defaults only to the exact original recipient before a selection is frozen', () => {
    expect(originalManualCardRecipient([owner, specialist], specialist)).toBe(specialist);
    expect(manualCardPaymentSelectionRecipient(context())).toBe(specialist);
  });

  it('fails closed when the original recipient is absent', () => {
    expect(originalManualCardRecipient([owner], specialist)).toBeNull();
  });

  it('replays the exact prepared recipient and values when selection is frozen', () => {
    const frozen = context({
      recipientSelectionFrozen: true,
      preparedRecipient: owner,
      preparedReason: '  сохранённая причина  ',
      preparedReceiptUrl: null
    });

    expect(manualCardPaymentSelectionRecipient(frozen)).toBe(owner);
    expect(manualCardPaymentSubmission(frozen, specialist, 'другая причина', 'https://wrong.test')).toEqual({
      recipient: owner,
      reason: '  сохранённая причина  ',
      receiptUrl: null
    });
  });

  it('fails closed when the frozen prepared recipient is absent from candidates', () => {
    expect(manualCardPaymentSelectionRecipient(context({
      recipientSelectionFrozen: true,
      candidates: [owner],
      preparedRecipient: specialist,
      preparedReason: 'сохранено'
    }))).toBeNull();
  });

  it('uses the exact frozen task key instead of collapsing the task into its contractor profile', () => {
    const taskRecipient: ManualCardPaymentRecipientOption = {
      key: 'TASK:16:4',
      cashDestinationKind: 'MANUAL_PAYMENT_TASK',
      recipientType: 'SPECIALIST',
      recipientProfileId: 25,
      displayName: 'Наталья',
      manualPaymentTaskId: 16,
      manualPaymentTaskGeneration: 4,
      taskTargetKind: 'SPECIALIST',
      taskRecipientName: 'Наталья',
      accountingTargetLabel: 'Специалист · Наталья'
    };
    const fixture = context({ originalRecipient: taskRecipient, candidates: [owner, specialist, taskRecipient] });

    expect(manualCardRecipientKey(taskRecipient)).toBe('TASK:16:4');
    expect(manualCardPaymentSelectionRecipient(fixture)).toBe(taskRecipient);
    expect(manualCardRecipientKey(taskRecipient)).not.toBe(manualCardRecipientKey(specialist));
  });
});

describe('«оплатили» of an order opens the payment window of a common invoice', () => {
  const owner: ManualCardPaymentRecipientOption = {
    key: 'OWNER', recipientType: 'OWNER', recipientProfileId: null, displayName: 'Владелец', availableKopecks: 0, projectedOverrunKopecks: 0
  };
  const specialist: ManualCardPaymentRecipientOption = {
    key: 'PROFILE:25', recipientType: 'SPECIALIST', recipientProfileId: 25, displayName: 'Наталья С.',
    availableKopecks: 2_235_00, projectedOverrunKopecks: 0, bankRecipientName: 'Тест Наталья С.'
  };
  const manager: ManualCardPaymentRecipientOption = {
    key: 'PROFILE:7', recipientType: 'MANAGER', recipientProfileId: 7, displayName: 'Вика Ц.',
    availableKopecks: 1_114_00, projectedOverrunKopecks: 0
  };
  const teammate: ManualCardPaymentRecipientOption = {
    key: 'PROFILE:31', recipientType: 'SPECIALIST', recipientProfileId: 31, displayName: 'Катя К.',
    availableKopecks: 900_00, projectedOverrunKopecks: 350_00
  };
  const context = (overrides: Partial<ManualCardPaymentContext> = {}): ManualCardPaymentContext => ({
    orderId: 21333,
    amountKopecks: 1_250_00,
    originalRecipient: specialist,
    candidates: [specialist, manager, teammate, owner],
    anomalyWarning: null,
    recipientSelectionFrozen: false,
    preparedRecipient: null,
    preparedReason: null,
    preparedReceiptUrl: null,
    teamRecipientKeys: ['PROFILE:31'],
    ...overrides
  });

  async function open(paymentContext: ManualCardPaymentContext = context(), roles: string[] = ['MANAGER'], otherOrder?: ManualCardPaymentContext) {
    const confirm = vi.fn((_orderId: number, _request: unknown) => of({ status: 'COMPLETED', orderId: 21333 }));
    const recordTransfer = vi.fn((_group: unknown) => of([{ status: 'COMPLETED', orderId: 21333 }, { status: 'COMPLETED', orderId: 21400 }]));
    TestBed.configureTestingModule({
      imports: [ManagerManualCardPaymentModalComponent],
      providers: [
        { provide: ManagerApi, useValue: {
          getManualCardPaymentContext: (orderId: number) => of(orderId === 21333 ? paymentContext : otherOrder),
          confirmManualCardPayment: confirm,
          recordTransfer
        } },
        // The window of a common invoice is used as it is: its own API must stay untouched for an order.
        { provide: CommonManualPaymentAttributionApi, useValue: {} },
        { provide: AuthService, useValue: { tokenParsed: () => undefined, hasAnyRealmRole: (wanted: string[]) => wanted.some(role => roles.includes(role)) } }
      ]
    });
    const fixture = TestBed.createComponent(ManagerManualCardPaymentModalComponent);
    fixture.componentInstance.order = { id: 21333, companyTitle: 'Тело bar' } as OrderCardItem;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    return { fixture, component: fixture.componentInstance, element, confirm, recordTransfer };
  }
  /** Sums are printed with non-breaking spaces between thousands. */
  const plain = (node: Element | null) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const labels = (element: HTMLElement) => [...element.querySelectorAll('.pay-recipient strong')].map(node => plain(node));
  const button = (element: HTMLElement) => element.querySelector('.pay-button--primary') as HTMLButtonElement;
  const settle = async (fixture: { whenStable(): Promise<unknown>; detectChanges(): void }) => {
    await fixture.whenStable();
    fixture.detectChanges();
  };

  it('is the same window: the owner first, the recipient of the invoice already chosen, the same hints', async () => {
    const { element } = await open();

    expect(element.querySelector('app-common-manual-payment-attribution-modal')).not.toBeNull();
    expect(plain(element.querySelector('.pay-kicker'))).toBe('Заказ №21333');
    expect(plain(element.querySelector('h2'))).toBe('Отметить оплату');
    expect(plain(element.querySelector('.pay-head p'))).toBe('Тело bar');
    expect(plain(element.querySelector('.pay-amount'))).toContain('К оплате по счёту');
    expect(plain(element.querySelector('.pay-amount strong'))).toBe('1 250,00 ₽');
    expect(labels(element)).toEqual(['Владелец', 'Специалист · Наталья С.', 'Менеджер · Вика Ц.']);
    expect(plain(element.querySelector('.pay-recipient.selected'))).toContain('Наталья С.');
    expect(plain(element.querySelector('.pay-recipient.selected .pay-tag'))).toBe('в счёте');
    // The same second line as for a common invoice: the limit, nothing about requisites.
    expect(plain(element.querySelector('.pay-recipient.selected small'))).toBe('Доступный лимит: 2 235,00 ₽');
    expect(element.querySelector('select')).toBeNull();
    expect(plain(element.querySelector('.pay-add strong'))).toBe('Другой получатель');
    expect(plain(element.querySelector('.pay-consequence'))).toContain('Счёт закроется сразу, второго подтверждения не будет.');
    expect(plain(button(element))).toBe('Оплачен — закрыть счёт');
    expect(button(element).disabled).toBe(false);
    // Splitting one receipt between people exists for a common invoice only.
    expect(plain(element.querySelector('.pay-modal'))).not.toContain('разделить сумму');
  });

  it('closes the invoice of the order in one click through the order API', async () => {
    const { fixture, element, component, confirm } = await open();
    const completed = vi.fn();
    component.completed.subscribe(completed);

    button(element).click();
    await settle(fixture);

    expect(confirm).toHaveBeenCalledExactlyOnceWith(21333, {
      reason: 'Оплата поступила получателю из счёта',
      receiptUrl: null,
      recipientKey: 'PROFILE:25',
      recipientType: 'SPECIALIST',
      recipientProfileId: 25
    });
    expect(completed).toHaveBeenCalledTimes(1);
    expect(completed.mock.calls[0][0]).toMatchObject({ recipient: specialist, result: { status: 'COMPLETED' } });
  });

  it('sends the request to the owner when a manager says that the owner received the money', async () => {
    const { fixture, element, confirm } = await open();

    (element.querySelector('.pay-recipient input') as HTMLInputElement).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(plain(element.querySelector('.pay-recipient.selected'))).toContain('Владелец');
    expect(plain(button(element))).toBe('Отправить владельцу на подтверждение');
    expect(plain(element.querySelector('.pay-consequence'))).toContain('подтверждает сам владелец');
    expect(plain(element.querySelector('.pay-consequence'))).toContain('в рабочую группу менеджера в Телеграме');
    button(element).click();
    await settle(fixture);

    expect(confirm.mock.calls[0][1]).toMatchObject({
      reason: 'Клиент перевёл деньги не получателю из счёта', recipientKey: 'OWNER', recipientType: 'OWNER', recipientProfileId: null
    });
  });

  it('says the same to the owner and an administrator: for an order the owner always confirms by the message', async () => {
    const { fixture, element } = await open(context(), ['OWNER']);

    (element.querySelector('.pay-recipient input') as HTMLInputElement).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(plain(button(element))).toBe('Отправить владельцу на подтверждение');
    expect(plain(element.querySelector('.pay-consequence'))).not.toContain('Счёт закроется сразу');
  });

  it('offers the owner on a bank link only as a request, chosen by hand, where the server lets the owner take card transfers', async () => {
    const { fixture, element, confirm } = await open(context({
      originalRecipient: owner, candidates: [specialist, manager, teammate, owner], bankLinkRoute: true
    }));

    expect(labels(element)).toEqual(['Владелец', 'Специалист · Наталья С.', 'Менеджер · Вика Ц.']);
    // Nobody is chosen and nobody is «в счёте»: the recipient of a bank link is the bank.
    expect(element.querySelector('.pay-recipient.selected')).toBeNull();
    expect(element.querySelector('.pay-recipient .pay-tag')).toBeNull();
    expect(button(element).disabled).toBe(true);
    expect(plain(element.querySelector('.pay-warning'))).toContain('Оплату по ссылке банк отмечает сам');
    expect(plain(element.querySelector('.pay-warning'))).toContain('выберите «Владелец»');
    expect(plain(element.querySelector('.pay-warning'))).toContain('только после его подтверждения');

    (element.querySelector('.pay-recipient input') as HTMLInputElement).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(plain(element.querySelector('.pay-recipient.selected'))).toContain('Владелец');
    expect(plain(button(element))).toBe('Отправить владельцу на подтверждение');
    button(element).click();
    await settle(fixture);

    expect(confirm.mock.calls[0][1]).toMatchObject({
      reason: 'Клиент перевёл деньги не получателю из счёта', recipientKey: 'OWNER', recipientType: 'OWNER', recipientProfileId: null
    });
  });

  it('finds another member of the team through «Другой получатель» for this payment only', async () => {
    const { fixture, element, confirm } = await open();

    expect(labels(element)).not.toContain('Специалист · Катя К.');
    (element.querySelector('.pay-add') as HTMLButtonElement).click();
    await settle(fixture);
    expect(plain(element.querySelector('.pay-picker-item'))).toContain('Катя К.');
    expect(plain(element.querySelector('.pay-picker-item .pay-tag'))).toBe('сотрудник менеджера');
    (element.querySelector('.pay-picker-item') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(plain(element.querySelector('.pay-recipient.selected'))).toContain('Катя К.');
    expect(plain(element.querySelector('.pay-recipient.selected .pay-tag'))).toBe('только эта оплата');
    expect(plain(element.querySelector('.pay-modal'))).toContain('Превышение лимита 350,00 ₽');
    button(element).click();
    await settle(fixture);
    expect(confirm.mock.calls[0][1]).toMatchObject({
      reason: 'Клиент перевёл деньги не получателю из счёта', recipientKey: 'PROFILE:31', recipientProfileId: 31
    });
  });

  it('explains why the owner is absent when the invoice of the order is a bank link, and chooses nobody', async () => {
    const { element } = await open(context({ originalRecipient: owner, candidates: [specialist, manager, teammate], bankLinkRoute: true }));

    expect(labels(element)).toEqual(['Специалист · Наталья С.', 'Менеджер · Вика Ц.']);
    expect(plain(element.querySelector('.pay-warning'))).toContain('ссылка банка владельца');
    expect(plain(element.querySelector('.pay-warning'))).toContain('банк отмечает сам');
    expect(plain(element.querySelector('.pay-warning'))).toContain('на карту этот владелец оплату не принимает');
    expect(element.querySelector('.pay-recipient.selected')).toBeNull();
    expect(element.querySelector('.pay-recipient .pay-tag')).toBeNull();
    expect(button(element).disabled).toBe(true);
    expect(plain(element.querySelector('.pay-consequence'))).toContain('Выберите, кому пришли деньги.');
  });

  it('repeats a frozen confirmation with the recipient and the reason the server has kept', async () => {
    const { fixture, element, confirm } = await open(context({
      recipientSelectionFrozen: true, preparedRecipient: teammate, preparedReason: 'сохранённая причина', preparedReceiptUrl: null
    }));

    expect(labels(element)).toEqual(['Специалист · Катя К.']);
    expect(plain(element.querySelector('.pay-recipient.selected'))).toContain('Катя К.');
    expect(element.querySelector('.pay-add')).not.toBeNull();
    expect(plain(element.querySelector('.pay-warning'))).toContain('Восстанавливаем ранее подготовленное подтверждение');
    button(element).click();
    await settle(fixture);
    expect(confirm.mock.calls[0][1]).toMatchObject({ reason: 'сохранённая причина', receiptUrl: null, recipientKey: 'PROFILE:31' });
  });

  it('records one bank transfer that paid several orders of the same recipient', async () => {
    const { fixture, element, recordTransfer, confirm } = await open(context(), ['MANAGER'],
      context({ orderId: 21400, amountKopecks: 750_00 }));
    const modal = fixture.debugElement.children[0].componentInstance as CommonManualPaymentAttributionModalComponent;

    const link = [...element.querySelectorAll('.pay-link')].find(node => plain(node).includes('несколько заказов')) as HTMLButtonElement;
    link.click();
    fixture.detectChanges();
    expect(plain(element.querySelector('.pay-transfer-lines'))).toContain('Заказ №21333');
    modal.transferOrderId.set('21400');
    await modal.addTransferOrder();
    fixture.detectChanges();
    expect(plain(element.querySelector('.pay-transfer-lines'))).toContain('Заказ №21400');
    expect(plain(element.querySelector('.pay-transfer-lines'))).toContain('750,00 ₽');
    // The statement line and the whole sum are needed before the button works.
    expect(button(element).disabled).toBe(true);
    modal.transferReference.set('OP-77');
    modal.transferRubles.set('2000');
    fixture.detectChanges();
    expect(button(element).disabled).toBe(false);
    button(element).click();
    await settle(fixture);

    expect(recordTransfer).toHaveBeenCalledTimes(1);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('opens the manual of the order at «Оплата» and keeps the window open when the manual is closed', async () => {
    const { fixture, element, component } = await open();
    const closed = vi.fn();
    component.closed.subscribe(closed);

    (element.querySelector('.pay-guide') as HTMLButtonElement).click();
    fixture.detectChanges();
    const guide = element.querySelector('app-common-invoice-guide') as HTMLElement;
    expect(plain(guide.querySelector('h2'))).toBe('Счёт и оплата заказа');
    expect(plain(guide.querySelector('.guide-toc button.active'))).toContain('Оплата');
    (guide.querySelector('.guide-close') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(element.querySelector('app-common-invoice-guide')).toBeNull();
    expect(element.querySelector('.pay-modal')).not.toBeNull();
    expect(closed).not.toHaveBeenCalled();
  });

  it('reads a bank link from the missing owner when an older server does not name the route', async () => {
    const { element } = await open(context({ originalRecipient: owner, candidates: [specialist, manager, teammate] }));

    expect(plain(element.querySelector('.pay-warning'))).toContain('ссылка банка владельца');
    expect(element.querySelector('.pay-recipient.selected')).toBeNull();
  });

  it('lists everyone when the server does not tell the team apart', () => {
    const view = manualCardPaymentWindowOptions(context({ teamRecipientKeys: undefined }));

    expect(view.options.candidates.map(candidate => candidate.key)).toEqual(['OWNER', 'PROFILE:25', 'PROFILE:7', 'PROFILE:31']);
    expect(view.options.defaultRecipientKey).toBe('PROFILE:25');
    expect(view.options.candidates.filter(candidate => candidate.originalRecipient).map(candidate => candidate.key)).toEqual(['PROFILE:25']);
    expect(view.others).toEqual([]);
  });
});
