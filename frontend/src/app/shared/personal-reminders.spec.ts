import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { AuthService } from '../core/auth.service';
import { ManagerApi } from '../core/manager.api';
import { ToastService } from './toast.service';
import { PersonalReminder, PersonalRemindersService } from './personal-reminders.service';
import { PersonalRemindersComponent } from './personal-reminders.component';

const note = (id: number): PersonalReminder => ({
  id, title: `Заметка ${id}`, text: 'Текст', reminderMode: 'datetime',
  remindAt: '2020-01-01T00:00:00Z', timerMinutes: null,
  createdAt: '2020-01-01T00:00:00Z', updatedAt: '2020-01-01T00:00:00Z', completedAt: null
});

describe('bulk reminder deletion', () => {
  let service: PersonalRemindersService;
  let http: HttpTestingController;
  let role: string;
  const isReminderRequest = (request: { url: string }) => request.url.endsWith('/api/personal-reminders');

  beforeEach(() => {
    role = 'ADMIN';
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { authenticated: signal(true), tokenParsed: signal({}), hasAnyRealmRole: (roles: string[]) => roles.includes(role) } },
      { provide: ManagerApi, useValue: {} },
      { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }
    ] });
    service = TestBed.inject(PersonalRemindersService);
    http = TestBed.inject(HttpTestingController);
    service.reminders.set([note(1), note(2)]);
  });

  afterEach(() => { http.verify(); vi.restoreAllMocks(); });

  it('cancels stale list reads and removes only IDs confirmed by the server', () => {
    service.load(true);
    const stale = http.expectOne(isReminderRequest);
    service.removeAll().subscribe();
    expect(stale.cancelled).toBe(true);
    const deletion = http.expectOne(isReminderRequest);
    expect(deletion.request.method).toBe('DELETE');
    service.reminders.update(notes => [...notes, note(3)]);
    service.removeAll().subscribe();
    http.expectNone(isReminderRequest);
    deletion.flush([1]);
    expect(service.reminders().map(note => note.id)).toEqual([2, 3]);
    expect(service.clearingAll()).toBe(false);
    expect(service.loading()).toBe(false);
  });

  it('retains reminders and restores the button after server failure', () => {
    service.removeAll().subscribe({ error: () => undefined });
    http.expectOne(isReminderRequest).flush({}, { status: 503, statusText: 'Unavailable' });
    expect(service.reminders()).toHaveLength(2);
    expect(service.clearingAll()).toBe(false);
  });

  it.each(['ADMIN', 'OWNER', 'MANAGER', 'WORKER'])('shows the icon only to privileged roles: %s', (currentRole) => {
    role = currentRole;
    const fixture = TestBed.createComponent(PersonalRemindersComponent);
    fixture.detectChanges();
    http.expectOne(isReminderRequest).flush([note(1), note(2)]);
    fixture.detectChanges();
    const icon = fixture.nativeElement.querySelector('[aria-label="Удалить все напоминания"]');
    expect(Boolean(icon)).toBe(['ADMIN', 'OWNER'].includes(role));
    expect(fixture.nativeElement.querySelector('.reminder-alert__heading strong').textContent).toBe('Напоминания');
  });

  it('canceling confirmation makes no delete request; confirmed deletion closes an affected editor', () => {
    const component = TestBed.runInInjectionContext(() => new PersonalRemindersComponent());
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    component.removeAll();
    http.expectNone(isReminderRequest);
    confirm.mockReturnValue(true);
    component.startEdit(note(1));
    component.removeAll();
    expect(component.canRemoveAll()).toBe(false);
    http.expectOne(isReminderRequest).flush([1, 2]);
    expect(component.formOpen()).toBe(false);
    expect(component.reminders()).toHaveLength(0);
  });
});
