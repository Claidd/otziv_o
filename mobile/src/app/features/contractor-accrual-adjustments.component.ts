import { ChangeDetectionStrategy } from '@angular/core';
import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { IonModal } from '@ionic/angular';
import { firstValueFrom } from 'rxjs';
import {
  AdminUser, ApiService, ContractorAccrualAdjustment, ContractorAccrualAdjustmentKind,
  ContractorAccrualAdjustmentPreview, ContractorPaymentProfile
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import {
  ContractorAccrualDraft, contractorAccrualRequest, currentContractorAccrualMonth,
  newContractorAccrualDraft, newContractorAccrualOperationKey
} from '../shared/contractor-accrual-adjustment';
import { MobileConfirmService } from '../shared/mobile-confirm.service';

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-contractor-accrual-adjustments',
  imports: [IonModal],
  template: `
    <ion-modal
      class="sheet-modal"
      style="--height: min(92vh, 50rem); --max-width: 48rem"
      [isOpen]="isOpen()"
      [canDismiss]="!mutationBusy()"
      (didDismiss)="dismissed.emit()"
    >
      <ng-template>
        <div class="sheet-body sheet-form accrual-sheet">
          <header class="sheet-head">
            <div>
              <p class="sheet-note">Платёжные профили</p>
              <h2>Премии и удержания · {{ user.fio || user.username }}</h2>
            </div>
            <button type="button" class="close-button" (click)="close()" [disabled]="mutationBusy()" aria-label="Закрыть">
              <span class="material-icons-sharp">close</span>
            </button>
          </header>

          <div class="sheet-form-content accrual-content">
            <p class="guidance">
              Корректировка входит в начисление текущего месяца. Месяц основания можно указать прошлый,
              но закрытый расчёт не меняется задним числом. Причину увидит сотрудник; не пишите в ней реквизиты.
            </p>
            <details class="guide">
              <summary>Как провести премию или удержание</summary>
              <ol>
                <li>Выберите профиль специалиста или менеджера, вид, сумму и месяц основания. Причину увидит сотрудник.</li>
                <li>Нажмите «Проверить расчёт» и сверьте начисление, действующие резервы, платёжные задания и свободную сумму.</li>
                <li>Нажмите «Провести». Удержание, затрагивающее оплаченные суммы или обязательства, сервер не пропустит.</li>
                <li>Для исправления найдите запись в истории, укажите причину и подтвердите отмену. История сохраняется.</li>
              </ol>
              <p>Месяц основания может быть прошлым, но сумма учитывается в текущем месяце. ID документа необязателен и не должен содержать реквизиты.</p>
            </details>

            @if (loadError()) {
              <p class="alert" role="alert">{{ loadError() }}</p>
              <button type="button" class="secondary" (click)="loadProfiles()" [disabled]="profilesLoading()">Повторить загрузку</button>
            }
            @if (profilesLoading() && !profiles().length) {
              <p class="hint">Загружаю платёжные профили…</p>
            } @else if (!loadError() && !profiles().length) {
              <p class="hint">У сотрудника нет платёжного профиля специалиста или менеджера.</p>
            }

            @if (profiles().length) {
              <div class="profile-tabs" aria-label="Платёжный профиль">
                @for (profile of profiles(); track profile.id) {
                  <button
                    type="button"
                    [class.active]="profile.id === selectedProfileId()"
                    [disabled]="mutationBusy()"
                    (click)="selectProfile(profile.id)"
                  >{{ roleLabel(profile) }}</button>
                }
              </div>

              @if (activeProfile(); as profile) {
                <section class="profile-summary" aria-label="Начисления и резерв">
                  <div><span>За работы в этом месяце</span><strong>{{ money(profile.workAccruedMonthKopecks) }}</strong></div>
                  <div><span>Премии с отменами</span><strong>{{ money(profile.bonusMonthKopecks) }}</strong></div>
                  <div><span>Удержания с отменами</span><strong>{{ money(profile.deductionMonthKopecks) }}</strong></div>
                  <div class="emphasis"><span>Начислено за месяц</span><strong>{{ money(profile.accruedMonthKopecks) }}</strong></div>
                  <div><span>Зарезервировано</span><strong>{{ money(profile.reservedKopecks) }}</strong></div>
                  <div><span>Доступно</span><strong>{{ money(profile.availableKopecks) }}</strong></div>
                </section>
                @if (profile.exposureOverrunKopecks > 0) {
                  <p class="alert" role="alert">Требуется сверка: назначения превышают обязательство на {{ money(profile.exposureOverrunKopecks) }}.</p>
                }

                <section class="form-card">
                  <h3>Новая корректировка</h3>
                  <label class="sheet-field">
                    <span>Вид</span>
                    <select [value]="draft().kind" (change)="updateDraft('kind', $any($event.target).value)" [disabled]="mutationBusy()">
                      <option value="BONUS">Премия</option>
                      <option value="DEDUCTION">Удержание</option>
                    </select>
                  </label>
                  <label class="sheet-field">
                    <span>Сумма, ₽</span>
                    <input type="text" inputmode="decimal" placeholder="0,00" [value]="draft().amountRubles"
                      (input)="updateDraft('amountRubles', $any($event.target).value)" [disabled]="mutationBusy()">
                  </label>
                  <label class="sheet-field">
                    <span>Месяц основания</span>
                    <input type="month" [max]="currentMonth" [value]="draft().referenceMonth"
                      (change)="updateDraft('referenceMonth', $any($event.target).value)" [disabled]="mutationBusy()">
                  </label>
                  <label class="sheet-field">
                    <span>Причина · видна сотруднику</span>
                    <input type="text" maxlength="255" placeholder="Например, премия за помощь команде"
                      [value]="draft().reason" (input)="updateDraft('reason', $any($event.target).value)" [disabled]="mutationBusy()">
                  </label>
                  <label class="sheet-field">
                    <span>ID документа · только для админа и владельца</span>
                    <input type="text" maxlength="160" placeholder="Необязательно, без персональных данных"
                      [value]="draft().evidenceReference" (input)="updateDraft('evidenceReference', $any($event.target).value)"
                      [disabled]="mutationBusy()">
                  </label>
                  @if (actionError()) {
                    <p class="alert" role="alert">{{ actionError() }}</p>
                  }
                  @if (actionSuccess()) {
                    <p class="success" role="status">{{ actionSuccess() }}</p>
                  }
                  <div class="action-row">
                    <button type="button" class="secondary" (click)="previewAdjustment()"
                      [disabled]="previewLoading() || mutationBusy()">
                      {{ previewLoading() ? 'Рассчитываю…' : 'Проверить расчёт' }}
                    </button>
                    <button type="button" (click)="createAdjustment()"
                      [disabled]="!preview() || previewKey() !== draft().idempotencyKey || previewLoading() || mutationBusy()">
                      {{ saving() ? 'Провожу…' : 'Провести' }}
                    </button>
                  </div>
                </section>

                @if (preview(); as calculation) {
                  <section class="profile-summary preview" aria-label="Предварительный расчёт">
                    <h3>Проверка перед проведением</h3>
                    <div><span>Начислено за месяц</span><strong>{{ money(calculation.accruedMonthBeforeKopecks) }} → {{ money(calculation.accruedMonthAfterKopecks) }}</strong></div>
                    <div><span>Уже оплачено всего</span><strong>{{ money(calculation.paidTotalKopecks) }}</strong></div>
                    <div><span>Занято действующими счетами</span><strong>{{ money(calculation.outstandingKopecks) }}</strong></div>
                    <div><span>Обязательства по заданиям</span><strong>{{ money(calculation.manualTaskCommitmentKopecks) }}</strong></div>
                    <div class="emphasis"><span>Свободно для новых оплат</span><strong>{{ money(calculation.freeForNewPaymentsBeforeKopecks) }} → {{ money(calculation.freeForNewPaymentsAfterKopecks) }}</strong></div>
                  </section>
                }

                <section class="history-card">
                  <div class="history-title">
                    <h3>История корректировок</h3>
                    <button type="button" class="secondary" (click)="refresh()" [disabled]="mutationBusy() || historyLoading() || profilesLoading()">Обновить</button>
                  </div>
                  @if (historyError()) {
                    <p class="alert" role="alert">{{ historyError() }}</p>
                  }
                  @if (historyLoading() && !historyLoaded()) {
                    <p class="hint">Загружаю историю…</p>
                  } @else if (historyLoaded() && !history().length) {
                    <p class="hint">Премий и удержаний пока не было.</p>
                  }
                  @for (entry of history(); track entry.id) {
                    <article class="history-entry">
                      <strong>{{ kindLabel(entry) }} · {{ money(entry.signedAmountKopecks) }}</strong>
                      <small>Учтено {{ entry.effectiveOn }} · за {{ entry.referenceMonth }} · {{ entry.createdBy }}</small>
                      <p>{{ entry.reason }}</p>
                      @if (entry.evidenceReference) { <small>Документ: {{ entry.evidenceReference }}</small> }
                      @if (entry.reversalOfId) { <small>Отмена записи №{{ entry.reversalOfId }}</small> }
                      @if (canReverse(entry)) {
                        <details>
                          <summary>Отменить запись</summary>
                          <label class="sheet-field">
                            <span>Причина отмены · видна сотруднику</span>
                            <input type="text" maxlength="255" placeholder="Обязательно"
                              [value]="reversalReasons()[entry.id] || ''"
                              (input)="updateReversalReason(entry.id, $any($event.target).value)" [disabled]="mutationBusy()">
                          </label>
                          <button type="button" class="secondary" (click)="reverseAdjustment(entry)" [disabled]="mutationBusy()">
                            {{ reversingId() === entry.id ? 'Отменяю…' : 'Подтвердить отмену' }}
                          </button>
                        </details>
                      }
                    </article>
                  }
                  @if (nextHistoryPage() !== null) {
                    <button type="button" class="secondary more" (click)="loadMoreHistory()" [disabled]="historyLoading() || mutationBusy()">
                      {{ historyLoading() ? 'Загружаю…' : 'Показать ещё' }}
                    </button>
                  }
                </section>
              }
            }
          </div>
          <footer class="sheet-actions"><button type="button" class="secondary" (click)="close()" [disabled]="mutationBusy()">Закрыть</button></footer>
        </div>
      </ng-template>
    </ion-modal>
  `,
  styles: [`
    .accrual-sheet { height: 100%; min-height: 0; gap: .65rem; }
    .accrual-sheet h2 { margin: .1rem 0 0; font-size: 1.05rem; line-height: 1.2; }
    .accrual-content { align-content: start; gap: .8rem; padding-right: .18rem; }
    .guidance, .hint { margin: 0; color: var(--otziv-info); font-size: .76rem; line-height: 1.4; }
    .guide { border:1px solid rgba(103,116,131,.16); border-radius:.75rem; padding:.65rem; background:var(--otziv-white); color:var(--otziv-dark); font-size:.76rem; line-height:1.4; }
    .guide summary { cursor:pointer; color:var(--otziv-primary); font-weight:900; min-height:28px; }
    .guide ol { margin:.55rem 0; padding-left:1.3rem; }.guide li+li { margin-top:.35rem; }.guide p { margin:.5rem 0 0; color:var(--otziv-info); }
    .close-button { display:grid; place-items:center; width:2.5rem; height:2.5rem; border:0; border-radius:.7rem; color:var(--otziv-dark); background:var(--otziv-light); }
    .profile-tabs, .action-row, .history-title { display:flex; gap:.5rem; align-items:center; }
    .profile-tabs { flex-wrap:wrap; }
    .profile-tabs button, .action-row button, .history-card button, .sheet-actions button { min-height:44px; border:1px solid rgba(103,116,131,.2); border-radius:.75rem; padding:.5rem .75rem; color:var(--otziv-dark); background:var(--otziv-white); font:inherit; font-size:.78rem; font-weight:900; }
    .profile-tabs button.active, .action-row button:not(.secondary) { color:#fff; background:var(--otziv-primary); }
    button:disabled { opacity:.55; }
    .profile-summary, .form-card, .history-card { display:grid; gap:.6rem; border:1px solid rgba(103,116,131,.16); border-radius:.85rem; padding:.75rem; background:var(--otziv-white); }
    .profile-summary div { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:.4rem; align-items:start; font-size:.75rem; }
    .profile-summary span { color:var(--otziv-info); }
    .profile-summary strong { text-align:right; overflow-wrap:anywhere; }
    .profile-summary .emphasis { border-top:1px solid rgba(103,116,131,.16); padding-top:.45rem; }
    h3 { margin:0; font-size:.88rem; }
    .action-row button { flex:1 1 0; }
    .alert, .success { margin:0; border-radius:.7rem; padding:.6rem; font-size:.74rem; line-height:1.35; }
    .alert { color:#a52b43; background:#fff0f3; }.success { color:#126e58; background:#e8f8f2; }
    .history-title { justify-content:space-between; }.history-title button { flex:0 0 auto; }
    .history-entry { display:grid; gap:.3rem; border-top:1px solid rgba(103,116,131,.16); padding-top:.7rem; font-size:.76rem; }
    .history-entry small { color:var(--otziv-info); line-height:1.3; }.history-entry p { margin:0; }
    .history-entry details { margin-top:.35rem; }.history-entry details[open] { display:grid; gap:.5rem; }
    .history-entry summary { cursor:pointer; color:var(--otziv-primary); font-weight:900; }
    .history-entry .sheet-field { margin-top:.5rem; }.history-entry button { margin-top:.5rem; }
    .more { width:100%; }
  `]
})
export class ContractorAccrualAdjustmentsComponent implements OnInit {
  @Input({ required: true }) user!: Pick<AdminUser, 'id' | 'fio' | 'username'>;
  @Input() initialKind: ContractorAccrualAdjustmentKind = 'BONUS';
  @Input() initialProfileId: number | null = null;
  @Input() onlyEnabledProfiles = false;
  @Output() readonly dismissed = new EventEmitter<void>();
  @Output() readonly adjustmentSaved = new EventEmitter<void>();

