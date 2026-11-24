package app.gson.format;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** OGC WKB reader/writer for the seven GeoPackage core geometry types. */
public final class WkbCodec {
    private static final int MAX_ELEMENTS = 2_000_000;
    private static final int MAX_DEPTH = 64;

    private WkbCodec() {}

    public static byte[] encode(JsonObject geometry) throws IOException {
        GeoJsonCodec.validateGeometry(geometry);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeGeometry(output, geometry, 0, null);
        return output.toByteArray();
    }

    public static JsonObject decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 5) throw new IOException("WKB слишком короткий или пустой.");
        ByteBuffer input = ByteBuffer.wrap(bytes);
        JsonObject geometry = readGeometry(input, 0);
        if (input.hasRemaining()) throw new IOException("В WKB обнаружены лишние данные после геометрии.");
        return geometry;
    }

    public static int dimension(JsonObject geometry) throws IOException {
        String type = geometry.get("type").getAsString();
        if ("GeometryCollection".equals(type)) {
            int max = 2;
            JsonArray geometries = geometry.getAsJsonArray("geometries");
            for (JsonElement item : geometries) max = Math.max(max, dimension(item.getAsJsonObject()));
            return max;
        }
        JsonArray coordinates = geometry.getAsJsonArray("coordinates");
        JsonArray tuple = findFirstTuple(coordinates);
        if (tuple == null) return 2;
        int size = tuple.size();
        if (size < 2 || size > 4) throw new IOException("WKB поддерживает позиции из 2–4 чисел.");
        return size;
    }

    private static JsonArray findFirstTuple(JsonArray value) {
        if (value == null || value.size() == 0) return null;
        if (value.get(0).isJsonPrimitive() && value.get(0).getAsJsonPrimitive().isNumber()) return value;
        for (JsonElement item : value) {
            if (item.isJsonArray()) {
                JsonArray tuple = findFirstTuple(item.getAsJsonArray());
                if (tuple != null) return tuple;
            }
        }
        return null;
    }

    private static void writeGeometry(ByteArrayOutputStream out, JsonObject geometry,
                                      int depth, Integer forcedDimension) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Слишком глубокая вложенность геометрии.");
        String type = geometry.get("type").getAsString();
        int base = baseType(type);
        int dim = forcedDimension == null ? dimension(geometry) : forcedDimension;
        writeByte(out, 1);
        writeIntLE(out, dimensionalType(base, dim));

        if ("Point".equals(type)) {
            JsonArray coordinate = geometry.getAsJsonArray("coordinates");
            if (coordinate == null || coordinate.size() == 0) {
                for (int i = 0; i < dim; i++) writeLongLE(out, Double.doubleToLongBits(Double.NaN));
            } else {
                writeCoordinate(out, coordinate, dim);
            }
        } else if ("LineString".equals(type)) {
            writeCoordinateSequence(out, geometry.getAsJsonArray("coordinates"), dim);
        } else if ("Polygon".equals(type)) {
            JsonArray rings = geometry.getAsJsonArray("coordinates");
            writeCount(out, rings.size());
            for (JsonElement ring : rings) writeCoordinateSequence(out, ring.getAsJsonArray(), dim);
        } else if ("MultiPoint".equals(type)) {
            JsonArray points = geometry.getAsJsonArray("coordinates");
            writeCount(out, points.size());
            for (JsonElement point : points) writeSimpleChild(out, "Point", point, dim, depth);
        } else if ("MultiLineString".equals(type)) {
            JsonArray lines = geometry.getAsJsonArray("coordinates");
            writeCount(out, lines.size());
            for (JsonElement line : lines) writeSimpleChild(out, "LineString", line, dim, depth);
        } else if ("MultiPolygon".equals(type)) {
            JsonArray polygons = geometry.getAsJsonArray("coordinates");
            writeCount(out, polygons.size());
            for (JsonElement polygon : polygons) writeSimpleChild(out, "Polygon", polygon, dim, depth);
        } else if ("GeometryCollection".equals(type)) {
            JsonArray geometries = geometry.getAsJsonArray("geometries");
            writeCount(out, geometries.size());
            for (JsonElement child : geometries) {
                if (!child.isJsonObject()) throw new IOException("Некорректный элемент GeometryCollection.");
                writeGeometry(out, child.getAsJsonObject(), depth + 1, null);
            }
        } else {
            throw new IOException("Неподдерживаемая геометрия WKB: " + type + ".");
        }
    }

    private static void writeSimpleChild(ByteArrayOutputStream out, String type, JsonElement coordinates,
                                         int dim, int depth) throws IOException {
        JsonObject child = new JsonObject();
        child.addProperty("type", type);
        child.add("coordinates", coordinates);
        writeGeometry(out, child, depth + 1, dim);
    }

    private static void writeCoordinateSequence(ByteArrayOutputStream out, JsonArray coordinates,
                                                int dimension) throws IOException {
        writeCount(out, coordinates.size());
        for (JsonElement coordinate : coordinates) {
            if (!coordinate.isJsonArray()) throw new IOException("Ожидалась координата в виде массива чисел.");
            writeCoordinate(out, coordinate.getAsJsonArray(), dimension);
        }
    }

    private static void writeCoordinate(ByteArrayOutputStream out, JsonArray coordinate,
                                        int dimension) throws IOException {
        if (coordinate.size() != dimension) {
            throw new IOException("В одной геометрии смешаны координаты разной размерности.");
        }
        for (JsonElement ordinate : coordinate) {
            if (!ordinate.isJsonPrimitive() || !ordinate.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Координаты должны содержать только числа.");
            }
            double value = ordinate.getAsDouble();
            if (!Double.isFinite(value)) throw new IOException("Координата содержит бесконечное или некорректное значение.");
            writeLongLE(out, Double.doubleToLongBits(value));
        }
    }

    private static JsonObject readGeometry(ByteBuffer input, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Слишком глубокая вложенность WKB.");
        require(input, 5);
        int byteOrder = input.get() & 0xff;
        if (byteOrder != 0 && byteOrder != 1) throw new IOException("Неизвестный порядок байтов WKB.");
        input.order(byteOrder == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int rawType = input.getInt();
        int dimension = 2;
        int type = rawType;
        if (rawType >= 3000 && rawType < 4000) {
            dimension = 4;
            type = rawType - 3000;
        } else if (rawType >= 2000 && rawType < 3000) {
            dimension = 3;
            type = rawType - 2000;
        } else if (rawType >= 1000 && rawType < 2000) {
            dimension = 3;
            type = rawType - 1000;
        }
        if (type < 1 || type > 7) throw new IOException("WKB содержит неподдерживаемый тип геометрии: " + rawType + ".");

        JsonObject geometry = new JsonObject();
        geometry.addProperty("type", typeName(type));
        if (type == 1) {
            JsonArray point = readCoordinate(input, dimension);
            geometry.add("coordinates", point);
        } else if (type == 2) {
            geometry.add("coordinates", readCoordinateSequence(input, dimension));
        } else if (type == 3) {
            int ringCount = readCount(input);
            JsonArray rings = new JsonArray();
            for (int i = 0; i < ringCount; i++) rings.add(readCoordinateSequence(input, dimension));
            geometry.add("coordinates", rings);
        } else if (type == 4 || type == 5 || type == 6) {
            int count = readCount(input);
            JsonArray coordinates = new JsonArray();
            int expectedChild = type == 4 ? 1 : type == 5 ? 2 : 3;
            for (int i = 0; i < count; i++) {
                JsonObject child = readGeometry(input, depth + 1);
                if (baseType(child.get("type").getAsString()) != expectedChild) {
                    throw new IOException("Неверный тип дочерней геометрии внутри " + typeName(type) + ".");
                }
                coordinates.add(child.get("coordinates").deepCopy());
            }
            geometry.add("coordinates", coordinates);
        } else {
            int count = readCount(input);
            JsonArray geometries = new JsonArray();
            for (int i = 0; i < count; i++) geometries.add(readGeometry(input, depth + 1));
            geometry.add("geometries", geometries);
        }
        return geometry;
    }

    private static JsonArray readCoordinateSequence(ByteBuffer input, int dimension) throws IOException {
        int count = readCount(input);
        JsonArray coordinates = new JsonArray();
        for (int i = 0; i < count; i++) coordinates.add(readCoordinate(input, dimension));
        return coordinates;
    }

    private static JsonArray readCoordinate(ByteBuffer input, int dimension) throws IOException {
        require(input, dimension * 8);
        JsonArray coordinate = new JsonArray();
        double[] ordinates = new double[dimension];
        for (int i = 0; i < dimension; i++) ordinates[i] = input.getDouble();
        if (Double.isNaN(ordinates[0]) && Double.isNaN(ordinates[1])) return coordinate;
        for (double ordinate : ordinates) coordinate.add(number(ordinate));
        return coordinate;
    }

    private static JsonPrimitive number(double value) {
        return new JsonPrimitive(value);
    }

    private static int baseType(String type) throws IOException {
        if ("Point".equals(type)) return 1;
        if ("LineString".equals(type)) return 2;
        if ("Polygon".equals(type)) return 3;
        if ("MultiPoint".equals(type)) return 4;
        if ("MultiLineString".equals(type)) return 5;
        if ("MultiPolygon".equals(type)) return 6;
        if ("GeometryCollection".equals(type)) return 7;
        throw new IOException("Неподдерживаемая геометрия: " + type + ".");
    }

    private static String typeName(int type) throws IOException {
        switch (type) {
            case 1: return "Point";
            case 2: return "LineString";
            case 3: return "Polygon";
            case 4: return "MultiPoint";
            case 5: return "MultiLineString";
            case 6: return "MultiPolygon";
            case 7: return "GeometryCollection";
            default: throw new IOException("Неизвестный тип геометрии WKB: " + type + ".");
        }
    }

    private static int dimensionalType(int base, int dimension) throws IOException {
        if (dimension == 2) return base;
        if (dimension == 3) return base + 1000;
        if (dimension == 4) return base + 3000;
        throw new IOException("Неподдерживаемая размерность координат: " + dimension + ".");
    }

    private static void writeCount(ByteArrayOutputStream out, int count) throws IOException {
        if (count < 0 || count > MAX_ELEMENTS) throw new IOException("Слишком много координат или геометрических элементов.");
        writeIntLE(out, count);
    }

    private static int readCount(ByteBuffer input) throws IOException {
        require(input, 4);
        int count = input.getInt();
        if (count < 0 || count > MAX_ELEMENTS) throw new IOException("Недопустимое количество координат в WKB.");
        return count;
    }

    private static void require(ByteBuffer input, int bytes) throws IOException {
        if (bytes < 0 || input.remaining() < bytes) throw new IOException("WKB неожиданно завершился: файл повреждён или обрезан.");
    }

    private static void writeByte(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void writeLongLE(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) out.write((int) ((value >>> (8 * i)) & 0xff));
    }
}
