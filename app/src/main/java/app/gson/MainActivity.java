package app.gson;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.graphics.drawable.ColorDrawable;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import app.gson.conversion.ConversionEngine;
import app.gson.conversion.ConversionResult;
import app.gson.format.GeoFormat;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor();
    private MaterialAutoCompleteTextView sourceFormatField;
    private MaterialAutoCompleteTextView targetFormatField;
    private TextView selectedFileText;
    private TextView statusText;
    private TextView resultText;
    private ProgressBar progressBar;
    private MaterialButton convertButton;
    private MaterialButton saveButton;
    private MaterialButton openButton;

    private Uri selectedUri;
    private String selectedName;
    private ConversionResult lastResult;

    private final ActivityResultLauncher<String[]> openInputLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                selectedUri = uri;
                selectedName = queryDisplayName(uri);
                selectedFileText.setText(selectedName);
                GeoFormat detected = GeoFormat.fromFileName(selectedName);
                if (detected != null) sourceFormatField.setText(detected.label, false);
                lastResult = null;
                saveButton.setEnabled(false);
                openButton.setEnabled(false);
                resultText.setText("Файл выбран. Укажите форматы и запустите конвертацию.");
                setStatus("Файл готов к обработке.", false);
            });

    private final ActivityResultLauncher<String> saveOutputLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                if (uri == null || lastResult == null) return;
                saveResultTo(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("GSon");
        setSupportActionBar(toolbar);

        sourceFormatField = findViewById(R.id.sourceFormat);
        targetFormatField = findViewById(R.id.targetFormat);
        selectedFileText = findViewById(R.id.selectedFile);
        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);
        progressBar = findViewById(R.id.progressBar);
        convertButton = findViewById(R.id.convertButton);
        saveButton = findViewById(R.id.saveButton);
        openButton = findViewById(R.id.openButton);

        String[] formatLabels = new String[GeoFormat.values().length];
        for (int i = 0; i < GeoFormat.values().length; i++) formatLabels[i] = GeoFormat.values()[i].label;
        ArrayAdapter<String> formatsAdapter = new ArrayAdapter<>(this, R.layout.dropdown_item, formatLabels);
        sourceFormatField.setAdapter(formatsAdapter);
        targetFormatField.setAdapter(new ArrayAdapter<>(this, R.layout.dropdown_item, formatLabels));
        int dropdownColor = getColor(R.color.dropdown_surface);
        sourceFormatField.setDropDownBackgroundDrawable(new ColorDrawable(dropdownColor));
        targetFormatField.setDropDownBackgroundDrawable(new ColorDrawable(dropdownColor));
        sourceFormatField.setDropDownVerticalOffset(dp(6));
        targetFormatField.setDropDownVerticalOffset(dp(6));
        sourceFormatField.setOnClickListener(view -> sourceFormatField.showDropDown());
        targetFormatField.setOnClickListener(view -> targetFormatField.showDropDown());
        sourceFormatField.setText(GeoFormat.GEOJSON.label, false);
        targetFormatField.setText(GeoFormat.GPKG.label, false);

        findViewById(R.id.pickFileButton).setOnClickListener(view ->
                openInputLauncher.launch(new String[]{"*/*"}));
        convertButton.setOnClickListener(view -> startConversion());
        saveButton.setOnClickListener(view -> saveOutputLauncher.launch(lastResult == null ? "converted.gpkg" : lastResult.fileName));
        openButton.setOnClickListener(view -> openResult());
    }

    private void startConversion() {
        if (selectedUri == null) {
            setStatus("Сначала выберите исходный файл.", true);
            return;
        }
        GeoFormat source = findFormat(sourceFormatField.getText().toString());
        GeoFormat target = findFormat(targetFormatField.getText().toString());
        if (source == null || target == null) {
            setStatus("Выберите оба формата из списка.", true);
            return;
        }
        if (source == target) {
            setStatus("Исходный и целевой форматы должны различаться.", true);
            return;
        }

        setBusy(true);
        lastResult = null;
        saveButton.setEnabled(false);
        openButton.setEnabled(false);
        resultText.setText("Читаю геоданные и подготавливаю файл…");
        setStatus("Конвертация выполняется на устройстве.", false);

        final Uri uri = selectedUri;
        final String name = selectedName;
        conversionExecutor.execute(() -> {
            try {
                ConversionResult result = ConversionEngine.convert(this, uri, name, source, target);
                runOnUiThread(() -> {
                    lastResult = result;
                    String details = result.featureCount + " объектов · " + result.layerCount + " слоёв · " + formatBytes(result.sizeBytes);
                    resultText.setText(result.fileName + "\n" + details);
                    setStatus("Готово. Геоданные преобразованы.", false);
                    saveButton.setEnabled(true);
                    openButton.setEnabled(true);
                    setBusy(false);
                });
            } catch (Exception exception) {
                String message = exception.getMessage();
                if (message == null || message.trim().isEmpty()) message = "Не удалось выполнить конвертацию.";
                final String error = message;
                runOnUiThread(() -> {
                    resultText.setText("Результат не создан.");
                    setStatus(error, true);
                    setBusy(false);
                });
            }
        });
    }

    private void saveResultTo(Uri destination) {
        ConversionResult result = lastResult;
        if (result == null) return;
        try (FileInputStream input = new FileInputStream(result.file);
             OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("Android не смог создать файл назначения.");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            setStatus("Файл сохранён: " + result.fileName, false);
            Toast.makeText(this, "Файл сохранён", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            setStatus("Не удалось сохранить файл: " + safeMessage(exception), true);
        }
    }

    private void openResult() {
        if (lastResult == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", lastResult.file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeFor(lastResult.fileName));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Открыть результат"));
        } catch (ActivityNotFoundException exception) {
            setStatus("На устройстве нет приложения, которое умеет открыть этот формат. Сохраните файл и откройте его через ГИС-приложение.", true);
        } catch (Exception exception) {
            setStatus("Не удалось открыть результат: " + safeMessage(exception), true);
        }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String name = uri.getLastPathSegment();
        return name == null ? "Выбранный файл" : name;
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        convertButton.setEnabled(!busy);
        findViewById(R.id.pickFileButton).setEnabled(!busy);
        sourceFormatField.setEnabled(!busy);
        targetFormatField.setEnabled(!busy);
    }

    private void setStatus(String message, boolean error) {
        statusText.setText(message);
        statusText.setTextColor(getColor(error ? R.color.error : R.color.on_surface_variant));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static GeoFormat findFormat(String label) {
        for (GeoFormat format : GeoFormat.values()) if (format.label.equals(label)) return format;
        return null;
    }

    private static String mimeFor(String fileName) {
        GeoFormat format = GeoFormat.fromFileName(fileName);
        return format == null ? "application/octet-stream" : format.mimeType;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return new DecimalFormat("0.0").format(bytes / 1024.0) + " КБ";
        return new DecimalFormat("0.00").format(bytes / (1024.0 * 1024.0)) + " МБ";
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        conversionExecutor.shutdown();
    }
}
