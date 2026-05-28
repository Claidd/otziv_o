import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-mobile-entity-card-shell',
  standalone: true,
  template: `
    <article class="mobile-entity-card tone-{{ tone }}">
      @if (title || eyebrow || meta) {
        <header>
          <div>
            @if (eyebrow) {
              <small>{{ eyebrow }}</small>
            }
            @if (title) {
              <h2>{{ title }}</h2>
            }
          </div>
          @if (meta) {
            <span>{{ meta }}</span>
          }
        </header>
      }

      <div class="mobile-entity-card-body">
        <ng-content />
      </div>

      <footer>
        <ng-content select="[mobileEntityFooter]" />
      </footer>
    </article>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .mobile-entity-card {
      display: grid;
      min-width: 0;
      min-height: var(--mobile-entity-card-min-height, auto);
      gap: var(--mobile-entity-card-gap, 0.62rem);
      border: 1px solid var(--mobile-entity-card-border, rgba(116, 154, 207, 0.22));
      border-radius: 1rem;
      padding: var(--mobile-entity-card-padding, 0.72rem);
      background: linear-gradient(180deg, var(--mobile-entity-card-surface, #fff) 0%, var(--otziv-white) 58%, var(--otziv-white) 100%);
      box-shadow: 0 0.55rem 1.35rem rgba(31, 44, 71, 0.055);
    }

    header,
    footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.55rem;
      min-width: 0;
    }

    header div {
      min-width: 0;
    }

    h2,
    small {
      margin: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    h2 {
      color: var(--otziv-dark);
      font-size: var(--mobile-entity-card-title-size, 1rem);
      font-weight: 900;
      line-height: 1.05;
    }

    small,
    span {
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 900;
      line-height: 1.05;
    }

    span {
      flex: 0 0 auto;
    }

    .mobile-entity-card-body {
      display: grid;
      min-width: 0;
      gap: 0.5rem;
    }

    .tone-green {
      --mobile-entity-card-border: var(--otziv-tone-success-border);
      --mobile-entity-card-surface: var(--otziv-tone-success-surface);
    }

    .tone-yellow {
      --mobile-entity-card-border: var(--otziv-tone-wait-border);
      --mobile-entity-card-surface: var(--otziv-tone-wait-surface);
    }

    .tone-teal {
      --mobile-entity-card-border: var(--otziv-tone-work-border);
      --mobile-entity-card-surface: var(--otziv-tone-work-surface);
    }

    .tone-pink,
    .tone-red {
      --mobile-entity-card-border: var(--otziv-tone-bad-border);
      --mobile-entity-card-surface: var(--otziv-tone-bad-surface);
    }

    .tone-violet {
      --mobile-entity-card-border: var(--otziv-tone-publication-border);
      --mobile-entity-card-surface: var(--otziv-tone-publication-surface);
    }

    :host-context(body.otziv-dark-theme) .mobile-entity-card {
      background: linear-gradient(180deg, rgba(31, 38, 41, 0.98), rgba(22, 27, 29, 0.96));
      box-shadow: none;
    }
  `]
})
export class MobileEntityCardShellComponent {
  @Input() title = '';
  @Input() eyebrow = '';
  @Input() meta = '';
  @Input() tone: 'blue' | 'green' | 'yellow' | 'red' | 'teal' | 'violet' | 'pink' | 'gray' = 'blue';
}
