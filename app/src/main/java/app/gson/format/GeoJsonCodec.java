package app.gson.format;

import app.gson.model.GeoDataset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GeoJsonCodec {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private GeoJsonCodec() {}

    public static GeoDataset read(InputStream input, String fallbackName) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) text.append(buffer, 0, read);
        }
        try {
            JsonElement rootElement = JsonParser.parseString(text.toString());
            if (!rootElement.isJsonObject()) throw new IOException("GeoJSON должен содержать JSON-объект.");
            JsonObject root = rootElement.getAsJsonObject();
            validateCrs(root);
            String type = string(root, "type");
            List<GeoDataset.Feature> features = new ArrayList<>();
            if ("FeatureCollection".equals(type)) {
                JsonElement collection = root.get("features");
                if (collection == null || !collection.isJsonArray()) {
                    throw new IOException("В GeoJSON FeatureCollection отсутствует массив features.");
                }
                for (JsonElement item : collection.getAsJsonArray()) {
                    if (!item.isJsonObject()) throw new IOException("Элемент массива features не является объектом Feature.");
                    features.add(parseFeature(item.getAsJsonObject()));
                }
            } else if ("Feature".equals(type)) {
                features.add(parseFeature(root));
            } else if (isGeometryType(type)) {
                validateGeometry(root);
                features.add(new GeoDataset.Feature(null, new JsonObject(), root));
            } else {
                throw new IOException("Неизвестный тип GeoJSON: " + (type == null ? "не указан" : type) + ".");
            }
            List<GeoDataset.Layer> layers = new ArrayList<>();
            layers.add(new GeoDataset.Layer(fallbackName == null ? "GeoJSON" : fallbackName, features,
                    GeoDataset.SpatialReference.WGS84));
            return new GeoDataset(layers);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Не удалось разобрать GeoJSON: " + safeMessage(exception), exception);
        }
    }

    public static void write(GeoDataset dataset, java.io.OutputStream output) throws IOException {
        requireWgs84(dataset, "GeoJSON");
        JsonObject root = new JsonObject();
        root.addProperty("type", "FeatureCollection");
        JsonArray features = new JsonArray();
        for (GeoDataset.Layer layer : dataset.layers) {
            for (GeoDataset.Feature feature : layer.features) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "Feature");
                if (feature.id != null && !feature.id.isJsonNull()) item.add("id", feature.id.deepCopy());
                item.add("properties", feature.properties.deepCopy());
                item.add("geometry", feature.geometry == null ? JsonNull.INSTANCE : feature.geometry.deepCopy());
                features.add(item);
            }
        }
        root.add("features", features);
        output.write(PRETTY.toJson(root).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    public static void requireWgs84(GeoDataset dataset, String target) throws IOException {
        if (!dataset.isWgs84()) {
            throw new IOException("Экспорт в " + target + " требует EPSG:4326 (WGS 84). "
                    + "Встроенное перепроецирование координат пока не поддерживается. "
                    + "Сначала перепроецируйте слой в EPSG:4326 в ГИС-программе.");
        }
    }

    public static boolean isGeometryType(String type) {
        return "Point".equals(type) || "LineString".equals(type) || "Polygon".equals(type)
                || "MultiPoint".equals(type) || "MultiLineString".equals(type)
                || "MultiPolygon".equals(type) || "GeometryCollection".equals(type);
    }

    private static GeoDataset.Feature parseFeature(JsonObject object) throws IOException {
        if (!"Feature".equals(string(object, "type"))) throw new IOException("Ожидался объект Feature.");
        JsonElement id = object.get("id");
        JsonElement propertiesElement = object.get("properties");
        JsonObject properties = propertiesElement == null || propertiesElement.isJsonNull()
                ? new JsonObject()
                : propertiesElement.isJsonObject() ? propertiesElement.getAsJsonObject() : null;
        if (properties == null) throw new IOException("Поле properties должно быть объектом или null.");
        JsonElement geometryElement = object.get("geometry");
        JsonObject geometry = geometryElement == null || geometryElement.isJsonNull()
                ? null
                : geometryElement.isJsonObject() ? geometryElement.getAsJsonObject() : null;
        if (geometryElement != null && !geometryElement.isJsonNull() && geometry == null) {
            throw new IOException("Поле geometry должно быть геометрией или null.");
        }
        if (geometry != null) validateGeometry(geometry);
        return new GeoDataset.Feature(id, properties, geometry);
    }

    public static void validateGeometry(JsonObject geometry) throws IOException {
        String type = string(geometry, "type");
        if (!isGeometryType(type)) throw new IOException("Неподдерживаемый тип геометрии: " + type + ".");
        if ("GeometryCollection".equals(type)) {
            JsonElement geometries = geometry.get("geometries");
            if (geometries == null || !geometries.isJsonArray()) {
                throw new IOException("GeometryCollection должен содержать массив geometries.");
            }
            for (JsonElement child : geometries.getAsJsonArray()) {
                if (!child.isJsonObject()) throw new IOException("Некорректная геометрия внутри GeometryCollection.");
                validateGeometry(child.getAsJsonObject());
            }
            return;
        }
        JsonElement coordinates = geometry.get("coordinates");
        if (coordinates == null || !coordinates.isJsonArray()) {
            throw new IOException("У геометрии " + type + " отсутствует массив coordinates.");
        }
        int[] dimension = new int[]{0};
        JsonArray values = coordinates.getAsJsonArray();
        switch (type) {
            case "Point":
                validatePosition(values, dimension, true);
                break;
            case "LineString":
                validatePositionSequence(values, dimension);
                break;
            case "Polygon":
                for (JsonElement ring : values) {
                    if (!ring.isJsonArray()) throw new IOException("Кольцо Polygon должно быть массивом координат.");
                    validatePositionSequence(ring.getAsJsonArray(), dimension);
                }
                break;
            case "MultiPoint":
                for (JsonElement point : values) {
                    if (!point.isJsonArray()) throw new IOException("MultiPoint должен содержать массивы позиций.");
                    validatePosition(point.getAsJsonArray(), dimension, true);
                }
                break;
            case "MultiLineString":
                for (JsonElement line : values) {
                    if (!line.isJsonArray()) throw new IOException("MultiLineString должен содержать массивы линий.");
                    validatePositionSequence(line.getAsJsonArray(), dimension);
                }
                break;
            case "MultiPolygon":
                for (JsonElement polygon : values) {
                    if (!polygon.isJsonArray()) throw new IOException("MultiPolygon должен содержать массивы полигонов.");
                    for (JsonElement ring : polygon.getAsJsonArray()) {
                        if (!ring.isJsonArray()) throw new IOException("Кольцо MultiPolygon должно быть массивом координат.");
                        validatePositionSequence(ring.getAsJsonArray(), dimension);
                    }
                }
                break;
            default:
                throw new IOException("Неподдерживаемый тип геометрии: " + type + ".");
        }
    }

    private static void validatePositionSequence(JsonArray sequence, int[] dimension) throws IOException {
        for (JsonElement position : sequence) {
            if (!position.isJsonArray()) throw new IOException("Последовательность должна содержать позиции в виде массивов.");
            validatePosition(position.getAsJsonArray(), dimension, false);
        }
    }

    private static void validatePosition(JsonArray position, int[] dimension, boolean allowEmpty) throws IOException {
        if (position.size() == 0 && allowEmpty) return;
        if (position.size() < 2 || position.size() > 4) throw new IOException("Координата должна содержать от 2 до 4 чисел.");
        if (dimension[0] != 0 && dimension[0] != position.size()) {
            throw new IOException("В одной геометрии смешаны координаты разной размерности.");
        }
        dimension[0] = position.size();
        for (JsonElement ordinate : position) {
            if (!ordinate.isJsonPrimitive() || !ordinate.getAsJsonPrimitive().isNumber()
                    || (Double.isNaN(ordinate.getAsDouble()) || Double.isInfinite(ordinate.getAsDouble()))) {
                throw new IOException("Координаты должны содержать только конечные числовые значения.");
            }
        }
    }

    private static void validateCrs(JsonObject root) throws IOException {
        JsonElement crsElement = root.get("crs");
        if (crsElement == null || crsElement.isJsonNull()) return;
        String crsText = crsElement.toString().toUpperCase(Locale.ROOT);
        if (!(crsText.contains("4326") || crsText.contains("CRS84") || crsText.contains("WGS 84"))) {
            throw new IOException("GeoJSON указывает другую систему координат. "
                    + "GSon принимает GeoJSON только в EPSG:4326 / WGS 84.");
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
