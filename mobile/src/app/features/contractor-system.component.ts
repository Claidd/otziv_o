import { ChangeDetectionStrategy } from '@angular/core';
import { Component, EventEmitter, OnInit, Output, signal } from '@angular/core';
import { IonModal } from '@ionic/angular';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { ContractorPaymentSystemStatus, ContractorSystemApi } from '../core/contractor-system.api';
import {
  ACTIVATION_CONFIRMATION, ROUTING_ENABLE_CONFIRMATION, ROUTING_PAUSE_CONFIRMATION,
  canActivateContractorSystem, canChangeContractorRouting, contractorBusinessDate,
  contractorSystemModeLabel, contractorSystemReason
} from '../shared/contractor-system';
import { MobileConfirmService } from '../shared/mobile-confirm.service';

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-contractor-system',
  imports: [IonModal],
  template: `
    <ion-modal class="sheet-modal" style="--height: min(94vh, 52rem); --max-width: 42rem"
      [isOpen]="isOpen()" [canDismiss]="!saving()" (didDismiss)="dismissed.emit()">
      <ng-template>
        <div class="sheet-body sheet-form system-sheet">
          <header class="sheet-head">
            <div><p class="sheet-note">Только владелец</p><h2>Общая система начислений</h2></div>
            <button type="button" class="close-button" (click)="close()" [disabled]="saving()" aria-label="Закрыть">
              <span class="material-icons-sharp">close</span>
            </button>
          </header>
          <div class="sheet-form-content system-content">
            <p class="hint">Эти настройки действуют на всю систему. Персональные допуски и реквизиты сотрудников изменяются в их платёжных профилях.</p>
            @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
            @if (success()) { <p class="success" role="status">{{ success() }}</p> }
            <button type="button" class="secondary" (click)="loadStatus()" [disabled]="loading() || saving()">
              {{ loading() ? 'Обновляю…' : 'Обновить состояние' }}
            </button>
            @if (status(); as state) {
              <section class="system-card">
                <h3>{{ modeLabel(state) }}</h3>
                <div class="status-grid">
                  <span>Начисления</span><strong>{{ state.completionAccountingEffective ? 'действуют' : 'не действуют' }}</strong>
                  <span>Выдача реквизитов клиенту</span><strong>{{ state.liveRoutingEffective ? 'действует' : 'не действует' }}</strong>
                  <span>Запрос маршрутизации</span><strong>{{ state.routingRequested ? 'включён' : 'приостановлен' }}</strong>
                  <span>Дата нового учёта</span><strong>{{ state.attributionStartDate || '—' }}</strong>
                  <span>Версия настроек</span><strong>{{ state.revision }}</strong>
                </div>
                @if (state.runtimeWarnings.length) {
                  <div class="alert"><strong>Предупреждения сервера</strong>
                    @for (warning of state.runtimeWarnings; track warning) { <p>{{ warning }}</p> }
                  </div>
                }
              </section>

              @if (state.mode === 'LEGACY' && !state.systemEnabled) {
                <section class="system-card">
                  <h3>Запуск нового учёта</h3>
                  <p class="hint">Активация необратима. После неё начисления учитываются по выполненным работам, а реквизиты клиентов остаются на прежнем маршруте до отдельного включения.</p>
                  @if (state.activationBlockedReasons.length) {
                    <div class="alert"><strong>Причины блокировки</strong>
                      @for (reason of state.activationBlockedReasons; track reason) { <p>{{ reason }}</p> }
                    </div>
                  }
                  <label class="sheet-field"><span>Рабочая дата начала · Иркутск</span>
                    <input type="date" [value]="activationDate()" (input)="activationDate.set($any($event.target).value)" [disabled]="saving()"></label>
                  <label class="sheet-field"><span>Причина · обязательна</span>
                    <textarea maxlength="500" [value]="activationReason()" (input)="activationReason.set($any($event.target).value)" [disabled]="saving()"></textarea></label>
                  <label class="sheet-field"><span>Введите «{{ activationConfirmationText }}»</span>
                    <input type="text" autocomplete="off" spellcheck="false" [value]="activationConfirmation()"
                      (input)="activationConfirmation.set($any($event.target).value)" [disabled]="saving()"></label>
                  <button type="button" class="danger" (click)="activate()" [disabled]="saving() || !canActivate(state)">
                    Необратимо включить новый учёт
                  </button>
                </section>
              }

              @if (canChangeRouting(state)) {
                <section class="system-card">
                  <h3>Маршрутизация реквизитов</h3>
                  <p class="hint">Действие относится только к новым счетам. Запрос включения может остаться неэффективным, если серверные предохранители или подготовка начислений блокируют выдачу реквизитов.</p>
                  @if (routingTarget() === null) {
                    <button type="button" [class.danger]="state.routingRequested" (click)="startRouting(!state.routingRequested)" [disabled]="saving()">
                      {{ state.routingRequested ? 'Приостановить реквизиты' : 'Включить реквизиты' }}
                    </button>
                  } @else {
                    <p class="action-label">{{ routingTarget() ? 'Включение реквизитов' : 'Приостановка реквизитов' }}</p>
                    <label class="sheet-field"><span>Причина · обязательна</span>
                      <textarea maxlength="500" [value]="routingReason()" (input)="routingReason.set($any($event.target).value)" [disabled]="saving()"></textarea></label>
                    <label class="sheet-field"><span>Введите «{{ routingConfirmationText() }}»</span>
                      <input type="text" autocomplete="off" spellcheck="false" [value]="routingConfirmation()"
                        (input)="routingConfirmation.set($any($event.target).value)" [disabled]="saving()"></label>
                    <div class="actions">
                      <button type="button" class="secondary" (click)="routingTarget.set(null)" [disabled]="saving()">Отмена</button>
                      <button type="button" [class.danger]="!routingTarget()" (click)="updateRouting()" [disabled]="saving()">Сохранить режим</button>
                    </div>
                  }
                </section>
              }
            }
          </div>
          <footer class="sheet-actions"><button type="button" class="secondary" (click)="close()" [disabled]="saving()">Закрыть</button></footer>
        </div>
      </ng-template>
    </ion-modal>
  `,
  styles: [`
    .system-sheet{height:100%;min-height:0}.system-content{align-content:start;gap:.75rem}.system-sheet h2{margin:.1rem 0 0;font-size:1.05rem}
    .close-button{display:grid;place-items:center;width:2.5rem;height:2.5rem;border:0;border-radius:.7rem;color:var(--otziv-dark);background:var(--otziv-light)}
    .system-card{display:grid;gap:.6rem;border:1px solid rgba(103,116,131,.2);border-radius:.85rem;padding:.75rem;background:var(--otziv-white)}
    h3{margin:0;font-size:.89rem}.hint{margin:0;color:var(--otziv-info);font-size:.75rem;line-height:1.4}.action-label{margin:0;font-size:.76rem;font-weight:900}
    .status-grid{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:.45rem;font-size:.75rem}.status-grid span{color:var(--otziv-info)}.status-grid strong{text-align:right}
    .alert,.success{border-radius:.72rem;padding:.65rem;font-size:.75rem;line-height:1.4}.alert{color:#a52b43;background:#fff0f3}.success{color:#126e58;background:#e8f8f2}.alert p{margin:.3rem 0 0}
    .system-sheet button{min-height:44px;border:1px solid rgba(103,116,131,.22);border-radius:.72rem;padding:.5rem .7rem;background:var(--otziv-white);color:var(--otziv-dark);font:inherit;font-size:.77rem;font-weight:900}
    .system-sheet button.danger{color:#fff;background:#bb3652}.system-sheet button:disabled{opacity:.55}.system-sheet textarea{min-height:5rem;resize:vertical}.actions{display:flex;gap:.5rem}
  `]
})
export class ContractorSystemComponent implements OnInit {
  @Output() readonly dismissed = new EventEmitter<void>();
  readonly isOpen = signal(true);
  readonly status = signal<ContractorPaymentSystemStatus | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);
  readonly activationDate = signal(contractorBusinessDate());
  readonly activationReason = signal('');
  readonly activationConfirmation = signal('');
  readonly routingTarget = signal<boolean | null>(null);
  readonly routingReason = signal('');
  readonly routingConfirmation = signal('');
  readonly activationConfirmationText = ACTIVATION_CONFIRMATION;

  constructor(private readonly api: ContractorSystemApi, private readonly auth: AuthService,
              private readonly confirm: MobileConfirmService) {}

  ngOnInit(): void { void this.loadStatus(); }

  close(): void { if (!this.saving()) this.isOpen.set(false); }
  modeLabel(state: ContractorPaymentSystemStatus): string { return contractorSystemModeLabel(state.mode); }
  canActivate(state: ContractorPaymentSystemStatus): boolean { return canActivateContractorSystem(state, this.auth.hasRealmRole('OWNER')); }
  canChangeRouting(state: ContractorPaymentSystemStatus): boolean { return canChangeContractorRouting(state, this.auth.hasRealmRole('OWNER')); }
  routingConfirmationText(): string { return this.routingTarget() ? ROUTING_ENABLE_CONFIRMATION : ROUTING_PAUSE_CONFIRMATION; }

  async loadStatus(): Promise<void> {
    if (!this.auth.hasRealmRole('OWNER') || this.loading() || this.saving()) {
      if (!this.auth.hasRealmRole('OWNER')) this.error.set('Изменять общую систему может только владелец.');
      return;
    }
    this.loading.set(true); this.error.set(null);
    try { this.status.set(await firstValueFrom(this.api.status())); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось загрузить состояние системы.')); }
    finally { this.loading.set(false); }
  }

  async activate(): Promise<void> {
    const state = this.status();
    if (!this.canActivate(state!) || !state || this.saving()) return;
    const reason = contractorSystemReason(this.activationReason());
    if (this.activationDate() !== contractorBusinessDate()) {
      this.error.set('Дата начала должна совпадать с сегодняшней рабочей датой Иркутска.'); return;
    }
    if (!reason || this.activationConfirmation().trim() !== ACTIVATION_CONFIRMATION) {
      this.error.set('Укажите причину до 500 символов и точно введите фразу подтверждения.'); return;
    }
    const confirmed = await this.confirm.confirm({ title: 'Необратимая активация',
      message: 'Новый учёт начислений будет включён навсегда. Реквизиты для новых счетов включаются отдельно.',
      confirmText: 'Активировать', danger: true, confirmDelayMs: 1500 });
    if (!confirmed || this.saving()) return;
    this.saving.set(true); this.error.set(null); this.success.set(null);
    try {
      this.status.set(await firstValueFrom(this.api.activate({ attributionStartDate: this.activationDate(),
        confirmation: ACTIVATION_CONFIRMATION, reason, expectedRevision: state.revision })));
      this.activationConfirmation.set(''); this.activationReason.set('');
      this.success.set('Новый учёт активирован. Возврат к старому режиму невозможен.');
    } catch (error) { await this.mutationError(error, 'Активация не выполнена. Обновите состояние и проверьте причины блокировки.'); }
    finally { this.saving.set(false); }
  }

  startRouting(enabled: boolean): void {
    if (!this.canChangeRouting(this.status()!) || this.saving()) return;
    this.routingTarget.set(enabled); this.routingReason.set(''); this.routingConfirmation.set('');
    this.error.set(null); this.success.set(null);
  }

  async updateRouting(): Promise<void> {
    const state = this.status(); const enabled = this.routingTarget();
    if (!state || enabled === null || !this.canChangeRouting(state) || this.saving()) return;
    const reason = contractorSystemReason(this.routingReason());
    if (!reason || this.routingConfirmation().trim() !== this.routingConfirmationText()) {
      this.error.set('Укажите причину до 500 символов и точно введите фразу подтверждения.'); return;
    }
    const confirmed = await this.confirm.confirm({ title: enabled ? 'Включить реквизиты' : 'Приостановить реквизиты',
      message: enabled ? 'Реквизиты сотрудников смогут использоваться в новых счетах при готовности системы.'
        : 'Новые счета перестанут получать реквизиты сотрудников.',
      confirmText: enabled ? 'Включить' : 'Приостановить', danger: !enabled });
    if (!confirmed || this.saving()) return;
    this.saving.set(true); this.error.set(null); this.success.set(null);
    try {
      const updated = await firstValueFrom(this.api.routing({ enabled, confirmation: this.routingConfirmationText(),
        reason, expectedRevision: state.revision }));
      this.status.set(updated); this.routingTarget.set(null); this.routingReason.set(''); this.routingConfirmation.set('');
      this.success.set(enabled && !updated.liveRoutingEffective
        ? 'Запрос включения сохранён, но серверные предохранители пока не дают выдавать реквизиты.'
        : enabled ? 'Выдача реквизитов включена для новых счетов.' : 'Выдача реквизитов приостановлена.');
    } catch (error) { await this.mutationError(error, 'Не удалось изменить маршрутизацию. Обновите состояние и повторите.'); }
    finally { this.saving.set(false); }
  }

  private async mutationError(error: unknown, fallback: string): Promise<void> {
    const message = this.errorMessage(error, fallback);
    if (this.statusCode(error) === 409) {
      try { this.status.set(await firstValueFrom(this.api.status())); } catch { /* Preserve the conflict message. */ }
    }
    this.error.set(message);
  }

  private statusCode(error: unknown): number | null {
    return typeof error === 'object' && error !== null && 'status' in error && typeof error.status === 'number'
      ? error.status : null;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (typeof error === 'object' && error !== null && 'error' in error) {
      const body = error.error;
      if (typeof body === 'object' && body !== null && 'message' in body && typeof body.message === 'string') return body.message;
      if (typeof body === 'string' && body.length < 500) return body;
    }
    return fallback;
  }
}
