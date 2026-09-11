import { Component, inject } from '@angular/core';
import { WorkerAccountActionCooldownService } from '../core/worker-account-action-cooldown.service';

@Component({
  selector: 'app-worker-account-action-cooldown-toast',
  template: `
    @if (cooldown.locked()) {
      <aside class="account-cooldown" aria-label="Ожидание смены и блокировки аккаунта">
        <span class="material-icons-sharp" aria-hidden="true">timer</span>
        <div>
          <strong>{{ cooldown.title() }}</strong>
          <p>Можно продолжать работу на других страницах</p>
        </div>
      </aside>
    }
  `,
  styleUrl: './worker-account-action-cooldown-toast.component.scss'
})
export class WorkerAccountActionCooldownToastComponent {
  readonly cooldown = inject(WorkerAccountActionCooldownService);
}
