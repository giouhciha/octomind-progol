package mx.octomind.progol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cliente del backend de Octomind (historial, datos publicados, etc.). */
public final class ApiRepository {
    public static final String BASE_URL =
            "https://8m5pa7pjih.execute-api.us-east-1.amazonaws.com/prod/";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT);

    /** Descarga el historial (resultados L/E/V) para el análisis. */
    public List<Contest> fetchHistory(DrawType type, String accessToken) throws Exception {
        return parseHistory(get(BASE_URL + "history?drawType=" + type.name(), accessToken), type);
    }

    static List<Contest> parseHistory(String json, DrawType type) throws Exception {
        JSONArray array = new JSONObject(json).getJSONArray("history");
        List<Contest> contests = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            int number = item.getInt("contestNumber");
            LocalDate date = parseDate(item.optString("date", ""));
            JSONArray resultsJson = item.getJSONArray("results");
            String[] results = new String[type.slots];
            for (int slot = 0; slot < type.slots; slot++) {
                results[slot] = resultsJson.getString(slot);
            }
            contests.add(new Contest(type.product, number, date, results));
        }
        return contests;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return LocalDate.of(1970, 1, 1);
        return LocalDate.parse(value.trim(), DATE_FORMAT);
    }

    /** Guarda una plantilla personalizada (config + combinaciones) del usuario. */
    public String saveTemplate(
            DrawType type,
            int contestNumber,
            String name,
            JSONObject config,
            List<String> combinations,
            String accessToken
    ) throws Exception {
        JSONObject body = new JSONObject();
        body.put("drawType", type.name());
        body.put("contestNumber", contestNumber);
        body.put("name", name);
        body.put("config", config);
        body.put("combinations", new JSONArray(combinations));
        return post(BASE_URL + "templates", body.toString(), accessToken);
    }

    public String getTemplates(String accessToken) throws Exception {
        return get(BASE_URL + "templates", accessToken);
    }

    public String getLeaderboard(String accessToken) throws Exception {
        return get(BASE_URL + "leaderboard", accessToken);
    }

    public enum PublishStatus { PUBLISHED, EXPIRED, NOT_PUBLISHED }

    /** Estado de publicación del sorteo (activo, finalizado o sin publicar). */
    public PublishStatus getPublishStatus(DrawType type, String accessToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                BASE_URL + "data/latest?drawType=" + type.name()).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            int status = connection.getResponseCode();
            if (status == 200) return PublishStatus.PUBLISHED;
            if (status == 404) {
                String body = readAll(connection.getErrorStream());
                return body != null && body.contains("finalizado")
                        ? PublishStatus.EXPIRED : PublishStatus.NOT_PUBLISHED;
            }
            throw new IllegalArgumentException("HTTP " + status);
        } finally {
            connection.disconnect();
        }
    }

    /** Descarga el concurso publicado (número + partidos) para mostrar los equipos. */
    public PublishedContest fetchPublished(DrawType type, int contestNumber, String accessToken) throws Exception {
        return parsePublished(
                get(BASE_URL + "data/latest?drawType=" + type.name()
                        + "&contestNumber=" + contestNumber, accessToken),
                contestNumber);
    }

    /** Descarga el concurso publicado más reciente (sin especificar número). */
    public PublishedContest fetchLatest(DrawType type, String accessToken) throws Exception {
        String json = get(BASE_URL + "data/latest?drawType=" + type.name(), accessToken);
        int contestNumber = new JSONObject(json).getInt("contestNumber");
        return parsePublished(json, contestNumber);
    }

    private static PublishedContest parsePublished(String json, int contestNumber) throws Exception {
        JSONObject obj = new JSONObject(json);
        JSONArray array = obj.optJSONArray("matches");
        List<Match> matches = new ArrayList<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                JSONObject match = array.getJSONObject(index);
                matches.add(new Match(
                        match.optInt("slot", index + 1),
                        match.optString("local", ""),
                        match.optString("visitor", ""),
                        match.optString("dateTime", "")
                ));
            }
        }
        return new PublishedContest(contestNumber, matches);
    }

    public record Match(int slot, String local, String visitor, String dateTime) {}

    public record PublishedContest(int contestNumber, List<Match> matches) {}

    private static String post(String url, String body, String accessToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String text = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException(text);
            }
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String get(String url, String accessToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            int status = connection.getResponseCode();
            String text = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException(text);
            }
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
