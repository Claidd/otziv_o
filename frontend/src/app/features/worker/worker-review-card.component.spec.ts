import { TestBed } from '@angular/core/testing';
import type { WorkerReviewItem } from '../../core/worker.api';
import { WorkerReviewCardComponent } from './worker-review-card.component';

function review(overrides: Partial<WorkerReviewItem> = {}): WorkerReviewItem {
  return {
    id: 17,
    companyId: 3,
    orderId: 5,
    text: 'Review text',
    answer: 'Answer text',
    category: 'Category',
    subCategory: 'Subcategory',
    botId: 11,
    botFio: 'Bot Name',
    botLogin: 'bot-login',
    botPassword: 'bot-password',
    botCounter: 2,
    companyTitle: 'Company',
    commentCompany: 'Company note',
    orderComments: 'Order note',
    filialCity: 'City',
    filialTitle: 'Filial',
    filialUrl: 'https://example.test/filial',
    productId: 9,
    productTitle: 'Product',
    productPhoto: true,
    workerFio: 'Worker',
    created: '2026-05-01',
    changed: '2026-05-02',
    publishedDate: '',
    publish: false,
    vigul: false,
    comment: 'Review note',
    url: 'https://example.test/review',
    urlPhoto: 'https://example.test/photo.jpg',
    ...overrides,
  };
}

