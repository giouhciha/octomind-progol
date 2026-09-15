package mx.octomind.progol;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class TrackingActivity extends Activity {
    private DatabaseHelper database;
    private Spinner contestSpinner;
    private TextView statusText;
    private LinearLayout resultInputs;
    private LinearLayout comparisonList;
    private List<DatabaseHelper.PredictionSnapshot> snapshots = List.of();
    private final List<RadioGroup> resultGroups = new ArrayList<>();
    private boolean rendering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tracking);
        database = new DatabaseHelper(this);
        contestSpinner = findViewById(R.id.contestSpinner);
        statusText = findViewById(R.id.trackingStatus);
        resultInputs = findViewById(R.id.resultInputs);
        comparisonList = findViewById(R.id.comparisonList);
        buildResultInputs();
        contestSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < snapshots.size()) {
                    renderContest(snapshots.get(position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                clearScreen();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSnapshots();
    }

    private void loadSnapshots() {
        try {
            snapshots = database.getPredictionSnapshots();
            List<String> labels = new ArrayList<>();
            for (DatabaseHelper.PredictionSnapshot snapshot : snapshots) {
                labels.add("Concurso " + snapshot.contestNumber());
            }
            contestSpinner.setAdapter(new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_dropdown_item, labels
            ));
            if (snapshots.isEmpty()) {
                clearScreen();
            }
        } catch (JSONException exception) {
            statusText.setText(R.string.tracking_read_error);
            Toast.makeText(this, R.string.tracking_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void buildResultInputs() {
        resultInputs.removeAllViews();
        resultGroups.clear();
        for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);

            TextView label = bodyText(Integer.toString(slot + 1));
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(label, new LinearLayout.LayoutParams(dp(32), dp(48)));

            RadioGroup group = new RadioGroup(this);
            group.setOrientation(RadioGroup.HORIZONTAL);
            addOption(group, "—");
            addOption(group, "L");
            addOption(group, "E");
            addOption(group, "V");
            int capturedSlot = slot;
            group.setOnCheckedChangeListener((changedGroup, checkedId) -> {
                if (rendering || snapshots.isEmpty()) return;
                RadioButton selected = changedGroup.findViewById(checkedId);
                String state = selected == null ? null : (String) selected.getTag();
                if ("—".equals(state)) state = null;
                database.saveTrackedResult(
                        selectedSnapshot().contestNumber(), capturedSlot, state
                );
                renderContest(selectedSnapshot());
            });
            row.addView(group, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1
            ));
            resultInputs.addView(row);
            resultGroups.add(group);
        }
    }

    private void addOption(RadioGroup group, String state) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setText(state);
        option.setTag(state);
        option.setGravity(Gravity.CENTER);
        group.addView(option, new RadioGroup.LayoutParams(0, dp(48), 1));
    }

    private void renderContest(DatabaseHelper.PredictionSnapshot snapshot) {
        DatabaseHelper.TrackedResults tracked = database.getTrackedResults(
                snapshot.contestNumber()
        );
        String[] results = tracked.results();
        rendering = true;
        for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
            RadioGroup group = resultGroups.get(slot);
            String expected = results[slot] == null ? "—" : results[slot];
            for (int index = 0; index < group.getChildCount(); index++) {
                RadioButton option = (RadioButton) group.getChildAt(index);
                if (expected.equals(option.getTag())) {
                    group.check(option.getId());
                    break;
                }
            }
            group.setEnabled(!"OFFICIAL".equals(tracked.source()));
            for (int index = 0; index < group.getChildCount(); index++) {
                group.getChildAt(index).setEnabled(!"OFFICIAL".equals(tracked.source()));
            }
        }
        rendering = false;

        int completed = tracked.completedCount();
        if ("OFFICIAL".equals(tracked.source())) {
            statusText.setText(R.string.tracking_official_status);
        } else if (completed == 0) {
            statusText.setText(R.string.tracking_no_results);
        } else {
            statusText.setText(getString(R.string.tracking_partial_status, completed));
        }
        renderComparison(snapshot, results, completed);
    }

    private void renderComparison(
            DatabaseHelper.PredictionSnapshot snapshot,
            String[] results,
            int completed
    ) {
        comparisonList.removeAllViews();
        List<PredictionComparison.RankedPrediction> comparisons =
                PredictionComparison.rank(snapshot.sequences(), results);
        if (completed == 0) {
            TextView pending = bodyText("Captura al menos un resultado para iniciar la comparación.");
            pending.setTextColor(getColor(R.color.muted));
            comparisonList.addView(pending);
        }
        int position = 1;
        for (PredictionComparison.RankedPrediction comparison : comparisons) {
            String sequence = comparison.sequence().replace("", " ").trim();
            TextView row = bodyText(
                    position + ".  " + sequence + "    "
                            + comparison.matches() + "/" + completed + " aciertos"
            );
            row.setPadding(0, dp(8), 0, dp(8));
            if (position == 1 && completed > 0) {
                row.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            }
            comparisonList.addView(row);
            position++;
        }
    }

    private DatabaseHelper.PredictionSnapshot selectedSnapshot() {
        return snapshots.get(contestSpinner.getSelectedItemPosition());
    }

    private void clearScreen() {
        statusText.setText(R.string.tracking_empty);
        comparisonList.removeAllViews();
        rendering = true;
        for (RadioGroup group : resultGroups) {
            group.clearCheck();
            group.setEnabled(false);
        }
        rendering = false;
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.ink));
        view.setTextSize(14);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        database.close();
        super.onDestroy();
    }
}
