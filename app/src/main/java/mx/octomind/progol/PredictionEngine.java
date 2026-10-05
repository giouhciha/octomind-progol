package mx.octomind.progol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PredictionEngine {
    public static final String MODEL_VERSION = "media-semana-baseline-1";
    private static final char[] STATES = {'L', 'E', 'V'};
    private static final double EPSILON = 1e-15;
    private static final double COMPOSITION_PRIOR_STRENGTH = 20.0;
    private static final double DIVERSITY_WEIGHT = 0.20;
    public static final int PACKAGE_SIZE = 10;

    private final DrawType type;
    public PredictionEngine() { this(DrawType.MS); }
    public PredictionEngine(DrawType type) { this.type = type; }

    public Forecast analyze(List<Contest> history) {
        return analyze(history, new String[type.slots], new String[type.slots]);
    }

    /** Fixed picks are intentionally excluded from the likelihood score. */
    public Forecast analyze(List<Contest> history, String[] fixedStates) {
        return analyze(history, fixedStates, new String[type.slots]);
    }

    /** Fija resultados (favoritos) y/o prohíbe resultados (descartes) por casilla. */
    public Forecast analyze(List<Contest> history, String[] fixedStates, String[] discardedStates) {
        if (history == null || history.size() < 100) {
            throw new IllegalArgumentException("Se requieren al menos 100 concursos");
        }
        for (Contest contest : history) {
            if (DrawType.identify(contest.product(), contest.results().length) != type)
                throw new IllegalArgumentException("Histórico de otro sorteo");
        }
        validateConstraints(fixedStates, discardedStates);
        int sourceContest = history.stream().mapToInt(Contest::number).max().orElseThrow();
        double[] global = globalProbabilities(history);
        double[][] slotProbabilities = new double[type.slots][STATES.length];
        for (int slot = 0; slot < type.slots; slot++) {
            slotProbabilities[slot] = Arrays.copyOf(global, global.length);
        }
        Map<Composition, Double> compositionProbabilities =
                compositionProbabilities(history, global);
        List<Recommendation> recommendations = recommend(
                slotProbabilities,
                compositionProbabilities,
                PACKAGE_SIZE,
                fixedStates,
                discardedStates
        );
        return new Forecast(
                sourceContest,
                sourceContest + 1,
                history.size(),
                slotProbabilities,
                recommendations
        );
    }

    private void validateConstraints(String[] fixedStates, String[] discardedStates) {
        if (fixedStates == null || fixedStates.length != type.slots)
            throw new IllegalArgumentException("Favoritos incompletos");
        if (discardedStates == null || discardedStates.length != type.slots)
            throw new IllegalArgumentException("Descartes incompletos");
        for (int slot = 0; slot < type.slots; slot++) {
            String fixed = fixedStates[slot];
            String discarded = discardedStates[slot];
            if (fixed != null && !Contest.isValidState(fixed))
                throw new IllegalArgumentException("Favorito inválido");
            if (discarded != null) {
                if (!Contest.isValidState(discarded))
                    throw new IllegalArgumentException("Descarte inválido");
                if (fixed != null)
                    throw new IllegalArgumentException("La casilla no puede estar fijada y descartada a la vez");
            }
        }
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
        int slots = history.get(0).results().length;
        double denominator = history.size() + COMPOSITION_PRIOR_STRENGTH;
        for (int local = 0; local <= slots; local++) {
            for (int draw = 0; draw <= slots - local; draw++) {
                int away = slots - local - draw;
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
        return recommend(slotProbabilities, compositionProbabilities, quantity,
                new String[slotProbabilities.length], new String[slotProbabilities.length]);
    }

    static List<Recommendation> recommend(
            double[][] slotProbabilities,
            Map<Composition, Double> compositionProbabilities,
            int quantity,
            String[] fixedStates
    ) {
        return recommend(slotProbabilities, compositionProbabilities, quantity,
                fixedStates, new String[slotProbabilities.length]);
    }

    static List<Recommendation> recommend(
            double[][] slotProbabilities,
            Map<Composition, Double> compositionProbabilities,
            int quantity,
            String[] fixedStates,
            String[] discardedStates
    ) {
        if (fixedStates == null || fixedStates.length != slotProbabilities.length)
            throw new IllegalArgumentException("Favoritos incompletos");
        if (discardedStates == null || discardedStates.length != slotProbabilities.length)
            throw new IllegalArgumentException("Descartes incompletos");
        Map<Composition, Integer> quotas = allocateQuotas(
                compositionProbabilities,
                quantity,
                fixedStates,
                discardedStates
        );
        Map<Composition, List<Candidate>> candidates = new HashMap<>();
        for (Map.Entry<Composition, Integer> entry : quotas.entrySet()) {
            if (entry.getValue() > 0) {
                candidates.put(entry.getKey(), new ArrayList<>());
            }
        }

        int slots = slotProbabilities.length;
        final int maxPerComposition = 1500;
        if (slots <= 9) {
            // MS and Revancha stay exhaustive: the full universe is small enough.
            int universe = (int) Math.pow(STATES.length, slots);
            for (int encoded = 0; encoded < universe; encoded++) {
                char[] sequence = decode(encoded, slots);
                if (!matchesConstraints(sequence, fixedStates, discardedStates)) continue;
                Composition composition = Composition.from(sequence);
                List<Candidate> bucket = candidates.get(composition);
                if (bucket == null) {
                    continue;
                }
                bucket.add(new Candidate(sequence, composition,
                        probabilityOf(sequence, slotProbabilities, fixedStates),
                        maximumRun(sequence)));
            }
        } else {
            // Fin de Semana: 3^14 = 4,782,969 sequences. Generate only the
            // compositions that received a quota and bound the sample per
            // composition instead of walking the whole universe.
            for (Map.Entry<Composition, List<Candidate>> entry : candidates.entrySet()) {
                entry.getValue().addAll(buildCandidates(
                        entry.getKey(), slotProbabilities, fixedStates, discardedStates,
                        maxPerComposition));
            }
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

    private static boolean matchesConstraints(
            char[] sequence, String[] fixedStates, String[] discardedStates) {
        for (int slot = 0; slot < sequence.length; slot++) {
            if (fixedStates[slot] != null && sequence[slot] != fixedStates[slot].charAt(0)) return false;
            if (discardedStates[slot] != null && sequence[slot] == discardedStates[slot].charAt(0)) return false;
        }
        return true;
    }

    private static double probabilityOf(
            char[] sequence,
            double[][] slotProbabilities,
            String[] fixedStates
    ) {
        double probability = 1.0;
        for (int slot = 0; slot < sequence.length; slot++) {
            if (fixedStates[slot] == null)
                probability *= slotProbabilities[slot][stateIndex(sequence[slot])];
        }
        return probability;
    }

    /**
     * Deterministic candidates for one composition. The fixed slots are
     * placed first; the remaining multiset of L/E/V is either fully enumerated
     * or sampled without replacement up to {@code cap}. No JVM randomness is
     * involved, so the 14-match package is reproducible across devices.
     */
    private static List<Candidate> buildCandidates(
            Composition composition,
            double[][] slotProbabilities,
            String[] fixedStates,
            String[] discardedStates,
            int cap
    ) {
        int slots = slotProbabilities.length;
        int[] remaining = {composition.local, composition.draw, composition.away};
        char[] base = new char[slots];
        int[] freePositions = new int[slots];
        int freeCount = 0;
        for (int slot = 0; slot < slots; slot++) {
            String fixed = fixedStates[slot];
            if (fixed == null) {
                freePositions[freeCount++] = slot;
            } else {
                base[slot] = fixed.charAt(0);
                remaining[stateIndex(fixed.charAt(0))]--;
            }
        }
        if (remaining[0] < 0 || remaining[1] < 0 || remaining[2] < 0) {
            return new ArrayList<>();
        }

        int[] free = Arrays.copyOf(freePositions, freeCount);
        String[] fixed = fixedStates.clone();
        String[] discarded = discardedStates.clone();
        List<Candidate> bucket = new ArrayList<>();
        long arrangements = multinomialCount(remaining[0], remaining[1], remaining[2]);
        if (arrangements <= cap) {
            collectArrangements(base, free, 0, remaining, composition,
                    slotProbabilities, fixed, discarded, bucket);
        } else {
            sampleArrangements(base, free, remaining, composition,
                    slotProbabilities, fixed, discarded, cap, bucket);
        }
        // Fixed scan order keeps the greedy step independent of hash iteration.
        bucket.sort(Comparator
                .comparingInt((Candidate candidate) -> candidate.maxRun)
                .thenComparing(candidate -> new String(candidate.sequence)));
        return bucket;
    }

    private static void collectArrangements(
            char[] base,
            int[] free,
            int index,
            int[] remaining,
            Composition composition,
            double[][] slotProbabilities,
            String[] fixedStates,
            String[] discardedStates,
            List<Candidate> bucket
    ) {
        if (index == free.length) {
            char[] sequence = base.clone();
            bucket.add(new Candidate(sequence, composition,
                    probabilityOf(sequence, slotProbabilities, fixedStates),
                    maximumRun(sequence)));
            return;
        }
        int position = free[index];
        String discarded = discardedStates[position];
        for (int state = 0; state < STATES.length; state++) {
            if (remaining[state] == 0) continue;
            if (discarded != null && STATES[state] == discarded.charAt(0)) continue;
            remaining[state]--;
            base[position] = STATES[state];
            collectArrangements(base, free, index + 1, remaining, composition,
                    slotProbabilities, fixedStates, discardedStates, bucket);
            remaining[state]++;
        }
    }

    private static void sampleArrangements(
            char[] base,
            int[] free,
            int[] remaining,
            Composition composition,
            double[][] slotProbabilities,
            String[] fixedStates,
            String[] discardedStates,
            int cap,
            List<Candidate> bucket
    ) {
        char[] pool = new char[free.length];
        int cursor = 0;
        for (int state = 0; state < STATES.length; state++) {
            for (int count = 0; count < remaining[state]; count++) pool[cursor++] = STATES[state];
        }
        long seed = 731L + composition.local * 101L + composition.draw * 17L + composition.away;
        SplitMix64 random = new SplitMix64(seed);
        Set<Long> seen = new HashSet<>();
        int attempts = 0;
        int maximumAttempts = cap * 20;
        while (bucket.size() < cap && attempts++ < maximumAttempts) {
            shuffle(pool, random);
            char[] sequence = base.clone();
            for (int index = 0; index < free.length; index++) sequence[free[index]] = pool[index];
            if (!matchesConstraints(sequence, fixedStates, discardedStates)) continue;
            if (!seen.add(encode(sequence))) continue;
            bucket.add(new Candidate(sequence, composition,
                    probabilityOf(sequence, slotProbabilities, fixedStates),
                    maximumRun(sequence)));
        }
    }

    private static void shuffle(char[] values, SplitMix64 random) {
        for (int index = values.length - 1; index > 0; index--) {
            int swap = random.nextInt(index + 1);
            char temp = values[index];
            values[index] = values[swap];
            values[swap] = temp;
        }
    }

    private static long encode(char[] sequence) {
        long code = 0;
        for (char state : sequence) code = code * STATES.length + stateIndex(state);
        return code;
    }

    /** Small self-contained PRNG; deterministic and independent of java.util.Random. */
    private static final class SplitMix64 {
        private long state;

        private SplitMix64(long seed) {
            this.state = seed;
        }

        private long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            long value = state;
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            return value ^ (value >>> 31);
        }

        private int nextInt(int bound) {
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }
    }

    private static Map<Composition, Integer> allocateQuotas(
            Map<Composition, Double> probabilities,
            int quantity,
            String[] fixedStates,
            String[] discardedStates
    ) {
        Map<Composition, Integer> quotas = new HashMap<>();
        List<Composition> compositions = new ArrayList<>(probabilities.keySet());
        int allocated = 0;
        for (Composition composition : compositions) {
            int quota = (int) Math.min(availableCount(composition, fixedStates, discardedStates),
                    Math.floor(quantity * probabilities.get(composition)));
            quotas.put(composition, quota);
            allocated += quota;
        }
        compositions.sort(
                Comparator.<Composition>comparingDouble(composition -> -(
                        quantity * probabilities.get(composition) - quotas.get(composition)
                )).thenComparingDouble(composition -> -probabilities.get(composition))
                        .thenComparing(Composition::key)
        );
        while (allocated < quantity) {
            boolean progressed = false;
            for (Composition composition : compositions) {
                if (allocated == quantity) break;
                if (quotas.get(composition) >= availableCount(composition, fixedStates, discardedStates)) continue;
                quotas.put(composition, quotas.get(composition) + 1);
                allocated++;
                progressed = true;
            }
            if (!progressed) throw new IllegalArgumentException("Paquete mayor que el universo");
        }
        return quotas;
    }

    private static long availableCount(
            Composition composition, String[] fixedStates, String[] discardedStates) {
        int fixedLocal = 0, fixedDraw = 0, fixedAway = 0;
        for (String state : fixedStates) {
            if ("L".equals(state)) fixedLocal++;
            else if ("E".equals(state)) fixedDraw++;
            else if ("V".equals(state)) fixedAway++;
        }
        int needLocal = composition.local - fixedLocal;
        int needDraw = composition.draw - fixedDraw;
        int needAway = composition.away - fixedAway;
        if (needLocal < 0 || needDraw < 0 || needAway < 0) return 0;

        // Cuenta el numero de asignaciones L/E/V a las casillas libres que
        // respetan los descartes y completan la composicion, por programacion
        // dinamica sobre (locales, empates).
        long[][] dp = new long[needLocal + 1][needDraw + 1];
        dp[0][0] = 1;
        for (int slot = 0; slot < fixedStates.length; slot++) {
            if (fixedStates[slot] != null) continue;
            char discarded = discardedStates[slot] == null ? 0 : discardedStates[slot].charAt(0);
            long[][] next = new long[needLocal + 1][needDraw + 1];
            for (int local = 0; local <= needLocal; local++) {
                for (int draw = 0; draw <= needDraw; draw++) {
                    long ways = dp[local][draw];
                    if (ways == 0) continue;
                    if (discarded != 'L' && local + 1 <= needLocal) next[local + 1][draw] += ways;
                    if (discarded != 'E' && draw + 1 <= needDraw) next[local][draw + 1] += ways;
                    if (discarded != 'V') next[local][draw] += ways;
                }
            }
            dp = next;
        }
        return dp[needLocal][needDraw];
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

    private static char[] decode(int encoded, int slots) {
        char[] sequence = new char[slots];
        int remaining = encoded;
        for (int slot = slots - 1; slot >= 0; slot--) {
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
        return multinomialCount(composition.local, composition.draw, composition.away);
    }

    private static long multinomialCount(int local, int draw, int away) {
        return factorial(local + draw + away)
                / (factorial(local) * factorial(draw) * factorial(away));
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

        public String modelVersion() { return probabilities.length == 14 ? DrawType.WEEKEND.modelVersion() : probabilities.length == 7 ? DrawType.REVANCHA.modelVersion() : MODEL_VERSION; }
        public int sourceContest() { return sourceContest; }
        public int targetContest() { return targetContest; }
        public int historicalContests() { return historicalContests; }
        public double probability(int slot, int state) { return probabilities[slot][state]; }
        public List<Recommendation> recommendations() { return recommendations; }

        /** Promedio esperado de aciertos del boleto bajo las probabilidades del modelo. */
        public double expectedHits(String sequence) {
            if (sequence == null || sequence.length() != probabilities.length) {
                throw new IllegalArgumentException("Pronóstico incompleto");
            }
            double total = 0.0;
            for (int slot = 0; slot < sequence.length(); slot++) {
                total += probabilities[slot][stateIndex(sequence.charAt(slot))];
            }
            return total;
        }

        public JSONObject toJson() {
            try {
                JSONObject root = new JSONObject();
                root.put("sourceContest", sourceContest);
                root.put("targetContest", targetContest);
                root.put("historicalContests", historicalContests);
                root.put("modelVersion", modelVersion());
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
