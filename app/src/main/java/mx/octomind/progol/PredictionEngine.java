package mx.octomind.progol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PredictionEngine {
    public static final String MODEL_VERSION = "media-semana-baseline-1";
    private static final char[] STATES = {'L', 'E', 'V'};
    private static final double EPSILON = 1e-15;
    private static final double COMPOSITION_PRIOR_STRENGTH = 20.0;
    private static final double DIVERSITY_WEIGHT = 0.20;
    private static final int DEFAULT_PACKAGE_SIZE = 20;

    public Forecast analyze(List<Contest> history) {
        if (history == null || history.size() < 100) {
            throw new IllegalArgumentException("Se requieren al menos 100 concursos");
        }
        int sourceContest = history.stream().mapToInt(Contest::number).max().orElseThrow();
        double[] global = globalProbabilities(history);
        double[][] slotProbabilities = new double[Contest.SLOT_COUNT][STATES.length];
        for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
            slotProbabilities[slot] = Arrays.copyOf(global, global.length);
        }
        Map<Composition, Double> compositionProbabilities =
                compositionProbabilities(history, global);
        List<Recommendation> recommendations = recommend(
                slotProbabilities,
                compositionProbabilities,
                DEFAULT_PACKAGE_SIZE
        );
        return new Forecast(
                sourceContest,
                sourceContest + 1,
                history.size(),
                slotProbabilities,
                recommendations
        );
    }

    static double[] globalProbabilities(List<Contest> history) {
        double[] counts = {1.0, 1.0, 1.0};
        for (Contest contest : history) {
            for (String result : contest.results()) {
                counts[stateIndex(result.charAt(0))]++;
            }
        }
        normalize(counts);
        return counts;
    }

    static Map<Composition, Double> compositionProbabilities(
            List<Contest> history,
            double[] global
    ) {
        Map<Composition, Integer> observed = new HashMap<>();
        for (Contest contest : history) {
            observed.merge(Composition.from(contest.results()), 1, Integer::sum);
        }

        Map<Composition, Double> probabilities = new HashMap<>();
        double denominator = history.size() + COMPOSITION_PRIOR_STRENGTH;
        for (int local = 0; local <= Contest.SLOT_COUNT; local++) {
            for (int draw = 0; draw <= Contest.SLOT_COUNT - local; draw++) {
                int away = Contest.SLOT_COUNT - local - draw;
                Composition composition = new Composition(local, draw, away);
                double prior = multinomialProbability(composition, global);
                double probability = (
                        observed.getOrDefault(composition, 0)
                                + COMPOSITION_PRIOR_STRENGTH * prior
                ) / denominator;
                probabilities.put(composition, probability);
            }
        }
        return probabilities;
    }

    static List<Recommendation> recommend(
            double[][] slotProbabilities,
            Map<Composition, Double> compositionProbabilities,
            int quantity
    ) {
        Map<Composition, Integer> quotas = allocateQuotas(
                compositionProbabilities,
                quantity
        );
        Map<Composition, List<Candidate>> candidates = new HashMap<>();
        for (Map.Entry<Composition, Integer> entry : quotas.entrySet()) {
            if (entry.getValue() > 0) {
                candidates.put(entry.getKey(), new ArrayList<>());
            }
        }

        int universe = (int) Math.pow(STATES.length, Contest.SLOT_COUNT);
        for (int encoded = 0; encoded < universe; encoded++) {
            char[] sequence = decode(encoded);
            Composition composition = Composition.from(sequence);
            List<Candidate> bucket = candidates.get(composition);
            if (bucket == null) {
                continue;
            }
            double probability = 1.0;
            for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                probability *= slotProbabilities[slot][stateIndex(sequence[slot])];
            }
            bucket.add(new Candidate(sequence, composition, probability, maximumRun(sequence)));
        }

        List<Composition> tasks = new ArrayList<>();
        List<Map.Entry<Composition, Integer>> quotaEntries = new ArrayList<>(quotas.entrySet());
        quotaEntries.sort(
                Comparator.<Map.Entry<Composition, Integer>>comparingDouble(
                        entry -> -compositionProbabilities.get(entry.getKey())
                ).thenComparing(entry -> entry.getKey().key())
        );
        for (Map.Entry<Composition, Integer> entry : quotaEntries) {
            for (int count = 0; count < entry.getValue(); count++) {
                tasks.add(entry.getKey());
            }
        }

        List<Candidate> selected = new ArrayList<>();
        for (Composition composition : tasks) {
            List<Candidate> bucket = candidates.get(composition);
            int bestIndex = 0;
            double bestUtility = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < bucket.size(); index++) {
                Candidate candidate = bucket.get(index);
                double utility = Math.log(Math.max(candidate.probability, EPSILON));
                if (!selected.isEmpty()) {
                    utility += DIVERSITY_WEIGHT * minimumDistance(candidate.sequence, selected);
                }
                if (utility > bestUtility + 1e-12
                        || (Math.abs(utility - bestUtility) <= 1e-12
                        && isPreferredTie(candidate, bucket.get(bestIndex)))) {
                    bestUtility = utility;
                    bestIndex = index;
                }
            }
            selected.add(bucket.remove(bestIndex));
        }

        List<Recommendation> output = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            Candidate candidate = selected.get(index);
            int distance = index == 0
                    ? 0
                    : minimumDistance(candidate.sequence, selected.subList(0, index));
            output.add(new Recommendation(
                    index + 1,
                    new String(candidate.sequence),
                    candidate.composition.local,
                    candidate.composition.draw,
                    candidate.composition.away,
                    compositionProbabilities.get(candidate.composition),
                    candidate.probability,
                    distance
            ));
        }
        return output;
    }

    private static Map<Composition, Integer> allocateQuotas(
            Map<Composition, Double> probabilities,
            int quantity
    ) {
        Map<Composition, Integer> quotas = new HashMap<>();
        List<Composition> compositions = new ArrayList<>(probabilities.keySet());
        int allocated = 0;
        for (Composition composition : compositions) {
            int quota = (int) Math.floor(quantity * probabilities.get(composition));
            quotas.put(composition, quota);
            allocated += quota;
        }
        compositions.sort(
                Comparator.<Composition>comparingDouble(composition -> -(
                        quantity * probabilities.get(composition) - quotas.get(composition)
                )).thenComparingDouble(composition -> -probabilities.get(composition))
                        .thenComparing(Composition::key)
        );
        for (int index = 0; index < quantity - allocated; index++) {
            Composition composition = compositions.get(index);
            quotas.put(composition, quotas.get(composition) + 1);
        }
        return quotas;
    }

    private static boolean isPreferredTie(Candidate left, Candidate right) {
        if (left.maxRun != right.maxRun) {
            return left.maxRun < right.maxRun;
        }
        return new String(left.sequence).compareTo(new String(right.sequence)) < 0;
    }

    private static int minimumDistance(char[] sequence, List<Candidate> selected) {
        int minimum = Integer.MAX_VALUE;
        for (Candidate candidate : selected) {
            int distance = 0;
            for (int slot = 0; slot < sequence.length; slot++) {
                if (sequence[slot] != candidate.sequence[slot]) {
                    distance++;
                }
            }
            minimum = Math.min(minimum, distance);
        }
        return minimum;
    }

    private static int maximumRun(char[] sequence) {
        int maximum = 0;
        int current = 0;
        char previous = 0;
        for (char state : sequence) {
            current = state == previous ? current + 1 : 1;
            maximum = Math.max(maximum, current);
            previous = state;
        }
        return maximum;
    }

    private static char[] decode(int encoded) {
        char[] sequence = new char[Contest.SLOT_COUNT];
        int remaining = encoded;
        for (int slot = Contest.SLOT_COUNT - 1; slot >= 0; slot--) {
            sequence[slot] = STATES[remaining % STATES.length];
            remaining /= STATES.length;
        }
        return sequence;
    }

    private static int stateIndex(char state) {
        if (state == 'L') {
            return 0;
        }
        if (state == 'E') {
            return 1;
        }
        if (state == 'V') {
            return 2;
        }
        throw new IllegalArgumentException("Estado invalido: " + state);
    }

    private static void normalize(double[] values) {
        double total = Arrays.stream(values).sum();
        for (int index = 0; index < values.length; index++) {
            values[index] /= total;
        }
    }

    private static long multinomialCount(Composition composition) {
        return factorial(Contest.SLOT_COUNT)
                / (factorial(composition.local)
                * factorial(composition.draw)
                * factorial(composition.away));
    }

    private static double multinomialProbability(Composition composition, double[] probability) {
        return multinomialCount(composition)
                * Math.pow(probability[0], composition.local)
                * Math.pow(probability[1], composition.draw)
                * Math.pow(probability[2], composition.away);
    }

    private static long factorial(int value) {
        long result = 1;
        for (int factor = 2; factor <= value; factor++) {
            result *= factor;
        }
        return result;
    }

    private static final class Candidate {
        private final char[] sequence;
        private final Composition composition;
        private final double probability;
        private final int maxRun;

        private Candidate(
                char[] sequence,
                Composition composition,
                double probability,
                int maxRun
        ) {
            this.sequence = sequence;
            this.composition = composition;
            this.probability = probability;
            this.maxRun = maxRun;
        }
    }

    static final class Composition {
        private final int local;
        private final int draw;
        private final int away;

        Composition(int local, int draw, int away) {
            this.local = local;
            this.draw = draw;
            this.away = away;
        }

        static Composition from(String[] results) {
            int local = 0;
            int draw = 0;
            int away = 0;
            for (String result : results) {
                if ("L".equals(result)) {
                    local++;
                } else if ("E".equals(result)) {
                    draw++;
                } else if ("V".equals(result)) {
                    away++;
                }
            }
            return new Composition(local, draw, away);
        }

        static Composition from(char[] results) {
            int local = 0;
            int draw = 0;
            int away = 0;
            for (char result : results) {
                if (result == 'L') {
                    local++;
                } else if (result == 'E') {
                    draw++;
                } else if (result == 'V') {
                    away++;
                }
            }
            return new Composition(local, draw, away);
        }

        String key() {
            return local + "-" + draw + "-" + away;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Composition)) {
                return false;
            }
            Composition that = (Composition) other;
            return local == that.local && draw == that.draw && away == that.away;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new int[]{local, draw, away});
        }
    }

    public static final class Recommendation {
        private final int rank;
        private final String sequence;
        private final int local;
        private final int draw;
        private final int away;
        private final double compositionProbability;
        private final double baseProbability;
        private final int minimumDistance;

        Recommendation(
                int rank,
                String sequence,
                int local,
                int draw,
                int away,
                double compositionProbability,
                double baseProbability,
                int minimumDistance
        ) {
            this.rank = rank;
            this.sequence = sequence;
            this.local = local;
            this.draw = draw;
            this.away = away;
            this.compositionProbability = compositionProbability;
            this.baseProbability = baseProbability;
            this.minimumDistance = minimumDistance;
        }

        public int rank() { return rank; }
        public String sequence() { return sequence; }
        public int local() { return local; }
        public int draw() { return draw; }
        public int away() { return away; }
        public double compositionProbability() { return compositionProbability; }
        public double baseProbability() { return baseProbability; }
        public int minimumDistance() { return minimumDistance; }
    }

    public static final class Forecast {
        private final int sourceContest;
        private final int targetContest;
        private final int historicalContests;
        private final double[][] probabilities;
        private final List<Recommendation> recommendations;

        Forecast(
                int sourceContest,
                int targetContest,
                int historicalContests,
                double[][] probabilities,
                List<Recommendation> recommendations
        ) {
            this.sourceContest = sourceContest;
            this.targetContest = targetContest;
            this.historicalContests = historicalContests;
            this.probabilities = probabilities;
            this.recommendations = List.copyOf(recommendations);
        }

        public int sourceContest() { return sourceContest; }
        public int targetContest() { return targetContest; }
        public int historicalContests() { return historicalContests; }
        public double probability(int slot, int state) { return probabilities[slot][state]; }
        public List<Recommendation> recommendations() { return recommendations; }

        public JSONObject toJson() {
            try {
                JSONObject root = new JSONObject();
                root.put("sourceContest", sourceContest);
                root.put("targetContest", targetContest);
                root.put("historicalContests", historicalContests);
                root.put("modelVersion", MODEL_VERSION);
                JSONArray probabilityRows = new JSONArray();
                for (double[] row : probabilities) {
                    JSONObject item = new JSONObject();
                    item.put("L", row[0]);
                    item.put("E", row[1]);
                    item.put("V", row[2]);
                    probabilityRows.put(item);
                }
                root.put("probabilities", probabilityRows);
                JSONArray combinationRows = new JSONArray();
                for (Recommendation recommendation : recommendations) {
                    JSONObject item = new JSONObject();
                    item.put("rank", recommendation.rank);
                    item.put("sequence", recommendation.sequence);
                    item.put("local", recommendation.local);
                    item.put("draw", recommendation.draw);
                    item.put("away", recommendation.away);
                    item.put("compositionProbability", recommendation.compositionProbability);
                    item.put("baseProbability", recommendation.baseProbability);
                    item.put("minimumDistance", recommendation.minimumDistance);
                    combinationRows.put(item);
                }
                root.put("recommendations", combinationRows);
                return root;
            } catch (JSONException exception) {
                throw new IllegalStateException("No se pudo serializar el pronostico", exception);
            }
        }
    }
}

