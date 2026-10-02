package mx.octomind.progol;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.util.*;
import org.json.JSONObject;

/** Runs against separate qa_ databases; never modifies a user's saved contests. */
public final class IntegrationInstrumentation extends Instrumentation {
    private boolean useFixtures;
    private boolean seedUi;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        useFixtures = "true".equals(arguments.getString("useFixtures"));
        seedUi = "true".equals(arguments.getString("seedUi"));
        start();
    }
    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        StringBuilder report = new StringBuilder();
        try {
            Context context = getTargetContext();
            for (DrawType type : DrawType.values()) {
                String name = "qa_" + type.databaseName;
                context.deleteDatabase(name);
                try (DatabaseHelper db = new DatabaseHelper(context, type, name)) {
                    List<Contest> history;
                    if (useFixtures) {
                        try (java.io.InputStream input = getContext().getAssets().open(type.name() + ".csv")) {
                            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                            history = new HistoricalRepository(type).parseCsv(bytes.toString("UTF-8"));
                        }
                    } else history = new HistoricalRepository(type).download();
                    db.replaceContests(history);
                    int latest = history.stream().mapToInt(Contest::number).max().orElseThrow();
                    long start = System.currentTimeMillis();
                    PredictionEngine.Forecast forecast = new PredictionEngine(type).analyze(history);
                    long duration = System.currentTimeMillis() - start;
                    db.savePrediction(forecast);
                    if (seedUi) {
                        try (DatabaseHelper visible = new DatabaseHelper(context, type)) {
                            visible.replaceContests(history);
                            visible.savePrediction(forecast);
                        }
                    }
                    db.savePrediction(forecast);
                    require(db.getPredictionSnapshots().size() == 1, "duplicate package");
                    require(db.getPredictionSnapshots().get(0).sequences().size() == 20, "package size");
                    db.saveTrackedResult(latest + 1, 0, "E");
                    db.saveTrackedResult(latest + 1, type.slots - 1, "V");
                    require(db.getTrackedResults(latest + 1).completedCount() == 2, "partial save");
                    db.saveMatchNames(latest + 1, 0, "América", "León");
                    db.saveMatchNames(latest + 1, type.slots - 1, "Último local", "Último visitante");
                    require(db.getMatchNames(latest + 2).get(0).local().isEmpty(), "names leaked to another contest");
                    String backup = db.exportBackup();
                    db.saveMatchNames(latest + 1, 0, "Cambiado", "");
                    JSONObject legacy = new JSONObject(backup);
                    legacy.remove("drawType");
                    if (type == DrawType.MS) require(db.restoreBackup(legacy.toString(), true) == history.size(), "legacy backup");
                    db.restoreBackup(backup);
                    require("América".equals(db.getMatchNames(latest + 1).get(0).local()), "names backup restore");
                    require("Último visitante".equals(db.getMatchNames(latest + 1).get(type.slots - 1).visitor()), "last team moved");
                    require(db.getTrackedResults(latest + 1).results()[1] == null, "pending slot moved");
                    require("V".equals(db.getTrackedResults(latest + 1).results()[type.slots-1]), "last slot moved");
                    JSONObject invalid = new JSONObject(backup);
                    invalid.getJSONArray("contests").getJSONObject(0).put("product", 999);
                    try { db.restoreBackup(invalid.toString()); throw new AssertionError("bad backup accepted"); }
                    catch (IllegalArgumentException expected) { }
                    require(db.getAllContests().size() == history.size(), "bad backup mutated DB");
                    // Simulate publication of the target using a complete known result vector.
                    Contest official = new Contest(type.product, latest + 1,
                            history.get(history.size()-1).date().plusDays(7),
                            history.get(history.size()-1).results());
                    db.reconcileTrackedResults(List.of(official));
                    require(db.getTrackedResults(latest + 1).completedCount() == type.slots, "official completion");
                    require("OFFICIAL".equals(db.getTrackedResults(latest + 1).source()), "official source");
                    require(Arrays.equals(official.results(), db.getTrackedResults(latest+1).results()), "official correction");
                    require(db.getPredictionSnapshots().size() == 1, "official replaced picks");
                    require("América".equals(db.getMatchNames(latest + 1).get(0).local()), "official erased names");
                    report.append(type).append(": ").append(history.size()).append(" concursos; target ")
                            .append(forecast.targetContest()).append("; motor ").append(duration).append(" ms; backup, parcial y oficial OK\n");
                    Bundle progress = new Bundle();
                    progress.putString("stream", report.toString());
                    sendStatus(0, progress);
                } finally { context.deleteDatabase(name); }
            }
            output.putString("stream", report.toString());
            finish(-1, output);
        } catch (Throwable failure) {
            java.io.StringWriter stack = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(stack));
            output.putString("stream", report + "\nFAIL: " + stack);
            finish(1, output);
        }
    }
}
