import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonContent, IonRefresher, IonRefresherContent, type RefresherCustomEvent } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  WorkloadShadowReadApi,
  type WorkloadCollection,
  type WorkloadEmergencyAssignment,
  type WorkloadEvent,
  type WorkloadExecution,
  type WorkloadHealth,
  type WorkloadLiveSettings,
  type WorkloadMonitorSummary,
  type WorkloadProposal,
  type WorkloadReadiness,
  type WorkloadShadowSettings,
  type WorkloadWorker,
  type WorkloadWorkflow
} from '../core/workload-shadow.api';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

type WorkloadSection = 'overview' | 'workers' | 'proposals' | 'events' | 'live';
type ReadinessTarget = 'CANARY' | 'LIVE';

/** Monitoring deliberately exposes GET endpoints only. LIVE controls remain on the guarded web screen. */
@Component({
  selector: 'app-admin-workload-shadow-page',
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Нагрузка" />
      <ion-content fullscreen>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)"><ion-refresher-content /></ion-refresher>
        <main class="workload-page">
          <header class="page-head">
            <div><small>АДМИН / НАГРУЗКА</small><h1>Мониторинг нагрузки</h1></div>
            <button type="button" aria-label="Обновить монитор" (click)="load()" [disabled]="loading()"><span class="material-icons-sharp">refresh</span></button>
          </header>

          <p class="view-note">Монитор только показывает расчёты и состояние передач. Изменение настроек, запуск и откат передач доступны в защищённой веб-панели.</p>
          @if (error()) { <p class="alert" role="alert">{{ error() }} <button type="button" (click)="load()">Повторить</button></p> }
          @if (loading() && !summary()) { <p class="empty">Загружаю монитор…</p> }

          <section class="card mode-card" aria-label="Режим нагрузки">
            <div><small>Расчёт</small><strong>{{ modeLabel(settings()?.mode ?? summary()?.mode) }}</strong></div>
            <div><small>Передачи</small><strong>{{ modeLabel(liveSettings()?.mode) }}</strong></div>
            <p>{{ liveSettings()?.applyEnabled ? 'Защищённое применение включено; проверяйте цепочки и журнал передач.' : 'Автоматические передачи не применяются.' }}</p>
            @if (summary()?.updatedAt) { <small>Обновлено {{ date(summary()?.updatedAt) }}</small> }
          </section>

          <label class="field manager-filter"><span>Менеджер</span>
            <select [ngModel]="managerId()" (ngModelChange)="selectManager($event)" aria-label="Фильтр по менеджеру">
              <option [ngValue]="null">Все менеджеры</option>
              @for (manager of managers(); track manager.managerId) { <option [ngValue]="manager.managerId">{{ manager.managerName }}</option> }
            </select>
          </label>

          <nav class="sections" aria-label="Разделы монитора">
            <button type="button" [class.active]="section() === 'overview'" (click)="section.set('overview')">Обзор</button>
            <button type="button" [class.active]="section() === 'workers'" (click)="section.set('workers')">Люди · {{ workers().length }}</button>
            <button type="button" [class.active]="section() === 'proposals'" (click)="section.set('proposals')">Предложения · {{ proposals().length }}</button>
            <button type="button" [class.active]="section() === 'events'" (click)="section.set('events')">События</button>
            <button type="button" [class.active]="section() === 'live'" (click)="section.set('live')">Передачи</button>
          </nav>

          @if (section() === 'overview') {
            @if (summary(); as data) {
              <section class="metrics" aria-label="Сводные показатели">
                <article><small>Специалистов</small><strong>{{ managerId() == null ? (data.workerCount ?? workers().length) : workers().length }}</strong></article>
                <article><small>Сегодня 100%</small><strong>{{ managerId() == null ? (data.workersAt100 ?? data.hundredPercentWorkers ?? 0) : workersAt100() }}</strong></article>
                <article><small>В зоне риска</small><strong>{{ managerId() == null ? (data.riskWorkers ?? data.atRiskWorkerCount ?? 0) : riskWorkers() }}</strong></article>
                <article><small>Кандидатов</small><strong>{{ eligibleWorkers() }}</strong></article>
                <article><small>Предложений</small><strong>{{ managerId() == null ? (data.pendingProposals ?? data.transferCaseCount ?? 0) : proposals().length }}</strong></article>
                <article><small>Поздних вне %</small><strong>{{ managerId() == null ? (data.lateExcludedUnits ?? 0) : lateUnits() }}</strong></article>
              </section>
              @if (data.progressDate) { <p class="hint">Дата расчёта: {{ data.progressDate }}. Текущий незавершённый процент служит для мониторинга; допуск получателя определяется по завершённым дням.</p> }
            }
            <section class="card">
              <h2>Самодиагностика <span class="badge" [attr.data-status]="health()?.status">{{ statusLabel(health()?.status) }}</span></h2>
              @if (health(); as state) {
                <div class="facts"><span>Последний расчёт</span><strong>{{ date(state.lastSuccessfulRunAt) }}</strong><span>Снимок</span><strong>{{ date(state.lastSnapshotAt) }}</strong><span>Очередь</span><strong>{{ (state.dueEvents ?? 0) + (state.processingEvents ?? 0) }}</strong><span>Недоставленных</span><strong>{{ state.deadEvents ?? 0 }}</strong><span>Ошибки графа</span><strong>{{ state.graphErrorCases ?? 0 }}</strong><span>Зависшие расчёты</span><strong>{{ state.staleRunningRuns ?? 0 }}</strong></div>
                @for (issue of state.issues ?? []; track issue.code) { <p class="issue"><strong>{{ issue.component || issue.code }} · {{ statusLabel(issue.severity) }}</strong><br>{{ issue.message }}</p> }
                @for (node of state.nodes ?? []; track node.name) { <details class="detail"><summary>{{ node.name }} · {{ statusLabel(node.status) }}</summary><p>{{ node.message || 'Описание отсутствует' }}</p></details> }
              } @else { <p class="hint">Данные самодиагностики недоступны.</p> }
            </section>
            @if (visibleManagers().length) {
              <section class="card"><h2>Менеджеры</h2>
                @for (manager of visibleManagers(); track manager.managerId) {
                  <div class="manager-row"><strong>{{ manager.managerName }}</strong><small>{{ manager.progressPercent ?? '—' }}% · 100%: {{ manager.workersAt100 ?? manager.hundredPercentWorkers ?? 0 }}/{{ manager.workerCount }} · предложений: {{ manager.transferCaseCount ?? manager.pendingProposals ?? 0 }}{{ manager.staffingRequired ? ' · нужен сотрудник' : '' }}</small></div>
                }
              </section>
            }
          }

          @if (section() === 'workers') {
            <label class="field"><span>Найти специалиста</span><input type="search" [ngModel]="workerSearch()" (ngModelChange)="workerSearch.set($event || '')" placeholder="Имя, логин, менеджер"></label>
            <p class="hint">Допуск рассчитывается по последнему завершённому дню и завершённым дням с начала месяца. Текущий процент сам по себе не исключает кандидата.</p>
            @for (worker of visibleWorkers(); track worker.workerId) {
              <article class="card">
                <header class="card-head"><h2>{{ worker.workerName }}</h2><span class="badge" [attr.data-status]="eligible(worker) ? 'UP' : 'WARNING'">{{ eligible(worker) ? 'Кандидат' : 'Вне отбора' }}</span></header>
                <p class="sub">{{ worker.managerName || 'Менеджер не указан' }}</p>
                <div class="facts"><span>Сегодня</span><strong>{{ percent(worker) }}%</strong><span>Последний завершённый день</span><strong>{{ worker.lastDayReached100 == null ? 'нет данных' : worker.lastDayReached100 ? '100%' : 'не 100%' }}</strong><span>Дни 100% / проверено</span><strong>{{ worker.hundredPercentDays ?? 0 }} / {{ evaluatedDays(worker) }}</strong><span>Неуспешных дней</span><strong>{{ worker.failureDays ?? 0 }}</strong><span>Готово / активно</span><strong>{{ worker.completedUnits ?? 0 }} / {{ worker.activeUnits ?? 0 }}</strong><span>Заблокировано / позднее вне %</span><strong>{{ worker.blockedUnits ?? 0 }} / {{ worker.lateExcludedUnits ?? 0 }}</strong></div>
                @if (worker.distributionRank) { <p class="hint">Очередь получения: №{{ worker.distributionRank }}</p> }
                @for (reason of worker.reasons ?? []; track reason) { <p class="issue">{{ reason }}</p> }
                @if (!worker.reasons?.length && !eligible(worker)) { <p class="hint">{{ worker.distributionStatus || worker.diagnosticStatus || 'Не проходит текущие пороги отбора' }}</p> }
              </article>
            } @empty { <p class="empty">По фильтру специалистов нет.</p> }
          }

          @if (section() === 'proposals') {
            <p class="hint">Предложения показывают расчёт системы. Они не означают состоявшуюся передачу.</p>
            @for (proposal of proposals(); track proposal.id) {
              <article class="card">
                <header class="card-head"><h2>{{ proposal.sourceWorkerName || 'Источник не указан' }}</h2><span class="badge">{{ statusLabel(proposal.status) }}</span></header>
                <p class="sub">{{ proposal.managerName || 'Без менеджера' }} · {{ proposal.companyTitle || 'Компания не указана' }}</p>
                <div class="facts"><span>Передать</span><strong>{{ proposal.targetPercent ?? proposal.transferPercent ?? '—' }}%</strong><span>Проблемных единиц</span><strong>{{ proposal.problemUnits ?? 0 }}</strong><span>Рекомендован</span><strong>{{ proposal.recommendedWorkerName || proposal.fallbackWorkerName || 'Кандидат не выбран' }}</strong><span>Целостность</span><strong>{{ proposal.graphErrorCount ?? 0 }} ошибок / {{ proposal.graphWarningCount ?? 0 }} предупреждений</strong></div>
                @if (proposal.staffingRequired) { <p class="issue">Нужен дополнительный сотрудник.</p> }
                @if (proposal.reason) { <p>{{ proposal.reason }}</p> }
                @if (proposal.companies?.length) { <details class="detail"><summary>Компании · {{ proposal.companies?.length }}</summary>@for (company of proposal.companies ?? []; track company.companyId) { <p>{{ company.companyName }} · {{ company.activeUnitCount ?? 0 }} ед.</p> }</details> }
                @if (proposal.candidates?.length) { <details class="detail"><summary>Кандидаты · {{ proposal.candidates?.length }}</summary>@for (candidate of proposal.candidates ?? []; track candidate.workerId) { <p>{{ candidate.workerName }} · {{ statusLabel(candidate.simulatedOfferStatus) }}{{ candidate.workerGroupConnected === false ? ' · группа не подключена' : '' }}</p> }</details> }
              </article>
            } @empty { <p class="empty">Предложений по текущему фильтру нет.</p> }
          }

          @if (section() === 'events') {
            <p class="hint">События показываются по всему контуру, независимо от выбранного менеджера.</p>
            @for (event of events(); track event.id) {
              <article class="card"><header class="card-head"><h2>{{ event.eventType }}</h2><span class="badge" [attr.data-status]="event.severity">{{ statusLabel(event.severity) }}</span></header><p>{{ event.message }}</p><small>{{ event.managerName || event.workerName || 'Система' }}{{ event.companyName || event.companyTitle ? ' · ' + (event.companyName || event.companyTitle) : '' }} · {{ date(event.createdAt ?? event.lastSeenAt) }}</small>@if (event.deliveryStatus) { <p class="hint">Доставка: {{ statusLabel(event.deliveryStatus) }}{{ event.occurrenceCount ? ' · повторов: ' + event.occurrenceCount : '' }}</p> }</article>
            } @empty { <p class="empty">Событий пока нет.</p> }
          }

          @if (section() === 'live') {
            <section class="card"><h2>Защищённый контур</h2>
              @if (liveSettings(); as live) { <div class="facts"><span>Режим</span><strong>{{ modeLabel(live.mode) }}</strong><span>Применение</span><strong>{{ live.applyEnabled ? 'включено' : 'выключено' }}</strong><span>Старт истории</span><strong>{{ live.historyStartDate }}</strong><span>Завершённых дней</span><strong>{{ live.minFinalizedDays }}</strong><span>Стабильность</span><strong>{{ live.stableHours }} ч</strong><span>CANARY менеджеры</span><strong>{{ live.canaryManagerIds.join(', ') || 'не указаны' }}</strong><span>Версия настроек</span><strong>{{ live.revision }}</strong></div> }
              @if (settings(); as shadow) { <p class="hint">Наблюдение: {{ shadow.observationEnabled ? 'включено' : 'выключено' }} · уведомления: {{ shadow.groupNotificationsEnabled ? 'включены' : 'выключены' }} · смена {{ shadow.shiftStart }}–{{ shadow.shiftEnd }} ({{ shadow.businessZone }}).</p> }
            </section>
            <section class="card"><h2>Готовность запуска</h2>
              <div class="actions"><button type="button" [class.active]="readinessTarget() === 'CANARY'" (click)="inspectReadiness('CANARY')" [disabled]="readinessLoading()">Проверить CANARY</button><button type="button" [class.active]="readinessTarget() === 'LIVE'" (click)="inspectReadiness('LIVE')" [disabled]="readinessLoading()">Проверить LIVE</button></div>
              @if (readinessLoading()) { <p class="hint">Проверяю…</p> }
              @if (readiness(); as report) { <p class="badge" [attr.data-status]="report.ready ? 'UP' : 'WARNING'">{{ report.targetMode }}: {{ report.ready ? 'готово' : 'не готово' }}</p>@for (check of report.checks; track check.code) { <div class="readiness-check"><strong>{{ check.code }} · {{ check.status }}</strong><small>{{ check.message }}</small></div> } }
            </section>
            <section class="card"><h2>Цепочки предложений · {{ workflows().length }}</h2>@for (row of workflows(); track row.id) { <details class="detail"><summary>{{ row.companyTitle }} · {{ statusLabel(row.status) }}</summary><p>{{ row.sourceWorkerName }} → {{ row.targetWorkerName || 'получатель не выбран' }} · {{ row.managerName }}</p><small>Режим {{ modeLabel(row.mode) }} · обновлено {{ date(row.lastTransitionAt) }}</small>@if (row.ownerConfirmationRequired) { <p class="issue">Ожидается подтверждение владельца.</p> }@if (row.lastErrorMessage) { <p class="issue">{{ row.lastErrorMessage }}</p> }</details> } @empty { <p class="hint">Цепочек нет.</p> }</section>
            <section class="card"><h2>Журнал передач · {{ executions().length }}</h2>@for (row of executions(); track row.id) { <details class="detail"><summary>{{ row.companyTitle }} · {{ statusLabel(row.status) }}</summary><p>{{ row.sourceWorkerName }} → {{ row.targetWorkerName }} · {{ row.managerName }}</p><small>Заказов: {{ row.orderCount }} · применено {{ date(row.appliedAt) }} · окно отката до {{ date(row.rollbackDeadlineAt) }}</small>@if (row.errorMessage) { <p class="issue">{{ row.errorMessage }}</p> }</details> } @empty { <p class="hint">Передач нет.</p> }</section>
            <section class="card"><h2>Аварийные назначения · {{ emergencyAssignments().length }}</h2>@for (row of emergencyAssignments(); track row.id) { <details class="detail"><summary>{{ row.companyTitle }} · {{ statusLabel(row.status) }}</summary><p>{{ row.sourceWorkerName }} ({{ row.sourceManagerName }}) → {{ row.targetWorkerName }} ({{ row.targetManagerName }})</p><small>{{ row.reason }} · применено {{ date(row.appliedAt) }}</small>@if (row.lastError) { <p class="issue">{{ row.lastError }}</p> }</details> } @empty { <p class="hint">Аварийных назначений нет.</p> }</section>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    :host { display: contents; }
    ion-content { --background: var(--otziv-background); }
    .workload-page { display:grid; gap:.8rem; max-width:44rem; margin:0 auto; padding:1rem 1rem calc(1.5rem + env(safe-area-inset-bottom)); color:var(--otziv-dark); }
    .page-head, .card-head { display:flex; align-items:flex-start; justify-content:space-between; gap:.5rem; }
    .page-head h1 { margin:.12rem 0 0; font-size:1.2rem; }
    .page-head small, .card small, .field span, .hint { color:var(--otziv-info); font-size:.7rem; }
    .page-head button { min-width:2.75rem; min-height:2.75rem; border:1px solid rgba(103,116,131,.2); border-radius:.7rem; background:var(--otziv-white); }
    .view-note, .hint, .alert, .empty { margin:0; line-height:1.4; font-size:.76rem; }
    .view-note { border-radius:.7rem; padding:.7rem; background:rgba(103,116,131,.1); }
    .card, .empty { border:1px solid rgba(103,116,131,.17); border-radius:.9rem; padding:.75rem; background:var(--otziv-white); }
    .card { display:grid; gap:.55rem; min-width:0; }
    .card h2 { margin:0; font-size:.94rem; }
    .card p { margin:0; font-size:.76rem; line-height:1.4; }
    .mode-card { grid-template-columns:1fr 1fr; }.mode-card > div { display:grid; gap:.12rem; }.mode-card > p, .mode-card > small { grid-column:1/-1; }
    .mode-card strong { font-size:.95rem; }
    .field { display:grid; gap:.3rem; }
    .field span { font-weight:800; }
    input, select { width:100%; min-height:2.75rem; border:1px solid rgba(103,116,131,.25); border-radius:.65rem; padding:.55rem; background:var(--otziv-white); color:var(--otziv-dark); font:inherit; }
    button { min-height:2.55rem; border:1px solid rgba(103,116,131,.25); border-radius:.65rem; padding:.45rem .65rem; background:var(--otziv-white); color:var(--otziv-dark); font:inherit; font-size:.72rem; font-weight:800; }
    button:disabled { opacity:.55; }
    .sections { display:flex; gap:.35rem; overflow-x:auto; padding-bottom:.15rem; }
    .sections button { flex:0 0 auto; }.sections button.active, .actions button.active { color:#fff; background:var(--otziv-primary); border-color:var(--otziv-primary); }
    .metrics { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.45rem; }
    .metrics article { display:grid; gap:.15rem; border:1px solid rgba(103,116,131,.17); border-radius:.7rem; padding:.65rem; background:var(--otziv-white); }
    .metrics small { color:var(--otziv-info); font-size:.67rem; }.metrics strong { font-size:1.2rem; }
    .facts { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:.35rem .5rem; align-items:start; font-size:.72rem; }
    .facts > span { color:var(--otziv-info); }.facts > strong { overflow-wrap:anywhere; text-align:right; }
    .badge { display:inline-flex; align-items:center; width:max-content; max-width:100%; border-radius:999px; padding:.22rem .5rem; background:rgba(103,116,131,.14); color:var(--otziv-dark); font-size:.65rem; font-weight:900; }
    .badge[data-status="UP"], .badge[data-status="PASS"] { background:rgba(31,155,116,.15); color:#16795c; }
    .badge[data-status="CRITICAL"], .badge[data-status="DEGRADED"] { background:rgba(230,50,78,.15); color:#b02040; }
    .badge[data-status="WARNING"], .badge[data-status="STALE"] { background:rgba(235,174,32,.18); color:#80600c; }
    .manager-row, .readiness-check { display:grid; gap:.12rem; padding:.45rem 0; border-top:1px solid rgba(103,116,131,.12); font-size:.74rem; }
    .manager-row small, .readiness-check small { line-height:1.35; }.sub { color:var(--otziv-info); }
    .issue { padding:.5rem; border-radius:.55rem; background:rgba(230,50,78,.1); color:#a6203e; overflow-wrap:anywhere; }
    .detail { border-top:1px solid rgba(103,116,131,.16); padding:.5rem 0; font-size:.74rem; }.detail summary { cursor:pointer; font-weight:800; }.detail p { margin:.4rem 0 0; overflow-wrap:anywhere; }
    .actions { display:flex; gap:.4rem; flex-wrap:wrap; }.alert { padding:.65rem; border-radius:.7rem; background:rgba(230,50,78,.12); color:#a6203e; }.alert button { min-height:2rem; margin-left:.3rem; }
  `]
})
export class AdminWorkloadShadowPage implements OnInit, OnDestroy {
  private readonly api = inject(WorkloadShadowReadApi);
  readonly section = signal<WorkloadSection>('overview');
  readonly managerId = signal<number | null>(null);
  readonly workerSearch = signal('');
  readonly settings = signal<WorkloadShadowSettings | null>(null);
  readonly liveSettings = signal<WorkloadLiveSettings | null>(null);
  readonly summary = signal<WorkloadMonitorSummary | null>(null);
  readonly health = signal<WorkloadHealth | null>(null);
  readonly workers = signal<WorkloadWorker[]>([]);
  readonly proposals = signal<WorkloadProposal[]>([]);
  readonly events = signal<WorkloadEvent[]>([]);
  readonly readiness = signal<WorkloadReadiness | null>(null);
  readonly readinessTarget = signal<ReadinessTarget>('CANARY');
  readonly readinessLoading = signal(false);
  readonly workflows = signal<WorkloadWorkflow[]>([]);
  readonly executions = signal<WorkloadExecution[]>([]);
  readonly emergencyAssignments = signal<WorkloadEmergencyAssignment[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly managers = computed(() => this.summary()?.managers ?? []);
  readonly visibleManagers = computed(() => this.managers().filter((manager) => this.managerId() == null || manager.managerId === this.managerId()));
  readonly visibleWorkers = computed(() => {
    const search = this.workerSearch().trim().toLocaleLowerCase('ru');
    return !search ? this.workers() : this.workers().filter((worker) =>
      [worker.workerName, worker.username, worker.managerName, ...(worker.reasons ?? [])]
        .some((value) => String(value ?? '').toLocaleLowerCase('ru').includes(search)));
  });
  readonly workersAt100 = computed(() => this.workers().filter((worker) => this.percent(worker) >= 100).length);
  readonly riskWorkers = computed(() => this.workers().filter((worker) => Number(worker.transferStage ?? 0) > 0).length);
  readonly eligibleWorkers = computed(() => this.workers().filter((worker) => this.eligible(worker)).length);
  readonly lateUnits = computed(() => this.workers().reduce((sum, worker) => sum + Number(worker.lateExcludedUnits ?? 0), 0));
  private requestEpoch = 0;
  private readinessEpoch = 0;
  private destroyed = false;

  ngOnInit(): void { void this.load(); }
  ngOnDestroy(): void { this.destroyed = true; this.requestEpoch += 1; this.readinessEpoch += 1; }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    try { await this.load(); } finally { await event.target.complete(); }
  }

  selectManager(value: number | null): void {
    const next = value == null ? null : Number(value);
    this.managerId.set(next != null && Number.isSafeInteger(next) && next > 0 ? next : null);
    void this.load();
  }

  async load(): Promise<void> {
    const epoch = ++this.requestEpoch;
    const manager = this.managerId();
    this.loading.set(true);
    this.error.set(null);
    const labels = ['настройки наблюдения', 'режим передач', 'сводка', 'самодиагностика', 'специалисты', 'предложения', 'события', 'цепочки', 'журнал передач', 'аварийные назначения'];
    const results = await Promise.allSettled([
      firstValueFrom(this.api.settings()), firstValueFrom(this.api.liveSettings()),
      firstValueFrom(this.api.summary()), firstValueFrom(this.api.health()),
      firstValueFrom(this.api.workers(manager)), firstValueFrom(this.api.proposals(manager)),
      firstValueFrom(this.api.events(50)), firstValueFrom(this.api.workflows(manager)),
      firstValueFrom(this.api.executions(manager)), firstValueFrom(this.api.emergencyAssignments(manager))
    ] as const);
    if (this.destroyed || epoch !== this.requestEpoch) return;
    const [settings, live, summary, health, workers, proposals, events, workflows, executions, emergency] = results;
    if (settings.status === 'fulfilled') this.settings.set(settings.value);
    if (live.status === 'fulfilled') this.liveSettings.set(live.value);
    if (summary.status === 'fulfilled') this.summary.set(summary.value);
    if (health.status === 'fulfilled') this.health.set(health.value);
    if (workers.status === 'fulfilled') this.workers.set(this.collectionItems(workers.value));
    if (proposals.status === 'fulfilled') this.proposals.set(this.collectionItems(proposals.value));
    if (events.status === 'fulfilled') this.events.set(this.collectionItems(events.value));
    if (workflows.status === 'fulfilled') this.workflows.set(workflows.value);
    if (executions.status === 'fulfilled') this.executions.set(executions.value);
    if (emergency.status === 'fulfilled') this.emergencyAssignments.set(emergency.value);
    const failed = results.flatMap((result, index) => result.status === 'rejected' ? [labels[index]] : []);
    if (failed.length) this.error.set(`Часть данных не обновилась: ${failed.join(', ')}.`);
    this.loading.set(false);
    if (live.status === 'fulfilled') {
      const target = live.value.mode === 'LIVE' ? 'LIVE' : 'CANARY';
      void this.inspectReadiness(target);
    }
  }

  async inspectReadiness(target: ReadinessTarget): Promise<void> {
    const epoch = ++this.readinessEpoch;
    this.readinessTarget.set(target);
    this.readinessLoading.set(true);
    this.readiness.set(null);
    try {
      const report = await firstValueFrom(this.api.readiness(target));
      if (!this.destroyed && epoch === this.readinessEpoch) this.readiness.set(report);
    } catch {
      if (!this.destroyed && epoch === this.readinessEpoch) this.error.set(`Не удалось проверить готовность режима ${target}.`);
    } finally {
      if (epoch === this.readinessEpoch) this.readinessLoading.set(false);
    }
  }

  percent(worker: WorkloadWorker): number {
    return Math.max(0, Math.min(100, Number(worker.currentPercent ?? worker.progressPercent ?? worker.predictedPercent ?? 0)));
  }
  eligible(worker: WorkloadWorker): boolean { return worker.eligibleRecipient ?? worker.recipientEligible ?? false; }
  evaluatedDays(worker: WorkloadWorker): number { return worker.evaluatedDays ?? Number(worker.hundredPercentDays ?? 0) + Number(worker.failureDays ?? 0); }
  modeLabel(value: string | null | undefined): string {
    return ({ OFF: 'Выключено', SHADOW: 'Наблюдение', CANARY: 'Ограниченный запуск', LIVE: 'Боевой режим' } as Record<string, string>)[String(value ?? '').toUpperCase()] || value || '—';
  }
  statusLabel(value: string | null | undefined): string {
    return ({ UP: 'Норма', HEALTHY: 'Норма', WARNING: 'Предупреждение', DEGRADED: 'Проблема', CRITICAL: 'Критично', STALE: 'Устарело', PASS: 'Пройдено', FAIL: 'Не пройдено', SKIPPED: 'Только мониторинг', READY_TO_OFFER: 'Готов к предложению', OFFERED: 'Предложено', ACCEPTED: 'Принято', DECLINED: 'Отказ', EXPIRED: 'Истекло', UNAVAILABLE: 'Недоступен', STAFFING_REQUIRED: 'Нужен сотрудник', AWAITING_OWNER_CONFIRMATION: 'Ожидает владельца', APPLYING: 'Применяется', APPLIED: 'Применено', ROLLING_BACK: 'Откат выполняется', ROLLED_BACK: 'Откачено', BLOCKED: 'Заблокировано', DELIVERY_FAILED: 'Ошибка доставки', PENDING: 'Ожидает', PROCESSING: 'Обрабатывается', SENT: 'Отправлено', RETRY: 'Повторная попытка', DEAD: 'Доставка остановлена' } as Record<string, string>)[String(value ?? '').toUpperCase()] || value || '—';
  }
  date(value: string | null | undefined): string {
    if (!value) return '—';
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('ru-RU');
  }
  private collectionItems<T>(value: WorkloadCollection<T>): T[] { return Array.isArray(value) ? value : value.items ?? []; }
}
