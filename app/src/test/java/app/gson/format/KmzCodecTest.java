package app.gson.format;

import static org.junit.Assert.assertEquals;

import app.gson.model.GeoDataset;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class KmzCodecTest {
    @Test
    public void readsKmzWithLegacyXmlVersionDeclaration() throws Exception {
        String kml = "<?xml version=\"0.0\" encoding=\"UTF-8\"?>"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><Point><coordinates>30.5,50.4</coordinates></Point></Placemark>"
                + "</Document></kml>";

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("doc.kml"));
            zip.write(kml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        GeoDataset dataset = KmzCodec.read(
                new ByteArrayInputStream(archive.toByteArray()), "sample");
        assertEquals(1, dataset.layers.get(0).features.size());
    }
}
