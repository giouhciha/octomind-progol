package mx.octomind.progol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

public final class HistoricalRepositoryTest {
    @Test
    public void parsesAndSortsOfficialShape() throws Exception {
        StringBuilder csv = new StringBuilder(
                "NPRODUCTO,CONCURSO,R1,R2,R3,R4,R5,R6,R7,R8,R9,FECHA\n"
        );
        for (int number = 100; number >= 1; number--) {
            csv.append("25,").append(number)
                    .append(",L,E,V,L,E,V,L,E,V,")
                    .append(String.format("%02d/01/2026", (number - 1) % 28 + 1))
                    .append('\n');
        }

        List<Contest> contests = new HistoricalRepository().parseCsv(csv.toString());

        assertEquals(100, contests.size());
        assertEquals(LocalDate.of(2026, 1, 1), contests.get(0).date());
        assertEquals("V", contests.get(0).result(2));
    }

    @Test(expected = IOException.class)
    public void rejectsChangedSchema() throws Exception {
        new HistoricalRepository().parseCsv("CONCURSO,R1\n1,L\n");
    }

    @Test(expected = IOException.class)
    public void rejectsTruncatedHistory() throws Exception {
        new HistoricalRepository().parseCsv(
                "NPRODUCTO,CONCURSO,R1,R2,R3,R4,R5,R6,R7,R8,R9,FECHA\n"
                        + "25,1,L,E,V,L,E,V,L,E,V,01/01/2026\n"
        );
    }
}

