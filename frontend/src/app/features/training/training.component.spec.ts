import { TRAINING_TABS } from './training.component';

describe('training account assignment tab', () => {
  const tab = TRAINING_TABS.accounts;
  const blockText = tab.blocks
    .flatMap((block) => [block.title, ...block.items])
    .join(' ');

  it('is available only to owners and administrators', () => {
    expect(tab.roles).toEqual(['ADMIN', 'OWNER']);
    expect(tab.roles).not.toContain('MANAGER');
    expect(tab.roles).not.toContain('WORKER');
  });

  it('documents account assignment for new orders', () => {
    expect(blockText).toContain('Назначение аккаунтов новым заказам');
    expect(blockText).toContain('счетчик не меньше 2');
    expect(blockText).toContain('счетчиком 0 или 1');
  });

  it('documents change and block actions in walk and publication sections', () => {
    expect(tab.blocks.map((block) => block.title)).toEqual(
      expect.arrayContaining([
        'Кнопка «Смена» в «Выгуле»',
        'Кнопка «Блок» в «Выгуле»',
        '«Смена» и «Блок» в «Публикации»',
      ]),
    );
    expect(blockText).toContain('цикла A → B → A');
    expect(blockText).toContain('карточка появляется в «Выгуле»');
  });

  it('explains that rejection history is isolated and cleaned up', () => {
    expect(blockText).toContain('История хранится по ключу «карточка отзыва + аккаунт»');
    expect(blockText).toContain('После успешной публикации вся история этой карточки удаляется');
  });
});