describe('WorkerReviewCardComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WorkerReviewCardComponent],
    }).compileComponents();
  });

  it('renders review data, bot label and task controls', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    fixture.componentInstance.review = review({
      badTask: true,
      badTaskId: 99,
      originalRating: 5,
      targetRating: 2,
    });
    fixture.componentInstance.activeSection = 'bad';

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.review-title')?.textContent?.trim()).toBe('Company');
    expect(element.textContent).toContain('5 -> 2');
    expect(element.textContent).toContain('Bot Name 2');
    expect(element.querySelector('.review-photo-link')?.getAttribute('href')).toBe(
      'https://example.test/photo.jpg',
    );
    expect(element.querySelector('.publish-button')?.textContent?.trim()).toBe('Сменил');
  });

  it('adds soft section tones for review work cards', () => {
    const render = (
      activeSection: WorkerReviewCardComponent['activeSection'],
      overrides: Partial<WorkerReviewItem> = {},
    ): HTMLElement => {
      const fixture = TestBed.createComponent(WorkerReviewCardComponent);
      fixture.componentInstance.review = review(overrides);
      fixture.componentInstance.activeSection = activeSection;
      fixture.detectChanges();
      return fixture.nativeElement.querySelector('article') as HTMLElement;
    };

    let article = render('nagul');

    expect(article.classList.contains('card-tone--walk')).toBe(true);

    article = render('publish');

    expect(article.classList.contains('card-tone--publication')).toBe(true);

    article = render('bad', { badTask: true });

    expect(article.classList.contains('card-tone--bad')).toBe(true);
    expect(article.classList.contains('card-tone--publication')).toBe(false);
  });

  it('keeps filial title link in walk and publication titles when title links are allowed', () => {
    const renderTitle = (
      activeSection: WorkerReviewCardComponent['activeSection'],
    ): { text?: string; tagName?: string; href?: string | null } => {
      const fixture = TestBed.createComponent(WorkerReviewCardComponent);
      fixture.componentInstance.review = review();
      fixture.componentInstance.activeSection = activeSection;

      fixture.detectChanges();

      const element = fixture.nativeElement as HTMLElement;
      const title = element.querySelector<HTMLElement>('.review-title');
      return {
        text: title?.textContent?.trim(),
        tagName: title?.tagName,
        href: title instanceof HTMLAnchorElement ? title.getAttribute('href') : null,
      };
    };

    expect(renderTitle('nagul')).toEqual({
      text: 'Company - Filial',
      tagName: 'A',
      href: 'https://example.test/filial',
    });
    expect(renderTitle('publish')).toEqual({
      text: 'Company - Filial',
      tagName: 'A',
      href: 'https://example.test/filial',
    });
    expect(renderTitle('bad')).toEqual({
      text: 'Company',
      tagName: 'A',
      href: 'https://example.test/filial',
    });
  });

  it('shows filial title without direct title link in walk and publication titles for worker role view', () => {
    const renderTitle = (
      activeSection: WorkerReviewCardComponent['activeSection'],
    ): { text?: string; tagName?: string; href?: string | null } => {
      const fixture = TestBed.createComponent(WorkerReviewCardComponent);
      fixture.componentInstance.review = review();
      fixture.componentInstance.activeSection = activeSection;
      fixture.componentInstance.canOpenTitleLink = false;

      fixture.detectChanges();

      const element = fixture.nativeElement as HTMLElement;
      const title = element.querySelector<HTMLElement>('.review-title');
      return {
        text: title?.textContent?.trim(),
        tagName: title?.tagName,
        href: title instanceof HTMLAnchorElement ? title.getAttribute('href') : null,
      };
    };

    expect(renderTitle('nagul')).toEqual({ text: 'Company - Filial', tagName: 'SPAN', href: null });
    expect(renderTitle('publish')).toEqual({
      text: 'Company - Filial',
      tagName: 'SPAN',
      href: null,
    });
    expect(renderTitle('bad')).toEqual({
      text: 'Company',
      tagName: 'A',
      href: 'https://example.test/filial',
    });
  });

  it('can show filial city in footer for worker role view', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    fixture.componentInstance.review = review();
    fixture.componentInstance.showFilialCityInFooter = true;

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('footer a')?.textContent?.trim()).toBe('City');
  });

  it('does not fall back to the creation date when publication date is empty', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    fixture.componentInstance.review = review({ created: '2026-05-01', publishedDate: '' });

    fixture.detectChanges();

    expect(fixture.componentInstance.reviewDate()).toBe('Не назначено');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('footer span')?.textContent?.trim(),
    ).toBe('Не назначено');
  });

  it('shows inactive real account warning and keeps publication action available', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    fixture.componentInstance.review = review({ botActive: false });
    fixture.componentInstance.activeSection = 'publish';

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.bot-line')?.textContent?.trim()).toBe(
      'аккаунт неактивен - можно закрыть',
    );
    const publishButton = element.querySelector<HTMLButtonElement>('.publish-button');
    expect(publishButton?.textContent?.trim()).toBe('ОПУБЛИКОВАЛ');
    expect(publishButton?.disabled).toBe(false);
  });

  it('blocks publication when account credentials are incomplete', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    fixture.componentInstance.review = review({ botPassword: '' });
    fixture.componentInstance.activeSection = 'publish';

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.bot-line')?.textContent?.trim()).toBe('Bot Name 2');
    const publishButton = element.querySelector<HTMLButtonElement>('.publish-button');
    expect(publishButton?.textContent?.trim()).toBe('СМЕНИТЕ АККАУНТ');
    expect(publishButton?.disabled).toBe(true);
  });

  it('allows template accounts in walk section but blocks them in publication section', () => {
    const render = (activeSection: WorkerReviewCardComponent['activeSection']): HTMLElement => {
      const fixture = TestBed.createComponent(WorkerReviewCardComponent);
      fixture.componentInstance.review = review({
        botFio: 'Впиши Имя Фамилию',
        botActive: true,
        botCounter: 0,
      });
      fixture.componentInstance.activeSection = activeSection;
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    };

    let element = render('nagul');
    expect(element.querySelector('.bot-line')?.textContent?.trim()).toBe('Впиши Имя Фамилию');
    expect(element.querySelector<HTMLButtonElement>('.publish-button')?.disabled).toBe(false);

    element = render('publish');
    expect(element.querySelector('.bot-line')?.textContent?.trim()).toBe('смените аккаунт');
    expect(element.querySelector<HTMLButtonElement>('.publish-button')?.disabled).toBe(true);
  });

  it('marks publication date as overdue only when it is before today', () => {
    const formatDate = (date: Date): string => {
      const year = date.getFullYear();
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      return `${year}-${month}-${day}`;
    };
    const today = new Date();
    const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
    const tomorrow = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
    const renderDate = (publishedDate: string): HTMLElement => {
      const fixture = TestBed.createComponent(WorkerReviewCardComponent);
      fixture.componentInstance.review = review({ publishedDate });
      fixture.detectChanges();
      return (fixture.nativeElement as HTMLElement).querySelector('footer span') as HTMLElement;
    };

    expect(renderDate(formatDate(yesterday)).classList.contains('review-date--overdue')).toBe(true);
    expect(renderDate(formatDate(today)).classList.contains('review-date--overdue')).toBe(false);
    expect(renderDate(formatDate(tomorrow)).classList.contains('review-date--overdue')).toBe(false);
  });

  it('emits review action events without owning mutations', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    const component = fixture.componentInstance;
    let copied = '';
    let botChanged = false;
    let botDeactivated = false;
    let done = false;
    let editOpened = false;
    component.review = review();
    component.canOpenEditModal = true;
    component.copyRequested.subscribe((kind) => {
      copied = kind;
    });
    component.botChangeRequested.subscribe(() => {
      botChanged = true;
    });
    component.botDeactivateRequested.subscribe(() => {
      botDeactivated = true;
    });
    component.doneRequested.subscribe(() => {
      done = true;
    });
    component.editOpened.subscribe(() => {
      editOpened = true;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const actionButtons = element.querySelectorAll<HTMLButtonElement>('.review-actions button');
    actionButtons[0]?.click();
    actionButtons[5]?.click();
    actionButtons[6]?.click();
    element.querySelector<HTMLButtonElement>('.publish-button')?.click();
    element.querySelector<HTMLAnchorElement>('footer a')?.click();

    expect(copied).toBe('url');
    expect(botChanged).toBe(true);
    expect(botDeactivated).toBe(true);
    expect(done).toBe(true);
    expect(editOpened).toBe(true);
  });

  it('locks account actions in publication until credentials are copied', () => {
    const render = (credentialsCopied: boolean): NodeListOf<HTMLButtonElement> => {
      const fixture = TestBed.createComponent(WorkerReviewCardComponent);
      const component = fixture.componentInstance;
      component.review = review();
      component.activeSection = 'publish';
      component.requireCredentialCopyBeforeAccountAction = true;
      component.accountActionCredentialsCopied = credentialsCopied;
      fixture.detectChanges();
      return (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.review-actions button');
    };

    let buttons = render(false);
    expect(buttons[5]?.disabled).toBe(true);
    expect(buttons[6]?.disabled).toBe(true);
    expect(buttons[5]?.title).toBe('Сначала скопируйте логин и пароль аккаунта');

    buttons = render(true);
    expect(buttons[5]?.disabled).toBe(false);
    expect(buttons[6]?.disabled).toBe(false);
  });

  it('emits field and note editing events', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    const component = fixture.componentInstance;
    let fieldChange = '';
    let noteChange = '';
    let sideChange = '';
    component.review = review();
    component.editingReviewFieldKey = '17-text';
    component.reviewFieldDrafts = { '17-text': 'Old text' };
    component.editingReviewNoteId = 17;
    component.reviewNoteDrafts = { 17: 'Old note' };
    component.editingSideNoteKey = 'order-17';
    component.sideNoteDrafts = { 'order-17': 'Old side note' };
    component.reviewFieldDraftChanged.subscribe((event) => {
      fieldChange = `${event.field}:${event.value}`;
    });
    component.reviewNoteDraftChanged.subscribe((value) => {
      noteChange = value;
    });
    component.sideNoteDraftChanged.subscribe((event) => {
      sideChange = `${event.field}:${event.value}`;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const textField = element.querySelector<HTMLTextAreaElement>(
      '.review-field-editor--text textarea',
    );
    textField!.value = 'New text';
    textField!.dispatchEvent(new Event('input', { bubbles: true }));

    const noteFields = Array.from(
      element.querySelectorAll<HTMLTextAreaElement>('.review-note-popover textarea'),
    );
    const noteField = noteFields.find(
      (item) => item.getAttribute('aria-label') === 'Заметка отзыва',
    );
    noteField!.value = 'New note';
    noteField!.dispatchEvent(new Event('input', { bubbles: true }));

    const sideField = noteFields.find(
      (item) => item.getAttribute('aria-label') === 'Заметка заказа',
    );
    sideField!.value = 'New side note';
    sideField!.dispatchEvent(new Event('input', { bubbles: true }));

    expect(fieldChange).toBe('text:New text');
    expect(noteChange).toBe('New note');
    expect(sideChange).toBe('order:New side note');
  });
});
