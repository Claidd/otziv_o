import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  type MobileAdminPerformer,
  type MobilePerformerAssignment,
  type MobilePerformerControl,
  PerformerAdminApi
} from '../core/performer-admin.api';
import { safeHttpsExternalUrl } from '../shared/external-navigation';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

type ControlSection = 'overview' | 'performers' | 'assignments';

@Component({
  selector: 'app-admin-performers-mobile',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Исполнители" />
      <ion-content fullscreen>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)"><ion-refresher-content /></ion-refresher>
        <main class="performers-page">
          <header class="page-title">
            <div><p class="eyebrow">КОНТРОЛЬ</p><h1>Исполнители</h1></div>
            <button type="button" class="icon-button" aria-label="Обновить" (click)="load()" [disabled]="loading() || !!saving()">
              <span class="material-icons-sharp">refresh</span>
            </button>
          </header>

          @if (notice()) { <p class="alert success" role="status">{{ notice() }}</p> }
          @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
          @if (loading() && !control()) { <p class="empty">Загружаю контроль исполнителей…</p> }

          @if (control(); as data) {
            <nav class="sections" aria-label="Разделы контроля">
              <button type="button" [class.active]="section() === 'overview'" (click)="section.set('overview')">Раздача</button>
              <button type="button" [class.active]="section() === 'performers'" (click)="section.set('performers')">Люди · {{ data.performers.length }}</button>
              <button type="button" [class.active]="section() === 'assignments'" (click)="section.set('assignments')">Задания · {{ data.assignments.length }}</button>
            </nav>

            @if (section() === 'overview') {
              <section class="card">
                <h2>Тестовый запуск</h2>
                <label class="check"><input type="checkbox" [ngModel]="rolloutEnabled()" (ngModelChange)="rolloutEnabled.set($event)"> Раздача исполнителям включена</label>
                <label class="field"><span>ID городов через запятую</span><input type="text" [ngModel]="rolloutCityIds()" (ngModelChange)="rolloutCityIds.set($event)" placeholder="Без ограничения"></label>
                <label class="field"><span>ID продуктов через запятую</span><input type="text" [ngModel]="rolloutProductIds()" (ngModelChange)="rolloutProductIds.set($event)" placeholder="Без ограничения"></label>
                <button type="button" class="primary" (click)="saveRollout()" [disabled]="!!saving()">{{ saving() === 'rollout' ? 'Сохраняю…' : 'Сохранить настройки' }}</button>
                <small>Сейчас: {{ data.rollout.enabled ? 'включено' : 'выключено' }}; города: {{ data.rollout.parsedCityIds.length ? data.rollout.parsedCityIds.join(', ') : 'все' }}; продукты: {{ data.rollout.parsedProductIds.length ? data.rollout.parsedProductIds.join(', ') : 'все' }}.</small>
              </section>

              <section class="card">
                <h2>Ручной запуск</h2>
                <label class="field"><span>ID существующего заказа</span><input type="number" min="1" step="1" inputmode="numeric" [ngModel]="manualOrderId()" (ngModelChange)="setManualOrderId($event)" placeholder="Номер заказа"></label>
                <div class="actions">
                  <button type="button" class="primary" (click)="createAssignments()" [disabled]="!!saving()">Создать задания</button>
                  <button type="button" (click)="runScheduler()" [disabled]="!!saving()">Прогнать раздачу</button>
                </div>
                <small>Для существующего заказа сначала создайте задания, затем запустите раздачу.</small>
              </section>

              <section class="city-grid" aria-label="Отчёт по городам">
                @for (city of data.cityReports; track city.cityId) {
                  <article class="card"><h2>{{ city.cityTitle }}</h2><p>Исполнителей: <strong>{{ city.activePerformers }}</strong></p><p>Очередь: <strong>{{ city.queueAssignments }}</strong></p><p>В работе: <strong>{{ city.activeAssignments }}</strong></p><p>Готово: <strong>{{ city.verifiedAssignments }}</strong></p></article>
                } @empty { <p class="empty">Нет данных по городам.</p> }
              </section>
            }

            @if (section() === 'performers') {
              <section class="card">
                <h2>Доступ исполнителей</h2>
                <p class="hint">Перед первой активацией исполнитель должен привязать личный Telegram. Проверьте телефон вне системы, отметьте проверку и укажите её способ. После активации выдайте временный пароль в разделе «Пользователи».</p>
                <label class="field"><span>Причина изменения / способ проверки телефона</span><input type="text" [ngModel]="statusReason()" (ngModelChange)="statusReason.set($event)" placeholder="Например: звонок на указанный номер"></label>
                <label class="field"><span>Найти исполнителя</span><input type="search" [ngModel]="performerSearch()" (ngModelChange)="performerSearch.set($event)" placeholder="Имя, город, логин"></label>
              </section>
              @for (person of visiblePerformers(); track person.id) {
                <article class="card person-card">
                  <header><div><h2>{{ person.fio || person.username }}</h2><small>{{ person.cityTitle || 'Город не указан' }} · {{ person.username }}</small></div><span class="status" [class.active]="person.status === 'ACTIVE'">{{ statusLabel(person.status) }}</span></header>
                  <p>Telegram: {{ person.telegramChatId || 'не привязан' }} · Готово / отменено / истекло: {{ person.completedCount }} / {{ person.cancelledCount }} / {{ person.expiredOfferCount }}</p>
                  @if (person.status !== 'ACTIVE') {
                    <p class="hint">{{ person.activationReady ? 'Готов к проверке телефона' : 'Ожидает Telegram или согласия' }}</p>
                    <label class="check"><input type="checkbox" [ngModel]="phoneChecked()[person.id] === true" (ngModelChange)="setPhoneChecked(person.id, $event)"> Телефон проверен вне системы</label>
                  }
                  @if (person.legacyApprovedBeforeSecureLifecycle) { <p class="warning">Активирован до защищённой регистрации. При повторной активации нужна новая проверка телефона.</p> }
                  @if (person.activationWarning) { <p class="warning">{{ person.activationWarning }}</p> }
                  <div class="actions">
                    <button type="button" class="primary" (click)="updateStatus(person, 'ACTIVE')" [disabled]="!!saving() || person.status === 'ACTIVE' || !canActivate(person)">Активировать</button>
                    <button type="button" (click)="updateStatus(person, 'PAUSED')" [disabled]="!!saving() || person.status === 'PAUSED'">Пауза</button>
                    <button type="button" class="danger" (click)="updateStatus(person, 'BLOCKED')" [disabled]="!!saving() || person.status === 'BLOCKED'">Блок</button>
                  </div>
                </article>
              } @empty { <p class="empty">Исполнители не найдены.</p> }
            }

            @if (section() === 'assignments') {
              <section class="card"><h2>Задания</h2><label class="field"><span>Найти задание</span><input type="search" [ngModel]="assignmentSearch()" (ngModelChange)="assignmentSearch.set($event)" placeholder="Заказ, компания, статус"></label></section>
              @for (task of visibleAssignments(); track task.id) {
                <article class="card task-card">
                  <header><div><h2>#{{ task.id }} · {{ task.companyTitle || 'Компания' }}</h2><small>{{ task.cityTitle }} · {{ task.platform }} · отзыв #{{ task.reviewId }} · заказ #{{ task.orderId }}</small></div><span class="status">{{ task.status }}</span></header>
                  @if (task.externalConfirmStatus) { <p>Внешняя проверка: {{ task.externalConfirmStatus }}</p> }
                  @if (task.draftText) { <p class="draft">{{ task.draftText }}</p> }
                  <div class="actions">
                    @if (safeScreenshot(task.performerPublicationScreenshotUrl)) { <button type="button" (click)="openScreenshot(task.performerPublicationScreenshotUrl)">Скриншот исполнителя</button> }
                    @if (safeScreenshot(task.managerConfirmationScreenshotUrl)) { <button type="button" (click)="openScreenshot(task.managerConfirmationScreenshotUrl)">Скриншот менеджера</button> }
                  </div>
                  @if (task.status === 'PUBLISHED_CLAIMED') {
                    <label class="field"><span>Скриншот подтверждения менеджера</span><input type="file" accept="image/*" (change)="uploadScreenshot(task, $event)" [disabled]="!!saving()"></label>
                    <button type="button" class="primary" (click)="verifyAssignment(task)" [disabled]="!!saving()">Подтвердить вручную</button>
                  }
                </article>
              } @empty { <p class="empty">Задания не найдены.</p> }
            }
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .performers-page{display:grid;gap:.8rem;max-width:900px;margin:auto;padding:1rem .75rem calc(5rem + env(safe-area-inset-bottom));color:var(--otziv-dark)}
    .page-title,.card header{display:flex;align-items:start;justify-content:space-between;gap:.7rem}.page-title h1{margin:.05rem 0;font-size:1.45rem}.eyebrow{margin:0;color:var(--otziv-info);font-size:.65rem;font-weight:900;letter-spacing:.09em}
    button{border:1px solid rgba(108,155,207,.3);border-radius:.75rem;padding:.55rem .75rem;color:var(--otziv-primary);background:var(--otziv-white);font:inherit;font-size:.78rem;font-weight:800}button:disabled{opacity:.48}button.primary{border-color:transparent;color:white;background:var(--otziv-primary)}button.danger{color:var(--otziv-danger)}.icon-button{display:grid;place-items:center;width:2.2rem;height:2.2rem;padding:0}.icon-button span{font-size:1.1rem}
    .sections{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.3rem}.sections button{min-width:0;padding:.55rem .28rem;font-size:.7rem}.sections button.active{color:white;background:var(--otziv-primary)}
    .card,.empty{display:grid;gap:.65rem;border:1px solid rgba(108,155,207,.23);border-radius:1rem;padding:.85rem;background:var(--otziv-white);box-shadow:0 .35rem 1.25rem rgba(62,81,121,.07)}.card h2{margin:0;font-size:1rem}.card p,.card small,.hint{margin:0;color:var(--otziv-info);font-size:.75rem;line-height:1.4}.card p strong{color:var(--otziv-dark)}.card header small{display:block;margin-top:.25rem}.city-grid{display:grid;gap:.6rem;grid-template-columns:repeat(auto-fit,minmax(155px,1fr))}.city-grid .card{gap:.35rem}.city-grid p{display:flex;justify-content:space-between}
    .field{display:grid;gap:.3rem;color:var(--otziv-info);font-size:.72rem;font-weight:800}.field input{width:100%;min-width:0;box-sizing:border-box;border:1px solid rgba(108,155,207,.3);border-radius:.7rem;padding:.65rem;color:var(--otziv-dark);background:var(--otziv-white);font:inherit;font-size:.85rem}.check{display:flex;align-items:center;gap:.4rem;font-size:.75rem;font-weight:750}.check input{width:1rem;height:1rem;accent-color:var(--otziv-primary)}.actions{display:flex;flex-wrap:wrap;gap:.4rem}.actions button{flex:1 1 auto}.status{flex:0 0 auto;border-radius:999px;padding:.25rem .45rem;color:var(--otziv-info);background:var(--otziv-light);font-size:.6rem;font-weight:900}.status.active{color:#127966;background:#e0f6ef}.warning{color:var(--otziv-danger)!important}.draft{white-space:pre-wrap;overflow-wrap:anywhere}.alert{border:1px solid rgba(232,48,103,.32);border-radius:.75rem;padding:.65rem;color:var(--otziv-danger);background:#fff0f4;font-size:.75rem}.alert.success{border-color:rgba(47,159,149,.35);color:#127966;background:#eafaf3}
    :host-context(body.otziv-dark-theme) .card,:host-context(body.otziv-dark-theme) .empty,:host-context(body.otziv-dark-theme) .field input,:host-context(body.otziv-dark-theme) button:not(.primary):not(.active){background:#1f2629}
  `]
})
export class AdminPerformersPage implements OnInit {
  private readonly api = inject(PerformerAdminApi);
  private readonly confirm = inject(MobileConfirmService);
  private readonly links = inject(MobileExternalLinkService);

  readonly loading = signal(false);
  readonly saving = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly control = signal<MobilePerformerControl | null>(null);
  readonly section = signal<ControlSection>('overview');
  readonly rolloutEnabled = signal(false);
  readonly rolloutCityIds = signal('');
  readonly rolloutProductIds = signal('');
  readonly manualOrderId = signal('');
  readonly statusReason = signal('');
  readonly phoneChecked = signal<Record<number, boolean>>({});
  readonly performerSearch = signal('');
  readonly assignmentSearch = signal('');
  readonly visiblePerformers = computed(() => {
    const query = this.performerSearch().trim().toLowerCase();
    return (this.control()?.performers ?? []).filter((person) =>
      !query || [person.fio, person.username, person.cityTitle, person.phoneNumber].some((value) => value?.toLowerCase().includes(query))
    );
  });
  readonly visibleAssignments = computed(() => {
    const query = this.assignmentSearch().trim().toLowerCase();
    return (this.control()?.assignments ?? []).filter((task) =>
      !query || [task.id, task.orderId, task.reviewId, task.companyTitle, task.cityTitle, task.status].some((value) => String(value ?? '').toLowerCase().includes(query))
    );
  });

  ngOnInit(): void { void this.load(); }

  setManualOrderId(value: number | string | null): void { this.manualOrderId.set(String(value ?? '')); }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    try { await this.load(); } finally { await event.target.complete(); }
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const control = await firstValueFrom(this.api.control());
      this.control.set(control);
      this.rolloutEnabled.set(control.rollout.enabled);
      this.rolloutCityIds.set(control.rollout.cityIds ?? '');
      this.rolloutProductIds.set(control.rollout.productIds ?? '');
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось загрузить контроль исполнителей.'));
    } finally {
      this.loading.set(false);
    }
  }

  canActivate(person: MobileAdminPerformer): boolean {
    return person.activationReady && this.phoneChecked()[person.id] === true && !!this.statusReason().trim();
  }

  setPhoneChecked(id: number, checked: boolean): void {
    this.phoneChecked.update((current) => ({ ...current, [id]: checked }));
  }

  statusLabel(status: MobileAdminPerformer['status']): string {
    return ({ NEW: 'Новый', ACTIVE: 'Активен', PAUSED: 'Пауза', BLOCKED: 'Заблокирован', REJECTED: 'Отклонён' })[status];
  }

  async updateStatus(person: MobileAdminPerformer, status: MobileAdminPerformer['status']): Promise<void> {
    if (this.saving() || (status === 'ACTIVE' && !this.canActivate(person))) return;
    const reason = this.statusReason().trim();
    const approved = await this.confirm.confirm({
      title: `${status === 'ACTIVE' ? 'Активировать' : status === 'PAUSED' ? 'Приостановить' : 'Заблокировать'} исполнителя?`,
      message: `${person.fio || person.username}. ${status === 'ACTIVE' ? 'Подтвердите, что телефон уже проверен вне системы.' : 'Доступ к заданиям изменится сразу.'}`,
      confirmText: 'Изменить статус', danger: status !== 'ACTIVE'
    });
    if (!approved) return;
    this.saving.set(`status-${person.id}`);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.updateStatus(person.id, status, reason, status === 'ACTIVE'));
      this.notice.set(`Статус «${person.fio || person.username}» обновлён.`);
      this.setPhoneChecked(person.id, false);
      await this.load();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Статус не изменён.'));
    } finally { this.saving.set(null); }
  }

  async saveRollout(): Promise<void> {
    if (this.saving()) return;
    this.saving.set('rollout'); this.error.set(null);
    try {
      const rollout = await firstValueFrom(this.api.updateRollout({
        enabled: this.rolloutEnabled(), cityIds: this.rolloutCityIds().trim(), productIds: this.rolloutProductIds().trim()
      }));
      this.control.update((current) => current ? { ...current, rollout } : current);
      this.rolloutEnabled.set(rollout.enabled);
      this.rolloutCityIds.set(rollout.cityIds);
      this.rolloutProductIds.set(rollout.productIds);
      this.notice.set(rollout.enabled ? 'Раздача включена.' : 'Раздача выключена.');
    } catch (error) { this.error.set(this.errorMessage(error, 'Настройки раздачи не сохранены.')); }
    finally { this.saving.set(null); }
  }

  async createAssignments(): Promise<void> {
    const raw = this.manualOrderId().trim();
    const orderId = Number(raw);
    if (!/^[1-9]\d*$/.test(raw) || !Number.isSafeInteger(orderId)) {
      this.error.set('Укажите числовой ID заказа.'); return;
    }
    if (this.saving()) return;
    const approved = await this.confirm.confirm({ title: 'Создать задания?', message: `Будут созданы задания по заказу #${orderId}.`, confirmText: 'Создать' });
    if (!approved) return;
    this.saving.set('assignments'); this.error.set(null);
    try {
      const result = await firstValueFrom(this.api.createAssignments(orderId));
      this.notice.set(`По заказу #${orderId} создано заданий: ${result.createdAssignments}.`);
      await this.load();
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось создать задания.')); }
    finally { this.saving.set(null); }
  }

  async runScheduler(): Promise<void> {
    if (this.saving()) return;
    const approved = await this.confirm.confirm({ title: 'Запустить раздачу?', message: 'Новые предложения будут отправлены исполнителям.', confirmText: 'Запустить' });
    if (!approved) return;
    this.saving.set('scheduler'); this.error.set(null);
    try {
      const result = await firstValueFrom(this.api.runScheduler());
      this.notice.set(`Раздача выполнена: предложений ${result.offeredAssignments}, истекло ${result.expiredOffers}, уведомлений ${result.readyNotifications}.`);
      await this.load();
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось запустить раздачу.')); }
    finally { this.saving.set(null); }
  }

  async uploadScreenshot(task: MobilePerformerAssignment, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.saving()) return;
    if (!file.type.startsWith('image/')) { this.error.set('Выберите изображение со скриншотом.'); return; }
    this.saving.set(`screenshot-${task.id}`); this.error.set(null);
    try {
      await firstValueFrom(this.api.uploadManagerScreenshot(task.id, file));
      this.notice.set(`Скриншот для задания #${task.id} загружен.`);
      await this.load();
    } catch (error) { this.error.set(this.errorMessage(error, 'Скриншот не загружен.')); }
    finally { this.saving.set(null); }
  }

  async verifyAssignment(task: MobilePerformerAssignment): Promise<void> {
    if (task.status !== 'PUBLISHED_CLAIMED' || this.saving()) return;
    const approved = await this.confirm.confirm({ title: 'Подтвердить задание?', message: `Проверьте публикацию по заданию #${task.id} перед подтверждением.`, confirmText: 'Подтвердить' });
    if (!approved) return;
    this.saving.set(`verify-${task.id}`); this.error.set(null);
    try {
      await firstValueFrom(this.api.verifyAssignment(task.id));
      this.notice.set(`Задание #${task.id} подтверждено.`);
      await this.load();
    } catch (error) { this.error.set(this.errorMessage(error, 'Задание не подтверждено.')); }
    finally { this.saving.set(null); }
  }

  safeScreenshot(url: string | null | undefined): string | null { return safeHttpsExternalUrl(url); }
  openScreenshot(url: string | null | undefined): void { void this.links.open(this.safeScreenshot(url)); }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { message?: string; error?: string } | string }).error;
      if (typeof body === 'string') return body || fallback;
      return body?.message || body?.error || fallback;
    }
    return error instanceof Error ? error.message : fallback;
  }
}
