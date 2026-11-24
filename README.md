# GSon Web

Веб-конвертер GeoJSON, GeoPackage, KML и KMZ.

## Запуск
Распакуй целиком ZIP, затем:
`python -m http.server 8080`
Открой http://127.0.0.1:8080.

## C++ / WebAssembly
GeoJSON проходит проверку JSON-синтаксиса в C++ модуле, собранном Emscripten с -O3. Модуль работает внутри Web Worker, поэтому парсинг не блокирует интерфейс. JavaScript преобразует проверенный JSON в объектную модель GeoJSON для конвертеров.
Скомпилированные geo-engine.js и geo-engine.wasm входят в ZIP, который создаёт GitHub Actions.

GeoPackage пока использует SQLite WebAssembly (sql.js). Он остаётся отдельным движком, поскольку GPKG — это SQLite-контейнер. Для полностью автономного GPKG sql.js и его WASM runtime нужно включить локально; текущая реализация загружает его с CDN при первом использовании.

Ограничения: CRS reprojection не реализован. Поддержка KML — распространённые Placemark-геометрии.
