import { Component, inject } from '@angular/core';
import { MobileNativeService } from '../core/mobile-native.service';

@Component({
  selector: 'app-mobile-native-status',
  standalone: true,
  template: `
    @if (native.isOffline()) {
      <div class="native-status offline" role="status" aria-live="polite">
        <span class="material-icons-sharp">wifi_off</span>
        <span>Нет сети. Данные обновятся после подключения.</span>
      </div>
    } @else if (native.runtimeError()) {
      <div class="native-status runtime" role="alert" aria-live="assertive">
        <span class="material-icons-sharp">error</span>
        <span>{{ native.runtimeError() }}</span>
      </div>
    }
  `,
  styles: [`
    :host {
      position: fixed;
      z-index: 10000;
      top: max(0.45rem, env(safe-area-inset-top));
      left: max(0.75rem, env(safe-area-inset-left));
      right: max(0.75rem, env(safe-area-inset-right));
      pointer-events: none;
    }

    .native-status {
      display: flex;
      min-height: 2.4rem;
      align-items: center;
      justify-content: center;
      gap: 0.45rem;
      border: 1px solid rgba(255, 0, 96, 0.28);
      border-radius: 999px;
      padding: 0 0.8rem;
      color: #9a2737;
      background: rgba(255, 245, 248, 0.96);
      box-shadow: 0 0.8rem 1.8rem rgba(54, 57, 73, 0.16);
      font-size: 0.78rem;
      font-weight: 900;
      text-align: center;
      backdrop-filter: blur(12px);
    }

    .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 1.05rem;
    }

    :host-context(body.otziv-dark-theme) .native-status {
      color: #ffd7e2;
      background: rgba(72, 31, 45, 0.96);
      box-shadow: 0 0.8rem 1.8rem rgba(0, 0, 0, 0.32);
    }
  `]
})
export class MobileNativeStatusComponent {
  readonly native = inject(MobileNativeService);
}
