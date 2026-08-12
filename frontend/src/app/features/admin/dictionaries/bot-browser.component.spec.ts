import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BehaviorSubject, NEVER, Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AdminDictionariesApi } from '../../../core/admin-dictionaries.api';
import { BotBrowserComponent } from './bot-browser.component';

describe('BotBrowserComponent', () => {
  it('loads only safe browser metadata for the requested bot', async () => {
    const sanitizer = {
      bypassSecurityTrustResourceUrl: vi.fn((url: string) => url)
    };
    const api = {
      getBotBrowserMetadata: vi.fn(() => of({
        botId: 37,
        login: 'browser-login',
        fio: 'Браузерный аккаунт'
      })),
      openBotBrowser: vi.fn(() => NEVER),
      heartbeatBotBrowser: vi.fn(() => of(undefined)),
      closeBotBrowser: vi.fn(() => of(undefined))
    };

    await TestBed.configureTestingModule({
      imports: [BotBrowserComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ botId: '37' }) } }
        },
        { provide: DomSanitizer, useValue: sanitizer },
        { provide: AdminDictionariesApi, useValue: api }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(BotBrowserComponent);

    expect(api.getBotBrowserMetadata).toHaveBeenCalledWith(37);
    expect(fixture.componentInstance.bot()).toEqual({
      botId: 37,
      login: 'browser-login',
      fio: 'Браузерный аккаунт'
    });
  });

  it('does not trust an unsafe VNC URL and closes the upstream session', async () => {
    const sanitizer = {
      bypassSecurityTrustResourceUrl: vi.fn((url: string) => url)
    };
    const api = {
      getBotBrowserMetadata: vi.fn(() => of({
        botId: 37,
        login: 'browser-login',
        fio: 'Браузерный аккаунт'
      })),
      openBotBrowser: vi.fn(() => of({
        sessionId: '7c121c71-7bc4-4a25-a33c-78c7fe63e5c9',
        vncUrl: 'data:text/html,<script>alert(document.cookie)</script>',
        vncPassword: 'aB3_xY9-'
      })),
      heartbeatBotBrowser: vi.fn(() => of(undefined)),
      closeBotBrowser: vi.fn(() => of(undefined))
    };

    await TestBed.configureTestingModule({
      imports: [BotBrowserComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ botId: '37' }) } }
        },
        { provide: DomSanitizer, useValue: sanitizer },
        { provide: AdminDictionariesApi, useValue: api }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(BotBrowserComponent);
    const component = fixture.componentInstance;

    expect(component.error()).toBe('Сервис браузера вернул небезопасный адрес подключения');
    expect(component.vncUrl()).toBeNull();
    expect(component.safeVncUrl()).toBeNull();
    expect(sanitizer.bypassSecurityTrustResourceUrl).not.toHaveBeenCalled();
    expect(api.closeBotBrowser).toHaveBeenCalledWith(
      37,
      '7c121c71-7bc4-4a25-a33c-78c7fe63e5c9'
    );
  });

  it('does not reopen during close and allows a later session to close normally', async () => {
    const firstClose = new Subject<void>();
    const sanitizer = {
      bypassSecurityTrustResourceUrl: vi.fn((url: string) => url)
    };
    const api = {
      getBotBrowserMetadata: vi.fn(() => of({
        botId: 37,
        login: 'browser-login',
        fio: 'Браузерный аккаунт'
      })),
      openBotBrowser: vi.fn(() => of({
        sessionId: '7c121c71-7bc4-4a25-a33c-78c7fe63e5c9',
        vncUrl: `${window.location.origin}/session`,
        vncPassword: 'aB3_xY9-'
      })),
      heartbeatBotBrowser: vi.fn(() => of(undefined)),
      closeBotBrowser: vi.fn()
        .mockReturnValueOnce(firstClose)
        .mockReturnValueOnce(of(undefined))
    };

    await TestBed.configureTestingModule({
      imports: [BotBrowserComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ botId: '37' }) } }
        },
        { provide: DomSanitizer, useValue: sanitizer },
        { provide: AdminDictionariesApi, useValue: api }
      ]
    }).compileComponents();

    const component = TestBed.createComponent(BotBrowserComponent).componentInstance;
    component.closeSession();
    component.retry();
    expect(api.openBotBrowser).toHaveBeenCalledTimes(1);

    firstClose.next();
    firstClose.complete();
    component.retry();
    component.closeSession();

    expect(api.openBotBrowser).toHaveBeenCalledTimes(2);
    expect(api.closeBotBrowser).toHaveBeenCalledTimes(2);
    expect(api.closeBotBrowser).toHaveBeenNthCalledWith(
      1,
      37,
      '7c121c71-7bc4-4a25-a33c-78c7fe63e5c9'
    );
  });

  it('opens the current routed bot after an older session finishes closing', async () => {
    const routeParams = new BehaviorSubject(convertToParamMap({ botId: '37' }));
    const firstClose = new Subject<void>();
    const api = {
      getBotBrowserMetadata: vi.fn((botId: number) => of({
        botId,
        login: `browser-${botId}`,
        fio: `Аккаунт ${botId}`
      })),
      openBotBrowser: vi.fn((botId: number) => of({
        sessionId: `session-${botId}`,
        vncUrl: `${window.location.origin}/session/${botId}`,
        vncPassword: 'aB3_xY9-'
      })),
      heartbeatBotBrowser: vi.fn(() => of(undefined)),
      closeBotBrowser: vi.fn()
        .mockReturnValueOnce(firstClose)
        .mockReturnValue(of(undefined))
    };

    await TestBed.configureTestingModule({
      imports: [BotBrowserComponent],
      providers: [
        { provide: ActivatedRoute, useValue: { paramMap: routeParams } },
        {
          provide: DomSanitizer,
          useValue: { bypassSecurityTrustResourceUrl: vi.fn((url: string) => url) }
        },
        { provide: AdminDictionariesApi, useValue: api }
      ]
    }).compileComponents();

    const component = TestBed.createComponent(BotBrowserComponent).componentInstance;
    component.closeSession();
    routeParams.next(convertToParamMap({ botId: '38' }));

    expect(api.openBotBrowser).toHaveBeenCalledTimes(1);
    firstClose.next();

    expect(api.openBotBrowser).toHaveBeenCalledTimes(2);
    expect(api.openBotBrowser).toHaveBeenLastCalledWith(38);
    expect(component.bot()?.botId).toBe(38);
  });

  it('retries metadata without discarding an already open VNC session', async () => {
    const api = {
      getBotBrowserMetadata: vi.fn(() => of({
        botId: 37,
        login: 'browser-login',
        fio: 'Браузерный аккаунт'
      })),
      openBotBrowser: vi.fn(() => of({
        sessionId: '7c121c71-7bc4-4a25-a33c-78c7fe63e5c9',
        vncUrl: `${window.location.origin}/session`,
        vncPassword: 'aB3_xY9-'
      })),
      heartbeatBotBrowser: vi.fn(() => of(undefined)),
      closeBotBrowser: vi.fn(() => of(undefined))
    };

    await TestBed.configureTestingModule({
      imports: [BotBrowserComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ botId: '37' }) } }
        },
        {
          provide: DomSanitizer,
          useValue: { bypassSecurityTrustResourceUrl: vi.fn((url: string) => url) }
        },
        { provide: AdminDictionariesApi, useValue: api }
      ]
    }).compileComponents();

    const component = TestBed.createComponent(BotBrowserComponent).componentInstance;
    const openVncUrl = component.vncUrl();

    component.error.set('Не удалось загрузить данные аккаунта');
    component.retry();

    expect(component.error()).toBeNull();
    expect(component.vncUrl()).toBe(openVncUrl);
    expect(api.getBotBrowserMetadata).toHaveBeenCalledTimes(2);
    expect(api.openBotBrowser).toHaveBeenCalledTimes(1);
  });
});