  readonly isOpen = signal(true);
  readonly currentMonth = currentContractorAccrualMonth();
  readonly profiles = signal<ContractorPaymentProfile[]>([]);
  readonly profilesLoading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly selectedProfileId = signal<number | null>(null);
  readonly activeProfile = computed(() => this.profiles().find((profile) => profile.id === this.selectedProfileId()) ?? null);
  readonly draft = signal<ContractorAccrualDraft>(newContractorAccrualDraft());
  readonly preview = signal<ContractorAccrualAdjustmentPreview | null>(null);
  readonly previewKey = signal<string | null>(null);
  readonly previewLoading = signal(false);
  readonly saving = signal(false);
  readonly reversalConfirming = signal(false);
  readonly reversingId = signal<number | null>(null);
  readonly mutationBusy = computed(() => this.saving() || this.reversalConfirming() || this.reversingId() !== null);
  readonly actionError = signal<string | null>(null);
  readonly actionSuccess = signal<string | null>(null);
  readonly history = signal<ContractorAccrualAdjustment[]>([]);
  readonly historyLoaded = signal(false);
  readonly historyLoading = signal(false);
  readonly historyError = signal<string | null>(null);
  readonly nextHistoryPage = signal<number | null>(null);
  readonly reversalReasons = signal<Record<number, string>>({});
  readonly reversalKeys = signal<Record<number, string>>({});

