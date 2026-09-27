import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { ApiService, PersonalReminder } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ManagerOrdersApi } from '../core/manager-orders.api';
import { ManagerReviewTasksApi } from '../core/manager-review-tasks.api';
import { MobileConfirmService } from './mobile-confirm.service';
import { MobileRemindersComponent } from './mobile-reminders.component';

const note = (id: number) => ({
  id, title: `Заметка ${id}`, text: '', reminderMode: 'none', remindAt: null,
  timerMinutes: null, createdAt: '2020-01-01T00:00:00Z', updatedAt: '2020-01-01T00:00:00Z', completedAt: null
} as PersonalReminder);

describe('mobile bulk reminder deletion', () => {
  let component: MobileRemindersComponent;
  let role: string;
  let api: { getPersonalReminders: ReturnType<typeof vi.fn>; deleteAllPersonalReminders: ReturnType<typeof vi.fn> };
  let confirm: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    role = 'OWNER';
    api = { getPersonalReminders: vi.fn(() => of([])), deleteAllPersonalReminders: vi.fn(() => of([1])) };
    confirm = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({ providers: [
      { provide: ApiService, useValue: api },
      { provide: AuthService, useValue: { isAuthenticated: () => true, hasAnyRealmRole: (roles: string[]) => roles.includes(role) } },
      { provide: MobileConfirmService, useValue: { confirm } },
      { provide: ManagerOrdersApi, useValue: {} },
      { provide: ManagerReviewTasksApi, useValue: {} }
    ] });
    component = TestBed.runInInjectionContext(() => new MobileRemindersComponent(
      TestBed.inject(ApiService), TestBed.inject(AuthService), TestBed.inject(MobileConfirmService)
    ));
    component.reminders.set([note(1), note(2)]);
  });

  it.each(['ADMIN', 'OWNER', 'MANAGER', 'WORKER'])('limits deletion to admin and owner: %s', async (currentRole) => {
    role = currentRole;
    await component.deleteAll();
    expect(api.deleteAllPersonalReminders).toHaveBeenCalledTimes(['ADMIN', 'OWNER'].includes(role) ? 1 : 0);
  });

  it('canceling confirmation leaves the list untouched', async () => {
    confirm.mockResolvedValue(false);
    await component.deleteAll();
    expect(api.deleteAllPersonalReminders).not.toHaveBeenCalled();
    expect(component.reminders()).toHaveLength(2);
    expect(component.clearingAll()).toBe(false);
  });

  it('does not let a late read restore deleted reminders and prevents duplicate deletion', async () => {
    const pendingRead = new Subject<PersonalReminder[]>();
    const pendingDelete = new Subject<number[]>();
    api.getPersonalReminders.mockReturnValue(pendingRead);
    api.deleteAllPersonalReminders.mockReturnValue(pendingDelete);
    component.open();
    const clearing = component.deleteAll();
    await Promise.resolve();
    await component.deleteAll();
    expect(api.deleteAllPersonalReminders).toHaveBeenCalledTimes(1);
    pendingDelete.next([1]);
    await clearing;
    pendingRead.next([note(1), note(2)]);
    await Promise.resolve();
    expect(component.reminders().map(note => note.id)).toEqual([2]);
    expect(component.loading()).toBe(false);
    expect(component.clearingAll()).toBe(false);
  });

  it('preserves the list when deletion fails', async () => {
    api.deleteAllPersonalReminders.mockReturnValue(throwError(() => new Error('Нет соединения')));
    await component.deleteAll();
    expect(component.reminders()).toHaveLength(2);
    expect(component.error()).toBe('Нет соединения');
    expect(component.clearingAll()).toBe(false);
  });
});
