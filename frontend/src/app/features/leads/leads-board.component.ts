import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { CompanyCreateResult, CompanyCreateSource } from '../../core/company-create.api';
import {
  LeadBoard,
  LeadBucketKey,
  LeadCreateRequest,
  LeadEditOptions,
  LeadItem,
  LeadImportResponse,
  LeadPage,
  LeadPerson,
  LeadPersonOption,
  LeadsApi,
  LeadUpdateRequest
} from '../../core/leads.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { CompanyCreateModalComponent } from '../../shared/company-create-modal.component';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { ToastService } from '../../shared/toast.service';

type LeadMutation = 'send' | 'resend' | 'archive' | 'new' | 'toWork';

type LeadBucket = {
  key: LeadBucketKey;
  label: string;
  icon: string;
};

type PromoItem = {
  label: string;
  text: string;
};

type LeadMetric = {
  label: string;
  value: number;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink' | 'gray';
};

type CompanyCreateContext = {
  source: CompanyCreateSource;
  leadId: number | null;
};

@Component({
  selector: 'app-leads-board',
  imports: [AdminLayoutComponent, CompanyCreateModalComponent, DatePipe, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './leads-board.component.html',
  styleUrl: './leads-board.component.scss'
})
export class LeadsBoardComponent {
  private readonly leadsApi = inject(LeadsApi);
  private readonly auth = inject(AuthService);
  private readonly toastService = inject(ToastService);
  private readonly router = inject(Router);
  private readonly emptyPage: LeadPage = {
    content: [],
    pageNumber: 0,
    pageSize: 10,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true
  };

  readonly buckets: LeadBucket[] = [
    { key: 'toWork', label: 'В работу', icon: 'assignment_ind' },
    { key: 'newLeads', label: 'Новые', icon: 'fiber_new' },
    { key: 'send', label: 'Напомнить', icon: 'outgoing_mail' },
    { key: 'inWork', label: 'В работе', icon: 'work_history' },
    { key: 'all', label: 'Все', icon: 'dataset' }
  ];

  readonly pageSizeOptions = [5, 10, 15];
  readonly board = signal<LeadBoard | null>(null);
  readonly activeBucket = signal<LeadBucketKey>('newLeads');
  readonly keyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly commentDrafts = signal<Record<number, string>>({});
  readonly mobileMenuOpen = signal(false);
  readonly createDraft = signal<LeadCreateRequest | null>(null);
  readonly createSaving = signal(false);
  readonly createError = signal<string | null>(null);
  readonly companyCreateContext = signal<CompanyCreateContext | null>(null);
  readonly editOptions = signal<LeadEditOptions | null>(null);
  readonly editLead = signal<LeadItem | null>(null);
  readonly editDraft = signal<LeadUpdateRequest | null>(null);
  readonly editSaving = signal(false);
  readonly deleteSaving = signal(false);
  readonly editError = signal<string | null>(null);
  readonly importModalOpen = signal(false);
  readonly importFile = signal<File | null>(null);
  readonly importResult = signal<LeadImportResponse | null>(null);
  readonly importError = signal<string | null>(null);
  readonly importing = signal(false);

  readonly currentPage = computed(() => this.pageFor(this.activeBucket()));
  readonly currentLeads = computed(() => this.currentPage().content);
  readonly canChooseCreateManager = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly canDeleteLead = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly canCreateCompany = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  });
  readonly canTransferLead = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MARKETOLOG']);
  });
  readonly canImportLeads = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly canSeeAllBucket = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  readonly visibleBuckets = computed(() => this.canSeeAllBucket()
    ? this.buckets
    : this.buckets.filter((bucket) => bucket.key !== 'all')
  );
  readonly operatorOptions = computed(() => this.editOptions()?.operators ?? []);
  readonly managerOptions = computed(() => this.editOptions()?.managers ?? []);
  readonly marketologOptions = computed(() => this.editOptions()?.marketologs ?? []);
  readonly statusOptions = computed(() => this.editOptions()?.statuses ?? this.board()?.statuses ?? []);
  readonly sortTitle = computed(() => this.sortDirection() === 'desc'
    ? 'Сначала давно без изменений'
    : 'Сначала недавно измененные'
  );
  readonly promoItems = computed<PromoItem[]>(() => {
    const labels = ['предложение', 'напоминание', 'данные', 'ответы'];
    const texts = this.board()?.promoTexts ?? [];
    return labels.map((label, index) => ({ label, text: texts[index] ?? '' }));
  });
  readonly metrics = computed<LeadMetric[]>(() => {
    const board = this.board();

    const metrics: LeadMetric[] = [
      { label: 'Новые', value: board?.newLeads.totalElements ?? 0, icon: 'fiber_new', tone: 'yellow' },
      { label: 'В работу', value: board?.toWork.totalElements ?? 0, icon: 'badge', tone: 'green' },
      { label: 'В работе', value: board?.inWork.totalElements ?? 0, icon: 'work_history', tone: 'teal' },
      { label: 'Напомнить', value: board?.send.totalElements ?? 0, icon: 'notifications_active', tone: 'pink' },
      { label: 'Архив', value: board?.archive?.totalElements ?? 0, icon: 'archive', tone: 'gray' },
    ];

    if (this.canSeeAllBucket()) {
      metrics.push({ label: 'Всего', value: board?.all.totalElements ?? 0, icon: 'dashboard', tone: 'blue' });
    }

    return metrics;
  });

  constructor() {
    this.loadBoard();
  }

  loadBoard(): void {
    this.loading.set(true);
    this.error.set(null);

    this.leadsApi.getBoard({
      keyword: this.keyword(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection(),
      section: this.activeBucket()
    }).subscribe({
      next: (board) => {
        this.board.set(board);
        this.mergeCommentDrafts(board);
        this.loading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить лиды');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Лиды не загрузились', message);
      }
    });
  }

  search(): void {
    this.pageNumber.set(0);
    this.loadBoard();
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  setBucket(bucket: LeadBucketKey): void {
    this.activeBucket.set(bucket);
    this.pageNumber.set(0);
    this.loadBoard();
  }

  changePageSize(value: string | number): void {
    this.pageSize.set(Number(value));
    this.pageNumber.set(0);
    this.loadBoard();
  }

  toggleSortDirection(): void {
    this.sortDirection.update((direction) => direction === 'desc' ? 'asc' : 'desc');
    this.pageNumber.set(0);
    this.loadBoard();
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }

  previousPage(): void {
    if (this.currentPage().first) {
      return;
    }

    this.pageNumber.update((page) => Math.max(page - 1, 0));
    this.loadBoard();
  }

  nextPage(): void {
    if (this.currentPage().last) {
      return;
    }

    this.pageNumber.update((page) => page + 1);
    this.loadBoard();
  }

  bucketTotal(bucket: LeadBucketKey): number {
    return this.pageFor(bucket).totalElements;
  }

  commentFor(lead: LeadItem): string {
    return this.commentDrafts()[lead.id] ?? lead.commentsLead ?? '';
  }

  setComment(lead: LeadItem, value: string): void {
    this.commentDrafts.update((drafts) => ({
      ...drafts,
      [lead.id]: value
    }));
  }

  assigneeName(person?: LeadPerson): string {
    return person?.fio || person?.username || (person?.id ? `ID ${person.id}` : '-');
  }

  whatsappUrl(lead: LeadItem): string {
    return `https://wa.me/${lead.telephoneLead}`;
  }

  canCreateCompanyFromLead(lead: LeadItem): boolean {
    if (!this.canCreateCompany() || this.activeBucket() === 'inWork') {
      return false;
    }

    const status = (lead.lidStatus ?? '').trim().toLocaleLowerCase('ru-RU');
    return status !== 'в работе' && status !== 'в работа';
  }

  statusActions(lead: LeadItem): LeadMutation[] {
    if (lead.lidStatus === 'Новый') {
      return ['send'];
    }
    return this.canTransferLead()
      ? ['toWork', 'resend', 'archive']
      : ['resend', 'archive'];
  }

  actionLabel(action: LeadMutation): string {
    return {
      send: 'отправил',
      resend: 'напомнил',
      archive: 'архив',
      new: 'новый',
      toWork: 'передать'
    }[action];
  }

  actionIcon(action: LeadMutation): string {
    return {
      send: 'send',
      resend: 'notifications_active',
      archive: 'archive',
      new: 'fiber_new',
      toWork: 'swap_horiz'
    }[action];
  }

  runAction(lead: LeadItem, action: LeadMutation): void {
    const key = `${lead.id}:${action}`;
    this.mutationKey.set(key);
    this.error.set(null);

    this.requestForAction(lead, action).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Статус лида обновлен', `Лид #${lead.id}: ${this.actionLabel(action)}`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось изменить статус');
        this.error.set(message);
        this.mutationKey.set(null);
        this.toastService.error('Статус не изменился', message);
      }
    });
  }

  isMutating(lead: LeadItem, action: LeadMutation): boolean {
    return this.mutationKey() === `${lead.id}:${action}`;
  }

  async copyPhone(lead: LeadItem): Promise<void> {
    await this.copyText(lead.telephoneLead, 'телефон');
  }

  async copyPromo(item: PromoItem): Promise<void> {
    await this.copyText(item.text, item.label);
  }

  openCreateModal(): void {
    this.createDraft.set({
      telephoneLead: '',
      cityLead: '',
      commentsLead: '',
      managerId: null
    });
    this.createError.set(null);

    if (this.canChooseCreateManager()) {
      this.loadEditOptions(
        () => this.setDefaultCreateManager(),
        (message) => this.createError.set(message)
      );
    }
  }

  closeCreateModal(): void {
    if (this.createSaving()) {
      return;
    }

    this.createDraft.set(null);
    this.createError.set(null);
  }

  setCreateField<K extends keyof LeadCreateRequest>(field: K, value: LeadCreateRequest[K]): void {
    this.createDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  saveNewLead(): void {
    const draft = this.createDraft();
    if (!draft) {
      return;
    }

    this.createSaving.set(true);
    this.createError.set(null);

    this.leadsApi.createLead({
      telephoneLead: draft.telephoneLead.trim(),
      cityLead: draft.cityLead.trim(),
      commentsLead: draft.commentsLead?.trim(),
      managerId: this.canChooseCreateManager() ? draft.managerId ?? null : null
    }).subscribe({
      next: (lead) => {
        this.createSaving.set(false);
        this.closeCreateModal();
        this.activeBucket.set('newLeads');
        this.pageNumber.set(0);
        this.toastService.success('Лид создан', `Новый лид #${lead.id} добавлен`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось создать лид');
        this.createError.set(message);
        this.createSaving.set(false);
        this.toastService.error('Лид не создан', message);
      }
    });
  }

  openCompanyCreate(lead: LeadItem): void {
    if (!this.canCreateCompanyFromLead(lead)) {
      return;
    }

    this.companyCreateContext.set({ source: 'manager', leadId: lead.id });
  }

  openManualCompanyCreate(): void {
    if (!this.canCreateCompany()) {
      return;
    }

    this.companyCreateContext.set({ source: 'manual', leadId: null });
  }

  openLeadImport(): void {
    if (!this.canImportLeads()) {
      return;
    }

    this.importModalOpen.set(true);
    this.importFile.set(null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  closeLeadImport(): void {
    if (this.importing()) {
      return;
    }

    this.importModalOpen.set(false);
    this.importFile.set(null);
    this.importError.set(null);
  }

  selectLeadImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.importFile.set(input.files?.[0] ?? null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  uploadLeadImport(): void {
    const file = this.importFile();
    if (!file || this.importing()) {
      this.importError.set('Выберите CSV или Excel-файл.');
      return;
    }

    this.importing.set(true);
    this.importError.set(null);
    this.importResult.set(null);

    this.leadsApi.importLeads(file).subscribe({
      next: (result) => {
        this.importing.set(false);
        this.importResult.set(result);
        this.importFile.set(null);
        this.toastService.success('Импорт лидов завершен', this.importResultMessage(result));
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось импортировать лиды');
        this.importError.set(message);
        this.importing.set(false);
        this.toastService.error('Лиды не импортированы', message);
      }
    });
  }

  importResultMessage(result: LeadImportResponse): string {
    const duplicateText = result.skippedDuplicates ? `, дублей пропущено: ${result.skippedDuplicates}` : '';
    const invalidText = result.skippedInvalid ? `, строк с ошибками: ${result.skippedInvalid}` : '';
    return `Добавлено: ${result.added}${duplicateText}${invalidText}`;
  }

  closeCompanyCreate(): void {
    this.companyCreateContext.set(null);
  }

  handleCompanyCreated(result: CompanyCreateResult): void {
    this.closeCompanyCreate();
    this.toastService.success('Компания создана', `${result.title} добавлена в работу`);
    void this.router.navigate(['/manager'], {
      queryParams: {
        section: 'companies',
        status: 'Новая'
      }
    });
  }

  openEditModal(lead: LeadItem): void {
    this.editLead.set(lead);
    this.editDraft.set({
      telephoneLead: lead.telephoneLead,
      cityLead: lead.cityLead,
      commentsLead: this.commentFor(lead),
      lidStatus: lead.lidStatus,
      operatorId: lead.operator?.id ?? null,
      managerId: lead.manager?.id ?? null,
      marketologId: lead.marketolog?.id ?? null
    });
    this.editError.set(null);
    this.deleteSaving.set(false);

    if (!this.editOptions()) {
      this.loadEditOptions(undefined, (message) => this.editError.set(message));
    }
  }

  closeEditModal(): void {
    if (this.editSaving() || this.deleteSaving()) {
      return;
    }

    this.editLead.set(null);
    this.editDraft.set(null);
    this.editError.set(null);
  }

  setEditField<K extends keyof LeadUpdateRequest>(field: K, value: LeadUpdateRequest[K]): void {
    this.editDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  saveLeadEdit(): void {
    const lead = this.editLead();
    const draft = this.editDraft();

    if (!lead || !draft) {
      return;
    }

    this.editSaving.set(true);
    this.editError.set(null);

    this.leadsApi.updateLead(lead.id, draft).subscribe({
      next: (updatedLead) => {
        this.editSaving.set(false);
        this.applyUpdatedLead(updatedLead);
        this.closeEditModal();
        this.toastService.success('Лид сохранен', `Изменения по лиду #${lead.id} применены`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить лид');
        this.editError.set(message);
        this.editSaving.set(false);
        this.toastService.error('Лид не сохранен', message);
      }
    });
  }

  deleteCurrentLead(): void {
    const lead = this.editLead();

    if (!lead || this.deleteSaving()) {
      return;
    }

    const confirmed = window.confirm(`Удалить лид #${lead.id}? Это действие нельзя отменить.`);
    if (!confirmed) {
      return;
    }

    this.deleteSaving.set(true);
    this.editError.set(null);

    this.leadsApi.deleteLead(lead.id).subscribe({
      next: () => {
        this.deleteSaving.set(false);
        this.closeEditModal();
        this.toastService.success('Лид удален', `Лид #${lead.id} удален`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить лид');
        this.editError.set(message);
        this.deleteSaving.set(false);
        this.toastService.error('Лид не удален', message);
      }
    });
  }

  optionLabel(option: LeadPersonOption): string {
    return option.fio || option.username || (option.email ? option.email : `ID ${option.id}`);
  }

  trackLead(_index: number, lead: LeadItem): number {
    return lead.id;
  }

  trackBucket(_index: number, bucket: LeadBucket): LeadBucketKey {
    return bucket.key;
  }

  trackMetric(_index: number, metric: LeadMetric): string {
    return metric.label;
  }

  trackOption(_index: number, option: LeadPersonOption): number {
    return option.id;
  }

  private requestForAction(lead: LeadItem, action: LeadMutation): Observable<void> {
    switch (action) {
      case 'send':
        return this.leadsApi.markSend(lead.id);
      case 'resend':
        return this.leadsApi.markResend(lead.id);
      case 'archive':
        return this.leadsApi.markArchive(lead.id);
      case 'new':
        return this.leadsApi.markNew(lead.id);
      case 'toWork':
        return this.leadsApi.markToWork(lead.id, this.commentFor(lead));
    }
  }

  private pageFor(bucket: LeadBucketKey): LeadPage {
    return this.board()?.[bucket] ?? this.emptyPage;
  }

  private loadEditOptions(onSuccess?: () => void, onError?: (message: string) => void): void {
    if (this.editOptions()) {
      onSuccess?.();
      return;
    }

    this.leadsApi.getEditOptions().subscribe({
      next: (options) => {
        this.editOptions.set(options);
        onSuccess?.();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить списки для редактирования');
        onError?.(message);
        this.toastService.error('Списки не загрузились', message);
      }
    });
  }

  private setDefaultCreateManager(): void {
    const draft = this.createDraft();
    const managerId = this.managerOptions()[0]?.id ?? null;

    if (!draft || draft.managerId !== null || managerId === null) {
      return;
    }

    this.setCreateField('managerId', managerId);
  }

  private mergeCommentDrafts(board: LeadBoard): void {
    const leads = [
      ...board.toWork.content,
      ...board.newLeads.content,
      ...board.send.content,
      ...(board.archive?.content ?? []),
      ...board.inWork.content,
      ...board.all.content
    ];

    this.commentDrafts.update((drafts) => {
      const next = { ...drafts };
      leads.forEach((lead) => {
        if (next[lead.id] === undefined) {
          next[lead.id] = lead.commentsLead ?? '';
        }
      });
      return next;
    });
  }

  private applyUpdatedLead(updatedLead: LeadItem): void {
    this.editLead.set(updatedLead);
    this.commentDrafts.update((drafts) => ({
      ...drafts,
      [updatedLead.id]: updatedLead.commentsLead ?? ''
    }));

    this.board.update((board) => board ? {
      ...board,
      toWork: this.replaceLeadInPage(board.toWork, updatedLead),
      newLeads: this.replaceLeadInPage(board.newLeads, updatedLead),
      send: this.replaceLeadInPage(board.send, updatedLead),
      archive: board.archive ? this.replaceLeadInPage(board.archive, updatedLead) : board.archive,
      inWork: this.replaceLeadInPage(board.inWork, updatedLead),
      all: this.replaceLeadInPage(board.all, updatedLead)
    } : board);
  }

  private replaceLeadInPage(page: LeadPage, updatedLead: LeadItem): LeadPage {
    let changed = false;
    const content = page.content.map((lead) => {
      if (lead.id !== updatedLead.id) {
        return lead;
      }

      changed = true;
      return updatedLead;
    });

    return changed ? { ...page, content } : page;
  }

  private async copyText(value: string, label: string): Promise<void> {
    if (!value) {
      this.toastService.info('Нечего копировать', `Поле "${label}" пустое`);
      return;
    }

    try {
      await navigator.clipboard.writeText(value);
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = value;
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      document.body.append(textarea);
      textarea.select();
      document.execCommand('copy');
      textarea.remove();
    }

    this.copied.set(label);
    this.toastService.success('Скопировано', label);
    window.setTimeout(() => this.copied.set(null), 1400);
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
