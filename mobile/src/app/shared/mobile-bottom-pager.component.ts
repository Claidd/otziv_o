import { Location } from '@angular/common';
import { Component, EventEmitter, inject, Input, Output } from '@angular/core';
import { Router } from '@angular/router';

@Component({
  selector: 'app-mobile-bottom-pager',
  standalone: true,
  template: `
    <section class="mobile-bottom-pager" aria-label="Пагинация">
      <div class="mobile-pager-actions">
        <ng-content select="[mobilePagerActions]" />
      </div>
      <div class="mobile-pager-nav">
        <div class="mobile-pager-turns">
          <button type="button" (click)="previous.emit()" [disabled]="disabled || pageIndex <= 0" aria-label="Предыдущая страница">
            <span class="material-icons-sharp">chevron_left</span>
          </button>
          <span>{{ pageIndex + 1 }} / {{ safeTotalPages }}</span>
          <button type="button" (click)="next.emit()" [disabled]="disabled || pageIndex >= safeTotalPages - 1" aria-label="Следующая страница">
            <span class="material-icons-sharp">chevron_right</span>
          </button>
        </div>
        <button class="mobile-pager-back" type="button" (click)="goBack()" [disabled]="disabled" aria-label="Вернуться назад">
          <span class="material-icons-sharp">keyboard_backspace</span>
        </button>
      </div>
    </section>
  `,
  styles: [`
    :host {
      display: block;
      flex: 0 0 auto;
      width: 100%;
    }

    .mobile-bottom-pager {
      position: relative;
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.42rem;
      min-height: var(--otziv-pager-height, 2.78rem);
      max-width: 100%;
      overflow: hidden;
      border: 1px solid rgba(116, 154, 207, 0.18);
      border-radius: 0.86rem;
      padding: 0.34rem;
      background: rgba(255, 255, 255, 0.92);
      box-shadow: 0 0.55rem 1.35rem rgba(31, 44, 71, 0.06);
    }

    .mobile-pager-actions {
      position: relative;
      z-index: 2;
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      min-width: 0;
    }

    .mobile-pager-actions ::ng-deep button {
      position: relative;
      display: grid;
      min-width: var(--otziv-pager-button-size, 2.08rem);
      height: var(--otziv-pager-button-size, 2.08rem);
      place-items: center;
      border: 1px solid rgba(116, 154, 207, 0.22);
      border-radius: 0.62rem;
      background: #f4f8ff;
      color: var(--otziv-primary);
      font: inherit;
      font-weight: 900;
    }

    .mobile-pager-actions ::ng-deep button.active {
      background: var(--otziv-light);
    }

    .mobile-pager-actions ::ng-deep small {
      position: absolute;
      top: -0.38rem;
      right: -0.28rem;
      display: grid;
      min-width: 1rem;
      height: 1rem;
      place-items: center;
      border-radius: 999px;
      background: var(--otziv-danger);
      color: #fff;
      font-size: 0.6rem;
      line-height: 1;
    }

    .mobile-pager-nav {
      display: grid;
      grid-template-columns: minmax(0, 1fr) var(--otziv-pager-back-width, 2.72rem);
      align-items: center;
      gap: 0.3rem;
      min-width: 0;
    }

    .mobile-pager-turns {
      display: grid;
      grid-template-columns: var(--otziv-pager-button-size, 2.08rem) auto var(--otziv-pager-button-size, 2.08rem);
      align-items: center;
      justify-self: center;
      gap: 0.3rem;
      min-width: 0;
    }

    .mobile-pager-nav button {
      display: grid;
      min-width: 0;
      height: var(--otziv-pager-button-size, 2.08rem);
      place-items: center;
      border: 0;
      border-radius: 0.62rem;
      background: linear-gradient(135deg, #f9fbff, #eef4ff);
      color: var(--otziv-primary);
      font: inherit;
      font-weight: 900;
    }

    .mobile-pager-nav .mobile-pager-back {
      width: calc(100% - 0.14rem);
      min-width: 0;
      padding: 0;
      justify-self: end;
      color: var(--otziv-info);
    }

    .mobile-pager-nav .mobile-pager-back .material-icons-sharp {
      display: inline-flex;
      width: 1.1rem;
      height: 1.1rem;
      align-items: center;
      justify-content: center;
      line-height: 1;
    }

    .mobile-pager-nav button:disabled {
      opacity: 0.45;
    }

    .mobile-pager-nav span {
      min-width: 2.8rem;
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 1000;
      text-align: center;
      white-space: nowrap;
    }

    .material-icons-sharp {
      font-size: 1.05rem;
    }

    :host-context(.otziv-dark-theme) .mobile-bottom-pager,
    :host-context(body.otziv-dark-theme) .mobile-bottom-pager {
      border-color: rgba(151, 169, 183, 0.18);
      background: rgba(31, 38, 41, 0.94);
      box-shadow: none;
    }

    :host-context(.otziv-dark-theme) .mobile-pager-actions ::ng-deep button,
    :host-context(body.otziv-dark-theme) .mobile-pager-actions ::ng-deep button,
    :host-context(.otziv-dark-theme) .mobile-pager-nav button,
    :host-context(body.otziv-dark-theme) .mobile-pager-nav button {
      border-color: rgba(151, 169, 183, 0.18);
      background: #1f282c;
    }
  `]
})
export class MobileBottomPagerComponent {
  private readonly location = inject(Location);
  private readonly router = inject(Router);

  @Input() pageIndex = 0;
  @Input() totalPages = 1;
  @Input() disabled = false;
  @Output() previous = new EventEmitter<void>();
  @Output() next = new EventEmitter<void>();

  get safeTotalPages(): number {
    return Math.max(1, this.totalPages || 1);
  }

  goBack(): void {
    if (window.history.length > 1) {
      this.location.back();
      return;
    }

    void this.router.navigateByUrl('/tabs/home');
  }
}
