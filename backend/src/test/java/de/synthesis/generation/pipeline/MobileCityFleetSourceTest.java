package de.synthesis.generation.pipeline;
import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MobileCityFleetSourceTest {
    @TempDir Path dir;
    @Test void pinnedKbaPrivateStockIsMappedThroughRealRastattBoundary() throws Exception {
        byte[] data=getClass().getResourceAsStream("/mobilecity/bkg-rastatt.geojson").readAllBytes();
        var source=new MobileCityFleetSource(uri->data); var scenario=new Scenario();
        scenario.mobileCity.useRegionalFleet=true;
        var result=source.resolve(scenario,List.of(zone()),dir,s->{});
        assertThat(result.shares()).containsKey(0);
        assertThat(result.shares().get(0).bev()).isEqualTo(598.0/27035,within(1e-12));
        assertThat(result.shares().get(0).phev()).isEqualTo(381.0/27035,within(1e-12));
        assertThat(result.shares().get(0).municipalities()).containsExactly("08216043");
        assertThat(result.boundarySnapshot()).isEqualTo(data);
        assertThat(result.provenance()).containsEntry("boundarySnapshotFile","fleet-boundaries.geojson");
        var cached=new MobileCityFleetSource(uri->{throw new AssertionError("cache should be reused");})
                .resolve(scenario,List.of(zone()),dir,s->{});
        assertThat(cached.shares()).isEqualTo(result.shares());
    }
    @Test void missingDataDoesNotBecomeZeroAndExplicitZeroFallbackIsPreserved() throws Exception {
        var source=new MobileCityFleetSource(uri->{throw new java.io.IOException("offline");});
        var scenario=new Scenario();scenario.mobileCity.useRegionalFleet=true;
        var unavailable=source.resolve(scenario,List.of(zone()),dir,s->{});
        assertThat(unavailable.shares()).isEmpty();
        assertThat(unavailable.boundarySnapshot()).isNull();
        assertThat(unavailable.provenance().get("sourceFailure")).isEqualTo("offline");
        scenario.mobileCity.electricShare=0.0;
        var explicit=source.resolve(scenario,List.of(zone()),dir,s->{});
        assertThat(explicit.shares().get(0).bev()).isZero();
        assertThat(explicit.shares().get(0).source()).isEqualTo("user-assumption");
    }
    @Test void futureObservationIsNotUsedForHistoricalScenario() throws Exception {
        var scenario=new Scenario(); scenario.referenceYear=2025;scenario.mobileCity.useRegionalFleet=true;
        var r=new MobileCityFleetSource(uri->{throw new AssertionError("Must not fetch");})
                .resolve(scenario,List.of(zone()),dir,s->{});
        assertThat(r.shares()).isEmpty();assertThat(r.provenance().get("sourceFailure").toString()).contains("nach dem Szenariojahr");
    }
    @Test void ambiguousNameMissingCountAndPartialCoverageAreNotSilentlyFilled() throws Exception {
        var data=MobileCityFleetSource.JSON.readTree(getClass().getResourceAsStream("/mobilecity/bkg-rastatt.geojson"));
        var stock=new MobileCityFleetSource.Stock("08216","RASTATT,ST.",27035L,598L,381L,407);
        assertThat(MobileCityFleetSource.map(List.of(zone()),data,List.of(stock,stock))).isEmpty();
        assertThat(MobileCityFleetSource.map(List.of(zone()),data,List.of(new MobileCityFleetSource.Stock("08216","RASTATT",27035L,null,381L,407)))).isEmpty();
        Zone cross=zone();cross.minLon=8.0;cross.maxLon=8.8;
        assertThat(MobileCityFleetSource.map(List.of(cross),data,List.of(stock))).isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode)data).put("numberMatched",100);
        assertThatThrownBy(()->MobileCityFleetSource.validateBoundaries(data)).isInstanceOf(java.io.IOException.class);
    }
    static Zone zone() {Zone z=new Zone();z.index=0;z.id=100001;z.minLat=48.855;z.maxLat=48.86;z.minLon=8.20;z.maxLon=8.205;return z;}
}
