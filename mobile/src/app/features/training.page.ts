import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IonContent } from '@ionic/angular/standalone';
import { MobileHeaderComponent } from '../shared/mobile-header.component';

type AuditTrainingSection = {
  title: string;
  icon: string;
  items: readonly string[];
};

const AUDIT_TRAINING_SECTIONS: readonly AuditTrainingSection[] = [
  {
    title: 'Как формируется аудит',
    icon: 'analytics',
    items: [
      'После рабочего дня система собирает фактические данные: сообщения клиентов, ответы сотрудников, действия с карточками, заказы, статусы, просрочки, риски и время выполнения.',
      'Для каждого примера сохраняются компания, текст клиента, найденный ответ менеджера, время переписки и способ закрытия карточки.',
      'Отдельно проверяется, был ли реальный ответ перед кнопками «Отвечено» и «Не требует ответа».',
      'Серии закрытий за считаные секунды отмечаются как возможное прокликивание без чтения переписки.',
      'В аудит входит работа с рисками: качество объяснений, итоговые решения и просрочки.',
      'DeepSeek анализирует смысл уже собранных фактов. Числа, сообщения и действия берутся из системы, а не создаются моделью.',
      'Кроме текущего дня учитывается динамика: повторяются ли формальные ответы, закрытия без ответа и быстрые серии действий.'
    ]
  },
  {
    title: 'Какие проблемы ищет система',
    icon: 'manage_search',
    items: [
      'Клиент задал вопрос или сообщил о проблеме, но ответа после этого нет.',
      'Карточка закрыта как отвеченная, хотя в переписке нет ответа менеджера.',
      'Выбрано «Не требует ответа» для вопроса об оплате, сроке, публикации или исправлении.',
      'Ответ слишком общий: «Хорошо», «Проверим», «Понял» — без решения, срока или следующего шага.',
      'Ответ не относится к вопросу либо закрывает только одну часть сообщения.',
      'Несколько карточек закрыты почти одновременно без подтверждённого результата.',
      'Остались просроченные заказы, сообщения или риски.',
      'Решение по риску принято без проверки заказа, переписки и истории действий.'
    ]
  },
  {
    title: 'Как изучать отчёт',
    icon: 'menu_book',
    items: [
      'Отчёт приходит во внутреннюю Telegram-группу менеджера и владельца. Нажмите «Изучить отчёт»: сообщение раскроется полностью.',
      'Если группа ещё не подключена, администратор сохраняет её ссылку в разделе «Пользователи → Группа аудита менеджера» и нажимает «Привязать Telegram». Команда /auditgroup остаётся резервным способом. Клиентские группы для аудита не используются.',
      'Прочитайте общий вывод, а затем каждый пример с названием компании, сообщением клиента, ответом и действием менеджера.',
      'По каждому примеру определите: что хотел клиент, что фактически сделано и какое действие полностью решило бы запрос.',
      'Различайте отсутствие ответа, формальный ответ, ответ не по теме и ошибочное определение автора сообщения.',
      'В блоке быстрых действий проверяйте, открывалась ли каждая переписка и был ли зафиксирован результат.',
      'Нажимайте «Подтвердить прочтение» только после полного просмотра. Мгновенное подтверждение система не принимает.'
    ]
  },
  {
    title: 'Как отвечать на вопросы',
    icon: 'quiz',
    items: [
      'Бот задаёт вопросы по одному. Нажмите «Ответить» и отправьте текст именно ответом на сообщение бота.',
      'Аудит и вопросы защищены средствами Telegram от обычного копирования, сохранения и пересылки, чтобы клиентские данные оставались внутри служебной группы.',
      'Запрос клиента уже полностью приведён в вопросе. Чек-лист «В ответе укажите» содержит название компании, ваш фактический ответ или действие и правильный ответ или необходимое действие. Повторять запрос и угадывать скрытые требования не нужно.',
      'Ответ должен быть не длиннее 420 символов. Почти дословная копия аудита не принимается — сформулируйте вывод своими словами.',
      'Владелец видит в этой же группе открытие отчёта, вопросы, ответы менеджера, их оценку и окончательное принятие аудита, но пройти проверку вместо менеджера не может.',
      'Правильный ответ содержит название компании, ваш фактический ответ или действие и правильное действие.',
      'Пример: «Компания Ромашка. Я не назвала срок публикации; нужно было подтвердить график и назвать дату следующего отзыва».',
      'Для закрытой карточки укажите компанию, что вы фактически ответили или сделали и как нужно было поступить правильно.',
      'Для быстрых кликов объясните, как проверялись переписка и результат по каждой карточке.',
      'Фразы «понял», «исправлю», «буду внимательнее» и пересказ вопроса без фактов не принимаются.',
      'Ответ принимается только при оценке не ниже 75 из 100 и наличии всех обязательных фактов.',
      'Если ответ неполный, бот спрашивает только о недостающем пункте, а новый ответ дополняет предыдущую попытку.',
      'Отвечайте кратко своими словами. Для почти мгновенного длинного или шаблонного ответа бот попросит коротко назвать личное действие; это сигнал для проверки, а не автоматическое обвинение.',
      'Если DeepSeek временно недоступен, ответ сохраняется, трёхчасовой срок приостанавливается и доступ из-за сбоя не ограничивается.',
      'После всех правильных ответов приходит «Отчёт принят»; все попытки и оценки сохраняются в истории.'
    ]
  },
  {
    title: 'Срок и ограничение доступа',
    icon: 'schedule',
    items: [
      'На проверку даётся три часа с первого входа в личный кабинет или нажатия «Изучить отчёт» — с более раннего события.',
      'В мобильном кабинете показываются тост, постоянная карточка статуса и прогресс ответов.',
      'После просрочки рабочие вкладки скрываются, а рабочие маршруты и API блокируются. Личный кабинет и Telegram остаются доступными.',
      'Время недоступности DeepSeek не входит в трёхчасовой срок и автоматически добавляется к дедлайну.',
      'После правильных ответов на все вопросы доступ восстанавливается автоматически.',
      'Если замечаний нет, отчёт принимается автоматически и бот сообщает «Вы молодец!».'
    ]
  },
  {
    title: 'Если аудит ошибся',
    icon: 'gavel',
    items: [
      'Нажмите «Сообщить о неточности» и укажите конкретный неверный вывод.',
      'Напишите компанию или заказ, фактическое событие, правильного автора, время и место, где это можно проверить.',
      'Фраз «это не я» или «аудит неверный» без проверяемых фактов недостаточно.',
      'На время проверки спора владельцем трёхчасовой срок и ограничение приостанавливаются.',
      'Подтверждённая ошибка закрывает аудит. Если отчёт верен, менеджер возвращается к чтению или вопросам и получает новый срок.'
    ]
  },
  {
    title: 'Как улучшать работу',
    icon: 'trending_up',
    items: [
      'Перед закрытием карточки откройте переписку и убедитесь, что последний вопрос действительно решён.',
      'Полный ответ содержит решение, срок или следующий шаг — клиент понимает, что произойдёт дальше.',
      '«Не требует ответа» подходит для благодарности после решённого вопроса, но не для просьбы, проблемы, оплаты или срока.',
      'Не очищайте очередь быстрыми сериями. Каждое закрытие должно подтверждаться перепиской.',
      'По рискам сначала проверяйте факты, затем принимайте решение и записывайте конкретное обоснование.',
      'Сравнивайте новые отчёты с предыдущими: хорошая динамика — меньше повторных замечаний и больше полных ответов клиентам.',
      'Цель аудита — не формальный балл, а своевременный понятный ответ и проверяемый результат по каждой карточке.'
    ]
  }
];

