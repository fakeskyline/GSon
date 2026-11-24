# GSon

GSon is an offline Android vector-geodata converter. It converts actual geometry and attribute data between GeoJSON, GeoPackage (GPKG), KML and KMZ.

## Supported formats

| Format | Read | Write |
|---|---:|---:|
| GeoJSON | yes | yes |
| GeoPackage (GPKG) | yes | yes |
| KML | yes | yes |
| KMZ | yes | yes |

The converter handles Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon and GeometryCollection geometries, including Z/M ordinates where represented by WKB. GeoPackage files can contain multiple feature tables; these are retained as separate layers when writing GPKG or KML/KMZ.

### Coordinate reference systems

GSon does not silently relabel coordinates or pretend to reproject them. GeoJSON (RFC 7946), KML and KMZ export require EPSG:4326 / WGS 84. GeoPackage-to-GeoPackage conversion retains each layer's stored SRS metadata and coordinate values. Other CRS transformations are not implemented yet; an incompatible export fails with an explicit message.

### Attribute handling

GeoJSON properties are preserved. GPKG exports store scalar properties as typed columns and preserve nested JSON values and original feature IDs in GSon metadata tables. KML ExtendedData is text-based, so nested attribute values are serialized as JSON text when exported to KML/KMZ.

## Build

Requirements: JDK 17 and Android SDK Platform 36.

```bash
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow builds the debug APK, runs unit tests and Android lint on pushes, pull requests and manual dispatches.

## Architecture

- `GeoJsonCodec`: GeoJSON Feature/FeatureCollection parsing and writing.
- `KmlCodec` / `KmzCodec`: KML XML and KMZ ZIP handling.
- `GeoPackageCodec`: SQLite GeoPackage feature-table parsing and writing.
- `WkbCodec`: OGC Well-Known Binary geometry encoding/decoding used by GeoPackage.
- `ConversionEngine`: format dispatch, temporary-file handling, CRS checks and conversion errors.
- `MainActivity`: Material Design 3 interface and Android Storage Access Framework integration.

The app works offline and accesses files only after the user selects them through Android's document picker.

## Fonts

The UI uses the supplied Ulm Grotesk fonts: `ulm_extrabold` for headings and `ulm_medium` for standard text. The compressed font payloads in `fontdata/` are restored into Android font resources automatically by the Gradle build.

## License

The application source is licensed under the MIT License. The supplied font files remain subject to their original font license; see the accompanying font files or license supplied by their author.
