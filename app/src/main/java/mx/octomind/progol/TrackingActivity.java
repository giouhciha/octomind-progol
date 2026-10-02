package mx.octomind.progol;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrackingActivity extends Activity {
    private DatabaseHelper database;
    private DrawType type;
    private Spinner contestSpinner;
    private TextView statusText;
    private LinearLayout resultInputs;
    private LinearLayout comparisonList;
    private Button deletePersonalButton;
    private Button deleteAutomaticButton;
    private List<DatabaseHelper.PredictionSnapshot> snapshots = List.of();
    private List<DatabaseHelper.PredictionSnapshot> allSnapshots = List.of();
    private final List<EditText> localInputs = new ArrayList<>();
    private final List<EditText> visitorInputs = new ArrayList<>();
    private final List<TextView> resultLabels = new ArrayList<>();
    private int activeContest;
    private boolean rendering;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tracking);
        String draw = getIntent().getStringExtra("drawType");
        type = DrawType.valueOf(draw == null ? "MS" : draw);
        database = new DatabaseHelper(this, type);
        Spinner drawSelector = findViewById(R.id.trackingDrawSelector);
        drawSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Mitad de Semana", "Fin de Semana", "Revancha"}));
        drawSelector.setSelection(type.ordinal());
        drawSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (type == DrawType.values()[position]) return;
                database.close(); type = DrawType.values()[position];
                database = new DatabaseHelper(TrackingActivity.this, type);
                buildMatchRows(); loadSnapshots();
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
        buildMatchRows();
        contestSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < snapshots.size()) renderContest(snapshots.get(position));
            }
            public void onNothingSelected(AdapterView<?> parent) { clearScreen(); }
        });
    }

    @Override protected void onResume() { super.onResume(); loadSnapshots(); }

    private void loadSnapshots() {
        activeContest = 0;
        try {
            allSnapshots = database.getPredictionSnapshots();
            Map<Integer, DatabaseHelper.PredictionSnapshot> onePerContest = new LinkedHashMap<>();
            for (DatabaseHelper.PredictionSnapshot snapshot : allSnapshots) {
                onePerContest.putIfAbsent(snapshot.contestNumber(), snapshot);
            }
            snapshots = new ArrayList<>(onePerContest.values());
            List<String> labels = new ArrayList<>();
            for (DatabaseHelper.PredictionSnapshot snapshot : snapshots) {
                labels.add("Concurso " + snapshot.contestNumber() + " · ambos análisis");
            }
            contestSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            if (snapshots.isEmpty()) clearScreen();
        } catch (JSONException exception) {
            statusText.setText(R.string.tracking_read_error);
            Toast.makeText(this, R.string.tracking_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void buildMatchRows() {
        resultInputs.removeAllViews();
        localInputs.clear(); visitorInputs.clear(); resultLabels.clear(); activeContest = 0;
        for (int slot = 0; slot < type.slots; slot++) {
            int capturedSlot = slot;
            LinearLayout match = new LinearLayout(this);
            match.setOrientation(LinearLayout.HORIZONTAL);
            match.setGravity(Gravity.CENTER_VERTICAL);
            match.setPadding(dp(7), dp(4), dp(7), dp(4));
            match.setBackground(matchBackground());

            TextView number = bodyText(Integer.toString(slot + 1));
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
            localInputs.add(local); visitorInputs.add(visitor); resultLabels.add(result);
            local.addTextChangedListener(nameWatcher(capturedSlot));
            visitor.addTextChangedListener(nameWatcher(capturedSlot));
            match.setOnClickListener(view -> chooseResult(capturedSlot));
            result.setOnClickListener(view -> chooseResult(capturedSlot));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(3)); resultInputs.addView(match, rowParams);
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
        return input;
    }

    private android.text.TextWatcher nameWatcher(int slot) {
        return new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) { }
            public void afterTextChanged(android.text.Editable text) {
                if (!rendering && activeContest > 0) database.saveMatchNames(activeContest, slot,
                        localInputs.get(slot).getText().toString(), visitorInputs.get(slot).getText().toString());
            }
        };
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

    private void chooseResult(int slot) {
        if (activeContest == 0) return;
        DatabaseHelper.TrackedResults tracked = database.getTrackedResults(activeContest);
        if ("OFFICIAL".equals(tracked.source())) {
            Toast.makeText(this, "Resultado oficial actualizado", Toast.LENGTH_SHORT).show(); return;
        }
        String[] choices = {"Local  ·  L", "Empate  ·  E", "Visitante  ·  V", "Quitar resultado"};
        new AlertDialog.Builder(this).setTitle("Resultado · Partido " + (slot + 1)).setItems(choices, (dialog, position) -> {
            String state = position == 0 ? "L" : position == 1 ? "E" : position == 2 ? "V" : null;
            database.saveTrackedResult(activeContest, slot, state); renderContest(selectedSnapshot());
        }).show();
    }

    private void renderContest(DatabaseHelper.PredictionSnapshot snapshot) {
        activeContest = snapshot.contestNumber();
        DatabaseHelper.TrackedResults tracked = database.getTrackedResults(activeContest);
        List<DatabaseHelper.TeamNames> names = database.getMatchNames(activeContest); String[] results = tracked.results();
        rendering = true;
        for (int slot = 0; slot < type.slots; slot++) {
            String local = names.get(slot).local(), visitor = names.get(slot).visitor();
            localInputs.get(slot).setText(local);
            visitorInputs.get(slot).setText(visitor);
            String result = results[slot] == null ? "—" : results[slot]; TextView resultLabel = resultLabels.get(slot);
            resultLabel.setText(result); boolean selected = results[slot] != null;
            resultLabel.setTextColor(selected ? Color.WHITE : getColor(R.color.muted)); resultLabel.setBackground(resultBackground(selected));
        }
        rendering = false;
        int completed = tracked.completedCount();
        if ("OFFICIAL".equals(tracked.source())) statusText.setText(getString(R.string.tracking_official_status, type.slots));
        else if (completed == 0) statusText.setText(R.string.tracking_no_results);
        else statusText.setText(getString(R.string.tracking_partial_status, completed, type.slots));
        renderComparison(snapshot, results, completed);
        updateDeleteButtons();
    }

    private void updateDeleteButtons() {
        boolean personal = false, automatic = false;
        for (DatabaseHelper.PredictionSnapshot snapshot : allSnapshots) {
            if (snapshot.contestNumber() != activeContest) continue;
            if ("PERSONAL".equals(snapshot.packageType())) personal = true;
            if ("AUTO".equals(snapshot.packageType())) automatic = true;
        }
        deletePersonalButton.setVisibility(personal ? View.VISIBLE : View.GONE);
        deleteAutomaticButton.setVisibility(automatic ? View.VISIBLE : View.GONE);
    }

    private void confirmDeletePackage(String packageType) {
        if (activeContest == 0) return;
        String label = "PERSONAL".equals(packageType) ? "personalizado" : "automático";
        new AlertDialog.Builder(this)
                .setTitle("Eliminar análisis " + label)
                .setMessage("Se eliminarán sus 20 pronósticos. Los resultados y equipos capturados se conservarán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    database.deletePredictionPackage(activeContest, packageType);
                    loadSnapshots();
                    Toast.makeText(this, "Análisis " + label + " eliminado", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void renderComparison(DatabaseHelper.PredictionSnapshot snapshot, String[] results, int completed) {
        comparisonList.removeAllViews(); comparisonList.addView(bodyText("Toca un pronóstico para ver los equipos. Los nombres se guardan automáticamente."));
        if (completed == 0) { TextView pending = bodyText("Captura al menos un resultado para iniciar la comparación."); pending.setTextColor(getColor(R.color.muted)); comparisonList.addView(pending); }
        List<DatabaseHelper.PredictionSnapshot> packages = new ArrayList<>();
        for (DatabaseHelper.PredictionSnapshot candidate : allSnapshots) {
            if (candidate.contestNumber() == snapshot.contestNumber()) packages.add(candidate);
        }
        packages.sort(Comparator.comparing(DatabaseHelper.PredictionSnapshot::packageType).reversed());
        for (DatabaseHelper.PredictionSnapshot packageSnapshot : packages) {
            TextView heading = bodyText("PERSONAL".equals(packageSnapshot.packageType())
                    ? "Personalizado · 20 pronósticos" : "Automático · 20 pronósticos");
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setTextColor(getColor(R.color.primary_dark));
            heading.setPadding(0, dp(12), 0, dp(2));
            comparisonList.addView(heading);
            addPackageComparison(packageSnapshot, results, completed);
        }
    }

    private void addPackageComparison(
            DatabaseHelper.PredictionSnapshot snapshot, String[] results, int completed
    ) {
        List<PredictionComparison.RankedPrediction> comparisons = PredictionComparison.rank(snapshot.sequences(), results);
        int position = 1;
        for (PredictionComparison.RankedPrediction comparison : comparisons) {
            String sequence = comparison.sequence().replace("", " ").trim();
            String prefix = position + ".  ";
            SpannableString line = new SpannableString(prefix + sequence + "    "
                    + comparison.matches() + "/" + type.slots + " aciertos");
            for (int slot = 0; slot < results.length; slot++) {
                int start = prefix.length() + slot * 2;
                int color = results[slot] == null ? getColor(R.color.muted)
                        : comparison.sequence().charAt(slot) == results[slot].charAt(0)
                        ? getColor(R.color.primary) : getColor(R.color.result_error);
                line.setSpan(new ForegroundColorSpan(color), start, start + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView row = bodyText("");
            row.setText(line);
            row.setPadding(0, dp(8), 0, dp(8)); int rank = position;
            row.setOnClickListener(view -> showPredictionDetails(snapshot, comparison, results, rank));
            if (position == 1 && completed > 0) row.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            comparisonList.addView(row); position++;
        }
    }

    private void showPredictionDetails(DatabaseHelper.PredictionSnapshot snapshot, PredictionComparison.RankedPrediction comparison, String[] results, int rank) {
        List<DatabaseHelper.TeamNames> names = database.getMatchNames(snapshot.contestNumber()); StringBuilder details = new StringBuilder();
        for (int slot = 0; slot < type.slots; slot++) {
            DatabaseHelper.TeamNames teams = names.get(slot);
            details.append(slot + 1).append(". ").append(teams.local().isBlank() ? "Local" : teams.local()).append(" vs ")
                    .append(teams.visitor().isBlank() ? "Visitante" : teams.visitor()).append("\nPronóstico: ")
                    .append(comparison.sequence().charAt(slot)).append(" · Resultado: ")
                    .append(results[slot] == null ? "Pendiente" : results[slot]).append("\n\n");
        }
        new AlertDialog.Builder(this).setTitle("Concurso " + snapshot.contestNumber() + " · Posición " + rank)
                .setMessage(details.toString().trim()).setPositiveButton("Cerrar", null).show();
    }

    private DatabaseHelper.PredictionSnapshot selectedSnapshot() { return snapshots.get(contestSpinner.getSelectedItemPosition()); }

    private void clearScreen() {
        activeContest = 0; statusText.setText(R.string.tracking_empty); comparisonList.removeAllViews();
        deletePersonalButton.setVisibility(View.GONE);
        deleteAutomaticButton.setVisibility(View.GONE);
        rendering = true;
        for (int slot = 0; slot < localInputs.size(); slot++) {
            localInputs.get(slot).setText(""); visitorInputs.get(slot).setText("");
            resultLabels.get(slot).setText("—"); resultLabels.get(slot).setTextColor(getColor(R.color.muted)); resultLabels.get(slot).setBackground(resultBackground(false));
        }
        rendering = false;
    }

    private TextView bodyText(String text) { TextView view = new TextView(this); view.setText(text); view.setTextColor(getColor(R.color.ink)); view.setTextSize(14); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { database.close(); super.onDestroy(); }
}
