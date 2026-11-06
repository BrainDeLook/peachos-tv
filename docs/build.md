# Build from source / Сборка из исходников

## English

Use **JDK 17**, **Android SDK Platform 34**, **Build Tools 34.0.0**, and the bundled **Gradle 8.9** wrapper. Set `JAVA_HOME` and `ANDROID_HOME`.

```sh
cd launcher
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

On Windows, use `gradlew.bat`. The APK is written to `launcher/app/build/outputs/apk/release/app-release.apk`.

Release APKs currently use the project's original local debug signing certificate for update compatibility. **The private signing key is not in this repository.** A self-built APK signed with another key cannot update an installed release. Set `ANDROID_USER_HOME` to the directory containing your original `debug.keystore` only if you already have that key.

## Русский

Нужны **JDK 17**, **Android SDK Platform 34**, **Build Tools 34.0.0**. Wrapper использует **Gradle 8.9**. Задайте `JAVA_HOME` и `ANDROID_HOME`.

```sh
cd launcher
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

В Windows используйте `gradlew.bat`. APK: `launcher/app/build/outputs/apk/release/app-release.apk`.

Готовые релизы сохраняют исходный локальный debug-сертификат проекта для совместимости обновлений. **Приватный ключ в репозиторий не включён.** Сборка с другим ключом не установится поверх релиза. Только если исходный ключ уже есть у вас, задайте `ANDROID_USER_HOME` на каталог с этим `debug.keystore`.

[README](../README.md) · [README на русском](../README.ru.md)
