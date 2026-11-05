# Шаблон: официальное исправление пакета ОС поверх принятого образа

Шаблон повторяет процедуру C45 (Brotli) и C48 (apt) для Keycloak и Alloy:
новое поколение отличается от принятого родителя только официальными
пакетами Ubuntu/Debian. Все проверки C45/C48 сохранены. Изменились только
источник параметров (один спец-файл) и объём ручной работы. Цель — около
часа работы плюс время CI.

Применять, когда Trivy сообщает `FIX_REQUIRED` в `os-pkgs` принятого образа
Keycloak или Alloy, а в архиве Ubuntu/Debian уже есть исправленная версия
пакета. Для Java-библиотек, Go-бинарников и пересборок шаблон не подходит.

## Файлы

- `os-package-overlay.py` — команды `pin`, `render`, `wire`, `compress`,
  `inspect`, `verify-runtime`, `capture-window`, `accept`, `activate`.
- `os-package-overlay-rehearsal.py` — неизменённый протокол C45/C48: данные,
  вход, роли и оба пути отката. Образы задаются спец-файлом.
- `os-package-overlay.mjs` — проверка принятия, а также парность с C48.
- `os-package-overlay-registry.mjs`, `os-package-overlays.json` — реестр
  спец-файлов, привязанных по SHA-256. Зарегистрированное поколение
  автоматически становится набором публикации, проходит проверку активации и
  связку Keycloak с PostgreSQL. Оно же попадает в выбор Alloy для monitoring CI
  и в `prepare-c36-activation.mjs`. Пустой реестр ничего не меняет.
- `fixtures/os-overlay-c48-{keycloak,alloy}.parity.json` — C48 в виде
  спец-файлов. Это образец и тест: шаблон побайтно воспроизводит рецепт,
  манифест, сравнение и `acceptance.json` поколения C48.

## Порядок работы

Перед записью файлов: `python -B infrastructure/scripts/prod/release_session.py status`
и `git fetch`. Чужие незакоммиченные изменения (backend, frontend, mobile,
contracts) не трогать и не индексировать. Номер поколения — следующий
свободный `cNN` (`git ls-files infrastructure/runtime-security/reviewed-images-c*`).
Для ACL приватного каталога репетиции в `PATH` нужен PowerShell 7 (`pwsh`).

1. **Закрепить пакеты, ~5 мин.** Версии брать из `fixedVersion` в
   `vulnerabilities.triage.json`. Пакеты указывать в порядке установки:
   сначала библиотека, затем зависимый пакет.

   ```
   python -B infrastructure/runtime-security/os-package-overlay.py pin --component keycloak \
     --generation c49 --advisory CVE-YYYY-NNNNN --distribution ubuntu-22.04 --label-name apt \
     --package libapt-pkg6.0=<версия> --package apt=<версия> --deb-directory <новый пустой каталог вне репозитория>
   ```

   Родитель берётся из текущей записи `reviewed-image-activations.json`.
   Команда сначала проверяет версию по подписанному индексу архива: внутри
   одноразового контейнера родителя выполняется `apt-get update` с keyring
   этого образа и `apt-get download --print-uris`. Затем `.deb` скачивается
   по той же ссылке, размер и SHA-256 сверяются. Состав пакета читает
   `dpkg-deb` в контейнере без сети.

   Создаются: `cNN-<комп>-parent.json` (снимок активации родителя),
   `cNN-<комп>-os-packages.json` (инвентарь), `os-overlay-cNN-<комп>.json`
   (спец-файл, `status: held`), рецепт `builds/cNN-<комп>/Dockerfile` и
   манифест `reviewed-images-cNN-<комп>.json`.
2. **Просмотреть спец-файл.** Проверить:
   - `recipeComment`, метки и `protectedPaths`;
   - `changedPrefixes` — файлы, которые обязаны измениться;
   - для Keycloak — образы `rehearsal` и `coupling` (по умолчанию: текущая
     пара Keycloak/PostgreSQL, исторический C35, 102 таблицы);
   - для Alloy — `alloyAdjudication`.

   После правок выполнить `render`, затем `wire`:

   ```
   python -B infrastructure/runtime-security/os-package-overlay.py render --spec infrastructure/runtime-security/os-overlay-c49-keycloak.json
   python -B infrastructure/runtime-security/os-package-overlay.py wire --spec infrastructure/runtime-security/os-overlay-c49-keycloak.json
   ```

   `wire` добавляет запись в реестр, опцию `reviewed-image-set` в
   `quality-gates.yml` и правила `.gitattributes`. Повторный запуск только
   обновляет хэш спец-файла.
3. **Публикация, ~30–40 мин CI.** Запустить
   `node --test infrastructure/runtime-security/*.test.mjs`, закоммитить свои
   файлы и продвинуть ветку выпуска fast-forward. Затем:
   `gh workflow run quality-gates.yml --ref <ветка> -f publish-reviewed-images=true -f reviewed-image-set=c49-keycloak`.
   Удерживать кандидата отдельным кодом не нужно: пока `status: held`,
   активация отклоняется с `overlay_runtime_and_data_acceptance_required`.
