# Changelog

## 1.5.13 — 2026-11-05

### Русский

- Исправлен пропуск назначения Home при выборе только «Совместимый Home». Галочка теперь включает настройку Home в простом и расширенном мастере; снятие Home отменяет совместимый режим. После исправления владелец TCL подтвердил физическую Home.
- Справочник синхронизирован с CleanYourTV `50fcc214`: 442 списка устройств и 1981 пакет. Рабочая база содержит 122 описанных кандидата. Доступность самостоятельных приложений отделена от автоматических рекомендаций; TCL Channel больше не скрывается на Android 14. HDMI/TV остаётся защищённым, TCL Live зависит от сохранения каналов.
- Единая настройка Home по фактическому поведению прошивки: штатное назначение Android, проверка перехода на PeachOS и отдельный, изначально выключенный «Совместимый Home через службу пульта» при отказе. Штатный лаунчер и сетевые службы не отключаются.
- Совместимый режим хранит настройку отдельно от темы, включает только необходимую службу и поддерживаемый автозапуск с явного согласия. Новый режим только для Home не фильтрует кнопки пульта; существующие настройки удержания Settings сохраняются.
- Исправлены проверка уже открытого штатного Home, позднее подключение службы после замены APK и зависание ожидания `am start -W`. При ошибках настройка останавливается до очистки и пытается вернуть исходное состояние.
- Добавлены журналируемое восстановление совместимого Home, сохранение чужих служб доступности и проверка результатов восстановления. «Только обновить» не меняет Home, службы или разрешения.
- Проверено на TCL G10 Android 14 AU02: настройка через установщик, повторная установка, update-only, восстановление и повторное применение, сохранение после перезагрузки. Другие прошивки требуют отдельной проверки; универсальная совместимость не гарантируется.

Проверки: release build/lint, 325 JVM-тестов лаунчера, 99 тестов установщика и самопроверка portable EXE. Модуль AirPlay не менялся. Справочник из 1981 пакета не является универсальным списком отключения: в обычном режиме доступны 122 описанных кандидата; остальные справочные пакеты можно выбирать только в экспертном режиме.

### English

- Fix silently skipping Home assignment when only compatible Home was selected. Compatibility now requests Home setup in both wizard modes; clearing Home clears compatibility. The TCL owner confirmed the physical Home button afterward.
- Sync the reference index to CleanYourTV `50fcc214`: 442 device lists and 1,981 packages, with 122 described actionable candidates. Separate standalone-app availability from profile-scoped recommendations; expose TCL Channel on Android 14. Keep HDMI/TV protected and TCL Live dependent on channel preservation.
- Configure Home by observed firmware behaviour: try Android's native assignment, verify the actual PeachOS transition, and offer a separate default-off compatible remote-helper fallback. Keep the stock launcher and networking services enabled.
- Store compatible settings separately from the theme. Enable the required accessibility service and supported autostart operation only with explicit consent. Fresh Home-only mode does not filter remote keys; preserve existing Settings-hold choices.
- Handle an already visible stock Home and late service binding after APK replacement. Avoid OEM launch hangs caused by `am start -W`; stop before cleanup and attempt rollback on setup failure.
- Journal compatible Home recovery, preserve unrelated accessibility services, and verify restored state. Update-only does not change Home, services or permissions.
- Validate setup, reinstall, update-only, restore/reapply and reboot persistence on TCL G10 Android 14 AU02. Other firmware still needs device validation; compatibility with every TV is not guaranteed.

Validation: release build/lint, 325 launcher JVM tests, 99 installer tests and portable EXE self-test. The optional AirPlay module is unchanged. The 1,981-package reference index is not a universal disable list: normal cleanup offers 122 described candidates; additional indexed packages remain reference-only outside expert mode.

## 1.5.12 — 2026-11-05

### Русский

