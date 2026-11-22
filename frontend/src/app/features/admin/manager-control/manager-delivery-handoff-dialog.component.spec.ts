import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerDeliveryHandoffDialogComponent } from './manager-delivery-handoff-dialog.component';

describe('«Сверка отправки» decision dialog', () => {
  const base = '/api/admin/manager-control/concrete-items/41';
  const preview = {
    stateId: 9, operationId: '00000000-0000-0000-0000-000000000041',
    preparedAt: '2026-10-22T09:30:00', message: 'Старый текст <img src=x onerror=alert(1)>', chatUrl: null
  };
  let http: HttpTestingController;
  let fixture: ComponentFixture<ManagerDeliveryHandoffDialogComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ManagerDeliveryHandoffDialogComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ManagerDeliveryHandoffDialogComponent);
    fixture.componentRef.setInput('itemId', 41);
    fixture.detectChanges();
  });

  afterEach(() => { http.verify(); vi.restoreAllMocks(); });

  async function ready() {
    http.expectOne(`${base}/uncertain-delivery`).flush(preview);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function checkChat(checked = true, note = '') {
    const checkbox: HTMLInputElement = fixture.nativeElement.querySelector('input[type=checkbox]');
    checkbox.checked = checked;
    checkbox.dispatchEvent(new Event('change'));
    const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('textarea');
    textarea.value = note;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function decisionButtons(): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('footer button'));
  }

  function press(label: string) {
    const button = decisionButtons().find(item => item.textContent?.includes(label));
    if (!button) throw new Error('no button ' + label);
    button.click();
    fixture.detectChanges();
  }

  it('loads the operation and renders its message as text without browser-native dialogs', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockImplementation(() => { throw new Error('unsupported'); });
    expect(fixture.nativeElement.textContent).toContain('Загружаем сведения');
    await ready();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('22.10.2026 09:30');
    expect(element.textContent).toContain(preview.message);
    expect(element.querySelector('img')).toBeNull();
    expect(decisionButtons().map(button => button.textContent?.trim())).toEqual(
      ['Сообщение есть в чате', 'Сообщения нет — отправить снова', 'Не отправлять']);
    expect(confirm).not.toHaveBeenCalled();
    http.expectNone(req => req.method === 'POST');
  });

  it('asks to open the chat first: every decision stays disabled until the check is ticked', async () => {
    await ready();
    expect(decisionButtons().every(button => button.disabled)).toBe(true);
    press('Сообщение есть в чате');
    http.expectNone(req => req.method === 'POST');
    await checkChat(true);
    expect(decisionButtons().every(button => !button.disabled)).toBe(true);
  });

  it.each([
    ['Сообщение есть в чате', 'SEEN_IN_CHAT'],
    ['Сообщения нет — отправить снова', 'NOT_IN_CHAT'],
    ['Не отправлять', 'CLOSE'],
  ])('sends «%s» once with the pinned operation, the decision and the comment', async (label, decision) => {
    await ready();
    await checkChat(true, '  Проверил чат  ');
    const saved = vi.fn(), closed = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    fixture.componentInstance.closed.subscribe(closed);
    const button = decisionButtons().find(item => item.textContent?.includes(label))!;
    button.click();
    fixture.detectChanges();
    expect(button.textContent?.trim()).toBe('Сохраняем…');
    button.click();
    fixture.detectChanges();
    const write = http.expectOne(`${base}/handoff-uncertain-delivery`);
    expect(write.request.body).toEqual({ operationId: preview.operationId, note: 'Проверил чат', checkedChat: true, decision });
    expect(decisionButtons().every(button => button.disabled)).toBe(true);
    fixture.componentInstance.cancel();
    expect(closed).not.toHaveBeenCalled();
    const updated = { controlEntityId: 41, type: 'AUTOMATION_FAILURE', itemStatus: 'RESOLVED' };
    write.flush(updated);
    expect(saved).toHaveBeenCalledExactlyOnceWith(updated);
    http.expectNone(req => req.method === 'POST');
  });

  it('keeps the comment and shows a save error without repeating the POST', async () => {
    await ready();
    await checkChat(true, 'Клиент не отвечает');
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    press('Сообщения нет — отправить снова');
    http.expectOne(`${base}/handoff-uncertain-delivery`).flush(
      { message: 'Сообщение отправлялось меньше часа назад' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('меньше часа назад');
    expect(fixture.componentInstance.note()).toBe('Клиент не отвечает');
    expect(fixture.componentInstance.saving()).toBe(false);
    expect(saved).not.toHaveBeenCalled();
    http.expectNone(req => req.method === 'POST');
  });

  it('shows preview errors and retries only the GET', async () => {
    http.expectOne(`${base}/uncertain-delivery`).flush({ message: 'Отправка недоступна' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Отправка недоступна');
    expect(decisionButtons()).toEqual([]);
    fixture.componentInstance.loadPreview();
    await ready();
    expect(fixture.componentInstance.checkedChat()).toBe(false);
    http.expectNone(req => req.method === 'POST');
  });

  it('cancels without POST and keeps focus inside, Escape closes', async () => {
    await ready();
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    const dialog = fixture.nativeElement.querySelector('[role=dialog]') as HTMLElement;
    const close = fixture.nativeElement.querySelector('header button') as HTMLElement;
    dialog.focus();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(close);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    expect(closed).toHaveBeenCalledOnce();
    http.expectNone(req => req.method === 'POST');
  });
});
