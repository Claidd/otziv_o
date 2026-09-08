import { PageWriteTracker } from '../../core/page-write-tracker';
import { Subject, of } from 'rxjs';
import { OrderCompanyReportFacade } from './order-company-report.facade';
import { CompanyReportPresentation } from './company-report.presentation';
import { RouteEpochGuard } from '../../core/route-epoch.guard';
import type { CompanyDeepReportState } from '../../core/api.service';

const state = (companyId: number): CompanyDeepReportState => ({ companyId, companyName: `Company ${companyId}`, canStart: false, canRefresh: true, unavailableReason: '' });

describe('order company report session', () => {
  const create = () => {
    const route = new RouteEpochGuard(); route.change('order:1');
    const api = { getManagerOrderCompanyReport: vi.fn(), startManagerOrderCompanyReport: vi.fn(), refreshManagerOrderCompanyReport: vi.fn() };
    const env = { orderId: 1, canRefresh: true };
    const facade = new OrderCompanyReportFacade({ writes: new PageWriteTracker(), api, orderId: () => env.orderId, canRefresh: () => env.canRefresh, capture: () => route.capture(), accepts: ticket => route.accepts(ticket), errorMessage: (_, fallback) => fallback });
    return { facade, api, route, env };
  };
  it('cancels a dismissed read and suppresses its late data after reopening the same order', () => {
    const { facade, api } = create(); const old = new Subject<CompanyDeepReportState>(); const current = new Subject<CompanyDeepReportState>();
    api.getManagerOrderCompanyReport.mockReturnValueOnce(old).mockReturnValueOnce(current);
    facade.openCompanyReport(); expect(old.observed).toBe(true);
    facade.closeCompanyReport(); expect(old.observed).toBe(false);
    facade.openCompanyReport(); old.next(state(99)); current.next(state(1));
    expect(facade.companyReportState()?.companyId).toBe(1);
    facade.closeCompanyReport();
  });
  it('keeps a dispatched report command alive and never starts it twice after close/reopen', () => {
    const { facade, api } = create(); const command = new Subject<CompanyDeepReportState>();
    api.startManagerOrderCompanyReport.mockReturnValue(command);
    api.getManagerOrderCompanyReport.mockReturnValue(of({ ...state(1), canStart: true }));
    facade.startCompanyReport(); facade.closeCompanyReport(); facade.openCompanyReport(); facade.startCompanyReport();
    expect(command.observed).toBe(true); expect(api.startManagerOrderCompanyReport).toHaveBeenCalledTimes(1);
    command.next(state(99)); command.complete();
    expect(facade.companyReportState()?.companyId).toBe(1);
    facade.closeCompanyReport();
  });
  it('does not let an old command update a different order or another page', () => {
    const { facade, api, route, env } = create(); const command = new Subject<CompanyDeepReportState>();
    api.refreshManagerOrderCompanyReport.mockReturnValue(command); facade.refreshCompanyReport();
    facade.closeCompanyReport(); env.orderId = 2; route.change('order:2');
    api.getManagerOrderCompanyReport.mockReturnValue(of(state(2))); facade.openCompanyReport();
    command.next(state(1)); command.complete();
    expect(facade.companyReportState()?.companyId).toBe(2);
    expect(create().facade.companyReportState()).toBeNull();
  });
  it('keeps refresh permission and escapes report content before rendering markup', () => {
    const { facade, api, env } = create(); env.canRefresh = false; facade.refreshCompanyReport();
    expect(api.refreshManagerOrderCompanyReport).not.toHaveBeenCalled();
    expect(facade.companyReportError()).toContain('владелец');
    const sections = new CompanyReportPresentation().buildCompanyReportSections({ reportMarkdown: '# Summary\n**Safe** <img src=x onerror=alert(1)>' });
    expect(sections).toHaveLength(1); expect(sections[0].html).toContain('<strong>Safe</strong>');
    expect(sections[0].html).not.toContain('<img'); expect(sections[0].html).toContain('&lt;img');
  });
});
