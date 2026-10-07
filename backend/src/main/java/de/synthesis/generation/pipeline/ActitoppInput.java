package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** SyntheSis-owned household contract; no KIT classes are linked into the backend. */
final class ActitoppInput {
    static final Set<String> AREA_TYPES = Set.of("RURAL", "PROVINCIAL", "CITYOUTSKIRT", "METROPOLITAN", "CONURBATION");
    record Counts(long persons, long households, long cars) {}

    static Counts write(Path personCsv, Path householdCsv, Path carCsv, String areaType, Path output) throws IOException {
        if (!AREA_TYPES.contains(areaType)) throw new IOException("Ungültiger actiTopp-Raumtyp: " + areaType);
        var households = new LinkedHashMap<String, List<Map<String, String>>>();
        for (var row : read(householdCsv, "householdId")) {
            String id = row.get("householdId");
            if (households.putIfAbsent(id, new ArrayList<>()) != null)
                throw new IOException("Doppelte Haushalts-ID: " + id);
        }
        var seenPersons = new HashSet<String>();
        for (var row : read(personCsv, "personId", "householdId", "age", "employment", "gender")) {
            String id = row.get("personId"), household = row.get("householdId");
            if (!seenPersons.add(id)) throw new IOException("Doppelte Personen-ID: " + id);
            if (!households.containsKey(household)) throw new IOException("Person " + id + ": Haushalt fehlt: " + household);
            int age = integer(row.get("age"), "Alter");
            if (age < 0 || age > 120) throw new IOException("Ungültiges Alter für Person " + id);
            households.get(household).add(row);
        }
        var cars = new HashMap<String, Integer>();
        long totalCars = 0;
        for (var row : read(carCsv, "ownerId")) {
            String owner = row.get("ownerId");
            if (!households.containsKey(owner)) throw new IOException("Pkw verweist auf fehlenden Haushalt: " + owner);
            cars.merge(owner, 1, Integer::sum); totalCars++;
        }
        try (var writer = Files.newBufferedWriter(output)) {
            writer.write("personId;householdId;age;employment;gender;householdSize;childrenUpTo10;childrenBelow18;numberOfCars;areaType\n");
            for (var entry : households.entrySet()) {
                var members = entry.getValue();
                if (members.isEmpty()) throw new IOException("Haushalt ohne Mitglieder: " + entry.getKey());
                int children10 = 0, children18 = 0;
                for (var m : members) {
                    int age = integer(m.get("age"), "Alter");
                    if (age <= 10) children10++;
                    if (age < 18) children18++;
                }
                for (var m : members) writer.write(String.join(";", m.get("personId"), entry.getKey(), m.get("age"),
                        m.get("employment"), m.get("gender"), String.valueOf(members.size()),
                        String.valueOf(children10), String.valueOf(children18),
                        String.valueOf(cars.getOrDefault(entry.getKey(), 0)), areaType) + "\n");
            }
        }
        return new Counts(seenPersons.size(), households.size(), totalCars);
    }

    private static int integer(String value, String label) throws IOException {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IOException("Ungültiger Wert für " + label + ": " + value, e); }
    }

    private static List<Map<String, String>> read(Path file, String... required) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            String first = reader.readLine();
            if (first == null) throw new IOException("CSV ist leer: " + file);
            var header = Arrays.asList(first.split(";", -1));
            for (String name : required) if (!header.contains(name)) throw new IOException("Spalte " + name + " fehlt: " + file);
            var rows = new ArrayList<Map<String, String>>(); String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                var columns = line.split(";", -1); var row = new LinkedHashMap<String, String>();
                for (String name : required) {
                    int index = header.indexOf(name);
                    if (index >= columns.length || columns[index].isBlank()) throw new IOException("Ungültige CSV-Zeile: " + file);
                    row.put(name, columns[index].trim());
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
