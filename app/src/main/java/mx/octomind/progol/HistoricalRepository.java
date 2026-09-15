package mx.octomind.progol;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HistoricalRepository {
    public static final String OFFICIAL_URL =
            "https://www.loterianacional.gob.mx/Documentos/Historicos/Progol-Media-S.csv";
    private static final String EXPECTED_HEADER =
            "NPRODUCTO,CONCURSO,R1,R2,R3,R4,R5,R6,R7,R8,R9,FECHA";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT);

    public List<Contest> download() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(OFFICIAL_URL).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "Octomind-Progol-Android/0.1");
        connection.setRequestProperty("Accept", "text/csv");
        connection.setInstanceFollowRedirects(true);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("La fuente oficial respondio HTTP " + status);
            }
            try (InputStream stream = connection.getInputStream()) {
                return parseCsv(readAll(stream));
            }
        } finally {
            connection.disconnect();
        }
    }

    public List<Contest> parseCsv(String csvText) throws IOException {
        List<Contest> contests = new ArrayList<>();
        Set<Integer> seenNumbers = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(csvText.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IOException("El archivo historico esta vacio");
            }
            header = stripBom(header).trim();
            if (!EXPECTED_HEADER.equals(header)) {
                throw new IOException("El formato del historico oficial cambio");
            }

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] values = parseCsvLine(line);
                if (values.length != 12) {
                    throw new IOException("Linea " + lineNumber + " con columnas incompletas");
                }
                try {
                    int product = Integer.parseInt(values[0].trim());
                    int number = Integer.parseInt(values[1].trim());
                    if (!seenNumbers.add(number)) {
                        throw new IOException("Concurso duplicado: " + number);
                    }
                    String[] results = new String[Contest.SLOT_COUNT];
                    for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                        results[slot] = values[slot + 2].trim().toUpperCase(Locale.ROOT);
                    }
                    LocalDate date = LocalDate.parse(values[11].trim(), DATE_FORMAT);
                    contests.add(new Contest(product, number, date, results));
                } catch (IllegalArgumentException | DateTimeParseException exception) {
                    throw new IOException("Dato invalido en la linea " + lineNumber, exception);
                }
            }
        }

        if (contests.size() < 100) {
            throw new IOException("El historico recibido esta incompleto");
        }
        contests.sort(Comparator.comparing(Contest::date).thenComparingInt(Contest::number));
        return contests;
    }

    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    static String[] parseCsvLine(String line) throws IOException {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted) {
            throw new IOException("Comillas sin cerrar en el CSV");
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }
}
