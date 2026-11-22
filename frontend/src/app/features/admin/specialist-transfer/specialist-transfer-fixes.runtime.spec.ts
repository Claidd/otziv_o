import { TestBed } from '@angular/core/testing';
import { NEVER, Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AdminSpecialistTransfersApi,
  SpecialistTransferAudit,
  SpecialistTransferPreview,
  SpecialistTransferRequest,
  SpecialistTransferResult,
  SpecialistTransferSource,
  SpecialistTransferWorker
} from '../../../core/admin-specialist-transfers.api';
import { AdminUsersApi, AssignmentOption, AssignmentOptions } from '../../../core/admin-users.api';
import { ToastService } from '../../../shared/toast.service';
import { SpecialistTransferComponent, countLabel } from './specialist-transfer.component';

const WORKERS: AssignmentOption[] = [
  { id: 13, userId: 103, username: 'sidorov', fio: 'Сидоров Сидор', role: 'WORKER' },
  { id: 12, userId: 102, username: 'petrov', fio: 'Петров Пётр', role: 'WORKER' },
  { id: 14, userId: 104, username: 'ivanov.b', fio: 'Иванов Иван', role: 'WORKER' },
  { id: 11, userId: 101, username: 'ivanov.a', fio: 'Иванов Иван', role: 'WORKER' }
];

const NOTHING_LEFT: Partial<SpecialistTransferPreview> = {
  activeOrderCount: 0,
  unpublishedReviewCount: 0,
  badReviewTaskCount: 0,
  reviewRecoveryTaskCount: 0,
  targetAlreadyAssignedCompanyCount: 3
};

function transferWorker(id: number): SpecialistTransferWorker {
  return { id, userId: id + 90, username: `worker${id}`, fio: `Специалист ${id}`, label: `Специалист ${id}`, active: true };
}

function checkOf(fromId: number, toId: number, overrides: Partial<SpecialistTransferPreview> = {}): SpecialistTransferPreview {
  return {
    fromWorker: transferWorker(fromId),
    toWorker: transferWorker(toId),
    companyCount: 3,
    activeOrderCount: 2,
    unpublishedReviewCount: 5,
    badReviewTaskCount: 1,
    reviewRecoveryTaskCount: 0,
    targetAlreadyAssignedCompanyCount: 0,
    companiesWithoutActiveOrdersCount: 1,
    targetWorkerMissingManagerLinksCount: 0,
    sampleCompanies: [],
    warnings: [],
    ...overrides
  };
}

function resultOf(fromId: number, toId: number): SpecialistTransferResult {
  return {
    auditId: 77,
    createdAt: '2026-11-21T10:00:00',
    fromWorker: transferWorker(fromId),
    toWorker: transferWorker(toId),
    companyCount: 3,
    companyLinksAdded: 3,
    companyLinksRemoved: 0,
    activeOrderCount: 2,
    unpublishedReviewCount: 5,
    badReviewTaskCount: 1,
    reviewRecoveryTaskCount: 0
  };
}

function auditOf(overrides: Partial<SpecialistTransferAudit> = {}): SpecialistTransferAudit {
  return {
    id: 5,
    createdAt: '2026-11-21T10:00:00',
    actorUserId: 1,
    actorName: 'Администратор',
    fromWorkerId: 11,
    fromWorkerName: 'Иванов Иван',
    toWorkerId: 12,
    toWorkerName: 'Петров Пётр',
    companyCount: 1,
    orderCount: 2,
    reviewCount: 5,
    badReviewTaskCount: 1,
    reviewRecoveryTaskCount: 21,
    comment: 'Уволился, работу забирает Пётр',
    ...overrides
  };
}

