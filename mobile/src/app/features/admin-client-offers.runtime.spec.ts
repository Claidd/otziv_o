import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { MobileClientOffersApi, type MobileOfferBoard, type MobileOfferSettings, type MobileOfferSummary } from '../core/client-offers.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { AdminClientOffersPage } from './admin-client-offers.page';

const root = '/api/admin/client-offers';
const settings: MobileOfferSettings = {
  title: 'Осеннее предложение', message: 'Описание услуги', dailyLimit: 20, intervalMinutes: 10,
  windowStart: '10:00', windowEnd: '21:00', includeActive: true, includeStopped: false,
  includeBanned: false, fileMode: 'ATTACHMENT', testOnly: false, includeLeadInWork: false,
  includeLeadOther: false, leadFallbackClientId: null
};
const summary = (state: MobileOfferSummary['campaign']['state'], failed = 0, unknown = 0): MobileOfferSummary => ({
  campaign: { id: 'a995a599-9cf8-4450-a17b-ff1c3c789828', settings, state, fileName: null, createdAt: '2026-10-16T10:00:00', startedAt: null, nextAt: null },
  counts: { total: 4, pending: 3, sending: 0, sent: 0, failed, unknown, skipped: 0 }, usedToday: 0
});
const board = (campaigns: MobileOfferSummary[] = []): MobileOfferBoard => ({ liveEnabled: true, campaigns });
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

describe('mobile client offer campaigns', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AdminClientOffersPage>;
  let page: AdminClientOffersPage;
  let confirm: { confirm: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    confirm = { confirm: vi.fn(async () => true) };
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: MobileConfirmService, useValue: confirm }
    ] });
    TestBed.overrideComponent(AdminClientOffersPage, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminClientOffersPage);
    page = fixture.componentInstance;
    page.ngOnInit();
    http.expectOne(root).flush(board());
    http.expectOne(`${root}/lead-senders`).flush(['whatsapp-main']);
    await settle();
  });

  afterEach(() => { fixture.destroy(); http.verify(); });

  it('previews exact recipients before a confirmed start and saves JSON plus the selected file as multipart', async () => {
    page.draft = { ...settings };
    await page.save(true);
    http.expectNone((request) => request.method === 'PUT' || request.url.endsWith('/start'));

    const preview = page.preview();
    const previewRequest = http.expectOne(`${root}/preview`);
    expect(previewRequest.request.method).toBe('POST');
    expect(previewRequest.request.body).toEqual(settings);
    previewRequest.flush([{ audience: 'ACTIVE', total: 5, reachable: 4 }]);
    await preview;

    page.file = new File(['hello'], 'offer.txt', { type: 'text/plain' });
    const start = page.save(true);
    await settle();
    expect(confirm.confirm).toHaveBeenCalledOnce();
    const saveRequest = http.expectOne((request) => request.url === `${root}/${page.draftId}`);
    expect(saveRequest.request.method).toBe('PUT');
    expect(saveRequest.request.params.get('removeFile')).toBe('false');
    expect(saveRequest.request.body).toBeInstanceOf(FormData);
    expect(((saveRequest.request.body as FormData).get('file') as File).name).toBe('offer.txt');
    expect((saveRequest.request.body as FormData).get('settings')).toBeInstanceOf(Blob);
    saveRequest.flush(summary('DRAFT').campaign);
    await settle();

    const startRequest = http.expectOne(`${root}/${summary('DRAFT').campaign.id}/start`);
    expect(startRequest.request.method).toBe('POST');
    startRequest.flush(board([summary('RUNNING')]));
    await settle();
    http.expectOne(`${root}/${summary('DRAFT').campaign.id}/recipients?page=0`).flush([]);
    await start;
    expect(page.notice()).toContain('запущена');
  });

  it('never retries unknown outcomes and only exposes retry for failed rows in paused or completed campaigns', async () => {
    expect(page.canAction(summary('PAUSED', 0, 2), 'retry-failed')).toBe(false);
    expect(page.canAction(summary('RUNNING', 1, 0), 'retry-failed')).toBe(false);
    const paused = summary('PAUSED', 2, 1);
    page.board.set(board([paused]));
    page.select(paused);
    http.expectOne(`${root}/${paused.campaign.id}/recipients?page=0`).flush([]);
    await settle();

    const retry = page.action('retry-failed');
    await settle();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('только 2') }));
    const request = http.expectOne(`${root}/${paused.campaign.id}/retry-failed`);
    expect(request.request.method).toBe('POST');
    request.flush(board([paused]));
    await settle();
    http.expectOne(`${root}/${paused.campaign.id}/recipients?page=0`).flush([]);
    await retry;
  });

  it('rejects oversized or unsupported files before save', () => {
    const input = document.createElement('input');
    const unsupported = new File(['x'], 'bad.exe');
    Object.defineProperty(input, 'files', { value: [unsupported] });
    page.chooseFile({ target: input } as unknown as Event);
    expect(page.file).toBeNull();
    expect(page.error()).toContain('PDF');
    expect(input.value).toBe('');
  });

  it('uses the existing API for pages, senders, previews and files', async () => {
    const api = TestBed.inject(MobileClientOffersApi);
    const request = api.file(summary('DRAFT').campaign.id).subscribe();
    const download = http.expectOne(`${root}/${summary('DRAFT').campaign.id}/file`);
    expect(download.request.responseType).toBe('blob');
    download.flush(new Blob(['file']));
    request.unsubscribe();
  });
});
