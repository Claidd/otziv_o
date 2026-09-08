# ADR-006. Проверяемый выпуск и восстановление системы

Статус: принято; production drills и целевые RPO/RTO подтверждаются отдельно.
Дата: 2026-09-07. Владельцы: эксплуатация и владельцы выпускаемых компонентов.
Пункты: P07–P10/P16/P21.

Зелёный unit suite не доказывает восстанавливаемость системы, работоспособность
native release или независимость мониторинга от отказавшего Docker-host.

Release set связывает exact commit, проверенный source snapshot, image digest,
schema, SDK и APK hash. Deployment проверяет lineage и пригодность подготовленного
среза. CI имеет постоянный aggregate status для условных проверок; skipped job
с изменённым scope не считается успехом. Branch rules сверяются с реально
появляющимися статусами. Запуск workflow на другом revision не подтверждает этот release.

Локальная интеграционная приёмка использует штатный `prod-like-smoke.ps1` со свежим
VPS restore и отключёнными внешними отправками. Миграции проверяются на пустой БД
и совместимом upgrade; in-flight writers/consumers выводятся перед сменой семантики.
Enforce/dispatcher cutover включается по типу изменения после необходимой проверки,
а не автоматически вместе со сборкой образа.

Recovery point включает MySQL, Keycloak PostgreSQL, объекты, секреты/ключи, signing
artifacts и integration ledger. Version-bound encrypted download и manifest —
необходимые части; external attestation не доказывает реальный write fence или restore.
Закрытие DR требует согласованного восстановленного стенда с login/refresh/decryption,
измеренными RPO/RTO и проверкой доступности копий независимо от production host.

Внешний monitor получает текущие наблюдения с timestamp и явным unavailable/disabled
состоянием. Protected HTTPS collector не обновляет возраст старой копии простым
перегенерированием JSON. Durable alert/resolve и независимый deadman проверяются
фактической доставкой при отказе host/channel; mock receiver лишь проверяет код.

Rollback кода не равен restore всей БД и не отменяет уже выполненный внешний эффект.
Registry/intents и расширенная совместимая схема сохраняются. Android recovery
release имеет ту же подпись/package и больший versionCode; опубликованный APK
не подменяется под прежним номером.

Опора: [rollout](ARCHITECTURE_ROLLOUT.md), [CI runbook](R10_CI_AND_REPOSITORY_CLEANUP.md),
[recovery CLI](RECOVERY_CLI_LOCAL_VERIFICATION_2026-09-07.md),
[native verification](NATIVE_ANDROID_VERIFICATION_2026-09-07.md).
Отвергнуты: ручное обходное принятие Flyway history, обязательный отсутствующий CI
status, восстановление только одной БД как «полный DR» и предположение о независимости
backup/alerting по различающимся DNS-именам.
