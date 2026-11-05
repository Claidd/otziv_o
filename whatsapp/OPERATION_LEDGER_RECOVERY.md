# Outbound operation ledger: эксплуатация и восстановление

Файлы `<sha256(operationId)>.json` — долговечный источник истины. Индекс содержит только состояние и время начала, восстанавливается одним проходом при запуске; отдельного потенциально отстающего файла счётчика нет. На новом запросе выполняется адресное чтение записи, а не `readdir`. Записи `RUNNING` предыдущего процесса читаются как `UNKNOWN`. Перевод `UNKNOWN` в «не отправлено» и удаление записи без tombstone запрещены: это снимает защиту от повторной отправки.

## Срок хранения и tombstone

Только `SUCCEEDED` старше `WHATSAPP_OPERATION_LEDGER_RETENTION_DAYS` (по умолчанию 30 дней, минимум 7, `0` отключает) компактируются в tombstone: `tombstones/<первые 3 hex ключа>.json`, 4096 shard-файлов. Tombstone хранит ключ, envelope hash, `messageId`, время начала/завершения/сверки, receipt candidates и `providerAck` — всё, что нужно для replay, `GET /operations/:id`, конфликта envelope и окна сверки. Повтор старого ID отвечает тем же `SUCCEEDED` без отправки; другой envelope получает `operation_payload_conflict`. `RUNNING`, `UNKNOWN`, записи в работе и `SUCCEEDED`, чьё окно квитанций может пересечься с нерешённой операцией того же envelope, не компактируются. Tombstone не занимают ёмкость и не загружаются в память: при новом ID читается один shard.

Порядок crash-safe: shard пишется во временный файл с `fsync`, атомарно переименовывается, каталог `tombstones` fsync-ится, и только затем удаляются полные записи и fsync-ится каталог ledger. Сбой между шагами оставляет дубликат, который следующий проход компактирует повторно. Повреждённый shard закрывает ledger как нечитаемая запись (`operation_record_unreadable`, без автоматического восстановления). Компакция идёт фоном каждые `WHATSAPP_OPERATION_LEDGER_MAINTENANCE_INTERVAL_MS` (15 с), не более 64 shard и 5000 записей за проход, затем раз в 10 минут. Окно хранения многократно превышает окна сверки шлюза (5 мин квитанций, 30 мин history sync), а ответ по tombstone совпадает с ответом полной записи, поэтому сверка backend по ID не зависит от компакции.

## Нормальный deployment, crash и смена контейнера

Production поддерживает локальную Linux filesystem с atomic create/rename и `fsync`, один общий persistent mount для всех процессов одного клиента. NFS, объектное хранилище, несколько независимых копий одного ledger и работа старой версии gateway, не соблюдающей блокировку, не поддерживаются. При первом обновлении на эту реализацию остановить старый gateway до запуска нового. Это исключает смешанный rollout с writer, который ещё не знает о lock.

На Linux Node держит FD постоянного файла `.writer.lock`. Короткий `flock --exclusive --nonblock 3` получает блокировку на унаследованное open-file description; после выхода helper FD остаётся у Node. Второй процесс может читать известные операции, но не создавать и не изменять их. При штатной остановке `close()` ждёт сохранения всех активных результатов и reconciliation. При `SIGKILL`, crash или удалении контейнера kernel освобождает FD автоматически. Новый контейнер с **тем же актуальным volume** безопасно получает эту же блокировку и восстанавливает индекс.

Не удалять и не переименовывать `.writer.lock`, в том числе если он существует после остановки. Само наличие файла не означает наличие writer. Нет TTL, «устаревшего PID» или ручного unlink на production. `flock` обязателен; отсутствие утилиты останавливает запуск. Dockerfile проверяет её наличие.

Если новый контейнер сообщает `operation_writer_locked`, проверить и остановить прежний контейнер, использующий этот mount. После его фактического завершения перезапустить новый. Не обходить отказ созданием копии каталога. Прямой kernel lock устраняет отдельную опасную процедуру удаления persistent PID-lock.

Windows предназначена для локальной разработки: нет directory fsync, используется консервативный exclusive owner с проверкой отсутствия PID на том же host. Неоднозначная смена host/PID namespace или прерванный takeover может потребовать нового **тестового** каталога; эта ветка не является production DR workflow.

