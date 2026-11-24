# GSon

Локальный конвертер GeoJSON, GeoPackage (GPKG), KML и KMZ.

## Запуск локально

Распакуй проект и запусти локальный HTTP-сервер:

```bash
python -m http.server 8080
```

Открой http://127.0.0.1:8080.

## Фоновая обработка

Все операции чтения и записи форматов выполняются в отдельном браузерном Worker (`format-worker.js`). Главный поток отвечает за интерфейс, а Worker разбирает GeoJSON, GPKG, KML и KMZ, формирует выходной файл и передаёт готовый буфер обратно. Файл не отправляется на сервер: обработка остаётся на устройстве пользователя.

Для GPKG используется SQLite WebAssembly (sql.js), для KMZ — JSZip. При первом обращении к GPKG sql.js и WASM загружаются с CDN; для этого требуется интернет. Тяжёлые файлы всё ещё требуют оперативной памяти устройства, но их разбор и упаковка не выполняются в UI-потоке.

## Cloudflare Workers

Проект подготовлен для Workers Static Assets вместе с Worker-обработчиком API. Статика раздаётся через Cloudflare, а `/api/health` обрабатывается Worker-кодом.

### Ручной деплой

Установи Node.js и выполни из корня проекта:

```bash
npx wrangler@4 login
npx wrangler@4 deploy
```

### Автоматический деплой из GitHub Actions

Добавь в Settings → Secrets and variables → Actions два repository secrets:

- `CLOUDFLARE_API_TOKEN` — API-токен с правом редактирования Workers Scripts для аккаунта.
- `CLOUDFLARE_ACCOUNT_ID` — ID Cloudflare-аккаунта.

После этого каждый push в `main` запускает `.github/workflows/deploy-cloudflare.yml`. URL Worker появится в логе шага Deploy.

Проверка Worker API: `/api/health`.

## C++ / WebAssembly

В репозитории остаётся C++-движок, собираемый Emscripten workflow для GeoJSON JSON validation. Форматный Worker в текущей реализации использует JS/SQLite WASM для GPKG и JS для KML/KMZ.

## Ограничения форматов

- Перепроецирование CRS не реализовано.
- Экспорт KML/KMZ и GPKG требует EPSG:4326 / WGS 84.
- KML поддерживает распространённые Placemark-геометрии и основные ExtendedData-поля; сложные стили, сетевые ссылки и все расширения KML не переносятся.
- GPKG ориентирован на векторные таблицы; растровые тайлы и расширения GeoPackage не конвертируются.
