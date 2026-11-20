# Исправление повторного Redeclaration и HoughLinesP в PoolTajectory 0.2.2

В предыдущих архивах Kotlin-файлы лежали в `app/src/main/java`. Если обновлять репозиторий распаковкой поверх старых файлов, GitHub мог сохранить старую копию в `app/src/main/java/com/pooltrajectoy/br`, а Gradle компилировал оба набора классов с одинаковым `package com.pooltrajectory.br`.

## Что изменено

1. Актуальные Kotlin-файлы находятся только в `app/src/main/kotlin/com/pooltrajectory/br`.
2. `app/build.gradle.kts` задаёт `main.java` source root ровно как `src/main/kotlin`. Поэтому оставшиеся старые файлы в `src/main/java` не являются исходниками приложения и не компилируются.
3. GitHub Actions дополнительно удаляет `app/src/main/java` перед сборкой.
4. `HoughLinesP` теперь вызывается через 5-аргументную перегрузку OpenCV. Отбор подходящих отрезков остаётся в приложении: проверяются длина, близость к центру битка и геометрическое отклонение.
5. Версия приложения — `0.2.2`, versionCode — `4`.

## Как обновить существующий GitHub-репозиторий

Надёжнее всего заменить файлы проекта содержимым этого ZIP и обязательно обновить:

- `app/build.gradle.kts`
- `.github/workflows/android.yml`
- каталог `app/src/main/kotlin/`

Старую папку `app/src/main/java` можно удалить вручную. Но даже если она останется в репозитории, новая Gradle-конфигурация не включает её в source roots, а workflow очищает её в рабочей копии перед сборкой.

После Commit Changes открой GitHub → Actions → `Build Android APK`. В workflow закреплён Gradle 8.9 для Android Gradle Plugin 8.7.3. Не используй старый workflow с Gradle 9.9.1.

## Проверка в этой среде

Проверены ZIP-структура, единственный source root, package declarations и отсутствие старых дублей среди собираемых файлов. Полная Android-сборка APK здесь не запускалась: Android SDK и Gradle в среде недоступны. Поэтому этот архив ещё требует проверки GitHub Actions.
