import { ChangeDetectionStrategy } from '@angular/core';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular';
import { firstValueFrom } from 'rxjs';
import {
  MobileArchiveAdminApi,
  type MobileArchiveBatchDetails,
  type MobileArchiveBatchSummary,
  type MobileArchiveLockStatus,
  type MobileArchivePreview,
  type MobileArchiveRunMode,
  type MobileArchiveRunRequest,
  type MobileArchiveRunResult,
  type MobileArchiveSettings,
  type MobileArchiveSettingsRequest
} from '../core/archive-admin.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

type ArchiveTab = 'settings' | 'candidates' | 'journal';

const defaultSettings = (): MobileArchiveSettingsRequest => ({
  archiveRetentionDays: 90, batchSize: 500, applyEnabled: false, scheduleWorkerEnabled: false,
  scheduleEnabled: false, runMode: 'dry-run', reason: 'scheduled-orders-retention-dry-run',
  scheduleTime: '04:15', scheduleZone: 'Asia/Irkutsk'
});

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-archive-admin-mobile',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Архиватор" />
      <ion-content>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)"><ion-refresher-content /></ion-refresher>
        <main class="archive-page">
          <header class="page-head"><div><small>АДМИН / СИСТЕМА</small><h1>Архиватор заказов</h1></div><button type="button" aria-label="Обновить" (click)="load()" [disabled]="loading() || busy()"><span class="material-icons-sharp">refresh</span></button></header>
          @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
          @if (notice()) { <p class="alert success" role="status">{{ notice() }}</p> }
          @if (loading() && !settings()) { <p class="card">Загружаю архиватор…</p> }
          @if (settings(); as current) {
            <nav class="tabs" aria-label="Разделы архиватора">
              <button type="button" [class.active]="tab() === 'settings'" (click)="tab.set('settings')">Настройки</button>
              <button type="button" [class.active]="tab() === 'candidates'" (click)="tab.set('candidates')">Кандидаты</button>
              <button type="button" [class.active]="tab() === 'journal'" (click)="tab.set('journal')">Журнал</button>
            </nav>

            @if (tab() === 'settings') {
              <section class="card">
                <h2>Состояние</h2>
                <div class="metrics"><span><b>{{ current.boardLiveSliceRetentionDays }} дн.</b>Рабочий срез</span><span><b>{{ current.archiveRetentionDays }} дн.</b>Возраст кандидатов</span><span><b>{{ current.batchSize }}</b>Размер пачки</span><span><b>{{ current.applyEnabled ? 'Разрешён' : 'Выключен' }}</b>Live перенос</span></div>
                <p class="hint">Закрытый заказ может оставаться в live-таблицах. Dry-run пишет проверочный batch без переноса; live копирует данные в archive_* и удаляет live-строки после сверки на сервере.</p>
              </section>
              <section class="card">
                <h2>Параметры запуска</h2>
                <label class="field"><span>Возраст закрытых заказов, дней</span><input type="number" min="1" step="1" [ngModel]="draft.archiveRetentionDays" (ngModelChange)="setRetention($event)" inputmode="numeric"></label>
                <label class="field"><span>Размер пачки (максимум {{ current.maxBatchSize }})</span><input type="number" min="1" [max]="current.maxBatchSize" step="1" [ngModel]="draft.batchSize" (ngModelChange)="setBatchSize($event)" inputmode="numeric"></label>
                <label class="field"><span>Причина ручного запуска</span><input type="text" maxlength="100" [ngModel]="manualReason" (ngModelChange)="manualReason = $event" placeholder="Причина для batch-журнала"></label>
                <div class="mode-tabs" role="group" aria-label="Режим ручного запуска"><button type="button" [class.active]="mode() === 'dry-run'" (click)="mode.set('dry-run')">Dry-run</button><button type="button" [class.active]="mode() === 'live'" (click)="mode.set('live')" [disabled]="!current.applyEnabled">Live</button></div>
                <p class="hint">Dry-run безопасно считает будущую пачку. Live доступен только при сохранённом разрешении на сервере.</p>
                <button type="button" class="primary" (click)="run()" [disabled]="busy() || !validRun() || (mode() === 'live' && !canRunLive())">{{ busy() ? 'Выполняю…' : mode() === 'live' ? 'Запустить live перенос' : 'Проверить dry-run' }}</button>
                @if (mode() === 'live' && !canRunLive()) { <p class="hint">Для live сначала сохраните разрешение, проверьте кандидатов с текущими параметрами, отсутствие незакрытых месяцев аналитики и свободный lock.</p> }
              </section>
              <section class="card">
                <h2>Расписание и предохранитель</h2>
                <label class="check"><input type="checkbox" [(ngModel)]="draft.applyEnabled" (ngModelChange)="onApplyChange()"> Разрешить live перенос</label>
                <label class="check"><input type="checkbox" [(ngModel)]="draft.scheduleWorkerEnabled"> Включить планировщик</label>
                <label class="check"><input type="checkbox" [(ngModel)]="draft.scheduleEnabled"> Ежедневный запуск</label>
                <label class="field"><span>Время запуска</span><input type="time" [(ngModel)]="draft.scheduleTime"></label>
                <label class="field"><span>Часовой пояс</span><input type="text" [(ngModel)]="draft.scheduleZone" placeholder="Asia/Irkutsk"></label>
                <label class="field"><span>Режим по расписанию</span><select [(ngModel)]="draft.runMode"><option value="dry-run">Dry-run</option><option value="live" [disabled]="!draft.applyEnabled">Live перенос</option></select></label>
                <label class="field"><span>Причина для журнала</span><input type="text" maxlength="100" [(ngModel)]="draft.reason"></label>
                <p class="hint">Сохранённое расписание: {{ current.scheduleWorkerEnabled ? current.scheduleEnabled ? 'активно' : 'пауза' : 'планировщик выключен' }} · {{ current.runMode }} · {{ current.scheduleTime }} · {{ current.scheduleZone }} · {{ current.scheduleCron }}</p>
                <button type="button" (click)="saveSettings()" [disabled]="busy() || !validSettings()">Сохранить настройки</button>
              </section>
              @if (result(); as latest) { <section class="card"><h2>Последний результат</h2><p><b>Batch #{{ latest.batchId }} · {{ latest.dryRun ? 'dry-run' : 'live' }}</b></p><p>Кандидатов {{ latest.eligibleOrders }}, в пачке {{ latest.selected.orders }}, отзывов {{ latest.selected.reviews }}.</p><p>Незакрытых месяцев аналитики: {{ latest.missingClosedAnalyticsMonths }}</p><p>{{ latest.message }}</p></section> }
            }

            @if (tab() === 'candidates') {
              <section class="card"><div class="section-head"><h2>Ближайшая пачка</h2><button type="button" (click)="loadPreview()" [disabled]="busy() || !validRun()">Обновить</button></div>
                @if (preview(); as candidates) {
                  <p class="hint">Граница {{ candidates.cutoffDate }} · {{ candidates.selected.orders }} из {{ candidates.eligibleOrders }} заказов. Незакрытых месяцев аналитики: <b>{{ candidates.missingClosedAnalyticsMonths }}</b>.</p>
                  <p class="hint">Параметры предпросмотра: {{ candidates.retentionDays }} дней, пачка {{ candidates.batchLimit }}.</p>
                  @for (item of candidates.items; track item.id) { <article class="candidate"><header><strong>{{ item.companyTitle || 'Без компании' }}</strong><small>#{{ item.id }} · {{ item.status }}</small></header><p>{{ item.sum }} ₽ · {{ item.orderDetailsCount }} деталей · {{ item.reviewsCount }} отзывов</p><small>{{ item.companyCity || item.filialTitle || 'Город не указан' }} · дата {{ item.candidateDate || '—' }}</small></article> }
                  @if (!candidates.items.length) { <p class="hint">Подходящих кандидатов нет.</p> }
                } @else { <p class="hint">Кандидаты ещё не загружены.</p> }
              </section>
              <section class="card"><h2>Проверка запуска</h2><p>Lock: <b>{{ lock()?.locked ? 'занят' : 'свободен' }}</b> @if (lock()?.ownerConnectionId) { · connection {{ lock()?.ownerConnectionId }} }</p><button type="button" (click)="loadLock()" [disabled]="busy()">Проверить lock</button><p class="hint">Параллельный запуск блокируется backend. Live дополнительно проверяет сохранённое разрешение и закрытие месяцев аналитики.</p></section>
            }

            @if (tab() === 'journal') {
              <section class="card"><div class="section-head"><h2>Журнал запусков</h2><button type="button" (click)="loadBatches()" [disabled]="busy()">Обновить</button></div>
                @for (batch of batches(); track batch.batchId) { <article class="batch"><header><strong>#{{ batch.batchId }} · {{ batch.dryRun ? 'dry-run' : 'live' }}</strong><span>{{ batch.status }}</span></header><small>{{ displayDate(batch.startedAt) }} · возраст {{ batch.retentionDays }} дн.</small><p>{{ batch.ordersSelected }} выбрано · {{ batch.ordersArchived }} перенесено · {{ batch.reviewsArchived }} отзывов · {{ batch.paymentCheckArchived }} оплат</p><small>{{ batch.archiveReason }}</small><button type="button" (click)="openBatch(batch)" [disabled]="busy()">Детали</button></article> }
                @if (!batches().length) { <p class="hint">Запусков пока нет.</p> }
              </section>
              @if (selectedBatch(); as details) { <section class="card"><div class="section-head"><h2>Batch #{{ details.summary.batchId }}</h2><button type="button" (click)="selectedBatch.set(null)">Закрыть</button></div><p>{{ details.summary.message }}</p><p>Заказов: {{ details.totals.orders }} · отзывов: {{ details.totals.reviews }} · оплат: {{ details.totals.paymentCheck }}</p>@for (order of details.orders; track order.id) { <article class="candidate"><strong>{{ order.companyTitle || 'Без компании' }} · #{{ order.id }}</strong><small>{{ order.status }} · {{ order.sum }} ₽ · {{ order.reviewsCount }} отзывов</small></article> } @if (!details.orders.length) { <p class="hint">{{ details.summary.dryRun ? 'Dry-run хранит числа без списка заказов.' : 'В batch нет перенесённых заказов.' }}</p> }</section> }
            }
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    :host{display:contents}ion-content{--background:var(--otziv-background)}.archive-page{display:grid;gap:.75rem;max-width:48rem;margin:auto;padding:1rem .8rem calc(5rem + env(safe-area-inset-bottom));color:var(--otziv-dark)}.page-head,.section-head,.candidate header,.batch header{display:flex;align-items:center;justify-content:space-between;gap:.5rem}.page-head h1{margin:.1rem 0;font-size:1.3rem}.page-head small{font-size:.65rem;font-weight:900;color:var(--otziv-info)}h2{margin:0;font-size:.95rem}button{min-height:2.5rem;border:1px solid rgba(108,155,207,.3);border-radius:.7rem;padding:.5rem .7rem;background:var(--otziv-white);color:var(--otziv-primary);font:inherit;font-size:.76rem;font-weight:800}button:disabled{opacity:.48}button.primary{background:var(--otziv-primary);border-color:var(--otziv-primary);color:white}.card,.candidate,.batch{display:grid;gap:.55rem;border:1px solid rgba(108,155,207,.23);border-radius:.9rem;padding:.8rem;background:var(--otziv-white)}.candidate,.batch{padding:.6rem;background:var(--otziv-muted-surface)}.candidate strong,.batch strong{font-size:.77rem}.candidate small,.batch small,.candidate p,.batch p{margin:0;color:var(--otziv-info);font-size:.69rem;line-height:1.4}.card>p{margin:.1rem 0;font-size:.77rem;line-height:1.45}.hint{margin:0!important;color:var(--otziv-info);font-size:.71rem!important;line-height:1.45}.tabs,.mode-tabs{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.35rem}.mode-tabs{grid-template-columns:repeat(2,minmax(0,1fr))}.tabs button.active,.mode-tabs button.active{background:var(--otziv-primary);color:#fff}.metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.4rem}.metrics span{display:grid;gap:.2rem;padding:.5rem;border-radius:.55rem;background:var(--otziv-muted-surface);font-size:.65rem}.metrics b{font-size:.85rem}.field{display:grid;gap:.25rem;color:var(--otziv-info);font-size:.7rem;font-weight:800}.field input,.field select{box-sizing:border-box;width:100%;min-height:2.6rem;border:1px solid rgba(108,155,207,.3);border-radius:.65rem;padding:.55rem;background:var(--otziv-white);color:var(--otziv-dark);font:inherit;font-size:.83rem}.check{display:flex;align-items:center;gap:.4rem;font-size:.75rem}.check input{accent-color:var(--otziv-primary);width:1.05rem;height:1.05rem}.alert{margin:0;padding:.7rem;border-radius:.7rem;background:#fff0f4;color:var(--otziv-danger);font-size:.75rem}.alert.success{background:#eafaf3;color:#127966}.batch button{justify-self:start}:host-context(body.otziv-dark-theme) .card,:host-context(body.otziv-dark-theme) button:not(.active):not(.primary),:host-context(body.otziv-dark-theme) .field input,:host-context(body.otziv-dark-theme) .field select{background:#1f2629}
  `]
})
export class ArchiveAdminPage implements OnInit {
  private readonly api = inject(MobileArchiveAdminApi);
  private readonly confirm = inject(MobileConfirmService);
  readonly tab = signal<ArchiveTab>('settings');
  readonly settings = signal<MobileArchiveSettings | null>(null);
  readonly preview = signal<MobileArchivePreview | null>(null);
  readonly lock = signal<MobileArchiveLockStatus | null>(null);
  readonly batches = signal<MobileArchiveBatchSummary[]>([]);
  readonly selectedBatch = signal<MobileArchiveBatchDetails | null>(null);
  readonly result = signal<MobileArchiveRunResult | null>(null);
  readonly mode = signal<MobileArchiveRunMode>('dry-run');
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly canRunLive = computed(() => {
    const preview = this.preview();
    const settings = this.settings();
    const request = this.runRequest();
    return !!settings?.applyEnabled && !!this.lock() && !this.lock()!.locked && !!preview && preview.selected.orders > 0
      && preview.missingClosedAnalyticsMonths === 0 && preview.retentionDays === request.retentionDays
      && preview.batchLimit === request.batchLimit && !this.busy();
  });
  draft = defaultSettings();
  manualReason = 'manual-orders-retention-dry-run';
  private previewEpoch = 0;

  ngOnInit(): void { void this.load(); }
  async refresh(event: RefresherCustomEvent): Promise<void> { await this.load(); event.target.complete(); }

  async load(): Promise<void> {
    if (this.busy() || this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    try {
      const settings = await firstValueFrom(this.api.settings());
      this.settings.set(settings);
      this.applySettings(settings);
      await Promise.all([this.loadPreview(), this.loadLock(), this.loadBatches()]);
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось загрузить настройки архиватора.')); }
    finally { this.loading.set(false); }
  }

  setRetention(value: unknown): void { this.draft.archiveRetentionDays = Number(value); this.invalidatePreview(); }
  setBatchSize(value: unknown): void { this.draft.batchSize = Number(value); this.invalidatePreview(); }
  invalidatePreview(): void { this.previewEpoch++; this.preview.set(null); }
  onApplyChange(): void { if (!this.draft.applyEnabled) { this.draft.runMode = 'dry-run'; this.mode.set('dry-run'); } }

  validRun(): boolean {
    return this.validParams() && !!this.manualReason.trim() && this.manualReason.trim().length <= 100;
  }

  validSettings(): boolean {
    return this.validParams() && !!this.draft.reason.trim() && this.draft.reason.trim().length <= 100
      && /^([01]\d|2[0-3]):[0-5]\d$/.test(this.draft.scheduleTime)
      && !!this.draft.scheduleZone.trim() && this.draft.scheduleZone.length <= 100
      && (this.draft.runMode !== 'live' || this.draft.applyEnabled);
  }

  async loadPreview(): Promise<void> {
    if (!this.validParams()) return;
    const epoch = ++this.previewEpoch;
    this.preview.set(null);
    try {
      const preview = await firstValueFrom(this.api.preview(this.runRequest()));
      if (epoch === this.previewEpoch) this.preview.set(preview);
    } catch (error) { if (epoch === this.previewEpoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить кандидатов.')); }
  }

  async loadLock(): Promise<void> {
    try { this.lock.set(await firstValueFrom(this.api.lock())); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось проверить lock архиватора.')); }
  }

  async loadBatches(): Promise<void> {
    try { this.batches.set(await firstValueFrom(this.api.batches())); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось загрузить журнал архиватора.')); }
  }

  async saveSettings(): Promise<void> {
    if (this.busy() || !this.validSettings()) return;
    const current = this.settings();
    const enablingLive = this.draft.applyEnabled && !current?.applyEnabled;
    const schedulingLive = this.draft.scheduleWorkerEnabled && this.draft.scheduleEnabled && this.draft.runMode === 'live'
      && !(current?.scheduleWorkerEnabled && current.scheduleEnabled && current.runMode === 'live');
    if ((enablingLive || schedulingLive) && !await this.confirm.confirm({
      title: schedulingLive ? 'Разрешить live по расписанию' : 'Разрешить live перенос',
      message: schedulingLive
        ? 'Планировщик сможет ежедневно физически переносить подходящие заказы. Проверьте возраст, размер пачки, время и часовой пояс.'
        : 'После сохранения станет доступен ручной физический перенос в архив. Проверьте предохранитель и параметры.',
      confirmText: 'Сохранить', danger: true, confirmDelayMs: 1200
    })) return;
    this.busy.set(true); this.error.set(null); this.notice.set(null);
    try {
      const settings = await firstValueFrom(this.api.updateSettings({ ...this.draft, reason: this.draft.reason.trim(), scheduleZone: this.draft.scheduleZone.trim() }));
      this.settings.set(settings);
      this.applySettings(settings);
      this.notice.set('Настройки архиватора сохранены.');
      await Promise.all([this.loadPreview(), this.loadLock()]);
    } catch (error) { this.error.set(this.errorMessage(error, 'Настройки архиватора не сохранены.')); }
    finally { this.busy.set(false); }
  }

  async run(): Promise<void> {
    if (this.busy() || !this.validRun()) return;
    const live = this.mode() === 'live';
    if (live) {
      if (!this.canRunLive()) { this.error.set('Live перенос недоступен. Обновите кандидатов и проверьте защиту, закрытие месяцев аналитики и lock.'); return; }
      if (!await this.confirm.confirm({
        title: 'Физически перенести заказы в архив',
        message: `Будут перенесены до ${this.preview()!.selected.orders} заказов старше ${this.draft.archiveRetentionDays} дней. После копирования и проверки сервер удалит эти строки из рабочих таблиц. Причина: ${this.manualReason.trim()}.`,
        confirmText: 'Запустить live', danger: true, confirmDelayMs: 1800
      })) return;
    }
    this.busy.set(true); this.error.set(null); this.notice.set(null); this.result.set(null);
    try {
      const request = this.runRequest();
      const result = await firstValueFrom(live ? this.api.runLive(request) : this.api.dryRun(request));
      this.result.set(result);
      this.notice.set(live ? `Live перенос завершён: batch #${result.batchId}.` : `Dry-run завершён: batch #${result.batchId}.`);
      await Promise.all([this.loadBatches(), this.loadPreview(), this.loadLock()]);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Запуск не подтверждён. Проверьте журнал перед повтором.'));
      await this.loadBatches();
    } finally { this.busy.set(false); }
  }

  async openBatch(batch: MobileArchiveBatchSummary): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true); this.error.set(null);
    try { this.selectedBatch.set(await firstValueFrom(this.api.batchDetails(batch.batchId))); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось загрузить детали batch.')); }
    finally { this.busy.set(false); }
  }

  displayDate(value: string | null): string { return value ? value.replace('T', ' ').slice(0, 16) : '—'; }
  private validParams(): boolean {
    const max = this.settings()?.maxBatchSize ?? 5000;
    return Number.isInteger(this.draft.archiveRetentionDays) && this.draft.archiveRetentionDays > 0
      && Number.isInteger(this.draft.batchSize) && this.draft.batchSize > 0 && this.draft.batchSize <= max;
  }
  private runRequest(): MobileArchiveRunRequest { return { retentionDays: this.draft.archiveRetentionDays, batchLimit: this.draft.batchSize, reason: this.manualReason.trim() }; }
  private applySettings(settings: MobileArchiveSettings): void {
    this.draft = {
      archiveRetentionDays: settings.archiveRetentionDays, batchSize: settings.batchSize,
      applyEnabled: settings.applyEnabled, scheduleWorkerEnabled: settings.scheduleWorkerEnabled,
      scheduleEnabled: settings.scheduleEnabled, runMode: settings.runMode,
      reason: settings.reason, scheduleTime: settings.scheduleTime || '04:15',
      scheduleZone: settings.scheduleZone || 'Asia/Irkutsk'
    };
    this.mode.set(settings.applyEnabled ? settings.runMode : 'dry-run');
    this.manualReason = settings.reason || 'manual-orders-retention-dry-run';
    this.invalidatePreview();
  }
  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { detail?: string; message?: string } | string }).error;
      if (typeof body === 'string' && body.trim()) return body;
      if (body && typeof body === 'object') return body.detail || body.message || fallback;
    }
    return fallback;
  }
}
