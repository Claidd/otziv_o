import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IonContent } from '@ionic/angular/standalone';

type PublicPageKey = 'services' | 'prices' | 'payment' | 'refund' | 'offer' | 'privacy' | 'contacts' | 'receiptConsent' | 'pay';

type PublicPageContent = {
  eyebrow: string;
  title: string;
  lead: string;
  accent: string;
  sections: Array<{ icon: string; title: string; body: string; items?: string[] }>;
};

const BUSINESS = {
  shortName: 'ИП Сивохин Иван Игоревич',
  officialName: 'СИВОХИН ИВАН ИГОРЕВИЧ (ИП)',
  inn: '380124742639',
  ogrnip: '314380107100064',
  address: '665824, Россия, Иркутская область, Ангарск, ул. Фестивальная, 17, 73',
  email: '2.12nps@mail.ru',
  phone: '+7 908 643-10-55',
  taxSystem: 'АУСН доходы',
  bank: 'Филиал "Новосибирский" АО "Альфа-Банк"',
  bik: '045004774',
  account: '40802810223090001725'
};

const LINKS: Array<{ key: PublicPageKey; label: string; href: string; icon: string }> = [
  { key: 'services', label: 'Услуги', href: '/services', icon: 'rate_review' },
  { key: 'prices', label: 'Цены', href: '/prices', icon: 'payments' },
  { key: 'payment', label: 'Оплата', href: '/payment', icon: 'credit_card' },
  { key: 'refund', label: 'Возврат', href: '/refund', icon: 'undo' },
  { key: 'offer', label: 'Оферта', href: '/offer', icon: 'article' },
  { key: 'privacy', label: 'Данные', href: '/privacy', icon: 'shield' },
  { key: 'receiptConsent', label: 'Чек', href: '/receipt-consent', icon: 'mark_email_read' },
  { key: 'contacts', label: 'Контакты', href: '/contacts', icon: 'contacts' },
  { key: 'pay', label: 'Оплатить', href: '/pay', icon: 'shopping_cart' }
];