function text(node: Element | null | undefined): string {
  return (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

function setup(options: {
  recent?: () => Observable<SpecialistTransferAudit[]>;
  workers?: Observable<AssignmentOptions>;
  sources?: () => Observable<SpecialistTransferSource[]>;
} = {}) {
  const transfersApi = {
    preview: vi.fn<(request: SpecialistTransferRequest) => Observable<SpecialistTransferPreview>>(),
    apply: vi.fn<(request: SpecialistTransferRequest) => Observable<SpecialistTransferResult>>(),
    recent: vi.fn(options.recent ?? (() => of<SpecialistTransferAudit[]>([]))),
    sources: vi.fn(options.sources ?? (() => of<SpecialistTransferSource[]>([])))
  };
  const usersApi = {
    getAssignmentOptions: vi.fn(() => options.workers ?? of<AssignmentOptions>({ managers: [], workers: WORKERS, operators: [], marketologs: [] }))
  };
  const toast = { success: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    imports: [SpecialistTransferComponent],
    providers: [
      { provide: AdminSpecialistTransfersApi, useValue: transfersApi },
      { provide: AdminUsersApi, useValue: usersApi },
      { provide: ToastService, useValue: toast }
    ]
  });
  const fixture = TestBed.createComponent(SpecialistTransferComponent);
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, element: fixture.nativeElement as HTMLElement, transfersApi, toast };
}

