# Подготовка доказательств C36 после публикации в GitHub Actions

Этот шаг выполняется только после настоящих запусков `quality-gates.yml` с
`publish-reviewed-images=true` и нужным `reviewed-image-set=c36-<component>`.
Артефакты нельзя создавать вручную или восстанавливать по образцу старого выпуска.
Скрипт проверяет сохранённые байты, но сам не подтверждает происхождение скачанного
архива из GitHub Actions: номер запуска, попытку и SHA коммита нужно брать со
страницы конкретного запуска. Артефакты CI хранятся 14 дней.

Компоненты: `dozzle`, `prometheus`, `loki`, `alloy`, `tempo`, `grafana`, `mc`,
`minio`, `postgres`. Для каждого компонента используйте его собственную пару
артефактов **из одного запуска и одной попытки**:

```powershell
$component = 'dozzle'
$run = 'НОМЕР_ЗАПУСКА'
$attempt = 'НОМЕР_ПОПЫТКИ'
$commit = 'ПОЛНЫЙ_SHA_КОММИТА_ЗАПУСКА'
$download = 'E:\Works\Projects\otziv\.codex-tmp\c36-' + $component
gh run view $run --repo Claidd/otziv_o
gh run download $run --repo Claidd/otziv_o --name "reviewed-publication-$component-attempt-$attempt" --dir "$download\publication"
gh run download $run --repo Claidd/otziv_o --name "anonymous-download-$component-attempt-$attempt" --dir "$download\anonymous"
node infrastructure/runtime-security/prepare-c36-activation.mjs "c36-$component" "$download\publication" "$download\anonymous" $commit $run $attempt
```

Последняя команда работает только на чтение (`DRY_RUN`). Она требует PASS у
публикации, сканирования и независимой анонимной загрузки, сверяет SHA
манифеста и Dockerfile, raw-отчёт, verdict, OCI index, конфигурацию, provenance,
SBOM, digest образа и identity запуска. Для всех компонентов кроме PostgreSQL
она также выполняет существующий валидатор активации. Если любой шаг не
сошёлся, ничего не записывается.

Когда dry run прошёл, проверьте `release_session.py status` и повторите команду
с `--write`. Скрипт тоже проверит отсутствие активной сессии перед записью.
Он создаст `proofs/c36-<component>/publication/`, `anonymous/` и файл
`activation-candidate.json`. Существующий каталог он не перезаписывает.
Скрипт не меняет `reviewed-image-activations.json` или Compose: кандидат и
образы нужно включить отдельной проверяемой правкой, затем запустить CI.

Для PostgreSQL результат всегда **draft**, даже после успешной проверки пары:
`proofs/c36-postgres/activation-draft.json` не является записью активации.
Нужна отдельная проверка перехода C23 → C36 → C23 на том же томе данных с
активным C35 Keycloak. Полученный подлинный `acceptance.json` должен войти в
запись как `postgresC36Acceptance`; `databaseTransition` должен ссылаться на
те же путь и SHA. До этого нельзя заменять образ базы в Compose.

Для Grafana C36 требуется ещё закреплённая identity точного собранного бинарника
в `grafana-tempo-adjudication.mjs`. Пока она не завершена, валидатор правильно
отклонит кандидат даже при успешно скачанных артефактах.

После помещения настоящих файлов в репозиторий отдельно запускайте
`node infrastructure/runtime-security/reviewed-image-defaults.mjs` и штатные
CI проверки. Успех подготовительного скрипта сам по себе не разрешает деплой.
