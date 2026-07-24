import { appEnvironment } from '../core/app-environment';

export type AppNavigationGroup = 'home' | 'leads' | 'companies' | 'orders' | 'worker' | 'operator';

export type AppNavigationLink = {
  id: string;
  label: string;
  description?: string;
  icon: string;
  mobileIcon?: string;
  active: string;
  group: AppNavigationGroup;
  roles: string[];
  adminOnly?: boolean;
  exactRoleOnly?: boolean;
  primary?: boolean;
  routerLink?: string;
  href?: string;
  openInNewTab?: boolean;
};

const PRIMARY_LINKS: AppNavigationLink[] = [
  {
    id: 'home',
    label: 'Главная',
    description: 'Личный кабинет и показатели',
    icon: 'home',
    active: 'dashboard',
    group: 'home',
    routerLink: '/',
    roles: [],
    primary: true
  },
  {
    id: 'leads',
    label: 'Лиды',
    description: 'Новые обращения и работа с лидами',
    icon: 'notifications_active',
    mobileIcon: 'grid_view',
    active: 'leads',
    group: 'leads',
    routerLink: '/leads',
    roles: ['ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG'],
    primary: true
  },
  {
    id: 'companies',
    label: 'Компании',
    description: 'Клиенты, филиалы и статусы',
    icon: 'business',
    mobileIcon: 'apartment',
    active: 'companies',
    group: 'companies',
    routerLink: '/companies',
    roles: ['ADMIN', 'OWNER', 'MANAGER'],
    primary: true
  },
  {
    id: 'orders',
    label: 'Заказы',
    description: 'Проверки, публикации и оплаты',
    icon: 'inventory_2',
    active: 'orders',
    group: 'orders',
    routerLink: '/orders',
    roles: ['ADMIN', 'OWNER', 'MANAGER'],
    primary: true
  },
  {
    id: 'worker',
    label: 'Специалист',
    description: 'Аккаунты, публикации и задачи',
    icon: 'engineering',
    mobileIcon: 'assignment_ind',
    active: 'worker',
    group: 'worker',
    routerLink: '/worker',
    roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'PERFORMER'],
    primary: true
  },
  {
    id: 'operator',
    label: 'Оператор',
    description: 'Обработка входящих обращений',
    icon: 'support_agent',
    mobileIcon: 'call',
    active: 'operator',
    group: 'operator',
    routerLink: '/operator',
    roles: ['ADMIN', 'OWNER', 'OPERATOR'],
    primary: true
  }
];

