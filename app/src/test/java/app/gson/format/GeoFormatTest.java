package app.gson.format;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GeoFormatTest {
    @Test
    public void detectsActualSupportedExtensions() {
        assertEquals(GeoFormat.GEOJSON, GeoFormat.fromFileName("map.geojson"));
        assertEquals(GeoFormat.GPKG, GeoFormat.fromFileName("map.gpkg"));
        assertEquals(GeoFormat.KML, GeoFormat.fromFileName("map.kml"));
        assertEquals(GeoFormat.KMZ, GeoFormat.fromFileName("map.kmz"));
    }

    @Test
    public void createsSafeTargetFileName() {
        assertEquals("my_map.kml", GeoFormat.KML.fileName("my/map.geojson"));
    }
}
