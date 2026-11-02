# Changelog

## 1.5.9 — 2026-11-02

### Русский

- Ускорен запуск Home: внутренняя копия проверенной темы включает сведения об обоях и подписях. Внешнее хранилище проверяется в фоне общей задачей Home и службы пульта. Неизменённая тема не пересоздаёт экран; при изменении сохраняется выбранное приложение. Повреждённый или отсутствующий снимок безопасно возвращает обычную загрузку.
- Во вкладке сети добавлен список доступных Wi-Fi с текущим подключением. Поиск работает только при открытой вкладке; запросы ограничены и объединяются. Подключение и ввод пароля передаются штатному окну Android. При Ethernet остаётся проводной статус с соответствующей иконкой. Строки сетей показываются целиком, без обрезанных снизу кнопок.
- Добавлен отдельный необязательный AirPlay-приёмник: с ним лупа становится переключателем приёма, готовое состояние отмечено синим. Без модуля лупа остаётся. Исправлено повторное включение без перезахода в панель. Сам приёмник остаётся preview и устанавливается только по выбору пользователя.
- Модуль поддерживает повтор экрана iPhone и отдельную передачу видео. Исправлены совместимость HLS YouTube/VK, повторные остановки/подключения и возврат к прежнему экрану. Добавлены пауза/продолжение и плавная ускоряющаяся перемотка с пульта. Исправлен разбор пустых EXTINF в некоторых потоках VK.
- Исправлены подтверждение удаления приложения из контекстного меню и выход из перемещения кнопкой Назад. Сокращены лишние декодирования и операции загрузки иконок/постеров; отложено создание невидимых эффектов, освобождаются устаревшие ресурсы и задачи.
- Портативный Windows EXE содержит APK v1.5.9, ADB, среду выполнения и опциональный модуль с соответствующими исходниками/лицензиями. Обычный APK лаунчера остаётся самостоятельным. ID пакета и подпись сохранены; обновление поверх предыдущей версии сохраняет настройки.

Проверки: 306 JVM-тестов лаунчера, release build/lint, 49 тестов ПК-мастера, 41 нативная проверка снимка/обновления Home, проверки сетевых границ, жизненного цикла плеера/кодеков, перемотки и HLS. На Homatics каталог Home после ребута появился за 1,59 с против 2,46 с в предыдущем замере; это небольшая выборка, не гарантия FPS или времени запуска на других устройствах. Визуал и параметры анимаций сохранены.

Ограничения: Wi-Fi-сканирование может требовать системное «Местоположение» и разрешение Android; без них доступен штатный сетевой экран. AirPlay preview проверен на Homatics Box R 4K Plus, требует ARMv7-совместимую ОС/API 24+ и разрешение поверх приложений. Совместимость со всеми сервисами, прошивками, защищённым видео и аудиоформатами не заявляется. Обработка громкости не менялась.

### English

- Speed up Home initialization using an internal verified-theme snapshot containing wallpaper metadata and captions. Home and the remote helper share background external-storage verification. Unchanged themes do not rebuild Home; changed themes preserve the selected app. Missing or invalid snapshots fall back to the ordinary loader.
- Add nearby Wi-Fi networks and the current connection to the Network tab. Scanning runs only while visible, with bounded/coalesced requests; Android's system UI handles connection and password entry. Ethernet retains its wired status and icon. Network rows fit as complete bubbles without clipped bottom buttons.
- Offer a separate optional AirPlay receiver. Installing it replaces Search with an on/off reception shortcut, blue when discovery is ready; without it, Search remains. Fix repeated toggling without reopening Control Center. The receiver itself remains a preview and is opt-in.
- Support iPhone mirroring and native video casting, with YouTube/VK HLS compatibility fixes, repeated stop/reconnect handling and return to the previous screen. Add remote pause/play and smooth accelerating timeline seeking. Handle orphaned empty EXTINF tags in affected VK streams.
- Fix app-uninstall confirmation from the context menu and Back while rearranging apps. Reduce redundant icon/poster loading and decoding, defer invisible effects, and release obsolete resources and work.
- Bundle v1.5.9, ADB, runtime and the optional receiver's matching source/licenses in one portable Windows EXE. The standalone launcher APK remains independent. Preserve package identity, signing certificate and in-place upgrade preferences.

Validation: 306 launcher JVM tests, release build/lint, 49 PC setup tests, 41 native Home snapshot/refresh checks, network-boundary tests and player/codec lifecycle, seeking and HLS checks. On Homatics, Home's catalogue appeared 1.59 seconds after Activity creation following reboot, versus 2.46 seconds in the preceding sample. This small sample does not guarantee FPS or startup timing elsewhere. Visual design and animation parameters are preserved.

