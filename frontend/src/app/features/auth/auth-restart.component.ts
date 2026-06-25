import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-auth-restart',
  standalone: true,
  template: `
    <main class="auth-restart">
      <section class="auth-restart__panel">
        <h1>Обновляем вход</h1>
        <p>Сессия авторизации устарела. Сейчас откроем форму входа заново.</p>
      </section>
    </main>
  `,
  styles: [`
    :host {
      display: block;
      min-height: 100vh;
      background: #f6f7fb;
      color: #263243;
      font-family: Inter, "Segoe UI", system-ui, -apple-system, BlinkMacSystemFont, Roboto, Arial, sans-serif;
    }

    .auth-restart {
      display: grid;
      min-height: 100vh;
      place-items: center;
      padding: 1.5rem;
    }

    .auth-restart__panel {
      width: min(30rem, 100%);
      border-radius: 1rem;
      padding: 2rem;
      background: #fff;
      box-shadow: 0 1.4rem 3.8rem rgba(54, 57, 73, 0.12);
    }

    h1 {
      margin: 0 0 0.65rem;
      font-size: 1.35rem;
      line-height: 1.2;
    }

    p {
      margin: 0;
      color: #6f7d91;
      line-height: 1.5;
    }
  `]
})
export class AuthRestartComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);

  ngOnInit(): void {
    const target = this.safeTarget(this.route.snapshot.queryParamMap.get('target'));
    setTimeout(() => {
      void this.auth.login(target);
    }, 150);
  }

  private safeTarget(value: string | null): string {
    if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/keycloak')) {
      return '/';
    }
    return value;
  }
}
