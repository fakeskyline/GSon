package app.gson.format;

import android.util.Xml;

import app.gson.model.GeoDataset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xmlpull.v1.XmlSerializer;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** KML 2.2 reader/writer for Placemark and core OGC Simple Features geometries. */
public final class KmlCodec {
    private static final String KML_NS = "http://www.opengis.net/kml/2.2";
    private static final int MAX_PLACEMARKS = 2_000_000;
    private static final int MAX_KML_BYTES = 256 * 1024 * 1024;

    private KmlCodec() {}

    public static GeoDataset read(InputStream input, String fallbackName) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            byte[] xml = readXmlBytes(input);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(normalizeXmlDeclaration(xml)));
            Element root = document.getDocumentElement();
            if (root == null || !"kml".equals(localName(root))) {
                throw new IOException("Файл не является документом KML.");
            }

            String layerName = firstText(root, "Document", "name");
            if (layerName == null) layerName = firstText(root, "Folder", "name");
            if (layerName == null) layerName = fallbackName == null ? "KML" : fallbackName;

            List<GeoDataset.Feature> features = new ArrayList<>();
            NodeList all = root.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Node node = all.item(i);
                if (!(node instanceof Element) || !"Placemark".equals(localName((Element) node))) continue;
                if (features.size() >= MAX_PLACEMARKS) throw new IOException("В KML слишком много объектов.");
                Element placemark = (Element) node;
                JsonObject geometry = null;
                for (Element child : directChildren(placemark)) {
                    if (isGeometryTag(localName(child))) {
                        geometry = parseGeometry(child);
                        break;
                    }
                }
                JsonObject properties = parseProperties(placemark);
                if (geometry != null) GeoJsonCodec.validateGeometry(geometry);
                features.add(new GeoDataset.Feature(null, properties, geometry));
            }
            if (features.isEmpty()) {
                throw new IOException("В KML не найдено ни одного поддерживаемого объекта Placemark.");
            }
            List<GeoDataset.Layer> layers = new ArrayList<>();
            layers.add(new GeoDataset.Layer(layerName, features, GeoDataset.SpatialReference.WGS84));
            return new GeoDataset(layers);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            String message = exception.getMessage();
            throw new IOException("Не удалось прочитать KML: "
                    + (message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message), exception);
        }
    }

    private static byte[] readXmlBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long) output.size() + count > MAX_KML_BYTES) {
                throw new IOException("KML превышает допустимый размер 256 МБ.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] normalizeXmlDeclaration(byte[] xml) throws IOException {
        java.nio.charset.Charset charset = StandardCharsets.UTF_8;
        int offset = 0;

        if (xml.length >= 4 && (xml[0] & 0xFF) == 0x00 && (xml[1] & 0xFF) == 0x00
                && (xml[2] & 0xFF) == 0xFE && (xml[3] & 0xFF) == 0xFF) {
            charset = java.nio.charset.Charset.forName("UTF-32BE");
            offset = 4;
        } else if (xml.length >= 4 && (xml[0] & 0xFF) == 0xFF && (xml[1] & 0xFF) == 0xFE
                && (xml[2] & 0xFF) == 0x00 && (xml[3] & 0xFF) == 0x00) {
            charset = java.nio.charset.Charset.forName("UTF-32LE");
            offset = 4;
        } else if (xml.length >= 3 && (xml[0] & 0xFF) == 0xEF
                && (xml[1] & 0xFF) == 0xBB && (xml[2] & 0xFF) == 0xBF) {
            offset = 3;
        } else if (xml.length >= 2 && (xml[0] & 0xFF) == 0xFE && (xml[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (xml.length >= 2 && (xml[0] & 0xFF) == 0xFF && (xml[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (xml.length >= 4 && xml[0] == 0x00 && xml[1] == 0x00
                && xml[2] == 0x00 && xml[3] == '<') {
            charset = java.nio.charset.Charset.forName("UTF-32BE");
        } else if (xml.length >= 4 && xml[0] == '<' && xml[1] == 0x00
                && xml[2] == 0x00 && xml[3] == 0x00) {
            charset = java.nio.charset.Charset.forName("UTF-32LE");
        } else if (xml.length >= 4 && xml[0] == 0x00 && xml[1] == '<'
                && xml[2] == 0x00 && xml[3] == '?') {
            charset = StandardCharsets.UTF_16BE;
        } else if (xml.length >= 4 && xml[0] == '<' && xml[1] == 0x00
                && xml[2] == '?' && xml[3] == 0x00) {
            charset = StandardCharsets.UTF_16LE;
        } else {
            String prolog = new String(xml, 0, Math.min(xml.length, 256), StandardCharsets.US_ASCII);
            java.util.regex.Matcher encoding = java.util.regex.Pattern
                    .compile("(?i)encoding\\s*=\\s*['\"]([^'\"]+)['\"]")
                    .matcher(prolog);
            if (encoding.find()) {
                try {
                    charset = java.nio.charset.Charset.forName(encoding.group(1));
                } catch (Exception exception) {
                    throw new IOException("Неизвестная кодировка KML: " + encoding.group(1), exception);
                }
            }
        }

        String document = new String(xml, offset, xml.length - offset, charset);
        if (!document.isEmpty() && document.charAt(0) == '\uFEFF') {
            document = document.substring(1);
        }

        java.util.regex.Matcher declaration = java.util.regex.Pattern
                .compile("(?is)^\\s*<\\?xml\\s+.*?\\?>")
                .matcher(document);
        if (declaration.find()) {
            document = document.substring(declaration.end());
        }
        return document.getBytes(StandardCharsets.UTF_8);
    }

    public static void write(GeoDataset dataset, OutputStream output) throws IOException {
        GeoJsonCodec.requireWgs84(dataset, "KML/KMZ");
        try {
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(output, StandardCharsets.UTF_8.name());
            serializer.startDocument(StandardCharsets.UTF_8.name(), true);
            serializer.setPrefix("", KML_NS);
            serializer.startTag(KML_NS, "kml");
            serializer.startTag(KML_NS, "Document");
            writeText(serializer, "name", "GSon export");

            for (GeoDataset.Layer layer : dataset.layers) {
                serializer.startTag(KML_NS, "Folder");
                writeText(serializer, "name", layer.name);
                for (int i = 0; i < layer.features.size(); i++) {
                    GeoDataset.Feature feature = layer.features.get(i);
                    serializer.startTag(KML_NS, "Placemark");
                    String name = propertyText(feature.properties, "name");
                    writeText(serializer, "name", name == null ? "Объект " + (i + 1) : name);
                    String description = propertyText(feature.properties, "description");
                    if (description != null) writeText(serializer, "description", description);
                    writeExtendedData(serializer, feature.properties);
                    if (feature.geometry != null) writeGeometry(serializer, feature.geometry);
                    serializer.endTag(KML_NS, "Placemark");
                }
                serializer.endTag(KML_NS, "Folder");
            }

            serializer.endTag(KML_NS, "Document");
            serializer.endTag(KML_NS, "kml");
            serializer.endDocument();
            serializer.flush();
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Не удалось сформировать KML: " + exception.getMessage(), exception);
        }
    }

    private static JsonObject parseProperties(Element placemark) {
        JsonObject properties = new JsonObject();
        String name = directText(placemark, "name");
        String description = directText(placemark, "description");
        if (name != null) properties.addProperty("name", name);
        if (description != null) properties.addProperty("description", description);

        for (Element extended : directChildren(placemark)) {
            if (!"ExtendedData".equals(localName(extended))) continue;
            for (Element child : directChildren(extended)) {
                if ("Data".equals(localName(child))) {
                    String key = child.getAttribute("name");
                    String value = descendantText(child, "value");
                    if (!key.isEmpty() && value != null && !properties.has(key)) properties.addProperty(key, value);
                } else if ("SchemaData".equals(localName(child))) {
                    for (Element simple : directChildren(child)) {
                        if (!"SimpleData".equals(localName(simple))) continue;
                        String key = simple.getAttribute("name");
                        if (!key.isEmpty() && !properties.has(key)) properties.addProperty(key, simple.getTextContent());
                    }
                }
            }
        }
        return properties;
    }

    private static JsonObject parseGeometry(Element element) throws IOException {
        String type = localName(element);
        JsonObject geometry = new JsonObject();
        geometry.addProperty("type", type);
        if ("Point".equals(type)) {
            List<JsonArray> coordinates = parseCoordinates(descendantText(element, "coordinates"));
            geometry.add("coordinates", coordinates.isEmpty() ? new JsonArray() : coordinates.get(0));
        } else if ("LineString".equals(type)) {
            geometry.add("coordinates", coordinateArray(parseCoordinates(descendantText(element, "coordinates"))));
        } else if ("Polygon".equals(type)) {
            JsonArray rings = new JsonArray();
            for (Element child : directChildren(element)) {
                if ("outerBoundaryIs".equals(localName(child)) || "innerBoundaryIs".equals(localName(child))) {
                    String coordinates = descendantText(child, "coordinates");
                    List<JsonArray> parsed = parseCoordinates(coordinates);
                    rings.add(coordinateArray(parsed));
                }
            }
            if (rings.size() == 0) throw new IOException("Polygon в KML не содержит LinearRing.");
            geometry.add("coordinates", rings);
        } else if ("MultiGeometry".equals(type)) {
            List<JsonObject> children = new ArrayList<>();
            for (Element child : directChildren(element)) {
                if (isGeometryTag(localName(child))) children.add(parseGeometry(child));
            }
            if (children.isEmpty()) throw new IOException("MultiGeometry не содержит геометрий.");
            return combineMultiGeometry(children);
        } else {
            throw new IOException("Геометрия " + type + " в KML пока не поддерживается.");
        }
        return geometry;
    }

    private static JsonObject combineMultiGeometry(List<JsonObject> children) throws IOException {
        String firstType = children.get(0).get("type").getAsString();
        boolean same = true;
        JsonArray coordinates = new JsonArray();
        for (JsonObject child : children) {
            String childType = child.get("type").getAsString();
            same &= firstType.equals(childType);
            if (child.has("coordinates")) coordinates.add(child.get("coordinates").deepCopy());
        }
        if (same && "Point".equals(firstType)) return geometryWith("MultiPoint", coordinates);
        if (same && "LineString".equals(firstType)) return geometryWith("MultiLineString", coordinates);
        if (same && "Polygon".equals(firstType)) return geometryWith("MultiPolygon", coordinates);
        JsonObject collection = new JsonObject();
        collection.addProperty("type", "GeometryCollection");
        JsonArray geometries = new JsonArray();
        for (JsonObject child : children) geometries.add(child.deepCopy());
        collection.add("geometries", geometries);
        return collection;
    }

    private static JsonObject geometryWith(String type, JsonArray coordinates) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.add("coordinates", coordinates);
        return object;
    }

    private static List<JsonArray> parseCoordinates(String value) throws IOException {
        List<JsonArray> coordinates = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return coordinates;
        String[] tuples = value.trim().split("\\s+");
        for (String tuple : tuples) {
            String[] ordinates = tuple.split(",");
            if (ordinates.length < 2 || ordinates.length > 3) {
                throw new IOException("Некорректная координата KML: " + tuple);
            }
            JsonArray coordinate = new JsonArray();
            for (String ordinate : ordinates) {
                try {
                    double number = Double.parseDouble(ordinate.trim());
                    if ((Double.isNaN(number) || Double.isInfinite(number))) throw new NumberFormatException("not finite");
                    coordinate.add(new JsonPrimitive(number));
                } catch (NumberFormatException exception) {
                    throw new IOException("Некорректное числовое значение координаты KML: " + ordinate);
                }
            }
            coordinates.add(coordinate);
        }
        return coordinates;
    }

    private static JsonArray coordinateArray(List<JsonArray> values) {
        JsonArray array = new JsonArray();
        for (JsonArray value : values) array.add(value);
        return array;
    }

    private static void writeGeometry(XmlSerializer serializer, JsonObject geometry) throws Exception {
        String type = geometry.get("type").getAsString();
        if ("GeometryCollection".equals(type)) {
            serializer.startTag(KML_NS, "MultiGeometry");
            for (JsonElement child : geometry.getAsJsonArray("geometries")) writeGeometry(serializer, child.getAsJsonObject());
            serializer.endTag(KML_NS, "MultiGeometry");
            return;
        }
        if ("MultiPoint".equals(type) || "MultiLineString".equals(type) || "MultiPolygon".equals(type)) {
            serializer.startTag(KML_NS, "MultiGeometry");
            JsonArray coordinates = geometry.getAsJsonArray("coordinates");
            String childType = "MultiPoint".equals(type) ? "Point"
                    : "MultiLineString".equals(type) ? "LineString" : "Polygon";
            for (JsonElement childCoordinates : coordinates) {
                JsonObject child = new JsonObject();
                child.addProperty("type", childType);
                child.add("coordinates", childCoordinates.deepCopy());
                writeGeometry(serializer, child);
            }
            serializer.endTag(KML_NS, "MultiGeometry");
            return;
        }
        if ("Point".equals(type)) {
            serializer.startTag(KML_NS, "Point");
            writeCoordinates(serializer, "coordinates", singleton(geometry.getAsJsonArray("coordinates")));
            serializer.endTag(KML_NS, "Point");
        } else if ("LineString".equals(type)) {
            serializer.startTag(KML_NS, "LineString");
            writeCoordinates(serializer, "coordinates", geometry.getAsJsonArray("coordinates"));
            serializer.endTag(KML_NS, "LineString");
        } else if ("Polygon".equals(type)) {
            serializer.startTag(KML_NS, "Polygon");
            JsonArray rings = geometry.getAsJsonArray("coordinates");
            for (int i = 0; i < rings.size(); i++) {
                serializer.startTag(KML_NS, i == 0 ? "outerBoundaryIs" : "innerBoundaryIs");
                serializer.startTag(KML_NS, "LinearRing");
                writeCoordinates(serializer, "coordinates", rings.get(i).getAsJsonArray());
                serializer.endTag(KML_NS, "LinearRing");
                serializer.endTag(KML_NS, i == 0 ? "outerBoundaryIs" : "innerBoundaryIs");
            }
            serializer.endTag(KML_NS, "Polygon");
        } else {
            throw new IOException("Неподдерживаемая геометрия KML: " + type + ".");
        }
    }

    private static List<JsonElement> singleton(JsonArray coordinate) {
        List<JsonElement> list = new ArrayList<>();
        list.add(coordinate);
        return list;
    }

    private static void writeCoordinates(XmlSerializer serializer, String tag,
                                         Iterable<? extends JsonElement> coordinates) throws Exception {
        StringBuilder text = new StringBuilder();
        for (JsonElement item : coordinates) {
            JsonArray tuple = item.getAsJsonArray();
            if (tuple.size() < 2 || tuple.size() > 3) throw new IOException("KML поддерживает координаты из 2 или 3 чисел.");
            if (text.length() > 0) text.append(' ');
            for (int i = 0; i < tuple.size(); i++) {
                if (i > 0) text.append(',');
                text.append(tuple.get(i).getAsString());
            }
        }
        writeText(serializer, tag, text.toString());
    }

    private static void writeExtendedData(XmlSerializer serializer, JsonObject properties) throws Exception {
        boolean hasExtra = false;
        for (String key : properties.keySet()) {
            if (!"name".equals(key) && !"description".equals(key)) { hasExtra = true; break; }
        }
        if (!hasExtra) return;
        serializer.startTag(KML_NS, "ExtendedData");
        for (String key : properties.keySet()) {
            if ("name".equals(key) || "description".equals(key)) continue;
            serializer.startTag(KML_NS, "Data");
            serializer.attribute("", "name", key);
            serializer.startTag(KML_NS, "value");
            serializer.text(properties.get(key).isJsonNull() ? "" : properties.get(key).toString());
            serializer.endTag(KML_NS, "value");
            serializer.endTag(KML_NS, "Data");
        }
        serializer.endTag(KML_NS, "ExtendedData");
    }

    private static String propertyText(JsonObject properties, String key) {
        JsonElement value = properties.get(key);
        if (value == null || value.isJsonNull()) return null;
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static void writeText(XmlSerializer serializer, String tag, String value) throws Exception {
        serializer.startTag(KML_NS, tag);
        serializer.text(value == null ? "" : value);
        serializer.endTag(KML_NS, tag);
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); } catch (Exception ignored) {}
    }

    private static String firstText(Element root, String parentName, String childName) {
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (node instanceof Element && parentName.equals(localName((Element) node))) {
                String value = directText((Element) node, childName);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static String directText(Element parent, String childName) {
        for (Element child : directChildren(parent)) {
            if (childName.equals(localName(child))) return child.getTextContent();
        }
        return null;
    }

    private static String descendantText(Element parent, String childName) {
        NodeList children = parent.getElementsByTagName("*");
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && childName.equals(localName((Element) child))) return child.getTextContent();
        }
        return null;
    }

    private static List<Element> directChildren(Element element) {
        List<Element> elements = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) elements.add((Element) node);
        }
        return elements;
    }

    private static String localName(Element element) {
        String name = element.getLocalName();
        if (name == null) name = element.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static boolean isGeometryTag(String name) {
        return "Point".equals(name) || "LineString".equals(name) || "Polygon".equals(name)
                || "MultiGeometry".equals(name);
    }
}
