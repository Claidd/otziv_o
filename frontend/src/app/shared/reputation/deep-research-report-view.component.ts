import { DatePipe } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import type {
  DeepCompanyResearchJob,
  DeepCompanyResearchReport,
  DeepResearchFactItem,
  DeepResearchQualityCheck,
  DeepResearchSource,
  DeepResearchSourceReview
} from '../../core/reputation-ai.api';
import { UiTooltipDirective } from '../ui-tooltip.directive';

export type DeepReportBlock = {
  key: string;
  sectionIndex: number;
  title: string;
  icon: string;
  body: string;
  html: string;
};

type RawDeepReportBlock = {
  sectionIndex?: number;
  title: string;
  body: string;
};

export type ReportQualityScore = {
  passed: number;
  total: number;
  percent: number;
  label: string;
};

export type ReportChangeItem = {
  key: string;
  icon: string;
  label: string;
  detail: string;
  tone: string;
};

export type ReportSectionChange = {
  key: string;
  title: string;
  status: 'added' | 'removed' | 'changed';
};

export type DeepResearchReportComparison = {
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

export type DeepReportSectionAction = {
  block: DeepReportBlock;
  sectionIndex: number;
};

@Component({
  selector: 'app-deep-research-report-view',
  imports: [DatePipe, UiTooltipDirective],
  templateUrl: './deep-research-report-view.component.html',
  styleUrl: './deep-research-report-view.component.scss'
})
export class DeepResearchReportViewComponent {
  @Input() report: DeepCompanyResearchReport | null = null;
  @Input() job: DeepCompanyResearchJob | null = null;
  @Input() comparison: DeepResearchReportComparison | null = null;
  @Input() busy = false;
  @Input() contentPackBusy = false;
  @Input() compact = false;
  @Input() showActions = true;
  @Input() showRefreshActions = false;
  @Input() showSectionActions = false;
  @Input() showContentPackAction = false;

  @Output() copyMarkdown = new EventEmitter<void>();
  @Output() downloadMarkdown = new EventEmitter<DeepCompanyResearchReport>();
  @Output() downloadPdf = new EventEmitter<DeepCompanyResearchReport>();
  @Output() refreshSources = new EventEmitter<void>();
  @Output() rebuildText = new EventEmitter<void>();
  @Output() createContentPack = new EventEmitter<void>();
  @Output() rebuildSection = new EventEmitter<DeepReportSectionAction>();
  @Output() saveReviewIdeas = new EventEmitter<string[]>();

  editingReviewIdeas = false;
  reviewIdeaDraft: string[] = [];

  deepReportBlocks(): DeepReportBlock[] {
    return this.rawDeepReportBlocks()
      .filter((block) => !this.isReviewIdeasSection(block.title));
  }

  private rawDeepReportBlocks(): DeepReportBlock[] {
    const report = this.report;
    if (!report) {
      return [];
    }

    const sections = (report.sections ?? [])
      .filter((section) => Boolean(section.title?.trim() || section.body?.trim()))
      .map((section, sectionIndex) => ({
        sectionIndex,
        title: section.title?.trim() || 'Раздел отчёта',
        body: section.body?.trim() || ''
      }));

    const blocks: RawDeepReportBlock[] = sections.length > 0
      ? sections
      : this.splitMarkdownIntoBlocks(report.reportMarkdown);

    return blocks.map((section, index) => ({
      key: `${index}-${section.title}`,
      sectionIndex: section.sectionIndex ?? index,
      title: section.title,
      icon: this.topicIcon(section.title),
      body: section.body,
      html: this.markdownToHtml(section.body)
    }));
  }

  reportQualityChecks(): DeepResearchQualityCheck[] {
    return this.report?.qualityChecks ?? [];
  }

  reportWarnings(): string[] {
    const qualityDetails = new Set(this.reportQualityChecks().map((check) => check.detail).filter(Boolean));
    return (this.report?.warnings ?? []).filter((warning) => !qualityDetails.has(warning));
  }

  reportQualitySummary(): { passed: number; total: number; label: string; tone: string; icon: string } {
    const checks = this.reportQualityChecks();
    const total = checks.length;
    const passed = checks.filter((check) => check.status === 'pass').length;
    if (total === 0) {
      return { passed, total, label: 'Чек-лист не собран', tone: 'gray', icon: 'rule' };
    }

    if (passed === total) {
      return { passed, total, label: 'Публичные факты проверены', tone: 'green', icon: 'verified' };
    }

    const failed = checks.some((check) => check.status === 'fail');
    return {
      passed,
      total,
      label: failed ? 'Есть пробелы в фактах' : 'Есть пункты на проверку',
      tone: failed ? 'red' : 'yellow',
      icon: failed ? 'error' : 'warning'
    };
  }

  confirmedFacts(): DeepResearchFactItem[] {
    return this.report?.factSnapshot?.confirmedFacts ?? [];
  }

  uncertainFacts(): DeepResearchFactItem[] {
    return this.report?.factSnapshot?.uncertainFacts ?? [];
  }

  sourceReviews(): DeepResearchSourceReview[] {
    return this.report?.factSnapshot?.sourceReviews ?? [];
  }

  reviewIdeas(): string[] {
    const explicit = (this.report?.reviewIdeas ?? [])
      .map((idea) => idea?.trim() ?? '')
      .filter(Boolean);
    if (explicit.length > 0) {
      return explicit.slice(0, 30);
    }
    return this.reviewIdeasFromSections();
  }

  startReviewIdeasEdit(): void {
    const ideas = this.reviewIdeas();
    this.reviewIdeaDraft = ideas.length > 0 ? [...ideas] : [''];
    this.editingReviewIdeas = true;
  }

  cancelReviewIdeasEdit(): void {
    this.editingReviewIdeas = false;
    this.reviewIdeaDraft = [];
  }

  addReviewIdea(): void {
    if (this.reviewIdeaDraft.length >= 30) {
      return;
    }
    this.reviewIdeaDraft = [...this.reviewIdeaDraft, ''];
  }

  removeReviewIdea(index: number): void {
    this.reviewIdeaDraft = this.reviewIdeaDraft.filter((_idea, ideaIndex) => ideaIndex !== index);
    if (this.reviewIdeaDraft.length === 0) {
      this.reviewIdeaDraft = [''];
    }
  }

  updateReviewIdea(index: number, event: Event): void {
    const target = event.target;
    const value = target instanceof HTMLTextAreaElement ? target.value : '';
    this.reviewIdeaDraft = this.reviewIdeaDraft.map((idea, ideaIndex) => ideaIndex === index ? value : idea);
  }

  emitSaveReviewIdeas(): void {
    const ideas = this.cleanReviewIdeas(this.reviewIdeaDraft);
    if (ideas.length === 0) {
      this.reviewIdeaDraft = [''];
      return;
    }
    this.saveReviewIdeas.emit(ideas);
    this.editingReviewIdeas = false;
    this.reviewIdeaDraft = ideas;
  }

  emitRebuildSection(block: DeepReportBlock, sectionIndex: number): void {
    this.rebuildSection.emit({ block, sectionIndex });
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
    if (normalized === 'official_site') {
      return 'Официальный сайт';
    }
    if (normalized === 'map_card') {
      return 'Карты';
    }
    if (normalized === 'directory') {
      return 'Справочник';
    }
    if (normalized === 'review_platform') {
      return 'Отзывы';
    }
    if (normalized === 'social') {
      return 'Соцсеть';
    }
    if (normalized === 'legal') {
      return 'Юридический';
    }
    if (normalized === 'aggregator') {
      return 'Агрегатор';
    }
    if (normalized === 'media') {
      return 'Медиа';
    }
    if (normalized === 'other') {
      return 'Другое';
    }
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

  trackDeepBlock(index: number, block: DeepReportBlock): string {
    return block.key || `${index}-${block.title}`;
  }

  trackText(index: number, text: string): string {
    return text || String(index);
  }

  trackReviewIdea(index: number, _text: string): string {
    return String(index);
  }

  trackQualityCheck(index: number, check: DeepResearchQualityCheck): string {
    return check.key || `${index}-${check.label}`;
  }

  trackSourceReview(index: number, source: DeepResearchSourceReview): string {
    return source.url || `${index}-${source.title}`;
  }

  trackFact(index: number, fact: DeepResearchFactItem): string {
    return `${index}-${fact.label}-${fact.value}`;
  }

  trackSectionChange(index: number, change: ReportSectionChange): string {
    return change.key || `${index}-${change.title}`;
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

  private reviewIdeasFromSections(): string[] {
    const ideas: string[] = [];
    for (const block of this.rawDeepReportBlocks()) {
      if (!this.isReviewIdeasSection(block.title)) {
        continue;
      }
      for (const line of block.body.split(/\r?\n/)) {
        const idea = line
          .replace(/^\s*(?:\d+[.)]|[-*])\s+/, '')
          .replace(/\s+/g, ' ')
          .trim();
        if (idea && idea !== line.trim() && !ideas.includes(idea)) {
          ideas.push(idea);
        }
        if (ideas.length >= 30) {
          return ideas;
        }
      }
    }
    return ideas;
  }

  private isReviewIdeasSection(title: string): boolean {
    const value = title.toLowerCase();
    return value.includes('иде')
      && value.includes('отзыв')
      && !/(пост|faq|карточ|контент|коммент|дозбор|спросить|уточн)/.test(value);
  }

  cleanReviewIdeas(values: string[]): string[] {
    const seen = new Set<string>();
    const result: string[] = [];
    for (const value of values) {
      const clean = value
        .replace(/^\s*(?:\d+[.)]|[-*])\s+/, '')
        .replace(/\s+/g, ' ')
        .trim();
      if (!clean || seen.has(clean)) {
        continue;
      }
      seen.add(clean);
      result.push(clean);
      if (result.length >= 30) {
        break;
      }
    }
    return result;
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
    if (/(сценари|утп|возраж|контент|тем|иде|пост|карточк)/.test(value)) {
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
}
