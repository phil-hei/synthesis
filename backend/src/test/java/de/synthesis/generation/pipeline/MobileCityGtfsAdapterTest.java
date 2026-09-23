package de.synthesis.generation.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MobileCityGtfsAdapterTest {
    @TempDir Path dir;

    @Test void mapsRepresentativeWorkdayFrequenciesModesTransfersAndDistrictAccess() throws Exception {
        Path feed=fixture(dir.resolve("feed.zip"));
        var source=new MobileCityGtfsAdapter.Feed(feed,"fixture","upload",
                new GtfsCropper.Result(4,3,4,2));
        var districts=List.of(new MobileCityGtfsAdapter.District("D1",49.0000,8.0000),
                new MobileCityGtfsAdapter.District("D2",49.0000,8.0300));

        var result=new MobileCityGtfsAdapter().convert(source,districts);

        assertThat(result.serviceDate().getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(result.feedStartDate().toString()).isEqualTo("2026-09-01");
        assertThat(result.feedEndDate().toString()).isEqualTo("2026-09-30");
        assertThat(result.scheduledTrips()).isEqualTo(6); // 4 frequency-based bus + reverse bus + rail
        assertThat(result.tripsByRouteType()).containsExactlyInAnyOrderEntriesOf(Map.of(2,1,3,5));
        assertThat(result.nodes().stream().filter(n->n.refNodeTrue()==1)).hasSize(2);
        assertThat(result.nodes()).allMatch(n->List.of("D1","D2").contains(n.districtId()));
        assertThat(result.accessLinks()).isEqualTo(2);
        assertThat(result.transferLinks()).isEqualTo(1);
        assertThat(result.links()).anyMatch(l->l.mode()==4 && l.frequency()==4 && l.name().equals("B1"));
        assertThat(result.links()).anyMatch(l->l.mode()==1 && l.name().equals("R1"));
        assertThat(result.links()).anyMatch(l->l.mode()==9 && l.frequency()==MobileCityGtfsAdapter.WALK_FREQUENCY);
        assertThat(result.peakBusVehicles()).isGreaterThan(0);
        assertThat(result.peakTrainVehicles()).isEqualTo(1);
        assertThat(result.provenance(source)).containsEntry("serviceDate",result.serviceDate().toString());
    }

    @Test void fullDayCropKeepsFrequencyDefinitions() throws Exception {
        Path source=fixture(dir.resolve("source.zip")),cropped=dir.resolve("cropped.zip"),second=dir.resolve("cropped-second.zip");
        new GtfsCropper(48.99,7.99,49.01,8.04).crop(source,cropped);
        new GtfsCropper(48.99,7.99,49.01,8.04).crop(source,second);
        assertThat(Files.readAllBytes(second)).isEqualTo(Files.readAllBytes(cropped));
        try(var zip=new ZipFile(cropped.toFile())) {
            assertThat(zip.getEntry("frequencies.txt")).isNotNull();
            assertThat(new String(zip.getInputStream(zip.getEntry("frequencies.txt")).readAllBytes(),StandardCharsets.UTF_8))
                    .contains("bus-out,06:00:00,07:00:00,900");
            assertThat(new String(zip.getInputStream(zip.getEntry("stops.txt")).readAllBytes(),StandardCharsets.UTF_8))
                    .contains("PA,Alpha station");
        }
    }

    static Path fixture(Path path) throws Exception {
        Map<String,String> files=Map.ofEntries(
                Map.entry("feed_info.txt","feed_publisher_name,feed_publisher_url,feed_lang,feed_start_date,feed_end_date\nFixture,https://example.test,de,20260901,20260930\n"),
                Map.entry("agency.txt","agency_id,agency_name,agency_url,agency_timezone\na,Fixture,https://example.test,Europe/Berlin\n"),
                Map.entry("calendar.txt","service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nwork,1,1,1,1,1,0,0,20260901,20260930\n"),
                Map.entry("calendar_dates.txt","service_id,date,exception_type\nwork,20260909,2\n"),
                Map.entry("routes.txt","route_id,agency_id,route_short_name,route_long_name,route_type\nbus,a,B1,Bus,3\nrail,a,R1,Rail,2\n"),
                Map.entry("trips.txt","route_id,service_id,trip_id,direction_id\nbus,work,bus-out,0\nbus,work,bus-back,1\nrail,work,rail,0\n"),
                Map.entry("stops.txt","stop_id,stop_name,stop_lat,stop_lon,parent_station\nPA,Alpha station,49.0000,8.0000,\nA,Alpha platform,49.0000,8.0000,PA\nB,Beta,49.0000,8.0100,\nC,Gamma,49.0000,8.0200,\nD,Delta,49.0000,8.0300,\n"),
                Map.entry("stop_times.txt","trip_id,arrival_time,departure_time,stop_id,stop_sequence\nbus-out,06:00:00,06:00:00,A,1\nbus-out,06:05:00,06:05:00,B,2\nbus-out,06:10:00,06:10:00,C,3\nbus-back,07:00:00,07:00:00,C,1\nbus-back,07:05:00,07:05:00,B,2\nbus-back,07:10:00,07:10:00,A,3\nrail,08:00:00,08:00:00,A,1\nrail,08:08:00,08:08:00,C,2\n"),
                Map.entry("frequencies.txt","trip_id,start_time,end_time,headway_secs\nbus-out,06:00:00,07:00:00,900\n"),
                Map.entry("transfers.txt","from_stop_id,to_stop_id,transfer_type,min_transfer_time\nC,D,2,180\n")
        );
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))) {
            for(var entry:files.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        return path;
    }
}
