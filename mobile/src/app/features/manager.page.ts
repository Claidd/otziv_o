import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import {
  IonContent,
  IonModal,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import { Subscription, firstValueFrom } from 'rxjs';
import {
  ApiService,
  CompanyEditPayload,
  CompanyFilialEditItem,
  CompanyFilialUpdateRequest,
  CompanyCreateOption,
  CompanyOrderCreatePayload,
  CompanyOrderCreateRequest,
  CompanyOrderCreateResult,
  CompanyCreatePayload,
  CompanyCreateRequest,
  CompanyCreateSource,
  CompanyItem,
  CompanyUpdateRequest,
  ManagerBoard,
  ManagerBoardSection,
  ManagerOption,
  OrderEditPayload,
  OrderItem,
  OrderUpdateRequest,
  Page
} from '../core/api.service';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileBottomPagerComponent } from '../shared/mobile-bottom-pager.component';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileOrderCardComponent, type MobileOrderCopyKind, type MobileOrderStatusAction } from '../shared/mobile-order-card.component';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';
import { MobileSearchBarComponent } from '../shared/mobile-search-bar.component';
import { MobileStatusSliderComponent, type MobileStatusItem } from '../shared/mobile-status-slider.component';
import {
  mobilePageIndex,
  mobilePageIsFirst,
  mobilePageIsLast,
  mobilePageLabel,
  mobilePageTotal,
  mobileSortTitle
} from '../shared/mobile-board.helpers';
import { displayPhone, normalizePhoneDigits, phoneHref } from '../shared/phone-format';
import {
  ALL_STATUS,
  COMPANY_ACTIONS,
  DEFAULT_COMPANY_STATUSES,
  DEFAULT_ORDER_STATUSES,
  ORDER_ACTIONS,
  PREFERRED_CREATE_ORDER_PRODUCT,
  ensureAllStatus,
  managerStatusLabel,
  managerOrderActionsFor,
  normalizedManagerStatus,
  type CompanyStatusAction,
  type ManagerNoteSaveState,
  type OrderStatusAction
} from './manager/manager-board.helpers';
import { MobileCompanyCardComponent } from './manager/mobile-company-card.component';

type SelectedCompany = { id: number; title: string };
type CompanyFilialEditDraft = CompanyFilialUpdateRequest & { filialId: number };
type CompanyNoteSaveState = ManagerNoteSaveState;
type OrderNoteSaveState = CompanyNoteSaveState;
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

