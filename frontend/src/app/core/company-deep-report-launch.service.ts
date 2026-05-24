import { Injectable, inject } from '@angular/core';
import { AuthService } from './auth.service';
import { CompanyCreateResult } from './company-create.api';
import { ReputationDeepReportMonitorService } from './reputation-deep-report-monitor.service';
import { deepReportErrorMessage } from '../shared/reputation/deep-report-error-message';
import { ToastAction, ToastService } from '../shared/toast.service';

type LaunchToastContext = {
  routerLink?: string;
  actionLabel?: string;
  doneMessage?: string;
};

@Injectable({ providedIn: 'root' })
export class CompanyDeepReportLaunchService {
  private readonly auth = inject(AuthService);
  private readonly monitor = inject(ReputationDeepReportMonitorService);
  private readonly toastService = inject(ToastService);

  handleCompanyCreated(result: CompanyCreateResult, context: LaunchToastContext = {}): void {
    const launch = result.deepReportLaunch;
    if (!launch?.attempted) {
      return;
    }

    const companyId = Number(result.companyId);
    const title = this.companyTitle(result);
    const action = this.action(result, context);

    if (launch.started) {
      if (Number.isFinite(companyId) && companyId > 0 && this.canMonitorReport()) {
        this.monitor.watch(Math.trunc(companyId), {
          routerLink: context.routerLink ?? action?.routerLink,
          actionLabel: context.actionLabel ?? action?.label,
          doneMessage: context.doneMessage ?? `Отчёт о компании "${title}" готов.`
        });
      }

      this.toastService.info(
        'Отчёт о компании готовится',
        launch.message || 'Можно продолжать работать на сайте. Когда отчёт будет готов, появится уведомление.'
      );
      return;
    }

    this.toastService.warning(
      'Компания создана, отчёт не запущен',
      deepReportErrorMessage(
        launch.message,
        'Автоматический запуск отчёта не сработал. Запустите отчёт вручную в деталях заказа.'
      ),
      action
    );
  }

  private canMonitorReport(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG']);
  }

  private canOpenManager(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  }

  private action(result: CompanyCreateResult, context: LaunchToastContext): ToastAction | undefined {
    if (context.routerLink) {
      return {
        label: context.actionLabel ?? 'Открыть',
        routerLink: context.routerLink
      };
    }

    if (!this.canOpenManager() || !result.companyId) {
      return undefined;
    }

    const title = encodeURIComponent(this.companyTitle(result));
    return {
      label: context.actionLabel ?? 'К заказам',
      routerLink: `/manager?section=orders&companyId=${result.companyId}&companyTitle=${title}`
    };
  }

  private companyTitle(result: CompanyCreateResult): string {
    return result.title?.trim() || (result.companyId ? `Компания #${result.companyId}` : 'Компания');
  }
}
