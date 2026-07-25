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
    expect(blockText).toContain('ручном добавлении новых отзывов');
    expect(blockText).toContain('общего пула проходит те же проверки');
  });

  it('documents the shared guard for every assignment path', () => {
    expect(blockText).toContain('Единая проверка при любом назначении');
    expect(blockText).toContain('live-таблице или физическом архиве');
    expect(blockText).toContain('активной задачей плохого отзыва');
    expect(blockText).toContain('повторно блокирует строку аккаунта');
    expect(blockText).toContain('назначение завершается ошибкой');
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

  it('documents bad reviews, recovery and safe owner troubleshooting', () => {
    expect(blockText).toContain('Плохие отзывы и восстановление');
    expect(blockText).toContain('Первичная задача плохого отзыва продолжает работу');
    expect(blockText).toContain('отдельный свободный подготовленный аккаунт');
    expect(blockText).toContain('Что проверять владельцу и администратору');
    expect(blockText).toContain('автоматически не переназначает');
    expect(blockText).toContain('Не исправляйте назначения прямым редактированием таблиц');
  });
});

describe('manager problem handling training', () => {
  const managerText = TRAINING_TABS.manager.blocks
    .flatMap((block) => [block.title, ...block.items])
    .join(' ');

  it('documents the risk workflow without extra forms', () => {
    expect(managerText).toContain('Работа с проблемами');
    expect(managerText).toContain('DeepSeek');
    expect(managerText).toContain('На ответ даётся 3 часа');
    expect(managerText).toContain('раздел "Специалист" будет ограничен');
    expect(managerText).toContain('просто переслать пояснение владельцу вместо решения нельзя');
  });
});
