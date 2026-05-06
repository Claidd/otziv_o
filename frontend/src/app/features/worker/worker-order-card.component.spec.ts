import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { OrderCardItem } from '../../core/manager.api';
import {
  DEFAULT_WORKER_PERMISSIONS,
  WORKER_ORDER_STATUS_ACTIONS
} from './worker-board.config';
import { WorkerOrderCardComponent } from './worker-order-card.component';

function order(overrides: Partial<OrderCardItem> = {}): OrderCardItem {
  return {
    id: 42,
    companyId: 7,
    orderDetailsId: 'review-uuid',
    companyTitle: 'Company',
    filialTitle: 'Filial',
    status: 'На проверке',
    sum: 1000,
    totalSumWithBadReviews: 1250,
    badReviewTasksSum: 250,
    badReviewTasksTotal: 2,
    badReviewTasksDone: 1,
    companyTelephone: '+79086431055',
    companyComments: 'company note',
    orderComments: 'worker note',
    amount: 10,
    counter: 4,
    workerUserFio: 'Worker',
    categoryTitle: 'Category',
    subCategoryTitle: 'Subcategory',
    dayToChangeStatusAgo: 3,
    ...overrides
  };
}

describe('WorkerOrderCardComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WorkerOrderCardComponent],
      providers: [provideRouter([])]
    }).compileComponents();
  });

  it('renders order data according to worker permissions', () => {
    const fixture = TestBed.createComponent(WorkerOrderCardComponent);
    fixture.componentInstance.order = order();
    fixture.componentInstance.permissions = {
      ...DEFAULT_WORKER_PERMISSIONS,
      canSeeMoney: true,
      canSeePhoneAndPayment: true,
      canManageOrderStatuses: true,
      canEditNotes: true
    };
    fixture.componentInstance.actions = WORKER_ORDER_STATUS_ACTIONS.slice(0, 1);

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('header a')?.textContent?.trim()).toBe('Company - Filial');
    expect(element.textContent).toContain('1250 руб.');
    expect(element.textContent).toContain('Плохие: 1/2');
    expect(element.textContent).toContain('7-908-643-10-55');
    expect(element.textContent).toContain('Worker');
    expect(element.querySelector('.order-note-text')?.textContent).toContain('worker note');
    expect(element.querySelector('article')?.classList.contains('order-card--compact')).toBe(false);
  });

  it('uses a compact layout when order controls are hidden for worker-like roles', () => {
    const fixture = TestBed.createComponent(WorkerOrderCardComponent);
    fixture.componentInstance.order = order({ status: 'Новый', waitingForClient: true });
    fixture.componentInstance.permissions = {
      ...DEFAULT_WORKER_PERMISSIONS,
      canSeeMoney: true,
      canEditNotes: true
    };

    fixture.detectChanges();

    const article = fixture.nativeElement.querySelector('article') as HTMLElement;
    const statusChip = fixture.nativeElement.querySelector('.status-chip') as HTMLElement;
    expect(article.classList.contains('order-card--compact')).toBe(true);
    expect(article.classList.contains('waiting-client')).toBe(true);
    expect(statusChip.classList.contains('status-chip--waiting')).toBe(true);
    expect(statusChip.textContent?.trim()).toBe('Ждем');
  });

  it('keeps full category names available from compact category chips', () => {
    const fixture = TestBed.createComponent(WorkerOrderCardComponent);
    fixture.componentInstance.order = order({
      categoryTitle: 'Юридические услуги',
      subCategoryTitle: 'Управленческий консалтинг'
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const chips = element.querySelectorAll<HTMLButtonElement>('.category-chip');
    expect(chips[0].title).toBe('Юридические услуги');
    expect(chips[0].querySelector('.category-chip__label')?.textContent?.trim()).toBe('Юридические услуги');
    expect(chips[1].title).toBe('Управленческий консалтинг');

    chips[1].click();
    fixture.detectChanges();

    expect(element.querySelector('.category-popover')?.textContent?.trim()).toBe('Управленческий консалтинг');
  });

  it('emits card actions without owning board mutations', () => {
    const fixture = TestBed.createComponent(WorkerOrderCardComponent);
    const component = fixture.componentInstance;
    let copiedPhone = '';
    let copyKind: 'check' | 'payment' | null = null;
    let status = '';
    let editOpened = false;
    component.order = order();
    component.permissions = {
      ...DEFAULT_WORKER_PERMISSIONS,
      canSeePhoneAndPayment: true,
      canManageOrderStatuses: true
    };
    component.actions = WORKER_ORDER_STATUS_ACTIONS.slice(0, 1);
    component.phoneCopied.subscribe((phone) => {
      copiedPhone = phone ?? '';
    });
    component.copyTextRequested.subscribe((kind) => {
      copyKind = kind;
    });
    component.statusUpdated.subscribe((action) => {
      status = action.status;
    });
    component.editOpened.subscribe(() => {
      editOpened = true;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.phone-row button')?.click();
    element.querySelector<HTMLButtonElement>('.order-links button')?.click();
    element.querySelector<HTMLButtonElement>('.order-status-actions button')?.click();
    element.querySelector<HTMLAnchorElement>('footer a')?.click();

    expect(copiedPhone).toBe('79086431055');
    expect(copyKind).toBe('check');
    expect(status).toBe('На проверке');
    expect(editOpened).toBe(true);
  });

  it('shows client waiting state and emits the manager toggle', () => {
    const fixture = TestBed.createComponent(WorkerOrderCardComponent);
    const component = fixture.componentInstance;
    let toggled = false;
    component.order = order({ status: 'Новый', waitingForClient: true });
    component.permissions = {
      ...DEFAULT_WORKER_PERMISSIONS,
      canManageClientWaiting: true
    };
    component.clientWaitingToggled.subscribe(() => {
      toggled = true;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('article')?.classList.contains('waiting-client')).toBe(true);
    expect(element.querySelector('.client-waiting-badge')).toBeNull();
    expect(element.querySelector('.client-waiting-action')?.textContent?.trim()).toBe('ждет клиента');
    element.querySelector<HTMLButtonElement>('.client-waiting-action')?.click();

    expect(toggled).toBe(true);
    expect(component.canOpenInlineEdit()).toBe(false);
  });

  it('emits note editing events', () => {
    const fixture = TestBed.createComponent(WorkerOrderCardComponent);
    const component = fixture.componentInstance;
    let draft = '';
    let saveRequested = false;
    component.order = order();
    component.permissions = {
      ...DEFAULT_WORKER_PERMISSIONS,
      canEditNotes: true
    };
    component.noteEditing = true;
    component.noteDraft = 'old note';
    component.noteChanged = true;
    component.noteDraftChanged.subscribe((value) => {
      draft = value;
    });
    component.noteSaveRequested.subscribe(() => {
      saveRequested = true;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const textarea = element.querySelector<HTMLTextAreaElement>('.order-note-editor textarea');
    textarea!.value = 'new note';
    textarea!.dispatchEvent(new Event('input', { bubbles: true }));
    element.querySelector<HTMLButtonElement>('.note-edit-actions .save')?.click();

    expect(draft).toBe('new note');
    expect(saveRequested).toBe(true);
  });
});
