import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  ManagerApi,
  ManagerPage,
  WorkerRiskIncident,
  WorkerRiskIncidentLevel,
  WorkerRiskResolutionAction,
  WorkerRiskIncidentStatus
} from '../../core/manager.api';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { PersonalRemindersService } from '../../shared/personal-reminders.service';
import { ToastService } from '../../shared/toast.service';

type RiskStatusTab = {
  key: WorkerRiskIncidentStatus;
  label: string;
  icon: string;
};

const EMPTY_PAGE: ManagerPage<WorkerRiskIncident> = {
  content: [],
  number: 0,
  size: 50,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
};

@Component({
  selector: 'app-worker-risk',
  imports: [DatePipe, LoadErrorCardComponent, RouterLink],
  templateUrl: './worker-risk.component.html',
  styleUrl: './worker-risk.component.scss'
})
export class WorkerRiskComponent {
  private readonly managerApi = inject(ManagerApi);
  private readonly remindersService = inject(PersonalRemindersService);
  private readonly toast = inject(ToastService);

  readonly tabs: RiskStatusTab[] = [
    { key: 'OPEN', label: 'Открытые', icon: 'warning' },
    { key: 'RESOLVED', label: 'Проверенные', icon: 'task_alt' },
    { key: 'IGNORED', label: 'Игнор', icon: 'visibility_off' },
    { key: 'VIOLATION', label: 'Нарушения', icon: 'gpp_bad' }
  ];
  readonly status = signal<WorkerRiskIncidentStatus>('OPEN');
  readonly page = signal<ManagerPage<WorkerRiskIncident>>(EMPTY_PAGE);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutatingId = signal<number | null>(null);

  readonly incidents = computed(() => this.page().content ?? []);
  readonly highRiskCount = computed(() => this.incidents().filter((incident) => incident.level === 'HIGH_RISK').length);
  readonly managerReviewCount = computed(() => this.incidents().filter((incident) => incident.level === 'MANAGER_REVIEW').length);

  constructor() {
    this.load();
  }

  setStatus(status: WorkerRiskIncidentStatus): void {
    if (this.status() === status) {
      return;
    }
    this.status.set(status);
    this.load();
  }

  refresh(): void {
    this.load();
  }

  resolve(incident: WorkerRiskIncident): void {
    this.updateIncident(incident, 'VERIFIED');
  }

  ignore(incident: WorkerRiskIncident): void {
    this.updateIncident(incident, 'FALSE_POSITIVE');
  }

  requestExplanation(incident: WorkerRiskIncident): void {
    this.updateIncident(incident, 'EXPLANATION_REQUESTED');
  }

  confirmViolation(incident: WorkerRiskIncident): void {
    this.updateIncident(incident, 'VIOLATION_CONFIRMED', 1);
  }

  rollback(incident: WorkerRiskIncident): void {
    if (this.mutatingId()) {
      return;
    }

    this.mutatingId.set(incident.id);
    this.managerApi.rollbackWorkerRiskIncident(incident.id).subscribe({
      next: (updated) => {
        const message = updated.rollbackMessage || 'Действие возвращено';
        if (updated.rollbackStatus === 'NOT_APPLICABLE') {
          this.toast.error(message);
        } else {
          this.toast.success(message);
        }
        this.mutatingId.set(null);
        this.load();
      },
      error: (error) => {
        this.toast.error(apiErrorMessage(error, 'Действие не возвращено'));
        this.mutatingId.set(null);
      }
    });
  }

  riskLabel(level: WorkerRiskIncidentLevel): string {
    switch (level) {
      case 'HIGH_RISK':
        return 'Высокий';
      case 'MANAGER_REVIEW':
        return 'Проверить';
      default:
        return 'Предупреждение';
    }
  }

