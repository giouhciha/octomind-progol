package mx.octomind.progol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TrackingActivity extends Activity {
    private final Map<DrawType, DatabaseHelper> databases = new EnumMap<>(DrawType.class);
    private final Map<DrawType, List<DatabaseHelper.PredictionSnapshot>> snapshotsByType = new EnumMap<>(DrawType.class);
    private final Map<DrawType, List<EditText>> localInputs = new EnumMap<>(DrawType.class);
    private final Map<DrawType, List<EditText>> visitorInputs = new EnumMap<>(DrawType.class);
    private final Map<DrawType, List<TextView>> resultLabels = new EnumMap<>(DrawType.class);
    private List<DrawType> types = new ArrayList<>();
    private Spinner contestSpinner;
    private TextView statusText;
    private LinearLayout resultInputs;
    private LinearLayout comparisonList;
    private Button deletePersonalButton;
    private Button deleteAutomaticButton;
    private Button unmarkAllButton;
    private int activeContest;
    private boolean rendering;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int publishedContestNumber = 0;
    private List<Integer> contestNumbers = List.of();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tracking);
        for (DrawType type : DrawType.values()) {
            databases.put(type, new DatabaseHelper(this, type));
            snapshotsByType.put(type, List.of());
            localInputs.put(type, new ArrayList<>());
            visitorInputs.put(type, new ArrayList<>());
            resultLabels.put(type, new ArrayList<>());
        }
        DrawType primary = "WEEKEND".equals(Session.selectedDraw(this)) ? DrawType.WEEKEND : DrawType.MS;
        types = primary == DrawType.MS
                ? new ArrayList<>(List.of(DrawType.MS))
                : new ArrayList<>(List.of(DrawType.WEEKEND, DrawType.REVANCHA));

        Spinner drawSelector = findViewById(R.id.trackingDrawSelector);
        drawSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Mitad de Semana", "Fin de Semana + Revancha"}));
        drawSelector.setSelection(primary == DrawType.MS ? 0 : 1);
        drawSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                List<DrawType> next = position == 0
                        ? List.of(DrawType.MS)
                        : List.of(DrawType.WEEKEND, DrawType.REVANCHA);
                if (types.equals(next)) return;
                types = new ArrayList<>(next);
                Session.setSelectedDraw(TrackingActivity.this, types.get(0).name());
                buildMatchRows();
                fetchPublishedContest();
            }
        });

        contestSpinner = findViewById(R.id.contestSpinner);
        statusText = findViewById(R.id.trackingStatus);
        resultInputs = findViewById(R.id.resultInputs);
        comparisonList = findViewById(R.id.comparisonList);
        deletePersonalButton = findViewById(R.id.deletePersonalButton);
        deleteAutomaticButton = findViewById(R.id.deleteAutomaticButton);
        deletePersonalButton.setOnClickListener(view -> confirmDeletePackage("PERSONAL"));
        deleteAutomaticButton.setOnClickListener(view -> confirmDeletePackage("AUTO"));
        unmarkAllButton = findViewById(R.id.unmarkAllButton);
        unmarkAllButton.setOnClickListener(view -> unmarkAll());
        updateUnmarkButton();
        buildMatchRows();
        contestSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < contestNumbers.size()) renderContest(contestNumbers.get(position));
            }
            public void onNothingSelected(AdapterView<?> parent) { clearScreen(); }
        });
    }

    @Override protected void onResume() { super.onResume(); fetchPublishedContest(); }

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
                accessToken = refreshed.idToken();
            } catch (Exception ignored) {
                // el token actual puede seguir siendo válido
            }
        }
        return accessToken;
    }

    private void fetchPublishedContest() {
        executor.execute(() -> {
            int published = 0;
            try {
                String token = refreshAccessToken();
                for (DrawType type : types) {
                    try {
                        ApiRepository.PublishedContest contest =
                                new ApiRepository().fetchLatest(type, token);
                        published = contest.contestNumber();
                        for (ApiRepository.Match match : contest.matches()) {
                            databases.get(type).saveMatchNames(
                                    contest.contestNumber(), match.slot() - 1,
                                    match.local(), match.visitor());
                        }
                    } catch (Exception ignored) {
                        // no hay concurso publicado o no se pudo descargar
                    }
                }
            } catch (Exception ignored) {
                // se continuará sin nombres
            }
            publishedContestNumber = published;
            runOnUiThread(this::loadSnapshots);
        });
    }

    private void loadSnapshots() {
        activeContest = 0;
        try {
            for (DrawType type : types) {
                snapshotsByType.put(type, databases.get(type).getPredictionSnapshots());
            }
            Map<Integer, DatabaseHelper.PredictionSnapshot> onePerContest = new LinkedHashMap<>();
            for (DrawType type : types) {
                for (DatabaseHelper.PredictionSnapshot snapshot : snapshotsByType.get(type)) {
                    onePerContest.putIfAbsent(snapshot.contestNumber(), snapshot);
                }
            }
            List<Integer> numbers = new ArrayList<>();
            if (publishedContestNumber > 0) numbers.add(publishedContestNumber);
            for (Integer number : onePerContest.keySet()) {
                if (!numbers.contains(number)) numbers.add(number);
            }
            contestNumbers = numbers;
            List<String> labels = new ArrayList<>();
            for (int number : contestNumbers) {
                labels.add("Concurso " + number + (number == publishedContestNumber ? " · publicado" : " · pronosticado"));
            }
            contestSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            if (contestNumbers.isEmpty()) clearScreen();
        } catch (JSONException exception) {
            statusText.setText(R.string.tracking_read_error);
            Toast.makeText(this, R.string.tracking_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void buildMatchRows() {
        resultInputs.removeAllViews();
        for (DrawType type : types) {
            localInputs.put(type, new ArrayList<>());
            visitorInputs.put(type, new ArrayList<>());
            resultLabels.put(type, new ArrayList<>());
        }
        activeContest = 0;
        int counter = 1;
        boolean multiple = types.size() > 1;
        for (DrawType type : types) {
            if (multiple) {
                TextView section = bodyText(type.label);
                section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                section.setTextColor(getColor(R.color.primary_dark));
                section.setPadding(0, dp(14), 0, dp(4));
                resultInputs.addView(section);
            }
            for (int slot = 0; slot < type.slots; slot++) {
                LinearLayout match = new LinearLayout(this);
                match.setOrientation(LinearLayout.HORIZONTAL);
                match.setGravity(Gravity.CENTER_VERTICAL);
                match.setPadding(dp(7), dp(4), dp(7), dp(4));
                match.setBackground(matchBackground());

                TextView number = bodyText(Integer.toString(counter++));
                number.setGravity(Gravity.CENTER); number.setTextColor(getColor(R.color.muted));
                match.addView(number, new LinearLayout.LayoutParams(dp(30), dp(44)));

                LinearLayout teams = new LinearLayout(this); teams.setOrientation(LinearLayout.VERTICAL);
                EditText local = teamInput("Equipo local");
                EditText visitor = teamInput("Equipo visitante");
                teams.addView(local, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));
                teams.addView(visitor, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));
                match.addView(teams, new LinearLayout.LayoutParams(0, dp(44), 1));

                TextView result = bodyText("—");
                result.setGravity(Gravity.CENTER); result.setTextSize(19); result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                result.setBackground(resultBackground(false));
                match.addView(result, new LinearLayout.LayoutParams(dp(44), dp(44)));
                localInputs.get(type).add(local); visitorInputs.get(type).add(visitor); resultLabels.get(type).add(result);

                DrawType capturedType = type; int capturedSlot = slot;
                match.setOnClickListener(view -> chooseResult(capturedType, capturedSlot));
                result.setOnClickListener(view -> chooseResult(capturedType, capturedSlot));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 0, 0, dp(3)); resultInputs.addView(match, rowParams);
            }
        }
    }

    private EditText teamInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint); input.setTextSize(16); input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL); input.setPadding(0, 0, 0, 0);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        input.setSaveEnabled(false);
        input.setFocusable(false);
        input.setKeyListener(null);
        return input;
    }

    private GradientDrawable matchBackground() {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(Color.WHITE); drawable.setCornerRadius(dp(9));
        drawable.setStroke(dp(1), getColor(R.color.divider)); return drawable;
    }

    private GradientDrawable resultBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setCornerRadius(dp(9));
        drawable.setColor(selected ? getColor(R.color.pick_green) : getColor(R.color.row_surface));
        drawable.setStroke(dp(1), selected ? getColor(R.color.pick_green) : getColor(R.color.divider)); return drawable;
    }

    private void chooseResult(DrawType type, int slot) {
        if (activeContest == 0) return;
        DatabaseHelper db = databases.get(type);
        DatabaseHelper.TrackedResults tracked = db.getTrackedResults(activeContest);
        if ("OFFICIAL".equals(tracked.source())) {
            Toast.makeText(this, "Resultado oficial actualizado", Toast.LENGTH_SHORT).show(); return;
        }
        String[] choices = {"Local  ·  L", "Empate  ·  E", "Visitante  ·  V", "Quitar resultado"};
        new AlertDialog.Builder(this)
                .setTitle("Resultado · " + type.label + " · Partido " + globalSlotNumber(type, slot))
                .setItems(choices, (dialog, position) -> {
                    String state = position == 0 ? "L" : position == 1 ? "E" : position == 2 ? "V" : null;
                    db.saveTrackedResult(activeContest, slot, state);
                    renderContest(selectedContest());
                }).show();
    }

    private void renderContest(int contestNumber) {
        activeContest = contestNumber;
        rendering = true;
        int totalCompleted = 0, totalSlots = 0;
        boolean allOfficial = true;
        for (DrawType type : types) {
            DatabaseHelper db = databases.get(type);
            DatabaseHelper.TrackedResults tracked = db.getTrackedResults(activeContest);
            List<DatabaseHelper.TeamNames> names = db.getMatchNames(activeContest);
            String[] results = tracked.results();
            for (int slot = 0; slot < type.slots; slot++) {
                String local = names.get(slot).local(), visitor = names.get(slot).visitor();
                localInputs.get(type).get(slot).setText(local);
                visitorInputs.get(type).get(slot).setText(visitor);
                String result = results[slot] == null ? "—" : results[slot];
                TextView resultLabel = resultLabels.get(type).get(slot);
                resultLabel.setText(result);
                boolean selected = results[slot] != null;
                resultLabel.setTextColor(selected ? Color.WHITE : getColor(R.color.muted));
                resultLabel.setBackground(resultBackground(selected));
            }
            totalCompleted += tracked.completedCount();
            totalSlots += type.slots;
            if (!"OFFICIAL".equals(tracked.source())) allOfficial = false;
        }
        rendering = false;
        if (types.size() > 1) {
            StringBuilder status = new StringBuilder();
            for (DrawType type : types) {
                int done = databases.get(type).getTrackedResults(activeContest).completedCount();
                if (status.length() > 0) status.append("   ·   ");
                status.append(type.label).append(": ").append(done).append("/").append(type.slots);
            }
            if (allOfficial) status.append("   ·   Validado");
            statusText.setText(status.toString());
        } else if (allOfficial) {
            statusText.setText(getString(R.string.tracking_official_status, totalSlots));
        } else if (totalCompleted == 0) {
            statusText.setText(R.string.tracking_no_results);
        } else {
            statusText.setText(getString(R.string.tracking_partial_status, totalCompleted, totalSlots));
        }
        renderComparison(contestNumber);
        updateDeleteButtons();
    }

    private void updateDeleteButtons() {
        boolean personal = false, automatic = false;
        for (DrawType type : types) {
            for (DatabaseHelper.PredictionSnapshot snapshot : snapshotsByType.get(type)) {
                if (snapshot.contestNumber() != activeContest) continue;
                if ("PERSONAL".equals(snapshot.packageType())) personal = true;
                if ("AUTO".equals(snapshot.packageType())) automatic = true;
            }
        }
        deletePersonalButton.setVisibility(personal ? View.VISIBLE : View.GONE);
        deleteAutomaticButton.setVisibility(automatic ? View.VISIBLE : View.GONE);
    }

    private void confirmDeletePackage(String packageType) {
        if (activeContest == 0) return;
        String label = "PERSONAL".equals(packageType) ? "personalizado" : "automático";
        new AlertDialog.Builder(this)
                .setTitle("Eliminar análisis " + label)
                .setMessage("Se eliminarán los pronósticos. Los resultados y equipos capturados se conservarán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    for (DrawType type : types) {
                        databases.get(type).deletePredictionPackage(activeContest, packageType);
                    }
                    loadSnapshots();
                    Toast.makeText(this, "Análisis " + label + " eliminado", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void renderComparison(int contestNumber) {
        comparisonList.removeAllViews();
        updateUnmarkButton();
        comparisonList.addView(bodyText("Toca para ver · mantén presionado para marcar. # ranking · P pronóstico · ✓ aciertos · ✗ fallidos"));
        boolean anyCompleted = false;
        for (DrawType type : types) {
            anyCompleted |= databases.get(type).getTrackedResults(contestNumber).completedCount() > 0;
        }
        if (!anyCompleted) {
            TextView pending = bodyText("Captura al menos un resultado para iniciar la comparación.");
            pending.setTextColor(getColor(R.color.muted));
            comparisonList.addView(pending);
        }
        boolean anyPackage = false;
        List<MergedPackage> packages = mergedPackages(contestNumber);
        if (packages.isEmpty()) {
            TextView none = bodyText("Sin pronósticos guardados para este concurso.");
            none.setTextColor(getColor(R.color.muted));
            comparisonList.addView(none);
            return;
        }
        for (MergedPackage pkg : packages) {
            anyPackage = true;
            String label = "PERSONAL".equals(pkg.packageType)
                    ? "Personalizado · " + pkg.name : "Automático";
            TextView heading = bodyText(label + " · " + pkg.sequences.size() + " pronósticos");
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setTextColor(getColor(R.color.primary_dark));
            heading.setPadding(0, dp(12), 0, dp(2));
            comparisonList.addView(heading);
            addMergedPackageComparison(pkg);
        }
        if (!anyPackage) {
            TextView none = bodyText("Sin pronósticos guardados para este concurso.");
            none.setTextColor(getColor(R.color.muted));
            comparisonList.addView(none);
        }
    }

    private void addMergedPackageComparison(MergedPackage pkg) {
        List<PredictionComparison.RankedPrediction> comparisons = PredictionComparison.rank(pkg.sequences, pkg.results);
        int position = 1;
        for (PredictionComparison.RankedPrediction comparison : comparisons) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(dp(4), dp(6), dp(4), dp(6));
            int rank = position;
            boolean marked = isMarked(activeContest, pkg.packageKey, comparison.originalRank());
            block.setBackgroundColor(marked ? getColor(R.color.marked_row) : Color.TRANSPARENT);
            block.setOnClickListener(view -> showMergedDetails(pkg, comparison, rank));
            block.setOnLongClickListener(view -> {
                toggleMark(activeContest, pkg.packageKey, comparison.originalRank());
                boolean nowMarked = isMarked(activeContest, pkg.packageKey, comparison.originalRank());
                block.setBackgroundColor(nowMarked ? getColor(R.color.marked_row) : Color.TRANSPARENT);
                return true;
            });

            int[][] stats = perTypeStats(comparison, pkg);

            StringBuilder summaryText = new StringBuilder();
            summaryText.append("#").append(position).append(" · P").append(comparison.originalRank());
            for (int t = 0; t < types.size(); t++) {
                summaryText.append("  ·  ")
                        .append(stats[t][0]).append(" ✓ ").append(stats[t][1])
                        .append(" ✗ (").append(types.get(t).slots).append(")");
            }
            LinearLayout summary = new LinearLayout(this);
            summary.setOrientation(LinearLayout.HORIZONTAL);
            summary.setGravity(Gravity.CENTER_VERTICAL);
            TextView summaryLabel = bodyText(summaryText.toString());
            summaryLabel.setTextSize(12);
            summaryLabel.setTextColor(getColor(R.color.muted));
            if (position == 1 && pkg.completed > 0) summaryLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            summary.addView(summaryLabel, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            block.addView(summary);

            int offset = 0;
            for (int t = 0; t < types.size(); t++) {
                DrawType type = types.get(t);
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = bodyText(shortLabel(type));
                label.setTextSize(11);
                label.setTextColor(getColor(R.color.muted));
                label.setGravity(Gravity.CENTER_VERTICAL);
                line.addView(label, new LinearLayout.LayoutParams(dp(56), dp(34)));
                for (int slot = 0; slot < type.slots; slot++) {
                    int combined = offset + slot;
                    char predicted = comparison.sequence().charAt(combined);
                    boolean hasResult = combined < pkg.results.length && pkg.results[combined] != null;
                    boolean correct = hasResult && pkg.results[combined].charAt(0) == predicted;
                    LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, dp(34), 1);
                    cellParams.setMargins(dp(2), 0, dp(2), 0);
                    line.addView(resultCell(String.valueOf(predicted), correct, hasResult), cellParams);
                }
                block.addView(line);
                offset += type.slots;
            }

            comparisonList.addView(block);
            position++;
        }
    }

    private TextView resultCell(String text, boolean correct, boolean hasResult) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setGravity(Gravity.CENTER);
        cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cell.setTextSize(13);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(6));
        if (!hasResult) {
            cell.setTextColor(getColor(R.color.muted));
            background.setColor(getColor(R.color.card));
            background.setStroke(dp(1), getColor(R.color.divider));
        } else if (correct) {
            cell.setTextColor(Color.WHITE);
            background.setColor(getColor(R.color.primary));
        } else {
            cell.setTextColor(Color.WHITE);
            background.setColor(getColor(R.color.result_error));
        }
        cell.setBackground(background);
        return cell;
    }

    private void showMergedDetails(MergedPackage pkg, PredictionComparison.RankedPrediction comparison, int rank) {
        boolean isPersonal = "PERSONAL".equals(pkg.packageType);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(6), 0, dp(6), 0);

        int[][] stats = perTypeStats(comparison, pkg);

        DrawType previousType = null;
        for (int slot = 0; slot < pkg.totalSlots; slot++) {
            DrawType currentType = typeAtGlobalSlot(slot);
            if (currentType != previousType) {
                int idx = types.indexOf(currentType);
                TextView section = bodyText(currentType.label + " · " + stats[idx][0] + "/" + currentType.slots + " aciertos");
                section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                section.setTextColor(getColor(R.color.primary_dark));
                section.setPadding(0, dp(10), 0, dp(2));
                content.addView(section);
                previousType = currentType;
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(4), dp(4), dp(4), dp(4));
            row.setBackgroundColor(getColor(R.color.row_surface));

            TextView number = bodyText(Integer.toString(slot + 1));
            number.setGravity(Gravity.CENTER);
            number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(number, new LinearLayout.LayoutParams(dp(30), dp(44)));

            String teamLabel = slot < pkg.teamNames.size() && !pkg.teamNames.get(slot).isEmpty()
                    ? pkg.teamNames.get(slot) : "Partido " + (slot + 1);
            TextView teamView = bodyText(teamLabel);
            teamView.setTextSize(12);
            teamView.setTextColor(getColor(R.color.muted));
            teamView.setGravity(Gravity.CENTER_VERTICAL);
            teamView.setPadding(dp(4), 0, dp(4), 0);
            row.addView(teamView, new LinearLayout.LayoutParams(0, dp(44), 1.5f));

            char predicted = comparison.sequence().charAt(slot);
            boolean hasResult = slot < pkg.results.length && pkg.results[slot] != null;
            boolean correct = hasResult && pkg.results[slot].charAt(0) == predicted;
            boolean fixed = isPersonal && pkg.fixedStates != null && slot < pkg.fixedStates.length && pkg.fixedStates[slot] != null;

            addDetailCell(row, "L", predicted == 'L', fixed, hasResult, correct);
            addDetailCell(row, "E", predicted == 'E', fixed, hasResult, correct);
            addDetailCell(row, "V", predicted == 'V', fixed, hasResult, correct);

            content.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Concurso " + activeContest + " · Posición " + rank)
                .setView(scroll).setPositiveButton("Cerrar", null).show();
    }

    private void addDetailCell(LinearLayout row, String state, boolean selected, boolean fixed, boolean hasResult, boolean correct) {
        TextView cell = new TextView(this);
        cell.setText(state);
        cell.setGravity(Gravity.CENTER);
        cell.setTextSize(15);
        cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int accent;
        int textColor;
        if (selected && fixed) {
            accent = R.color.fixed_yellow;
            textColor = R.color.ink;
        } else if (selected && hasResult && !correct) {
            accent = R.color.result_error;
            textColor = R.color.card;
        } else if (selected) {
            accent = R.color.pick_green;
            textColor = R.color.card;
        } else {
            accent = R.color.pick_green;
            textColor = R.color.pick_green;
        }
        cell.setTextColor(getColor(textColor));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(7));
        background.setColor(getColor(selected ? accent : R.color.card));
        background.setStroke(dp(2), getColor(accent));
        cell.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(cell, params);
    }

    private int selectedContest() { return contestNumbers.get(contestSpinner.getSelectedItemPosition()); }

    private void clearScreen() {
        activeContest = 0; statusText.setText(R.string.tracking_empty); comparisonList.removeAllViews();
        deletePersonalButton.setVisibility(View.GONE);
        deleteAutomaticButton.setVisibility(View.GONE);
        rendering = true;
        for (DrawType type : types) {
            for (int slot = 0; slot < type.slots; slot++) {
                localInputs.get(type).get(slot).setText("");
                visitorInputs.get(type).get(slot).setText("");
                TextView resultLabel = resultLabels.get(type).get(slot);
                resultLabel.setText("—"); resultLabel.setTextColor(getColor(R.color.muted)); resultLabel.setBackground(resultBackground(false));
            }
        }
        rendering = false;
    }

    private SharedPreferences markPrefs() {
        return getSharedPreferences("octomind_tracking", Context.MODE_PRIVATE);
    }

    private String markKey(int contestNumber, String packageType, int originalRank) {
        return contestNumber + ":" + packageType + ":" + originalRank;
    }

    private boolean isMarked(int contestNumber, String packageType, int originalRank) {
        return markPrefs().getStringSet("marked", new HashSet<>())
                .contains(markKey(contestNumber, packageType, originalRank));
    }

    private void toggleMark(int contestNumber, String packageType, int originalRank) {
        Set<String> marked = new HashSet<>(markPrefs().getStringSet("marked", new HashSet<>()));
        String key = markKey(contestNumber, packageType, originalRank);
        boolean nowMarked;
        if (marked.contains(key)) {
            marked.remove(key);
            nowMarked = false;
        } else {
            marked.add(key);
            nowMarked = true;
        }
        markPrefs().edit().putStringSet("marked", marked).apply();
        Toast.makeText(this, nowMarked ? "Pronóstico marcado" : "Pronóstico desmarcado", Toast.LENGTH_SHORT).show();
        updateUnmarkButton();
    }

    private void unmarkAll() {
        markPrefs().edit().remove("marked").apply();
        updateUnmarkButton();
        if (activeContest != 0) renderComparison(activeContest);
        Toast.makeText(this, "Todos los pronósticos desmarcados", Toast.LENGTH_SHORT).show();
    }

    private boolean hasAnyMarks() {
        return !markPrefs().getStringSet("marked", new HashSet<>()).isEmpty();
    }

    private void updateUnmarkButton() {
        unmarkAllButton.setEnabled(hasAnyMarks());
    }

    private String shortLabel(DrawType type) {
        return type == DrawType.WEEKEND ? "Fin Sem" : type.label;
    }

    private String packageKeyOf(DatabaseHelper.PredictionSnapshot snapshot) {
        return "PERSONAL".equals(snapshot.packageType())
                ? "PERSONAL:" + (snapshot.name() == null ? "Principal" : snapshot.name())
                : "AUTO";
    }

    private int globalSlotNumber(DrawType type, int slot) {
        int base = 1;
        for (DrawType t : types) {
            if (t == type) return base + slot;
            base += t.slots;
        }
        return slot + 1;
    }

    private DrawType typeAtGlobalSlot(int globalSlot) {
        int offset = 0;
        for (DrawType type : types) {
            if (globalSlot < offset + type.slots) return type;
            offset += type.slots;
        }
        return types.get(types.size() - 1);
    }

    private int[][] perTypeStats(PredictionComparison.RankedPrediction comparison, MergedPackage pkg) {
        int[][] stats = new int[types.size()][2]; // [aciertos, fallas]
        int offset = 0;
        for (int t = 0; t < types.size(); t++) {
            int hits = 0, completed = 0;
            for (int slot = 0; slot < types.get(t).slots; slot++) {
                int combined = offset + slot;
                String r = combined < pkg.results.length ? pkg.results[combined] : null;
                if (r != null) {
                    completed++;
                    if (r.charAt(0) == comparison.sequence().charAt(combined)) hits++;
                }
            }
            stats[t][0] = hits;
            stats[t][1] = completed - hits;
            offset += types.get(t).slots;
        }
        return stats;
    }

    private List<MergedPackage> mergedPackages(int contestNumber) {
        Map<String, Map<DrawType, DatabaseHelper.PredictionSnapshot>> grouped = new LinkedHashMap<>();
        for (DrawType type : types) {
            for (DatabaseHelper.PredictionSnapshot snapshot : snapshotsByType.get(type)) {
                if (snapshot.contestNumber() == contestNumber) {
                    grouped.computeIfAbsent(packageKeyOf(snapshot), k -> new EnumMap<>(DrawType.class))
                            .put(type, snapshot);
                }
            }
        }
        List<MergedPackage> merged = new ArrayList<>();
        for (Map.Entry<String, Map<DrawType, DatabaseHelper.PredictionSnapshot>> entry : grouped.entrySet()) {
            Map<DrawType, DatabaseHelper.PredictionSnapshot> byType = entry.getValue();
            if (byType.size() != types.size()) continue;
            merged.add(mergePackage(entry.getKey(), byType, contestNumber));
        }
        merged.sort(Comparator
                .comparing((MergedPackage p) -> "PERSONAL".equals(p.packageType) ? 0 : 1)
                .thenComparing(p -> p.name == null ? "" : p.name));
        return merged;
    }

    private MergedPackage mergePackage(String key, Map<DrawType, DatabaseHelper.PredictionSnapshot> byType, int contestNumber) {
        DatabaseHelper.PredictionSnapshot first = byType.values().iterator().next();
        String packageType = first.packageType();
        String name = "PERSONAL".equals(packageType)
                ? (first.name() == null ? "Principal" : first.name()) : null;

        int count = first.sequences().size();
        List<String> combined = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder();
            for (DrawType type : types) sb.append(byType.get(type).sequences().get(i));
            combined.add(sb.toString());
        }

        List<String> resultList = new ArrayList<>();
        List<String> fixedList = new ArrayList<>();
        List<String> teamList = new ArrayList<>();
        int completed = 0, totalSlots = 0;
        for (DrawType type : types) {
            String[] results = databases.get(type).getTrackedResults(contestNumber).results();
            List<DatabaseHelper.TeamNames> names = databases.get(type).getMatchNames(contestNumber);
            String[] fixed = "PERSONAL".equals(packageType) ? byType.get(type).fixedStates() : null;
            for (int slot = 0; slot < type.slots; slot++) {
                String r = slot < results.length ? results[slot] : null;
                resultList.add(r);
                if (r != null) completed++;
                fixedList.add(fixed != null && slot < fixed.length ? fixed[slot] : null);
                DatabaseHelper.TeamNames tn = slot < names.size() ? names.get(slot)
                        : new DatabaseHelper.TeamNames("", "");
                teamList.add(tn.local().isBlank() && tn.visitor().isBlank()
                        ? "" : tn.local() + " vs " + tn.visitor());
            }
            totalSlots += type.slots;
        }
        return new MergedPackage(key, packageType, name, combined,
                resultList.toArray(new String[0]), fixedList.toArray(new String[0]),
                teamList, completed, totalSlots);
    }

    private static final class MergedPackage {
        final String packageKey;
        final String packageType;
        final String name;
        final List<String> sequences;
        final String[] results;
        final String[] fixedStates;
        final List<String> teamNames;
        final int completed;
        final int totalSlots;
        MergedPackage(String packageKey, String packageType, String name, List<String> sequences,
                      String[] results, String[] fixedStates, List<String> teamNames,
                      int completed, int totalSlots) {
            this.packageKey = packageKey;
            this.packageType = packageType;
            this.name = name;
            this.sequences = sequences;
            this.results = results;
            this.fixedStates = fixedStates;
            this.teamNames = teamNames;
            this.completed = completed;
            this.totalSlots = totalSlots;
        }
    }

    private TextView bodyText(String text) { TextView view = new TextView(this); view.setText(text); view.setTextColor(getColor(R.color.ink)); view.setTextSize(14); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        executor.shutdown();
        for (DrawType type : DrawType.values()) databases.get(type).close();
        super.onDestroy();
    }
}
