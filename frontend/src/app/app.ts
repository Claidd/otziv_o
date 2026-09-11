import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ReputationDeepReportMonitorService } from './core/reputation-deep-report-monitor.service';
import { ToastContainerComponent } from './shared/toast-container.component';
import { ManagerWorkActivityService } from './core/manager-work-activity.service';
import { WorkerAccountActionCooldownToastComponent } from './shared/worker-account-action-cooldown-toast.component';
import { WorkerAccountActionCooldownService } from './core/worker-account-action-cooldown.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastContainerComponent, WorkerAccountActionCooldownToastComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  readonly accountActionCooldown = inject(WorkerAccountActionCooldownService);
  constructor(
    private readonly deepReportMonitor: ReputationDeepReportMonitorService,
    private readonly managerWorkActivity: ManagerWorkActivityService
  ) {
    this.deepReportMonitor.restore();
    this.managerWorkActivity.start();
  }
}
