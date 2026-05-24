import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  IonContent,
  IonModal,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  ApiService,
  CompanyCreateOption,
  CompanyCreatePayload,
  CompanyCreateRequest,
  CompanyCreateSource,
  LeadItem,
  LeadPerson,
  OperatorBoard,
  OperatorBoardSection,
  OperatorPhone,
  OperatorPhoneRequest,
  PhoneOperatorOption
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';
import { displayPhone, normalizePhoneDigits } from '../shared/phone-format';

type OperatorAction = 'send' | 'toWork';

type OperatorPromoItem = {
  key: string;
  label: string;
  text: string;
  icon: string;
};

type OperatorTab = {
  key: OperatorBoardSection;
  label: string;
  icon: string;
  tone: string;
};

type CompanyCreateDraft = {
  source: CompanyCreateSource;
  leadId: number | null;
  managerId: number | null;
  title: string;
  urlChat: string;
  urlSite: string;
  telephone: string;
  city: string;
  email: string;
  commentsCompany: string;
  categoryId: number | null;
  subCategoryId: number | null;
  workerId: number | null;
  filialCityId: number | null;
  filialTitle: string;
  filialUrl: string;
};

type CompanyPreservedFields = Pick<
  CompanyCreateDraft,
  'title' | 'urlChat' | 'urlSite' | 'telephone' | 'city' | 'email' | 'commentsCompany' | 'categoryId' | 'subCategoryId' | 'workerId' | 'filialCityId' | 'filialTitle' | 'filialUrl'
>;

const OPERATOR_TABS: OperatorTab[] = [
  { key: 'queue', label: 'К выдаче', icon: 'support_agent', tone: 'tone-green' },
  { key: 'sent', label: 'Отправл.', icon: 'mail', tone: 'tone-blue' }
];

