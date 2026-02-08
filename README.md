# Telegram Bot Emily

Kotlin Telegram-бот с:
- чатом через Venice API,
- генерацией изображений,
- оплатой тарифов/паков в Telegram Payments,
- хранением состояния и истории в Firebase Realtime Database.

## Требования
- JDK 8+ (в проекте `jvmToolchain(8)`).
- Firebase Realtime Database + service account JSON.
- Telegram Bot Token и Provider Token.
- Venice API token.

## Быстрый старт
1. Создайте `secrets.properties` в корне проекта.
2. Добавьте ключи:
```properties
TELEGRAM_BOT_TOKEN=...
PROVIDER_TOKEN=...
VENICE_TOKEN=...
FIREBASE_CREDENTIALS_PATH=/absolute/path/to/service-account.json
FIREBASE_DATABASE_URL=https://<project>.firebaseio.com
```
3. Сборка:
```bash
./gradlew -q build
```
4. Запуск:
```bash
./gradlew run
```

## Основные команды бота
- `/start`
- `/buy`
- `/balance`
- `/reset`
- `/pic`

## Где хранятся данные в Firebase
- `balances/{userId}`: актуальные лимиты, план, дата истечения, daily usage.
- `chatHistory/{userId}`: история диалога (`user`/`assistant`).
- `payments/{userId}`: оплаты (без детального usage-лога по умолчанию).
- `analytics/daily/{YYYY-MM-DD}/users/{userId}`: компактная ежедневная аналитика.
- `userActivity/{userId}`: активность и служебные маркеры.

## Аналитика расхода тарифов
Записывается агрегировано по пользователю за день:
- `spentTextTokens`
- `spentImageCredits`
- `topupTextTokens`
- `topupImageCredits`
- `plan` (`free`, `basic`, `pro`, `ultra`)
- `tariffType` (`free`/`paid`)
- `textAvailableBeforeLast`, `imageAvailableBeforeLast`
- `textLeftAfterLast`, `imageLeftAfterLast`

Это позволяет считать:
- сколько пользователей тратят free vs paid,
- сколько они тратят от доступного,
- динамику пополнений и расхода по дням.

## Оптимизация Firebase (включено)
- Детальный `payments/{userId}/usage/*` отключен по умолчанию (`BalanceRepository(enableDetailedUsageLog = false)`).
- Аналитика компактная: одна дневная агрегированная запись на пользователя вместо хранения каждого события.
- Баланс сохраняется только при фактическом изменении в `ensureUserBalance()`.

## Retention 60 дней
При старте приложения запускается фоновая очистка старых логов (`DataRetentionService`):
- удаляет `payments/{userId}/usage/*` старше 60 дней,
- удаляет `analytics/daily/{date}` старше 60 дней,
- удаляет `analytics/events/{date}` старше 60 дней (для совместимости со старым форматом).

Важно:
- Очистка не затрагивает `balances`, поэтому тарифная логика не ломается.
- Историческая аналитика старше 60 дней удаляется осознанно для экономии лимитов Firebase.

## Полезные команды разработки
```bash
./gradlew -q build
./gradlew test
./gradlew shadowJar
```
