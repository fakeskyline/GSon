package app.gson.format;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.gson.model.GeoDataset;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class GeoJsonCodecTest {
    @Test
    public void featureCollectionRoundTripsAttributesAndIds() throws Exception {
        String json = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","id":"road-7","properties":{"name":"Road","lanes":2},
                   "geometry":{"type":"LineString","coordinates":[[30,50],[31,51]]}}
                ]}
                """;
        GeoDataset dataset = GeoJsonCodec.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "roads");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GeoJsonCodec.write(dataset, output);
        String converted = output.toString("UTF-8");
        assertTrue(converted.contains("road-7"));
        assertTrue(converted.contains("lanes"));
        assertTrue(converted.contains("LineString"));
    }

    @Test
    public void rejectsNonWgs84GeoJsonCrs() throws Exception {
        String json = """
                {"type":"FeatureCollection","crs":{"properties":{"name":"EPSG:3857"}},"features":[]}
                """;
        try {
            GeoJsonCodec.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("EPSG:4326"));
            return;
        }
        throw new AssertionError("A non-WGS84 CRS must fail.");
    }

    @Test
    public void geoDatasetRecognizesOnlySupportedWgs84Ids() {
        assertTrue(new GeoDataset(java.util.Collections.singletonList(
                new GeoDataset.Layer("ok", java.util.Collections.emptyList(), GeoDataset.SpatialReference.WGS84))).isWgs84());
        GeoDataset.SpatialReference mercator = new GeoDataset.SpatialReference(3857, "Web Mercator", "EPSG", 3857, "", "");
        assertFalse(new GeoDataset(java.util.Collections.singletonList(
                new GeoDataset.Layer("mercator", java.util.Collections.emptyList(), mercator))).isWgs84());
    }
}
