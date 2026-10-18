import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CabinetApi, ScoreContractorPaymentSummary, ScoreResponse, ScoreUser } from '../../core/cabinet.api';
import {
  AdminUsersApi,
  ContractorAccrualAdjustmentKind,
  ContractorAccrualAdjustmentPreview,
  ContractorAccrualAdjustmentRequest
} from '../../core/admin-users.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { businessDateIso } from '../../shared/business-date';
import {
  scoreMonthLabel,
  scoreOrphanPayments,
  scoreOutstandingDebtKopecks,
  scoreOutstandingReservedKopecks,
  scorePaymentsForUser
} from './score-finance.helpers';

type ScoreGroupKey = 'managers' | 'workers' | 'operators' | 'marketologs' | 'savedBalances';

type ScoreSection = {
  key: ScoreGroupKey;
  title: string;
  icon: string;
  financialOnly?: boolean;
};

type AccrualTarget = {
  userId: number;
  fio: string;
  kind: ContractorAccrualAdjustmentKind;
  profiles: ScoreContractorPaymentSummary[];
};

type AccrualDraft = {
  amountRubles: string;
  referenceMonth: string;
  reason: string;
  evidenceReference: string;
  idempotencyKey: string;
};

@Component({
  selector: 'app-score',
  imports: [AdminLayoutComponent, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './score.component.html',
  styleUrl: './score.component.scss'
})
export class ScoreComponent {
  readonly selectedDate = signal(this.todayIso());
  readonly score = signal<ScoreResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly accrualTarget = signal<AccrualTarget | null>(null);
  readonly accrualProfileId = signal<number | null>(null);
  readonly accrualDraft = signal<AccrualDraft | null>(null);
  readonly accrualPreview = signal<ContractorAccrualAdjustmentPreview | null>(null);
  readonly accrualPreviewKey = signal<string | null>(null);
  readonly accrualPreviewLoading = signal(false);
  readonly accrualSaving = signal(false);
  readonly accrualError = signal<string | null>(null);
  readonly accrualSuccess = signal<string | null>(null);
  private accrualRequestVersion = 0;

  readonly sections: ScoreSection[] = [
    { key: 'managers', title: 'Менеджеры', icon: 'groups' },
    { key: 'workers', title: 'Работники', icon: 'engineering' },
    { key: 'operators', title: 'Операторы', icon: 'support_agent' },
    { key: 'marketologs', title: 'Маркетологи', icon: 'campaign' }
  ];
  private readonly savedBalancesSection: ScoreSection = {
    key: 'savedBalances',
    title: 'Сохранённые остатки',
    icon: 'account_balance_wallet',
    financialOnly: true
  };

  constructor(
    private readonly cabinetApi: CabinetApi,
    private readonly adminUsersApi: AdminUsersApi
  ) {
    this.load();
  }

  load(forceRefresh = false): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getScore(this.selectedDate(), { forceRefresh }).subscribe({
      next: (response) => {
        this.score.set(response);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(apiErrorDetail(error, 'Обновите данные через пару минут или обратитесь к администратору.'));
        this.loading.set(false);
      }
    });
  }

  refresh(): void {
    this.load(true);
  }

  selectDate(date: string): void {
    this.selectedDate.set(date);
    this.load();
  }

  users(section: ScoreSection): ScoreUser[] {
    if (section.key === 'savedBalances') {
      return this.savedContractorUsers();
    }
    return this.score()?.groups[section.key] ?? [];
  }

  visibleSections(): ScoreSection[] {
    return this.savedContractorPayments().length
      ? [...this.sections, this.savedBalancesSection]
      : this.sections;
  }

  savedContractorPayments(): ScoreContractorPaymentSummary[] {
    const response = this.score();
    if (!response?.financeVisible) {
      return [];
    }
    const groups = response.groups;
    const visibleUserIds = [
      ...groups.managers,
      ...groups.workers,
      ...groups.operators,
      ...groups.marketologs
    ]
      .map((user) => user.userId)
      .filter((userId): userId is number => userId != null);
    return scoreOrphanPayments(response.contractorPayments ?? [], visibleUserIds);
  }

  private savedContractorUsers(): ScoreUser[] {
    const users = new Map<number, ScoreUser>();
    for (const payment of this.savedContractorPayments()) {
      if (!users.has(payment.userId)) {
        users.set(payment.userId, {
          fio: payment.fio || `Профиль #${payment.profileId}`,
          role: 'Сохранённый финансовый профиль',
          userId: payment.userId
        });
      }
    }
    return [...users.values()];
  }

  contractorPaymentsFor(user: ScoreUser): ScoreContractorPaymentSummary[] {
    if (!this.score()?.financeVisible || !user.userId) {
      return [];
    }
    const rows = this.score()?.contractorPayments ?? [];
    return scorePaymentsForUser(rows, user.userId, this.contractorRoleForUser(user));
  }

  eligibleAccrualProfiles(user: ScoreUser): ScoreContractorPaymentSummary[] {
    if (!this.contractorRoleForUser(user)) {
      return [];
    }
    return this.contractorPaymentsFor(user)
      .filter((row) => row.profileEnabled && (row.role === 'SPECIALIST' || row.role === 'MANAGER'));
  }

  openAccrual(user: ScoreUser, kind: ContractorAccrualAdjustmentKind): void {
    if (!this.score()?.financeVisible || !user.userId || this.accrualSaving()) {
      return;
    }
    const profiles = this.eligibleAccrualProfiles(user);
    if (!profiles.length) {
      return;
    }
    this.accrualRequestVersion++;
    this.accrualTarget.set({ userId: user.userId, fio: user.fio, kind, profiles });
    this.accrualProfileId.set(profiles[0].profileId);
    this.accrualDraft.set({
      amountRubles: '',
      referenceMonth: this.currentAccrualMonth(),
      reason: '',
      evidenceReference: '',
      idempotencyKey: this.newAccrualKey()
    });
    this.accrualPreview.set(null);
    this.accrualPreviewKey.set(null);
    this.accrualPreviewLoading.set(false);
    this.accrualError.set(null);
    this.accrualSuccess.set(null);
  }

  closeAccrual(): void {
    if (this.accrualSaving()) {
      return;
    }
    this.accrualRequestVersion++;
    this.accrualTarget.set(null);
    this.accrualProfileId.set(null);
    this.accrualDraft.set(null);
    this.accrualPreview.set(null);
    this.accrualPreviewKey.set(null);
    this.accrualPreviewLoading.set(false);
    this.accrualError.set(null);
  }

  selectAccrualProfile(value: string): void {
    const profileId = Number(value);
    if (!this.accrualTarget()?.profiles.some((row) => row.profileId === profileId)) {
      return;
    }
    this.accrualProfileId.set(profileId);
    this.invalidateAccrualPreview();
  }

  updateAccrualDraft(field: 'amountRubles' | 'referenceMonth' | 'reason' | 'evidenceReference', value: string): void {
    const draft = this.accrualDraft();
    if (!draft) {
      return;
    }
    this.accrualDraft.set({ ...draft, [field]: value, idempotencyKey: this.newAccrualKey() });
    this.invalidateAccrualPreview();
  }

  previewAccrual(): void {
    const target = this.accrualTarget();
    const profileId = this.accrualProfileId();
    if (!target || profileId == null || this.accrualPreviewLoading() || this.accrualSaving()) {
      return;
    }
    const request = this.accrualAdjustmentRequest();
    if (!request) {
      return;
    }
    const version = ++this.accrualRequestVersion;
    this.accrualError.set(null);
    this.accrualPreviewLoading.set(true);
    this.adminUsersApi.previewContractorAccrualAdjustment(target.userId, profileId, request).subscribe({
      next: (preview) => {
        if (version !== this.accrualRequestVersion) {
          return;
        }
        this.accrualPreviewLoading.set(false);
        this.accrualPreview.set(preview);
        this.accrualPreviewKey.set(request.idempotencyKey ?? null);
      },
      error: (error) => {
        if (version !== this.accrualRequestVersion) {
          return;
        }
        this.accrualPreviewLoading.set(false);
        this.accrualError.set(apiErrorDetail(error, 'Не удалось проверить расчёт.'));
      }
    });
  }

  saveAccrual(): void {
    const target = this.accrualTarget();
    const profileId = this.accrualProfileId();
    const draft = this.accrualDraft();
    if (!target || profileId == null || !draft || this.accrualSaving()) {
      return;
    }
    if (!this.accrualPreview() || this.accrualPreviewKey() !== draft.idempotencyKey) {
      this.accrualError.set('Сначала проверьте расчёт до и после корректировки.');
      return;
    }
    const request = this.accrualAdjustmentRequest();
    if (!request) {
      return;
    }
    const version = ++this.accrualRequestVersion;
    this.accrualSaving.set(true);
    this.accrualError.set(null);
    this.adminUsersApi.createContractorAccrualAdjustment(target.userId, profileId, request).subscribe({
      next: () => {
        if (version !== this.accrualRequestVersion) {
          return;
        }
        this.accrualSaving.set(false);
        this.closeAccrual();
        this.accrualSuccess.set(`${target.kind === 'BONUS' ? 'Премия' : 'Удержание'} для ${target.fio} проведено.`);
        this.refresh();
      },
      error: (error) => {
        if (version !== this.accrualRequestVersion) {
          return;
        }
        this.accrualSaving.set(false);
        this.accrualError.set(apiErrorDetail(error, 'Не удалось провести корректировку.'));
        if (error?.status === 409) {
          this.invalidateAccrualPreview();
          this.refresh();
        }
      }
    });
  }

  currentAccrualMonth(): string {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Irkutsk', year: 'numeric', month: '2-digit'
    }).formatToParts(new Date());
    return `${parts.find((part) => part.type === 'year')?.value ?? ''}-${parts.find((part) => part.type === 'month')?.value ?? ''}`;
  }

  private invalidateAccrualPreview(): void {
    this.accrualRequestVersion++;
    this.accrualPreview.set(null);
    this.accrualPreviewKey.set(null);
    this.accrualPreviewLoading.set(false);
    this.accrualError.set(null);
  }

  private accrualAdjustmentRequest(): ContractorAccrualAdjustmentRequest | null {
    const target = this.accrualTarget();
    const draft = this.accrualDraft();
    if (!target || !draft) {
      return null;
    }
    const normalized = draft.amountRubles.trim().replace(',', '.');
    if (!/^\d{1,12}(?:\.\d{1,2})?$/.test(normalized)) {
      this.accrualError.set('Укажите положительную сумму в рублях с точностью до копейки.');
      return null;
    }
    const [rubles, kopecks = ''] = normalized.split('.');
    const amountKopecks = Number(rubles) * 100 + Number(kopecks.padEnd(2, '0'));
    if (!Number.isSafeInteger(amountKopecks) || amountKopecks <= 0 || amountKopecks > 100_000_000_000) {
      this.accrualError.set('Сумма должна быть больше нуля и не превышать 1 млрд рублей.');
      return null;
    }
    const reason = draft.reason.trim();
    if (!reason || reason.length > 255) {
      this.accrualError.set('Укажите понятную причину до 255 символов; её увидит сотрудник.');
      return null;
    }
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(draft.referenceMonth)
      || draft.referenceMonth > this.currentAccrualMonth()) {
      this.accrualError.set('Укажите месяц основания не позднее текущего.');
      return null;
    }
    return {
      kind: target.kind,
      amountKopecks,
      reason,
      referenceMonth: draft.referenceMonth,
      evidenceReference: draft.evidenceReference.trim() || undefined,
      idempotencyKey: draft.idempotencyKey
    };
  }

  private newAccrualKey(): string {
    const uuid = globalThis.crypto?.randomUUID?.();
    return uuid ? `score-ui-${uuid}` : `score-ui-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }

  rows(section: ScoreSection, user: ScoreUser): Array<{ label: string; value: string }> {
    if (section.key === 'managers') {
      return [
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        user.newCompanies != null ? { label: 'Новые компании', value: this.count(user.newCompanies) } : null,
        this.financeRow('Оборот', user.totalSum)
      ].filter(Boolean) as Array<{ label: string; value: string }>;
    }

    if (section.key === 'workers') {
      return [
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        { label: 'Выгул', value: this.count(user.inVigul) },
        { label: 'Публикация', value: this.count(user.inPublish) }
      ].filter(Boolean) as Array<{ label: string; value: string }>;
    }

    return [
      this.accruedFinanceRow(user),
      { label: 'Новые', value: this.count(user.leadsNew) },
      { label: 'В работе', value: this.count(user.leadsInWork) },
      { label: 'Конверсия', value: `${user.percentInWork || 0}%` }
    ].filter(Boolean) as Array<{ label: string; value: string }>;
  }

  imageUrl(imageId?: number | null): string {
    return this.cabinetApi.imageUrl(imageId);
  }

  userTrack(user: ScoreUser): string {
    return `${user.role}-${user.userId || user.fio}`;
  }


  private contractorRoleForUser(user: ScoreUser): string | null {
    if (user.role === 'ROLE_MANAGER') {
      return 'MANAGER';
    }
    if (user.role === 'ROLE_WORKER') {
      return 'SPECIALIST';
    }
    return null;
  }

  contractorStatusLabel(row: ScoreContractorPaymentSummary): string {
    if (!row.profileEnabled) {
      return 'выключен · остаток сохранён';
    }
    if (!row.liveEnabled) {
      return 'реквизиты выключены · учёт сохранён';
    }
    return row.reportingLive ? 'LIVE' : 'тестовый расчёт';
  }

  contractorRoleLabel(row: ScoreContractorPaymentSummary): string {
    return row.role === 'MANAGER' ? 'менеджер' : 'специалист';
  }

  selectedMonthLabel(): string {
    return scoreMonthLabel(this.score()?.date || this.selectedDate());
  }

  outstandingDebtKopecks(row: ScoreContractorPaymentSummary): number {
    return scoreOutstandingDebtKopecks(row);
  }

  outstandingReservedKopecks(row: ScoreContractorPaymentSummary): number {
    return scoreOutstandingReservedKopecks(row);
  }

  moneyKopecks(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format((value || 0) / 100)} ₽`;
  }

  private financeRow(label: string, value?: number | null, suffix = ' руб.'): { label: string; value: string } | null {
    if (!this.score()?.financeVisible || value == null) {
      return null;
    }

    return {
      label,
      value: `${new Intl.NumberFormat('ru-RU').format(value || 0)}${suffix}`
    };
  }

  private accruedFinanceRow(user: ScoreUser): { label: string; value: string } | null {
    const kopecks = user.salaryKopecks ?? (user.salary == null ? null : user.salary * 100);
    if (!this.score()?.financeVisible || kopecks == null) {
      return null;
    }
    return { label: 'Начислено', value: this.moneyKopecks(kopecks) };
  }

  private count(value?: number | null): string {
    return `${this.amount(value)} шт.`;
  }

  private amount(value?: number | null): string {
    return new Intl.NumberFormat('ru-RU').format(value || 0);
  }

  private todayIso(): string {
    return businessDateIso();
  }
}
