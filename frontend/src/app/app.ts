import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ReputationDeepReportMonitorService } from './core/reputation-deep-report-monitor.service';
import { ToastContainerComponent } from './shared/toast-container.component';
import { ManagerWorkActivityService } from './core/manager-work-activity.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastContainerComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  constructor(
    private readonly deepReportMonitor: ReputationDeepReportMonitorService,
    private readonly managerWorkActivity: ManagerWorkActivityService
  ) {
    this.deepReportMonitor.restore();
    this.managerWorkActivity.start();
  }
}
