@echo off
echo 🔻 Остановка и удаление текущих контейнеров...
docker-compose down -v

echo 🧹 Удаление старых образов...
docker image prune -a -f

echo 🛠 Пересборка без кэша...
docker-compose build --no-cache

echo 🚀 Запуск проекта...
docker-compose up -d

echo ✅ Готово. Логи контейнера client1:
docker logs -f client1
