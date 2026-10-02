package mx.octomind.progol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PredictionComparison {
    private PredictionComparison() {}

    public static List<RankedPrediction> rank(List<String> sequences, String[] results) {
        if (results == null || results.length == 0) {
            throw new IllegalArgumentException("Se requieren nueve posiciones");
        }
        List<RankedPrediction> ranked = new ArrayList<>();
        for (int index = 0; index < sequences.size(); index++) {
            String sequence = sequences.get(index);
            if (sequence.length() != results.length) {
                throw new IllegalArgumentException("Pronostico incompleto");
            }
            int matches = 0;
            for (int slot = 0; slot < results.length; slot++) {
                if (results[slot] != null
                        && results[slot].charAt(0) == sequence.charAt(slot)) {
                    matches++;
                }
            }
            ranked.add(new RankedPrediction(index + 1, sequence, matches));
        }
        ranked.sort(
                Comparator.comparingInt(RankedPrediction::matches).reversed()
                        .thenComparingInt(RankedPrediction::originalRank)
        );
        return List.copyOf(ranked);
    }

    public record RankedPrediction(int originalRank, String sequence, int matches) {}
}
