package app.gson.conversion;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import app.gson.format.GeoFormat;
import app.gson.format.GeoJsonCodec;
import app.gson.format.GeoPackageCodec;
import app.gson.format.KmlCodec;
import app.gson.format.KmzCodec;
import app.gson.model.GeoDataset;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Orchestrates real parsing and serialization between supported geospatial formats. */
public final class ConversionEngine {
    private static final long MAX_INPUT_BYTES = 512L * 1024L * 1024L;

    private ConversionEngine() {}

    public static ConversionResult convert(Context context, Uri sourceUri, String sourceName,
                                           GeoFormat sourceFormat, GeoFormat targetFormat) throws IOException {
        if (sourceUri == null) throw new IOException("Сначала выберите исходный файл.");
        if (sourceFormat == null || targetFormat == null) throw new IOException("Выберите исходный и целевой форматы.");
        if (sourceFormat == targetFormat) throw new IOException("Выбран один и тот же формат. Укажите другой целевой формат.");

        File workDirectory = new File(context.getCacheDir(), "gson-work");
        if (!workDirectory.exists() && !workDirectory.mkdirs()) throw new IOException("Не удалось создать временную папку.");
        File inputFile = File.createTempFile("source-", "." + sourceFormat.extension, workDirectory);
        String baseName = baseName(sourceName);
        File outputFile = new File(workDirectory, targetFormat.fileName(baseName));
        if (outputFile.exists() && !outputFile.delete()) throw new IOException("Не удалось подготовить файл результата.");

        boolean success = false;
        try {
            copyInput(context.getContentResolver(), sourceUri, inputFile);
            GeoDataset dataset = read(sourceFormat, inputFile, baseName);
            write(targetFormat, dataset, outputFile);
            if (!outputFile.isFile() || outputFile.length() == 0) throw new IOException("Файл результата не был создан или оказался пустым.");
            success = true;
            return new ConversionResult(outputFile, dataset.featureCount(), dataset.layers.size());
        } finally {
            // The result remains in the app cache until it is saved or shared by the user.
            inputFile.delete();
            if (!success) outputFile.delete();
        }
    }

    private static void copyInput(ContentResolver resolver, Uri uri, File destination) throws IOException {
        try {
            InputStream raw = resolver.openInputStream(uri);
            if (raw == null) throw new IOException("Android не смог открыть выбранный файл.");
            try (InputStream input = new BufferedInputStream(raw);
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_INPUT_BYTES) throw new IOException("Файл больше допустимого лимита 512 МБ.");
                    output.write(buffer, 0, count);
                }
                if (total == 0) throw new IOException("Выбранный файл пустой.");
            }
        } catch (SecurityException exception) {
            throw new IOException("Нет доступа к выбранному файлу. Выберите его ещё раз.", exception);
        }
    }

    private static GeoDataset read(GeoFormat format, File file, String name) throws IOException {
        switch (format) {
            case GEOJSON:
                try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file))) {
                    return GeoJsonCodec.read(input, name);
                }
            case GPKG:
                return GeoPackageCodec.read(file);
            case KML:
                try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file))) {
                    return KmlCodec.read(input, name);
                }
            case KMZ:
                try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file))) {
                    return KmzCodec.read(input, name);
                }
            default:
                throw new IOException("Формат источника не поддерживается.");
        }
    }

    private static void write(GeoFormat format, GeoDataset dataset, File file) throws IOException {
        switch (format) {
            case GEOJSON:
                GeoJsonCodec.requireWgs84(dataset, "GeoJSON");
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                    GeoJsonCodec.write(dataset, output);
                }
                break;
            case GPKG:
                GeoPackageCodec.write(dataset, file);
                break;
            case KML:
                GeoJsonCodec.requireWgs84(dataset, "KML");
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                    KmlCodec.write(dataset, output);
                }
                break;
            case KMZ:
                GeoJsonCodec.requireWgs84(dataset, "KMZ");
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                    KmzCodec.write(dataset, output);
                }
                break;
            default:
                throw new IOException("Целевой формат не поддерживается.");
        }
    }

    private static String baseName(String name) {
        if (name == null || name.trim().isEmpty()) return "converted";
        String value = name.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        return value.isEmpty() ? "converted" : value;
    }
}