@Component({
  selector: 'app-manager',
  imports: [FormsModule, IonContent, IonModal, IonRefresher, IonRefresherContent, MobileBottomPagerComponent, MobileCompanyCardComponent, MobileHeaderComponent, MobileOrderCardComponent, MobileRemindersComponent, MobileSearchBarComponent, MobileStatusSliderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header [title]="sectionLabel()" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <app-mobile-reminders #reminders />

        <main class="manager-page">
          <app-mobile-status-slider
            [items]="statusItems()"
            [activeKey]="currentStatus()"
            [ariaLabel]="'Статусы: ' + sectionLabel()"
            (select)="selectStatus($event)"
          />

          <app-mobile-search-bar
            [value]="keyword()"
            [placeholder]="searchPlaceholder()"
            [showRefresh]="false"
            [hasExtraAction]="true"
            (valueChange)="setKeyword($event)"
            (searchSubmit)="applySearch()"
          >
            @if (keyword() || appliedKeyword()) {
              <button mobileSearchActions type="button" (click)="clearSearch()" [disabled]="loading()" aria-label="Сбросить поиск">
                <span class="material-icons-sharp">close</span>
              </button>
            }

            <button mobileSearchActions type="button" (click)="openArchive()" [disabled]="loading()" aria-label="Архив">
              <span class="material-icons-sharp">archive</span>
            </button>

            @if (activeSection() === 'companies') {
              <button mobileSearchActions type="button" (click)="openManualCompanyCreate()" aria-label="Добавить компанию">
                <span class="material-icons-sharp">add</span>
              </button>
            }
          </app-mobile-search-bar>

          @if (error()) {
            <button class="inline-alert" type="button" (click)="reload()">
              <span class="material-icons-sharp">error</span>
              <span>{{ error() }}</span>
            </button>
          }

          <section
            class="lead-list manager-list"
            [class.manager-list--companies]="activeSection() === 'companies'"
            [class.manager-list--orders]="activeSection() === 'orders'"
            aria-label="Список"
          >
            @if (activeSection() === 'companies') {
              @for (company of board()?.companies?.content ?? []; track company.id) {
                <app-mobile-company-card
                  [company]="company"
                  [actions]="companyActions"
                  [copiedKey]="copiedKey()"
                  [mutationKey]="mutationKey()"
                  [note]="companyNoteFor(company)"
                  [noteStateLabel]="companyNoteSaveLabel(company) || ''"
                  [noteState]="companyNoteSaveState(company)"
                  (copyPhone)="copyCompanyPhone($event)"
                  (createOrder)="openCompanyOrderCreate($event)"
                  (focusNote)="focusCompanyNote($event)"
                  (noteChange)="setCompanyNote(company, $event)"
                  (noteSave)="saveCompanyNoteNow(company)"
                  (statusChange)="updateCompanyStatus(company, $event)"
                  (openOrders)="openCompanyOrders($event)"
                  (showAllOrders)="showAllCompanyOrders()"
                  (edit)="openCompanyEdit($event)"
                />
              } @empty {
                <div class="empty-state compact-empty">Компаний для отображения нет.</div>
              }
            } @else {
              @for (order of board()?.orders?.content ?? []; track order.id) {
                <app-mobile-order-card
                  [order]="order"
                  [statusActions]="orderActionsFor(order)"
                  [copiedKey]="copiedKey()"
                  [mutationKey]="mutationKey()"
                  [title]="orderTitle(order)"
                  [titleHref]="orderChatUrl(order)"
                  [toneClass]="orderTone(order)"
                  [companyMode]="!!selectedCompany()"
                  [statusLabel]="order.status || '-'"
                  [amountLabel]="orderMoney(orderPayableSum(order))"
                  [badReviewSummary]="showBadReviewSummary(order) ? 'Плохие: ' + (order.badReviewTasksDone || 0) + '/' + (order.badReviewTasksTotal || 0) : ''"
                  [badReviewAmount]="showBadReviewSummary(order) ? '+' + orderMoney(order.badReviewTasksSum || 0) : ''"
                  [phoneLabel]="orderPhone(order)"
                  [phoneHref]="orderChatUrl(order)"
                  [reviewHref]="orderReviewUrl(order)"
                  [filialHref]="orderFilialUrl(order)"
                  [phoneCopyKey]="'order-phone-' + order.id"
                  [reviewCopyKey]="'order-review-' + order.id"
                  [paymentCopyKey]="'order-payment-' + order.id"
                  [progress]="orderProgress(order)"
                  [cityLabel]="orderCity(order)"
                  [workerLabel]="workerLabel(order)"
                  [workerTitle]="order.workerUserFio || 'Исполнитель не назначен'"
                  [noteBadge]="orderNoteBadge(order)"
                  [showNoteEditor]="!selectedCompany()"
                  [noteEditorId]="'orderNote' + order.id"
                  [noteValue]="orderNoteFor(order)"
                  [noteStateLabel]="orderNoteSaveLabel(order) || ''"
                  [noteState]="orderNoteSaveState(order)"
                  [canManageClientWaiting]="canManageOrderClientWaiting(order)"
                  [unchangedDays]="orderUnchangedDays(order)"
                  [unchangedAlert]="isOrderUnchangedAlert(order)"
                  (copyPhone)="copyOrderPhone(order)"
                  (copyText)="copyManagerOrderText(order, $event)"
                  (statusChange)="updateOrderStatusFromCard(order, $event)"
                  (clientWaitingToggle)="toggleOrderClientWaiting(order)"
                  (noteBadgeClick)="focusOrderNote(order)"
                  (noteChange)="setOrderNote(order, $event)"
                  (noteBlur)="saveOrderNoteNow(order)"
                  (details)="openOrderDetails(order)"
                  (workerClick)="openOrderEdit(order)"
                />
              } @empty {
                <div class="empty-state compact-empty">Заказов для отображения нет.</div>
              }
            }
          </section>

          <app-mobile-bottom-pager
            class="mobile-page-bottom-pager"
            [pageIndex]="currentPageIndex() - 1"
            [totalPages]="currentPageTotal()"
            [disabled]="loading()"
            (previous)="previousPage()"
            (next)="nextPage()"
          >
            <button mobilePagerActions class="expand-list-button reminder-hero-button" type="button" (click)="reminders.open()" aria-label="Напоминания">
              <span class="material-icons-sharp">notifications_active</span>
              @if (reminders.activeReminderCount()) {
                <small>{{ reminders.activeReminderCount() }}</small>
              }
            </button>
            <button
              mobilePagerActions
              class="expand-list-button"
              type="button"
              [class.active]="sortDirection() === 'asc'"
              (click)="toggleSortDirection()"
              [attr.aria-label]="sortDirection() === 'asc' ? 'Показать сначала давно без изменений' : 'Показать сначала недавно измененные'"
            >
              <span class="material-icons-sharp">swap_vert</span>
            </button>
          </app-mobile-bottom-pager>
        </main>

        @if (statusSheetOpen()) {
          <button class="manager-status-backdrop" type="button" aria-label="Закрыть статусы" (click)="closeStatusSheet()"></button>
          <section
            [class]="'manager-status-sheet manager-status-sheet--' + activeSection()"
            role="dialog"
            aria-modal="true"
            aria-label="Выбор статуса"
          >
            <header>
              <div>
                <small>{{ sectionLabel() }}</small>
                <h2>Выберите статус</h2>
              </div>
              <button type="button" class="icon-button" (click)="closeStatusSheet()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </header>

            <div class="manager-status-options">
              @for (status of currentStatuses(); track status) {
                <button
                  type="button"
                  class="metric-tile {{ statusTone(status) }}"
                  [class.active]="currentStatus() === normalizedStatus(status)"
                  (click)="selectStatus(status)"
                >
                  <span class="material-icons-sharp">{{ statusIcon(status) }}</span>
                  <strong>{{ metricValue(status) }}</strong>
                  <small>{{ statusLabel(status) }}</small>
                </button>
              }
            </div>
          </section>
        }

        @if (companyDetails(); as company) {
          <button class="manager-detail-backdrop" type="button" aria-label="Закрыть компанию" (click)="closeCompanyDetails()"></button>
          <section class="manager-detail-sheet" role="dialog" aria-modal="true" aria-label="Карточка компании">
            <header>
              <div>
                <small>Компания #{{ company.id }} · {{ company.status }}</small>
                <h2>{{ company.title }}</h2>
              </div>
              <button type="button" class="icon-button" (click)="closeCompanyDetails()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </header>

            <div class="manager-detail-grid">
              <div>
                <small>Телефон</small>
                <b>{{ companyPhone(company) }}</b>
              </div>
              <div>
                <small>Город</small>
                <b>{{ company.city || '-' }}</b>
              </div>
              <div>
                <small>Менеджер</small>
                <b>{{ company.manager || '-' }}</b>
              </div>
              <div>
                <small>Филиалы</small>
                <b>{{ filialLabel(company) }}</b>
              </div>
              <div class="wide">
                <small>Комментарий</small>
                <p>{{ company.commentsCompany || 'Комментарий не указан.' }}</p>
              </div>
            </div>

            @if (hasNextOrderRequest(company)) {
              <p class="company-next-order detail-next-order" [class.company-next-order--failed]="hasFailedNextOrderRequest(company)">
                <span class="material-icons-sharp">{{ hasFailedNextOrderRequest(company) ? 'error' : 'assignment_add' }}</span>
                {{ nextOrderRequestLabel(company) }}
              </p>
            }

            <div class="lead-action-grid">
              <a [href]="companyChatUrl(company)" target="_blank" rel="noopener">
                <span class="material-icons-sharp">chat</span>
                Связь
              </a>
              <button type="button" (click)="openCompanyOrders(company)">
                <span class="material-icons-sharp">inventory_2</span>
                Заказы
              </button>
              @for (action of companyActions; track action.status) {
                <button
                  type="button"
                  (click)="updateCompanyStatus(company, action)"
                  [disabled]="isMutatingCompany(company, action)"
                >
                  <span class="material-icons-sharp">{{ action.icon }}</span>
                  {{ isMutatingCompany(company, action) ? 'выполняю' : action.label }}
                </button>
              }
            </div>
          </section>
        }

        <ion-modal class="sheet-modal company-create-sheet company-edit-sheet" [isOpen]="companyEditOpen()" (didDismiss)="closeCompanyEdit()">
          <ng-template>
            <form class="sheet-body sheet-form" (ngSubmit)="saveCompanyEdit()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Компания #{{ companyEdit()?.id || '' }}</p>
                  <h2>Редактор компании</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeCompanyEdit()" [disabled]="companyEditSaving() || !!companyEditDeleteKey()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content">
                @if (companyEditLoading() && !companyEdit()) {
                  <p class="sheet-hint">Загружаю компанию...</p>
                }

                @if (companyEditError()) {
                  <p class="sheet-error">{{ companyEditError() }}</p>
                }

                @if (companyEdit(); as company) {
                  @if (companyEditDraft(); as draft) {
                    <label class="sheet-field">
                      <span>Название</span>
                      <input
                        name="editCompanyTitle"
                        type="text"
                        required
                        [ngModel]="draft.title"
                        (ngModelChange)="setCompanyEditField('title', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Телефон</span>
                      <input
                        name="editCompanyTelephone"
                        type="tel"
                        required
                        [ngModel]="draft.telephone"
                        (ngModelChange)="setCompanyEditField('telephone', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Город компании</span>
                      <input
                        name="editCompanyCity"
                        type="text"
                        required
                        [ngModel]="draft.city"
                        (ngModelChange)="setCompanyEditField('city', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Ссылка на чат</span>
                      <input
                        name="editCompanyUrlChat"
                        type="text"
                        required
                        [ngModel]="draft.urlChat"
                        (ngModelChange)="setCompanyEditField('urlChat', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Сайт</span>
                      <input
                        name="editCompanyUrlSite"
                        type="url"
                        [ngModel]="draft.urlSite"
                        (ngModelChange)="setCompanyEditField('urlSite', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Email</span>
                      <input
                        name="editCompanyEmail"
                        type="email"
                        [ngModel]="draft.email"
                        (ngModelChange)="setCompanyEditField('email', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Категория</span>
                      <select
                        name="editCompanyCategory"
                        [ngModel]="draft.categoryId"
                        (ngModelChange)="changeCompanyEditCategory($event)"
                        [disabled]="companyEditSaving()"
                      >
                        <option [ngValue]="null">Не выбрана</option>
                        @for (category of company.categories; track category.id) {
                          <option [ngValue]="category.id">{{ category.label }}</option>
                        }
                      </select>
                    </label>

                    <label class="sheet-field">
                      <span>Подкатегория</span>
                      <select
                        name="editCompanySubCategory"
                        [ngModel]="draft.subCategoryId"
                        (ngModelChange)="setCompanyEditField('subCategoryId', $event)"
                        [disabled]="companyEditSaving() || !draft.categoryId"
                      >
                        <option [ngValue]="null">Не выбрана</option>
                        @for (subCategory of company.subCategories; track subCategory.id) {
                          <option [ngValue]="subCategory.id">{{ subCategory.label }}</option>
                        }
                      </select>
                    </label>

                    <label class="sheet-field">
                      <span>Статус</span>
                      <select
                        name="editCompanyStatus"
                        required
                        [ngModel]="draft.statusId"
                        (ngModelChange)="setCompanyEditField('statusId', $event)"
                        [disabled]="companyEditSaving()"
                      >
                        @for (status of company.statuses; track status.id) {
                          <option [ngValue]="status.id">{{ status.label }}</option>
                        }
                      </select>
                    </label>

                    @if (company.canChangeManager) {
                      <label class="sheet-field">
                        <span>Менеджер</span>
                        <select
                          name="editCompanyManager"
                          [ngModel]="draft.managerId"
                          (ngModelChange)="setCompanyEditField('managerId', $event)"
                          [disabled]="companyEditSaving()"
                        >
                          @for (manager of company.managers; track manager.id) {
                            <option [ngValue]="manager.id">{{ manager.label }}</option>
                          }
                        </select>
                      </label>
                    }

                    <label class="sheet-field">
                      <span>Комментарий</span>
                      <textarea
                        name="editCompanyComments"
                        rows="3"
                        [ngModel]="draft.commentsCompany"
                        (ngModelChange)="setCompanyEditField('commentsCompany', $event)"
                        [disabled]="companyEditSaving()"
                      ></textarea>
                    </label>

                    <section class="company-edit-mobile-section">
                      <strong>Специалисты</strong>
                      @for (worker of company.currentWorkers; track worker.id) {
                        <div class="company-edit-mobile-row">
                          <span>{{ worker.label }}</span>
                          <button type="button" (click)="deleteCompanyWorker(worker)" [disabled]="!!companyEditDeleteKey() || companyEditSaving()">
                            {{ companyEditDeleteKey() === 'worker-' + worker.id ? '...' : 'Удалить' }}
                          </button>
                        </div>
                      } @empty {
                        <small>Специалисты не назначены</small>
                      }
                    </section>

                    <label class="sheet-field">
                      <span>Добавить специалиста</span>
                      <select
                        name="editCompanyNewWorker"
                        [ngModel]="draft.newWorkerId"
                        (ngModelChange)="setCompanyEditField('newWorkerId', $event)"
                        [disabled]="companyEditSaving()"
                      >
                        <option [ngValue]="null">Не добавлять</option>
                        @for (worker of company.workers; track worker.id) {
                          <option [ngValue]="worker.id">{{ worker.label }}</option>
                        }
                      </select>
                    </label>

                    <section class="company-edit-mobile-section">
                      <strong>Филиалы</strong>
                      @for (filial of company.filials; track filial.id) {
                        <div class="company-edit-mobile-row company-edit-mobile-row--stack">
                          <span>{{ filial.city || 'город' }}: {{ filial.title || 'филиал' }}</span>
                          <div>
                            <button type="button" (click)="startFilialEdit(filial)" [disabled]="!!companyEditDeleteKey() || companyEditSaving()">
                              Редактировать
                            </button>
                            <button type="button" (click)="deleteCompanyFilial(filial)" [disabled]="!!companyEditDeleteKey() || companyEditSaving()">
                              {{ companyEditDeleteKey() === 'filial-' + filial.id ? '...' : 'Удалить' }}
                            </button>
                          </div>
                        </div>
                      } @empty {
                        <small>Филиалы не добавлены</small>
                      }
                    </section>

                    @if (companyFilialDraft(); as filialDraft) {
                      <section class="company-edit-mobile-section">
                        <strong>Редактирование филиала</strong>
                        <label class="sheet-field">
                          <span>Город</span>
                          <select
                            [ngModel]="filialDraft.cityId"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="setFilialDraftField('cityId', $event)"
                          >
                            <option [ngValue]="null">Выберите город</option>
                            @for (city of company.cities; track city.id) {
                              <option [ngValue]="city.id">{{ city.label }}</option>
                            }
                          </select>
                        </label>
                        <label class="sheet-field">
                          <span>Адрес</span>
                          <input
                            type="text"
                            [ngModel]="filialDraft.title"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="setFilialDraftField('title', $event)"
                          >
                        </label>
                        <label class="sheet-field">
                          <span>Ссылка 2ГИС</span>
                          <input
                            type="text"
                            [ngModel]="filialDraft.url"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="setFilialDraftField('url', $event)"
                          >
                        </label>
                        <div class="sheet-actions">
                          <button class="secondary" type="button" (click)="cancelFilialEdit()" [disabled]="!!companyEditDeleteKey()">Отмена</button>
                          <button type="button" (click)="saveFilialEdit()" [disabled]="!canSaveFilialEdit()">Сохранить филиал</button>
                        </div>
                      </section>
                    }

                    <label class="sheet-field">
                      <span>Город нового филиала</span>
                      <select
                        name="editCompanyNewFilialCity"
                        [ngModel]="draft.newFilialCityId"
                        (ngModelChange)="setCompanyEditField('newFilialCityId', $event)"
                        [disabled]="companyEditSaving()"
                      >
                        <option [ngValue]="null">Не добавлять</option>
                        @for (city of company.cities; track city.id) {
                          <option [ngValue]="city.id">{{ city.label }}</option>
                        }
                      </select>
                    </label>

                    <label class="sheet-field">
                      <span>Адрес нового филиала</span>
                      <input
                        name="editCompanyNewFilialTitle"
                        type="text"
                        [ngModel]="draft.newFilialTitle"
                        (ngModelChange)="setCompanyEditField('newFilialTitle', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Ссылка 2ГИС нового филиала</span>
                      <input
                        name="editCompanyNewFilialUrl"
                        type="text"
                        [ngModel]="draft.newFilialUrl"
                        (ngModelChange)="setCompanyEditField('newFilialUrl', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>

                    <label class="sheet-field sheet-field--inline">
                      <span>Активна</span>
                      <input
                        name="editCompanyActive"
                        type="checkbox"
                        [ngModel]="draft.active"
                        (ngModelChange)="setCompanyEditField('active', $event)"
                        [disabled]="companyEditSaving()"
                      >
                    </label>
                  }
                }
              </section>

              <div class="sheet-actions">
                <button class="secondary" type="button" (click)="closeCompanyEdit()" [disabled]="companyEditSaving() || !!companyEditDeleteKey()">Отмена</button>
                <button type="submit" [disabled]="companyEditLoading() || companyEditSaving() || !!companyEditDeleteKey() || !canSaveCompanyEdit()">
                  {{ companyEditSaving() ? 'Сохраняю' : 'Сохранить' }}
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
                  <p class="sheet-note">Без лида</p>
                  <h2>Новая компания</h2>
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
                    <input
                      name="companyTitle"
                      type="text"
                      required
                      [ngModel]="draft.title"
                      (ngModelChange)="setCompanyField('title', $event)"
                      [disabled]="companySaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Телефон</span>
                    <input
                      name="companyTelephone"
                      type="tel"
                      required
                      autocomplete="tel"
                      [ngModel]="draft.telephone"
                      (ngModelChange)="setCompanyField('telephone', $event)"
                      [disabled]="companySaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Город компании</span>
                    <input
                      name="companyCity"
                      type="text"
                      required
                      [ngModel]="draft.city"
                      (ngModelChange)="setCompanyField('city', $event)"
                      [disabled]="companySaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Ссылка на чат</span>
                    <input
                      name="companyUrlChat"
                      type="text"
                      required
                      [ngModel]="draft.urlChat"
                      (ngModelChange)="setCompanyField('urlChat', $event)"
                      [disabled]="companySaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Официальный сайт</span>
                    <input
                      name="companyUrlSite"
                      type="url"
                      [ngModel]="draft.urlSite"
                      (ngModelChange)="setCompanyField('urlSite', $event)"
                      [disabled]="companySaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Email</span>
                    <input
                      name="companyEmail"
                      type="email"
                      [ngModel]="draft.email"
                      (ngModelChange)="setCompanyField('email', $event)"
                      [disabled]="companySaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Категория</span>
                    <select
                      name="companyCategory"
                      required
                      [ngModel]="draft.categoryId"
                      (ngModelChange)="changeCompanyCategory($event)"
                      [disabled]="companySaving()"
                    >
                      <option [ngValue]="null">Не выбрана</option>
                      @for (category of companyCategoryOptions(); track category.id) {
                        <option [ngValue]="category.id">{{ category.label }}</option>
                      }
                    </select>
                  </label>

                  <label class="sheet-field">
                    <span>Подкатегория</span>
                    <select
                      name="companySubCategory"
                      required
                      [ngModel]="draft.subCategoryId"
                      (ngModelChange)="setCompanyField('subCategoryId', $event)"
                      [disabled]="companySaving() || !draft.categoryId"
                    >
                      <option [ngValue]="null">Не выбрана</option>
                      @for (subCategory of companySubCategoryOptions(); track subCategory.id) {
                        <option [ngValue]="subCategory.id">{{ subCategory.label }}</option>
                      }
                    </select>
                  </label>

                  <label class="sheet-field">
                    <span>Специалист</span>
                    <select
                      name="companyWorker"
                      required
                      [ngModel]="draft.workerId"
                      (ngModelChange)="setCompanyField('workerId', $event)"
                      [disabled]="companySaving()"
                    >
                      <option [ngValue]="null">Не выбран</option>
                      @for (worker of companyWorkerOptions(); track worker.id) {
                        <option [ngValue]="worker.id">{{ worker.label }}</option>
                      }
                    </select>
                  </label>

                  <label class="sheet-field">
                    <span>Город филиала</span>
                    <select
                      name="companyFilialCity"
                      required
                      [ngModel]="draft.filialCityId"
                      (ngModelChange)="setCompanyField('filialCityId', $event)"
                      [disabled]="companySaving()"
                    >
                      <option [ngValue]="null">Не выбран</option>
                      @for (city of companyCityOptions(); track city.id) {
                        <option [ngValue]="city.id">{{ city.label }}</option>
                      }
                    </select>
                  </label>

                  <label class="sheet-field">
                    <span>Название филиала</span>
                    <input
                      name="companyFilialTitle"
                      type="text"
                      required
                      [ngModel]="draft.filialTitle"
                      (ngModelChange)="setCompanyField('filialTitle', $event)"
                      [disabled]="companySaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Ссылка филиала</span>
                    <input
                      name="companyFilialUrl"
                      type="text"
                      required
                      [ngModel]="draft.filialUrl"
                      (ngModelChange)="setCompanyField('filialUrl', $event)"
                      [disabled]="companySaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Комментарий</span>
                    <textarea
                      name="companyComments"
                      rows="3"
                      [ngModel]="draft.commentsCompany"
                      (ngModelChange)="setCompanyField('commentsCompany', $event)"
                      [disabled]="companySaving()"
                    ></textarea>
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

        <ion-modal class="sheet-modal company-create-sheet order-create-sheet" [isOpen]="orderCreateOpen()" (didDismiss)="closeCompanyOrderCreate()">
          <ng-template>
            <form class="sheet-body sheet-form order-create-form" (ngSubmit)="saveCompanyOrder()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Новый заказ</p>
                  <h2>{{ orderCreatePayload()?.companyTitle || 'Компания' }}</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeCompanyOrderCreate()" [disabled]="orderCreateSaving()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content order-create-content">
                @if (orderCreateLoading() && !orderCreatePayload()) {
                  <p class="sheet-hint">Загружаю данные заказа...</p>
                }

                @if (orderCreateError()) {
                  <p class="sheet-error">{{ orderCreateError() }}</p>
                }

                @if (orderCreatePayload(); as payload) {
                  @if (orderCreateDraft(); as draft) {
                    <section class="order-create-products-panel">
                      <div class="order-create-section-title">
                        <span>Продукт</span>
                        @if (selectedOrderCreateProduct(); as product) {
                          <strong>{{ product.label }}</strong>
                        }
                      </div>

                      <div class="order-create-products-grid">
                        @for (product of payload.products; track product.id) {
                          <button
                            type="button"
                            class="order-create-product"
                            [class.active]="draft.productId === product.id"
                            (click)="setOrderCreateField('productId', product.id)"
                            [disabled]="orderCreateSaving()"
                          >
                            <span class="material-icons-sharp">{{ product.photo ? 'image' : 'inventory_2' }}</span>
                            <strong>{{ product.label }}</strong>
                            <small>{{ orderMoney(product.price || 0) }}</small>
                          </button>
                        } @empty {
                          <p class="sheet-hint">Продуктов нет.</p>
                        }
                      </div>
                    </section>

                    <div class="order-create-grid">
                      <label class="sheet-field">
                        <span>Количество в месяц</span>
                        <select
                          name="createOrderAmount"
                          required
                          [ngModel]="draft.amount"
                          (ngModelChange)="setOrderCreateField('amount', numericValue($event))"
                          [disabled]="orderCreateSaving()"
                        >
                          @for (amount of payload.amounts; track amount) {
                            <option [ngValue]="amount">{{ amount }}</option>
                          }
                        </select>
                      </label>

                      <label class="sheet-field">
                        <span>Специалист</span>
                        <select
                          name="createOrderWorker"
                          required
                          [ngModel]="draft.workerId"
                          (ngModelChange)="setOrderCreateField('workerId', $event)"
                          [disabled]="orderCreateSaving()"
                        >
                          <option [ngValue]="null">Не выбран</option>
                          @for (worker of payload.workers; track worker.id) {
                            <option [ngValue]="worker.id">{{ optionLabel(worker) }}</option>
                          }
                        </select>
                      </label>

                      <label class="sheet-field order-create-wide">
                        <span>Филиал</span>
                        <select
                          name="createOrderFilial"
                          required
                          [ngModel]="draft.filialId"
                          (ngModelChange)="setOrderCreateField('filialId', $event)"
                          [disabled]="orderCreateSaving()"
                        >
                          <option [ngValue]="null">Не выбран</option>
                          @for (filial of payload.filials; track filial.id) {
                            <option [ngValue]="filial.id">{{ filial.city || 'город' }}: {{ filial.title || 'филиал' }}</option>
                          }
                        </select>
                      </label>
                    </div>

                    <aside class="order-create-summary">
                      <span>Сумма заказа</span>
                      <strong>{{ orderMoney(orderCreateTotal()) }}</strong>
                      @if (selectedOrderCreateProduct(); as product) {
                        <small>{{ draft.amount }} x {{ orderMoney(product.price || 0) }}</small>
                      }
                    </aside>
                  }
                }
              </section>

              <div class="sheet-actions">
                <button class="secondary" type="button" (click)="closeCompanyOrderCreate()" [disabled]="orderCreateSaving()">Отмена</button>
                <button type="submit" [disabled]="orderCreateLoading() || orderCreateSaving() || !canSaveOrderCreate()">
                  {{ orderCreateSaving() ? 'Создаю' : 'Создать заказ' }}
                </button>
              </div>
            </form>
          </ng-template>
        </ion-modal>

        <ion-modal class="sheet-modal company-create-sheet order-edit-sheet" [isOpen]="orderEditOpen()" (didDismiss)="closeOrderEdit()">
          <ng-template>
            <form class="sheet-body sheet-form" (ngSubmit)="saveOrderEdit()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Заказ #{{ orderEdit()?.id || '' }}</p>
                  <h2>Редактор заказа</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeOrderEdit()" [disabled]="orderEditSaving() || orderEditDeleting()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content">
                @if (orderEditLoading() && !orderEdit()) {
                  <p class="sheet-hint">Загружаю заказ...</p>
                }

                @if (orderEditError()) {
                  <p class="sheet-error">{{ orderEditError() }}</p>
                }

                @if (orderEdit(); as order) {
                  @if (orderEditDraft(); as draft) {
                    <label class="sheet-field">
                      <span>Компания</span>
                      <input name="orderEditCompany" type="text" [value]="order.companyTitle" readonly>
                    </label>

                    <label class="sheet-field">
                      <span>Статус</span>
                      <input name="orderEditStatus" type="text" [value]="order.status" readonly>
                    </label>

                    <label class="sheet-field">
                      <span>Филиал</span>
                      <select
                        name="orderEditFilial"
                        [ngModel]="draft.filialId"
                        (ngModelChange)="setOrderEditField('filialId', $event)"
                        [disabled]="orderEditSaving() || orderEditDeleting()"
                      >
                        <option [ngValue]="null">Не выбран</option>
                        @for (filial of order.filials; track filial.id) {
                          <option [ngValue]="filial.id">{{ optionLabel(filial) }}</option>
                        }
                      </select>
                    </label>

                    <label class="sheet-field">
                      <span>Специалист</span>
                      <select
                        name="orderEditWorker"
                        [ngModel]="draft.workerId"
                        (ngModelChange)="setOrderEditField('workerId', $event)"
                        [disabled]="orderEditSaving() || orderEditDeleting()"
                      >
                        <option [ngValue]="null">Не выбран</option>
                        @for (worker of order.workers; track worker.id) {
                          <option [ngValue]="worker.id">{{ optionLabel(worker) }}</option>
                        }
                      </select>
                    </label>

                    <label class="sheet-field">
                      <span>Менеджер</span>
                      <select
                        name="orderEditManager"
                        [ngModel]="draft.managerId"
                        (ngModelChange)="setOrderEditField('managerId', $event)"
                        [disabled]="orderEditSaving() || orderEditDeleting()"
                      >
                        <option [ngValue]="null">Не выбран</option>
                        @for (manager of order.managers; track manager.id) {
                          <option [ngValue]="manager.id">{{ optionLabel(manager) }}</option>
                        }
                      </select>
                    </label>

                    <label class="sheet-field">
                      <span>Опубликовано</span>
                      <input
                        name="orderEditCounter"
                        type="number"
                        min="0"
                        [ngModel]="draft.counter"
                        (ngModelChange)="setOrderEditField('counter', numericValue($event))"
                        [disabled]="orderEditSaving() || orderEditDeleting()"
                      >
                    </label>

                    <label class="sheet-field">
                      <span>Кол-во отзывов</span>
                      <input name="orderEditAmount" type="text" [value]="order.amount ?? 0" readonly>
                    </label>

                    <label class="sheet-field">
                      <span>Сумма</span>
                      <input name="orderEditSum" type="text" [value]="money(order.sum)" readonly>
                    </label>

                    <label class="sheet-field">
                      <span>Комментарий заказа</span>
                      <textarea
                        name="orderEditComments"
                        rows="3"
                        [ngModel]="draft.orderComments"
                        (ngModelChange)="setOrderEditField('orderComments', $event)"
                        [disabled]="orderEditSaving() || orderEditDeleting()"
                      ></textarea>
                    </label>

                    <label class="sheet-field">
                      <span>Комментарий компании</span>
                      <textarea
                        name="orderEditCompanyComments"
                        rows="3"
                        [ngModel]="draft.commentsCompany"
                        (ngModelChange)="setOrderEditField('commentsCompany', $event)"
                        [disabled]="orderEditSaving() || orderEditDeleting()"
                      ></textarea>
                    </label>

                    @if (order.canComplete) {
                      <label class="sheet-field sheet-field--inline">
                        <span>Выполнен</span>
                        <input
                          name="orderEditComplete"
                          type="checkbox"
                          [ngModel]="draft.complete"
                          (ngModelChange)="setOrderEditField('complete', $event)"
                          [disabled]="orderEditSaving() || orderEditDeleting()"
                        >
                      </label>
                    }

                    <section class="order-edit-meta">
                      <span>Создан: {{ dateText(order.created) }}</span>
                      <span>Изменен: {{ dateText(order.changed) }}</span>
                      <span>Оплата: {{ order.payDay || '-' }}</span>
                    </section>
                  }
                }
              </section>

              <div class="sheet-actions" [class.edit-actions]="!!orderEdit()?.canDelete">
                @if (orderEdit()?.canDelete) {
                  <button class="danger" type="button" (click)="deleteOrderEdit()" [disabled]="orderEditLoading() || orderEditSaving() || orderEditDeleting()">
                    {{ orderEditDeleting() ? 'Удаляю' : 'Удалить' }}
                  </button>
                }
                <button class="secondary" type="button" (click)="closeOrderEdit()" [disabled]="orderEditSaving() || orderEditDeleting()">Отмена</button>
                <button type="submit" [disabled]="orderEditLoading() || orderEditSaving() || orderEditDeleting() || !canSaveOrderEdit()">
                  {{ orderEditSaving() ? 'Сохраняю' : 'Сохранить' }}
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

    .manager-page {
      display: flex;
      height: 100%;
      max-width: 44rem;
      min-height: 0;
      margin: 0 auto;
      overflow: hidden;
      flex-direction: column;
      gap: var(--otziv-page-gap, 0.46rem);
      padding: var(--otziv-page-padding-y, 0.55rem) var(--otziv-page-padding-x, 0.62rem) calc(var(--otziv-page-padding-bottom, 0.45rem) + env(safe-area-inset-bottom));
    }

    .manager-search-strip {
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

    .manager-search-strip label {
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

    .manager-search-strip input {
      min-width: 0;
      border: 0;
      outline: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: 900 0.82rem/1 var(--otziv-font-family);
    }

    .manager-search-strip input::placeholder {
      color: var(--otziv-info);
      opacity: 1;
    }

    .manager-search-strip .material-icons-sharp {
      font-size: 1.12rem;
    }

    .manager-search-strip .icon-button {
      width: 2.42rem;
      height: 2.42rem;
      min-width: 2.42rem;
      border: 0;
      border-radius: 0.82rem;
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
    }

    .manager-search-strip button:disabled {
      opacity: 0.52;
    }

    .manager-list {
      flex: 1 1 0;
      min-height: 0;
      align-items: stretch;
      gap: var(--otziv-list-gap, 0.56rem);
      margin-inline: calc(var(--otziv-page-padding-x, 0.62rem) * -1);
      overflow-y: hidden;
      padding: 0 var(--otziv-page-padding-x, 0.62rem) 0.12rem;
    }

    .manager-list .manager-card {
      scroll-snap-align: center;
    }

    .manager-list--expanded {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      align-content: start;
      align-items: start;
      gap: 0.58rem;
      margin-inline: 0;
      overflow-x: hidden;
      overflow-y: auto;
      padding: 0 0.05rem 0.25rem;
      scroll-snap-type: none;
    }

    .manager-list--expanded .manager-card {
      flex: none;
      width: 100%;
      min-width: 0;
      max-width: none;
      min-height: 0;
      height: auto;
      scroll-snap-align: none;
    }

    .manager-list.manager-list--expanded.manager-list--companies .company-mobile-card {
      min-height: 25.5rem;
    }

    .manager-list--expanded .company-mobile-card {
      gap: 0.42rem;
      justify-content: space-between;
      overflow: hidden;
    }

    .manager-list--expanded .company-note-editor {
      flex: 0 0 auto;
      height: 3.6rem;
      min-height: 3.6rem;
      max-height: none;
    }

    .manager-list--expanded .lead-card-comment {
      min-height: 100%;
      overflow: auto;
    }

    .manager-list--expanded .company-card-actions {
      gap: 0.42rem 0.5rem;
    }

    .manager-list--expanded .lead-card-foot {
      margin-top: 0.1rem;
    }

    .manager-card {
      min-height: 15rem;
    }

    .company-mobile-card {
      gap: clamp(0.46rem, 0.9vh, 0.72rem);
      justify-content: space-between;
    }

    .company-mobile-card .lead-card-head a {
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 0.98rem;
      font-weight: 1000;
      line-height: 1.08;
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .company-note-editor {
      flex: 0 0 clamp(4.6rem, 16vh, 5.65rem);
      min-height: 4.6rem;
      max-height: 5.65rem;
    }

    .company-note-editor .lead-card-comment {
      min-height: 100%;
      font-size: 0.76rem;
    }

    .company-note-indicator {
      display: grid;
      width: 1.24rem;
      height: 1.24rem;
      place-items: center;
      border: 0;
      border-radius: 999px;
      padding: 0;
      color: var(--otziv-primary);
      background: rgba(108, 155, 207, 0.12);
    }

    span.company-note-indicator {
      flex: 0 0 auto;
    }

    .company-note-indicator--order {
      color: #8a6a11;
      background: rgba(247, 208, 96, 0.24);
    }

    .company-note-indicator .material-icons-sharp {
      font-size: 0.9rem;
    }

    .company-details-button {
      display: grid;
      flex: 0 0 auto;
      width: 100%;
      min-height: 1.92rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 999px;
      color: var(--otziv-info);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font-size: 0.64rem;
      font-weight: 1000;
      text-transform: uppercase;
    }

    .company-filial-count {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 0.55rem;
      flex: 0 0 auto;
    }

    .company-filial-count span,
    .company-filial-count strong {
      display: grid;
      min-height: 2.05rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font-size: 0.72rem;
      font-weight: 900;
    }

    .company-mobile-card .lead-meta-row {
      gap: 0.55rem;
    }

    .company-mobile-card .lead-meta-row span,
    .company-mobile-card .lead-meta-row button {
      min-height: 1.92rem;
      padding: 0 0.56rem;
      font-size: 0.75rem;
      font-weight: 900;
      line-height: 1;
    }

    .company-mobile-card .lead-meta-row button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      overflow: hidden;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      text-decoration: none;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .company-mobile-card .lead-card-foot button {
      min-width: 0;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      font-size: 0.75rem;
      font-weight: 900;
      text-decoration: none;
    }

    .company-mobile-card .lead-card-foot button {
      border: 0;
      padding: 0;
      color: var(--otziv-info);
      background: transparent;
      font-size: 0.64rem;
    }

    .company-next-order {
      display: inline-flex;
      align-items: center;
      gap: 0.28rem;
      min-height: 1.8rem;
      border: 1px solid rgba(214, 159, 43, 0.26);
      border-radius: 999px;
      margin: 0;
      padding: 0 0.58rem;
      color: #8a6a11;
      background: var(--otziv-tone-wait-surface);
      font-size: 0.66rem;
      font-weight: 900;
      line-height: 1;
      white-space: nowrap;
    }

    .company-next-order--failed {
      border-color: rgba(255, 0, 96, 0.24);
      color: var(--otziv-danger);
      background: var(--otziv-tone-correction-surface);
    }

    .company-next-order .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 1rem;
    }

    .company-card-actions {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem 0.56rem;
      flex: 0 0 auto;
    }

    .company-card-actions button {
      min-height: 1.92rem;
      border-radius: 999px;
      font-size: 0.68rem;
      font-weight: 900;
    }

    .order-create-form {
      gap: 0.8rem;
    }

    .order-create-content {
      align-content: start;
      gap: 0.74rem;
    }

    .order-create-products-panel,
    .order-create-summary {
      display: grid;
      gap: 0.62rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      padding: 0.72rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.8rem 1.7rem rgba(132, 139, 200, 0.09);
    }

    .order-create-section-title {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.55rem;
      min-width: 0;
    }

    .order-create-section-title span,
    .order-create-summary span {
      color: var(--otziv-info);
      font-size: 0.72rem;
      font-weight: 1000;
      text-transform: uppercase;
    }

    .order-create-section-title strong,
    .order-create-summary strong {
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 0.96rem;
      font-weight: 1000;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .order-create-products-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem;
    }

    .order-create-product {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.18rem 0.42rem;
      min-height: 3.2rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.92rem;
      padding: 0.48rem 0.58rem;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      text-align: left;
    }

    .order-create-product.active {
      border-color: rgba(108, 155, 207, 0.42);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.2) 0%, var(--otziv-white) 100%);
      box-shadow: inset 0 0 0 1px rgba(108, 155, 207, 0.12);
    }

    .order-create-product .material-icons-sharp {
      grid-row: 1 / span 2;
      display: grid;
      width: 1.85rem;
      height: 1.85rem;
      place-items: center;
      border-radius: 999px;
      color: var(--otziv-primary);
      background: rgba(108, 155, 207, 0.14);
      font-size: 1rem;
    }

    .order-create-product strong,
    .order-create-product small {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .order-create-product strong {
      font-size: 0.74rem;
      font-weight: 1000;
      line-height: 1.08;
    }

    .order-create-product small,
    .order-create-summary small {
      color: var(--otziv-info);
      font-size: 0.66rem;
      font-weight: 900;
      line-height: 1.1;
    }

    .order-create-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.62rem;
    }

    .order-create-wide {
      grid-column: 1 / -1;
    }

    .order-create-summary {
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
    }

    .order-create-summary small {
      grid-column: 1 / -1;
      text-align: right;
    }

    .order-worker-pill {
      min-height: 1.65rem;
      margin: 0;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 999px;
      padding: 0.38rem 0.62rem;
      overflow: hidden;
      color: var(--otziv-info);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-tone-work-surface) 100%);
      font-size: 0.7rem;
      font-weight: 900;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .company-edit-mobile-section {
      display: grid;
      gap: 0.5rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.95rem;
      padding: 0.72rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .company-edit-mobile-section > strong {
      color: var(--otziv-info);
      font-size: 0.72rem;
      font-weight: 1000;
      text-transform: uppercase;
    }

    .company-edit-mobile-section small {
      color: var(--otziv-info);
      font-size: 0.72rem;
      font-weight: 800;
    }

    .company-edit-mobile-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.5rem;
      min-width: 0;
    }

    .company-edit-mobile-row--stack {
      grid-template-columns: minmax(0, 1fr);
    }

    .company-edit-mobile-row span {
      min-width: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 0.78rem;
      font-weight: 900;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .company-edit-mobile-row div {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.4rem;
    }

    .company-edit-mobile-row button {
      min-height: 1.9rem;
      border: 1px solid rgba(108, 155, 207, 0.22);
      border-radius: 999px;
      color: var(--otziv-primary);
      background: var(--otziv-white);
      font: inherit;
      font-size: 0.68rem;
      font-weight: 900;
    }

    .manager-bottom-controls {
      flex: 0 0 auto;
    }

    .manager-detail-backdrop {
      position: fixed;
      z-index: 82;
      inset: 0;
      border: 0;
      background: rgba(24, 26, 30, 0.34);
    }

    .manager-detail-sheet {
      position: fixed;
      z-index: 83;
      inset: auto 0 0;
      display: grid;
      max-height: min(84vh, 40rem);
      gap: 0.75rem;
      border-radius: 1.2rem 1.2rem 0 0;
      padding: 0.9rem 0.9rem calc(0.9rem + env(safe-area-inset-bottom));
      color: var(--otziv-dark);
      background: var(--otziv-background);
      box-shadow: 0 -1rem 2.7rem rgba(54, 57, 73, 0.22);
      overflow: auto;
    }

    .manager-detail-sheet header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 0.75rem;
      min-width: 0;
    }

    .manager-detail-sheet small,
    .manager-detail-grid small {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
      text-transform: uppercase;
    }

    .manager-detail-sheet h2 {
      margin: 0.12rem 0 0;
      font-size: 1.28rem;
      line-height: 1.08;
      overflow-wrap: anywhere;
    }

    .manager-detail-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.55rem;
    }

    .manager-detail-grid div {
      display: grid;
      gap: 0.18rem;
      min-width: 0;
      border-radius: 0.9rem;
      padding: 0.72rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
    }

    .manager-detail-grid .wide {
      grid-column: 1 / -1;
    }

    .manager-detail-grid b,
    .manager-detail-grid p {
      margin: 0;
      color: var(--otziv-dark);
      font-size: 0.84rem;
      font-weight: 900;
      line-height: 1.25;
      overflow-wrap: anywhere;
    }

    .detail-next-order {
      width: 100%;
      justify-content: center;
      white-space: normal;
      line-height: 1.2;
      padding-block: 0.45rem;
    }
  `]
})
export class ManagerPage implements OnInit, OnDestroy {
  private initialized = false;
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly companyNoteTimers = new Map<number, ReturnType<typeof setTimeout>>();
  private readonly companyNoteVersions = new Map<number, number>();
  private readonly orderNoteTimers = new Map<number, ReturnType<typeof setTimeout>>();
  private readonly orderNoteVersions = new Map<number, number>();
  private routeSubscription?: Subscription;
  private lastMobileNavKey = '';

  readonly board = signal<ManagerBoard | null>(null);
  readonly activeSection = signal<ManagerBoardSection>('companies');
  readonly companyStatus = signal(ALL_STATUS);
  readonly orderStatus = signal(ALL_STATUS);
  readonly keyword = signal('');
  readonly appliedKeyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);
  readonly statusSheetOpen = signal(false);
  readonly listExpanded = signal(false);
  readonly mutationKey = signal<string | null>(null);
  readonly copiedKey = signal<string | null>(null);
  readonly companyNoteDrafts = signal<Record<number, string>>({});
  readonly companyNoteSaveStates = signal<Record<number, CompanyNoteSaveState>>({});
  readonly orderNoteDrafts = signal<Record<number, string>>({});
  readonly orderNoteSaveStates = signal<Record<number, OrderNoteSaveState>>({});
  readonly selectedCompany = signal<SelectedCompany | null>(null);
  readonly companyDetails = signal<CompanyItem | null>(null);
  readonly companyCreateOpen = signal(false);
  readonly companyPayload = signal<CompanyCreatePayload | null>(null);
  readonly companyDraft = signal<CompanyCreateDraft | null>(null);
  readonly companySubCategories = signal<CompanyCreateOption[]>([]);
  readonly companyLoading = signal(false);
  readonly companySaving = signal(false);
  readonly companyError = signal<string | null>(null);
  readonly companyEditOpen = signal(false);
  readonly companyEdit = signal<CompanyEditPayload | null>(null);
  readonly companyEditDraft = signal<CompanyUpdateRequest | null>(null);
  readonly companyEditLoading = signal(false);
  readonly companyEditSaving = signal(false);
  readonly companyEditError = signal<string | null>(null);
  readonly companyEditDeleteKey = signal<string | null>(null);
  readonly companyFilialDraft = signal<CompanyFilialEditDraft | null>(null);
  readonly orderEditOpen = signal(false);
  readonly orderEdit = signal<OrderEditPayload | null>(null);
  readonly orderEditDraft = signal<OrderUpdateRequest | null>(null);
  readonly orderEditLoading = signal(false);
  readonly orderEditSaving = signal(false);
  readonly orderEditDeleting = signal(false);
  readonly orderEditError = signal<string | null>(null);
  readonly orderCreateOpen = signal(false);
  readonly orderCreatePayload = signal<CompanyOrderCreatePayload | null>(null);
  readonly orderCreateDraft = signal<CompanyOrderCreateRequest | null>(null);
  readonly orderCreateLoading = signal(false);
  readonly orderCreateSaving = signal(false);
  readonly orderCreateError = signal<string | null>(null);
  readonly companyActions = COMPANY_ACTIONS;
  readonly orderActions = ORDER_ACTIONS;

  readonly currentStatus = computed(() =>
    this.activeSection() === 'orders' ? this.orderStatus() : this.companyStatus()
  );
  readonly currentStatuses = computed(() => {
    const section = this.activeSection();
    const statuses = section === 'companies'
      ? this.board()?.companyStatuses
      : this.board()?.orderStatuses;

    return ensureAllStatus(statuses ?? (section === 'companies' ? DEFAULT_COMPANY_STATUSES : DEFAULT_ORDER_STATUSES));
  });
  readonly statusItems = computed<MobileStatusItem[]>(() =>
    this.currentStatuses().map((status) => {
      const normalized = this.normalizedStatus(status);
      const metric = this.statusMetric(status);
      return {
        key: normalized,
        title: this.statusLabel(status),
        value: this.metricValue(status),
        icon: metric?.icon || 'dashboard',
        tone: metric?.tone || 'blue'
      };
    })
  );
  readonly companyManagerOptions = computed(() => this.companyPayload()?.managers ?? []);
  readonly companyWorkerOptions = computed(() => this.companyPayload()?.workers ?? []);
  readonly companyCategoryOptions = computed(() => this.companyPayload()?.categories ?? []);
  readonly companySubCategoryOptions = computed(() => this.companySubCategories());
  readonly companyCityOptions = computed(() => this.companyPayload()?.cities ?? []);
  readonly selectedOrderCreateProduct = computed(() => {
    const payload = this.orderCreatePayload();
    const draft = this.orderCreateDraft();
    return payload?.products.find((product) => product.id === draft?.productId) ?? null;
  });
  readonly orderCreateTotal = computed(() => (this.selectedOrderCreateProduct()?.price ?? 0) * (this.orderCreateDraft()?.amount ?? 0));

  constructor(
    private readonly api: ApiService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly confirm: MobileConfirmService
  ) {}

  ngOnInit(): void {
    this.applyRouteSection();
    this.applyMobileNavIntent(false);
    this.lastMobileNavKey = this.mobileNavKey();
    this.initialized = true;
    void this.load();
    this.routeSubscription = this.route.queryParamMap.subscribe((params) => {
      if (!this.initialized) {
        return;
      }

      const nextKey = this.mobileNavKey(params);
      if (nextKey === this.lastMobileNavKey) {
        return;
      }

      this.lastMobileNavKey = nextKey;
      const changed = this.applyRouteSection();
      const loaded = this.applyMobileNavIntent(true, params);
      if (changed && !loaded) {
        this.pageNumber.set(0);
        void this.load();
      }
    });
  }

  ionViewWillEnter(): void {
    if (!this.initialized) {
      return;
    }

    const changed = this.applyRouteSection();
    if (changed) {
      this.pageNumber.set(0);
      void this.load();
    }
    this.applyMobileNavIntent(false);
  }

  ngOnDestroy(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.routeSubscription?.unsubscribe();
    this.companyNoteTimers.forEach((timer) => clearTimeout(timer));
    this.companyNoteTimers.clear();
    this.orderNoteTimers.forEach((timer) => clearTimeout(timer));
    this.orderNoteTimers.clear();
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.load();
    event.target.complete();
  }

  reload(): void {
    void this.load();
  }

  setKeyword(value: string): void {
    this.keyword.set(value);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }

    this.searchTimer = setTimeout(() => {
      this.applySearch();
    }, 450);
  }

  applySearch(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }

    if (this.appliedKeyword() === this.keyword().trim()) {
      return;
    }

    this.appliedKeyword.set(this.keyword().trim());
    this.pageNumber.set(0);
    this.listExpanded.set(false);
    void this.load();
  }

  openArchive(): void {
    void this.router.navigate(['/tabs/archive']);
  }

  openAllStatus(load = true): void {
    if (this.activeSection() === 'orders') {
      this.orderStatus.set(ALL_STATUS);
    } else {
      this.companyStatus.set(ALL_STATUS);
      this.selectedCompany.set(null);
      this.companyDetails.set(null);
    }

    this.pageNumber.set(0);
    this.listExpanded.set(false);
    this.statusSheetOpen.set(false);
    if (load) {
      void this.load();
    }
  }

  clearSearch(): void {
    this.keyword.set('');
    this.appliedKeyword.set('');
    this.pageNumber.set(0);
    this.listExpanded.set(false);
    void this.load();
  }

  searchPlaceholder(): string {
    return this.activeSection() === 'companies'
      ? 'Компания, телефон, город'
      : 'Заказ, компания, филиал';
  }

  openStatusSheet(): void {
    this.statusSheetOpen.set(true);
  }

  closeStatusSheet(): void {
    this.statusSheetOpen.set(false);
  }

  selectStatus(status: string): void {
    const normalized = this.normalizedStatus(status);
    if (this.currentStatus() === normalized) {
      this.closeStatusSheet();
      return;
    }

    if (this.activeSection() === 'orders') {
      this.orderStatus.set(normalized);
    } else {
      this.companyStatus.set(normalized);
      this.selectedCompany.set(null);
    }

    this.pageNumber.set(0);
    this.listExpanded.set(false);
    this.closeStatusSheet();
    void this.load();
  }

  previousPage(): void {
    if (this.isFirstPage() || this.loading()) {
      return;
    }
    this.pageNumber.set(Math.max(this.pageNumber() - 1, 0));
    void this.load();
  }

  nextPage(): void {
    if (this.isLastPage() || this.loading()) {
      return;
    }
    this.pageNumber.set(this.pageNumber() + 1);
    void this.load();
  }

  toggleSortDirection(): void {
    this.sortDirection.set(this.sortDirection() === 'desc' ? 'asc' : 'desc');
    this.pageNumber.set(0);
    void this.load().finally(() => this.resetListScroll());
  }

  toggleListExpanded(): void {
    this.toggleSortDirection();
  }

  private resetListScroll(): void {
    window.setTimeout(() => {
      document.querySelector<HTMLElement>('app-manager .lead-list')?.scrollTo({ left: 0, top: 0, behavior: 'auto' });
    });
  }

  sectionLabel(): string {
    return this.activeSection() === 'companies' ? 'Компании' : 'Заказы';
  }

  heroMeta(): string {
    const company = this.selectedCompany();
    const companySuffix = this.activeSection() === 'orders' && company ? ` · ${company.title}` : '';
    return `${this.activeTotal()} записей · ${this.currentStatus().toLowerCase()}${companySuffix}`;
  }

  activeTotal(): number {
    return this.activePage().totalElements;
  }

  pageLabel(): string {
    return mobilePageLabel(this.activePage(), this.pageNumber());
  }

  sortTitle(): string {
    return mobileSortTitle(this.sortDirection());
  }

  currentPageIndex(): number {
    return mobilePageIndex(this.activePage(), this.pageNumber());
  }

  currentPageTotal(): number {
    return mobilePageTotal(this.activePage());
  }

  isFirstPage(): boolean {
    return mobilePageIsFirst(this.activePage(), this.pageNumber());
  }

  isLastPage(): boolean {
    return mobilePageIsLast(this.activePage(), this.pageNumber());
  }

  metricValue(status: string): number {
    const section = this.activeSection();
    const normalized = this.normalizedStatus(status);
    return this.board()?.metrics?.find((metric) =>
      metric.section === section && this.normalizedStatus(metric.status ?? '') === normalized
    )?.value ?? 0;
  }

  statusIcon(status: string): string {
    return this.statusMetric(status)?.icon || 'dashboard';
  }

  statusTone(status: string): string {
    return `tone-${this.statusMetric(status)?.tone || 'blue'}`;
  }

  statusLabel(status: string): string {
    return managerStatusLabel(status);
  }

  normalizedStatus(status: string): string {
    return normalizedManagerStatus(status);
  }

  openCompanyDetails(company: CompanyItem): void {
    this.companyDetails.set(company);
  }

  closeCompanyDetails(): void {
    this.companyDetails.set(null);
  }

  openCompanyEdit(company: CompanyItem): void {
    this.companyEditOpen.set(true);
    this.companyEdit.set(null);
    this.companyEditDraft.set(null);
    this.companyFilialDraft.set(null);
    this.companyEditError.set(null);
    void this.loadCompanyEdit(company.id);
  }

  closeCompanyEdit(): void {
    if (this.companyEditSaving() || this.companyEditDeleteKey()) {
      return;
    }

    this.companyEditOpen.set(false);
    this.companyEdit.set(null);
    this.companyEditDraft.set(null);
    this.companyFilialDraft.set(null);
    this.companyEditError.set(null);
    this.companyEditLoading.set(false);
  }

  openCompanyOrderCreate(company: CompanyItem): void {
    if (this.orderCreateSaving()) {
      return;
    }

    this.orderCreateOpen.set(true);
    this.orderCreatePayload.set(null);
    this.orderCreateDraft.set(null);
    this.orderCreateError.set(null);
    void this.loadCompanyOrderCreate(company.id);
  }

  closeCompanyOrderCreate(): void {
    if (this.orderCreateSaving()) {
      return;
    }

    this.orderCreateOpen.set(false);
    this.orderCreatePayload.set(null);
    this.orderCreateDraft.set(null);
    this.orderCreateError.set(null);
    this.orderCreateLoading.set(false);
  }

  setOrderCreateField<K extends keyof CompanyOrderCreateRequest>(field: K, value: CompanyOrderCreateRequest[K]): void {
    this.orderCreateDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  canSaveOrderCreate(): boolean {
    const draft = this.orderCreateDraft();
    return Boolean(draft?.productId && draft.amount > 0 && draft.workerId && draft.filialId);
  }

  async saveCompanyOrder(): Promise<void> {
    const payload = this.orderCreatePayload();
    const draft = this.orderCreateDraft();

    if (!payload || !draft || !this.canSaveOrderCreate()) {
      this.orderCreateError.set('Выберите продукт, количество, специалиста и филиал.');
      return;
    }

    this.orderCreateSaving.set(true);
    this.orderCreateError.set(null);

    try {
      const result = await firstValueFrom(this.api.createManagerCompanyOrder(payload.companyId, this.normalizedOrderCreateDraft(draft)));
      this.orderCreateSaving.set(false);
      this.closeCompanyOrderCreate();
      await this.openCreatedCompanyOrders(result);
    } catch (error) {
      this.orderCreateError.set(this.apiErrorMessage(error, 'Заказ не создан.'));
    } finally {
      this.orderCreateSaving.set(false);
    }
  }

  openOrderEdit(order: OrderItem): void {
    if (this.orderEditSaving() || this.orderEditDeleting()) {
      return;
    }

    this.orderEditOpen.set(true);
    this.orderEdit.set(null);
    this.orderEditDraft.set(null);
    this.orderEditError.set(null);
    void this.loadOrderEdit(order.id);
  }

  closeOrderEdit(): void {
    if (this.orderEditSaving() || this.orderEditDeleting()) {
      return;
    }

    this.orderEditOpen.set(false);
    this.orderEdit.set(null);
    this.orderEditDraft.set(null);
    this.orderEditError.set(null);
    this.orderEditLoading.set(false);
  }

  setOrderEditField<K extends keyof OrderUpdateRequest>(field: K, value: OrderUpdateRequest[K]): void {
    this.orderEditDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  numericValue(value: unknown): number {
    const next = Number(value);
    return Number.isFinite(next) ? next : 0;
  }

  optionLabel(option?: ManagerOption | null): string {
    return (option?.label ?? '').trim() || '-';
  }

  canSaveOrderEdit(): boolean {
    const draft = this.orderEditDraft();
    return Boolean(draft && Number.isFinite(draft.counter));
  }

  async saveOrderEdit(): Promise<void> {
    const order = this.orderEdit();
    const draft = this.orderEditDraft();
    if (!order || !draft || !this.canSaveOrderEdit()) {
      this.orderEditError.set('Проверьте данные заказа.');
      return;
    }

    this.orderEditSaving.set(true);
    this.orderEditError.set(null);

    try {
      const updated = await firstValueFrom(this.api.updateManagerOrder(order.id, this.normalizedOrderEditDraft(draft)));
      this.applyOrderEditPayload(updated);
      this.patchOrderFromEdit(updated);
      await this.load();
      this.orderEditSaving.set(false);
      this.closeOrderEdit();
    } catch (error) {
      this.orderEditError.set(this.apiErrorMessage(error, 'Заказ не сохранен.'));
    } finally {
      this.orderEditSaving.set(false);
    }
  }

  async deleteOrderEdit(): Promise<void> {
    const order = this.orderEdit();
    if (!order || !order.canDelete) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Удалить заказ',
      message: 'Удалить заказ?',
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.orderEditDeleting.set(true);
    this.orderEditError.set(null);

    try {
      await firstValueFrom(this.api.deleteManagerOrder(order.id));
      await this.load();
      this.orderEditDeleting.set(false);
      this.closeOrderEdit();
    } catch (error) {
      this.orderEditError.set(this.apiErrorMessage(error, 'Заказ не удален.'));
    } finally {
      this.orderEditDeleting.set(false);
    }
  }

  setCompanyEditField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
    this.companyEditDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  changeCompanyEditCategory(categoryId: number | null): void {
    this.companyEditDraft.update((draft) => draft ? { ...draft, categoryId, subCategoryId: null } : draft);
    this.companyEdit.update((company) => company ? { ...company, subCategories: [] } : company);

    if (!categoryId) {
      return;
    }

    void this.loadCompanyEditSubCategories(categoryId);
  }

  canSaveCompanyEdit(): boolean {
    const draft = this.companyEditDraft();
    return Boolean(
      draft?.title.trim()
      && draft.telephone.trim()
      && draft.urlChat.trim()
      && draft.city.trim()
      && draft.statusId
    );
  }

  async saveCompanyEdit(): Promise<void> {
    const company = this.companyEdit();
    const draft = this.companyEditDraft();
    if (!company || !draft || !this.canSaveCompanyEdit()) {
      this.companyEditError.set('Заполните обязательные поля компании.');
      return;
    }

    this.companyEditSaving.set(true);
    this.companyEditError.set(null);

    try {
      const updated = await firstValueFrom(this.api.updateManagerCompany(company.id, this.normalizedCompanyEditDraft(draft)));
      this.applyCompanyEditPayload(updated);
      this.patchCompanyFromEdit(updated);
      await this.load();
      this.closeCompanyEdit();
    } catch (error) {
      this.companyEditError.set(this.apiErrorMessage(error, 'Компания не сохранена.'));
    } finally {
      this.companyEditSaving.set(false);
    }
  }

  async deleteCompanyWorker(worker: ManagerOption): Promise<void> {
    const company = this.companyEdit();
    if (!company) {
      return;
    }

    const key = `worker-${worker.id}`;
    this.companyEditDeleteKey.set(key);
    this.companyEditError.set(null);

    try {
      const updated = await firstValueFrom(this.api.deleteManagerCompanyWorker(company.id, worker.id));
      this.applyCompanyEditPayload(updated);
      this.patchCompanyFromEdit(updated);
    } catch (error) {
      this.companyEditError.set(this.apiErrorMessage(error, 'Специалист не удален.'));
    } finally {
      this.companyEditDeleteKey.set(null);
    }
  }

  async deleteCompanyFilial(filial: CompanyFilialEditItem): Promise<void> {
    const company = this.companyEdit();
    if (!company) {
      return;
    }

    const key = `filial-${filial.id}`;
    this.companyEditDeleteKey.set(key);
    this.companyEditError.set(null);

    try {
      const updated = await firstValueFrom(this.api.deleteManagerCompanyFilial(company.id, filial.id));
      this.applyCompanyEditPayload(updated);
      this.patchCompanyFromEdit(updated);
    } catch (error) {
      this.companyEditError.set(this.apiErrorMessage(error, 'Филиал не удален.'));
    } finally {
      this.companyEditDeleteKey.set(null);
    }
  }

  startFilialEdit(filial: CompanyFilialEditItem): void {
    this.companyFilialDraft.set({
      filialId: filial.id,
      title: filial.title ?? '',
      url: filial.url ?? '',
      cityId: filial.cityId ?? null
    });
  }

  cancelFilialEdit(): void {
    this.companyFilialDraft.set(null);
  }

  setFilialDraftField<K extends keyof CompanyFilialUpdateRequest>(field: K, value: CompanyFilialUpdateRequest[K]): void {
    this.companyFilialDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  canSaveFilialEdit(): boolean {
    const draft = this.companyFilialDraft();
    return Boolean(
      draft
      && draft.title.trim()
      && draft.url.trim()
      && draft.cityId
      && !this.companyEditSaving()
      && !this.companyEditDeleteKey()
    );
  }

  async saveFilialEdit(): Promise<void> {
    const company = this.companyEdit();
    const draft = this.companyFilialDraft();
    if (!company || !draft || !this.canSaveFilialEdit()) {
      return;
    }

    const key = `filial-edit-${draft.filialId}`;
    this.companyEditDeleteKey.set(key);
    this.companyEditError.set(null);

    try {
      const updated = await firstValueFrom(this.api.updateManagerCompanyFilial(company.id, draft.filialId, {
        title: draft.title.trim(),
        url: draft.url.trim(),
        cityId: draft.cityId
      }));
      this.applyCompanyEditPayload(updated);
      this.patchCompanyFromEdit(updated);
      this.companyFilialDraft.set(null);
    } catch (error) {
      this.companyEditError.set(this.apiErrorMessage(error, 'Филиал не сохранен.'));
    } finally {
      this.companyEditDeleteKey.set(null);
    }
  }

  showAllCompanyOrders(): void {
    this.activeSection.set('orders');
    this.orderStatus.set(ALL_STATUS);
    this.selectedCompany.set(null);
    this.companyDetails.set(null);
    this.pageNumber.set(0);
    this.listExpanded.set(false);
    this.statusSheetOpen.set(false);
    void this.load();
  }

  openManualCompanyCreate(): void {
    if (this.companySaving()) {
      return;
    }

    this.companyCreateOpen.set(true);
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

    this.companyCreateOpen.set(false);
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

    if (!categoryId) {
      return;
    }

    void this.loadCompanySubCategories(categoryId);
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
      this.companyCreateOpen.set(false);
      this.companyPayload.set(null);
      this.companyDraft.set(null);
      this.companySubCategories.set([]);
      this.activeSection.set('companies');
      this.companyStatus.set('Новая');
      this.selectedCompany.set(null);
      this.keyword.set('');
      this.appliedKeyword.set('');
      this.pageNumber.set(0);
      this.listExpanded.set(false);
      await this.load();
    } catch (error) {
      this.companyError.set(this.apiErrorMessage(error, 'Не удалось создать компанию.'));
    } finally {
      this.companySaving.set(false);
    }
  }

  openCompanyOrders(company: CompanyItem): void {
    this.activeSection.set('orders');
    this.orderStatus.set(ALL_STATUS);
    this.selectedCompany.set({ id: company.id, title: company.title || `Компания #${company.id}` });
    this.companyDetails.set(null);
    this.pageNumber.set(0);
    this.listExpanded.set(false);
    this.statusSheetOpen.set(false);
    void this.load();
  }

  private async openCreatedCompanyOrders(result: CompanyOrderCreateResult): Promise<void> {
    this.activeSection.set('orders');
    this.orderStatus.set(ALL_STATUS);
    this.selectedCompany.set({ id: result.companyId, title: result.companyTitle || `Компания #${result.companyId}` });
    this.companyDetails.set(null);
    this.keyword.set('');
    this.appliedKeyword.set('');
    this.pageNumber.set(0);
    this.listExpanded.set(false);
    this.statusSheetOpen.set(false);
    await this.load();
  }

  async updateCompanyStatus(company: CompanyItem, action: CompanyStatusAction): Promise<void> {
    const key = this.companyMutationKey(company, action);
    this.mutationKey.set(key);

    try {
      await firstValueFrom(this.api.updateManagerCompanyStatus(company.id, action.status));
      this.patchCompany(company.id, { status: action.status });
      this.companyDetails.update((current) => current?.id === company.id ? { ...current, status: action.status } : current);
      await this.load();
      this.error.set(null);
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Не удалось изменить статус компании.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  isMutatingCompany(company: CompanyItem, action: CompanyStatusAction): boolean {
    return this.mutationKey() === this.companyMutationKey(company, action);
  }

  async updateOrderStatus(order: OrderItem, action: OrderStatusAction): Promise<void> {
    const key = this.orderMutationKey(order, action);
    this.mutationKey.set(key);

    try {
      await firstValueFrom(this.api.updateManagerOrderStatus(order.id, action.status));
      this.patchOrder(order.id, { status: action.status, waitingForClient: false });
      await this.load();
      this.error.set(null);
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Не удалось изменить статус заказа.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  isMutatingOrder(order: OrderItem, action: OrderStatusAction): boolean {
    return this.mutationKey() === this.orderMutationKey(order, action);
  }

  async updateOrderStatusFromCard(order: OrderItem, action: MobileOrderStatusAction): Promise<void> {
    const matchedAction = this.orderActions.find((item) => item.status === action.status);
    if (!matchedAction) {
      return;
    }

    await this.updateOrderStatus(order, matchedAction);
  }

  canManageOrderClientWaiting(order: OrderItem): boolean {
    return order.status === 'Новый' || order.status === 'Коррекция' || Boolean(order.waitingForClient);
  }

  async toggleOrderClientWaiting(order: OrderItem): Promise<void> {
    const key = `order-${order.id}-client-waiting`;
    const waitingForClient = !order.waitingForClient;
    this.mutationKey.set(key);

    try {
      await firstValueFrom(this.api.updateManagerOrderClientWaiting(order.id, waitingForClient));
      this.patchOrder(order.id, { waitingForClient });
      this.error.set(null);
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Не удалось изменить ожидание клиента.'));
    } finally {
      this.mutationKey.set(null);
    }
  }

  isOrderClientWaitingMutating(order: OrderItem): boolean {
    return this.mutationKey() === `order-${order.id}-client-waiting`;
  }

  hasCompanyNote(company: CompanyItem): boolean {
    return Boolean((company.commentsCompany ?? '').trim());
  }

  companyNoteBadge(company: CompanyItem): string {
    const hasNote = this.hasCompanyNote(company);
    const hasOrderRequest = this.hasNextOrderRequest(company);
    if (hasNote && hasOrderRequest) {
      return '2 метки';
    }
    if (hasNote) {
      return 'заметка';
    }
    if (hasOrderRequest) {
      return this.hasFailedNextOrderRequest(company) ? 'ошибка' : 'заказ';
    }
    return '';
  }

  companyNoteFor(company: CompanyItem): string {
    return this.companyNoteDrafts()[company.id] ?? company.commentsCompany ?? '';
  }

  companyNoteSaveState(company: CompanyItem): CompanyNoteSaveState {
    return this.companyNoteSaveStates()[company.id] ?? 'idle';
  }

  companyNoteSaveLabel(company: CompanyItem): string {
    return {
      idle: '',
      saving: 'сохранение',
      saved: 'сохранено',
      error: 'ошибка'
    }[this.companyNoteSaveState(company)];
  }

  setCompanyNote(company: CompanyItem, value: string): void {
    this.companyNoteDrafts.update((drafts) => ({
      ...drafts,
      [company.id]: value
    }));

    const existingTimer = this.companyNoteTimers.get(company.id);
    if (existingTimer) {
      clearTimeout(existingTimer);
    }

    this.setCompanyNoteSaveState(company.id, 'idle');
    this.companyNoteTimers.set(company.id, setTimeout(() => {
      void this.saveCompanyNoteNow(company);
    }, 850));
  }

  async saveCompanyNoteNow(company: CompanyItem): Promise<void> {
    const timer = this.companyNoteTimers.get(company.id);
    if (timer) {
      clearTimeout(timer);
      this.companyNoteTimers.delete(company.id);
    }

    const companyComments = this.companyNoteFor(company).trim();
    if (companyComments === (company.commentsCompany ?? '').trim()) {
      this.setCompanyNoteSaveState(company.id, 'idle');
      return;
    }

    const version = (this.companyNoteVersions.get(company.id) ?? 0) + 1;
    this.companyNoteVersions.set(company.id, version);
    this.setCompanyNoteSaveState(company.id, 'saving');

    try {
      await firstValueFrom(this.api.updateManagerCompanyNote(company.id, companyComments));
      if (this.companyNoteVersions.get(company.id) !== version) {
        return;
      }

      this.patchCompany(company.id, { commentsCompany: companyComments });
      this.companyDetails.update((current) => current?.id === company.id ? { ...current, commentsCompany: companyComments } : current);
      this.companyEdit.update((current) => current?.id === company.id ? { ...current, commentsCompany: companyComments } : current);
      this.setCompanyNoteSaveState(company.id, 'saved');
      window.setTimeout(() => {
        if (this.companyNoteSaveStates()[company.id] === 'saved') {
          this.setCompanyNoteSaveState(company.id, 'idle');
        }
      }, 1200);
    } catch (error) {
      if (this.companyNoteVersions.get(company.id) !== version) {
        return;
      }

      this.setCompanyNoteSaveState(company.id, 'error');
      this.error.set(this.apiErrorMessage(error, 'Комментарий компании не сохранился.'));
    }
  }

  focusCompanyNote(company: CompanyItem): void {
    window.setTimeout(() => {
      document.getElementById(`companyNote${company.id}`)?.focus();
    });
  }

  hasOrderNote(order: OrderItem): boolean {
    return Boolean((order.orderComments ?? '').trim());
  }

  hasOrderCompanyNote(order: OrderItem): boolean {
    return Boolean((order.companyComments ?? '').trim());
  }

  orderNoteBadge(order: OrderItem): string {
    const hasOrder = this.hasOrderNote(order);
    const hasCompany = this.hasOrderCompanyNote(order);
    if (hasOrder && hasCompany) {
      return '2 заметки';
    }
    if (hasOrder) {
      return 'заметка';
    }
    if (hasCompany) {
      return 'комп.';
    }
    return '';
  }

  orderNoteFor(order: OrderItem): string {
    return this.orderNoteDrafts()[order.id] ?? order.orderComments ?? '';
  }

  orderNoteSaveState(order: OrderItem): OrderNoteSaveState {
    return this.orderNoteSaveStates()[order.id] ?? 'idle';
  }

  orderNoteSaveLabel(order: OrderItem): string {
    return {
      idle: '',
      saving: 'сохранение',
      saved: 'сохранено',
      error: 'ошибка'
    }[this.orderNoteSaveState(order)];
  }

  setOrderNote(order: OrderItem, value: string): void {
    this.orderNoteDrafts.update((drafts) => ({
      ...drafts,
      [order.id]: value
    }));

    const existingTimer = this.orderNoteTimers.get(order.id);
    if (existingTimer) {
      clearTimeout(existingTimer);
    }

    this.setOrderNoteSaveState(order.id, 'idle');
    this.orderNoteTimers.set(order.id, setTimeout(() => {
      void this.saveOrderNoteNow(order);
    }, 850));
  }

  async saveOrderNoteNow(order: OrderItem): Promise<void> {
    const timer = this.orderNoteTimers.get(order.id);
    if (timer) {
      clearTimeout(timer);
      this.orderNoteTimers.delete(order.id);
    }

    const orderComments = this.orderNoteFor(order).trim();
    if (orderComments === (order.orderComments ?? '').trim()) {
      this.setOrderNoteSaveState(order.id, 'idle');
      return;
    }

    const version = (this.orderNoteVersions.get(order.id) ?? 0) + 1;
    this.orderNoteVersions.set(order.id, version);
    this.setOrderNoteSaveState(order.id, 'saving');

    try {
      const notes = await firstValueFrom(this.api.updateManagerOrderNote(order.id, orderComments));
      if (this.orderNoteVersions.get(order.id) !== version) {
        return;
      }

      this.patchOrder(order.id, {
        orderComments: notes.orderComments ?? orderComments,
        companyComments: notes.companyComments ?? order.companyComments
      });
      this.setOrderNoteSaveState(order.id, 'saved');
      window.setTimeout(() => {
        if (this.orderNoteSaveStates()[order.id] === 'saved') {
          this.setOrderNoteSaveState(order.id, 'idle');
        }
      }, 1200);
    } catch (error) {
      if (this.orderNoteVersions.get(order.id) !== version) {
        return;
      }

      this.setOrderNoteSaveState(order.id, 'error');
      this.error.set(this.apiErrorMessage(error, 'Заметка заказа не сохранилась.'));
    }
  }

  focusOrderNote(order: OrderItem): void {
    window.setTimeout(() => {
      document.getElementById(`orderNote${order.id}`)?.focus();
    });
  }

  async copyCompanyPhone(company: CompanyItem): Promise<void> {
    const phone = normalizePhoneDigits(company.telephone);
    if (!phone) {
      return;
    }

    await this.copyText(phone, `company-phone-${company.id}`, 'Не удалось скопировать телефон.');
  }

  async copyOrderPhone(order: OrderItem): Promise<void> {
    const phone = normalizePhoneDigits(order.companyTelephone);
    if (!phone) {
      return;
    }

    await this.copyText(phone, `order-phone-${order.id}`, 'Не удалось скопировать телефон.');
  }

  async copyManagerOrderText(order: OrderItem, kind: MobileOrderCopyKind): Promise<void> {
    if (kind === 'payment') {
      await this.copyOrderPaymentText(order);
      return;
    }

    await this.copyOrderReviewText(order);
  }

  async copyOrderReviewText(order: OrderItem): Promise<void> {
    const reviewUrl = this.orderReviewUrl(order);
    const text = [
      this.orderTitle(order),
      order.firstOrderForCompany
        ? 'Здравствуйте, это новые тексты на проверку. Проверьте, пожалуйста, их в течение трёх дней.'
        : 'Здравствуйте, текст отзывов для новых отзывов на следующий месяц готов.',
      reviewUrl ? `Ссылка на проверку отзывов: ${reviewUrl}` : ''
    ].filter(Boolean).join('\n\n');

    await this.copyText(text, `order-review-${order.id}`, 'Не удалось скопировать текст.');
  }

  async copyOrderPaymentText(order: OrderItem): Promise<void> {
    const text = [
      this.orderTitle(order),
      (order.managerPayText ?? '').trim(),
      `К оплате: ${this.orderMoney(this.orderPayableSum(order))}`
    ].filter(Boolean).join('\n\n');

    await this.copyText(text, `order-payment-${order.id}`, 'Не удалось скопировать счет.');
  }

  guardLink(event: Event, url: string): void {
    if (url) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
  }

  openOrderDetails(order: OrderItem): void {
    const companyId = order.companyId;
    if (!companyId) {
      this.error.set('Для заказа не указан ID компании.');
      return;
    }

    void this.router.navigate(['/tabs/orders', companyId, order.id]);
  }

  companyChatUrl(company: CompanyItem): string {
    return (company.telegramBotInviteUrl ?? '').trim()
      || (company.maxBotInviteUrl ?? '').trim()
      || (company.urlChat ?? '').trim()
      || phoneHref(company.telephone);
  }

  companyPhone(company: CompanyItem): string {
    return displayPhone(company.telephone);
  }

  orderTitle(order: OrderItem): string {
    return [order.companyTitle || 'Без компании', order.filialTitle || 'Без филиала']
      .filter(Boolean)
      .join(' - ');
  }

  orderPayableSum(order: OrderItem): number {
    return order.totalSumWithBadReviews ?? order.sum ?? 0;
  }

  orderMoney(value?: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value ?? 0)} руб.`;
  }

  orderPhone(order: OrderItem): string {
    return displayPhone(order.companyTelephone);
  }

  orderChatUrl(order: OrderItem): string {
    const digits = normalizePhoneDigits(order.companyTelephone);
    return (order.telegramBotInviteUrl ?? '').trim()
      || (order.maxBotInviteUrl ?? '').trim()
      || (order.companyUrlChat ?? '').trim()
      || (digits ? `tel:+${digits}` : '');
  }

  orderReviewUrl(order: OrderItem): string {
    const detailsId = (order.orderDetailsId ?? '').trim();
    if (!detailsId) {
      return '';
    }

    const origin = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
      ? 'https://o-ogo.ru'
      : window.location.origin;
    return new URL(`/${detailsId}`, origin).toString();
  }

  orderFilialUrl(order: OrderItem): string {
    return (order.filialUrl ?? '').trim();
  }

  orderCity(order: OrderItem): string {
    return (order.filialCity ?? '').trim() || 'Город не указан';
  }

  showBadReviewSummary(order: OrderItem): boolean {
    return order.status !== 'Оплачено' && (order.badReviewTasksTotal ?? 0) > 0;
  }

  orderActionsFor(order: OrderItem): readonly OrderStatusAction[] {
    return managerOrderActionsFor(order, Boolean(this.selectedCompany()));
  }

  orderProgress(order: OrderItem): number {
    if (!order.amount || !order.counter) {
      return 0;
    }

    return Math.max(0, Math.min(100, Math.round((order.counter / order.amount) * 100)));
  }

  orderUnchangedDays(order: OrderItem): number {
    return Math.max(0, order.dayToChangeStatusAgo ?? 0);
  }

  isOrderUnchangedAlert(order: OrderItem): boolean {
    return this.orderUnchangedDays(order) >= 2;
  }

  workerLabel(order: OrderItem): string {
    const fio = (order.workerUserFio ?? '').trim();
    if (!fio) {
      return '-';
    }

    const [first, second] = fio.split(/\s+/);
    return second ? `${first} ${second.charAt(0)}.` : first;
  }

  orderTone(order: OrderItem): string {
    if (order.waitingForClient) {
      return 'lead-card--new';
    }

    switch (order.status) {
      case 'Новый':
      case 'На проверке':
      case 'Напоминание':
        return 'lead-card--new';
      case 'Коррекция':
      case 'Публикация':
        return 'lead-card--send';
      case 'Опубликовано':
      case 'Оплачено':
        return 'lead-card--work';
      default:
        return '';
    }
  }

  companyTone(company: CompanyItem): 'default' | 'new' | 'work' | 'send' {
    const status = (company.status ?? '').trim().toLocaleLowerCase('ru-RU');
    if (status.includes('нов')) {
      return 'new';
    }
    if (status.includes('работ')) {
      return 'work';
    }
    if (status.includes('рассыл') || status.includes('ожидан')) {
      return 'send';
    }
    return 'default';
  }

  hasNextOrderRequest(company: CompanyItem): boolean {
    return (company.nextOrderRequestsCount ?? 0) > 0;
  }

  hasFailedNextOrderRequest(company: CompanyItem): boolean {
    return (company.failedNextOrderRequestsCount ?? 0) > 0;
  }

  nextOrderRequestLabel(company: CompanyItem): string {
    const count = company.nextOrderRequestsCount ?? 0;
    const filial = (company.nextOrderRequestFilialTitle ?? '').trim();
    if (count <= 1) {
      const label = this.hasFailedNextOrderRequest(company) ? 'Автозаказ не создан' : 'Нужен заказ';
      return filial ? `${label}: ${filial}` : label;
    }
    return filial ? `Нужны заказы: ${count}, ${filial}` : `Нужны заказы: ${count}`;
  }

  filialLabel(company: CompanyItem): string {
    const count = company.countFilials ?? 0;
    if (count === 1) {
      return '1 филиал';
    }
    if (count > 1 && count < 5) {
      return `${count} филиала`;
    }
    return `${count} филиалов`;
  }

  money(value?: number): string {
    return value == null ? 'Сумма не указана' : `${new Intl.NumberFormat('ru-RU').format(value)} ₽`;
  }

  dateText(value?: string): string {
    return value ? value.slice(0, 10) : 'Нет даты';
  }

  private async load(): Promise<void> {
    this.loading.set(true);

    try {
      const board = await firstValueFrom(this.api.getManagerBoard({
        section: this.activeSection(),
        status: this.currentStatus(),
        keyword: this.appliedKeyword(),
        companyId: this.activeSection() === 'orders' ? this.selectedCompany()?.id : undefined,
        pageNumber: this.pageNumber(),
        pageSize: this.pageSize(),
        sortDirection: this.sortDirection()
      }));
      this.board.set(board);
      this.mergeCompanyNoteDrafts(board);
      this.mergeOrderNoteDrafts(board);
      this.error.set(null);
    } catch (error) {
      this.error.set(this.apiErrorMessage(error, 'Не удалось загрузить раздел.'));
    } finally {
      this.loading.set(false);
    }
  }

  private activePage(): Page<CompanyItem> | Page<OrderItem> {
    const board = this.board();
    return this.activeSection() === 'orders'
      ? board?.orders ?? this.emptyPage<OrderItem>()
      : board?.companies ?? this.emptyPage<CompanyItem>();
  }

  private statusMetric(status: string) {
    const section = this.activeSection();
    const normalized = this.normalizedStatus(status);
    return this.board()?.metrics?.find((metric) =>
      metric.section === section && this.normalizedStatus(metric.status ?? '') === normalized
    );
  }

  private applyRouteSection(): boolean {
    const rawSection = this.route.snapshot.data['managerSection'];
    const nextSection: ManagerBoardSection = rawSection === 'orders' ? 'orders' : 'companies';
    const changed = this.activeSection() !== nextSection;
    this.activeSection.set(nextSection);
    if (changed || nextSection === 'companies') {
      this.selectedCompany.set(null);
      this.companyDetails.set(null);
      this.listExpanded.set(false);
    }
    return changed;
  }

  private applyMobileNavIntent(load: boolean, params: ParamMap = this.route.snapshot.queryParamMap): boolean {
    const intent = params.get('mobileNav');
    if (intent === 'menu') {
      this.statusSheetOpen.set(true);
      return false;
    }

    if (intent === 'all') {
      this.openAllStatus(load);
      return load;
    }

    return false;
  }

  private mobileNavKey(params: ParamMap = this.route.snapshot.queryParamMap): string {
    return `${params.get('mobileNav') ?? ''}:${params.get('navTs') ?? ''}`;
  }

  private emptyPage<T>(): Page<T> {
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

  private setCompanyNoteSaveState(companyId: number, state: CompanyNoteSaveState): void {
    this.companyNoteSaveStates.update((states) => ({
      ...states,
      [companyId]: state
    }));
  }

  private mergeCompanyNoteDrafts(board: ManagerBoard): void {
    const companies = board.companies?.content ?? [];
    if (!companies.length) {
      return;
    }

    this.companyNoteDrafts.update((drafts) => {
      const nextDrafts = { ...drafts };
      for (const company of companies) {
        if (!(company.id in nextDrafts)) {
          nextDrafts[company.id] = company.commentsCompany ?? '';
        }
      }
      return nextDrafts;
    });
  }

  private setOrderNoteSaveState(orderId: number, state: OrderNoteSaveState): void {
    this.orderNoteSaveStates.update((states) => ({
      ...states,
      [orderId]: state
    }));
  }

  private mergeOrderNoteDrafts(board: ManagerBoard): void {
    const orders = board.orders?.content ?? [];
    if (!orders.length) {
      return;
    }

    this.orderNoteDrafts.update((drafts) => {
      const nextDrafts = { ...drafts };
      for (const order of orders) {
        if (!(order.id in nextDrafts)) {
          nextDrafts[order.id] = order.orderComments ?? '';
        }
      }
      return nextDrafts;
    });
  }

  private async loadCompanyEdit(companyId: number): Promise<void> {
    this.companyEditLoading.set(true);
    this.companyEditError.set(null);

    try {
      const payload = await firstValueFrom(this.api.getManagerCompanyEdit(companyId));
      this.applyCompanyEditPayload(payload);
    } catch (error) {
      this.companyEditError.set(this.apiErrorMessage(error, 'Не удалось загрузить редактор компании.'));
    } finally {
      this.companyEditLoading.set(false);
    }
  }

  private async loadOrderEdit(orderId: number): Promise<void> {
    this.orderEditLoading.set(true);
    this.orderEditError.set(null);

    try {
      const payload = await firstValueFrom(this.api.getManagerOrderEdit(orderId));
      this.applyOrderEditPayload(payload);
    } catch (error) {
      this.orderEditError.set(this.apiErrorMessage(error, 'Не удалось загрузить редактор заказа.'));
    } finally {
      this.orderEditLoading.set(false);
    }
  }

  private async loadCompanyOrderCreate(companyId: number): Promise<void> {
    this.orderCreateLoading.set(true);
    this.orderCreateError.set(null);

    try {
      const payload = await firstValueFrom(this.api.getManagerCompanyOrderCreate(companyId));
      this.orderCreatePayload.set(payload);
      this.orderCreateDraft.set(this.orderCreateDraftFromPayload(payload));
    } catch (error) {
      this.orderCreateError.set(this.apiErrorMessage(error, 'Не удалось загрузить создание заказа.'));
    } finally {
      this.orderCreateLoading.set(false);
    }
  }

  private orderCreateDraftFromPayload(payload: CompanyOrderCreatePayload): CompanyOrderCreateRequest {
    const preferredProductId = this.preferredOrderProduct(payload)?.id;
    const defaultAmount = payload.amounts.includes(5)
      ? 5
      : payload.defaultAmount ?? payload.amounts[0] ?? 5;

    return {
      productId: preferredProductId ?? payload.defaultProductId ?? payload.products[0]?.id ?? null,
      amount: defaultAmount,
      workerId: payload.defaultWorkerId ?? payload.workers[0]?.id ?? null,
      filialId: payload.defaultFilialId ?? payload.filials[0]?.id ?? null
    };
  }

  private normalizedOrderCreateDraft(draft: CompanyOrderCreateRequest): CompanyOrderCreateRequest {
    return {
      productId: draft.productId,
      amount: Number.isFinite(draft.amount) ? draft.amount : 0,
      workerId: draft.workerId,
      filialId: draft.filialId
    };
  }

  private preferredOrderProduct(payload: CompanyOrderCreatePayload) {
    const preferred = this.normalizedProductLabel(PREFERRED_CREATE_ORDER_PRODUCT);
    return payload.products.find((product) => this.normalizedProductLabel(product.label) === preferred);
  }

  private normalizedProductLabel(label: string): string {
    return label
      .toLocaleLowerCase('ru-RU')
      .replace(/ё/g, 'е')
      .replace(/\s+/g, '');
  }

  private applyOrderEditPayload(payload: OrderEditPayload): void {
    this.orderEdit.set(payload);
    this.orderEditDraft.set(this.orderEditDraftFromPayload(payload));
  }

  private orderEditDraftFromPayload(payload: OrderEditPayload): OrderUpdateRequest {
    return {
      filialId: payload.filial?.id ?? null,
      workerId: payload.worker?.id ?? null,
      managerId: payload.manager?.id ?? null,
      counter: payload.counter ?? 0,
      orderComments: payload.orderComments ?? '',
      commentsCompany: payload.commentsCompany ?? '',
      complete: !!payload.complete
    };
  }

  private normalizedOrderEditDraft(draft: OrderUpdateRequest): OrderUpdateRequest {
    return {
      ...draft,
      counter: Number.isFinite(draft.counter) ? draft.counter : 0,
      orderComments: draft.orderComments.trim(),
      commentsCompany: draft.commentsCompany.trim()
    };
  }

  private patchOrderFromEdit(payload: OrderEditPayload): void {
    this.patchOrder(payload.id, {
      status: payload.status,
      sum: payload.sum,
      amount: payload.amount,
      counter: payload.counter,
      changed: payload.changed,
      payDay: payload.payDay,
      orderComments: payload.orderComments,
      companyComments: payload.commentsCompany,
      workerUserFio: payload.worker?.label ?? ''
    });
  }

  private async loadCompanyEditSubCategories(categoryId: number): Promise<void> {
    try {
      const subCategories = await firstValueFrom(this.api.getManagerCompanySubcategories(categoryId));
      this.companyEdit.update((company) => company ? { ...company, subCategories } : company);
    } catch (error) {
      this.companyEditError.set(this.apiErrorMessage(error, 'Не удалось загрузить подкатегории.'));
    }
  }

  private applyCompanyEditPayload(payload: CompanyEditPayload): void {
    this.companyEdit.set(payload);
    this.companyEditDraft.set(this.companyEditDraftFromPayload(payload));
  }

  private companyEditDraftFromPayload(payload: CompanyEditPayload): CompanyUpdateRequest {
    return {
      title: payload.title ?? '',
      urlChat: payload.urlChat ?? '',
      urlSite: payload.urlSite ?? '',
      telephone: payload.telephone ?? '',
      city: payload.city ?? '',
      email: payload.email ?? '',
      categoryId: payload.category?.id ?? null,
      subCategoryId: payload.subCategory?.id ?? null,
      statusId: payload.status?.id ?? null,
      managerId: payload.manager?.id ?? null,
      commentsCompany: payload.commentsCompany ?? '',
      active: payload.active,
      newWorkerId: null,
      newFilialCityId: null,
      newFilialTitle: '',
      newFilialUrl: ''
    };
  }

  private normalizedCompanyEditDraft(draft: CompanyUpdateRequest): CompanyUpdateRequest {
    return {
      ...draft,
      title: draft.title.trim(),
      urlChat: draft.urlChat.trim(),
      urlSite: draft.urlSite.trim(),
      telephone: draft.telephone.trim(),
      city: draft.city.trim(),
      email: draft.email.trim(),
      commentsCompany: draft.commentsCompany.trim(),
      newFilialTitle: draft.newFilialTitle.trim(),
      newFilialUrl: draft.newFilialUrl.trim()
    };
  }

  private patchCompanyFromEdit(payload: CompanyEditPayload): void {
    const patch = {
      title: payload.title,
      urlChat: payload.urlChat,
      telephone: payload.telephone,
      city: payload.city,
      status: payload.status?.label ?? '',
      manager: payload.manager?.label ?? '',
      commentsCompany: payload.commentsCompany,
      countFilials: payload.filials?.length ?? 0,
      dateNewTry: payload.dateNewTry
    };
    this.patchCompany(payload.id, patch);
    this.companyDetails.update((current) => current?.id === payload.id ? { ...current, ...patch } : current);
  }

  private async loadCompanyPayload(managerId?: number | null, preserved?: CompanyPreservedFields): Promise<void> {
    this.companyLoading.set(true);
    this.companyError.set(null);

    try {
      const payload = await firstValueFrom(this.api.getCompanyCreatePayload('manual', null, managerId));
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
      managerId: payload.manager?.id ?? managerId ?? null,
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

  private companyMutationKey(company: CompanyItem, action: CompanyStatusAction): string {
    return `company-${company.id}-${action.status}`;
  }

  private orderMutationKey(order: OrderItem, action: OrderStatusAction): string {
    return `order-${order.id}-${action.status}`;
  }

  private async copyText(text: string, key: string, fallback: string): Promise<void> {
    const value = text.trim();
    if (!value) {
      return;
    }

    try {
      await this.writeClipboard(value);
      this.copiedKey.set(key);
      window.setTimeout(() => {
        if (this.copiedKey() === key) {
          this.copiedKey.set(null);
        }
      }, 1100);
    } catch {
      this.error.set(fallback);
    }
  }

  private async writeClipboard(value: string): Promise<void> {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        return;
      }
    } catch {
      // Some mobile/in-app browsers expose Clipboard API but deny access.
    }

    if (this.writeClipboardLegacy(value)) {
      return;
    }

    throw new Error('Clipboard access denied');
  }

  private writeClipboardLegacy(value: string): boolean {
    const textArea = document.createElement('textarea');
    textArea.value = value;
    textArea.setAttribute('readonly', '');
    textArea.style.position = 'fixed';
    textArea.style.left = '-9999px';
    textArea.style.top = '0';
    textArea.style.opacity = '0';
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    textArea.setSelectionRange(0, value.length);

    try {
      return document.execCommand('copy');
    } catch {
      return false;
    } finally {
      document.body.removeChild(textArea);
    }
  }

  private patchCompany(companyId: number, patch: Partial<CompanyItem>): void {
    this.board.update((board) => board ? {
      ...board,
      companies: {
        ...board.companies,
        content: board.companies.content.map((company) => company.id === companyId
          ? { ...company, ...patch }
          : company
        )
      }
    } : board);

    if (patch.commentsCompany != null) {
      this.companyNoteDrafts.update((drafts) => ({
        ...drafts,
        [companyId]: patch.commentsCompany ?? ''
      }));
    }
  }

  private patchOrder(orderId: number, patch: Partial<OrderItem>): void {
    this.board.update((board) => board ? {
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.map((order) => order.id === orderId
          ? { ...order, ...patch }
          : order
        )
      }
    } : board);

    if (patch.orderComments != null) {
      this.orderNoteDrafts.update((drafts) => ({
        ...drafts,
        [orderId]: patch.orderComments ?? ''
      }));
    }
  }

  private apiErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const backendMessage = typeof error.error === 'object' && error.error && 'message' in error.error
        ? String((error.error as { message?: unknown }).message ?? '')
        : typeof error.error === 'string'
          ? error.error
          : '';
      const message = backendMessage.trim();
      if (message) {
        return message;
      }

      if (error.status === 404 && error.url?.includes('/api/companies/create-payload')) {
        return 'Backend не нашел API создания компании. Перезапустите локальный prod-like backend, чтобы подтянуть свежую версию.';
      }

      if (error.status) {
        return `${fallback} Код ${error.status}.`;
      }
    }

    return error instanceof Error ? error.message : fallback;
  }
}