  private profilesRequestEpoch = 0;
  private previewRequestEpoch = 0;
  private historyRequestEpoch = 0;

  constructor(
    private readonly api: ApiService,
    private readonly auth: AuthService,
    private readonly confirm: MobileConfirmService
  ) {}

  ngOnInit(): void {
    this.draft.set({ ...newContractorAccrualDraft(), kind: this.initialKind });
    void this.loadProfiles();
  }

  close(): void {
    if (!this.mutationBusy()) {
      this.isOpen.set(false);
    }
  }

  async loadProfiles(): Promise<void> {
    if (!this.auth.hasAnyRealmRole(['ADMIN', 'OWNER'])) {
      this.loadError.set('Для управления начислениями нужен доступ администратора или владельца.');
      return;
    }
    const requestEpoch = ++this.profilesRequestEpoch;
    this.profilesLoading.set(true);
    this.loadError.set(null);
    try {
      const profiles = await firstValueFrom(this.api.getAdminContractorPaymentProfiles(this.user.id));
      if (requestEpoch !== this.profilesRequestEpoch || !this.isOpen()) return;
      const visibleProfiles = this.onlyEnabledProfiles ? profiles.filter((profile) => profile.enabled) : profiles;
      this.profiles.set(visibleProfiles);
      if (!visibleProfiles.some((profile) => profile.id === this.selectedProfileId())) {
        const preferred = visibleProfiles.find((profile) => profile.id === this.initialProfileId && profile.enabled);
        this.selectProfile(preferred?.id ?? visibleProfiles[0]?.id ?? null);
      }
    } catch (error) {
      if (requestEpoch === this.profilesRequestEpoch && this.isOpen()) {
        this.loadError.set(this.errorMessage(error, 'Не удалось загрузить платёжные профили.'));
      }
    } finally {
      if (requestEpoch === this.profilesRequestEpoch) this.profilesLoading.set(false);
    }
  }

