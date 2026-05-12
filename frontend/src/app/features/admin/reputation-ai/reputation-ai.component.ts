import { DatePipe } from '@angular/common';
import { Component, OnDestroy, computed, effect, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { ReputationDeepReportMonitorService } from '../../../core/reputation-deep-report-monitor.service';
import { ReputationAiApi } from '../../../core/reputation-ai.api';
import type {
  DeepCompanyResearchJob,
  DeepCompanyResearchReport,
  ReputationContentPackJob,
  ReputationContentPack,
  ReputationAiStatus,
  ReputationAiModelProfile,
  ReputationReviewReplyResponse,
  ReputationReviewRewriteResponse,
  ReviewDraftResult,
  ReviewSafetyReport
} from '../../../core/reputation-ai.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorDetail } from '../../../shared/api-error-message';

type ReputationAction =
  | 'deepResearch'
  | 'contentPack'
  | 'draft'
  | 'check'
  | 'rewrite'
  | 'positiveReply'
  | 'negativeReply';

type PackBlock = {
  key: string;
  title: string;
  icon: string;
  items: string[];
};

type ReportMetric = {
  label: string;
  value: string | number;
  icon: string;
  tone: string;
};

type DeepReportBlock = {
  key: string;
  title: string;
  icon: string;
  body: string;
  html: string;
};

@Component({
  selector: 'app-reputation-ai',
  imports: [AdminLayoutComponent, DatePipe, FormsModule],
  templateUrl: './reputation-ai.component.html',
  styleUrl: './reputation-ai.component.scss'
})
export class ReputationAiComponent implements OnDestroy {
  companyId = 1;
  productOrService = '';
  manualDescription = '';
  productsOrServicesText = '';
  publicUrlsText = '';
  includeCompanyWebsite = true;
  readonly deepResearchProfile = signal('economy');
  readonly contentPackProfile = signal('quality');
  adTextsCount = 6;
  socialPostsCount = 4;
  positiveReplyCount = 6;
  negativeReplyCount = 4;
  realExperiencePointsText = 'помогли с выбором\nбыстро доставили\nтовар подошел';
  draftTone = 'естественный';
  draftLength = 'medium';
  checkText = '';
  allowedFactsText = '';
  rewriteTone = 'естественный';
  replyReviewText = '';
  replyTone = 'деловой';
  replyCount = 3;

  readonly deepResearchJob = signal<DeepCompanyResearchJob | null>(null);
  readonly deepResearch = signal<DeepCompanyResearchReport | null>(null);
  readonly status = signal<ReputationAiStatus | null>(null);
  readonly contentPack = signal<ReputationContentPack | null>(null);
  readonly contentPackJob = signal<ReputationContentPackJob | null>(null);
  readonly draftResult = signal<ReviewDraftResult | null>(null);
  readonly checkReport = signal<ReviewSafetyReport | null>(null);
  readonly rewriteResult = signal<ReputationReviewRewriteResponse | null>(null);
  readonly replyResult = signal<ReputationReviewReplyResponse | null>(null);
  readonly loadingAction = signal<ReputationAction | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  private contentPackPollSubscription: Subscription | null = null;

  readonly busy = computed(() => this.loadingAction() !== null);
  readonly deepResearchProfiles = computed<ReputationAiModelProfile[]>(() => {
    const profiles = this.status()?.openAiResearchReportProfiles ?? [];
    return profiles.length > 0 ? profiles : this.fallbackDeepResearchProfiles();
  });
  readonly selectedDeepResearchProfile = computed(() => {
    const profiles = this.deepResearchProfiles();
    return profiles.find((profile) => profile.key === this.deepResearchProfile()) ?? profiles[0] ?? null;
  });
  readonly contentPackProfiles = computed<ReputationAiModelProfile[]>(() => {
    const profiles = this.status()?.openAiContentPackProfiles ?? [];
    return profiles.length > 0 ? profiles : this.fallbackContentPackProfiles();
  });
  readonly selectedContentPackProfile = computed(() => {
    const profiles = this.contentPackProfiles();
    return profiles.find((profile) => profile.key === this.contentPackProfile()) ?? profiles[0] ?? null;
  });
  readonly reportPassportMetrics = computed<ReportMetric[]>(() => {
    const report = this.deepResearch();
    const job = this.deepResearchJob();
    const selectedProfile = this.selectedDeepResearchProfile();

    return [
      { label: 'Статус', value: this.deepResearchStatusLabel(job), icon: this.reportStatusIcon(job), tone: this.reportStatusTone(job) },
      { label: 'Источники', value: report?.sources.length ?? 0, icon: 'travel_explore', tone: 'blue' },
      { label: 'Разделы', value: this.deepReportBlocks().length, icon: 'dashboard', tone: 'green' },
      { label: 'Риски', value: report?.warnings.length ?? 0, icon: 'warning', tone: (report?.warnings.length ?? 0) > 0 ? 'yellow' : 'green' },
      { label: 'Модель', value: report?.model || job?.model || selectedProfile?.model || this.status()?.openAiResearchReportModel || '-', icon: 'memory', tone: 'blue' }
    ];
  });
  readonly deepReportBlocks = computed<DeepReportBlock[]>(() => {
    const report = this.deepResearch();
    if (!report) {
      return [];
    }

    const sections = (report.sections ?? [])
      .filter((section) => Boolean(section.title?.trim() || section.body?.trim()))
      .map((section) => ({
        title: section.title?.trim() || 'Раздел отчёта',
        body: section.body?.trim() || ''
      }));

    const blocks = sections.length > 0
      ? sections
      : this.splitMarkdownIntoBlocks(report.reportMarkdown);

    return blocks.map((section, index) => ({
      key: `${index}-${section.title}`,
      title: section.title,
      icon: this.topicIcon(section.title),
      body: section.body,
      html: this.markdownToHtml(section.body)
    }));
  });
  readonly packBlocks = computed<PackBlock[]>(() => {
    const pack = this.contentPack();
    if (!pack) {
      return [];
    }

    return [
      { key: 'utp', title: 'УТП', icon: 'workspace_premium', items: pack.utp },
      { key: 'adTexts', title: 'Рекламные тексты', icon: 'campaign', items: pack.adTexts },
      { key: 'socialPostTopics', title: 'Темы постов', icon: 'forum', items: pack.socialPostTopics },
      { key: 'socialPosts', title: 'Посты / статьи', icon: 'edit_note', items: pack.socialPosts },
      { key: 'honestReviewTopics', title: 'Темы честного отзыва', icon: 'fact_check', items: pack.honestReviewTopics },
      { key: 'reviewDraftTemplates', title: 'Черновики честного отзыва', icon: 'rate_review', items: pack.reviewDraftTemplates },
      { key: 'positiveReviewReplies', title: 'Ответы на положительные отзывы', icon: 'thumb_up', items: pack.positiveReviewReplies },
      { key: 'negativeReviewReplies', title: 'Ответы на негативные отзывы', icon: 'support_agent', items: pack.negativeReviewReplies },
      { key: 'safetyNotes', title: 'Проверки и ограничения', icon: 'policy', items: pack.safetyNotes }
    ].filter((block) => block.items.length > 0);
  });

  constructor(
    private readonly reputationAiApi: ReputationAiApi,
    private readonly deepReportMonitor: ReputationDeepReportMonitorService
  ) {
    this.loadStatus();
    this.deepReportMonitor.restore();
    effect(() => {
      const job = this.deepReportMonitor.currentJob();
      queueMicrotask(() => this.applyDeepResearchJob(job));
    });
  }

  loadStatus(): void {
    this.reputationAiApi.status().subscribe({
      next: (status) => this.status.set(status),
      error: () => this.status.set(null)
    });
  }

  createDeepResearch(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('deepResearch');
    this.reputationAiApi.startDeepResearchJob(companyId, this.researchRequest()).subscribe({
      next: (job) => {
        this.applyDeepResearchJob(job);
        this.deepReportMonitor.watch(companyId);
        this.finish(this.isActiveDeepResearchJob(job)
          ? 'Глубокий GPT-отчет запущен в фоне. Можно перейти в другой раздел.'
          : 'Глубокий GPT-отчет уже готов.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось запустить глубокий GPT-отчет.')
    });
  }

  loadLatestDeepResearch(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('deepResearch');
    this.reputationAiApi.latestDeepResearchJob(companyId).subscribe({
      next: (job) => {
        this.applyDeepResearchJob(job);
        if (this.isActiveDeepResearchJob(job)) {
          this.deepReportMonitor.watch(companyId);
          this.finish('Глубокий GPT-отчет ещё собирается.');
        } else if (job.report) {
          this.finish('Последний глубокий GPT-отчет загружен из базы.');
        } else {
          this.finish('Статус глубокого GPT-отчета загружен.');
        }
      },
      error: (error: unknown) => this.fail(error, 'Глубокий GPT-отчет для этой компании пока не найден.')
    });
  }

  createContentPack(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('contentPack');
    this.reputationAiApi.startContentPackJob(companyId, {
      ...this.researchRequest(),
      productOrService: this.cleanText(this.productOrService),
      adTextsCount: this.positiveOrNull(this.adTextsCount, 8),
      socialPostsCount: this.positiveOrNull(this.socialPostsCount, 5),
      positiveReplyCount: this.positiveOrNull(this.positiveReplyCount, 8),
      negativeReplyCount: this.positiveOrNull(this.negativeReplyCount, 6),
      contentPackProfile: this.cleanText(this.contentPackProfile())
    }).subscribe({
      next: (job) => {
        this.applyContentPackJob(job);
        if (this.isActiveContentPackJob(job)) {
          this.watchContentPackJob(companyId);
          this.finish('AI-пакет запущен в фоне. Результат появится автоматически.');
        } else if (job.pack) {
          this.finish('AI-пакет компании готов.');
        } else {
          this.finish('Статус AI-пакета обновлен.');
        }
      },
      error: (error: unknown) => this.fail(error, 'Не удалось подготовить AI-пакет компании.')
    });
  }

  ngOnDestroy(): void {
    this.stopContentPackPolling();
  }

  createDraft(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('draft');
    this.reputationAiApi.createReviewDraft(companyId, {
      productOrService: this.cleanText(this.productOrService),
      realExperiencePoints: this.lines(this.realExperiencePointsText),
      tone: this.cleanText(this.draftTone),
      length: this.cleanText(this.draftLength)
    }).subscribe({
      next: (result) => {
        this.draftResult.set(result);
        this.checkReport.set(result.safetyReport);
        this.finish('Черновик отзыва готов.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось подготовить черновик отзыва.')
    });
  }

  checkReview(): void {
    const text = this.cleanText(this.checkText);
    if (!text) {
      this.error.set('Вставь текст для проверки.');
      return;
    }

    this.start('check');
    this.reputationAiApi.checkReview({
      text,
      allowedFacts: this.lines(this.allowedFactsText)
    }).subscribe({
      next: (report) => {
        this.checkReport.set(report);
        this.finish('Текст проверен.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось проверить текст.')
    });
  }

  rewriteReview(): void {
    const text = this.cleanText(this.checkText);
    if (!text) {
      this.error.set('Вставь текст для улучшения.');
      return;
    }

    this.start('rewrite');
    this.reputationAiApi.rewriteReview({
      text,
      tone: this.cleanText(this.rewriteTone)
    }).subscribe({
      next: (result) => {
        this.rewriteResult.set(result);
        this.checkReport.set(result.safetyReport);
        this.finish('Текст аккуратно переписан.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось улучшить текст.')
    });
  }

  createReply(kind: 'positive' | 'negative'): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    const action: ReputationAction = kind === 'positive' ? 'positiveReply' : 'negativeReply';
    this.start(action);
    const request = {
      reviewText: this.cleanText(this.replyReviewText),
      tone: this.cleanText(this.replyTone),
      count: this.positiveOrNull(this.replyCount)
    };
    const call = kind === 'positive'
      ? this.reputationAiApi.positiveReply(companyId, request)
      : this.reputationAiApi.negativeReply(companyId, request);

    call.subscribe({
      next: (result) => {
        this.replyResult.set(result);
        this.finish(kind === 'positive' ? 'Ответы на положительный отзыв готовы.' : 'Ответы на негативный отзыв готовы.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось подготовить ответы на отзыв.')
    });
  }

  copyText(text: string): void {
    if (!text.trim()) {
      return;
    }

    void navigator.clipboard?.writeText(text);
    this.notice.set('Текст скопирован.');
  }

  contentPackText(pack: ReputationContentPack): string {
    const lines: string[] = [
      'AI-пакет компании',
      pack.companyProfile.shortDescription,
      ''
    ];

    const warnings = pack.companyProfile.factualWarnings ?? [];
    if (warnings.length > 0) {
      lines.push('Предупреждения');
      warnings.forEach((warning) => lines.push(`- ${warning}`));
      lines.push('');
    }

    for (const block of this.packBlocks()) {
      lines.push(block.title);
      block.items.forEach((item, index) => {
        lines.push(`${index + 1}. ${item}`);
      });
      lines.push('');
    }

    return lines.join('\n').trim();
  }

  isLoading(action: ReputationAction): boolean {
    return this.loadingAction() === action;
  }

  hasActiveDeepResearch(): boolean {
    const job = this.deepResearchJob();
    return Boolean(job && Number(job.companyId) === Number(this.companyId) && this.isActiveDeepResearchJob(job));
  }

  hasActiveContentPackJob(): boolean {
    const job = this.contentPackJob();
    return Boolean(job && Number(job.companyId) === Number(this.companyId) && this.isActiveContentPackJob(job));
  }

  downloadDeepReport(report: DeepCompanyResearchReport): void {
    const markdown = report.reportMarkdown?.trim();
    if (!markdown) {
      return;
    }

    const fileName = `${this.slugify(report.companyName || `company-${report.companyId}`)}-deep-report.md`;
    const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
    this.notice.set('Markdown-отчёт скачан.');
  }

  trackMetric(_index: number, metric: ReportMetric): string {
    return metric.label;
  }

  trackBlock(_index: number, block: PackBlock): string {
    return block.key;
  }

  trackProfile(_index: number, profile: ReputationAiModelProfile): string {
    return profile.key;
  }

  trackText(index: number, text: string): string {
    return `${index}-${text}`;
  }

  trackSource(index: number, source: { url: string; title: string }): string {
    return source.url || `${index}-${source.title}`;
  }

  trackDeepBlock(_index: number, block: DeepReportBlock): string {
    return block.key;
  }

  safetyTone(report: ReviewSafetyReport | null): string {
    if (!report) {
      return 'gray';
    }

    if (report.safeToUseAsDraft && report.riskScore < 35) {
      return 'green';
    }

    return report.riskScore > 70 ? 'red' : 'yellow';
  }

  deepResearchStatusLabel(job: DeepCompanyResearchJob | null): string {
    if (!job) {
      return 'не запускался';
    }

    return {
      QUEUED: 'в очереди',
      RUNNING: 'собирается',
      DONE: 'готов',
      FAILED: 'ошибка'
    }[job.status] ?? job.status.toLowerCase();
  }

  deepResearchJobError(job: DeepCompanyResearchJob): string {
    return this.humanizeAiError(job.errorMessage, 'Глубокий GPT-отчёт завершился ошибкой.');
  }

  contentPackJobError(job: ReputationContentPackJob): string {
    return this.humanizeAiError(job.errorMessage, 'AI-пакет завершился ошибкой.');
  }

  private reportStatusIcon(job: DeepCompanyResearchJob | null): string {
    if (!job) {
      return 'radio_button_unchecked';
    }

    return {
      QUEUED: 'schedule',
      RUNNING: 'hourglass_top',
      DONE: 'check_circle',
      FAILED: 'error'
    }[job.status] ?? 'info';
  }

  private reportStatusTone(job: DeepCompanyResearchJob | null): string {
    if (!job) {
      return 'gray';
    }
    if (job.status === 'DONE') {
      return 'green';
    }
    if (job.status === 'FAILED') {
      return 'red';
    }
    return 'blue';
  }

  private applyDeepResearchJob(job: DeepCompanyResearchJob | null): void {
    if (!job || Number(job.companyId) !== Number(this.companyId)) {
      return;
    }

    this.deepResearchJob.set(job);
    if (job.report) {
      this.deepResearch.set(job.report);
    }
  }

  private applyContentPackJob(job: ReputationContentPackJob | null): void {
    if (!job || Number(job.companyId) !== Number(this.companyId)) {
      return;
    }

    this.contentPackJob.set(job);
    if (job.pack) {
      this.contentPack.set(job.pack);
    }
  }

  private isActiveDeepResearchJob(job: DeepCompanyResearchJob | null): boolean {
    return Boolean(job && (job.status === 'QUEUED' || job.status === 'RUNNING'));
  }

  private isActiveContentPackJob(job: ReputationContentPackJob | null): boolean {
    return Boolean(job && (job.status === 'QUEUED' || job.status === 'RUNNING'));
  }

  private watchContentPackJob(companyId: number): void {
    this.stopContentPackPolling();
    this.contentPackPollSubscription = timer(0, 15_000)
      .pipe(switchMap(() => this.reputationAiApi.latestContentPackJob(companyId)))
      .subscribe({
        next: (job) => this.handleContentPackJob(job),
        error: () => this.stopContentPackPolling()
      });
  }

  private handleContentPackJob(job: ReputationContentPackJob): void {
    this.applyContentPackJob(job);
    if (job.status === 'DONE') {
      this.stopContentPackPolling();
      this.notice.set('AI-пакет компании готов.');
      if (this.loadingAction() === 'contentPack') {
        this.loadingAction.set(null);
      }
      return;
    }

    if (job.status === 'FAILED') {
      this.stopContentPackPolling();
      this.error.set(this.contentPackJobError(job));
      if (this.loadingAction() === 'contentPack') {
        this.loadingAction.set(null);
      }
    }
  }

  private stopContentPackPolling(): void {
    this.contentPackPollSubscription?.unsubscribe();
    this.contentPackPollSubscription = null;
  }

  private researchRequest() {
    return {
      manualDescription: this.cleanText(this.manualDescription),
      productsOrServices: this.lines(this.productsOrServicesText),
      publicUrls: this.lines(this.publicUrlsText),
      includeCompanyWebsite: this.includeCompanyWebsite,
      deepResearchProfile: this.cleanText(this.deepResearchProfile())
    };
  }

  private validCompanyId(): number | null {
    const id = Number(this.companyId);
    if (!Number.isFinite(id) || id <= 0) {
      this.error.set('Укажи корректный ID компании.');
      return null;
    }

    return Math.trunc(id);
  }

  private lines(value: string): string[] {
    return value
      .split(/\r?\n|;/)
      .map((item) => item.trim())
      .filter(Boolean);
  }

  private cleanText(value: string): string | null {
    const text = value.trim();
    return text ? text : null;
  }

  private positiveOrNull(value: number, max?: number): number | null {
    const number = Number(value);
    if (!Number.isFinite(number) || number <= 0) {
      return null;
    }
    const normalized = Math.trunc(number);
    return max ? Math.min(normalized, max) : normalized;
  }

  private splitMarkdownIntoBlocks(markdown: string): { title: string; body: string }[] {
    const text = markdown?.trim();
    if (!text) {
      return [];
    }

    const blocks: { title: string; body: string }[] = [];
    const matches = [...text.matchAll(/^#{1,3}\s+(.+)$/gm)];
    if (matches.length === 0) {
      return [{ title: 'Глубокий отчёт', body: text }];
    }

    for (let index = 0; index < matches.length; index++) {
      const match = matches[index];
      const title = (match[1] ?? 'Раздел отчёта').trim();
      const start = (match.index ?? 0) + match[0].length;
      const end = matches[index + 1]?.index ?? text.length;
      const body = text.slice(start, end).trim();
      blocks.push({ title, body });
    }

    return blocks.filter((block) => block.title || block.body);
  }

  private topicIcon(title: string): string {
    const value = title.toLowerCase();
    if (/(свод|крат|вывод|позиционир)/.test(value)) {
      return 'summarize';
    }
    if (/(цен|пакет|услуг|товар|предлож)/.test(value)) {
      return 'sell';
    }
    if (/(филиал|адрес|локац|ехать|логист)/.test(value)) {
      return 'location_on';
    }
    if (/(интерьер|экстерьер|фасад|вход|вывеск|атмосфер|парков|удобств|ожидан)/.test(value)) {
      return 'weekend';
    }
    if (/(сотруд|команд|персонал|опыт)/.test(value)) {
      return 'groups';
    }
    if (/(репутац|отзыв|хвал|жалоб|критик)/.test(value)) {
      return 'reviews';
    }
    if (/(довер|доказат|сертифик|лиценз|юрид|портфолио|кейс)/.test(value)) {
      return 'verified';
    }
    if (/(сценари|утп|возраж|контент|тем)/.test(value)) {
      return 'psychology_alt';
    }
    if (/(акци|скид|брон|заказ|запис|возврат|гарант)/.test(value)) {
      return 'assignment_turned_in';
    }
    if (/(источник|риск|уточн|противореч)/.test(value)) {
      return 'manage_search';
    }
    if (/(аудитор|возраст|кому|подходит)/.test(value)) {
      return 'family_restroom';
    }
    return 'article';
  }

  private slugify(value: string): string {
    const ascii = value
      .trim()
      .toLowerCase()
      .replace(/[^a-zа-я0-9]+/gi, '-')
      .replace(/^-+|-+$/g, '');
    return ascii || 'deep-report';
  }

  private fallbackDeepResearchProfiles(): ReputationAiModelProfile[] {
    return [
      {
        key: 'economy',
        label: 'Эконом',
        model: 'gpt-5.4-mini',
        description: 'Быстрее и дешевле для локальных проверок.',
        maxToolCalls: 6,
        maxOutputTokens: 6000,
        reasoningEffort: 'low',
        searchContextSize: 'low'
      },
      {
        key: 'quality',
        label: 'Качество',
        model: 'gpt-5.5',
        description: 'Основной режим для хорошего отчёта.',
        maxToolCalls: 16,
        maxOutputTokens: 12000,
        reasoningEffort: 'low',
        searchContextSize: 'low'
      },
      {
        key: 'maximum',
        label: 'Максимум',
        model: 'gpt-5.5',
        description: 'Устойчивый режим 5.5 с усиленным reasoning без чрезмерного расхода TPM.',
        maxToolCalls: 20,
        maxOutputTokens: 14000,
        reasoningEffort: 'medium',
        searchContextSize: 'low'
      }
    ];
  }

  private humanizeAiError(message: string | null | undefined, fallback: string): string {
    const text = (message ?? '').trim();
    if (!text) {
      return fallback;
    }

    const lower = text.toLowerCase();
    if (lower.includes('rate limit reached')
      || lower.includes('tokens per min')
      || lower.includes('rate_limit_exceeded')
      || lower.includes('лимит токен')) {
      const retry = text.match(/try again in ([0-9.]+)s/i)?.[1];
      const retryHint = retry ? ` API просит повторить примерно через ${retry} с.` : '';
      return `OpenAI временно упёрся в лимит токенов в минуту.${retryHint} Подождите 1-2 минуты и запустите отчёт снова.`;
    }

    return text;
  }

  private fallbackContentPackProfiles(): ReputationAiModelProfile[] {
    return [
      {
        key: 'economy',
        label: 'Эконом',
        model: 'gpt-5.4-mini',
        description: 'Дешевле для быстрых вариантов пакета по готовому отчёту.',
        maxToolCalls: 0,
        maxOutputTokens: 10000,
        reasoningEffort: 'low',
        searchContextSize: 'off'
      },
      {
        key: 'quality',
        label: 'Качество',
        model: 'gpt-5.5',
        description: 'Основной режим: сильный маркетинговый пакет без web search.',
        maxToolCalls: 0,
        maxOutputTokens: 18000,
        reasoningEffort: 'low',
        searchContextSize: 'off'
      },
      {
        key: 'maximum',
        label: 'Максимум',
        model: 'gpt-5.5',
        description: 'Самый подробный маркетинговый пакет по глубокому отчёту.',
        maxToolCalls: 0,
        maxOutputTokens: 26000,
        reasoningEffort: 'medium',
        searchContextSize: 'off'
      }
    ];
  }

  private markdownToHtml(markdown: string): string {
    const lines = markdown.replace(/\r\n/g, '\n').split('\n');
    const blocks: string[] = [];
    let index = 0;

    while (index < lines.length) {
      const line = lines[index] ?? '';
      const trimmed = line.trim();

      if (!trimmed) {
        index++;
        continue;
      }

      if (trimmed.startsWith('```')) {
        const codeLines: string[] = [];
        index++;
        while (index < lines.length && !(lines[index] ?? '').trim().startsWith('```')) {
          codeLines.push(lines[index] ?? '');
          index++;
        }
        index++;
        blocks.push(`<pre><code>${this.escapeHtml(codeLines.join('\n'))}</code></pre>`);
        continue;
      }

      const heading = /^(#{1,4})\s+(.+)$/.exec(trimmed);
      if (heading) {
        const level = Math.min(heading[1].length + 1, 5);
        blocks.push(`<h${level}>${this.renderInline(heading[2])}</h${level}>`);
        index++;
        continue;
      }

      if (this.isTableStart(lines, index)) {
        const rows: string[][] = [];
        rows.push(this.splitTableRow(lines[index]));
        index += 2;
        while (index < lines.length && this.splitTableRow(lines[index]).length > 1) {
          rows.push(this.splitTableRow(lines[index]));
          index++;
        }
        blocks.push(this.renderTable(rows));
        continue;
      }

      if (/^[-*]\s+/.test(trimmed)) {
        const items: string[] = [];
        while (index < lines.length && /^[-*]\s+/.test((lines[index] ?? '').trim())) {
          items.push(`<li>${this.renderInline((lines[index] ?? '').trim().replace(/^[-*]\s+/, ''))}</li>`);
          index++;
        }
        blocks.push(`<ul>${items.join('')}</ul>`);
        continue;
      }

      if (/^\d+\.\s+/.test(trimmed)) {
        const items: string[] = [];
        while (index < lines.length && /^\d+\.\s+/.test((lines[index] ?? '').trim())) {
          items.push(`<li>${this.renderInline((lines[index] ?? '').trim().replace(/^\d+\.\s+/, ''))}</li>`);
          index++;
        }
        blocks.push(`<ol>${items.join('')}</ol>`);
        continue;
      }

      const paragraph: string[] = [];
      while (index < lines.length) {
        const next = lines[index] ?? '';
        const nextTrimmed = next.trim();
        if (!nextTrimmed || /^(#{1,4})\s+/.test(nextTrimmed) || /^[-*]\s+/.test(nextTrimmed) || /^\d+\.\s+/.test(nextTrimmed) || this.isTableStart(lines, index)) {
          break;
        }
        paragraph.push(nextTrimmed);
        index++;
      }
      blocks.push(`<p>${this.renderInline(paragraph.join(' '))}</p>`);
    }

    return blocks.join('');
  }

  private isTableStart(lines: string[], index: number): boolean {
    const header = lines[index] ?? '';
    const separator = lines[index + 1] ?? '';
    if (!header.includes('|') || !separator.includes('|')) {
      return false;
    }

    const cells = this.splitTableRow(separator);
    return cells.length > 1 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.trim()));
  }

  private splitTableRow(row: string): string[] {
    const trimmed = row.trim().replace(/^\|/, '').replace(/\|$/, '');
    return trimmed.split('|').map((cell) => cell.trim()).filter((cell) => cell.length > 0);
  }

  private renderTable(rows: string[][]): string {
    const [header, ...body] = rows;
    const head = `<thead><tr>${header.map((cell) => `<th>${this.renderInline(cell)}</th>`).join('')}</tr></thead>`;
    const bodyRows = body
      .map((row) => `<tr>${row.map((cell) => `<td>${this.renderInline(cell)}</td>`).join('')}</tr>`)
      .join('');
    return `<table>${head}<tbody>${bodyRows}</tbody></table>`;
  }

  private renderInline(value: string): string {
    let text = this.escapeHtml(value);
    text = text.replace(/`([^`]+)`/g, '<code>$1</code>');
    text = text.replace(/\[([^\]]+)]\((https?:\/\/[^)\s]+)\)/g, (_match, label: string, url: string) => {
      const safeUrl = this.escapeAttribute(url);
      return `<a href="${safeUrl}" target="_blank" rel="noreferrer">${label}</a>`;
    });
    text = text.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    text = text.replace(/__([^_]+)__/g, '<strong>$1</strong>');
    return text;
  }

  private escapeHtml(value: string): string {
    return value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  private escapeAttribute(value: string): string {
    return this.escapeHtml(value).replace(/`/g, '&#96;');
  }

  private start(action: ReputationAction): void {
    this.loadingAction.set(action);
    this.error.set(null);
    this.notice.set(null);
  }

  private finish(message: string): void {
    this.loadingAction.set(null);
    this.notice.set(message);
  }

  private fail(error: unknown, fallback: string): void {
    this.loadingAction.set(null);
    this.error.set(apiErrorDetail(error, fallback));
  }
}
