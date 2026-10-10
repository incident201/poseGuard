# Окружение PoseGuard без Android Studio

Все инструменты установлены локально в `.tools/`; исходный проект находится в `poseGuard/`. Системный PATH и настройки оболочки не меняются. Android Studio, Android Emulator и образы виртуальных устройств в это окружение не устанавливаются. Доступ к уже подключённому USB-устройству работает без sudo.

## Использование

Из этой папки:

```bash
source ./env.sh
pg doctor
pg build                 # debug APK: offline и online
pg test                  # JVM/Robolectric, оба варианта
pg lint                  # Android lint, оба варианта
pg test-apk              # APK инструментальных тестов
pg verify                # все перечисленное + проверка release R8 без release-ключа
pg quick-test offline    # быстрые сценарии сессии и кодека на телефоне
pg quick-test online     # те же сценарии + настоящий сервер Intiface и test device
pg camera-smoke          # камера, MediaPipe, пересоздание Activity и timelapse сессии
pg ui-test               # пользовательские пути через настоящую MainActivity
pg release-audit         # production release APK (R8): статический аудит и запуск на телефоне
pg release-test online   # те же сценарии на минифицированном release APK
pg release-check         # весь release-контур для обоих flavor
pg restart-test          # сохранённый прибор после настоящего force-stop приложения
pg scenario offline 'SessionScenarioTest#slowDisplacementIsClassifiedAsDriftAndEmitsDriftCue' --no-build
```

Можно использовать `./bin/pg` без активации окружения. Для одного варианта доступны `pg build offline`, `pg test online` и аналогичные команды. Любая Gradle-задача запускается через `pg gradle :app:tasks --all`; после активации доступен также обычный `gradle` из папки проекта. В репозитории нет Gradle Wrapper, поэтому окружение предоставляет Gradle той же версии, что GitHub Actions.

Отчёты Gradle находятся в `poseGuard/app/build/reports/`, APK — в `poseGuard/app/build/outputs/apk/`. Тест скриншота использует Robolectric с API 34 внутри JVM и не требует эмулятора или отдельного Android SDK 34.

## Физическое устройство

```bash
pg devices
pg install offline       # сборка и установка debug APK с сохранением данных
pg launch
pg device-test offline   # инструментальные тесты; устанавливают приложение и тестовый APK
pg mirror                # экран телефона и управление через scrcpy
pg logs                  # logcat только текущего процесса PoseGuard
pg crashes               # Android crash buffer
pg diagnostics           # dumpsys: приложение, память, камера, датчики, температура
pg screenshot
pg bugreport
pg profile 10            # системная трасса Perfetto, 10 секунд
pg debug                 # перезапуск с ожиданием JDB, порт 8700
```

`pg debug` подключает JDB к Android JDWP. Примеры команд в JDB: `stop in com.incident201.poseguard.MainActivity.onCreate`, `cont`, `threads`, `where`, `locals`, `exit`. Для Kotlin сопоставление строк и локальных переменных зависит от байткода; Compose и встроенные функции могут иметь менее удобное соответствие исходникам. После завершения JDB скрипт убирает ожидание отладчика и перенаправление порта.

Трассы `.perfetto-trace` можно открыть в [Perfetto UI](https://ui.perfetto.dev/). Логи, снимки экрана, трассы и bugreport сохраняются в `artifacts/`; эти файлы могут содержать данные устройства. Для остановки приложения есть `pg stop`.

Если подключено несколько устройств, выберите нужное:

```bash
export ANDROID_SERIAL=серийный_номер_из_pg_devices
```

На новом телефоне нужно включить USB debugging в Developer options и разрешить RSA-ключ компьютера. Для Wi-Fi доступны `pg adb pair IP:PORT` и `pg adb connect IP:PORT`; адреса и код сопряжения показывает телефон. Если USB недоступен из-за прав, на CachyOS/Arch системный пакет `android-udev` устанавливает соответствующие udev-правила; в текущей проверенной конфигурации он не требуется.

Для проверки Intiface установлен сервер Intiface Engine 5.0.4, используемый как backend Intiface Central. `pg intiface` запускает сервер, два виртуальных устройства (A: два мотора, B: один мотор) и запись команд каждого прибора. В другом терминале можно использовать `pg adb reverse tcp:12345 tcp:12345`, а в online-приложении — `ws://127.0.0.1:12345`. `pg intiface-test` выполняет проверки подключения, отключения, переключения, восстановления выбора и сигналов автоматически. Графическая оболочка Intiface Central не устанавливается. Подробнее о сценариях и границах проверки: [TESTING.md](TESTING.md).

## Состав и восстановление

| Компонент | Версия / назначение |
| --- | --- |
| Eclipse Temurin JDK | 17.0.20.1, включая javac, JDB, keytool, jstack, jcmd |
| Gradle | 9.6.0, как в CI проекта |
| Android Command-line Tools | 23.0: sdkmanager, Android CLI, apkanalyzer, R8/retrace |
| Android SDK Platform | 37.0 revision 2; alias `android-37` для проекта |
| Android Build Tools | 37.0.0: AAPT2, D8, apksigner, zipalign |
| Android Platform Tools | 37.0.1: ADB и сопутствующие утилиты |
| Android Sources | 37.0 revision 2 |
| scrcpy | 5.0.1, готовая сборка для Linux x86_64 |
| Intiface Engine | 5.0.4, настоящий Buttplug-сервер с виртуальным WebSocket-устройством |
| Python venv | локальное окружение с websockets 16.0 для стенда и записи команд |

```bash
./tools/setup.sh
```

Установка повторяемая: существующие инструменты используются повторно, архивы проверяются по опубликованным контрольным суммам. Скрипт принимает лицензии SDK, устанавливает только перечисленные SDK-пакеты, прописывает локальный `sdk.dir` и восстанавливает debug keystore из файла репозитория, как CI. Gradle-кэш и Java toolchain находятся внутри `.tools/`; существующие пользовательские ADB-ключи используются без изменений. Debug keystore и `local.properties` игнорируются Git проекта.

Release-подписание требует собственных `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`; окружение не создаёт настоящие release-ключи и не изменяет настройки подписания проекта. Для локальных проверок `pg release-*` подписывают сборки открытым debug-ключом репозитория под псевдонимом `upload` (хранилище `.tools/release-test.p12`, переменные задаются только внутри этих команд), поэтому release-сборка устанавливается поверх debug-сборки без потери данных.

Версии соответствуют [требованиям AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes). Справка по [ADB](https://developer.android.com/tools/adb) и [scrcpy для Linux](https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md).
