# LainTun (LainTunApp)

Android-клиент личного VPN-сервиса: вход через **Яндекс ID**, бесплатный триал на 24 часа, оплата подписки прямо в приложении (ЮKassa) и подключение по **VLESS + Reality (XTLS-Vision)** через встроенное ядро Xray.


| | |
|---|---|
| Платформа | Android 8.0+ (`minSdk 26`, `targetSdk 35`), ABI: `arm64-v8a` |
| Язык / UI | Kotlin 2.0, Jetpack Compose, Material 3 |
| DI / данные | Hilt, DataStore (Preferences), kotlinx.serialization |
| Сеть | Retrofit 2 + OkHttp 4 |
| VPN | `VpnService` + Xray (`libv2ray.aar`, встроенный TUN) |

## Как это работает

1. При запуске приложение проверяет сохранённую сессию. Нет сессии — экран входа.
2. «Войти через Яндекс» → Custom Tab с `oauth.yandex.ru` (OAuth Authorization Code + **PKCE**) → редирект `myvpn://auth?code=…` обратно в приложение.
3. Приложение отправляет `code` + `code_verifier` на `vpn-api`; сервер обменивает код, определяет Яндекс ID и выдаёт подписанный токен сессии (JWT, 90 дней).
4. `/register` возвращает ссылку подписки 3x-ui (новому пользователю — с триалом на 24 часа), `/status` — срок действия.
5. Кнопка питания: скачивание подписки → разбор `vless://…security=reality` → запрос разрешения VPN → запуск `VpnService` с ядром Xray.
6. Подписка кончилась → экран тарифов → платёж ЮKassa в Custom Tab → после возврата приложение опрашивает `/status`, пока вебхук не продлит срок.

## Требования для сборки

- JDK 17, Android SDK 35 (compileSdk), Gradle — через wrapper (8.13)
- Файл `app/libs/libv2ray.aar` (ядро Xray, ~58 МБ). В Gradle подключается как `files("libs/libv2ray.aar")` и не скачивается автоматически
- Развёрнутый и настроенный [`vpn-api`](../vpn-api/README.md)


## Безопасность

- Весь трафик приложения — только HTTPS; пользовательские CA не доверяются (`network_security_config.xml`).
- Каждый запрос к API подписывается HMAC (`X-Device-Id`, `X-Timestamp`, `X-App-Signature`) и несёт Bearer-токен сессии. Секрет подписи вшит в APK, поэтому это защита от случайного/массового мусора, а не от целенаправленного реверса — настоящую защиту дают токен сессии и серверные проверки.
- Страница оплаты открывается только если адрес — https на `yookassa.ru` / `yoomoney.ru` (`PaymentUrlPolicy`).
- PKCE + проверка `state` защищают OAuth-редирект от перехвата кода другим приложением.
- Собственный трафик приложения исключён из туннеля (`addDisallowedApplication`) — нет петли маршрутизации; локального SOCKS-порта нет.


## Происхождение и лицензия

Проект основан на открытом клиенте [V2RayEZ](https://github.com/ovld-team/V2RayEZ) (MIT, © 2026 ovld-team): оставлены встроенное ядро Xray и каркас `VpnService`, остальное (вход, оплата, подписки, конфиг, UI) переработано. Лицензия — MIT, см. `LICENSE`.
