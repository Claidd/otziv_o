# C10: GitHub Actions с Node 24

Предупреждения `Node.js 20 is deprecated` относятся к внутренней среде
GitHub Actions. Обновлены 64 вызова в четырёх workflow; версии Node приложения,
Java 21/26 и .NET 8 сохранены. Каждый action закреплён по полному commit SHA,
проверенному по официальному выпуску, тегу и `action.yml`.

| Action | Выпуск | Commit SHA |
| --- | --- | --- |
| checkout | [7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| setup-node | [7.0.0](https://github.com/actions/setup-node/releases/tag/v7.0.0) | `820762786026740c76f36085b0efc47a31fe5020` |
| setup-java | [6.0.0](https://github.com/actions/setup-java/releases/tag/v6.0.0) | `dd06d9cba3e5552c54d9f8ea23572deb30010f7c` |
| setup-dotnet | [6.0.0](https://github.com/actions/setup-dotnet/releases/tag/v6.0.0) | `a98b56852c35b8e3190ac28c8c2271da59106c68` |
| upload-artifact | [7.0.1](https://github.com/actions/upload-artifact/releases/tag/v7.0.1) | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` |
| download-artifact | [8.0.1](https://github.com/actions/download-artifact/releases/tag/v8.0.1) | `3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c` |

В 12 вызовах setup-node без прежнего кеширования выключен новый автоматический
npm-кеш. В пяти вызовах setup-java с кешем зависимостей выключен новый кеш JDK.
Названия параметров учётных данных Maven приведены к `server-username-env-var`
и `server-password-env-var`; они по-прежнему содержат имена переменных окружения.

Отдельно рассмотрено изменение кеша самого setup-java: Maven wrapper теперь
хранится в `~/.m2/wrapper/dists`, Gradle wrapper — в отдельной записи для прежнего
`~/.gradle/wrapper`. Ключи включают ОС, архитектуру и хеш wrapper properties;
восстановление выполняется по точному ключу. Эти пути не включают Maven
`settings.xml` или файлы учётных данных checkout. Это описанное изменение
поставщика, поэтому тождественность прежнего поведения кеша не заявляется.
[Исходник setup-java](https://github.com/actions/setup-java/blob/dd06d9cba3e5552c54d9f8ea23572deb30010f7c/src/cache.ts).

Артефакты сохраняют ZIP-формат, имена, каталоги, сроки хранения и исключение
скрытых файлов. Загрузка выполняется по точному имени внутри того же запуска.
Новая проверка digest в download-artifact завершает операцию ошибкой при
несовпадении. Checkout сохраняет полный fetch там, где он требовался,
и `persist-credentials: false` в проверке анонимного скачивания.

Локально прошли actionlint для всех четырёх workflow, инфраструктурный контракт,
контракт развёртывания и 40 существующих тестов без ошибок и пропусков.
Проверены входы и Node 24 во всех 64 вызовах. Два существующих теста закрепления
SHA согласованы с новыми версиями. После обратной замены перечисленных SHA и
параметров получается исходный текст workflow: условия, разрешения, матрицы,
пороги аудита и команды проверок сохранены.

Freeze локального пакета: SHA-256
`cd27d53c1596cb7d0038cc8e56932444b8a1b28655916c2a49559f5e21e3991c`.
Эти проверки не исполняют JavaScript Actions на GitHub; результат нового
коммита нужно подтверждать его собственным hosted-прогоном. Предупреждения
Node 20 сами по себе не устанавливают причину ошибки Maven.

## Состояние до обновления Actions

Предыдущий коммит C9: `7141d947ef6b6172c76fdbb9f297895fda107e60`.
На срезе 2026-09-08 05:06:31 UTC завершены все 20 ожидаемых проверок PR:
18 успешны, две завершились ошибкой. Backend и issuer прошли и для PR,
и для push. Gitleaks прошёл для
[push](https://github.com/Claidd/otziv_o/actions/runs/34187577743/job/101938902604)
и [PR](https://github.com/Claidd/otziv_o/actions/runs/34187580405/job/101938910804).
[Dependency audit gate](https://github.com/Claidd/otziv_o/actions/runs/34187580370/job/101939966679)
и [Upstream image security gate](https://github.com/Claidd/otziv_o/actions/runs/34187580408/job/101943515454)
завершились ошибкой. В матрице образов не прошли проверки Alloy, MinIO,
mc, MySQL, phpMyAdmin и PostgreSQL. Здесь зафиксированы статусы заданий;
конкретные причины не выводятся из их кода выхода.
Исторические красные Gitleaks для C8 `88bbc05` не являются результатом C9.

Полная приёмка P01–P21 остаётся открытой: успешные аудиты зависимостей и образов,
обязательные проверки на `main`, производственные переходы, независимые backup
и alerts, подписанное native-обновление и поддерживаемый минимум клиента.
Штатный локальный прогон со свежей БД VPS описан в
[отчёте C8](C8_IMAGE_ACTIVATION_2026-09-08.md); обновление Actions не меняет
приложение и не выдаётся за новый прогон с БД. Merge и производственного
развёртывания в этом этапе нет.

Автоматическая проверка ранее отклонила работы по MinIO/mc из-за возможного
риска кибербезопасности, не уточнив операцию; отдельно отклонён независимый
разбор исключения JTidy. Эти операции не возобновлены, исключение JTidy
не внедрено. Границы прежних native-проверок сохранены в
[отчёте совместимости](CLIENT_RELEASE_COMPATIBILITY_2026-09-07.md).