  selectProfile(profileId: number | null): void {
    if (this.mutationBusy() || profileId === this.selectedProfileId()) return;
    this.selectedProfileId.set(profileId);
    this.draft.set({ ...newContractorAccrualDraft(), kind: this.draft().kind });
    this.clearPreview();
    this.actionError.set(null);
    this.actionSuccess.set(null);
    this.history.set([]);
    this.historyLoaded.set(false);
    this.nextHistoryPage.set(null);
    this.historyError.set(null);
    this.historyLoading.set(false);
    this.reversalReasons.set({});
    this.reversalKeys.set({});
    this.historyRequestEpoch += 1;
    if (profileId !== null) void this.loadHistory(0);
  }

  updateDraft(field: 'kind' | 'amountRubles' | 'reason' | 'referenceMonth' | 'evidenceReference', value: string): void {
    if (this.mutationBusy()) return;
    this.draft.update((current) => ({ ...current, [field]: value, idempotencyKey: newContractorAccrualOperationKey() } as ContractorAccrualDraft));
    this.clearPreview();
    this.actionError.set(null);
    this.actionSuccess.set(null);
  }

  async previewAdjustment(): Promise<void> {
    const profileId = this.selectedProfileId();
    if (profileId === null || this.previewLoading() || this.mutationBusy()) return;
    const validated = contractorAccrualRequest(this.draft(), this.currentMonth);
    if (!validated.request) {
      this.actionError.set(validated.error);
      return;
    }
    this.clearPreview();
    const key = validated.request.idempotencyKey;
    const epoch = ++this.previewRequestEpoch;
    this.previewLoading.set(true);
    this.actionError.set(null);
    try {
      const result = await firstValueFrom(this.api.previewAdminContractorAccrualAdjustment(this.user.id, profileId, validated.request));
      if (epoch === this.previewRequestEpoch && this.selectedProfileId() === profileId && this.draft().idempotencyKey === key) {
        this.preview.set(result);
        this.previewKey.set(key);
      }
    } catch (error) {
      if (epoch === this.previewRequestEpoch && this.selectedProfileId() === profileId && this.draft().idempotencyKey === key) {
        this.actionError.set(this.errorMessage(error, 'Не удалось проверить расчёт.'));
      }
    } finally {
      if (epoch === this.previewRequestEpoch) this.previewLoading.set(false);
    }
  }

