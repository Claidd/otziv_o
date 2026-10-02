# Ручной benchmark транзакций очереди

`queue-saturation.mjs` — opt-in проверка P21.5, отдельно от CI unit/full suite.
Он компилирует текущие **production** `LeadCommandRepository` и
`PerformerNotificationRepository`, затем вызывает их через Spring transaction
proxies на отдельном MySQL. Backend source, настройки приложения и рабочие базы
не меняются. Класс `QueueSaturationBenchmark` находится вне Maven test sources.

Нужны локальный Docker, JDK 26, Node и dependency classpath из XML завершённого
Surefire test. Например, такой XML создаёт существующий `LeadCommandCodecTest`;
подготовку зависимостей следует выполнять в отдельном Maven snapshot. Runner
использует зависимости из classpath, но самостоятельно компилирует два актуальных
repository source файла в новый каталог, который стоит первым в runtime classpath.

Пример из PowerShell; пути classpath report/JDK заменить своими:

```powershell
node infrastructure/runtime-security/queue-saturation.mjs `
  --confirm-local-synthetic `
  --java-home "E:/path/to/jdk-26" `
  --classpath-report "E:/snapshot/backend/target/surefire-reports/TEST-com.hunt.otziv.l_lead.service.LeadCommandCodecTest.xml" `
  --output ".codex-tmp/queue-saturation-new-run"
```

`--quick` оставляет те же 6 scenarios/инварианты с меньшим fixture. Существующий
output каталог не перезаписывается. Ни один режим не обращается к provider HTTP.
Container image MySQL закреплён digest; Docker context должен быть локальным.

Стандартный профиль на каждую очередь и concurrency **1/4/8**:

- 20 000 terminal history rows, затем 400 pending и 1 200 новых команд.
- Целевая скорость admission 200/s; реальная скорость и отставание производителя
  измеряются отдельно. При достижении предела MySQL нельзя считать requested rate
  достигнутым фактом. Lead commands распределены по 256 агрегатам с FIFO blockers.
- Детерминированный workload/operation IDs; каждый twentieth sequence задаёт
  UNKNOWN, явный rejection либо принудительно истёкший lease. Остальные завершаются.
- Provider заменён **20ms локальной паузой вне транзакции**. Это модель задержки,
  не измерение Telegram/VPS. Heartbeat API у этих repositories отсутствует;
  performer `owns()` проверяет текущую lease, но не продлевает её.
- Hikari максимум 4 connections; MySQL 2 CPU/768MiB, buffer pool 128MiB;
  JVM heap 512MiB, connection timeout 10s/socket timeout 15s.
- Подтверждённый MySQL **1213 / SQLSTATE 40001** означает rollback SQL-транзакции.
  Только такой отказ driver повторяет с 20ms backoff, максимум 100 повторов,
  со счётчиком по фазе. Повторяется claim/admission/ack DB transaction, а не provider.
  Это явно заданная политика benchmark driver, **не production scheduler policy**.
  Timeout/неопределённый commit этой веткой не повторяется.

После admission выполняется bounded drain. Фиксированная модель проверяет точные
SQL totals, attempts, terminal/blocked rows, единственный claim каждой операции и
отказ late ack после expiry. Затем 32 дополнительных claim подтверждают, что UNKNOWN
и FIFO-blocked followers не отправляются повторно. Производственный код не заменён
переписанным SELECT: текст и SHA256 фактически исполненного SQL собираются через
JDBC proxy, вместе с числом выполнений/ошибок и временем каждой фазы.

Артефакты: `manifest.json` с source/class hashes, `compile.log`, `run.log`,
`result.json` и отдельный `{lead,performer}-c{1,4,8}.json`. Последние содержат
latency distributions, actual admission, backlog timeline, Hikari active/awaiting,
MySQL status counters, Docker resources и exact SQL counts/hashes. Успешное удаление
созданного MySQL контейнера проверяется runner. Ошибочный прогон сохраняется отдельно.

Fixture применяет production queue migrations/indexes, но минимальные assignment/
offer tables не содержат посторонних business fields/FK. Payload здесь проверяет
repository persistence path; codec/receiver/business semantics покрыты отдельными
integration tests. Lead payload — короткий sequence token: этот профиль не измеряет
максимальный размер payload, сериализацию или сетевую пропускную способность.
Shared host noise фиксируется перед стартом. Результат не является
полным application benchmark или согласованным production SLO.

## Локальный прогон 2026-09-07

Все 6 стандартных scenarios прошли проверки totals, fencing и отсутствия повторного
claim UNKNOWN. Сводка с source/class/query hashes и SQL counts сохранена в
`QUEUE_SATURATION_2026-09-07.json`; raw scenarios — в указанном там evidence каталоге.

| Очередь | Consumers | Завершённых операций/s | Пик незавершённых | Пик ожидания connection | Подтверждённые rollback retries |
|---|---:|---:|---:|---:|---:|
| Lead | 1 | 24.91 | 1232 | 0 | 0 |
| Lead | 4 | 108.84 | 419 | 1 | 0 |
| Lead | 8 | 72.05 | 401 | 5 | 5 claim |
| Performer | 1 | 25.84 | 1312 | 0 | 0 |
| Performer | 4 | 102.34 | 409 | 1 | 1 ack |
| Performer | 8 | 106.87 | 400 | 5 | 1 ack |

Для каждого lead scenario SQL подтвердил: 1237 SUCCEEDED, 38 DEAD, 26 UNKNOWN,
299 READY за блокирующими командами; 1301 claim/attempt. Для performer:
1360 SENT, 80 BLOCKED, 160 UNKNOWN; 1600 claim/attempt. Наличие 299 заблокированных
lead followers — ожидаемый результат консервативного FIFO, а не незамеченный drain.

Первый quick run сохранён как RED: реальный MySQL deadlock при concurrency 4.
Повторы rollback-транзакций в driver добавлены явно и учитываются в последующих
артефактах; это не скрывает contention и не меняет production retry policy.
Стандартные repository scenarios завершились PASS. Отдельная завершающая проверка
cleanup обнаружила конфликт CLI options `docker ps --quiet/--format`; runner исправлен,
read-only проверка подтвердила удаление owned MySQL. Она записана отдельно в
`cleanup-verification.json`, без переписывания исходных измерений/manifest.
