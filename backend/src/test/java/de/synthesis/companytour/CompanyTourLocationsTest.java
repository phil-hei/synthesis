package de.synthesis.companytour;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompanyTourLocationsTest {
    @Test void missingHouseNumberUsesNearestOnSameStreetAndPostcodeWithVisibleProvenance() {
        var reader=new TourTableReader();var mapper=new CompanyTourMapper();
        var table=reader.read(CompanyTourImportTest.CSV.getBytes(StandardCharsets.UTF_8),"plan.csv").getFirst();
        var draft=mapper.apply(table,mapper.suggest(mapper.profiles(List.of(table)).getFirst()),"source");
        var lookup=CompanyTourLocations.match(("street;postalCode;city;lon;lat\n"
            +"Beispielweg 5;01234;Teststadt;7.5;51.5\n"
            +"Beispielweg 2;01234;Teststadt;7.6;51.6\n"
            +"Beispielweg 1;99999;Teststadt;9;52\n"
            +"Beispielweg 1;01234;Andere Stadt;9;52\n"
            +"Testweg 2;01234;Teststadt;7.7;51.7\n").getBytes(StandardCharsets.UTF_8),"addresses.csv",draft);
        assertEquals(2,lookup.matched());assertEquals(0,lookup.missing());
        var approximate=lookup.matches().getFirst().candidates().getFirst();
        assertEquals(7.6,approximate.lon());assertTrue(approximate.provenance().contains("approximate-nearest-house-number"));
        assertFalse(lookup.matches().getLast().candidates().getFirst().provenance().contains("approximate"));
    }
}
