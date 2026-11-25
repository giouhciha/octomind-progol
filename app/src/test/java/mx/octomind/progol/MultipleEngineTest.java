package mx.octomind.progol;

import org.junit.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

public class MultipleEngineTest {
    static List<Contest> history(DrawType type) {
        List<Contest> history = new ArrayList<>();
        for (int i = 1; i <= 110; i++) {
            String[] results = new String[type.slots];
            for (int j = 0; j < results.length; j++) {
                results[j] = new String[]{"L", "E", "V"}[(i + j) % 3];
            }
            history.add(new Contest(type.product, i, LocalDate.of(2020, 1, 1).plusDays(i), results));
        }
        return history;
    }

    @Test public void producesCorrectMarkCountsPerType() {
        // MS (9): 2 triples + 3 dobles + 4 sencillos.
        assertCounts(DrawType.MS, 2, 3, 4);
        // Fin de Semana (14): 3 triples + 2 dobles + 9 sencillos.
        assertCounts(DrawType.WEEKEND, 3, 2, 9);
        // Revancha (7): 2 triples + 3 dobles + 2 sencillos.
        assertCounts(DrawType.REVANCHA, 2, 3, 2);
    }

    private void assertCounts(DrawType type, int expectedTriples, int expectedDobles, int expectedSencillos) {
        String[] marks = new MultipleEngine(type).compute(history(type));
        assertEquals(type.slots, marks.length);
        int triples = 0, dobles = 0, sencillos = 0;
        for (String mark : marks) {
            assertTrue("Marca inválida: " + mark, isValidMark(mark));
            if (mark.length() == 3) triples++;
            else if (mark.length() == 2) dobles++;
            else sencillos++;
        }
        assertEquals("Triples", expectedTriples, triples);
        assertEquals("Dobles", expectedDobles, dobles);
        assertEquals("Sencillos", expectedSencillos, sencillos);
    }

    @Test public void isDeterministic() {
        for (DrawType type : DrawType.values()) {
            List<Contest> h = history(type);
            assertArrayEquals(new MultipleEngine(type).compute(h), new MultipleEngine(type).compute(h));
        }
    }

    private boolean isValidMark(String mark) {
        if (mark == null || mark.isEmpty()) return false;
        boolean[] seen = new boolean[3];
        for (char c : mark.toCharArray()) {
            int idx = "LEV".indexOf(c);
            if (idx < 0 || seen[idx]) return false;
            seen[idx] = true;
        }
        return true;
    }
}
