package app.gson.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GeoDataset {
    public final List<Layer> layers;

    public GeoDataset(List<Layer> layers) {
        this.layers = Collections.unmodifiableList(new ArrayList<>(layers));
        if (this.layers.isEmpty()) {
            throw new IllegalArgumentException("Документ не содержит векторных слоёв.");
        }
    }

    public int featureCount() {
        int count = 0;
        for (Layer layer : layers) count += layer.features.size();
        return count;
    }

    public boolean isWgs84() {
        for (Layer layer : layers) {
            boolean epsg4326 = layer.srs.srsId == 4326
                    || ("EPSG".equalsIgnoreCase(layer.srs.organization)
                    && layer.srs.organizationCoordSysId == 4326);
            boolean ogcCrs84 = "OGC".equalsIgnoreCase(layer.srs.organization)
                    && layer.srs.organizationCoordSysId == 84;
            if (!epsg4326 && !ogcCrs84) return false;
        }
        return true;
    }

    public static final class Layer {
        public final String name;
        public final List<Feature> features;
        public final SpatialReference srs;

        public Layer(String name, List<Feature> features, SpatialReference srs) {
            this.name = name == null || name.trim().isEmpty() ? "features" : name.trim();
            this.features = Collections.unmodifiableList(new ArrayList<>(features));
            this.srs = srs == null ? SpatialReference.WGS84 : srs;
        }
    }

    public static final class Feature {
        public final JsonElement id;
        public final JsonObject properties;
        public final JsonObject geometry;

        public Feature(JsonElement id, JsonObject properties, JsonObject geometry) {
            this.id = id == null ? null : id.deepCopy();
            this.properties = properties == null ? new JsonObject() : properties.deepCopy();
            this.geometry = geometry == null ? null : geometry.deepCopy();
        }
    }

    public static final class SpatialReference {
        public static final SpatialReference WGS84 = new SpatialReference(
                4326,
                "WGS 84 geodetic",
                "EPSG",
                4326,
                "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433],AUTHORITY[\"EPSG\",\"4326\"]]",
                "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid"
        );

        public final int srsId;
        public final String name;
        public final String organization;
        public final int organizationCoordSysId;
        public final String definition;
        public final String description;

        public SpatialReference(int srsId, String name, String organization,
                                int organizationCoordSysId, String definition, String description) {
            this.srsId = srsId;
            this.name = valueOr(name, "Undefined spatial reference system");
            this.organization = valueOr(organization, "NONE");
            this.organizationCoordSysId = organizationCoordSysId;
            this.definition = valueOr(definition, "undefined");
            this.description = valueOr(description, "");
        }

        private static String valueOr(String value, String fallback) {
            return value == null || value.trim().isEmpty() ? fallback : value;
        }
    }
}