Limits: Wi-Fi scanning can require Android Location permission and the system Location switch; system network settings remain available without them. AirPlay preview was tested on Homatics Box R 4K Plus and requires an ARMv7-compatible OS/API 24+ plus overlay permission. Universal provider/firmware, protected-video and audio-format compatibility is not claimed. Volume handling is unchanged.

## 1.5.8 — 2026-10-31

### Русский

- Исправлено обрезание увеличенной кнопки во вкладке «Источники» контрол-панели: список больше не сбрасывает отступ, необходимый для выделения. Скругления первой и последней строк остаются полностью видимыми.
- Проверено на TCL с навигацией пультом. Стиль панели, индикаторы подключённых входов и переключение источников не изменены.
- Обновлён портативный Windows-установщик со встроенным APK v1.5.8 и ADB. Устанавливайте поверх предыдущей версии для сохранения настроек.

Проверки: 289 Android-тестов, release lint и 41 тест мастера ПК.

### English

- Fix clipped focused buttons in Control Center's Inputs tab. The source list now preserves the inset required for focus zoom, keeping the first and last rows' rounded edges fully visible.
- Verified on TCL using remote navigation. Panel styling, connected-input indicators and source switching are unchanged.
- Update the portable Windows installer with the v1.5.8 APK and bundled ADB. Install over the existing version to preserve preferences.

Validation: 289 Android unit tests, release lint and 41 PC setup tests.

## 1.5.7 — 2026-10-31

### Русский

- Добавлена опциональная контрол-панель поверх приложений: плавное открытие и закрытие, вкладки с белым пружинным выделением, сохранение объёма и стекла. В этом режиме Home и Назад закрывают панель, оставляя приложение открытым. Возможность остаётся пробной и зависит от прошивки и воспроизводимого контента.
- Добавлено контекстное меню приложений по удержанию OK. В первых трёх столбцах меню открывается справа от иконки, в последующих — слева. Ореол выбранной иконки продолжает цвета её контура.
- Источники постеров синхронизируются с каналами, которые действительно публикуют приложения Android TV. Настройки лаунчера сгруппированы по разделам.
- На главном экране выбранная иконка сохраняет выделение под контрол-панелью. Кнопки панели больше не обрезаются по бокам. Home при открытой панели плавно закрывает её и сохраняет выбранное приложение; без панели Home по-прежнему возвращает на первую иконку дока.
- Исправлено удержание закрытых панелей в памяти. Ограничены кэши, отменяется устаревшая загрузка постеров и иконок, сокращены лишние декодирования фонов и сетевые обновления. В мастере ПК ускорено изменение выбора пакетов и исправлена упаковка встроенного APK.
- Выпускаются отдельный APK и один портативный Windows EXE со встроенными APK, ADB и средой выполнения. Обновление сохраняет данные и настройки лаунчера.

Проверки: 289 Android-тестов, release lint и 41 тест мастера ПК. Проверка производительности подтверждает устранение накопления закрытых панелей в измеренном сценарии, но не гарантирует постоянные 60 FPS на всех устройствах. Глобальный перехват Settings на некоторых прошивках может нарушать удержание громкости по HDMI-CEC; обработка громкости в этом выпуске не изменена.

### English

- Add optional Control Center over apps, with animated opening and closing, spring-driven white focus, and layered glass materials. In this mode, Home and Back dismiss the panel while keeping the app open. This feature remains experimental and depends on firmware and displayed content.
- Add an app context menu on long-press OK. It opens to the right of icons in the first three columns and to the left in later columns. The selected icon's contour glow follows its edge colours.
- Synchronize poster sources with channels actually published by Android TV apps. Organize launcher settings into sections.
- Preserve the selected Home icon while Control Center is open. Prevent focused panel buttons from clipping at their sides. Home smoothly dismisses an open panel and retains the selected app; without the panel, Home still returns to the first dock icon.
- Release closed panel views, bound caches, cancel stale poster and icon work, and reduce redundant background decoding and network refreshes. Improve package selection updates in the PC wizard and correct its bundled APK packaging.
- Provide a standalone APK and one portable Windows executable containing the APK, ADB and runtime. In-place updates preserve launcher data and preferences.

Validation: 289 Android unit tests, release lint and 41 PC setup tests. Device measurements confirm that closed panels no longer accumulate in the tested scenario; they do not guarantee locked 60 FPS on every device. Global Settings interception can interrupt held HDMI-CEC volume on some firmware; volume handling is unchanged in this release.

## 1.5.6 — 2026-10-31

