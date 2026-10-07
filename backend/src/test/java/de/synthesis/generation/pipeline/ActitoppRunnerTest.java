package de.synthesis.generation.pipeline;

import de.synthesis.SynthesisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ActitoppRunnerTest {
    @TempDir Path tempDir;
    private ActitoppRunner runner(Path project) {
        var config = new SynthesisProperties.Actitopp(); config.setProjectDir(project.toString());
        return new ActitoppRunner(config);
    }
    @Test void availabilityRejectsLegacyContractAndRequiresSeparateLauncher() throws Exception {
        var runner = runner(tempDir);
        assertFalse(runner.isAvailable());
        Files.writeString(tempDir.resolve("synthesis-contract.properties"), "contractVersion=synthesis-actitopp-v1\n");
        assertThrows(IOException.class, runner::verifyContract);
        Files.writeString(tempDir.resolve("synthesis-contract.properties"), "contractVersion=" + ActitoppRunner.CONTRACT + "\n");
        assertFalse(runner.isAvailable());
        Files.createDirectories(tempDir.resolve("bin"));
        Files.writeString(tempDir.resolve("bin/synthesis-actitopp-adapter"), "#!/bin/sh\n");
        assertTrue(runner.isAvailable());
    }
    @Test void joinsActualFamilyAndCarsIncludingInfants() throws Exception {
        Files.writeString(tempDir.resolve("household.csv"), "householdId\n7\n8\n");
        Files.writeString(tempDir.resolve("person.csv"), "personId;householdId;age;employment;gender\n1;7;38;FULLTIME;MALE\n2;7;36;PARTTIME;FEMALE\n3;7;8;EDUCATION;MALE\n4;7;4;INFANT;FEMALE\n5;8;70;RETIRED;FEMALE\n");
        Files.writeString(tempDir.resolve("car.csv"), "ownerId;mainUserId\n7;1\n7;2\n");
        var counts = ActitoppInput.write(tempDir.resolve("person.csv"), tempDir.resolve("household.csv"), tempDir.resolve("car.csv"), "METROPOLITAN", tempDir.resolve("input.csv"));
        assertEquals(5, counts.persons()); assertEquals(2, counts.households()); assertEquals(2, counts.cars());
        var rows = Files.readAllLines(tempDir.resolve("input.csv"));
        assertTrue(rows.contains("1;7;38;FULLTIME;MALE;4;2;2;2;METROPOLITAN"));
        assertTrue(rows.contains("4;7;4;INFANT;FEMALE;4;2;2;2;METROPOLITAN"));
        assertTrue(rows.contains("5;8;70;RETIRED;FEMALE;1;0;0;0;METROPOLITAN"));
        Files.writeString(tempDir.resolve("car.csv"), "ownerId\n999\n");
        assertThrows(IOException.class, () -> ActitoppInput.write(tempDir.resolve("person.csv"), tempDir.resolve("household.csv"), tempDir.resolve("car.csv"), "METROPOLITAN", tempDir.resolve("input.csv")));
    }
    @Test void rejectsOrphanAndDuplicatePersons() throws Exception {
        Files.writeString(tempDir.resolve("household.csv"), "householdId\n7\n");
        Files.writeString(tempDir.resolve("car.csv"), "ownerId\n");
        for (String rows : new String[]{"1;999;38;FULLTIME;MALE\n", "1;7;38;FULLTIME;MALE\n1;7;36;PARTTIME;FEMALE\n"}) {
            Files.writeString(tempDir.resolve("person.csv"), "personId;householdId;age;employment;gender\n" + rows);
            assertThrows(IOException.class, () -> ActitoppInput.write(tempDir.resolve("person.csv"), tempDir.resolve("household.csv"), tempDir.resolve("car.csv"), "METROPOLITAN", tempDir.resolve("input.csv")));
        }
    }
}
