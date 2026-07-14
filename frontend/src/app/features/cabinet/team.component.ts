import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CabinetApi, TeamMember, TeamResponse } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { DailyProgressStripComponent } from '../../shared/daily-progress-strip.component';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';

type TeamRole = 'manager' | 'marketolog' | 'worker' | 'operator';
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
export class TeamComponent {
  readonly selectedDate = signal(this.todayIso());
  readonly team = signal<TeamResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly sections: TeamSection[] = [
    { key: 'manager', title: 'Менеджеры', icon: 'groups' },
    { key: 'marketolog', title: 'Маркетологи', icon: 'campaign' },
    { key: 'worker', title: 'Работники', icon: 'engineering' },
    { key: 'operator', title: 'Операторы', icon: 'support_agent' }
  ];

  constructor(private readonly cabinetApi: CabinetApi) {
    this.load();
  }

  load(forceRefresh = false): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getTeam(this.selectedDate(), { forceRefresh }).subscribe({
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

  statRows(role: TeamRole, member: TeamMember): Array<{ label: string; value: string }> {
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
      const rows = [
        { label: 'ЗП', value: this.money(member.sum1Month) },
        { label: 'Заказы', value: this.count(member.order1Month) },
        { label: 'Отзывы', value: this.count(member.review1Month) },
        { label: 'В работе', value: this.count((member.newOrder || 0) + (member.inCorrect || 0) + (member.intVigul || 0) + (member.publish || 0)) }
      ];
      const progress = member.dailyProgress;
      if (progress?.visible) {
        if ((progress.recoveryCreatedCount || 0) > 0) {
          rows.push({ label: 'Восст. создано', value: this.count(progress.recoveryCreatedCount) });
        }
        if ((progress.botChangeCount || 0) > 0 || (progress.botBlockCount || 0) > 0) {
          rows.push({
            label: 'Боты',
            value: `смена ${this.formatNumber(progress.botChangeCount)} / блок ${this.formatNumber(progress.botBlockCount)}`
          });
        }
        if ((progress.orderOverdueCount || 0) > 0) {
          rows.push({ label: 'Просрочки заказов', value: this.count(progress.orderOverdueCount) });
        }
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
    return role === 'manager' ? 'Команда' : 'Сегодня';
  }

  progressDetails(member: TeamMember): ProgressDetailRow[] {
    const progress = member.dailyProgress;
    if (!progress?.visible) {
      return [];
    }

    const rows: ProgressDetailRow[] = [];
    const total = Number(progress.total || 0);
    const active = Number(progress.active || 0);

    if (total > 0 || active > 0) {
      rows.push({
        label: 'Осталось',
        value: this.formatNumber(active),
        tone: active > 0 ? 'warn' : 'good',
        hint: 'Сколько активных карточек ещё нужно закрыть, чтобы прогресс дошёл до 100%.'
      });
    }

    if (total > 0) {
      rows.push({
        label: 'Нагрузка',
        value: this.formatNumber(total),
        tone: 'neutral',
        hint: 'Всего карточек в расчёте за день: закрытые + активные сейчас.'
      });
    }

    this.pushCountRow(rows, 'Заказы закрыто', progress.orderCompletedCount, 'Сколько заказов выведено из «Новые/Коррекция» за выбранный день.');
    this.pushCountRow(rows, 'Выгул закрыто', progress.nagulCompletedCount, 'Сколько карточек выгула выполнено за выбранный день.');
    this.pushCountRow(rows, 'Публикаций', progress.publishCompletedCount, 'Сколько отзывов опубликовано за выбранный день.');
    this.pushCountRow(rows, 'Плохие закрыто', progress.badCompletedCount, 'Сколько задач по плохим отзывам выполнено за выбранный день.');
    this.pushCountRow(rows, 'Восст. закрыто', progress.recoveryCompletedCount, 'Сколько задач восстановления выполнено за выбранный день.');
    this.pushCountRow(rows, 'Восст. создано', progress.recoveryCreatedCount, 'Сколько задач восстановления назначили специалисту за выбранный день.');
    this.pushCountRow(rows, 'Проср. заказов', progress.orderOverdueCount, 'Заказы, которые не были выполнены день-в-день с учётом позднего дедлайна.');
    this.pushCountRow(rows, 'Просрочек всего', progress.totalOverdueCount, 'Все просроченные карточки в дневном расчёте.');
    this.pushCountRow(rows, 'Смена бота', progress.botChangeCount, 'Сколько раз специалист нажал «смена» у аккаунта.');
    this.pushCountRow(rows, 'Блок бота', progress.botBlockCount, 'Сколько раз специалист увёл аккаунт в блок.');
    this.pushScoreRow(rows, 'Скорость', progress.speedScore, 'Оценка 0–100 по скорости закрытия после появления карточки. Ночное окно 00:00–10:00 не увеличивает время.');
    this.pushScoreRow(rows, 'Дисциплина', progress.disciplineScore, 'Оценка 0–100 по отсутствию просрочек.');
    this.pushScoreRow(rows, 'Нагрузка', progress.workloadScore, 'Оценка 0–100 по объёму выполненных задач относительно дневного норматива.');

    this.pushDurationRow(
      rows,
      'Активно',
      progress.activeWorkSeconds,
      'Примерное активное рабочее время: сумма сессий по действиям. Пауза больше 15 минут начинает новую сессию.'
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

    this.pushDurationRow(rows, 'Медиана', progress.medianCloseSeconds, 'Типичная скорость закрытия: половина задач закрыта быстрее этого времени.');
    this.pushDurationRow(rows, 'Среднее', progress.averageCloseSeconds, 'Среднее время закрытия задачи за день.');
    this.pushDurationRow(rows, 'P90', progress.p90CloseSeconds, '90% задач закрыты быстрее этого времени. Помогает увидеть длинные задержки.');

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
    const progress = member.dailyProgress;
    if (!progress?.visible || Number(progress.total || 0) <= 0) {
      return null;
    }

    const value = this.safePercent(progress.efficiencyScore || progress.percent);
    return {
      value,
      hint: `Эффективность ${value} из 100: 35% прогресс, 35% скорость закрытия после появления карточки, 20% дисциплина по просрочкам, 10% нагрузка. Ночное окно 00:00–10:00 не считается просрочкой.`
    };
  }

  progressSummary(member: TeamMember): string {
    const progress = member.dailyProgress;
    if (!progress?.visible) {
      return '';
    }

    if ((progress.total || 0) <= 0) {
      return 'Нет задач за день';
    }

    const base = (progress.active || 0) > 0
      ? `Осталось ${this.formatNumber(progress.active)}`
      : 'День закрыт';
    const median = this.formatDuration(progress.medianCloseSeconds);
    const activeWork = this.formatDuration(progress.activeWorkSeconds);
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

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