- Add a single portable Windows setup executable with bundled ADB, APK, runtime and PeachOS icon. Default to a simple install/update screen; keep the detailed wizard and separately confirmed expert mode available.
- Wait for TV-side ADB authorization, with cancellation, instead of failing immediately on a first unauthorized connection.
- Add sequential installation of multiple local APKs, reviewed plans and per-install results. An update-only plan preserves Home and performs no package disabling.
- Expand the described catalogue from 50 to 116 candidates and bundle a read-only evidence index of 1,403 package names from 260 CleanYourTV device lists. Preserve normal-mode core protection and retained-function checks. The expert mode can attempt disabling any installed package subject to Android permissions; warnings explain the risk of losing boot, networking or ADB.
- Provide an explicitly reviewed, journalled Home fallback for Xiaomi MiTV-AESP0 / Android 9 when the stock launcher ignores default-Home selection.
- Default new launcher installs to six columns and the first six available apps, while preserving existing preferences and saved order.
- Keep the dock band at the configured number of slots even when the first row is incomplete. Preserve existing end-tile contour easing.
- Restore Reorder apps in launcher settings independently of the top-bar settings-button visibility.

Validation: 268 Android unit tests, release lint and 35 setup tests pass. GUI smoke checks cover simple/advanced mode switching, update isolation, APK queue controls and authorization cancellation. The portable executable is checked in an isolated folder without external Python, ADB or APK files. Package disabling and install-failure paths are tested with simulated ADB; compatibility across additional TV firmware is not yet established. Existing package ID and signing certificate are retained.

## 1.5.2 — 2026-10-30

- Improve focus animation performance by caching attached app tiles, including their continuous mask and lighting. Release tile layers on detach and restore them on reattach; preserve the existing scale spring and interrupted-motion velocity.
- Stop changing image filters on every zoom frame. Only the first and last dock tiles invalidate the dock during their scale animation. Cache the full dock material and keep its ambient illumination stable.
- Refine dock spacing and end-tile clearance, substantially reduce icon illumination, and remove dark shadow contours outside app silhouettes.
- Refine the translucent clock capsule and share its font metrics with the control-center clock to keep the opening/closing handoff consistent.
- Keep fully visible posters stationary while moving focus between them; scroll the row only when needed to reveal an edge item.
- Adapt the section heading to artwork underneath the text, anticipate taller incoming posters, reserve animation headroom, and avoid card-to-card focus jitter. During D-pad key repeat, hold the highest reached heading position until the key is released and scrolling settles.

Validation: 258 unit tests pass; release lint has no errors. The preview performance investigation on Homatics Box R4K Plus reduced median dock presentation intervals from 33.37 ms to 16.68 ms in the measured scenario; this is not a guarantee of locked 60 FPS on all devices. Initial control-center openings can still have long frames. See [rendering review](docs/render-performance-review.md).

Package ID and signing certificate are unchanged. Install over the previous version to retain settings.

## 1.5.1 — 2026-10-29

- Play the existing navigation tick when D-pad focus moves between control-center tabs and buttons. Opening the panel or pressing against a focus boundary does not add a tick.
- Add a distinct, synthesized two-note sound for selecting TV sources, using the existing sound toggle and SoundPool. App-opening, Home-return and navigation sound files remain unchanged from 1.5.0.
- Give control-center buttons soft directional lighting, lower-edge shading and a small elevation shadow. Focus lighting transitions smoothly; paths and gradients are cached without additional blur buffers.
- Match the source buttons' corner radii to the other horizontal control-center buttons. The material mask and shadow follow the same outline.

Package ID and signing certificate are unchanged. Install over the previous version to retain settings.

## 1.5.0 — 2026-10-29

- Add a compact control center with quick settings, network status, TV inputs where available, and detected VPN clients. Press the gear to open it; hold OK for the launcher menu.
- Add optional standby through an accessibility helper on Android 9+, without screen-content access or remote-key filtering.
- Animate header separation, tab focus and workspace changes. Keep the control center in Home's window so the returning clock and gear share the same rendered frame.
- Refine continuous tile corners, subtle focus lighting, typography and portrait/landscape shelf headings.
- Add optional app-opening and Home-return sounds, and retain the launched application's selection when returning Home.
- Load the app catalogue outside the UI thread, discard stale artwork work and defer channel initialization until after the first catalogue frame.
- Retain the existing 2 MiB thumbnail LRU on ordinary Home returns, while releasing it on memory pressure. Keep the disk cache capped at 32 MiB.
- Decode the first wallpaper in the background and avoid recycling hardware images while rendering may still reference them.

Validation: 248 unit tests pass; release lint has no errors. Control-center navigation and header return were checked on TCL Smart TV Pro and Homatics Box R 4K Plus. Cold first-use frame drops are not completely eliminated.

Package ID and signing certificate are unchanged. Install the new APK over the previous version to retain settings; do not uninstall first.
