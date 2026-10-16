import { Capacitor } from '@capacitor/core';
import { Directory, Filesystem } from '@capacitor/filesystem';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  MobileClientOffersApi,
  type MobileOfferAction,
  type MobileOfferAudience,
  type MobileOfferBoard,
  type MobileOfferRecipient,
  type MobileOfferSettings,
  type MobileOfferSummary
} from '../core/client-offers.api';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

const emptyDraft = (): MobileOfferSettings => ({
  title: '', message: '', dailyLimit: 30, intervalMinutes: 10, windowStart: '10:00', windowEnd: '21:00',
  includeActive: true, includeStopped: false, includeBanned: false, fileMode: 'ATTACHMENT', testOnly: false,
  includeLeadInWork: false, includeLeadOther: false, leadFallbackClientId: null
});

const FILE_EXTENSIONS = new Set(['pdf', 'txt', 'doc', 'docx', 'xlsx', 'pptx', 'jpg', 'jpeg', 'png']);
const MAX_FILE_BYTES = 5 * 1024 * 1024;

@Component({
  selector: 'app-admin-client-offers-mobile',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Рассылки" />
      <ion-content>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)"><ion-refresher-content /></ion-refresher>
        <main class="offers-page">
          <header class="page-title">
            <div><p class="eyebrow">АДМИН / КЛИЕНТЫ</p><h1>Рассылки предложений</h1></div>
            <button type="button" class="icon-button" aria-label="Обновить" (click)="load()" [disabled]="busy() || loading()"><span class="material-icons-sharp">refresh</span></button>
          </header>
          <p class="intro">Одно предложение каждому выбранному чату. Отправка продолжается на сервере, даже когда приложение закрыто.</p>
          @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
          @if (notice()) { <p class="alert success" role="status">{{ notice() }}</p> }
          @if (loading() && !board()) { <p class="empty">Загружаю рассылки…</p> }
          @if (board(); as data) {
            @if (!data.liveEnabled) { <p class="alert warning">Отправка клиентских сообщений выключена в настройках автоответчика. Черновики можно сохранять; очередь продолжит работу после включения.</p> }
            <section class="card campaigns">
              <div class="section-heading"><h2>Кампании</h2><button type="button" (click)="newDraft()" [disabled]="busy()">+ Новая</button></div>
              <div class="campaign-scroll">
                @for (row of data.campaigns; track row.campaign.id) {
                  <button type="button" class="campaign" [class.selected]="selectedId() === row.campaign.id" (click)="select(row)" [disabled]="busy()">
                    <strong>{{ row.campaign.settings.title }}</strong>
                    <span>{{ label(row.campaign.state) }} @if (row.campaign.settings.testOnly) { · Тестовая } · {{ row.counts.sent }} / {{ row.counts.total }}</span>
                  </button>
                } @empty { <p class="hint">Пока нет рассылок. Создайте черновик ниже.</p> }
              </div>
            </section>

            <section class="card editor">
              <div class="section-heading"><h2>{{ editable() ? (selectedId() ? 'Черновик' : 'Новое предложение') : selected()?.campaign?.settings?.title }}</h2>
                @if (selected(); as row) { <span class="pill">{{ label(row.campaign.state) }}</span> }
              </div>
              @if (editable()) {
                <form (ngSubmit)="save(false)">
                  <fieldset [disabled]="busy()">
                    <label class="field"><span>Название рассылки</span><input name="title" [(ngModel)]="draft.title" (ngModelChange)="changed()" maxlength="120" required></label>
                    <label class="field"><span>Сообщение</span><textarea name="message" [(ngModel)]="draft.message" (ngModelChange)="changed()" rows="6" maxlength="1000" required></textarea><small>{{ draft.message.length }} / 1000 символов</small></label>
                    <div class="file-panel">
                      <label class="field"><span>Файл к предложению (до 5 МБ)</span>
                        @for (key of [fileInputKey]; track key) { <input type="file" accept=".pdf,.txt,.doc,.docx,.xlsx,.pptx,.jpg,.jpeg,.png" (change)="chooseFile($event)"> }
                      </label>
                      @if (fileName()) { <div class="file-name"><span>{{ fileName() }}</span><button type="button" (click)="clearFile()">Убрать</button></div> }
                      <label class="field"><span>Как отправлять файл</span><select name="fileMode" [(ngModel)]="draft.fileMode" (ngModelChange)="changed()"><option value="ATTACHMENT">Вложением</option><option value="LINK">Ссылкой</option></select></label>
                    </div>

                    <h3>Получатели</h3>
                    <label class="field"><span>Режим</span><select name="testOnly" [(ngModel)]="draft.testOnly" (ngModelChange)="changed()"><option [ngValue]="false">Клиенты и лиды</option><option [ngValue]="true">Тестовая рассылка</option></select></label>
                    @if (draft.testOnly) {
                      <p class="alert warning">Получат только активные администраторы и владельцы с личными Telegram-чатами. Клиентам сообщение не отправится.</p>
                    } @else {
                      <p class="hint">Выберите списки. Общий чат нескольких компаний получит одно сообщение.</p>
                      <h4>Компании</h4>
                      <label class="check"><input type="checkbox" name="active" [(ngModel)]="draft.includeActive" (ngModelChange)="changed()"> В работе</label>
                      <label class="check"><input type="checkbox" name="stopped" [(ngModel)]="draft.includeStopped" (ngModelChange)="changed()"> На стопе</label>
                      <label class="check"><input type="checkbox" name="banned" [(ngModel)]="draft.includeBanned" (ngModelChange)="changed()"> Бан</label>
                      <h4>Лиды</h4>
                      <label class="check"><input type="checkbox" name="leadInWork" [(ngModel)]="draft.includeLeadInWork" (ngModelChange)="changed()"> В работе</label>
                      <label class="check"><input type="checkbox" name="leadOther" [(ngModel)]="draft.includeLeadOther" (ngModelChange)="changed()"> Остальные, кроме бана</label>
                      @if (draft.includeLeadInWork || draft.includeLeadOther) {
                        <p class="hint">Лидам отправляется в личный WhatsApp с аккаунта менеджера. Лиды в бане всегда исключены; один номер получает одно сообщение.</p>
                        <label class="field"><span>Аккаунт для лидов без менеджера</span><select name="leadFallbackClientId" [(ngModel)]="draft.leadFallbackClientId" (ngModelChange)="changed()"><option [ngValue]="null">Пропускать</option>@for (account of leadSenders(); track account) { <option [ngValue]="account">{{ account }}</option> }</select></label>
                        <p class="hint">Если аккаунт назначенного менеджера не настроен, лид будет пропущен. Этот вариант нужен только для лидов без менеджера.</p>
                        @if (sendersUnavailable()) { <p class="alert warning">Не удалось загрузить аккаунты отправителей. Обновите страницу.</p> }
                      }
                    }

                    <h3>Темп отправки</h3>
                    <div class="schedule">
                      <label class="field"><span>Сообщений в день</span><input type="number" name="dailyLimit" [(ngModel)]="draft.dailyLimit" (ngModelChange)="changed()" min="1" max="10000" step="1" required></label>
                      <label class="field"><span>Интервал, минут</span><input type="number" name="intervalMinutes" [(ngModel)]="draft.intervalMinutes" (ngModelChange)="changed()" min="1" max="1440" step="1" required></label>
                      <label class="field"><span>Начало</span><input type="time" name="windowStart" [(ngModel)]="draft.windowStart" (ngModelChange)="changed()" required></label>
                      <label class="field"><span>Окончание</span><input type="time" name="windowEnd" [(ngModel)]="draft.windowEnd" (ngModelChange)="changed()" required></label>
                    </div>
                    <p class="hint">Время Иркутска. Лимит общий для кампании и не переносится на следующий день. Непоместившаяся в окно очередь продолжится в следующие дни.</p>
                    <div class="actions">
                      <button type="button" (click)="preview()" [disabled]="!valid()">Посчитать получателей</button>
                      <button type="submit" [disabled]="!valid()">Сохранить черновик</button>
                    </div>
                    @if (audience(); as rows) {
                      <div class="audience-counts">@for (row of rows; track row.audience) { <p><strong>{{ label(row.audience) }}</strong><span>{{ row.reachable }} доступны @if (row.total > row.reachable) { · {{ row.total - row.reachable }} недоступны }</span></p> }</div>
                      <button type="button" class="primary full" (click)="save(true)" [disabled]="!valid() || !data.liveEnabled || !reachableCount()">{{ draft.testOnly ? 'Запустить тестовую рассылку' : 'Запустить рассылку' }}</button>
                    } @else { <p class="hint">Перед запуском посчитайте получателей и проверьте списки.</p> }
                  </fieldset>
                </form>
              } @else if (selected(); as row) {
                @if (row.campaign.settings.testOnly) { <p class="alert warning">Тестовая рассылка только администраторам и владельцам в личные Telegram-чаты.</p> }
                <div class="stats"><span><b>{{ row.counts.sent }}</b>Отправлено</span><span><b>{{ row.counts.pending }}</b>В очереди</span><span><b>{{ row.usedToday }} / {{ row.campaign.settings.dailyLimit }}</b>Попыток сегодня</span><span><b>{{ row.counts.failed + row.counts.unknown }}</b>Требуют внимания</span></div>
                <p class="message">{{ row.campaign.settings.message }}</p>
                @if (row.campaign.fileName) { <button type="button" (click)="download()" [disabled]="busy()">Скачать {{ row.campaign.fileName }}</button><p class="hint">{{ row.campaign.settings.fileMode === 'LINK' ? 'Отправляется ссылкой' : 'Отправляется вложением' }}</p> }
                <p class="hint">Интервал {{ row.campaign.settings.intervalMinutes }} мин · {{ row.campaign.settings.windowStart }}–{{ row.campaign.settings.windowEnd }}, Иркутск</p>
                <p class="hint">Следующая проверка очереди: {{ displayDate(row.campaign.nextAt) }}</p>
                <div class="actions">
                  @if (row.campaign.state === 'RUNNING') { <button type="button" (click)="action('pause')" [disabled]="busy()">Пауза</button> }
                  @if (row.campaign.state === 'PAUSED') { <button type="button" class="primary" (click)="action('resume')" [disabled]="busy() || !data.liveEnabled">Продолжить</button> }
                  @if ((row.campaign.state === 'PAUSED' || row.campaign.state === 'COMPLETED') && row.counts.failed > 0) { <button type="button" (click)="action('retry-failed')" [disabled]="busy()">Вернуть ошибки в очередь</button> }
                  @if (row.campaign.state === 'RUNNING' || row.campaign.state === 'PAUSED') { <button type="button" class="danger" (click)="action('cancel')" [disabled]="busy()">Остановить</button> }
                </div>
                <p class="hint">Пауза и остановка действуют на следующие отправки. Уже начатое сообщение может завершиться.</p>
                @if (row.counts.unknown > 0) { <p class="alert warning">У {{ row.counts.unknown }} сообщений нет подтверждения. Проверьте чаты вручную: автоматический повтор заблокирован, чтобы не отправить дубликат.</p> }
                <div class="section-heading"><h3>Получатели</h3><span>{{ row.counts.total }} всего · {{ row.counts.skipped }} пропущено</span></div>
                @if (recipientLoading()) { <p class="hint">Загружаю получателей…</p> }
                @for (recipient of recipients(); track recipient.id) { <div class="recipient"><strong>{{ recipient.companyTitle }}</strong><span>{{ label(recipient.audience) }} · {{ label(recipient.state) }}</span>@if (recipient.errorMessage) { <small>{{ recipient.errorMessage }}</small> }</div> }
                <nav class="pager" aria-label="Страницы получателей"><button type="button" (click)="setPage(page() - 1)" [disabled]="page() === 0 || recipientLoading()">Назад</button><span>Страница {{ page() + 1 }}</span><button type="button" (click)="setPage(page() + 1)" [disabled]="(page() + 1) * 100 >= row.counts.total || recipientLoading()">Далее</button></nav>
              }
            </section>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    :host{display:contents}ion-content{--background:var(--otziv-background)}.offers-page{display:grid;gap:.75rem;max-width:48rem;margin:0 auto;padding:1rem .8rem calc(5rem + env(safe-area-inset-bottom));color:var(--otziv-dark)}
    .page-title,.section-heading{display:flex;align-items:center;justify-content:space-between;gap:.6rem}.page-title h1{margin:.1rem 0;font-size:1.3rem}.eyebrow{margin:0;color:var(--otziv-info);font-size:.65rem;font-weight:900}.intro,.hint{margin:0;color:var(--otziv-info);font-size:.75rem;line-height:1.45}.card,.empty{display:grid;gap:.65rem;border:1px solid rgba(108,155,207,.24);border-radius:1rem;padding:.85rem;background:var(--otziv-white)}.section-heading h2,.section-heading h3{margin:0;font-size:1rem}.section-heading span{font-size:.7rem;color:var(--otziv-info)}
    button{min-height:2.4rem;border:1px solid rgba(108,155,207,.3);border-radius:.7rem;padding:.45rem .75rem;background:var(--otziv-white);color:var(--otziv-primary);font:inherit;font-size:.78rem;font-weight:800}button:disabled{opacity:.48}button.primary{background:var(--otziv-primary);border-color:var(--otziv-primary);color:#fff}button.danger{color:var(--otziv-danger)}button.full{width:100%}.icon-button{width:2.4rem;padding:0}.icon-button span{font-size:1.15rem}.campaign-scroll{display:grid;grid-auto-flow:column;grid-auto-columns:minmax(10rem,76%);gap:.4rem;overflow-x:auto;padding-bottom:.25rem}.campaign{text-align:left;display:grid;align-content:start;gap:.3rem;min-height:4.4rem;color:var(--otziv-dark)}.campaign.selected{border-color:var(--otziv-primary);background:var(--otziv-muted-surface)}.campaign strong{font-size:.8rem;overflow-wrap:anywhere}.campaign span{font-size:.65rem;color:var(--otziv-info)}.pill{border-radius:999px;padding:.2rem .4rem;background:var(--otziv-muted-surface);font-size:.65rem;font-weight:900}
    fieldset,.editor form{border:0;padding:0;margin:0;display:grid;gap:.7rem}.field{display:grid;gap:.25rem;color:var(--otziv-info);font-size:.72rem;font-weight:800}.field input,.field textarea,.field select{box-sizing:border-box;width:100%;min-width:0;border:1px solid rgba(108,155,207,.32);border-radius:.65rem;padding:.6rem;background:var(--otziv-white);color:var(--otziv-dark);font:inherit;font-size:.86rem}.field input,.field select{min-height:2.5rem}.field textarea{resize:vertical}.field small{font-size:.65rem}.file-panel{display:grid;gap:.5rem;padding:.6rem;border-radius:.7rem;background:var(--otziv-muted-surface)}.file-name{display:flex;align-items:center;justify-content:space-between;gap:.5rem;font-size:.7rem;overflow-wrap:anywhere}.file-name span{min-width:0}.check{display:flex;gap:.45rem;align-items:center;font-size:.77rem}.check input{accent-color:var(--otziv-primary);width:1.05rem;height:1.05rem}.editor h3,.editor h4{margin:.4rem 0 0;font-size:.85rem}.editor h4{font-size:.74rem}.schedule,.stats{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.55rem}.actions{display:flex;flex-wrap:wrap;gap:.4rem}.actions button{flex:1 1 auto}.audience-counts{display:grid;gap:.35rem}.audience-counts p,.recipient{display:grid;gap:.15rem;margin:0;padding:.45rem;border-radius:.6rem;background:var(--otziv-muted-surface);font-size:.72rem}.audience-counts span,.recipient span,.recipient small{color:var(--otziv-info);font-size:.67rem}.stats span{display:grid;gap:.15rem;padding:.5rem;border-radius:.6rem;background:var(--otziv-muted-surface);font-size:.65rem}.stats b{font-size:.95rem}.message{white-space:pre-wrap;overflow-wrap:anywhere;margin:0;padding:.7rem;border-radius:.7rem;background:var(--otziv-muted-surface);font-size:.8rem}.pager{display:flex;justify-content:space-between;align-items:center;gap:.4rem}.pager span{font-size:.7rem}.alert{margin:0;padding:.65rem;border-radius:.7rem;color:var(--otziv-danger);background:#fff0f4;font-size:.75rem;line-height:1.4}.alert.success{color:#127966;background:#eafaf3}.alert.warning{color:#916716;background:#fff6df}
    :host-context(body.otziv-dark-theme) .card,:host-context(body.otziv-dark-theme) .field input,:host-context(body.otziv-dark-theme) .field textarea,:host-context(body.otziv-dark-theme) .field select,:host-context(body.otziv-dark-theme) button:not(.primary):not(.selected){background:#1f2629}
  `]
})
export class AdminClientOffersPage implements OnInit, OnDestroy {
  private readonly api = inject(MobileClientOffersApi);
  private readonly confirm = inject(MobileConfirmService);
  readonly board = signal<MobileOfferBoard | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly recipientLoading = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly selectedId = signal<string | null>(null);
  readonly selected = computed(() => this.board()?.campaigns.find((row) => row.campaign.id === this.selectedId()) ?? null);
  readonly editable = computed(() => !this.selected() || this.selected()?.campaign.state === 'DRAFT');
  readonly audience = signal<MobileOfferAudience[] | null>(null);
  readonly reachableCount = computed(() => (this.audience() ?? []).reduce((sum, row) => sum + row.reachable, 0));
  readonly recipients = signal<MobileOfferRecipient[]>([]);
  readonly page = signal(0);
  readonly leadSenders = signal<string[]>([]);
  readonly sendersUnavailable = signal(false);
  draft = emptyDraft();
  draftId: string = crypto.randomUUID();
  file: File | null = null;
  removeFile = false;
  fileInputKey = 0;
  private polling: ReturnType<typeof setInterval> | undefined;
  private destroyed = false;
  private loadEpoch = 0;
  private recipientEpoch = 0;

  ngOnInit(): void {
    void this.load();
    void this.loadLeadSenders();
    this.polling = setInterval(() => {
      if (!document.hidden && !this.busy() && !this.loading()) void this.load(false);
    }, 15_000);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.loadEpoch++;
    this.recipientEpoch++;
    if (this.polling) clearInterval(this.polling);
  }

  async refresh(event: RefresherCustomEvent): Promise<void> { await this.load(); event.target.complete(); }

  async load(showLoading = true): Promise<void> {
    const epoch = ++this.loadEpoch;
    if (showLoading) this.loading.set(true);
    if (showLoading && this.sendersUnavailable()) void this.loadLeadSenders();
    try {
      const board = await firstValueFrom(this.api.board());
      if (this.destroyed || epoch !== this.loadEpoch) return;
      this.board.set(board);
      if (this.selected() && !this.editable()) void this.loadRecipients();
      if (showLoading) this.error.set(null);
    } catch (error) {
      if (!this.destroyed && epoch === this.loadEpoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить рассылки.'));
    } finally {
      if (epoch === this.loadEpoch) this.loading.set(false);
    }
  }

  private async loadLeadSenders(): Promise<void> {
    try { this.leadSenders.set(await firstValueFrom(this.api.leadSenders())); this.sendersUnavailable.set(false); }
    catch { this.sendersUnavailable.set(true); }
  }

  newDraft(): void {
    if (this.busy()) return;
    this.recipientEpoch++;
    this.recipientLoading.set(false);
    this.selectedId.set(null);
    this.draftId = crypto.randomUUID();
    this.draft = emptyDraft();
    this.file = null;
    this.removeFile = false;
    this.fileInputKey++;
    this.audience.set(null);
    this.recipients.set([]);
    this.error.set(null);
    this.notice.set(null);
  }

  select(row: MobileOfferSummary): void {
    if (this.busy()) return;
    this.recipientEpoch++;
    this.recipientLoading.set(false);
    this.selectedId.set(row.campaign.id);
    this.draftId = row.campaign.id;
    this.draft = { ...emptyDraft(), ...row.campaign.settings };
    this.file = null;
    this.removeFile = false;
    this.fileInputKey++;
    this.audience.set(null);
    this.recipients.set([]);
    this.page.set(0);
    this.error.set(null);
    this.notice.set(null);
    if (row.campaign.state !== 'DRAFT') void this.loadRecipients();
  }

  changed(): void { this.audience.set(null); this.notice.set(null); }

  chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    if (!file) return;
    const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
    if (!file.size || file.size > MAX_FILE_BYTES || file.name.length > 200 || !FILE_EXTENSIONS.has(extension)) {
      this.error.set('Выберите PDF, TXT, DOC, DOCX, XLSX, PPTX, JPG или PNG от 1 байта до 5 МБ.');
      input.value = '';
      return;
    }
    this.file = file;
    this.removeFile = false;
    this.error.set(null);
    this.changed();
  }

  clearFile(): void { this.file = null; this.removeFile = true; this.fileInputKey++; this.changed(); }
  fileName(): string { return this.file?.name ?? (this.removeFile ? '' : this.selected()?.campaign.fileName ?? ''); }

  valid(): boolean {
    const s = this.draft;
    return !!s.title.trim() && s.title.length <= 120 && !!s.message.trim() && s.message.length <= 1000
      && Number.isInteger(s.dailyLimit) && s.dailyLimit >= 1 && s.dailyLimit <= 10_000
      && Number.isInteger(s.intervalMinutes) && s.intervalMinutes >= 1 && s.intervalMinutes <= 1440
      && /^([01]\d|2[0-3]):[0-5]\d$/.test(s.windowStart) && /^([01]\d|2[0-3]):[0-5]\d$/.test(s.windowEnd) && s.windowStart < s.windowEnd
      && (s.testOnly || s.includeActive || s.includeStopped || s.includeBanned || s.includeLeadInWork || s.includeLeadOther);
  }

  async preview(): Promise<void> {
    if (!this.valid() || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try { this.audience.set(await firstValueFrom(this.api.preview(this.draft))); }
    catch (error) { this.error.set(this.errorMessage(error, 'Не удалось посчитать получателей.')); }
    finally { this.busy.set(false); }
  }

  async save(start: boolean): Promise<void> {
    if (!this.valid() || !this.editable() || this.busy()) return;
    if (start && (!this.board()?.liveEnabled || !this.audience() || this.reachableCount() === 0)) return;
    if (start && !await this.confirm.confirm({
      title: this.draft.testOnly ? 'Запустить тестовую рассылку' : 'Запустить рассылку клиентам',
      message: `Доступных получателей: ${this.reachableCount()}. После запуска состав получателей фиксируется. Проверьте текст, файл и выбранные списки.`,
      confirmText: 'Запустить',
      confirmDelayMs: this.draft.testOnly ? 0 : 1200
    })) return;
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const campaign = await firstValueFrom(this.api.save(this.draftId, this.draft, this.file, this.removeFile));
      this.selectedId.set(campaign.id);
      this.file = null;
      this.removeFile = false;
      this.fileInputKey++;
      const board = start ? await firstValueFrom(this.api.action(campaign.id, 'start')) : await firstValueFrom(this.api.board());
      this.board.set(board);
      this.notice.set(start ? 'Рассылка запущена. Отправка продолжится на сервере.' : 'Черновик сохранён.');
      if (start) void this.loadRecipients();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось сохранить рассылку. Проверьте её состояние перед повтором.'));
      void this.load(false);
    } finally {
      this.busy.set(false);
    }
  }

  async action(action: MobileOfferAction): Promise<void> {
    const row = this.selected();
    if (!row || this.busy() || !this.canAction(row, action)) return;
    if (action === 'cancel' && !await this.confirm.confirm({ title: 'Остановить рассылку', message: 'Сообщения в очереди будут пропущены. Уже начатая отправка может завершиться.', confirmText: 'Остановить', danger: true })) return;
    if (action === 'retry-failed' && !await this.confirm.confirm({ title: 'Повторить ошибки', message: `В очередь вернутся только ${row.counts.failed} сообщений с подтверждённой ошибкой. Сообщения без подтверждения не повторяются.`, confirmText: 'Вернуть в очередь' })) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      this.board.set(await firstValueFrom(this.api.action(row.campaign.id, action)));
      await this.loadRecipients();
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось изменить рассылку.')); }
    finally { this.busy.set(false); }
  }

  canAction(row: MobileOfferSummary, action: MobileOfferAction): boolean {
    switch (action) {
      case 'pause': return row.campaign.state === 'RUNNING';
      case 'resume': return row.campaign.state === 'PAUSED' && !!this.board()?.liveEnabled;
      case 'cancel': return row.campaign.state === 'RUNNING' || row.campaign.state === 'PAUSED';
      case 'retry-failed': return (row.campaign.state === 'PAUSED' || row.campaign.state === 'COMPLETED') && row.counts.failed > 0;
      case 'start': return row.campaign.state === 'DRAFT' && !!this.board()?.liveEnabled;
    }
  }

  async loadRecipients(): Promise<void> {
    const id = this.selectedId();
    const page = this.page();
    if (!id || this.recipientLoading()) return;
    const epoch = ++this.recipientEpoch;
    this.recipientLoading.set(true);
    try {
      const recipients = await firstValueFrom(this.api.recipients(id, page));
      if (!this.destroyed && epoch === this.recipientEpoch && this.selectedId() === id && this.page() === page) this.recipients.set(recipients);
    } catch (error) {
      if (epoch === this.recipientEpoch) this.error.set(this.errorMessage(error, 'Не удалось загрузить получателей.'));
    } finally {
      if (epoch === this.recipientEpoch) this.recipientLoading.set(false);
    }
  }

  setPage(page: number): void { if (this.recipientLoading() || page < 0) return; this.page.set(page); void this.loadRecipients(); }

  async download(): Promise<void> {
    const campaign = this.selected()?.campaign;
    if (!campaign?.fileName || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const blob = await firstValueFrom(this.api.file(campaign.id));
      const name = campaign.fileName.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 200) || 'offer-file';
      if (Capacitor.isNativePlatform()) {
        const data = await this.blobBase64(blob);
        await Filesystem.writeFile({ path: name, data, directory: Directory.Documents });
        this.notice.set(`Файл сохранён в документах приложения: ${name}`);
      } else {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = name;
        link.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
        this.notice.set(`Файл ${name} подготовлен для скачивания.`);
      }
    } catch (error) { this.error.set(this.errorMessage(error, 'Не удалось скачать файл.')); }
    finally { this.busy.set(false); }
  }

  private blobBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error('Не удалось прочитать файл.'));
      reader.onload = () => resolve(String(reader.result).split(',', 2)[1] ?? '');
      reader.readAsDataURL(blob);
    });
  }

  label(value: string): string {
    return ({ DRAFT: 'Черновик', RUNNING: 'Рассылается', PAUSED: 'Пауза', COMPLETED: 'Завершена', CANCELLED: 'Остановлена',
      PENDING: 'В очереди', SENDING: 'Отправляется', SENT: 'Отправлено', FAILED: 'Ошибка', UNKNOWN: 'Нужна проверка',
      SKIPPED: 'Пропущено', ACTIVE: 'Компании: в работе', STOPPED: 'Компании: на стопе', BANNED: 'Компании: бан',
      LEAD_IN_WORK: 'Лиды: в работе', LEAD_OTHER: 'Остальные лиды', TEST_STAFF: 'Администраторы и владельцы' } as Record<string, string>)[value] ?? value;
  }

  displayDate(value: string | null): string {
    if (!value) return '—';
    const date = new Date(value.endsWith('Z') ? value : `${value}Z`);
    return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('ru-RU', { timeZone: 'Asia/Irkutsk', dateStyle: 'short', timeStyle: 'short' }).format(date);
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
