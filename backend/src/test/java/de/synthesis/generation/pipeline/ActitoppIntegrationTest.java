package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Echter, explizit gegateter Prozess-Smoke-Test gegen den gepinnten actiTopp-Clone. */
class ActitoppIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void pinnedRunnerProducesValidWeeklySchedules() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("SYNTHESIS_RUN_ACTITOPP_TEST")),
                "SYNTHESIS_RUN_ACTITOPP_TEST nicht gesetzt – actiTopp-Prozesstest übersprungen");

        SynthesisProperties.Actitopp config = new SynthesisProperties.Actitopp();
        String project = System.getenv("SYNTHESIS_ACTITOPP_DIR");
        if (project != null && !project.isBlank()) {
            config.setProjectDir(project);
        }
        ActitoppRunner runner = new ActitoppRunner(config);
        assumeTrue(runner.isAvailable(), "Gepinnter actiTopp-Vertrag nicht verfügbar");

        Path persons = tempDir.resolve("person.csv");
        Files.writeString(persons, """
                personId;personNumber;householdId;age;employment;gender
                1;1;1;40;FULLTIME;MALE
                2;1;2;17;EDUCATION;FEMALE
                3;1;3;70;RETIRED;FEMALE
                4;1;4;4;INFANT;MALE
                """);

        ActitoppRunner.Result result = runner.run(persons, tempDir.resolve("work"));
        assertTrue(result.persons() == 4, "Alle Eingabepersonen werden an den Prozess übergeben");
        List<String> rows = Files.readAllLines(result.scheduleCsv());
        assertTrue(rows.size() > 20, "actiTopp muss eine echte Modellwoche erzeugen");
        assertTrue(rows.stream().skip(1).anyMatch(row -> row.startsWith("1;WORK;")));
        assertTrue(rows.stream().skip(1).anyMatch(row -> row.startsWith("2;EDUCATION;")));
        assertFalse(rows.stream().skip(1).anyMatch(row -> row.startsWith("4;")),
                "Kleinkinder werden bewusst vom Backend-Fallback geplant");
        for (String row : rows.subList(1, rows.size())) {
            String[] columns = row.split(";", -1);
            assertTrue(columns.length == 4);
            assertTrue(Long.parseLong(columns[2]) >= 0);
            assertTrue(Long.parseLong(columns[3]) > 0);
        }
    }
}
