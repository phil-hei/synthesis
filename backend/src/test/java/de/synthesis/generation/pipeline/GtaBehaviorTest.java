package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import de.synthesis.generation.pipeline.ActivityGenerator.GtaActivity;
import de.synthesis.generation.pipeline.ActivityGenerator.GtaWeek;
import de.synthesis.generation.pipeline.PopulationGenerator.PersonRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the GTA behaviour source: (1) the deterministic layout of LLM tours into a
 * gapless legacy activity.csv (no LLM needed), and (2) the live path against a running
 * local LLM (Ollama), which self-skips when the endpoint is unreachable.
 */
class GtaBehaviorTest {

    private static PersonRef person(long id, int age, String employment) {
        return new PersonRef(id, id, age, employment, 0, 0, 0);
    }

    @Test
    void layoutTurnsToursIntoGaplessWeek() throws Exception {
        List<PersonRef> persons = List.of(
                person(1, 40, "FULLTIME"),
                person(2, 70, "RETIRED"));
        GtaWeek week = new GtaWeek(
                List.of(List.of(new GtaActivity("WORK", 480), new GtaActivity("SHOPPING", 30)),
                        List.of(new GtaActivity("LEISURE", 90))),
                List.of(List.of(new GtaActivity("SHOPPING", 60))));

        Path file = Files.createTempFile("gta-activity", ".csv");
        try {
            ActivityGenerator generator = new ActivityGenerator(new Random(42));
            long count = generator.generateFromGta(file, persons, p -> week);

            List<String[]> rows = Files.readAllLines(file).stream()
                    .skip(1).filter(l -> !l.isBlank())
                    .map(l -> l.split(";", -1)).toList();
            assertEquals(count, rows.size(), "Rückgabewert = geschriebene Zeilen");
            assertTrue(rows.size() > 14, "mind. zwei Aktivitäten je Tag und Person");

            // Alle Codes gehören zum bekannten LegacyActivityType-Vokabular
            Set<Integer> validCodes = Set.of(1, 31, 32, 33, 6, 7, 12, 41, 42, 51, 52);
            for (String[] r : rows) {
                int start = Integer.parseInt(r[3]);
                int duration = Integer.parseInt(r[4]);
                assertTrue(validCodes.contains(Integer.parseInt(r[1])), "gültiger Aktivitätscode");
                assertTrue(start >= 0 && start < 7 * 1440, "Startzeit in Woche-Minuten");
                assertTrue(duration > 0, "Dauer > 0");
            }
            // Person 1 (Vollzeit) hat WORK (1) und HOME (7) über die Woche
            Set<Integer> p1codes = rows.stream().filter(r -> r[0].equals("1"))
                    .map(r -> Integer.parseInt(r[1])).collect(Collectors.toSet());
            assertTrue(p1codes.contains(1), "Vollzeit-Person hat WORK");
            assertTrue(p1codes.contains(7), "HOME wird eingewebt");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void liveLlmProducesPersonaPlans() throws Exception {
        SynthesisProperties.Llm config = new SynthesisProperties.Llm();
        config.setMaxPersonas(3); // Latenz begrenzen (~30-40 s je Persona)
        GtaBehaviorRunner runner = new GtaBehaviorRunner(config, 42L);
        assumeTrue(runner.isAvailable(), "Lokales LLM (" + config.getEndpoint() + ") nicht erreichbar – übersprungen");

        Path personCsv = Files.createTempFile("gta-person", ".csv");
        Path activityCsv = Files.createTempFile("gta-act", ".csv");
        try {
            Files.writeString(personCsv, """
                    personId;personNumber;householdId;age;employment;gender
                    1;1;1;40;FULLTIME;MALE
                    2;1;2;17;EDUCATION;FEMALE
                    3;1;3;70;RETIRED;FEMALE
                    """);
            List<PersonRef> persons = List.of(
                    person(1, 40, "FULLTIME"), person(2, 17, "EDUCATION"), person(3, 70, "RETIRED"));

            GtaBehaviorRunner.Result result = runner.run(personCsv, msg -> { });
            assertTrue(result.llmCalls() > 0, "mindestens ein LLM-Plan erzeugt");
            assertTrue(result.personsCovered() > 0, "mindestens eine Person abgedeckt");
            Map<Long, GtaWeek> plans = result.plans();
            assertTrue(plans.values().stream().anyMatch(w -> !w.weekdayTours().isEmpty()),
                    "mindestens ein Werktagsplan mit Touren");

            ActivityGenerator generator = new ActivityGenerator(new Random(7));
            long count = generator.generateFromGta(activityCsv, persons, p -> plans.get(p.id()));
            assertTrue(count > 7, "Aktivitäten für die abgedeckten Personen geschrieben");
        } finally {
            Files.deleteIfExists(personCsv);
            Files.deleteIfExists(activityCsv);
        }
    }

    @Test
    void sameSeedIsReproducible() throws Exception {
        SynthesisProperties.Llm config = new SynthesisProperties.Llm();
        config.setMaxPersonas(2);
        assumeTrue(new GtaBehaviorRunner(config, 1L).isAvailable(), "Lokales LLM nicht erreichbar – übersprungen");

        Path personCsv = Files.createTempFile("gta-seed", ".csv");
        try {
            Files.writeString(personCsv, """
                    personId;personNumber;householdId;age;employment;gender
                    1;1;1;40;FULLTIME;MALE
                    2;1;2;70;RETIRED;FEMALE
                    """);
            // Gleicher Seed → identische Pläne (GtaWeek-Records vergleichen tief)
            Map<Long, GtaWeek> a = new GtaBehaviorRunner(config, 99L).run(personCsv, m -> { }).plans();
            Map<Long, GtaWeek> b = new GtaBehaviorRunner(config, 99L).run(personCsv, m -> { }).plans();
            assertEquals(a, b, "gleicher Szenario-Seed muss identische GTA-Pläne liefern");
        } finally {
            Files.deleteIfExists(personCsv);
        }
    }
}
