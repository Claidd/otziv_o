# ADR-013: граница специализированной генерации AI

Дата: 8 сентября 2026. Статус: принято для текущей волны A12; изменение исходников не требуется.

## Контекст и проверенная цепочка

`AiSingleReviewDraftFactory` в application прямо зависит от infrastructure-класса `OpenAiProvider`. Фабрика вызывает `generateSingleReviewDraft`, `generateBatchReviewDraft` и `generateBatchReviewWritingGuide`, передаёт `contentPackProfile`, разбирает ответы и применяет правила безопасности/качества результата. Зависимость слоёв реальна и остаётся архитектурным ограничением.

Название `OpenAiProvider` не описывает весь фактический маршрут. Его специализированные методы делегируют `OpenAiResponsesClient`, который выбирает DeepSeek, YandexGPT или OpenAI через `ReputationAiProviderSelectionService`. Клиент проверяет runtime switch перед генерацией; доступность также учитывает выключатель. Для draft/batch/guide используются профильные options, timeout и разные верхние пределы output tokens (2400/9000/3600, дополнительно ограниченные настройками маршрута). OpenAI-ветки формируют разные JSON schemas; single draft также может включать web search. Provider переносит в `AiResponse` текст, имя активного провайдера, input/output tokens и ошибку.

Существующие `AiProvider` и `AiProviderRouter` предоставляют общий `generate(AiRequest)`, имя и доступность; router добавляет выбор реализации и runtime guard. Они не выражают специализированные draft/batch/guide capabilities и профильный аргумент. Прямая замена трёх методов на общий `generate()` без переноса поведения изменит контракт сценария. Факт наличия нескольких внешних маршрутов уже учтён текущим клиентом и сам по себе не требует второго router.

Проверенные источники:

- [AiSingleReviewDraftFactory](../backend/src/main/java/com/hunt/otziv/reputationai/application/service/AiSingleReviewDraftFactory.java): поле зависимости и вызовы методов, строки 388–395, 427, 536, 617; повторные single draft вызовы 650 и 691.
- [OpenAiProvider](../backend/src/main/java/com/hunt/otziv/reputationai/infrastructure/ai/openai/service/OpenAiProvider.java): специализированные методы и перенос metadata, строки 62–105.
- [OpenAiResponsesClient](../backend/src/main/java/com/hunt/otziv/reputationai/infrastructure/ai/openai/service/OpenAiResponsesClient.java): доступность/активный маршрут 125–159, draft/batch/guide 383–522, runtime guard и выбор маршрута 2211–2233.
- [AiProvider](../backend/src/main/java/com/hunt/otziv/reputationai/infrastructure/ai/service/AiProvider.java) и [AiProviderRouter](../backend/src/main/java/com/hunt/otziv/reputationai/infrastructure/ai/service/AiProviderRouter.java): фактический общий интерфейс и его маршрутизация.

## Решение

В этой волне сохраняем явную зависимость фабрики от существующего специализированного adapter. Принимаем локальное application → infrastructure coupling; не распространяем это разрешение на новые сценарии. Новый общий wrapper/router без самостоятельного контракта не вводим. Документ фиксирует допустимое текущее решение, а не доказательство полной изоляции application.

Узкий нейтральный application port для draft/batch/guide выделяется при одном из следующих конкретных требований:

- специализированную генерацию нужно реализовать вторым независимым adapter, который нельзя безопасно подключить существующим маршрутом клиента;
- требуется отдельная исполняемая граница application без инфраструктурных AI DTO/классов либо независимая поставка/тестирование фабрики через контракт capabilities;
- появляется отдельная политика выбора, отказов или жизненного цикла этих capabilities, которую текущий специализированный adapter не может выразить без смешения сценариев.

Порт должен принадлежать application и выражать именно нужные draft/batch/guide операции и их входы/результаты. Существующий клиент становится adapter этого порта. Расширять общий `AiProvider` всеми специализированными методами по умолчанию не требуется.

При выделении порта проверяются сохранение profile selection, token budgets/timeouts, runtime disable до отправки, маршрутов всех поддерживаемых провайдеров, структурированных ответов и task-specific возможностей, provider/usage/error metadata, а также нынешней обработки пустого/ошибочного ответа фабрикой. Отдельные причинные тесты должны подтвердить это поведение, включая timeout, выключение AI и отсутствие лишнего внешнего вызова.

## Объём решения

ADR выполняет условный пункт о решении AI-границы в [плане A12](ARCHITECTURE_CORRECTION_PLAN_2026-09-08.md). Обязательная миграция общих web/mobile контрактов и оставшиеся зависимости A12 этим решением не закрываются. Проверка для ADR — чтение текущих исходников; новых тестов, сборок и проверок внешних AI API не выполнялось.