@Component({
  selector: 'app-training-mobile',
  imports: [IonContent, MobileHeaderComponent, RouterLink],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Обучение" />

      <ion-content fullscreen>
        <main class="training-page">
          <section class="training-hero">
            <span class="material-icons-sharp">fact_check</span>
            <div>
              <p class="eyebrow">МЕНЕДЖЕР · ПРОВЕРКА АУДИТА</p>
              <h1>Как изучить отчёт и пройти проверку</h1>
              <p>Аудит помогает разобрать конкретные ситуации за день и закрепить правильную работу с клиентами.</p>
            </div>
          </section>

          <section class="training-note">
            <span class="material-icons-sharp">info</span>
            <p>
              Проверка завершается после чтения и правильных ответов по замечаниям.
              Простого открытия сообщения или нажатия кнопки недостаточно.
            </p>
          </section>

          <section class="training-sections" aria-label="Главы обучения">
            @for (section of sections; track section.title; let first = $first) {
              <details [open]="first">
                <summary>
                  <span class="material-icons-sharp">{{ section.icon }}</span>
                  <strong>{{ section.title }}</strong>
                  <span class="material-icons-sharp chevron">expand_more</span>
                </summary>
                <ul>
                  @for (item of section.items; track item) {
                    <li>{{ item }}</li>
                  }
                </ul>
              </details>
            }
          </section>

          <a class="cabinet-link" routerLink="/tabs/home/profile">
            <span class="material-icons-sharp">person</span>
            Вернуться в личный кабинет
          </a>
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .training-page {
      display: grid;
      gap: 0.72rem;
      min-height: 100%;
      padding: 0.78rem max(0.72rem, env(safe-area-inset-right)) calc(1rem + env(safe-area-inset-bottom)) max(0.72rem, env(safe-area-inset-left));
      background: var(--otziv-light);
    }

    .training-hero,
    .training-note,
    details,
    .cabinet-link {
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      background: var(--otziv-white);
      box-shadow: 0 0.72rem 1.35rem rgba(132, 139, 200, 0.1);
    }

    .training-hero {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: start;
      gap: 0.68rem;
      padding: 0.88rem;
    }

    .training-hero > .material-icons-sharp {
      display: grid;
      width: 2.7rem;
      height: 2.7rem;
      place-items: center;
      border-radius: 0.85rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    h1,
    p {
      margin: 0;
    }

    h1 {
      margin-top: 0.12rem;
      color: var(--otziv-dark);
      font-family: var(--otziv-card-title-font);
      font-size: 1.18rem;
      line-height: 1.12;
    }

    .training-hero p:not(.eyebrow) {
      margin-top: 0.42rem;
      color: var(--otziv-info);
      font-size: 0.72rem;
      font-weight: 800;
      line-height: 1.4;
    }

    .eyebrow {
      color: var(--otziv-primary);
      font-size: 0.58rem;
      font-weight: 1000;
      letter-spacing: 0.06em;
    }

    .training-note {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: start;
      gap: 0.55rem;
      border-color: rgba(231, 180, 52, 0.32);
      padding: 0.72rem;
      color: #745500;
      background: linear-gradient(155deg, #fff9e9, var(--otziv-white));
    }

    .training-note .material-icons-sharp {
      font-size: 1.2rem;
    }

    .training-note p {
      font-size: 0.69rem;
      font-weight: 850;
      line-height: 1.4;
    }

    .training-sections {
      display: grid;
      gap: 0.58rem;
    }

    details {
      overflow: hidden;
    }

    summary {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.55rem;
      min-height: 3.15rem;
      padding: 0.68rem 0.78rem;
      color: var(--otziv-dark);
      cursor: pointer;
      list-style: none;
    }

    summary::-webkit-details-marker {
      display: none;
    }

    summary > .material-icons-sharp:first-child {
      color: var(--otziv-primary);
      font-size: 1.22rem;
    }

    summary strong {
      font-size: 0.82rem;
      font-weight: 950;
      line-height: 1.2;
    }

    .chevron {
      color: var(--otziv-info);
      transition: transform 160ms ease;
    }

    details[open] .chevron {
      transform: rotate(180deg);
    }

    details ul {
      display: grid;
      gap: 0.55rem;
      margin: 0;
      border-top: 1px solid rgba(103, 116, 131, 0.12);
      padding: 0.74rem 0.9rem 0.82rem 1.72rem;
      color: var(--otziv-info);
      background: linear-gradient(155deg, rgba(248, 251, 255, 0.9), var(--otziv-white));
    }

    details li {
      padding-left: 0.12rem;
      font-size: 0.7rem;
      font-weight: 800;
      line-height: 1.42;
    }

    details li::marker {
      color: var(--otziv-primary);
    }

    .cabinet-link {
      display: inline-flex;
      min-height: 2.8rem;
      align-items: center;
      justify-content: center;
      gap: 0.45rem;
      color: var(--otziv-primary);
      font-size: 0.75rem;
      font-weight: 950;
      text-decoration: none;
    }
  `]
})
export class TrainingPage {
  readonly sections = AUDIT_TRAINING_SECTIONS;
}
