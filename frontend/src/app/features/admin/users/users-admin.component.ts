import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  AdminUser,
  AdminUsersApi,
  AssignmentOptions,
  ChangeKeycloakPasswordRequest,
  ContractorPaymentProfile,
  ContractorPaymentProfileAdjustment,
  ContractorPaymentProfileRequest,
  UpdateKeycloakUserRequest,
  UpdateUserAssignmentsRequest,
  UserAssignments
} from '../../../core/admin-users.api';
import { appEnvironment } from '../../../core/app-environment';
import { AuthService } from '../../../core/auth.service';
import { CabinetApi } from '../../../core/cabinet.api';
import {
  ContractorDirectSettlement,
  ContractorDirectSettlementRequest,
  ContractorPaymentAllocationJournalItem,
  ContractorPaymentAllocationStatus,
  ContractorPaymentJournalPage,
  ContractorPaymentMode,
  ContractorPaymentQueueHealth,
  ContractorRoutingDecisionReason,
  ContractorPaymentSourceType,
  ContractorPaymentsApi
} from '../../../core/contractor-payments.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { copyTextToClipboard } from '../../../shared/clipboard-copy';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';
import {
  canEditContractorProfileRouting,
  contractorProfileRoutingPresentation
} from './contractor-profile-routing';
import { validateContractorTransferIdentifierForSave } from './contractor-transfer-identifier';

type UserStatusFilter = 'all' | 'active' | 'inactive' | 'linked' | 'unlinked';

export const CONTRACTOR_PAYMENT_SOURCE_FILTER_OPTIONS: ReadonlyArray<{
  value: ContractorPaymentSourceType;
  label: string;
}> = [
  { value: 'PAYMENT_LINK', label: 'Платёжная ссылка' },
  { value: 'COMMON_INVOICE', label: 'Общий счёт' },
  { value: 'DIRECT_SETTLEMENT', label: 'Прямой перевод' },
  { value: 'ACTUAL_PAYMENT', label: 'Фактическое поступление' }
];

export function contractorPaymentSourceLabel(sourceType: ContractorPaymentSourceType): string {
  return CONTRACTOR_PAYMENT_SOURCE_FILTER_OPTIONS.find(option => option.value === sourceType)?.label
    ?? sourceType;
}

type UserStatusTab = {
  key: UserStatusFilter;
  label: string;
  icon: string;
};

type UserMetric = {
  label: string;
  value: number;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink' | 'gray';
};

type OwnerControlViewMode = 'OWN_MANAGERS' | 'ALL_MANAGERS';
type AssignmentIdControlName = 'managerIds' | 'workerIds' | 'operatorIds' | 'marketologIds';

type ContractorPaymentProfileDraft = ContractorPaymentProfile & {
  openingBalanceRubles: string;
  initialOpeningBalanceKopecks: number;
  openingBalanceReason: string;
  savedEnabled: boolean;
  savedLiveEnabled: boolean;
};

type ContractorReturnDraft = {
  amountRubles: string;
  reason: string;
};

type ContractorDirectSettlementDraft = {
  amountRubles: string;
  effectiveAt: string;
  reason: string;
  evidenceReference: string;
  idempotencyKey: string;
};

