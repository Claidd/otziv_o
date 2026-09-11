# Классификация исторических lead-команд

`POST /api/admin/lead-commands/classify-legacy` доступен ADMIN/OWNER. Он классифицирует историю и ничего не отправляет receiver. Существующие корректные payload не означают подтверждённую недоставку: они получают UNKNOWN; исчерпанные попытки — DEAD, пустой/повреждённый/неподдерживаемый payload — QUARANTINED. Причины — фиксированные коды, без тела сообщения, телефона или remote response.

1. Выполнить `?dryRun=true&limit=500&afterId=0`; сохранить возвращённый `throughId` и `nextAfterId`.
2. Продолжать с тем же `throughId` и предыдущим `nextAfterId` как `afterId`, до `complete=true`. Каждый ответ содержит IDs, proposedState, reason, outcome и counts. Новые записи выше high-water не смешиваются с этим проходом.
3. Проверить полный report, а не первую страницу. Для выполнения начать новый проход с afterId0 и сохранённым high-water, `dryRun=false`.
4. После каждого ответа сохранить cursor и report. Возобновление использует последний сохранённый cursor. Повтор ранее применённой страницы безопасен: строки уже не LEGACY и второй CLASSIFY audit не создаётся.
5. `scanned` — число кандидатов страницы, `changed` — успешные CAS из LEGACY, `conflicts` — конкурентно изменившиеся строки. В dry-run changed/conflicts0. Суммировать только неперекрывающиеся страницы одного прохода; rejected CAS не считается changed и не создаёт audit.
6. Перед любым разрешением UNKNOWN сверить receiver persistent ledger. `POST /{id}/resolve` требует осмысленного actor/reason и допустимого текущего состояния. Не разрешать retry по одному факту отсутствующего локального ответа.

Пример page response содержит только administrative данные:
```json
{"dryRun":true,"afterId":0,"throughId":1203,"nextAfterId":500,"complete":false,"scanned":500,"changed":0,"conflicts":0,"reasons":{"LEGACY_DELIVERY_UNCONFIRMED":500},"rows":[{"id":1,"proposedState":"UNKNOWN","reason":"LEGACY_DELIVERY_UNCONFIRMED","outcome":"DRY_RUN"}]}
```
В примере rows сокращён для чтения; настоящий ответ возвращает все rows своей страницы. Контракт ответа не содержит payload/PII. Авторизация и database credentials не передаются в query string; использовать существующую аутентификацию приложения.

Проверка: `LeadLegacyClassificationMySqlIntegrationTest` — настоящий repository proxy/MySQL,1203 records полный keyset traversal, неизменность dry-run, safe high-water, отдельные invalid reasons и конкурентный CAS с точным audit/count. Совмещённый log `legacy-maintenance-targeted-v3.log` —23/23 PASS; каталог `.codex-tmp/remediation-completion-20260907/`. Перформерские pre-Flyway операции имеют отдельный [runbook](LEGACY_QUEUE_MAINTENANCE.md). Этот инструмент не запускался на VPS.
