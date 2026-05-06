import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  AdminUser,
  AdminUsersApi,
  AssignmentOptions,
  ChangeKeycloakPasswordRequest,
  UpdateKeycloakUserRequest,
  UpdateUserAssignmentsRequest,
  UserAssignments
} from '../../../core/admin-users.api';
import { appEnvironment } from '../../../core/app-environment';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';

type UserStatusFilter = 'all' | 'active' | 'inactive' | 'linked' | 'unlinked';

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

@Component({
  selector: 'app-users-admin',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './users-admin.component.html',
  styleUrl: './users-admin.component.scss'
})
export class UsersAdminComponent implements OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly adminUsersApi = inject(AdminUsersApi);
  private readonly toastService = inject(ToastService);

  readonly availableRoles = ['ADMIN', 'OWNER', 'MANAGER', 'OPERATOR', 'WORKER', 'MARKETOLOG', 'CLIENT'];
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
  readonly assignmentsLoading = signal(false);
  readonly assignmentsSaving = signal(false);
  readonly passwordSaving = signal(false);
  readonly photoUploading = signal(false);
  readonly error = signal<string | null>(null);
  readonly deleteError = signal<string | null>(null);
  readonly assignmentsError = signal<string | null>(null);
  readonly passwordError = signal<string | null>(null);
  readonly photoError = signal<string | null>(null);
  readonly savedUser = signal<AdminUser | null>(null);
  readonly savedAssignments = signal<UserAssignments | null>(null);
  readonly savedPasswordFor = signal<string | null>(null);
  readonly savedPhotoFor = signal<string | null>(null);
  readonly profilePhotoFile = signal<File | null>(null);
  readonly profilePhotoPreviewUrl = signal<string | null>(null);
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
    return imageId ? `${appEnvironment.legacyBaseUrl}/images/${imageId}` : null;
  });

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.email]],
    fio: [''],
    phoneNumber: [''],
    coefficient: ['0.05'],
    enabled: [true],
    roles: this.fb.nonNullable.control<string[]>([], [Validators.required])
  });

  readonly passwordForm = this.fb.nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(6)]]
  });

  readonly assignmentForm = this.fb.nonNullable.group({
    managerIds: this.fb.nonNullable.control<number[]>([]),
    workerIds: this.fb.nonNullable.control<number[]>([]),
    operatorIds: this.fb.nonNullable.control<number[]>([]),
    marketologIds: this.fb.nonNullable.control<number[]>([])
  });

  constructor() {
    this.loadUsers();
    this.loadAssignmentOptions();
  }

  ngOnDestroy(): void {
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

    const coefficient = raw.coefficient.trim();
    const request: UpdateKeycloakUserRequest = {
      email: raw.email.trim() || undefined,
      fio: raw.fio.trim() || undefined,
      phoneNumber: raw.phoneNumber.trim() || undefined,
      coefficient: coefficient ? Number(coefficient) : undefined,
      enabled: raw.enabled,
      roles: raw.roles
    };

    this.saving.set(true);
    this.adminUsersApi.updateUser(user.id, request).subscribe({
      next: (updatedUser) => {
        this.savedUser.set(updatedUser);
        this.selectedUser.set(updatedUser);
        this.users.update((users) => users.map((item) => item.id === updatedUser.id ? updatedUser : item));
        this.patchForm(updatedUser);
        this.saving.set(false);
        this.toastService.success('Пользователь сохранен', updatedUser.username);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить пользователя');
        this.error.set(message);
        this.saving.set(false);
        this.toastService.error('Пользователь не сохранен', message);
      }
    });
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

  toggleAssignment(controlName: keyof UpdateUserAssignmentsRequest, id: number, checked: boolean): void {
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

  isAssignmentSelected(controlName: keyof UpdateUserAssignmentsRequest, id: number): boolean {
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
      email: user.email ?? '',
      fio: user.fio ?? '',
      phoneNumber: user.phoneNumber ?? '',
      coefficient: String(user.coefficient ?? '0.05'),
      enabled: user.active,
      roles: user.roles ?? []
    });
  }

  private patchAssignmentForm(assignments: UserAssignments): void {
    this.assignmentForm.reset({
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