const SECONDARY_LINKS: AppNavigationLink[] = [
  {
    id: 'personal-cabinet',
    label: 'Личный кабинет',
    description: 'Профиль и личные показатели',
    icon: 'dashboard',
    active: 'dashboard',
    group: 'home',
    routerLink: '/',
    roles: []
  },
  {
    id: 'analytics',
    label: 'Аналитика',
    description: 'Оборот, зарплата и графики',
    icon: 'analytics',
    active: 'analytics',
    group: 'home',
    routerLink: '/admin/analyse',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'manager-control',
    label: 'Контроль',
    description: 'Замечания менеджеров',
    icon: 'fact_check',
    active: 'manager-control',
    group: 'home',
    routerLink: '/admin/manager-control',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'team',
    label: 'Моя команда',
    description: 'Сотрудники и показатели',
    icon: 'badge',
    active: 'team',
    group: 'home',
    routerLink: '/admin/team',
    roles: ['ADMIN', 'OWNER', 'MANAGER']
  },
  {
    id: 'score',
    label: 'Рейтинг',
    description: 'Рабочие счетчики команды',
    icon: 'leaderboard',
    active: 'score',
    group: 'home',
    routerLink: '/admin/score',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'manager-control-self',
    label: 'Мои замечания',
    description: 'Личный контроль дня',
    icon: 'fact_check',
    active: 'manager-control-self',
    group: 'home',
    routerLink: '/cabinet/manager-control',
    exactRoleOnly: true,
    roles: ['MANAGER']
  },
  {
    id: 'achievements',
    label: 'Мои достижения',
    description: 'Цели, серии и личный прогресс',
    icon: 'emoji_events',
    active: 'gamification-progress',
    group: 'home',
    routerLink: '/gamification/progress',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'gamification-rewards',
    label: 'Награды',
    description: 'Каталог и заявки сотрудников',
    icon: 'redeem',
    active: 'gamification-rewards',
    group: 'home',
    routerLink: '/admin/gamification/rewards',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'dictionaries',
    label: 'Справочники',
    description: 'Настройки данных и аккаунты',
    icon: 'tune',
    active: 'dictionaries',
    group: 'home',
    routerLink: '/admin/dictionaries',
    roles: ['ADMIN', 'OWNER', 'MANAGER']
  },
  {
    id: 'tbank',
    label: 'Т Банк',
    description: 'Платежи, чеки и профили',
    icon: 'account_balance_wallet',
    active: 'tbank-payments',
    group: 'home',
    routerLink: '/admin/tbank-payments',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'users',
    label: 'Пользователи',
    description: 'Доступы и назначения',
    icon: 'admin_panel_settings',
    active: 'users',
    group: 'home',
    routerLink: '/admin/users',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'new-user',
    label: 'Новый пользователь',
    description: 'Создать учетную запись',
    icon: 'person_add',
    active: 'create-user',
    group: 'home',
    routerLink: '/admin/users/new',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'common-billing',
    label: 'Общие счета',
    description: 'Сводные счета по заказам',
    icon: 'receipt_long',
    active: 'common-billing',
    group: 'orders',
    routerLink: '/admin/common-billing',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'company-archive',
    label: 'Архив компаний',
    description: 'Архивные компании и заказы',
    icon: 'archive',
    active: 'manager-archive',
    group: 'companies',
    routerLink: '/manager/archive',
    roles: ['ADMIN', 'OWNER', 'MANAGER']
  },
  {
    id: 'worker-risk',
    label: 'Риски',
    description: 'Проблемные задачи специалистов',
    icon: 'policy',
    active: 'worker-risk',
    group: 'worker',
    routerLink: '/worker/risk',
    roles: ['ADMIN', 'OWNER', 'MANAGER']
  },
  {
    id: 'training',
    label: 'Обучение',
    description: 'Материалы для специалистов',
    icon: 'school',
    active: 'training',
    group: 'worker',
    routerLink: '/training',
    roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER']
  },
  {
    id: 'performer',
    label: 'Исполнитель',
    description: 'Задачи исполнителя',
    icon: 'assignment_ind',
    active: 'performer',
    group: 'worker',
    routerLink: '/performer',
    roles: ['ADMIN', 'OWNER', 'MANAGER', 'PERFORMER']
  },
  {
    id: 'operator-phones',
    label: 'Телефоны',
    description: 'Телефоны операторов',
    icon: 'phone_in_talk',
    active: 'operator',
    group: 'operator',
    routerLink: '/admin/dictionaries/phones',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'cities',
    label: 'Города',
    description: 'Статистика по городам',
    icon: 'location_city',
    active: 'city-stats',
    group: 'home',
    routerLink: '/admin/cities',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'archive-admin',
    label: 'Архиватор',
    description: 'Системный архив',
    icon: 'inventory_2',
    active: 'archive-admin',
    group: 'home',
    routerLink: '/admin/archive',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'performers-admin',
    label: 'Исполнители',
    description: 'Управление исполнителями',
    icon: 'assignment_ind',
    active: 'performers',
    group: 'home',
    routerLink: '/admin/performers',
    roles: ['ADMIN', 'OWNER', 'MANAGER']
  },
  {
    id: 'reputation-ai',
    label: 'AI-помощник',
    description: 'Анализ репутации',
    icon: 'auto_awesome',
    active: 'reputation-ai',
    group: 'home',
    routerLink: '/admin/reputation-ai',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'whatsapp',
    label: 'WhatsApp',
    description: 'Привязка рабочего аккаунта',
    icon: 'qr_code_2',
    active: 'whatsapp-bind',
    group: 'home',
    routerLink: '/cabinet/whatsapp',
    exactRoleOnly: true,
    roles: ['MANAGER']
  },
  {
    id: 'migration',
    label: 'Миграция',
    description: 'Перенос учетной записи',
    icon: 'sync',
    active: 'migration',
    group: 'home',
    routerLink: '/legacy-migration',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'mobile-update',
    label: 'Обновление Android',
    description: 'Публикация APK сотрудникам',
    icon: 'system_update',
    active: 'mobile-update',
    group: 'home',
    routerLink: '/admin/mobile-update',
    roles: ['ADMIN', 'OWNER']
  },
  {
    id: 'metrics',
    label: 'Метрики',
    description: 'Мониторинг приложения',
    icon: 'desktop_windows',
    active: 'metrics',
    group: 'home',
    href: appEnvironment.metricsBaseUrl,
    openInNewTab: true,
    roles: ['ADMIN', 'OWNER']
  }
];