@Component({
  selector: 'app-users-admin',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './users-admin.component.html',
  styleUrl: './users-admin.component.scss'
})
export class UsersAdminComponent implements OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly adminUsersApi = inject(AdminUsersApi);
  private readonly auth = inject(AuthService);
  private readonly cabinetApi = inject(CabinetApi);
  private readonly contractorPaymentsApi = inject(ContractorPaymentsApi);
  private readonly toastService = inject(ToastService);

  readonly availableRoles = ['ADMIN', 'OWNER', 'MANAGER', 'OPERATOR', 'WORKER', 'PERFORMER', 'MARKETOLOG', 'CLIENT'];
  readonly statusTabs: UserStatusTab[] = [
    { key: 'all', label: 'Все', icon: 'groups' },
    { key: 'active', label: 'Активные', icon: 'how_to_reg' },
    { key: 'inactive', label: 'Отключены', icon: 'person_off' },
    { key: 'linked', label: 'Keycloak', icon: 'verified_user' },
    { key: 'unlinked', label: 'Миграция', icon: 'sync_problem' }
  ];
  readonly pageSizeOptions = [8, 15, 30];
  readonly users = signal<AdminUser[]>([]);
  readonly selectedUser = signal<AdminUser | null>(null);
  readonly userSearch = signal('');
  readonly roleFilter = signal('all');
  readonly statusFilter = signal<UserStatusFilter>('all');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(8);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleteSaving = signal(false);
  readonly telegramResetting = signal(false);
  readonly assignmentsLoading = signal(false);
  readonly assignmentsSaving = signal(false);
  readonly passwordSaving = signal(false);
  readonly photoUploading = signal(false);
  readonly error = signal<string | null>(null);
  readonly deleteError = signal<string | null>(null);
  readonly assignmentsError = signal<string | null>(null);
  readonly passwordError = signal<string | null>(null);
  readonly photoError = signal<string | null>(null);
  readonly contractorProfiles = signal<ContractorPaymentProfileDraft[]>([]);
  readonly contractorProfilesLoading = signal(false);
  readonly contractorProfileSavingId = signal<number | null>(null);
  readonly contractorProfilesError = signal<string | null>(null);
  readonly contractorTransferIdentifierTouched = signal<Record<number, boolean>>({});
  readonly contractorOpeningHistory = signal<Record<number, ContractorPaymentProfileAdjustment[]>>({});
  readonly contractorOpeningHistoryLoadingId = signal<number | null>(null);
  readonly contractorOpeningHistoryError = signal<string | null>(null);
  readonly contractorDirectSettlements = signal<Record<number, ContractorDirectSettlement[]>>({});
  readonly contractorDirectSettlementsLoadingId = signal<number | null>(null);
  readonly contractorDirectSettlementsError = signal<string | null>(null);
  readonly contractorDirectSettlementDrafts = signal<Record<string, ContractorDirectSettlementDraft>>({});
  readonly contractorDirectSettlementSavingKey = signal<string | null>(null);
  readonly contractorQueueHealth = signal<ContractorPaymentQueueHealth | null>(null);
  readonly contractorQueueHealthLoading = signal(false);
  readonly contractorQueueHealthError = signal<string | null>(null);
  readonly contractorJournal = signal<ContractorPaymentJournalPage | null>(null);
  readonly contractorJournalLoading = signal(false);
  readonly contractorJournalError = signal<string | null>(null);
  readonly contractorJournalStatus = signal<ContractorPaymentAllocationStatus | ''>('');
  readonly contractorJournalMode = signal<ContractorPaymentMode | ''>('');
  readonly contractorJournalSourceType = signal<ContractorPaymentSourceType | ''>('');
  readonly contractorJournalSourceOptions = CONTRACTOR_PAYMENT_SOURCE_FILTER_OPTIONS;
  readonly contractorJournalSourceId = signal<number | null>(null);
  readonly contractorJournalAllUsers = signal(false);
  readonly contractorJournalPage = signal(0);
  readonly contractorJournalPageSize = 10;
  readonly contractorReturnDrafts = signal<Record<number, ContractorReturnDraft>>({});
  readonly contractorReturnSavingId = signal<number | null>(null);
  readonly contractorReturnError = signal<string | null>(null);
  readonly contractorJournalStatuses: ContractorPaymentAllocationStatus[] = [
    'RESERVED',
    'CLIENT_REPORTED',
    'PARTIALLY_CONFIRMED',
    'CONFIRMED',
    'SIMULATED_PAID',
    'LATE_PAYMENT_AFTER_RELEASE',
    'OWNER_FALLBACK',
    'RELEASED_UNPAID',
    'EXPIRED',
    'CANCELED',
    'PARTIALLY_RETURNED',
    'RETURN_AMOUNT_PENDING',
    'RETURNED'
  ];
  readonly savedUser = signal<AdminUser | null>(null);
  readonly savedAssignments = signal<UserAssignments | null>(null);
  readonly savedPasswordFor = signal<string | null>(null);
  readonly savedPhotoFor = signal<string | null>(null);
  readonly profilePhotoFile = signal<File | null>(null);
  readonly profilePhotoPreviewUrl = signal<string | null>(null);
  readonly hasUnsavedUserChanges = signal(false);
  readonly canManageContractorProfiles = computed(() => {
    this.auth.tokenParsed();
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  });
  private readonly subscriptions = new Subscription();
  private formBaseline = '';
  private contractorJournalRequest = 0;
  private contractorProfilesRequest = 0;
  readonly assignmentOptions = signal<AssignmentOptions>({
    managers: [],
    workers: [],
    operators: [],
    marketologs: []
  });

  readonly keycloakLinkedUsers = computed(() => this.users().filter((user) => user.keycloakLinked).length);
  readonly activeUsers = computed(() => this.users().filter((user) => user.active).length);
  readonly unlinkedUsers = computed(() => this.users().filter((user) => !user.keycloakLinked).length);
  readonly inactiveUsers = computed(() => this.users().filter((user) => !user.active).length);
  readonly metrics = computed<UserMetric[]>(() => [
    { label: 'Всего', value: this.users().length, icon: 'groups', tone: 'blue' },
    { label: 'Активные', value: this.activeUsers(), icon: 'how_to_reg', tone: 'green' },
    { label: 'Отключены', value: this.inactiveUsers(), icon: 'person_off', tone: 'gray' },
    { label: 'В Keycloak', value: this.keycloakLinkedUsers(), icon: 'verified_user', tone: 'teal' },
    { label: 'Миграция', value: this.unlinkedUsers(), icon: 'sync_problem', tone: 'yellow' }
  ]);
  readonly filteredUsers = computed(() => {
    const query = this.userSearch().trim().toLowerCase();
    const role = this.roleFilter();
    const status = this.statusFilter();

    return this.users().filter((user) => {
      const haystack = [
        user.username,
        user.email,
        user.fio,
        user.phoneNumber,
        user.authProvider,
        ...(user.roles ?? [])
      ].filter(Boolean).join(' ').toLowerCase();

      const matchesQuery = !query || haystack.includes(query);
      const matchesRole = role === 'all' || user.roles.includes(role);
      const matchesStatus =
        status === 'all' ||
        (status === 'active' && user.active) ||
        (status === 'inactive' && !user.active) ||
        (status === 'linked' && user.keycloakLinked) ||
        (status === 'unlinked' && !user.keycloakLinked);

      return matchesQuery && matchesRole && matchesStatus;
    });
  });
  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.filteredUsers().length / this.pageSize())));
  readonly currentPageIndex = computed(() => Math.min(this.pageNumber(), this.totalPages() - 1));
  readonly paginatedUsers = computed(() => {
    const pageSize = this.pageSize();
    const start = this.currentPageIndex() * pageSize;

    return this.filteredUsers().slice(start, start + pageSize);
  });
  readonly pageStart = computed(() => {
    if (this.filteredUsers().length === 0) {
      return 0;
    }

    return this.currentPageIndex() * this.pageSize() + 1;
  });
  readonly pageEnd = computed(() => Math.min(
    this.filteredUsers().length,
    (this.currentPageIndex() + 1) * this.pageSize()
  ));
  readonly visiblePageNumbers = computed(() => {
    const totalPages = this.totalPages();
    const currentPage = this.currentPageIndex();
    const start = Math.max(0, Math.min(currentPage - 2, totalPages - 5));
    const end = Math.min(totalPages, start + 5);

    return Array.from({ length: end - start }, (_item, index) => start + index);
  });
  readonly selectedProfilePhotoUrl = computed(() => {
    const previewUrl = this.profilePhotoPreviewUrl();
    if (previewUrl) {
      return previewUrl;
    }

    const imageId = this.selectedUser()?.imageId;
    return imageId ? `${appEnvironment.backendBaseUrl}/images/${imageId}` : null;
  });

  readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required]],
    email: ['', [Validators.email]],
    fio: [''],
    phoneNumber: [''],
    coefficient: ['0.05'],
    workerChatUrl: [''],
    managerAuditChatUrl: [''],
    enabled: [true],
    roles: this.fb.nonNullable.control<string[]>([], [Validators.required])
  });

  readonly passwordForm = this.fb.nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(6)]]
  });

  readonly assignmentForm = this.fb.nonNullable.group({
    ownerControlViewMode: this.fb.nonNullable.control<OwnerControlViewMode>('OWN_MANAGERS'),
    managerIds: this.fb.nonNullable.control<number[]>([]),
    workerIds: this.fb.nonNullable.control<number[]>([]),
    operatorIds: this.fb.nonNullable.control<number[]>([]),
    marketologIds: this.fb.nonNullable.control<number[]>([])
  });

  constructor() {
    this.subscriptions.add(
      this.form.valueChanges.subscribe(() => {
        this.hasUnsavedUserChanges.set(this.currentFormSnapshot() !== this.formBaseline);
      })
    );
    this.loadUsers();
    this.loadAssignmentOptions();
    this.loadContractorQueueHealth();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.revokeProfilePhotoPreview();
  }

  loadUsers(): void {
    this.loading.set(true);
    this.error.set(null);

    this.adminUsersApi.getUsers().subscribe({
      next: (users) => {
        this.users.set(users);
        this.loading.set(false);

        const selectedId = this.selectedUser()?.id;
        if (selectedId) {
          const refreshedSelected = users.find((user) => user.id === selectedId) ?? null;
          this.selectedUser.set(refreshedSelected);
          if (refreshedSelected) {
            this.patchForm(refreshedSelected);
          }
        }
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить пользователей');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Пользователи не загрузились', message);
      }
    });
  }

  selectUser(user: AdminUser): void {
    this.selectedUser.set(user);
    this.savedUser.set(null);
    this.savedAssignments.set(null);
    this.savedPasswordFor.set(null);
    this.savedPhotoFor.set(null);
    this.error.set(null);
    this.deleteError.set(null);
    this.assignmentsError.set(null);
    this.passwordError.set(null);
    this.photoError.set(null);
    this.profilePhotoFile.set(null);
    this.revokeProfilePhotoPreview();
    this.patchForm(user);
    this.passwordForm.reset({ password: '' });
    this.loadAssignments(user.id);
    this.contractorProfiles.set([]);
    this.contractorProfilesError.set(null);
    this.contractorTransferIdentifierTouched.set({});
    this.contractorOpeningHistory.set({});
    this.contractorOpeningHistoryError.set(null);
    this.contractorOpeningHistoryLoadingId.set(null);
    this.contractorDirectSettlements.set({});
    this.contractorDirectSettlementsError.set(null);
    this.contractorDirectSettlementsLoadingId.set(null);
    this.contractorDirectSettlementDrafts.set({});
    this.contractorDirectSettlementSavingKey.set(null);
    this.loadContractorPaymentProfiles(user.id);
    this.contractorJournal.set(null);
    this.contractorJournalPage.set(0);
    this.contractorJournalStatus.set('');
    this.contractorJournalMode.set('');
    this.contractorJournalSourceType.set('');
    this.contractorJournalSourceId.set(null);
    this.contractorJournalAllUsers.set(false);
    this.contractorReturnDrafts.set({});
    this.contractorReturnError.set(null);
    this.loadContractorPaymentJournal(user.id, 0);
  }

  loadContractorPaymentProfiles(userId: number): void {
    const request = ++this.contractorProfilesRequest;
    this.contractorProfilesLoading.set(true);
    this.contractorProfilesError.set(null);
    this.adminUsersApi.getContractorPaymentProfiles(userId).subscribe({
      next: (profiles) => {
        if (request !== this.contractorProfilesRequest || this.selectedUser()?.id !== userId) {
          return;
        }
        this.contractorProfiles.set(profiles.map((profile) => ({
          ...profile,
          openingBalanceRubles: (profile.openingBalanceKopecks / 100).toFixed(2),
          initialOpeningBalanceKopecks: profile.openingBalanceKopecks,
          openingBalanceReason: '',
          savedEnabled: profile.enabled,
          savedLiveEnabled: profile.liveEnabled
        })));
        this.contractorProfilesLoading.set(false);
      },
      error: (err) => {
        if (request !== this.contractorProfilesRequest || this.selectedUser()?.id !== userId) {
          return;
        }
        this.contractorProfilesError.set(this.errorMessage(err, 'Не удалось загрузить платёжные профили'));
        this.contractorProfilesLoading.set(false);
      }
    });
  }

  updateContractorProfile(
    profileId: number,
    field: 'enabled' | 'liveEnabled' | 'recipientName' | 'paymentPhone' | 'bankName' | 'paymentComment'
      | 'openingBalanceRubles' | 'openingBalanceReason',
    value: string | boolean
  ): void {
    if (!this.canManageContractorProfiles() || this.contractorProfileSavingId() !== null) {
      return;
    }
    this.contractorProfiles.update((profiles) => profiles.map((profile) => {
      if (profile.id !== profileId) {
        return profile;
      }
      const updated = { ...profile, [field]: value } as ContractorPaymentProfileDraft;
      return field === 'enabled' && value === false
        ? { ...updated, liveEnabled: false }
        : updated;
    }));
  }

  saveContractorPaymentProfile(profile: ContractorPaymentProfileDraft): void {
    const user = this.selectedUser();
    if (!user || !this.canManageContractorProfiles() || this.contractorProfileSavingId() !== null) {
      return;
    }
    this.markContractorTransferIdentifierTouched(profile.id);
    const transferIdentifier = this.contractorTransferIdentifierValidation(profile);
    if (!transferIdentifier.valid) {
      this.contractorProfilesError.set(transferIdentifier.error);
      return;
    }
    const openingBalance = Number(profile.openingBalanceRubles.replace(',', '.'));
    if (!Number.isFinite(openingBalance) || openingBalance < 0) {
      this.contractorProfilesError.set('Переходящий остаток должен быть равен нулю или быть положительным числом.');
      return;
    }
    const openingBalanceKopecks = Math.round(openingBalance * 100);
    const openingBalanceChanged = openingBalanceKopecks !== profile.initialOpeningBalanceKopecks;
    const openingBalanceReason = profile.openingBalanceReason.trim();
    if (openingBalanceChanged && !openingBalanceReason) {
      this.contractorProfilesError.set('Укажите источник и причину изменения переходящего остатка.');
      return;
    }
    const request: ContractorPaymentProfileRequest = {
      role: profile.role,
      expectedVersion: profile.rowVersion,
      enabled: profile.enabled,
      liveEnabled: profile.liveEnabled,
      recipientName: profile.recipientName?.trim() || undefined,
      paymentPhone: transferIdentifier.normalizedValue || undefined,
      bankName: profile.bankName?.trim() || undefined,
      paymentComment: profile.paymentComment?.trim() || undefined,
      openingBalanceKopecks,
      openingBalanceReason: openingBalanceChanged && openingBalanceReason
        ? openingBalanceReason
        : undefined
    };
    this.contractorProfileSavingId.set(profile.id);
    this.contractorProfilesError.set(null);
    this.adminUsersApi.updateContractorPaymentProfile(user.id, request).subscribe({
      next: (saved) => {
        this.contractorProfiles.update((profiles) => profiles.map((item) => item.id === saved.id
          ? {
              ...saved,
              openingBalanceRubles: (saved.openingBalanceKopecks / 100).toFixed(2),
              initialOpeningBalanceKopecks: saved.openingBalanceKopecks,
              openingBalanceReason: '',
              savedEnabled: saved.enabled,
              savedLiveEnabled: saved.liveEnabled
            }
          : item));
        this.contractorOpeningHistory.update((history) => {
          const updated = { ...history };
          delete updated[saved.id];
          return updated;
        });
        this.contractorProfileSavingId.set(null);
        this.toastService.success('Платёжный профиль сохранён', this.contractorRoleLabel(saved.role));
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить платёжный профиль');
        this.contractorProfilesError.set(message);
        this.contractorProfileSavingId.set(null);
        this.toastService.error('Платёжный профиль не сохранён', message);
        if (err?.status === 409) {
          this.loadContractorPaymentProfiles(user.id);
        }
      }
    });
  }

  contractorRoleLabel(role: ContractorPaymentProfile['role']): string {
    return role === 'SPECIALIST' ? 'Специалист' : 'Менеджер';
  }

  contractorTransferIdentifierValidation(profile: ContractorPaymentProfileDraft) {
    return validateContractorTransferIdentifierForSave(
      profile.paymentPhone,
      profile.enabled && !this.contractorProfileEligibilityBeingReduced(profile)
    );
  }

  contractorProfileEligibilityBeingReduced(profile: ContractorPaymentProfileDraft): boolean {
    const liveRoutingBeingDisabled = profile.savedLiveEnabled
      && !profile.liveEnabled
      && (!profile.enabled || profile.savedEnabled);
    return liveRoutingBeingDisabled || (profile.savedEnabled && !profile.enabled);
  }

  contractorTransferIdentifierErrorVisible(profile: ContractorPaymentProfileDraft): boolean {
    return Boolean(this.contractorTransferIdentifierTouched()[profile.id])
      && !this.contractorTransferIdentifierValidation(profile).valid;
  }

  markContractorTransferIdentifierTouched(profileId: number): void {
    this.contractorTransferIdentifierTouched.update((touched) => ({ ...touched, [profileId]: true }));
  }

  contractorProfileRoutingState(profile: ContractorPaymentProfileDraft) {
    return contractorProfileRoutingPresentation(
      profile.role,
      profile.liveEnabled,
      profile.enabled,
      profile.liveRouting,
      profile.savedLiveEnabled
    );
  }

  canEditContractorRouting(profile: ContractorPaymentProfileDraft): boolean {
    return canEditContractorProfileRouting(
      this.canManageContractorProfiles(),
      profile.enabled,
      profile.liveEnabled,
      this.contractorProfileSavingId() !== null
    );
  }

  contractorOpeningBalanceChanged(profile: ContractorPaymentProfileDraft): boolean {
    const rubles = Number(profile.openingBalanceRubles.replace(',', '.'));
    return Number.isFinite(rubles)
      && Math.round(rubles * 100) !== profile.initialOpeningBalanceKopecks;
  }

  loadContractorOpeningHistory(profileId: number, open: boolean): void {
    const userId = this.selectedUser()?.id;
    if (!open || userId == null || this.contractorOpeningHistory()[profileId]
      || this.contractorOpeningHistoryLoadingId() === profileId) {
      return;
    }
    this.contractorOpeningHistoryLoadingId.set(profileId);
    this.contractorOpeningHistoryError.set(null);
    this.adminUsersApi.getContractorOpeningBalanceHistory(userId, profileId).subscribe({
      next: (items) => {
        if (this.selectedUser()?.id !== userId) {
          return;
        }
        this.contractorOpeningHistory.update((history) => ({ ...history, [profileId]: items }));
        this.contractorOpeningHistoryLoadingId.set(null);
      },
      error: (err) => {
        if (this.selectedUser()?.id !== userId) {
          return;
        }
        this.contractorOpeningHistoryError.set(this.errorMessage(
          err,
          'Не удалось загрузить историю переходящего остатка'
        ));
        this.contractorOpeningHistoryLoadingId.set(null);
      }
    });
  }

  loadContractorDirectSettlements(profileId: number, open: boolean): void {
    const userId = this.selectedUser()?.id;
    if (!open || userId == null || this.contractorDirectSettlements()[profileId]
      || this.contractorDirectSettlementsLoadingId() === profileId) {
      return;
    }
    this.contractorDirectSettlementsLoadingId.set(profileId);
    this.contractorDirectSettlementsError.set(null);
    this.contractorPaymentsApi.getDirectSettlements(userId, profileId).subscribe({
      next: (items) => {
        if (this.selectedUser()?.id !== userId) {
          return;
        }
        this.contractorDirectSettlements.update((history) => ({ ...history, [profileId]: items }));
        this.contractorDirectSettlementsLoadingId.set(null);
      },
      error: (err) => {
        if (this.selectedUser()?.id !== userId) {
          return;
        }
        this.contractorDirectSettlementsError.set(this.errorMessage(
          err,
          'Не удалось загрузить историю фактических переводов'
        ));
        this.contractorDirectSettlementsLoadingId.set(null);
      }
    });
  }

  contractorDirectSettlementDraft(key: string): ContractorDirectSettlementDraft {
    return this.contractorDirectSettlementDrafts()[key] ?? {
      amountRubles: '',
      effectiveAt: this.currentLocalDateTimeInput(),
      reason: '',
      evidenceReference: '',
      idempotencyKey: this.newIdempotencyKey()
    };
  }

  updateContractorDirectSettlementDraft(
    key: string,
    field: keyof ContractorDirectSettlementDraft,
    value: string
  ): void {
    const current = this.contractorDirectSettlementDraft(key);
    this.contractorDirectSettlementDrafts.update((drafts) => ({
      ...drafts,
      [key]: { ...current, [field]: value }
    }));
  }

  createContractorDirectSettlement(profile: ContractorPaymentProfileDraft): void {
    const userId = this.selectedUser()?.id;
    if (userId == null) {
      return;
    }
    const key = `payment:${profile.id}`;
    const request = this.contractorDirectSettlementRequest(profile, key);
    if (!request) {
      return;
    }

    this.contractorDirectSettlementSavingKey.set(key);
    this.contractorDirectSettlementsError.set(null);
    this.contractorPaymentsApi.createDirectSettlement(userId, profile.id, request).subscribe({
      next: (saved) => {
        this.contractorDirectSettlementSavingKey.set(null);
        this.resetContractorDirectSettlementDraft(key);
        this.refreshContractorDirectSettlements(userId, profile.id);
        this.loadContractorPaymentProfiles(userId);
        this.loadContractorPaymentJournal(userId, 0);
        this.toastService.success(
          saved.simulated ? 'Тестовый перевод учтён' : 'Фактический перевод учтён',
          this.moneyKopecks(saved.amountKopecks)
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось учесть фактический перевод');
        this.contractorDirectSettlementsError.set(message);
        this.contractorDirectSettlementSavingKey.set(null);
        this.toastService.error('Перевод не учтён', message);
        if (err?.status === 409) {
          this.loadContractorPaymentProfiles(userId);
        }
      }
    });
  }

  reverseContractorDirectSettlement(
    profile: ContractorPaymentProfileDraft,
    original: ContractorDirectSettlement
  ): void {
    const userId = this.selectedUser()?.id;
    if (userId == null) {
      return;
    }
    const key = `reversal:${original.id}`;
    const request = this.contractorDirectSettlementRequest(profile, key);
    if (!request) {
      return;
    }
    const remaining = this.contractorDirectSettlementRemaining(profile.id, original);
    if (request.amountKopecks > remaining) {
      this.contractorDirectSettlementsError.set(
        `Корректировка не может превышать ${this.moneyKopecks(remaining)}.`
      );
      return;
    }

    this.contractorDirectSettlementSavingKey.set(key);
    this.contractorDirectSettlementsError.set(null);
    this.contractorPaymentsApi.reverseDirectSettlement(userId, profile.id, original.id, request).subscribe({
      next: (saved) => {
        this.contractorDirectSettlementSavingKey.set(null);
        this.resetContractorDirectSettlementDraft(key);
        this.refreshContractorDirectSettlements(userId, profile.id);
        this.loadContractorPaymentProfiles(userId);
        this.loadContractorPaymentJournal(userId, 0);
        this.toastService.success('Корректировка перевода учтена', this.moneyKopecks(saved.amountKopecks));
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось учесть корректировку перевода');
        this.contractorDirectSettlementsError.set(message);
        this.contractorDirectSettlementSavingKey.set(null);
        this.toastService.error('Корректировка не учтена', message);
        if (err?.status === 409) {
          this.refreshContractorDirectSettlements(userId, profile.id);
          this.loadContractorPaymentProfiles(userId);
        }
      }
    });
  }

  contractorDirectSettlementRemaining(
    profileId: number,
    original: ContractorDirectSettlement
  ): number {
    if (original.type !== 'PAYMENT') {
      return 0;
    }
    const reversed = (this.contractorDirectSettlements()[profileId] ?? [])
      .filter((item) => item.type === 'REVERSAL' && item.originalSettlementId === original.id)
      .reduce((sum, item) => sum + item.amountKopecks, 0);
    return Math.max(0, original.amountKopecks - reversed);
  }

  loadContractorQueueHealth(): void {
    this.contractorQueueHealthLoading.set(true);
    this.contractorQueueHealthError.set(null);
    this.contractorPaymentsApi.getQueueHealth().subscribe({
      next: (health) => {
        this.contractorQueueHealth.set(health);
        this.contractorQueueHealthLoading.set(false);
      },
      error: (err) => {
        this.contractorQueueHealthError.set(this.errorMessage(err, 'Не удалось загрузить состояние очередей'));
        this.contractorQueueHealthLoading.set(false);
      }
    });
  }

  contractorQueueHasProblems(): boolean {
    const health = this.contractorQueueHealth();
    if (!health) {
      return false;
    }
    return [
      health.allocationReconciliation,
      health.rewardRepair,
      health.shadowBackfill,
      health.completionRewardRepair
    ]
      .some((item) => item.expiredClaims > 0 || item.dueRetries > 0 || Boolean(item.lastErrorCode));
  }

  private contractorDirectSettlementRequest(
    profile: ContractorPaymentProfileDraft,
    key: string
  ): ContractorDirectSettlementRequest | null {
    const draft = this.contractorDirectSettlementDraft(key);
    const rubles = Number(draft.amountRubles.replace(',', '.'));
    const amountKopecks = Number.isFinite(rubles) ? Math.round(rubles * 100) : 0;
    if (!Number.isSafeInteger(amountKopecks) || amountKopecks <= 0 || amountKopecks > 100_000_000_000) {
      this.contractorDirectSettlementsError.set('Сумма должна быть больше нуля и не превышать 1 000 000 000 ₽.');
      return null;
    }
    const reason = draft.reason.trim();
    const evidenceReference = draft.evidenceReference.trim();
    if (!reason || !evidenceReference || !draft.effectiveAt) {
      this.contractorDirectSettlementsError.set(
        'Укажите дату, основание и идентификатор внутреннего подтверждающего документа.'
      );
      return null;
    }
    if (evidenceReference.length > 160 || reason.length > 255) {
      this.contractorDirectSettlementsError.set('Основание или идентификатор документа слишком длинные.');
      return null;
    }
    return {
      expectedMode: profile.reportingLive ? 'LIVE' : 'SHADOW',
      amountKopecks,
      effectiveAt: draft.effectiveAt,
      reason,
      evidenceReference,
      idempotencyKey: draft.idempotencyKey
    };
  }

  private refreshContractorDirectSettlements(userId: number, profileId: number): void {
    this.contractorDirectSettlements.update((history) => {
      const updated = { ...history };
      delete updated[profileId];
      return updated;
    });
    this.loadContractorDirectSettlements(profileId, true);
  }

  private resetContractorDirectSettlementDraft(key: string): void {
    this.contractorDirectSettlementDrafts.update((drafts) => {
      const updated = { ...drafts };
      delete updated[key];
      return updated;
    });
  }

  private currentLocalDateTimeInput(): string {
    const now = new Date();
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
      + `T${pad(now.getHours())}:${pad(now.getMinutes())}`;
  }

  private newIdempotencyKey(): string {
    const uuid = globalThis.crypto?.randomUUID?.();
    return uuid ? `admin-ui-${uuid}` : `admin-ui-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }

  loadContractorPaymentJournal(userId = this.selectedUser()?.id, page = this.contractorJournalPage()): void {
    if (userId == null) {
      return;
    }

    const request = ++this.contractorJournalRequest;
    this.contractorJournalLoading.set(true);
    this.contractorJournalError.set(null);
    this.contractorPaymentsApi.getAllocationJournal({
      userId: this.contractorJournalAllUsers() ? undefined : userId,
      status: this.contractorJournalStatus(),
      mode: this.contractorJournalMode(),
      sourceType: this.contractorJournalSourceType(),
      sourceId: this.contractorJournalSourceId() ?? undefined,
      page,
      size: this.contractorJournalPageSize
    }).subscribe({
      next: (journal) => {
        if (request !== this.contractorJournalRequest || this.selectedUser()?.id !== userId) {
          return;
        }
        this.contractorJournal.set(journal);
        this.contractorJournalPage.set(journal.number);
        this.contractorJournalLoading.set(false);
      },
      error: (err) => {
        if (request !== this.contractorJournalRequest || this.selectedUser()?.id !== userId) {
          return;
        }
        this.contractorJournalError.set(this.errorMessage(err, 'Не удалось загрузить журнал решений'));
        this.contractorJournalLoading.set(false);
      }
    });
  }

  changeContractorJournalStatus(value: string): void {
    this.contractorJournalStatus.set(value as ContractorPaymentAllocationStatus | '');
    this.contractorJournalPage.set(0);
    this.loadContractorPaymentJournal(this.selectedUser()?.id, 0);
  }

  changeContractorJournalMode(value: string): void {
    this.contractorJournalMode.set(value as ContractorPaymentMode | '');
    this.reloadContractorPaymentJournalFromStart();
  }

  changeContractorJournalUserScope(value: string): void {
    this.contractorJournalAllUsers.set(value === 'all');
    this.reloadContractorPaymentJournalFromStart();
  }

  changeContractorJournalSourceType(value: string): void {
    this.contractorJournalSourceType.set(value as ContractorPaymentSourceType | '');
    this.reloadContractorPaymentJournalFromStart();
  }

  changeContractorJournalSourceId(value: string): void {
    const parsed = Number(value);
    this.contractorJournalSourceId.set(Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null);
    this.reloadContractorPaymentJournalFromStart();
  }

  private reloadContractorPaymentJournalFromStart(): void {
    this.contractorJournalPage.set(0);
    this.loadContractorPaymentJournal(this.selectedUser()?.id, 0);
  }

  changeContractorJournalPage(page: number): void {
    const journal = this.contractorJournal();
    if (!journal || page < 0 || page >= journal.totalPages || this.contractorJournalLoading()) {
      return;
    }
    this.loadContractorPaymentJournal(this.selectedUser()?.id, page);
  }

  contractorRoutingModeLabel(): string {
    const profile = this.contractorProfiles()[0];
    if (!profile) {
      return 'Режим не определён';
    }
    if (profile.liveRouting) {
      return 'Боевой маршрут';
    }
    if (profile.reportingLive) {
      return 'Фактический учёт · новые маршруты остановлены';
    }
    return profile.shadowMode ? 'Тестовый расчёт' : 'Маршрутизация отключена';
  }

  contractorRoutingModeClass(): string {
    const profile = this.contractorProfiles()[0];
    if (profile?.liveRouting || profile?.reportingLive) {
      return 'live';
    }
    return profile?.shadowMode ? 'shadow' : 'disabled';
  }

  contractorRoutingModeDescription(): string {
    const profile = this.contractorProfiles()[0];
    if (!profile) {
      return 'Откройте общие настройки, чтобы проверить фактический режим на сервере.';
    }
    if (profile.liveRouting) {
      return 'Боевой маршрут активен: новые счета могут получать реквизиты допущенных исполнителей.';
    }
    if (profile.reportingLive) {
      return 'Фактический учёт активен, но выдача реквизитов исполнителей новым счетам приостановлена.';
    }
    if (profile.shadowMode) {
      return 'Тестовый расчёт не меняет получателя счёта и только показывает смоделированный результат.';
    }
    return 'Новая маршрутизация выключена: счета обрабатываются по прежним правилам.';
  }

  contractorAllocationStatusLabel(status: ContractorPaymentAllocationStatus): string {
    const labels: Record<ContractorPaymentAllocationStatus, string> = {
      RESERVED: 'Зарезервировано',
      CLIENT_REPORTED: 'Клиент сообщил',
      PARTIALLY_CONFIRMED: 'Подтверждено частично',
      CONFIRMED: 'Поступление подтверждено',
      SIMULATED_PAID: 'Оплата смоделирована',
      LATE_PAYMENT_AFTER_RELEASE: 'Поздняя оплата',
      OWNER_FALLBACK: 'Получатель — владелец',
      RELEASED_UNPAID: 'Резерв освобождён',
      EXPIRED: 'Истекло',
      CANCELED: 'Отменено',
      PARTIALLY_RETURNED: 'Частично возвращено',
      RETURN_AMOUNT_PENDING: 'Сумма возврата уточняется',
      RETURNED: 'Возвращено'
    };
    return labels[status];
  }

  contractorAllocationSource(item: ContractorPaymentAllocationJournalItem): string {
    return `${contractorPaymentSourceLabel(item.sourceType)} №${item.sourceId}`;
  }

  contractorAllocationEventLabel(eventType: string): string {
    const labels: Record<string, string> = {
      RESERVED: 'Резерв создан',
      CLIENT_REPORTED: 'Клиент сообщил об оплате',
      CONFIRMED: 'Поступление подтверждено',
      SIMULATED_CONFIRMED: 'Поступление смоделировано',
      RELEASED: 'Резерв освобождён',
      EXPIRED: 'Счёт истёк',
      CANCELED: 'Счёт отменён',
      RETURNED: 'Возврат учтён',
      RETURN_AMOUNT_PENDING: 'Ожидается сумма возврата',
      OWNER_FALLBACK: 'Выбран владелец'
    };
    return labels[eventType] ?? eventType;
  }

  contractorRoutingDecisionLabel(reason: ContractorRoutingDecisionReason): string {
    const labels: Record<ContractorRoutingDecisionReason, string> = {
      SPECIALIST_SELECTED: 'выбран специалист',
      MANAGER_SELECTED: 'выбран менеджер',
      SPECIALIST_NOT_ASSIGNED: 'специалист не назначен',
      MANAGER_NOT_ASSIGNED: 'менеджер не назначен',
      MIXED_SPECIALISTS: 'в общем счёте разные специалисты',
      PRIOR_PAYMENT_EVIDENCE: 'у счёта уже есть платёжные данные',
      USER_INACTIVE: 'пользователь неактивен',
      REQUIRED_ROLE_MISSING: 'нет требуемой роли',
      PROFILE_NOT_FOUND: 'платёжный профиль не найден',
      PROFILE_DISABLED: 'платёжный профиль отключён',
      LIVE_PROFILE_DISABLED: 'персональная выдача реквизитов отключена',
      PROFILE_IDENTITY_MISMATCH: 'профиль не принадлежит пользователю',
      RECIPIENT_DETAILS_INCOMPLETE: 'реквизиты получателя не заполнены',
      INSUFFICIENT_AVAILABLE_BALANCE: 'недостаточно доступного остатка',
      LIMIT_CONFIGURATION_INVALID: 'ошибка настройки лимитов',
      LIMIT_ROUTE_INPUT_INVALID: 'некорректные параметры маршрута',
      LIMIT_SINGLE_INVOICE_EXCEEDED: 'превышен лимит одного счёта',
      LIMIT_DATABASE_CLOCK_INVALID: 'ошибка времени базы данных',
      LIMIT_DAILY_TOTALS_INVALID: 'некорректны суточные итоги',
      LIMIT_DAILY_TOTAL_OVERFLOW: 'переполнение суточного итога',
      LIMIT_DAILY_AMOUNT_EXCEEDED: 'превышен суточный лимит суммы',
      LIMIT_DAILY_COUNT_EXCEEDED: 'превышен суточный лимит количества',
      NO_ELIGIBLE_RECIPIENT: 'подходящий получатель не найден',
      LEGACY_UNCLASSIFIED: 'старое решение без классификации'
    };
    return labels[reason];
  }

  contractorReturnDraft(item: ContractorPaymentAllocationJournalItem): ContractorReturnDraft {
    return this.contractorReturnDrafts()[item.id] ?? {
      amountRubles: (item.returnedKopecks / 100).toFixed(2),
      reason: ''
    };
  }

  updateContractorReturnDraft(
    item: ContractorPaymentAllocationJournalItem,
    field: keyof ContractorReturnDraft,
    value: string
  ): void {
    const current = this.contractorReturnDraft(item);
    this.contractorReturnDrafts.update((drafts) => ({
      ...drafts,
      [item.id]: { ...current, [field]: value }
    }));
  }

  canRecordContractorReturnedAmount(item: ContractorPaymentAllocationJournalItem): boolean {
    return item.sourceType === 'PAYMENT_LINK'
      && (item.status === 'RETURN_AMOUNT_PENDING' || item.status === 'PARTIALLY_RETURNED')
      && item.confirmedKopecks > 0;
  }

  saveContractorReturnedAmount(item: ContractorPaymentAllocationJournalItem): void {
    if (!this.canRecordContractorReturnedAmount(item)) {
      this.contractorReturnError.set(
        item.sourceType === 'COMMON_INVOICE'
          ? 'Возврат общего счёта учитывается только после сверки самого счёта.'
          : 'Ручной итог возврата доступен для ссылки с ожидаемой или уже частично учтённой суммой возврата.'
      );
      return;
    }
    const draft = this.contractorReturnDraft(item);
    const rubles = Number(draft.amountRubles.replace(',', '.'));
    const returnedTotalKopecks = Number.isFinite(rubles) ? Math.round(rubles * 100) : -1;
    if (returnedTotalKopecks < item.returnedKopecks) {
      this.contractorReturnError.set('Итог возврата не может быть меньше уже учтённой суммы.');
      return;
    }
    if (returnedTotalKopecks > item.confirmedKopecks) {
      this.contractorReturnError.set('Итог возврата не может превышать подтверждённое поступление.');
      return;
    }
    const reason = draft.reason.trim();
    if (!reason) {
      this.contractorReturnError.set('Укажите основание для ручного учёта возврата.');
      return;
    }

    this.contractorReturnSavingId.set(item.id);
    this.contractorReturnError.set(null);
    this.contractorPaymentsApi.recordReturnedAmount(item.id, {
      returnedTotalKopecks,
      reason
    }).subscribe({
      next: () => {
        this.contractorReturnSavingId.set(null);
        this.contractorReturnDrafts.update((drafts) => {
          const updated = { ...drafts };
          delete updated[item.id];
          return updated;
        });
        this.toastService.success('Возврат учтён', this.contractorAllocationSource(item));
        this.loadContractorPaymentJournal();
        const userId = this.selectedUser()?.id;
        if (userId != null) {
          this.loadContractorPaymentProfiles(userId);
        }
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось учесть сумму возврата');
        this.contractorReturnError.set(message);
        this.contractorReturnSavingId.set(null);
        this.toastService.error('Возврат не учтён', message);
      }
    });
  }

  contractorAllocationRecipient(item: ContractorPaymentAllocationJournalItem): string {
    const type = item.recipientType === 'SPECIALIST'
      ? 'Специалист'
      : item.recipientType === 'MANAGER' ? 'Менеджер' : 'Владелец';
    return item.recipientName ? `${type}: ${item.recipientName}` : type;
  }

  contractorDateTime(value?: string | null): string {
    if (!value) {
      return '—';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return new Intl.DateTimeFormat('ru-RU', {
      dateStyle: 'short',
      timeStyle: 'short'
    }).format(date);
  }

  moneyKopecks(value: number | null | undefined): string {
    return `${((value ?? 0) / 100).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ₽`;
  }

  toggleRole(role: string, checked: boolean): void {
    if (this.isAdminRoleLocked(role)) {
      return;
    }

    const roles = new Set(this.form.controls.roles.value);

    if (checked) {
      roles.add(role);
    } else {
      roles.delete(role);
    }

    this.form.controls.roles.setValue([...roles]);
    this.form.controls.roles.markAsDirty();
  }

  isRoleSelected(role: string): boolean {
    return this.form.controls.roles.value.includes(role);
  }

  isAdminUser(user: AdminUser | null | undefined): boolean {
    return user?.roles?.includes('ADMIN') ?? false;
  }

  isAdminRoleLocked(role: string): boolean {
    return role === 'ADMIN' && this.isAdminUser(this.selectedUser());
  }

  isWorkerProfile(): boolean {
    return this.isRoleSelected('WORKER');
  }

  isManagerProfile(): boolean {
    return this.isRoleSelected('MANAGER');
  }

  isOwnerProfile(): boolean {
    return this.isRoleSelected('OWNER');
  }

  workerChatStatus(user: AdminUser | null | undefined): string {
    const currentUrl = this.form.controls.workerChatUrl.value.trim();
    const savedUrl = (user?.workerChatUrl ?? '').trim();
    if (!currentUrl) {
      return 'Группа: ссылка не указана';
    }
    if (currentUrl !== savedUrl) {
      return 'Группа: сохраните ссылку';
    }
    return user?.workerTelegramGroupChatId ? 'Группа: привязана' : 'Группа: не привязана';
  }

  personalTelegramStatus(user: AdminUser | null | undefined): string {
    return user?.personalTelegramLinked
      ? 'Личный Telegram: привязан'
      : 'Личный Telegram: не привязан';
  }

  resetPersonalTelegramLink(): void {
    const user = this.selectedUser();
    if (!user?.personalTelegramLinked || this.telegramResetting()) {
      return;
    }

    const confirmed = window.confirm(
      `Сбросить личную Telegram-привязку пользователя «${user.fio || user.username}»?\n\n`
      + 'Группа специалиста останется привязанной. После сброса пользователь должен заново написать боту свой логин.'
    );
    if (!confirmed) {
      return;
    }

    this.telegramResetting.set(true);
    this.adminUsersApi.resetPersonalTelegramLink(user.id).subscribe({
      next: (updatedUser) => {
        this.selectedUser.set(updatedUser);
        this.users.update((users) => users.map((item) => item.id === updatedUser.id ? updatedUser : item));
        this.telegramResetting.set(false);
        this.toastService.success(
          'Личный Telegram отвязан',
          `Попросите ${updatedUser.fio || updatedUser.username} написать боту логин «${updatedUser.username}».`
        );
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сбросить личную Telegram-привязку');
        this.telegramResetting.set(false);
        this.toastService.error('Telegram не отвязан', message);
      }
    });
  }

  managerAuditChatStatus(user: AdminUser | null | undefined): string {
    const currentUrl = this.form.controls.managerAuditChatUrl.value.trim();
    const savedUrl = (user?.managerAuditChatUrl ?? '').trim();
    if (!currentUrl) {
      return 'Не указана';
    }
    if (currentUrl !== savedUrl) {
      return 'Сохраните ссылку';
    }
    return user?.managerAuditTelegramGroupChatId ? 'Telegram привязан' : 'Telegram не привязан';
  }

  async copyWorkerTelegramInviteUrl(user: AdminUser | null | undefined): Promise<void> {
    const inviteUrl = (user?.workerTelegramBotInviteUrl ?? '').trim();
    if (!inviteUrl) {
      return;
    }

    if (await copyTextToClipboard(inviteUrl)) {
      this.toastService.success('Ссылка скопирована', 'Ее можно отправить человеку, который добавит бота в группу.');
      return;
    }

    this.toastService.error('Не удалось скопировать', 'Скопируйте ссылку вручную из кнопки привязки.');
  }

  async copyManagerAuditTelegramInviteUrl(user: AdminUser | null | undefined): Promise<void> {
    const inviteUrl = (user?.managerAuditTelegramBotInviteUrl ?? '').trim();
    if (!inviteUrl) {
      return;
    }
    if (await copyTextToClipboard(inviteUrl)) {
      this.toastService.success(
        'Ссылка скопирована',
        'Добавьте бота во внутреннюю группу менеджера и владельца.'
      );
      return;
    }
    this.toastService.error('Не удалось скопировать', 'Скопируйте ссылку вручную из кнопки привязки.');
  }

  save(): void {
    const user = this.selectedUser();
    if (!user) {
      return;
    }

    this.error.set(null);
    this.savedUser.set(null);

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();

    if (this.isAdminUser(user)) {
      if (!raw.enabled) {
        this.error.set('Админа нельзя отключить.');
        return;
      }

      if (!raw.roles.includes('ADMIN')) {
        this.error.set('У админа нельзя снять роль администратора.');
        return;
      }
    }

    const coefficient = this.parseCoefficient(raw.coefficient);
    if (coefficient === null) {
      this.error.set('Коэффициент должен быть числом от 0 до 1. Можно вводить через точку или запятую.');
      return;
    }
    const request: UpdateKeycloakUserRequest = {
      username: raw.username.trim(),
      email: raw.email.trim() || undefined,
      fio: raw.fio.trim() || undefined,
      phoneNumber: raw.phoneNumber.trim() || undefined,
      coefficient,
      workerChatUrl: raw.workerChatUrl.trim() || undefined,
      managerAuditChatUrl: raw.managerAuditChatUrl.trim() || undefined,
      enabled: raw.enabled,
      roles: raw.roles
    };
    const employmentChanged = user.active !== request.enabled;

    this.saving.set(true);
    this.adminUsersApi.updateUser(user.id, request).subscribe({
      next: (updatedUser) => {
        this.savedUser.set(updatedUser);
        this.selectedUser.set(updatedUser);
        this.users.update((users) => users.map((item) => item.id === updatedUser.id ? updatedUser : item));
        this.patchForm(updatedUser);
        this.cabinetApi.clearTeamCache();
        this.loadAssignmentOptions();
        this.saving.set(false);
        if (employmentChanged) {
          this.toastService.success(
            updatedUser.active ? 'Доступ восстановлен' : 'Доступ отключён',
            updatedUser.active
              ? 'Пользователь снова появился в рабочих списках.'
              : 'Доступ отключён, профиль и история работы сохранены.'
          );
        } else {
          this.toastService.success('Пользователь сохранен', updatedUser.username);
        }
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить пользователя');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Пользователь не сохранен', message);
      }
    });
  }

  changeEmploymentStatus(active: boolean): void {
    const user = this.selectedUser();
    if (!user || this.isAdminUser(user) || user.active === active || this.saving()) {
      return;
    }

    const action = active ? 'восстановить доступ пользователя' : 'отключить доступ пользователя';
    const consequence = active
      ? 'Доступ к системе будет восстановлен.'
      : 'Доступ к системе будет закрыт, но профиль и история работы сохранятся.';
    if (!window.confirm(`Вы действительно хотите ${action} «${user.fio || user.username}»?\n\n${consequence}`)) {
      return;
    }

    // Кадровое действие не должно случайно сохранять незавершённые правки формы.
    this.patchForm(user);
    this.form.controls.enabled.setValue(active);
    this.form.controls.enabled.markAsDirty();
    this.save();
  }

  deleteSelectedUser(): void {
    const user = this.selectedUser();

    if (!user) {
      return;
    }

    this.deleteError.set(null);

    if (this.isAdminUser(user)) {
      this.deleteError.set('Админа нельзя удалить.');
      return;
    }

    const confirmed = window.confirm(`Удалить пользователя ${user.username}? Это действие нельзя отменить.`);
    if (!confirmed) {
      return;
    }

    this.deleteSaving.set(true);
    this.adminUsersApi.deleteUser(user.id).subscribe({
      next: () => {
        this.users.update((users) => users.filter((item) => item.id !== user.id));
        this.selectedUser.set(null);
        this.cabinetApi.clearTeamCache();
        this.deleteSaving.set(false);
        this.toastService.success('Пользователь удален', user.username);
        this.loadAssignmentOptions();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить пользователя');
        this.deleteError.set(message);
        this.deleteSaving.set(false);
        this.toastService.error('Пользователь не удален', message);
      }
    });
  }

  changePassword(): void {
    const user = this.selectedUser();
    if (!user) {
      return;
    }

    this.passwordError.set(null);
    this.savedPasswordFor.set(null);

    if (this.passwordForm.invalid) {
      this.passwordForm.markAllAsTouched();
      return;
    }

    const raw = this.passwordForm.getRawValue();
    const request: ChangeKeycloakPasswordRequest = {
      password: raw.password,
      temporary: false
    };

    this.passwordSaving.set(true);
    this.adminUsersApi.changePassword(user.id, request).subscribe({
      next: () => {
        this.savedPasswordFor.set(user.username);
        this.passwordForm.reset({ password: '' });
        this.passwordSaving.set(false);
        this.toastService.success('Пароль изменен', user.username);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось изменить пароль');
        this.passwordError.set(message);
        this.passwordSaving.set(false);
        this.toastService.error('Пароль не изменен', message);
      }
    });
  }

  onProfilePhotoSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;

    this.photoError.set(null);
    this.savedPhotoFor.set(null);

    if (!file) {
      this.profilePhotoFile.set(null);
      this.revokeProfilePhotoPreview();
      return;
    }

    if (!file.type.startsWith('image/')) {
      this.profilePhotoFile.set(null);
      this.revokeProfilePhotoPreview();
      this.photoError.set('Выбери файл изображения.');
      input.value = '';
      return;
    }

    this.revokeProfilePhotoPreview();
    this.profilePhotoFile.set(file);
    this.profilePhotoPreviewUrl.set(URL.createObjectURL(file));
  }

  uploadProfilePhoto(input: HTMLInputElement): void {
    const user = this.selectedUser();
    const file = this.profilePhotoFile();
    if (!user || !file) {
      return;
    }

    this.photoError.set(null);
    this.savedPhotoFor.set(null);
    this.photoUploading.set(true);

    this.adminUsersApi.updateUserPhoto(user.id, file).subscribe({
      next: (updatedUser) => {
        this.selectedUser.set(updatedUser);
        this.users.update((users) => users.map((item) => item.id === updatedUser.id ? updatedUser : item));
        this.patchForm(updatedUser);
        this.profilePhotoFile.set(null);
        this.revokeProfilePhotoPreview();
        input.value = '';
        this.savedPhotoFor.set(updatedUser.username);
        this.photoUploading.set(false);
        this.toastService.success('Фото обновлено', updatedUser.username);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось обновить фото');
        this.photoError.set(message);
        this.photoUploading.set(false);
        this.toastService.error('Фото не сохранено', message);
      }
    });
  }

  loadAssignmentOptions(): void {
    this.adminUsersApi.getAssignmentOptions().subscribe({
      next: (options) => {
        this.assignmentOptions.set(options);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить варианты связей');
        this.assignmentsError.set(message);
        this.toastService.error('Связи не загрузились', message);
      }
    });
  }

  loadAssignments(userId: number): void {
    this.assignmentsLoading.set(true);

    this.adminUsersApi.getUserAssignments(userId).subscribe({
      next: (assignments) => {
        this.patchAssignmentForm(assignments);
        this.assignmentsLoading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить связи пользователя');
        this.assignmentsError.set(message);
        this.assignmentsLoading.set(false);
        this.toastService.error('Связи не загрузились', message);
      }
    });
  }

  toggleAssignment(controlName: AssignmentIdControlName, id: number, checked: boolean): void {
    const control = this.assignmentForm.controls[controlName];
    const ids = new Set(control.value);

    if (checked) {
      ids.add(id);
    } else {
      ids.delete(id);
    }

    control.setValue([...ids]);
    control.markAsDirty();
  }

  isAssignmentSelected(controlName: AssignmentIdControlName, id: number): boolean {
    return this.assignmentForm.controls[controlName].value.includes(id);
  }

  saveAssignments(): void {
    const user = this.selectedUser();
    if (!user) {
      return;
    }

    this.assignmentsError.set(null);
    this.savedAssignments.set(null);

    const raw = this.assignmentForm.getRawValue();
    const request: UpdateUserAssignmentsRequest = {
      ownerControlViewMode: raw.ownerControlViewMode,
      managerIds: raw.managerIds,
      workerIds: raw.workerIds,
      operatorIds: raw.operatorIds,
      marketologIds: raw.marketologIds
    };

    this.assignmentsSaving.set(true);
    this.adminUsersApi.updateUserAssignments(user.id, request).subscribe({
      next: (assignments) => {
        this.savedAssignments.set(assignments);
        this.patchAssignmentForm(assignments);
        this.cabinetApi.clearTeamCache();
        this.assignmentsSaving.set(false);
        this.toastService.success('Связи сохранены', user.username);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить связи');
        this.assignmentsError.set(message);
        this.assignmentsSaving.set(false);
        this.toastService.error('Связи не сохранены', message);
      }
    });
  }

  clearFilters(): void {
    this.userSearch.set('');
    this.roleFilter.set('all');
    this.statusFilter.set('all');
    this.resetPage();
  }

  updateUserSearch(value: string): void {
    this.userSearch.set(value);
    this.resetPage();
  }

  setRoleFilter(role: string): void {
    this.roleFilter.set(role);
    this.resetPage();
  }

  setStatusFilter(status: UserStatusFilter): void {
    this.statusFilter.set(status);
    this.resetPage();
  }

  changePageSize(value: string | number): void {
    const parsed = Number(value);
    const pageSize = this.pageSizeOptions.includes(parsed) ? parsed : this.pageSizeOptions[0];

    this.pageSize.set(pageSize);
    this.resetPage();
  }

  goToPage(page: number): void {
    const lastPage = Math.max(0, this.totalPages() - 1);
    const nextPage = Math.min(Math.max(page, 0), lastPage);

    this.pageNumber.set(nextPage);
  }

  previousPage(): void {
    this.goToPage(this.currentPageIndex() - 1);
  }

  nextPage(): void {
    this.goToPage(this.currentPageIndex() + 1);
  }

  statusTotal(status: UserStatusFilter): number {
    return {
      all: this.users().length,
      active: this.activeUsers(),
      inactive: this.inactiveUsers(),
      linked: this.keycloakLinkedUsers(),
      unlinked: this.unlinkedUsers()
    }[status];
  }

  roleLabel(role: string): string {
    return {
      ADMIN: 'Админ',
      OWNER: 'Владелец',
      MANAGER: 'Менеджер',
      OPERATOR: 'Оператор',
      WORKER: 'Специалист',
      PERFORMER: 'Исполнитель',
      MARKETOLOG: 'Маркетолог',
      CLIENT: 'Клиент'
    }[role] ?? role;
  }

  formatRoles(roles: string[] | undefined): string {
    return roles?.map((role) => this.roleLabel(role)).join(', ') || '-';
  }

  trackUser(_index: number, user: AdminUser): number {
    return user.id;
  }

  trackRole(_index: number, role: string): string {
    return role;
  }

  trackStatus(_index: number, status: UserStatusTab): UserStatusFilter {
    return status.key;
  }

  trackMetric(_index: number, metric: UserMetric): string {
    return metric.label;
  }

  private patchForm(user: AdminUser): void {
    this.form.reset({
      username: user.username ?? '',
      email: user.email ?? '',
      fio: user.fio ?? '',
      phoneNumber: user.phoneNumber ?? '',
      coefficient: this.formatCoefficient(user.coefficient),
      workerChatUrl: user.workerChatUrl ?? '',
      managerAuditChatUrl: user.managerAuditChatUrl ?? '',
      enabled: user.active,
      roles: user.roles ?? []
    });
    this.formBaseline = this.currentFormSnapshot();
    this.hasUnsavedUserChanges.set(false);
  }

  private parseCoefficient(value: string): number | undefined | null {
    const normalized = value.trim().replace(',', '.');
    if (!normalized) {
      return undefined;
    }

    const coefficient = Number(normalized);
    if (!Number.isFinite(coefficient) || coefficient < 0 || coefficient > 1) {
      return null;
    }

    return coefficient;
  }

  private formatCoefficient(value: number | undefined): string {
    return String(value ?? '0.05').replace('.', ',');
  }

  private currentFormSnapshot(): string {
    const raw = this.form.getRawValue();
    const coefficient = this.parseCoefficient(raw.coefficient);
    return JSON.stringify({
      username: raw.username.trim(),
      email: raw.email.trim(),
      fio: raw.fio.trim(),
      phoneNumber: raw.phoneNumber.trim(),
      coefficient: coefficient === null ? `invalid:${raw.coefficient.trim()}` : coefficient ?? '',
      workerChatUrl: raw.workerChatUrl.trim(),
      managerAuditChatUrl: raw.managerAuditChatUrl.trim(),
      enabled: raw.enabled,
      roles: [...raw.roles].sort()
    });
  }

  private patchAssignmentForm(assignments: UserAssignments): void {
    this.assignmentForm.reset({
      ownerControlViewMode: assignments.ownerControlViewMode ?? 'OWN_MANAGERS',
      managerIds: assignments.managerIds ?? [],
      workerIds: assignments.workerIds ?? [],
      operatorIds: assignments.operatorIds ?? [],
      marketologIds: assignments.marketologIds ?? []
    });
  }

  private revokeProfilePhotoPreview(): void {
    const previewUrl = this.profilePhotoPreviewUrl();
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
      this.profilePhotoPreviewUrl.set(null);
    }
  }

  private resetPage(): void {
    this.pageNumber.set(0);
  }

  private errorMessage(err: unknown, fallback: string): string {
    return apiErrorMessage(err, fallback);
  }
}
