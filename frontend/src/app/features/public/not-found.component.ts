import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [RouterLink],
  template: `
    <main class="not-found" role="main">
      <span class="material-icons-sharp" aria-hidden="true">travel_explore</span>
      <p>404</p>
      <h1>Страница не найдена</h1>
      <small>Проверьте адрес или вернитесь на главную страницу.</small>
      <a routerLink="/">На главную</a>
    </main>
  `,
  styles: [`
    :host { display: grid; min-height: 100dvh; place-items: center; padding: 1rem; background: var(--otziv-background, #f5f7fb); }
    .not-found { display: grid; width: min(100%, 32rem); justify-items: center; gap: .75rem; border: 1px solid rgba(103,116,131,.16); border-radius: 1rem; padding: 2.5rem 1.5rem; text-align: center; background: var(--otziv-white, #fff); box-shadow: 0 1rem 2rem rgba(42,55,72,.1); }
    .material-icons-sharp { color: var(--otziv-primary, #5b6ee1); font-size: 3.5rem; }
    p, h1, small { margin: 0; }
    p { color: var(--otziv-primary, #5b6ee1); font-weight: 1000; }
    h1 { color: var(--otziv-dark, #263238); font-size: clamp(1.5rem, 5vw, 2.2rem); }
    small { color: var(--otziv-info, #677483); font-weight: 700; }
    a { margin-top: .5rem; border-radius: .55rem; padding: .75rem 1rem; color: #fff; text-decoration: none; background: var(--otziv-primary, #5b6ee1); font-weight: 900; }
  `]
})
export class NotFoundComponent {}