4. **Перенести доказательства.**
   `gh run download <run> -n reviewed-publication-<комп>-attempt-1 -D <pub>` и
   `-n anonymous-download-<комп>-attempt-1 -D <anon>`, затем
   `node infrastructure/runtime-security/prepare-c36-activation.mjs c49-keycloak <pub> <anon> <commit> <run> 1 --write`.
5. **Проверить секрет-сканом до первого коммита.** Запустить gitleaks из
   `secret-scan.yml` по новым доказательствам в двух режимах: `dir`
   (каталог-копия) и `git` (история ветки). В C48 SBOM Alloy дал находки
   только в режиме `git`. Файлы с находками и файлы больше 5 МиБ сжать:
   `os-package-overlay.py compress --spec <спец> <пути .json>`. Их SHA-256
   запишутся в `compressedProofs`. После этого снова выполнить `wire`.
6. **Сравнить остановленные образы, ~3 мин.**
   `os-package-overlay.py inspect --spec <спец> --candidate <ghcr digest> --publication-commit <commit> --temporary-directory <tmp> --output infrastructure/runtime-security/proofs/c49-keycloak/inspection`.
   Сравнивается вся файловая система вместе с каталогами, правами и
   владельцами; контейнеры не запускаются. Допускаются только официальный
   состав пакетов, их управляющие файлы и учётные файлы dpkg/ldconfig. Также
   учитываются фильтры путей dpkg и merged `/usr`.
7. **Только Keycloak: окно захвата и репетиция, ~15 мин.** Локальный
   prod-like стенд должен быть свободен: согласовать с координатором.

   ```
   os-package-overlay.py capture-window --spec <спец> --private-directory <новый каталог вне репозитория> \
     --output infrastructure/runtime-security/proofs/c49-keycloak/rehearsal \
     --record infrastructure/runtime-security/proofs/c49-keycloak/rehearsal/capture-window.json
   os-package-overlay-rehearsal.py --spec <спец> replay --private-directory <тот же каталог> \
     --output infrastructure/runtime-security/proofs/c49-keycloak/rehearsal --candidate <ghcr digest>
   ```

   Окно (около 20 с) работает так:
   - Issuer и его база переносятся во временную сеть `--internal` с прежними
     алиасами.
   - Внутри окна выполняется неизменный захват, только чтение; проверка
     «одна общая Internal-сеть» не ослабляется.
   - Затем оба контейнера возвращаются в сеть Compose с теми же алиасами и
     адресами, временная сеть удаляется.
   - Discovery Keycloak на стенде должен вернуть 200.

   Время окна, сеть, адреса и каждая команда Docker записываются в
   доказательства. Приватный дамп и env-файлы остаются вне репозитория.
8. **Принять и активировать, ~10 мин.** Последовательность:
   1. `os-package-overlay.py accept --spec <спец>`. Команда копирует
      выполненные скрипты (если хэш не совпадает, шаг нужно повторить), пишет
      `acceptance.json` и `activation-candidate.json`.
   2. В спец-файле поставить `"status": "accepted"`, затем выполнить `wire`.
   3. `os-package-overlay.py activate --spec <спец>` — индекс активаций и три
      Compose-файла переходят от родителя к кандидату.
   4. Если Keycloak: в `infrastructure/scripts/prod/test_coordinated_ssl_refresh.py`
      указать новый манифест.
   5. Прогнать проверки:
      - `node --test infrastructure/runtime-security/*.test.mjs`;
      - `node infrastructure/runtime-security/reviewed-image-defaults.mjs .`;
      - `infrastructure/scripts/security/check-infrastructure-contract.ps1`;
      - `infrastructure/scripts/security/check-deploy-release-contract.ps1`
        (запускать из PowerShell после коммита);
      - Python-тесты, которые затрагивались.
   6. Закоммитить и продвинуть ветку выпуска. Деплой — только через
      `deploy.ps1` по `AGENTS.md`.

## Что проверяет активация

- Точные байты официальных `.deb` и их подпись в индексе архива.
- Цепочка доверия родителя целиком: его собственная активация проверяется
  повторно.
- Свежий скан опубликованного digest: CVE отсутствует, версии пакетов и PURL
  точные. Анонимная загрузка.
- Сравнение остановленных образов, конфигурация и метки, неизменность
  провайдера Keycloak и бинарника Alloy. Для Alloy дополнительно: тот же
  разбор бинарника, что у родителя.
- Для Keycloak: 54 проверки репетиции, покрытие всех таблиц, оба пути отката,
  окно захвата и связка с принятым PostgreSQL C36.

## Ограничения

- Поддерживаются только компоненты `keycloak` и `alloy`. Для Alloy набор
  поддерживаемых разборов пока равен `c43`: это проверка docker/docker,
  унаследованная меткой `alloy-daemon-c43`.
- Если скрипт сопровождающего пакета меняет файлы вне своего состава,
  сравнение откажет. Это намеренно: такой случай требует отдельного разбора,
  а не расширения списка допустимых путей.
- Публикацию, слияние и деплой шаблон не выполняет.
