# GSon

GSon is an offline Android vector-geodata converter. It converts real geometry and attribute data between GeoJSON, GeoPackage (GPKG), KML and KMZ. Input parsing, geometry encoding and output writing happen locally on the device.

## Supported formats

| Format | Read | Write |
|---|---:|---:|
| GeoJSON | yes | yes |
| GeoPackage (GPKG) | yes | yes |
| KML 2.2 | yes | yes |
| KMZ | yes | yes |

Supported geometry types: Point, LineString, Polygon (including interior rings), MultiPoint, MultiLineString, MultiPolygon and GeometryCollection. Geometry ordinates of 2–4 values are handled by the WKB codec; the third/fourth numeric ordinates are carried through, but GeoJSON coordinate arrays do not distinguish a Z axis from an M axis. KMZ archives are read by extracting a KML entry (preferring `doc.kml`) and written as a ZIP containing `doc.kml`.

GeoPackage support covers vector feature tables, core metadata, GeoPackageBinary headers and OGC WKB. Raster tile tables and non-core extended geometry encodings are not converted. Multiple GeoPackage feature tables are retained as separate layers when exporting to GPKG or KML/KMZ. GeoJSON uses a single FeatureCollection, so layer boundaries are flattened when writing GeoJSON.

### Coordinate reference systems

GSon never silently relabels coordinates or claims to reproject them. GeoJSON (RFC 7946), KML and KMZ export require EPSG:4326 / WGS 84 / OGC:CRS84. GPKG-to-GPKG conversion retains each layer's SRS metadata and coordinate values. Other CRS transformations are not implemented; an incompatible export fails with an explicit error. Reproject the layer to EPSG:4326 in a GIS application before exporting it to GeoJSON/KML/KMZ.

### Attribute preservation

GeoJSON properties and feature IDs are preserved. GPKG stores scalar properties in typed SQLite columns and uses GSon metadata tables to retain nested JSON properties and original GeoJSON feature IDs. KML ExtendedData is text-based; nested values are stored as JSON text and values read back from KML are strings. KML Placemark names and descriptions are mapped to `name` and `description` properties.

## Build

Requirements: JDK 17, Gradle 8.13 and Android SDK Platform 36.

```bash
./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The `gradlew` bootstrap script downloads the pinned Gradle distribution if it is not installed in the local GSon cache.

GitHub Actions runs local unit tests, Android lint and a debug APK build for pushes, pull requests and manual dispatches. The APK is uploaded as the `GSon-debug-apk` workflow artifact.

## Architecture

- `GeoJsonCodec`: GeoJSON parsing, validation and FeatureCollection writing.
- `KmlCodec` / `KmzCodec`: KML 2.2 XML and KMZ ZIP handling.
- `GeoPackageCodec`: GeoPackage feature table parsing and writing using Android SQLite.
- `WkbCodec`: OGC Well-Known Binary geometry encoder/decoder.
- `ConversionEngine`: file staging, format dispatch, CRS checks and clear conversion errors.
- `MainActivity`: Material 3 UI and Android Storage Access Framework file selection/save/open.

The app works offline and accesses user files only after selection through Android's document picker. The converter caps input and expanded KMZ KML sizes to limit accidental memory/disk exhaustion.

## Fonts

The supplied Ulm Grotesk fonts are used directly: `ulm_extrabold` for headings and `ulm_medium` for standard text. Compressed text payloads in `fontdata/` are restored into the original `.ttf` Android font resources automatically by the Gradle build.

## License

The application source is licensed under the MIT License. The supplied font files remain subject to their original font license; the font license was not included with the uploaded files.