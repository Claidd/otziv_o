export const MOBILE_ROLE = {
  admin: 'ADMIN',
  owner: 'OWNER',
  manager: 'MANAGER',
  worker: 'WORKER',
  operator: 'OPERATOR',
  marketolog: 'MARKETOLOG',
  client: 'CLIENT'
} as const;

export type MobileRole = typeof MOBILE_ROLE[keyof typeof MOBILE_ROLE];
export type MobileRoleSet = readonly MobileRole[];

export const MOBILE_ROLES = {
  authenticated: [] as MobileRoleSet,
  admin: [MOBILE_ROLE.admin] as MobileRoleSet,
  ownerAdmin: [MOBILE_ROLE.admin, MOBILE_ROLE.owner] as MobileRoleSet,
  manager: [MOBILE_ROLE.admin, MOBILE_ROLE.owner, MOBILE_ROLE.manager] as MobileRoleSet,
  worker: [MOBILE_ROLE.admin, MOBILE_ROLE.owner, MOBILE_ROLE.manager, MOBILE_ROLE.worker] as MobileRoleSet,
  leads: [MOBILE_ROLE.admin, MOBILE_ROLE.owner, MOBILE_ROLE.manager, MOBILE_ROLE.marketolog] as MobileRoleSet,
  operator: [MOBILE_ROLE.admin, MOBILE_ROLE.owner, MOBILE_ROLE.operator] as MobileRoleSet,
  botBrowser: [MOBILE_ROLE.admin, MOBILE_ROLE.owner, MOBILE_ROLE.manager, MOBILE_ROLE.worker] as MobileRoleSet,
  score: [
    MOBILE_ROLE.admin,
    MOBILE_ROLE.owner,
    MOBILE_ROLE.manager,
    MOBILE_ROLE.worker,
    MOBILE_ROLE.operator,
    MOBILE_ROLE.marketolog
  ] as MobileRoleSet
} as const;

export const MOBILE_SECTIONS = {
  home: 'home',
  leads: 'leads',
  companies: 'companies',
  orders: 'orders',
  commonBilling: 'commonBilling',
  archive: 'archive',
  workerRisk: 'workerRisk',
  worker: 'worker',
  operator: 'operator',
  tbank: 'tbank',
  dictionaries: 'dictionaries',
  adminUsers: 'adminUsers',
  botBrowser: 'botBrowser'
} as const;

export type MobileSection = typeof MOBILE_SECTIONS[keyof typeof MOBILE_SECTIONS];

export const MOBILE_ACTIONS = {
  view: 'view',
  create: 'create',
  edit: 'edit',
  delete: 'delete',
  import: 'import',
  assign: 'assign',
  restore: 'restore',
  manage: 'manage'
} as const;

export type MobileAction = typeof MOBILE_ACTIONS[keyof typeof MOBILE_ACTIONS];

type MobileActionMatrix = Record<MobileSection, Partial<Record<MobileAction, MobileRoleSet>>>;

export const MOBILE_ACTION_MATRIX: MobileActionMatrix = {
  home: {
    view: MOBILE_ROLES.authenticated
  },
  leads: {
    view: MOBILE_ROLES.leads,
    create: MOBILE_ROLES.leads,
    edit: MOBILE_ROLES.manager,
    delete: MOBILE_ROLES.ownerAdmin,
    import: MOBILE_ROLES.ownerAdmin,
    assign: MOBILE_ROLES.ownerAdmin,
    manage: MOBILE_ROLES.ownerAdmin
  },
  companies: {
    view: MOBILE_ROLES.manager,
    create: MOBILE_ROLES.manager,
    edit: MOBILE_ROLES.manager,
    delete: MOBILE_ROLES.ownerAdmin,
    manage: MOBILE_ROLES.manager
  },
  orders: {
    view: MOBILE_ROLES.manager,
    create: MOBILE_ROLES.manager,
    edit: MOBILE_ROLES.manager,
    delete: MOBILE_ROLES.ownerAdmin,
    manage: MOBILE_ROLES.manager
  },
  commonBilling: {
    view: MOBILE_ROLES.manager,
    create: MOBILE_ROLES.manager,
    edit: MOBILE_ROLES.manager,
    delete: MOBILE_ROLES.ownerAdmin,
    manage: MOBILE_ROLES.manager
  },
  archive: {
    view: MOBILE_ROLES.manager,
    restore: MOBILE_ROLES.manager,
    manage: MOBILE_ROLES.ownerAdmin
  },
  workerRisk: {
    view: MOBILE_ROLES.manager,
    manage: MOBILE_ROLES.manager
  },
  worker: {
    view: MOBILE_ROLES.worker,
    edit: MOBILE_ROLES.worker,
    manage: MOBILE_ROLES.manager
  },
  operator: {
    view: MOBILE_ROLES.operator,
    edit: MOBILE_ROLES.operator,
    manage: MOBILE_ROLES.ownerAdmin
  },
  tbank: {
    view: MOBILE_ROLES.ownerAdmin,
    create: MOBILE_ROLES.ownerAdmin,
    edit: MOBILE_ROLES.ownerAdmin,
    delete: MOBILE_ROLES.ownerAdmin,
    manage: MOBILE_ROLES.ownerAdmin
  },
  dictionaries: {
    view: MOBILE_ROLES.manager,
    create: MOBILE_ROLES.ownerAdmin,
    edit: MOBILE_ROLES.ownerAdmin,
    delete: MOBILE_ROLES.ownerAdmin,
    import: MOBILE_ROLES.ownerAdmin,
    manage: MOBILE_ROLES.ownerAdmin
  },
  adminUsers: {
    view: MOBILE_ROLES.ownerAdmin,
    create: MOBILE_ROLES.ownerAdmin,
    edit: MOBILE_ROLES.ownerAdmin,
    delete: MOBILE_ROLES.ownerAdmin,
    manage: MOBILE_ROLES.ownerAdmin
  },
  botBrowser: {
    view: MOBILE_ROLES.botBrowser,
    manage: MOBILE_ROLES.botBrowser
  }
};

export const MOBILE_ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Админ',
  OWNER: 'Владелец',
  MANAGER: 'Менеджер',
  WORKER: 'Специалист',
  OPERATOR: 'Оператор',
  MARKETOLOG: 'Маркетолог',
  CLIENT: 'Клиент'
};

export function canUseSection(userRoles: readonly string[] | undefined, allowedRoles: readonly string[]): boolean {
  if (!allowedRoles.length) {
    return true;
  }
  return allowedRoles.some((role) => userRoles?.includes(role));
}

export function canUseAction(
  userRoles: readonly string[] | undefined,
  section: MobileSection,
  action: MobileAction
): boolean {
  return canUseSection(userRoles, MOBILE_ACTION_MATRIX[section][action] ?? []);
}

export function rolesForAction(section: MobileSection, action: MobileAction): MobileRoleSet {
  return MOBILE_ACTION_MATRIX[section][action] ?? MOBILE_ROLES.authenticated;
}
