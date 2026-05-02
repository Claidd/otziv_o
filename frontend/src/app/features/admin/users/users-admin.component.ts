import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  AdminUser,
  AdminUsersApi,
  AssignmentOptions,
  UpdateKeycloakUserRequest,
  UpdateUserAssignmentsRequest,
  UserAssignments
} from '../../../core/admin-users.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
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
  imports: [AdminLayoutComponent, ReactiveFormsModule, RouterLink],
  templateUrl: './users-admin.component.html',
  styleUrl: './users-admin.component.scss'
})
export class UsersAdminComponent {
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
  readonly users = signal<AdminUser[]>([]);
  readonly selectedUser = signal<AdminUser | null>(null);
  readonly userSearch = signal('');
  readonly roleFilter = signal('all');
  readonly statusFilter = signal<UserStatusFilter>('all');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly assignmentsLoading = signal(false);
  readonly assignmentsSaving = signal(false);
  readonly error = signal<string | null>(null);
  readonly assignmentsError = signal<string | null>(null);
  readonly savedUser = signal<AdminUser | null>(null);
  readonly savedAssignments = signal<UserAssignments | null>(null);
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

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.email]],
    fio: [''],
    phoneNumber: [''],
    coefficient: ['0.05'],
    enabled: [true],
    roles: this.fb.nonNullable.control<string[]>([], [Validators.required])
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
    this.error.set(null);
    this.assignmentsError.set(null);
    this.patchForm(user);
    this.loadAssignments(user.id);
  }

  toggleRole(role: string, checked: boolean): void {
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
  }

  setStatusFilter(status: UserStatusFilter): void {
    this.statusFilter.set(status);
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

  private errorMessage(err: unknown, fallback: string): string {
    if (typeof err === 'object' && err !== null) {
      const response = err as { error?: unknown; message?: unknown };
      if (typeof response.error === 'string') {
        return response.error;
      }

      if (typeof response.error === 'object' && response.error !== null) {
        const body = response.error as { message?: unknown; detail?: unknown; title?: unknown };
        if (typeof body.message === 'string') {
          return body.message;
        }

        if (typeof body.detail === 'string') {
          return body.detail;
        }

        if (typeof body.title === 'string') {
          return body.title;
        }
      }

      if (typeof response.message === 'string') {
        return response.message;
      }
    }

    return fallback;
  }
}
