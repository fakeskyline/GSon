package app.gson.format;

import static org.junit.Assert.assertEquals;

import app.gson.model.GeoDataset;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class KmlCodecTest {
    @Test
    public void readsKmlWithLegacyZeroZeroXmlDeclaration() throws Exception {
        String kml = "<?xml version=\"0.0\" encoding=\"UTF-8\"?>"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><Point><coordinates>30.5,50.4</coordinates></Point></Placemark>"
                + "</Document></kml>";
        GeoDataset dataset = KmlCodec.read(
                new ByteArrayInputStream(kml.getBytes(StandardCharsets.UTF_8)), "test.kml");
        assertEquals(1, dataset.layers.get(0).features.size());
    }
}
