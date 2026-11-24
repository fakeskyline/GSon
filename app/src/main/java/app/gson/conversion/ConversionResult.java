package app.gson.conversion;

import java.io.File;

public final class ConversionResult {
    public final File file;
    public final String fileName;
    public final long sizeBytes;
    public final int featureCount;
    public final int layerCount;

    public ConversionResult(File file, int featureCount, int layerCount) {
        this.file = file;
        this.fileName = file.getName();
        this.sizeBytes = file.length();
        this.featureCount = featureCount;
        this.layerCount = layerCount;
    }
}
