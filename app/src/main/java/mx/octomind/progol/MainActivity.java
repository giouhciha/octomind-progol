package mx.octomind.progol;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import org.json.JSONObject;
import java.util.EnumMap;
import java.util.Map;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

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
    private DrawType selectedType = DrawType.MS;
    private Spinner drawSelector;
    private final Map<DrawType, DatabaseHelper> databases = new EnumMap<>(DrawType.class);


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

        for (DrawType type : DrawType.values()) databases.put(type, new DatabaseHelper(this, type));
        database = databases.get(selectedType);
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
        predictButton.setOnClickListener(view -> requestFavorites());
        backupButton.setOnClickListener(view -> createBackup());
        restoreButton.setOnClickListener(view -> chooseBackup());
        trackingButton.setOnClickListener(view -> startActivity(
                new Intent(this, TrackingActivity.class).putExtra("drawType", selectedType.name())
        ));
        previousCombinationButton.setOnClickListener(view -> showPreviousCombination());
        nextCombinationButton.setOnClickListener(view -> showNextCombination());
        configureCombinationGestures();

        drawSelector = findViewById(R.id.drawSelector);
        drawSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Mitad de Semana", "Fin de Semana + Revancha"}));
        drawSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedType = position == 0 ? DrawType.MS : DrawType.WEEKEND;
                database = databases.get(selectedType);
                clearResults();
                showStoredState();
            }
        });
    }

    private DrawType[] activeTypes() {
        return selectedType == DrawType.MS ? new DrawType[]{DrawType.MS}
                : new DrawType[]{DrawType.WEEKEND, DrawType.REVANCHA};
    }

    private void showStoredState() { loadForecasts(false, new EnumMap<>(DrawType.class)); }
    private void updateHistory() { loadForecasts(true); }
    private void requestFavorites() {
        showFavoritesStep(activeTypes(), 0, new EnumMap<>(DrawType.class));
    }

    private void showFavoritesStep(DrawType[] types, int index, Map<DrawType, String[]> favorites) {
        if (index == types.length) { loadForecasts(false, favorites); return; }
        DrawType type = types[index];
        int limit = favoriteLimit(type);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(18), 0, dp(18), 0);
        List<RadioGroup> groups = new java.util.ArrayList<>();
        for (int slot = 0; slot < type.slots; slot++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView number = bodyText(Integer.toString(slot + 1));
            number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(number, new LinearLayout.LayoutParams(dp(28), dp(40)));
            RadioGroup choices = new RadioGroup(this);
            choices.setOrientation(RadioGroup.HORIZONTAL);
            addFavoriteOption(choices, "—"); addFavoriteOption(choices, "L");
            addFavoriteOption(choices, "E"); addFavoriteOption(choices, "V");
            choices.check(choices.getChildAt(0).getId());
            row.addView(choices, new LinearLayout.LayoutParams(0, dp(40), 1));
            rows.addView(row); groups.add(choices);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(rows);
        String title = type.label + " · favoritos opcionales";
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage("Fija hasta " + limit + " casilla" + (limit == 1 ? "." : "s. Estas no entrarán al análisis."))
                .setView(scroll)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(index + 1 == types.length ? "Calcular" : "Continuar", (dialog, which) -> {
                    String[] selections = new String[type.slots]; int count = 0;
                    for (int slot = 0; slot < groups.size(); slot++) {
                        RadioButton chosen = groups.get(slot).findViewById(groups.get(slot).getCheckedRadioButtonId());
                        String state = chosen == null ? "—" : chosen.getText().toString();
                        if (!"—".equals(state)) { selections[slot] = state; count++; }
                    }
                    if (count > limit) { toast("Máximo " + limit + " favorito" + (limit == 1 ? "" : "s") + " para " + type.label); return; }
                    favorites.put(type, selections);
                    showFavoritesStep(types, index + 1, favorites);
                }).show();
    }

    private int favoriteLimit(DrawType type) {
        return type == DrawType.MS ? 2 : type == DrawType.WEEKEND ? 4 : 1;
    }

    private void addFavoriteOption(RadioGroup group, String state) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId()); option.setText(state); option.setGravity(Gravity.CENTER);
        group.addView(option, new RadioGroup.LayoutParams(0, dp(40), 1));
    }

    private void loadForecasts(boolean download) { loadForecasts(download, new EnumMap<>(DrawType.class)); }

    private void loadForecasts(boolean download, Map<DrawType, String[]> favorites) {
        DrawType[] types = activeTypes();
        setBusy(true, download ? "Descargando y validando históricos…" : "Preparando pronósticos…");
        executor.execute(() -> {
            try {
                Map<DrawType, List<Contest>> histories = new EnumMap<>(DrawType.class);
                for (DrawType type : types) {
                    List<Contest> history = download ? new HistoricalRepository(type).download()
                            : databases.get(type).getAllContests();
                    histories.put(type, history);
                    if (download) {
                        databases.get(type).replaceContests(history);
                        databases.get(type).reconcileTrackedResults(history);
                    }
                }
                boolean missing = histories.values().stream().anyMatch(List::isEmpty);
                if (missing) {
                    postUi(() -> { setBusy(false, null); clearResults();
                        statusText.setText(R.string.load_all_draws); });
                    return;
                }
                if (types.length == 2 && histories.get(types[0]).stream().mapToInt(Contest::number).max().orElse(0)
                        != histories.get(types[1]).stream().mapToInt(Contest::number).max().orElse(0)) {
                    throw new IOException("Los históricos de Fin de Semana y Revancha aún no coinciden. Actualiza cuando ambos estén publicados.");
                }
                Map<DrawType, PredictionEngine.Forecast> automaticForecasts = new EnumMap<>(DrawType.class);
                Map<DrawType, PredictionEngine.Forecast> personalForecasts = new EnumMap<>(DrawType.class);
                boolean createPersonalPackage = !favorites.isEmpty();
                StringBuilder summary = new StringBuilder();
                for (DrawType type : types) {
                    List<Contest> history = histories.get(type);
                    PredictionEngine.Forecast automatic = new PredictionEngine(type).analyze(history);
                    automaticForecasts.put(type, automatic);
                    // Refresh AUTO when the user explicitly requests a side-by-side test.
                    databases.get(type).savePrediction(automatic, "AUTO", createPersonalPackage);
                    if (createPersonalPackage) {
                        PredictionEngine.Forecast personal = new PredictionEngine(type).analyze(
                                history, favorites.getOrDefault(type, new String[type.slots]));
                        personalForecasts.put(type, personal);
                        databases.get(type).savePrediction(personal, "PERSONAL", true);
                    }
                    if (summary.length() > 0) summary.append("\\n");
                    summary.append(type.label).append(": ").append(historySummary(history));
                }
                postUi(() -> {
                    setBusy(false, null);
                    statusText.setText(summary.toString());
                    renderForecasts(automaticForecasts, personalForecasts);
                    if (download) toast("Históricos y resultados actualizados");
                });
            } catch (Exception exception) {
                postUi(this::clearResults);
                showError("No se pudieron preparar los pronósticos", exception);
            }
        });
    }

    private void createBackup() {
        setBusy(true, "Preparando respaldo…");
        executor.execute(() -> {
            try {
                JSONObject bundle = new JSONObject();
                bundle.put("bundleVersion", 1);
                JSONObject draws = new JSONObject();
                for (DrawType type : DrawType.values()) draws.put(type.name(), new JSONObject(databases.get(type).exportBackup()));
                bundle.put("draws", draws);
                String json = bundle.toString(2);
                postUi(() -> {
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
                postUi(() -> {
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
                JSONObject root = new JSONObject(json);
                int restored = 0;
                if (root.has("bundleVersion")) {
                    if (root.getInt("bundleVersion") != 1) throw new IOException("Versión de respaldo no compatible");
                    JSONObject draws = root.getJSONObject("draws");
                    for (DrawType type : DrawType.values()) databases.get(type).restoreBackup(draws.getJSONObject(type.name()).toString(), true);
                    for (DrawType type : DrawType.values()) restored += databases.get(type).restoreBackup(draws.getJSONObject(type.name()).toString());
                } else {
                    restored = databases.get(DrawType.MS).restoreBackup(json);
                }
                final int count = restored;
                postUi(() -> { setBusy(false, null); toast("Respaldo restaurado: " + count + " concursos"); showStoredState(); });
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

    private void renderForecasts(
            Map<DrawType, PredictionEngine.Forecast> automaticForecasts,
            Map<DrawType, PredictionEngine.Forecast> personalForecasts
    ) {
        combinationsPager.removeAllViews();
        PredictionEngine.Forecast first = automaticForecasts.get(selectedType);
        combinationMeta.setText(getString(R.string.combination_meta, first.targetContest()));
        Map<String, Map<DrawType, List<String>>> savedByGroup = new java.util.HashMap<>();
        try {
            for (DrawType type : activeTypes()) {
                for (DatabaseHelper.PredictionSnapshot snapshot : databases.get(type).getPredictionSnapshots()) {
                    if (snapshot.contestNumber() == automaticForecasts.get(type).targetContest()) {
                        savedByGroup.computeIfAbsent(snapshot.packageType(), ignored -> new EnumMap<>(DrawType.class))
                                .put(type, snapshot.sequences());
                    }
                }
            }
        } catch (Exception error) {
            showError("No se pudo leer el paquete guardado", error);
            return;
        }
        if (savedByGroup.containsKey("PERSONAL") || !personalForecasts.isEmpty()) {
            addComparisonPages(personalForecasts, automaticForecasts, savedByGroup);
        } else {
            addPackagePages("Análisis automático", "AUTO", automaticForecasts, savedByGroup);
        }
        combinationsPager.setDisplayedChild(0);
        updatePageIndicator();
    }

    private void addComparisonPages(
            Map<DrawType, PredictionEngine.Forecast> personalForecasts,
            Map<DrawType, PredictionEngine.Forecast> automaticForecasts,
            Map<String, Map<DrawType, List<String>>> savedByGroup
    ) {
        for (int index = 0; index < 20; index++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            addPackageSection(page, "Personalizado · favoritos", "PERSONAL", index,
                    personalForecasts, savedByGroup);
            View separator = new View(this);
            separator.setBackgroundColor(getColor(R.color.divider));
            page.addView(separator, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
            addPackageSection(page, "Análisis automático", "AUTO", index,
                    automaticForecasts, savedByGroup);
            combinationsPager.addView(page);
        }
    }

    private void addPackagePages(
            String title,
            String packageType,
            Map<DrawType, PredictionEngine.Forecast> forecasts,
            Map<String, Map<DrawType, List<String>>> savedByGroup
    ) {
        Map<DrawType, List<String>> saved = savedByGroup.get(packageType);
        for (int index = 0; index < 20; index++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            addPackageSection(page, title, packageType, index, forecasts, savedByGroup);
            combinationsPager.addView(page);
        }
    }

    private void addPackageSection(
            LinearLayout page, String title, String packageType, int index,
            Map<DrawType, PredictionEngine.Forecast> forecasts,
            Map<String, Map<DrawType, List<String>>> savedByGroup
    ) {
        TextView packageHeading = bodyText(title);
        packageHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        packageHeading.setTextColor(getColor(R.color.primary_dark));
        packageHeading.setPadding(0, dp(8), 0, dp(2));
        page.addView(packageHeading);
        Map<DrawType, List<String>> saved = savedByGroup.get(packageType);
        for (DrawType type : activeTypes()) {
            String sequence = saved != null && saved.containsKey(type) ? saved.get(type).get(index)
                    : forecasts.get(type).recommendations().get(index).sequence();
            int local = 0, draw = 0, away = 0;
            for (char state : sequence.toCharArray()) {
                if (state == 'L') local++; else if (state == 'E') draw++; else away++;
            }
            PredictionEngine.Recommendation recommendation = new PredictionEngine.Recommendation(
                    index + 1, sequence, local, draw, away, 0, 0, 0);
            TextView heading = bodyText(type.label + " · " + type.slots + " partidos");
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setPadding(0, dp(12), 0, dp(8));
            page.addView(heading);
            page.addView(combinationPage(recommendation));
        }
    }

    private LinearLayout combinationPage(PredictionEngine.Recommendation recommendation) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        for (int slot = 0; slot < recommendation.sequence().length(); slot++) {
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

            if (slot < recommendation.sequence().length() - 1) {
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
        if (drawSelector != null) drawSelector.setEnabled(!busy);
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

    private void postUi(Runnable action) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }

    private void showError(String message, Exception exception) {
        postUi(() -> {
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
        executor.execute(() -> {
            for (DatabaseHelper helper : databases.values()) helper.close();
        });
        executor.shutdown();
        super.onDestroy();
    }
}
