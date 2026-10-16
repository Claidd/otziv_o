import { Component, EventEmitter, Input, OnInit, Output, computed, signal } from '@angular/core';
import { IonModal } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  AdminUser, ApiService, ContractorAllocationJournalItem, ContractorAllocationJournalPage,
  ContractorDirectSettlement, ContractorOpeningBalanceChange, ContractorPaymentMode,
  ContractorPaymentProfile
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { newContractorAccrualOperationKey } from '../shared/contractor-accrual-adjustment';
import {
  ContractorProfileDraft, ContractorSettlementDraft, contractorProfileDraft, contractorProfileRequest,
  contractorSettlementRequest, newContractorSettlementDraft, parseKopecks
} from '../shared/contractor-finance';
import { MobileConfirmService } from '../shared/mobile-confirm.service';

type FinanceSection = 'profile' | 'settlements' | 'journal';

@Component({
  selector: 'app-contractor-finance',
  imports: [IonModal],
  template: `
    <ion-modal class="sheet-modal" style="--height: min(94vh, 54rem); --max-width: 50rem"
      [isOpen]="isOpen()" [canDismiss]="!mutationBusy() && !profileDirty()" (didDismiss)="dismissed.emit()">
      <ng-template>
        <div class="sheet-body sheet-form finance-sheet">
          <header class="sheet-head">
            <div><p class="sheet-note">Расчёты с сотрудником</p><h2>{{ user.fio || user.username }}</h2></div>
            <button type="button" class="close-button" (click)="close()" [disabled]="mutationBusy()" aria-label="Закрыть">
              <span class="material-icons-sharp">close</span>
            </button>
          </header>
          <div class="sheet-form-content finance-content">
            <nav class="section-tabs" aria-label="Раздел расчётов">
              <button type="button" [class.active]="section() === 'profile'" (click)="selectSection('profile')">Профиль</button>
              <button type="button" [class.active]="section() === 'settlements'" (click)="selectSection('settlements')">Переводы</button>
              <button type="button" [class.active]="section() === 'journal'" (click)="selectSection('journal')">Журнал</button>
            </nav>
            @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
            @if (success()) { <p class="success" role="status">{{ success() }}</p> }

            @if (section() !== 'journal') {
              @if (profilesLoading() && !profiles().length) {
                <p class="hint">Загружаю платёжные профили…</p>
              } @else if (!profiles().length) {
                <p class="hint">У сотрудника нет платёжных профилей.</p>
              }
              <div class="profile-tabs" aria-label="Роль платёжного профиля">
                @for (profile of profiles(); track profile.id) {
                  <button type="button" [class.active]="selectedProfileId() === profile.id"
                    (click)="selectProfile(profile.id)" [disabled]="mutationBusy()">
                    {{ profile.role === 'MANAGER' ? 'Менеджер' : 'Специалист' }}
                  </button>
                }
              </div>
            }

            @if (section() === 'profile' && activeProfile(); as profile) {
              <section class="finance-card mode-card">
                <h3>Участие в расчёте и новых счетах</h3>
                <p class="hint">Персональный допуск влияет только на новые счета. Общая выдача реквизитов включается отдельно владельцем.</p>
                <p class="mode-state">{{ profile.liveRouting ? 'Боевой маршрут активен' : profile.reportingLive ? 'Фактический учёт, новые маршруты остановлены' : profile.shadowMode ? 'Тестовый расчёт' : 'Маршрутизация отключена' }}</p>
                <label class="toggle-row">
                  <span>Участвует в тестовом расчёте</span>
                  <input type="checkbox" [checked]="profileDraft()?.enabled" (change)="setProfileField('enabled', $any($event.target).checked)" [disabled]="mutationBusy()">
                </label>
                <label class="toggle-row">
                  <span>Использовать реквизиты в новых счетах</span>
                  <input type="checkbox" [checked]="profileDraft()?.liveEnabled" (change)="setProfileField('liveEnabled', $any($event.target).checked)"
                    [disabled]="mutationBusy() || (!profileDraft()?.enabled && !profileDraft()?.liveEnabled)">
                </label>
                <p class="hint">Если допуск специалиста выключен, система проверит менеджера заказа; затем при необходимости выберет владельца.</p>
              </section>

              <section class="finance-card">
                <h3>Реквизиты получателя</h3>
                <label class="sheet-field"><span>ФИО получателя</span>
                  <input type="text" [value]="profileDraft()?.recipientName || ''" (input)="setProfileField('recipientName', $any($event.target).value)" [disabled]="mutationBusy()">
                </label>
                <label class="sheet-field"><span>Телефон или номер карты</span>
                  <input type="text" inputmode="tel" placeholder="+7 999 123-45-67 или 2202 2082 3839 6676"
                    [value]="profileDraft()?.paymentPhone || ''" (input)="setProfileField('paymentPhone', $any($event.target).value)" [disabled]="mutationBusy()">
                </label>
                <p class="hint">Телефон: 10–15 цифр. Карта: 16–19 цифр. Пробелы, дефисы и скобки допустимы.</p>
                <label class="sheet-field"><span>Банк</span>
                  <input type="text" [value]="profileDraft()?.bankName || ''" (input)="setProfileField('bankName', $any($event.target).value)" [disabled]="mutationBusy()">
                </label>
                <label class="sheet-field"><span>Комментарий к переводу</span>
                  <input type="text" [value]="profileDraft()?.paymentComment || ''" (input)="setProfileField('paymentComment', $any($event.target).value)" [disabled]="mutationBusy()">
                </label>
                <p class="hint">Не указывайте в комментарии персональные данные или реквизиты.</p>
                <label class="sheet-field"><span>Переходящий неоплаченный остаток, ₽</span>
                  <input type="text" inputmode="decimal" [value]="profileDraft()?.openingBalanceRubles || ''"
                    (input)="setProfileField('openingBalanceRubles', $any($event.target).value)" [disabled]="mutationBusy()">
                </label>
                @if (openingBalanceChanged(profile)) {
                  <label class="sheet-field"><span>Причина изменения остатка · обязательна</span>
                    <input type="text" maxlength="255" placeholder="Источник расчёта, дата среза и причина"
                      [value]="profileDraft()?.openingBalanceReason || ''" (input)="setProfileField('openingBalanceReason', $any($event.target).value)" [disabled]="mutationBusy()">
                  </label>
                }
                <button type="button" class="primary" (click)="saveProfile()" [disabled]="!profileDirty() || mutationBusy()">
                  {{ profileSaving() ? 'Сохраняю…' : 'Сохранить платёжный профиль' }}
                </button>
              </section>

              <section class="finance-card metrics">
                <h3>Баланс и резервы</h3>
                <div><span>Начислено за месяц</span><strong>{{ money(profile.accruedMonthKopecks) }}</strong></div>
                <div><span>Зарезервировано</span><strong>{{ money(profile.reservedKopecks) }}</strong></div>
                <div><span>Клиент сообщил об оплате</span><strong>{{ money(profile.clientReportedKopecks) }}</strong></div>
                <div><span>Подтверждено за месяц</span><strong>{{ money(profile.grossConfirmedMonthKopecks) }}</strong></div>
                <div><span>Возвращено за месяц</span><strong>{{ money(profile.returnedMonthKopecks) }}</strong></div>
                <div><span>Доступно для новых оплат</span><strong>{{ money(profile.availableKopecks) }}</strong></div>
              </section>
              @if (profile.exposureOverrunKopecks > 0) {
                <p class="alert">Требуется ручная сверка: назначения превышают обязательство на {{ money(profile.exposureOverrunKopecks) }}.</p>
              }
              <details class="finance-card" (toggle)="loadOpeningHistory($any($event.target).open)">
                <summary>История переходящего остатка</summary>
                @if (openingLoading()) { <p class="hint">Загружаю историю…</p> }
                @for (change of openingHistory(); track change.id) {
                  <article class="history-entry"><strong>{{ money(change.oldBalanceKopecks) }} → {{ money(change.newBalanceKopecks) }}</strong>
                    <small>{{ change.effectiveAt }} · {{ change.changedBy }}</small><p>{{ change.reason }}</p></article>
                }
                @if (openingLoaded() && !openingHistory().length) { <p class="hint">Изменений пока не было.</p> }
              </details>
            }

            @if (section() === 'settlements' && activeProfile(); as profile) {
              <section class="finance-card">
                <h3>{{ profile.reportingLive ? 'Фактический перевод напрямую' : 'Тестовый перевод' }}</h3>
                <p class="hint">{{ profile.reportingLive ? 'Учитывайте перевод только после проверки выписки.' : 'Запись только моделирует поступление; реального перевода нет.' }} ID документа — внутренний номер, без ФИО, карты и телефона.</p>
                <label class="sheet-field"><span>Сумма, ₽</span><input type="text" inputmode="decimal" [value]="settlementDraft().amountRubles"
                  (input)="setSettlementField('amountRubles', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                <label class="sheet-field"><span>Дата и время перевода</span><input type="datetime-local" [value]="settlementDraft().effectiveAt"
                  (input)="setSettlementField('effectiveAt', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                <label class="sheet-field"><span>Основание</span><input type="text" maxlength="255" [value]="settlementDraft().reason"
                  (input)="setSettlementField('reason', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                <label class="sheet-field"><span>ID подтверждающего документа</span><input type="text" maxlength="160" [value]="settlementDraft().evidenceReference"
                  (input)="setSettlementField('evidenceReference', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                <button type="button" class="primary" (click)="createSettlement()" [disabled]="mutationBusy()">
                  {{ settlementSaving() ? 'Учитываю…' : profile.reportingLive ? 'Учесть фактический перевод' : 'Смоделировать перевод' }}
                </button>
              </section>
              <section class="finance-card">
                <div class="section-head"><h3>История прямых переводов</h3>
                  <button type="button" class="secondary" (click)="loadSettlements()" [disabled]="settlementsLoading() || mutationBusy()">Обновить</button></div>
                @if (settlementsLoading() && !settlementsLoaded()) { <p class="hint">Загружаю переводы…</p> }
                @if (settlementsLoaded() && !settlements().length) { <p class="hint">Прямых переводов пока не было.</p> }
                @for (item of settlements(); track item.id) {
                  <article class="history-entry">
                    <strong>{{ item.type === 'PAYMENT' ? 'Перевод' : 'Корректировка' }} · {{ money(item.amountKopecks) }}</strong>
                    <small>{{ item.effectiveAt }} · {{ item.simulated ? 'тестовый учёт' : 'фактический учёт' }} · {{ item.actor }}</small>
                    <small>Документ {{ item.evidenceReference }}</small><p>{{ item.reason }}</p>
                    @if (item.type === 'PAYMENT' && remainingSettlement(item) > 0) {
                      <details><summary>Учесть корректировку · доступно {{ money(remainingSettlement(item)) }}</summary>
                        <label class="sheet-field"><span>Сумма возврата, ₽</span><input type="text" inputmode="decimal" [value]="reversalDraft(item.id).amountRubles"
                          (input)="setReversalField(item.id, 'amountRubles', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                        <label class="sheet-field"><span>Дата и время</span><input type="datetime-local" [value]="reversalDraft(item.id).effectiveAt"
                          (input)="setReversalField(item.id, 'effectiveAt', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                        <label class="sheet-field"><span>Основание</span><input type="text" maxlength="255" [value]="reversalDraft(item.id).reason"
                          (input)="setReversalField(item.id, 'reason', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                        <label class="sheet-field"><span>ID документа</span><input type="text" maxlength="160" [value]="reversalDraft(item.id).evidenceReference"
                          (input)="setReversalField(item.id, 'evidenceReference', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                        <button type="button" class="secondary" (click)="reverseSettlement(item)" [disabled]="mutationBusy()">Учесть корректировку</button>
                      </details>
                    }
                  </article>
                }
              </section>
            }

            @if (section() === 'journal') {
              <section class="finance-card">
                <h3>Распределение, резервы и возвраты</h3>
                <p class="hint">История решений по выбранному сотруднику. Общие счета и прямые выплаты показаны вместе с оплатами заказов.</p>
                <div class="journal-filters">
                  <label class="sheet-field"><span>Статус</span><select [value]="journalStatus()" (change)="changeJournalStatus($any($event.target).value)">
                    <option value="">Все</option><option value="RESERVED">Зарезервировано</option>
                    <option value="CLIENT_REPORTED">Клиент сообщил</option><option value="PARTIALLY_CONFIRMED">Частично подтверждено</option>
                    <option value="CONFIRMED">Подтверждено</option><option value="SIMULATED_PAID">Тестовая оплата</option>
                    <option value="RETURN_AMOUNT_PENDING">Возврат уточняется</option><option value="PARTIALLY_RETURNED">Частично возвращено</option>
                    <option value="RETURNED">Возвращено</option><option value="RELEASED_UNPAID">Резерв освобождён</option>
                    <option value="OWNER_FALLBACK">Владелец</option><option value="EXPIRED">Истекло</option>
                    <option value="CANCELED">Отменено</option>
                  </select></label>
                  <label class="sheet-field"><span>Режим</span><select [value]="journalMode()" (change)="changeJournalMode($any($event.target).value)">
                    <option value="">Оба</option><option value="SHADOW">Тестовый</option><option value="LIVE">Боевой</option>
                  </select></label>
                  <label class="sheet-field"><span>Источник</span><select [value]="journalSourceType()" (change)="changeJournalSourceType($any($event.target).value)">
                    <option value="">Все</option><option value="PAYMENT_LINK">Ссылка</option>
                    <option value="COMMON_INVOICE">Общий счёт</option><option value="DIRECT_SETTLEMENT">Прямой перевод</option>
                    <option value="ACTUAL_PAYMENT">Поступление</option>
                  </select></label>
                  <label class="sheet-field"><span>ID источника</span><input type="number" min="1" step="1" placeholder="Любой"
                    [value]="journalSourceIdDraft()" (input)="journalSourceIdDraft.set($any($event.target).value)"
                    (change)="applyJournalSourceId()"></label>
                </div>
                <button type="button" class="secondary" (click)="applyJournalSourceId()" [disabled]="journalLoading() || mutationBusy()">Применить ID</button>
                <button type="button" class="secondary" (click)="loadJournal(journalPage())" [disabled]="journalLoading() || mutationBusy()">Обновить журнал</button>
              </section>
              @if (journalLoading() && !journal()) { <p class="hint">Загружаю журнал…</p> }
              @if (journal()?.content?.length === 0) { <p class="hint">Записей по выбранным условиям нет.</p> }
              @for (item of journal()?.content || []; track item.id) {
                <article class="finance-card journal-entry">
                  <div class="section-head"><strong>{{ sourceLabel(item) }} №{{ item.sourceId }}</strong><span>{{ statusLabel(item.status) }}</span></div>
                  <small>Попытка {{ item.attemptNo }} · {{ item.mode === 'SHADOW' ? 'тест' : 'боевой режим' }} · {{ item.createdAt }}</small>
                  <div class="metrics"><div><span>Сумма счёта</span><strong>{{ money(item.amountKopecks) }}</strong></div>
                    <div><span>Подтверждено</span><strong>{{ money(item.confirmedKopecks) }}</strong></div>
                    <div><span>Возвращено</span><strong>{{ money(item.returnedKopecks) }}</strong></div></div>
                  <small>Получатель: {{ item.recipientName || (item.recipientType === 'OWNER' ? 'владелец' : item.recipientType === 'MANAGER' ? 'менеджер' : 'специалист') }}</small>
                  @if (item.orderId) { <small>Заказ №{{ item.orderId }}</small> }
                  @if (item.commonInvoiceId) { <small>Общий счёт №{{ item.commonInvoiceId }}</small> }
                  @if (item.routingDecisionReason) { <small>Решение маршрута: {{ item.routingDecisionReason }}</small> }
                  @if (item.specialistRejectionReason) { <small>Проверка специалиста: {{ item.specialistRejectionReason }}</small> }
                  @if (item.managerRejectionReason) { <small>Проверка менеджера: {{ item.managerRejectionReason }}</small> }
                  <small>Резерв: {{ item.reservedAt || '—' }} · Подтверждение: {{ item.confirmedAt || '—' }} · Освобождение: {{ item.releasedAt || '—' }}</small>
                  @if (item.reason) { <p>{{ item.reason }}</p> }
                  @if (canRecordReturn(item)) {
                    <details [open]="item.status === 'RETURN_AMOUNT_PENDING'"><summary>Указать итоговую сумму возврата</summary>
                      <p class="hint">Укажите накопительный итог, не сумму последней операции. Не больше {{ money(item.confirmedKopecks) }}.</p>
                      <label class="sheet-field"><span>Возвращено всего, ₽</span><input type="text" inputmode="decimal" [value]="returnDraft(item.id, item.returnedKopecks).amountRubles"
                        (input)="setReturnField(item.id, item.returnedKopecks, 'amountRubles', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                      <label class="sheet-field"><span>Основание</span><input type="text" maxlength="255" [value]="returnDraft(item.id, item.returnedKopecks).reason"
                        (input)="setReturnField(item.id, item.returnedKopecks, 'reason', $any($event.target).value)" [disabled]="mutationBusy()"></label>
                      <button type="button" class="secondary" (click)="recordReturn(item)" [disabled]="mutationBusy()">Учесть возврат</button>
                    </details>
                  } @else if (item.sourceType === 'COMMON_INVOICE' && item.status === 'RETURN_AMOUNT_PENDING') {
                    <p class="hint">Возврат общего счёта учитывается после сверки самого счёта.</p>
                  }
                  @if (item.events.length) {
                    <details><summary>История изменений ({{ item.events.length }})</summary>
                      @for (event of item.events; track event.id) {
                        <p><small>{{ event.effectiveAt }} · {{ event.eventType }} · {{ money(event.amountKopecks) }}{{ event.reason ? ' · ' + event.reason : '' }}</small></p>
                      }
                    </details>
                  }
                </article>
              }
              @if (journal() && journal()!.totalPages > 1) {
                <nav class="journal-pagination" aria-label="Страницы журнала">
                  <button type="button" class="secondary" (click)="loadJournal(journalPage() - 1)" [disabled]="journalLoading() || journal()!.first">Назад</button>
                  <span>{{ journalPage() + 1 }} из {{ journal()!.totalPages }}</span>
                  <button type="button" class="secondary" (click)="loadJournal(journalPage() + 1)" [disabled]="journalLoading() || journal()!.last">Далее</button>
                </nav>
              }
            }
          </div>
          <footer class="sheet-actions"><button type="button" class="secondary" (click)="close()" [disabled]="mutationBusy()">Закрыть</button></footer>
        </div>
      </ng-template>
    </ion-modal>
  `,
  styles: [`
    .finance-sheet{height:100%;min-height:0;gap:.65rem}.finance-sheet h2{margin:.1rem 0 0;font-size:1.05rem}.finance-content{align-content:start;gap:.75rem;padding-right:.15rem}
    .close-button{display:grid;place-items:center;width:2.5rem;height:2.5rem;border:0;border-radius:.7rem;color:var(--otziv-dark);background:var(--otziv-light)}
    .section-tabs,.profile-tabs,.section-head,.journal-pagination{display:flex;align-items:center;gap:.45rem}.section-tabs button,.profile-tabs button,.finance-card button,.journal-pagination button,.sheet-actions button{min-height:44px;border:1px solid rgba(103,116,131,.2);border-radius:.72rem;padding:.48rem .65rem;background:var(--otziv-white);color:var(--otziv-dark);font:inherit;font-size:.76rem;font-weight:900}.section-tabs button{flex:1 1 0;padding-inline:.28rem}.section-tabs button.active,.profile-tabs button.active,.finance-card button.primary{color:#fff;background:var(--otziv-primary)}.profile-tabs{flex-wrap:wrap}button:disabled{opacity:.55}
    .finance-card{display:grid;gap:.62rem;border:1px solid rgba(103,116,131,.17);border-radius:.85rem;padding:.75rem;background:var(--otziv-white)}h3{margin:0;font-size:.88rem}.hint,.finance-card small{margin:0;color:var(--otziv-info);font-size:.74rem;line-height:1.4}.mode-state{margin:0;font-size:.78rem;font-weight:900;color:var(--otziv-primary)}
    .toggle-row{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:.6rem;min-height:44px;font-size:.78rem;font-weight:850}.toggle-row input{width:1.2rem;height:1.2rem}.finance-card input,.finance-card select{min-height:44px}.metrics{display:grid;gap:.45rem}.metrics>div{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:.4rem;font-size:.76rem}.metrics span{color:var(--otziv-info)}.metrics strong{text-align:right}
    .alert,.success{margin:0;border-radius:.7rem;padding:.6rem;font-size:.74rem;line-height:1.35}.alert{color:#a52b43;background:#fff0f3}.success{color:#126e58;background:#e8f8f2}.section-head{justify-content:space-between}.section-head span{font-size:.72rem;font-weight:900}.journal-entry>p,.history-entry p{margin:0;font-size:.75rem}.journal-filters{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.5rem}.journal-pagination{justify-content:space-between}.journal-entry details,.history-entry details{display:grid;gap:.5rem}.journal-entry summary,.history-entry summary{cursor:pointer;color:var(--otziv-primary);font-weight:900}.history-entry{display:grid;gap:.25rem;border-top:1px solid rgba(103,116,131,.17);padding-top:.5rem;font-size:.76rem}
  `]
})
export class ContractorFinanceComponent implements OnInit {
  @Input({ required: true }) user!: AdminUser;
  @Output() readonly dismissed = new EventEmitter<void>();

