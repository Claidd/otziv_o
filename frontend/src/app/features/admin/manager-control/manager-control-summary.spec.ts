import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../../core/auth.service';
import { ManagerApi } from '../../../core/manager.api';
import { ManagerControlApi, ManagerControlManager, ManagerControlManagerDetail, ManagerControlProblem } from '../../../core/manager-control.api';
import { ToastService } from '../../../shared/toast.service';
import { ManagerControlComponent } from './manager-control.component';

const invoiceProblem: ManagerControlProblem = {
  code: 'COMMON_INVOICES', label: 'Общие счета', count: 1, severity: 'CRITICAL', group: 'ACTION',
  icon: 'receipt_long', targetUrl: '/admin/common-billing', itemStatus: 'OPEN'
};
const chatProblem: ManagerControlProblem = {
  ...invoiceProblem, code: 'CLIENT_CHAT_UNANSWERED', label: 'Неотвеченные сообщения', targetUrl: '/admin/messages'
};
function manager(problems: ManagerControlProblem[]): ManagerControlManager {
  return {
    managerId: 3, username: 'fixture', name: 'Анжелика', active: true, qualityScore: 0, riskScore: 0,
    fastClickRisk: false, canCloseDay: false, openItemCount: 1, handledItemCount: 0,
    actionTotalCount: 1, actionCompletedCount: 0, actionProgressPercent: 0, leadActionCount: 1,
    status: 'RED', criticalCount: 1, warningCount: 0, workloadCount: 0, totalAttentionCount: 1,
    overdueOrderCount: 0, openRiskCount: 0, orderAttentionCount: 1, workerSectionCount: 0,
    problems, workerSections: [], overdueStatuses: [], workerExplanationStats: [],
    activeWorkSeconds: 0, averageDailyWorkSeconds: 0, averageReactionSeconds: 0, reactionCount: 0
  };
}
function detail(): ManagerControlManagerDetail {
  return {
    managerId: 3, username: 'fixture', name: 'Анжелика', dailyControlId: 252, controlDate: '2026-10-22',
    dailyControlStatus: 'IN_PROGRESS', qualityScore: 0, riskScore: 0, fastClickRisk: false,
    canCloseDay: false, closeBlockers: [], openItemCount: 1, handledItemCount: 0,
    workerExplanationStats: [], events: [], items: [{
      itemId: 1499, itemKey: 'COMMON_INVOICES', itemType: 'PROBLEM', reasonCode: 'COMMON_INVOICES',
      reasonLabel: 'Общий счёт требует проверки', label: 'Общие счета', targetUrl: '/admin/common-billing',
      count: 1, severity: 'CRITICAL', group: 'ACTION', itemStatus: 'OPEN', hiddenExampleCount: 0,
      createdAt: '2026-10-22T10:00:00', updatedAt: '2026-10-22T10:00:00',
      examples: [{ controlEntityId: 8769, entityId: 175, type: 'COMMON_INVOICE', title: 'Счёт №175',
        reason: 'Проверьте состав счёта', itemStatus: 'OPEN', targetUrl: '/admin/common-billing?invoiceId=175' }]
    }]
  };
}
function setup(problems = [invoiceProblem], personal = false, fragment: string | null = null) {
  const row = manager(problems);
  const detailResponse = new Subject<ManagerControlManagerDetail>();
  const params = new Subject<ParamMap>();
  const api = { today: () => of({ date: '2026-10-22', generatedAt: '2026-10-22T10:00:00', managers: [row] }),
    managerDetails: vi.fn().mockReturnValue(detailResponse) };
  const router = { navigate: vi.fn().mockResolvedValue(true) };
  TestBed.configureTestingModule({ imports: [ManagerControlComponent], providers: [
    { provide: ManagerControlApi, useValue: api }, { provide: ManagerApi, useValue: {} },
    { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
    { provide: AuthService, useValue: { hasAnyRealmRole: () => false } },
    { provide: ActivatedRoute, useValue: { snapshot: { data: {}, fragment }, paramMap: params } },
    { provide: Router, useValue: router }
  ] });
  const fixture = TestBed.createComponent(ManagerControlComponent);
  const page = fixture.componentInstance;
  page.embedded = true;
  page.personalControl = personal;
  fixture.detectChanges();
  return { fixture, page, row, api, router, detailResponse, params };
}

describe('manager action summary links', () => {
  it('omits a resolved invoice section while retaining the actual unanswered-message action', () => {
    const { fixture, page, row } = setup([{ ...invoiceProblem, itemStatus: 'RESOLVED' }, chatProblem]);
    const chips = [...fixture.nativeElement.querySelectorAll('.action-group .control-chip')] as HTMLElement[];
    expect(chips.map(chip => chip.textContent)).toHaveLength(1);
    expect(chips[0].textContent).toContain('Неотвеченные сообщения');
    expect(chips[0].textContent).not.toContain('Общие счета');
    expect(page.currentActionCount(row)).toBe(1);
  });

  it('opens the chosen manager remarks instead of the unfiltered common billing page', () => {
    const { fixture, router } = setup();
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('.action-group .control-chip');
    expect(link.getAttribute('href')).toBe('/admin/manager-control/3#manager-control-problem-COMMON_INVOICES');
    const event = new MouseEvent('click', { bubbles: true, cancelable: true });
    link.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(router.navigate).toHaveBeenCalledExactlyOnceWith(['/admin/manager-control', 3], {
      fragment: 'manager-control-problem-COMMON_INVOICES'
    });
  });

  it('keeps unrelated problem links and workload billing links unchanged', () => {
    const { page, row, router } = setup();
    const event = new MouseEvent('click', { cancelable: true });
    page.openActionProblem(event, row, chatProblem);
    expect(event.defaultPrevented).toBe(false);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(page.actionProblemTargetUrl(row, chatProblem)).toBe('/admin/messages');
    expect(page.actionProblemTargetUrl(row, { ...invoiceProblem, group: 'WORKLOAD' })).toBe('/admin/common-billing');
  });

  it('opens personal remarks in place and focuses the matching section after the detail arrives', async () => {
    const { fixture, page, router, api, detailResponse } = setup([invoiceProblem], true);
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('.personal-remark-actions .control-chip');
    expect(link.getAttribute('href')).not.toContain('/admin');
    link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    expect(router.navigate).not.toHaveBeenCalled();
    expect(api.managerDetails).toHaveBeenCalledExactlyOnceWith(3);
    expect(page.detailLoading()).toBe(true);
    detailResponse.next(detail());
    fixture.detectChanges();
    await fixture.whenStable();
    const section: HTMLElement = fixture.nativeElement.querySelector('#manager-control-problem-COMMON_INVOICES');
    expect(section.textContent).toContain('Счёт №175');
    expect(document.activeElement).toBe(section);
    expect(page.isPersonalControl()).toBe(true);
  });

  it('restores the requested focus after navigation to the manager detail route', async () => {
    const { fixture, params, detailResponse } = setup([invoiceProblem], false, 'manager-control-problem-COMMON_INVOICES');
    params.next(convertToParamMap({ managerId: '3' }));
    detailResponse.next(detail());
    fixture.detectChanges();
    await fixture.whenStable();
    const section: HTMLElement = fixture.nativeElement.querySelector('#manager-control-problem-COMMON_INVOICES');
    expect(document.activeElement).toBe(section);
  });

  it.each([false, true])('offers technical evidence only when administrator access is %s', (admin) => {
    const { fixture, page, row, detailResponse } = setup([invoiceProblem], true);
    vi.spyOn(page, 'canManageDailySummary').mockReturnValue(admin);
    page.openDetails(row);
    const value = detail();
    value.items[0].examples[0].diagnosis = {
      caseId: 12, version: 1, episode: 1, status: 'QUEUED', observedAt: '2026-10-22T10:00:00',
      model: 'server-rules', rolloutMode: 'ENFORCE',
      facts: [{ reference: 'invoice:175:status', subject: 'invoice', field: 'status', value: 'PARTIALLY_PAID' }],
      diagnosis: { summary: 'Счёт оплачен частично.', confirmedFacts: [], hypotheses: [], missingEvidence: [],
        actions: [{ code: 'CHECK_SOURCE', text: 'Сверьте остаток оплаты.', owner: 'Менеджер', verification: 'Остаток подтверждён.', evidenceRefs: [] }],
        verification: ['Остаток урегулирован.'] }
    };
    detailResponse.next(value);
    fixture.detectChanges();
    const diagnosis: HTMLElement = fixture.nativeElement.querySelector('app-manager-problem-diagnosis');
    expect(diagnosis.textContent).toContain('Сверьте остаток оплаты.');
    expect(!!diagnosis.querySelector('.technical-details')).toBe(admin);
    expect(diagnosis.textContent).not.toContain('invoice:175:status');
  });
});
