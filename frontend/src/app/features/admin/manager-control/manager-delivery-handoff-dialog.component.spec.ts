import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerDeliveryHandoffDialogComponent } from './manager-delivery-handoff-dialog.component';

describe('manual delivery handoff dialog', () => {
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

  async function fill(note = 'В чате проверена история. Свяжусь с клиентом лично.', checked = true) {
    const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('textarea');
    textarea.value = note;
    textarea.dispatchEvent(new Event('input'));
    const checkbox: HTMLInputElement = fixture.nativeElement.querySelector('input[type=checkbox]');
    checkbox.checked = checked;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function submit() {
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();
  }

  it('loads the operation and renders its message as text without browser-native dialogs', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockImplementation(() => { throw new Error('unsupported'); });
    const prompt = vi.spyOn(window, 'prompt').mockImplementation(() => { throw new Error('unsupported'); });
    expect(fixture.nativeElement.textContent).toContain('Загружаем сведения');
    expect(fixture.nativeElement.querySelector('button[type=submit]').disabled).toBe(true);
    await ready();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain(preview.operationId);
    expect(element.textContent).toContain(preview.preparedAt);
    expect(element.textContent).toContain(preview.message);
    expect(element.querySelector('img')).toBeNull();
    expect(confirm).not.toHaveBeenCalled();
    expect(prompt).not.toHaveBeenCalled();
    http.expectNone(req => req.method === 'POST');
  });

  it('cancels without POST and cancels the preview read when the dialog is removed', () => {
    const read = http.expectOne(`${base}/uncertain-delivery`);
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    const cancel: HTMLButtonElement = fixture.nativeElement.querySelector('footer button[type=button]');
    cancel.click();
    expect(closed).toHaveBeenCalledOnce();
    fixture.destroy();
    expect(read.cancelled).toBe(true);
    http.expectNone(req => req.method === 'POST');
  });

  it.each([
    ['Коротко', true], [' '.repeat(12), true], ['x'.repeat(401), true],
    ['Проверил историю клиентского чата.', false]
  ])('rejects an invalid note or missing explicit chat check: %s / %s', async (note, checked) => {
    await ready();
    await fill(note as string, checked as boolean);
    expect(fixture.nativeElement.querySelector('button[type=submit]').disabled).toBe(true);
    submit();
    http.expectNone(req => req.method === 'POST');
  });

  it('sends the pinned operation and checkedChat exactly once, including a double submit', async () => {
    await ready();
    const note = '  Проверил историю чата; отвечу клиенту вручную.  ';
    await fill(note);
    const saved = vi.fn(), closed = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    fixture.componentInstance.closed.subscribe(closed);
    submit();
    submit();
    const write = http.expectOne(`${base}/handoff-uncertain-delivery`);
    expect(write.request.method).toBe('POST');
    expect(write.request.body).toEqual({ operationId: preview.operationId, note: note.trim(), checkedChat: true });
    expect(fixture.nativeElement.querySelector('button[type=submit]').disabled).toBe(true);
    fixture.componentInstance.cancel();
    expect(closed).not.toHaveBeenCalled();
    const updated = { controlEntityId: 41, type: 'AUTOMATION_FAILURE', itemStatus: 'OPEN' };
    write.flush(updated);
    expect(saved).toHaveBeenCalledExactlyOnceWith(updated);
    submit();
    http.expectNone(req => req.method === 'POST');
  });

  it('retains the note and shows a save error without automatically repeating the POST', async () => {
    await ready();
    const note = 'Проверил историю чата. Требуется ручная связь.';
    await fill(note);
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    submit();
    http.expectOne(`${base}/handoff-uncertain-delivery`).flush(
      { message: 'Состояние операции изменилось' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Состояние операции изменилось');
    expect(fixture.componentInstance.note()).toBe(note);
    expect(fixture.componentInstance.saving()).toBe(false);
    expect(saved).not.toHaveBeenCalled();
    http.expectNone(req => req.method === 'POST');
  });

  it('shows preview errors and retries only the GET', async () => {
    http.expectOne(`${base}/uncertain-delivery`).flush({ message: 'Отправка недоступна' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Отправка недоступна');
    expect(fixture.nativeElement.querySelector('button[type=submit]').disabled).toBe(true);
    fixture.componentInstance.loadPreview();
    await ready();
    expect(fixture.componentInstance.checkedChat()).toBe(false);
    http.expectNone(req => req.method === 'POST');
  });

  it('keeps keyboard focus in the dialog and supports Escape cancellation', async () => {
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
