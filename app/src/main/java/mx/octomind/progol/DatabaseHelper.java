package mx.octomind.progol;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "octomind_progol.db";
    private static final int DATABASE_VERSION = 2;
    private static final int BACKUP_SCHEMA_VERSION = 2;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE contests ("
                        + "number INTEGER PRIMARY KEY,"
                        + "product INTEGER NOT NULL,"
                        + "contest_date TEXT NOT NULL,"
                        + "r1 TEXT NOT NULL,r2 TEXT NOT NULL,r3 TEXT NOT NULL,"
                        + "r4 TEXT NOT NULL,r5 TEXT NOT NULL,r6 TEXT NOT NULL,"
                        + "r7 TEXT NOT NULL,r8 TEXT NOT NULL,r9 TEXT NOT NULL)"
        );
        database.execSQL(
                "CREATE TABLE prediction_runs ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "created_at TEXT NOT NULL,"
                        + "source_contest INTEGER NOT NULL,"
                        + "target_contest INTEGER NOT NULL,"
                        + "model_version TEXT NOT NULL,"
                        + "payload TEXT NOT NULL)"
        );
        createTrackedResultsTable(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createTrackedResultsTable(database);
        }
    }

    private void createTrackedResultsTable(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS tracked_results ("
                        + "contest_number INTEGER PRIMARY KEY,"
                        + "r1 TEXT,r2 TEXT,r3 TEXT,r4 TEXT,r5 TEXT,"
                        + "r6 TEXT,r7 TEXT,r8 TEXT,r9 TEXT,"
                        + "source TEXT NOT NULL,updated_at TEXT NOT NULL)"
        );
    }

    public void replaceContests(List<Contest> contests) {
        if (contests == null || contests.size() < 100) {
            throw new IllegalArgumentException("El historico validado esta incompleto");
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("contests", null, null);
            for (Contest contest : contests) {
                ContentValues values = new ContentValues();
                values.put("number", contest.number());
                values.put("product", contest.product());
                values.put("contest_date", contest.date().toString());
                for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                    values.put("r" + (slot + 1), contest.result(slot));
                }
                if (database.insertOrThrow("contests", null, values) == -1) {
                    throw new IllegalStateException("No se pudo guardar el concurso");
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public List<Contest> getAllContests() {
        List<Contest> contests = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "contests",
                null,
                null,
                null,
                null,
                null,
                "contest_date ASC, number ASC"
        )) {
            while (cursor.moveToNext()) {
                int product = cursor.getInt(cursor.getColumnIndexOrThrow("product"));
                int number = cursor.getInt(cursor.getColumnIndexOrThrow("number"));
                LocalDate date = LocalDate.parse(
                        cursor.getString(cursor.getColumnIndexOrThrow("contest_date"))
                );
                String[] results = new String[Contest.SLOT_COUNT];
                for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                    results[slot] = cursor.getString(
                            cursor.getColumnIndexOrThrow("r" + (slot + 1))
                    );
                }
                contests.add(new Contest(product, number, date, results));
            }
        }
        return contests;
    }

    public void savePrediction(PredictionEngine.Forecast forecast) {
        ContentValues values = new ContentValues();
        values.put("created_at", Instant.now().toString());
        values.put("source_contest", forecast.sourceContest());
        values.put("target_contest", forecast.targetContest());
        values.put("model_version", PredictionEngine.MODEL_VERSION);
        values.put("payload", forecast.toJson().toString());
        getWritableDatabase().insertOrThrow("prediction_runs", null, values);
    }

    public List<PredictionSnapshot> getPredictionSnapshots() throws JSONException {
        Map<Integer, PredictionSnapshot> latestByContest = new LinkedHashMap<>();
        try (Cursor cursor = getReadableDatabase().query(
                "prediction_runs", null, null, null, null, null, "id DESC"
        )) {
            while (cursor.moveToNext()) {
                int target = cursor.getInt(cursor.getColumnIndexOrThrow("target_contest"));
                if (latestByContest.containsKey(target)) {
                    continue;
                }
                JSONObject payload = new JSONObject(
                        cursor.getString(cursor.getColumnIndexOrThrow("payload"))
                );
                JSONArray rows = payload.getJSONArray("recommendations");
                List<String> sequences = new ArrayList<>();
                for (int index = 0; index < rows.length(); index++) {
                    String sequence = rows.getJSONObject(index).getString("sequence");
                    if (sequence.length() != Contest.SLOT_COUNT) {
                        throw new JSONException("Pronostico guardado incompleto");
                    }
                    sequences.add(sequence);
                }
                latestByContest.put(target, new PredictionSnapshot(target, sequences));
            }
        }
        List<PredictionSnapshot> snapshots = new ArrayList<>(latestByContest.values());
        snapshots.sort((left, right) -> Integer.compare(right.contestNumber(), left.contestNumber()));
        return snapshots;
    }

    public TrackedResults getTrackedResults(int contestNumber) {
        String[] results = new String[Contest.SLOT_COUNT];
        String source = "USER";
        try (Cursor cursor = getReadableDatabase().query(
                "tracked_results", null, "contest_number = ?",
                new String[]{Integer.toString(contestNumber)}, null, null, null
        )) {
            if (cursor.moveToFirst()) {
                source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
                for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                    results[slot] = cursor.getString(
                            cursor.getColumnIndexOrThrow("r" + (slot + 1))
                    );
                }
            }
        }
        return new TrackedResults(contestNumber, results, source);
    }

    public void saveTrackedResult(int contestNumber, int slot, String state) {
        if (slot < 0 || slot >= Contest.SLOT_COUNT) {
            throw new IllegalArgumentException("Casilla invalida");
        }
        if (state != null && !Contest.isValidState(state)) {
            throw new IllegalArgumentException("Resultado invalido");
        }
        ContentValues initial = new ContentValues();
        initial.put("contest_number", contestNumber);
        initial.put("source", "USER");
        initial.put("updated_at", Instant.now().toString());
        getWritableDatabase().insertWithOnConflict(
                "tracked_results", null, initial, SQLiteDatabase.CONFLICT_IGNORE
        );
        ContentValues values = new ContentValues();
        if (state == null) {
            values.putNull("r" + (slot + 1));
        } else {
            values.put("r" + (slot + 1), state);
        }
        values.put("source", "USER");
        values.put("updated_at", Instant.now().toString());
        getWritableDatabase().update(
                "tracked_results", values, "contest_number = ?",
                new String[]{Integer.toString(contestNumber)}
        );
    }

    public void reconcileTrackedResults(List<Contest> history) {
        Set<Integer> predictedTargets = new HashSet<>();
        try (Cursor cursor = getReadableDatabase().query(
                true, "prediction_runs", new String[]{"target_contest"},
                null, null, null, null, null, null
        )) {
            while (cursor.moveToNext()) {
                predictedTargets.add(cursor.getInt(0));
            }
        }
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            for (Contest contest : history) {
                if (!predictedTargets.contains(contest.number())) {
                    continue;
                }
                ContentValues values = trackedValues(contest.number(), contest.results(), "OFFICIAL");
                database.insertWithOnConflict(
                        "tracked_results", null, values, SQLiteDatabase.CONFLICT_REPLACE
                );
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private ContentValues trackedValues(int contestNumber, String[] results, String source) {
        ContentValues values = new ContentValues();
        values.put("contest_number", contestNumber);
        for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
            if (results[slot] == null) {
                values.putNull("r" + (slot + 1));
            } else {
                values.put("r" + (slot + 1), results[slot]);
            }
        }
        values.put("source", source);
        values.put("updated_at", Instant.now().toString());
        return values;
    }

    public String exportBackup() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", BACKUP_SCHEMA_VERSION);
        root.put("createdAt", Instant.now().toString());
        root.put("modelVersion", PredictionEngine.MODEL_VERSION);

        JSONArray contestArray = new JSONArray();
        for (Contest contest : getAllContests()) {
            JSONObject item = new JSONObject();
            item.put("product", contest.product());
            item.put("number", contest.number());
            item.put("date", contest.date().toString());
            JSONArray results = new JSONArray();
            for (String result : contest.results()) {
                results.put(result);
            }
            item.put("results", results);
            contestArray.put(item);
        }
        root.put("contests", contestArray);

        JSONArray predictions = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query(
                "prediction_runs", null, null, null, null, null, "id ASC"
        )) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("createdAt", cursor.getString(cursor.getColumnIndexOrThrow("created_at")));
                item.put("sourceContest", cursor.getInt(cursor.getColumnIndexOrThrow("source_contest")));
                item.put("targetContest", cursor.getInt(cursor.getColumnIndexOrThrow("target_contest")));
                item.put("modelVersion", cursor.getString(cursor.getColumnIndexOrThrow("model_version")));
                item.put("payload", new JSONObject(
                        cursor.getString(cursor.getColumnIndexOrThrow("payload"))
                ));
                predictions.put(item);
            }
        }
        root.put("predictions", predictions);

        JSONArray tracked = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query(
                "tracked_results", null, null, null, null, null, "contest_number ASC"
        )) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("contestNumber", cursor.getInt(
                        cursor.getColumnIndexOrThrow("contest_number")
                ));
                item.put("source", cursor.getString(cursor.getColumnIndexOrThrow("source")));
                JSONArray results = new JSONArray();
                for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                    String value = cursor.getString(
                            cursor.getColumnIndexOrThrow("r" + (slot + 1))
                    );
                    results.put(value == null ? JSONObject.NULL : value);
                }
                item.put("results", results);
                tracked.put(item);
            }
        }
        root.put("trackedResults", tracked);
        return root.toString(2);
    }

    public int restoreBackup(String jsonText) throws JSONException {
        JSONObject root = new JSONObject(jsonText);
        int schemaVersion = root.getInt("schemaVersion");
        if (schemaVersion < 1 || schemaVersion > BACKUP_SCHEMA_VERSION) {
            throw new JSONException("Version de respaldo no compatible");
        }
        JSONArray contestArray = root.getJSONArray("contests");
        List<Contest> contests = new ArrayList<>();
        Set<Integer> contestNumbers = new HashSet<>();
        for (int index = 0; index < contestArray.length(); index++) {
            JSONObject item = contestArray.getJSONObject(index);
            JSONArray jsonResults = item.getJSONArray("results");
            if (jsonResults.length() != Contest.SLOT_COUNT) {
                throw new JSONException("Concurso con resultados incompletos");
            }
            String[] results = new String[Contest.SLOT_COUNT];
            for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                results[slot] = jsonResults.getString(slot);
            }
            Contest contest = new Contest(
                    item.getInt("product"),
                    item.getInt("number"),
                    LocalDate.parse(item.getString("date")),
                    results
            );
            if (!contestNumbers.add(contest.number())) {
                throw new JSONException("Concurso duplicado en el respaldo");
            }
            contests.add(contest);
        }
        if (contests.size() < 100) {
            throw new JSONException("El historico del respaldo esta incompleto");
        }

        JSONArray predictions = root.optJSONArray("predictions");
        List<ContentValues> predictionValues = new ArrayList<>();
        if (predictions != null) {
            for (int index = 0; index < predictions.length(); index++) {
                JSONObject item = predictions.getJSONObject(index);
                ContentValues values = new ContentValues();
                values.put("created_at", Instant.parse(item.getString("createdAt")).toString());
                values.put("source_contest", item.getInt("sourceContest"));
                values.put("target_contest", item.getInt("targetContest"));
                values.put("model_version", item.getString("modelVersion"));
                values.put("payload", item.getJSONObject("payload").toString());
                predictionValues.add(values);
            }
        }

        List<ContentValues> trackedValues = new ArrayList<>();
        JSONArray tracked = root.optJSONArray("trackedResults");
        if (tracked != null) {
            Set<Integer> trackedContests = new HashSet<>();
            for (int index = 0; index < tracked.length(); index++) {
                JSONObject item = tracked.getJSONObject(index);
                int contestNumber = item.getInt("contestNumber");
                if (!trackedContests.add(contestNumber)) {
                    throw new JSONException("Seguimiento duplicado en el respaldo");
                }
                JSONArray results = item.getJSONArray("results");
                if (results.length() != Contest.SLOT_COUNT) {
                    throw new JSONException("Seguimiento incompleto en el respaldo");
                }
                String[] states = new String[Contest.SLOT_COUNT];
                for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                    if (!results.isNull(slot)) {
                        states[slot] = results.getString(slot);
                        if (!Contest.isValidState(states[slot])) {
                            throw new JSONException("Resultado de seguimiento invalido");
                        }
                    }
                }
                String source = item.optString("source", "USER");
                if (!"USER".equals(source) && !"OFFICIAL".equals(source)) {
                    throw new JSONException("Fuente de seguimiento invalida");
                }
                trackedValues.add(trackedValues(contestNumber, states, source));
            }
        }

        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("contests", null, null);
            for (Contest contest : contests) {
                ContentValues values = new ContentValues();
                values.put("number", contest.number());
                values.put("product", contest.product());
                values.put("contest_date", contest.date().toString());
                for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                    values.put("r" + (slot + 1), contest.result(slot));
                }
                database.insertOrThrow("contests", null, values);
            }
            database.delete("prediction_runs", null, null);
            for (ContentValues values : predictionValues) {
                database.insertOrThrow("prediction_runs", null, values);
            }
            database.delete("tracked_results", null, null);
            for (ContentValues values : trackedValues) {
                database.insertOrThrow("tracked_results", null, values);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return contests.size();
    }

    public record PredictionSnapshot(int contestNumber, List<String> sequences) {
        public PredictionSnapshot {
            sequences = List.copyOf(sequences);
        }
    }

    public record TrackedResults(int contestNumber, String[] results, String source) {
        public TrackedResults {
            results = results.clone();
        }

        @Override
        public String[] results() {
            return results.clone();
        }

        public int completedCount() {
            int count = 0;
            for (String result : results) {
                if (result != null) count++;
            }
            return count;
        }
    }
}