  async createAdjustment(): Promise<void> {
    const profileId = this.selectedProfileId();
    if (profileId === null || this.mutationBusy() || this.previewLoading()) return;
    const validated = contractorAccrualRequest(this.draft(), this.currentMonth);
    if (!validated.request) {
      this.actionError.set(validated.error);
      return;
    }
    if (!this.preview() || this.previewKey() !== validated.request.idempotencyKey) {
      this.actionError.set('Сначала проверьте расчёт до и после корректировки.');
      return;
    }
    this.saving.set(true);
    this.actionError.set(null);
    try {
      const saved = await firstValueFrom(this.api.createAdminContractorAccrualAdjustment(this.user.id, profileId, validated.request));
      this.draft.set({ ...newContractorAccrualDraft(), kind: this.draft().kind });
      this.clearPreview();
      this.actionSuccess.set(saved.kind === 'BONUS' ? 'Премия проведена.' : 'Удержание проведено.');
      this.adjustmentSaved.emit();
      await Promise.all([this.loadProfiles(), this.loadHistory(0)]);
    } catch (error) {
      this.actionError.set(this.errorMessage(error, 'Не удалось провести корректировку. Повторите действие с тем же расчётом.'));
      if (this.status(error) === 409) {
        this.clearPreview();
        void this.loadProfiles();
      }
    } finally {
      this.saving.set(false);
    }
  }

  async loadHistory(page: number): Promise<void> {
    const profileId = this.selectedProfileId();
    if (profileId === null || (page !== 0 && this.historyLoading())) return;
    const epoch = ++this.historyRequestEpoch;
    this.historyLoading.set(true);
    this.historyError.set(null);
    try {
      const result = await firstValueFrom(this.api.getAdminContractorAccrualAdjustmentsPage(this.user.id, profileId, page));
      if (epoch !== this.historyRequestEpoch || profileId !== this.selectedProfileId()) return;
      const merged = page === 0 ? result.items : [...this.history(), ...result.items];
      this.history.set([...new Map(merged.map((entry) => [entry.id, entry])).values()]);
      this.nextHistoryPage.set(result.hasMore ? page + 1 : null);
      this.historyLoaded.set(true);
    } catch (error) {
      if (epoch === this.historyRequestEpoch && profileId === this.selectedProfileId()) {
        this.historyError.set(this.errorMessage(error, 'Не удалось загрузить историю корректировок.'));
      }
    } finally {
      if (epoch === this.historyRequestEpoch) this.historyLoading.set(false);
    }
  }