## Ёмкость и здоровье

Авторизованный `GET /internal/operation-metrics` возвращает без payload: `capacity`, `records`, `remaining`, `utilizationRatio`, `capacityWarning` (от 80%), `unknown`, `running`, `oldestUnknownAgeSeconds`, `persistenceFailures`, `readFailures`, `admissionRejected`, `writer`, `recoveryRequired`, `acceptingNew`, `code`, а также `readyUtilizationThreshold`, `capacityReady`, `readinessReady`, `readinessCode`, `retentionDays`, `pruneEligible`, `prunedTotal`, `lastPrunedAt`, `ackPending`, `healthRecoveredTotal`, `healthRecoveryFailures`, `autoRecoveryBlocked`, `repairedInterruptedClaims`. Endpoint использует существующую внутреннюю авторизацию и `Cache-Control: no-store`.

Начало новой отправки учитывает admission, `/ready` — ещё и порог заполнения: при `records >= capacity * WHATSAPP_OPERATION_LEDGER_READY_MAX_UTILIZATION` (0.95) `/ready` отвечает 503 с `operationLedgerCode=operation_ledger_near_capacity`, а отправки продолжаются до 100%. `/health` (liveness) остаётся 200; Docker не перезапускает контейнер по unhealthy, но деплой ждёт healthy. Заполненный ledger (`operation_ledger_full`), ошибка хранения, отсутствие writer или DR fence запрещают **новую** работу. Сначала проверяется существующий ID, поэтому подтверждённые повторы и чтение продолжаются при полном ledger. Ошибка записи claim исключает вызов провайдера; ошибка сохранения результата после возможной отправки оставляет защищённый `UNKNOWN`. Ответ об ошибке отправки содержит `dispatched=false`, если claim не создан и провайдер не вызывался (только writer без DR fence), и `dispatched=true`, если вызов провайдера начался.

После ошибки хранения фоновая проверка не чаще раза в минуту пишет пробный файл с `fsync`. При успехе claim этого процесса, упавший до вызова провайдера, освобождается, только если файл содержит префикс собственных байтов claim; результат, не сохранённый после вызова провайдера, дописывается из памяти поверх неизменённой записи. Нечитаемая или неожиданная запись оставляет admission закрытым до расследования и перезапуска (`autoRecoveryBlocked`). При запуске writer превращает claim, оборванный до `fsync` (строгий префикс собственной сериализации claim), в долговечный `UNKNOWN` с `interruptedClaim`; иной повреждённый файл не изменяется и закрывает admission.

До исчерпания `remaining` оценить темп роста, место на volume и допустимое время восстановления. Для увеличения ёмкости поднять `WHATSAPP_OPERATION_LEDGER_MAX_RECORDS` (максимум 1 000 000) и перезапустить gateway на том же volume; вручную записи не удалять, только штатная компакция в tombstone. Отдельно наблюдать реальное свободное место filesystem: счётчик записей не заменяет disk monitoring. Ошибку записи расследовать до перезапуска; повреждённые файлы не удалять. Рост/возраст `UNKNOWN` требует reconciliation по положительным данным провайдера, а не повторной отправки.

## Восстановление из отстающей копии: fence до запуска

Crash с актуальным volume и restore из backup — разные события. По содержимому произвольной копии невозможно доказать её актуальность. Даже присутствующая `.recovery.json` со статусом `COMPLETE` может быть старой. Каждый restore получает **новый уникальный restore ID**. Установить `WHATSAPP_OPERATION_LEDGER_RESTORE_ID` в конфигурации восстановленного gateway **до первого запуска** либо выполнить offline `prepare` ниже до запуска gateway. Сначала ограничить доступ producer к восстановленной реплике; исключить параллельную работу прежней реплики. При запуске новый restore ID записывает и fsync-ит `REQUIRED` до открытия admission. Удаление переменной после этого не снимает durable fence.

Команды ниже выполняются внутри maintenance-контейнера с той же версией Node/gateway и восстановленным volume, когда gateway остановлен. `LEDGER_DIRECTORY` обозначает именно настроенный `WHATSAPP_OPERATION_LEDGER_PATH`; shell-переменные здесь — пример, не значения production.

