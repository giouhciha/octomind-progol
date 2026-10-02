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
    private final DrawType type;
    private static final int DATABASE_VERSION = 4;
    private static final int BACKUP_SCHEMA_VERSION = 4;

    public DatabaseHelper(Context context) {
        this(context, DrawType.MS);
    }

    public DatabaseHelper(Context context, DrawType type) {
        this(context, type, type.databaseName);
    }

    DatabaseHelper(Context context, DrawType type, String databaseName) {
        super(context, databaseName, null, DATABASE_VERSION);
        this.type = type;
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE contests ("
                        + "number INTEGER PRIMARY KEY,"
                        + "product INTEGER NOT NULL,"
                        + "contest_date TEXT NOT NULL,"
                        + resultColumns(true) + ")"
        );
        database.execSQL(
                "CREATE TABLE prediction_runs ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "created_at TEXT NOT NULL,"
                        + "source_contest INTEGER NOT NULL,"
                        + "target_contest INTEGER NOT NULL,"
                        + "package_type TEXT NOT NULL DEFAULT 'AUTO',"
                        + "model_version TEXT NOT NULL,"
                        + "payload TEXT NOT NULL)"
        );
        createTrackedResultsTable(database);
        createMatchNamesTable(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createTrackedResultsTable(database);
        }
        if (oldVersion < 3) createMatchNamesTable(database);
        if (oldVersion < 4) database.execSQL(
                "ALTER TABLE prediction_runs ADD COLUMN package_type TEXT NOT NULL DEFAULT 'AUTO'");
    }

    private void createMatchNamesTable(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS match_names (contest_number INTEGER NOT NULL,"
                + "slot INTEGER NOT NULL,local_name TEXT NOT NULL,visitor_name TEXT NOT NULL,"
                + "PRIMARY KEY(contest_number,slot))");
    }

    public record TeamNames(String local, String visitor) { }

    public List<TeamNames> getMatchNames(int contestNumber) {
        List<TeamNames> names = new ArrayList<>();
        for (int slot = 0; slot < type.slots; slot++) names.add(new TeamNames("", ""));
        try (Cursor cursor = getReadableDatabase().query("match_names", null,
                "contest_number = ?", new String[]{Integer.toString(contestNumber)}, null, null, null)) {
            while (cursor.moveToNext()) {
                int slot = cursor.getInt(cursor.getColumnIndexOrThrow("slot"));
                names.set(slot, new TeamNames(cursor.getString(cursor.getColumnIndexOrThrow("local_name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("visitor_name"))));
            }
        }
        return names;
    }

    private ContentValues nameValues(int contest, int slot, String local, String visitor) {
        if (contest <= 0 || slot < 0 || slot >= type.slots || local == null || visitor == null
                || local.length() > 120 || visitor.length() > 120)
            throw new IllegalArgumentException("Nombres de equipos inválidos");
        ContentValues values = new ContentValues();
        values.put("contest_number", contest);
        values.put("slot", slot);
        values.put("local_name", local);
        values.put("visitor_name", visitor);
        return values;
    }

    public void saveMatchNames(int contest, int slot, String local, String visitor) {
        getWritableDatabase().insertWithOnConflict("match_names", null,
                nameValues(contest, slot, local, visitor), SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void createTrackedResultsTable(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS tracked_results ("
                        + "contest_number INTEGER PRIMARY KEY,"
                        + resultColumns(false) + ","
                        + "source TEXT NOT NULL,updated_at TEXT NOT NULL)"
        );
    }

    private String resultColumns(boolean required) {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= type.slots; i++) columns.add("r" + i + " TEXT" + (required ? " NOT NULL" : ""));
        return String.join(",", columns);
    }

    public void replaceContests(List<Contest> contests) {
        if (contests == null || contests.size() < 100) {
            throw new IllegalArgumentException("El historico validado esta incompleto");
        }
        for (Contest contest : contests) {
            if (DrawType.identify(contest.product(), contest.results().length) != type)
                throw new IllegalArgumentException("Histórico de otro sorteo");
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
                for (int slot = 0; slot < type.slots; slot++) {
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
                String[] results = new String[type.slots];
                for (int slot = 0; slot < type.slots; slot++) {
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
        savePrediction(forecast, "AUTO", false);
    }

    /** A deliberately chosen set of favorites may replace an unplayed package. */
    public void savePrediction(PredictionEngine.Forecast forecast, boolean replaceExisting) {
        savePrediction(forecast, replaceExisting ? "PERSONAL" : "AUTO", replaceExisting);
    }

    public void savePrediction(PredictionEngine.Forecast forecast, String packageType, boolean replaceExisting) {
        if (!"AUTO".equals(packageType) && !"PERSONAL".equals(packageType))
            throw new IllegalArgumentException("Tipo de paquete inválido");
        try (Cursor cursor = getReadableDatabase().query("prediction_runs", new String[]{"id"},
                "target_contest = ? AND package_type = ?", new String[]{Integer.toString(forecast.targetContest()), packageType}, null, null, null)) {
            if (cursor.moveToFirst() && !replaceExisting) return;
        }
        if (replaceExisting) getWritableDatabase().delete("prediction_runs", "target_contest = ? AND package_type = ?",
                new String[]{Integer.toString(forecast.targetContest()), packageType});
        ContentValues values = new ContentValues();
        values.put("created_at", Instant.now().toString());
        values.put("source_contest", forecast.sourceContest());
        values.put("target_contest", forecast.targetContest());
        values.put("package_type", packageType);
        values.put("model_version", forecast.modelVersion());
        values.put("payload", forecast.toJson().toString());
        getWritableDatabase().insertOrThrow("prediction_runs", null, values);
    }

    public void deletePredictionPackage(int contestNumber, String packageType) {
        if (!"AUTO".equals(packageType) && !"PERSONAL".equals(packageType))
            throw new IllegalArgumentException("Tipo de paquete inválido");
        getWritableDatabase().delete("prediction_runs",
                "target_contest = ? AND package_type = ?",
                new String[]{Integer.toString(contestNumber), packageType});
    }

    public List<PredictionSnapshot> getPredictionSnapshots() throws JSONException {
        Map<String, PredictionSnapshot> latestByContest = new LinkedHashMap<>();
        try (Cursor cursor = getReadableDatabase().query(
                "prediction_runs", null, null, null, null, null, "id DESC"
        )) {
            while (cursor.moveToNext()) {
                int target = cursor.getInt(cursor.getColumnIndexOrThrow("target_contest"));
                String packageType = cursor.getString(cursor.getColumnIndexOrThrow("package_type"));
                String key = target + ":" + packageType;
                if (latestByContest.containsKey(key)) {
                    continue;
                }
                JSONObject payload = new JSONObject(
                        cursor.getString(cursor.getColumnIndexOrThrow("payload"))
                );
                JSONArray rows = payload.getJSONArray("recommendations");
                List<String> sequences = new ArrayList<>();
                for (int index = 0; index < rows.length(); index++) {
                    String sequence = rows.getJSONObject(index).getString("sequence");
                    if (sequence.length() != type.slots) {
                        throw new JSONException("Pronostico guardado incompleto");
                    }
                    sequences.add(sequence);
                }
                latestByContest.put(key, new PredictionSnapshot(target, packageType, sequences));
            }
        }
        List<PredictionSnapshot> snapshots = new ArrayList<>(latestByContest.values());
        snapshots.sort((left, right) -> {
            int contest = Integer.compare(right.contestNumber(), left.contestNumber());
            return contest != 0 ? contest : left.packageType().compareTo(right.packageType());
        });
        return snapshots;
    }

    public TrackedResults getTrackedResults(int contestNumber) {
        String[] results = new String[type.slots];
        String source = "USER";
        try (Cursor cursor = getReadableDatabase().query(
                "tracked_results", null, "contest_number = ?",
                new String[]{Integer.toString(contestNumber)}, null, null, null
        )) {
            if (cursor.moveToFirst()) {
                source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
                for (int slot = 0; slot < type.slots; slot++) {
                    results[slot] = cursor.getString(
                            cursor.getColumnIndexOrThrow("r" + (slot + 1))
                    );
                }
            }
        }
        return new TrackedResults(contestNumber, results, source);
    }

    public void saveTrackedResult(int contestNumber, int slot, String state) {
        if (slot < 0 || slot >= type.slots) {
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
        for (int slot = 0; slot < type.slots; slot++) {
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
        root.put("modelVersion", type.modelVersion());
        root.put("drawType", type.name());

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
                item.put("packageType", cursor.getString(cursor.getColumnIndexOrThrow("package_type")));
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
                for (int slot = 0; slot < type.slots; slot++) {
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
        JSONArray names = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query("match_names", null, null, null,
                null, null, "contest_number ASC, slot ASC")) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("contestNumber", cursor.getInt(cursor.getColumnIndexOrThrow("contest_number")));
                item.put("slot", cursor.getInt(cursor.getColumnIndexOrThrow("slot")));
                item.put("local", cursor.getString(cursor.getColumnIndexOrThrow("local_name")));
                item.put("visitor", cursor.getString(cursor.getColumnIndexOrThrow("visitor_name")));
                names.put(item);
            }
        }
        root.put("matchNames", names);
        return root.toString(2);
    }

    public int restoreBackup(String jsonText) throws JSONException { return restoreBackup(jsonText, false); }

    public int restoreBackup(String jsonText, boolean validateOnly) throws JSONException {
        JSONObject root = new JSONObject(jsonText);
        if (!type.name().equals(root.optString("drawType", "MS"))) throw new JSONException("Respaldo de otro sorteo");
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
            if (jsonResults.length() != type.slots) {
                throw new JSONException("Concurso con resultados incompletos");
            }
            String[] results = new String[type.slots];
            for (int slot = 0; slot < type.slots; slot++) {
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
            if (DrawType.identify(contest.product(), contest.results().length) != type) throw new JSONException("Sorteo incorrecto");
            contests.add(contest);
        }
        if (!contests.isEmpty() && contests.size() < 100) {
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
                String packageType = item.optString("packageType", "AUTO");
                if (!"AUTO".equals(packageType) && !"PERSONAL".equals(packageType)) throw new JSONException("Tipo de paquete inválido");
                values.put("package_type", packageType);
                values.put("model_version", item.getString("modelVersion"));
                JSONObject payload = item.getJSONObject("payload");
                JSONArray picks = payload.getJSONArray("recommendations");
                if (picks.length() != 20) throw new JSONException("Se requieren 20 pronósticos");
                for (int pick = 0; pick < picks.length(); pick++) {
                    String sequence = picks.getJSONObject(pick).getString("sequence");
                    if (!sequence.matches("[LEV]{" + type.slots + "}")) throw new JSONException("Pronóstico inválido");
                }
                values.put("payload", payload.toString());
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
                if (results.length() != type.slots) {
                    throw new JSONException("Seguimiento incompleto en el respaldo");
                }
                String[] states = new String[type.slots];
                for (int slot = 0; slot < type.slots; slot++) {
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

        List<ContentValues> names = new ArrayList<>();
        JSONArray nameArray = root.optJSONArray("matchNames");
        Set<String> nameKeys = new HashSet<>();
        if (nameArray != null) {
            for (int index = 0; index < nameArray.length(); index++) {
                JSONObject item = nameArray.getJSONObject(index);
                int contest = item.getInt("contestNumber"), slot = item.getInt("slot");
                if (!nameKeys.add(contest + ":" + slot)) throw new JSONException("Partido duplicado");
                names.add(nameValues(contest, slot, item.getString("local"), item.getString("visitor")));
            }
        }
        if (validateOnly) return contests.size();
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("contests", null, null);
            for (Contest contest : contests) {
                ContentValues values = new ContentValues();
                values.put("number", contest.number());
                values.put("product", contest.product());
                values.put("contest_date", contest.date().toString());
                for (int slot = 0; slot < type.slots; slot++) {
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
            database.delete("match_names", null, null);
            for (ContentValues values : names) database.insertOrThrow("match_names", null, values);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        reconcileTrackedResults(contests);
        return contests.size();
    }

    public record PredictionSnapshot(int contestNumber, String packageType, List<String> sequences) {
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
