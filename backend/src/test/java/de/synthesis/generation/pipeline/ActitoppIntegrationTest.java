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
        assertTrue(runner.isAvailable(), "Separater actiTopp-Adapter nicht verfügbar; scripts/build-actitopp-adapter.sh ausführen");

        Path persons = tempDir.resolve("person.csv");
        Files.writeString(persons, """
                personId;personNumber;householdId;age;employment;gender
                1;1;7;40;FULLTIME;MALE
                2;2;7;17;EDUCATION;FEMALE
                3;1;8;70;RETIRED;FEMALE
                4;3;7;4;INFANT;MALE
                """);
        Path households = tempDir.resolve("household.csv");
        Path cars = tempDir.resolve("car.csv");
        Files.writeString(households, "householdId\n7\n8\n");
        Files.writeString(cars, "ownerId;mainUserId\n7;1\n7;1\n");
        ActitoppRunner.Result result = runner.run(persons, households, cars, "METROPOLITAN", tempDir.resolve("work"));
        assertTrue(result.households() == 2 && result.cars() == 2);
        List<String> contexts = Files.readAllLines(tempDir.resolve("work/actitopp-households.csv"));
        assertTrue(contexts.contains("7;3;1;2;2;METROPOLITAN"));
        assertTrue(contexts.contains("8;1;0;0;0;METROPOLITAN"));
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
