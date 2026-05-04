import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  CABINET_NAVIGATION_LINKS,
  CabinetNavigationLink,
  visibleCabinetNavigationLinks
} from './cabinet-navigation';

@Component({
  selector: 'app-cabinet-navigation',
  imports: [RouterLink],
  template: `
    @let navigationLinks = visibleNavigationLinks();
    @if (navigationLinks.length) {
      <nav class="cabinet-nav-block" aria-label="Разделы кабинета">
        @for (link of navigationLinks; track link.label) {
          @if (link.routerLink) {
            <a class="cabinet-nav-card" [routerLink]="link.routerLink" [class.active]="active === link.active">
              <span class="material-icons-sharp">{{ link.icon }}</span>
              <strong>{{ link.label }}</strong>
              <small>{{ link.description }}</small>
            </a>
          } @else {
            <a class="cabinet-nav-card" [href]="link.href" [class.active]="active === link.active">
              <span class="material-icons-sharp">{{ link.icon }}</span>
              <strong>{{ link.label }}</strong>
              <small>{{ link.description }}</small>
            </a>
          }
        }
      </nav>
    }
  `,
  styles: [`
    :host {
      display: block;
      margin-top: 1.05rem;
    }

    .cabinet-nav-block {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.9rem;
    }

    .cabinet-nav-card {
      display: grid;
      min-height: 7.1rem;
      align-content: start;
      gap: 0.35rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.5rem;
      padding: 1rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      box-shadow: var(--otziv-shadow);
      text-decoration: none;
      transition: border-color 0.2s ease, color 0.2s ease, transform 0.2s ease;
    }

    .cabinet-nav-card:hover,
    .cabinet-nav-card.active {
      border-color: rgba(108, 155, 207, 0.55);
      color: var(--otziv-primary);
      transform: translateY(-1px);
    }

    .cabinet-nav-card.active {
      background: var(--otziv-light);
    }

    .cabinet-nav-card > span {
      color: var(--otziv-primary);
      font-size: 2rem;
    }

    .cabinet-nav-card strong,
    .cabinet-nav-card small {
      min-width: 0;
      overflow-wrap: anywhere;
      line-height: 1.25;
    }

    .cabinet-nav-card small {
      color: var(--otziv-info);
      font-weight: 700;
    }

    @media (max-width: 1120px) {
      .cabinet-nav-block {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 640px) {
      .cabinet-nav-block {
        grid-template-columns: 1fr;
      }
    }
  `]
})
export class CabinetNavigationComponent {
  @Input() roles: string[] = [];
  @Input() active = '';
  @Input() links: CabinetNavigationLink[] = CABINET_NAVIGATION_LINKS;

  visibleNavigationLinks(): CabinetNavigationLink[] {
    return visibleCabinetNavigationLinks(this.roles, this.links);
  }
}