const PAGES: Record<PublicPageKey, PublicPageContent> = {
  services: {
    eyebrow: 'Что продаем',
    title: 'Услуги',
    lead: 'Дистанционные информационно-маркетинговые услуги для компаний: репутационное сопровождение, тексты, карточки организаций и работа с обратной связью.',
    accent: 'Оплата производится после выполнения и согласования заказа.',
    sections: [
      { icon: 'article', title: 'PR и публикации', body: 'Размещение информации о компании на тематических сайтах и подготовка материалов для согласованных площадок.', items: ['Тематические площадки', 'Публикации о компании', 'Информационные материалы'] },
      { icon: 'edit_note', title: 'Тексты и материалы', body: 'Уникальные тексты для сайта, справочников, тематических площадок и социальных сетей.' },
      { icon: 'business', title: 'Карточки компаний', body: 'Запуск, ведение и актуализация карточек компании в справочниках и на интернет-площадках.' },
      { icon: 'rate_review', title: 'Отзывы и обратная связь', body: 'Сбор обратной связи, подготовка ответов и сопровождение клиентской коммуникации.' },
      { icon: 'cloud_done', title: 'Формат оказания', body: 'Услуги оказываются дистанционно. Товары не продаются и не доставляются; склад, товарные остатки и поставщики товаров не используются.' }
    ]
  },
  prices: {
    eyebrow: 'Стоимость',
    title: 'Цены и тарифы',
    lead: 'Стоимость определяется по согласованной смете после оценки задачи. Итоговая сумма фиксируется в заказе до оплаты.',
    accent: 'Клиент видит сумму, компанию и услугу в платежной ссылке.',
    sections: [
      { icon: 'info', title: 'Как формируется цена', body: 'Цена зависит от состава работ, количества площадок, объема материалов и сроков.' },
      { icon: 'receipt_long', title: 'Форматы', body: 'Разовая услуга, пакет услуг или индивидуальный заказ. Во всех случаях сумма согласуется до оплаты.' }
    ]
  },
  payment: {
    eyebrow: 'Порядок расчетов',
    title: 'Оплата',
    lead: 'Оплата производится после выполнения заказа. Клиент получает платежную ссылку, проверяет услугу и сумму, затем указывает e-mail для электронного чека.',
    accent: 'Платежная ссылка и электронный чек связывают оплату с услугой, заказом и компанией клиента.',
    sections: [
      { icon: 'looks_one', title: 'Выполнение заказа', body: 'Сначала задача согласуется и выполняется, после этого формируется сумма к оплате.' },
      { icon: 'looks_two', title: 'Платежная ссылка', body: 'В ссылке отображаются компания, заказ, услуга и итоговая сумма. Для электронного чека клиент указывает e-mail.' },
      { icon: 'looks_3', title: 'Подтверждение платежа', body: 'После успешной оплаты статус поступает в систему через уведомление банка.' },
      { icon: 'receipt_long', title: 'Электронный чек', body: 'В чеке указывается услуга "Репутационное сопровождение компании в сети Интернет" и ставка без НДС.' }
    ]
  },
  refund: {
    eyebrow: 'Правила отмены',
    title: 'Возврат и отмена услуги',
    lead: 'Так как оплата производится после выполнения заказа, возврат после оплаты рассматривается по обращению клиента.',
    accent: `Заявления принимаются на ${BUSINESS.email}.`,
    sections: [
      { icon: 'assignment_return', title: 'Как обратиться', body: 'Напишите дату оплаты, сумму, e-mail из платежа, номер заказа или название компании и причину обращения.' },
      { icon: 'payments', title: 'Как возвращаются деньги', body: 'Возврат выполняется тем же способом, которым была произведена оплата, если иной порядок не согласован сторонами.' }
    ]
  },
  offer: {
    eyebrow: 'Документ',
    title: 'Публичная оферта',
    lead: `Оферта определяет условия оказания дистанционных информационно-маркетинговых услуг ${BUSINESS.shortName}. Оплата заказа или подтверждение согласия на странице оплаты означает принятие условий оферты.`,
    accent: `${BUSINESS.officialName}, ИНН ${BUSINESS.inn}, ОГРНИП ${BUSINESS.ogrnip}.`,
    sections: [
      { icon: 'badge', title: 'Стороны договора', body: `Исполнитель: ${BUSINESS.officialName}, ИНН ${BUSINESS.inn}, ОГРНИП ${BUSINESS.ogrnip}, адрес: ${BUSINESS.address}. Заказчик: лицо, заказавшее и оплатившее услугу.` },
      { icon: 'rule', title: 'Предмет оферты', body: 'Исполнитель оказывает информационно-маркетинговые услуги по согласованному заданию клиента: репутационное сопровождение, тексты, карточки организаций, обратная связь, публикации и рекламные материалы.' },
      { icon: 'task_alt', title: 'Порядок заказа и акцепт', body: 'Заказ оформляется дистанционно через сайт, платежную ссылку, счет, электронную переписку или иной согласованный канал. Акцептом считается оплата заказа или подтверждение согласия на странице оплаты.' },
      { icon: 'cloud_done', title: 'Формат оказания услуг', body: 'Услуги оказываются дистанционно. Исполнитель не продает и не доставляет товары, не хранит товарные остатки и не использует склад.' },
      { icon: 'receipt_long', title: 'Расчеты, чек и НДС', body: `Оплата принимается за услугу "Репутационное сопровождение компании в сети Интернет". Электронный чек направляется на e-mail клиента. СНО: ${BUSINESS.taxSystem}. Операции оформляются без НДС.` },
      { icon: 'verified', title: 'Приемка результата', body: 'Услуга считается оказанной после передачи результата, отчета, ссылки, материалов или иного согласованного подтверждения выполнения.' },
      { icon: 'policy', title: 'Ограничения результата', body: 'Исполнитель не гарантирует действия третьих площадок, изменение рейтинга или публикацию материалов, не соответствующих правилам сторонних сервисов.' }
    ]
  },
  privacy: {
    eyebrow: '152-ФЗ',
    title: 'Политика обработки персональных данных',
    lead: 'Политика определяет порядок обработки и защиты персональных данных при использовании сайта, регистрации, оформлении заказов, оплате услуг и коммуникации.',
    accent: `Контакт по вопросам персональных данных: ${BUSINESS.email}.`,
    sections: [
      { icon: 'groups', title: 'Субъекты и данные', body: 'Обрабатываются данные посетителей, клиентов, пользователей системы, работников и исполнителей в объеме, необходимом для целей сервиса.' },
      { icon: 'task_alt', title: 'Цели обработки', body: 'Заявки, договоры, заказы, оплаты, электронные чеки, учетные записи, безопасность и выполнение требований законодательства РФ.' },
      { icon: 'lock', title: 'Защита данных', body: 'Применяются организационные и технические меры защиты, разграничение доступа и учет действий в информационной системе.' },
      { icon: 'verified_user', title: 'Права субъекта', body: `Запросы на уточнение, удаление или отзыв согласия можно направить по e-mail: ${BUSINESS.email}.` }
    ]
  },
  contacts: {
    eyebrow: 'Связь и реквизиты',
    title: 'Контакты',
    lead: `Контактная информация и реквизиты ${BUSINESS.shortName} для клиентов, оплаты и обращений по заказам.`,
    accent: `${BUSINESS.phone}, ${BUSINESS.email}.`,
    sections: [
      { icon: 'mail', title: 'Контакты для клиентов', body: `E-mail: ${BUSINESS.email}. Телефон: ${BUSINESS.phone}.` },
      { icon: 'location_on', title: 'Адрес', body: BUSINESS.address },
      { icon: 'receipt_long', title: 'Реквизиты', body: `${BUSINESS.officialName}. ИНН ${BUSINESS.inn}. ОГРНИП ${BUSINESS.ogrnip}.` },
      { icon: 'account_balance', title: 'Банк', body: `${BUSINESS.bank}. Р/с ${BUSINESS.account}. БИК ${BUSINESS.bik}.` }
    ]
  },
  receiptConsent: {
    eyebrow: 'Электронный чек',
    title: 'Согласие на получение электронного чека',
    lead: 'При оплате заказа клиент указывает e-mail и соглашается получить кассовый чек в электронном виде.',
    accent: 'Чек направляется на e-mail, указанный клиентом при оплате.',
    sections: [
      { icon: 'mark_email_read', title: 'Согласие клиента', body: 'Указывая e-mail при оплате, клиент подтверждает согласие на получение электронного кассового чека.' },
      { icon: 'fact_check', title: 'Связь с заказом', body: 'Платежная ссылка связывает оплату и чек с услугой "Репутационное сопровождение компании в сети Интернет", заказом и компанией клиента.' }
    ]
  },
  pay: {
    eyebrow: 'Форма',
    title: 'Оплата услуги',
    lead: 'Откройте платежную ссылку из сообщения менеджера. Компания, услуга и сумма подтянутся автоматически.',
    accent: 'Если у вас нет ссылки, свяжитесь с менеджером или напишите нам.',
    sections: [
      { icon: 'security', title: 'Безопасность оплаты', body: 'Платежные данные вводятся на стороне платежной формы банка. Сайт не хранит реквизиты карт.' }
    ]
  }
};

