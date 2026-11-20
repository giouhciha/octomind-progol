package mx.octomind.progol;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HeatmapActivity extends Activity {
    private final Map<DrawType, DatabaseHelper> databases = new EnumMap<>(DrawType.class);
    private LinearLayout grid;
    private TextView statsText;
    private DrawType selectedType = DrawType.MS;
    private boolean sortByAverage = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_heatmap);
        for (DrawType type : DrawType.values()) databases.put(type, new DatabaseHelper(this, type));

        grid = findViewById(R.id.heatmapGrid);
        statsText = findViewById(R.id.heatmapStats);

        Spinner drawSelector = findViewById(R.id.heatmapDrawSelector);
        drawSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{DrawType.MS.label, DrawType.WEEKEND.label, DrawType.REVANCHA.label}));
        drawSelector.setSelection(0);
        drawSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedType = DrawType.values()[position];
                renderHeatmap();
            }
        });

        Spinner sortSelector = findViewById(R.id.heatmapSortSelector);
        sortSelector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.heatmap_sort_position), getString(R.string.heatmap_sort_average)}));
        sortSelector.setSelection(0);
        sortSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sortByAverage = position == 1;
                renderHeatmap();
            }
        });
        renderHeatmap();
    }

    private void renderHeatmap() {
        grid.removeAllViews();
        double[] averages;
        int contestCount;
        try {
            DatabaseHelper.AutomaticHeatmap stats = databases.get(selectedType).automaticHeatmap();
            averages = stats.averages();
            contestCount = stats.contestCount();
        } catch (JSONException error) {
            grid.addView(bodyText("No se pudieron leer los pronósticos guardados."));
            statsText.setText("");
            return;
        }

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        boolean any = false;
        for (double value : averages) {
            if (value < 0) continue;
            any = true;
            if (value < min) min = value;
            if (value > max) max = value;
        }
        if (!any) {
            grid.addView(bodyText("Sin resultados capturados para las quinielas automáticas."));
            statsText.setText("");
            return;
        }

        statsText.setText(contestCount + " concursos con resultados · rango "
                + formatOne(min) + " – " + formatOne(max) + " aciertos");

        List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= 60; i++) order.add(i);
        if (sortByAverage) {
            order.sort((a, b) -> {
                double va = averages[a - 1];
                double vb = averages[b - 1];
                if (va < 0 && vb < 0) return Integer.compare(a, b);
                if (va < 0) return 1;
                if (vb < 0) return -1;
                return Double.compare(vb, va);
            });
        }

        for (int row = 0; row < 6; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 10; col++) {
                int position = order.get(row * 10 + col);
                double avg = averages[position - 1];
                TextView cell = new TextView(this);
                cell.setGravity(Gravity.CENTER);
                cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                cell.setTextSize(12);
                if (avg < 0) {
                    cell.setText(String.valueOf(position));
                    cell.setTextColor(getColor(R.color.muted));
                    cell.setBackgroundColor(getColor(R.color.card));
                } else {
                    cell.setText(position + "\n" + formatOne(avg));
                    cell.setTextColor(Color.rgb(28, 28, 28));
                    double t = (max == min) ? 0.5 : (avg - min) / (max - min);
                    GradientDrawable background = new GradientDrawable();
                    background.setCornerRadius(dp(6));
                    background.setColor(heatColor(t));
                    cell.setBackground(background);
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
                params.setMargins(dp(2), dp(2), dp(2), dp(2));
                line.addView(cell, params);
            }
            grid.addView(line);
        }
    }

    private int heatColor(double t) {
        // 0 = rojo (menos aciertos) · 1 = verde (más aciertos)
        int r = (int) (229 + (129 - 229) * t);
        int g = (int) (115 + (199 - 115) * t);
        int b = (int) (115 + (132 - 115) * t);
        return Color.rgb(r, g, b);
    }

    private String formatOne(double value) {
        return String.format(Locale.US, "%.1f", value);
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
}
