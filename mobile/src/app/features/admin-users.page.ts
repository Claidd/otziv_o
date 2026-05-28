import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonContent, IonModal } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  AdminUser,
  ApiService,
  AssignmentOption,
  AssignmentOptions,
  ChangeKeycloakPasswordRequest,
  CreateKeycloakUserRequest,
  UpdateKeycloakUserRequest,
  UpdateUserAssignmentsRequest,
  UserAssignments
} from '../core/api.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileMediaService } from '../shared/mobile-media.service';

type AssignmentGroup = keyof Pick<UpdateUserAssignmentsRequest, 'managerIds' | 'workerIds' | 'operatorIds' | 'marketologIds'>;

interface UserDraft {
  id: number | null;
  username: string;
  email: string;
  fio: string;
  phoneNumber: string;
  coefficient: number | null;
  password: string;
  temporaryPassword: boolean;
  enabled: boolean;
  emailVerified: boolean;
  roles: string[];
}

const ROLE_OPTIONS = ['ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG'];

@Component({
  selector: 'app-admin-users-page',
  imports: [DatePipe, FormsModule, IonContent, IonModal, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Пользователи" />

      <ion-content fullscreen [scrollY]="false">
        <main class="admin-users-page">
          <section class="admin-users-top">
            <div>
              <p>ADMIN</p>
              <h1>Пользователи</h1>
              <small>{{ filteredUsers().length }} из {{ users().length }}</small>
            </div>
            <button class="icon-button" type="button" (click)="reload()" [disabled]="loading()" aria-label="Обновить">
              <span class="material-icons-sharp">refresh</span>
            </button>
            <button class="icon-button add" type="button" (click)="openCreateUser()" aria-label="Новый пользователь">
              <span class="material-icons-sharp">person_add</span>
            </button>
          </section>

          <form class="admin-users-search" (ngSubmit)="reload()" role="search">
            <label>
              <span class="material-icons-sharp">search</span>
              <input
                name="adminUserSearch"
                type="search"
                autocomplete="off"
                placeholder="Логин, ФИО, email, роль"
                [ngModel]="keyword()"
                (ngModelChange)="keyword.set($event)"
              >
            </label>
          </form>

          @if (error()) {
            <p class="inline-alert">{{ error() }}</p>
          }

          <section class="admin-users-list">
            @if (loading()) {
              <article class="empty-card">Загружаю пользователей...</article>
            } @else if (!filteredUsers().length) {
              <article class="empty-card">Пользователи не найдены.</article>
            } @else {
              @for (user of filteredUsers(); track user.id) {
                <article class="user-card" [class.disabled]="!user.active">
                  <header>
                    <div class="avatar">{{ initials(user) }}</div>
                    <div>
                      <h2>{{ user.fio || user.username }}</h2>
                      <small>{{ user.username }} · {{ user.authProvider }}</small>
                    </div>
                    <span class="status-pill" [class.active]="user.active">{{ user.active ? 'активен' : 'выкл.' }}</span>
                  </header>

                  <div class="user-meta-grid">
                    <span><small>Email</small><b>{{ user.email || '-' }}</b></span>
                    <span><small>Телефон</small><b>{{ user.phoneNumber || '-' }}</b></span>
                    <span><small>Коэф.</small><b>{{ user.coefficient ?? '-' }}</b></span>
                    <span><small>Вход</small><b>{{ user.lastLoginAt ? (user.lastLoginAt | date: 'dd.MM HH:mm') : '-' }}</b></span>
                  </div>

                  <div class="role-row">
                    @for (role of user.roles; track role) {
                      <span>{{ roleLabel(role) }}</span>
                    }
                  </div>

                  <footer>
                    <label class="ghost upload">
                      <span class="material-icons-sharp">photo_camera</span>
                      фото
                      <input type="file" accept="image/*" (change)="uploadPhoto(user, $event)" [disabled]="busyUserId() === user.id">
                    </label>
                    <button class="ghost" type="button" (click)="openPassword(user)">пароль</button>
                    <button class="ghost" type="button" (click)="openAssignments(user)">назначения</button>
                    <button class="ghost" type="button" (click)="openEditUser(user)">изменить</button>
                    <button class="danger-link" type="button" (click)="deleteUser(user)" [disabled]="isAdminUser(user) || busyUserId() === user.id">удалить</button>
                  </footer>
                </article>
              }
            }
          </section>
        </main>

        <ion-modal class="sheet-modal" [isOpen]="userDraft() !== null" (didDismiss)="closeUserEditor()">
          <ng-template>
            @if (userDraft(); as draft) {
              <form class="sheet-body sheet-form" (ngSubmit)="saveUser()">
                <div class="sheet-head">
                  <div>
                    <p class="sheet-note">{{ draft.id ? 'Редактирование' : 'Новый пользователь' }}</p>
                    <h2>{{ draft.id ? draft.username : 'Создать доступ' }}</h2>
                  </div>
                  <button class="icon-button" type="button" (click)="closeUserEditor()" [disabled]="saving()"> 
                    <span class="material-icons-sharp">close</span>
                  </button>
                </div>

                <section class="sheet-form-content">
                  @if (editorError()) {
                    <p class="sheet-error">{{ editorError() }}</p>
                  }

                  <label class="sheet-field">
                    <span>Логин</span>
                    <input name="username" type="text" required [readonly]="!!draft.id" [ngModel]="draft.username" (ngModelChange)="setDraft('username', $event)">
                  </label>
                  <label class="sheet-field">
                    <span>Email</span>
                    <input name="email" type="email" [ngModel]="draft.email" (ngModelChange)="setDraft('email', $event)">
                  </label>
                  <label class="sheet-field">
                    <span>ФИО</span>
                    <input name="fio" type="text" [ngModel]="draft.fio" (ngModelChange)="setDraft('fio', $event)">
                  </label>
                  <label class="sheet-field">
                    <span>Телефон</span>
                    <input name="phoneNumber" type="tel" [ngModel]="draft.phoneNumber" (ngModelChange)="setDraft('phoneNumber', $event)">
                  </label>
                  <label class="sheet-field">
                    <span>Коэффициент</span>
                    <input name="coefficient" type="number" step="0.01" [ngModel]="draft.coefficient" (ngModelChange)="setDraft('coefficient', numberOrNull($event))">
                  </label>
                  @if (!draft.id) {
                    <label class="sheet-field">
                      <span>Пароль</span>
                      <input name="password" type="text" required [ngModel]="draft.password" (ngModelChange)="setDraft('password', $event)">
                    </label>
                  }

                  <div class="role-editor">
                    <span>Роли</span>
                    <div>
                      @for (role of roleOptions; track role) {
                        <button type="button" [class.active]="draft.roles.includes(role)" (click)="toggleDraftRole(role)" [disabled]="draft.id !== null && isDraftAdmin()">
                          {{ roleLabel(role) }}
                        </button>
                      }
                    </div>
                  </div>

                  <label class="sheet-field sheet-field--inline">
                    <span>Включен</span>
                    <input name="enabled" type="checkbox" [ngModel]="draft.enabled" (ngModelChange)="setDraft('enabled', $event)">
                  </label>
                  <label class="sheet-field sheet-field--inline">
                    <span>Email подтвержден</span>
                    <input name="emailVerified" type="checkbox" [ngModel]="draft.emailVerified" (ngModelChange)="setDraft('emailVerified', $event)">
                  </label>
                  @if (!draft.id) {
                    <label class="sheet-field sheet-field--inline">
                      <span>Временный пароль</span>
                      <input name="temporaryPassword" type="checkbox" [ngModel]="draft.temporaryPassword" (ngModelChange)="setDraft('temporaryPassword', $event)">
                    </label>
                  }
                </section>

                <div class="sheet-actions">
                  <button class="secondary" type="button" (click)="closeUserEditor()" [disabled]="saving()">Отмена</button>
                  <button type="submit" [disabled]="saving() || !canSaveUser()">{{ saving() ? 'Сохраняю' : 'Сохранить' }}</button>
                </div>
              </form>
            }
          </ng-template>
        </ion-modal>

        <ion-modal class="sheet-modal" [isOpen]="passwordUser() !== null" (didDismiss)="closePassword()">
          <ng-template>
            @if (passwordUser(); as user) {
              <form class="sheet-body sheet-form" (ngSubmit)="savePassword()">
                <div class="sheet-head">
                  <div>
                    <p class="sheet-note">Пароль</p>
                    <h2>{{ user.username }}</h2>
                  </div>
                  <button class="icon-button" type="button" (click)="closePassword()" [disabled]="saving()">
                    <span class="material-icons-sharp">close</span>
                  </button>
                </div>
                <section class="sheet-form-content">
                  @if (editorError()) {
                    <p class="sheet-error">{{ editorError() }}</p>
                  }
                  <label class="sheet-field">
                    <span>Новый пароль</span>
                    <input name="newPassword" type="text" required [ngModel]="passwordDraft().password" (ngModelChange)="setPasswordField('password', $event)">
                  </label>
                  <label class="sheet-field sheet-field--inline">
                    <span>Временный</span>
                    <input name="temporary" type="checkbox" [ngModel]="passwordDraft().temporary" (ngModelChange)="setPasswordField('temporary', $event)">
                  </label>
                </section>
                <div class="sheet-actions">
                  <button class="secondary" type="button" (click)="closePassword()" [disabled]="saving()">Отмена</button>
                  <button type="submit" [disabled]="saving() || !passwordDraft().password.trim()">Сохранить</button>
                </div>
              </form>
            }
          </ng-template>
        </ion-modal>

        <ion-modal class="sheet-modal" [isOpen]="assignmentUser() !== null" (didDismiss)="closeAssignments()">
          <ng-template>
            @if (assignmentUser(); as user) {
              <form class="sheet-body sheet-form assignments-sheet" (ngSubmit)="saveAssignments()">
                <div class="sheet-head">
                  <div>
                    <p class="sheet-note">Назначения</p>
                    <h2>{{ user.username }}</h2>
                  </div>
                  <button class="icon-button" type="button" (click)="closeAssignments()" [disabled]="saving()">
                    <span class="material-icons-sharp">close</span>
                  </button>
                </div>
                <section class="sheet-form-content">
                  @if (editorError()) {
                    <p class="sheet-error">{{ editorError() }}</p>
                  }
                  @if (assignmentOptions(); as options) {
                    <div class="assignment-grid">
                      <section>
                        <h3>Менеджеры</h3>
                        @for (option of options.managers; track option.id) {
                          <button type="button" [class.active]="isAssigned('managerIds', option.userId)" (click)="toggleAssignment('managerIds', option.userId)">
                            {{ assignmentLabel(option) }}
                          </button>
                        }
                      </section>
                      <section>
                        <h3>Специалисты</h3>
                        @for (option of options.workers; track option.id) {
                          <button type="button" [class.active]="isAssigned('workerIds', option.userId)" (click)="toggleAssignment('workerIds', option.userId)">
                            {{ assignmentLabel(option) }}
                          </button>
                        }
                      </section>
                      <section>
                        <h3>Операторы</h3>
                        @for (option of options.operators; track option.id) {
                          <button type="button" [class.active]="isAssigned('operatorIds', option.userId)" (click)="toggleAssignment('operatorIds', option.userId)">
                            {{ assignmentLabel(option) }}
                          </button>
                        }
                      </section>
                      <section>
                        <h3>Маркетологи</h3>
                        @for (option of options.marketologs; track option.id) {
                          <button type="button" [class.active]="isAssigned('marketologIds', option.userId)" (click)="toggleAssignment('marketologIds', option.userId)">
                            {{ assignmentLabel(option) }}
                          </button>
                        }
                      </section>
                    </div>
                  } @else {
                    <article class="empty-card">Загружаю назначения...</article>
                  }
                </section>
                <div class="sheet-actions">
                  <button class="secondary" type="button" (click)="closeAssignments()" [disabled]="saving()">Отмена</button>
                  <button type="submit" [disabled]="saving() || !assignmentDraft()">Сохранить</button>
                </div>
              </form>
            }
          </ng-template>
        </ion-modal>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }
    .admin-users-page{display:flex;flex-direction:column;gap:.65rem;height:100%;max-width:48rem;margin:0 auto;padding:.75rem .75rem calc(.75rem + env(safe-area-inset-bottom));overflow:hidden}
    .admin-users-top,.admin-users-search,.user-card,.empty-card{border:1px solid rgba(103,116,131,.16);border-radius:1rem;background:linear-gradient(155deg,var(--otziv-white),var(--otziv-tone-walk-surface));box-shadow:0 .8rem 1.6rem rgba(132,139,200,.1)}
    .admin-users-top{display:grid;grid-template-columns:minmax(0,1fr)2.42rem 2.42rem;align-items:center;gap:.5rem;padding:.75rem}
    .admin-users-top p,.sheet-note{margin:0;color:var(--otziv-info);font-size:.68rem;font-weight:1000;text-transform:uppercase}.admin-users-top h1{margin:.1rem 0;color:var(--otziv-dark);font-size:1.28rem}.admin-users-top small{color:var(--otziv-info);font-weight:900}
    .icon-button{display:grid;place-items:center;width:2.42rem;height:2.42rem;border:0;border-radius:.82rem;color:var(--otziv-primary);background:linear-gradient(145deg,rgba(108,155,207,.16),var(--otziv-white))}
    .icon-button.add{color:#16735f;background:linear-gradient(145deg,rgba(74,198,177,.16),var(--otziv-white))}
    .admin-users-search{display:grid;grid-template-columns:minmax(0,1fr);padding:.48rem}.admin-users-search label{display:grid;grid-template-columns:auto minmax(0,1fr);align-items:center;gap:.45rem;min-height:2.42rem;border:1px solid rgba(103,116,131,.14);border-radius:.82rem;padding:0 .7rem;color:var(--otziv-info);background:var(--otziv-white)}.admin-users-search input{min-width:0;border:0;outline:0;background:transparent;color:var(--otziv-dark);font:900 .82rem/1 var(--otziv-font-family)}
    .inline-alert{border:1px solid rgba(255,0,96,.24);border-radius:.85rem;padding:.65rem .75rem;color:#9a2737;background:rgba(255,0,96,.06);font-size:.78rem;font-weight:900}
    .admin-users-list{display:flex;flex:1 1 0;min-height:0;flex-direction:column;gap:.62rem;overflow-y:auto;padding:.02rem .02rem .35rem}.admin-users-list::-webkit-scrollbar{display:none}
    .empty-card{display:grid;min-height:8rem;place-items:center;color:var(--otziv-info);font-weight:900}
    .user-card{display:flex;flex-direction:column;gap:.65rem;padding:.75rem}.user-card.disabled{opacity:.76}.user-card header{display:grid;grid-template-columns:auto minmax(0,1fr)auto;align-items:center;gap:.62rem}.avatar{display:grid;place-items:center;width:2.35rem;height:2.35rem;border-radius:.8rem;color:var(--otziv-primary);background:rgba(116,154,207,.14);font-weight:1000}.user-card h2{overflow:hidden;margin:0;color:var(--otziv-dark);font-size:1rem;text-overflow:ellipsis;white-space:nowrap}.user-card small{color:var(--otziv-info);font-size:.66rem;font-weight:900}.status-pill{border-radius:999px;padding:.35rem .55rem;color:var(--otziv-info);background:rgba(135,151,178,.12);font-size:.62rem;font-weight:1000}.status-pill.active{color:#16735f;background:rgba(74,198,177,.14)}
    .user-meta-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.45rem}.user-meta-grid span{display:grid;gap:.12rem;min-width:0;border:1px solid rgba(103,116,131,.13);border-radius:.72rem;padding:.48rem;background:rgba(255,255,255,.7)}.user-meta-grid b{overflow:hidden;color:var(--otziv-dark);font-size:.75rem;text-overflow:ellipsis;white-space:nowrap}
    .role-row,.user-card footer,.role-editor div,.assignment-grid section{display:flex;flex-wrap:wrap;gap:.42rem}.role-row span,.ghost,.danger-link,.role-editor button,.assignment-grid button{min-height:1.9rem;border:1px solid rgba(103,116,131,.18);border-radius:999px;padding:0 .68rem;background:var(--otziv-white);color:var(--otziv-dark);font-size:.65rem;font-weight:1000}.ghost{display:inline-flex;align-items:center;justify-content:center;gap:.25rem;color:var(--otziv-primary)}.danger-link{color:var(--otziv-danger)}.upload{position:relative;overflow:hidden}.upload input{position:absolute;inset:0;opacity:0}
    .role-editor{display:grid;gap:.35rem}.role-editor>span,.assignment-grid h3{margin:0;color:var(--otziv-info);font-size:.66rem;font-weight:1000}.role-editor button.active,.assignment-grid button.active{border-color:rgba(74,198,177,.38);color:#16735f;background:rgba(74,198,177,.12)}
    .assignment-grid{display:grid;grid-template-columns:1fr;gap:.7rem}.assignment-grid section{flex-direction:column;border:1px solid rgba(103,116,131,.13);border-radius:.85rem;padding:.65rem;background:rgba(255,255,255,.62)}
    :host-context(body.otziv-dark-theme) .admin-users-top,:host-context(body.otziv-dark-theme) .admin-users-search,:host-context(body.otziv-dark-theme) .user-card,:host-context(body.otziv-dark-theme) .empty-card{background:linear-gradient(155deg,rgba(31,38,41,.98),rgba(22,27,29,.96));box-shadow:none}
  `]
})
export class AdminUsersPage implements OnInit {
  readonly roleOptions = ROLE_OPTIONS;
  readonly users = signal<AdminUser[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly busyUserId = signal<number | null>(null);
  readonly error = signal<string | null>(null);
  readonly editorError = signal<string | null>(null);
  readonly keyword = signal('');
  readonly userDraft = signal<UserDraft | null>(null);
  readonly passwordUser = signal<AdminUser | null>(null);
  readonly passwordDraft = signal<ChangeKeycloakPasswordRequest>({ password: '', temporary: false });
  readonly assignmentUser = signal<AdminUser | null>(null);
  readonly assignmentOptions = signal<AssignmentOptions | null>(null);
  readonly assignmentDraft = signal<UpdateUserAssignmentsRequest | null>(null);

  readonly filteredUsers = computed(() => {
    const keyword = this.keyword().trim().toLocaleLowerCase('ru-RU');
    const users = [...this.users()].sort((left, right) => (left.fio || left.username).localeCompare(right.fio || right.username, 'ru'));
    if (!keyword) {
      return users;
    }
    return users.filter((user) => [
      user.username,
      user.email,
      user.fio,
      user.phoneNumber,
      user.roles.join(' ')
    ].some((value) => (value ?? '').toLocaleLowerCase('ru-RU').includes(keyword)));
  });

  constructor(
    private readonly api: ApiService,
    private readonly confirm: MobileConfirmService,
    private readonly media: MobileMediaService
  ) {}

  ngOnInit(): void {
    void this.reload();
  }

  async reload(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.users.set(await firstValueFrom(this.api.getAdminUsers()));
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось загрузить пользователей.'));
    } finally {
      this.loading.set(false);
    }
  }

  openCreateUser(): void {
    this.editorError.set(null);
    this.userDraft.set({
      id: null,
      username: '',
      email: '',
      fio: '',
      phoneNumber: '',
      coefficient: null,
      password: '',
      temporaryPassword: false,
      enabled: true,
      emailVerified: true,
      roles: ['WORKER']
    });
  }

  openEditUser(user: AdminUser): void {
    this.editorError.set(null);
    this.userDraft.set({
      id: user.id,
      username: user.username,
      email: user.email ?? '',
      fio: user.fio ?? '',
      phoneNumber: user.phoneNumber ?? '',
      coefficient: user.coefficient ?? null,
      password: '',
      temporaryPassword: false,
      enabled: user.active,
      emailVerified: true,
      roles: [...user.roles]
    });
  }

  closeUserEditor(): void {
    if (!this.saving()) {
      this.userDraft.set(null);
      this.editorError.set(null);
    }
  }

  setDraft<K extends keyof UserDraft>(field: K, value: UserDraft[K]): void {
    this.userDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  toggleDraftRole(role: string): void {
    this.userDraft.update((draft) => {
      if (!draft || (draft.id !== null && draft.roles.includes('ADMIN'))) {
        return draft;
      }
      return {
        ...draft,
        roles: draft.roles.includes(role)
          ? draft.roles.filter((item) => item !== role)
          : [...draft.roles, role]
      };
    });
  }

  canSaveUser(): boolean {
    const draft = this.userDraft();
    return !!draft?.username.trim() && !!draft.roles.length && (draft.id !== null || !!draft.password.trim());
  }

  async saveUser(): Promise<void> {
    const draft = this.userDraft();
    if (!draft || !this.canSaveUser()) {
      this.editorError.set('Заполните логин, пароль и роли.');
      return;
    }

    this.saving.set(true);
    this.editorError.set(null);
    try {
      if (draft.id === null) {
        const request: CreateKeycloakUserRequest = {
          username: draft.username.trim(),
          email: draft.email.trim(),
          fio: draft.fio.trim(),
          phoneNumber: draft.phoneNumber.trim(),
          password: draft.password.trim(),
          temporaryPassword: draft.temporaryPassword,
          enabled: draft.enabled,
          emailVerified: draft.emailVerified,
          coefficient: draft.coefficient ?? undefined,
          roles: draft.roles
        };
        await firstValueFrom(this.api.createAdminUser(request));
      } else {
        const request: UpdateKeycloakUserRequest = {
          email: draft.email.trim(),
          fio: draft.fio.trim(),
          phoneNumber: draft.phoneNumber.trim(),
          coefficient: draft.coefficient ?? undefined,
          enabled: draft.enabled,
          roles: draft.roles
        };
        await firstValueFrom(this.api.updateAdminUser(draft.id, request));
      }
      this.userDraft.set(null);
      await this.reload();
    } catch (error) {
      this.editorError.set(this.errorMessage(error, 'Пользователь не сохранен.'));
    } finally {
      this.saving.set(false);
    }
  }

  async deleteUser(user: AdminUser): Promise<void> {
    if (this.isAdminUser(user)) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Удалить пользователя',
      message: `Удалить пользователя ${user.username}?`,
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.busyUserId.set(user.id);
    try {
      await firstValueFrom(this.api.deleteAdminUser(user.id));
      await this.reload();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Пользователь не удален.'));
    } finally {
      this.busyUserId.set(null);
    }
  }

  async uploadPhoto(user: AdminUser, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0] ?? null;
    if (!file) {
      return;
    }

    this.busyUserId.set(user.id);
    try {
      const preparedFile = await this.media.prepareImageFile(file, `user-${user.id}`, { maxDimension: 900, maxBytes: 350 * 1024, quality: 0.78 });
      const updated = await firstValueFrom(this.api.updateAdminUserPhoto(user.id, preparedFile));
      this.users.update((users) => users.map((item) => item.id === updated.id ? updated : item));
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Фото не загружено.'));
    } finally {
      this.busyUserId.set(null);
      if (input) {
        input.value = '';
      }
    }
  }

  openPassword(user: AdminUser): void {
    this.passwordUser.set(user);
    this.passwordDraft.set({ password: '', temporary: false });
    this.editorError.set(null);
  }

  closePassword(): void {
    if (!this.saving()) {
      this.passwordUser.set(null);
      this.editorError.set(null);
    }
  }

  setPasswordField<K extends keyof ChangeKeycloakPasswordRequest>(field: K, value: ChangeKeycloakPasswordRequest[K]): void {
    this.passwordDraft.update((draft) => ({ ...draft, [field]: value }));
  }

  async savePassword(): Promise<void> {
    const user = this.passwordUser();
    const draft = this.passwordDraft();
    if (!user || !draft.password.trim()) {
      this.editorError.set('Введите новый пароль.');
      return;
    }

    this.saving.set(true);
    try {
      await firstValueFrom(this.api.changeAdminUserPassword(user.id, { password: draft.password.trim(), temporary: draft.temporary }));
      this.passwordUser.set(null);
    } catch (error) {
      this.editorError.set(this.errorMessage(error, 'Пароль не изменен.'));
    } finally {
      this.saving.set(false);
    }
  }

  async openAssignments(user: AdminUser): Promise<void> {
    this.assignmentUser.set(user);
    this.assignmentOptions.set(null);
    this.assignmentDraft.set(null);
    this.editorError.set(null);
    try {
      const [options, assignments] = await Promise.all([
        firstValueFrom(this.api.getAdminUserAssignmentOptions()),
        firstValueFrom(this.api.getAdminUserAssignments(user.id))
      ]);
      this.assignmentOptions.set(options);
      this.assignmentDraft.set(this.assignmentRequestFrom(assignments));
    } catch (error) {
      this.editorError.set(this.errorMessage(error, 'Назначения не загружены.'));
    }
  }

  closeAssignments(): void {
    if (!this.saving()) {
      this.assignmentUser.set(null);
      this.assignmentOptions.set(null);
      this.assignmentDraft.set(null);
      this.editorError.set(null);
    }
  }

  toggleAssignment(group: AssignmentGroup, userId: number): void {
    this.assignmentDraft.update((draft) => draft ? {
      ...draft,
      [group]: draft[group].includes(userId)
        ? draft[group].filter((id) => id !== userId)
        : [...draft[group], userId]
    } : draft);
  }

  isAssigned(group: AssignmentGroup, userId: number): boolean {
    return this.assignmentDraft()?.[group].includes(userId) ?? false;
  }

  async saveAssignments(): Promise<void> {
    const user = this.assignmentUser();
    const draft = this.assignmentDraft();
    if (!user || !draft) {
      return;
    }

    this.saving.set(true);
    try {
      await firstValueFrom(this.api.updateAdminUserAssignments(user.id, draft));
      this.assignmentUser.set(null);
    } catch (error) {
      this.editorError.set(this.errorMessage(error, 'Назначения не сохранены.'));
    } finally {
      this.saving.set(false);
    }
  }

  initials(user: AdminUser): string {
    const source = user.fio || user.username || '?';
    return source.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase()).join('') || '?';
  }

  roleLabel(role: string): string {
    return {
      ADMIN: 'Админ',
      OWNER: 'Владелец',
      MANAGER: 'Менеджер',
      WORKER: 'Специалист',
      OPERATOR: 'Оператор',
      MARKETOLOG: 'Маркетолог'
    }[role] ?? role;
  }

  assignmentLabel(option: AssignmentOption): string {
    return option.fio || option.username || option.email || `ID ${option.userId}`;
  }

  isAdminUser(user: AdminUser): boolean {
    return user.roles.includes('ADMIN');
  }

  isDraftAdmin(): boolean {
    return this.userDraft()?.roles.includes('ADMIN') ?? false;
  }

  numberOrNull(value: unknown): number | null {
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  private assignmentRequestFrom(assignments: UserAssignments): UpdateUserAssignmentsRequest {
    return {
      managerIds: [...assignments.managerIds],
      workerIds: [...assignments.workerIds],
      operatorIds: [...assignments.operatorIds],
      marketologIds: [...assignments.marketologIds]
    };
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { message?: string; error?: string } | string }).error;
      if (typeof body === 'string') {
        return body;
      }
      return body?.message || body?.error || fallback;
    }
    return error instanceof Error ? error.message : fallback;
  }
}
