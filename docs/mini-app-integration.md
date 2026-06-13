# Telegram Mini App

## Цель

Mini App работает как интерфейс к уже существующему Telegram-боту Emily. Основной источник данных остается в Kotlin-боте:

- персонажи и истории берутся из `BotCatalog`;
- настройки пользователя берутся из `UserSettingsRepository`;
- баланс берется из `BalanceRepository`;
- прогресс диалога берется из `ChatHistoryRepository`;
- список диалогов и сообщения диалогов берутся из `DialogRepository`;
- активный системный промпт синхронизируется через `ConversationMemory`.

Внешний репозиторий `telegram_bot_story_girl_mini_app` использовался только как пример UX-потока: выбор персонажа, выбор истории, отправка результата обратно в Telegram. Python/Flask/SQLite-часть не переносилась напрямую, потому что в текущем проекте серверная логика должна жить в Kotlin и использовать Firebase.

## Пользовательский сценарий

1. Пользователь открывает Mini App через `/app`, кнопку в приветствии или кнопку меню Telegram.
2. Mini App запрашивает `/miniapp/api/bootstrap`.
3. Пользователь выбирает персонажа.
4. Открывается экран выбора истории.
5. Пользователь выбирает историю или нажимает `Пропустить выбор истории`.
6. Mini App сохраняет выбор через Kotlin API и создает новый диалог в Firebase.
7. Kotlin API отправляет пользователю шаблонное сообщение в Telegram через Bot API.
8. Mini App открывает чат бота, где уже активны выбранный персонаж и история.

Если история выбрана, бот очищает старую историю чата, выставляет системный промпт персонажа с условиями сюжета и добавляет стартовую реплику истории.

Если история пропущена, бот очищает выбранную историю, оставляет выбранного персонажа и запускает обычный режим общения без сценария.

Возврат в бот не зависит от `Telegram.WebApp.sendData`: выбор уже сохранен на сервере, а сообщение в чат отправляется напрямую через Bot API. Это надежнее для Mini App, открытого через menu button или inline web app кнопку.

## Диалоги

Mini App показывает экран `Диалоги`, похожий на список чатов:

- аватар персонажа;
- имя персонажа;
- название истории или `Свободный чат`;
- последняя реплика;
- время последнего обновления.

При нажатии на диалог Mini App вызывает `/miniapp/api/restore-dialog`. Сервер:

- выставляет `selectedCharacter`;
- выставляет или очищает `selectedStory`;
- сохраняет `activeDialogId`;
- очищает текущий `chatHistory`;
- восстанавливает сообщения выбранного диалога в `chatHistory` и `ConversationMemory`;
- отправляет пользователю сообщение `Диалог восстановлен`;
- возвращает Mini App ссылку на чат бота.

Firebase-структура:

```text
dialogSessions/{userId}/{dialogId}
  id: string
  characterId: string
  characterName: string
  characterImageUrl: string
  storyId: string | null
  storyTitle: string | null
  lastMessage: string
  lastRole: "user" | "assistant" | null
  createdAt: timestamp
  updatedAt: timestamp

dialogMessages/{userId}/{dialogId}/{messageId}
  role: "user" | "assistant"
  text: string
  createdAt: timestamp

userSettings/{userId}
  selectedCharacter: string
  selectedStory: string | null
  activeDialogId: string | null
```

## Новые сущности

`BotCatalog`

Единый каталог персонажей и историй для бота и Mini App.

- `CharacterProfile` — id, имя, описание, фото, системный промпт, персона для генерации изображений.
- `StoryScenario` — id, название, описание, сетап, инструкции для системного промпта, стартовая реплика.

## Mini App API

Все эндпоинты обслуживаются Kotlin-классом `MiniAppServer`.

- `GET /miniapp/api/bootstrap` — пользователь, настройки, баланс, персонажи, истории, последние сообщения прогресса.
- `POST /miniapp/api/select-character` — сохраняет выбранного персонажа и возвращает список историй.
- `POST /miniapp/api/select-story` — сохраняет персонажа и историю, сбрасывает старый чат, готовит режим истории.
- `POST /miniapp/api/skip-story` — сохраняет персонажа, очищает выбранную историю, сбрасывает старый чат.
- `POST /miniapp/api/restore-dialog` — восстанавливает ранее сохраненный диалог.
- `POST /miniapp/api/settings` — сохраняет пользовательские настройки, сейчас поддерживает `language`.
- `GET /miniapp/health` — простая проверка доступности сервера.

Mini App передает Telegram initData в заголовке `X-Telegram-Init-Data`. Сервер проверяет подпись по токену бота. Для локальной разработки можно указать `MINI_APP_DEV_USER_ID`, тогда API разрешит запросы без initData.

## Экраны

- `Персонажи` — карточки персонажей из `BotCatalog`.
- `Истории` — список историй после выбора персонажа.
- `Пропустить выбор истории` — отдельная кнопка на экране историй.
- `Настройки` — язык и текущий выбор пользователя.

## Настройки

В `secrets.properties` можно добавить:

```properties
MINI_APP_ENABLED=true
MINI_APP_PORT=8080
MINI_APP_URL=https://your-domain.example/miniapp/
MINI_APP_DEV_USER_ID=123456789
```

`MINI_APP_URL` должен быть публичным HTTPS-адресом, который Telegram может открыть внутри WebApp. Кнопки Mini App в боте появляются только при заданном `MINI_APP_URL`.

Если нужно проверить сервер локально без Telegram initData, укажи `MINI_APP_ENABLED=true` и `MINI_APP_DEV_USER_ID`. В этом режиме сервер поднимется на локальном адресе `http://localhost:8080/miniapp/`, но Telegram-кнопка не появится без публичного URL.

## Что важно не дублировать

- Не заводить отдельный список персонажей во фронтенде.
- Не хранить прогресс в браузере как источник правды.
- Не переносить SQLite-модели из примера.
- Не выбирать историю без сохранения в `UserSettingsRepository`.
- Не менять промпты Mini App отдельно от бота.

Так Mini App остается расширением бота, а не отдельным приложением с расходящейся логикой.
