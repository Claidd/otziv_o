import { Component, Input, OnDestroy, OnInit, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { IonModal } from '@ionic/angular/standalone';
import { firstValueFrom, forkJoin } from 'rxjs';
import {
  AdminBot,
  BotImportResponse,
  AdminCategory,
  AdminCity,
  AdminClientMessageMonitor,
  AdminClientMessageMonitorAttempt,
  AdminClientMessageMonitorQueueItem,
  AdminClientMessageMonitorScenario,
  AdminClientMessageSettings,
  AdminManagerText,
  AdminProduct,
  AdminPromoText,
  AdminSubCategory,
  ApiService,
  DictionaryOption,
  OperatorPhone,
  OperatorPhoneRequest,
  PromoButtonSlot,
  PromoTextAssignment,
  ClientMessageSettingsRequest
} from '../core/api.service';
import { MobileSearchBarComponent } from './mobile-search-bar.component';
import { MobileStatusSliderComponent, type MobileStatusItem } from './mobile-status-slider.component';

type DictionaryTabKey = 'categories' | 'cities' | 'products' | 'phones' | 'accounts' | 'promo' | 'managerTexts' | 'settings' | 'autoresponder' | 'autoresponderMonitor';
type EditorKind = Exclude<DictionaryTabKey, 'settings' | 'autoresponder' | 'autoresponderMonitor'> | 'subcategory';

interface DictionaryTab {
  key: DictionaryTabKey;
  title: string;
  icon: string;
  description: string;
  roles: 'all' | 'admin';
}

interface SettingsRow {
  title: string;
  value: string;
  detail: string;
  icon: string;
}

type Draft = Record<string, string | number | boolean | null>;

const TABS: DictionaryTab[] = [
  { key: 'categories', title: 'Категории', icon: 'category', description: 'структура услуг и подкатегорий', roles: 'all' },
  { key: 'cities', title: 'Города', icon: 'location_city', description: 'города для карточек и фильтров', roles: 'admin' },
  { key: 'products', title: 'Продукты', icon: 'inventory_2', description: 'позиции заказов и справочные цены', roles: 'admin' },
  { key: 'phones', title: 'Телефоны', icon: 'phone_iphone', description: 'номера, лимиты и устройства', roles: 'admin' },
  { key: 'accounts', title: 'Аккаунты', icon: 'manage_accounts', description: 'боты и рабочие профили', roles: 'admin' },
  { key: 'promo', title: 'Промо', icon: 'smart_button', description: 'тексты и назначения кнопок', roles: 'admin' },
  { key: 'managerTexts', title: 'Тексты', icon: 'article', description: 'сообщения менеджеров', roles: 'admin' },
  { key: 'settings', title: 'Настройки', icon: 'tune', description: 'рассылки, выгул и синхронизация', roles: 'admin' },
  { key: 'autoresponder', title: 'Автоответчик', icon: 'mark_chat_unread', description: 'клиентские автонапоминания', roles: 'admin' },
  { key: 'autoresponderMonitor', title: 'Мониторинг', icon: 'monitor_heart', description: 'очередь, сценарии и попытки', roles: 'admin' }
];

const SETTINGS_DEFAULTS = {
  nagulCooldownMinutes: 60,
  nagulLookaheadDays: 60,
  morningReportEnabled: true,
  morningReportTime: '09:00',
  eveningReportEnabled: true,
  eveningReportTime: '18:00',
  telegramReportZone: 'Asia/Irkutsk',
  whatsAppGroupSyncEnabled: false,
  whatsAppGroupSyncIntervalMinutes: 60,
  clientPublicationProgressReportsEnabled: true,
  whatsAppGroupSyncLastRunAt: '',
  whatsAppGroupSyncLastLinkedCount: 0,
  telegramMorningLastRunKey: '',
  telegramEveningLastRunKey: ''
};

const CLIENT_MESSAGE_MONITOR_POLLING_MS = 60000;

const CLIENT_MESSAGE_DEFAULTS: AdminClientMessageSettings = {
  workerEnabled: true,
  liveEnabled: true,
  monitorEnabled: false,
  reviewCheckEnabled: true,
  paymentReminderEnabled: true,
  badReviewInvoiceEnabled: true,
  paymentOverdueEnabled: true,
  paymentOverdueLiveEnabled: false,
  archiveReorderEnabled: true,
  reviewCheckIntervalDays: 2,
  paymentReminderIntervalDays: 2,
  paymentOverdueDays: 30,
  archiveReorderMonths: 3,
  retentionDays: 90,
  tickBatchSize: 5,
  candidateLimit: 200,
  dailyLimit: 140,
  defaultGapSeconds: 180,
  whatsAppGapSeconds: 180,
  telegramGapSeconds: 90,
  maxGapSeconds: 90,
  businessWindows: '10:00-12:00,14:00-17:00,19:00-21:00',
  reviewCheckStatuses: 'На проверке',
  paymentReminderStatuses: 'Выставлен счет,Напоминание',
  paymentOverdueStatuses: 'Выставлен счет,Напоминание',
  closedOrderStatuses: 'Оплачено,Архив,Бан,Не оплачено',
  paymentOverdueTargetStatus: 'Не оплачено',
  archiveCompanyStatus: 'На стопе',
  archiveInactiveOrderStatuses: 'Оплачено,Архив,Бан',
  openNextOrderRequestStatuses: 'PENDING,FAILED',
  reviewLinkBaseUrl: 'https://o-ogo.ru',
  reviewReminderText: '{companyAndFilial}\n\nПроверьте, пожалуйста, отзывы по ссылке: {reviewLink}',
  paymentInstructionSource: 'MANAGER_TEXT',
  paymentReminderText: '{companyAndFilial}\n\n{managerPayText} К оплате: {sum} руб.',
  archiveOfferText: '{company}, добрый день! Хотите повторить заказ?'
};

@Component({
  selector: 'app-mobile-dictionaries',
  imports: [FormsModule, IonModal, MobileSearchBarComponent, MobileStatusSliderComponent],
  template: `
    <section class="mobile-dictionaries">
      <app-mobile-status-slider
        [items]="tabStatusItems()"
        [activeKey]="activeTab()"
        ariaLabel="Справочники"
        (select)="selectTabStatus($event)"
      />

      @if (showSearch()) {
        <app-mobile-search-bar
          [value]="search()"
          [placeholder]="searchPlaceholder()"
          [showRefresh]="false"
          [hasExtraAction]="true"
          (valueChange)="setSearch($event)"
          (searchSubmit)="loadActive(true)"
        >
          <button mobileSearchActions type="button" (click)="loadActive(true)" [disabled]="loading()" aria-label="Обновить">
            <span class="material-icons-sharp">refresh</span>
          </button>
          @if (canCreateActive()) {
            <button mobileSearchActions type="button" class="add" (click)="openCreate()" aria-label="Добавить">
              <span class="material-icons-sharp">add</span>
            </button>
          }
        </app-mobile-search-bar>
      }

      @if (notice()) {
        <button type="button" class="notice success" (click)="notice.set(null)">{{ notice() }}</button>
      }

      <section class="dictionary-list">
        @if (loading()) {
          <div class="empty-state">
            <span class="material-icons-sharp">hourglass_top</span>
            <strong>Загружаем справочник</strong>
          </div>
        } @else if (error()) {
          <div class="empty-state error-state">
            <span class="material-icons-sharp">error</span>
            <strong>{{ error() }}</strong>
            <button type="button" class="ghost" (click)="loadActive(true)">Повторить загрузку</button>
          </div>
        } @else {
          @switch (activeTab()) {
            @case ('categories') {
              @for (category of categories(); track category.id) {
                <article class="dict-card tone-green">
                  <header>
                    <span class="material-icons-sharp">category</span>
                    <div>
                      <strong>{{ category.title }}</strong>
                      <small>{{ category.subCategoryCount }} подкатегорий</small>
                    </div>
                    <button type="button" (click)="openEdit('categories', category)">
                      <span class="material-icons-sharp">edit</span>
                    </button>
                  </header>
                  <div class="chip-list">
                    @for (subCategory of category.subCategories; track subCategory.id) {
                      <button type="button" (click)="openSubcategoryEdit(category, subCategory)">
                        {{ subCategory.title }}
                      </button>
                    } @empty {
                      <span>Подкатегорий нет</span>
                    }
                  </div>
                  <footer>
                    <button type="button" class="ghost" (click)="openSubcategoryCreate(category)">
                      <span class="material-icons-sharp">add</span>
                      подкатегория
                    </button>
                    <button type="button" class="danger-link" (click)="removeCategory(category)">удалить</button>
                  </footer>
                </article>
              } @empty {
                <div class="empty-state">Категории не найдены.</div>
              }
            }

            @case ('cities') {
              @for (city of cities(); track city.id) {
                <article class="dict-card compact-card tone-blue">
                  <header>
                    <span class="material-icons-sharp">location_city</span>
                    <div>
                      <strong>{{ city.title }}</strong>
                      <small>город #{{ city.id }}</small>
                    </div>
                    <button type="button" (click)="openEdit('cities', city)" aria-label="Изменить город">
                      <span class="material-icons-sharp">edit</span>
                    </button>
                  </header>
                </article>
              } @empty {
                <div class="empty-state">Города не найдены.</div>
              }
            }

            @case ('products') {
              @for (product of products(); track product.id) {
                <article class="dict-card tone-yellow">
                  <header>
                    <span class="material-icons-sharp">inventory_2</span>
                    <div>
                      <strong>{{ product.title }}</strong>
                      <small>{{ product.category?.title || 'без категории' }}</small>
                    </div>
                    <b>{{ money(product.price) }}</b>
                  </header>
                  <footer>
                    <span class="badge" [class.active]="product.photo">
                      <span class="material-icons-sharp">photo_camera</span>
                      {{ product.photo ? 'фото нужно' : 'без фото' }}
                    </span>
                    <button type="button" class="ghost" (click)="openEdit('products', product)">изменить</button>
                  </footer>
                </article>
              } @empty {
                <div class="empty-state">Продукты не найдены.</div>
              }
            }

            @case ('phones') {
              @for (phone of phones(); track phone.id) {
                <article class="dict-card tone-blue">
                  <header>
                    <span class="material-icons-sharp">phone_iphone</span>
                    <div>
                      <strong>{{ phone.number }}</strong>
                      <small>{{ phone.operator?.title || 'оператор не назначен' }}</small>
                    </div>
                    <b>{{ phone.amountSent }}/{{ phone.amountAllowed }}</b>
                  </header>
                  <div class="meta-grid">
                    <span>{{ phone.fio || 'ФИО не указано' }}</span>
                    <span>{{ phone.active ? 'активен' : 'выключен' }}</span>
                    <span>блок {{ phone.blockTime }} мин.</span>
                    <span>{{ phone.deviceTokens?.length || 0 }} устройств</span>
                  </div>
                  @if (phone.deviceTokens?.length) {
                    <div class="chip-list compact">
                      @for (token of phone.deviceTokens; track token.token) {
                        <button type="button" (click)="removeDeviceToken(phone, token.token)">
                          {{ token.active ? 'активное' : 'старое' }} устройство
                        </button>
                      }
                    </div>
                  }
                  <footer>
                    <button type="button" class="ghost" (click)="openEdit('phones', phone)">изменить</button>
                    <button type="button" class="danger-link" (click)="removePhone(phone)">удалить</button>
                  </footer>
                </article>
              } @empty {
                <div class="empty-state">Телефоны не найдены.</div>
              }
            }

            @case ('accounts') {
              <article class="dict-card tone-blue">
                <header>
                  <span class="material-icons-sharp">upload_file</span>
                  <div>
                    <strong>Импорт аккаунтов</strong>
                    <small>CSV, TSV, XLS или XLSX как в веб-версии</small>
                  </div>
                  <button type="button" (click)="accountImportInput.click()" [disabled]="importing()" aria-label="Выбрать файл">
                    <span class="material-icons-sharp">upload</span>
                  </button>
                </header>
                <input
                  #accountImportInput
                  hidden
                  type="file"
                  accept=".csv,.tsv,.xls,.xlsx"
                  (change)="uploadBotImport($event)"
                >
                @if (importing()) {
                  <p>Загружаем аккаунты...</p>
                }
                @if (importResult(); as result) {
                  <p>{{ importResultMessage(result) }} Всего строк: {{ result.totalRows }}.</p>
                }
                @if (importError()) {
                  <p class="danger-text">{{ importError() }}</p>
                }
              </article>

              @for (bot of bots(); track bot.id) {
                <article class="dict-card tone-teal">
                  <header>
                    <span class="material-icons-sharp">manage_accounts</span>
                    <div>
                      <strong>{{ bot.fio }}</strong>
                      <small>{{ bot.login }}</small>
                    </div>
                    <b>{{ bot.counter }}</b>
                  </header>
                  <div class="meta-grid">
                    <span>{{ bot.status?.title || 'статус не указан' }}</span>
                    <span>{{ bot.worker?.title || 'работник не назначен' }}</span>
                    <span>{{ bot.city?.title || 'город не указан' }}</span>
                    <span>{{ bot.active ? 'активен' : 'выключен' }}</span>
                  </div>
                  <footer class="card-actions split-actions">
                    <button type="button" class="ghost" (click)="openBotBrowser(bot)" [disabled]="botBrowserBusyId() === bot.id">
                      {{ botBrowserBusyId() === bot.id ? 'открываем' : 'браузер' }}
                    </button>
                    <button type="button" class="ghost" (click)="closeBotBrowser(bot)" [disabled]="botBrowserBusyId() === bot.id">закрыть</button>
                    <button type="button" class="ghost" (click)="openEdit('accounts', bot)">изменить</button>
                    <button type="button" class="danger-link" (click)="removeBot(bot)">удалить</button>
                  </footer>
                </article>
              } @empty {
                <div class="empty-state">Аккаунты не найдены.</div>
              }
            }

            @case ('promo') {
              <article class="dict-card promo-manager-card">
                <label>
                  <span>Менеджер для назначений</span>
                  <select [ngModel]="selectedPromoManagerId()" (ngModelChange)="selectPromoManager($event)">
                    @for (manager of promoManagers(); track manager.id) {
                      <option [ngValue]="manager.id">{{ manager.title }}</option>
                    }
                  </select>
                </label>
              </article>

              <section class="promo-two-columns">
                <div class="promo-texts">
                  @for (promoText of promoTexts(); track promoText.id) {
                    <article class="dict-card tone-violet">
                      <header>
                        <span class="material-icons-sharp">smart_button</span>
                        <div>
                          <strong>{{ promoTextLabel(promoText) }}</strong>
                          <small>ID {{ promoText.id }} · позиция {{ promoText.position }}</small>
                        </div>
                      </header>
                      <p>{{ preview(promoText.text) }}</p>
                      <footer>
                        <button type="button" class="ghost" (click)="openEdit('promo', promoText)">изменить</button>
                      </footer>
                    </article>
                  } @empty {
                    <div class="empty-state">Промо-тексты не найдены.</div>
                  }
                </div>

                <div class="promo-assignments">
                  @for (button of promoButtons(); track promoButtonKey(button)) {
                    <article class="dict-card assignment-card">
                      <header>
                        <span class="material-icons-sharp">radio_button_checked</span>
                        <div>
                          <strong>{{ button.buttonLabel }}</strong>
                          <small>{{ button.sectionTitle }} · {{ promoAssignedTextLabel(button) }}</small>
                        </div>
                      </header>
                      <select [ngModel]="promoAssignmentValue(button)" (ngModelChange)="setPromoAssignment(button, $event)">
                        <option [ngValue]="''">по умолчанию</option>
                        @for (text of promoTexts(); track text.id) {
                          <option [ngValue]="text.id">{{ promoTextLabel(text) }}</option>
                        }
                      </select>
                    </article>
                  }
                </div>
              </section>
            }

            @case ('managerTexts') {
              @for (managerText of managerTexts(); track managerText.managerId) {
                <article class="dict-card tone-green">
                  <header>
                    <span class="material-icons-sharp">article</span>
                    <div>
                      <strong>{{ managerText.managerTitle }}</strong>
                      <small>оплата, начало, оффер, напоминание, старт</small>
                    </div>
                    <button type="button" (click)="openEdit('managerTexts', managerText)">
                      <span class="material-icons-sharp">edit</span>
                    </button>
                  </header>
                  <p>{{ preview(managerText.offerText || managerText.startText || managerText.payText) }}</p>
                </article>
              } @empty {
                <div class="empty-state">Тексты менеджеров не найдены.</div>
              }
            }

            @case ('autoresponder') {
              <section class="settings-stack autoresponder-stack">
                <article class="dict-card settings-card tone-green">
                  <header>
                    <span class="material-icons-sharp">mark_chat_unread</span>
                    <div>
                      <strong>Автоответчик</strong>
                      <small>{{ autoresponder().workerEnabled ? 'worker включен' : 'worker выключен' }} · {{ autoresponder().liveEnabled ? 'live' : 'dry-run' }}</small>
                    </div>
                    <b>{{ autoresponderTotal() }}</b>
                  </header>
                  <p>Сервис находит подходящие компании и заказы, планирует сообщения внутри рабочих окон по Москве и отправляет их с паузами между каналами.</p>
                  <div class="chip-list">
                    <span>лимит {{ autoresponder().dailyLimit }}/день</span>
                    <span>пачка {{ autoresponder().tickBatchSize }}</span>
                    <span>кандидатов {{ autoresponder().candidateLimit }}</span>
                  </div>
                </article>

                <article class="dict-card settings-card">
                  <header>
                    <span class="material-icons-sharp">toggle_on</span>
                    <div>
                      <strong>Включатели</strong>
                      <small>глобальная работа сценариев</small>
                    </div>
                  </header>
                  <div class="form-grid">
                    <label class="toggle-row"><input type="checkbox" [ngModel]="autoresponder().workerEnabled" (ngModelChange)="patchAutoresponder('workerEnabled', $event)"><span>Автоответчик включен</span></label>
                    <label class="toggle-row"><input type="checkbox" [ngModel]="autoresponder().liveEnabled" (ngModelChange)="patchAutoresponder('liveEnabled', $event)"><span>Реальная отправка</span></label>
                    <label class="toggle-row"><input type="checkbox" [ngModel]="autoresponder().reviewCheckEnabled" (ngModelChange)="patchAutoresponder('reviewCheckEnabled', $event)"><span>Проверка отзывов</span></label>
                    <label class="toggle-row"><input type="checkbox" [ngModel]="autoresponder().paymentReminderEnabled" (ngModelChange)="patchAutoresponder('paymentReminderEnabled', $event)"><span>Напоминать об оплате</span></label>
                    <label class="toggle-row"><input type="checkbox" [ngModel]="autoresponder().badReviewInvoiceEnabled" (ngModelChange)="patchAutoresponder('badReviewInvoiceEnabled', $event)"><span>Счет после плохого</span></label>
                    <label class="toggle-row"><input type="checkbox" [ngModel]="autoresponder().paymentOverdueEnabled" (ngModelChange)="patchAutoresponder('paymentOverdueEnabled', $event)"><span>Просрочка оплаты</span></label>
                    <label class="toggle-row"><input type="checkbox" [ngModel]="autoresponder().paymentOverdueLiveEnabled" (ngModelChange)="patchAutoresponder('paymentOverdueLiveEnabled', $event)"><span>Live-перевод просрочки</span></label>
                    <label class="toggle-row"><input type="checkbox" [ngModel]="autoresponder().archiveReorderEnabled" (ngModelChange)="patchAutoresponder('archiveReorderEnabled', $event)"><span>Архивные предложения</span></label>
                    <label class="toggle-row wide"><input type="checkbox" [ngModel]="autoresponder().monitorEnabled" (ngModelChange)="patchAutoresponder('monitorEnabled', $event)"><span>Мониторинг автоответчика</span></label>
                  </div>
                </article>

                <article class="dict-card settings-card">
                  <header>
                    <span class="material-icons-sharp">schedule</span>
                    <div>
                      <strong>Расписание и лимиты</strong>
                      <small>рабочие окна и паузы каналов</small>
                    </div>
                  </header>
                  <div class="form-grid">
                    <label><span>Интервал проверки, дней</span><input type="number" min="1" [ngModel]="autoresponder().reviewCheckIntervalDays" (ngModelChange)="patchAutoresponder('reviewCheckIntervalDays', $event)"></label>
                    <label><span>Интервал оплаты, дней</span><input type="number" min="1" [ngModel]="autoresponder().paymentReminderIntervalDays" (ngModelChange)="patchAutoresponder('paymentReminderIntervalDays', $event)"></label>
                    <label><span>Просрочка оплаты, дней</span><input type="number" min="1" [ngModel]="autoresponder().paymentOverdueDays" (ngModelChange)="patchAutoresponder('paymentOverdueDays', $event)"></label>
                    <label><span>Архив, месяцев</span><input type="number" min="1" [ngModel]="autoresponder().archiveReorderMonths" (ngModelChange)="patchAutoresponder('archiveReorderMonths', $event)"></label>
                    <label><span>Дневной лимит</span><input type="number" min="1" [ngModel]="autoresponder().dailyLimit" (ngModelChange)="patchAutoresponder('dailyLimit', $event)"></label>
                    <label><span>Пачка за тик</span><input type="number" min="1" [ngModel]="autoresponder().tickBatchSize" (ngModelChange)="patchAutoresponder('tickBatchSize', $event)"></label>
                    <label><span>Лимит кандидатов</span><input type="number" min="1" [ngModel]="autoresponder().candidateLimit" (ngModelChange)="patchAutoresponder('candidateLimit', $event)"></label>
                    <label><span>Хранить журнал, дней</span><input type="number" min="1" [ngModel]="autoresponder().retentionDays" (ngModelChange)="patchAutoresponder('retentionDays', $event)"></label>
                    <label class="wide"><span>Рабочие окна по Москве</span><input [ngModel]="autoresponder().businessWindows" (ngModelChange)="patchAutoresponder('businessWindows', $event)"></label>
                    <label><span>Общая пауза, сек.</span><input type="number" min="30" [ngModel]="autoresponder().defaultGapSeconds" (ngModelChange)="patchAutoresponder('defaultGapSeconds', $event)"></label>
                    <label><span>WhatsApp, сек.</span><input type="number" min="30" [ngModel]="autoresponder().whatsAppGapSeconds" (ngModelChange)="patchAutoresponder('whatsAppGapSeconds', $event)"></label>
                    <label><span>Telegram, сек.</span><input type="number" min="30" [ngModel]="autoresponder().telegramGapSeconds" (ngModelChange)="patchAutoresponder('telegramGapSeconds', $event)"></label>
                    <label><span>MAX, сек.</span><input type="number" min="30" [ngModel]="autoresponder().maxGapSeconds" (ngModelChange)="patchAutoresponder('maxGapSeconds', $event)"></label>
                  </div>
                </article>

                <article class="dict-card settings-card">
                  <header>
                    <span class="material-icons-sharp">fact_check</span>
                    <div>
                      <strong>Статусы</strong>
                      <small>через запятую, как в веб-версии</small>
                    </div>
                  </header>
                  <div class="form-grid">
                    <label class="wide"><span>Статусы проверки отзывов</span><input [ngModel]="autoresponder().reviewCheckStatuses" (ngModelChange)="patchAutoresponder('reviewCheckStatuses', $event)"></label>
                    <label class="wide"><span>Статусы ожидания оплаты</span><input [ngModel]="autoresponder().paymentReminderStatuses" (ngModelChange)="patchAutoresponder('paymentReminderStatuses', $event)"></label>
                    <label class="wide"><span>Статусы просрочки оплаты</span><input [ngModel]="autoresponder().paymentOverdueStatuses" (ngModelChange)="patchAutoresponder('paymentOverdueStatuses', $event)"></label>
                    <label class="wide"><span>Закрытые статусы заказов</span><input [ngModel]="autoresponder().closedOrderStatuses" (ngModelChange)="patchAutoresponder('closedOrderStatuses', $event)"></label>
                    <label><span>Целевой статус просрочки</span><input [ngModel]="autoresponder().paymentOverdueTargetStatus" (ngModelChange)="patchAutoresponder('paymentOverdueTargetStatus', $event)"></label>
                    <label><span>Статус архивной компании</span><input [ngModel]="autoresponder().archiveCompanyStatus" (ngModelChange)="patchAutoresponder('archiveCompanyStatus', $event)"></label>
                    <label class="wide"><span>Неактивные статусы заказов</span><input [ngModel]="autoresponder().archiveInactiveOrderStatuses" (ngModelChange)="patchAutoresponder('archiveInactiveOrderStatuses', $event)"></label>
                    <label class="wide"><span>Открытые статусы заявок</span><input [ngModel]="autoresponder().openNextOrderRequestStatuses" (ngModelChange)="patchAutoresponder('openNextOrderRequestStatuses', $event)"></label>
                  </div>
                </article>

                <article class="dict-card settings-card">
                  <header>
                    <span class="material-icons-sharp">article</span>
                    <div>
                      <strong>Тексты</strong>
                      <small>переменные сохранены как в вебе</small>
                    </div>
                  </header>
                  <div class="form-grid">
                    <label class="wide"><span>Ссылка проверки отзывов</span><input [ngModel]="autoresponder().reviewLinkBaseUrl" (ngModelChange)="patchAutoresponder('reviewLinkBaseUrl', $event)"></label>
                    <label class="wide"><span>Источник оплаты</span>
                      <select [ngModel]="autoresponder().paymentInstructionSource" (ngModelChange)="patchAutoresponder('paymentInstructionSource', $event)">
                        <option value="MANAGER_TEXT">Текст менеджера</option>
                        <option value="TBANK_LINK">T-Bank ссылка</option>
                      </select>
                    </label>
                    <label class="wide"><span>Текст проверки отзывов</span><textarea rows="5" [ngModel]="autoresponder().reviewReminderText" (ngModelChange)="patchAutoresponder('reviewReminderText', $event)"></textarea></label>
                    <label class="wide"><span>Текст оплаты</span><textarea rows="5" [ngModel]="autoresponder().paymentReminderText" (ngModelChange)="patchAutoresponder('paymentReminderText', $event)"></textarea></label>
                    <label class="wide"><span>Текст архивного предложения</span><textarea rows="5" [ngModel]="autoresponder().archiveOfferText" (ngModelChange)="patchAutoresponder('archiveOfferText', $event)"></textarea></label>
                  </div>
                </article>

                <button type="button" class="save-wide" (click)="saveAutoresponderSettings()" [disabled]="saving()">
                  {{ saving() ? 'Сохраняем...' : 'Сохранить автоответчик' }}
                </button>
              </section>
            }

            @case ('autoresponderMonitor') {
              <section class="settings-stack monitor-stack">
                <article class="dict-card settings-card tone-blue monitor-head-card">
                  <header>
                    <span class="material-icons-sharp">monitor_heart</span>
                    <div>
                      <strong>Мониторинг автоответчика</strong>
                      <small>{{ autoresponder().monitorEnabled ? 'наблюдение включено' : 'наблюдение выключено' }}</small>
                    </div>
                  </header>
                  <div class="monitor-control-row">
                    <button
                      type="button"
                      class="monitor-switch"
                      role="switch"
                      [class.on]="autoresponder().monitorEnabled"
                      [attr.aria-checked]="autoresponder().monitorEnabled"
                      (click)="setClientMessageMonitorEnabled(!autoresponder().monitorEnabled)"
                      [disabled]="clientMessageMonitorSaving()"
                    >
                      <span class="switch-track" aria-hidden="true"><span class="switch-thumb"></span></span>
                      <span>{{ clientMessageMonitorSaving() ? 'Переключаем...' : (autoresponder().monitorEnabled ? 'Наблюдение включено' : 'Наблюдение выключено') }}</span>
                    </button>
                    <button
                      type="button"
                      class="monitor-refresh"
                      (click)="loadClientMessageMonitor()"
                      [disabled]="clientMessageMonitorLoading() || !autoresponder().monitorEnabled"
                    >
                      <span class="material-icons-sharp">refresh</span>
                      <span>Обновить</span>
                    </button>
                  </div>
                  <p class="monitor-note">Переключатель включает только экран наблюдения и периодическое обновление. Отправка автоответчика живет по настройкам раздела "Автоответчик".</p>
                </article>

                @if (clientMessageMonitorError()) {
                  <div class="empty-state error-state">
                    <span class="material-icons-sharp">error</span>
                    <strong>{{ clientMessageMonitorError() }}</strong>
                    <button type="button" class="ghost" (click)="loadClientMessageMonitor()">Повторить</button>
                  </div>
                } @else if (!autoresponder().monitorEnabled) {
                  <div class="empty-state">
                    <span class="material-icons-sharp">visibility_off</span>
                    <strong>Наблюдение выключено</strong>
                    <span>Очередь и отправка продолжают работать по настройкам автоответчика.</span>
                  </div>
                } @else if (clientMessageMonitorLoading() && !clientMessageMonitor()) {
                  <div class="empty-state">
                    <span class="material-icons-sharp">hourglass_top</span>
                    <strong>Загружаем мониторинг</strong>
                  </div>
                } @else if (clientMessageMonitor(); as monitor) {
                  <article class="dict-card settings-card">
                    <header>
                      <span class="material-icons-sharp">settings_input_component</span>
                      <div>
                        <strong>{{ monitor.liveEnabled ? 'live-отправка' : 'dry-run' }}</strong>
                        <small>обновлено {{ dateTimeLabel(monitor.updatedAt) }} · окна {{ monitor.businessWindows }}</small>
                      </div>
                      <b>{{ monitorTotal() }}</b>
                    </header>
                    <div class="chip-list">
                      <span [class.badge]="true" [class.active]="monitor.workerEnabled">{{ monitor.workerEnabled ? 'worker включен' : 'worker выключен' }}</span>
                      <span [class.badge]="true" [class.active]="monitor.windowAllowed">{{ monitor.windowAllowed ? 'рабочее окно' : 'вне окна' }}</span>
                      <span>следующий слот: {{ monitor.nextAttemptAt ? dateTimeLabel(monitor.nextAttemptAt) : 'нет' }}</span>
                    </div>
                  </article>

                  <div class="monitor-metric-list">
                    @for (metric of monitorMetrics(); track metric.title) {
                      <article class="setting-summary-row">
                        <span class="material-icons-sharp">{{ metric.icon }}</span>
                        <div>
                          <strong>{{ metric.title }}</strong>
                          <small>{{ metric.detail }}</small>
                        </div>
                        <b>{{ metric.value }}</b>
                      </article>
                    }
                  </div>

                  @for (scenario of monitor.scenarios; track scenario.scenario) {
                    <article class="dict-card monitor-scenario-card tone-{{ monitorScenarioTone(scenario) }}">
                      <header>
                        <span class="material-icons-sharp">{{ monitorScenarioIcon(scenario) }}</span>
                        <div>
                          <strong>{{ scenario.label }}</strong>
                          <small>{{ monitorScenarioHint(scenario) }}</small>
                        </div>
                        <b>{{ scenario.activeCandidates }}</b>
                      </header>
                      <div class="meta-grid">
                        <span>готово: {{ scenario.dueNow }}</span>
                        <span>сегодня: {{ scenario.sentToday }}</span>
                        <span>за 7 дней: {{ scenario.sentSevenDays }}</span>
                        <span>ошибок: {{ scenario.failedToday }}</span>
                      </div>
                      @if (scenario.lastError) {
                        <p class="danger-text">Последняя ошибка: {{ scenario.lastError }}</p>
                      }
                    </article>
                  }

                  <article class="dict-card settings-card">
                    <header>
                      <span class="material-icons-sharp">playlist_add_check</span>
                      <div>
                        <strong>Очередь кандидатов</strong>
                        <small>следующие попытки и причины ошибок</small>
                      </div>
                      <b>{{ monitor.queue.length }}</b>
                    </header>
                    @for (item of monitor.queue; track item.id) {
                      <article class="dict-row monitor-row">
                        <span class="material-icons-sharp">{{ monitorScenarioIcon(item) }}</span>
                        <div>
                          <strong>{{ item.orderTitle || item.companyTitle }}</strong>
                          <small>{{ item.scenarioLabel }} · {{ item.statusTitle || 'без статуса' }}</small>
                          @if (item.lastErrorMessage) {
                            <small class="danger-text">{{ item.lastErrorMessage }}</small>
                          }
                        </div>
                        <b>{{ item.nextAttemptAt ? dateTimeLabel(item.nextAttemptAt) : '-' }}</b>
                      </article>
                    } @empty {
                      <p>В очереди пока пусто.</p>
                    }
                  </article>

                  <article class="dict-card settings-card">
                    <header>
                      <span class="material-icons-sharp">history</span>
                      <div>
                        <strong>Журнал попыток</strong>
                        <small>отправлено, пропущено, ошибки</small>
                      </div>
                      <b>{{ monitor.attempts.length }}</b>
                    </header>
                    @for (attempt of monitor.attempts; track attempt.id) {
                      <article class="dict-row monitor-row">
                        <span class="material-icons-sharp">{{ attempt.status === 'SENT' ? 'done' : (attempt.status === 'FAILED' ? 'error' : 'pause') }}</span>
                        <div>
                          <strong>{{ attempt.statusLabel }} · {{ attempt.scenarioLabel }}</strong>
                          <small>{{ attempt.orderTitle || attempt.companyTitle }} · {{ attempt.channel || 'канал не выбран' }}</small>
                          <small>{{ attempt.errorMessage || attempt.messagePreview || 'без превью' }}</small>
                        </div>
                        <b>{{ dateTimeLabel(attempt.attemptedAt) }}</b>
                      </article>
                    } @empty {
                      <p>Попыток пока нет.</p>
                    }
                  </article>
                }
              </section>
            }

            @case ('settings') {
              <section class="settings-stack">
                <article class="dict-card settings-card">
                  <header>
                    <span class="material-icons-sharp">tune</span>
                    <div>
                      <strong>Настройки</strong>
                      <small>сводка текущих параметров</small>
                    </div>
                    <b>{{ settingsTotal() }}</b>
                  </header>
                  <div class="settings-summary-list">
                    @for (row of settingsRows(); track row.title) {
                      <article class="setting-summary-row">
                        <span class="material-icons-sharp">{{ row.icon }}</span>
                        <div>
                          <strong>{{ row.title }}</strong>
                          <small>{{ row.detail }}</small>
                        </div>
                        <b>{{ row.value }}</b>
                      </article>
                    }
                  </div>
                </article>

                <article class="dict-card settings-card">
                  <header>
                    <span class="material-icons-sharp">directions_walk</span>
                    <div>
                      <strong>Рассылки и выгул</strong>
                      <small>окна доступности заданий</small>
                    </div>
                  </header>
                  <div class="form-grid">
                    <label>
                      <span>Пауза выгула, минут</span>
                      <input type="number" [ngModel]="settings().nagulCooldownMinutes" (ngModelChange)="patchSettings('nagulCooldownMinutes', $event)">
                    </label>
                    <label>
                      <span>Горизонт, дней</span>
                      <input type="number" [ngModel]="settings().nagulLookaheadDays" (ngModelChange)="patchSettings('nagulLookaheadDays', $event)">
                    </label>
                  </div>
                  <p>Если поставить 0 минут, специалист сможет делать выгул без паузы.</p>
                </article>

                <article class="dict-card settings-card">
                  <header>
                    <span class="material-icons-sharp">telegram</span>
                    <div>
                      <strong>Отчеты Telegram</strong>
                      <small>{{ settings().telegramReportZone }}</small>
                    </div>
                  </header>
                  <div class="form-grid">
                    <label class="toggle-row">
                      <input type="checkbox" [ngModel]="settings().morningReportEnabled" (ngModelChange)="patchSettings('morningReportEnabled', $event)">
                      <span>Утренний отчет</span>
                    </label>
                    <label>
                      <span>Утро</span>
                      <input type="time" [ngModel]="settings().morningReportTime" (ngModelChange)="patchSettings('morningReportTime', $event)">
                    </label>
                    <label class="toggle-row">
                      <input type="checkbox" [ngModel]="settings().eveningReportEnabled" (ngModelChange)="patchSettings('eveningReportEnabled', $event)">
                      <span>Вечерний отчет</span>
                    </label>
                    <label>
                      <span>Вечер</span>
                      <input type="time" [ngModel]="settings().eveningReportTime" (ngModelChange)="patchSettings('eveningReportTime', $event)">
                    </label>
                    <label class="wide">
                      <span>Часовой пояс</span>
                      <input [ngModel]="settings().telegramReportZone" (ngModelChange)="patchSettings('telegramReportZone', $event)">
                    </label>
                  </div>
                  <p>Значения применяются сразу после сохранения. Дефолт расписания остается прежним: 11:30 утром и 22:00 вечером.</p>
                </article>

                <article class="dict-card settings-card">
                  <header>
                    <span class="material-icons-sharp">sync</span>
                    <div>
                      <strong>Синхронизация чатов</strong>
                      <small>{{ settings().whatsAppGroupSyncLastRunAt || 'еще не запускалась' }}</small>
                    </div>
                  </header>
                  <div class="form-grid">
                    <label class="toggle-row">
                      <input type="checkbox" [ngModel]="settings().whatsAppGroupSyncEnabled" (ngModelChange)="patchSettings('whatsAppGroupSyncEnabled', $event)">
                      <span>WhatsApp группы</span>
                    </label>
                    <label>
                      <span>Интервал, минут</span>
                      <input type="number" [ngModel]="settings().whatsAppGroupSyncIntervalMinutes" (ngModelChange)="patchSettings('whatsAppGroupSyncIntervalMinutes', $event)">
                    </label>
                    <label class="toggle-row wide">
                      <input type="checkbox" [ngModel]="settings().clientPublicationProgressReportsEnabled" (ngModelChange)="patchSettings('clientPublicationProgressReportsEnabled', $event)">
                      <span>Отчеты прогресса публикаций клиентам</span>
                    </label>
                  </div>
                  <p>Сервер сам сверяет ссылки вида chat.whatsapp.com с карточками компаний и группами, которые отдает WhatsApp-шлюз.</p>
                  <div class="action-row">
                    <button type="button" class="ghost" (click)="runWhatsAppSync()" [disabled]="saving()">Синхронизировать сейчас</button>
                    <button type="button" class="ghost" (click)="runSharedChatSync()" [disabled]="saving()">Скопировать id общих чатов</button>
                  </div>
                </article>

                <button type="button" class="save-wide" (click)="saveSettings()" [disabled]="saving()">
                  {{ saving() ? 'Сохраняем...' : 'Сохранить настройки' }}
                </button>
              </section>
            }
          }
        }
      </section>

      <ion-modal class="sheet-modal dictionary-editor-sheet" [isOpen]="editorOpen()" (didDismiss)="closeEditor()">
        <ng-template>
          <section class="dictionary-editor">
            <header>
              <div>
                <p>{{ editorEyebrow() }}</p>
                <h2>{{ editorTitle() }}</h2>
              </div>
              <button type="button" class="icon-button" (click)="closeEditor()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </header>

            <form class="editor-form" (ngSubmit)="saveEditor()">
              @switch (editorKind()) {
                @case ('categories') {
                  <label><span>Название</span><input name="title" [ngModel]="draftValue('title')" (ngModelChange)="patchDraft('title', $event)" required></label>
                }
                @case ('subcategory') {
                  <label><span>Название</span><input name="title" [ngModel]="draftValue('title')" (ngModelChange)="patchDraft('title', $event)" required></label>
                  <label>
                    <span>Категория</span>
                    <select name="categoryId" [ngModel]="draftValue('categoryId')" (ngModelChange)="patchDraft('categoryId', $event)" required>
                      @for (category of categories(); track category.id) {
                        <option [ngValue]="category.id">{{ category.title }}</option>
                      }
                    </select>
                  </label>
                }
                @case ('cities') {
                  <label><span>Город</span><input name="title" [ngModel]="draftValue('title')" (ngModelChange)="patchDraft('title', $event)" required></label>
                }
                @case ('products') {
                  <label><span>Название</span><input name="title" [ngModel]="draftValue('title')" (ngModelChange)="patchDraft('title', $event)" required></label>
                  <label><span>Цена</span><input name="price" type="number" [ngModel]="draftValue('price')" (ngModelChange)="patchDraft('price', $event)" required></label>
                  <label>
                    <span>Категория</span>
                    <select name="categoryId" [ngModel]="draftValue('categoryId')" (ngModelChange)="patchDraft('categoryId', $event)" required>
                      @for (category of productCategories(); track category.id) {
                        <option [ngValue]="category.id">{{ category.title }}</option>
                      }
                    </select>
                  </label>
                  <label class="toggle-row"><input name="photo" type="checkbox" [ngModel]="draftValue('photo')" (ngModelChange)="patchDraft('photo', $event)"><span>Требуется фото</span></label>
                }
                @case ('phones') {
                  <label><span>Номер</span><input name="number" [ngModel]="draftValue('number')" (ngModelChange)="patchDraft('number', $event)" required></label>
                  <label><span>ФИО</span><input name="fio" [ngModel]="draftValue('fio')" (ngModelChange)="patchDraft('fio', $event)"></label>
                  <label><span>День рождения</span><input name="birthday" type="date" [ngModel]="draftValue('birthday')" (ngModelChange)="patchDraft('birthday', $event)"></label>
                  <label><span>Лимит</span><input name="amountAllowed" type="number" [ngModel]="draftValue('amountAllowed')" (ngModelChange)="patchDraft('amountAllowed', $event)"></label>
                  <label><span>Отправлено</span><input name="amountSent" type="number" [ngModel]="draftValue('amountSent')" (ngModelChange)="patchDraft('amountSent', $event)"></label>
                  <label><span>Блок, минут</span><input name="blockTime" type="number" [ngModel]="draftValue('blockTime')" (ngModelChange)="patchDraft('blockTime', $event)"></label>
                  <label><span>Оператор</span><select name="operatorId" [ngModel]="draftValue('operatorId')" (ngModelChange)="patchDraft('operatorId', $event)"><option [ngValue]="null">не назначен</option>@for (operator of phoneOperators(); track operator.id) { <option [ngValue]="operator.id">{{ operator.title }}</option> }</select></label>
                  <label><span>Google логин</span><input name="googleLogin" [ngModel]="draftValue('googleLogin')" (ngModelChange)="patchDraft('googleLogin', $event)"></label>
                  <label><span>Google пароль</span><input name="googlePassword" [ngModel]="draftValue('googlePassword')" (ngModelChange)="patchDraft('googlePassword', $event)"></label>
                  <label><span>Avito пароль</span><input name="avitoPassword" [ngModel]="draftValue('avitoPassword')" (ngModelChange)="patchDraft('avitoPassword', $event)"></label>
                  <label><span>Почта</span><input name="mailLogin" [ngModel]="draftValue('mailLogin')" (ngModelChange)="patchDraft('mailLogin', $event)"></label>
                  <label><span>Пароль почты</span><input name="mailPassword" [ngModel]="draftValue('mailPassword')" (ngModelChange)="patchDraft('mailPassword', $event)"></label>
                  <label><span>Фото Instagram</span><input name="fotoInstagram" [ngModel]="draftValue('fotoInstagram')" (ngModelChange)="patchDraft('fotoInstagram', $event)"></label>
                  <label class="toggle-row"><input name="active" type="checkbox" [ngModel]="draftValue('active')" (ngModelChange)="patchDraft('active', $event)"><span>Активен</span></label>
                }
                @case ('accounts') {
                  <label><span>Логин</span><input name="login" [ngModel]="draftValue('login')" (ngModelChange)="patchDraft('login', $event)" required></label>
                  <label><span>Пароль</span><input name="password" [ngModel]="draftValue('password')" (ngModelChange)="patchDraft('password', $event)" required></label>
                  <label><span>ФИО</span><input name="fio" [ngModel]="draftValue('fio')" (ngModelChange)="patchDraft('fio', $event)" required></label>
                  <label><span>Работник</span><select name="workerId" [ngModel]="draftValue('workerId')" (ngModelChange)="patchDraft('workerId', $event)" required>@for (worker of botWorkers(); track worker.id) { <option [ngValue]="worker.id">{{ worker.title }}</option> }</select></label>
                  <label><span>Статус</span><select name="statusId" [ngModel]="draftValue('statusId')" (ngModelChange)="patchDraft('statusId', $event)" required>@for (status of botStatuses(); track status.id) { <option [ngValue]="status.id">{{ status.title }}</option> }</select></label>
                  <label><span>Город</span><select name="cityId" [ngModel]="draftValue('cityId')" (ngModelChange)="patchDraft('cityId', $event)"><option [ngValue]="null">без города</option>@for (city of botCities(); track city.id) { <option [ngValue]="city.id">{{ city.title }}</option> }</select></label>
                  <label><span>Счетчик</span><input name="counter" type="number" [ngModel]="draftValue('counter')" (ngModelChange)="patchDraft('counter', $event)"></label>
                  <label class="toggle-row"><input name="active" type="checkbox" [ngModel]="draftValue('active')" (ngModelChange)="patchDraft('active', $event)"><span>Активен</span></label>
                }
                @case ('promo') {
                  <label class="wide"><span>Текст</span><textarea name="text" rows="10" [ngModel]="draftValue('text')" (ngModelChange)="patchDraft('text', $event)" required></textarea></label>
                }
                @case ('managerTexts') {
                  <label class="wide"><span>Оплата</span><textarea name="payText" rows="4" [ngModel]="draftValue('payText')" (ngModelChange)="patchDraft('payText', $event)"></textarea></label>
                  <label class="wide"><span>Начало</span><textarea name="beginText" rows="4" [ngModel]="draftValue('beginText')" (ngModelChange)="patchDraft('beginText', $event)"></textarea></label>
                  <label class="wide"><span>Оффер</span><textarea name="offerText" rows="4" [ngModel]="draftValue('offerText')" (ngModelChange)="patchDraft('offerText', $event)"></textarea></label>
                  <label class="wide"><span>Напоминание</span><textarea name="reminderText" rows="4" [ngModel]="draftValue('reminderText')" (ngModelChange)="patchDraft('reminderText', $event)"></textarea></label>
                  <label class="wide"><span>Старт</span><textarea name="startText" rows="4" [ngModel]="draftValue('startText')" (ngModelChange)="patchDraft('startText', $event)"></textarea></label>
                }
              }

              <div class="editor-actions">
                @if (canDeleteEditor()) {
                  <button type="button" class="danger" (click)="deleteEditor()" [disabled]="saving()">Удалить</button>
                }
                <button type="button" (click)="closeEditor()">Отмена</button>
                <button type="submit" class="primary" [disabled]="saving()">{{ saving() ? 'Сохраняем...' : 'Сохранить' }}</button>
              </div>
            </form>
          </section>
        </ng-template>
      </ion-modal>
    </section>
  `,
  styles: [`
    :host{display:contents}.mobile-dictionaries{display:flex;height:100%;min-height:0;flex-direction:column;gap:.55rem}.dictionary-tabs{display:grid;grid-auto-columns:minmax(7rem,1fr);grid-auto-flow:column;gap:.5rem;overflow-x:auto;padding:.05rem .05rem .18rem}.dictionary-tabs::-webkit-scrollbar,.dictionary-list::-webkit-scrollbar{display:none}.dictionary-tabs button{display:grid;grid-template-columns:auto 1fr;grid-template-rows:auto auto;align-items:center;min-height:3.15rem;border:1px solid rgba(116,154,207,.28);border-radius:.75rem;padding:.5rem .65rem;background:linear-gradient(135deg,#fff,rgba(237,244,255,.88));box-shadow:0 .55rem 1.4rem rgba(31,44,71,.06);color:var(--otziv-dark);text-align:left}.dictionary-tabs button.active{border-color:rgba(116,154,207,.74);background:linear-gradient(135deg,#f2f7ff,#fff)}.dictionary-tabs .material-icons-sharp{grid-row:1/3;width:2rem;height:2rem;border-radius:.65rem;display:grid;place-items:center;background:rgba(116,154,207,.14);color:var(--otziv-primary);font-size:1.05rem}.dictionary-tabs strong{font-size:1.02rem;line-height:1}.dictionary-tabs small{overflow:hidden;color:var(--otziv-info);font-size:.63rem;font-weight:900;text-overflow:ellipsis;white-space:nowrap}.dictionary-search{display:grid;grid-template-columns:minmax(0,1fr)2.45rem 2.45rem;gap:.42rem;border-radius:1rem;padding:.52rem;background:linear-gradient(135deg,rgba(255,255,255,.96),rgba(242,246,252,.88));box-shadow:0 .45rem 1.35rem rgba(31,44,71,.06)}.dictionary-search label{display:flex;align-items:center;gap:.35rem;border:1px solid rgba(135,151,178,.18);border-radius:.78rem;padding:0 .72rem;background:#fff}.dictionary-search input{width:100%;height:2.35rem;border:0;outline:0;background:transparent;color:var(--otziv-dark);font-size:.78rem;font-weight:900}.dictionary-search .material-icons-sharp{color:var(--otziv-info);font-size:1.1rem}.icon-button{display:grid;place-items:center;border:1px solid rgba(116,154,207,.18);border-radius:.78rem;background:var(--otziv-light);color:var(--otziv-primary);font-weight:900}.icon-button.add{background:#edf4ff}.notice{border:1px solid transparent;border-radius:.75rem;padding:.65rem .75rem;font-size:.75rem;font-weight:900;text-align:left}.notice.success{border-color:rgba(68,158,133,.24);background:rgba(68,158,133,.1);color:#28806a}.notice.danger{border-color:rgba(239,52,95,.24);background:rgba(239,52,95,.1);color:var(--otziv-danger)}.dictionary-list{display:flex;flex:1 1 0;min-height:0;flex-direction:column;gap:.58rem;overflow-y:auto;padding:.02rem .02rem .3rem}.dict-card,.dict-row,.empty-state{border:1px solid rgba(135,151,178,.18);border-radius:.85rem;background:linear-gradient(135deg,#fff,rgba(247,250,255,.92));box-shadow:0 .65rem 1.8rem rgba(31,44,71,.07)}.dict-card{display:flex;flex-direction:column;gap:.62rem;padding:.72rem}.dict-card header{display:flex;align-items:center;gap:.62rem}.dict-card header>.material-icons-sharp{display:grid;place-items:center;width:2rem;height:2rem;border-radius:.65rem;background:rgba(116,154,207,.14);color:var(--otziv-primary);font-size:1.1rem}.dict-card header div{min-width:0;flex:1}.dict-card strong,.dict-row strong{color:var(--otziv-dark);font-size:.9rem;font-weight:900}.dict-card small{display:block;overflow:hidden;color:var(--otziv-info);font-size:.66rem;font-weight:900;text-overflow:ellipsis;white-space:nowrap}.dict-card b{font-size:.9rem}.dict-card header button{display:grid;place-items:center;width:2.1rem;height:2.1rem;border:1px solid rgba(116,154,207,.18);border-radius:.65rem;background:var(--otziv-light);color:var(--otziv-primary)}.dict-card p{margin:0;color:var(--otziv-dark);font-size:.74rem;font-weight:800;line-height:1.35}.dict-card footer,.action-row{display:flex;align-items:center;justify-content:space-between;gap:.5rem}.chip-list{display:flex;flex-wrap:wrap;gap:.42rem}.chip-list button,.chip-list span,.ghost,.danger-link,.badge,.dict-row button,.save-wide,.assignment-card select{min-height:2rem;border:1px solid rgba(135,151,178,.26);border-radius:999px;padding:0 .7rem;background:#fff;color:var(--otziv-dark);font-size:.66rem;font-weight:900}.chip-list.compact button{min-height:1.75rem;font-size:.61rem}.ghost{display:inline-flex;align-items:center;justify-content:center;gap:.25rem;color:var(--otziv-primary)}.danger-link,.editor-actions .danger{color:var(--otziv-danger)}.badge{display:inline-flex;align-items:center;gap:.25rem}.badge.active{border-color:rgba(68,158,133,.28);background:rgba(68,158,133,.1);color:#28806a}.dict-row{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:.65rem;padding:.7rem}.dict-row .material-icons-sharp{color:var(--otziv-primary)}.meta-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.42rem}.meta-grid span{min-height:1.95rem;border:1px solid rgba(135,151,178,.2);border-radius:.65rem;padding:.45rem .55rem;background:rgba(255,255,255,.72);color:var(--otziv-info);font-size:.66rem;font-weight:900}.tone-green{background:linear-gradient(135deg,rgba(237,255,247,.98),#fff)}.tone-yellow{background:linear-gradient(135deg,rgba(255,249,228,.98),#fff)}.tone-blue{background:linear-gradient(135deg,rgba(237,246,255,.98),#fff)}.tone-teal{background:linear-gradient(135deg,rgba(232,255,253,.98),#fff)}.tone-violet{background:linear-gradient(135deg,rgba(249,239,255,.98),#fff)}.promo-two-columns,.settings-stack{display:flex;flex-direction:column;gap:.58rem}.promo-manager-card label,.assignment-card select{width:100%}.promo-manager-card span,.settings-card label span,.dictionary-editor label span{color:var(--otziv-info);font-size:.66rem;font-weight:900}.promo-manager-card select,.settings-card input,.dictionary-editor input,.dictionary-editor select,.dictionary-editor textarea{width:100%;border:1px solid rgba(135,151,178,.22);border-radius:.72rem;background:#fff;color:var(--otziv-dark);font-size:.78rem;font-weight:900;outline:0}.promo-manager-card select,.settings-card input,.dictionary-editor input,.dictionary-editor select{height:2.55rem;padding:0 .75rem}.dictionary-editor textarea{min-height:5.5rem;padding:.7rem;resize:vertical}.assignment-card select{height:2.1rem}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.55rem}.form-grid .wide,.editor-form .wide{grid-column:1/-1}.toggle-row{display:flex!important;align-items:center;gap:.55rem}.toggle-row input{width:1.1rem!important;height:1.1rem!important;min-width:1.1rem}.save-wide{width:100%;height:2.75rem;background:var(--otziv-primary);color:#fff}.empty-state{display:grid;min-height:8rem;place-items:center;padding:1rem;color:var(--otziv-info);font-size:.82rem;font-weight:900;text-align:center}.empty-state .material-icons-sharp{font-size:2rem}.dictionary-editor{display:flex;max-height:92vh;flex-direction:column;gap:.75rem;padding:1rem;background:var(--otziv-background)}.dictionary-editor header{display:flex;align-items:center;justify-content:space-between;gap:.75rem}.dictionary-editor h2{margin:.1rem 0 0;color:var(--otziv-dark);font-size:1.25rem}.dictionary-editor p{margin:0;color:var(--otziv-info);font-size:.7rem;font-weight:900;text-transform:uppercase}.editor-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.62rem;overflow-y:auto;padding-bottom:.2rem}.editor-form label{display:flex;min-width:0;flex-direction:column;gap:.28rem}.editor-actions{grid-column:1/-1;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.5rem;position:sticky;bottom:0;padding-top:.45rem;background:var(--otziv-background)}.editor-actions button{min-height:2.35rem;border:1px solid rgba(135,151,178,.22);border-radius:999px;background:#fff;color:var(--otziv-primary);font-size:.72rem;font-weight:900}.editor-actions .primary{background:var(--otziv-primary);color:#fff}@media(max-width:420px){.dictionary-tabs{grid-auto-columns:minmax(6.6rem,1fr)}.editor-form{grid-template-columns:1fr}.form-grid{grid-template-columns:1fr}.editor-actions{grid-template-columns:1fr 1fr}.editor-actions .danger{grid-column:1/-1}.dictionary-search{grid-template-columns:minmax(0,1fr)2.45rem 2.45rem}}
    :host-context(.dark) .dict-card,:host-context(.dark) .dict-row,:host-context(.dark) .empty-state,:host-context(.dark) .dictionary-search{background:linear-gradient(135deg,rgba(30,37,39,.98),rgba(24,29,31,.96));border-color:rgba(151,169,183,.18);box-shadow:none}:host-context(.dark) .dictionary-search label,:host-context(.dark) .chip-list button,:host-context(.dark) .ghost,:host-context(.dark) .badge,:host-context(.dark) .meta-grid span,:host-context(.dark) .promo-manager-card select,:host-context(.dark) .settings-card input,:host-context(.dark) .dictionary-editor input,:host-context(.dark) .dictionary-editor select,:host-context(.dark) .dictionary-editor textarea,:host-context(.dark) .editor-actions button{background:#151b1d;border-color:rgba(151,169,183,.2);color:var(--otziv-dark)}:host-context(.dark) .dictionary-editor{background:var(--otziv-background)}
    .mobile-dictionaries{gap:.62rem;padding:.05rem .05rem .25rem;overflow:hidden}.dictionary-tabs{gap:.48rem;padding:.02rem .02rem .08rem}.dictionary-tabs button{min-height:3.05rem;border-radius:.9rem;padding:.48rem .6rem;background:linear-gradient(145deg,#fff,rgba(246,250,255,.96));box-shadow:0 .5rem 1.2rem rgba(31,44,71,.055)}.dictionary-tabs button.active{background:linear-gradient(145deg,#edf5ff,#fff);box-shadow:inset 0 0 0 1px rgba(116,154,207,.18),0 .55rem 1.25rem rgba(31,44,71,.07)}.dictionary-tabs .material-icons-sharp{width:1.9rem;height:1.9rem;border-radius:.72rem}.dictionary-tabs strong{font-size:1rem}.dictionary-search{grid-template-columns:minmax(0,1fr)2.45rem 2.45rem;border:1px solid rgba(135,151,178,.14);border-radius:1.05rem;padding:.5rem;background:linear-gradient(135deg,rgba(255,255,255,.96),rgba(244,247,252,.9));box-shadow:0 .5rem 1.3rem rgba(31,44,71,.055)}.dictionary-search.no-search{grid-template-columns:minmax(0,1fr)2.45rem}.dictionary-search label{min-width:0;border-radius:.86rem}.dictionary-search input{height:2.4rem;font-size:.78rem}.settings-compact-title{display:flex;align-items:center;gap:.45rem;min-width:0;border:1px solid rgba(135,151,178,.18);border-radius:.86rem;padding:0 .72rem;background:#fff;color:var(--otziv-dark);font-size:.78rem;font-weight:900}.settings-compact-title .material-icons-sharp{color:var(--otziv-primary)}.dictionary-list{gap:.62rem;scrollbar-width:none}.dict-card,.dict-row,.empty-state{border-color:rgba(135,151,178,.2);border-radius:1rem;background:linear-gradient(145deg,#fff,rgba(248,251,255,.96));box-shadow:0 .7rem 1.7rem rgba(31,44,71,.065)}.dict-card{gap:.7rem;padding:.78rem}.dict-card header>.material-icons-sharp{width:2.15rem;height:2.15rem;border-radius:.78rem}.dict-card strong,.dict-row strong{font-size:.92rem;line-height:1.14}.dict-card small{font-size:.67rem}.dict-card header button,.icon-button{transition:transform .12s ease,box-shadow .12s ease}.dict-card header button:active,.icon-button:active,.ghost:active,.danger-link:active{transform:scale(.97)}.dict-card footer,.card-actions,.action-row{justify-content:space-between;flex-wrap:wrap}.split-actions{justify-content:flex-start}.split-actions button{flex:1 1 calc(50% - .35rem)}.compact-card{padding:.65rem .72rem}.chip-list button,.chip-list span,.ghost,.danger-link,.badge,.dict-row button,.save-wide,.assignment-card select{min-height:2.05rem;border-color:rgba(135,151,178,.28);box-shadow:0 .25rem .8rem rgba(31,44,71,.035)}.ghost,.danger-link{padding-inline:.82rem}.danger-text{color:var(--otziv-danger)!important}.meta-grid span{display:flex;min-width:0;flex-direction:column;justify-content:center;gap:.1rem;min-height:2.3rem}.settings-summary-list{display:flex;flex-direction:column;gap:.45rem}.setting-summary-row{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:.62rem;border:1px solid rgba(135,151,178,.18);border-radius:.82rem;padding:.55rem .62rem;background:rgba(255,255,255,.72)}.setting-summary-row>.material-icons-sharp{display:grid;place-items:center;width:2rem;height:2rem;border-radius:.65rem;background:rgba(116,154,207,.12);color:var(--otziv-primary);font-size:1.05rem}.setting-summary-row div{min-width:0}.setting-summary-row strong{display:block;overflow:hidden;color:var(--otziv-dark);font-size:.76rem;font-weight:900;text-overflow:ellipsis;white-space:nowrap}.setting-summary-row small{display:block;overflow:hidden;color:var(--otziv-info);font-size:.62rem;font-weight:800;text-overflow:ellipsis;white-space:nowrap}.setting-summary-row b{color:var(--otziv-dark);font-size:.76rem;font-weight:900;text-align:right}.form-grid{gap:.62rem}.settings-card{background:linear-gradient(145deg,#fff,rgba(247,250,255,.98))}.settings-card p{color:var(--otziv-info);font-size:.69rem}.promo-manager-card{border-style:dashed}.empty-state{min-height:9rem;border-style:dashed;background:linear-gradient(145deg,rgba(255,255,255,.86),rgba(244,248,253,.76))}.error-state{border-color:rgba(239,52,95,.22);background:linear-gradient(145deg,rgba(255,246,249,.92),rgba(255,255,255,.78));color:var(--otziv-danger)}.error-state>.material-icons-sharp{color:var(--otziv-danger);font-size:2.2rem}.error-state strong{max-width:18rem;color:var(--otziv-danger);line-height:1.3}.dictionary-editor{border-radius:1.1rem 1.1rem 0 0}.dictionary-editor header{padding-bottom:.45rem;border-bottom:1px solid rgba(135,151,178,.16)}.dictionary-editor h2{font-size:1.18rem}.editor-form{gap:.7rem}.editor-form label{gap:.34rem}.editor-actions{gap:.55rem}.editor-actions button{min-height:2.45rem}@media(max-width:420px){.dictionary-tabs{grid-auto-columns:minmax(6.75rem,1fr)}.split-actions button{flex-basis:calc(50% - .35rem)}}
    :host-context(.dark) .dictionary-search,:host-context(.dark) .dict-card,:host-context(.dark) .dict-row,:host-context(.dark) .empty-state{background:linear-gradient(145deg,rgba(31,38,41,.98),rgba(22,27,29,.96));border-color:rgba(151,169,183,.18);box-shadow:none}:host-context(.dark) .settings-compact-title{background:#151b1d;border-color:rgba(151,169,183,.2);color:var(--otziv-dark)}:host-context(.dark) .dictionary-tabs button{background:linear-gradient(145deg,rgba(31,38,41,.98),rgba(24,30,32,.94));box-shadow:none}:host-context(.dark) .dictionary-tabs button.active{background:linear-gradient(145deg,rgba(38,48,53,.98),rgba(25,31,34,.96));border-color:rgba(116,154,207,.5)}:host-context(.dark) .setting-summary-row{background:#151b1d;border-color:rgba(151,169,183,.2)}:host-context(.dark) .settings-card{background:linear-gradient(145deg,rgba(31,38,41,.98),rgba(22,27,29,.96))}
    .dictionary-tabs button{grid-template-columns:2rem minmax(0,1fr);grid-template-rows:1fr 1fr;column-gap:.48rem;align-items:center}.dictionary-tabs .material-icons-sharp{align-self:center;justify-self:center}.dictionary-tabs strong{align-self:end;line-height:.95}.dictionary-tabs small{align-self:start;line-height:1.05}
    .dictionary-tabs button{min-width:0;overflow:hidden}.dictionary-tabs strong,.dictionary-tabs small{min-width:0;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.monitor-head-card{gap:.58rem}.monitor-head-card header{align-items:center}.monitor-control-row{display:grid;grid-template-columns:minmax(0,1fr)auto;align-items:center;gap:.52rem}.monitor-switch,.monitor-refresh{min-width:0;min-height:2.34rem;border:1px solid rgba(68,158,133,.35);border-radius:.82rem;background:#fff;color:#3c8374;font-size:.67rem;font-weight:900;box-shadow:0 .35rem .85rem rgba(31,44,71,.04)}.monitor-switch{display:flex;align-items:center;gap:.52rem;padding:0 .7rem;text-align:left}.monitor-switch:disabled,.monitor-refresh:disabled{opacity:.58}.switch-track{position:relative;display:inline-block;flex:0 0 3.12rem;width:3.12rem;height:1.52rem;border-radius:999px;background:#cbd5e1;box-shadow:inset 0 0 0 1px rgba(31,44,71,.08);transition:background .16s ease}.switch-thumb{position:absolute;top:.17rem;left:.17rem;width:1.18rem;height:1.18rem;border-radius:50%;background:#fff;box-shadow:0 .14rem .28rem rgba(31,44,71,.2);transition:transform .16s ease}.monitor-switch.on .switch-track{background:#4aa08a}.monitor-switch.on .switch-thumb{transform:translateX(1.58rem)}.monitor-switch>span:last-child{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.monitor-refresh{display:inline-flex;align-items:center;justify-content:center;gap:.34rem;padding:0 .76rem}.monitor-refresh .material-icons-sharp{font-size:1.12rem}.monitor-note{color:var(--otziv-info)!important;font-size:.66rem!important;line-height:1.32!important}@media(max-width:360px){.monitor-control-row{grid-template-columns:1fr}.monitor-refresh{width:100%}.dictionary-tabs{grid-auto-columns:minmax(6.45rem,1fr)}}
    :host-context(.dark) .monitor-switch,:host-context(.dark) .monitor-refresh{background:#151b1d;border-color:rgba(74,160,138,.42);color:#9fd8ca}:host-context(.dark) .monitor-switch:not(.on) .switch-track{background:#2c363a}
  `]
})
export class MobileDictionariesComponent implements OnInit, OnDestroy {
  @Input() adminMode = false;

  readonly activeTab = signal<DictionaryTabKey>('categories');
  readonly search = signal('');
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly editorOpen = signal(false);
  readonly editorKind = signal<EditorKind>('categories');
  readonly selectedId = signal<number | null>(null);
  readonly draft = signal<Draft>({});
  readonly settings = signal({ ...SETTINGS_DEFAULTS });
  readonly autoresponder = signal<AdminClientMessageSettings>({ ...CLIENT_MESSAGE_DEFAULTS });
  readonly clientMessageMonitor = signal<AdminClientMessageMonitor | null>(null);
  readonly clientMessageMonitorLoading = signal(false);
  readonly clientMessageMonitorSaving = signal(false);
  readonly clientMessageMonitorError = signal<string | null>(null);

  readonly categories = signal<AdminCategory[]>([]);
  readonly subCategories = signal<AdminSubCategory[]>([]);
  readonly cities = signal<AdminCity[]>([]);
  readonly products = signal<AdminProduct[]>([]);
  readonly productCategories = signal<DictionaryOption[]>([]);
  readonly phones = signal<OperatorPhone[]>([]);
  readonly phoneOperators = signal<DictionaryOption[]>([]);
  readonly bots = signal<AdminBot[]>([]);
  readonly botWorkers = signal<DictionaryOption[]>([]);
  readonly botStatuses = signal<DictionaryOption[]>([]);
  readonly botCities = signal<DictionaryOption[]>([]);
  readonly promoTexts = signal<AdminPromoText[]>([]);
  readonly promoManagers = signal<DictionaryOption[]>([]);
  readonly promoAssignments = signal<PromoTextAssignment[]>([]);
  readonly promoButtons = signal<PromoButtonSlot[]>([]);
  readonly selectedPromoManagerId = signal<number | null>(null);
  readonly managerTexts = signal<AdminManagerText[]>([]);
  readonly importing = signal(false);
  readonly importResult = signal<BotImportResponse | null>(null);
  readonly importError = signal<string | null>(null);
  readonly botBrowserBusyId = signal<number | null>(null);
  readonly loadedTabs = signal<Partial<Record<DictionaryTabKey, boolean>>>({});

  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private monitorTimer: ReturnType<typeof setInterval> | null = null;

  constructor(
    private readonly api: ApiService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    void this.loadActive(true);
  }

  ngOnDestroy(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.stopClientMessageMonitorPolling();
  }

  visibleTabs(): DictionaryTab[] {
    return TABS.filter((tab) => tab.roles === 'all' || this.adminMode);
  }

  tabStatusItems(): MobileStatusItem[] {
    return this.visibleTabs().map((tab, index) => ({
      key: tab.key,
      title: tab.title,
      value: this.tabTotalLabel(tab.key),
      icon: tab.icon,
      tone: this.tabTone(index)
    }));
  }

  selectTabStatus(tab: string): void {
    this.setTab(tab as DictionaryTabKey);
  }

  activeTabMeta(): DictionaryTab {
    return this.visibleTabs().find((tab) => tab.key === this.activeTab()) ?? TABS[0];
  }

  activeTabSubtitle(): string {
    if (this.loading()) {
      return `загружаем · ${this.activeTabMeta().description}`;
    }
    if (this.error()) {
      return `раздел не загрузился · ${this.activeTabMeta().description}`;
    }
    return `${this.tabTotalLabel(this.activeTab())} записей · ${this.activeTabMeta().description}`;
  }

  setTab(tab: DictionaryTabKey): void {
    if (!this.visibleTabs().some((item) => item.key === tab)) {
      tab = 'categories';
    }
    this.activeTab.set(tab);
    this.search.set('');
    this.notice.set(null);
    this.syncClientMessageMonitorPolling();
    void this.loadActive(true);
  }

  setSearch(value: string): void {
    this.search.set(value);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => void this.loadActive(), 1000);
  }

  tabTone(index: number): MobileStatusItem['tone'] {
    const tones: Array<MobileStatusItem['tone']> = ['blue', 'green', 'yellow', 'teal', 'violet', 'pink', 'gray'];
    return tones[index % tones.length] ?? 'blue';
  }

  async loadActive(force = false): Promise<void> {
    if (this.loading() && !force) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const keyword = this.search();
      switch (this.activeTab()) {
        case 'categories': {
          const [categories, subCategories] = await firstValueFrom(forkJoin([
            this.api.getAdminCategories(keyword),
            this.api.getAdminSubCategories(keyword)
          ]));
          this.categories.set(categories);
          this.subCategories.set(subCategories);
          break;
        }
        case 'cities':
          this.cities.set(await firstValueFrom(this.api.getAdminCities(keyword)));
          break;
        case 'products': {
          const response = await firstValueFrom(this.api.getAdminProducts(keyword));
          this.products.set(response.products);
          this.productCategories.set(response.categories);
          break;
        }
        case 'phones': {
          const response = await firstValueFrom(this.api.getOperatorPhones(keyword));
          this.phones.set(response.phones);
          this.phoneOperators.set(response.operators);
          break;
        }
        case 'accounts': {
          const response = await firstValueFrom(this.api.getAdminBots(keyword));
          this.bots.set(response.bots);
          this.botWorkers.set(response.workers);
          this.botStatuses.set(response.statuses);
          this.botCities.set(response.cities);
          break;
        }
        case 'promo': {
          const response = await firstValueFrom(this.api.getAdminPromoTextManagement(keyword));
          this.promoTexts.set(response.texts);
          this.promoManagers.set(response.managers);
          this.promoAssignments.set(response.assignments);
          this.promoButtons.set(response.buttons);
          if (this.selectedPromoManagerId() == null && response.managers.length) {
            this.selectedPromoManagerId.set(response.managers[0].id);
          }
          break;
        }
        case 'managerTexts':
          this.managerTexts.set(await firstValueFrom(this.api.getAdminManagerTexts(keyword)));
          break;
        case 'settings':
          await this.loadSettings();
          break;
        case 'autoresponder':
          await this.loadAutoresponderSettings();
          break;
        case 'autoresponderMonitor':
          await this.loadAutoresponderSettings();
          await this.loadClientMessageMonitor(true);
          break;
      }
      this.markLoaded(this.activeTab());
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  openCreate(): void {
    const tab = this.activeTab();
    if (tab === 'settings' || tab === 'autoresponder' || tab === 'autoresponderMonitor') {
      return;
    }
    this.editorKind.set(tab);
    this.selectedId.set(null);
    this.draft.set(this.defaultDraft(tab));
    this.editorOpen.set(true);
  }

  openEdit(kind: EditorKind, item: AdminCategory | AdminCity | AdminProduct | OperatorPhone | AdminBot | AdminPromoText | AdminManagerText): void {
    this.editorKind.set(kind);
    this.selectedId.set(this.itemId(kind, item));
    this.draft.set(this.itemDraft(kind, item));
    this.editorOpen.set(true);
  }

  openSubcategoryCreate(category: AdminCategory): void {
    this.editorKind.set('subcategory');
    this.selectedId.set(null);
    this.draft.set({ title: '', categoryId: category.id });
    this.editorOpen.set(true);
  }

  openSubcategoryEdit(category: AdminCategory, subCategory: DictionaryOption): void {
    this.editorKind.set('subcategory');
    this.selectedId.set(subCategory.id);
    this.draft.set({ title: subCategory.title, categoryId: category.id });
    this.editorOpen.set(true);
  }

  closeEditor(): void {
    this.editorOpen.set(false);
    this.selectedId.set(null);
    this.draft.set({});
  }

  async saveEditor(): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    try {
      const kind = this.editorKind();
      const id = this.selectedId();
      const draft = this.draft();
      switch (kind) {
        case 'categories':
          await firstValueFrom(id == null
            ? this.api.createAdminCategory({ title: this.text(draft['title']) })
            : this.api.updateAdminCategory(id, { title: this.text(draft['title']) }));
          break;
        case 'subcategory':
          await firstValueFrom(id == null
            ? this.api.createAdminSubCategory({ title: this.text(draft['title']), categoryId: this.numberOrNull(draft['categoryId']) })
            : this.api.updateAdminSubCategory(id, { title: this.text(draft['title']), categoryId: this.numberOrNull(draft['categoryId']) }));
          break;
        case 'cities':
          await firstValueFrom(id == null
            ? this.api.createAdminCity({ title: this.text(draft['title']) })
            : this.api.updateAdminCity(id, { title: this.text(draft['title']) }));
          break;
        case 'products':
          await firstValueFrom(id == null
            ? this.api.createAdminProduct({
              title: this.text(draft['title']),
              price: this.number(draft['price']),
              categoryId: this.numberOrNull(draft['categoryId']),
              photo: Boolean(draft['photo'])
            })
            : this.api.updateAdminProduct(id, {
              title: this.text(draft['title']),
              price: this.number(draft['price']),
              categoryId: this.numberOrNull(draft['categoryId']),
              photo: Boolean(draft['photo'])
            }));
          break;
        case 'phones': {
          const request = this.phoneRequest(draft);
          await firstValueFrom(id == null ? this.api.createOperatorPhone(request) : this.api.updateOperatorPhone(id, request));
          break;
        }
        case 'accounts':
          await firstValueFrom(id == null
            ? this.api.createAdminBot({
              login: this.text(draft['login']),
              password: this.text(draft['password']),
              fio: this.text(draft['fio']),
              workerId: this.numberOrNull(draft['workerId']),
              cityId: this.numberOrNull(draft['cityId']),
              statusId: this.numberOrNull(draft['statusId']),
              active: Boolean(draft['active']),
              counter: this.number(draft['counter'])
            })
            : this.api.updateAdminBot(id, {
              login: this.text(draft['login']),
              password: this.text(draft['password']),
              fio: this.text(draft['fio']),
              workerId: this.numberOrNull(draft['workerId']),
              cityId: this.numberOrNull(draft['cityId']),
              statusId: this.numberOrNull(draft['statusId']),
              active: Boolean(draft['active']),
              counter: this.number(draft['counter'])
            }));
          break;
        case 'promo':
          await firstValueFrom(id == null
            ? this.api.createAdminPromoText({ text: this.text(draft['text']) })
            : this.api.updateAdminPromoText(id, { text: this.text(draft['text']) }));
          break;
        case 'managerTexts':
          if (id == null) {
            throw new Error('Менеджер не выбран');
          }
          await firstValueFrom(this.api.updateAdminManagerText(id, {
            payText: this.text(draft['payText']),
            beginText: this.text(draft['beginText']),
            offerText: this.text(draft['offerText']),
            reminderText: this.text(draft['reminderText']),
            startText: this.text(draft['startText'])
          }));
          break;
      }
      this.notice.set('Сохранено.');
      this.closeEditor();
      await this.loadActive(true);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  async deleteEditor(): Promise<void> {
    const id = this.selectedId();
    if (id == null || !this.canDeleteEditor()) {
      return;
    }
    this.saving.set(true);
    try {
      switch (this.editorKind()) {
        case 'categories':
          await firstValueFrom(this.api.deleteAdminCategory(id));
          break;
        case 'subcategory':
          await firstValueFrom(this.api.deleteAdminSubCategory(id));
          break;
        case 'cities':
          await firstValueFrom(this.api.deleteAdminCity(id));
          break;
        case 'products':
          await firstValueFrom(this.api.deleteAdminProduct(id));
          break;
        case 'phones':
          await firstValueFrom(this.api.deleteOperatorPhone(id));
          break;
        case 'accounts':
          await firstValueFrom(this.api.deleteAdminBot(id));
          break;
      }
      this.notice.set('Удалено.');
      this.closeEditor();
      await this.loadActive(true);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  async removeCategory(category: AdminCategory): Promise<void> {
    this.openEdit('categories', category);
    await this.deleteEditor();
  }

  async removePhone(phone: OperatorPhone): Promise<void> {
    this.openEdit('phones', phone);
    await this.deleteEditor();
  }

  async removeBot(bot: AdminBot): Promise<void> {
    this.openEdit('accounts', bot);
    await this.deleteEditor();
  }

  async uploadBotImport(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.importResult.set(null);
    this.importError.set(null);
    if (!file) {
      this.importError.set('Выберите CSV или Excel-файл.');
      return;
    }

    this.importing.set(true);
    try {
      const result = await firstValueFrom(this.api.importAdminBots(file));
      this.importResult.set(result);
      this.notice.set(this.importResultMessage(result));
      await this.loadActive(true);
    } catch (error) {
      this.importError.set(this.errorMessage(error));
    } finally {
      this.importing.set(false);
      input.value = '';
    }
  }

  async openBotBrowser(bot: AdminBot): Promise<void> {
    if (this.botBrowserBusyId()) {
      return;
    }

    this.botBrowserBusyId.set(bot.id);
    try {
      await this.router.navigate(['/tabs/bots', bot.id, 'browser']);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.botBrowserBusyId.set(null);
    }
  }

  async closeBotBrowser(bot: AdminBot): Promise<void> {
    if (this.botBrowserBusyId()) {
      return;
    }

    this.botBrowserBusyId.set(bot.id);
    try {
      await firstValueFrom(this.api.closeAdminBotBrowser(bot.id));
      this.notice.set(`Браузер аккаунта ${bot.login} закрыт.`);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.botBrowserBusyId.set(null);
    }
  }

  async removeDeviceToken(phone: OperatorPhone, token: string): Promise<void> {
    this.saving.set(true);
    try {
      await firstValueFrom(this.api.deleteOperatorPhoneDeviceToken(phone.id, token));
      this.notice.set('Устройство удалено.');
      await this.loadActive(true);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  selectPromoManager(value: number | string): void {
    this.selectedPromoManagerId.set(Number(value) || null);
  }

  async setPromoAssignment(button: PromoButtonSlot, value: number | string): Promise<void> {
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      this.error.set('Выберите менеджера.');
      return;
    }
    this.saving.set(true);
    try {
      const promoTextId = Number(value);
      if (!promoTextId) {
        await firstValueFrom(this.api.resetAdminPromoTextAssignment(managerId, button.section, button.buttonKey));
      } else {
        await firstValueFrom(this.api.saveAdminPromoTextAssignment({
          managerId,
          section: button.section,
          buttonKey: button.buttonKey,
          promoTextId
        }));
      }
      this.notice.set('Назначение обновлено.');
      await this.loadActive(true);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  patchSettings(key: keyof typeof SETTINGS_DEFAULTS, value: string | number | boolean): void {
    const numericKeys: Array<keyof typeof SETTINGS_DEFAULTS> = [
      'nagulCooldownMinutes',
      'nagulLookaheadDays',
      'whatsAppGroupSyncIntervalMinutes'
    ];
    this.settings.update((current) => ({
      ...current,
      [key]: numericKeys.includes(key) ? Number(value) || 0 : value
    }));
  }

  async saveSettings(): Promise<void> {
    this.saving.set(true);
    try {
      const s = this.settings();
      await firstValueFrom(forkJoin({
        nagul: this.api.updateAdminNagulSettings({
          cooldownMinutes: Number(s.nagulCooldownMinutes) || 0,
          lookaheadDays: Number(s.nagulLookaheadDays) || 0
        }),
        telegram: this.api.updateAdminTelegramReportSettings({
          morningEnabled: Boolean(s.morningReportEnabled),
          morningTime: String(s.morningReportTime || '09:00'),
          eveningEnabled: Boolean(s.eveningReportEnabled),
          eveningTime: String(s.eveningReportTime || '18:00'),
          zone: String(s.telegramReportZone || 'Asia/Irkutsk')
        }),
        whatsApp: this.api.updateAdminWhatsAppGroupSyncSettings({
          enabled: Boolean(s.whatsAppGroupSyncEnabled),
          intervalMinutes: Number(s.whatsAppGroupSyncIntervalMinutes) || 0
        }),
        clientReports: this.api.updateAdminClientPublicationProgressReportSettings({
          enabled: Boolean(s.clientPublicationProgressReportsEnabled)
        })
      }));
      this.notice.set('Настройки сохранены.');
      await this.loadSettings();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  async runWhatsAppSync(): Promise<void> {
    this.saving.set(true);
    try {
      const response = await firstValueFrom(this.api.runAdminWhatsAppGroupSync());
      this.patchWhatsAppSettings(response);
      this.notice.set(`WhatsApp синхронизация: ${response.lastLinkedCount} привязок.`);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  async runSharedChatSync(): Promise<void> {
    this.saving.set(true);
    try {
      const response = await firstValueFrom(this.api.runAdminSharedChatLinkSync());
      this.notice.set(`Общие чаты: обновлено ${response.updatedCompanies}, конфликтов ${response.conflictGroups}.`);
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  patchAutoresponder(key: keyof AdminClientMessageSettings, value: string | number | boolean): void {
    const numericKeys: Array<keyof AdminClientMessageSettings> = [
      'reviewCheckIntervalDays',
      'paymentReminderIntervalDays',
      'paymentOverdueDays',
      'archiveReorderMonths',
      'retentionDays',
      'tickBatchSize',
      'candidateLimit',
      'dailyLimit',
      'defaultGapSeconds',
      'whatsAppGapSeconds',
      'telegramGapSeconds',
      'maxGapSeconds'
    ];

    this.autoresponder.update((current) => ({
      ...current,
      [key]: numericKeys.includes(key) ? Math.max(0, Number(value) || 0) : value
    }));
  }

  async saveAutoresponderSettings(): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    try {
      const saved = await firstValueFrom(this.api.updateAdminClientMessageSettings(this.autoresponderRequest()));
      this.autoresponder.set(saved);
      this.notice.set(saved.workerEnabled ? `Автоответчик сохранен. Лимит: ${saved.dailyLimit} в день.` : 'Автоответчик сохранен и выключен.');
      this.syncClientMessageMonitorPolling();
    } catch (error) {
      this.error.set(this.errorMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  async setClientMessageMonitorEnabled(enabled: boolean): Promise<void> {
    if (this.clientMessageMonitorSaving()) {
      return;
    }

    const previous = this.autoresponder().monitorEnabled;
    this.clientMessageMonitorSaving.set(true);
    this.clientMessageMonitorError.set(null);
    this.patchAutoresponder('monitorEnabled', enabled);

    try {
      const response = await firstValueFrom(this.api.updateAdminClientMessageMonitorSettings(enabled));
      this.patchAutoresponder('monitorEnabled', response.enabled);
      if (response.enabled) {
        await this.loadClientMessageMonitor();
        this.startClientMessageMonitorPolling();
      } else {
        this.stopClientMessageMonitorPolling();
        this.clientMessageMonitor.set(null);
      }
      this.notice.set(response.enabled ? 'Мониторинг включен.' : 'Мониторинг выключен.');
    } catch (error) {
      this.patchAutoresponder('monitorEnabled', previous);
      this.clientMessageMonitorError.set(this.errorMessage(error));
    } finally {
      this.clientMessageMonitorSaving.set(false);
    }
  }

  async loadClientMessageMonitor(silent = false): Promise<void> {
    if (!this.autoresponder().monitorEnabled) {
      this.clientMessageMonitor.set(null);
      this.stopClientMessageMonitorPolling();
      return;
    }

    if (!silent) {
      this.clientMessageMonitorLoading.set(true);
    }
    this.clientMessageMonitorError.set(null);

    try {
      const monitor = await firstValueFrom(this.api.getAdminClientMessageMonitor());
      this.clientMessageMonitor.set(monitor);
      this.patchAutoresponder('monitorEnabled', monitor.enabled);
      if (monitor.enabled && this.activeTab() === 'autoresponderMonitor') {
        this.startClientMessageMonitorPolling();
      }
    } catch (error) {
      this.clientMessageMonitorError.set(this.errorMessage(error));
    } finally {
      this.clientMessageMonitorLoading.set(false);
    }
  }

  patchDraft(key: string, value: string | number | boolean | null): void {
    this.draft.update((current) => ({ ...current, [key]: value }));
  }

  draftValue(key: string): string | number | boolean | null {
    return this.draft()[key] ?? null;
  }

  canCreateActive(): boolean {
    const tab = this.activeTab();
    if (tab === 'settings' || tab === 'managerTexts' || tab === 'autoresponder' || tab === 'autoresponderMonitor') {
      return false;
    }
    return this.adminMode || tab === 'categories';
  }

  showSearch(): boolean {
    const tab = this.activeTab();
    return tab !== 'settings' && tab !== 'autoresponder' && tab !== 'autoresponderMonitor';
  }

  canDeleteEditor(): boolean {
    const kind = this.editorKind();
    return this.selectedId() != null && ['categories', 'subcategory', 'cities', 'products', 'phones', 'accounts'].includes(kind);
  }

  editorEyebrow(): string {
    const id = this.selectedId();
    return id == null ? 'Новая запись' : `Запись #${id}`;
  }

  editorTitle(): string {
    const labels: Record<EditorKind, string> = {
      categories: 'Категория',
      subcategory: 'Подкатегория',
      cities: 'Город',
      products: 'Продукт',
      phones: 'Телефон',
      accounts: 'Аккаунт',
      promo: 'Промо-текст',
      managerTexts: 'Текст менеджера'
    };
    return labels[this.editorKind()];
  }

  searchPlaceholder(): string {
    const labels: Record<DictionaryTabKey, string> = {
      categories: 'Категория, подкатегория',
      cities: 'Город',
      products: 'Продукт, категория',
      phones: 'Телефон, ФИО, оператор',
      accounts: 'Логин, ФИО, город',
      promo: 'Промо-текст или позиция',
      managerTexts: 'Менеджер или текст',
      settings: '',
      autoresponder: '',
      autoresponderMonitor: ''
    };
    return labels[this.activeTab()];
  }

  tabTotal(tab: DictionaryTabKey): number {
    const totals: Record<DictionaryTabKey, number> = {
      categories: this.categories().length,
      cities: this.cities().length,
      products: this.products().length,
      phones: this.phones().length,
      accounts: this.bots().length,
      promo: this.promoTexts().length,
      managerTexts: this.managerTexts().length,
      settings: this.settingsTotal(),
      autoresponder: this.autoresponderTotal(),
      autoresponderMonitor: this.monitorTotal()
    };
    return totals[tab] ?? 0;
  }

  tabTotalLabel(tab: DictionaryTabKey): string {
    if (!this.loadedTabs()[tab]) {
      return '0';
    }
    return this.tabTotal(tab).toLocaleString('ru-RU');
  }

  money(value?: number | null): string {
    return `${Math.round(value || 0).toLocaleString('ru-RU')} руб.`;
  }

  preview(value?: string | null): string {
    const text = (value || '').trim();
    if (!text) {
      return 'Текст не заполнен.';
    }
    return text.length > 160 ? `${text.slice(0, 160)}...` : text;
  }

  promoTextLabel(text: AdminPromoText): string {
    return `Текст #${text.position || text.id}`;
  }

  promoButtonKey(button: PromoButtonSlot): string {
    return `${button.section}:${button.buttonKey}`;
  }

  promoAssignmentValue(button: PromoButtonSlot): number | '' {
    return this.promoAssignmentFor(button)?.promoTextId ?? '';
  }

  promoAssignedTextLabel(button: PromoButtonSlot): string {
    const assignment = this.promoAssignmentFor(button);
    if (!assignment?.promoTextId) {
      return `по умолчанию: #${button.defaultPromoPosition}`;
    }
    const text = this.promoTexts().find((item) => item.id === assignment.promoTextId);
    return text ? this.promoTextLabel(text) : assignment.promoTextLabel;
  }

  importResultMessage(result: BotImportResponse): string {
    const duplicateText = result.skippedDuplicates ? `, дублей пропущено: ${result.skippedDuplicates}` : '';
    const invalidText = result.skippedInvalid ? `, строк с ошибками: ${result.skippedInvalid}` : '';
    return `Добавлено: ${result.added}${duplicateText}${invalidText}`;
  }

  settingsRows(): SettingsRow[] {
    const s = this.settings();
    return [
      {
        icon: 'directions_walk',
        title: 'Время между выгулами',
        value: `${s.nagulCooldownMinutes} мин`,
        detail: Number(s.nagulCooldownMinutes) === 0 ? 'без паузы' : 'активно'
      },
      {
        icon: 'event',
        title: 'Горизонт выдачи выгула',
        value: `${s.nagulLookaheadDays} дн.`,
        detail: Number(s.nagulLookaheadDays) === 0 ? 'только сегодня' : 'вперед от сегодня'
      },
      {
        icon: 'wb_sunny',
        title: 'Утренняя Telegram-рассылка',
        value: s.morningReportEnabled ? String(s.morningReportTime || '11:30') : 'выключена',
        detail: String(s.telegramReportZone || 'Asia/Irkutsk')
      },
      {
        icon: 'dark_mode',
        title: 'Вечерняя Telegram-рассылка',
        value: s.eveningReportEnabled ? String(s.eveningReportTime || '22:00') : 'выключена',
        detail: String(s.telegramReportZone || 'Asia/Irkutsk')
      },
      {
        icon: 'sync',
        title: 'WhatsApp автосинхронизация',
        value: s.whatsAppGroupSyncEnabled ? 'включена' : 'выключена',
        detail: `каждые ${s.whatsAppGroupSyncIntervalMinutes} мин.`
      },
      {
        icon: 'history',
        title: 'Последняя WhatsApp-проверка',
        value: this.dateTimeLabel(String(s.whatsAppGroupSyncLastRunAt || '')),
        detail: `новых привязок: ${s.whatsAppGroupSyncLastLinkedCount || 0}`
      },
      {
        icon: 'schedule',
        title: 'Последние запуски',
        value: String(s.telegramMorningLastRunKey || 'утро: нет'),
        detail: String(s.telegramEveningLastRunKey || 'вечер: нет')
      }
    ];
  }

  private async loadSettings(): Promise<void> {
    const response = await firstValueFrom(forkJoin({
      nagul: this.api.getAdminNagulSettings(),
      telegram: this.api.getAdminTelegramReportSettings(),
      whatsApp: this.api.getAdminWhatsAppGroupSyncSettings(),
      clientReports: this.api.getAdminClientPublicationProgressReportSettings()
    }));
    this.settings.set({
      nagulCooldownMinutes: response.nagul.cooldownMinutes,
      nagulLookaheadDays: response.nagul.lookaheadDays,
      morningReportEnabled: response.telegram.morningEnabled,
      morningReportTime: response.telegram.morningTime,
      eveningReportEnabled: response.telegram.eveningEnabled,
      eveningReportTime: response.telegram.eveningTime,
      telegramReportZone: response.telegram.zone,
      whatsAppGroupSyncEnabled: response.whatsApp.enabled,
      whatsAppGroupSyncIntervalMinutes: response.whatsApp.intervalMinutes,
      clientPublicationProgressReportsEnabled: response.clientReports.enabled,
      whatsAppGroupSyncLastRunAt: response.whatsApp.lastRunAt,
      whatsAppGroupSyncLastLinkedCount: response.whatsApp.lastLinkedCount,
      telegramMorningLastRunKey: response.telegram.morningLastRunKey,
      telegramEveningLastRunKey: response.telegram.eveningLastRunKey
    });
  }

  private async loadAutoresponderSettings(): Promise<void> {
    const settings = await firstValueFrom(this.api.getAdminClientMessageSettings());
    this.autoresponder.set({ ...CLIENT_MESSAGE_DEFAULTS, ...settings });
    this.syncClientMessageMonitorPolling();
  }

  private autoresponderRequest(): ClientMessageSettingsRequest {
    const value = this.autoresponder();
    return {
      ...value,
      reviewCheckIntervalDays: this.positiveNumber(value.reviewCheckIntervalDays, 2),
      paymentReminderIntervalDays: this.positiveNumber(value.paymentReminderIntervalDays, 2),
      paymentOverdueDays: this.positiveNumber(value.paymentOverdueDays, 30),
      archiveReorderMonths: this.positiveNumber(value.archiveReorderMonths, 3),
      retentionDays: this.positiveNumber(value.retentionDays, 90),
      tickBatchSize: this.positiveNumber(value.tickBatchSize, 5),
      candidateLimit: this.positiveNumber(value.candidateLimit, 200),
      dailyLimit: this.positiveNumber(value.dailyLimit, 140),
      defaultGapSeconds: this.positiveNumber(value.defaultGapSeconds, 180),
      whatsAppGapSeconds: this.positiveNumber(value.whatsAppGapSeconds, 180),
      telegramGapSeconds: this.positiveNumber(value.telegramGapSeconds, 90),
      maxGapSeconds: this.positiveNumber(value.maxGapSeconds, 90),
      businessWindows: this.text(value.businessWindows) || CLIENT_MESSAGE_DEFAULTS.businessWindows,
      reviewCheckStatuses: this.text(value.reviewCheckStatuses) || CLIENT_MESSAGE_DEFAULTS.reviewCheckStatuses,
      paymentReminderStatuses: this.text(value.paymentReminderStatuses) || CLIENT_MESSAGE_DEFAULTS.paymentReminderStatuses,
      paymentOverdueStatuses: this.text(value.paymentOverdueStatuses) || CLIENT_MESSAGE_DEFAULTS.paymentOverdueStatuses,
      closedOrderStatuses: this.text(value.closedOrderStatuses) || CLIENT_MESSAGE_DEFAULTS.closedOrderStatuses,
      paymentOverdueTargetStatus: this.text(value.paymentOverdueTargetStatus) || CLIENT_MESSAGE_DEFAULTS.paymentOverdueTargetStatus,
      archiveCompanyStatus: this.text(value.archiveCompanyStatus) || CLIENT_MESSAGE_DEFAULTS.archiveCompanyStatus,
      archiveInactiveOrderStatuses: this.text(value.archiveInactiveOrderStatuses) || CLIENT_MESSAGE_DEFAULTS.archiveInactiveOrderStatuses,
      openNextOrderRequestStatuses: this.text(value.openNextOrderRequestStatuses) || CLIENT_MESSAGE_DEFAULTS.openNextOrderRequestStatuses,
      reviewLinkBaseUrl: this.text(value.reviewLinkBaseUrl) || CLIENT_MESSAGE_DEFAULTS.reviewLinkBaseUrl,
      reviewReminderText: this.text(value.reviewReminderText) || CLIENT_MESSAGE_DEFAULTS.reviewReminderText,
      paymentInstructionSource: value.paymentInstructionSource === 'TBANK_LINK' ? 'TBANK_LINK' : 'MANAGER_TEXT',
      paymentReminderText: this.text(value.paymentReminderText) || CLIENT_MESSAGE_DEFAULTS.paymentReminderText,
      archiveOfferText: this.text(value.archiveOfferText) || CLIENT_MESSAGE_DEFAULTS.archiveOfferText
    };
  }

  private patchWhatsAppSettings(value: { enabled: boolean; intervalMinutes: number; lastRunAt: string; lastLinkedCount: number }): void {
    this.settings.update((current) => ({
      ...current,
      whatsAppGroupSyncEnabled: value.enabled,
      whatsAppGroupSyncIntervalMinutes: value.intervalMinutes,
      whatsAppGroupSyncLastRunAt: value.lastRunAt,
      whatsAppGroupSyncLastLinkedCount: value.lastLinkedCount
    }));
  }

  settingsTotal(): number {
    const s = this.settings();
    return [
      s.nagulCooldownMinutes,
      s.nagulLookaheadDays,
      s.morningReportEnabled,
      s.eveningReportEnabled,
      s.whatsAppGroupSyncEnabled,
      s.clientPublicationProgressReportsEnabled
    ].filter(Boolean).length;
  }

  autoresponderTotal(): number {
    const s = this.autoresponder();
    return [
      s.workerEnabled,
      s.liveEnabled,
      s.reviewCheckEnabled,
      s.paymentReminderEnabled,
      s.badReviewInvoiceEnabled,
      s.paymentOverdueEnabled,
      s.archiveReorderEnabled,
      s.monitorEnabled
    ].filter(Boolean).length;
  }

  monitorTotal(): number {
    const monitor = this.clientMessageMonitor();
    if (!this.autoresponder().monitorEnabled) {
      return 0;
    }
    return monitor ? monitor.activeCandidates + monitor.dueNow + monitor.failedToday : 0;
  }

  monitorMetrics(): SettingsRow[] {
    const monitor = this.clientMessageMonitor();
    return [
      { icon: 'playlist_add_check', title: 'Активных', value: String(monitor?.activeCandidates ?? 0), detail: 'кандидаты в очереди' },
      { icon: 'bolt', title: 'Готово сейчас', value: String(monitor?.dueNow ?? 0), detail: 'можно отправлять' },
      { icon: 'send', title: 'Сегодня', value: String(monitor?.sentToday ?? 0), detail: 'отправлено' },
      { icon: 'priority_high', title: 'Ошибок', value: String(monitor?.failedToday ?? 0), detail: 'за сегодня' },
      { icon: 'pause_circle', title: 'Пропущено', value: String(monitor?.skippedToday ?? 0), detail: 'за сегодня' },
      { icon: 'block', title: 'Отключено', value: String(monitor?.disabledStates ?? 0), detail: 'состояний' }
    ];
  }

  monitorScenarioIcon(item: { scenario: string }): string {
    return {
      REVIEW_CHECK_REMINDER: 'playlist_add_check',
      PAYMENT_REMINDER: 'receipt_long',
      PAYMENT_OVERDUE_ESCALATION: 'priority_high',
      ARCHIVE_REORDER_OFFER: 'archive',
      BAD_REVIEW_INVOICE: 'request_quote'
    }[item.scenario] ?? 'mark_chat_unread';
  }

  monitorScenarioTone(scenario: AdminClientMessageMonitorScenario): string {
    if (scenario.failedToday > 0 || scenario.lastError) {
      return 'yellow';
    }
    if (scenario.dueNow > 0) {
      return 'blue';
    }
    if (scenario.activeCandidates > 0) {
      return 'green';
    }
    return 'teal';
  }

  monitorScenarioHint(scenario: AdminClientMessageMonitorScenario): string {
    return {
      REVIEW_CHECK_REMINDER: 'напоминания проверить шаблоны отзывов',
      PAYMENT_REMINDER: 'напоминания об оплате заказа',
      PAYMENT_OVERDUE_ESCALATION: 'контроль долгой просрочки оплаты',
      ARCHIVE_REORDER_OFFER: 'предложение нового заказа архивным компаниям',
      BAD_REVIEW_INVOICE: 'счет после плохого отзыва'
    }[scenario.scenario] ?? 'сценарий автоответчика';
  }

  paymentInstructionSourceLabel(source?: string | null): string {
    return source === 'TBANK_LINK' ? 'T-Bank ссылка' : 'текст менеджера';
  }

  private markLoaded(tab: DictionaryTabKey): void {
    this.loadedTabs.update((current) => ({ ...current, [tab]: true }));
  }

  private syncClientMessageMonitorPolling(): void {
    if (this.activeTab() === 'autoresponderMonitor' && this.autoresponder().monitorEnabled) {
      void this.loadClientMessageMonitor(true);
      this.startClientMessageMonitorPolling();
      return;
    }
    this.stopClientMessageMonitorPolling();
  }

  private startClientMessageMonitorPolling(): void {
    if (this.monitorTimer || this.activeTab() !== 'autoresponderMonitor' || !this.autoresponder().monitorEnabled) {
      return;
    }
    this.monitorTimer = setInterval(() => {
      if (this.activeTab() === 'autoresponderMonitor' && this.autoresponder().monitorEnabled) {
        void this.loadClientMessageMonitor(true);
      } else {
        this.stopClientMessageMonitorPolling();
      }
    }, CLIENT_MESSAGE_MONITOR_POLLING_MS);
  }

  private stopClientMessageMonitorPolling(): void {
    if (!this.monitorTimer) {
      return;
    }
    clearInterval(this.monitorTimer);
    this.monitorTimer = null;
  }

  private positiveNumber(value: number, fallback: number): number {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
  }

  private withVncParams(rawUrl: string): string {
    const url = new URL(rawUrl, window.location.origin);
    url.searchParams.set('autoconnect', '1');
    url.searchParams.set('reconnect', '1');
    url.searchParams.set('resize', 'none');
    url.searchParams.set('clip', 'true');
    return url.toString();
  }

  dateTimeLabel(value: string): string {
    if (!value) {
      return 'еще не запускалась';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  private itemId(kind: EditorKind, item: AdminCategory | AdminCity | AdminProduct | OperatorPhone | AdminBot | AdminPromoText | AdminManagerText): number {
    return kind === 'managerTexts'
      ? (item as AdminManagerText).managerId
      : (item as AdminCategory | AdminCity | AdminProduct | OperatorPhone | AdminBot | AdminPromoText).id;
  }

  private defaultDraft(kind: DictionaryTabKey): Draft {
    if (kind === 'categories' || kind === 'cities') {
      return { title: '' };
    }
    if (kind === 'products') {
      return { title: '', price: 0, categoryId: this.productCategories()[0]?.id ?? null, photo: false };
    }
    if (kind === 'phones') {
      return {
        number: '',
        fio: '',
        birthday: '',
        amountAllowed: 0,
        amountSent: 0,
        blockTime: 0,
        timer: '',
        googleLogin: '',
        googlePassword: '',
        avitoPassword: '',
        mailLogin: '',
        mailPassword: '',
        fotoInstagram: '',
        active: true,
        createDate: '',
        operatorId: null
      };
    }
    if (kind === 'accounts') {
      return {
        login: '',
        password: '',
        fio: '',
        workerId: this.botWorkers()[0]?.id ?? null,
        cityId: null,
        statusId: this.botStatuses()[0]?.id ?? null,
        counter: 0,
        active: true
      };
    }
    if (kind === 'promo') {
      return { text: '' };
    }
    return {};
  }

  private itemDraft(kind: EditorKind, item: AdminCategory | AdminCity | AdminProduct | OperatorPhone | AdminBot | AdminPromoText | AdminManagerText): Draft {
    switch (kind) {
      case 'categories':
      case 'cities':
        return { title: (item as AdminCategory | AdminCity).title };
      case 'products': {
        const product = item as AdminProduct;
        return { title: product.title, price: product.price, categoryId: product.category?.id ?? null, photo: product.photo };
      }
      case 'phones': {
        const phone = item as OperatorPhone;
        return {
          number: phone.number,
          fio: phone.fio ?? '',
          birthday: phone.birthday ?? '',
          amountAllowed: phone.amountAllowed,
          amountSent: phone.amountSent,
          blockTime: phone.blockTime,
          timer: phone.timer ?? '',
          googleLogin: phone.googleLogin ?? '',
          googlePassword: phone.googlePassword ?? '',
          avitoPassword: phone.avitoPassword ?? '',
          mailLogin: phone.mailLogin ?? '',
          mailPassword: phone.mailPassword ?? '',
          fotoInstagram: phone.fotoInstagram ?? '',
          active: phone.active,
          createDate: phone.createDate ?? '',
          operatorId: phone.operator?.id ?? null
        };
      }
      case 'accounts': {
        const bot = item as AdminBot;
        return {
          login: bot.login,
          password: bot.password,
          fio: bot.fio,
          workerId: bot.worker?.id ?? null,
          cityId: bot.city?.id ?? null,
          statusId: bot.status?.id ?? null,
          counter: bot.counter,
          active: bot.active
        };
      }
      case 'promo':
        return { text: (item as AdminPromoText).text };
      case 'managerTexts': {
        const text = item as AdminManagerText;
        return {
          payText: text.payText,
          beginText: text.beginText,
          offerText: text.offerText,
          reminderText: text.reminderText,
          startText: text.startText
        };
      }
      default:
        return {};
    }
  }

  private phoneRequest(draft: Draft): OperatorPhoneRequest {
    return {
      number: this.text(draft['number']),
      fio: this.textOrNull(draft['fio']),
      birthday: this.textOrNull(draft['birthday']),
      amountAllowed: this.number(draft['amountAllowed']),
      amountSent: this.number(draft['amountSent']),
      blockTime: this.number(draft['blockTime']),
      timer: this.textOrNull(draft['timer']),
      googleLogin: this.textOrNull(draft['googleLogin']),
      googlePassword: this.textOrNull(draft['googlePassword']),
      avitoPassword: this.textOrNull(draft['avitoPassword']),
      mailLogin: this.textOrNull(draft['mailLogin']),
      mailPassword: this.textOrNull(draft['mailPassword']),
      fotoInstagram: this.textOrNull(draft['fotoInstagram']),
      active: Boolean(draft['active']),
      createDate: this.textOrNull(draft['createDate']),
      operatorId: this.numberOrNull(draft['operatorId'])
    };
  }

  private promoAssignmentFor(button: PromoButtonSlot): PromoTextAssignment | null {
    const managerId = this.selectedPromoManagerId();
    if (managerId == null) {
      return null;
    }
    return this.promoAssignments().find((assignment) =>
      assignment.managerId === managerId
      && assignment.section === button.section
      && assignment.buttonKey === button.buttonKey
    ) ?? null;
  }

  private text(value: string | number | boolean | null | undefined): string {
    return String(value ?? '').trim();
  }

  private textOrNull(value: string | number | boolean | null | undefined): string | null {
    const text = this.text(value);
    return text || null;
  }

  private number(value: string | number | boolean | null | undefined): number {
    return Number(value) || 0;
  }

  private numberOrNull(value: string | number | boolean | null | undefined): number | null {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
  }

  private errorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      const payload = error.error;
      const payloadMessage = typeof payload === 'string'
        ? payload
        : payload?.message || payload?.detail || payload?.error;
      if (payloadMessage) {
        return payloadMessage;
      }
      if (error.status === 401) {
        return 'Сессия истекла. Войдите снова.';
      }
      if (error.status === 403) {
        return 'Нет доступа к этому справочнику для текущей роли.';
      }
      if (error.status === 404) {
        return 'Раздел не найден на сервере.';
      }
      if (error.status === 0) {
        return 'Сервер недоступен. Проверьте локальный backend.';
      }
      return `Не удалось загрузить раздел. Код: ${error.status}`;
    }
    if (typeof error === 'object' && error && 'error' in error) {
      const payload = (error as { error?: { message?: string; detail?: string; error?: string } | string }).error;
      if (typeof payload === 'string') {
        return payload;
      }
      if (payload?.message) {
        return payload.message;
      }
      if (payload?.detail) {
        return payload.detail;
      }
      if (payload?.error) {
        return payload.error;
      }
    }
    return error instanceof Error ? error.message : 'Не удалось выполнить действие.';
  }
}
