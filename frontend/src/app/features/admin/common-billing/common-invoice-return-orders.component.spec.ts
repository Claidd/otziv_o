import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import {
  CommonInvoiceReturnOrdersComponent,
  RETURN_ORDER_REFUSAL_LEAD
} from './common-invoice-return-orders.component';
import type {
  CommonInvoiceReturnableOrder,
  CommonInvoiceReturnableOrdersResponse
} from '../../../core/common-billing.api';

describe('Return order to common invoice window', () => {
  const first: CommonInvoiceReturnableOrder = { orderId: 27391, companyId: 20, companyTitle: 'Кафе на Ленина',
    orderTitle: 'Заказ №27391 · Центр', status: 'Опубликовано', amountKopecks: 90_000 };
  const second: CommonInvoiceReturnableOrder = { orderId: 27400, companyId: 21, companyTitle: 'Кафе у вокзала',
    orderTitle: 'Заказ №27400', status: 'На проверке', amountKopecks: 0 };
  const answer = (patch: Partial<CommonInvoiceReturnableOrdersResponse> = {}): CommonInvoiceReturnableOrdersResponse => ({
    invoiceId: 318, canReturn: true, blockReason: null, orders: [first, second], ...patch
  });

  function open(inputs: Partial<CommonInvoiceReturnOrdersComponent> = {}) {
    TestBed.configureTestingModule({ imports: [CommonInvoiceReturnOrdersComponent] });
    const fixture = TestBed.createComponent(CommonInvoiceReturnOrdersComponent);
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('invoiceId', 318);
    fixture.componentRef.setInput('heading', 'Общий счёт №318');
    fixture.componentRef.setInput('orders', answer());
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    const returned: CommonInvoiceReturnableOrder[] = [];
    component.returned.subscribe(order => returned.push(order));
    const events = { closed: 0, retry: 0 };
    component.closed.subscribe(() => events.closed++);
    component.retry.subscribe(() => events.retry++);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const text = () => (element.textContent ?? '').replace(/\s+/g, ' ');
    const button = (label: string) => Array.from(element.querySelectorAll('button'))
      .find(candidate => candidate.textContent?.trim() === label) as HTMLButtonElement | undefined;
    return { fixture, component, returned, events, element, text, button };
  }

  it('lists the company, the order, its status and amount', () => {
    const { element, text } = open();
    const rows = Array.from(element.querySelectorAll('.ret-row')).map(row => (row.textContent ?? '').replace(/\s+/g, ' ').trim());
    expect(rows.length).toBe(2);
    expect(rows[0]).toContain('Кафе на Ленина');
    expect(rows[0]).toContain('Заказ №27391 · Центр');
    expect(rows[0]).toContain('Опубликовано');
    expect(rows[0]).toContain('900 ₽');
    expect(rows[1]).toContain('На проверке');
    expect(rows[1]).toContain('сумма не посчитана');
    expect(text()).toContain('Общий счёт №318');
    expect(element.querySelectorAll('.ret-row .ret-button').length).toBe(2);
  });

  it('returns an order only after its consequence is confirmed; «Отмена» leaves everything untouched', () => {
    const { fixture, component, returned, element, text, button } = open();
    (element.querySelector('.ret-row .ret-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(returned).toEqual([]);
    expect(component.confirming()).toBe(27391);
    expect(text()).toContain('Заказ №27391 вернётся в общий счёт №318');
    expect(text()).toContain('сумма счёта вырастет на 900 ₽');
    expect(text()).toContain('Клиенту сейчас ничего не отправляется');

    button('Отмена')?.click();
    fixture.detectChanges();
    expect(returned).toEqual([]);
    expect(component.confirming()).toBeNull();
    expect(element.querySelector('.ret-confirm')).toBeNull();

    (element.querySelector('.ret-row .ret-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    button('Подтвердить возврат')?.click();
    expect(returned).toEqual([first]);
  });

  it('explains why the list is empty', () => {
    const { element, text } = open({ orders: answer({ orders: [] }) });
    expect(element.querySelector('.ret-row')).toBeNull();
    expect(text()).toContain('Возвращать нечего');
    expect(text()).toContain('из счёта ничего не исключали');
    expect(text()).toContain('такие заказы в счёт не возвращаются');
    expect(text()).toContain('от «Новый» до «Напоминание»');
  });

  it('says why a fixed invoice cannot take an order back and offers no rows', () => {
    const reason = 'Общий счёт уже отправлен клиенту, его состав зафиксирован. Что делать: ведите заказ отдельно — он оплачивается своим счётом.';
    const { element, text } = open({ orders: answer({ canReturn: false, blockReason: reason }) });
    expect(element.querySelector('.ret-row')).toBeNull();
    expect(text()).toContain('счёт не изменён');
    expect(text()).toContain(reason);
  });

  it('keeps a refusal in the window once and brings the row back to its button', () => {
    const { fixture, component, element, text } = open();
    component.ask(first);
    fixture.detectChanges();
    fixture.componentRef.setInput('refusal',
      `${RETURN_ORDER_REFUSAL_LEAD} Заказ #27391 нельзя включить в общий счет: отдельный способ оплаты не закрыт безопасно.`);
    fixture.detectChanges();
    const refusal = (element.querySelector('.ret-refusal')?.textContent ?? '').replace(/\s+/g, ' ');
    expect(refusal.split(RETURN_ORDER_REFUSAL_LEAD).length - 1).toBe(1);
    expect(refusal).toContain('отдельный способ оплаты не закрыт безопасно');
    // The server gave no next step, so the window adds one.
    expect(refusal).toContain('Что делать');
    expect(component.confirming()).toBeNull();
    expect(text()).not.toContain('Подтвердить возврат');
  });

  it('does not repeat «Что делать» when the refusal already has it', () => {
    const { element } = open({
      refusal: `${RETURN_ORDER_REFUSAL_LEAD} Компания этого заказа не входит в связь общего счёта. Что делать: добавьте компанию в связь.`
    });
    const refusal = element.querySelector('.ret-refusal')?.textContent ?? '';
    expect(refusal.split('Что делать').length - 1).toBe(1);
  });

  it('shows the load state and a retry for a failed load', () => {
    const loading = open({ orders: null, loading: true });
    expect(loading.text()).toContain('Загружаем заказы');
    expect(loading.element.querySelector('.ret-row')).toBeNull();
    TestBed.resetTestingModule();

    const failed = open({ orders: null, error: 'Сервер не отвечает' });
    expect(failed.text()).toContain('Сервер не отвечает');
    failed.button('Повторить')?.click();
    expect(failed.events.retry).toBe(1);
  });

  it('cannot be closed or confirmed again while the return is being saved', () => {
    const { fixture, component, returned, events, element, button } = open();
    component.ask(first);
    fixture.componentRef.setInput('saving', true);
    fixture.detectChanges();
    expect(button('Возвращаем…')?.disabled).toBe(true);
    component.confirm(first);
    component.cancel();
    component.close();
    (element.querySelector('.ret-backdrop') as HTMLElement).click();
    expect(returned).toEqual([]);
    expect(events.closed).toBe(0);
    expect(component.confirming()).toBe(27391);

    fixture.componentRef.setInput('saving', false);
    fixture.detectChanges();
    (element.querySelector('.ret-close') as HTMLButtonElement).click();
    expect(events.closed).toBe(1);
  });
});