@Component({
  selector: 'app-public-mobile-page',
  imports: [FormsModule, IonContent, RouterLink],
  template: `
    <div class="ion-page public-shell">
      <ion-content fullscreen>
        <main class="public-page">
          <header class="public-hero">
            <a class="brand" routerLink="/">Компания <strong>О!</strong></a>
            <p>{{ page().eyebrow }}</p>
            <h1>{{ page().title }}</h1>
            <span>{{ page().lead }}</span>
          </header>

          <nav class="public-links" aria-label="Публичные разделы">
            @for (link of links; track link.key) {
              <a [routerLink]="link.href" [class.active]="link.key === pageKey()">
                <span class="material-icons-sharp">{{ link.icon }}</span>
                {{ link.label }}
              </a>
            }
          </nav>

          <section class="accent-card">
            <span class="material-icons-sharp">info</span>
            <strong>{{ page().accent }}</strong>
          </section>

          <section class="public-list">
            @for (section of page().sections; track section.title) {
              <article>
                <span class="material-icons-sharp">{{ section.icon }}</span>
                <div>
                  <h2>{{ section.title }}</h2>
                  <p>{{ section.body }}</p>
                  @if (section.items?.length) {
                    <div class="chips">
                      @for (item of section.items; track item) {
                        <small>{{ item }}</small>
                      }
                    </div>
                  }
                </div>
              </article>
            }
          </section>
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content{--background:#f6f8fc}.public-page{display:grid;gap:.8rem;max-width:48rem;margin:0 auto;padding:calc(1rem + env(safe-area-inset-top)) .85rem calc(1.2rem + env(safe-area-inset-bottom));font-family:var(--otziv-font-family)}
    .public-hero,.accent-card,.public-list article{border:1px solid rgba(103,116,131,.16);border-radius:1rem;background:linear-gradient(155deg,var(--otziv-white),var(--otziv-tone-walk-surface));box-shadow:0 .9rem 1.8rem rgba(132,139,200,.12)}
    .public-hero{display:grid;gap:.52rem;padding:1.1rem}.brand{color:var(--otziv-dark);font:900 1.15rem/1 var(--otziv-card-title-font);text-decoration:none}.brand strong{color:var(--otziv-danger)}.public-hero p{margin:0;color:var(--otziv-info);font-size:.72rem;font-weight:1000;text-transform:uppercase}.public-hero h1{margin:0;color:var(--otziv-dark);font-size:2rem;line-height:1}.public-hero span{color:var(--otziv-info);font-weight:800;line-height:1.45}
    .public-links{display:flex;gap:.45rem;overflow-x:auto;padding:.08rem}.public-links::-webkit-scrollbar{display:none}.public-links a{display:inline-flex;align-items:center;gap:.32rem;min-height:2.28rem;border:1px solid rgba(108,155,207,.22);border-radius:999px;padding:0 .72rem;color:var(--otziv-primary);background:var(--otziv-white);font-size:.72rem;font-weight:1000;text-decoration:none;white-space:nowrap}.public-links a.active{color:#fff;background:var(--otziv-primary)}
    .accent-card{display:grid;grid-template-columns:auto minmax(0,1fr);align-items:center;gap:.55rem;padding:.8rem;color:#16735f}.accent-card .material-icons-sharp{font-size:1.25rem}
    .public-list{display:grid;gap:.62rem}.public-list article{display:grid;grid-template-columns:auto minmax(0,1fr);gap:.65rem;padding:.85rem}.public-list article>.material-icons-sharp{display:grid;place-items:center;width:2.2rem;height:2.2rem;border-radius:.8rem;color:var(--otziv-primary);background:rgba(108,155,207,.13)}.public-list h2{margin:0 0 .25rem;color:var(--otziv-dark);font-size:1rem}.public-list p{margin:0;color:var(--otziv-info);font-size:.82rem;font-weight:800;line-height:1.45}.chips{display:flex;flex-wrap:wrap;gap:.35rem;margin-top:.55rem}.chips small{border-radius:999px;padding:.32rem .55rem;color:#16735f;background:rgba(74,198,177,.13);font-weight:900}
  `]
})
export class PublicPage {
  readonly links = LINKS;
  readonly pageKey = computed<PublicPageKey>(() => {
    const page = this.route.snapshot.data['page'];
    return typeof page === 'string' && page in PAGES ? page as PublicPageKey : 'services';
  });
  readonly page = computed(() => PAGES[this.pageKey()]);

  constructor(private readonly route: ActivatedRoute) {}
}