  readonly isOpen = signal(true);
  readonly section = signal<FinanceSection>('profile');
  readonly profiles = signal<ContractorPaymentProfile[]>([]);
  readonly profilesLoading = signal(false);
  readonly selectedProfileId = signal<number | null>(null);
  readonly activeProfile = computed(() => this.profiles().find((item) => item.id === this.selectedProfileId()) ?? null);
  readonly profileDraft = signal<ContractorProfileDraft | null>(null);
  readonly profileDirty = computed(() => {
    const profile = this.activeProfile();
    const draft = this.profileDraft();
    return !!profile && !!draft && JSON.stringify(draft) !== JSON.stringify(contractorProfileDraft(profile));
  });
  readonly profileSaving = signal(false);
  readonly openingHistory = signal<ContractorOpeningBalanceChange[]>([]);
  readonly openingLoading = signal(false);
  readonly openingLoaded = signal(false);
  readonly settlementDraft = signal<ContractorSettlementDraft>(newContractorSettlementDraft());
  readonly settlements = signal<ContractorDirectSettlement[]>([]);
  readonly settlementsLoading = signal(false);
  readonly settlementsLoaded = signal(false);
  readonly reversalDrafts = signal<Record<number, ContractorSettlementDraft>>({});
  readonly settlementSaving = signal(false);
  readonly journal = signal<ContractorAllocationJournalPage | null>(null);
  readonly journalLoading = signal(false);
  readonly journalStatus = signal('');
  readonly journalMode = signal<ContractorPaymentMode | ''>('');
  readonly journalSourceType = signal<ContractorAllocationJournalItem['sourceType'] | ''>('');
  readonly journalSourceIdDraft = signal('');
  readonly journalSourceId = signal<number | null>(null);
  readonly journalPage = signal(0);
  readonly returnDrafts = signal<Record<number, { amountRubles: string; reason: string }>>({});
  readonly returnSaving = signal(false);
  readonly confirming = signal(false);
  readonly mutationBusy = computed(() => this.profileSaving() || this.settlementSaving() || this.returnSaving() || this.confirming());
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);

  private profilesEpoch = 0;
  private openingEpoch = 0;
  private settlementsEpoch = 0;
  private journalEpoch = 0;

  constructor(private readonly api: ApiService, private readonly auth: AuthService, private readonly confirm: MobileConfirmService) {}

  ngOnInit(): void { void this.loadProfiles(); }

  async close(): Promise<void> {
    if (this.mutationBusy()) return;
    if (this.profileDirty()) {
      const discard = await this.confirm.confirm({
        title: 'Закрыть без сохранения', message: 'Изменения платёжного профиля не сохранены.',
        confirmText: 'Закрыть', danger: true
      });
      if (!discard) return;
      const profile = this.activeProfile();
      if (profile) this.profileDraft.set(contractorProfileDraft(profile));
    }
    this.isOpen.set(false);
  }

  async selectSection(next: FinanceSection): Promise<void> {
    if (next === this.section() || this.mutationBusy()) return;
    if (this.profileDirty()) {
      const discard = await this.confirm.confirm({
        title: 'Перейти без сохранения', message: 'Изменения платёжного профиля не сохранены.',
        confirmText: 'Перейти', danger: true
      });
      if (!discard) return;
      const profile = this.activeProfile();
      if (profile) this.profileDraft.set(contractorProfileDraft(profile));
    }
    this.section.set(next);
    this.error.set(null);
    if (next === 'settlements' && !this.settlementsLoaded()) void this.loadSettlements();
    if (next === 'journal' && !this.journal()) void this.loadJournal(0);
  }

  async loadProfiles(resetDraft = false): Promise<void> {
    if (!this.auth.hasAnyRealmRole(['ADMIN', 'OWNER'])) {
      this.error.set('Для управления расчётами нужен доступ администратора или владельца.');
      return;
    }
    const epoch = ++this.profilesEpoch;
    const previousMode = this.activeProfile()?.reportingLive;
    this.profilesLoading.set(true);
    try {
      const profiles = await firstValueFrom(this.api.getAdminContractorPaymentProfiles(this.user.id));
      if (epoch !== this.profilesEpoch || !this.isOpen()) return;
      this.profiles.set(profiles);
      const selected = profiles.find((profile) => profile.id === this.selectedProfileId()) ?? profiles[0] ?? null;
      if (selected && selected.id !== this.selectedProfileId()) {
        this.selectedProfileId.set(selected.id);
        this.profileDraft.set(contractorProfileDraft(selected));
      } else if (selected && resetDraft) {
        this.profileDraft.set(contractorProfileDraft(selected));
      } else if (!selected) {
        this.selectedProfileId.set(null);
        this.profileDraft.set(null);
      }
      if (selected && previousMode !== undefined && previousMode !== selected.reportingLive) {
        this.settlementDraft.set(newContractorSettlementDraft());
        this.error.set('Режим учёта изменился. Проверьте данные перевода заново.');
      }
    } catch (error) {
      if (epoch === this.profilesEpoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить платёжные профили.'));
    } finally {
      if (epoch === this.profilesEpoch) this.profilesLoading.set(false);
    }
  }

  async selectProfile(profileId: number): Promise<void> {
    if (profileId === this.selectedProfileId() || this.mutationBusy()) return;
    const next = this.profiles().find((profile) => profile.id === profileId);
    if (!next) return;
    if (this.profileDirty()) {
      const discard = await this.confirm.confirm({ title: 'Сменить профиль', message: 'Несохранённые изменения будут потеряны.', confirmText: 'Сменить', danger: true });
      if (!discard) return;
    }
    this.selectedProfileId.set(profileId);
    this.profileDraft.set(contractorProfileDraft(next));
    this.openingHistory.set([]); this.openingLoaded.set(false); this.openingEpoch += 1; this.openingLoading.set(false);
    this.settlements.set([]); this.settlementsLoaded.set(false); this.settlementsEpoch += 1; this.settlementsLoading.set(false);
    this.reversalDrafts.set({}); this.settlementDraft.set(newContractorSettlementDraft());
    this.error.set(null); this.success.set(null);
    if (this.section() === 'settlements') void this.loadSettlements();
  }

  setProfileField(field: keyof ContractorProfileDraft, value: string | boolean): void {
    if (this.mutationBusy()) return;
    this.profileDraft.update((draft) => {
      if (!draft) return null;
      const updated = { ...draft, [field]: value } as ContractorProfileDraft;
      return field === 'enabled' && value === false ? { ...updated, liveEnabled: false } : updated;
    });
    this.error.set(null); this.success.set(null);
  }

  openingBalanceChanged(profile: ContractorPaymentProfile): boolean {
    const value = parseKopecks(this.profileDraft()?.openingBalanceRubles ?? '', true);
    return value !== null && value !== profile.openingBalanceKopecks;
  }

  async saveProfile(): Promise<void> {
    const profile = this.activeProfile();
    const draft = this.profileDraft();
    if (!profile || !draft || !this.profileDirty() || this.mutationBusy()) return;
    const validated = contractorProfileRequest(profile, draft);
    if (!validated.request) { this.error.set(validated.error); return; }
    if (!profile.liveEnabled && draft.liveEnabled) {
      const confirmed = await this.confirmMutation({
        title: 'Включить выдачу реквизитов',
        message: 'После сохранения реквизиты смогут попасть в новые счета при достаточном доступном остатке.',
        confirmText: 'Включить'
      });
      if (!confirmed) return;
    }
    this.profileSaving.set(true); this.error.set(null); this.success.set(null);
    try {
      await firstValueFrom(this.api.updateAdminContractorPaymentProfile(this.user.id, validated.request));
      this.success.set('Платёжный профиль сохранён.');
      this.openingLoaded.set(false); this.openingHistory.set([]);
      await this.loadProfiles(true);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось сохранить платёжный профиль.'));
      if (this.status(error) === 409) await this.loadProfiles(true);
    } finally { this.profileSaving.set(false); }
  }

  async loadOpeningHistory(open = true): Promise<void> {
    const profileId = this.selectedProfileId();
    if (!open || profileId === null || this.openingLoaded() || this.openingLoading()) return;
    const epoch = ++this.openingEpoch; this.openingLoading.set(true);
    try {
      const changes = await firstValueFrom(this.api.getAdminContractorOpeningBalanceHistory(this.user.id, profileId));
      if (epoch === this.openingEpoch && profileId === this.selectedProfileId()) {
        this.openingHistory.set(changes); this.openingLoaded.set(true);
      }
    } catch (error) {
      if (epoch === this.openingEpoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить историю остатка.'));
    } finally { if (epoch === this.openingEpoch) this.openingLoading.set(false); }
  }

  setSettlementField(field: keyof ContractorSettlementDraft, value: string): void {
    if (this.mutationBusy()) return;
    this.settlementDraft.update((draft) => ({ ...draft, [field]: value, idempotencyKey: newContractorAccrualOperationKey() }));
    this.error.set(null); this.success.set(null);
  }

  async loadSettlements(): Promise<void> {
    const profileId = this.selectedProfileId();
    if (profileId === null || this.settlementsLoading()) return;
    const epoch = ++this.settlementsEpoch; this.settlementsLoading.set(true);
    try {
      const history = await firstValueFrom(this.api.getAdminContractorDirectSettlements(this.user.id, profileId));
      if (epoch === this.settlementsEpoch && profileId === this.selectedProfileId()) {
        this.settlements.set(history); this.settlementsLoaded.set(true);
        this.reversalDrafts.update((drafts) => {
          const next = { ...drafts };
          for (const item of history) {
            if (item.type === 'PAYMENT' && !next[item.id]) next[item.id] = newContractorSettlementDraft();
          }
          return next;
        });
      }
    } catch (error) {
      if (epoch === this.settlementsEpoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить прямые переводы.'));
    } finally { if (epoch === this.settlementsEpoch) this.settlementsLoading.set(false); }
  }

  async createSettlement(): Promise<void> {
    const profile = this.activeProfile();
    if (!profile || this.mutationBusy()) return;
    const mode: ContractorPaymentMode = profile.reportingLive ? 'LIVE' : 'SHADOW';
    const validated = contractorSettlementRequest(mode, this.settlementDraft());
    if (!validated.request) { this.error.set(validated.error); return; }
    if (mode === 'LIVE') {
      const confirmed = await this.confirmMutation({
        title: 'Учесть фактический перевод',
        message: `Вы проверили выписку и подтверждаете перевод ${this.money(validated.request.amountKopecks)}?`,
        confirmText: 'Учесть перевод'
      });
      if (!confirmed) return;
    }
    this.settlementSaving.set(true); this.error.set(null); this.success.set(null);
    try {
      const result = await firstValueFrom(this.api.createAdminContractorDirectSettlement(this.user.id, profile.id, validated.request));
      this.settlementDraft.set(newContractorSettlementDraft());
      this.success.set(result.simulated ? 'Тестовый перевод учтён.' : 'Фактический перевод учтён.');
      await Promise.all([this.loadProfiles(), this.reloadSettlements(), this.refreshJournalIfLoaded()]);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось учесть перевод. Повторите с теми же данными.'));
      if (this.status(error) === 409) await this.loadProfiles();
    } finally { this.settlementSaving.set(false); }
  }

  reversalDraft(id: number): ContractorSettlementDraft {
    return this.reversalDrafts()[id] ?? newContractorSettlementDraft();
  }

  setReversalField(id: number, field: keyof ContractorSettlementDraft, value: string): void {
    if (this.mutationBusy()) return;
    const current = this.reversalDrafts()[id] ?? newContractorSettlementDraft();
    this.reversalDrafts.update((drafts) => ({ ...drafts, [id]: {
      ...current, [field]: value, idempotencyKey: newContractorAccrualOperationKey()
    } }));
    this.error.set(null); this.success.set(null);
  }

  remainingSettlement(original: ContractorDirectSettlement): number {
    if (original.type !== 'PAYMENT') return 0;
    const reversed = this.settlements()
      .filter((item) => item.type === 'REVERSAL' && item.originalSettlementId === original.id)
      .reduce((sum, item) => sum + item.amountKopecks, 0);
    return Math.max(0, original.amountKopecks - reversed);
  }

  async reverseSettlement(original: ContractorDirectSettlement): Promise<void> {
    const profileId = this.selectedProfileId();
    if (profileId === null || original.type !== 'PAYMENT' || this.mutationBusy()) return;
    const draft = this.reversalDrafts()[original.id];
    if (!draft) { this.error.set('Заполните сумму, дату, основание и ID документа.'); return; }
    const validated = contractorSettlementRequest(original.mode, draft);
    if (!validated.request) { this.error.set(validated.error); return; }
    if (validated.request.amountKopecks > this.remainingSettlement(original)) {
      this.error.set(`Корректировка не может превышать ${this.money(this.remainingSettlement(original))}.`); return;
    }
    if (validated.request.effectiveAt < original.effectiveAt.slice(0, 16)) {
      this.error.set('Дата корректировки не может быть раньше исходного перевода.'); return;
    }
    const confirmed = await this.confirmMutation({
      title: 'Корректировка перевода',
      message: `Учесть возврат ${this.money(validated.request.amountKopecks)} из перевода №${original.id}?`,
      confirmText: 'Учесть корректировку', danger: true
    });
    if (!confirmed) return;
    this.settlementSaving.set(true); this.error.set(null); this.success.set(null);
    try {
      await firstValueFrom(this.api.reverseAdminContractorDirectSettlement(this.user.id, profileId, original.id, validated.request));
      this.reversalDrafts.update((drafts) => { const next = { ...drafts }; delete next[original.id]; return next; });
      this.success.set('Корректировка перевода учтена.');
      await Promise.all([this.loadProfiles(), this.reloadSettlements(), this.refreshJournalIfLoaded()]);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось учесть корректировку.'));
      if (this.status(error) === 409) await Promise.all([this.loadProfiles(), this.reloadSettlements()]);
    } finally { this.settlementSaving.set(false); }
  }

  changeJournalStatus(value: string): void { this.journalStatus.set(value); void this.loadJournal(0); }
  changeJournalMode(value: string): void { this.journalMode.set(value as ContractorPaymentMode | ''); void this.loadJournal(0); }
  changeJournalSourceType(value: string): void {
    this.journalSourceType.set(value as ContractorAllocationJournalItem['sourceType'] | '');
    void this.loadJournal(0);
  }
  applyJournalSourceId(): void {
    const value = this.journalSourceIdDraft().trim();
    const id = value ? Number(value) : null;
    if (id !== null && (!Number.isSafeInteger(id) || id <= 0)) {
      this.error.set('ID источника должен быть положительным целым числом.'); return;
    }
    this.journalSourceId.set(id); this.error.set(null); void this.loadJournal(0);
  }

  async loadJournal(page: number): Promise<void> {
    if (page < 0 || (this.journal() && page >= this.journal()!.totalPages && page !== 0)) return;
    const epoch = ++this.journalEpoch; this.journalLoading.set(true);
    try {
      const journal = await firstValueFrom(this.api.getAdminContractorAllocationJournal(this.user.id, {
        status: this.journalStatus(), mode: this.journalMode(), sourceType: this.journalSourceType(),
        sourceId: this.journalSourceId(), page, size: 10
      }));
      if (epoch === this.journalEpoch) { this.journal.set(journal); this.journalPage.set(journal.number); }
    } catch (error) {
      if (epoch === this.journalEpoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить журнал распределения.'));
    } finally { if (epoch === this.journalEpoch) this.journalLoading.set(false); }
  }

  canRecordReturn(item: ContractorAllocationJournalItem): boolean {
    return item.sourceType === 'PAYMENT_LINK'
      && (item.status === 'RETURN_AMOUNT_PENDING' || item.status === 'PARTIALLY_RETURNED')
      && item.confirmedKopecks > 0;
  }

  returnDraft(id: number, returnedKopecks: number): { amountRubles: string; reason: string } {
    return this.returnDrafts()[id] ?? { amountRubles: (returnedKopecks / 100).toFixed(2), reason: '' };
  }

  setReturnField(id: number, returnedKopecks: number, field: 'amountRubles' | 'reason', value: string): void {
    if (this.mutationBusy()) return;
    this.returnDrafts.update((drafts) => ({
      ...drafts, [id]: { ...this.returnDraft(id, returnedKopecks), [field]: value }
    }));
    this.error.set(null); this.success.set(null);
  }

  async recordReturn(item: ContractorAllocationJournalItem): Promise<void> {
    if (!this.canRecordReturn(item) || this.mutationBusy()) return;
    const draft = this.returnDraft(item.id, item.returnedKopecks);
    const total = parseKopecks(draft.amountRubles, true);
    if (total === null || total < item.returnedKopecks || total > item.confirmedKopecks) {
      this.error.set(`Итог возврата должен быть от ${this.money(item.returnedKopecks)} до ${this.money(item.confirmedKopecks)}.`); return;
    }
    const reason = draft.reason.trim();
    if (!reason || reason.length > 255) { this.error.set('Укажите основание возврата длиной до 255 символов.'); return; }
    const confirmed = await this.confirmMutation({
      title: 'Учесть возврат', message: `Подтверждаете накопительный итог возврата ${this.money(total)} для ссылки №${item.sourceId}?`,
      confirmText: 'Учесть возврат', danger: true
    });
    if (!confirmed) return;
    this.returnSaving.set(true); this.error.set(null); this.success.set(null);
    try {
      await firstValueFrom(this.api.recordAdminContractorReturnedAmount(item.id, { returnedTotalKopecks: total, reason }));
      this.returnDrafts.update((drafts) => { const next = { ...drafts }; delete next[item.id]; return next; });
      this.success.set('Итог возврата учтён.');
      await Promise.all([this.loadProfiles(), this.loadJournal(this.journalPage())]);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось учесть возврат. Проверьте актуальные суммы.'));
      if (this.status(error) === 409) await this.loadJournal(this.journalPage());
    } finally { this.returnSaving.set(false); }
  }

  sourceLabel(item: ContractorAllocationJournalItem): string {
    return ({ PAYMENT_LINK: 'Ссылка', COMMON_INVOICE: 'Общий счёт', DIRECT_SETTLEMENT: 'Прямой перевод', ACTUAL_PAYMENT: 'Поступление' })[item.sourceType];
  }

  statusLabel(status: string): string {
    return ({
      RESERVED: 'Зарезервировано', CLIENT_REPORTED: 'Клиент сообщил', PARTIALLY_CONFIRMED: 'Частично подтверждено',
      CONFIRMED: 'Подтверждено', SIMULATED_PAID: 'Тестовая оплата', LATE_PAYMENT_AFTER_RELEASE: 'Поздняя оплата',
      OWNER_FALLBACK: 'Владелец', RELEASED_UNPAID: 'Резерв освобождён', EXPIRED: 'Истекло', CANCELED: 'Отменено',
      PARTIALLY_RETURNED: 'Частично возвращено', RETURN_AMOUNT_PENDING: 'Возврат уточняется', RETURNED: 'Возвращено'
    } as Record<string, string>)[status] ?? status;
  }

  money(kopecks: number): string {
    return `${(kopecks / 100).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ₽`;
  }

  private async reloadSettlements(): Promise<void> {
    this.settlementsEpoch += 1; this.settlementsLoading.set(false);
    await this.loadSettlements();
  }

  private async refreshJournalIfLoaded(): Promise<void> {
    if (this.journal()) await this.loadJournal(this.journalPage());
  }

  private async confirmMutation(options: { title: string; message: string; confirmText: string; danger?: boolean }): Promise<boolean> {
    if (this.mutationBusy()) return false;
    this.confirming.set(true);
    try { return await this.confirm.confirm(options); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось открыть подтверждение.')); return false; }
    finally { this.confirming.set(false); }
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
    if (this.status(error) === 409) return 'Данные изменились. Обновите страницу и повторите действие.';
    if (this.status(error) === 403) return 'У вас нет доступа к расчётам этого сотрудника.';
    return error instanceof Error ? error.message : fallback;
  }
}
