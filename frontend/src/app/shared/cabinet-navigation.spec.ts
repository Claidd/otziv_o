import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { CabinetNavigationComponent } from './cabinet-navigation.component';
import { visibleCabinetNavigationLinks } from './cabinet-navigation';

describe('cabinet navigation', () => {
  it('filters links by business role', () => {
    expect(visibleCabinetNavigationLinks(['WORKER']).map((link) => link.label)).toEqual([
      'Личный кабинет',
      'Рейтинг'
    ]);
    expect(visibleCabinetNavigationLinks(['MANAGER']).map((link) => link.label)).toEqual([
      'Личный кабинет',
      'Моя команда',
      'Рейтинг'
    ]);
    expect(visibleCabinetNavigationLinks(['OWNER']).map((link) => link.label)).toEqual([
      'Личный кабинет',
      'Моя команда',
      'Рейтинг',
      'Аналитика'
    ]);
  });

  it('renders the active visible card', async () => {
    await TestBed.configureTestingModule({
      imports: [CabinetNavigationComponent],
      providers: [provideRouter([])]
    }).compileComponents();

    const fixture = TestBed.createComponent(CabinetNavigationComponent);
    fixture.componentInstance.roles = ['WORKER'];
    fixture.componentInstance.active = 'score';

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const cards = Array.from(element.querySelectorAll<HTMLElement>('.cabinet-nav-card'));

    expect(cards.map((card) => card.querySelector('strong')?.textContent?.trim())).toEqual([
      'Личный кабинет',
      'Рейтинг'
    ]);
    expect(element.querySelector('.cabinet-nav-card.active strong')?.textContent?.trim()).toBe('Рейтинг');
    expect(element.textContent).not.toContain('Аналитика');
  });
});
