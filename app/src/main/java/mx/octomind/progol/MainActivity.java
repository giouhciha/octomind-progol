package mx.octomind.progol;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int CREATE_BACKUP_REQUEST = 1001;
    private static final int OPEN_BACKUP_REQUEST = 1002;
    private static final int MAX_BACKUP_BYTES = 10 * 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final PredictionEngine predictionEngine = new PredictionEngine();
    private final HistoricalRepository historicalRepository = new HistoricalRepository();

    private DatabaseHelper database;
    private TextView statusText;
    private TableLayout probabilityTable;
    private ViewFlipper combinationsPager;
    private TextView combinationMeta;
    private TextView pageIndicator;
    private Button previousCombinationButton;
    private Button nextCombinationButton;
    private GestureDetector combinationGestures;
    private Button updateButton;
    private Button predictButton;
    private Button backupButton;
    private Button restoreButton;
    private Button trackingButton;
    private String pendingBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        database = new DatabaseHelper(this);
        statusText = findViewById(R.id.statusText);
        probabilityTable = findViewById(R.id.probabilityTable);
        combinationsPager = findViewById(R.id.combinationsPager);
        combinationMeta = findViewById(R.id.combinationMeta);
        pageIndicator = findViewById(R.id.pageIndicator);
        previousCombinationButton = findViewById(R.id.previousCombinationButton);
        nextCombinationButton = findViewById(R.id.nextCombinationButton);
        updateButton = findViewById(R.id.updateButton);
        predictButton = findViewById(R.id.predictButton);
        backupButton = findViewById(R.id.backupButton);
        restoreButton = findViewById(R.id.restoreButton);
        trackingButton = findViewById(R.id.trackingButton);

        updateButton.setOnClickListener(view -> updateHistory());
        predictButton.setOnClickListener(view -> calculatePrediction(true));
        backupButton.setOnClickListener(view -> createBackup());
        restoreButton.setOnClickListener(view -> chooseBackup());
        trackingButton.setOnClickListener(view -> startActivity(
                new Intent(this, TrackingActivity.class)
        ));
        previousCombinationButton.setOnClickListener(view -> showPreviousCombination());
        nextCombinationButton.setOnClickListener(view -> showNextCombination());
        configureCombinationGestures();

        showStoredState();
    }

    private void showStoredState() {
        setBusy(true, "Cargando histórico local…");
        executor.execute(() -> {
            List<Contest> history = database.getAllContests();
            runOnUiThread(() -> {
                setBusy(false, null);
                if (history.isEmpty()) {
                    statusText.setText(R.string.status_empty);
                    clearResults();
                } else {
                    statusText.setText(historySummary(history));
                    calculatePrediction(false);
                }
            });
        });
    }

    private void updateHistory() {
        setBusy(true, "Descargando y validando el histórico oficial…");
        executor.execute(() -> {
            try {
                List<Contest> history = historicalRepository.download();
                database.replaceContests(history);
                database.reconcileTrackedResults(history);
                PredictionEngine.Forecast forecast = predictionEngine.analyze(history);
                database.savePrediction(forecast);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    statusText.setText(getString(
                            R.string.history_updated,
                            historySummary(history)
                    ));
                    renderForecast(forecast);
                    toast("Histórico actualizado");
                });
            } catch (Exception exception) {
                showError("No se pudo actualizar el histórico", exception);
            }
        });
    }

    private void calculatePrediction(boolean saveRun) {
        setBusy(true, "Calculando las 19,683 combinaciones…");
        executor.execute(() -> {
            try {
                List<Contest> history = database.getAllContests();
                PredictionEngine.Forecast forecast = predictionEngine.analyze(history);
                if (saveRun) {
                    database.savePrediction(forecast);
                }
                runOnUiThread(() -> {
                    setBusy(false, null);
                    statusText.setText(historySummary(history));
                    renderForecast(forecast);
                });
            } catch (Exception exception) {
                showError("No se pudo calcular el pronóstico", exception);
            }
        });
    }

    private void createBackup() {
        setBusy(true, "Preparando respaldo…");
        executor.execute(() -> {
            try {
                String json = database.exportBackup();
                runOnUiThread(() -> {
                    setBusy(false, null);
                    pendingBackup = json;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(
                            Intent.EXTRA_TITLE,
                            "octomind-progol-" + LocalDate.now() + ".json"
                    );
                    startActivityForResult(intent, CREATE_BACKUP_REQUEST);
                });
            } catch (Exception exception) {
                showError("No se pudo preparar el respaldo", exception);
            }
        });
    }

    private void chooseBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, OPEN_BACKUP_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingBackup = null;
            return;
        }
        Uri uri = data.getData();
        if (requestCode == CREATE_BACKUP_REQUEST) {
            writeBackup(uri);
        } else if (requestCode == OPEN_BACKUP_REQUEST) {
            restoreBackup(uri);
        }
    }

    private void writeBackup(Uri uri) {
        String backup = pendingBackup;
        pendingBackup = null;
        if (backup == null) {
            toast("El respaldo ya no está disponible");
            return;
        }
        setBusy(true, "Guardando respaldo…");
        executor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) {
                    throw new IOException("No se pudo abrir el destino");
                }
                output.write(backup.getBytes(StandardCharsets.UTF_8));
                output.flush();
                runOnUiThread(() -> {
                    setBusy(false, null);
                    toast("Respaldo guardado");
                });
            } catch (Exception exception) {
                showError("No se pudo guardar el respaldo", exception);
            }
        });
    }

    private void restoreBackup(Uri uri) {
        setBusy(true, "Validando y restaurando respaldo…");
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("No se pudo abrir el respaldo");
                }
                String json = readLimited(input);
                int restored = database.restoreBackup(json);
                List<Contest> history = database.getAllContests();
                PredictionEngine.Forecast forecast = predictionEngine.analyze(history);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    statusText.setText(historySummary(history));
                    renderForecast(forecast);
                    toast("Respaldo restaurado: " + restored + " concursos");
                });
            } catch (Exception exception) {
                showError("El respaldo no es válido", exception);
            }
        });
    }

    private String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_BACKUP_BYTES) {
                throw new IOException("El respaldo excede 10 MB");
            }
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void renderForecast(PredictionEngine.Forecast forecast) {
        probabilityTable.removeAllViews();
        probabilityTable.addView(probabilityRow("#", "Local", "Empate", "Visita", "Selección", true));
        for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
            double local = forecast.probability(slot, 0);
            double draw = forecast.probability(slot, 1);
            double away = forecast.probability(slot, 2);
            String selection = local >= draw && local >= away
                    ? "L"
                    : draw >= away ? "E" : "V";
            probabilityTable.addView(probabilityRow(
                    Integer.toString(slot + 1),
                    percent(local),
                    percent(draw),
                    percent(away),
                    selection,
                    false
            ));
        }

        combinationsPager.removeAllViews();
        combinationMeta.setText(getString(
                R.string.combination_meta,
                forecast.targetContest(),
                PredictionEngine.MODEL_VERSION
        ));
        for (PredictionEngine.Recommendation recommendation : forecast.recommendations()) {
            combinationsPager.addView(combinationPage(recommendation));
        }
        combinationsPager.setDisplayedChild(0);
        updatePageIndicator();
    }

    private LinearLayout combinationPage(PredictionEngine.Recommendation recommendation) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(6), dp(4), dp(6), dp(4));
            row.setBackgroundColor(getColor(R.color.row_surface));

            TextView number = bodyText(Integer.toString(slot + 1));
            number.setGravity(Gravity.CENTER);
            number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(number, new LinearLayout.LayoutParams(dp(34), dp(48)));

            char selected = recommendation.sequence().charAt(slot);
            addStateCell(row, "L", selected == 'L');
            addStateCell(row, "E", selected == 'E');
            addStateCell(row, "V", selected == 'V');
            page.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ));

            if (slot < Contest.SLOT_COUNT - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.WHITE);
                page.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(2)
                ));
            }
        }

        TextView composition = bodyText(
                recommendation.local() + " Local · "
                        + recommendation.draw() + " Empate · "
                        + recommendation.away() + " Visita"
        );
        composition.setGravity(Gravity.CENTER);
        composition.setTextColor(getColor(R.color.muted));
        composition.setPadding(0, dp(10), 0, dp(2));
        page.addView(composition);
        return page;
    }

    private void addStateCell(LinearLayout row, String state, boolean selected) {
        TextView cell = bodyText(state);
        cell.setGravity(Gravity.CENTER);
        cell.setTextSize(18);
        cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cell.setTextColor(getColor(selected ? R.color.card : R.color.pick_green));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(8));
        background.setColor(getColor(selected ? R.color.pick_green : R.color.card));
        background.setStroke(dp(2), getColor(R.color.pick_green));
        cell.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(6), 0, dp(6), 0);
        row.addView(cell, params);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configureCombinationGestures() {
        combinationGestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent event) {
                        return true;
                    }

                    @Override
                    public boolean onFling(
                            MotionEvent first,
                            MotionEvent second,
                            float velocityX,
                            float velocityY
                    ) {
                        if (first == null || second == null) return false;
                        float distance = second.getX() - first.getX();
                        if (Math.abs(distance) < dp(60)
                                || Math.abs(velocityX) < Math.abs(velocityY)) {
                            return false;
                        }
                        if (distance < 0) showNextCombination();
                        else showPreviousCombination();
                        return true;
                    }
                });
        combinationsPager.setOnTouchListener((view, event) -> {
            boolean handled = combinationGestures.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            return handled;
        });
        combinationsPager.setOnClickListener(view -> { });
    }

    private void showNextCombination() {
        if (combinationsPager.getChildCount() < 2) return;
        combinationsPager.setInAnimation(this, R.anim.page_in_right);
        combinationsPager.setOutAnimation(this, R.anim.page_out_left);
        combinationsPager.showNext();
        updatePageIndicator();
    }

    private void showPreviousCombination() {
        if (combinationsPager.getChildCount() < 2) return;
        combinationsPager.setInAnimation(this, R.anim.page_in_left);
        combinationsPager.setOutAnimation(this, R.anim.page_out_right);
        combinationsPager.showPrevious();
        updatePageIndicator();
    }

    private void updatePageIndicator() {
        int total = combinationsPager.getChildCount();
        if (total == 0) {
            pageIndicator.setText("");
            return;
        }
        pageIndicator.setText(getString(
                R.string.prediction_page,
                combinationsPager.getDisplayedChild() + 1,
                total
        ));
    }

    private TableRow probabilityRow(
            String slot,
            String local,
            String draw,
            String away,
            String selection,
            boolean header
    ) {
        TableRow row = new TableRow(this);
        row.addView(tableCell(slot, header));
        row.addView(tableCell(local, header));
        row.addView(tableCell(draw, header));
        row.addView(tableCell(away, header));
        row.addView(tableCell(selection, header));
        return row;
    }

    private TextView tableCell(String text, boolean header) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setTextColor(getColor(header ? R.color.ink : R.color.muted));
        cell.setTextSize(header ? 12 : 13);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(8), dp(4), dp(8));
        if (header) {
            cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return cell;
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.ink));
        view.setTextSize(14);
        return view;
    }

    private String historySummary(List<Contest> history) {
        Contest latest = history.get(history.size() - 1);
        return "Histórico local: " + history.size() + " concursos"
                + "\nÚltimo concurso: " + latest.number()
                + " · " + latest.date();
    }

    private String percent(double value) {
        NumberFormat format = NumberFormat.getPercentInstance(new Locale("es", "MX"));
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setBusy(boolean busy, String message) {
        updateButton.setEnabled(!busy);
        predictButton.setEnabled(!busy);
        backupButton.setEnabled(!busy);
        restoreButton.setEnabled(!busy);
        trackingButton.setEnabled(!busy);
        if (busy && message != null) {
            statusText.setText(message);
        }
    }

    private void clearResults() {
        probabilityTable.removeAllViews();
        combinationsPager.removeAllViews();
        combinationMeta.setText("");
        pageIndicator.setText("");
    }

    private void showError(String message, Exception exception) {
        runOnUiThread(() -> {
            setBusy(false, null);
            statusText.setText(getString(
                    R.string.error_detail,
                    message,
                    safeMessage(exception)
            ));
            toast(message);
        });
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        database.close();
        super.onDestroy();
    }
}
