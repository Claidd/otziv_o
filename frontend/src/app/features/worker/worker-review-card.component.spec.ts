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

  it('edits the account name inline during walk with explicit save and without hiding its counter', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    const component = fixture.componentInstance;
    component.review = review({ botFio: 'Old Name', botCounter: 3 });
    component.activeSection = 'nagul';
    component.canInlineEditBotName = true;
    let savedName = '';
    component.botNameSaveRequested.subscribe((value) => {
      savedName = value;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.bot-counter')?.textContent?.trim()).toBe('3');
    element.querySelector<HTMLButtonElement>('.bot-name-button')?.click();
    fixture.detectChanges();

    const input = element.querySelector<HTMLInputElement>('.bot-name-input');
    expect(input?.value).toBe('Old Name');
    expect(element.querySelector('.bot-name-actions')).not.toBeNull();
    input!.value = 'New Name';
    input!.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    element.querySelector<HTMLButtonElement>('.bot-name-action--save')?.click();
    fixture.detectChanges();

    expect(savedName).toBe('New Name');
    expect(element.querySelector('.bot-counter')?.textContent?.trim()).toBe('3');
  });

  it('cancels inline account name editing without saving', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    const component = fixture.componentInstance;
    component.review = review({ botFio: 'Old Name', botCounter: 3 });
    component.activeSection = 'nagul';
    component.canInlineEditBotName = true;
    let savedName = '';
    component.botNameSaveRequested.subscribe((value) => {
      savedName = value;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.bot-name-button')?.click();
    fixture.detectChanges();

    const input = element.querySelector<HTMLInputElement>('.bot-name-input');
    input!.value = 'New Name';
    input!.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    element.querySelector<HTMLButtonElement>('.bot-name-action--cancel')?.click();
    fixture.detectChanges();

    expect(savedName).toBe('');
    expect(element.querySelector('.bot-name-button')?.textContent?.trim()).toBe('Old Name');
    expect(element.querySelector('.bot-counter')?.textContent?.trim()).toBe('3');
  });

  it('groups everything after the review text into the adaptive card footer', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    fixture.componentInstance.review = review();

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const card = element.querySelector('.review-card');
    const cardFooter = card?.querySelector('.review-card-footer');

    expect(card?.children.item(1)?.classList.contains('review-field-editor--text')).toBe(true);
    expect(card?.children.item(2)).toBe(cardFooter);
    expect(cardFooter?.querySelector('.review-field-editor--answer')).not.toBeNull();
    expect(cardFooter?.querySelector('.bot-line')).not.toBeNull();
    expect(cardFooter?.querySelector('.review-actions')).not.toBeNull();
    expect(cardFooter?.querySelector('.publish-button')).not.toBeNull();
    expect(cardFooter?.lastElementChild?.tagName).toBe('FOOTER');
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

  it('disables review text copying only in the walk section', () => {
    const renderTextButton = (activeSection: WorkerReviewCardComponent['activeSection']) => {
      const fixture = TestBed.createComponent(WorkerReviewCardComponent);
      const component = fixture.componentInstance;
      component.review = review();
      component.activeSection = activeSection;
      let copiedKind = '';
      component.copyRequested.subscribe((kind) => {
        copiedKind = kind;
      });

      fixture.detectChanges();

      return {
        button: (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
          '.review-action--text',
        )!,
        copiedKind: () => copiedKind,
      };
    };

    const walk = renderTextButton('nagul');
    expect(walk.button.disabled).toBe(true);
    expect(walk.button.title).toBe('Копирование текста недоступно в разделе «Выгул»');
    walk.button.click();
    expect(walk.copiedKind()).toBe('');

    const publication = renderTextButton('publish');
    expect(publication.button.disabled).toBe(false);
    publication.button.click();
    expect(publication.copiedKind()).toBe('text');
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

    expect(renderTitle('nagul')).toEqual({ text: 'Company - Filial', tagName: 'BUTTON', href: null });
    expect(renderTitle('publish')).toEqual({
      text: 'Company - Filial',
      tagName: 'BUTTON',
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

  it('shows recovery planned date editor for privileged recovery cards', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    const component = fixture.componentInstance;
    component.review = review({
      recoveryTask: true,
      recoveryTaskId: 92,
      recoveryTaskScheduledDate: '2026-07-04'
    });
    component.activeSection = 'recovery';
    component.canEditRecoveryTaskDate = true;
    let edited = false;
    let draft = '';
    let saved = false;
    component.recoveryTaskDateEditStarted.subscribe(() => {
      edited = true;
    });
    component.recoveryTaskDateDraftChanged.subscribe((value) => {
      draft = value;
    });
    component.recoveryTaskDateSaveRequested.subscribe(() => {
      saved = true;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const input = element.querySelector<HTMLInputElement>('.recovery-date-editor input');
    expect(component.recoveryTaskDateValue()).toBe('2026-07-04');

    input?.dispatchEvent(new Event('focus'));
    component.recoveryTaskDateDrafts = { 92: '2026-07-09' };
    component.editingRecoveryTaskDateId = 92;
    fixture.detectChanges();

    const saveButton = element.querySelector<HTMLButtonElement>('.recovery-date-editor .save');
    expect(edited).toBe(true);
    expect(saveButton?.disabled).toBe(false);

    input!.value = '2026-07-10';
    input?.dispatchEvent(new Event('input'));
    saveButton?.click();

    expect(draft).toBe('2026-07-10');
    expect(saved).toBe(true);
  });

  it('expands and emits full review title for worker role view', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    const component = fixture.componentInstance;
    let copiedTitle = '';
    component.review = review();
    component.activeSection = 'publish';
    component.showFilialCityInFooter = true;
    component.titleCopyRequested.subscribe((title) => {
      copiedTitle = title;
    });

    fixture.detectChanges();

    const title = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.review-title');
    expect(title?.textContent?.trim()).toBe('Company - Filial');

    title?.click();
    fixture.detectChanges();

    expect(title?.textContent?.trim()).toBe('Company - Filial - City');

    title?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, detail: 2 }));

    expect(copiedTitle).toBe('Company - Filial - City');
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
    let repairMessage = '';
    let doneEmitted = false;
    fixture.componentInstance.accountRepairRequested.subscribe((message) => {
      repairMessage = message;
    });
    fixture.componentInstance.doneRequested.subscribe(() => {
      doneEmitted = true;
    });

    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.bot-line')?.textContent?.trim()).toBe('Bot Name 2');
    const publishButton = element.querySelector<HTMLButtonElement>('.publish-button');
    expect(publishButton?.textContent?.trim()).toBe('СМЕНИТЕ АККАУНТ');
    expect(publishButton?.disabled).toBe(false);
    const actionButtons = element.querySelectorAll<HTMLButtonElement>('.review-actions button');
    expect(actionButtons[1]?.textContent?.trim()).toBe('логин');
    expect(actionButtons[1]?.disabled).toBe(true);
    expect(actionButtons[2]?.textContent?.trim()).toBe('пароль');
    expect(actionButtons[2]?.disabled).toBe(true);
    publishButton?.click();
    expect(doneEmitted).toBe(false);
    expect(repairMessage).toContain('нет логина или пароля');
  });

  it('disables login and password in the new section', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    const component = fixture.componentInstance;
    component.review = review();
    component.activeSection = 'new';

    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLButtonElement>('.review-actions button');
    expect(buttons[1]?.disabled).toBe(true);
    expect(buttons[2]?.disabled).toBe(true);
    expect(buttons[1]?.title).toBe('В разделе «Новые» логин и пароль недоступны');
    expect(component.reviewCredentialCopyDisabled('login')).toBe(true);
    expect(component.reviewCredentialCopyDisabled('password')).toBe(true);
  });

  it('blocks publication while credential wait timer is active', () => {
    const fixture = TestBed.createComponent(WorkerReviewCardComponent);
    fixture.componentInstance.review = review();
    fixture.componentInstance.activeSection = 'publish';
    fixture.componentInstance.publishLockedByCredentialWait = true;
    fixture.componentInstance.publishCredentialWaitTitle = 'После копирования логина и пароля подождите еще 150 сек.';

    fixture.detectChanges();

    const publishButton = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.publish-button');
    expect(publishButton?.textContent?.trim()).toBe('ОПУБЛИКОВАЛ');
    expect(publishButton?.disabled).toBe(true);
    expect(publishButton?.classList.contains('publish-button--credential-locked')).toBe(true);
    expect(publishButton?.title).toBe('После копирования логина и пароля подождите еще 150 сек.');
  });

  it('allows template-named accounts in walk section but blocks publication with explanation', () => {
    const render = (activeSection: WorkerReviewCardComponent['activeSection']) => {
      const fixture = TestBed.createComponent(WorkerReviewCardComponent);
      fixture.componentInstance.review = review({
        botFio: 'Впиши Имя Фамилию',
        botActive: true,
        botCounter: 0,
      });
      fixture.componentInstance.activeSection = activeSection;
      let repairMessage = '';
      let doneEmitted = false;
      fixture.componentInstance.accountRepairRequested.subscribe((message) => {
        repairMessage = message;
      });
      fixture.componentInstance.doneRequested.subscribe(() => {
        doneEmitted = true;
      });
      fixture.detectChanges();
      return { element: fixture.nativeElement as HTMLElement, getRepairMessage: () => repairMessage, getDoneEmitted: () => doneEmitted };
    };

    let { element, getRepairMessage, getDoneEmitted } = render('nagul');
    expect(element.querySelector('.bot-line')?.textContent?.trim()).toBe('Впиши Имя Фамилию');
    expect(element.querySelector<HTMLButtonElement>('.publish-button')?.textContent?.trim()).toBe('ВЫГУЛЯЛ');
    expect(element.querySelector<HTMLButtonElement>('.publish-button')?.disabled).toBe(false);
    element.querySelector<HTMLButtonElement>('.publish-button')?.click();
    expect(getDoneEmitted()).toBe(true);
    expect(getRepairMessage()).toBe('');

    ({ element, getRepairMessage, getDoneEmitted } = render('publish'));
    expect(element.querySelector('.bot-line')?.textContent?.trim()).toBe('Впиши Имя Фамилию');
    expect(element.querySelector<HTMLButtonElement>('.publish-button')?.textContent?.trim()).toBe('НУЖЕН ВЫГУЛ');
    expect(element.querySelector<HTMLButtonElement>('.publish-button')?.disabled).toBe(false);
    const publishActionButtons = element.querySelectorAll<HTMLButtonElement>('.review-actions button');
    expect(publishActionButtons[1]?.disabled).toBe(true);
    expect(publishActionButtons[2]?.disabled).toBe(true);
    element.querySelector<HTMLButtonElement>('.publish-button')?.click();
    expect(getDoneEmitted()).toBe(false);
    expect(getRepairMessage()).toContain('новый невыгулянный аккаунт');
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

    const editLink = element.querySelector<HTMLAnchorElement>('.review-edit-link');
    expect(editLink?.getAttribute('aria-label')).toBe('Редактировать отзыв: Worker');
    expect(copied).toBe('url');
    expect(botChanged).toBe(true);
    expect(botDeactivated).toBe(true);
    expect(done).toBe(true);
    expect(editOpened).toBe(true);
  });

  it('keeps account change available but locks blocking until credentials are copied', () => {
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
    expect(buttons[5]?.disabled).toBe(false);
    expect(buttons[6]?.disabled).toBe(true);
    expect(buttons[5]?.title).toBe('Сменить аккаунт');
    expect(buttons[6]?.title).toBe('Сначала скопируйте логин и пароль аккаунта');

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
