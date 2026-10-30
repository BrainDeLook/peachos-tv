"""Curated candidates, not a blacklist to apply to every Android TV.

Sources: CleanYourTV examples/xiaomi-mitv-aesp0 and examples/tcl-g10-google-tv,
plus the user's MiTV-AESP0 Android 9 session (28 cleanup packages, two optional apps).
Package presence alone never establishes device-profile compatibility.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Rule:
    package: str
    title: str
    category: str
    impact: str
    profiles: tuple[str, ...] = ()
    features: tuple[str, ...] = ()
    suggested: bool = False


FEATURES = {
    'voice': 'Голосовой поиск', 'cast': 'Трансляция с телефона',
    'updates': 'Обновления ТВ', 'channels': 'Эфир / Live Channels',
    'accessibility': 'Озвучивание и TalkBack', 'screensaver': 'Заставки',
    'backup': 'Синхронизация и резервные копии',
}
PROFILES = {
    'xiaomi-aesp0': ('Xiaomi Mi TV Stick MiTV-AESP0 · Android 9',
                    {'ro.product.manufacturer': 'Xiaomi', 'ro.product.model': 'MiTV-AESP0', 'ro.product.device': 'aquaman', 'ro.build.version.sdk': '28'}),
    'tcl-g10': ('TCL G10 · Android 12',
                {'ro.product.manufacturer': 'TCL', 'ro.product.device': 'G10', 'ro.product.name': 'G10_4K_GB', 'ro.build.version.sdk': '31'}),
}


def matching_profiles(props):
    return tuple(key for key, (_, expected) in PROFILES.items()
                 if all(props.get(k, '').casefold() == v.casefold() for k, v in expected.items()))


RULES = [
    Rule('com.google.android.feedback', 'Отправка отзывов Google', 'Метрики и диагностика', 'Не будет отправки отзывов Google.', suggested=True),
    Rule('com.google.android.tv.bugreportsender', 'Отчёты об ошибках ТВ', 'Метрики и диагностика', 'Отключает отправку отчётов об ошибках.', suggested=True),
    Rule('com.miui.tv.analytics', 'Аналитика Xiaomi', 'Метрики и диагностика', 'Сбор статистики Xiaomi; проверено на указанном Mi TV Stick.', ('xiaomi-aesp0',), suggested=True),
    Rule('tv.alphonso.alphonso_eula', 'Alphonso / распознавание просмотра', 'Метрики и диагностика', 'Компонент отслеживания просмотра Alphonso.', ('xiaomi-aesp0',), suggested=True),
    Rule('tv.samba.ssm.ui', 'Samba TV ACR', 'Метрики и диагностика', 'Распознавание просматриваемого контента. На других прошивках требует отдельной проверки.'),
    Rule('com.tcl.logkit', 'Логи TCL', 'Метрики и диагностика', 'Не будет штатного сбора диагностических логов TCL.', ('tcl-g10',), suggested=True),
    Rule('com.tcl.ttvs', 'TCL: аналитика и обновления', 'Метрики и диагностика', 'СОВМЕЩЁННЫЙ пакет: вместе с аналитикой могут перестать приходить обновления прошивки.', ('tcl-g10',), ('updates',)),
    Rule('com.mitv.tvhome.atv', 'PatchWall', 'Реклама и рекомендации', 'Отключает PatchWall. Это не штатный Android TV Home; текущий Home всегда защищён.', ('xiaomi-aesp0',), suggested=True),
    Rule('com.mitv.tvhome.michannel', 'Каналы PatchWall', 'Реклама и рекомендации', 'Не будет контентных каналов Xiaomi.', ('xiaomi-aesp0',), suggested=True),
    Rule('com.xm.webcontent', 'Веб-контент Xiaomi', 'Реклама и рекомендации', 'Встроенные контентные и рекламные блоки Xiaomi.', ('xiaomi-aesp0',), suggested=True),
    Rule('com.google.android.tvrecommendations', 'Рекомендации Android TV', 'Реклама и рекомендации', 'Рекомендации штатного экрана. Рекомендовано только для проверенного Mi TV Stick.', ('xiaomi-aesp0',), suggested=True),
    Rule('android.autoinstalls.config.xiaomi.mibox3', 'Автоустановка партнёров Xiaomi', 'Реклама и рекомендации', 'Прекращает партнёрскую автоустановку приложений.', ('xiaomi-aesp0',), suggested=True),
    Rule('android.autoinstalls.config.xioami.mibox3', 'Автоустановка Xiaomi (дубликат)', 'Реклама и рекомендации', 'Пакет с исторической опечаткой в имени.', ('xiaomi-aesp0',), suggested=True),
    Rule('com.xiaomi.android.tvsetup.partnercustomizer', 'Партнёрские настройки Xiaomi', 'Реклама и рекомендации', 'Партнёрские приложения и рекомендации первоначальной настройки.', ('xiaomi-aesp0',), suggested=True),
    Rule('com.xiaomo.tv.milegal', 'Правовые экраны Xiaomi', 'Фоновые дополнения', 'Экраны соглашений Xiaomi; проверено на MiTV-AESP0.', ('xiaomi-aesp0',), suggested=True),
    Rule('com.mitv.download.service', 'Загрузчик контента PatchWall', 'Фоновые дополнения', 'Не будут работать загрузки контента PatchWall.', ('xiaomi-aesp0',), suggested=True),
    Rule('com.mitv.dream', 'Заставка Xiaomi', 'Необязательные функции', 'Отключает штатную заставку Xiaomi.', ('xiaomi-aesp0',), ('screensaver',)),
    Rule('com.tcl.tv.plus', 'TCL TV+', 'Реклама и рекомендации', 'Не будет TCL TV+.', ('tcl-g10',), suggested=True),
    Rule('com.tcl.tv.tclhome_passive', 'Контент TCL при запуске', 'Реклама и рекомендации', 'Не будет запуска контентной панели TCL.', ('tcl-g10',), suggested=True),
    Rule('com.tcl.waterfall.overseas', 'TCL Channel', 'Реклама и рекомендации', 'Отключает контентную ленту TCL Channel.', ('tcl-g10',)),
    Rule('com.tcl.exhibit', 'Демонстрационный режим TCL', 'Фоновые дополнения', 'Отключает магазинные демонстрации.', ('tcl-g10',), suggested=True),
    Rule('com.tcl.esticker', 'Магазинные наклейки TCL', 'Фоновые дополнения', 'Не будет магазинных экранных наклеек.', ('tcl-g10',), suggested=True),
    Rule('com.tcl.ocean.instructions', 'Инструкция TCL', 'Предустановленные приложения', 'Не будет встроенного руководства пользователя.', ('tcl-g10',)),
    Rule('com.tcl.repairguide', 'Руководство ремонта TCL', 'Предустановленные приложения', 'Не будет штатного руководства ремонта.', ('tcl-g10',)),
    Rule('com.google.android.katniss', 'Голосовой поиск Google TV', 'Необязательные функции', 'Перестанет работать голосовой поиск.', features=('voice',)),
    Rule('com.google.android.apps.mediashell', 'Chromecast', 'Необязательные функции', 'Прекратится трансляция через Chromecast.', features=('cast',)),
    Rule('com.mitv.milinkservice', 'Mi Cast', 'Необязательные функции', 'Отключает приём трансляции Xiaomi.', ('xiaomi-aesp0',), ('cast',)),
    Rule('com.xiaomi.mitv.updateservice', 'Обновления Xiaomi', 'Необязательные функции', 'Перестанет работать штатное обновление прошивки.', ('xiaomi-aesp0',), ('updates',)),
    Rule('com.google.android.tv', 'Live Channels', 'Необязательные функции', 'Не будет Live Channels. Не отключает аппаратные HDMI-входы.', ('xiaomi-aesp0',), ('channels',)),
    Rule('com.google.android.marvin.talkback', 'TalkBack', 'Необязательные функции', 'Прекратится озвучивание интерфейса TalkBack.', features=('accessibility',)),
    Rule('com.google.android.apps.tv.dreamx', 'Заставка Google TV', 'Необязательные функции', 'Могут пропасть заставка и её настройки.', features=('screensaver',)),
    Rule('com.netflix.ninja', 'Netflix', 'Предустановленные приложения', 'Netflix перестанет запускаться. Аккаунт и данные не удаляются.'),
    Rule('com.amazon.amazonvideo.livingroom', 'Prime Video', 'Предустановленные приложения', 'Prime Video перестанет запускаться. Аккаунт и данные не удаляются.'),
    Rule('com.google.android.videos', 'Google TV / Play Фильмы', 'Предустановленные приложения', 'Не будет приложения Google TV / Play Фильмы.'),
    Rule('com.google.android.youtube.tvmusic', 'YouTube Music', 'Предустановленные приложения', 'Не будет приложения YouTube Music.'),
    Rule('com.dazn', 'DAZN', 'Предустановленные приложения', 'Не будет приложения DAZN.'),
    Rule('com.nousguide.android.rbtv', 'Red Bull TV', 'Предустановленные приложения', 'Не будет приложения Red Bull TV.'),
]

# These are optional on the verified tuner-less, NFC-less Android 9 stick, not universal junk.
for package, title, feature in [
    ('com.android.providers.contacts', 'Хранилище контактов', 'backup'),
    ('com.android.providers.calendar', 'Хранилище календаря', 'backup'),
    ('com.google.android.syncadapters.contacts', 'Синхронизация контактов', 'backup'),
    ('com.google.android.syncadapters.calendar', 'Синхронизация календаря', 'backup'),
    ('com.google.android.backuptransport', 'Резервные копии Google', 'backup'),
    ('com.android.backupconfirm', 'Подтверждение резервных копий', 'backup'),
    ('com.android.sharedstoragebackup', 'Копии общего хранилища', 'backup'),
    ('com.android.wallpaperbackup', 'Резервные копии обоев', 'backup'),
    ('com.android.printspooler', 'Печать', ''),
    ('com.android.settings.intelligence', 'Подсказки настроек', ''),
    ('com.android.se', 'Защищённый элемент / NFC', ''),
    ('com.android.cts.ctsshim', 'CTS shim', ''),
    ('com.android.cts.priv.ctsshim', 'CTS privileged shim', ''),
]:
    RULES.append(Rule(package, title, 'Необязательные функции',
                      'Отключалось на MiTV-AESP0 Android 9. Это не универсальный мусор; оставьте, если используете функцию.',
                      ('xiaomi-aesp0',), (feature,) if feature else ()))

CORE_EXCEPTIONS = {r.package for r in RULES if r.profiles == ('xiaomi-aesp0',)}
PROTECTED = {
    'android', 'com.gathin.tvhome.backdrops', 'com.android.systemui', 'com.android.shell',
    'com.android.settings', 'com.android.tv.settings', 'com.google.android.gms', 'com.android.vending',
    'com.google.android.gsf', 'com.google.android.tv.remote.service',
    'com.google.android.tungsten.setupwraith', 'com.google.android.tvlauncher',
    'com.google.android.apps.tv.launcherx', 'com.android.bluetooth',
    'com.google.android.inputmethod.latin', 'com.google.android.tv.frameworkpackagestubs',
    'com.tcl.tv', 'com.tcl.tvinput', 'com.tcl.dashboard', 'com.tcl.suspension',
    'com.tcl.autopair', 'com.tcl.systemserver', 'com.realtek.power', 'com.realtek.dongledetect',
    'com.android.companiondevicemanager',
}


def protected(package, home_package=''):
    if package in PROTECTED or package == home_package:
        return True
    if package.startswith(('com.android.', 'com.google.android.overlay.', 'com.google.android.modulemetadata',
                           'com.google.android.permissioncontroller', 'com.google.android.packageinstaller',
                           'com.google.android.ext.', 'com.google.android.networkstack')):
        return package not in CORE_EXCEPTIONS
    return False


def candidates(props, installed, user_apps, home_package):
    profiles = matching_profiles(props)
    rows = {r.package: r for r in RULES if r.package in installed
            and (not r.profiles or set(r.profiles).intersection(profiles))
            and not protected(r.package, home_package)}
    for package in user_apps:
        if package not in rows and not protected(package, home_package):
            rows[package] = Rule(package, package, 'Другие пользовательские приложения',
                                 'Установленное пользователем приложение. Выбирайте только если оно вам не нужно.')
    return profiles, rows
