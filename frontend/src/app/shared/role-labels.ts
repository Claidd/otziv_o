const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Администратор',
  OWNER: 'Владелец',
  MANAGER: 'Менеджер',
  WORKER: 'Специалист',
  OPERATOR: 'Оператор',
  MARKETOLOG: 'Маркетолог',
  CLIENT: 'Клиент',
  USER: 'Пользователь'
};

export function normalizeRole(role: string | null | undefined): string {
  return role?.trim().replace(/^ROLE_/i, '').toUpperCase() ?? '';
}

export function roleLabel(role: string | null | undefined): string {
  const normalizedRole = normalizeRole(role);

  if (!normalizedRole) {
    return ROLE_LABELS['USER'];
  }

  return ROLE_LABELS[normalizedRole] ?? normalizedRole;
}
