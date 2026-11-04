package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyCarLocationTest {
    @TempDir Path dir;
    @Test void generatedAndLegacyCarsStartAtTheirOwnHouseholdsHomeWithCorrectZoneSpace()throws Exception {
        List<Zone> zones=new ArrayList<>();
        for(int i=0;i<2;i++) {
            var zone=new Zone();zone.index=i;zone.id=100001+i;zone.minLon=8+i*.1;zone.maxLon=zone.minLon+.02;
            zone.minLat=48.8;zone.maxLat=48.82;zone.centroidLon=zone.minLon+.01;zone.centroidLat=48.81;zone.population=80;zones.add(zone);
        }
        var scenario=new Scenario();scenario.population.useKitPopulation=false;scenario.population.carsPerHousehold=2;
        new PopulationGenerator(zones,new Random(42)).generate(dir,scenario);
        Map<String,String[]> homes=new HashMap<>();
        for(String line:Files.readAllLines(dir.resolve("household.csv")).subList(1,Files.readAllLines(dir.resolve("household.csv")).size())) {
            var c=line.split(";",-1);homes.put(c[1],c);
        }
        var cars=Files.readAllLines(dir.resolve("car.csv"));assertTrue(cars.size()>10);
        for(String line:cars.subList(1,cars.size())) {
            var c=line.split(";",-1);var home=homes.get(c[0]);String xy=home[4].substring(1,home[4].indexOf(':'));
            assertEquals(xy+",ZoneId(value="+home[3]+")",c[6]);
        }
        new LegacyExportGenerator(zones,new Random(42)).convertCars(dir,dir.resolve("legacy-cars.csv"));
        var legacy=Files.readAllLines(dir.resolve("legacy-cars.csv"));assertEquals(cars.size(),legacy.size());
        for(String line:legacy.subList(1,legacy.size())) {
            String[] c=line.replace("\"","").split(";",-1);var home=homes.get(c[0]);
            assertEquals(home[4],c[6]);assertEquals(Long.parseLong(home[3])-100001,Long.parseLong(c[5]));
        }
    }
    @Test void missingHouseholdCannotSilentlyDiscardACar()throws Exception {
        Files.writeString(dir.resolve("household.csv"),"householdNumber;householdId;year;homeZone;homeLocation\n");
        Files.writeString(dir.resolve("car.csv"),"ownerId;mainUserId;personalUserId;carType\n99;2;2;conventional\n");
        var failure=assertThrows(java.io.IOException.class,()->new LegacyExportGenerator(List.of(),new Random(42)).convertCars(dir,dir.resolve("legacy-cars.csv")));
        assertTrue(failure.getMessage().contains("99"));
    }
}
