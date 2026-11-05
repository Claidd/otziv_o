import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { ManagerControlApi } from '../core/manager-control.api';
import { ManagerReportsApi } from '../core/manager-reports.api';
import { ManagerWorkerRiskApi } from '../core/manager-worker-risk.api';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import type { ManagerControlConcreteItem, ManagerControlManagerDetail } from '../core/manager-control.models';
import { ManagerControlPage } from './manager-control.page';

describe('unanswered client card outcomes outside the chat (mobile)', () => {
  const card: ManagerControlConcreteItem = { controlEntityId: 6914, type: 'CLIENT_CHAT_UNANSWERED', title: 'Toyama motors', itemStatus: 'OPEN' };
  const detail = { managerId: 3, dailyControlId: 1, items: [{ itemId: 1, group: 'ACTION', itemType: 'CLIENT_CHAT_UNANSWERED', itemStatus: 'OPEN', examples: [card] }] } as ManagerControlManagerDetail;

  function createPage(api: Record<string, unknown>) {
    TestBed.configureTestingModule({ providers: [
      { provide: ManagerControlApi, useValue: { getManagerControlDetails: () => of(detail),
        getManagerControlToday: () => of({ managers: [] }), ...api } },
      { provide: ManagerReportsApi, useValue: {} }, { provide: ManagerWorkerRiskApi, useValue: {} },
      { provide: AuthService, useValue: { hasAnyRealmRole: () => false } },
      { provide: MobileExternalLinkService, useValue: {} },
      { provide: ActivatedRoute, useValue: {} }, { provide: Router, useValue: {} }
    ] });
    TestBed.overrideComponent(ManagerControlPage, { set: { template: '', imports: [] } });
    const fixture = TestBed.createComponent(ManagerControlPage), page = fixture.componentInstance;
    page.selectedManagerId.set(3);
    page.detail.set(detail);
    return { fixture, page };
  }

  afterEach(() => vi.restoreAllMocks());

  it('needs the comment and a confirmation before recording an answer outside the chat', async () => {
    const mark = vi.fn().mockReturnValue(of({ ...card, itemStatus: 'RESOLVED' }));
    const { fixture, page } = createPage({ markManagerControlAnsweredOutsideChat: mark });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);

    await page.markAnsweredOutsideChat(card);
    expect(page.notice()).toContain('где и когда');
    expect(confirm).not.toHaveBeenCalled();

    page.setCommentText(card, 'Ответила лично в WhatsApp в 22:10');
    await page.markAnsweredOutsideChat(card);
    expect(mark).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await page.markAnsweredOutsideChat(card);
    expect(mark).toHaveBeenCalledWith(6914, 'Ответила лично в WhatsApp в 22:10');
    expect(page.notice()).toContain('Владелец проверит');
    fixture.destroy();
  });

  it('offers the link re-check on a chat-link card whose WhatsApp invite no longer opens the group', () => {
    const { fixture, page } = createPage({});
    expect(page.showRepair({ controlEntityId: 9001, type: 'COMPANY_CHAT_BINDING', title: 'Toyama motors', itemStatus: 'OPEN',
      reason: 'Почему в контроле: ссылка-приглашение WhatsApp в карточке компании больше не открывает привязанную группу.' })).toBe(true);
    fixture.destroy();
  });

  it('offers the current invite after a confirmed stale-link report', async () => {
    const report = vi.fn().mockReturnValue(of({ item: card, message: 'Подключённый WhatsApp-аккаунт видит актуальную ссылку-приглашение',
      inviteUrl: 'https://chat.whatsapp.com/NewInviteCode12345' }));
    const { fixture, page } = createPage({ reportManagerControlStaleChatInvite: report });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);

    await page.reportStaleChatInvite(card);
    expect(report).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await page.reportStaleChatInvite(card);
    expect(report).toHaveBeenCalledWith(6914);
    expect(page.staleInviteUrl(card)).toBe('https://chat.whatsapp.com/NewInviteCode12345');
    expect(page.notice()).toContain('актуальную ссылку-приглашение');
    expect(page.mutatingId()).toBeNull();
    fixture.destroy();
  });
});
