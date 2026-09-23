package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MobileCityChargingSourceTest {
    @Test void assignsPublicChargingPointsAndLeavesFutureYearsToMobileCity() {
        Scenario scenario=scenario();scenario.referenceYear=2024;scenario.mobileCity.useRegionalCharging=true;
        var data=new MobileCityChargingSource.Dataset(List.of(
                point("A",2019,49.005,8.005,2,"Normalladeeinrichtung"),
                point("B",2023,49.006,8.006,3,"Schnellladeeinrichtung"),
                point("C",2020,49.005,8.015,4,"Normalladeeinrichtung"),
                point("boundary",2022,49.005,8.010,1,"Normalladeeinrichtung")),
                Map.of("referenceDate","2026-09-01","rows",4,"sourceSha256","fixture"));
        var districts=List.of(
                new MobileCityChargingSource.District("D1",rectangle(8.0,49.0,8.01,49.01),1000),
                new MobileCityChargingSource.District("D2",rectangle(8.01,49.0,8.02,49.01),2000));

        var result=new MobileCityChargingSource(()->data).resolve(scenario,districts,
                List.of(2021,2022,2023,2024,2025));

        assertThat(result.status()).isEqualTo("observed");
        assertThat(result.value("D1",2021)).isEqualTo(2);
        assertThat(result.value("D1",2022)).isEqualTo(3); // boundary tie deterministically uses D1
        assertThat(result.value("D1",2024)).isEqualTo(6);
        assertThat(result.value("D2",2024)).isEqualTo(4);
        assertThat(result.value("D1",2025)).isNull();
        assertThat(result.effectivePoints()).isEqualTo(10);
        assertThat(result.provenance()).containsEntry("districtBoundaryTies",1);
        assertThat(new String(result.regionalSnapshot())).contains("facilityId;", "boundary", ";D1");
    }

    @Test void usesExplicitPerCapitaFallbackWhenAutomaticSourceFails() {
        Scenario scenario=scenario();scenario.mobileCity.useRegionalCharging=true;
        scenario.mobileCity.chargingPointsPer1000=1.5;
        var districts=List.of(
                new MobileCityChargingSource.District("D1",rectangle(8,49,8.01,49.01),1000),
                new MobileCityChargingSource.District("D2",rectangle(8.01,49,8.02,49.01),2000));

        var result=new MobileCityChargingSource(()->{throw new IOException("fixture unavailable");})
                .resolve(scenario,districts,List.of(2024,2026,2027));

        assertThat(result.status()).isEqualTo("assumption");
        assertThat(result.resolution()).isEqualTo("fallback");
        assertThat(result.value("D1",2026)).isEqualTo(2);
        assertThat(result.value("D2",2026)).isEqualTo(3);
        assertThat(result.value("D1",2027)).isNull();
        assertThat(result.provenance()).containsEntry("sourceFailure","fixture unavailable");
    }

    @Test void carriesSnapshotOnlyToFirstModelYearWhenScenarioStartsLater() {
        Scenario scenario=scenario();scenario.referenceYear=2030;scenario.mobileCity.useRegionalCharging=true;
        var data=new MobileCityChargingSource.Dataset(
                List.of(point("A",2024,49.005,8.005,2,"Normalladeeinrichtung")),
                Map.of("referenceDate","2026-09-01","rows",1,"sourceSha256","fixture"));
        var districts=List.of(new MobileCityChargingSource.District(
                "D1",rectangle(8,49,8.01,49.01),1000));

        var result=new MobileCityChargingSource(()->data).resolve(scenario,districts,
                List.of(2028,2029,2030));

        assertThat(result.status()).isEqualTo("derived");
        assertThat(result.value("D1",2028)).isEqualTo(2);
        assertThat(result.value("D1",2029)).isNull();
        assertThat(result.provenance()).containsEntry("carryForwardYears",2);
    }

    @Test void bundledSnapshotHasVerifiedPublishedTotals() throws Exception {
        var data=MobileCityChargingSource.loadBundled();
        assertThat(data.points()).hasSize(116422);
        assertThat(data.points().stream().mapToInt(MobileCityChargingSource.ChargingPoint::chargingPoints).sum())
                .isEqualTo(209096);
        assertThat(data.metadata()).containsEntry("referenceDate","2026-09-01")
                .containsEntry("invalidRows",1);
    }

    private static Scenario scenario(){var s=new Scenario();s.region=new Scenario.Region();s.region.minLat=48.9;s.region.maxLat=49.1;s.region.minLon=7.9;s.region.maxLon=8.1;return s;}
    private static MobileCityChargingSource.ChargingPoint point(String id,int year,double lat,double lon,int count,String type){return new MobileCityChargingSource.ChargingPoint(id, LocalDate.of(year,1,1),lat,lon,count,22,type);}
    private static org.locationtech.jts.geom.Polygon rectangle(double minLon,double minLat,double maxLon,double maxLat){return MobileCityGeometry.FACTORY.createPolygon(new Coordinate[]{new Coordinate(minLon,minLat),new Coordinate(maxLon,minLat),new Coordinate(maxLon,maxLat),new Coordinate(minLon,maxLat),new Coordinate(minLon,minLat)});}
}