- Добавлен отдельный профиль Windows-мастера для TCL G10 / Q6CS на Android 14 AU02. Он совпадает только с проверенными устройством, продуктом, API и строкой сборки; другие прошивки не наследуют старый список Android 12.
- Лёгкая очистка этого профиля предлагает отзывы Google, TCL LogKit, контентный экран TCL Home Passive и магазинные наклейки ESticker. Пакет Exhibit исключён: в этой прошивке он также содержит Art Mode. Отключение четырёх пакетов проверено через мастер на телевизоре с резервной копией.
- При отказе назначения Home мастер теперь объясняет ограничение TCL и сохраняет установленные APK, возвращает исходный Home и останавливает очистку. Совет про обход Xiaomi показывается только для соответствующего профиля.
- Добавлена инструкция по перенаправлению Home через существующую службу пульта и разрешению автозапуска PeachOS в защите TCL. Проверены повторная установка APK и перезагрузка. Мастер не включает эти разрешения автоматически; настройка выполняется отдельно с согласия владельца. Google TV, SetupWraith, TGuard, сеть, HDMI и службы пульта не отключаются этим профилем.
- Обновлены portable EXE, APK и архив исходников. Код интерфейса лаунчера и AirPlay не менялся; версия APK повышена для согласованного релиза и совместимого обновления.

Проверки: 61 тест установщика, release build/lint и 313 JVM-тестов лаунчера; самопроверка portable EXE и контроль встроенных файлов. На TCL владелец подтвердил Home из приложения, удержание громкости и источники; после настройки автозапуска Home проверен после перезагрузки. Полностью автоматический обход назначения Home для TCL не заявляется.

### English

- Add a separate Windows wizard profile for TCL G10 / Q6CS running Android 14 AU02. It matches the verified device, product, API and exact build string; other firmware does not inherit the Android 12 cleanup list.
- Recommend Google feedback, TCL LogKit, TCL Home Passive and ESticker for this profile's light cleanup. Exclude Exhibit because it also provides Art Mode on this firmware. Verify all four reversible disable operations through the wizard on the TV, with a restore journal.
- Explain TCL Home-selection failures accurately: keep installed APKs, restore the original Home and stop before cleanup. Show Xiaomi workaround advice only for the matching Xiaomi profile.
- Document the existing remote helper's Home redirect and TCL's PeachOS-specific autostart permission. Verify APK replacement and reboot behavior. The wizard does not grant these permissions automatically; configure them separately with the owner's consent. This profile does not disable Google TV, SetupWraith, TGuard, networking, HDMI or remote services.
- Update the portable EXE, launcher APK and source archive. Launcher UI and AirPlay code are unchanged; increment the APK version for a consistent release and in-place updates.

Validation: 61 installer tests, release build/lint and 313 launcher JVM tests; portable EXE self-test and embedded-asset verification. The TCL owner confirmed Home from an app, held volume and input selection; Home was also checked after reboot with autostart configured. Fully automatic TCL Home setup is not claimed.

## 1.5.11 — 2026-11-03

### Русский

- Переработан Windows-установщик: интерфейс с карточками, понятный основной сценарий и расширенный режим с боковой навигацией. Добавлена опциональная тёмная тема с сохранением выбора. Прокручиваемые страницы сохраняют доступ к параметрам в небольшом окне и при увеличенном масштабе Windows.
- План начинается с короткого итога; подробности и список пакетов раскрываются отдельно. При выполнении настройки блокируются, после успеха показывается результат без повторного подключения. Экспертный режим остаётся отдельным и выключенным по умолчанию; прежние проверки устройства, резервные копии и подтверждения сохранены.
- После ошибки мастер сбрасывает снимок устройства и требует переподключения перед следующим планом. Проверки установщика: 59 тестов, включая нативный Tk, смену темы, малое окно, неизменяемый план и обработку ошибок.
- Улучшена читаемость кнопок панели поверх HDMI на TCL, когда прошивка не позволяет захватить фон. Материал на Home и при доступном захвате не менялся.
- Длинное название выбранной Wi-Fi-точки плавно прокручивается с мягкими краями, паузой в конце и повтором. У остальных строк остаётся троеточие; исправлен цвет значка на белом выделении. Уменьшенное движение сохраняет статичный текст.
- Повышена устойчивость регистрации сетевых наблюдателей. Убраны запросы скрытых кнопок сети и источников, а также лишний проход отрисовки при возврате на Home.
- TMDB-кэш ограничен 512 записями, просроченные и повреждённые значения очищаются без постоянного таймера. Нехватка памяти больше не исключает обои из ротации; неудачная аппаратная копия сохраняет готовое изображение.
- Обновлены APK, portable EXE и исходники. Опциональный AirPlay остаётся прежним preview-модулем, выключенным по умолчанию в установщике.

