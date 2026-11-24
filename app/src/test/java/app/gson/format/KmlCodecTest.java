package app.gson.format;

import static org.junit.Assert.assertEquals;

import app.gson.model.GeoDataset;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class KmlCodecTest {
    @Test
    public void readsKmlWithLegacyZeroZeroXmlDeclaration() throws Exception {
        assertOneFeature("<?xml version=\"0.0\" encoding=\"UTF-8\"?>");
    }

    @Test
    public void readsUtf16KmlWithLegacyZeroZeroXmlDeclaration() throws Exception {
        String xml = "<?xml version=\"0.0\" encoding=\"UTF-16\"?>"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><Point><coordinates>30.5,50.4</coordinates></Point></Placemark>"
                + "</Document></kml>";
        byte[] encoded = xml.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[encoded.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(encoded, 0, withBom, 2, encoded.length);
        GeoDataset dataset = KmlCodec.read(new ByteArrayInputStream(withBom), "test.kml");
        assertEquals(1, dataset.layers.get(0).features.size());
    }

    private static void assertOneFeature(String declaration) throws Exception {
        String kml = declaration
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><Point><coordinates>30.5,50.4</coordinates></Point></Placemark>"
                + "</Document></kml>";
        GeoDataset dataset = KmlCodec.read(
                new ByteArrayInputStream(kml.getBytes(StandardCharsets.UTF_8)), "test.kml");
        assertEquals(1, dataset.layers.get(0).features.size());
    }
}
