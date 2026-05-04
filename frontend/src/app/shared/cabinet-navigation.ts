export type CabinetNavigationLink = {
  label: string;
  description: string;
  icon: string;
  active: string;
  roles: string[];
  routerLink?: string;
  href?: string;
};

export const CABINET_HOME_LINK: CabinetNavigationLink = {
  label: 'Личный кабинет',
  description: 'Зарплата, графики и профиль',
  icon: 'dashboard',
  active: 'dashboard',
  routerLink: '/',
  roles: []
};

export const CABINET_SECTION_LINKS: CabinetNavigationLink[] = [
  {
    label: 'Моя команда',
    description: 'Сотрудники и показатели',
    icon: 'badge',
    active: 'team',
    routerLink: '/admin/team',
    roles: ['ADMIN', 'OWNER', 'MANAGER']
  },
  {
    label: 'Рейтинг',
    description: 'Рабочие счетчики команды',
    icon: 'leaderboard',
    active: 'score',
    routerLink: '/admin/score',
    roles: ['ADMIN', 'OWNER', 'MANAGER', 'WORKER', 'OPERATOR', 'MARKETOLOG']
  },
  {
    label: 'Аналитика',
    description: 'Оборот, ЗП и графики',
    icon: 'analytics',
    active: 'analytics',
    routerLink: '/admin/analyse',
    roles: ['ADMIN', 'OWNER']
  }
];

export const CABINET_NAVIGATION_LINKS: CabinetNavigationLink[] = [
  CABINET_HOME_LINK,
  ...CABINET_SECTION_LINKS
];

export function canSeeCabinetNavigationLink(link: CabinetNavigationLink, roles: string[]): boolean {
  if (link.roles.length === 0) {
    return true;
  }

  const roleSet = new Set(roles);
  if (roleSet.has('ADMIN') || roleSet.has('OWNER')) {
    return true;
  }

  return link.roles.some((role) => roleSet.has(role));
}

export function visibleCabinetNavigationLinks(
  roles: string[],
  links = CABINET_NAVIGATION_LINKS
): CabinetNavigationLink[] {
  return links.filter((link) => canSeeCabinetNavigationLink(link, roles));
}
