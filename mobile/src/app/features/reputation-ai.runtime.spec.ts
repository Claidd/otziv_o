import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import type { DeepCompanyResearchJob, ReputationAiPrompt } from '../core/reputation-ai.api';
import { ReputationAiPage } from './reputation-ai.page';

const root = '/api/ai/reputation';
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
const job = {
  jobId: 44, companyId: 3, companyName: 'Компания', status: 'QUEUED',
  provider: 'openai', model: 'test', responseId: '', errorMessage: '', report: null,
  operation: 'full_report', createdAt: '', updatedAt: '', startedAt: null, completedAt: null
} as DeepCompanyResearchJob;

describe('mobile AI reputation administration', () => {
  let fixture: ComponentFixture<ReputationAiPage>;
  let page: ReputationAiPage;
  let http: HttpTestingController;
  let approved = true;

  beforeEach(async () => {
    approved = true;
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: MobileConfirmService, useValue: { confirm: async () => approved } },
      { provide: MobileExternalLinkService, useValue: { open: async () => undefined } }
    ] });
    TestBed.overrideComponent(ReputationAiPage, { set: { template: '', imports: [] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReputationAiPage);
    page = fixture.componentInstance;
    page.ngOnInit();
    http.expectOne(`${root}/status`).flush({ enabled: true, openAiResearchReportProfiles: [], openAiContentPackProfiles: [] });
    http.expectOne(`${root}/prompts`).flush([]);
    http.expectOne((item) => item.url === `${root}/companies/1/deep-research/jobs/history`).flush([]);
    await settle();
  });

  afterEach(() => { fixture.destroy(); http.verify(); });

  it('requires a valid company and explicit approval before starting a costly report', async () => {
    page.setCompanyId('1.5');
    await page.startDeep('full_report');
    expect(page.error()).toContain('целый положительный');
    http.expectNone((item) => item.url.includes('/deep-research/jobs') && item.method === 'POST');

    page.setCompanyId('3');
    approved = false;
    await page.startDeep('full_report');
    http.expectNone(`${root}/companies/3/deep-research/jobs`);

    approved = true;
    page.manualDescription.set('Проверенный факт');
    page.productsText.set('Товар A\nТовар B');
    page.publicUrlsText.set('https://example.test');
    const pending = page.startDeep('full_report');
    await settle();
    const request = http.expectOne(`${root}/companies/3/deep-research/jobs`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toMatchObject({
      manualDescription: 'Проверенный факт',
      productsOrServices: ['Товар A', 'Товар B'],
      publicUrls: ['https://example.test'],
      deepResearchProfile: 'quality', enrichCollectionGaps: true
    });
    request.flush(job);
    await settle();
    http.expectOne((item) => item.url === `${root}/companies/3/deep-research/jobs/history`).flush([job]);
    await pending;
    expect(page.deepJob()?.jobId).toBe(44);
  });

  it('blocks prompt changes without required markers and confirms a valid save', async () => {
    const prompt = {
      key: 'review', title: 'Отзыв', description: '', content: 'Исходный {company}',
      defaultContent: 'Исходный {company}', customized: false, updatedAt: null,
      requiredPlaceholders: ['{company}'], presets: []
    } as ReputationAiPrompt;
    const loading = page.loadPrompts();
    http.expectOne(`${root}/prompts`).flush([prompt]);
    await loading;
    http.expectOne((item) => item.url === `${root}/prompts/review/history`).flush([]);
    await settle();

    page.updatePromptDraft('Изменено без маркера');
    await page.savePrompt();
    expect(page.error()).toContain('{company}');
    http.expectNone(`${root}/prompts/review`);

    page.updatePromptDraft('Изменено для {company}');
    approved = false;
    await page.savePrompt();
    http.expectNone(`${root}/prompts/review`);

    approved = true;
    const saving = page.savePrompt();
    await settle();
    const request = http.expectOne(`${root}/prompts/review`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ content: 'Изменено для {company}' });
    request.flush({ ...prompt, content: 'Изменено для {company}', customized: true });
    await settle();
    http.expectOne((item) => item.url === `${root}/prompts/review/history`).flush([]);
    await saving;
    expect(page.selectedPrompt()?.customized).toBe(true);
  });
});
