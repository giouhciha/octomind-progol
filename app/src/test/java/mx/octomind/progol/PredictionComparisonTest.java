package mx.octomind.progol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class PredictionComparisonTest {
    @Test
    public void sortsPartialResultsByMatchesDescending() {
        List<String> sequences = List.of(
                "LEVLLLLLL",
                "LELLLLLLL",
                "VVVLLLLLL",
                "LEVEEEEEE"
        );
        String[] partial = {"L", "E", "V", null, null, null, null, null, null};

        List<PredictionComparison.RankedPrediction> ranked =
                PredictionComparison.rank(sequences, partial);

        assertEquals("LEVLLLLLL", ranked.get(0).sequence());
        assertEquals("LEVEEEEEE", ranked.get(1).sequence());
        assertEquals(3, ranked.get(0).matches());
        assertEquals(2, ranked.get(2).matches());
        assertEquals(1, ranked.get(3).matches());
    }

    @Test
    public void preservesOriginalOrderWhenMatchesTie() {
        String[] empty = new String[Contest.SLOT_COUNT];
        List<PredictionComparison.RankedPrediction> ranked = PredictionComparison.rank(
                List.of("LLLLLLLLL", "EEEEEEEEE"), empty
        );
        assertEquals(1, ranked.get(0).originalRank());
        assertEquals(2, ranked.get(1).originalRank());
    }
}
