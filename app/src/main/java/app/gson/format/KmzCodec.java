package app.gson.format;

import app.gson.model.GeoDataset;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class KmzCodec {
    private static final int MAX_KML_BYTES = 256 * 1024 * 1024;

    private KmzCodec() {}

    public static GeoDataset read(InputStream input, String fallbackName) throws IOException {
        byte[] kml = null;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".kml")) continue;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int read;
                while ((read = zip.read(chunk)) != -1) {
                    if ((long) buffer.size() + read > MAX_KML_BYTES) {
                        throw new IOException("Распакованный KML превышает лимит 256 МБ.");
                    }
                    buffer.write(chunk, 0, read);
                }
                kml = buffer.toByteArray();
                if ("doc.kml".equals(name) || name.endsWith("/doc.kml")) break;
            }
        }
        if (kml == null) throw new IOException("В KMZ-архиве не найден KML-файл.");
        return KmlCodec.read(new java.io.ByteArrayInputStream(kml), fallbackName);
    }

    public static void write(GeoDataset dataset, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("doc.kml"));
            KmlCodec.write(dataset, zip);
            zip.closeEntry();
            zip.finish();
        }
    }
}
