package mx.octomind.progol;

import java.time.LocalDate;
import java.util.Arrays;

public final class Contest {
    public static final int SLOT_COUNT = 9;

    private final int product;
    private final int number;
    private final LocalDate date;
    private final String[] results;

    public Contest(int product, int number, LocalDate date, String[] results) {
        if (product != 25) {
            throw new IllegalArgumentException("Producto inesperado: " + product);
        }
        if (number < 1) {
            throw new IllegalArgumentException("Numero de concurso invalido");
        }
        if (date == null) {
            throw new IllegalArgumentException("La fecha es obligatoria");
        }
        if (results == null || results.length != SLOT_COUNT) {
            throw new IllegalArgumentException("Se requieren nueve resultados");
        }
        for (String result : results) {
            if (!isValidState(result)) {
                throw new IllegalArgumentException("Estado invalido: " + result);
            }
        }
        this.product = product;
        this.number = number;
        this.date = date;
        this.results = Arrays.copyOf(results, results.length);
    }

    public static boolean isValidState(String state) {
        return "L".equals(state) || "E".equals(state) || "V".equals(state);
    }

    public int product() {
        return product;
    }

    public int number() {
        return number;
    }

    public LocalDate date() {
        return date;
    }

    public String result(int slot) {
        return results[slot];
    }

    public String[] results() {
        return Arrays.copyOf(results, results.length);
    }
}