Проверки лаунчера: release build/lint, 313 JVM-тестов, 73 проверки отказов сетевых API/декодирования, 718 проверок сетевого статуса и 35 нативных сценариев интерфейса на Homatics; исправления HDMI проверены пользователем на TCL. Гарантия роста FPS не заявляется. Синхронная перестройка каталога при перемещении приложений, диагностические логи и хранение Plex-токенов не менялись.

### English

- Redesigned the Windows installer with a card-based interface, a clear default workflow and sidebar navigation in advanced mode. Added an optional dark theme with a saved preference. Scrollable pages keep options accessible in smaller windows and at increased Windows display scaling.
- Plans now begin with a concise summary and expandable technical details. Controls are locked while work is running; completion shows a result instead of reconnecting automatically. Expert mode remains separate and off by default, with existing device checks, backups and confirmations preserved.
- After an error, the wizard discards the device snapshot and requires reconnection before another plan. Installer validation: 59 tests covering native Tk, theme changes, narrow windows, immutable plans and error recovery.
- Improved Control Center button readability over HDMI on TCL when the firmware cannot capture the background. Home and successfully captured materials are unchanged.
- Long selected Wi-Fi names scroll with soft edge fades, an end pause and repeat. Unfocused rows retain ellipsis; fixed the glyph tint on white selection. Reduced motion keeps text static.
- Hardened network-observer registration and release. Removed redundant queries for hidden network/input controls and an unnecessary draw traversal on return to Home.
- Bounded TMDB caching to 512 entries, with lazy expiry/corruption cleanup and no permanent timer. Allocation pressure no longer removes wallpapers from rotation; failed hardware copies preserve the decoded image.
- Updated the launcher APK, portable EXE and source archive. Optional AirPlay remains the same preview receiver and is unchecked by default in the installer.

Launcher validation: release build/lint, 313 JVM tests, 73 network/decode fault-injection checks, 718 transport-status checks and 35 native UI scenarios on Homatics. The HDMI fallback was user-verified on TCL. No measured FPS gain is claimed. Synchronous reorder catalogue loading, diagnostic logs and Plex-token storage are unchanged.

## 1.5.10 — 2026-11-03

### Русский

- Переработан список Wi-Fi в контрол-панели: компактный статус, «Настройки сети» и «Обновить список» сверху, плотная карусель точек снизу без заголовка «Доступные сети». Плотность строк соответствует разделу «Источники».
- Добавлены плавная прокрутка одной перенаправляемой пружиной и мягкое появление/затухание у краёв списка. В покое видны целые карточки. Увеличенное выделение больше не срезается внутренними отступами; список не перекрывает верхние действия.
- Исправлена навигация вкладок: нажатия наружу на крайних вкладках сохраняют фокус в ряду. Из правой колонки вниз выбирается крайняя правая круглая кнопка (Поиск/AirPlay), из левой — крайняя левая; обратный переход вверх сохраняет колонку.
- Обновлён портативный Windows-установщик со встроенным APK v1.5.10. Настройки и совместимость обновлений сохранены. Опциональный AirPlay-модуль остаётся прежней preview-версией.

Проверки: release build/lint, 306 JVM-тестов, 25 нативных сценариев карусели и навигации на Homatics, визуальная проверка выбранных первой/средней/последней Wi-Fi-точек на TCL. Правила сканирования, подключения и обработки громкости не менялись. Новых фоновых таймеров, сканов при прокрутке или захватов размытия нет; прирост FPS не заявляется.

### English

- Rework Control Center's Wi-Fi picker: compact status, Network settings and Refresh above a dense AP carousel, without an Available networks heading. Row density matches Inputs.
- Add smooth retargetable spring scrolling and soft edge fades. Cards fit completely at rest. Focus zoom is no longer clipped by inner padding, and scrolling networks stay below the fixed actions.
- Keep outward Left/Right presses within the tab strip. Down from the right column selects the rightmost round shortcut (Search/AirPlay); the left column selects the leftmost one. Up returns to the corresponding column.
- Update the portable Windows installer with the v1.5.10 launcher APK, preserving preferences and update compatibility. The optional AirPlay receiver remains the same preview build.

Validation: release build/lint, 306 JVM tests, 25 native carousel/navigation scenarios on Homatics, and visual checks of focused first/middle/last Wi-Fi cards on TCL. Scan/connection budgets and volume handling are unchanged. No new background timers, scans while scrolling or blur captures; no measured FPS improvement is claimed.

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
