package mx.octomind.progol;

import org.junit.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

public class MultiDrawTest {
    static List<Contest> history(DrawType type, boolean uniform) {
        List<Contest> history = new ArrayList<>();
        for (int i = 1; i <= 110; i++) {
            String[] results = new String[type.slots];
            for (int j = 0; j < results.length; j++) results[j] = uniform ? "L" : new String[]{"L","E","V"}[(i+j)%3];
            history.add(new Contest(type.product, i, LocalDate.of(2020,1,1).plusDays(i), results));
        }
        return history;
    }

    @Test public void allEnginesProduceIndependentReproduciblePackages() {
        for (DrawType type : DrawType.values()) {
            PredictionEngine engine = new PredictionEngine(type);
            PredictionEngine.Forecast first = engine.analyze(history(type, false));
            PredictionEngine.Forecast second = engine.analyze(history(type, false));
            assertEquals(111, first.targetContest());
            assertEquals(type.modelVersion(), first.modelVersion());
            assertEquals(20, first.recommendations().size());
            Set<String> unique = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                String sequence = first.recommendations().get(i).sequence();
                assertEquals(type.slots, sequence.length());
                assertTrue(unique.add(sequence));
                assertEquals(sequence, second.recommendations().get(i).sequence());
            }
        }
    }
    @Test(expected = IllegalArgumentException.class)
    public void principalCannotConsumeRevanchaDespiteSameProductId() {
        new PredictionEngine(DrawType.WEEKEND).analyze(history(DrawType.REVANCHA, false));
    }
    @Test public void extremeCompositionsCannotExhaustCandidateBucket() {
        assertEquals(20, new PredictionEngine(DrawType.REVANCHA)
                .analyze(history(DrawType.REVANCHA, true)).recommendations().size());
    }
    @Test public void parsersAcceptTheirOwnSchemasAndRejectOthers() throws Exception {
        for (DrawType type : DrawType.values()) {
            StringBuilder csv = new StringBuilder("NPRODUCTO,CONCURSO");
            for (int i = 1; i <= type.slots; i++) csv.append(",R").append(i);
            if (type == DrawType.WEEKEND) csv.append(",BOLSA");
            csv.append(",FECHA\n");
            for (int i = 1; i <= 100; i++) {
                csv.append(type.product).append(",").append(i);
                for (int j = 0; j < type.slots; j++) csv.append(",L");
                if (type == DrawType.WEEKEND) csv.append(",5000000");
                csv.append(",01/01/2026\n");
            }
            assertEquals(100, new HistoricalRepository(type).parseCsv(csv.toString()).size());
            DrawType other = type == DrawType.MS ? DrawType.WEEKEND : DrawType.MS;
            try { new HistoricalRepository(other).parseCsv(csv.toString()); fail(); }
            catch (java.io.IOException expected) { }
        }
    }
    @Test public void comparisonSupportsFourteenAndSevenWithPartialResults() {
        for (int length : new int[]{7,14}) {
            String[] partial = new String[length];
            partial[0] = "V"; partial[length-1] = "L";
            List<PredictionComparison.RankedPrediction> ranked = PredictionComparison.rank(
                    List.of("L".repeat(length), "V" + "L".repeat(length-1)), partial);
            assertEquals(2, ranked.get(0).matches());
            assertEquals(1, ranked.get(1).matches());
        }
    }

    @Test public void fixedFavoritesAppearInEveryTicketAndDoNotBreakPackageSize() {
        for (DrawType type : DrawType.values()) {
            String[] favorites = new String[type.slots];
            favorites[0] = "L";
            if (type != DrawType.REVANCHA) favorites[type.slots - 1] = "V";
            PredictionEngine.Forecast forecast = new PredictionEngine(type).analyze(history(type, false), favorites);
            assertEquals(20, forecast.recommendations().size());
            for (PredictionEngine.Recommendation ticket : forecast.recommendations()) {
                assertEquals('L', ticket.sequence().charAt(0));
                if (type != DrawType.REVANCHA) assertEquals('V', ticket.sequence().charAt(type.slots - 1));
            }
        }
    }
}
