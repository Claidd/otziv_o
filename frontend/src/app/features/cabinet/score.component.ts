import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CabinetApi, ManagerPerformanceScore, ScoreResponse, ScoreUser } from '../../core/cabinet.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorDetail } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';

type ScoreGroupKey = 'managers' | 'workers' | 'operators' | 'marketologs';

type ScoreSection = {
  key: ScoreGroupKey;
  title: string;
  icon: string;
};

type ManagerScoreFactor = {
  key: string;
  label: string;
  weight: number;
  score: number;
  hint: string;
};

@Component({
  selector: 'app-score',
  imports: [AdminLayoutComponent, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './score.component.html',
  styleUrl: './score.component.scss'
})
export class ScoreComponent {
  readonly selectedDate = signal(this.todayIso());
  readonly score = signal<ScoreResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly activeFactorTip = signal<string | null>(null);

  readonly sections: ScoreSection[] = [
    { key: 'managers', title: 'Менеджеры', icon: 'groups' },
    { key: 'workers', title: 'Работники', icon: 'engineering' },
    { key: 'operators', title: 'Операторы', icon: 'support_agent' },
    { key: 'marketologs', title: 'Маркетологи', icon: 'campaign' }
  ];

  constructor(private readonly cabinetApi: CabinetApi) {
    this.load();
  }

  load(forceRefresh = false): void {
    this.loading.set(true);
    this.error.set(null);

    this.cabinetApi.getScore(this.selectedDate(), { forceRefresh }).subscribe({
      next: (response) => {
        this.score.set(response);
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

  users(section: ScoreSection): ScoreUser[] {
    return this.score()?.groups[section.key] ?? [];
  }

  rows(section: ScoreSection, user: ScoreUser): Array<{ label: string; value: string }> {
    if (section.key === 'managers') {
      const performance = user.managerPerformance;
      return [
        performance ? { label: 'Эффективность', value: `${performance.grade} · ${performance.loadAdjustedPerformanceScore}` } : null,
        performance ? { label: 'База KPI', value: `${performance.performanceScore} без нагрузки` } : null,
        performance ? { label: 'Нагрузка', value: `${this.workloadLabel(performance.workloadLevel)} · ${this.decimal(performance.avgDailyWorkload)} в день` } : null,
        performance ? { label: 'К действию', value: this.amount(performance.actionTotal) } : null,
        this.financeRow('ЗП', user.salary),
        this.financeRow('Новые компании', user.newCompanies, ' шт.'),
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        this.financeRow('Оборот', user.totalSum)
      ].filter(Boolean) as Array<{ label: string; value: string }>;
    }

    if (section.key === 'workers') {
      return [
        this.financeRow('ЗП', user.salary),
        { label: 'Заказы', value: this.count(user.order1Month) },
        { label: 'Отзывы', value: this.count(user.review1Month) },
        { label: 'Выгул', value: this.count(user.inVigul) },
        { label: 'Публикация', value: this.count(user.inPublish) }
      ].filter(Boolean) as Array<{ label: string; value: string }>;
    }

    return [
      this.financeRow('ЗП', user.salary),
      { label: 'Новые', value: this.count(user.leadsNew) },
      { label: 'В работе', value: this.count(user.leadsInWork) },
      { label: 'Конверсия', value: `${user.percentInWork || 0}%` }
    ].filter(Boolean) as Array<{ label: string; value: string }>;
  }

  managerPerformanceRows(user: ScoreUser): Array<{ label: string; value: string }> {
    const performance = user.managerPerformance;
    if (!performance) {
      return [];
    }
    return [
      { label: 'В срок проблем', value: this.percent(performance.problemSlaRate) },
      { label: 'В срок клиентов', value: this.percent(performance.clientSlaRate) },
      { label: 'Просрочки', value: `${this.percent(performance.overdueRate)} · ${this.decimal(performance.avgDailyOverdue)} в день` },
      { label: 'Заказы / спец.', value: `${this.amount(performance.workloadOrder)} / ${this.amount(performance.workloadWorker)}` },
      { label: 'Ответы', value: performance.clientReplyMedianMinutes > 0 ? `${this.decimal(performance.clientReplyMedianMinutes)} / ${this.decimal(performance.clientReplyP90Minutes)} мин.` : '-' },
      { label: 'Риски', value: performance.riskResolutionAvgHours > 0 ? `${this.decimal(performance.riskResolutionAvgHours)} ч.` : '-' },
      { label: 'Хвосты', value: this.amount(performance.backlogCount) },
      { label: 'Повторы', value: this.percent(performance.reopenRate) },
      { label: 'Контроль', value: `${performance.controlAcceptedCount}/${performance.controlClosedCount}` }
    ];
  }

  managerScoreFactors(user: ScoreUser): ManagerScoreFactor[] {
    const performance = user.managerPerformance;
    if (!performance) {
      return [];
    }
    return [
      {
        key: 'problem-speed',
        label: 'Проблемы',
        weight: 25,
        score: performance.problemSpeedScore,
        hint: 'Скорость решения замечаний из дневного контроля. Открытые задачи считаются по текущему времени и не штрафуются жестко, пока они еще внутри SLA 8 часов.'
      },
      {
        key: 'client-response',
        label: 'Клиенты',
        weight: 20,
        score: performance.clientResponseScore,
        hint: 'Скорость ответа на неотвеченные клиентские сообщения. Открытые сообщения считаются по текущему времени и штрафуются только по мере приближения или выхода за норматив 30 минут.'
      },
      {
        key: 'overdue-control',
        label: 'Просрочки',
        weight: 20,
        score: performance.overdueControlScore,
        hint: 'Контроль просроченных заказов: учитываем долю просрочек в общей нагрузке и возраст просроченных задач.'
      },
      {
        key: 'specialist-risk',
        label: 'Спец. и риски',
        weight: 15,
        score: performance.specialistRiskScore,
        hint: `Работа с проблемами специалистов и рисками. Учитываем SLA реакции и качество обработки риска: ${performance.riskQualityScore}/100.`
      },
      {
        key: 'control-discipline',
        label: 'Контроль',
        weight: 10,
        score: performance.controlDisciplineScore,
        hint: 'Дисциплина дневного контроля: принятие контроля, закрытие дня и отсутствие формального быстрого прокликивания.'
      },
      {
        key: 'stability',
        label: 'Стабильность',
        weight: 10,
        score: performance.stabilityScore,
        hint: 'Стабильность работы: меньше повторных проблем и отложенных задач означает более высокий балл.'
      }
    ];
  }

  scorePeriodLabel(): string {
    const date = new Date(`${this.selectedDate()}T00:00:00`);
    return new Intl.DateTimeFormat('ru-RU', { month: 'long', year: 'numeric' }).format(date);
  }

  factorTipKey(user: ScoreUser, factor: ManagerScoreFactor): string {
    return `${user.userId || user.fio}-${factor.key}`;
  }

  toggleFactorTip(event: MouseEvent, user: ScoreUser, factor: ManagerScoreFactor): void {
    event.stopPropagation();
    const key = this.factorTipKey(user, factor);
    this.activeFactorTip.set(this.activeFactorTip() === key ? null : key);
  }

  closeFactorTip(): void {
    this.activeFactorTip.set(null);
  }

  hasManagerPerformance(section: ScoreSection, user: ScoreUser): boolean {
    return section.key === 'managers'
      && !!this.score()?.managerPerformanceVisible
      && !!user.managerPerformance;
  }

  performanceTone(performance?: ManagerPerformanceScore | null): string {
    const score = performance?.loadAdjustedPerformanceScore ?? performance?.performanceScore ?? 0;
    if (score >= 90) {
      return 'excellent';
    }
    if (score >= 75) {
      return 'good';
    }
    if (score >= 55) {
      return 'warning';
    }
    return 'risk';
  }

  imageUrl(imageId?: number | null): string {
    return this.cabinetApi.imageUrl(imageId);
  }

  userTrack(user: ScoreUser): string {
    return `${user.role}-${user.userId || user.fio}`;
  }

  private financeRow(label: string, value?: number | null, suffix = ' руб.'): { label: string; value: string } | null {
    if (!this.score()?.financeVisible || value == null) {
      return null;
    }

    return {
      label,
      value: `${new Intl.NumberFormat('ru-RU').format(value || 0)}${suffix}`
    };
  }

  private count(value?: number | null): string {
    return `${this.amount(value)} шт.`;
  }

  private amount(value?: number | null): string {
    return new Intl.NumberFormat('ru-RU').format(value || 0);
  }

  private percent(value?: number | null): string {
    return `${this.decimal(value ?? 0)}%`;
  }

  private decimal(value?: number | null): string {
    return new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 1
    }).format(value ?? 0);
  }

  private workloadLabel(value?: string | null): string {
    switch (value) {
      case 'EXTREME':
        return 'очень высокая';
      case 'HIGH':
        return 'высокая';
      case 'NORMAL':
        return 'нормальная';
      default:
        return 'низкая';
    }
  }

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
