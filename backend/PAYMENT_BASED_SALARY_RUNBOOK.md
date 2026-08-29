# Payment-based salary cutover

Этот релиз меняет финансовое основание начисления: активная ЗП по заказу допустима только при
точном статусе заказа `Оплачено`. Публикация и выполнение больше не являются событиями ЗП.

## До деплоя

1. Сделать проверяемый backup как минимум таблиц `orders`, `order_statuses`, `zp`,
   `contractor_reward_ledger`, `contractor_reward_sync_markers`,
   `contractor_completion_reward_markers`, `contractor_payment_rollout_state` и
   `business_audit_events`.
2. Убедиться, что локальный `prod-like-smoke.ps1` завершился успешно.
3. Остановить все старые экземпляры приложения до запуска Flyway. Старый код знает authority
   `COMPLETION`, а миграция `V1_10_267` атомарно заменяет его на `PAYMENT`; смешивать две версии
   writer нельзя.
4. Не переключать authority вручную и не выполнять отдельные UPDATE по ЗП: перенос данных уже
   входит в миграцию и сначала пишет аудит.

## Что делает V1_10_267

- переводит authority `COMPLETION -> PAYMENT`;
- оставляет преждевременные строки `zp` на месте, но делает их неактивными;
- деактивирует соответствующий канонический ledger и синхронизирует его маркеры;
- исправляет дату строк, созданных раньше последующей оплаты, на `order_pay_day`;
- сохраняет каждую коррекцию в `business_audit_events`;
- ставит декларативные DB-ограничения на `zp`, ledger и снятие оплаченного статуса;
  они работают при включённом binary log без `SUPER`/`TRIGGER` у приложения.

Повторное применение начисления не создаёт новую строку: уникальный логический ключ из
`V1_10_227` приводит к обновлению или реактивации прежней строки.

## Проверка сразу после миграции

Все запросы ниже должны вернуть `0`, кроме запроса authority, который должен вернуть `PAYMENT`.

```sql
SELECT accounting_authority
FROM contractor_payment_rollout_state
WHERE id = 1;

SELECT COUNT(DISTINCT z.zp_order) AS unpaid_orders_with_active_zp
FROM zp z
JOIN orders o ON o.order_id = z.zp_order
LEFT JOIN order_statuses s ON s.order_status_id = o.order_status
WHERE z.zp_active = 1
  AND COALESCE(s.order_status_title, '') <> 'Оплачено';

SELECT COUNT(DISTINCT l.order_id) AS unpaid_orders_with_active_ledger
FROM contractor_reward_ledger l
JOIN orders o ON o.order_id = l.order_id
LEFT JOIN order_statuses s ON s.order_status_id = o.order_status
WHERE l.active = 1
  AND COALESCE(s.order_status_title, '') <> 'Оплачено';

SELECT COUNT(*) AS active_rows_earlier_than_payment
FROM zp z
JOIN orders o ON o.order_id = z.zp_order
JOIN order_statuses s ON s.order_status_id = o.order_status
JOIN contractor_completion_cutover_state c ON c.id = 1
WHERE z.zp_active = 1
  AND s.order_status_title = 'Оплачено'
  AND o.order_pay_day IS NOT NULL
  AND z.zp_date >= c.attribution_start_date
  AND z.zp_date < o.order_pay_day;

SELECT COUNT(*) AS active_duplicate_groups
FROM (
    SELECT zp_order, zp_user, zp_profession, zp_source, zp_contractor_role, COUNT(*) AS rows_count
    FROM zp
    WHERE zp_active = 1 AND zp_order IS NOT NULL AND zp_order > 0
    GROUP BY zp_order, zp_user, zp_profession, zp_source, zp_contractor_role
    HAVING COUNT(*) > 1
) duplicates;
```

Сводка сохранённого аудита:

```sql
SELECT action, COUNT(*) AS rows_count
FROM business_audit_events
WHERE action IN (
    'SALARY_AUTHORITY_MIGRATED',
    'UNPAID_SALARY_QUARANTINED',
    'UNPAID_LEDGER_QUARANTINED',
    'UNPAID_SALARY_MARKER_RESET',
    'SALARY_DATE_ALIGNED_TO_PAYMENT'
)
GROUP BY action
ORDER BY action;
```

Сверить количество и сумму `UNPAID_SALARY_QUARANTINED` с сохранённым incident snapshot. Числа
могут отличаться от 119 / 61 075,50 ₽ только на документированные изменения, произошедшие после
снимка 27 августа 2026 года.

## Агрегаты текущего месяца

`analytics_salary_source` является единым источником персональной зарплатной аналитики. После
миграции пересобрать август 2026 штатным `rebuild-month` из
`ANALYTICS_AGGREGATES_RUNBOOK.md`, затем выполнить compare. До `matches=true` не включать чтение
агрегатов. Текущий UI без закрытого агрегата читает актуальные канонические строки напрямую.

## Наблюдение после запуска

- readiness и scheduler должны показывать нулевое число заказов без оплаты с активной ЗП;
- повторный webhook оплаты не должен менять число активных логических строк;
- полный возврат должен оставить строки в `zp`, но сделать `zp_active=0` и `ledger.active=0`;
- смена способа/получателя оплаты не должна создавать или изменять строки ЗП;
- ошибки DB-ограничений являются финансовым fail-closed событием: не обходить их ручным отключением.

Откат на старый application writer после миграции запрещён. При проблеме остановить запись и
выпустить forward-fix; восстановление backup допустимо только вместе с возвратом всей версии БД
и приложения в согласованную точку.
