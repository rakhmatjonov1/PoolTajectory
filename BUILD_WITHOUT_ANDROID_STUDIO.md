# Как получить APK без Android Studio

В проекте есть GitHub Actions workflow, который собирает debug APK в облаке GitHub.

1. Распакуйте архив PoolTajectory.
2. Откройте свой репозиторий GitHub, в котором у вас есть права на запись.
3. Загрузите файлы из распакованной папки в корень репозитория, сохранив структуру каталогов. Обновите существующие файлы проекта; обязательно добавьте `.github/workflows/android.yml`.
4. Зафиксируйте изменения кнопкой **Commit changes**.
5. Откройте вкладку **Actions** и выберите workflow **Build Android APK**. При push в ветку `main` сборка также запускается автоматически.
6. Откройте завершённый запуск workflow, прокрутите до раздела **Artifacts** и скачайте `PoolTajectory-APK`.
7. Распакуйте artifact и установите `app-debug.apk` на телефон.

Для сборки на GitHub Actions Android Studio на компьютере не требуется. Репозиторию нужны включённые GitHub Actions и разрешённые workflow-запуски.

Это debug APK, а не подписанный релиз для публикации в Google Play.
