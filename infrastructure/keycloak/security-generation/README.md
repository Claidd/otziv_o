# Образ issuer

Dockerfile сохраняет Keycloak 26.7.3 и provider этого каталога, использует
Temurin 21 JRE на Ubuntu 22.04 и собирает PostgreSQL, health и metrics.
[Keycloak поддерживает Java 21](https://www.keycloak.org/server/supported-configurations).
Базовые образы закреплены digest; обновления Ubuntu устанавливаются при
сборке. Для выпуска сохраняйте итоговый image ID, SBOM и результаты сканирования:
пакетный репозиторий может измениться между сборками одного Dockerfile.

Provider компилируется и проходит `mvn verify` внутри отдельной стадии Docker
на Maven 3.9.15 и Java 21. Контекст содержит `pom.xml` и `src`, а готовый JAR
копируется из этой стадии. Сборка образа не создаёт `target` в Git checkout:
публикация проверяет чистоту всего дерева, включая исключённые из Git файлы,
и требует точного коммита без суффикса `-dirty` в provenance Buildx.

В дистрибутиве также заменены полные vendor JAR Jackson 2.21.6 и Parsson 1.1.9.
Каждый `ADD --checksum` проверяет SHA-256 неизменённого артефакта Maven Central.
Их исходные имена файлов сохранены для сериализованного classpath Quarkus;
фактическую версию определяют содержимое JAR и контрольная сумма.

Вместо исходного Microsoft JDBC 13.2.1 используется полный неизменённый vendor
artifact 13.4.0.jre11 из Maven Central. Docker `ADD --checksum` проверяет SHA-256
`e36f5237c1267983e5b88dc2169f6b9d7e50eceec6dc1ca31018e3877e14af66`.
Он совпадает с [опубликованным checksum](https://repo.maven.apache.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.4.0.jre11/mssql-jdbc-13.4.0.jre11.jar.sha256).
Сохраняются исходные подписи, классы, Maven metadata и manifest с
`Bundle-Version: 13.4.0.jre11`; содержимое JAR не редактируется.
Файл размещён по прежнему имени `com.microsoft.sqlserver.mssql-jdbc-13.2.1.jre11.jar`,
поскольку этот путь входит в сериализованный classpath дистрибутива Quarkus.
Версию зависимости следует определять по содержимому и SHA, а не этому имени.
[Microsoft публикует стабильные выпуски и изменения драйвера](https://github.com/microsoft/mssql-jdbc/releases).

Удаление драйвера до `kc build` действительно ломает augmentation. Удаление после
сборки не является выбранным решением: весь classpath сохранён для штатного
`start --import-realm`, `start-dev` и повторной augmentation. Проверяемая база
проекта — PostgreSQL; эти проверки не подтверждают поддержку SQL Server или
других СУБД новым составом образа.

Для кандидата 2026-09-07 фактически проверяются Java `21.0.12+8`, поставляемый с
ним `libpng 1.6.58`, UID `1000:0` и SHA provider/JDBC. Это отдельное свидетельство
состава JRE: отсутствие RPM в новом образе само по себе не доказывает устранение
ошибки встроенной в JRE библиотеки. Исходный SQL Server 13.2.1 имеет известное
расхождение classifier в scanner metadata: [выпуск Microsoft 13.2.1 уже описывает
исправление проверки имени сертификата](https://github.com/microsoft/mssql-jdbc/releases/tag/v13.2.1).
Вместо исключения/VEX или переписывания metadata используется реальное обновление.

## Проверка кандидата

Сначала соберите provider по [основному runbook](../../../docs/ISSUER_SECURITY_GENERATION.md).
Команды выполняются из корня репозитория. Используйте новое имя образа и новые
пути результатов; smoke не использует существующие базы и не публикует порты.

```sh
docker build -t otziv-keycloak-security:candidate infrastructure/keycloak/security-generation
node infrastructure/keycloak/security-generation/image-startup-smoke.mjs otziv-keycloak-security:candidate evidence/issuer-startup.json
node infrastructure/keycloak/security-generation/container-proof.mjs otziv-keycloak-security:candidate evidence/issuer-protocol.json
node infrastructure/runtime-security/scan.mjs image otziv-keycloak-security:candidate evidence/issuer-vulnerabilities.json
node infrastructure/recovery/full-system-fixture.mjs otziv-app:candidate otziv-keycloak-security:candidate evidence/full-system
```

Startup smoke использует реальные PostgreSQL и Keycloak: импортирует realm при
обычном `start`, затем проверяет тот же realm при `start-dev` с повторной
augmentation. Контейнеры ограничены памятью и соединены отдельной внутренней
сетью; очистка проверяет метку владельца. Имя `image-startup-smoke` не включает
этот дорогой Docker test в обычные unit tests.

Protocol proof проверяет реальные login/PKCE/refresh/offline, поколение сессии,
отзыв и атомарность журнала. Полный recovery proof отдельно восстанавливает
реальные paired dumps, объектные версии и секреты и проверяет backend. Требуется
именно тот immutable image ID, который прошёл сканирование и эти проверки.
Сканирование не скрывает vendor-unfixed ошибки и не является доказательством
отсутствия всех уязвимостей. Изолированный fixture не заменяет независимую
производственную копию, согласованные RPO/RTO или разрешение на выпуск.

Откат использует заранее проверенный immutable образ и совместимый backup
PostgreSQL. Не откатывайте вслепую версию Keycloak поверх уже обновлённой схемы.
Старые proof images сохраняются для воспроизводимости; известные уязвимости
такого образа не становятся допустимыми только потому, что он раньше запускался.
