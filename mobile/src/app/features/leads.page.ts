import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { IonContent, IonModal } from '@ionic/angular/standalone';
import { Observable, Subscription, firstValueFrom } from 'rxjs';
import {
  ApiService,
  CompanyCreateOption,
  CompanyCreatePayload,
  CompanyCreateRequest,
  CompanyCreateSource,
  LeadBoard,
  LeadBucketKey,
  LeadCreateRequest,
  LeadEditOptions,
  LeadImportResponse,
  LeadItem,
  LeadPersonOption,
  LeadUpdateRequest,
  Page,
  PersonalReminder,
  PersonalReminderMode,
  PersonalReminderRequest
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { MOBILE_ACTIONS, MOBILE_SECTIONS, canUseAction } from '../core/mobile-permissions';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileBottomPagerComponent } from '../shared/mobile-bottom-pager.component';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileNoteEditorComponent } from '../shared/mobile-note-editor.component';
import { MobileSearchBarComponent } from '../shared/mobile-search-bar.component';
import { MobileStatusSliderComponent, type MobileStatusItem } from '../shared/mobile-status-slider.component';
import {
  mobilePageIsFirst,
  mobilePageIsLast,
  mobilePageLabel,
  mobileSortTitle
} from '../shared/mobile-board.helpers';
import { displayPhone, phoneHref as formatPhoneHref } from '../shared/phone-format';
import {
  LEAD_BUCKETS,
  type LeadCommentSaveState,
  type LeadContactLink,
  type LeadMutation,
  leadActionIcon,
  leadActionLabel,
  leadAddressLine as leadAddressLineHelper,
  leadCategoryLine as leadCategoryLineHelper,
  leadContactLinks as leadContactLinksHelper,
  leadSocialLine,
  leadStatusActions,
  leadTitle as leadTitleHelper,
  leadTone as leadToneHelper,
  normalizedLeadPhone
} from './leads/lead-board.helpers';
import { MobileLeadCardComponent } from './leads/mobile-lead-card.component';

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

type PersonalReminderDraft = {
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAtLocal: string;
  timerMinutes: number;
};

