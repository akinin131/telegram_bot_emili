# 🚀 Обновление сервера YouEmilyBot

## Документация

- Реферальная система: [docs/referral-system.md](/Users/ilya/telegram_bot_emili/docs/referral-system.md:1)
- Telegram Mini App: [docs/mini-app-integration.md](/Users/ilya/telegram_bot_emili/docs/mini-app-integration.md:1)

## 1. Остановить сервис на сервере

sudo systemctl stop youemilybot

## 2. Собрать новый JAR локально

./gradlew clean shadowJar

Готовый файл будет здесь:
./build/libs/chat_girl_sex-1.0-SNAPSHOT-all.jar

## 3. Загрузить JAR на сервер

scp ./build/libs/chat_girl_sex-1.0-SNAPSHOT-all.jar root@91.222.238.199:/root/

## 4. Запустить сервис на сервере

sudo systemctl start youemilybot

## 5. Проверить статус

sudo systemctl status youemilybot

## 6. Посмотреть логи

journalctl -u youemilybot -f

## ⚡ Быстрое обновление (всё сразу)

sudo systemctl stop youemilybot
./gradlew clean shadowJar
scp ./build/libs/chat_girl_sex-1.0-SNAPSHOT-all.jar root@91.222.238.199:/root/
sudo systemctl start youemilybot
sudo systemctl status youemilybot

## ⚠️ Примечания

- Убедись, что новый .jar заменил старый в /root/
- Если сервис не стартует — смотри логи (journalctl)
- Перед деплоем проверь, что сборка прошла без ошибок
