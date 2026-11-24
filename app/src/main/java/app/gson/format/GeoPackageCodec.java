package app.gson.format;

import app.gson.model.GeoDataset;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** GeoPackage 1.3 vector-feature reader/writer backed by Android's SQLite engine. */
public final class GeoPackageCodec {
    private static final int APPLICATION_ID = 0x47504B47;
    private static final int USER_VERSION = 10300;
    private static final String GEOMETRY_COLUMN = "geom";
    private static final String FEATURE_ID_COLUMN = "fid";

    private GeoPackageCodec() {}

    public static GeoDataset read(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 100) {
            throw new IOException("Файл GeoPackage пустой или повреждён.");
        }
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            if (!tableExists(database, "gpkg_contents")
                    || !tableExists(database, "gpkg_geometry_columns")
                    || !tableExists(database, "gpkg_spatial_ref_sys")) {
                throw new IOException("Это не GeoPackage: отсутствуют обязательные системные таблицы.");
            }

            List<GeoDataset.Layer> layers = new ArrayList<>();
            try (Cursor contents = database.rawQuery(
                    "SELECT table_name, identifier, description, srs_id FROM gpkg_contents WHERE lower(data_type) = 'features' ORDER BY rowid",
                    null)) {
                while (contents.moveToNext()) {
                    String tableName = contents.getString(0);
                    String name = contents.isNull(1) ? tableName : contents.getString(1);
                    int contentsSrsId = contents.isNull(3) ? 4326 : contents.getInt(3);
                    GeometryColumn geometryColumn = findGeometryColumn(database, tableName);
                    if (geometryColumn == null) continue;
                    if (contentsSrsId != geometryColumn.srsId) {
                        throw new IOException("В слое " + name + " не совпадают SRS в gpkg_contents и gpkg_geometry_columns.");
                    }
                    GeoDataset.SpatialReference srs = readSrs(database, geometryColumn.srsId);
                    List<GeoDataset.Feature> features = readFeatures(database, tableName,
                            geometryColumn.columnName, geometryColumn.srsId);
                    layers.add(new GeoDataset.Layer(name, features, srs));
                }
            }
            if (layers.isEmpty()) {
                throw new IOException("В GeoPackage не найдено векторных feature-слоёв. "
                        + "Растровые тайлы и изображения в этой версии не конвертируются.");
            }
            return new GeoDataset(layers);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Не удалось прочитать GeoPackage: " + safeMessage(exception), exception);
        } finally {
            if (database != null) database.close();
        }
    }

    public static void write(GeoDataset dataset, File file) throws IOException {
        if (file == null) throw new IOException("Не задан файл результата GeoPackage.");
        SQLiteDatabase database = null;
        boolean completed = false;
        try {
            if (file.exists() && !file.delete()) throw new IOException("Не удалось подготовить файл GeoPackage для записи.");
            database = SQLiteDatabase.openOrCreateDatabase(file, null);
            database.setForeignKeyConstraintsEnabled(true);
            database.execSQL("PRAGMA application_id = " + APPLICATION_ID);
            database.execSQL("PRAGMA user_version = " + USER_VERSION);
            createCoreTables(database);

            database.beginTransaction();
            try {
                insertSrsIfMissing(database, new GeoDataset.SpatialReference(-1,
                        "Undefined Cartesian", "NONE", -1, "undefined", "undefined Cartesian coordinate reference system"));
                insertSrsIfMissing(database, new GeoDataset.SpatialReference(0,
                        "Undefined Geographic", "NONE", 0, "undefined", "undefined geographic coordinate reference system"));
                insertSrsIfMissing(database, GeoDataset.SpatialReference.WGS84);
                database.execSQL("CREATE TABLE gson_property_schema ("
                        + "table_name TEXT NOT NULL, column_name TEXT NOT NULL, property_name TEXT NOT NULL, value_type TEXT NOT NULL,"
                        + " PRIMARY KEY (table_name, column_name))");
                database.execSQL("CREATE TABLE gson_feature_meta ("
                        + "table_name TEXT NOT NULL, fid INTEGER NOT NULL, id_json TEXT, PRIMARY KEY (table_name, fid))");

                Set<String> identifiers = new HashSet<>();
                for (int i = 0; i < dataset.layers.size(); i++) {
                    GeoDataset.Layer layer = dataset.layers.get(i);
                    insertSrsIfMissing(database, layer.srs);
                    writeLayer(database, layer, i, identifiers);
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            database.execSQL("PRAGMA optimize");
            completed = true;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Не удалось создать GeoPackage: " + safeMessage(exception), exception);
        } finally {
            if (database != null) database.close();
            if (!completed && file.exists()) file.delete();
        }
    }

    private static void createCoreTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE gpkg_spatial_ref_sys ("
                + "srs_name TEXT NOT NULL, srs_id INTEGER NOT NULL PRIMARY KEY, organization TEXT NOT NULL, "
                + "organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)");
        db.execSQL("CREATE TABLE gpkg_contents ("
                + "table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE, "
                + "description TEXT DEFAULT '', last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')), "
                + "min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER, "
                + "CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id))");
        db.execSQL("CREATE TABLE gpkg_geometry_columns ("
                + "table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL, "
                + "srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL, "
                + "CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name), "
                + "CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name), "
                + "CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id))");
    }

    private static void writeLayer(SQLiteDatabase db, GeoDataset.Layer layer, int index,
                                   Set<String> identifiers) throws Exception {
        String table = "gson_layer_" + (index + 1) + "_" + safeTableSuffix(layer.name);
        List<PropertyColumn> properties = collectPropertyColumns(layer.features);
        StringBuilder create = new StringBuilder("CREATE TABLE ").append(quote(table)).append(" (")
                .append(quote(FEATURE_ID_COLUMN)).append(" INTEGER PRIMARY KEY AUTOINCREMENT, ")
                .append(quote(GEOMETRY_COLUMN)).append(" BLOB");
        for (PropertyColumn property : properties) {
            create.append(", ").append(quote(property.columnName)).append(' ').append(property.sqlType);
        }
        create.append(')');
        db.execSQL(create.toString());

        for (PropertyColumn property : properties) {
            ContentValues meta = new ContentValues();
            meta.put("table_name", table);
            meta.put("column_name", property.columnName);
            meta.put("property_name", property.propertyName);
            meta.put("value_type", property.kind);
            db.insertOrThrow("gson_property_schema", null, meta);
        }

        Bounds bounds = new Bounds();
        String geometryType = null;
        boolean mixedGeometryTypes = false;
        int maxDimension = 2;
        for (GeoDataset.Feature feature : layer.features) {
            ContentValues values = new ContentValues();
            if (feature.geometry == null) {
                values.putNull(GEOMETRY_COLUMN);
            } else {
                GeoJsonCodec.validateGeometry(feature.geometry);
                values.put(GEOMETRY_COLUMN, encodeGeometry(feature.geometry, layer.srs.srsId));
                String type = feature.geometry.get("type").getAsString().toUpperCase(Locale.ROOT);
                if (geometryType == null) geometryType = type;
                else if (!geometryType.equals(type)) mixedGeometryTypes = true;
                maxDimension = Math.max(maxDimension, WkbCodec.dimension(feature.geometry));
                bounds.include(feature.geometry);
            }
            for (PropertyColumn property : properties) {
                putProperty(values, property, feature.properties.get(property.propertyName));
            }
            long fid = db.insertOrThrow(table, null, values);
            if (feature.id != null && !feature.id.isJsonNull()) {
                ContentValues id = new ContentValues();
                id.put("table_name", table);
                id.put("fid", fid);
                id.put("id_json", feature.id.toString());
                db.insertOrThrow("gson_feature_meta", null, id);
            }
        }

        String identifier = uniqueIdentifier(layer.name, identifiers);
        ContentValues contents = new ContentValues();
        contents.put("table_name", table);
        contents.put("data_type", "features");
        contents.put("identifier", identifier);
        contents.put("description", "Created by GSon");
        contents.put("srs_id", layer.srs.srsId);
        if (bounds.hasValues) {
            contents.put("min_x", bounds.minX);
            contents.put("min_y", bounds.minY);
            contents.put("max_x", bounds.maxX);
            contents.put("max_y", bounds.maxY);
        }
        db.insertOrThrow("gpkg_contents", null, contents);

        ContentValues geometry = new ContentValues();
        geometry.put("table_name", table);
        geometry.put("column_name", GEOMETRY_COLUMN);
        geometry.put("geometry_type_name", geometryType == null || mixedGeometryTypes ? "GEOMETRY" : geometryType);
        geometry.put("srs_id", layer.srs.srsId);
        geometry.put("z", maxDimension >= 3 ? 2 : 0);
        geometry.put("m", maxDimension >= 4 ? 2 : 0);
        db.insertOrThrow("gpkg_geometry_columns", null, geometry);
    }

    private static List<PropertyColumn> collectPropertyColumns(List<GeoDataset.Feature> features) {
        LinkedHashMap<String, List<JsonElement>> values = new LinkedHashMap<>();
        for (GeoDataset.Feature feature : features) {
            for (Map.Entry<String, JsonElement> entry : feature.properties.entrySet()) {
                values.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue());
            }
        }
        Set<String> usedNames = new HashSet<>();
        Collections.addAll(usedNames, "fid", "geom", "rowid", "_rowid_", "oid");
        List<PropertyColumn> columns = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, List<JsonElement>> entry : values.entrySet()) {
            String propertyName = entry.getKey();
            String candidate = propertyName == null ? "" : propertyName;
            String key = candidate.toLowerCase(Locale.ROOT);
            if (candidate.trim().isEmpty() || usedNames.contains(key)) candidate = "gson_property_" + index;
            while (usedNames.contains(candidate.toLowerCase(Locale.ROOT))) candidate = candidate + "_" + index;
            usedNames.add(candidate.toLowerCase(Locale.ROOT));
            String kind = inferKind(entry.getValue());
            String sqlType = "INTEGER".equals(kind) || "BOOLEAN".equals(kind) ? "INTEGER"
                    : "REAL".equals(kind) ? "REAL" : "TEXT";
            columns.add(new PropertyColumn(propertyName, candidate, kind, sqlType));
            index++;
        }
        return columns;
    }

    private static String inferKind(List<JsonElement> values) {
        String found = null;
        boolean integerAndReal = false;
        for (JsonElement value : values) {
            if (value == null || value.isJsonNull()) continue;
            String current;
            if (value.isJsonObject() || value.isJsonArray()) current = "JSON";
            else if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isBoolean()) current = "BOOLEAN";
                else if (value.getAsJsonPrimitive().isString()) current = "TEXT";
                else if (value.getAsJsonPrimitive().isNumber()) {
                    double number = value.getAsDouble();
                    current = Double.isFinite(number) && number == Math.rint(number) ? "INTEGER" : "REAL";
                } else current = "TEXT";
            } else current = "TEXT";
            if (found == null) found = current;
            else if (("INTEGER".equals(found) && "REAL".equals(current))
                    || ("REAL".equals(found) && "INTEGER".equals(current))) integerAndReal = true;
            else if (!found.equals(current)) return "JSON";
        }
        if (integerAndReal) return "REAL";
        return found == null ? "TEXT" : found;
    }

    private static void putProperty(ContentValues values, PropertyColumn column, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            values.putNull(column.columnName);
            return;
        }
        switch (column.kind) {
            case "INTEGER": values.put(column.columnName, value.getAsLong()); break;
            case "REAL": values.put(column.columnName, value.getAsDouble()); break;
            case "BOOLEAN": values.put(column.columnName, value.getAsBoolean() ? 1 : 0); break;
            case "JSON": values.put(column.columnName, value.toString()); break;
            default: values.put(column.columnName, value.getAsString()); break;
        }
    }

    private static List<GeoDataset.Feature> readFeatures(SQLiteDatabase db, String table,
                                                          String geometryColumn, int srsId) throws IOException {
        Map<String, FieldMetadata> fieldMetadata = readFieldMetadata(db, table);
        Map<Long, JsonElement> featureIds = readFeatureIds(db, table);
        List<GeoDataset.Feature> features = new ArrayList<>();
        String sql = "SELECT rowid AS \"__gson_rowid\", * FROM " + quote(table);
        try (Cursor cursor = db.rawQuery(sql, null)) {
            String[] columns = cursor.getColumnNames();
            int geometryIndex = findColumn(columns, geometryColumn);
            int fidIndex = findColumn(columns, FEATURE_ID_COLUMN);
            int rowidIndex = findColumn(columns, "__gson_rowid");
            if (geometryIndex < 0) throw new IOException("В таблице " + table + " отсутствует geometry-колонка.");
            while (cursor.moveToNext()) {
                JsonObject geometry = null;
                if (!cursor.isNull(geometryIndex)) {
                    if (cursor.getType(geometryIndex) != Cursor.FIELD_TYPE_BLOB) {
                        throw new IOException("Геометрия в таблице " + table + " хранится не в GeoPackageBinary BLOB.");
                    }
                    geometry = decodeGeometry(cursor.getBlob(geometryIndex), srsId);
                }
                long rowId = fidIndex >= 0 ? cursor.getLong(fidIndex) : cursor.getLong(rowidIndex);
                JsonObject properties = new JsonObject();
                for (int column = 0; column < columns.length; column++) {
                    String columnName = columns[column];
                    if (column == geometryIndex || column == fidIndex || column == rowidIndex
                            || "fid".equalsIgnoreCase(columnName) || "geom".equalsIgnoreCase(columnName)
                            || columnName.startsWith("sqlite_")) continue;
                    FieldMetadata metadata = fieldMetadata.get(columnName.toLowerCase(Locale.ROOT));
                    String propertyName = metadata == null ? columnName : metadata.propertyName;
                    JsonElement value = readSqlValue(cursor, column, metadata == null ? null : metadata.kind);
                    properties.add(propertyName, value);
                }
                JsonElement id = featureIds.get(rowId);
                if (id == null && fidIndex >= 0) id = new com.google.gson.JsonPrimitive(rowId);
                features.add(new GeoDataset.Feature(id, properties, geometry));
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Ошибка чтения feature-таблицы " + table + ": " + safeMessage(exception), exception);
        }
        return features;
    }

    private static JsonElement readSqlValue(Cursor cursor, int index, String kind) throws IOException {
        if (cursor.isNull(index)) return JsonNull.INSTANCE;
        if ("JSON".equals(kind)) {
            try { return JsonParser.parseString(cursor.getString(index)); }
            catch (RuntimeException exception) { throw new IOException("Повреждено JSON-значение атрибута GeoPackage.", exception); }
        }
        if ("BOOLEAN".equals(kind)) return new com.google.gson.JsonPrimitive(cursor.getLong(index) != 0);
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER: return new com.google.gson.JsonPrimitive(cursor.getLong(index));
            case Cursor.FIELD_TYPE_FLOAT: return new com.google.gson.JsonPrimitive(cursor.getDouble(index));
            case Cursor.FIELD_TYPE_BLOB:
                return new com.google.gson.JsonPrimitive(android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP));
            case Cursor.FIELD_TYPE_STRING:
                return new com.google.gson.JsonPrimitive(cursor.getString(index));
            default: return JsonNull.INSTANCE;
        }
    }

    private static Map<String, FieldMetadata> readFieldMetadata(SQLiteDatabase db, String table) {
        if (!tableExists(db, "gson_property_schema")) return Collections.emptyMap();
        Map<String, FieldMetadata> result = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT column_name, property_name, value_type FROM gson_property_schema WHERE table_name = ?", new String[]{table})) {
            while (cursor.moveToNext()) {
                result.put(cursor.getString(0).toLowerCase(Locale.ROOT),
                        new FieldMetadata(cursor.getString(1), cursor.getString(2)));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static Map<Long, JsonElement> readFeatureIds(SQLiteDatabase db, String table) {
        if (!tableExists(db, "gson_feature_meta")) return Collections.emptyMap();
        Map<Long, JsonElement> result = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT fid, id_json FROM gson_feature_meta WHERE table_name = ?", new String[]{table})) {
            while (cursor.moveToNext()) {
                try { result.put(cursor.getLong(0), JsonParser.parseString(cursor.getString(1))); }
                catch (RuntimeException ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static GeometryColumn findGeometryColumn(SQLiteDatabase db, String table) throws IOException {
        try (Cursor cursor = db.rawQuery("SELECT column_name, geometry_type_name, srs_id FROM gpkg_geometry_columns WHERE table_name = ?", new String[]{table})) {
            if (!cursor.moveToFirst()) return null;
            return new GeometryColumn(cursor.getString(0), cursor.getString(1), cursor.getInt(2));
        }
    }

    private static GeoDataset.SpatialReference readSrs(SQLiteDatabase db, int srsId) throws IOException {
        try (Cursor cursor = db.rawQuery("SELECT srs_name, organization, organization_coordsys_id, definition, description "
                + "FROM gpkg_spatial_ref_sys WHERE srs_id = ?", new String[]{String.valueOf(srsId)})) {
            if (!cursor.moveToFirst()) throw new IOException("Для SRS " + srsId + " отсутствует запись в gpkg_spatial_ref_sys.");
            return new GeoDataset.SpatialReference(srsId, cursor.getString(0), cursor.getString(1),
                    cursor.getInt(2), cursor.getString(3), cursor.isNull(4) ? "" : cursor.getString(4));
        }
    }

    private static void insertSrsIfMissing(SQLiteDatabase db, GeoDataset.SpatialReference srs) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM gpkg_spatial_ref_sys WHERE srs_id = ?", new String[]{String.valueOf(srs.srsId)})) {
            if (cursor.moveToFirst()) return;
        }
        ContentValues values = new ContentValues();
        values.put("srs_name", srs.name);
        values.put("srs_id", srs.srsId);
        values.put("organization", srs.organization);
        values.put("organization_coordsys_id", srs.organizationCoordSysId);
        values.put("definition", srs.definition);
        values.put("description", srs.description);
        db.insertOrThrow("gpkg_spatial_ref_sys", null, values);
    }

    private static byte[] encodeGeometry(JsonObject geometry, int srsId) throws IOException {
        byte[] wkb = WkbCodec.encode(geometry);
        ByteArrayOutputStream output = new ByteArrayOutputStream(wkb.length + 8);
        output.write('G');
        output.write('P');
        output.write(0);
        output.write(1);
        writeIntLE(output, srsId);
        output.write(wkb);
        return output.toByteArray();
    }

    private static JsonObject decodeGeometry(byte[] blob, int expectedSrsId) throws IOException {
        if (blob == null || blob.length < 8) throw new IOException("GeoPackageBinary слишком короткий.");
        ByteBuffer buffer = ByteBuffer.wrap(blob);
        if (buffer.get() != 'G' || buffer.get() != 'P') throw new IOException("Неизвестная сигнатура GeoPackageBinary (ожидалась GP).");
        int version = buffer.get() & 0xff;
        if (version != 0) throw new IOException("Версия GeoPackageBinary " + version + " не поддерживается.");
        int flags = buffer.get() & 0xff;
        if ((flags & 0xC0) != 0) throw new IOException("GeoPackageBinary содержит зарезервированные флаги.");
        if ((flags & 0x20) != 0) throw new IOException("GeoPackageBinary использует расширенный формат геометрии, который GSon пока не поддерживает.");
        ByteOrder order = (flags & 1) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        buffer.order(order);
        int srsId = buffer.getInt();
        if (srsId != expectedSrsId) throw new IOException("SRS геометрии не совпадает с метаданными слоя GeoPackage.");
        int envelopeIndicator = (flags >>> 1) & 7;
        int envelopeDoubles;
        switch (envelopeIndicator) {
            case 0: envelopeDoubles = 0; break;
            case 1: envelopeDoubles = 4; break;
            case 2: case 3: envelopeDoubles = 6; break;
            case 4: envelopeDoubles = 8; break;
            default: throw new IOException("Некорректный тип envelope в GeoPackageBinary.");
        }
        long skip = envelopeDoubles * 8L;
        if (skip > buffer.remaining()) throw new IOException("GeoPackageBinary обрезан в заголовке envelope.");
        buffer.position(buffer.position() + (int) skip);
        if ((flags & 0x10) != 0) return null;
        if (buffer.remaining() < 5) throw new IOException("В GeoPackageBinary отсутствует WKB-геометрия.");
        byte[] wkb = new byte[buffer.remaining()];
        buffer.get(wkb);
        return WkbCodec.decode(wkb);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return cursor.moveToFirst();
        }
    }

    private static int findColumn(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) if (name.equalsIgnoreCase(columns[i])) return i;
        return -1;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String safeTableSuffix(String name) {
        String value = name == null ? "layer" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        if (value.isEmpty()) value = "layer";
        if (value.length() > 32) value = value.substring(0, 32);
        return value;
    }

    private static String uniqueIdentifier(String name, Set<String> used) {
        String base = name == null || name.trim().isEmpty() ? "layer" : name.trim();
        String result = base;
        int suffix = 2;
        while (used.contains(result.toLowerCase(Locale.ROOT))) result = base + " (" + suffix++ + ")";
        used.add(result.toLowerCase(Locale.ROOT));
        return result;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private static final class GeometryColumn {
        final String columnName;
        final String typeName;
        final int srsId;
        GeometryColumn(String columnName, String typeName, int srsId) {
            this.columnName = columnName;
            this.typeName = typeName;
            this.srsId = srsId;
        }
    }

    private static final class FieldMetadata {
        final String propertyName;
        final String kind;
        FieldMetadata(String propertyName, String kind) { this.propertyName = propertyName; this.kind = kind; }
    }

    private static final class PropertyColumn {
        final String propertyName;
        final String columnName;
        final String kind;
        final String sqlType;
        PropertyColumn(String propertyName, String columnName, String kind, String sqlType) {
            this.propertyName = propertyName;
            this.columnName = columnName;
            this.kind = kind;
            this.sqlType = sqlType;
        }
    }

    private static final class Bounds {
        boolean hasValues;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        void include(JsonObject geometry) throws IOException {
            String type = geometry.get("type").getAsString();
            if ("GeometryCollection".equals(type)) {
                for (JsonElement child : geometry.getAsJsonArray("geometries")) include(child.getAsJsonObject());
            } else {
                includeArray(geometry.getAsJsonArray("coordinates"));
            }
        }

        private void includeArray(com.google.gson.JsonArray array) throws IOException {
            if (array == null || array.size() == 0) return;
            JsonElement first = array.get(0);
            if (first.isJsonPrimitive() && first.getAsJsonPrimitive().isNumber()) {
                if (array.size() < 2) throw new IOException("В координате меньше двух значений.");
                double x = array.get(0).getAsDouble();
                double y = array.get(1).getAsDouble();
                if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IOException("Координаты содержат некорректные значения.");
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                hasValues = true;
            } else {
                for (JsonElement child : array) {
                    if (child.isJsonArray()) includeArray(child.getAsJsonArray());
                }
            }
        }
    }
}
