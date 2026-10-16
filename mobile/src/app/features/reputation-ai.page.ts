import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Capacitor } from '@capacitor/core';
import { Directory, Filesystem } from '@capacitor/filesystem';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular/standalone';
import { firstValueFrom, type Observable } from 'rxjs';
import {
  ReputationAiApi,
  type DeepCompanyResearchJob,
  type ReputationAiPrompt,
  type ReputationAiPromptPreset,
  type ReputationAiPromptPreview,
  type ReputationAiPromptValidation,
  type ReputationAiPromptVersion,
  type ReputationAiStatus,
  type ReputationContentPackJob,
  type ReputationResearchRequest,
  type ReputationReviewReplyResponse,
  type ReputationReviewTemplatesResult,
  type ReputationSingleReviewDraftResult,
  type ResearchSnapshot,
  type ReviewDraftResult,
  type ReviewSafetyReport,
  type ReputationReviewRewriteResponse
} from '../core/reputation-ai.api';
import { safeHttpsExternalUrl } from '../shared/external-navigation';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

type Section = 'report' | 'pack' | 'text' | 'prompts' | 'route';
type DeepOperation = 'full_report' | 'refresh_sources' | 'rebuild_text' | 'rebuild_section';

@Component({
  selector: 'app-reputation-ai-mobile',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  templateUrl: './reputation-ai.page.html',
  styleUrl: './reputation-ai.page.scss'
})
export class ReputationAiPage implements OnInit, OnDestroy {
  private readonly api = inject(ReputationAiApi);
  private readonly confirm = inject(MobileConfirmService);
  private readonly external = inject(MobileExternalLinkService);
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private epoch = 0;
  private pollingDeep = false;
  private pollingPack = false;

  readonly section = signal<Section>('report');
  readonly busy = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly companyId = signal('1');
  readonly status = signal<ReputationAiStatus | null>(null);

  readonly productOrService = signal('');
  readonly manualDescription = signal('');
  readonly productsText = signal('');
  readonly publicUrlsText = signal('');
  readonly includeWebsite = signal(true);
  readonly autoEnrich = signal(true);
  readonly deepProfile = signal('quality');
  readonly packProfile = signal('quality');
  readonly adTextsCount = signal(6);
  readonly socialPostsCount = signal(4);
  readonly positiveReplyCount = signal(6);
  readonly negativeReplyCount = signal(4);

  readonly snapshot = signal<ResearchSnapshot | null>(null);
  readonly deepJob = signal<DeepCompanyResearchJob | null>(null);
  readonly history = signal<DeepCompanyResearchJob[]>([]);
  readonly historyQuery = signal('');
  readonly historyStatus = signal('all');
  readonly historyOperation = signal('all');
  readonly comparisonBase = signal<DeepCompanyResearchJob | null>(null);
  readonly reviewIdeasText = signal('');
  readonly filteredHistory = computed(() => {
    const query = this.historyQuery().trim().toLowerCase();
    return this.history().filter((job) =>
      (this.historyStatus() === 'all' || job.status === this.historyStatus())
      && (this.historyOperation() === 'all' || job.operation === this.historyOperation())
      && (!query || [job.companyName, job.jobId, job.model, job.operation, job.errorMessage].some((value) => String(value ?? '').toLowerCase().includes(query)))
    );
  });
  readonly deepProfiles = computed(() => this.status()?.openAiResearchReportProfiles ?? []);
  readonly packProfiles = computed(() => this.status()?.openAiContentPackProfiles ?? []);
  readonly reportComparison = computed(() => {
    const base = this.comparisonBase()?.report;
    const current = this.deepJob()?.report;
    if (!base || !current) return null;
    const quality = (report: typeof current) => {
      const checks = (report.qualityChecks ?? []).filter((check) => check.status !== 'info');
      return `${checks.filter((check) => check.status === 'pass').length}/${checks.length}`;
    };
    const sourceSet = (report: typeof current) => new Set((report.sources ?? []).map((source) => source.url));
    const before = sourceSet(base); const after = sourceSet(current);
    return {
      qualityBefore: quality(base), qualityAfter: quality(current),
      addedSources: [...after].filter((source) => !before.has(source)).length,
      removedSources: [...before].filter((source) => !after.has(source)).length,
      sectionsBefore: base.sections.length, sectionsAfter: current.sections.length,
      warningsBefore: base.warnings.length, warningsAfter: current.warnings.length
    };
  });

