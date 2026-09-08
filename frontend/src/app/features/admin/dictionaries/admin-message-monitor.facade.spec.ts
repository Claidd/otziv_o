import { of, Subject } from 'rxjs';
import type { AdminClientMessageMonitor, AdminClientMessageMonitorQueueItem, AdminClientMessageMonitorSettings } from '../../../core/admin-dictionaries.api';
import { AdminMessageMonitorFacade } from './admin-message-monitor.facade';

const snapshot = (activeCandidates: number) => ({ enabled: true, activeCandidates, updatedAt: '2026-09-07T10:00:00+08:00', queue: [], attempts: [] }) as unknown as AdminClientMessageMonitor;
const candidate = { id: 17, targetKey: 'company:91', scenarioLabel: 'Оплата' } as AdminClientMessageMonitorQueueItem;

function setup() {
  const view = { active: true, visible: true, enabled: true };
  const api = {
    getClientMessageMonitor: vi.fn().mockReturnValue(of(snapshot(1))),
    getClientMessageMaintenancePreview: vi.fn().mockReturnValue(of({})),
    updateClientMessageMonitorSettings: vi.fn(),
    applyClientMessageMaintenance: vi.fn(),
    retryClientMessageNow: vi.fn(),
    disableClientMessageCandidate: vi.fn(),
    markClientMessageCandidateDone: vi.fn()
  };
  const toast = { success: vi.fn(), error: vi.fn() };
  const facade = new AdminMessageMonitorFacade({ api, toast, isActive: () => view.active, isVisible: () => view.visible, enabled: () => view.enabled, setEnabled: enabled => { view.enabled = enabled; } });
  return { facade, api, view, toast };
}

describe('dictionary message monitor tab lifecycle', () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it('does not overlap polling reads and stops polling and HTTP when the tab is left', () => {
    vi.useFakeTimers();
    const { facade, api, view } = setup();
    const pending = new Subject<AdminClientMessageMonitor>();
    api.getClientMessageMonitor.mockReturnValueOnce(pending);
    facade.syncClientMessageMonitorPolling();
    vi.advanceTimersByTime(180_000);
    expect(api.getClientMessageMonitor).toHaveBeenCalledTimes(1);
    expect(pending.observed).toBe(true);
    view.active = false;
    facade.syncClientMessageMonitorPolling();
    expect(pending.observed).toBe(false);
    vi.advanceTimersByTime(180_000);
    expect(api.getClientMessageMonitor).toHaveBeenCalledTimes(1);
    pending.next(snapshot(99));
    expect(facade.clientMessageMonitor()).toBeNull();
    facade.destroy();
  });

  it('hides old errors and reloads current data when the document becomes visible again', () => {
    vi.useFakeTimers();
    const { facade, api, view, toast } = setup();
    const old = new Subject<AdminClientMessageMonitor>();
    api.getClientMessageMonitor.mockReturnValueOnce(old);
    facade.loadClientMessageMonitor();
    view.visible = false;
    facade.syncClientMessageMonitorPolling();
    old.error(new Error('old failure'));
    expect(facade.clientMessageMonitorLoading()).toBe(false);
    expect(toast.error).not.toHaveBeenCalled();
    view.visible = true;
    facade.syncClientMessageMonitorPolling();
    expect(facade.clientMessageMonitor()?.activeCandidates).toBe(1);
    facade.destroy();
  });

  it('keeps dispatched mutations alive but suppresses their stale response after leave and reentry', () => {
    vi.useFakeTimers();
    const { facade, api, view, toast } = setup();
    const write = new Subject<AdminClientMessageMonitor>();
    api.retryClientMessageNow.mockReturnValue(write);
    facade.retryClientMessageCandidate(candidate);
    view.active = false;
    facade.syncClientMessageMonitorPolling();
    expect(write.observed).toBe(true);
    view.active = true;
    facade.retryClientMessageCandidate(candidate);
    expect(api.retryClientMessageNow).toHaveBeenCalledTimes(1);
    write.next(snapshot(99)); write.complete();
    expect(facade.clientMessageManualAction()).toBeNull();
    expect(facade.clientMessageMonitor()).toBeNull();
    expect(toast.success).not.toHaveBeenCalled();
    facade.loadClientMessageMonitor();
    expect(facade.clientMessageMonitor()?.activeCandidates).toBe(1);
    facade.destroy();
  });

  it('cancels stale reads before a manual action without canceling or replaying the write', () => {
    vi.useFakeTimers();
    const { facade, api } = setup();
    const read = new Subject<AdminClientMessageMonitor>();
    const write = new Subject<AdminClientMessageMonitor>();
    api.getClientMessageMonitor.mockReturnValue(read);
    api.retryClientMessageNow.mockReturnValue(write);
    facade.loadClientMessageMonitor();
    facade.retryClientMessageCandidate(candidate);
    expect(read.observed).toBe(false);
    facade.loadClientMessageMonitor(true);
    expect(api.getClientMessageMonitor).toHaveBeenCalledTimes(1);
    write.next(snapshot(7)); write.complete();
    read.next(snapshot(1));
    expect(facade.clientMessageMonitor()?.activeCandidates).toBe(7);
    expect(api.retryClientMessageNow).toHaveBeenCalledWith(17);
    facade.destroy();
  });

  it('does not update another screen after an enabled-setting request finishes late', () => {
    const { facade, api, view, toast } = setup();
    const write = new Subject<AdminClientMessageMonitorSettings>();
    api.updateClientMessageMonitorSettings.mockReturnValue(write);
    facade.setClientMessageMonitorEnabled(false);
    facade.destroy();
    expect(write.observed).toBe(true);
    write.next({ enabled: false }); write.complete();
    expect(view.enabled).toBe(true);
    expect(toast.success).not.toHaveBeenCalled();
    expect(facade.clientMessageMonitorSaving()).toBe(false);
  });

  it('discovers the server monitoring setting on first entry without a settings-tab preload', () => {
    vi.useFakeTimers();
    const { facade, view } = setup();
    view.enabled = false;
    facade.loadClientMessageMonitor(false, true);
    expect(view.enabled).toBe(true);
    expect(facade.clientMessageMonitor()?.activeCandidates).toBe(1);
    facade.destroy();
  });

  it('keeps filters and snapshots isolated between two dictionary screen instances', () => {
    const a = setup(); const b = setup();
    a.facade.setMonitorScenarioFilter('PAYMENT_REMINDER');
    a.facade.clientMessageMonitor.set(snapshot(99));
    expect(b.facade.monitorScenarioFilter()).toBe('ALL');
    expect(b.facade.clientMessageMonitor()).toBeNull();
    a.facade.destroy(); b.facade.destroy();
  });
});