describe('specialist transfer fixes', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('offers a deactivated specialist with work left as the source, never as the receiver', () => {
    const { element } = setup({
      sources: () => of<SpecialistTransferSource[]>([
        { workerId: 15, label: 'Уволенный Ушёл', active: false, companyCount: 4, openOrderCount: 2 },
        { workerId: 12, label: 'Петров Пётр', active: true, companyCount: 1, openOrderCount: 0 }
      ])
    });
    const [from, to] = Array.from(element.querySelectorAll('select'));
    const fromOptions = Array.from(from.querySelectorAll('option')).map((option) => text(option));
    expect(fromOptions).toEqual([
      'Выберите специалиста',
      'Уволенный Ушёл — отключён · компаний 4, открытых заказов 2',
      'Петров Пётр · компаний 1, открытых заказов 0'
    ]);
    expect(Array.from(to.querySelectorAll('option')).map((option) => text(option)).join('|')).not.toContain('Уволенный');
  });

  it('falls back to the specialist list when the sources did not load', () => {
    const { element } = setup({ sources: () => throwError(() => ({ status: 500 })) });
    const fromOptions = Array.from(element.querySelectorAll('select')[0].querySelectorAll('option')).map((option) => text(option));
    expect(fromOptions.length).toBe(WORKERS.length + 1);
  });

  it('declines the numbers: 1 запись, 2 записи, 5 записей, 11 записей, 21 запись', () => {
    const forms = ['запись', 'записи', 'записей'] as const;
    expect([0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111, 112].map((value) => countLabel(value, forms))).toEqual([
      '0 записей', '1 запись', '2 записи', '4 записи', '5 записей', '11 записей', '12 записей', '14 записей',
      '21 запись', '22 записи', '25 записей', '101 запись', '111 записей', '112 записей'
    ]);
  });

  it('locks the lists, the swap and the comment while a check runs and drops an answer for a pair that changed', async () => {
    const { fixture, component, element, transfersApi } = setup();
    const answer = new Subject<SpecialistTransferPreview>();
    transfersApi.preview.mockReturnValue(answer);
    component.setFromWorkerId(11);
    component.setToWorkerId(12);
    component.previewTransfer();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(Array.from(element.querySelectorAll('select')).map((select) => select.disabled)).toEqual([true, true]);
    expect(element.querySelector('textarea')!.disabled).toBe(true);
    expect(element.querySelector<HTMLButtonElement>('.swap-button')!.disabled).toBe(true);
    component.swapWorkers();
    expect([component.fromWorkerId(), component.toWorkerId()]).toEqual([11, 12]);

    // A change that still gets through makes the late answer stale: it never reaches the screen.
    component.setFromWorkerId(13);
    answer.next(checkOf(11, 12));
    answer.complete();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(component.preview()).toBeNull();
    expect(component.previewLoading()).toBe(false);
    expect(element.querySelector('.preview-panel')).toBeNull();
    expect(Array.from(element.querySelectorAll('select')).map((select) => select.disabled)).toEqual([false, false]);
    expect(element.querySelector('textarea')!.disabled).toBe(false);
  });

  it('never shows a check about another pair than the one sent, nor a failure of a check for a changed pair', () => {
    const { component, transfersApi, toast } = setup();
    transfersApi.preview.mockReturnValue(of(checkOf(13, 12)));
    component.setFromWorkerId(11);
    component.setToWorkerId(12);
    component.previewTransfer();
    expect(component.preview()).toBeNull();
    expect(component.error()).toBe('Проверка вернула другую пару специалистов. Нажмите «Проверить» ещё раз.');

    const failing = new Subject<SpecialistTransferPreview>();
    transfersApi.preview.mockReturnValue(failing);
    component.setToWorkerId(14);
    component.previewTransfer();
    component.setToWorkerId(13);
    failing.error({ status: 500 });
    expect(component.error()).toBeNull();
    expect(component.previewLoading()).toBe(false);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('transfers only the checked pair, takes the word in any case and names the pair in the result', () => {
    const { fixture, component, element, transfersApi, toast } = setup();
    transfersApi.preview.mockReturnValue(of(checkOf(11, 12)));
    transfersApi.apply.mockReturnValue(of(resultOf(11, 12)));
    component.setFromWorkerId(11);
    component.setToWorkerId(12);
    component.previewTransfer();
    component.setConfirmation(' перенести ');
    expect(component.canApply()).toBe(true);

    // A check about another pair than the lists show is never applied.
    component.preview.set(checkOf(13, 12));
    expect(component.canApply()).toBe(false);
    expect(component.applyStopReason()).toBe('Специалисты изменились: нажмите «Проверить» ещё раз.');
    component.applyTransfer();
    expect(transfersApi.apply).not.toHaveBeenCalled();

    component.preview.set(checkOf(11, 12));
    component.applyTransfer();
    expect(transfersApi.apply).toHaveBeenCalledTimes(1);
    expect(transfersApi.apply).toHaveBeenCalledWith({
      fromWorkerId: 11,
      toWorkerId: 12,
      companyIds: null,
      comment: undefined,
      confirmationText: 'ПЕРЕНЕСТИ'
    });

    fixture.detectChanges();
    const resultPanel = element.querySelector('.result-panel');
    expect(text(resultPanel?.querySelector('h3'))).toBe('Специалист 11 → Специалист 12');
    expect(text(resultPanel)).toContain('Прежний специалист остался привязан к компаниям.');
    expect(Array.from(resultPanel?.querySelectorAll('.metric-grid article') ?? [])
      .map((tile) => `${text(tile.querySelector('span'))}: ${text(tile.querySelector('strong'))}`)).toEqual([
      'Компании: 3', 'Новый добавлен в компании: 3', 'Заказы: 2', 'Отзывы: 5', 'Задачи по плохим отзывам: 1', 'Восстановления: 0'
    ]);
    expect(toast.success).toHaveBeenCalledWith('Работа передана', '2 заказа, 5 отзывов, 1 задача, новый специалист добавлен в 3 компании');
    expect(transfersApi.recent).toHaveBeenCalledTimes(2);
  });

  it('blocks «Перенести» with a reason when a transfer would move nothing', async () => {
    const { fixture, component, element, transfersApi } = setup();
    transfersApi.preview.mockReturnValue(of(checkOf(11, 12, NOTHING_LEFT)));
    component.setFromWorkerId(11);
    component.setToWorkerId(12);
    component.previewTransfer();
    component.setConfirmation('ПЕРЕНЕСТИ');
    fixture.detectChanges();
    await fixture.whenStable();

    const confirmRow = element.querySelector('.confirm-row');
    expect(text(confirmRow)).toContain('Нечего переносить: заказов, отзывов и задач нет, а новый специалист уже есть во всех компаниях.');
    expect(text(confirmRow)).not.toContain('введите слово');
    expect(confirmRow?.querySelector('input')?.disabled).toBe(true);
    expect(confirmRow?.querySelector<HTMLButtonElement>('button')?.disabled).toBe(true);
    component.applyTransfer();
    expect(transfersApi.apply).not.toHaveBeenCalled();

    // A company still without the new specialist is real work: its link gets added.
    component.preview.set(checkOf(11, 12, { ...NOTHING_LEFT, targetAlreadyAssignedCompanyCount: 2 }));
    expect(component.nothingToMove()).toBe(false);
    expect(component.canApply()).toBe(true);

    // A specialist without companies has nothing to transfer either.
    component.preview.set(checkOf(11, 12, { ...NOTHING_LEFT, companyCount: 0, targetAlreadyAssignedCompanyCount: 0 }));
    expect(component.applyStopReason()).toBe('Нечего переносить: нет компаний для переноса.');
    expect(component.canApply()).toBe(false);
  });

  it('says why «Проверить» waits', () => {
    const loading = setup({ workers: NEVER });
    expect(text(loading.element.querySelector('.action-hint'))).toBe('Загружаю список специалистов.');
    TestBed.resetTestingModule();

    const { fixture, component, element } = setup();
    const hint = () => text(element.querySelector('.action-hint'));
    const button = () => element.querySelector<HTMLButtonElement>('.transfer-actions button')!;
    expect(hint()).toBe('Выберите, от кого и кому передать работу.');
    expect(button().getAttribute('aria-describedby')).toBe('transfer-preview-hint');

    component.setFromWorkerId(11);
    component.setToWorkerId(11);
    fixture.detectChanges();
    expect(hint()).toBe('Выберите двух разных специалистов.');
    expect(button().disabled).toBe(true);

    component.setToWorkerId(12);
    fixture.detectChanges();
    expect(element.querySelector('.action-hint')).toBeNull();
    expect(button().disabled).toBe(false);
    expect(button().getAttribute('aria-describedby')).toBeNull();
  });

  it('shows a failed transfer next to «Перенести», not at the top of the page', () => {
    const { fixture, component, element, transfersApi, toast } = setup();
    const message = 'Перенос остановлен: у части заказов уже зафиксирован получатель платежа';
    transfersApi.preview.mockReturnValue(of(checkOf(11, 12)));
    transfersApi.apply.mockReturnValue(throwError(() => ({ status: 409, error: { message } })));
    component.setFromWorkerId(11);
    component.setToWorkerId(12);
    component.previewTransfer();
    component.setConfirmation('ПЕРЕНЕСТИ');
    component.applyTransfer();
    fixture.detectChanges();

    expect(text(element.querySelector('.confirm-row .confirm-error'))).toContain(message);
    expect(element.querySelector('.transfer-panel:not(.preview-panel) .transfer-error')).toBeNull();
    expect(component.error()).toBeNull();
    expect(component.preview()).not.toBeNull();
    expect(toast.error).toHaveBeenCalledWith('Перенос не выполнен', message);

    // Another pair drops the old failure together with its check.
    component.setToWorkerId(13);
    expect(component.applyError()).toBeNull();
  });

  it('shows a journal that did not load as an error with «Повторить», never as an empty journal', () => {
    const answers: Observable<SpecialistTransferAudit[]>[] = [throwError(() => ({ status: 500 })), of([auditOf()])];
    const { fixture, element, transfersApi } = setup({ recent: () => answers.shift() ?? NEVER });
    fixture.detectChanges();

    const recent = element.querySelector('.recent-panel')!;
    expect(text(recent.querySelector('.panel-chip'))).toBe('Ошибка');
    expect(text(recent)).not.toContain('Операций пока нет');
    expect(text(recent)).not.toContain('записей');
    expect(text(recent.querySelector('.recent-error'))).toContain('Не удалось загрузить последние операции');

    recent.querySelector<HTMLButtonElement>('.recent-error button')!.click();
    fixture.detectChanges();

    expect(transfersApi.recent).toHaveBeenCalledTimes(2);
    expect(recent.querySelector('.recent-error')).toBeNull();
    expect(text(recent.querySelector('.panel-chip'))).toBe('1 запись');
    expect(text(recent.querySelector('.recent-main strong'))).toBe('Иванов Иван → Петров Пётр');
    expect(Array.from(recent.querySelectorAll('.recent-stats span')).map((span) => text(span))).toEqual([
      '1 компания', '2 заказа', '5 отзывов', '1 задача по плохим отзывам', '21 восстановление'
    ]);
    expect(text(recent.querySelector('.recent-comment'))).toBe('Уволился, работу забирает Пётр');
  });

  it('keeps the newest journal when an older answer comes back after it, and hides an empty comment', () => {
    const first = new Subject<SpecialistTransferAudit[]>();
    const second = new Subject<SpecialistTransferAudit[]>();
    const answers = [first, second];
    const { fixture, component, element } = setup({ recent: () => answers.shift() ?? NEVER });
    component.loadRecent();
    second.next([auditOf({ id: 9, comment: '  ' })]);
    first.next([auditOf({ id: 5 })]);
    fixture.detectChanges();

    expect(component.recentTransfers().map((transfer) => transfer.id)).toEqual([9]);
    expect(component.recentLoading()).toBe(false);
    expect(element.querySelector('.recent-comment')).toBeNull();
  });

  it('puts numbers into the warnings, marks a cut company list and names specialists with their logins', async () => {
    const { fixture, component, element, transfersApi } = setup();
    const companies = Array.from({ length: 20 }, (_, index) => ({ id: index + 1, title: `Компания ${index + 1}` }));
    transfersApi.preview.mockReturnValue(of(checkOf(11, 12, {
      companyCount: 57,
      targetAlreadyAssignedCompanyCount: 2,
      companiesWithoutActiveOrdersCount: 21,
      targetWorkerMissingManagerLinksCount: 1,
      sampleCompanies: companies,
      warnings: [
        { code: 'TARGET_ALREADY_ASSIGNED', message: 'Новый специалист уже привязан к части компаний; дубли не будут созданы' },
        { code: 'COMPANIES_WITHOUT_ACTIVE_ORDERS', message: 'У части компаний нет текущих заказов; будет перенесена только привязка компании' },
        { code: 'TARGET_MISSING_MANAGER_LINKS', message: 'Новый специалист не состоит в команде части менеджеров этих компаний' },
        { code: 'FROM_INACTIVE', message: 'Исходный специалист отключен' }
      ]
    })));
    component.setFromWorkerId(11);
    component.setToWorkerId(12);
    component.previewTransfer();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(Array.from(element.querySelectorAll('.warning-list p span:last-child')).map((span) => text(span))).toEqual([
      'Новый специалист уже привязан к 2 компаниям: дублей не будет.',
      'У 21 компании нет текущих заказов: туда только добавится новый специалист.',
      'Новый специалист не в команде 1 менеджера этих компаний.',
      'Исходный специалист отключен'
    ]);
    expect(text(element.querySelector('.preview-panel h3'))).toBe('Специалист 11 → Специалист 12');
    expect(text(element.querySelector('.company-samples h4'))).toBe('Компании · показаны первые 20 из 57');
    expect(text(element.querySelector('.preview-panel .transfer-note'))).toContain('Новый специалист добавится в компании, прежний в них останется.');
    expect(Array.from(element.querySelectorAll('.preview-panel .metric-grid span')).map((span) => text(span))).toEqual([
      'Компании', 'Незавершённые заказы', 'Неопубликованные отзывы', 'Новые задачи по плохим отзывам',
      'Запланированные восстановления', 'Компании, где новый уже есть'
    ]);

    // Without a cut the list needs no note.
    component.preview.set(checkOf(11, 12, { companyCount: 2, sampleCompanies: companies.slice(0, 2) }));
    expect(component.sampleNote()).toBeNull();

    const options = Array.from(element.querySelectorAll('select')[0].querySelectorAll('option')).map((option) => text(option));
    expect(options).toEqual(['Выберите специалиста', 'Иванов Иван (ivanov.a)', 'Иванов Иван (ivanov.b)', 'Петров Пётр (petrov)', 'Сидоров Сидор (sidorov)']);
    expect(text(element.querySelector('.list-hint'))).toBe('«От специалиста» показывает и отключённых специалистов, у которых осталась работа; «К специалисту» — только активных.');
    expect(element.querySelector('textarea')?.getAttribute('maxlength')).toBe('1000');
  });
});
