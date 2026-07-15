import { Component, OnDestroy, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  CabinetApi,
  TeamMember,
  TeamResponse,
  WorkerNetworkViolationDetail,
  WorkerNetworkViolationStats
} from '../../core/cabinet.api';
import { DailyWorkProgress } from '../../core/daily-progress';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { DailyProgressStripComponent } from '../../shared/daily-progress-strip.component';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';

type TeamRole = 'manager' | 'marketolog' | 'worker' | 'operator';
type TeamProgressMode = 'day' | 'month';
type ProgressDetailTone = 'good' | 'warn' | 'neutral';
type ProgressDetailRow = {
  label: string;
  value: string;
  tone: ProgressDetailTone;
  hint: string;
};

type EfficiencyBadge = {
  value: number;
  hint: string;
};

type StatRow = {
  label: string;
  value: string;
  hint?: string;
  tone?: 'neutral' | 'good' | 'warn' | 'danger';
};

type TeamSection = {
  key: TeamRole;
  title: string;
  icon: string;
};

@Component({
  selector: 'app-team',
  imports: [AdminLayoutComponent, DailyProgressStripComponent, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './team.component.html',
  styleUrl: './team.component.scss'
})
export class TeamComponent implements OnDestroy {
  readonly selectedDate = signal(this.todayIso());
  readonly selectedMonth = signal(this.currentMonthIso());
  readonly progressMode = signal<TeamProgressMode>('day');
  readonly team = signal<TeamResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  private midnightRefreshTimer: ReturnType<typeof setTimeout> | null = null;

  readonly sections: TeamSection[] = [
    { key: 'manager', title: 'Менеджеры', icon: 'groups' },
    { key: 'marketolog', title: 'Маркетологи', icon: 'campaign' },
    { key: 'worker', title: 'Работники', icon: 'engineering' },
    { key: 'operator', title: 'Операторы', icon: 'support_agent' }
  ];

  constructor(private readonly cabinetApi: CabinetApi) {
    this.load();
    this.scheduleMidnightRefresh();
  }

  ngOnDestroy(): void {
    this.clearMidnightRefresh();
  }

  load(forceRefresh = false): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getTeam(this.selectedDate(), { forceRefresh, month: this.monthParam() }).subscribe({
      next: (response) => {
        this.team.set(response);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(apiErrorDetail(error, 'Обновите данные через пару минут или обратитесь к администратору.'));
        this.loading.set(false);
      }
    });
  }

  refresh(): void {
    this.load(true);
  }

  selectDate(date: string): void {
    this.selectedDate.set(date);
    this.load();
  }

  selectMonth(month: string): void {
    this.selectedMonth.set(month || this.currentMonthIso());
    this.load(true);
  }

  selectProgressMode(mode: TeamProgressMode): void {
    this.progressMode.set(mode);
  }

  members(section: TeamSection): TeamMember[] {
    const team = this.team();
    if (!team) {
      return [];
    }

    switch (section.key) {
      case 'manager':
        return team.managers;
      case 'marketolog':
        return team.marketologs;
      case 'worker':
        return team.workers;
      case 'operator':
        return team.operators;
    }
  }

  memberProgress(member: TeamMember): DailyWorkProgress | null | undefined {
    return this.progressMode() === 'month' ? member.monthlyProgress : member.dailyProgress;
  }

  statRows(role: TeamRole, member: TeamMember): StatRow[] {
    if (role === 'manager') {
      if (!this.hasStats(member)) {
        return [];
      }
      return [
        { label: 'ЗП', value: this.money(member.sum1Month) },
        { label: 'Выручка', value: this.money(member.payment1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) }
      ];
    }

    if (role === 'worker') {
      const rows: StatRow[] = [
        { label: 'ЗП', value: this.money(member.sum1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) },
        { label: 'В работе', value: this.count((member.newOrder || 0) + (member.inCorrect || 0) + (member.intVigul || 0) + (member.publish || 0)) }
      ];
      const progress = this.memberProgress(member);
      if (progress?.visible) {
        if ((progress.recoveryCreatedCount || 0) > 0) {
          rows.push({
            label: 'Восст. создано',
            value: this.count(progress.recoveryCreatedCount),
            hint: this.progressMode() === 'month'
              ? 'Сколько задач восстановления создали специалисту за выбранный месяц.'
              : 'Сколько задач восстановления создали специалисту за выбранный день.'
          });
        }
        if ((progress.botChangeCount || 0) > 0) {
          rows.push({
            label: 'Смена бота',
            value: this.count(progress.botChangeCount),
            hint: this.progressMode() === 'month'
              ? 'Сколько раз специалист нажал «смена» у аккаунта за выбранный месяц.'
              : 'Сколько раз специалист нажал «смена» у аккаунта.'
          });
        }
        if ((progress.botBlockCount || 0) > 0) {
          rows.push({
            label: 'Блок бота',
            value: this.count(progress.botBlockCount),
            tone: 'warn',
            hint: this.progressMode() === 'month'
              ? 'Сколько раз специалист увёл аккаунт в блок за выбранный месяц.'
              : 'Сколько раз специалист увёл аккаунт в блок.'
          });
        }
        if ((progress.orderOverdueCount || 0) > 0) {
          rows.push({
            label: 'Проср. заказов',
            value: this.count(progress.orderOverdueCount),
            tone: 'warn',
            hint: this.progressMode() === 'month'
              ? 'Заказы, которые не были выполнены день-в-день за выбранный месяц.'
              : 'Заказы, которые не были выполнены день-в-день.'
          });
        }
      }
      const violations = this.networkViolations(member);
      if (violations?.visible && violations.episodeCount > 0) {
        rows.push({
          label: 'Нарушения сети',
          value: this.count(violations.episodeCount),
          tone: violations.severity === 'CRITICAL' ? 'danger' : 'warn',
          hint: `${this.count(violations.episodeCount)} эпизодов, ${this.count(violations.attemptCount)} попыток.`
        });
      }
      return rows.filter((row) => row.label === 'ЗП' || row.value !== this.count(0));
    }

    if (!this.hasStats(member)) {
      return [];
    }

    return [
      { label: 'ЗП', value: this.money(member.sum1Month) },
      { label: 'Новые', value: this.count(member.leadsNew) },
      { label: 'В работе', value: this.count(member.leadsInWork) },
      { label: 'Конверсия', value: `${member.percentInWork || 0}%` }
    ];
  }

  statusRows(member: TeamMember): Array<{ label: string; value: number }> {
    return [
      { label: 'Новые', value: member.newOrder || 0 },
      { label: 'Коррекция', value: member.inCorrect || 0 },
      { label: 'Выгул', value: member.intVigul || 0 },
      { label: 'Публикация', value: member.publish || 0 }
    ].filter((row) => row.value > 0);
  }

  progressLabel(role: TeamRole): string {
    if (this.progressMode() === 'month') {
      return role === 'manager' ? 'Команда' : 'Месяц';
    }
    return role === 'manager' ? 'Команда' : 'Сегодня';
  }

  networkViolations(member: TeamMember): WorkerNetworkViolationStats | null | undefined {
    return this.progressMode() === 'month'
      ? member.monthlyNetworkViolations
      : member.dailyNetworkViolations;
  }

  networkViolationReason(reason: string): string {
    switch (reason) {
      case 'NON_CELLULAR_NETWORK':
        return 'Домашняя сеть или Wi-Fi';
      case 'VPN_PROXY_OR_DATACENTER':
        return 'VPN, прокси или анонимная сеть';
      case 'DESKTOP_OR_UNKNOWN_DEVICE':
        return 'Компьютер или неподдерживаемое устройство';
      case 'UNKNOWN_NETWORK':
        return 'Не удалось определить сеть';
      default:
        return 'Нарушение требований подключения';
    }
  }

  networkViolationScope(scope: string): string {
    switch ((scope || '').toLowerCase()) {
      case 'nagul':
        return 'Выгул';
      case 'publish':
        return 'Публикация';
      case 'recovery':
        return 'Восстановление';
      case 'bad':
        return 'Плохие';
      case 'review':
        return 'Отзывы';
      default:
        return 'Раздел специалиста';
    }
  }

  networkViolationTime(detail: WorkerNetworkViolationDetail): string {
    const date = this.formatDateTime(detail.firstSeenAt);
    const from = this.formatTime(detail.firstSeenAt);
    const to = this.formatTime(detail.lastSeenAt);
    if (!date) {
      return '';
    }
    return !to || from === to ? date : `${date}–${to}`;
  }

  networkViolationResult(detail: WorkerNetworkViolationDetail): string {
    return detail.blocked ? 'Заблокировано' : 'Зафиксировано в режиме аудита';
  }

  progressDetails(member: TeamMember): ProgressDetailRow[] {
    const progress = this.memberProgress(member);
    if (!progress?.visible) {
      return [];
    }

    const rows: ProgressDetailRow[] = [];
    const total = Number(progress.total || 0);
    const active = Number(progress.active || 0);
    const monthMode = this.progressMode() === 'month';

    if (monthMode) {
      rows.push({
        label: 'Рабочих дней',
        value: this.formatNumber(progress.workingDays || 0),
        tone: 'neutral',
        hint: 'Сколько дневных строк вошло в месячную агрегированную статистику.'
      });
      rows.push({
        label: 'Дней закрыто',
        value: this.formatNumber(progress.checkedDays || 0),
        tone: (progress.checkedDays || 0) >= (progress.workingDays || 0) && (progress.workingDays || 0) > 0 ? 'good' : 'neutral',
        hint: 'Сколько дней месяца были закрыты полностью к моменту расчёта.'
      });
      rows.push({
        label: 'Дней 100%',
        value: this.formatNumber(progress.reached100Days || 0),
        tone: (progress.reached100Days || 0) > 0 ? 'good' : 'neutral',
        hint: 'Сколько дней за месяц специалист хотя бы раз доходил до 100% выполнения задач.'
      });
    }

    if (total > 0 || active > 0) {
      rows.push({
        label: monthMode ? 'Остаток сумма' : 'Осталось',
        value: this.formatNumber(active),
        tone: active > 0 ? 'warn' : 'good',
        hint: monthMode
          ? 'Сумма дневных остатков по агрегированным дням месяца.'
          : 'Сколько активных карточек ещё нужно закрыть, чтобы прогресс дошёл до 100%.'
      });
    }

    if (total > 0) {
      rows.push({
        label: 'Нагрузка',
        value: this.formatNumber(total),
        tone: 'neutral',
        hint: monthMode
          ? 'Сумма месячной нагрузки: закрытые задачи + дневные остатки.'
          : 'Всего карточек в расчёте за день: закрытые + активные сейчас.'
      });

      if (!monthMode) {
        rows.push({
          label: '100% достигал',
          value: progress.reached100 ? 'Да' : 'Нет',
          tone: progress.reached100 ? 'good' : 'neutral',
          hint: 'Показывает, доходил ли специалист хотя бы один раз за выбранный день до 100% выполнения задач. Не сбрасывается, если позже пришли новые карточки.'
        });
      }
    }

    const firstReached100At = this.formatTime(progress.firstReached100At);
    if (!monthMode && firstReached100At) {
      rows.push({
        label: 'Первый 100%',
        value: firstReached100At,
        tone: 'good',
        hint: 'Время, когда специалист впервые за день закрыл все доступные карточки.'
      });
    }

    const lastReached100At = this.formatTime(progress.lastReached100At);
    if (!monthMode && lastReached100At && lastReached100At !== firstReached100At) {
      rows.push({
        label: 'Последний 100%',
        value: lastReached100At,
        tone: 'good',
        hint: 'Последнее время за день, когда специалист снова доходил до 100% после новых задач.'
      });
    }

    const periodHint = monthMode ? 'за выбранный месяц' : 'за выбранный день';
    this.pushCountRow(rows, 'Заказы закрыто', progress.orderCompletedCount, `Сколько заказов выведено из «Новые/Коррекция» ${periodHint}.`);
    this.pushCountRow(rows, 'Выгул закрыто', progress.nagulCompletedCount, `Сколько карточек выгула выполнено ${periodHint}.`);
    this.pushCountRow(rows, 'Публикаций', progress.publishCompletedCount, `Сколько отзывов опубликовано ${periodHint}.`);
    this.pushCountRow(rows, 'Плохие закрыто', progress.badCompletedCount, `Сколько задач по плохим отзывам выполнено ${periodHint}.`);
    this.pushCountRow(rows, 'Восст. закрыто', progress.recoveryCompletedCount, `Сколько задач восстановления выполнено ${periodHint}.`);
    this.pushCountRow(rows, 'Восст. создано', progress.recoveryCreatedCount, `Сколько задач восстановления назначили специалисту ${periodHint}.`);
    this.pushCountRow(rows, 'Проср. заказов', progress.orderOverdueCount, `Заказы, которые не были выполнены день-в-день ${periodHint}.`);
    this.pushCountRow(rows, 'Просрочек всего', progress.totalOverdueCount, `Все просроченные карточки в расчёте ${periodHint}.`);
    this.pushCountRow(rows, 'Смена бота', progress.botChangeCount, `Сколько раз специалист нажал «смена» у аккаунта ${periodHint}.`);
    this.pushCountRow(rows, 'Блок бота', progress.botBlockCount, `Сколько раз специалист увёл аккаунт в блок ${periodHint}.`);
    this.pushScoreRow(rows, 'Скорость', progress.speedScore, monthMode ? 'Средняя месячная оценка 0–100 по скорости закрытия задач.' : 'Оценка 0–100 по скорости закрытия после появления карточки. Ночное окно 00:00–10:00 не увеличивает время.');
    this.pushScoreRow(rows, 'Дисциплина', progress.disciplineScore, monthMode ? 'Средняя месячная оценка 0–100 по отсутствию просрочек.' : 'Оценка 0–100 по отсутствию просрочек.');
    this.pushScoreRow(rows, 'Нагрузка', progress.workloadScore, monthMode ? 'Средняя месячная оценка 0–100 по объёму выполненных задач.' : 'Оценка 0–100 по объёму выполненных задач относительно дневного норматива.');

    this.pushDurationRow(
      rows,
      'Активно',
      progress.activeWorkSeconds,
      monthMode
        ? 'Сумма примерного активного рабочего времени за выбранный месяц.'
        : 'Примерное активное рабочее время: сумма сессий по действиям. Пауза больше 15 минут начинает новую сессию.'
    );

    const activityWindow = this.formatTimeWindow(progress.firstActivityAt, progress.lastActivityAt);
    if (activityWindow) {
      rows.push({
        label: 'Окно',
        value: activityWindow,
        tone: 'neutral',
        hint: 'Промежуток между первым и последним действием за день. Это не равно фактическому рабочему времени.'
      });
    }

    if ((progress.activityEvents || 0) > 0) {
      rows.push({
        label: 'Действий',
        value: this.formatNumber(progress.activityEvents),
        tone: 'neutral',
        hint: 'Количество зафиксированных действий сотрудника в системе за день.'
      });
    }

    this.pushDurationRow(rows, 'Медиана', progress.medianCloseSeconds, monthMode ? 'Средняя месячная медиана скорости закрытия задач.' : 'Типичная скорость закрытия: половина задач закрыта быстрее этого времени.');
    this.pushDurationRow(rows, 'Среднее', progress.averageCloseSeconds, monthMode ? 'Среднее время закрытия задачи по агрегированным дням месяца.' : 'Среднее время закрытия задачи за день.');
    this.pushDurationRow(rows, 'P90', progress.p90CloseSeconds, monthMode ? 'Средний месячный P90 по скорости закрытия задач.' : '90% задач закрыты быстрее этого времени. Помогает увидеть длинные задержки.');

    const firstCompletedAt = this.formatTime(progress.firstCompletedAt);
    if (firstCompletedAt) {
      rows.push({
        label: 'Первое закрытие',
        value: firstCompletedAt,
        tone: 'neutral',
        hint: 'Время первой закрытой карточки за день.'
      });
    }

    const lastCompletedAt = this.formatTime(progress.lastCompletedAt);
    if (lastCompletedAt) {
      rows.push({
        label: 'Последнее',
        value: lastCompletedAt,
        tone: 'neutral',
        hint: 'Время последней закрытой карточки за день.'
      });
    }

    return rows;
  }

  efficiencyBadge(member: TeamMember): EfficiencyBadge | null {
    const progress = this.memberProgress(member);
    if (!progress?.visible || Number(progress.total || 0) <= 0) {
      return null;
    }

    const value = this.safePercent(progress.efficiencyScore || progress.percent);
    const period = this.progressMode() === 'month' ? 'Месячная эффективность' : 'Эффективность';
    return {
      value,
      hint: `${period} ${value} из 100: 35% прогресс, 35% скорость закрытия после появления карточки, 20% дисциплина по просрочкам, 10% нагрузка. Ночное окно 00:00–10:00 не считается просрочкой.`
    };
  }

  progressSummary(member: TeamMember): string {
    const progress = this.memberProgress(member);
    if (!progress?.visible) {
      return '';
    }

    if (this.progressMode() === 'month') {
      if ((progress.workingDays || 0) <= 0) {
        return 'Нет месячных данных';
      }
      const reached = this.formatNumber(progress.reached100Days || 0);
      const working = this.formatNumber(progress.workingDays || 0);
      const median = this.formatDuration(progress.medianCloseSeconds);
      return median
        ? `100%: ${reached}/${working} дн. · медиана ${median}`
        : `100%: ${reached}/${working} дн.`;
    }

    if ((progress.total || 0) <= 0) {
      return 'Нет задач за день';
    }

    const base = (progress.active || 0) > 0
      ? `Осталось ${this.formatNumber(progress.active)}`
      : 'День закрыт';
    const median = this.formatDuration(progress.medianCloseSeconds);
    const activeWork = this.formatDuration(progress.activeWorkSeconds);
    if ((progress.active || 0) > 0 && progress.reached100) {
      return `${base} · 100% был`;
    }
    if ((progress.orderOverdueCount || 0) > 0) {
      return `${base} · проср. заказов ${this.formatNumber(progress.orderOverdueCount)}`;
    }
    if (median) {
      return `${base} · медиана ${median}`;
    }
    return activeWork ? `${base} · активно ${activeWork}` : base;
  }

  imageUrl(imageId?: number | null): string {
    return this.cabinetApi.imageUrl(imageId);
  }

  editUrl(userId: number): string {
    return `/admin/users?userId=${userId}`;
  }

  addUserUrl(): string {
    return '/admin/users/new';
  }

  memberTrack(member: TeamMember): number {
    return member.userId;
  }

  private hasStats(member: TeamMember): boolean {
    return [
      member.sum1Month,
      member.order1Month,
      member.review1Month,
      member.payment1Month,
      member.leadsNew,
      member.leadsInWork,
      member.newOrder,
      member.inCorrect,
      member.intVigul,
      member.publish
    ].some((value) => Number(value || 0) > 0);
  }

  private money(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} руб.`;
  }

  private count(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU').format(value || 0)} шт.`;
  }

  private pushDurationRow(rows: ProgressDetailRow[], label: string, seconds: number | null | undefined, hint: string): void {
    const value = this.formatDuration(seconds);
    if (value) {
      rows.push({ label, value, tone: 'neutral', hint });
    }
  }

  private pushCountRow(rows: ProgressDetailRow[], label: string, count: number | null | undefined, hint: string): void {
    const value = Number(count || 0);
    if (value > 0) {
      rows.push({
        label,
        value: this.formatNumber(value),
        tone: label.toLowerCase().includes('проср') ? 'warn' : 'neutral',
        hint
      });
    }
  }

  private pushScoreRow(rows: ProgressDetailRow[], label: string, score: number | null | undefined, hint: string): void {
    const value = Number(score || 0);
    if (value > 0) {
      rows.push({
        label,
        value: `${this.safePercent(value)}`,
        tone: value >= 90 ? 'good' : value < 60 ? 'warn' : 'neutral',
        hint
      });
    }
  }

  private formatDuration(seconds?: number | null): string {
    const raw = Number(seconds || 0);
    if (!Number.isFinite(raw) || raw <= 0) {
      return '';
    }

    const totalSeconds = Math.max(1, Math.round(raw));
    if (totalSeconds < 60) {
      return '< 1 мин';
    }

    const totalMinutes = Math.max(1, Math.round(totalSeconds / 60));
    if (totalMinutes < 60) {
      return `${totalMinutes} мин`;
    }

    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    if (hours < 24) {
      return minutes > 0 ? `${hours} ч ${minutes} мин` : `${hours} ч`;
    }

    const days = Math.floor(hours / 24);
    const restHours = hours % 24;
    return restHours > 0 ? `${days} д ${restHours} ч` : `${days} д`;
  }

  private formatTime(value?: string | null): string {
    if (!value) {
      return '';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }

    return new Intl.DateTimeFormat('ru-RU', {
      hour: '2-digit',
      minute: '2-digit'
    }).format(date);
  }

  private formatTimeWindow(from?: string | null, to?: string | null): string {
    const fromTime = this.formatTime(from);
    const toTime = this.formatTime(to);
    if (!fromTime && !toTime) {
      return '';
    }
    if (!toTime || fromTime === toTime) {
      return fromTime;
    }
    if (!fromTime) {
      return toTime;
    }
    return `${fromTime}–${toTime}`;
  }

  private safePercent(value?: number | null): number {
    const raw = Number(value || 0);
    if (!Number.isFinite(raw)) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round(raw)));
  }

  private formatNumber(value?: number | null): string {
    return new Intl.NumberFormat('ru-RU').format(value || 0);
  }

  private formatDateTime(value?: string | null): string {
    if (!value) {
      return '';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    return new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(date);
  }

  private todayIso(): string {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private currentMonthIso(): string {
    return this.todayIso().slice(0, 7);
  }

  private monthParam(): string {
    const value = this.selectedMonth() || this.currentMonthIso();
    return `${value}-01`;
  }

  private scheduleMidnightRefresh(): void {
    this.clearMidnightRefresh();

    const now = new Date();
    const scheduledToday = this.todayIso();
    const scheduledMonth = scheduledToday.slice(0, 7);
    const nextMidnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 2, 0);
    const delay = Math.max(1_000, nextMidnight.getTime() - now.getTime());

    this.midnightRefreshTimer = setTimeout(() => {
      const newToday = this.todayIso();
      const newMonth = this.currentMonthIso();
      let shouldRefresh = false;

      if (this.selectedDate() === scheduledToday) {
        this.selectedDate.set(newToday);
        shouldRefresh = true;
      }

      if (this.selectedMonth() === scheduledMonth) {
        this.selectedMonth.set(newMonth);
        shouldRefresh = true;
      }

      if (shouldRefresh) {
        this.load(true);
      }

      this.scheduleMidnightRefresh();
    }, delay);
  }

  private clearMidnightRefresh(): void {
    if (this.midnightRefreshTimer !== null) {
      clearTimeout(this.midnightRefreshTimer);
      this.midnightRefreshTimer = null;
    }
  }
}
