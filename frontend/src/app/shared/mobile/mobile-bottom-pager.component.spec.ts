import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ManagerApi } from '../../core/manager.api';
import { PersonalReminder, PersonalReminderInput, PersonalRemindersService } from '../personal-reminders.service';
import { PersonalRemindersComponent } from '../personal-reminders.component';
import { MobileBottomPagerComponent } from './mobile-bottom-pager.component';

function reminder(id = 7): PersonalReminder {
  return {
    id,
    title: 'Позвонить клиенту',
    text: 'Уточнить время публикации',
    reminderMode: 'none',
    remindAt: null,
    timerMinutes: null,
    createdAt: '2026-07-10T08:00:00Z',
    updatedAt: '2026-07-10T08:00:00Z',
    completedAt: null
  };
}

describe('MobileBottomPagerComponent reminders', () => {
  const activeReminders = signal<PersonalReminder[]>([reminder()]);
  const dueReminders = signal<PersonalReminder[]>([]);
  const load = vi.fn();
  const create = vi.fn((input: PersonalReminderInput) => {
    const created = { ...reminder(8), ...input, remindAt: null, timerMinutes: null } as PersonalReminder;
    activeReminders.update((items) => [...items, created]);
    return of(created);
  });
  const update = vi.fn((id: number, input: PersonalReminderInput) => {
    const updated = { ...activeReminders().find((item) => item.id === id)!, ...input } as PersonalReminder;
    activeReminders.update((items) => items.map((item) => item.id === id ? updated : item));
    return of(updated);
  });
  const complete = vi.fn((id: number) => {
    const completed = activeReminders().find((item) => item.id === id)!;
    activeReminders.update((items) => items.filter((item) => item.id !== id));
    return of(completed);
  });

  const remindersService = {
    authenticated: signal(true),
    activeReminders,
    dueReminders,
    load,
    create,
    update,
    complete,
    remove: vi.fn(() => of(undefined)),
    removeLocal: vi.fn(),
    claimDueToast: vi.fn(() => true),
    localInputFromIso: vi.fn(() => ''),
    minutesUntil: vi.fn(() => 30),
    dispatchRecoveryClientNotified: vi.fn()
  };

  beforeEach(async () => {
    activeReminders.set([reminder()]);
    dueReminders.set([]);
    vi.clearAllMocks();

    await TestBed.configureTestingModule({
      imports: [MobileBottomPagerComponent],
      providers: [
        provideRouter([]),
        { provide: PersonalRemindersService, useValue: remindersService },
        { provide: ManagerApi, useValue: {} }
      ]
    }).compileComponents();
  });

  it('shows the active reminder badge and opens the mobile modal from the pager bell', () => {
    const fixture = TestBed.createComponent(MobileBottomPagerComponent);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const bell = host.querySelector<HTMLButtonElement>('.mobile-pager-reminders')!;
    expect(bell).toBeTruthy();
    expect(bell.textContent).toContain('1');
    expect(fixture.nativeElement.querySelector('[role="dialog"]')).toBeNull();

    bell.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="dialog"]')).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('Напоминания');
    expect(fixture.nativeElement.textContent).toContain('Позвонить клиенту');
    expect(load).toHaveBeenCalledWith(true);

    fixture.destroy();
  });

  it('creates, edits and completes reminders inside the modal', () => {
    const fixture = TestBed.createComponent(MobileBottomPagerComponent);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelector<HTMLButtonElement>('.mobile-pager-reminders')!.click();
    fixture.detectChanges();

    const add = host.querySelector<HTMLButtonElement>('[aria-label="Создать напоминание"]')!;
    add.click();
    fixture.detectChanges();

    const editor = fixture.debugElement.query((node) => node.componentInstance instanceof PersonalRemindersComponent)
      .componentInstance as PersonalRemindersComponent;
    expect(editor.formOpen()).toBe(true);

    editor.setDraftField('title', 'Новая заметка');
    editor.saveDraft();
    fixture.detectChanges();
    expect(create).toHaveBeenCalled();
    expect(activeReminders().some((item) => item.title === 'Новая заметка')).toBe(true);

    editor.startEdit(activeReminders()[0]);
    editor.setDraftField('text', 'Новый текст');
    editor.saveDraft();
    fixture.detectChanges();
    expect(update).toHaveBeenCalledWith(7, expect.objectContaining({ text: 'Новый текст' }));

    editor.complete(activeReminders()[0]);
    fixture.detectChanges();
    expect(complete).toHaveBeenCalledWith(7);
    expect(activeReminders().some((item) => item.id === 7)).toBe(false);

    fixture.destroy();
  });
});