  readonly packJob = signal<ReputationContentPackJob | null>(null);
  readonly templateNotes = signal('');
  readonly templateTopicsCount = signal(7);
  readonly templateDraftsCount = signal(5);
  readonly templateTone = signal('естественный, рекламно-полезный, без фейкового опыта');
  readonly templates = signal<ReputationReviewTemplatesResult | null>(null);
  readonly singleIdea = signal('');
  readonly singleStyle = signal('живой, спокойный, с мягкой рекламной пользой');
  readonly singleLength = signal('medium');
  readonly singleDraft = signal<ReputationSingleReviewDraftResult | null>(null);

  readonly experienceText = signal('');
  readonly draftTone = signal('естественный');
  readonly draftLength = signal('medium');
  readonly draftResult = signal<ReviewDraftResult | null>(null);
  readonly checkText = signal('');
  readonly allowedFactsText = signal('');
  readonly safety = signal<ReviewSafetyReport | null>(null);
  readonly rewriteTone = signal('естественный');
  readonly rewriteResult = signal<ReputationReviewRewriteResponse | null>(null);
  readonly replyText = signal('');
  readonly replyTone = signal('деловой');
  readonly replyCount = signal(3);
  readonly replies = signal<ReputationReviewReplyResponse | null>(null);

  readonly prompts = signal<ReputationAiPrompt[]>([]);
  readonly promptKey = signal('');
  readonly promptDraft = signal('');
  readonly promptValidation = signal<ReputationAiPromptValidation | null>(null);
  readonly promptPreview = signal<ReputationAiPromptPreview | null>(null);
  readonly promptHistory = signal<ReputationAiPromptVersion[]>([]);
  readonly selectedPrompt = computed(() => this.prompts().find((item) => item.key === this.promptKey()) ?? null);
  readonly missingPlaceholders = computed(() => this.selectedPrompt()?.requiredPlaceholders.filter((marker) => !this.promptDraft().includes(marker)) ?? []);
  readonly promptChanged = computed(() => {
    const prompt = this.selectedPrompt();
    return !!prompt && prompt.content.trim() !== this.promptDraft().trim();
  });

  ngOnInit(): void {
    void this.loadStatus();
    void this.loadPrompts();
    void this.loadHistory(false);
    this.pollTimer = setInterval(() => { void this.pollActiveJobs(); }, 6_000);
  }

  ngOnDestroy(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.pollTimer = null;
    this.epoch++;
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    try {
      await this.loadStatus();
      if (this.section() === 'prompts') await this.loadPrompts();
      if (this.section() === 'report') await this.loadHistory(false);
    } finally { await event.target.complete(); }
  }

  setCompanyId(value: number | string | null): void {
    const next = String(value ?? '');
    if (next === this.companyId()) return;
    this.companyId.set(next);
    this.epoch++;
    this.snapshot.set(null);
    this.deepJob.set(null);
    this.history.set([]);
    this.comparisonBase.set(null);
    this.packJob.set(null);
    this.templates.set(null);
    this.singleDraft.set(null);
  }

  validCompanyId(): number | null {
    const id = this.currentCompanyId();
    if (id == null) {
      this.error.set('Укажите целый положительный ID компании.');
      return null;
    }
    return id;
  }

