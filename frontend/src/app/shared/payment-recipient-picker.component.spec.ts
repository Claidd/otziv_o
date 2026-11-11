import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import {
  PaymentRecipientPickerComponent,
  paymentRecipientRelation,
  type PaymentRecipientChoice,
  type PaymentRecipientOption
} from './payment-recipient-picker.component';

describe('payment recipient picker', () => {
  const person = (overrides: Partial<PaymentRecipientOption>): PaymentRecipientOption => ({
    profileId: 1, name: 'Лидия З.', role: 'SPECIALIST', orderSpecialist: false, manager: false, teamMember: true,
    workedWithClient: false, availableKopecks: 300_000, overrunKopecks: 0, current: false, blockReason: null, ...overrides
  });
  const people = [
    person({ profileId: 1, name: 'Лидия З.', orderSpecialist: true, current: true }),
    person({ profileId: 2, name: 'Вика Ц.', role: 'MANAGER', manager: true, teamMember: false }),
    person({ profileId: 3, name: 'Анна П.', workedWithClient: true, availableKopecks: 40_000, overrunKopecks: 60_000 }),
    person({ profileId: 4, name: 'Олег К.', blockReason: 'допуск к приёму оплат выключен' })
  ];

  function open() {
    TestBed.configureTestingModule({ imports: [PaymentRecipientPickerComponent] });
    const fixture = TestBed.createComponent(PaymentRecipientPickerComponent);
    const component = fixture.componentInstance;
    component.heading = 'Общий счёт №318';
    component.amountKopecks = 100_000;
    component.currentRecipient = 'Лидия З. (специалист заказа)';
    component.recipients = people;
    const emitted: PaymentRecipientChoice[] = [];
    component.chosen.subscribe(choice => emitted.push(choice));
    fixture.detectChanges();
    return { fixture, component, emitted, element: fixture.nativeElement as HTMLElement };
  }

  it('starts with the automatic choice and names every person with the reserve', () => {
    const { component, emitted, element } = open();
    expect(component.selected()).toBeNull();
    expect(element.querySelector('.pick-option.selected')?.textContent).toContain('Подобрать автоматически');
    const rows = Array.from(element.querySelectorAll('.pick-list--people .pick-option')).map(row => row.textContent?.replace(/\s+/g, ' ').trim());
    expect(rows[0]).toContain('специалист заказа');
    expect(rows[0]).toContain('сейчас в счёте');
    expect(rows[1]).toContain('менеджер');
    expect(rows[1]).toContain('хватает');
    expect(rows[2]).toContain('работал с этим клиентом');
    expect(rows[2]).toContain('сверх резерва');
    expect(rows[3]).toContain('допуск к приёму оплат выключен');
    expect(rows[3]).toContain('недоступен');

    component.submit();
    expect(emitted).toEqual([{ profileId: null }]);
  });

  it('lets a manager name a person above the reserve and says what that means before the button', () => {
    const { fixture, component, emitted, element } = open();
    component.select(3);
    fixture.detectChanges();
    expect(component.chosenOverrun()).toBe(60_000);
    expect(element.querySelector('.pick-consequence')?.classList.contains('warning')).toBe(true);
    expect(component.consequence()).toContain('сверх резерва на 600,00');
    expect(component.consequence()).toContain('не будет сама назначать');
    expect(component.consequence()).toContain('клиент ещё не оплатил');

    component.submit();
    expect(emitted).toEqual([{ profileId: 3 }]);
  });

  it('never submits the current recipient or a person who cannot receive invoices', () => {
    const { component, emitted, element } = open();
    const inputs = Array.from(element.querySelectorAll<HTMLInputElement>('.pick-list--people input'));
    expect(inputs.map(input => input.disabled)).toEqual([true, false, false, true]);
    component.select(1);
    component.submit();
    component.select(4);
    component.submit();
    expect(emitted).toEqual([]);
  });

  it('keeps the window open with the refusal of the server', () => {
    const { fixture, component, element } = open();
    component.submitError = 'Сейчас ни специалист, ни менеджер не могут принять сумму общего счёта';
    fixture.componentRef.setInput('submitError', component.submitError);
    fixture.detectChanges();
    expect(element.querySelector('.pick-error')?.textContent).toContain('не могут принять');
  });

  it('describes how a person relates to the invoice', () => {
    expect(paymentRecipientRelation(person({ orderSpecialist: true, workedWithClient: true }))).toBe('специалист заказа');
    expect(paymentRecipientRelation(person({ manager: true }))).toBe('менеджер');
    expect(paymentRecipientRelation(person({ workedWithClient: true }))).toBe('работал с этим клиентом');
    expect(paymentRecipientRelation(person({}))).toBe('сотрудник менеджера');
  });
});
