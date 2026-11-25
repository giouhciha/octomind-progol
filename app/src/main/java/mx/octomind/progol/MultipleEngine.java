package mx.octomind.progol;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Motor exclusivo para la quiniela de opciones múltiples (dobles/triples).
 *
 * <p>No toca el motor v1. Usa señales propias:</p>
 * <ul>
 *   <li><b>Recencia</b>: los concursos recientes pesan más (decaimiento exponencial).</li>
 *   <li><b>Localía</b>: prior que favorece al Local.</li>
 *   <li><b>Entropía</b>: los partidos con distribución L/E/V más pareja son los más
 *       inciertos y reciben triples/dobles.</li>
 * </ul>
 */
public final class MultipleEngine {
    private static final double RECENCY_DECAY = 0.97;
    private static final double[] HOME_PRIOR = {3.0, 2.0, 2.0}; // L, E, V
    private static final char[] STATE_CHARS = {'L', 'E', 'V'};

    private final DrawType type;

    public MultipleEngine(DrawType type) {
        this.type = type;
    }

    /** Devuelve las marcas por partido (p.ej. "LEV", "LE", "V"). */
    public String[] compute(List<Contest> history) {
        int slots = type.slots;
        double[][] probs = slotProbabilities(history);
        double[] uncertainty = entropy(probs);

        int triples = type == DrawType.WEEKEND ? 3 : 2;
        int dobles = type == DrawType.WEEKEND ? 2 : 3;

        Integer[] order = new Integer[slots];
        for (int i = 0; i < slots; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(uncertainty[b], uncertainty[a]));

        String[] marks = new String[slots];
        for (int rank = 0; rank < slots; rank++) {
            int slot = order[rank];
            if (rank < triples) {
                marks[slot] = "LEV";
            } else if (rank < triples + dobles) {
                marks[slot] = topStates(probs[slot], 2);
            } else {
                marks[slot] = topStates(probs[slot], 1);
            }
        }
        return marks;
    }

    private double[][] slotProbabilities(List<Contest> history) {
        int slots = type.slots;
        double[] local = new double[slots];
        double[] draw = new double[slots];
        double[] away = new double[slots];
        for (int s = 0; s < slots; s++) {
            local[s] = HOME_PRIOR[0];
            draw[s] = HOME_PRIOR[1];
            away[s] = HOME_PRIOR[2];
        }
        double weight = 1.0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Contest contest = history.get(i);
            for (int s = 0; s < slots; s++) {
                String result = contest.result(s);
                if ("L".equals(result)) {
                    local[s] += weight;
                } else if ("E".equals(result)) {
                    draw[s] += weight;
                } else {
                    away[s] += weight;
                }
            }
            weight *= RECENCY_DECAY;
        }
        double[][] probs = new double[slots][3];
        for (int s = 0; s < slots; s++) {
            double total = local[s] + draw[s] + away[s];
            probs[s][0] = local[s] / total;
            probs[s][1] = draw[s] / total;
            probs[s][2] = away[s] / total;
        }
        return probs;
    }

    private double[] entropy(double[][] probs) {
        double[] result = new double[probs.length];
        for (int s = 0; s < probs.length; s++) {
            double h = 0.0;
            for (double p : probs[s]) {
                if (p > 0) h -= p * Math.log(p);
            }
            result[s] = h;
        }
        return result;
    }

    private String topStates(double[] probs, int count) {
        Integer[] idx = {0, 1, 2};
        Arrays.sort(idx, (a, b) -> Double.compare(probs[b], probs[a]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(STATE_CHARS[idx[i]]);
        return sb.toString();
    }
}