export const APP_PRIMARY_NAVIGATION: readonly AppNavigationLink[] = PRIMARY_LINKS;

export const APP_NAVIGATION_LINKS: readonly AppNavigationLink[] = [
  SECONDARY_LINKS[0],
  ...PRIMARY_LINKS.slice(1),
  ...SECONDARY_LINKS.slice(1)
];

export const APP_LOGOUT_LINK: AppNavigationLink = {
  id: 'logout',
  label: 'Выход',
  icon: 'logout',
  active: 'logout',
  group: 'home',
  roles: []
};

export function canSeeAppNavigationLink(link: AppNavigationLink, roles: readonly string[]): boolean {
  if (link.roles.length === 0) {
    return true;
  }

  const roleSet = new Set(roles);
  if (link.adminOnly) {
    return roleSet.has('ADMIN');
  }

  if (link.exactRoleOnly) {
    return link.roles.some((role) => roleSet.has(role));
  }

  if (roleSet.has('ADMIN') || roleSet.has('OWNER')) {
    return true;
  }

  return link.roles.some((role) => roleSet.has(role));
}

export function visibleAppNavigationLinks(
  roles: readonly string[],
  links: readonly AppNavigationLink[] = APP_NAVIGATION_LINKS
): AppNavigationLink[] {
  return links.filter((link) => canSeeAppNavigationLink(link, roles));
}

export function appNavigationGroupForUrl(url: string, active = ''): AppNavigationGroup {
  const path = (`/${url.split(/[?#]/, 1)[0]}`).replace(/^\/\/+/, '/').replace(/\/$/, '') || '/';

  if (path === '/leads') {
    return 'leads';
  }
  if (path === '/companies' || path.startsWith('/manager/archive')) {
    return 'companies';
  }
  if (
    path === '/orders'
    || path.startsWith('/orders/')
    || path.startsWith('/manager/orders/')
    || path.startsWith('/admin/common-billing')
    || path.startsWith('/manager/common-billing')
    || path.startsWith('/review/')
  ) {
    return 'orders';
  }
  if (path === '/worker' || path.startsWith('/worker/') || path === '/performer' || path === '/training') {
    return 'worker';
  }
  if (path === '/operator' || path.startsWith('/operator/') || path === '/admin/dictionaries/phones') {
    return 'operator';
  }

  const activeMatch = APP_NAVIGATION_LINKS.find((link) => link.active === active);
  return activeMatch?.group ?? 'home';
}

export function appNavigationLinksForGroup(
  group: AppNavigationGroup,
  roles: readonly string[]
): AppNavigationLink[] {
  return visibleAppNavigationLinks(roles, APP_NAVIGATION_LINKS)
    .filter((link) => link.group === group);
}

export function appNavigationRouteForGroup(group: AppNavigationGroup, roles: readonly string[]): string {
  if (group === 'home' && (roles.includes('ADMIN') || roles.includes('OWNER'))) {
    return '/admin/analyse';
  }

  if (
    group === 'worker'
    && roles.includes('PERFORMER')
    && !roles.some((role) => ['ADMIN', 'OWNER', 'MANAGER', 'WORKER'].includes(role))
  ) {
    return '/performer';
  }

  return APP_PRIMARY_NAVIGATION.find((link) => link.group === group)?.routerLink ?? '/';
}

export function isAppNavigationGroupRootUrl(
  group: AppNavigationGroup,
  url: string,
  roles: readonly string[]
): boolean {
  const path = url.split(/[?#]/, 1)[0].replace(/\/$/, '') || '/';
  return path === appNavigationRouteForGroup(group, roles);
}