@Component({
  selector: 'app-leads',
  imports: [DatePipe, FormsModule, IonContent, IonModal, MobileBottomPagerComponent, MobileHeaderComponent, MobileLeadCardComponent, MobileNoteEditorComponent, MobileSearchBarComponent, MobileStatusSliderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Лиды" />

      <ion-content fullscreen [scrollY]="false">
        <main class="leads-page">
          <app-mobile-status-slider
            [items]="statusItems()"
            [activeKey]="activeBucket()"
            ariaLabel="Статусы лидов"
            (select)="selectStatusSliderBucket($event)"
          />

          <app-mobile-search-bar
            [value]="keyword()"
            placeholder="Телефон, город, комментарий"
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

            <button mobileSearchActions type="button" (click)="openArchiveBucket()" [disabled]="loading()" aria-label="Архив">
              <span class="material-icons-sharp">archive</span>
            </button>

            @if (canImportLeads()) {
              <button mobileSearchActions type="button" (click)="openLeadImport()" [disabled]="loading()" aria-label="Импорт лидов">
                <span class="material-icons-sharp">upload_file</span>
              </button>
            }

            @if (canCreateLead()) {
              <button mobileSearchActions class="lead-create-inline-button" type="button" (click)="openCreateLead()" aria-label="Добавить лид">
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

          <section class="lead-list" aria-label="Список лидов">
            @for (lead of visibleLeads(); track lead.id) {
              <app-mobile-lead-card
                [lead]="lead"
                [canEdit]="canEditLead()"
                [canCreateCompany]="canCreateCompanyFromLead(lead)"
                [copiedLeadId]="copiedLeadId()"
                [comment]="commentFor(lead)"
                [commentStateLabel]="commentSaveLabel(lead) || ''"
                [commentState]="commentSaveState(lead)"
                [mutationKey]="mutationKey()"
                [privilegedActions]="canUsePrivilegedLeadActions()"
                (contact)="openContactMenu($event)"
                (copy)="copyPhone($event)"
                (commentChange)="setLeadComment(lead, $event)"
                (commentSave)="saveLeadCommentNow(lead)"
                (action)="runAction(lead, $event)"
                (createCompany)="openCompanyCreate($event)"
                (open)="openLead($event)"
              />
            } @empty {
              <div class="empty-state compact-empty">
                <span class="material-icons-sharp">inbox</span>
                <b>В этом статусе пусто</b>
              </div>
            }
          </section>

          <app-mobile-bottom-pager
            class="mobile-page-bottom-pager"
            [pageIndex]="pageNumber()"
            [totalPages]="currentPage().totalPages || 1"
            (previous)="previousPage()"
            (next)="nextPage()"
          >
            <button
              mobilePagerActions
              class="expand-list-button reminder-hero-button"
              type="button"
              (click)="openReminderSheet()"
              aria-label="Напоминания"
            >
              <span class="material-icons-sharp">notifications_active</span>
              @if (activeReminderCount()) {
                <small>{{ activeReminderCount() }}</small>
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

          @if (bucketSheetOpen()) {
            <button class="lead-section-backdrop" type="button" aria-label="Закрыть разделы" (click)="closeBucketSheet()"></button>
            <section class="lead-section-sheet" role="dialog" aria-modal="true" aria-label="Выбор раздела лидов">
              <header>
                <div>
                  <small>Лиды</small>
                  <h2>Выберите раздел</h2>
                </div>
                <button type="button" class="icon-button" (click)="closeBucketSheet()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </header>

              <div class="lead-section-options">
                @for (bucket of bucketMenuOptions(); track bucket.key) {
                  <button
                    type="button"
                    class="metric-tile tone-{{ bucket.tone }}"
                    [class.active]="activeBucket() === bucket.key"
                    (click)="selectBucket(bucket.key)"
                  >
                    <span class="material-icons-sharp">{{ bucket.icon }}</span>
                    <strong>{{ bucketTotal(bucket.key) }}</strong>
                    <small>{{ bucket.short }}</small>
                  </button>
                }
              </div>
            </section>
          }
        </main>
      </ion-content>

      <ion-modal class="sheet-modal lead-detail-modal" [isOpen]="leadSheetOpen()" (didDismiss)="closeLeadSheet()">
        <ng-template>
          @if (selectedLead(); as lead) {
            <main class="sheet-body lead-detail">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Лид #{{ lead.id }} · {{ lead.lidStatus || 'без статуса' }}</p>
                  <h2>{{ leadTitle(lead) }}</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeLeadSheet()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              @if (leadContactLinks(lead); as contactLinks) {
                @if (contactLinks.length) {
                  <div class="lead-detail-contacts" aria-label="Контакты лида">
                    @for (link of contactLinks; track link.key) {
                      <a
                        [class]="'lead-contact-link lead-contact-link--' + link.key"
                        [href]="link.url"
                        target="_blank"
                        rel="noopener"
                        [title]="link.title"
                      >
                        {{ link.label }}
                      </a>
                    }
                  </div>
                }
              }

              <section class="detail-grid">
                <div>
                  <small>Телефон</small>
                  <b>{{ phoneDisplay(lead) }}</b>
                </div>
                <div>
                  <small>Город</small>
                  <b>{{ lead.cityLead || '-' }}</b>
                </div>
                <div class="wide">
                  <small>Компания</small>
                  <b>{{ lead.companyName || '-' }}</b>
                </div>
                <div class="wide">
                  <small>Категории</small>
                  <b>{{ leadCategoryLine(lead) || '-' }}</b>
                </div>
                <div class="wide">
                  <small>Адрес</small>
                  <b>{{ leadAddressLine(lead) || '-' }}</b>
                </div>
                <div>
                  <small>Телефоны</small>
                  <b>{{ lead.phones || '-' }}</b>
                </div>
                <div>
                  <small>Мобильные</small>
                  <b>{{ lead.mobilePhones || '-' }}</b>
                </div>
                <div>
                  <small>WhatsApp</small>
                  <b>{{ lead.whatsappPhones || '-' }}</b>
                </div>
                <div>
                  <small>Email</small>
                  <b>{{ lead.emails || '-' }}</b>
                </div>
                <div>
                  <small>Сайт</small>
                  <b>{{ lead.websites || '-' }}</b>
                </div>
                <div>
                  <small>VK / TG</small>
                  <b>{{ socialLine(lead) || '-' }}</b>
                </div>
                <div>
                  <small>Создан</small>
                  <b>{{ lead.createDate ? (lead.createDate | date: 'dd.MM HH:mm') : '-' }}</b>
                </div>
                <div>
                  <small>Менеджер</small>
                  <b>{{ personName(lead.manager) }}</b>
                </div>
                <div>
                  <small>Оператор</small>
                  <b>{{ personName(lead.operator) }}</b>
                </div>
              </section>

              <app-mobile-note-editor
                class="lead-comment-editor lead-comment-editor--detail"
                name="leadDetailComment"
                [rows]="4"
                placeholder="Комментарий"
                [readOnly]="!canEditLead()"
                [value]="commentFor(lead)"
                [stateLabel]="commentSaveLabel(lead) || ''"
                [state]="commentSaveState(lead)"
                (valueChange)="setLeadComment(lead, $event)"
                (blurred)="saveLeadCommentNow(lead)"
              />

              <div class="lead-action-grid">
                <button type="button" (click)="openContactMenu(lead)">
                  <span class="material-icons-sharp">call</span>
                  Связь
                </button>
                @if (canEditLead()) {
                  <button type="button" (click)="openEditLead(lead)">
                    <span class="material-icons-sharp">edit</span>
                    Изменить
                  </button>
                }
                @for (action of statusActions(lead); track action) {
                  <button type="button" (click)="runAction(lead, action)" [disabled]="isMutating(lead, action)">
                    <span class="material-icons-sharp">{{ actionIcon(action) }}</span>
                    {{ isMutating(lead, action) ? 'выполняю' : actionLabel(action) }}
                  </button>
                }
                @if (canCreateCompanyFromLead(lead)) {
                  <button type="button" (click)="openCompanyCreate(lead)">
                    <span class="material-icons-sharp">business_center</span>
                    работа
                  </button>
                }
              </div>
            </main>
          }
        </ng-template>
      </ion-modal>

      <ion-modal class="sheet-modal contact-sheet" [isOpen]="contactLead() !== null" (didDismiss)="closeContactMenu()">
        <ng-template>
          @if (contactLead(); as lead) {
            <main class="sheet-body contact-menu">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Связь с лидом #{{ lead.id }}</p>
                  <h2>{{ phoneDisplay(lead) }}</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeContactMenu()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="contact-options" aria-label="Открыть чат">
                <button class="contact-option contact-option--whatsapp" type="button" (click)="openMessenger(lead, 'whatsapp')">
                  <span>WA</span>
                  <strong>WhatsApp</strong>
                  <small>чат по номеру</small>
                </button>
                <button class="contact-option contact-option--telegram" type="button" (click)="openMessenger(lead, 'telegram')">
                  <span>TG</span>
                  <strong>Telegram</strong>
                  <small>если номер доступен</small>
                </button>
                <button class="contact-option contact-option--max" type="button" (click)="openMessenger(lead, 'max')">
                  <span>MX</span>
                  <strong>MAX</strong>
                  <small>отправить номер</small>
                </button>
                <a class="contact-option contact-option--call" [href]="phoneHref(lead)">
                  <span class="material-icons-sharp">call</span>
                  <strong>Звонок</strong>
                  <small>обычная связь</small>
                </a>
                <button class="contact-option" type="button" (click)="copyPhone(lead)">
                  <span class="material-icons-sharp">content_copy</span>
                  <strong>Скопировать</strong>
                  <small>{{ copiedLeadId() === lead.id ? 'готово' : 'номер телефона' }}</small>
                </button>
              </section>

              <p class="sheet-hint">Если приложение установлено, телефон откроет его автоматически. Если нет, откроется веб-страница или системный fallback.</p>
            </main>
          }
        </ng-template>
      </ion-modal>

      <ion-modal class="sheet-modal" [isOpen]="filterSheetOpen()" (didDismiss)="closeFilterSheet()">
        <ng-template>
          <form class="sheet-body sheet-form" (ngSubmit)="applyFilters()">
            <div class="sheet-head">
              <div>
                <p class="sheet-note">Список лидов</p>
                <h2>Поиск</h2>
              </div>
              <button class="icon-button" type="button" (click)="closeFilterSheet()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </div>

            <section class="sheet-form-content">
              <label class="sheet-field">
                <span>Найти</span>
                <input
                  name="keyword"
                  type="search"
                  autocomplete="off"
                  placeholder="Телефон, город, комментарий"
                  [ngModel]="keyword()"
                  (ngModelChange)="keyword.set($event)"
                >
              </label>

              <label class="sheet-field">
                <span>Сортировка</span>
                <select
                  name="sortDirection"
                  [ngModel]="sortDirection()"
                  (ngModelChange)="setSortDirection($event)"
                >
                  <option value="desc">Сначала давно без изменений</option>
                  <option value="asc">Сначала недавно измененные</option>
                </select>
              </label>

              <label class="sheet-field">
                <span>На странице</span>
                <select
                  name="pageSize"
                  [ngModel]="pageSize()"
                  (ngModelChange)="setPageSize($event)"
                >
                  @for (size of pageSizeOptions; track size) {
                    <option [ngValue]="size">{{ size }}</option>
                  }
                </select>
              </label>
            </section>

            <div class="sheet-actions">
              <button class="secondary" type="button" (click)="clearFilters()" [disabled]="loading()">Сбросить</button>
              <button type="submit" [disabled]="loading()">Применить</button>
            </div>
          </form>
        </ng-template>
      </ion-modal>

      <ion-modal class="sheet-modal" [isOpen]="leadImportOpen()" (didDismiss)="closeLeadImport()">
        <ng-template>
          <form class="sheet-body sheet-form" (ngSubmit)="uploadLeadImport()">
            <div class="sheet-head">
              <div>
                <p class="sheet-note">ADMIN / OWNER</p>
                <h2>Импорт лидов</h2>
              </div>
              <button class="icon-button" type="button" (click)="closeLeadImport()" [disabled]="leadImportSaving()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </div>

            <section class="sheet-form-content">
              @if (leadImportError()) {
                <p class="sheet-error">{{ leadImportError() }}</p>
              }

              <label class="import-file-picker">
                <span class="material-icons-sharp">upload_file</span>
                <strong>{{ leadImportFile()?.name || 'Выбрать CSV / Excel' }}</strong>
                <small>Файл будет отправлен на сервер через /api/leads/file-import</small>
                <input type="file" accept=".csv,.xlsx,.xls,.tsv" (change)="selectLeadImportFile($event)" [disabled]="leadImportSaving()">
              </label>

              <div class="import-assignment-card">
                <span>Менеджеры для распределения</span>
                <div class="import-manager-grid">
                  @for (manager of managerOptions(); track manager.id) {
                    <button type="button" [class.active]="isImportManagerSelected(manager.id)" (click)="toggleImportManager(manager.id)" [disabled]="leadImportSaving()">
                      <span class="material-icons-sharp">{{ isImportManagerSelected(manager.id) ? 'check_circle' : 'radio_button_unchecked' }}</span>
                      {{ optionLabel(manager) }}
                    </button>
                  }
                </div>
              </div>

              <label class="sheet-field">
                <span>Оператор</span>
                <select name="leadImportOperator" [ngModel]="leadImportOperatorId()" (ngModelChange)="leadImportOperatorId.set($event)" [disabled]="leadImportSaving()">
                  <option [ngValue]="null">Не назначать</option>
                  @for (operator of operatorOptions(); track operator.id) {
                    <option [ngValue]="operator.id">{{ optionLabel(operator) }}</option>
                  }
                </select>
              </label>

              <label class="sheet-field">
                <span>Маркетолог</span>
                <select name="leadImportMarketolog" [ngModel]="leadImportMarketologId()" (ngModelChange)="leadImportMarketologId.set($event)" [disabled]="leadImportSaving()">
                  <option [ngValue]="null">Не назначать</option>
                  @for (marketolog of marketologOptions(); track marketolog.id) {
                    <option [ngValue]="marketolog.id">{{ optionLabel(marketolog) }}</option>
                  }
                </select>
              </label>

              @if (leadImportResult(); as result) {
                <div class="import-result-card">
                  <strong>{{ leadImportResultMessage(result) }}</strong>
                  <span>Строк: {{ result.totalRows }}</span>
                  <span>Без телефона: {{ result.skippedWithoutPhones }}</span>
                  <span>Дубликаты: {{ result.skippedDuplicates }}</span>
                  <span>Некорректные: {{ result.skippedInvalid }}</span>
                  @if (result.managerAssignments.length) {
                    <div class="import-result-list">
                      @for (assignment of result.managerAssignments; track assignment.managerId) {
                        <small>{{ assignment.managerName }}: {{ assignment.added }}</small>
                      }
                    </div>
                  }
                  @if (result.errors.length) {
                    <div class="import-result-list danger">
                      @for (item of result.errors.slice(0, 4); track item) {
                        <small>{{ item }}</small>
                      }
                    </div>
                  }
                </div>
              }
            </section>

            <div class="sheet-actions">
              <button class="secondary" type="button" (click)="closeLeadImport()" [disabled]="leadImportSaving()">Отмена</button>
              <button type="submit" [disabled]="leadImportSaving() || !leadImportFile() || !leadImportManagerIds().length">
                {{ leadImportSaving() ? 'Загружаю' : 'Импортировать' }}
              </button>
            </div>
          </form>
        </ng-template>
      </ion-modal>

      <ion-modal class="sheet-modal" [isOpen]="createDraft() !== null" (didDismiss)="closeCreateLead()">
        <ng-template>
          @if (createDraft(); as draft) {
            <form class="sheet-body sheet-form" (ngSubmit)="saveNewLead()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Новый отклик</p>
                  <h2>Новый лид</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeCreateLead()" [disabled]="createSaving()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content">
                @if (createError()) {
                  <p class="sheet-error">{{ createError() }}</p>
                }

                <label class="sheet-field">
                  <span>Телефон</span>
                  <input
                    name="createTelephoneLead"
                    type="tel"
                    required
                    autocomplete="tel"
                    placeholder="Номер телефона"
                    [ngModel]="draft.telephoneLead"
                    (ngModelChange)="setCreateField('telephoneLead', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Наименование</span>
                  <input
                    name="createCompanyName"
                    type="text"
                    [ngModel]="draft.companyName"
                    (ngModelChange)="setCreateField('companyName', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Город</span>
                  <input
                    name="createCityLead"
                    type="text"
                    required
                    placeholder="Город"
                    [ngModel]="draft.cityLead"
                    (ngModelChange)="setCreateField('cityLead', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Регион</span>
                  <input
                    name="createRegion"
                    type="text"
                    [ngModel]="draft.region"
                    (ngModelChange)="setCreateField('region', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Телефоны</span>
                  <input
                    name="createPhones"
                    type="text"
                    [ngModel]="draft.phones"
                    (ngModelChange)="setCreateField('phones', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Мобильные</span>
                  <input
                    name="createMobilePhones"
                    type="text"
                    [ngModel]="draft.mobilePhones"
                    (ngModelChange)="setCreateField('mobilePhones', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>WhatsApp</span>
                  <input
                    name="createWhatsappPhones"
                    type="text"
                    [ngModel]="draft.whatsappPhones"
                    (ngModelChange)="setCreateField('whatsappPhones', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Емейлы</span>
                  <input
                    name="createEmails"
                    type="text"
                    [ngModel]="draft.emails"
                    (ngModelChange)="setCreateField('emails', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Сайты</span>
                  <input
                    name="createWebsites"
                    type="text"
                    [ngModel]="draft.websites"
                    (ngModelChange)="setCreateField('websites', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>VK</span>
                  <input
                    name="createVkUrl"
                    type="text"
                    [ngModel]="draft.vkUrl"
                    (ngModelChange)="setCreateField('vkUrl', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>TG</span>
                  <input
                    name="createTelegramUrl"
                    type="text"
                    [ngModel]="draft.telegramUrl"
                    (ngModelChange)="setCreateField('telegramUrl', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Отрасли</span>
                  <input
                    name="createIndustries"
                    type="text"
                    [ngModel]="draft.industries"
                    (ngModelChange)="setCreateField('industries', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Тип</span>
                  <input
                    name="createCompanyType"
                    type="text"
                    [ngModel]="draft.companyType"
                    (ngModelChange)="setCreateField('companyType', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Адрес</span>
                  <input
                    name="createAddress"
                    type="text"
                    [ngModel]="draft.address"
                    (ngModelChange)="setCreateField('address', $event)"
                  >
                </label>

                @if (canChooseCreateManager()) {
                  <label class="sheet-field">
                    <span>Менеджер</span>
                    <select
                      name="createManagerId"
                      [ngModel]="draft.managerId"
                      (ngModelChange)="setCreateField('managerId', $event)"
                    >
                      <option [ngValue]="null">Не выбран</option>
                      @for (manager of managerOptions(); track manager.id) {
                        <option [ngValue]="manager.id">{{ optionLabel(manager) }}</option>
                      }
                    </select>
                  </label>
                }

                <label class="sheet-field">
                  <span>Комментарий</span>
                  <textarea
                    name="createCommentsLead"
                    rows="3"
                    placeholder="Комментарий"
                    [ngModel]="draft.commentsLead"
                    (ngModelChange)="setCreateField('commentsLead', $event)"
                  ></textarea>
                </label>
              </section>

              <div class="sheet-actions">
                <button class="secondary" type="button" (click)="closeCreateLead()" [disabled]="createSaving()">Отмена</button>
                <button type="submit" [disabled]="createSaving() || !canSaveCreate()">
                  {{ createSaving() ? 'Создаю' : 'Создать' }}
                </button>
              </div>
            </form>
          }
        </ng-template>
      </ion-modal>

      <ion-modal class="sheet-modal reminders-sheet" [isOpen]="reminderSheetOpen()" (didDismiss)="closeReminderSheet()">
        <ng-template>
          <main class="sheet-body reminders-menu">
            <div class="sheet-head">
              <div>
                <p class="sheet-note">Личные дела</p>
                <h2>Напоминания</h2>
              </div>
              <div class="sheet-head-actions">
                <button class="icon-button" type="button" (click)="openReminderCreate()" aria-label="Создать напоминание">
                  <span class="material-icons-sharp">add</span>
                </button>
                <button class="icon-button" type="button" (click)="closeReminderSheet()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>
            </div>

            <section class="sheet-form-content reminders-menu-content">
              @if (reminderError()) {
                <p class="sheet-error">{{ reminderError() }}</p>
              }

              @if (reminderFormOpen()) {
                <form class="reminder-create-card" (ngSubmit)="saveReminder()">
                  <label class="sheet-field">
                    <span>Название</span>
                    <input
                      name="personalReminderTitle"
                      type="text"
                      maxlength="120"
                      placeholder="Что нужно сделать"
                      [ngModel]="reminderDraft().title"
                      (ngModelChange)="setReminderDraftField('title', $event)"
                      [disabled]="reminderSaving()"
                    >
                  </label>

                  <label class="sheet-field">
                    <span>Текст</span>
                    <textarea
                      name="personalReminderText"
                      rows="3"
                      maxlength="1000"
                      placeholder="Подробности"
                      [ngModel]="reminderDraft().text"
                      (ngModelChange)="setReminderDraftField('text', $event)"
                      [disabled]="reminderSaving()"
                    ></textarea>
                  </label>

                  <div class="reminder-mode-row" role="group" aria-label="Тип напоминания">
                    <button type="button" [class.active]="reminderDraft().reminderMode === 'none'" (click)="setReminderMode('none')" [disabled]="reminderSaving()" aria-label="Без времени">
                      <span class="material-icons-sharp">sticky_note_2</span>
                    </button>
                    <button type="button" [class.active]="reminderDraft().reminderMode === 'datetime'" (click)="setReminderMode('datetime')" [disabled]="reminderSaving()" aria-label="Дата и время">
                      <span class="material-icons-sharp">event</span>
                    </button>
                    <button type="button" [class.active]="reminderDraft().reminderMode === 'timer'" (click)="setReminderMode('timer')" [disabled]="reminderSaving()" aria-label="Таймер">
                      <span class="material-icons-sharp">timer</span>
                    </button>
                  </div>

                  @if (reminderDraft().reminderMode === 'datetime') {
                    <label class="sheet-field">
                      <span>Дата</span>
                      <input
                        name="personalReminderDate"
                        type="datetime-local"
                        [ngModel]="reminderDraft().remindAtLocal"
                        (ngModelChange)="setReminderDraftField('remindAtLocal', $event)"
                        [disabled]="reminderSaving()"
                      >
                    </label>
                  }

                  @if (reminderDraft().reminderMode === 'timer') {
                    <label class="sheet-field">
                      <span>Минут</span>
                      <input
                        name="personalReminderTimer"
                        type="number"
                        min="1"
                        max="10080"
                        [ngModel]="reminderDraft().timerMinutes"
                        (ngModelChange)="setReminderDraftField('timerMinutes', $event)"
                        [disabled]="reminderSaving()"
                      >
                    </label>
                  }

                  <div class="sheet-actions">
                    <button class="secondary" type="button" (click)="cancelReminderCreate()" [disabled]="reminderSaving()">Отмена</button>
                    <button type="submit" [disabled]="!canSaveReminder()">
                      {{ reminderSaving() ? 'Сохраняю' : (reminderEditingId() ? 'Сохранить' : 'Создать') }}
                    </button>
                  </div>
                </form>
              }

              @if (reminderLoading()) {
                <p class="sheet-hint">Загружаю напоминания...</p>
              } @else {
                <div class="mobile-reminder-list">
                  @for (reminder of activeReminders(); track reminder.id) {
                    <article class="mobile-reminder-card" [class.due]="isReminderDue(reminder)" [class.expanded]="isReminderExpanded(reminder)">
                      <span class="material-icons-sharp reminder-card-icon">{{ reminderIcon(reminder) }}</span>
                      <button class="mobile-reminder-body" type="button" (click)="toggleReminder(reminder)">
                        <strong>{{ reminder.title || 'Без названия' }}</strong>
                        <span>{{ reminderText(reminder) }}</span>
                        <small>{{ reminderTimeLabel(reminder) }}</small>
                      </button>
                      <div class="mobile-reminder-actions">
                        @if (canEditReminder(reminder)) {
                          <button type="button" (click)="startReminderEdit(reminder)" [disabled]="reminderMutatingId() === reminder.id" aria-label="Редактировать">
                            <span class="material-icons-sharp">edit</span>
                          </button>
                        }
                        <button type="button" (click)="completeReminder(reminder)" [disabled]="reminderMutatingId() === reminder.id" aria-label="Готово">
                          <span class="material-icons-sharp">done</span>
                        </button>
                        <button class="danger" type="button" (click)="deleteReminder(reminder)" [disabled]="reminderMutatingId() === reminder.id" aria-label="Удалить">
                          <span class="material-icons-sharp">delete</span>
                        </button>
                      </div>
                    </article>
                  } @empty {
                    <article class="personal-reminders-empty">
                      <span class="material-icons-sharp">edit_note</span>
                      <p>Нет напоминаний</p>
                    </article>
                  }
                </div>
              }
            </section>
          </main>
        </ng-template>
      </ion-modal>

      <ion-modal class="sheet-modal lead-edit-sheet" [isOpen]="editDraft() !== null" (didDismiss)="closeEditLead()">
        <ng-template>
          @if (editDraft(); as draft) {
            <form class="sheet-body sheet-form" (ngSubmit)="saveLeadEdit()">
              <div class="sheet-head">
                <div>
                  <p class="sheet-note">Лид #{{ editLead()?.id }}</p>
                  <h2>Редактирование</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeEditLead()" [disabled]="editSaving() || deleteSaving()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </div>

              <section class="sheet-form-content">
                @if (editError()) {
                  <p class="sheet-error">{{ editError() }}</p>
                }

                <label class="sheet-field">
                  <span>Телефон</span>
                  <input
                    name="editTelephoneLead"
                    type="tel"
                    required
                    [ngModel]="draft.telephoneLead"
                    (ngModelChange)="setEditField('telephoneLead', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Наименование</span>
                  <input
                    name="editCompanyName"
                    type="text"
                    [ngModel]="draft.companyName"
                    (ngModelChange)="setEditField('companyName', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Город</span>
                  <input
                    name="editCityLead"
                    type="text"
                    required
                    [ngModel]="draft.cityLead"
                    (ngModelChange)="setEditField('cityLead', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Регион</span>
                  <input
                    name="editRegion"
                    type="text"
                    [ngModel]="draft.region"
                    (ngModelChange)="setEditField('region', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Телефоны</span>
                  <input
                    name="editPhones"
                    type="text"
                    [ngModel]="draft.phones"
                    (ngModelChange)="setEditField('phones', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Мобильные</span>
                  <input
                    name="editMobilePhones"
                    type="text"
                    [ngModel]="draft.mobilePhones"
                    (ngModelChange)="setEditField('mobilePhones', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>WhatsApp</span>
                  <input
                    name="editWhatsappPhones"
                    type="text"
                    [ngModel]="draft.whatsappPhones"
                    (ngModelChange)="setEditField('whatsappPhones', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Емейлы</span>
                  <input
                    name="editEmails"
                    type="text"
                    [ngModel]="draft.emails"
                    (ngModelChange)="setEditField('emails', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Сайты</span>
                  <input
                    name="editWebsites"
                    type="text"
                    [ngModel]="draft.websites"
                    (ngModelChange)="setEditField('websites', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>VK</span>
                  <input
                    name="editVkUrl"
                    type="text"
                    [ngModel]="draft.vkUrl"
                    (ngModelChange)="setEditField('vkUrl', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>TG</span>
                  <input
                    name="editTelegramUrl"
                    type="text"
                    [ngModel]="draft.telegramUrl"
                    (ngModelChange)="setEditField('telegramUrl', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Отрасли</span>
                  <input
                    name="editIndustries"
                    type="text"
                    [ngModel]="draft.industries"
                    (ngModelChange)="setEditField('industries', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Тип</span>
                  <input
                    name="editCompanyType"
                    type="text"
                    [ngModel]="draft.companyType"
                    (ngModelChange)="setEditField('companyType', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Адрес</span>
                  <input
                    name="editAddress"
                    type="text"
                    [ngModel]="draft.address"
                    (ngModelChange)="setEditField('address', $event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>Статус</span>
                  <select
                    name="editStatus"
                    required
                    [ngModel]="draft.lidStatus"
                    (ngModelChange)="setEditField('lidStatus', $event)"
                  >
                    @for (status of statusOptions(); track status) {
                      <option [ngValue]="status">{{ status }}</option>
                    }
                  </select>
                </label>

                <label class="sheet-field">
                  <span>Оператор</span>
                  <select
                    name="editOperatorId"
                    [ngModel]="draft.operatorId"
                    (ngModelChange)="setEditField('operatorId', $event)"
                  >
                    <option [ngValue]="null">Не выбран</option>
                    @for (operator of operatorOptions(); track operator.id) {
                      <option [ngValue]="operator.id">{{ optionLabel(operator) }}</option>
                    }
                  </select>
                </label>

                <label class="sheet-field">
                  <span>Менеджер</span>
                  <select
                    name="editManagerId"
                    [ngModel]="draft.managerId"
                    (ngModelChange)="setEditField('managerId', $event)"
                  >
                    <option [ngValue]="null">Не выбран</option>
                    @for (manager of managerOptions(); track manager.id) {
                      <option [ngValue]="manager.id">{{ optionLabel(manager) }}</option>
                    }
                  </select>
                </label>

                <label class="sheet-field">
                  <span>Маркетолог</span>
                  <select
                    name="editMarketologId"
                    [ngModel]="draft.marketologId"
                    (ngModelChange)="setEditField('marketologId', $event)"
                  >
                    <option [ngValue]="null">Не выбран</option>
                    @for (marketolog of marketologOptions(); track marketolog.id) {
                      <option [ngValue]="marketolog.id">{{ optionLabel(marketolog) }}</option>
                    }
                  </select>
                </label>

                @if (canEditLeadTelephone()) {
                  <label class="sheet-field">
                    <span>ID телефона</span>
                    <input
                      name="editTelephoneId"
                      type="number"
                      min="1"
                      placeholder="Не выбран"
                      [ngModel]="draft.telephoneId"
                      (ngModelChange)="setEditTelephoneId($event)"
                    >
                  </label>
                }

                <label class="sheet-field">
                  <span>Комментарий</span>
                  <textarea
                    name="editCommentsLead"
                    rows="3"
                    [ngModel]="draft.commentsLead"
                    (ngModelChange)="setEditField('commentsLead', $event)"
                  ></textarea>
                </label>
              </section>

              <div class="sheet-actions edit-actions">
                @if (canDeleteLead()) {
                  <button class="danger" type="button" (click)="deleteCurrentLead()" [disabled]="editSaving() || deleteSaving()">
                    {{ deleteSaving() ? 'Удаляю' : 'Удалить' }}
                  </button>
                }
                <button class="secondary" type="button" (click)="closeEditLead()" [disabled]="editSaving() || deleteSaving()">Отмена</button>
                <button type="submit" [disabled]="editSaving() || deleteSaving() || !canSaveEdit()">
                  {{ editSaving() ? 'Сохраняю' : 'Сохранить' }}
                </button>
              </div>
            </form>
          }
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
                    readonly
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
                    readonly
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
    </div>
  `,
  styles: [`
    ion-content { --overflow: hidden; }

    .leads-page {
      display: flex;
      flex-direction: column;
      gap: var(--otziv-page-gap, 0.65rem);
      height: 100%;
      max-width: 760px;
      margin: 0 auto;
      padding: var(--otziv-page-padding-y, 0.75rem) var(--otziv-page-padding-x, 0.75rem) calc(var(--otziv-page-padding-bottom, 0.75rem) + env(safe-area-inset-bottom));
      overflow: hidden;
    }

    .lead-hero,
    .lead-bottom-controls,
    .lead-card,
    .metric-tile,
    .inline-alert {
      border: 1px solid rgba(103, 116, 131, 0.18);
      background: var(--otziv-white);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
    }

    .lead-hero {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      flex: 0 0 auto;
      min-height: 5.2rem;
      border-radius: 1rem;
      padding: 0.9rem;
    }

    .hero-kicker,
    .detail-grid small,
    .sheet-lead small {
      color: var(--otziv-info);
      font-size: 0.72rem;
      font-weight: 800;
    }

    h1, h2, p { margin: 0; }
    h1 { font-size: 1.35rem; line-height: 1.05; }
    h2 { font-size: 1.25rem; line-height: 1.1; }
    .lead-hero p { margin-top: 0.25rem; color: var(--otziv-info); font-size: 0.78rem; }

    .lead-business-stack {
      display: grid;
      gap: 0.18rem;
      min-width: 0;
    }

    .lead-business-line {
      display: -webkit-box;
      overflow: hidden;
      border-radius: 0.72rem;
      padding: 0.3rem 0.5rem;
      color: var(--otziv-info);
      background: rgba(108, 155, 207, 0.12);
      font-size: 0.64rem;
      font-weight: 800;
      line-height: 1.16;
      overflow-wrap: anywhere;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    .lead-business-line--category {
      -webkit-line-clamp: 3;
    }

    .lead-business-line--address {
      background: rgba(27, 156, 133, 0.1);
    }

    .icon-button {
      display: grid;
      place-items: center;
      width: 2.55rem;
      height: 2.55rem;
      border: 0;
      border-radius: 0.8rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
      flex: 0 0 auto;
    }

    .inline-alert {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      flex: 0 0 auto;
      min-width: 0;
      border-color: rgba(255, 0, 96, 0.24);
      border-radius: 0.85rem;
      padding: 0.65rem 0.75rem;
      color: #9a2737;
      background: rgba(255, 0, 96, 0.06);
      font: inherit;
      font-size: 0.78rem;
      font-weight: 800;
      text-align: left;
    }

    .lead-create-inline-button {
      border-color: rgba(108, 155, 207, 0.28) !important;
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.2), rgba(255, 255, 255, 0.96)) !important;
      color: var(--otziv-primary) !important;
    }

    .lead-list {
      flex: 1 1 0;
      min-height: 0;
    }

    .lead-search-strip {
      display: grid;
      grid-template-columns: minmax(0, 1fr) repeat(4, auto);
      align-items: center;
      gap: 0.45rem;
      flex: 0 0 auto;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      padding: 0.48rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
    }

    .lead-search-strip label {
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

    .lead-search-strip input {
      min-width: 0;
      border: 0;
      outline: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: 900 0.82rem/1 var(--otziv-font-family);
    }

    .lead-search-strip input::placeholder {
      color: var(--otziv-info);
      opacity: 1;
    }

    .lead-search-strip .material-icons-sharp {
      font-size: 1.12rem;
    }

    .lead-search-strip .icon-button {
      width: 2.42rem;
      height: 2.42rem;
      min-width: 2.42rem;
      border: 0;
      border-radius: 0.82rem;
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
    }

    .lead-search-strip button:disabled {
      opacity: 0.52;
    }

    .import-file-picker {
      position: relative;
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: 0.18rem 0.65rem;
      align-items: center;
      border: 1px dashed rgba(116, 154, 207, 0.38);
      border-radius: 0.95rem;
      padding: 0.75rem;
      background: linear-gradient(145deg, rgba(237, 246, 255, 0.9), var(--otziv-white));
    }

    .import-file-picker > .material-icons-sharp {
      grid-row: 1 / 3;
      display: grid;
      place-items: center;
      width: 2.2rem;
      height: 2.2rem;
      border-radius: 0.72rem;
      color: var(--otziv-primary);
      background: rgba(116, 154, 207, 0.14);
    }

    .import-file-picker strong,
    .import-assignment-card > span,
    .import-result-card strong {
      color: var(--otziv-dark);
      font-size: 0.85rem;
      font-weight: 1000;
    }

    .import-file-picker small,
    .import-result-card span,
    .import-result-list small {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
    }

    .import-file-picker input {
      position: absolute;
      inset: 0;
      opacity: 0;
    }

    .import-assignment-card,
    .import-result-card {
      display: grid;
      gap: 0.55rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.95rem;
      padding: 0.75rem;
      background: var(--otziv-white);
    }

    .import-manager-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.45rem;
    }

    .import-manager-grid button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.25rem;
      min-height: 2.1rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.68rem/1 var(--otziv-font-family);
    }

    .import-manager-grid button.active {
      border-color: rgba(74, 198, 177, 0.4);
      color: #16735f;
      background: rgba(74, 198, 177, 0.12);
    }

    .import-result-list {
      display: flex;
      flex-direction: column;
      gap: 0.18rem;
    }

    .import-result-list.danger small {
      color: var(--otziv-danger);
    }

  `]
})
export class LeadsPage implements OnInit, OnDestroy {
  private readonly commentSaveTimers = new Map<number, ReturnType<typeof setTimeout>>();
  private readonly commentSaveVersions = new Map<number, number>();
  private searchTimer?: ReturnType<typeof setTimeout>;
  private routeSubscription?: Subscription;
  private lastMobileNavKey = '';

  private readonly emptyPage: Page<LeadItem> = {
    content: [],
    number: 0,
    pageNumber: 0,
    size: 10,
    pageSize: 10,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true
  };

  readonly pageSizeOptions = [5, 10, 12, 15];

  readonly buckets = LEAD_BUCKETS;

  readonly board = signal<LeadBoard | null>(null);
  readonly activeBucket = signal<LeadBucketKey>('newLeads');
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);
  readonly keyword = signal('');
  readonly appliedKeyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly selectedLead = signal<LeadItem | null>(null);
  readonly listExpanded = signal(false);
  readonly bucketSheetOpen = signal(false);
  readonly filterSheetOpen = signal(false);
  readonly mutationKey = signal<string | null>(null);
  readonly copiedLeadId = signal<number | null>(null);
  readonly commentDrafts = signal<Record<number, string>>({});
  readonly commentSaveStates = signal<Record<number, LeadCommentSaveState>>({});
  readonly editOptions = signal<LeadEditOptions | null>(null);
  readonly createDraft = signal<LeadCreateRequest | null>(null);
  readonly createSaving = signal(false);
  readonly createError = signal<string | null>(null);
  readonly leadImportOpen = signal(false);
  readonly leadImportFile = signal<File | null>(null);
  readonly leadImportManagerIds = signal<number[]>([]);
  readonly leadImportOperatorId = signal<number | null>(null);
  readonly leadImportMarketologId = signal<number | null>(null);
  readonly leadImportSaving = signal(false);
  readonly leadImportError = signal<string | null>(null);
  readonly leadImportResult = signal<LeadImportResponse | null>(null);
  readonly editLead = signal<LeadItem | null>(null);
  readonly editDraft = signal<LeadUpdateRequest | null>(null);
  readonly editSaving = signal(false);
  readonly deleteSaving = signal(false);
  readonly editError = signal<string | null>(null);
  readonly contactLead = signal<LeadItem | null>(null);
  readonly companyLead = signal<LeadItem | null>(null);
  readonly companyPayload = signal<CompanyCreatePayload | null>(null);
  readonly companyDraft = signal<CompanyCreateDraft | null>(null);
  readonly companySubCategories = signal<CompanyCreateOption[]>([]);
  readonly companyLoading = signal(false);
  readonly companySaving = signal(false);
  readonly companyError = signal<string | null>(null);
  readonly reminders = signal<PersonalReminder[]>([]);
  readonly reminderSheetOpen = signal(false);
  readonly reminderFormOpen = signal(false);
  readonly reminderEditingId = signal<number | null>(null);
  readonly reminderDraft = signal<PersonalReminderDraft>(this.emptyReminderDraft());
  readonly reminderLoading = signal(false);
  readonly reminderSaving = signal(false);
  readonly reminderError = signal<string | null>(null);
  readonly reminderMutatingId = signal<number | null>(null);
  readonly expandedReminderId = signal<number | null>(null);

  readonly canSeeAllBucket = computed(() => canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.leads, MOBILE_ACTIONS.manage));
  readonly canCreateLead = computed(() => canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.leads, MOBILE_ACTIONS.create));
  readonly canEditLead = computed(() => canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.leads, MOBILE_ACTIONS.edit));
  readonly canCreateCompany = computed(() => canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.companies, MOBILE_ACTIONS.create));
  readonly canDeleteLead = computed(() => canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.leads, MOBILE_ACTIONS.delete));
  readonly canImportLeads = computed(() => canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.leads, MOBILE_ACTIONS.import));
  readonly canChooseCreateManager = computed(() => canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.leads, MOBILE_ACTIONS.assign));
  readonly canEditLeadTelephone = computed(() => canUseAction(this.auth.user()?.roles, MOBILE_SECTIONS.leads, MOBILE_ACTIONS.assign));
  readonly canUsePrivilegedLeadActions = computed(() => this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MARKETOLOG']));
  readonly visibleBuckets = computed(() => this.buckets.filter((bucket) => !bucket.adminOnly || this.canSeeAllBucket()));
  readonly statusItems = computed<MobileStatusItem[]>(() =>
    this.visibleBuckets().map((bucket) => ({
      key: bucket.key,
      title: bucket.short,
      value: this.bucketTotal(bucket.key),
      icon: bucket.icon,
      tone: bucket.tone
    }))
  );
  readonly bucketMenuOptions = computed(() => {
    const visible = this.visibleBuckets();
    const allBucket = visible.find((bucket) => bucket.key === 'all');
    const rest = visible.filter((bucket) => bucket.key !== 'all');
    return allBucket ? [allBucket, ...rest] : rest;
  });
  readonly activeBucketInfo = computed(() => (
    this.visibleBuckets().find((bucket) => bucket.key === this.activeBucket()) ?? this.visibleBuckets()[0] ?? this.buckets[0]
  ));
  readonly currentPage = computed(() => this.pageFor(this.activeBucket()));
  readonly visibleLeads = computed(() => this.currentPage().content ?? []);
  readonly previewLeads = computed(() => this.visibleLeads().slice(0, 3));
  readonly leadSheetOpen = computed(() => this.selectedLead() !== null);
  readonly sortTitle = computed(() => mobileSortTitle(this.sortDirection(), {
    desc: 'давно без изменений',
    asc: 'недавно измененные'
  }));
  readonly heroMeta = computed(() => {
    const keyword = this.appliedKeyword();
    return `${this.leadCountLabel(this.currentPage().totalElements)} · ${this.sortTitle()}${keyword ? ` · ${keyword}` : ''}`;
  });
  readonly pageLabel = computed(() => mobilePageLabel(this.currentPage(), this.pageNumber()));
  readonly isFirstPage = computed(() => mobilePageIsFirst(this.currentPage(), this.pageNumber()));
  readonly isLastPage = computed(() => mobilePageIsLast(this.currentPage(), this.pageNumber()));
  readonly operatorOptions = computed(() => this.editOptions()?.operators ?? []);
  readonly managerOptions = computed(() => this.editOptions()?.managers ?? []);
  readonly marketologOptions = computed(() => this.editOptions()?.marketologs ?? []);
  readonly companyCreateOpen = computed(() => this.companyLead() !== null);
  readonly companyManagerOptions = computed(() => this.companyPayload()?.managers ?? []);
  readonly companyWorkerOptions = computed(() => this.companyPayload()?.workers ?? []);
  readonly companyCategoryOptions = computed(() => this.companyPayload()?.categories ?? []);
  readonly companySubCategoryOptions = computed(() => this.companySubCategories());
  readonly companyCityOptions = computed(() => this.companyPayload()?.cities ?? []);
  readonly activeReminders = computed(() => this.sortReminders(this.reminders().filter((reminder) => !reminder.completedAt)));
  readonly activeReminderCount = computed(() => this.activeReminders().length);
  readonly statusOptions = computed(() => {
    const statuses = this.editOptions()?.statuses ?? this.board()?.statuses ?? [];
    const current = this.editDraft()?.lidStatus;
    return current && !statuses.includes(current) ? [current, ...statuses] : statuses;
  });

  constructor(
    private readonly api: ApiService,
    private readonly auth: AuthService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    private readonly confirm: MobileConfirmService,
    private readonly externalLink: MobileExternalLinkService
  ) {}

  ngOnInit(): void {
    this.applyMobileNavIntent(false);
    this.lastMobileNavKey = this.mobileNavKey();
    void this.loadBoard();
    void this.loadReminders();
    this.routeSubscription = this.route.queryParamMap.subscribe((params) => {
      const nextKey = this.mobileNavKey(params);
      if (nextKey === this.lastMobileNavKey) {
        return;
      }

      this.lastMobileNavKey = nextKey;
      this.applyMobileNavIntent(true, params);
    });
  }

  ionViewWillEnter(): void {
    this.applyMobileNavIntent(false);
  }

  ngOnDestroy(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.commentSaveTimers.forEach((timer) => clearTimeout(timer));
    this.commentSaveTimers.clear();
    this.routeSubscription?.unsubscribe();
  }

  reload(): void {
    void this.loadBoard();
  }

  openFilterSheet(): void {
    this.selectedLead.set(null);
    this.filterSheetOpen.set(true);
  }

  closeFilterSheet(): void {
    this.filterSheetOpen.set(false);
  }

  openBucketSheet(): void {
    this.bucketSheetOpen.set(true);
  }

  closeBucketSheet(): void {
    this.bucketSheetOpen.set(false);
  }

  openAllBucket(load = true): void {
    const fallback = this.visibleBuckets()[0]?.key ?? 'newLeads';
    this.activeBucket.set(this.canSeeAllBucket() ? 'all' : fallback);
    this.selectedLead.set(null);
    this.listExpanded.set(false);
    this.pageNumber.set(0);
    this.bucketSheetOpen.set(false);
    if (load) {
      void this.loadBoard();
    }
  }

  setPageSize(value: number | string): void {
    this.pageSize.set(Number(value));
  }

  setSortDirection(value: 'desc' | 'asc' | string): void {
    this.sortDirection.set(value === 'asc' ? 'asc' : 'desc');
  }

  setKeyword(value: string): void {
    this.keyword.set(value);
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }

    this.searchTimer = setTimeout(() => this.applySearch(), 450);
  }

  applySearch(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = undefined;
    }

    this.appliedKeyword.set(this.keyword().trim());
    this.pageNumber.set(0);
    void this.loadBoard().then(() => this.resetListScroll());
  }

  clearSearch(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = undefined;
    }

    this.keyword.set('');
    this.appliedKeyword.set('');
    this.pageNumber.set(0);
    void this.loadBoard().then(() => this.resetListScroll());
  }

  openArchiveBucket(): void {
    this.setBucket('archive');
  }

  toggleSortDirection(): void {
    this.sortDirection.set(this.sortDirection() === 'desc' ? 'asc' : 'desc');
    this.pageNumber.set(0);
    void this.loadBoard().then(() => this.resetListScroll());
  }

  applyFilters(): void {
    this.filterSheetOpen.set(false);
    this.applySearch();
  }

  clearFilters(): void {
    this.keyword.set('');
    this.appliedKeyword.set('');
    this.pageNumber.set(0);
    this.pageSize.set(10);
    this.sortDirection.set('desc');
    this.filterSheetOpen.set(false);
    void this.loadBoard();
  }

  setBucket(bucket: LeadBucketKey): void {
    this.activeBucket.set(bucket);
    this.listExpanded.set(false);
    this.pageNumber.set(0);
    void this.loadBoard();
  }

  selectStatusSliderBucket(bucket: string): void {
    this.setBucket(bucket as LeadBucketKey);
  }

  selectBucket(bucket: LeadBucketKey): void {
    if (this.activeBucket() === bucket) {
      this.closeBucketSheet();
      return;
    }

    this.setBucket(bucket);
    this.closeBucketSheet();
  }

  private applyMobileNavIntent(load: boolean, params: ParamMap = this.route.snapshot.queryParamMap): boolean {
    const intent = params.get('mobileNav');
    if (intent === 'menu') {
      this.bucketSheetOpen.set(true);
      return false;
    }

    if (intent === 'all') {
      this.openAllBucket(load);
      return load;
    }

    return false;
  }

  private mobileNavKey(params: ParamMap = this.route.snapshot.queryParamMap): string {
    return `${params.get('mobileNav') ?? ''}:${params.get('navTs') ?? ''}`;
  }

  previousPage(): void {
    if (this.isFirstPage()) {
      return;
    }

    this.pageNumber.update((page) => Math.max(page - 1, 0));
    void this.loadBoard();
  }

  nextPage(): void {
    if (this.isLastPage()) {
      return;
    }

    this.pageNumber.update((page) => page + 1);
    void this.loadBoard();
  }

  bucketTotal(bucket: LeadBucketKey): number {
    return this.pageFor(bucket).totalElements ?? 0;
  }

  leadCountLabel(value: number): string {
    const count = Math.abs(value);
    const mod10 = count % 10;
    const mod100 = count % 100;
    const word = mod10 === 1 && mod100 !== 11
      ? 'лид'
      : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)
        ? 'лида'
        : 'лидов';
    return `${value} ${word}`;
  }

  commentFor(lead: LeadItem): string {
    return this.commentDrafts()[lead.id] ?? lead.commentsLead ?? '';
  }

  commentSaveState(lead: LeadItem): LeadCommentSaveState {
    return this.commentSaveStates()[lead.id] ?? 'idle';
  }

  commentSaveLabel(lead: LeadItem): string {
    return {
      idle: '',
      saving: 'сохранение',
      saved: 'сохранено',
      error: 'ошибка'
    }[this.commentSaveState(lead)];
  }

  setLeadComment(lead: LeadItem, value: string): void {
    this.commentDrafts.update((drafts) => ({
      ...drafts,
      [lead.id]: value
    }));

    if (!this.canEditLead()) {
      return;
    }

    const existingTimer = this.commentSaveTimers.get(lead.id);
    if (existingTimer) {
      clearTimeout(existingTimer);
    }

    this.setCommentSaveState(lead.id, 'idle');
    this.commentSaveTimers.set(lead.id, setTimeout(() => {
      void this.saveLeadCommentNow(lead);
    }, 700));
  }

  async saveLeadCommentNow(lead: LeadItem): Promise<void> {
    if (!this.canEditLead()) {
      return;
    }

    const timer = this.commentSaveTimers.get(lead.id);
    if (timer) {
      clearTimeout(timer);
      this.commentSaveTimers.delete(lead.id);
    }

    const commentsLead = this.commentFor(lead).trim();
    if (commentsLead === (lead.commentsLead ?? '').trim()) {
      this.setCommentSaveState(lead.id, 'idle');
      return;
    }

    const version = (this.commentSaveVersions.get(lead.id) ?? 0) + 1;
    this.commentSaveVersions.set(lead.id, version);
    this.setCommentSaveState(lead.id, 'saving');

    try {
      const updatedLead = await firstValueFrom(this.api.updateLead(lead.id, this.commentUpdateRequest(lead, commentsLead)));
      if (this.commentSaveVersions.get(lead.id) !== version) {
        return;
      }

      this.applyUpdatedLead(updatedLead);
      this.setCommentSaveState(lead.id, 'saved');
      setTimeout(() => {
        if (this.commentSaveState(updatedLead) === 'saved') {
          this.setCommentSaveState(updatedLead.id, 'idle');
        }
      }, 1200);
    } catch (error) {
      if (this.commentSaveVersions.get(lead.id) !== version) {
        return;
      }

      this.setCommentSaveState(lead.id, 'error');
      this.error.set(error instanceof Error ? error.message : 'Комментарий не сохранился.');
    }
  }

  openLead(lead: LeadItem): void {
    this.selectedLead.set(lead);
  }

  closeLeadSheet(): void {
    this.selectedLead.set(null);
  }

  openContactMenu(lead: LeadItem): void {
    this.contactLead.set(lead);
  }

  closeContactMenu(): void {
    this.contactLead.set(null);
  }

  toggleListExpanded(): void {
    this.toggleSortDirection();
  }

  private resetListScroll(): void {
    window.setTimeout(() => {
      document.querySelector<HTMLElement>('app-leads .lead-list')?.scrollTo({ left: 0, top: 0, behavior: 'auto' });
    });
  }

  openCreateLead(): void {
    this.selectedLead.set(null);
    this.createDraft.set({
      telephoneLead: '',
      companyName: '',
      phones: '',
      mobilePhones: '',
      whatsappPhones: '',
      emails: '',
      websites: '',
      vkUrl: '',
      telegramUrl: '',
      industries: '',
      companyType: '',
      region: '',
      address: '',
      cityLead: '',
      commentsLead: '',
      managerId: null
    });
    this.createError.set(null);

    if (this.canChooseCreateManager()) {
      void this.loadEditOptions(() => {
        const managerId = this.managerOptions()[0]?.id ?? null;
        if (managerId !== null) {
          this.setCreateField('managerId', managerId);
        }
      }, (message) => this.createError.set(message));
    }
  }

  closeCreateLead(): void {
    if (this.createSaving()) {
      return;
    }

    this.createDraft.set(null);
    this.createError.set(null);
  }

  setCreateField<K extends keyof LeadCreateRequest>(field: K, value: LeadCreateRequest[K]): void {
    this.createDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  canSaveCreate(): boolean {
    const draft = this.createDraft();
    return Boolean(draft?.telephoneLead.trim() && draft.cityLead.trim());
  }

  async saveNewLead(): Promise<void> {
    const draft = this.createDraft();
    if (!draft || !this.canSaveCreate()) {
      this.createError.set('Телефон и город обязательны.');
      return;
    }

    this.createSaving.set(true);
    this.createError.set(null);

    try {
      await firstValueFrom(this.api.createLead({
        telephoneLead: draft.telephoneLead.trim(),
        companyName: this.cleanText(draft.companyName),
        phones: this.cleanText(draft.phones),
        mobilePhones: this.cleanText(draft.mobilePhones),
        whatsappPhones: this.cleanText(draft.whatsappPhones),
        emails: this.cleanText(draft.emails),
        websites: this.cleanText(draft.websites),
        vkUrl: this.cleanText(draft.vkUrl),
        telegramUrl: this.cleanText(draft.telegramUrl),
        industries: this.cleanText(draft.industries),
        companyType: this.cleanText(draft.companyType),
        region: this.cleanText(draft.region),
        address: this.cleanText(draft.address),
        cityLead: draft.cityLead.trim(),
        commentsLead: draft.commentsLead?.trim(),
        managerId: this.canChooseCreateManager() ? draft.managerId ?? null : null
      }));
      this.createDraft.set(null);
      this.activeBucket.set('newLeads');
      this.pageNumber.set(0);
      await this.loadBoard();
    } catch (error) {
      this.createError.set(error instanceof Error ? error.message : 'Не удалось создать лид.');
    } finally {
      this.createSaving.set(false);
    }
  }

  openLeadImport(): void {
    if (!this.canImportLeads()) {
      return;
    }

    this.leadImportOpen.set(true);
    this.leadImportFile.set(null);
    this.leadImportError.set(null);
    this.leadImportResult.set(null);
    void this.loadEditOptions(
      () => this.setDefaultImportManagers(),
      (message) => this.leadImportError.set(message)
    );
  }

  closeLeadImport(): void {
    if (this.leadImportSaving()) {
      return;
    }

    this.leadImportOpen.set(false);
    this.leadImportFile.set(null);
    this.leadImportError.set(null);
  }

  selectLeadImportFile(event: Event): void {
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0] ?? null;
    this.leadImportFile.set(file);
    this.leadImportResult.set(null);
    this.leadImportError.set(file ? null : 'Выберите файл для импорта.');
  }

  toggleImportManager(managerId: number): void {
    this.leadImportManagerIds.update((ids) => ids.includes(managerId)
      ? ids.filter((id) => id !== managerId)
      : [...ids, managerId]
    );
    this.leadImportResult.set(null);
  }

  isImportManagerSelected(managerId: number): boolean {
    return this.leadImportManagerIds().includes(managerId);
  }

  async uploadLeadImport(): Promise<void> {
    const file = this.leadImportFile();
    const managerIds = this.leadImportManagerIds();
    if (!file) {
      this.leadImportError.set('Выберите файл для импорта.');
      return;
    }
    if (!managerIds.length) {
      this.leadImportError.set('Выберите хотя бы одного менеджера.');
      return;
    }

    this.leadImportSaving.set(true);
    this.leadImportError.set(null);
    this.leadImportResult.set(null);

    try {
      const result = await firstValueFrom(this.api.importLeads({
        file,
        managerIds,
        operatorId: this.leadImportOperatorId(),
        marketologId: this.leadImportMarketologId()
      }));
      this.leadImportResult.set(result);
      this.activeBucket.set('newLeads');
      this.pageNumber.set(0);
      await this.loadBoard();
    } catch (error) {
      this.leadImportError.set(error instanceof Error ? error.message : 'Не удалось импортировать лиды.');
    } finally {
      this.leadImportSaving.set(false);
    }
  }

  leadImportResultMessage(result: LeadImportResponse): string {
    return `Добавлено ${result.added}, пропущено ${result.skippedDuplicates + result.skippedWithoutPhones + result.skippedInvalid}`;
  }

  openReminderSheet(): void {
    this.selectedLead.set(null);
    this.reminderSheetOpen.set(true);
    this.reminderError.set(null);
    void this.loadReminders(true);
  }

  closeReminderSheet(): void {
    if (this.reminderSaving() || this.reminderMutatingId()) {
      return;
    }

    this.reminderSheetOpen.set(false);
    this.reminderFormOpen.set(false);
    this.reminderEditingId.set(null);
    this.expandedReminderId.set(null);
    this.reminderError.set(null);
  }

  openReminderCreate(): void {
    this.reminderEditingId.set(null);
    this.reminderDraft.set(this.emptyReminderDraft());
    this.reminderFormOpen.set(true);
    this.reminderError.set(null);
  }

  startReminderEdit(reminder: PersonalReminder): void {
    if (!this.canEditReminder(reminder)) {
      return;
    }

    this.reminderEditingId.set(reminder.id);
    this.reminderDraft.set(this.reminderDraftFromReminder(reminder));
    this.expandedReminderId.set(null);
    this.reminderFormOpen.set(true);
    this.reminderError.set(null);
  }

  cancelReminderCreate(): void {
    if (this.reminderSaving()) {
      return;
    }

    this.reminderFormOpen.set(false);
    this.reminderEditingId.set(null);
    this.reminderDraft.set(this.emptyReminderDraft());
  }

  setReminderDraftField<K extends keyof PersonalReminderDraft>(field: K, value: PersonalReminderDraft[K]): void {
    this.reminderDraft.update((draft) => ({ ...draft, [field]: value }));
  }

  setReminderMode(mode: PersonalReminderMode): void {
    this.reminderDraft.update((draft) => ({
      ...draft,
      reminderMode: mode,
      remindAtLocal: mode === 'datetime' && !draft.remindAtLocal ? this.localInputAfterMinutes(60) : draft.remindAtLocal,
      timerMinutes: mode === 'timer' && !draft.timerMinutes ? 30 : draft.timerMinutes
    }));
  }

  canSaveReminder(): boolean {
    const draft = this.reminderDraft();
    const hasContent = Boolean(draft.title.trim() || draft.text.trim());

    if (!hasContent || this.reminderSaving()) {
      return false;
    }

    if (draft.reminderMode === 'datetime') {
      return Boolean(draft.remindAtLocal);
    }

    if (draft.reminderMode === 'timer') {
      return Number(draft.timerMinutes) > 0;
    }

    return true;
  }

  async saveReminder(): Promise<void> {
    if (!this.canSaveReminder()) {
      this.reminderError.set('Заполните заметку и время напоминания.');
      return;
    }

    this.reminderSaving.set(true);
    this.reminderError.set(null);

    try {
      const request = this.reminderRequestFromDraft(this.reminderDraft());
      const editingId = this.reminderEditingId();
      const reminder = editingId
        ? await firstValueFrom(this.api.updatePersonalReminder(editingId, request))
        : await firstValueFrom(this.api.createPersonalReminder(request));
      this.reminders.update((reminders) => editingId
        ? reminders.map((item) => item.id === editingId ? reminder : item)
        : [reminder, ...reminders]
      );
      this.reminderFormOpen.set(false);
      this.reminderEditingId.set(null);
      this.reminderDraft.set(this.emptyReminderDraft());
    } catch (error) {
      this.reminderError.set(error instanceof Error ? error.message : 'Не удалось сохранить напоминание.');
    } finally {
      this.reminderSaving.set(false);
    }
  }

  async completeReminder(reminder: PersonalReminder): Promise<void> {
    this.reminderMutatingId.set(reminder.id);
    this.reminderError.set(null);

    try {
      await firstValueFrom(this.api.completePersonalReminder(reminder.id));
      this.reminders.update((reminders) => reminders.filter((item) => item.id !== reminder.id));
    } catch (error) {
      this.reminderError.set(error instanceof Error ? error.message : 'Не удалось закрыть напоминание.');
    } finally {
      this.reminderMutatingId.set(null);
    }
  }

  async deleteReminder(reminder: PersonalReminder): Promise<void> {
    const confirmed = await this.confirm.confirm({
      title: 'Удалить напоминание',
      message: `Удалить напоминание "${reminder.title || 'Без названия'}"?`,
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.reminderMutatingId.set(reminder.id);
    this.reminderError.set(null);

    try {
      await firstValueFrom(this.api.deletePersonalReminder(reminder.id));
      this.reminders.update((reminders) => reminders.filter((item) => item.id !== reminder.id));
    } catch (error) {
      this.reminderError.set(error instanceof Error ? error.message : 'Не удалось удалить напоминание.');
    } finally {
      this.reminderMutatingId.set(null);
    }
  }

  toggleReminder(reminder: PersonalReminder): void {
    this.expandedReminderId.update((id) => id === reminder.id ? null : reminder.id);
  }

  isReminderExpanded(reminder: PersonalReminder): boolean {
    return this.expandedReminderId() === reminder.id;
  }

  canEditReminder(reminder: PersonalReminder): boolean {
    return !reminder.sourceType;
  }

  reminderText(reminder: PersonalReminder): string {
    return reminder.text?.trim() || 'Без текста';
  }

  reminderIcon(reminder: PersonalReminder): string {
    if (reminder.reminderMode === 'timer') {
      return 'timer';
    }

    if (reminder.reminderMode === 'datetime') {
      return 'event';
    }

    return 'sticky_note_2';
  }

  reminderTimeLabel(reminder: PersonalReminder): string {
    if (!reminder.remindAt) {
      return reminder.reminderMode === 'timer' && reminder.timerMinutes
        ? `Таймер: ${reminder.timerMinutes} мин.`
        : 'Без времени';
    }

    const date = new Date(reminder.remindAt);
    if (Number.isNaN(date.getTime())) {
      return 'Без времени';
    }

    const prefix = date.getTime() <= Date.now() ? 'Сработало' : 'Напомнит';
    return `${prefix}: ${new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(date)}`;
  }

  isReminderDue(reminder: PersonalReminder): boolean {
    if (!reminder.remindAt) {
      return false;
    }

    const dueAt = Date.parse(reminder.remindAt);
    return Number.isFinite(dueAt) && dueAt <= Date.now();
  }

  openEditLead(lead: LeadItem): void {
    if (!this.canEditLead()) {
      return;
    }

    this.selectedLead.set(null);
    this.editLead.set(lead);
    this.editDraft.set({
      telephoneLead: lead.telephoneLead,
      companyName: lead.companyName ?? '',
      phones: lead.phones ?? '',
      mobilePhones: lead.mobilePhones ?? '',
      whatsappPhones: lead.whatsappPhones ?? '',
      emails: lead.emails ?? '',
      websites: lead.websites ?? '',
      vkUrl: lead.vkUrl ?? '',
      telegramUrl: lead.telegramUrl ?? '',
      industries: lead.industries ?? '',
      companyType: lead.companyType ?? '',
      region: lead.region ?? '',
      address: lead.address ?? '',
      cityLead: lead.cityLead,
      commentsLead: lead.commentsLead ?? '',
      lidStatus: lead.lidStatus,
      operatorId: lead.operator?.id ?? null,
      telephoneId: lead.telephoneId ?? null,
      managerId: lead.manager?.id ?? null,
      marketologId: lead.marketolog?.id ?? null
    });
    this.editError.set(null);
    this.deleteSaving.set(false);
    void this.loadEditOptions(undefined, (message) => this.editError.set(message));
  }

  closeEditLead(): void {
    if (this.editSaving() || this.deleteSaving()) {
      return;
    }

    this.editLead.set(null);
    this.editDraft.set(null);
    this.editError.set(null);
  }

  setEditField<K extends keyof LeadUpdateRequest>(field: K, value: LeadUpdateRequest[K]): void {
    this.editDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  setEditTelephoneId(value: unknown): void {
    if (value === null || value === undefined || value === '') {
      this.setEditField('telephoneId', null);
      return;
    }

    const parsed = Number(value);
    this.setEditField('telephoneId', Number.isFinite(parsed) ? parsed : null);
  }

  canSaveEdit(): boolean {
    const draft = this.editDraft();
    return Boolean(draft?.telephoneLead.trim() && draft.cityLead.trim() && draft.lidStatus);
  }

  async saveLeadEdit(): Promise<void> {
    const lead = this.editLead();
    const draft = this.editDraft();
    if (!lead || !draft || !this.canSaveEdit()) {
      this.editError.set('Телефон, город и статус обязательны.');
      return;
    }

    this.editSaving.set(true);
    this.editError.set(null);

    try {
      await firstValueFrom(this.api.updateLead(lead.id, {
        ...draft,
        telephoneLead: draft.telephoneLead.trim(),
        companyName: this.cleanText(draft.companyName),
        phones: this.cleanText(draft.phones),
        mobilePhones: this.cleanText(draft.mobilePhones),
        whatsappPhones: this.cleanText(draft.whatsappPhones),
        emails: this.cleanText(draft.emails),
        websites: this.cleanText(draft.websites),
        vkUrl: this.cleanText(draft.vkUrl),
        telegramUrl: this.cleanText(draft.telegramUrl),
        industries: this.cleanText(draft.industries),
        companyType: this.cleanText(draft.companyType),
        region: this.cleanText(draft.region),
        address: this.cleanText(draft.address),
        cityLead: draft.cityLead.trim(),
        commentsLead: draft.commentsLead?.trim(),
        telephoneId: this.canEditLeadTelephone() ? draft.telephoneId ?? null : undefined
      }));
      this.editLead.set(null);
      this.editDraft.set(null);
      await this.loadBoard();
    } catch (error) {
      this.editError.set(error instanceof Error ? error.message : 'Не удалось сохранить лид.');
    } finally {
      this.editSaving.set(false);
    }
  }

  async deleteCurrentLead(): Promise<void> {
    const lead = this.editLead();
    if (!lead || this.deleteSaving()) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Удалить лид',
      message: `Удалить лид #${lead.id}?`,
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.deleteSaving.set(true);
    this.editError.set(null);

    try {
      await firstValueFrom(this.api.deleteLead(lead.id));
      this.editLead.set(null);
      this.editDraft.set(null);
      await this.loadBoard();
    } catch (error) {
      this.editError.set(error instanceof Error ? error.message : 'Не удалось удалить лид.');
    } finally {
      this.deleteSaving.set(false);
    }
  }

  canCreateCompanyFromLead(lead: LeadItem): boolean {
    if (!this.canCreateCompany()) {
      return false;
    }

    const status = (lead.lidStatus ?? '').trim().toLocaleLowerCase('ru-RU');
    return status !== 'в работе' && status !== 'в работа';
  }

  openCompanyCreate(lead: LeadItem): void {
    if (!this.canCreateCompanyFromLead(lead)) {
      return;
    }

    this.selectedLead.set(null);
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
      this.companyLead.set(null);
      this.companyPayload.set(null);
      this.companyDraft.set(null);
      this.companySubCategories.set([]);
      await this.loadBoard();
      await this.router.navigateByUrl('/tabs/companies');
    } catch (error) {
      this.companyError.set(error instanceof Error ? error.message : 'Не удалось создать компанию.');
    } finally {
      this.companySaving.set(false);
    }
  }

  phoneHref(lead: LeadItem): string {
    return formatPhoneHref(lead.telephoneLead);
  }

  phoneDisplay(lead: LeadItem): string {
    return displayPhone(lead.telephoneLead, '-');
  }

  openMessenger(lead: LeadItem, messenger: 'whatsapp' | 'telegram' | 'max'): void {
    const phone = this.normalizedPhone(lead);
    if (!phone) {
      return;
    }

    const url = {
      whatsapp: `https://wa.me/${phone}`,
      telegram: `tg://resolve?phone=${phone}`,
      max: `https://max.ru/:share?text=${encodeURIComponent(`Номер клиента: +${phone}`)}`
    }[messenger];

    if (messenger === 'telegram') {
      this.externalLink.openScheme(url);
      return;
    }

    void this.externalLink.open(url);
  }

  async copyPhone(lead: LeadItem): Promise<void> {
    const phone = this.normalizedPhone(lead);
    if (!phone) {
      return;
    }

    try {
      await navigator.clipboard.writeText(phone);
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = phone;
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      document.body.append(textarea);
      textarea.select();
      document.execCommand('copy');
      textarea.remove();
    }

    this.copiedLeadId.set(lead.id);
    window.setTimeout(() => {
      if (this.copiedLeadId() === lead.id) {
        this.copiedLeadId.set(null);
      }
    }, 1200);
  }

  optionLabel(option: LeadPersonOption): string {
    return option.fio || option.username || option.email || `ID ${option.id}`;
  }

  private normalizedPhone(lead: LeadItem): string {
    return normalizedLeadPhone(lead);
  }

  personName(person?: { fio?: string; username?: string; id?: number }): string {
    return person?.fio || person?.username || (person?.id ? `ID ${person.id}` : '-');
  }

  leadCardPerson(lead: LeadItem): string {
    const manager = this.personName(lead.manager);
    return manager !== '-' ? manager : this.personName(lead.operator);
  }

  leadTitle(lead: LeadItem): string {
    return leadTitleHelper(lead);
  }

  leadCategoryLine(lead: LeadItem): string {
    return leadCategoryLineHelper(lead);
  }

  leadAddressLine(lead: LeadItem): string {
    return leadAddressLineHelper(lead);
  }

  socialLine(lead: LeadItem): string {
    return leadSocialLine(lead);
  }

  leadContactLinks(lead: LeadItem): LeadContactLink[] {
    return leadContactLinksHelper(lead);
  }

  leadTone(lead: LeadItem): 'default' | 'new' | 'work' | 'send' {
    return leadToneHelper(lead);
  }

  statusActions(lead: LeadItem): LeadMutation[] {
    return leadStatusActions(lead, this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MARKETOLOG']));
  }

  actionLabel(action: LeadMutation): string {
    return leadActionLabel(action);
  }

  actionIcon(action: LeadMutation): string {
    return leadActionIcon(action);
  }

  isMutating(lead: LeadItem, action: LeadMutation): boolean {
    return this.mutationKey() === `${lead.id}:${action}`;
  }

  async runAction(lead: LeadItem, action: LeadMutation): Promise<void> {
    const key = `${lead.id}:${action}`;
    this.mutationKey.set(key);
    this.error.set(null);

    try {
      await this.saveLeadCommentNow(lead);
      await firstValueFrom(this.requestForAction(lead, action));
      this.selectedLead.set(null);
      await this.loadBoard();
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось изменить статус лида.');
    } finally {
      this.mutationKey.set(null);
    }
  }

  private async loadBoard(): Promise<void> {
    this.loading.set(true);
    try {
      const board = await firstValueFrom(this.api.getLeadBoard({
        keyword: this.appliedKeyword(),
        section: this.activeBucket(),
        pageNumber: this.pageNumber(),
        pageSize: this.pageSize(),
        sortDirection: this.sortDirection()
      }));
      this.board.set(board);
      this.mergeCommentDrafts(board);
      this.error.set(null);
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось загрузить лиды.');
    } finally {
      this.loading.set(false);
    }
  }

  private pageFor(bucket: LeadBucketKey): Page<LeadItem> {
    const board = this.board();
    if (!board) {
      return this.emptyPage;
    }

    return bucket === 'archive' ? board.archive ?? this.emptyPage : board[bucket] ?? this.emptyPage;
  }

  private mergeCommentDrafts(board: LeadBoard): void {
    const leads = [
      ...board.toWork.content,
      ...board.newLeads.content,
      ...board.send.content,
      ...(board.archive?.content ?? []),
      ...board.inWork.content,
      ...board.all.content
    ];

    this.commentDrafts.update((drafts) => {
      const next = { ...drafts };
      leads.forEach((lead) => {
        if (next[lead.id] === undefined) {
          next[lead.id] = lead.commentsLead ?? '';
        }
      });
      return next;
    });
  }

  private applyUpdatedLead(updatedLead: LeadItem): void {
    this.commentDrafts.update((drafts) => ({
      ...drafts,
      [updatedLead.id]: updatedLead.commentsLead ?? ''
    }));

    this.selectedLead.update((lead) => lead?.id === updatedLead.id ? updatedLead : lead);
    this.editLead.update((lead) => lead?.id === updatedLead.id ? updatedLead : lead);

    this.board.update((board) => board ? {
      ...board,
      toWork: this.replaceLeadInPage(board.toWork, updatedLead),
      newLeads: this.replaceLeadInPage(board.newLeads, updatedLead),
      send: this.replaceLeadInPage(board.send, updatedLead),
      archive: board.archive ? this.replaceLeadInPage(board.archive, updatedLead) : board.archive,
      inWork: this.replaceLeadInPage(board.inWork, updatedLead),
      all: this.replaceLeadInPage(board.all, updatedLead)
    } : board);
  }

  private replaceLeadInPage(page: Page<LeadItem>, updatedLead: LeadItem): Page<LeadItem> {
    let changed = false;
    const content = page.content.map((lead) => {
      if (lead.id !== updatedLead.id) {
        return lead;
      }

      changed = true;
      return updatedLead;
    });

    return changed ? { ...page, content } : page;
  }

  private setCommentSaveState(leadId: number, state: LeadCommentSaveState): void {
    this.commentSaveStates.update((states) => ({
      ...states,
      [leadId]: state
    }));
  }

  private commentUpdateRequest(lead: LeadItem, commentsLead: string): LeadUpdateRequest {
    return {
      telephoneLead: lead.telephoneLead,
      companyName: lead.companyName,
      phones: lead.phones,
      mobilePhones: lead.mobilePhones,
      whatsappPhones: lead.whatsappPhones,
      emails: lead.emails,
      websites: lead.websites,
      vkUrl: lead.vkUrl,
      telegramUrl: lead.telegramUrl,
      industries: lead.industries,
      companyType: lead.companyType,
      region: lead.region,
      address: lead.address,
      cityLead: lead.cityLead,
      commentsLead,
      lidStatus: lead.lidStatus,
      operatorId: lead.operator?.id ?? null,
      telephoneId: this.canEditLeadTelephone() ? lead.telephoneId ?? null : undefined,
      managerId: lead.manager?.id ?? null,
      marketologId: lead.marketolog?.id ?? null
    };
  }

  private async loadEditOptions(onSuccess?: () => void, onError?: (message: string) => void): Promise<void> {
    if (this.editOptions()) {
      onSuccess?.();
      return;
    }

    try {
      this.editOptions.set(await firstValueFrom(this.api.getLeadEditOptions()));
      onSuccess?.();
    } catch (error) {
      onError?.(error instanceof Error ? error.message : 'Не удалось загрузить списки.');
    }
  }

  private setDefaultImportManagers(): void {
    if (this.leadImportManagerIds().length) {
      return;
    }

    const managers = this.managerOptions();
    const preferred = managers
      .filter((manager) => manager.id === 2 || manager.id === 3)
      .map((manager) => manager.id);
    this.leadImportManagerIds.set(preferred.length ? preferred : managers.slice(0, 2).map((manager) => manager.id));
  }

  private async loadReminders(force = false): Promise<void> {
    if (this.reminderLoading() || (!force && this.reminders().length)) {
      return;
    }

    this.reminderLoading.set(true);
    this.reminderError.set(null);

    try {
      this.reminders.set(await firstValueFrom(this.api.getPersonalReminders()));
    } catch (error) {
      this.reminderError.set(error instanceof Error ? error.message : 'Не удалось загрузить напоминания.');
    } finally {
      this.reminderLoading.set(false);
    }
  }

  private emptyReminderDraft(): PersonalReminderDraft {
    return {
      title: '',
      text: '',
      reminderMode: 'none',
      remindAtLocal: '',
      timerMinutes: 30
    };
  }

  private reminderDraftFromReminder(reminder: PersonalReminder): PersonalReminderDraft {
    return {
      title: reminder.title ?? '',
      text: reminder.text ?? '',
      reminderMode: reminder.reminderMode ?? 'none',
      remindAtLocal: reminder.reminderMode === 'datetime' ? this.localInputFromIso(reminder.remindAt) : '',
      timerMinutes: reminder.reminderMode === 'timer' ? reminder.timerMinutes ?? 30 : 30
    };
  }

  private reminderRequestFromDraft(draft: PersonalReminderDraft): PersonalReminderRequest {
    return {
      title: draft.title.trim(),
      text: draft.text.trim(),
      reminderMode: draft.reminderMode,
      remindAt: draft.reminderMode === 'datetime' ? this.isoFromLocalInput(draft.remindAtLocal) : null,
      timerMinutes: draft.reminderMode === 'timer' ? this.normalizeReminderMinutes(draft.timerMinutes) : null
    };
  }

  private sortReminders(reminders: PersonalReminder[]): PersonalReminder[] {
    return reminders.slice().sort((left, right) => {
      const leftDue = this.isReminderDue(left) ? 0 : 1;
      const rightDue = this.isReminderDue(right) ? 0 : 1;
      if (leftDue !== rightDue) {
        return leftDue - rightDue;
      }

      const leftTime = left.remindAt ? Date.parse(left.remindAt) : Number.POSITIVE_INFINITY;
      const rightTime = right.remindAt ? Date.parse(right.remindAt) : Number.POSITIVE_INFINITY;
      if (leftTime !== rightTime) {
        return leftTime - rightTime;
      }

      return Date.parse(right.updatedAt) - Date.parse(left.updatedAt);
    });
  }

  private normalizeReminderMinutes(value: number | string): number {
    const minutes = Math.round(Number(value));
    if (!Number.isFinite(minutes) || minutes < 1) {
      return 30;
    }

    return Math.min(minutes, 10_080);
  }

  private isoFromLocalInput(value: string): string | null {
    if (!value) {
      return null;
    }

    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }

  private localInputFromIso(value: string | null): string {
    if (!value) {
      return '';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }

    const timezoneOffset = date.getTimezoneOffset() * 60_000;
    return new Date(date.getTime() - timezoneOffset).toISOString().slice(0, 16);
  }

  private localInputAfterMinutes(minutes: number): string {
    const date = new Date(Date.now() + minutes * 60_000);
    const timezoneOffset = date.getTimezoneOffset() * 60_000;
    return new Date(date.getTime() - timezoneOffset).toISOString().slice(0, 16);
  }

  private async loadCompanyPayload(managerId?: number | null, preserved?: CompanyPreservedFields): Promise<void> {
    const lead = this.companyLead();
    if (!lead) {
      return;
    }

    this.companyLoading.set(true);
    this.companyError.set(null);

    try {
      const payload = await firstValueFrom(this.api.getCompanyCreatePayload('manager', lead.id, managerId));
      this.companyPayload.set(payload);
      this.companySubCategories.set(payload.subCategories ?? []);
      this.companyDraft.set({
        ...this.companyDraftFromPayload(payload, managerId),
        ...preserved
      });
    } catch (error) {
      this.companyError.set(error instanceof Error ? error.message : 'Не удалось загрузить данные для компании.');
    } finally {
      this.companyLoading.set(false);
    }
  }

  private async loadCompanySubCategories(categoryId: number): Promise<void> {
    try {
      this.companySubCategories.set(await firstValueFrom(this.api.getCompanySubcategories(categoryId)));
    } catch (error) {
      this.companyError.set(error instanceof Error ? error.message : 'Не удалось загрузить подкатегории.');
    }
  }

  private companyDraftFromPayload(payload: CompanyCreatePayload, managerId?: number | null): CompanyCreateDraft {
    return {
      source: payload.source,
      leadId: payload.leadId ?? this.companyLead()?.id ?? null,
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

  private requestForAction(lead: LeadItem, action: LeadMutation): Observable<void> {
    switch (action) {
      case 'send':
        return this.api.markLeadSend(lead.id);
      case 'resend':
        return this.api.markLeadResend(lead.id);
      case 'archive':
        return this.api.markLeadArchive(lead.id);
      case 'new':
        return this.api.markLeadNew(lead.id);
      case 'toWork':
        return this.api.markLeadToWork(lead.id, this.commentFor(lead));
    }
  }

  private cleanText(value?: string): string | undefined {
    const cleanValue = value?.trim() ?? '';
    return cleanValue || undefined;
  }
}