```sh
node operation-ledger-maintenance.js prepare --directory "$LEDGER_DIRECTORY" --restore-id "$NEW_RESTORE_ID"
node operation-ledger-maintenance.js status --directory "$LEDGER_DIRECTORY"
```

Пока `REQUIRED`, lookup отсутствующего ID возвращает `operation_recovery_required` / 503, а не 404, и execute не отправляет его. Известные `SUCCEEDED` доступны для replay; известные `UNKNOWN` сохраняются. Заблокированный второй writer не может успешно выполнить `prepare`.

## Reconcile внешней истории и открытие admission

Получить независимо от backup полный manifest всех producer operation IDs, которые могли дойти до провайдера в потерянном интервале. Остановить/зафиксировать всех producers и прежний gateway на согласованной границе. Сопоставить durable producer dispatch history, provider history и исключения до этой границы; включить также неопределённые попытки. Manifest должен использовать исходные IDs, исходный envelope hash и timestamp. Наличие ID в backup, список только успешных provider сообщений или операторское предположение не доказывают полноту. Если полноту нельзя подтвердить, fence оставить закрытым; не заменять IDs для обхода защиты.

Формат manifest:

```json
{
  "restoreId": "restore-unique-id",
  "authority": "independent-producer-export-reference",
  "watermark": "all-producers-fenced-at-authoritative-boundary",
  "operations": [
    { "operationId": "original-operation-id", "envelopeHash": "64-lowercase-hex", "startedAt": 1788825600000 }
  ]
}
```

Проверяющий фиксирует canonical hash, authority и watermark в incident evidence. `digest` только вычисляет hash; эта команда **не подтверждает полноту**. Пустой manifest допустим только при независимом доказательстве отсутствия всех отправок в потерянном интервале.

```sh
node operation-ledger-maintenance.js digest --manifest "$MANIFEST" --restore-id "$NEW_RESTORE_ID"
node operation-ledger-maintenance.js recover --directory "$LEDGER_DIRECTORY" --restore-id "$NEW_RESTORE_ID" --manifest "$MANIFEST" --expected-sha256 "$INDEPENDENTLY_VERIFIED_HASH" --authority "$VERIFIED_AUTHORITY" --watermark "$VERIFIED_WATERMARK" --confirm-authoritative-coverage
```

Последний флаг — явная offline attestation оператора после независимой проверки; программный helper не может установить историческую полноту сам. Gateway не публикует HTTP endpoint для снятия fence. Программный `reconcileRecovery` требует verifier callback, связанный с точным hash, restore ID, authority и watermark. Production автоматизация может реализовать этот callback через собственный авторитетный источник; перенос такой доверенной границы в случайные данные backup недопустим.

Recovery импортирует отсутствующие исторические IDs как долговечный `UNKNOWN`, сохраняя существующие `SUCCEEDED` и проверяя envelope conflicts. Импорт не вызывает провайдера и может превысить capacity, чтобы не потерять защиту; в этом случае новая работа остаётся остановленной по ёмкости. Только после fsync всех защищённых IDs сохраняется `COMPLETE` receipt с hash и watermark. Частичный сбой сохраняет `REQUIRED`; после устранения ошибки и перезапуска повторить тот же проверенный manifest. Существующий `UNKNOWN` можно отдельно разрешить только положительным provider evidence через `reconcile`; API «сбросить и отправить» отсутствует.

Запустить gateway, проверить metrics и readiness. Тот же завершённый restore ID не re-arm-ит fence при обычном рестарте; следующий restore обязан получить новый ID. Сохранить manifest, attestation и receipt в incident evidence вне восстанавливаемого volume.

## Воспроизводимые проверки

```sh
node --test operation-ledger*.test.js
node operation-ledger-benchmark.js
```

Linux suite проверяет отдельными Node-процессами: helper завершился, второй writer заблокирован, `SIGKILL` первого освобождает lock, новый writer использует тот же inode, прежняя отправка остаётся `UNKNOWN`. Также проверяются missing `flock`, directory fsync error, диск/rename errors, capacity/restart, DR из старой копии, partial import и offline maintenance attestation.

Benchmark создаёт 99 999 **метаданных в памяти**, без 100 000 файлов и без отправок. Он измеряет только CPU стоимость index/admission/metrics; не является измерением filesystem latency, startup сканирования production volume или пропускной способности провайдера.
