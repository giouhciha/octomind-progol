package mx.octomind.progol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PredictionEngineTest {
    @Test
    public void forecastIsNormalizedAndUsesTwentyUniqueBalancedCards() {
        PredictionEngine.Forecast forecast = new PredictionEngine().analyze(history(150));

        assertEquals(151, forecast.targetContest());
        assertEquals(PredictionEngine.PACKAGE_SIZE, forecast.recommendations().size());
        for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
            double total = forecast.probability(slot, 0)
                    + forecast.probability(slot, 1)
                    + forecast.probability(slot, 2);
            assertEquals(1.0, total, 1e-12);
        }

        Set<String> sequences = new HashSet<>();
        for (PredictionEngine.Recommendation recommendation : forecast.recommendations()) {
            assertTrue(sequences.add(recommendation.sequence()));
            assertEquals(
                    Contest.SLOT_COUNT,
                    recommendation.local() + recommendation.draw() + recommendation.away()
            );
        }
        assertFalse(sequences.contains("LLLLLLLLL"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void forecastRejectsInsufficientHistory() {
        new PredictionEngine().analyze(history(99));
    }

    @Test
    public void expectedHitsSumsTheModelProbabilities() {
        PredictionEngine.Forecast forecast = new PredictionEngine().analyze(history(150));
        String sequence = forecast.recommendations().get(0).sequence();
        double expected = 0;
        for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
            int state = sequence.charAt(slot) == 'L' ? 0
                    : sequence.charAt(slot) == 'E' ? 1 : 2;
            expected += forecast.probability(slot, state);
        }
        assertEquals(expected, forecast.expectedHits(sequence), 1e-12);
        assertTrue(expected > 0 && expected <= Contest.SLOT_COUNT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void expectedHitsRejectsIncompleteSequence() {
        new PredictionEngine().analyze(history(150)).expectedHits("LEV");
    }

    private static List<Contest> history(int count) {
        List<Contest> contests = new ArrayList<>();
        String[] states = {"L", "E", "V"};
        for (int index = 0; index < count; index++) {
            String[] results = new String[Contest.SLOT_COUNT];
            for (int slot = 0; slot < Contest.SLOT_COUNT; slot++) {
                results[slot] = states[(index + slot) % states.length];
            }
            contests.add(new Contest(
                    25,
                    index + 1,
                    LocalDate.of(2020, 1, 1).plusDays(index * 7L),
                    results
            ));
        }
        return contests;
    }
}

