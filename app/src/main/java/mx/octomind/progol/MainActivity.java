package mx.octomind.progol;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.content.Intent;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import org.json.JSONArray;
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
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int CREATE_BACKUP_REQUEST = 1001;
    private static final int OPEN_BACKUP_REQUEST = 1002;
    private static final int MAX_BACKUP_BYTES = 10 * 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler publishCheckHandler = new Handler(Looper.getMainLooper());
    private static final long PUBLISH_POLL_INTERVAL_MS = 10_000;
    private DrawType selectedType = DrawType.MS;
    private Spinner drawSelector;
    private Spinner personalPackageSizeSpinner;
    private Spinner automaticPackageSizeSpinner;
    private int personalPackageSize = 20;
    private int automaticPackageSize = 60;
    private final Map<DrawType, DatabaseHelper> databases = new EnumMap<>(DrawType.class);
    private final Set<DrawType> publishedTypes = new HashSet<>();
    private volatile Map<DrawType, PredictionEngine.Forecast> lastAutomatic = new EnumMap<>(DrawType.class);
    private volatile Map<DrawType, PredictionEngine.Forecast> lastPersonal = new EnumMap<>(DrawType.class);
    private volatile Map<DrawType, String[]> lastFavorites = new EnumMap<>(DrawType.class);
    private final Map<DrawType, Integer> publishedContestNumbers = new EnumMap<>(DrawType.class);


    private DatabaseHelper database;
    private TextView statusText;
    private TableLayout probabilityTable;
    private ViewFlipper combinationsPager;
    private TextView combinationMeta;
    private TextView pageIndicator;
    private Button previousCombinationButton;
    private Button nextCombinationButton;
    private Button refreshMatchesButton;
    private GestureDetector combinationGestures;
    private Button predictButton;
    private Button backupButton;
    private Button restoreButton;
    private Button trackingButton;
    private Button logoutButton;
    private String pendingBackup;
    private Spinner personalSelector;
    private Button newPersonalButton;
    private View personalControls;
    private boolean refreshingPersonalSelector;
    private int currentTargetContest;
    private String selectedPersonalName;
    private static final int MAX_PERSONAL = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isLoggedIn(this) && !Session.hasRefreshToken(this)) {
            goToLogin();
            return;
        }
        setupMain();
    }

    private void goToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private void logout() {
        Session.logout(this);
        goToLogin();
    }

    private void setupMain() {
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
        refreshMatchesButton = findViewById(R.id.refreshMatchesButton);
        predictButton = findViewById(R.id.predictButton);
        backupButton = findViewById(R.id.backupButton);
        restoreButton = findViewById(R.id.restoreButton);
        trackingButton = findViewById(R.id.trackingButton);
        logoutButton = findViewById(R.id.logoutButton);
        personalSelector = findViewById(R.id.personalSelector);
        newPersonalButton = findViewById(R.id.newPersonalButton);
        personalControls = findViewById(R.id.personalControls);

        predictButton.setOnClickListener(view -> requestFavorites());
        backupButton.setOnClickListener(view -> createBackup());
        restoreButton.setOnClickListener(view -> chooseBackup());
        trackingButton.setOnClickListener(view -> startActivity(
                new Intent(this, TrackingActivity.class).putExtra("drawType", selectedType.name())
        ));
        logoutButton.setOnClickListener(view -> logout());
        newPersonalButton.setOnClickListener(view -> requestNewPersonal());
        personalSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (refreshingPersonalSelector) return;
                Object item = parent.getItemAtPosition(position);
                if (item != null && !item.toString().equals(selectedPersonalName)) {
                    selectedPersonalName = item.toString();
                    if (!lastAutomatic.isEmpty()) renderForecasts(lastAutomatic, lastPersonal);
                }
            }
        });
        previousCombinationButton.setOnClickListener(view -> showPreviousCombination());
        nextCombinationButton.setOnClickListener(view -> showNextCombination());
        refreshMatchesButton.setOnClickListener(view -> refreshMatches());
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
                checkPublishedStatus();
            }
        });

        Integer[] packageSizes = {10, 20, 30, 40, 50, 60};
        personalPackageSizeSpinner = findViewById(R.id.personalPackageSizeSpinner);
        personalPackageSizeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                packageSizes));
        personalPackageSizeSpinner.setSelection(1); // 20 por defecto
        personalPackageSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                personalPackageSize = (Integer) parent.getItemAtPosition(position);
            }
        });

        automaticPackageSizeSpinner = findViewById(R.id.automaticPackageSizeSpinner);
        automaticPackageSizeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                packageSizes));
        automaticPackageSizeSpinner.setSelection(5); // 60 por defecto
        automaticPackageSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                automaticPackageSize = (Integer) parent.getItemAtPosition(position);
            }
        });

        predictButton.setEnabled(false);
        checkPublishedStatus();
    }

    private DrawType[] activeTypes() {
        return selectedType == DrawType.MS ? new DrawType[]{DrawType.MS}
                : new DrawType[]{DrawType.WEEKEND, DrawType.REVANCHA};
    }

    private void showStoredState() { loadForecasts(false); }
    private void requestFavorites() {
        if (!isSelectedViewPublished()) {
            toast("El sorteo seleccionado aún no está publicado.");
            return;
        }
        startAnalysis(activeTypes(), null);
    }

    private void startAnalysis(DrawType[] types, String name) {
        boolean onlyMs = types.length == 1 && types[0] == DrawType.MS;
        selectedType = onlyMs ? DrawType.MS : DrawType.WEEKEND;
        database = databases.get(selectedType);
        drawSelector.setSelection(onlyMs ? 0 : 1);
        setBusy(true, "Descargando partidos…");
        executor.execute(() -> {
            try {
                String accessToken = refreshAccessToken();
                for (DrawType type : types) {
                    try {
                        ApiRepository.PublishedContest published =
                                new ApiRepository().fetchLatest(type, accessToken);
                        publishedContestNumbers.put(type, published.contestNumber());
                        for (ApiRepository.Match match : published.matches()) {
                            databases.get(type).saveMatchNames(
                                    published.contestNumber(), match.slot() - 1,
                                    match.local(), match.visitor());
                        }
                    } catch (Exception ignored) {
                        // los nombres de equipos son opcionales
                    }
                }
            } catch (Exception ignored) {
                // se continuará sin nombres
            }
            postUi(() -> {
                setBusy(false, null);
                showFavoritesStep(types, 0, new EnumMap<>(DrawType.class), name);
            });
        });
    }

    private void showFavoritesStep(DrawType[] types, int index, Map<DrawType, String[]> favorites, String name) {
        if (index == types.length) { loadForecasts(true, favorites, name); return; }
        DrawType type = types[index];
        int limit = favoriteLimit(type);
        int contestNumber = publishedContestNumbers.getOrDefault(type, 0);
        List<DatabaseHelper.TeamNames> names = databases.get(type).getMatchNames(contestNumber);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(18), 0, dp(18), 0);
        List<RadioGroup> groups = new java.util.ArrayList<>();
        for (int slot = 0; slot < type.slots; slot++) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(6), 0, dp(6));

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView number = bodyText(Integer.toString(slot + 1));
            number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            number.setGravity(Gravity.CENTER);
            titleRow.addView(number, new LinearLayout.LayoutParams(dp(30), dp(36)));
            DatabaseHelper.TeamNames teams = slot < names.size() ? names.get(slot)
                    : new DatabaseHelper.TeamNames("", "");
            String teamLabel = teams.local().isBlank() && teams.visitor().isBlank()
                    ? "Partido " + (slot + 1) : teams.local() + " vs " + teams.visitor();
            TextView teamView = bodyText(teamLabel);
            teamView.setTextSize(14);
            teamView.setPadding(dp(6), 0, 0, 0);
            titleRow.addView(teamView, new LinearLayout.LayoutParams(0, dp(36), 1));
            block.addView(titleRow);

            RadioGroup choices = new RadioGroup(this);
            choices.setOrientation(RadioGroup.HORIZONTAL);
            addFavoriteOption(choices, "—", null);
            addFavoriteOption(choices, "Local", "L");
            addFavoriteOption(choices, "Empate", "E");
            addFavoriteOption(choices, "Visita", "V");
            choices.check(choices.getChildAt(0).getId());
            block.addView(choices, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

            rows.addView(block); groups.add(choices);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        FrameLayout wrapper = new FrameLayout(this);
        int scrollHeight = Math.min(dp(500),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.55f));
        wrapper.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, scrollHeight));
        String title = type.label + " · favoritos opcionales";
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage("Fija hasta " + limit + " casilla" + (limit == 1 ? "." : "s. Estas no entrarán al análisis."))
                .setView(wrapper)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(index + 1 == types.length ? "Calcular" : "Continuar", (dialog, which) -> {
                    String[] selections = new String[type.slots]; int count = 0;
                    for (int slot = 0; slot < groups.size(); slot++) {
                        RadioButton chosen = groups.get(slot).findViewById(groups.get(slot).getCheckedRadioButtonId());
                        Object tag = chosen == null ? null : chosen.getTag();
                        if (tag != null) { selections[slot] = tag.toString(); count++; }
                    }
                    if (count > limit) { toast("Máximo " + limit + " favorito" + (limit == 1 ? "" : "s") + " para " + type.label); return; }
                    favorites.put(type, selections);
                    showFavoritesStep(types, index + 1, favorites, name);
                }).show();
    }

    private int favoriteLimit(DrawType type) {
        return type == DrawType.MS ? 3 : type == DrawType.WEEKEND ? 4 : 1;
    }

    private void requestNewPersonal() {
        if (!isSelectedViewPublished()) {
            toast("El sorteo seleccionado aún no está publicado.");
            return;
        }
        List<String> names = currentPersonalNames(currentTargetContest);
        if (names.size() >= MAX_PERSONAL) {
            toast("Máximo " + MAX_PERSONAL + " pronósticos personalizados.");
            return;
        }
        promptPersonalName(names.size());
    }

    private void promptPersonalName(int count) {
        EditText input = new EditText(this);
        input.setHint("Nombre del pronóstico");
        input.setText("Personalizado " + (count + 1));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Nuevo pronóstico personalizado")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Crear", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("Escribe un nombre para el pronóstico.");
                        return;
                    }
                    startAnalysis(activeTypes(), name);
                }).show();
    }

    private List<String> currentPersonalNames(int contestNumber) {
        List<String> names = new ArrayList<>();
        try {
            for (DatabaseHelper.PredictionSnapshot snapshot : databases.get(selectedType).getPredictionSnapshots()) {
                if (snapshot.contestNumber() == contestNumber && "PERSONAL".equals(snapshot.packageType())) {
                    String name = snapshot.name() == null ? "Principal" : snapshot.name();
                    if (!names.contains(name)) names.add(name);
                }
            }
        } catch (Exception ignored) {
            // sin nombres
        }
        java.util.Collections.sort(names);
        return names;
    }

    private void refreshPersonalControls(int contestNumber) {
        List<String> names = currentPersonalNames(contestNumber);
        refreshingPersonalSelector = true;
        personalSelector.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        if (names.isEmpty()) {
            personalControls.setVisibility(View.GONE);
            selectedPersonalName = null;
        } else {
            if (selectedPersonalName == null || !names.contains(selectedPersonalName)) {
                selectedPersonalName = names.get(0);
            }
            personalSelector.setSelection(names.indexOf(selectedPersonalName));
            personalControls.setVisibility(View.VISIBLE);
            newPersonalButton.setEnabled(names.size() < MAX_PERSONAL);
            newPersonalButton.setText("Nuevo (" + names.size() + "/" + MAX_PERSONAL + ")");
        }
        refreshingPersonalSelector = false;
    }

    private static boolean hasFavorites(String[] fixedStates) {
        if (fixedStates == null) return false;
        for (String state : fixedStates) {
            if (state != null) return true;
        }
        return false;
    }

    private void savePersonalizedTemplate(
            DrawType type,
            PredictionEngine.Forecast forecast,
            String name,
            String[] fixedStates,
            String accessToken
    ) {
        try {
            JSONObject config = new JSONObject();
            JSONArray favorites = new JSONArray();
            for (String state : fixedStates) {
                favorites.put(state == null ? JSONObject.NULL : state);
            }
            config.put("favorites", favorites);
            List<String> combinations = new ArrayList<>();
            for (PredictionEngine.Recommendation recommendation : forecast.recommendations()) {
                combinations.add(recommendation.sequence());
            }
            new ApiRepository().saveTemplate(
                    type, forecast.targetContest(), name, config, combinations, accessToken);
        } catch (Exception ignored) {
            // el guardado de la plantilla no debe interrumpir el cálculo
        }
    }

    /** Descarga los partidos publicados y guarda los nombres de equipos por casilla. */
    private void downloadMatchNames(DrawType type, int contestNumber, String accessToken) {
        try {
            ApiRepository.PublishedContest published =
                    new ApiRepository().fetchPublished(type, contestNumber, accessToken);
            for (ApiRepository.Match match : published.matches()) {
                databases.get(type).saveMatchNames(
                        contestNumber, match.slot() - 1, match.local(), match.visitor());
            }
        } catch (Exception ignored) {
            // los nombres de equipos son opcionales
        }
    }

    /** Vuelve a descargar los partidos publicados y re-renderiza el paquete de combinaciones. */
    private void refreshMatches() {
        if (lastAutomatic.isEmpty()) {
            toast("Calcula primero un pronóstico.");
            return;
        }
        setBusy(true, "Refrescando partidos…");
        executor.execute(() -> {
            try {
                String accessToken = refreshAccessToken();
                for (DrawType type : activeTypes()) {
                    if (lastAutomatic.containsKey(type)) {
                        downloadMatchNames(type, lastAutomatic.get(type).targetContest(), accessToken);
                    }
                }
            } catch (Exception exception) {
                showError("No se pudieron refrescar los partidos", exception);
            }
            postUi(() -> {
                setBusy(false, null);
                if (!lastAutomatic.isEmpty()) {
                    renderForecasts(lastAutomatic, lastPersonal);
                    toast("Partidos actualizados");
                }
            });
        });
    }

    private String refreshAccessToken() {
        String accessToken = Session.accessToken(this);
        String refresh = Session.refreshToken(this);
        if (!refresh.isEmpty()) {
            try {
                AuthRepository.Tokens refreshed = new AuthRepository().refresh(refresh);
                if (refreshed.refreshToken().isEmpty()) {
                    refreshed = new AuthRepository.Tokens(
                            refreshed.accessToken(), refreshed.idToken(), refresh);
                }
                Session.save(this, refreshed);
                // El autorizador de Cognito valida el ID token (no el access token).
                accessToken = refreshed.idToken();
            } catch (Exception ignored) {
                // el token actual puede seguir siendo válido
            }
        }
        return accessToken;
    }

    private void checkPublishedStatus() {
        executor.execute(() -> {
            Set<DrawType> found = new HashSet<>();
            boolean anyExpired = false;
            try {
                String accessToken = refreshAccessToken();
                for (DrawType type : DrawType.values()) {
                    ApiRepository.PublishStatus status =
                            new ApiRepository().getPublishStatus(type, accessToken);
                    if (status == ApiRepository.PublishStatus.PUBLISHED) {
                        found.add(type);
                    } else if (status == ApiRepository.PublishStatus.EXPIRED) {
                        anyExpired = true;
                    }
                }
            } catch (Exception ignored) {
                // Se reintentará en el siguiente sondeo.
            }
            final Set<DrawType> published = found;
            final boolean expired = anyExpired;
            postUi(() -> {
                publishedTypes.clear();
                publishedTypes.addAll(published);
                boolean available = isSelectedViewPublished();
                predictButton.setEnabled(available);
                statusText.setText(available
                        ? "Datos disponibles. Toca 'Calcular pronóstico'."
                        : expired
                                ? "Concurso finalizado. Esperando el próximo concurso…"
                                : "Esperando datos del administrador…");
                // Volver a consultar periódicamente para detectar publicación y vencimiento.
                if (!isFinishing()) {
                    publishCheckHandler.removeCallbacksAndMessages(null);
                    publishCheckHandler.postDelayed(this::checkPublishedStatus, PUBLISH_POLL_INTERVAL_MS);
                }
            });
        });
    }

    /** Indica si la vista seleccionada (sorteo actual) está publicada. */
    private boolean isSelectedViewPublished() {
        for (DrawType type : activeTypes()) {
            if (!publishedTypes.contains(type)) return false;
        }
        return true;
    }

    private void addFavoriteOption(RadioGroup group, String label, String value) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setText(label);
        option.setTag(value);
        option.setGravity(Gravity.CENTER);
        option.setButtonDrawable(null);
        option.setTextSize(13);
        option.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        option.setPadding(dp(2), 0, dp(2), 0);
        option.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{getColor(R.color.card), getColor(R.color.pick_green)}
        ));
        StateListDrawable background = new StateListDrawable();
        GradientDrawable checked = new GradientDrawable();
        checked.setCornerRadius(dp(8));
        checked.setColor(getColor(R.color.pick_green));
        GradientDrawable unchecked = new GradientDrawable();
        unchecked.setCornerRadius(dp(8));
        unchecked.setColor(getColor(R.color.card));
        unchecked.setStroke(dp(1), getColor(R.color.pick_green));
        background.addState(new int[]{android.R.attr.state_checked}, checked);
        background.addState(new int[]{}, unchecked);
        option.setBackground(background);
        group.addView(option, new RadioGroup.LayoutParams(0, dp(46), 1));
    }

    private void loadForecasts(boolean download) { loadForecasts(download, new EnumMap<>(DrawType.class), null); }

    private void loadForecasts(boolean download, Map<DrawType, String[]> favorites, String name) {
        String personalName = name == null || name.isBlank() ? "Principal" : name.trim();
        DrawType[] types = activeTypes();
        setBusy(true, download ? "Descargando y validando históricos…" : "Preparando pronósticos…");
        executor.execute(() -> {
            try {
                String accessToken = refreshAccessToken();
                // Vuelve a consultar el concurso publicado para usar el número correcto.
                for (DrawType type : types) {
                    try {
                        ApiRepository.PublishedContest published =
                                new ApiRepository().fetchLatest(type, accessToken);
                        publishedContestNumbers.put(type, published.contestNumber());
                    } catch (Exception ignored) {
                        publishedContestNumbers.remove(type);
                    }
                }
                Map<DrawType, List<Contest>> histories = new EnumMap<>(DrawType.class);
                for (DrawType type : types) {
                    List<Contest> history = download ? new ApiRepository().fetchHistory(type, accessToken)
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
                boolean createPersonalPackage = false;
                for (DrawType type : types) {
                    if (hasFavorites(favorites.get(type))) {
                        createPersonalPackage = true;
                        break;
                    }
                }
                StringBuilder summary = new StringBuilder();
                for (DrawType type : types) {
                    List<Contest> history = histories.get(type);
                    int target = publishedContestNumbers.getOrDefault(type, 0);
                    PredictionEngine.Forecast automatic = new PredictionEngine(type).analyze(
                            history, new String[type.slots], target, automaticPackageSize);
                    automaticForecasts.put(type, automatic);
                    if (download) {
                        downloadMatchNames(type, automatic.targetContest(), accessToken);
                    }
                    // Refresh AUTO when the user explicitly requests a side-by-side test.
                    databases.get(type).savePrediction(automatic, "AUTO", createPersonalPackage);
                    if (createPersonalPackage) {
                        String[] fixedStates = favorites.get(type);
                        PredictionEngine.Forecast personal = new PredictionEngine(type).analyze(
                                history, fixedStates == null ? new String[type.slots] : fixedStates,
                                target, personalPackageSize);
                        personalForecasts.put(type, personal);
                        databases.get(type).savePrediction(personal, "PERSONAL", true, personalName);
                        if (hasFavorites(fixedStates)) {
                            savePersonalizedTemplate(type, personal, personalName, fixedStates, accessToken);
                        }
                    }
                    if (summary.length() > 0) summary.append("\\n");
                    summary.append(type.label).append(": ").append(historySummary(history));
                }
                postUi(() -> {
                    setBusy(false, null);
                    statusText.setText(summary.toString());
                    lastAutomatic = automaticForecasts;
                    lastPersonal = personalForecasts;
                    lastFavorites = favorites;
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
        int target = first.targetContest();
        currentTargetContest = target;
        combinationMeta.setText(getString(R.string.combination_meta, target));
        Map<String, Map<DrawType, List<String>>> savedByGroup = new java.util.HashMap<>();
        Map<String, Map<DrawType, String[]>> fixedByGroup = new java.util.HashMap<>();
        try {
            for (DrawType type : activeTypes()) {
                for (DatabaseHelper.PredictionSnapshot snapshot : databases.get(type).getPredictionSnapshots()) {
                    if (snapshot.contestNumber() == target) {
                        String key = "AUTO".equals(snapshot.packageType())
                                ? "AUTO" : (snapshot.name() == null ? "Principal" : snapshot.name());
                        savedByGroup.computeIfAbsent(key, ignored -> new EnumMap<>(DrawType.class))
                                .put(type, snapshot.sequences());
                        fixedByGroup.computeIfAbsent(key, ignored -> new EnumMap<>(DrawType.class))
                                .put(type, snapshot.fixedStates());
                    }
                }
            }
        } catch (Exception error) {
            showError("No se pudo leer el paquete guardado", error);
            return;
        }
        List<String> names = new ArrayList<>(savedByGroup.keySet());
        names.remove("AUTO");
        java.util.Collections.sort(names);
        if (names.isEmpty()) {
            addPackagePages("Análisis automático", "AUTO", automaticForecasts, savedByGroup, fixedByGroup);
        } else {
            if (selectedPersonalName == null || !names.contains(selectedPersonalName)) {
                selectedPersonalName = names.get(0);
            }
            addComparisonPages(selectedPersonalName, automaticForecasts, savedByGroup, fixedByGroup);
        }
        combinationsPager.setDisplayedChild(0);
        updatePageIndicator();
        refreshPersonalControls(target);
    }

    private void addComparisonPages(
            String personalName,
            Map<DrawType, PredictionEngine.Forecast> automaticForecasts,
            Map<String, Map<DrawType, List<String>>> savedByGroup,
            Map<String, Map<DrawType, String[]>> fixedByGroup
    ) {
        int pages = Math.max(personalPackageSize, automaticPackageSize);
        for (int index = 0; index < pages; index++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            boolean hasPersonal = index < personalPackageSize;
            boolean hasAutomatic = index < automaticPackageSize;
            if (hasPersonal) {
                addPackageSection(page, "Personalizado · " + personalName, personalName, index,
                        automaticForecasts, savedByGroup, fixedByGroup);
            }
            if (hasPersonal && hasAutomatic) {
                View separator = new View(this);
                separator.setBackgroundColor(getColor(R.color.divider));
                page.addView(separator, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
            }
            if (hasAutomatic) {
                addPackageSection(page, "Análisis automático", "AUTO", index,
                        automaticForecasts, savedByGroup, fixedByGroup);
            }
            combinationsPager.addView(page);
        }
    }

    private void addPackagePages(
            String title,
            String packageType,
            Map<DrawType, PredictionEngine.Forecast> forecasts,
            Map<String, Map<DrawType, List<String>>> savedByGroup,
            Map<String, Map<DrawType, String[]>> fixedByGroup
    ) {
        for (int index = 0; index < automaticPackageSize; index++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            addPackageSection(page, title, packageType, index, forecasts, savedByGroup, fixedByGroup);
            combinationsPager.addView(page);
        }
    }

    private void addPackageSection(
            LinearLayout page, String title, String key, int index,
            Map<DrawType, PredictionEngine.Forecast> forecasts,
            Map<String, Map<DrawType, List<String>>> savedByGroup,
            Map<String, Map<DrawType, String[]>> fixedByGroup
    ) {
        TextView packageHeading = bodyText(title);
        packageHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        packageHeading.setTextColor(getColor(R.color.primary_dark));
        packageHeading.setPadding(0, dp(8), 0, dp(2));
        page.addView(packageHeading);
        Map<DrawType, List<String>> saved = savedByGroup.get(key);
        Map<DrawType, String[]> fixed = fixedByGroup.get(key);
        for (DrawType type : activeTypes()) {
            List<String> sequences = saved != null ? saved.get(type) : null;
            String sequence = sequences != null && index < sequences.size()
                    ? sequences.get(index)
                    : (forecasts.get(type).recommendations().size() > index
                    ? forecasts.get(type).recommendations().get(index).sequence() : null);
            if (sequence == null) continue;
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
            String[] fixedStates = fixed != null ? fixed.get(type) : null;
            page.addView(combinationPage(recommendation, type, currentTargetContest, fixedStates));
        }
    }

    private LinearLayout combinationPage(
            PredictionEngine.Recommendation recommendation,
            DrawType type,
            int contestNumber,
            String[] fixedStates
    ) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        List<DatabaseHelper.TeamNames> names = databases.get(type).getMatchNames(contestNumber);

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

            DatabaseHelper.TeamNames teams = slot < names.size() ? names.get(slot)
                    : new DatabaseHelper.TeamNames("", "");
            String teamLabel = teams.local().isBlank() && teams.visitor().isBlank()
                    ? "" : teams.local() + " vs " + teams.visitor();
            TextView teamView = bodyText(teamLabel);
            teamView.setTextSize(12);
            teamView.setTextColor(getColor(R.color.muted));
            teamView.setGravity(Gravity.CENTER_VERTICAL);
            teamView.setPadding(dp(4), 0, dp(4), 0);
            row.addView(teamView, new LinearLayout.LayoutParams(0, dp(48), 1.5f));

            char selected = recommendation.sequence().charAt(slot);
            boolean fixed = fixedStates != null && slot < fixedStates.length && fixedStates[slot] != null;
            addStateCell(row, "L", selected == 'L', fixed);
            addStateCell(row, "E", selected == 'E', fixed);
            addStateCell(row, "V", selected == 'V', fixed);
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

    private void addStateCell(LinearLayout row, String state, boolean selected, boolean fixed) {
        TextView cell = bodyText(state);
        cell.setGravity(Gravity.CENTER);
        cell.setTextSize(18);
        cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int accent = fixed ? R.color.fixed_yellow : R.color.pick_green;
        cell.setTextColor(getColor(selected ? (fixed ? R.color.ink : R.color.card) : accent));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(8));
        background.setColor(getColor(selected ? accent : R.color.card));
        background.setStroke(dp(2), getColor(accent));
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
        publishCheckHandler.removeCallbacksAndMessages(null);
        executor.execute(() -> {
            for (DatabaseHelper helper : databases.values()) helper.close();
        });
        executor.shutdown();
        super.onDestroy();
    }
}