  loadMoreHistory(): void {
    const page = this.nextHistoryPage();
    if (page !== null && !this.mutationBusy()) void this.loadHistory(page);
  }

  async refresh(): Promise<void> {
    if (this.mutationBusy()) return;
    this.clearPreview();
    await Promise.all([this.loadProfiles(), this.loadHistory(0)]);
  }

  updateReversalReason(adjustmentId: number, reason: string): void {
    if (this.mutationBusy()) return;
    this.reversalReasons.update((values) => ({ ...values, [adjustmentId]: reason }));
    this.reversalKeys.update((values) => ({ ...values, [adjustmentId]: newContractorAccrualOperationKey() }));
    this.actionError.set(null);
  }

  canReverse(entry: ContractorAccrualAdjustment): boolean {
    return entry.kind !== 'REVERSAL' && !this.history().some((item) => item.reversalOfId === entry.id);
  }

  async reverseAdjustment(entry: ContractorAccrualAdjustment): Promise<void> {
    const profileId = this.selectedProfileId();
    if (profileId === null || !this.canReverse(entry) || this.mutationBusy()) return;
    const reason = (this.reversalReasons()[entry.id] ?? '').trim();
    if (!reason || reason.length > 255) {
      this.actionError.set('Укажите причину отмены длиной до 255 символов; её увидит сотрудник.');
      return;
    }
    this.reversalConfirming.set(true);
    let confirmed = false;
    try {
      confirmed = await this.confirm.confirm({
        title: 'Отменить корректировку',
        message: `Отменить запись №${entry.id} на ${this.money(entry.signedAmountKopecks)}? Отмена появится отдельной записью в истории.`,
        confirmText: 'Отменить запись', danger: true
      });
    } catch (error) {
      this.actionError.set(this.errorMessage(error, 'Не удалось открыть подтверждение отмены.'));
      return;
    } finally {
      this.reversalConfirming.set(false);
    }
    if (!confirmed || profileId !== this.selectedProfileId() || this.mutationBusy()) return;
    const key = this.reversalKeys()[entry.id] ?? newContractorAccrualOperationKey();
    this.reversalKeys.update((values) => ({ ...values, [entry.id]: key }));
    this.reversingId.set(entry.id);
    this.actionError.set(null);
    try {
      await firstValueFrom(this.api.reverseAdminContractorAccrualAdjustment(this.user.id, profileId, entry.id, {
        reason, idempotencyKey: key
      }));
      this.reversalReasons.update((values) => { const next = { ...values }; delete next[entry.id]; return next; });
      this.reversalKeys.update((values) => { const next = { ...values }; delete next[entry.id]; return next; });
      this.actionSuccess.set('Корректировка отменена.');
      this.clearPreview();
      await Promise.all([this.loadProfiles(), this.loadHistory(0)]);
    } catch (error) {
      this.actionError.set(this.errorMessage(error, 'Не удалось отменить корректировку.'));
      if (this.status(error) === 409) {
        void this.loadProfiles();
        void this.loadHistory(0);
      }
    } finally {
      this.reversingId.set(null);
    }
  }

  roleLabel(profile: ContractorPaymentProfile): string {
    return profile.role === 'MANAGER' ? 'Менеджер' : 'Специалист';
  }

  kindLabel(entry: ContractorAccrualAdjustment): string {
    return entry.kind === 'BONUS' ? 'Премия' : entry.kind === 'DEDUCTION' ? 'Удержание' : 'Отмена';
  }

  money(kopecks: number): string {
    return `${(kopecks / 100).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ₽`;
  }

  private clearPreview(): void {
    this.previewRequestEpoch += 1;
    this.preview.set(null);
    this.previewKey.set(null);
    this.previewLoading.set(false);
  }

  private status(error: unknown): number | null {
    return error && typeof error === 'object' && 'status' in error ? Number(error.status) : null;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object' && 'error' in error) {
      const body = (error as { error?: { detail?: string; message?: string; error?: string } | string }).error;
      if (typeof body === 'string' && body.trim()) return body;
      if (body && typeof body === 'object') return body.detail || body.message || body.error || fallback;
    }
    if (this.status(error) === 403) return 'У вас нет доступа к платёжным профилям.';
    if (this.status(error) === 409) return 'Данные изменились. Обновите расчёт и повторите действие.';
    return error instanceof Error ? error.message : fallback;
  }
}