@Component({
  selector: 'app-operator',
  imports: [FormsModule, IonContent, IonModal, IonRefresher, IonRefresherContent, MobileHeaderComponent, MobileRemindersComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Оператор" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <app-mobile-reminders #reminders />

        <main class="operator-page">
          <section class="operator-status-scroll" aria-label="Состояние оператора">
            @for (tab of tabs; track tab.key) {
              <button
                type="button"
                class="metric-tile {{ tab.tone }}"
                [class.active]="activeSection() === tab.key"
                (click)="setSection(tab.key)"
              >
                <span class="material-icons-sharp">{{ tab.icon }}</span>
                <strong>{{ tabValue(tab.key) }}</strong>
                <small>{{ tab.label.toLowerCase() }}</small>
              </button>
            }

            <button type="button" class="metric-tile tone-yellow" (click)="openBindModal()">
              <span class="material-icons-sharp">timer</span>
              <strong>{{ timerState() }}</strong>
              <small>{{ board()?.telephoneId ? ('тел. #' + board()?.telephoneId) : 'телефон' }}</small>
            </button>
          </section>

          <form class="operator-search-strip" (ngSubmit)="applySearch()" role="search" aria-label="Поиск">
            <label>
              <span class="material-icons-sharp">search</span>
              <input
                name="operatorKeyword"
                type="search"
                autocomplete="off"
                placeholder="Телефон, город, компания"
                [ngModel]="keyword()"
                (ngModelChange)="setKeyword($event)"
              >
            </label>

            @if (keyword() || appliedKeyword()) {
              <button class="icon-button" type="button" (click)="clearSearch()" [disabled]="loading()" aria-label="Сбросить поиск">
                <span class="material-icons-sharp">close</span>
              </button>
            }

            @if (canManagePhones()) {
              <button class="icon-button" type="button" (click)="openPhones()" [disabled]="phoneLoading()" aria-label="Телефоны">
                <span class="material-icons-sharp">settings_cell</span>
              </button>
            }

            <button class="icon-button" type="button" (click)="reload()" [disabled]="loading()" aria-label="Обновить">
              <span class="material-icons-sharp">refresh</span>
            </button>
          </form>

          <section class="operator-promo-strip" aria-label="Тексты оператора">
            @for (item of promoItems(); track item.key) {
              <button type="button" (click)="copyPromo(item)" [disabled]="!item.text">
                <span class="material-icons-sharp">{{ item.icon }}</span>
                {{ copiedKey() === ('promo-' + item.key) ? 'готово' : item.label }}
              </button>
            }
          </section>

          @if (error()) {
            <button class="inline-alert" type="button" (click)="reload()">
              <span class="material-icons-sharp">error</span>
              <span>{{ error() }}</span>
            </button>
          }

          <section class="lead-list operator-list" aria-label="Лиды оператора">
            @for (lead of currentLeads(); track lead.id) {
              <article [class]="'lead-card operator-lead-card lead-card--' + leadTone(lead)">
                <header class="lead-card-head">
                  <strong>{{ leadTitle(lead) }}</strong>
                  <span>
                    <small>{{ lead.lidStatus || '-' }} / #{{ lead.id }}</small>
                  </span>
                </header>

                <div class="lead-phone-row" [class.lead-phone-row--offer]="lead.offer">
                  <a [href]="whatsappUrl(lead)" target="_blank" rel="noopener">
                    {{ phoneDisplay(lead) }}
                  </a>
                  <button type="button" (click)="copyPhone(lead)" aria-label="Скопировать телефон">
                    {{ copiedKey() === ('phone-' + lead.id) ? '✓' : 'T' }}
                  </button>
                </div>

                <div class="lead-meta-row">
                  <span>{{ dateText(lead.createDate) }}</span>
                  <span>{{ lead.cityLead || 'Город' }}</span>
                </div>

                @if (businessLine(lead); as business) {
                  <div class="lead-business-line" [title]="business">{{ business }}</div>
                }

                @if (addressLine(lead); as address) {
                  <div class="lead-business-line lead-business-line--address" [title]="address">{{ address }}</div>
                }

                <div class="lead-comment-editor operator-comment-editor">
                  <textarea
                    class="lead-card-comment"
                    [name]="'operatorComment' + lead.id"
                    rows="3"
                    placeholder="Комментарий"
                    [ngModel]="commentFor(lead)"
                    (ngModelChange)="setComment(lead, $event)"
                  ></textarea>
                </div>

                <div class="lead-card-actions operator-card-actions">
                  @for (action of actionsForLead(lead); track action) {
                    <button type="button" [disabled]="isMutating(lead, action)" (click)="runAction(lead, action)">
                      <span class="material-icons-sharp">{{ actionIcon(action) }}</span>
                      {{ isMutating(lead, action) ? '...' : actionLabel(action) }}
                    </button>
                  }
                  <button type="button" (click)="openCompanyCreate(lead)">
                    <span class="material-icons-sharp">business_center</span>
                    работа
                  </button>
                </div>

                <footer class="lead-card-foot">
                  <button type="button" [title]="assigneeName(lead.operator)">{{ assigneeName(lead.operator) }}</button>
                  <span>{{ dateText(lead.updateStatus) }}</span>
                </footer>
              </article>
            } @empty {
              <div class="empty-state compact-empty operator-empty">
                <span class="material-icons-sharp">{{ board()?.requireDeviceId ? 'settings_cell' : 'inbox' }}</span>
                <p>{{ emptyMessage() }}</p>
                @if (board()?.requireDeviceId) {
                  <button type="button" (click)="openBindModal()">Указать ID телефона</button>
                }
              </div>
            }
          </section>

          <section class="lead-bottom-controls operator-bottom-controls" aria-label="Пагинация">
            <button class="expand-list-button reminder-hero-button" type="button" (click)="reminders.open()" aria-label="Напоминания">
              <span class="material-icons-sharp">notifications_active</span>
              @if (reminders.activeReminderCount()) {
                <small>{{ reminders.activeReminderCount() }}</small>
              }
            </button>
            <button class="expand-list-button" type="button" (click)="openBindModal()" aria-label="ID телефона">
              <span class="material-icons-sharp">phone_iphone</span>
            </button>
            <div class="lead-pager">
              <button type="button" (click)="previousPage()" [disabled]="isFirstPage() || loading()" aria-label="Предыдущая страница">
                <span class="material-icons-sharp">chevron_left</span>
              </button>
              <span>{{ pageLabel() }}</span>
              <button type="button" (click)="nextPage()" [disabled]="isLastPage() || loading()" aria-label="Следующая страница">
                <span class="material-icons-sharp">chevron_right</span>
              </button>
            </div>
          </section>
        </main>

        <ion-modal class="sheet-modal operator-bind-sheet" [isOpen]="bindModalOpen()" (didDismiss)="closeBindModal()">
          <ng-template>
            <form class="sheet-body sheet-form" (ngSubmit)="saveDeviceToken()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">device_token</p>
                  <h2>ID телефона</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeBindModal()" [disabled]="bindSaving()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content">
                @if (bindError()) {
                  <p class="sheet-error">{{ bindError() }}</p>
                }

                <p class="sheet-hint">Укажи ID телефона, к которому привязано рабочее устройство оператора.</p>

                <label class="sheet-field">
                  <span>ID телефона</span>
                  <input
                    name="operatorTelephoneId"
                    type="number"
                    min="1"
                    required
                    autocomplete="off"
                    [ngModel]="telephoneIdDraft()"
                    (ngModelChange)="setTelephoneIdDraft($event)"
                    [disabled]="bindSaving()"
                  >
                </label>
              </section>

              <div class="sheet-actions">
                <button class="secondary" type="button" (click)="closeBindModal()" [disabled]="bindSaving()">Отмена</button>
                <button type="submit" [disabled]="bindSaving() || !canSaveDeviceToken()">
                  {{ bindSaving() ? 'Сохраняю' : 'Сохранить' }}
                </button>
              </div>
            </form>
          </ng-template>
        </ion-modal>

        <ion-modal class="sheet-modal company-create-sheet" [isOpen]="companyCreateOpen()" (didDismiss)="closeCompanyCreate()">
          <ng-template>
            <form class="sheet-body sheet-form" (ngSubmit)="saveCompany()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">{{ companyLead() ? 'Лид #' + companyLead()?.id : 'Лид' }}</p>
                  <h2>Создание компании</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeCompanyCreate()" [disabled]="companySaving()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content">
                @if (companyLoading()) {
                  <p class="sheet-hint">Загружаю данные компании...</p>
                }

                @if (companyError()) {
                  <p class="sheet-error">{{ companyError() }}</p>
                }

                @if (companyDraft(); as draft) {
                  @if (companyPayload()?.canChangeManager) {
                    <label class="sheet-field">
                      <span>Менеджер</span>
                      <select
                        name="companyManager"
                        [ngModel]="draft.managerId"
                        (ngModelChange)="changeCompanyManager($event)"
                        [disabled]="companySaving()"
                      >
                        @for (manager of companyManagerOptions(); track manager.id) {
                          <option [ngValue]="manager.id">{{ manager.label }}</option>
                        }
                      </select>
                    </label>
                  }

                  <label class="sheet-field">
                    <span>Название</span>
                    <input name="companyTitle" type="text" required [ngModel]="draft.title" (ngModelChange)="setCompanyField('title', $event)" [disabled]="companySaving()">
                  </label>

                  <label class="sheet-field">
                    <span>Телефон</span>
                    <input name="companyTelephone" type="tel" required readonly [ngModel]="draft.telephone" (ngModelChange)="setCompanyField('telephone', $event)" [disabled]="companySaving()">
                  </label>

                  <label class="sheet-field">
                    <span>Город компании</span>
                    <input name="companyCity" type="text" required readonly [ngModel]="draft.city" (ngModelChange)="setCompanyField('city', $event)" [disabled]="companySaving()">
                  </label>

                  <label class="sheet-field">
                    <span>Ссылка на чат</span>
                    <input name="companyUrlChat" type="text" required [ngModel]="draft.urlChat" (ngModelChange)="setCompanyField('urlChat', $event)" [disabled]="companySaving()">
                  </label>

                  <label class="sheet-field">
                    <span>Официальный сайт</span>
                    <input name="companyUrlSite" type="url" [ngModel]="draft.urlSite" (ngModelChange)="setCompanyField('urlSite', $event)" [disabled]="companySaving()">
                  </label>

                  <label class="sheet-field">
                    <span>Email</span>
                    <input name="companyEmail" type="email" [ngModel]="draft.email" (ngModelChange)="setCompanyField('email', $event)" [disabled]="companySaving()">
                  </label>

                  <label class="sheet-field">
                    <span>Категория</span>
                    <select name="companyCategory" required [ngModel]="draft.categoryId" (ngModelChange)="changeCompanyCategory($event)" [disabled]="companySaving()">
                      <option [ngValue]="null">Не выбрана</option>
                      @for (category of companyCategoryOptions(); track category.id) {
                        <option [ngValue]="category.id">{{ category.label }}</option>
                      }
                    </select>
                  </label>

                  <label class="sheet-field">
                    <span>Подкатегория</span>
                    <select name="companySubCategory" required [ngModel]="draft.subCategoryId" (ngModelChange)="setCompanyField('subCategoryId', $event)" [disabled]="companySaving() || !draft.categoryId">
                      <option [ngValue]="null">Не выбрана</option>
                      @for (subCategory of companySubCategoryOptions(); track subCategory.id) {
                        <option [ngValue]="subCategory.id">{{ subCategory.label }}</option>
                      }
                    </select>
                  </label>

                  <label class="sheet-field">
                    <span>Специалист</span>
                    <select name="companyWorker" required [ngModel]="draft.workerId" (ngModelChange)="setCompanyField('workerId', $event)" [disabled]="companySaving()">
                      <option [ngValue]="null">Не выбран</option>
                      @for (worker of companyWorkerOptions(); track worker.id) {
                        <option [ngValue]="worker.id">{{ worker.label }}</option>
                      }
                    </select>
                  </label>

                  <label class="sheet-field">
                    <span>Город филиала</span>
                    <select name="companyFilialCity" required [ngModel]="draft.filialCityId" (ngModelChange)="setCompanyField('filialCityId', $event)" [disabled]="companySaving()">
                      <option [ngValue]="null">Не выбран</option>
                      @for (city of companyCityOptions(); track city.id) {
                        <option [ngValue]="city.id">{{ city.label }}</option>
                      }
                    </select>
                  </label>

                  <label class="sheet-field">
                    <span>Название филиала</span>
                    <input name="companyFilialTitle" type="text" required [ngModel]="draft.filialTitle" (ngModelChange)="setCompanyField('filialTitle', $event)" [disabled]="companySaving()">
                  </label>

                  <label class="sheet-field">
                    <span>Ссылка филиала</span>
                    <input name="companyFilialUrl" type="text" required [ngModel]="draft.filialUrl" (ngModelChange)="setCompanyField('filialUrl', $event)" [disabled]="companySaving()">
                  </label>

                  <label class="sheet-field">
                    <span>Комментарий</span>
                    <textarea name="companyComments" rows="3" [ngModel]="draft.commentsCompany" (ngModelChange)="setCompanyField('commentsCompany', $event)" [disabled]="companySaving()"></textarea>
                  </label>
                }
              </section>

              <div class="sheet-actions">
                <button class="secondary" type="button" (click)="closeCompanyCreate()" [disabled]="companySaving()">Отмена</button>
                <button type="submit" [disabled]="companyLoading() || companySaving() || !canSaveCompany()">
                  {{ companySaving() ? 'Создаю' : 'Создать компанию' }}
                </button>
              </div>
            </form>
          </ng-template>
        </ion-modal>

        <ion-modal class="sheet-modal operator-phones-sheet" [isOpen]="phonesOpen()" (didDismiss)="closePhones()">
          <ng-template>
            <form class="sheet-body sheet-form operator-phone-form" (ngSubmit)="savePhone()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Оператор</p>
                  <h2>Телефоны</h2>
                </div>
                <button class="icon-button" type="button" (click)="closePhones()" [disabled]="phoneSaving() || phoneDeleting()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content">
                @if (phoneError()) {
                  <p class="sheet-error">{{ phoneError() }}</p>
                }

                <div class="operator-phone-search">
                  <label>
                    <span class="material-icons-sharp">search</span>
                    <input
                      name="phoneSearch"
                      type="search"
                      placeholder="Телефон, ФИО, логин"
                      [ngModel]="phoneSearch()"
                      (ngModelChange)="setPhoneSearch($event)"
                    >
                  </label>
                  <button type="button" (click)="startNewPhone()" aria-label="Новый телефон">
                    <span class="material-icons-sharp">add</span>
                  </button>
                  <button type="button" (click)="loadPhones()" [disabled]="phoneLoading()" aria-label="Обновить">
                    <span class="material-icons-sharp">refresh</span>
                  </button>
                </div>

                <section class="operator-phone-metrics" aria-label="Статистика телефонов">
                  <article>
                    <strong>{{ phones().length }}</strong>
                    <span>всего</span>
                  </article>
                  <article>
                    <strong>{{ activePhones() }}</strong>
                    <span>активные</span>
                  </article>
                  <article>
                    <strong>{{ pausedPhones() }}</strong>
                    <span>пауза</span>
                  </article>
                </section>

                <section class="operator-phone-list" aria-label="Список телефонов">
                  @for (phone of phones(); track phone.id) {
                    <button type="button" [class.active]="selectedPhone()?.id === phone.id" (click)="selectPhone(phone)">
                      <strong>{{ phone.number }}</strong>
                      <span>{{ operatorName(phone.operator) }} · {{ timerStateForPhone(phone) }}</span>
                    </button>
                  } @empty {
                    <p class="sheet-hint">Телефонов нет.</p>
                  }
                </section>

                @if (phoneDraft(); as draft) {
                  <section class="operator-phone-editor">
                    <label class="sheet-field">
                      <span>Номер телефона</span>
                      <input name="phoneNumber" type="text" required [ngModel]="draft.number" (ngModelChange)="setPhoneField('number', $event)" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>ФИО</span>
                      <input name="phoneFio" type="text" [ngModel]="draft.fio" (ngModelChange)="setPhoneField('fio', emptyToNull($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Оператор</span>
                      <select name="phoneOperator" [ngModel]="draft.operatorId" (ngModelChange)="setPhoneField('operatorId', normalizeId($event))" [disabled]="phoneSaving()">
                        <option [ngValue]="null">Не выбран</option>
                        @for (operator of operators(); track operator.id) {
                          <option [ngValue]="operator.id">{{ operator.title }}</option>
                        }
                      </select>
                    </label>

                    <label class="sheet-field">
                      <span>Разрешено отправлять</span>
                      <input name="phoneAllowed" type="number" min="0" [ngModel]="draft.amountAllowed" (ngModelChange)="setPhoneField('amountAllowed', numericValue($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Отправлено</span>
                      <input name="phoneSent" type="number" min="0" [ngModel]="draft.amountSent" (ngModelChange)="setPhoneField('amountSent', numericValue($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Блокировка, мин</span>
                      <input name="phoneBlock" type="number" min="0" [ngModel]="draft.blockTime" (ngModelChange)="setPhoneField('blockTime', numericValue($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Таймер блокировки</span>
                      <input name="phoneTimer" type="datetime-local" [ngModel]="draft.timer" (ngModelChange)="setPhoneField('timer', $event || null)" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Дата рождения</span>
                      <input name="phoneBirthday" type="date" [ngModel]="draft.birthday" (ngModelChange)="setPhoneField('birthday', $event || null)" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Дата создания</span>
                      <input name="phoneCreateDate" type="date" [ngModel]="draft.createDate" (ngModelChange)="setPhoneField('createDate', $event || null)" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Google логин</span>
                      <input name="phoneGoogleLogin" type="text" [ngModel]="draft.googleLogin" (ngModelChange)="setPhoneField('googleLogin', emptyToNull($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Google пароль</span>
                      <input name="phoneGooglePassword" type="text" [ngModel]="draft.googlePassword" (ngModelChange)="setPhoneField('googlePassword', emptyToNull($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Avito пароль</span>
                      <input name="phoneAvitoPassword" type="text" [ngModel]="draft.avitoPassword" (ngModelChange)="setPhoneField('avitoPassword', emptyToNull($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Mail логин</span>
                      <input name="phoneMailLogin" type="text" [ngModel]="draft.mailLogin" (ngModelChange)="setPhoneField('mailLogin', emptyToNull($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Mail пароль</span>
                      <input name="phoneMailPassword" type="text" [ngModel]="draft.mailPassword" (ngModelChange)="setPhoneField('mailPassword', emptyToNull($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field">
                      <span>Фото Instagram</span>
                      <input name="phoneFotoInstagram" type="text" [ngModel]="draft.fotoInstagram" (ngModelChange)="setPhoneField('fotoInstagram', emptyToNull($event))" [disabled]="phoneSaving()">
                    </label>

                    <label class="sheet-field sheet-field--inline operator-phone-active">
                      <span>Телефон активен</span>
                      <input name="phoneActive" type="checkbox" [ngModel]="draft.active" (ngModelChange)="setPhoneField('active', $event)" [disabled]="phoneSaving()">
                    </label>
                  </section>
                }
              </section>

              <div class="sheet-actions edit-actions">
                @if (selectedPhone()) {
                  <button class="danger" type="button" (click)="deleteSelectedPhone()" [disabled]="phoneSaving() || phoneDeleting()">
                    {{ phoneDeleting() ? 'Удаляю' : 'Удалить' }}
                  </button>
                }
                <button class="secondary" type="button" (click)="startNewPhone()" [disabled]="phoneSaving() || phoneDeleting()">Новый</button>
                <button type="submit" [disabled]="phoneSaving() || phoneDeleting() || !canSavePhone()">
                  {{ phoneSaving() ? 'Сохраняю' : 'Сохранить' }}
                </button>
              </div>
            </form>
          </ng-template>
        </ion-modal>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }

    .operator-page {
      display: flex;
      height: 100%;
      max-width: 44rem;
      min-height: 0;
      margin: 0 auto;
      overflow: hidden;
      flex-direction: column;
      gap: 0.52rem;
      padding: 0.75rem 0.75rem calc(0.7rem + env(safe-area-inset-bottom));
    }

    .operator-status-scroll {
      display: flex;
      gap: 0.45rem;
      flex: 0 0 auto;
      margin-inline: -0.15rem;
      overflow: hidden;
      padding: 0 0.15rem 0.08rem;
    }

    .operator-promo-strip {
      display: flex;
      gap: 0.45rem;
      flex: 0 0 auto;
      margin-inline: -0.15rem;
      overflow-x: auto;
      padding: 0 0.15rem 0.08rem;
      scrollbar-width: none;
    }

    .operator-status-scroll::-webkit-scrollbar,
    .operator-promo-strip::-webkit-scrollbar {
      display: none;
    }

    .operator-status-scroll .metric-tile {
      flex: 1 1 0 !important;
      width: auto;
      min-width: 0;
      max-width: none;
      min-height: 3.45rem;
      box-sizing: border-box;
      border: 1px solid var(--status-menu-border, rgba(108, 155, 207, 0.28));
      background: linear-gradient(155deg, var(--status-menu-surface, var(--otziv-tone-walk-surface)) 0%, var(--otziv-white) 82%);
      box-shadow: 0 0.7rem 1.45rem rgba(132, 139, 200, 0.09);
    }

    .operator-status-scroll .metric-tile .material-icons-sharp {
      grid-row: span 2;
      width: 1.34rem;
      min-width: 1.34rem;
      overflow: hidden;
      font-size: 1.22rem;
      line-height: 1;
    }

    .operator-status-scroll .metric-tile strong {
      align-self: end;
      min-width: 0;
      overflow: hidden;
      font-size: 1.02rem;
      font-weight: 1000;
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .operator-status-scroll .metric-tile small {
      align-self: start;
      min-width: 0;
      overflow: hidden;
      font-size: 0.54rem;
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .operator-status-scroll .metric-tile.active {
      border-color: rgba(108, 155, 207, 0.5);
      background: linear-gradient(155deg, var(--status-menu-surface, var(--otziv-tone-walk-surface)) 0%, rgba(108, 155, 207, 0.14) 100%);
    }

    .operator-promo-strip button {
      display: inline-flex;
      flex: 0 0 auto;
      min-height: 2rem;
      align-items: center;
      justify-content: center;
      gap: 0.3rem;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 999px;
      padding: 0 0.72rem;
      color: var(--otziv-primary);
      background: var(--otziv-white);
      font: inherit;
      font-size: 0.68rem;
      font-weight: 900;
      white-space: nowrap;
    }

    .operator-promo-strip button:disabled {
      color: var(--otziv-info);
      opacity: 0.55;
    }

    .operator-promo-strip .material-icons-sharp {
      font-size: 1rem;
    }

    .operator-search-strip {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto auto;
      align-items: center;
      gap: 0.45rem;
      flex: 0 0 auto;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      padding: 0.48rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
    }

    .operator-search-strip label {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.45rem;
      min-width: 0;
      min-height: 2.42rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.82rem;
      padding: 0 0.7rem;
      color: var(--otziv-info);
      background: var(--otziv-white);
    }

    .operator-search-strip input {
      min-width: 0;
      border: 0;
      outline: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: 900 0.82rem/1 var(--otziv-font-family);
    }

    .operator-search-strip input::placeholder {
      color: var(--otziv-info);
      opacity: 1;
    }

    .operator-search-strip .icon-button,
    .operator-search-strip .material-icons-sharp {
      font-size: 1.12rem;
    }

    .operator-search-strip .icon-button {
      width: 2.42rem;
      height: 2.42rem;
      min-width: 2.42rem;
      border: 0;
      border-radius: 0.82rem;
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
    }

    .operator-list {
      flex: 1 1 0;
      gap: 0.72rem;
      overflow-y: hidden;
    }

    .operator-list .operator-lead-card {
      scroll-snap-align: center;
      gap: clamp(0.44rem, 0.8vh, 0.65rem);
      justify-content: space-between;
    }

    .operator-lead-card .lead-card-head strong {
      font-size: 0.98rem;
      font-weight: 1000;
      line-height: 1.06;
      -webkit-line-clamp: 2;
    }

    .operator-lead-card .lead-card-head small {
      font-size: 0.62rem;
      white-space: nowrap;
    }

    .operator-lead-card .lead-phone-row a,
    .operator-lead-card .lead-phone-row button,
    .operator-lead-card .lead-meta-row span,
    .operator-lead-card .lead-card-actions button {
      min-height: 1.62rem;
      font-size: 0.7rem;
      font-weight: 900;
    }

    .operator-comment-editor {
      flex: 0 0 clamp(5rem, 16vh, 6.4rem);
      min-height: 5rem;
    }

    .operator-comment-editor .lead-card-comment {
      min-height: 100%;
    }

    .operator-card-actions {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem 0.56rem;
    }

    .operator-card-actions button {
      min-height: 1.78rem;
      border-radius: 999px;
      font-size: 0.64rem;
    }

    .operator-empty {
      display: grid;
      min-width: min(15.4rem, 76vw);
      place-items: center;
      align-content: center;
      gap: 0.55rem;
      text-align: center;
    }

    .operator-empty .material-icons-sharp {
      font-size: 2rem;
    }

    .operator-empty p {
      margin: 0;
      color: var(--otziv-info);
      font-weight: 900;
      line-height: 1.25;
    }

    .operator-empty button {
      min-height: 2rem;
      border: 1px solid rgba(108, 155, 207, 0.24);
      border-radius: 999px;
      padding: 0 0.75rem;
      color: var(--otziv-primary);
      background: var(--otziv-white);
      font: inherit;
      font-size: 0.72rem;
      font-weight: 900;
    }

    .operator-bottom-controls {
      border-color: var(--otziv-tone-walk-border);
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .operator-phone-form {
      --phone-panel-gap: 0.65rem;
    }

    .operator-phone-search {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      align-items: center;
      gap: 0.45rem;
    }

    .operator-phone-search label {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.42rem;
      min-height: 2.25rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.85rem;
      padding: 0 0.65rem;
      color: var(--otziv-info);
      background: var(--otziv-white);
    }

    .operator-phone-search input {
      min-width: 0;
      border: 0;
      outline: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: 900 0.78rem/1 var(--otziv-font-family);
    }

    .operator-phone-search button {
      display: grid;
      width: 2.25rem;
      height: 2.25rem;
      place-items: center;
      border: 0;
      border-radius: 0.75rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    .operator-phone-search .material-icons-sharp {
      font-size: 1.05rem;
    }

    .operator-phone-metrics {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.45rem;
    }

    .operator-phone-metrics article {
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.85rem;
      padding: 0.55rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .operator-phone-metrics strong {
      display: block;
      color: var(--otziv-dark);
      font-size: 1rem;
      line-height: 1;
    }

    .operator-phone-metrics span {
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 900;
    }

    .operator-phone-list {
      display: flex;
      gap: 0.45rem;
      overflow-x: auto;
      scrollbar-width: none;
    }

    .operator-phone-list::-webkit-scrollbar {
      display: none;
    }

    .operator-phone-list button {
      display: grid;
      flex: 0 0 9.6rem;
      gap: 0.16rem;
      min-height: 3.1rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.9rem;
      padding: 0.55rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: inherit;
      text-align: left;
    }

    .operator-phone-list button.active {
      border-color: rgba(108, 155, 207, 0.48);
      background: var(--otziv-light);
    }

    .operator-phone-list strong,
    .operator-phone-list span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .operator-phone-list strong {
      font-size: 0.82rem;
      font-weight: 900;
    }

    .operator-phone-list span {
      color: var(--otziv-info);
      font-size: 0.64rem;
      font-weight: 900;
    }

    .operator-phone-editor {
      display: grid;
      gap: 0.58rem;
    }

    .operator-phone-active {
      grid-template-columns: minmax(0, 1fr) auto;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.85rem;
      padding: 0.7rem;
      background: var(--otziv-white);
    }

    .operator-phone-active input {
      width: 1.1rem;
      height: 1.1rem;
    }

    .operator-phones-sheet {
      --height: min(90vh, 42rem);
    }
  `]
})
export class OperatorPage implements OnInit, OnDestroy {
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private phoneSearchTimer: ReturnType<typeof setTimeout> | null = null;

  readonly tabs = OPERATOR_TABS;
  readonly board = signal<OperatorBoard | null>(null);
  readonly activeSection = signal<OperatorBoardSection>('queue');
  readonly keyword = signal('');
  readonly appliedKeyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly copiedKey = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly commentDrafts = signal<Record<number, string>>({});
  readonly bindModalOpen = signal(false);
  readonly telephoneIdDraft = signal('');
  readonly bindSaving = signal(false);
  readonly bindError = signal<string | null>(null);
  readonly companyLead = signal<LeadItem | null>(null);
  readonly companyPayload = signal<CompanyCreatePayload | null>(null);
  readonly companyDraft = signal<CompanyCreateDraft | null>(null);
  readonly companySubCategories = signal<CompanyCreateOption[]>([]);
  readonly companyLoading = signal(false);
  readonly companySaving = signal(false);
  readonly companyError = signal<string | null>(null);
  readonly phonesOpen = signal(false);
  readonly phoneSearch = signal('');
  readonly phones = signal<OperatorPhone[]>([]);
  readonly operators = signal<PhoneOperatorOption[]>([]);
  readonly selectedPhone = signal<OperatorPhone | null>(null);
  readonly phoneDraft = signal<OperatorPhoneRequest | null>(null);
  readonly phoneLoading = signal(false);
  readonly phoneSaving = signal(false);
  readonly phoneDeleting = signal(false);
  readonly phoneError = signal<string | null>(null);

  readonly canManagePhones = computed(() => this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']));
  readonly currentPage = computed(() => this.board()?.leads ?? this.emptyPage());
  readonly currentLeads = computed(() => this.currentPage().content ?? []);
  readonly companyCreateOpen = computed(() => this.companyLead() !== null);
  readonly companyManagerOptions = computed(() => this.companyPayload()?.managers ?? []);
  readonly companyWorkerOptions = computed(() => this.companyPayload()?.workers ?? []);
  readonly companyCategoryOptions = computed(() => this.companyPayload()?.categories ?? []);
  readonly companySubCategoryOptions = computed(() => this.companySubCategories());
  readonly companyCityOptions = computed(() => this.companyPayload()?.cities ?? []);
  readonly activePhones = computed(() => this.phones().filter((phone) => phone.active).length);
  readonly pausedPhones = computed(() => this.phones().filter((phone) => !this.isTimerReady(phone)).length);
  readonly promoItems = computed<OperatorPromoItem[]>(() => {
    const text = this.board()?.text;
    return [
      { key: 'begin', label: 'начало', text: text?.beginText ?? '', icon: 'waving_hand' },
      { key: 'offer', label: 'оффер', text: text?.offerText ?? '', icon: 'sell' },
      { key: 'repeat', label: 'повтор', text: text?.offer2Text ?? '', icon: 'history' },
      { key: 'start', label: 'старт', text: text?.startText ?? '', icon: 'rocket_launch' }
    ];
  });

  constructor(
    private readonly api: ApiService,
    private readonly auth: AuthService
  ) {}

  ngOnInit(): void {
    void this.loadBoard();
  }

  ngOnDestroy(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    if (this.phoneSearchTimer) {
      clearTimeout(this.phoneSearchTimer);
    }
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.loadBoard();
    event.target.complete();
  }

  reload(): void {
    void this.loadBoard();
  }

  setKeyword(value: string): void {
    this.keyword.set(value);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => this.applySearch(), 1000);
  }

  applySearch(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }
    this.appliedKeyword.set(this.keyword().trim());
    this.pageNumber.set(0);
    void this.loadBoard().finally(() => this.resetListScroll());
  }

  clearSearch(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }
    this.keyword.set('');
    this.appliedKeyword.set('');
    this.pageNumber.set(0);
    void this.loadBoard().finally(() => this.resetListScroll());
  }

  setSection(section: OperatorBoardSection): void {
    if (this.activeSection() === section) {
      return;
    }
    this.activeSection.set(section);
    this.pageNumber.set(0);
    void this.loadBoard().finally(() => this.resetListScroll());
  }

  previousPage(): void {
    if (this.isFirstPage() || this.loading()) {
      return;
    }
    this.pageNumber.set(Math.max(0, this.pageNumber() - 1));
    void this.loadBoard().finally(() => this.resetListScroll());
  }

  nextPage(): void {
    if (this.isLastPage() || this.loading()) {
      return;
    }
    this.pageNumber.set(this.pageNumber() + 1);
    void this.loadBoard().finally(() => this.resetListScroll());
  }

  tabValue(section: OperatorBoardSection): number {
    return section === 'queue' ? this.board()?.queueTotal ?? 0 : this.board()?.sentTotal ?? 0;
  }

  timerState(): string {
    const board = this.board();
    if (!board) {
      return '-';
    }
    if (board.requireDeviceId) {
      return 'ID';
    }
    return board.timerExpired ? 'готов' : 'пауза';
  }

  pageLabel(): string {
    return `${this.currentPageIndex()} / ${this.currentPageTotal()}`;
  }

  currentPageIndex(): number {
    const page = this.currentPage();
    return (page.number ?? page.pageNumber ?? this.pageNumber()) + 1;
  }

  currentPageTotal(): number {
    return this.currentPage().totalPages || 1;
  }

  isFirstPage(): boolean {
    return this.currentPage().first ?? this.pageNumber() <= 0;
  }

  isLastPage(): boolean {
    return this.currentPage().last ?? this.currentPageIndex() >= this.currentPageTotal();
  }

  emptyMessage(): string {
    const board = this.board();
    if (!board) {
      return 'Доска еще не загружена.';
    }
    if (board.requireDeviceId) {
      return 'Укажи ID телефона, чтобы привязать рабочее устройство.';
    }
    if (this.activeSection() === 'sent') {
      return 'Сейчас нет отправленных лидов.';
    }
    if (this.appliedKeyword()) {
      return 'По этому номеру ничего не найдено.';
    }
    if (!board.timerExpired) {
      return 'Телефон на паузе после лимита отправок.';
    }
    return 'Сейчас нет лидов для выдачи.';
  }

  commentFor(lead: LeadItem): string {
    return this.commentDrafts()[lead.id] ?? lead.commentsLead ?? '';
  }

  setComment(lead: LeadItem, value: string): void {
    this.commentDrafts.update((drafts) => ({
      ...drafts,
      [lead.id]: value
    }));
  }

  actionsForLead(lead: LeadItem): OperatorAction[] {
    return this.isSentLead(lead) ? ['toWork'] : ['send', 'toWork'];
  }

  actionLabel(action: OperatorAction): string {
    return action === 'send' ? 'отправил' : 'передать';
  }

  actionIcon(action: OperatorAction): string {
    return action === 'send' ? 'send' : 'swap_horiz';
  }

  isMutating(lead: LeadItem, action: OperatorAction): boolean {
    return this.mutationKey() === `${lead.id}:${action}`;
  }

  async runAction(lead: LeadItem, action: OperatorAction): Promise<void> {
    const key = `${lead.id}:${action}`;
    this.mutationKey.set(key);
    this.error.set(null);

    try {
      if (action === 'send') {
        await firstValueFrom(this.api.markOperatorLeadSend(lead.id));
      } else {
        await firstValueFrom(this.api.markOperatorLeadToWork(lead.id, this.commentFor(lead)));
      }
      await this.loadBoard();
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Не удалось изменить статус лида.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  async copyPhone(lead: LeadItem): Promise<void> {
    await this.copyText(normalizePhoneDigits(lead.telephoneLead), `phone-${lead.id}`, 'Не удалось скопировать телефон.');
  }

  async copyPromo(item: OperatorPromoItem): Promise<void> {
    await this.copyText(item.text, `promo-${item.key}`, 'Не удалось скопировать текст.');
  }

  whatsappUrl(lead: LeadItem): string {
    const phone = this.normalizedPhone(lead);
    return phone ? `https://wa.me/${phone}` : '';
  }

  phoneDisplay(lead: LeadItem): string {
    return displayPhone(lead.telephoneLead, '-');
  }

  leadTitle(lead: LeadItem): string {
    return lead.companyName?.trim() || lead.lidStatus || 'Без статуса';
  }

  leadTone(lead: LeadItem): 'default' | 'new' | 'work' | 'send' {
    const status = (lead.lidStatus ?? '').trim().toLocaleLowerCase('ru-RU');
    if (status.includes('нов')) {
      return 'new';
    }
    if (status.includes('работ')) {
      return 'work';
    }
    if (status.includes('отправ') || status.includes('рассыл')) {
      return 'send';
    }
    return 'default';
  }

  businessLine(lead: LeadItem): string {
    return [lead.companyType, lead.industries].map((value) => value?.trim()).filter(Boolean).join(' • ');
  }

  addressLine(lead: LeadItem): string {
    return [lead.region, lead.address].map((value) => value?.trim()).filter(Boolean).join(' • ');
  }

  assigneeName(person?: LeadPerson): string {
    return person?.fio || person?.username || (person?.id ? `ID ${person.id}` : '-');
  }

  dateText(value?: string): string {
    return value ? value.slice(0, 10) : '-';
  }

  openBindModal(): void {
    this.bindError.set(null);
    this.telephoneIdDraft.set(this.board()?.telephoneId ? String(this.board()?.telephoneId) : this.telephoneIdDraft());
    this.bindModalOpen.set(true);
  }

  closeBindModal(): void {
    if (this.bindSaving()) {
      return;
    }
    this.bindModalOpen.set(false);
    this.bindError.set(null);
  }

  setTelephoneIdDraft(value: unknown): void {
    this.telephoneIdDraft.set(String(value ?? ''));
  }

  canSaveDeviceToken(): boolean {
    const id = Number(this.telephoneIdDraft());
    return Number.isFinite(id) && id > 0;
  }

  async saveDeviceToken(): Promise<void> {
    if (!this.canSaveDeviceToken()) {
      this.bindError.set('Введите корректный ID телефона.');
      return;
    }

    this.bindSaving.set(true);
    this.bindError.set(null);

    try {
      await firstValueFrom(this.api.bindOperatorDevice(Number(this.telephoneIdDraft())));
      this.bindModalOpen.set(false);
      await this.loadBoard();
    } catch (error) {
      this.bindError.set(this.apiErrorMessage(error, 'Не удалось привязать телефон.'));
    } finally {
      this.bindSaving.set(false);
    }
  }

  openCompanyCreate(lead: LeadItem): void {
    if (this.companySaving()) {
      return;
    }
    this.companyLead.set(lead);
    this.companyPayload.set(null);
    this.companyDraft.set(null);
    this.companySubCategories.set([]);
    this.companyError.set(null);
    void this.loadCompanyPayload();
  }

  closeCompanyCreate(): void {
    if (this.companySaving()) {
      return;
    }
    this.companyLead.set(null);
    this.companyPayload.set(null);
    this.companyDraft.set(null);
    this.companySubCategories.set([]);
    this.companyError.set(null);
    this.companyLoading.set(false);
  }

  setCompanyField<K extends keyof CompanyCreateDraft>(field: K, value: CompanyCreateDraft[K]): void {
    this.companyDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  changeCompanyManager(managerId: number | null): void {
    const preserved = this.companyDraft() ? this.preservedCompanyFields(this.companyDraft()!) : undefined;
    void this.loadCompanyPayload(managerId, preserved);
  }

  changeCompanyCategory(categoryId: number | null): void {
    this.companyDraft.update((draft) => draft ? { ...draft, categoryId, subCategoryId: null } : draft);
    this.companySubCategories.set([]);
    if (categoryId) {
      void this.loadCompanySubCategories(categoryId);
    }
  }

  canSaveCompany(): boolean {
    const draft = this.companyDraft();
    return Boolean(
      draft?.title.trim()
      && draft.urlChat.trim()
      && draft.telephone.trim()
      && draft.city.trim()
      && draft.categoryId
      && draft.subCategoryId
      && draft.workerId
      && draft.filialCityId
      && draft.filialTitle.trim()
      && draft.filialUrl.trim()
    );
  }

  async saveCompany(): Promise<void> {
    const draft = this.companyDraft();
    if (!draft || !this.canSaveCompany()) {
      this.companyError.set('Заполните обязательные поля компании.');
      return;
    }

    this.companySaving.set(true);
    this.companyError.set(null);

    try {
      await firstValueFrom(this.api.createCompany(this.companyRequestFromDraft(draft)));
      this.closeCompanyCreate();
      await this.loadBoard();
    } catch (error) {
      this.companyError.set(this.apiErrorMessage(error, 'Не удалось создать компанию.'));
    } finally {
      this.companySaving.set(false);
    }
  }

  openPhones(): void {
    if (!this.canManagePhones()) {
      return;
    }
    this.phonesOpen.set(true);
    if (!this.phones().length) {
      void this.loadPhones();
    }
  }

  closePhones(): void {
    if (this.phoneSaving() || this.phoneDeleting()) {
      return;
    }
    this.phonesOpen.set(false);
    this.phoneError.set(null);
  }

  setPhoneSearch(value: string): void {
    this.phoneSearch.set(value);
    if (this.phoneSearchTimer) {
      clearTimeout(this.phoneSearchTimer);
    }
    this.phoneSearchTimer = setTimeout(() => {
      void this.loadPhones();
    }, 1000);
  }

  async loadPhones(): Promise<void> {
    if (!this.canManagePhones()) {
      return;
    }

    this.phoneLoading.set(true);
    this.phoneError.set(null);

    try {
      const response = await firstValueFrom(this.api.getOperatorPhones(this.phoneSearch()));
      this.phones.set(response.phones);
      this.operators.set(response.operators);
      this.restorePhoneSelection(response.phones);
    } catch (error) {
      this.phoneError.set(this.apiErrorMessage(error, 'Не удалось загрузить телефоны.'));
    } finally {
      this.phoneLoading.set(false);
    }
  }

  selectPhone(phone: OperatorPhone): void {
    this.selectedPhone.set(phone);
    this.phoneDraft.set(this.phoneDraftFromPhone(phone));
    this.phoneError.set(null);
  }

  startNewPhone(): void {
    this.selectedPhone.set(null);
    this.phoneError.set(null);
    this.phoneDraft.set({
      number: '+7',
      fio: null,
      birthday: null,
      amountAllowed: 1,
      amountSent: 0,
      blockTime: 3,
      timer: this.toDateTimeInput(new Date().toISOString()),
      googleLogin: null,
      googlePassword: null,
      avitoPassword: null,
      mailLogin: null,
      mailPassword: null,
      fotoInstagram: null,
      active: true,
      createDate: this.toDateInput(new Date().toISOString()),
      operatorId: null
    });
  }

  setPhoneField<K extends keyof OperatorPhoneRequest>(field: K, value: OperatorPhoneRequest[K]): void {
    this.phoneDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  canSavePhone(): boolean {
    const draft = this.phoneDraft();
    return Boolean(draft?.number.trim()) && Number.isFinite(draft?.amountAllowed) && Number.isFinite(draft?.amountSent) && Number.isFinite(draft?.blockTime);
  }

  async savePhone(): Promise<void> {
    const draft = this.phoneDraft();
    if (!draft || !this.canSavePhone()) {
      this.phoneError.set('Номер и числовые поля обязательны.');
      return;
    }

    this.phoneSaving.set(true);
    this.phoneError.set(null);

    try {
      const selected = this.selectedPhone();
      const request = this.phoneRequest(draft);
      const saved = selected
        ? await firstValueFrom(this.api.updateOperatorPhone(selected.id, request))
        : await firstValueFrom(this.api.createOperatorPhone(request));
      this.selectedPhone.set(saved);
      this.phoneDraft.set(this.phoneDraftFromPhone(saved));
      await this.loadPhones();
    } catch (error) {
      this.phoneError.set(this.apiErrorMessage(error, 'Не удалось сохранить телефон.'));
    } finally {
      this.phoneSaving.set(false);
    }
  }

  async deleteSelectedPhone(): Promise<void> {
    const phone = this.selectedPhone();
    if (!phone || this.phoneDeleting() || !window.confirm(`Удалить телефон ${phone.number}?`)) {
      return;
    }

    this.phoneDeleting.set(true);
    this.phoneError.set(null);

    try {
      await firstValueFrom(this.api.deleteOperatorPhone(phone.id));
      this.startNewPhone();
      await this.loadPhones();
    } catch (error) {
      this.phoneError.set(this.apiErrorMessage(error, 'Не удалось удалить телефон.'));
    } finally {
      this.phoneDeleting.set(false);
    }
  }

  operatorName(operator?: PhoneOperatorOption | null): string {
    return operator?.title || '-';
  }

  timerStateForPhone(phone: OperatorPhone): string {
    return this.isTimerReady(phone) ? 'готов' : 'пауза';
  }

  isTimerReady(phone: OperatorPhone): boolean {
    return !phone.timer || new Date(phone.timer).getTime() <= Date.now();
  }

  numericValue(value: unknown): number {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  normalizeId(value: unknown): number | null {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
  }

  emptyToNull(value: unknown): string | null {
    const trimmed = String(value ?? '').trim();
    return trimmed || null;
  }

  private async loadBoard(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);

    try {
      const board = await firstValueFrom(this.api.getOperatorBoard({
        section: this.activeSection(),
        keyword: this.appliedKeyword(),
        pageNumber: this.pageNumber(),
        pageSize: this.pageSize()
      }));
      this.activeSection.set(board.section ?? this.activeSection());
      this.board.set(board);
      this.mergeCommentDrafts(board);
      if (board.requireDeviceId) {
        this.bindModalOpen.set(true);
      }
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Не удалось загрузить доску оператора.'));
    } finally {
      this.loading.set(false);
    }
  }

  private mergeCommentDrafts(board: OperatorBoard): void {
    this.commentDrafts.update((drafts) => {
      const next = { ...drafts };
      board.leads.content.forEach((lead) => {
        if (next[lead.id] === undefined) {
          next[lead.id] = lead.commentsLead ?? '';
        }
      });
      return next;
    });
  }

  private isSentLead(lead: LeadItem): boolean {
    return lead.lidStatus === 'Отправленный' || lead.lidStatus === 'К рассылке';
  }

  private normalizedPhone(lead: LeadItem): string {
    return normalizePhoneDigits(lead.telephoneLead);
  }

  private emptyPage() {
    return {
      content: [],
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
      number: this.pageNumber(),
      pageNumber: this.pageNumber(),
      size: this.pageSize(),
      pageSize: this.pageSize()
    };
  }

  private resetListScroll(): void {
    window.setTimeout(() => {
      document.querySelector<HTMLElement>('app-operator .lead-list')?.scrollTo({ left: 0, top: 0, behavior: 'auto' });
    });
  }

  private async copyText(value: string, key: string, fallbackError: string): Promise<void> {
    const text = value.trim();
    if (!text) {
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      document.body.append(textarea);
      textarea.select();
      document.execCommand('copy');
      textarea.remove();
    }
    this.copiedKey.set(key);
    window.setTimeout(() => {
      if (this.copiedKey() === key) {
        this.copiedKey.set(null);
      }
    }, 1200);
  }

  private async loadCompanyPayload(managerId?: number | null, preserved?: CompanyPreservedFields): Promise<void> {
    const lead = this.companyLead();
    if (!lead) {
      return;
    }

    this.companyLoading.set(true);
    this.companyError.set(null);

    try {
      const payload = await firstValueFrom(this.api.getCompanyCreatePayload('operator', lead.id, managerId));
      this.companyPayload.set(payload);
      this.companySubCategories.set(payload.subCategories ?? []);
      this.companyDraft.set({
        ...this.companyDraftFromPayload(payload, managerId),
        ...preserved
      });
    } catch (error) {
      this.companyError.set(this.apiErrorMessage(error, 'Не удалось загрузить данные для компании.'));
    } finally {
      this.companyLoading.set(false);
    }
  }

  private async loadCompanySubCategories(categoryId: number): Promise<void> {
    try {
      this.companySubCategories.set(await firstValueFrom(this.api.getCompanySubcategories(categoryId)));
    } catch (error) {
      this.companyError.set(this.apiErrorMessage(error, 'Не удалось загрузить подкатегории.'));
    }
  }

  private companyDraftFromPayload(payload: CompanyCreatePayload, managerId?: number | null): CompanyCreateDraft {
    return {
      source: payload.source,
      leadId: payload.leadId ?? null,
      managerId: managerId ?? payload.manager?.id ?? payload.managers[0]?.id ?? null,
      title: payload.title ?? '',
      urlChat: payload.urlChat ?? '',
      urlSite: payload.urlSite ?? '',
      telephone: payload.telephone ?? '',
      city: payload.city ?? '',
      email: payload.email ?? '',
      commentsCompany: payload.commentsCompany ?? '',
      categoryId: payload.category?.id ?? null,
      subCategoryId: payload.subCategory?.id ?? null,
      workerId: payload.worker?.id ?? null,
      filialCityId: payload.filialCity?.id ?? null,
      filialTitle: payload.filialTitle ?? '',
      filialUrl: payload.filialUrl ?? ''
    };
  }

  private preservedCompanyFields(draft: CompanyCreateDraft): CompanyPreservedFields {
    return {
      title: draft.title,
      urlChat: draft.urlChat,
      urlSite: draft.urlSite,
      telephone: draft.telephone,
      city: draft.city,
      email: draft.email,
      commentsCompany: draft.commentsCompany,
      categoryId: draft.categoryId,
      subCategoryId: draft.subCategoryId,
      workerId: draft.workerId,
      filialCityId: draft.filialCityId,
      filialTitle: draft.filialTitle,
      filialUrl: draft.filialUrl
    };
  }

  private companyRequestFromDraft(draft: CompanyCreateDraft): CompanyCreateRequest {
    return {
      source: draft.source,
      leadId: draft.leadId,
      managerId: draft.managerId,
      title: draft.title.trim(),
      urlChat: draft.urlChat.trim(),
      urlSite: draft.urlSite.trim(),
      telephone: draft.telephone.trim(),
      city: draft.city.trim(),
      email: draft.email.trim(),
      commentsCompany: draft.commentsCompany.trim(),
      categoryId: draft.categoryId,
      subCategoryId: draft.subCategoryId,
      workerId: draft.workerId,
      filialCityId: draft.filialCityId,
      filialTitle: draft.filialTitle.trim(),
      filialUrl: draft.filialUrl.trim()
    };
  }

  private restorePhoneSelection(phones: OperatorPhone[]): void {
    const selectedId = this.selectedPhone()?.id;
    const selected = selectedId ? phones.find((phone) => phone.id === selectedId) : null;
    if (selected) {
      this.selectPhone(selected);
      return;
    }
    if (!this.phoneDraft()) {
      this.startNewPhone();
    }
  }

  private phoneDraftFromPhone(phone: OperatorPhone): OperatorPhoneRequest {
    return {
      number: phone.number ?? '+7',
      fio: phone.fio ?? null,
      birthday: this.toDateInput(phone.birthday),
      amountAllowed: phone.amountAllowed,
      amountSent: phone.amountSent,
      blockTime: phone.blockTime,
      timer: this.toDateTimeInput(phone.timer),
      googleLogin: phone.googleLogin ?? null,
      googlePassword: phone.googlePassword ?? null,
      avitoPassword: phone.avitoPassword ?? null,
      mailLogin: phone.mailLogin ?? null,
      mailPassword: phone.mailPassword ?? null,
      fotoInstagram: phone.fotoInstagram ?? null,
      active: phone.active,
      createDate: this.toDateInput(phone.createDate),
      operatorId: phone.operator?.id ?? null
    };
  }

  private phoneRequest(draft: OperatorPhoneRequest): OperatorPhoneRequest {
    return {
      number: draft.number.trim(),
      fio: this.emptyToNull(draft.fio),
      birthday: draft.birthday || null,
      amountAllowed: Number(draft.amountAllowed || 0),
      amountSent: Number(draft.amountSent || 0),
      blockTime: Number(draft.blockTime || 0),
      timer: draft.timer || null,
      googleLogin: this.emptyToNull(draft.googleLogin),
      googlePassword: this.emptyToNull(draft.googlePassword),
      avitoPassword: this.emptyToNull(draft.avitoPassword),
      mailLogin: this.emptyToNull(draft.mailLogin),
      mailPassword: this.emptyToNull(draft.mailPassword),
      fotoInstagram: this.emptyToNull(draft.fotoInstagram),
      active: draft.active,
      createDate: draft.createDate || null,
      operatorId: draft.operatorId ?? null
    };
  }

  private toDateInput(value?: string | null): string {
    return value ? value.slice(0, 10) : '';
  }

  private toDateTimeInput(value?: string | null): string {
    return value ? value.slice(0, 16) : '';
  }

  private apiErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof Error && error.message) {
      return error.message;
    }
    const maybe = error as { error?: unknown; message?: string; status?: number };
    if (typeof maybe.error === 'string' && maybe.error.trim()) {
      return maybe.error;
    }
    if (typeof maybe.error === 'object' && maybe.error !== null) {
      const body = maybe.error as { message?: string; detail?: string; error?: string };
      return body.message || body.detail || body.error || fallback;
    }
    return maybe.message || (maybe.status ? `${fallback} Код: ${maybe.status}` : fallback);
  }
}