  async loadStatus(): Promise<void> {
    try { this.status.set(await firstValueFrom(this.api.status())); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось загрузить статус AI.')); }
  }

  async checkOpenAiRoute(): Promise<void> {
    await this.run('check-route', this.api.checkOpenAiRoute(), (diagnostics) => {
      this.status.update((current) => current ? { ...current, openAiDiagnostics: diagnostics } : current);
      this.notice.set(diagnostics.lastMessage || `Маршрут: ${diagnostics.route}`);
    }, 'Проверка маршрута AI не выполнена.');
  }

  async loadSnapshot(): Promise<void> {
    const id = this.validCompanyId(); if (id == null) return;
    await this.run('load-snapshot', this.api.latestResearch(id), (value) => { this.snapshot.set(value); this.notice.set('Сырьё и источники загружены.'); }, 'Сырьё для этой компании пока не найдено.');
  }

  async refreshSnapshot(): Promise<void> {
    const id = this.validCompanyId(); if (id == null) return;
    if (!await this.confirm.confirm({ title: 'Обновить источники?', message: 'Будут собраны CRM, сайт, поиск и публичные страницы.', confirmText: 'Собрать' })) return;
    await this.run('refresh-snapshot', this.api.createResearch(id, this.researchRequest()), (value) => { this.snapshot.set(value); this.notice.set('Сырьё и источники обновлены.'); }, 'Не удалось собрать сырьё.');
  }

  async loadLatestDeep(): Promise<void> {
    const id = this.validCompanyId(); if (id == null) return;
    await this.run('load-deep', this.api.latestDeepResearchJob(id), (value) => { this.applyDeepJob(value); this.notice.set('Последний отчёт загружен.'); }, 'Отчёт для этой компании пока не найден.');
  }

  async loadHistory(showNotice = true): Promise<void> {
    const id = this.validCompanyId(); if (id == null) return;
    const epoch = this.epoch;
    try {
      const jobs = await firstValueFrom(this.api.deepResearchJobHistory(id, 20));
      if (epoch !== this.epoch) return;
      this.history.set(jobs);
      if (showNotice) this.notice.set(jobs.length ? 'История отчётов загружена.' : 'История пока пуста.');
    } catch (error) { if (showNotice && epoch === this.epoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить историю.')); }
  }

  openHistory(job: DeepCompanyResearchJob): void {
    if (job.companyId !== this.validCompanyId()) return;
    this.comparisonBase.set(null);
    this.applyDeepJob(job);
    this.notice.set(job.report ? 'Версия отчёта открыта.' : 'Статус запуска открыт.');
  }

  compareWithCurrent(job: DeepCompanyResearchJob): void {
    if (!job.report || !this.deepJob()?.report || job.jobId === this.deepJob()?.jobId) return;
    this.comparisonBase.set(job);
    this.notice.set(`Сравнение с отчётом #${job.jobId} открыто.`);
  }

  async startDeep(mode: DeepOperation, sectionIndex?: number): Promise<void> {
    const id = this.validCompanyId(); if (id == null || this.busy()) return;
    const baseId = this.deepJob()?.jobId ?? null;
    if (mode !== 'full_report' && (!baseId || !this.deepJob()?.report)) { this.error.set('Сначала откройте готовый отчёт.'); return; }
    if (this.deepActive()) { this.error.set('Текущий отчёт ещё собирается.'); return; }
    const section = sectionIndex == null ? null : this.deepJob()?.report?.sections?.[sectionIndex];
    if (mode === 'rebuild_section' && !section) return;
    const label = ({ full_report: 'глубокий отчёт', refresh_sources: 'источники', rebuild_text: 'текст отчёта', rebuild_section: `раздел «${section?.title}»` })[mode];
    if (!await this.confirm.confirm({ title: 'Запустить AI?', message: `Будет собран ${label}. Это может занять время и расходовать лимит AI.`, confirmText: 'Запустить' })) return;
    const request = this.researchRequest(mode === 'full_report' ? null : mode, mode === 'full_report' ? null : baseId, section?.title ?? null, sectionIndex ?? null);
    const call = mode === 'full_report' ? this.api.startDeepResearchJob(id, request)
      : mode === 'refresh_sources' ? this.api.refreshDeepResearchSourcesJob(id, request)
        : mode === 'rebuild_text' ? this.api.rebuildDeepResearchTextJob(id, request)
          : this.api.rebuildDeepResearchSectionJob(id, request);
    await this.run(`deep-${mode}`, call, (job) => {
      this.applyDeepJob(job);
      void this.loadHistory(false);
      this.notice.set(job.status === 'DONE' ? 'Отчёт готов.' : 'Задача запущена в фоне. Статус обновится автоматически.');
    }, 'Не удалось запустить AI-отчёт.');
  }

  async saveReviewIdeas(): Promise<void> {
    const id = this.validCompanyId(); const job = this.deepJob();
    if (id == null || !job?.report) return;
    const ideas = this.lines(this.reviewIdeasText());
    if (!await this.confirm.confirm({ title: 'Сохранить идеи?', message: 'Идеи будут записаны в текущую версию отчёта.', confirmText: 'Сохранить' })) return;
    await this.run('save-ideas', this.api.updateDeepResearchReviewIdeas(id, job.jobId, ideas), (updated) => { this.applyDeepJob(updated); this.notice.set('Идеи сохранены.'); }, 'Идеи не сохранены.');
  }

  async loadLatestPack(): Promise<void> {
    const id = this.validCompanyId(); if (id == null) return;
    await this.run('load-pack', this.api.latestContentPackJob(id), (value) => { this.packJob.set(value); this.notice.set('Последний AI-пакет загружен.'); }, 'AI-пакет для компании пока не найден.');
  }

  async startPack(reportJobId = this.deepJob()?.jobId ?? null): Promise<void> {
    const id = this.validCompanyId(); if (id == null || this.busy()) return;
    if (this.packActive()) { this.error.set('AI-пакет ещё собирается.'); return; }
    if (!await this.confirm.confirm({ title: 'Собрать AI-пакет?', message: 'Создание использует AI и может занять несколько минут.', confirmText: 'Собрать' })) return;
    const research = this.researchRequest();
    await this.run('start-pack', this.api.startContentPackJob(id, {
      manualDescription: research.manualDescription,
      productsOrServices: research.productsOrServices,
      publicUrls: research.publicUrls,
      includeCompanyWebsite: research.includeCompanyWebsite,
      productOrService: this.clean(this.productOrService()),
      adTextsCount: this.positive(this.adTextsCount(), 8),
      socialPostsCount: this.positive(this.socialPostsCount(), 5),
      positiveReplyCount: this.positive(this.positiveReplyCount(), 8),
      negativeReplyCount: this.positive(this.negativeReplyCount(), 6),
      contentPackProfile: this.clean(this.packProfile()),
      deepReportJobId: reportJobId
    }), (value) => { this.packJob.set(value); this.notice.set(value.status === 'DONE' ? 'AI-пакет готов.' : 'AI-пакет запущен в фоне.'); }, 'Не удалось запустить AI-пакет.');
  }

  async improveTemplates(): Promise<void> {
    const id = this.validCompanyId(); if (id == null || !this.packJob()?.pack) { this.error.set('Сначала откройте готовый AI-пакет.'); return; }
    await this.run('improve-templates', this.api.improveReviewTemplates(id, {
      deepReportJobId: this.deepJob()?.jobId ?? null,
      contentPackJobId: this.packJob()?.jobId ?? null,
      manualNotes: this.clean(this.templateNotes()),
      topicsCount: this.positive(this.templateTopicsCount(), 10),
      draftsCount: this.positive(this.templateDraftsCount(), 8),
      tone: this.clean(this.templateTone()),
      contentPackProfile: this.clean(this.packProfile())
    }), (value) => { this.templates.set(value); this.notice.set('Новые темы и черновики готовы.'); }, 'Не удалось улучшить шаблоны.');
  }

  async applyTemplates(): Promise<void> {
    const id = this.validCompanyId(); const result = this.templates();
    if (id == null || !result) return;
    if (!await this.confirm.confirm({ title: 'Заменить шаблоны?', message: 'Темы и черновики текущего AI-пакета будут заменены. Проверьте новые тексты перед сохранением.', confirmText: 'Применить' })) return;
    await this.run('apply-templates', this.api.applyReviewTemplates(id, {
      contentPackJobId: result.contentPackJobId ?? this.packJob()?.jobId ?? null,
      honestReviewTopics: result.honestReviewTopics,
      reviewDraftTemplates: result.reviewDraftTemplates
    }), (pack) => { this.packJob.update((job) => job ? { ...job, pack } : job); this.notice.set('Шаблоны сохранены в AI-пакет.'); }, 'Шаблоны не сохранены.');
  }

  async createSingleDraft(): Promise<void> {
    const id = this.validCompanyId(); if (id == null || !this.packJob()?.pack) { this.error.set('Сначала откройте готовый AI-пакет.'); return; }
    await this.run('single-draft', this.api.createSingleReviewDraft(id, {
      deepReportJobId: this.deepJob()?.jobId ?? null,
      contentPackJobId: this.packJob()?.jobId ?? null,
      idea: this.clean(this.singleIdea()),
      style: this.clean(this.singleStyle()),
      manualNotes: this.clean(this.templateNotes()),
      length: this.clean(this.singleLength()),
      contentPackProfile: this.clean(this.packProfile())
    }), (value) => { this.singleDraft.set(value); this.safety.set(value.safetyReport); this.notice.set('Черновик подготовлен.'); }, 'Не удалось подготовить черновик.');
  }

  async createDraft(): Promise<void> {
    const id = this.validCompanyId(); if (id == null) return;
    await this.run('draft', this.api.createReviewDraft(id, {
      productOrService: this.clean(this.productOrService()), realExperiencePoints: this.lines(this.experienceText()),
      tone: this.clean(this.draftTone()), length: this.clean(this.draftLength())
    }), (value) => { this.draftResult.set(value); this.safety.set(value.safetyReport); this.notice.set('Черновик готов.'); }, 'Черновик не подготовлен.');
  }

  async checkReview(): Promise<void> {
    const text = this.clean(this.checkText()); if (!text) { this.error.set('Введите текст для проверки.'); return; }
    await this.run('check', this.api.checkReview({ text, allowedFacts: this.lines(this.allowedFactsText()) }), (value) => { this.safety.set(value); this.notice.set('Текст проверен.'); }, 'Не удалось проверить текст.');
  }

  async rewriteReview(): Promise<void> {
    const text = this.clean(this.checkText()); if (!text) { this.error.set('Введите текст для улучшения.'); return; }
    await this.run('rewrite', this.api.rewriteReview({ text, tone: this.clean(this.rewriteTone()) }), (value) => { this.rewriteResult.set(value); this.safety.set(value.safetyReport); this.notice.set('Текст переписан.'); }, 'Не удалось переписать текст.');
  }

  async createReply(kind: 'positive' | 'negative'): Promise<void> {
    const id = this.validCompanyId(); if (id == null) return;
    const request = { reviewText: this.clean(this.replyText()), tone: this.clean(this.replyTone()), count: this.positive(this.replyCount()) };
    const call = kind === 'positive' ? this.api.positiveReply(id, request) : this.api.negativeReply(id, request);
    await this.run(`reply-${kind}`, call, (value) => { this.replies.set(value); this.notice.set('Варианты ответов готовы.'); }, 'Не удалось подготовить ответы.');
  }

  async loadPrompts(): Promise<void> {
    try {
      const prompts = await firstValueFrom(this.api.prompts());
      this.prompts.set(prompts);
      const selected = prompts.find((item) => item.key === this.promptKey()) ?? prompts[0];
      if (selected) this.selectPrompt(selected.key);
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось загрузить промпты.')); }
  }

  selectPrompt(key: string): void {
    const prompt = this.prompts().find((item) => item.key === key); if (!prompt) return;
    this.promptKey.set(key); this.promptDraft.set(prompt.content);
    this.promptValidation.set(null); this.promptPreview.set(null);
    void this.loadPromptHistory(key);
  }

  updatePromptDraft(value: string): void { this.promptDraft.set(value); this.promptValidation.set(null); this.promptPreview.set(null); }

  async loadPromptHistory(key = this.promptKey()): Promise<void> {
    if (!key) return;
    try { const history = await firstValueFrom(this.api.promptHistory(key)); if (key === this.promptKey()) this.promptHistory.set(history); }
    catch (error) { if (key === this.promptKey()) this.error.set(this.errorMessage(error, 'История промпта не загружена.')); }
  }

  async validatePrompt(): Promise<void> {
    const prompt = this.selectedPrompt(); if (!prompt) return;
    await this.run('validate-prompt', this.api.validatePrompt(prompt.key, this.promptDraft()), (value) => { this.promptValidation.set(value); this.notice.set(value.valid ? 'Промпт прошёл проверку.' : 'Не хватает обязательных маркеров.'); }, 'Промпт не проверен.');
  }

  async previewPrompt(): Promise<void> {
    const prompt = this.selectedPrompt(); if (!prompt) return;
    await this.run('preview-prompt', this.api.previewPrompt(prompt.key, this.promptDraft()), (value) => { this.promptPreview.set(value); this.notice.set('Пример промпта собран без AI-запроса.'); }, 'Пример промпта не собран.');
  }

  async savePrompt(): Promise<void> {
    const prompt = this.selectedPrompt(); if (!prompt || !this.promptChanged()) return;
    const content = this.promptDraft().trim();
    if (!content || this.missingPlaceholders().length) { this.error.set(`В промпте не хватает обязательных маркеров: ${this.missingPlaceholders().join(', ') || 'текст пуст'}.`); return; }
    if (!await this.confirm.confirm({ title: 'Сохранить промпт?', message: 'Новая версия повлияет на следующие AI-запуски.', confirmText: 'Сохранить' })) return;
    await this.run('save-prompt', this.api.updatePrompt(prompt.key, content), (value) => { this.upsertPrompt(value); this.notice.set('Промпт сохранён.'); }, 'Промпт не сохранён.');
  }

  async resetPrompt(): Promise<void> {
    const prompt = this.selectedPrompt(); if (!prompt?.customized) return;
    if (!await this.confirm.confirm({ title: 'Вернуть исходный промпт?', message: 'Текущая версия будет заменена дефолтной. Предыдущая сохранится в истории.', confirmText: 'Сбросить', danger: true })) return;
    await this.run('reset-prompt', this.api.resetPrompt(prompt.key), (value) => { this.upsertPrompt(value); this.notice.set('Промпт сброшен.'); }, 'Промпт не сброшен.');
  }

  async applyPreset(preset: ReputationAiPromptPreset): Promise<void> {
    const prompt = this.selectedPrompt(); if (!prompt) return;
    if (!await this.confirm.confirm({ title: 'Применить пресет?', message: `«${preset.title}» заменит текущий промпт для следующих запусков.`, confirmText: 'Применить' })) return;
    await this.run('preset-prompt', this.api.applyPromptPreset(prompt.key, preset.key), (value) => { this.upsertPrompt(value); this.notice.set('Пресет применён.'); }, 'Пресет не применён.');
  }

  async copyText(text: string | null | undefined): Promise<void> {
    if (!text?.trim()) return;
    try { await navigator.clipboard.writeText(text); this.notice.set('Текст скопирован.'); }
    catch { this.error.set('Не удалось скопировать текст.'); }
  }

  async copyExport(source: 'report' | 'pack'): Promise<void> {
    const id = this.validCompanyId(); if (id == null || this.busy()) return;
    const call = source === 'report'
      ? this.api.exportDeepResearchMarkdown(id, this.deepJob()?.jobId ?? null)
      : this.api.exportContentPackMarkdown(id);
    await this.run('copy-export', call, (markdown) => { void this.copyText(markdown); }, 'Не удалось получить Markdown.');
  }

  async exportFile(source: 'report' | 'pack', format: 'markdown' | 'pdf'): Promise<void> {
    const id = this.validCompanyId(); if (id == null || this.busy()) return;
    const jobId = this.deepJob()?.jobId ?? null;
    if (source === 'report' && !this.deepJob()?.report || source === 'pack' && !this.packJob()?.pack) { this.error.set('Сначала откройте готовый результат.'); return; }
    const call: Observable<string | Blob> = source === 'report'
      ? format === 'pdf' ? this.api.exportDeepResearchPdf(id, jobId) : this.api.exportDeepResearchMarkdown(id, jobId)
      : format === 'pdf' ? this.api.exportContentPackPdf(id) : this.api.exportContentPackMarkdown(id);
    this.busy.set('export'); this.error.set(null);
    try {
      const result = await firstValueFrom(call);
      const name = `company-${id}-${source === 'report' ? 'deep-report' : 'content-pack'}.${format === 'pdf' ? 'pdf' : 'md'}`;
      const blob = typeof result === 'string' ? new Blob([result], { type: 'text/markdown;charset=utf-8' }) : result;
      if (!blob.size) throw new Error('Экспорт пуст.');
      if (Capacitor.isNativePlatform()) {
        await Filesystem.writeFile({ path: name, data: await this.blobBase64(blob), directory: Directory.Documents });
        this.notice.set(`Файл сохранён в документах приложения: ${name}`);
      } else {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a'); link.href = url; link.download = name; link.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
        this.notice.set(`Файл ${name} подготовлен для скачивания.`);
      }
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось сохранить файл.')); }
    finally { this.busy.set(null); }
  }

  openSource(url: string): void { void this.external.open(safeHttpsExternalUrl(url)); }
  safeSource(url: string): boolean { return !!safeHttpsExternalUrl(url); }
  deepActive(): boolean { return ['QUEUED', 'RUNNING'].includes(this.deepJob()?.status ?? ''); }
  packActive(): boolean { return ['QUEUED', 'RUNNING'].includes(this.packJob()?.status ?? ''); }

  private researchRequest(mode: DeepOperation | null = null, baseReportJobId: number | null = null, sectionTitle: string | null = null, sectionIndex: number | null = null): ReputationResearchRequest {
    return { manualDescription: this.clean(this.manualDescription()), productsOrServices: this.lines(this.productsText()), publicUrls: this.lines(this.publicUrlsText()), includeCompanyWebsite: this.includeWebsite(), deepResearchProfile: this.clean(this.deepProfile()), deepResearchMode: mode, baseReportJobId, sectionTitle, sectionIndex, enrichCollectionGaps: mode ? false : this.autoEnrich() };
  }

  private async run<T>(key: string, call: Observable<T>, onSuccess: (result: T) => void, failure: string): Promise<void> {
    if (this.busy()) return;
    const epoch = this.epoch;
    this.busy.set(key); this.error.set(null);
    try { const result = await firstValueFrom(call); if (epoch === this.epoch) onSuccess(result); }
    catch (error) { if (epoch === this.epoch) this.error.set(this.errorMessage(error, failure)); }
    finally { this.busy.set(null); }
  }

  private applyDeepJob(job: DeepCompanyResearchJob): void {
    this.deepJob.set(job);
    this.reviewIdeasText.set((job.report?.reviewIdeas ?? []).join('\n'));
  }

  private upsertPrompt(prompt: ReputationAiPrompt): void {
    this.prompts.update((current) => current.map((item) => item.key === prompt.key ? prompt : item));
    this.promptKey.set(prompt.key); this.promptDraft.set(prompt.content);
    this.promptValidation.set(null); this.promptPreview.set(null);
    void this.loadPromptHistory(prompt.key);
  }

  private async pollActiveJobs(): Promise<void> {
    const id = this.currentCompanyId(); if (id == null) return;
    const epoch = this.epoch;
    if (this.deepActive() && !this.pollingDeep) {
      this.pollingDeep = true;
      try {
        const job = await firstValueFrom(this.api.latestDeepResearchJob(id));
        if (epoch === this.epoch && job.companyId === id) { this.applyDeepJob(job); if (job.status === 'DONE') void this.loadHistory(false); }
      } catch { /* Keep the current job visible; manual refresh remains available. */ }
      finally { this.pollingDeep = false; }
    }
    if (this.packActive() && !this.pollingPack) {
      this.pollingPack = true;
      try { const job = await firstValueFrom(this.api.latestContentPackJob(id)); if (epoch === this.epoch && job.companyId === id) this.packJob.set(job); }
      catch { /* Keep the current job visible; manual refresh remains available. */ }
      finally { this.pollingPack = false; }
    }
  }

  private lines(value: string): string[] { return value.split(/\r?\n|;/).map((item) => item.trim()).filter(Boolean); }
  private currentCompanyId(): number | null {
    const raw = this.companyId().trim();
    const id = Number(raw);
    return /^[1-9]\d*$/.test(raw) && Number.isSafeInteger(id) ? id : null;
  }
  private clean(value: string): string | null { return value.trim() || null; }
  private positive(value: number, max?: number): number | null { const normalized = Math.trunc(Number(value)); return Number.isFinite(normalized) && normalized > 0 ? max ? Math.min(normalized, max) : normalized : null; }

  private blobBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(reader.error);
      reader.onload = () => resolve(String(reader.result ?? '').split(',')[1] ?? '');
      reader.readAsDataURL(blob);
    });
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { detail?: string; message?: string; error?: string } | string }).error;
      if (typeof body === 'string') return body || fallback;
      return body?.detail || body?.message || body?.error || fallback;
    }
    return error instanceof Error ? error.message : fallback;
  }
}
