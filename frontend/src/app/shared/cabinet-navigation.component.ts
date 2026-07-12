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

    :host-context(body.otziv-dark-theme) .cabinet-nav-card {
      border-color: rgba(159, 184, 215, 0.2);
      background: linear-gradient(155deg, rgba(39, 47, 57, 0.98) 0%, rgba(28, 34, 42, 0.98) 100%);
      box-shadow: 0 0.65rem 1.35rem rgba(0, 0, 0, 0.22);
    }

    :host-context(body.otziv-dark-theme) .cabinet-nav-card:hover,
    :host-context(body.otziv-dark-theme) .cabinet-nav-card.active {
      border-color: rgba(122, 167, 220, 0.55);
      color: #9bc4f2;
      background: linear-gradient(155deg, rgba(43, 58, 73, 0.98) 0%, rgba(27, 34, 43, 0.98) 100%);
      box-shadow: inset 0 0 0 1px rgba(122, 167, 220, 0.08), 0 0.65rem 1.35rem rgba(0, 0, 0, 0.24);
    }

    @media (max-width: 1120px) {
      .cabinet-nav-block {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 860px) {
      :host {
        margin-top: 0.52rem;
      }

      .cabinet-nav-block {
        display: flex;
        gap: 0.5rem;
        margin-inline: -0.15rem;
        overflow-x: auto;
        padding: 0 0.15rem 0.08rem;
        scroll-snap-type: x mandatory;
        scrollbar-width: none;
      }

      .cabinet-nav-block::-webkit-scrollbar {
        display: none;
      }

      .cabinet-nav-card {
        flex: 0 0 7.3rem;
        min-height: 3.45rem;
        grid-template-columns: 1.45rem minmax(0, 1fr);
        align-content: center;
        align-items: center;
        gap: 0.08rem 0.42rem;
        border-color: rgba(108, 155, 207, 0.28);
        border-radius: 1rem;
        padding: 0.52rem;
        background: linear-gradient(155deg, #f6faff 0%, var(--otziv-white) 82%);
        box-shadow: 0 0.7rem 1.45rem rgba(132, 139, 200, 0.09);
        scroll-snap-align: start;
      }

      .cabinet-nav-card:hover {
        border-color: rgba(108, 155, 207, 0.38);
        color: var(--otziv-dark);
        transform: none;
      }

      .cabinet-nav-card.active {
        border-color: rgba(108, 155, 207, 0.38);
        color: var(--otziv-primary);
        background: linear-gradient(155deg, var(--otziv-light) 0%, var(--otziv-white) 88%);
        transform: none;
      }

      .cabinet-nav-card > span {
        grid-row: 1 / 3;
        font-size: 1.22rem;
      }

      .cabinet-nav-card strong {
        align-self: end;
        overflow: hidden;
        font-size: 0.72rem;
        line-height: 1;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .cabinet-nav-card small {
        align-self: start;
        overflow: hidden;
        font-size: 0.54rem;
        line-height: 1;
        text-overflow: ellipsis;
        white-space: nowrap;
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
