import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { ClientOffersApi, OfferBoard, OfferCampaign } from '../../../core/client-offers.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { ClientOffersComponent } from './client-offers.component';

@Component({ selector: 'app-admin-layout', template: '<ng-content />' })
class LayoutStub { title = input(''); active = input(''); }

describe('client offer campaigns', () => {
  const empty: OfferBoard = { liveEnabled: true, campaigns: [] };
  const api = { board: vi.fn(() => of(empty)), preview: vi.fn(() => of([])), save: vi.fn(), action: vi.fn(),
    recipients: vi.fn(() => of([])), file: vi.fn() };
  beforeEach(() => {
    vi.clearAllMocks(); api.board.mockReturnValue(of(empty));
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ClientOffersApi, useValue: api }] });
    TestBed.overrideComponent(ClientOffersComponent, { remove: { imports: [AdminLayoutComponent] }, add: { imports: [LayoutStub] } });
  });
  afterEach(() => TestBed.resetTestingModule());
  function setup() { const fixture = TestBed.createComponent(ClientOffersComponent); fixture.detectChanges(); return fixture; }
  function valid(component: ClientOffersComponent) { component.draft.title = 'Новая услуга'; component.draft.message = 'Предложение'; }
  function campaign(component: ClientOffersComponent, state: string): OfferCampaign {
    return { id: component.draftId, settings: { ...component.draft }, state, fileName: null, createdAt: '2026-09-17T02:00:00', startedAt: null, nextAt: null };
  }
  it('defaults to attachments and first audience only, renders all controls', () => {
    const fixture = setup(); const component = fixture.componentInstance;
    expect(component.draft.fileMode).toBe('ATTACHMENT'); expect(component.draft.includeActive).toBe(true);
    expect(component.draft.includeStopped).toBe(false); expect(component.draft.includeBanned).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Вложением к сообщению');
    expect(fixture.nativeElement.textContent).toContain('3. Бан');
  });
  it('rejects empty audiences, fractional limits, reversed windows and empty messages', () => {
    const c = setup().componentInstance; valid(c); expect(c.valid()).toBe(true);
    c.draft.includeActive = false; c.save(true); expect(api.save).not.toHaveBeenCalled();
    c.draft.includeActive = true; c.draft.dailyLimit = 1.5; expect(c.valid()).toBe(false);
    c.draft.dailyLimit = 10; c.draft.windowEnd = '09:00'; expect(c.valid()).toBe(false);
  });
  it('saves text, file and link selection before the explicit start action', () => {
    const c = setup().componentInstance; valid(c); c.draft.fileMode = 'LINK';
    c.file = new File(['fixture'], 'offer.txt', { type: 'text/plain' });
    api.save.mockReturnValue(of(campaign(c, 'DRAFT'))); api.action.mockReturnValue(of(empty));
    c.save(true);
    expect(api.save).toHaveBeenCalledWith(c.draftId, expect.objectContaining({ fileMode: 'LINK' }), expect.any(File), false);
    expect(api.action).toHaveBeenCalledWith(c.draftId, 'start'); expect(c.busy()).toBe(false);
  });
  it('polling keeps unsaved content and never starts a campaign', () => {
    const c = setup().componentInstance; valid(c); c.load(false);
    expect(c.draft.message).toBe('Предложение'); expect(api.action).not.toHaveBeenCalled();
  });
  it('shows unknown delivery separately and locks the payload after start', () => {
    const fixture = setup(); const c = fixture.componentInstance; valid(c);
    const row = { campaign: campaign(c, 'RUNNING'), counts: { total: 3, pending: 1, sent: 1, unknown: 1, failed: 0, skipped: 0, sending: 0 }, usedToday: 2 };
    c.board.set({ liveEnabled: true, campaigns: [row] }); c.select(row); fixture.detectChanges();
    expect(c.editable()).toBe(false); expect(fixture.nativeElement.querySelector('textarea')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('автоматический повтор заблокирован');
    expect(fixture.nativeElement.textContent).toContain('Пауза');
  });
});
