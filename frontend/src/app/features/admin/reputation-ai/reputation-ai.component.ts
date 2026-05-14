import { DatePipe } from '@angular/common';
import { Component, OnDestroy, computed, effect, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { ReputationDeepReportMonitorService } from '../../../core/reputation-deep-report-monitor.service';
import { ReputationAiApi } from '../../../core/reputation-ai.api';
import type {
  CompanySource,
  DeepCompanyResearchJob,
  DeepCompanyResearchReport,
  DeepResearchFactItem,
  DeepResearchQualityCheck,
  DeepResearchSource,
  DeepResearchSourceReview,
  ResearchSnapshot,
  ReputationContentPackJob,
  ReputationContentPack,
  ReputationAiStatus,
  ReputationAiModelProfile,
  ReputationAiPrompt,
  ReputationAiPromptPreview,
  ReputationAiPromptPreset,
  ReputationAiPromptValidation,
  ReputationAiPromptVersion,
  OpenAiProviderDiagnostics,
  ReputationReviewReplyResponse,
  ReputationSingleReviewDraftResult,
  ReputationReviewTemplatesResult,
  ReputationReviewRewriteResponse,
  ReviewDraftResult,
  ReviewSafetyReport
} from '../../../core/reputation-ai.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorDetail } from '../../../shared/api-error-message';
import { UiTooltipDirective } from '../../../shared/ui-tooltip.directive';

type ReputationAction =
  | 'deepResearch'
  | 'researchSnapshot'
  | 'refreshSources'
  | 'rebuildText'
  | 'rebuildSection'
  | 'contentPack'
  | 'loadContentPack'
  | 'reviewTemplates'
  | 'applyReviewTemplates'
  | 'singleReviewDraft'
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

type ReportQualitySummary = {
  passed: number;
  total: number;
  label: string;
  tone: string;
  icon: string;
};

type ReportQualityScore = {
  passed: number;
  total: number;
  percent: number;
  label: string;
};

type ReportChangeItem = {
  key: string;
  icon: string;
  label: string;
  detail: string;
  tone: string;
};

type ReportSectionChange = {
  key: string;
  title: string;
  status: 'added' | 'removed' | 'changed';
};

type GapEnrichmentStatus = {
  label: string;
  detail: string;
  icon: string;
  tone: 'green' | 'yellow' | 'red' | 'gray' | 'blue';
};

type HistoryStatusFilter = 'all' | 'DONE' | 'FAILED' | 'RUNNING' | 'QUEUED';

type HistoryOperationFilter = 'all' | 'full_report' | 'refresh_sources' | 'rebuild_text' | 'rebuild_section';

type WorkflowStep = {
  icon: string;
  title: string;
  text: string;
};

type SnapshotMetric = {
  label: string;
  value: string | number;
};

type SnapshotSourceGroup = {
  key: string;
  label: string;
  sources: CompanySource[];
};

type PromptDiffLine = {
  key: string;
  status: 'same' | 'changed' | 'added' | 'removed';
  lineNumber: number;
  defaultText: string;
  draftText: string;
};

type ReportComparison = {
  baseJob: DeepCompanyResearchJob;
  targetJob: DeepCompanyResearchJob;
  baseQuality: ReportQualityScore;
  targetQuality: ReportQualityScore;
  sourceAdded: DeepResearchSource[];
  sourceRemoved: DeepResearchSource[];
  sectionChanges: ReportSectionChange[];
  warningsAdded: string[];
  warningsResolved: string[];
  summaryItems: ReportChangeItem[];
  hasChanges: boolean;
};

@Component({
  selector: 'app-reputation-ai',
  imports: [AdminLayoutComponent, DatePipe, FormsModule, UiTooltipDirective],
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
  autoEnrichCollectionGaps = false;
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
  reviewTemplateNotes = '';
  reviewTemplateTopicsCount = 7;
  reviewTemplateDraftsCount = 5;
  reviewTemplateTone = 'естественный, рекламно-полезный, без фейкового опыта';
  singleReviewIdea = '';
  singleReviewStyle = 'живой, спокойный, с мягкой рекламной пользой';
  singleReviewLength = 'medium';

  readonly deepResearchJob = signal<DeepCompanyResearchJob | null>(null);
  readonly deepResearch = signal<DeepCompanyResearchReport | null>(null);
  readonly deepResearchHistory = signal<DeepCompanyResearchJob[]>([]);
  readonly selectedHistoryJob = signal<DeepCompanyResearchJob | null>(null);
  readonly historyStatusFilter = signal<HistoryStatusFilter>('all');
  readonly historyOperationFilter = signal<HistoryOperationFilter>('all');
  readonly historySearchText = signal('');
  readonly comparisonBaseJob = signal<DeepCompanyResearchJob | null>(null);
  readonly researchSnapshot = signal<ResearchSnapshot | null>(null);
  readonly status = signal<ReputationAiStatus | null>(null);
  readonly prompts = signal<ReputationAiPrompt[]>([]);
  readonly selectedPromptKey = signal('');
  readonly promptDraft = signal('');
  readonly promptPreview = signal<ReputationAiPromptPreview | null>(null);
  readonly promptValidation = signal<ReputationAiPromptValidation | null>(null);
  readonly promptHistory = signal<ReputationAiPromptVersion[]>([]);
  readonly previewingPrompt = signal(false);
  readonly validatingPrompt = signal(false);
  readonly applyingPromptPreset = signal<string | null>(null);
  readonly savingPrompt = signal(false);
  readonly contentPack = signal<ReputationContentPack | null>(null);
  readonly contentPackJob = signal<ReputationContentPackJob | null>(null);
  readonly reviewTemplatesResult = signal<ReputationReviewTemplatesResult | null>(null);
  readonly singleReviewDraftResult = signal<ReputationSingleReviewDraftResult | null>(null);
  readonly draftResult = signal<ReviewDraftResult | null>(null);
  readonly checkReport = signal<ReviewSafetyReport | null>(null);
  readonly rewriteResult = signal<ReputationReviewRewriteResponse | null>(null);
  readonly replyResult = signal<ReputationReviewReplyResponse | null>(null);
  readonly loadingAction = signal<ReputationAction | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly checkingOpenAiRoute = signal(false);
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
  readonly selectedPrompt = computed<ReputationAiPrompt | null>(() => {
    const key = this.selectedPromptKey();
    return this.prompts().find((prompt) => prompt.key === key) ?? this.prompts()[0] ?? null;
  });
  readonly promptChanged = computed(() => {
    const prompt = this.selectedPrompt();
    return Boolean(prompt && this.promptDraft().trim() !== prompt.content.trim());
  });
  readonly promptMissingPlaceholders = computed<string[]>(() => {
    const prompt = this.selectedPrompt();
    if (!prompt) {
      return [];
    }
    const draft = this.promptDraft();
    return prompt.requiredPlaceholders.filter((placeholder) => !draft.includes(placeholder));
  });
  readonly promptDiffLines = computed<PromptDiffLine[]>(() => {
    const prompt = this.selectedPrompt();
    if (!prompt) {
      return [];
    }
    return this.promptDiff(prompt.defaultContent, this.promptDraft());
  });
  readonly promptDiffSummary = computed(() => {
    const lines = this.promptDiffLines();
    const changed = lines.filter((line) => line.status !== 'same').length;
    return changed > 0 ? `${changed} строк отличаются от дефолта` : 'Совпадает с дефолтом';
  });
  readonly workflowSteps = computed<WorkflowStep[]>(() => [
    {
      icon: 'tune',
      title: '1. Настройки',
      text: 'Выберите профиль, добавьте ручные факты и публичные ссылки, если CRM их не знает.'
    },
    {
      icon: 'storage',
      title: '2. Сырьё',
      text: 'Обновите факты и источники: система соберёт CRM, сайт, поиск и страницы для проверки.'
    },
    {
      icon: 'psychology',
      title: '3. Отчёт',
      text: 'Запустите глубокий отчёт: OpenAI синтезирует разделы, источники, сомнения и чек-лист качества.'
    },
    {
      icon: 'compare_arrows',
      title: '4. Версии',
      text: 'Сравните новый отчёт со старым, пересоберите источники, весь текст или только один раздел.'
    },
    {
      icon: 'inventory_2',
      title: '5. AI-пакет',
      text: 'После удачного отчёта соберите темы отзывов, посты, рекламу и ответы компании.'
    }
  ]);
  readonly filteredDeepResearchHistory = computed<DeepCompanyResearchJob[]>(() => {
    const statusFilter = this.historyStatusFilter();
    const operationFilter = this.historyOperationFilter();
    const query = this.historySearchText().trim().toLowerCase();

    return this.deepResearchHistory().filter((job) => {
      if (statusFilter !== 'all' && job.status !== statusFilter) {
        return false;
      }
      if (operationFilter !== 'all' && job.operation !== operationFilter) {
        return false;
      }
      if (!query) {
        return true;
      }
      return [
        job.companyName,
        String(job.companyId),
        String(job.jobId),
        job.model,
        job.errorMessage,
        this.deepResearchStatusLabel(job),
        this.deepResearchOperationLabel(job)
      ].some((value) => (value ?? '').toLowerCase().includes(query));
    });
  });
  readonly selectedHistoryDetails = computed<DeepCompanyResearchJob | null>(() => {
    const selected = this.selectedHistoryJob();
    if (selected) {
      return this.deepResearchHistory().find((job) => job.jobId === selected.jobId) ?? selected;
    }
    return this.deepResearchJob();
  });
  readonly activeResearchSnapshot = computed<ResearchSnapshot | null>(() => {
    return this.contentPack()?.researchSnapshot ?? this.researchSnapshot();
  });
  readonly snapshotMetrics = computed<SnapshotMetric[]>(() => {
    const snapshot = this.activeResearchSnapshot();
    if (!snapshot) {
      return [];
    }
    return [
      { label: 'Источники', value: snapshot.sources?.length ?? 0 },
      { label: 'Поиск', value: snapshot.searchAvailable ? snapshot.searchProvider : 'выключен' },
      { label: 'Результаты', value: snapshot.searchResultsCount ?? 0 },
      { label: 'Страницы сайта', value: snapshot.websitePagesRead ?? 0 }
    ];
  });
  readonly snapshotSourceGroups = computed<SnapshotSourceGroup[]>(() => {
    const sources = this.activeResearchSnapshot()?.sources ?? [];
    const groups = new Map<string, CompanySource[]>();
    for (const source of sources) {
      const key = source.type || 'unknown';
      groups.set(key, [...(groups.get(key) ?? []), source]);
    }
    return [...groups.entries()].map(([key, groupedSources]) => ({
      key,
      label: this.sourceTypeLabel(key),
      sources: groupedSources
    }));
  });
  readonly reportPassportMetrics = computed<ReportMetric[]>(() => {
    const report = this.deepResearch();
    const job = this.deepResearchJob();
    const selectedProfile = this.selectedDeepResearchProfile();

    return [
      { label: 'Статус', value: this.deepResearchStatusLabel(job), icon: this.reportStatusIcon(job), tone: this.reportStatusTone(job) },
      { label: 'Источники', value: report?.sources?.length ?? 0, icon: 'travel_explore', tone: 'blue' },
      { label: 'Разделы', value: this.deepReportBlocks().length, icon: 'dashboard', tone: 'green' },
      {
        label: 'Качество',
        value: this.reportQualitySummary().total > 0
          ? `${this.reportQualitySummary().passed}/${this.reportQualitySummary().total}`
          : '-',
        icon: this.reportQualitySummary().icon,
        tone: this.reportQualitySummary().tone
      },
      { label: 'Модель', value: report?.model || job?.model || selectedProfile?.model || this.status()?.openAiResearchReportModel || '-', icon: 'memory', tone: 'blue' }
    ];
  });
  readonly reportQualityChecks = computed<DeepResearchQualityCheck[]>(() => this.deepResearch()?.qualityChecks ?? []);
  readonly reportQualitySummary = computed<ReportQualitySummary>(() => {
    const checks = this.reportQualityChecks();
    const scoredChecks = checks.filter((check) => check.status !== 'info');
    const total = scoredChecks.length;
    const passed = scoredChecks.filter((check) => check.status === 'pass').length;
    const failed = checks.filter((check) => check.status === 'fail').length;
    const warned = checks.filter((check) => check.status === 'warn').length;

    if (checks.length === 0) {
      return { passed: 0, total: 0, label: 'Чек-лист не собран', tone: 'gray', icon: 'rule' };
    }
    if (total === 0) {
      return { passed: 0, total: 0, label: 'Только справочные проверки', tone: 'gray', icon: 'rule' };
    }
    if (failed > 0) {
      return { passed, total, label: 'Есть критичные пробелы', tone: 'red', icon: 'error' };
    }
    if (warned > 0) {
      return { passed, total, label: 'Нужна ручная проверка', tone: 'yellow', icon: 'warning' };
    }
    return { passed, total, label: 'Отчёт выглядит надёжно', tone: 'green', icon: 'verified' };
  });
  readonly reportWarnings = computed<string[]>(() => {
    const qualityDetails = new Set(this.reportQualityChecks().map((check) => check.detail).filter(Boolean));
    return (this.deepResearch()?.warnings ?? []).filter((warning) => !qualityDetails.has(warning));
  });
  readonly gapEnrichmentStatus = computed<GapEnrichmentStatus | null>(() => {
    const check = this.reportQualityChecks().find((item) => item.key === 'gap_enrichment');
    if (!check) {
      return null;
    }

    const detail = check.detail || '';
    const normalized = detail.toLowerCase();
    if (check.status === 'warn' || normalized.includes('не выполнен') || normalized.includes('не добавлен')) {
      return { label: 'Досбор не выполнен', detail, icon: 'warning', tone: 'yellow' };
    }
    if (check.status === 'fail') {
      return { label: 'Досбор с ошибкой', detail, icon: 'error', tone: 'red' };
    }
    if (normalized.includes('пропущен')) {
      return { label: 'Досбор пропущен', detail, icon: 'block', tone: 'gray' };
    }
    if (normalized.includes('не требовался')) {
      return { label: 'Досбор не требовался', detail, icon: 'task_alt', tone: 'gray' };
    }
    if (normalized.includes('выполнен')) {
      return { label: 'Досбор выполнен', detail, icon: 'travel_explore', tone: 'green' };
    }
    return { label: 'Досбор', detail, icon: 'info', tone: 'blue' };
  });
  readonly confirmedFacts = computed<DeepResearchFactItem[]>(() => this.deepResearch()?.factSnapshot?.confirmedFacts ?? []);
  readonly uncertainFacts = computed<DeepResearchFactItem[]>(() => this.deepResearch()?.factSnapshot?.uncertainFacts ?? []);
  readonly sourceReviews = computed<DeepResearchSourceReview[]>(() => this.deepResearch()?.factSnapshot?.sourceReviews ?? []);
  readonly reportComparison = computed<ReportComparison | null>(() => {
    const targetJob = this.deepResearchJob();
    if (!targetJob?.report) {
      return null;
    }

    const manualBase = this.comparisonBaseJob();
    const baseJob = manualBase?.report && manualBase.jobId !== targetJob.jobId
      ? manualBase
      : this.previousReportJob(targetJob);
    if (!baseJob?.report || baseJob.jobId === targetJob.jobId) {
      return null;
    }

    return this.compareReportJobs(baseJob, targetJob);
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
      { key: 'honestReviewTopics', title: 'Темы для честного отзыва', icon: 'fact_check', items: pack.honestReviewTopics },
      { key: 'reviewDraftTemplates', title: 'Черновики отзывов с УТП', icon: 'rate_review', items: pack.reviewDraftTemplates },
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
    this.loadPrompts(false);
    this.loadDeepResearchHistory(false);
    this.loadLatestResearchSnapshot(false);
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

  loadPrompts(showError = true): void {
    this.reputationAiApi.prompts().subscribe({
      next: (prompts) => {
        this.prompts.set(prompts);
        const selected = prompts.find((prompt) => prompt.key === this.selectedPromptKey()) ?? prompts[0] ?? null;
        if (selected) {
          this.selectedPromptKey.set(selected.key);
          this.promptDraft.set(selected.content);
          this.promptValidation.set(null);
          this.promptPreview.set(null);
          this.loadPromptHistory(selected.key, false);
        }
      },
      error: (error: unknown) => {
        if (showError) {
          this.fail(error, 'Не удалось загрузить промпты AI-репутации.');
        }
      }
    });
  }

  selectPrompt(key: string): void {
    const prompt = this.prompts().find((item) => item.key === key);
    if (!prompt) {
      return;
    }
    this.selectedPromptKey.set(prompt.key);
    this.promptDraft.set(prompt.content);
    this.promptValidation.set(null);
    this.promptPreview.set(null);
    this.loadPromptHistory(prompt.key, false);
  }

  updatePromptDraft(value: string): void {
    this.promptDraft.set(value ?? '');
    this.promptValidation.set(null);
    this.promptPreview.set(null);
  }

  validatePrompt(showNotice = true): void {
    const prompt = this.selectedPrompt();
    if (!prompt) {
      return;
    }

    this.validatingPrompt.set(true);
    this.reputationAiApi.validatePrompt(prompt.key, this.promptDraft()).subscribe({
      next: (validation) => {
        this.promptValidation.set(validation);
        this.validatingPrompt.set(false);
        if (showNotice) {
          this.notice.set(validation.valid ? 'Промпт прошёл проверку маркеров.' : 'В промпте не хватает обязательных маркеров.');
        }
      },
      error: (error: unknown) => {
        this.validatingPrompt.set(false);
        this.error.set(apiErrorDetail(error, 'Не удалось проверить промпт.'));
      }
    });
  }

  previewPrompt(): void {
    const prompt = this.selectedPrompt();
    if (!prompt) {
      return;
    }

    this.previewingPrompt.set(true);
    this.reputationAiApi.previewPrompt(prompt.key, this.promptDraft()).subscribe({
      next: (preview) => {
        this.promptPreview.set(preview);
        this.previewingPrompt.set(false);
        this.notice.set(preview.unresolvedPlaceholders.length > 0
          ? 'Preview собран, но часть маркеров не подставилась.'
          : 'Preview промпта собран без вызова OpenAI.');
      },
      error: (error: unknown) => {
        this.previewingPrompt.set(false);
        this.error.set(apiErrorDetail(error, 'Не удалось собрать preview промпта.'));
      }
    });
  }

  savePrompt(): void {
    const prompt = this.selectedPrompt();
    if (!prompt) {
      return;
    }
    const content = this.promptDraft().trim();
    if (!content) {
      this.error.set('Промпт не может быть пустым.');
      return;
    }
    if (this.promptMissingPlaceholders().length > 0) {
      this.error.set(`В промпте не хватает обязательных маркеров: ${this.promptMissingPlaceholders().join(', ')}`);
      return;
    }

    this.savingPrompt.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.reputationAiApi.updatePrompt(prompt.key, content).subscribe({
      next: (updatedPrompt) => {
        this.replacePrompt(updatedPrompt);
        this.selectedPromptKey.set(updatedPrompt.key);
        this.promptDraft.set(updatedPrompt.content);
        this.promptValidation.set(null);
        this.promptPreview.set(null);
        this.loadPromptHistory(updatedPrompt.key, false);
        this.savingPrompt.set(false);
        this.notice.set('Промпт сохранён. Следующие AI-запуски будут использовать эту версию.');
      },
      error: (error: unknown) => {
        this.savingPrompt.set(false);
        this.error.set(apiErrorDetail(error, 'Не удалось сохранить промпт.'));
      }
    });
  }

  resetPrompt(): void {
    const prompt = this.selectedPrompt();
    if (!prompt) {
      return;
    }

    this.savingPrompt.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.reputationAiApi.resetPrompt(prompt.key).subscribe({
      next: (updatedPrompt) => {
        this.replacePrompt(updatedPrompt);
        this.selectedPromptKey.set(updatedPrompt.key);
        this.promptDraft.set(updatedPrompt.content);
        this.promptValidation.set(null);
        this.promptPreview.set(null);
        this.loadPromptHistory(updatedPrompt.key, false);
        this.savingPrompt.set(false);
        this.notice.set('Промпт сброшен к дефолтной версии из проекта.');
      },
      error: (error: unknown) => {
        this.savingPrompt.set(false);
        this.error.set(apiErrorDetail(error, 'Не удалось сбросить промпт.'));
      }
    });
  }

  applyPromptPreset(preset: ReputationAiPromptPreset): void {
    const prompt = this.selectedPrompt();
    if (!prompt) {
      return;
    }

    this.applyingPromptPreset.set(preset.key);
    this.error.set(null);
    this.notice.set(null);
    this.reputationAiApi.applyPromptPreset(prompt.key, preset.key).subscribe({
      next: (updatedPrompt) => {
        this.replacePrompt(updatedPrompt);
        this.selectedPromptKey.set(updatedPrompt.key);
        this.promptDraft.set(updatedPrompt.content);
        this.promptValidation.set(null);
        this.promptPreview.set(null);
        this.loadPromptHistory(updatedPrompt.key, false);
        this.applyingPromptPreset.set(null);
        this.notice.set(`Пресет «${preset.title}» применён. Следующие AI-запуски будут использовать эту версию.`);
      },
      error: (error: unknown) => {
        this.applyingPromptPreset.set(null);
        this.error.set(apiErrorDetail(error, 'Не удалось применить пресет промпта.'));
      }
    });
  }

  loadPromptHistory(key = this.selectedPromptKey(), showError = true): void {
    if (!key) {
      this.promptHistory.set([]);
      return;
    }
    this.reputationAiApi.promptHistory(key).subscribe({
      next: (history) => this.promptHistory.set(history),
      error: (error: unknown) => {
        this.promptHistory.set([]);
        if (showError) {
          this.error.set(apiErrorDetail(error, 'Не удалось загрузить историю промпта.'));
        }
      }
    });
  }

  checkOpenAiRoute(): void {
    this.checkingOpenAiRoute.set(true);
    this.reputationAiApi.checkOpenAiRoute().subscribe({
      next: (diagnostics) => {
        const current = this.status();
        if (current) {
          this.status.set({ ...current, openAiDiagnostics: diagnostics });
        }
        this.checkingOpenAiRoute.set(false);
        this.notice.set(this.openAiCheckNotice(diagnostics));
      },
      error: (error: unknown) => {
        this.checkingOpenAiRoute.set(false);
        this.fail(error, 'Не удалось проверить маршрут OpenAI.');
      }
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
        this.refreshDeepResearchHistory(companyId);
        this.deepReportMonitor.watch(companyId);
        this.finish(this.isActiveDeepResearchJob(job)
          ? 'Глубокий GPT-отчет запущен в фоне. Можно перейти в другой раздел.'
          : 'Глубокий GPT-отчет уже готов.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось запустить глубокий GPT-отчет.')
    });
  }

  loadLatestResearchSnapshot(showNotice = true): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.reputationAiApi.latestResearch(companyId).subscribe({
      next: (snapshot) => {
        this.researchSnapshot.set(snapshot);
        if (showNotice) {
          this.notice.set('Сырьё и источники загружены.');
        }
      },
      error: () => {
        this.researchSnapshot.set(null);
        if (showNotice) {
          this.notice.set('Сырьё ещё не собиралось для этой компании.');
        }
      }
    });
  }

  refreshResearchSnapshot(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('researchSnapshot');
    this.reputationAiApi.createResearch(companyId, this.researchRequest()).subscribe({
      next: (snapshot) => {
        this.researchSnapshot.set(snapshot);
        this.finish('Сырьё и источники обновлены.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось обновить сырьё и источники.')
    });
  }

  refreshDeepReportSources(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('refreshSources');
    this.reputationAiApi.refreshDeepResearchSourcesJob(
      companyId,
      this.researchRequest('refresh_sources', this.deepResearchJob()?.jobId ?? null)
    ).subscribe({
      next: (job) => {
        this.applyDeepResearchJob(job);
        this.refreshDeepResearchHistory(companyId);
        this.deepReportMonitor.watch(companyId);
        this.finish(this.isActiveDeepResearchJob(job)
          ? 'Обновление источников запущено в фоне.'
          : 'Источники отчёта обновлены.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось запустить обновление источников.')
    });
  }

  rebuildDeepReportText(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('rebuildText');
    this.reputationAiApi.rebuildDeepResearchTextJob(
      companyId,
      this.researchRequest('rebuild_text', this.deepResearchJob()?.jobId ?? null)
    ).subscribe({
      next: (job) => {
        this.applyDeepResearchJob(job);
        this.refreshDeepResearchHistory(companyId);
        this.deepReportMonitor.watch(companyId);
        this.finish(this.isActiveDeepResearchJob(job)
          ? 'Пересборка текста запущена в фоне.'
          : 'Текст отчёта пересобран.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось запустить пересборку текста.')
    });
  }

  rebuildDeepReportSection(block: DeepReportBlock, sectionIndex: number): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }
    const baseJobId = this.deepResearchJob()?.jobId ?? null;
    if (!baseJobId) {
      this.error.set('Откройте готовый отчёт перед пересборкой раздела.');
      return;
    }

    this.start('rebuildSection');
    this.reputationAiApi.rebuildDeepResearchSectionJob(
      companyId,
      this.researchRequest('rebuild_section', baseJobId, block.title, sectionIndex)
    ).subscribe({
      next: (job) => {
        this.applyDeepResearchJob(job);
        this.refreshDeepResearchHistory(companyId);
        this.deepReportMonitor.watch(companyId);
        this.finish(this.isActiveDeepResearchJob(job)
          ? `Пересборка раздела «${block.title}» запущена в фоне.`
          : `Раздел «${block.title}» пересобран.`);
      },
      error: (error: unknown) => this.fail(error, 'Не удалось запустить пересборку раздела.')
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
        this.refreshDeepResearchHistory(companyId);
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

  loadDeepResearchHistory(showNotice = true): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.reputationAiApi.deepResearchJobHistory(companyId).subscribe({
      next: (history) => {
        this.deepResearchHistory.set(history);
        if (showNotice) {
          this.notice.set(history.length > 0 ? 'История отчётов загружена.' : 'История отчётов пока пустая.');
        }
      },
      error: () => this.deepResearchHistory.set([])
    });
  }

  openDeepResearchJob(job: DeepCompanyResearchJob): void {
    this.comparisonBaseJob.set(null);
    this.selectedHistoryJob.set(job);
    this.applyDeepResearchJob(job);
    if (this.isActiveDeepResearchJob(job)) {
      this.deepReportMonitor.watch(job.companyId);
      this.notice.set('Отчёт ещё собирается, наблюдение включено.');
      return;
    }
    this.notice.set(job.report ? 'Отчёт открыт из истории.' : 'Статус запуска открыт из истории.');
  }

  selectHistoryJob(job: DeepCompanyResearchJob): void {
    this.selectedHistoryJob.set(job);
  }

  compareWithCurrent(job: DeepCompanyResearchJob): void {
    const current = this.deepResearchJob();
    if (!current?.report || !job.report || current.jobId === job.jobId) {
      return;
    }

    this.comparisonBaseJob.set(job);
    this.notice.set('Сравнение версий отчёта обновлено.');
  }

  resetHistoryFilters(): void {
    this.historyStatusFilter.set('all');
    this.historyOperationFilter.set('all');
    this.historySearchText.set('');
  }

  createContentPack(deepReportJobId: number | null = this.deepResearchJob()?.jobId ?? null): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('contentPack');
    const researchRequest = this.researchRequest();
    this.reputationAiApi.startContentPackJob(companyId, {
      manualDescription: researchRequest.manualDescription,
      productsOrServices: researchRequest.productsOrServices,
      publicUrls: researchRequest.publicUrls,
      includeCompanyWebsite: researchRequest.includeCompanyWebsite,
      productOrService: this.cleanText(this.productOrService),
      adTextsCount: this.positiveOrNull(this.adTextsCount, 8),
      socialPostsCount: this.positiveOrNull(this.socialPostsCount, 5),
      positiveReplyCount: this.positiveOrNull(this.positiveReplyCount, 8),
      negativeReplyCount: this.positiveOrNull(this.negativeReplyCount, 6),
      contentPackProfile: this.cleanText(this.contentPackProfile()),
      deepReportJobId
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

  loadLatestContentPack(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.start('loadContentPack');
    this.reputationAiApi.latestContentPackJob(companyId).subscribe({
      next: (job) => {
        this.applyContentPackJob(job);
        if (this.isActiveContentPackJob(job)) {
          this.watchContentPackJob(companyId);
          this.finish('AI-пакет ещё собирается.');
        } else if (job.pack) {
          this.finish('Последний AI-пакет загружен из базы.');
        } else {
          this.finish('Статус AI-пакета загружен.');
        }
      },
      error: (error: unknown) => this.fail(error, 'AI-пакет для этой компании пока не найден.')
    });
  }

  createContentPackFromJob(job: DeepCompanyResearchJob): void {
    this.openDeepResearchJob(job);
    this.createContentPack(job.jobId);
  }

  improveReviewTemplates(): void {
    const companyId = this.validCompanyId();
    const pack = this.contentPack();
    if (companyId == null || !pack) {
      this.error.set('Сначала открой готовый AI-пакет.');
      return;
    }

    this.start('reviewTemplates');
    this.reputationAiApi.improveReviewTemplates(companyId, {
      deepReportJobId: this.deepResearchJob()?.jobId ?? null,
      contentPackJobId: this.contentPackJob()?.jobId ?? null,
      manualNotes: this.cleanText(this.reviewTemplateNotes),
      topicsCount: this.positiveOrNull(this.reviewTemplateTopicsCount, 10),
      draftsCount: this.positiveOrNull(this.reviewTemplateDraftsCount, 8),
      tone: this.cleanText(this.reviewTemplateTone),
      contentPackProfile: this.cleanText(this.contentPackProfile())
    }).subscribe({
      next: (result) => {
        this.reviewTemplatesResult.set(result);
        this.finish(result.provider === 'local'
          ? 'Улучшенные отзывы собраны локально, OpenAI не использовался.'
          : 'Улучшенные отзывы готовы.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось улучшить шаблоны отзывов.')
    });
  }

  applyImprovedReviewTemplates(): void {
    const companyId = this.validCompanyId();
    const result = this.reviewTemplatesResult();
    if (companyId == null || !result) {
      return;
    }

    this.start('applyReviewTemplates');
    this.reputationAiApi.applyReviewTemplates(companyId, {
      contentPackJobId: result.contentPackJobId ?? this.contentPackJob()?.jobId ?? null,
      honestReviewTopics: result.honestReviewTopics,
      reviewDraftTemplates: result.reviewDraftTemplates
    }).subscribe({
      next: (pack) => {
        this.contentPack.set(pack);
        const job = this.contentPackJob();
        if (job) {
          this.contentPackJob.set({ ...job, pack });
        }
        this.finish('Улучшенные отзывы сохранены в AI-пакет.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось заменить отзывы в AI-пакете.')
    });
  }

  createSingleReviewDraft(): void {
    const companyId = this.validCompanyId();
    const pack = this.contentPack();
    if (companyId == null || !pack) {
      this.error.set('Сначала открой готовый AI-пакет.');
      return;
    }

    this.start('singleReviewDraft');
    this.reputationAiApi.createSingleReviewDraft(companyId, {
      deepReportJobId: this.deepResearchJob()?.jobId ?? null,
      contentPackJobId: this.contentPackJob()?.jobId ?? null,
      idea: this.cleanText(this.singleReviewIdea),
      style: this.cleanText(this.singleReviewStyle),
      manualNotes: this.cleanText(this.reviewTemplateNotes),
      length: this.cleanText(this.singleReviewLength),
      contentPackProfile: this.cleanText(this.contentPackProfile())
    }).subscribe({
      next: (result) => {
        this.singleReviewDraftResult.set(result);
        this.checkReport.set(result.safetyReport);
        this.finish(result.provider === 'local'
          ? 'Один черновик собран локально, OpenAI не использовался.'
          : 'Один черновик отзыва готов.');
      },
      error: (error: unknown) => this.fail(error, 'Не удалось подготовить точечный черновик отзыва.')
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

  copyDeepReportMarkdown(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    const jobId = this.deepResearchJob()?.jobId ?? null;
    this.reputationAiApi.exportDeepResearchMarkdown(companyId, jobId).subscribe({
      next: (markdown) => this.copyText(markdown),
      error: (error: unknown) => this.fail(error, 'Не удалось получить Markdown-экспорт отчёта.')
    });
  }

  copyContentPackMarkdown(): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    this.reputationAiApi.exportContentPackMarkdown(companyId).subscribe({
      next: (markdown) => this.copyText(markdown),
      error: (error: unknown) => this.fail(error, 'Не удалось получить Markdown-экспорт AI-пакета.')
    });
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

  setDeepResearchProfile(profileKey: string): void {
    const nextProfile = profileKey || 'economy';
    this.deepResearchProfile.set(nextProfile);
    this.autoEnrichCollectionGaps = this.defaultAutoEnrichCollectionGaps(nextProfile);
  }

  autoEnrichCollectionGapsHint(): string {
    return this.autoEnrichCollectionGaps
      ? 'Включён второй короткий поиск по пунктам из «Что ещё собирать».'
      : 'Второй поиск отключён, отчёт соберётся быстрее.';
  }

  downloadDeepReport(report: DeepCompanyResearchReport): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    const fileName = `${this.slugify(report.companyName || `company-${report.companyId}`)}-deep-report.md`;
    const jobId = this.deepResearchJob()?.jobId ?? null;
    this.reputationAiApi.exportDeepResearchMarkdown(companyId, jobId).subscribe({
      next: (markdown) => this.downloadMarkdown(markdown, fileName, 'Markdown-отчёт скачан.'),
      error: (error: unknown) => this.fail(error, 'Не удалось скачать Markdown-отчёт.')
    });
  }

  downloadDeepReportPdf(report: DeepCompanyResearchReport): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    const fileName = `${this.slugify(report.companyName || `company-${report.companyId}`)}-deep-report.pdf`;
    const jobId = this.deepResearchJob()?.jobId ?? null;
    this.reputationAiApi.exportDeepResearchPdf(companyId, jobId).subscribe({
      next: (blob) => this.downloadBlob(blob, fileName, 'PDF-отчёт скачан.'),
      error: (error: unknown) => this.fail(error, 'Не удалось скачать PDF-отчёт.')
    });
  }

  downloadContentPackMarkdown(pack: ReputationContentPack): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    const companyName = pack.researchSnapshot?.companyName || pack.companyProfile?.shortDescription || `company-${companyId}`;
    const fileName = `${this.slugify(companyName)}-content-pack.md`;
    this.reputationAiApi.exportContentPackMarkdown(companyId).subscribe({
      next: (markdown) => this.downloadMarkdown(markdown, fileName, 'Markdown AI-пакета скачан.'),
      error: (error: unknown) => this.fail(error, 'Не удалось скачать Markdown AI-пакета.')
    });
  }

  downloadContentPackPdf(pack: ReputationContentPack): void {
    const companyId = this.validCompanyId();
    if (companyId == null) {
      return;
    }

    const companyName = pack.researchSnapshot?.companyName || pack.companyProfile?.shortDescription || `company-${companyId}`;
    const fileName = `${this.slugify(companyName)}-content-pack.pdf`;
    this.reputationAiApi.exportContentPackPdf(companyId).subscribe({
      next: (blob) => this.downloadBlob(blob, fileName, 'PDF AI-пакета скачан.'),
      error: (error: unknown) => this.fail(error, 'Не удалось скачать PDF AI-пакета.')
    });
  }

  private downloadMarkdown(markdown: string, fileName: string, message: string): void {
    const text = markdown?.trim();
    if (!text) {
      return;
    }

    const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' });
    this.downloadBlob(blob, fileName, message);
  }

  private downloadBlob(blob: Blob, fileName: string, message: string): void {
    if (!blob || blob.size === 0) {
      return;
    }

    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
    this.notice.set(message);
  }

  trackMetric(_index: number, metric: ReportMetric): string {
    return metric.label;
  }

  trackWorkflowStep(_index: number, step: WorkflowStep): string {
    return step.title;
  }

  trackSnapshotMetric(_index: number, metric: SnapshotMetric): string {
    return metric.label;
  }

  trackSnapshotSourceGroup(_index: number, group: SnapshotSourceGroup): string {
    return group.key;
  }

  trackCompanySource(index: number, source: CompanySource): string {
    return source.url || `${index}-${source.title}`;
  }

  trackBlock(_index: number, block: PackBlock): string {
    return block.key;
  }

  trackProfile(_index: number, profile: ReputationAiModelProfile): string {
    return profile.key;
  }

  trackPrompt(_index: number, prompt: ReputationAiPrompt): string {
    return prompt.key;
  }

  trackPromptPreset(_index: number, preset: ReputationAiPromptPreset): string {
    return preset.key;
  }

  trackPromptDiff(_index: number, line: PromptDiffLine): string {
    return line.key;
  }

  trackPromptVersion(_index: number, version: ReputationAiPromptVersion): number {
    return version.id;
  }

  trackText(index: number, text: string): string {
    return `${index}-${text}`;
  }

  trackSource(index: number, source: { url: string; title: string }): string {
    return source.url || `${index}-${source.title}`;
  }

  trackChangeItem(_index: number, item: ReportChangeItem): string {
    return item.key;
  }

  trackSectionChange(_index: number, change: ReportSectionChange): string {
    return change.key;
  }

  trackQualityCheck(index: number, check: DeepResearchQualityCheck): string {
    return check.key || `${index}-${check.label}`;
  }

  trackFact(index: number, fact: DeepResearchFactItem): string {
    return `${index}-${fact.label}-${fact.value}`;
  }

  trackSourceReview(index: number, source: DeepResearchSourceReview): string {
    return source.url || `${index}-${source.title}`;
  }

  trackDeepBlock(_index: number, block: DeepReportBlock): string {
    return block.key;
  }

  trackJob(_index: number, job: DeepCompanyResearchJob): number {
    return job.jobId;
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

    if (job.status === 'QUEUED' || job.status === 'RUNNING') {
      if (job.operation === 'refresh_sources') {
        return job.status === 'QUEUED' ? 'источники в очереди' : 'источники обновляются';
      }
      if (job.operation === 'rebuild_text') {
        return job.status === 'QUEUED' ? 'текст в очереди' : 'текст пересобирается';
      }
      if (job.operation === 'rebuild_section') {
        return job.status === 'QUEUED' ? 'раздел в очереди' : 'раздел пересобирается';
      }
    }
    if (job.status === 'DONE') {
      if (job.operation === 'refresh_sources') {
        return 'источники обновлены';
      }
      if (job.operation === 'rebuild_text') {
        return 'текст пересобран';
      }
      if (job.operation === 'rebuild_section') {
        return 'раздел пересобран';
      }
    }

    return {
      QUEUED: 'в очереди',
      RUNNING: 'собирается',
      DONE: 'готов',
      FAILED: 'ошибка'
    }[job.status] ?? job.status.toLowerCase();
  }

  deepResearchOperationLabel(job: DeepCompanyResearchJob | null): string {
    if (!job) {
      return 'новый отчёт';
    }
    if (job.operation === 'refresh_sources') {
      return 'обновление источников';
    }
    if (job.operation === 'rebuild_text') {
      return 'пересборка текста';
    }
    if (job.operation === 'rebuild_section') {
      return 'пересборка раздела';
    }
    return 'новый отчёт';
  }

  promptHistoryActionLabel(action: string): string {
    if (action?.startsWith('preset:')) {
      return `пресет: ${action.replace('preset:', '')}`;
    }
    if (action === 'reset') {
      return 'сброс к дефолту';
    }
    if (action === 'update') {
      return 'сохранение';
    }
    return action || 'изменение';
  }

  deepResearchJobError(job: DeepCompanyResearchJob): string {
    return this.humanizeAiError(job.errorMessage, 'Глубокий GPT-отчёт завершился ошибкой.');
  }

  contentPackJobError(job: ReputationContentPackJob): string {
    return this.humanizeAiError(job.errorMessage, 'AI-пакет завершился ошибкой.');
  }

  openAiRouteLabel(diagnostics: OpenAiProviderDiagnostics | null | undefined): string {
    if (!diagnostics) {
      return 'неизвестно';
    }
    if (!diagnostics.configured) {
      return 'OpenAI не настроен';
    }
    if (diagnostics.requestGoesThroughProxy) {
      return `через VPS proxy ${diagnostics.proxyHost}:${diagnostics.proxyPort}`;
    }
    if (diagnostics.proxyEnabled && !diagnostics.proxyConfigured) {
      return 'proxy включён, но host не задан';
    }
    return 'прямое соединение';
  }

  openAiCheckLabel(diagnostics: OpenAiProviderDiagnostics | null | undefined): string {
    if (!diagnostics) {
      return 'нет данных';
    }
    if (diagnostics.lastCheckStatus === 'ok') {
      return `OK${diagnostics.lastHttpStatus ? ' · HTTP ' + diagnostics.lastHttpStatus : ''}`;
    }
    if (diagnostics.lastCheckStatus === 'http_error') {
      return `HTTP ${diagnostics.lastHttpStatus ?? '-'}`;
    }
    if (diagnostics.lastCheckStatus === 'network_error') {
      return 'ошибка сети';
    }
    if (diagnostics.lastCheckStatus === 'not_configured') {
      return 'не настроен';
    }
    return 'не проверялось';
  }

  reportStatusIcon(job: DeepCompanyResearchJob | null): string {
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

  qualityStatusIcon(status: string): string {
    if (status === 'pass') {
      return 'check_circle';
    }
    if (status === 'fail') {
      return 'error';
    }
    if (status === 'warn') {
      return 'warning';
    }
    return 'info';
  }

  qualityStatusLabel(status: string): string {
    if (status === 'pass') {
      return 'ОК';
    }
    if (status === 'fail') {
      return 'Пробел';
    }
    if (status === 'warn') {
      return 'Проверить';
    }
    return 'Инфо';
  }

  sectionChangeLabel(status: ReportSectionChange['status']): string {
    if (status === 'added') {
      return 'добавлен';
    }
    if (status === 'removed') {
      return 'убран';
    }
    return 'изменён';
  }

  confidenceLabel(confidence: string): string {
    if (confidence === 'high') {
      return 'высокая';
    }
    if (confidence === 'low') {
      return 'низкая';
    }
    return 'средняя';
  }

  sourceReviewIcon(status: string): string {
    return status === 'trusted_public' ? 'public' : 'manage_search';
  }

  sourceReviewLabel(status: string): string {
    return status === 'trusted_public' ? 'публичный' : 'проверить';
  }

  sourceTypeLabel(type: string): string {
    const normalized = (type || '').toLowerCase();
    if (normalized.includes('website')) {
      return 'Сайт';
    }
    if (normalized.includes('manual')) {
      return 'Ручные ссылки';
    }
    if (normalized.includes('database')) {
      return 'CRM';
    }
    if (normalized.includes('catalog')) {
      return 'Справочники';
    }
    if (normalized.includes('search')) {
      return 'Поиск';
    }
    if (normalized.includes('competitor')) {
      return 'Конкуренты';
    }
    return type || 'Другое';
  }

  historyStatusFilterLabel(filter: HistoryStatusFilter): string {
    return {
      all: 'Все',
      DONE: 'Готовые',
      FAILED: 'Ошибки',
      RUNNING: 'Собираются',
      QUEUED: 'В очереди'
    }[filter];
  }

  jobQualityLabel(job: DeepCompanyResearchJob): string {
    if (!job.report) {
      return '-';
    }
    const score = this.reportQualityScore(job.report);
    return score.total > 0 ? `${score.passed}/${score.total}` : '-';
  }

  jobSourceCount(job: DeepCompanyResearchJob): number {
    return job.report?.sources?.length ?? 0;
  }

  jobSectionCount(job: DeepCompanyResearchJob): number {
    return job.report?.sections?.length ?? 0;
  }

  isCurrentDeepResearchJob(job: DeepCompanyResearchJob): boolean {
    return this.deepResearchJob()?.jobId === job.jobId;
  }

  canCompareWithCurrent(job: DeepCompanyResearchJob): boolean {
    const current = this.deepResearchJob();
    return Boolean(current?.report && job.report && current.jobId !== job.jobId);
  }

  historyDetailWarnings(job: DeepCompanyResearchJob): string[] {
    if (job.errorMessage) {
      return [this.deepResearchJobError(job)];
    }
    return job.report?.warnings?.slice(0, 4) ?? [];
  }

  private previousReportJob(targetJob: DeepCompanyResearchJob): DeepCompanyResearchJob | null {
    const targetTime = Date.parse(targetJob.createdAt || '');
    const reports = this.deepResearchHistory()
      .filter((job) => job.status === 'DONE' && job.report && job.jobId !== targetJob.jobId)
      .sort((left, right) => Date.parse(right.createdAt || '') - Date.parse(left.createdAt || ''));

    if (Number.isFinite(targetTime)) {
      const older = reports.find((job) => Date.parse(job.createdAt || '') < targetTime);
      if (older) {
        return older;
      }
    }

    return reports[0] ?? null;
  }

  private compareReportJobs(baseJob: DeepCompanyResearchJob, targetJob: DeepCompanyResearchJob): ReportComparison | null {
    if (!baseJob.report || !targetJob.report) {
      return null;
    }

    const baseSources = this.sourceMap(baseJob.report.sources ?? []);
    const targetSources = this.sourceMap(targetJob.report.sources ?? []);
    const sourceAdded = [...targetSources.entries()]
      .filter(([key]) => !baseSources.has(key))
      .map(([, source]) => source);
    const sourceRemoved = [...baseSources.entries()]
      .filter(([key]) => !targetSources.has(key))
      .map(([, source]) => source);

    const baseSections = this.sectionMap(baseJob.report);
    const targetSections = this.sectionMap(targetJob.report);
    const sectionChanges: ReportSectionChange[] = [];
    for (const [key, section] of targetSections.entries()) {
      const base = baseSections.get(key);
      if (!base) {
        sectionChanges.push({ key: `added-${key}`, title: section.title, status: 'added' });
      } else if (this.normalizeLongText(base.body) !== this.normalizeLongText(section.body)) {
        sectionChanges.push({ key: `changed-${key}`, title: section.title, status: 'changed' });
      }
    }
    for (const [key, section] of baseSections.entries()) {
      if (!targetSections.has(key)) {
        sectionChanges.push({ key: `removed-${key}`, title: section.title, status: 'removed' });
      }
    }

    const warningsAdded = this.diffStrings(targetJob.report.warnings ?? [], baseJob.report.warnings ?? []);
    const warningsResolved = this.diffStrings(baseJob.report.warnings ?? [], targetJob.report.warnings ?? []);
    const baseQuality = this.reportQualityScore(baseJob.report);
    const targetQuality = this.reportQualityScore(targetJob.report);
    const summaryItems = this.comparisonSummaryItems(
      baseQuality,
      targetQuality,
      sourceAdded.length,
      sourceRemoved.length,
      sectionChanges,
      warningsAdded.length,
      warningsResolved.length
    );
    const hasChanges = sourceAdded.length > 0
      || sourceRemoved.length > 0
      || sectionChanges.length > 0
      || warningsAdded.length > 0
      || warningsResolved.length > 0
      || baseQuality.percent !== targetQuality.percent;

    return {
      baseJob,
      targetJob,
      baseQuality,
      targetQuality,
      sourceAdded,
      sourceRemoved,
      sectionChanges,
      warningsAdded,
      warningsResolved,
      summaryItems,
      hasChanges
    };
  }

  private comparisonSummaryItems(
    baseQuality: ReportQualityScore,
    targetQuality: ReportQualityScore,
    sourceAddedCount: number,
    sourceRemovedCount: number,
    sectionChanges: ReportSectionChange[],
    warningsAddedCount: number,
    warningsResolvedCount: number
  ): ReportChangeItem[] {
    const qualityDelta = targetQuality.percent - baseQuality.percent;
    const changedSections = sectionChanges.filter((change) => change.status === 'changed').length;
    const addedSections = sectionChanges.filter((change) => change.status === 'added').length;
    const removedSections = sectionChanges.filter((change) => change.status === 'removed').length;

    return [
      {
        key: 'quality',
        icon: qualityDelta > 0 ? 'trending_up' : qualityDelta < 0 ? 'trending_down' : 'rule',
        label: 'Качество',
        detail: targetQuality.total > 0 || baseQuality.total > 0
          ? `${baseQuality.label} → ${targetQuality.label}`
          : 'чек-лист не собран',
        tone: qualityDelta > 0 ? 'green' : qualityDelta < 0 ? 'red' : 'gray'
      },
      {
        key: 'sources',
        icon: 'travel_explore',
        label: 'Источники',
        detail: sourceAddedCount || sourceRemovedCount
          ? `+${sourceAddedCount} / -${sourceRemovedCount}`
          : 'без изменений',
        tone: sourceRemovedCount > 0 ? 'yellow' : sourceAddedCount > 0 ? 'green' : 'gray'
      },
      {
        key: 'sections',
        icon: 'article',
        label: 'Разделы',
        detail: sectionChanges.length > 0
          ? `${changedSections} изменено · +${addedSections} · -${removedSections}`
          : 'без изменений',
        tone: sectionChanges.length > 0 ? 'blue' : 'gray'
      },
      {
        key: 'warnings',
        icon: 'warning',
        label: 'Риски',
        detail: warningsAddedCount || warningsResolvedCount
          ? `+${warningsAddedCount} новых · ${warningsResolvedCount} закрыто`
          : 'без изменений',
        tone: warningsAddedCount > 0 ? 'yellow' : warningsResolvedCount > 0 ? 'green' : 'gray'
      }
    ];
  }

  private reportQualityScore(report: DeepCompanyResearchReport): ReportQualityScore {
    const checks = report.qualityChecks ?? [];
    const total = checks.length;
    const passed = checks.filter((check) => check.status === 'pass').length;
    const percent = total > 0 ? Math.round((passed / total) * 100) : 0;
    return {
      passed,
      total,
      percent,
      label: total > 0 ? `${passed}/${total}` : '-'
    };
  }

  private sourceMap(sources: DeepResearchSource[]): Map<string, DeepResearchSource> {
    const map = new Map<string, DeepResearchSource>();
    for (const source of sources) {
      const key = this.sourceKey(source);
      if (key && !map.has(key)) {
        map.set(key, source);
      }
    }
    return map;
  }

  private sourceKey(source: DeepResearchSource): string {
    const url = (source.url ?? '').trim().toLowerCase().replace(/\/+$/, '');
    if (url) {
      return url;
    }
    return this.textKey(`${source.title ?? ''} ${source.note ?? ''}`);
  }

  private sectionMap(report: DeepCompanyResearchReport): Map<string, { title: string; body: string }> {
    const sections = (report.sections ?? []).length > 0
      ? report.sections
      : this.splitMarkdownIntoBlocks(report.reportMarkdown);
    const map = new Map<string, { title: string; body: string }>();
    for (const section of sections) {
      const title = (section.title ?? 'Раздел отчёта').trim() || 'Раздел отчёта';
      const key = this.textKey(title);
      if (key) {
        map.set(key, { title, body: section.body ?? '' });
      }
    }
    return map;
  }

  private diffStrings(left: string[], right: string[]): string[] {
    const rightKeys = new Set(right.map((value) => this.textKey(value)));
    return left
      .filter((value) => value?.trim())
      .filter((value) => !rightKeys.has(this.textKey(value)))
      .map((value) => value.trim());
  }

  private textKey(value: string): string {
    return (value ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
  }

  private promptDiff(defaultContent: string, draftContent: string): PromptDiffLine[] {
    const defaultLines = (defaultContent ?? '').split(/\r?\n/);
    const draftLines = (draftContent ?? '').split(/\r?\n/);
    const maxLines = Math.max(defaultLines.length, draftLines.length);
    const result: PromptDiffLine[] = [];

    for (let index = 0; index < maxLines; index += 1) {
      const defaultText = defaultLines[index] ?? '';
      const draftText = draftLines[index] ?? '';
      let status: PromptDiffLine['status'] = 'same';
      if (index >= defaultLines.length) {
        status = 'added';
      } else if (index >= draftLines.length) {
        status = 'removed';
      } else if (defaultText !== draftText) {
        status = 'changed';
      }

      if (status !== 'same' || result.length < 120) {
        result.push({
          key: `${index}-${status}`,
          status,
          lineNumber: index + 1,
          defaultText,
          draftText
        });
      }
    }

    return result;
  }

  private normalizeLongText(value: string): string {
    return this.textKey(value).replace(/[.,;:!?'"«»]/g, '');
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
    this.mergeDeepResearchHistory(job);
  }

  private applyContentPackJob(job: ReputationContentPackJob | null): void {
    if (!job || Number(job.companyId) !== Number(this.companyId)) {
      return;
    }

    this.contentPackJob.set(job);
    if (job.pack) {
      this.contentPack.set(job.pack);
      this.researchSnapshot.set(job.pack.researchSnapshot);
      this.reviewTemplatesResult.set(null);
      this.singleReviewDraftResult.set(null);
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

  private refreshDeepResearchHistory(companyId: number): void {
    this.reputationAiApi.deepResearchJobHistory(companyId).subscribe({
      next: (history) => this.deepResearchHistory.set(history),
      error: () => undefined
    });
  }

  private mergeDeepResearchHistory(job: DeepCompanyResearchJob): void {
    const next = [
      job,
      ...this.deepResearchHistory().filter((item) => item.jobId !== job.jobId)
    ].slice(0, 10);
    this.deepResearchHistory.set(next);
  }

  private replacePrompt(prompt: ReputationAiPrompt): void {
    const current = this.prompts();
    const exists = current.some((item) => item.key === prompt.key);
    this.prompts.set(exists
      ? current.map((item) => item.key === prompt.key ? prompt : item)
      : [...current, prompt]);
  }

  private researchRequest(
    deepResearchMode: string | null = null,
    baseReportJobId: number | null = null,
    sectionTitle: string | null = null,
    sectionIndex: number | null = null
  ) {
    return {
      manualDescription: this.cleanText(this.manualDescription),
      productsOrServices: this.lines(this.productsOrServicesText),
      publicUrls: this.lines(this.publicUrlsText),
      includeCompanyWebsite: this.includeCompanyWebsite,
      deepResearchProfile: this.cleanText(this.deepResearchProfile()),
      deepResearchMode,
      baseReportJobId,
      sectionTitle,
      sectionIndex,
      enrichCollectionGaps: deepResearchMode ? false : this.autoEnrichCollectionGaps
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
    if (/(автодосбор|досбор)/.test(value)) {
      return 'travel_explore';
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

  private defaultAutoEnrichCollectionGaps(profileKey: string | null | undefined): boolean {
    const key = (profileKey ?? '').trim().toLowerCase();
    return key === 'quality' || key === 'maximum';
  }

  private fallbackDeepResearchProfiles(): ReputationAiModelProfile[] {
    return [
      {
        key: 'economy',
        label: 'Быстро',
        model: 'gpt-5.4-mini',
        description: 'Короткий и дешёвый отчёт для быстрой проверки маршрута и фактов.',
        maxToolCalls: 6,
        maxOutputTokens: 6000,
        reasoningEffort: 'low',
        searchContextSize: 'low'
      },
      {
        key: 'quality',
        label: 'Баланс',
        model: 'gpt-5.5',
        description: 'Основной режим: нормальный отчёт с web search и источниками.',
        maxToolCalls: 16,
        maxOutputTokens: 12000,
        reasoningEffort: 'low',
        searchContextSize: 'low'
      },
      {
        key: 'maximum',
        label: 'Максимум',
        model: 'gpt-5.5',
        description: 'Глубокий отчёт с усиленным reasoning и большим запасом контекста.',
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

    if (lower.includes('unsupported_country_region_territory')
      || lower.includes('country, region, or territory not supported')
      || lower.includes('unsupported country')
      || lower.includes('неподдерживаемого региона')) {
      return 'OpenAI отклонил запрос из неподдерживаемого региона. Проверьте, что включён OpenAI proxy, заданы OPENAI_PROXY_HOST/PORT, а VPS-прокси разрешает IP приложения.';
    }

    if (lower.includes('http 407') || lower.includes('proxy authentication required')) {
      return 'VPS-прокси не пустил запрос к OpenAI. Для схемы без логина и пароля проверьте allowlist IP в Squid/UFW и отсутствие proxy_auth в конфиге Squid.';
    }

    if (lower.includes('eof reached while reading')
      || lower.includes('eof')
      || lower.includes('connection reset')
      || lower.includes('connection closed')
      || lower.includes('header parser received no bytes')
      || lower.includes('remote host terminated')
      || lower.includes('сетевом уровне')
      || lower.includes('proxy/vpn')) {
      return 'Соединение с OpenAI оборвалось на сетевом уровне. Это не ошибка модели: чаще всего виноват proxy/VPN/маршрут. Проверьте соединение или повторите запрос через пару минут.';
    }

    if (lower.includes('timed out') || lower.includes('timeout') || lower.includes('таймаут')) {
      return 'OpenAI не успел ответить за отведённое время. Можно повторить запрос или выбрать более лёгкий профиль.';
    }

    if (lower.includes('поврежд') && lower.includes('json')) {
      return 'Модель вернула повреждённый JSON отчёта. Система попробовала восстановить структуру; если ошибка повторится, запустите отчёт ещё раз или выберите профиль «Быстро».';
    }

    return text;
  }

  private fallbackContentPackProfiles(): ReputationAiModelProfile[] {
    return [
      {
        key: 'economy',
        label: 'Быстро',
        model: 'gpt-5.4-mini',
        description: 'Дешевле для быстрых вариантов пакета по готовому отчёту.',
        maxToolCalls: 0,
        maxOutputTokens: 10000,
        reasoningEffort: 'low',
        searchContextSize: 'off'
      },
      {
        key: 'quality',
        label: 'Баланс',
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

  private openAiCheckNotice(diagnostics: OpenAiProviderDiagnostics): string {
    if (diagnostics.lastCheckStatus === 'ok') {
      return `OpenAI доступен: ${this.openAiRouteLabel(diagnostics)}.`;
    }
    return diagnostics.lastMessage || 'Маршрут OpenAI проверен, требуется внимание.';
  }
}
