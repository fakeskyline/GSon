package app.gson.format;

public enum GeoFormat {
    GEOJSON("GeoJSON", "geojson", "application/geo+json"),
    GPKG("GeoPackage", "gpkg", "application/geopackage+sqlite3"),
    KML("KML", "kml", "application/vnd.google-earth.kml+xml"),
    KMZ("KMZ", "kmz", "application/vnd.google-earth.kmz");

    public final String label;
    public final String extension;
    public final String mimeType;

    GeoFormat(String label, String extension, String mimeType) {
        this.label = label;
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String fileName(String baseName) {
        String safe = baseName == null ? "converted" : baseName.trim();
        int dot = safe.lastIndexOf('.');
        if (dot > 0) safe = safe.substring(0, dot);
        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "converted";
        return safe + "." + extension;
    }

    public static GeoFormat fromFileName(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".geojson") || lower.endsWith(".json")) return GEOJSON;
        if (lower.endsWith(".gpkg")) return GPKG;
        if (lower.endsWith(".kml")) return KML;
        if (lower.endsWith(".kmz")) return KMZ;
        return null;
    }
}