  actionLabel(action: string | null | undefined): string {
    const labels: Record<string, string> = {
      REVIEW_COPY_LOGIN: 'копирование логина',
      REVIEW_COPY_PASSWORD: 'копирование пароля',
      REVIEW_PUBLISH: 'публикация',
      REVIEW_NAGUL: 'выгул',
      REVIEW_BOT_CHANGE: 'смена аккаунта',
      REVIEW_BOT_DEACTIVATE: 'деактивация аккаунта',
      REVIEW_TEXT_UPDATE: 'правка текста',
      REVIEW_ANSWER_UPDATE: 'правка ответа',
      REVIEW_NOTE_UPDATE: 'правка заметки',
      ORDER_NOTE_UPDATE: 'заметка заказа',
      COMPANY_NOTE_UPDATE: 'заметка компании',
      BAD_TASK_COMPLETE: 'закрытие плохой',
      BAD_TASK_UPDATE: 'правка плохой',
      BAD_TASK_BOT_CHANGE: 'смена аккаунта плохой',
      BAD_TASK_BOT_DEACTIVATE: 'деактивация аккаунта плохой',
      RECOVERY_TASK_COMPLETE: 'закрытие восстановления',
      RECOVERY_TASK_UPDATE: 'правка восстановления',
      RECOVERY_TASK_BOT_CHANGE: 'смена аккаунта восстановления',
      RECOVERY_TASK_BOT_DEACTIVATE: 'деактивация аккаунта восстановления'
    };
    return action ? labels[action] ?? action : '-';
  }

  resolutionLabel(action: WorkerRiskResolutionAction | null | undefined): string {
    const labels: Record<WorkerRiskResolutionAction, string> = {
      VERIFIED: 'Проверено',
      FALSE_POSITIVE: 'Игнорировать',
      NORMAL_ACCOUNT_SELECTION: 'Нормальный подбор',
      EXPLANATION_REQUESTED: 'Ожидаем разъяснение',
      VIOLATION_CONFIRMED: 'Нарушение / штраф',
      WORKER_WARNED: 'Пояснение запрошено'
    };
    return action ? labels[action] ?? action : '-';
  }

  rollbackLabel(status: string | null | undefined): string {
    switch (status) {
      case 'APPLIED':
        return 'Действие возвращено';
      case 'NOT_APPLICABLE':
        return 'Нужен ручной разбор';
      default:
        return '-';
    }
  }

  trackIncident(_: number, incident: WorkerRiskIncident): number {
    return incident.id;
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.managerApi.getWorkerRiskIncidents(this.status()).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(apiErrorMessage(error, 'Подозрительные действия не загрузились'));
        this.loading.set(false);
      }
    });
  }

  explanationRequested(incident: WorkerRiskIncident): boolean {
    return incident.resolutionAction === 'EXPLANATION_REQUESTED' || incident.resolutionAction === 'WORKER_WARNED';
  }

  private updateIncident(incident: WorkerRiskIncident, action: WorkerRiskResolutionAction, penaltyPoints?: number): void {
    if (this.mutatingId()) {
      return;
    }

    this.mutatingId.set(incident.id);

    this.managerApi.setWorkerRiskIncidentResolution(incident.id, action, penaltyPoints).subscribe({
      next: () => {
        this.toast.success(this.toastTitle(action));
        this.mutatingId.set(null);
        this.remindersService.load(true);
        this.load();
      },
      error: (error) => {
        this.toast.error(apiErrorMessage(error, 'Статус инцидента не изменен'));
        this.mutatingId.set(null);
      }
    });
  }

  private toastTitle(action: WorkerRiskResolutionAction): string {
    switch (action) {
      case 'FALSE_POSITIVE':
        return 'Инцидент проигнорирован';
      case 'NORMAL_ACCOUNT_SELECTION':
        return 'Отмечен нормальный подбор';
      case 'EXPLANATION_REQUESTED':
      case 'WORKER_WARNED':
        return 'Разъяснение запрошено';
      case 'VIOLATION_CONFIRMED':
        return 'Нарушение зафиксировано';
      default:
        return 'Инцидент проверен';
    }
  }
}
