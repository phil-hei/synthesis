package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.operation.union.UnaryUnionOp;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class MobileCitySurroundingSourceTest {
    @Test void metricBufferFormsEightDisjointSectorsOutsideStudyArea() {
        Scenario.Region region = region();
        var sectors = MobileCitySurroundingSource.sectorGeometries(region, 10);
        var study = MobileCityGeometry.zone(rectangle(region));

        assertThat(sectors).hasSize(8).allMatch(g -> !g.isEmpty() && g.isValid());
        for (var sector : sectors)
            assertThat(MobileCityGeometry.project(sector).intersection(
                    MobileCityGeometry.project(study)).getArea()).isLessThan(1);
        double sum = sectors.stream().mapToDouble(g -> MobileCityGeometry.project(g).getArea()).sum();
        double union = UnaryUnionOp.union(sectors.stream().map(MobileCityGeometry::project).toList()).getArea();
        assertThat(sum).isCloseTo(union, offset(1e-3));
        assertThat(sectors.getFirst().getCentroid().getY()).isGreaterThan(region.maxLat);
        assertThat(sectors.get(2).getCentroid().getX()).isGreaterThan(region.maxLon);
    }

    @Test void proportionalAllocationIsExactAndDeterministic() {
        assertThat(MobileCityRegionalExporter.allocateTotal(803,
                new double[]{10,10,10,10,10,10,10,10}))
                .containsExactly(101,101,101,100,100,100,100,100);
        assertThat(MobileCityRegionalExporter.allocateTotal(7,
                new double[]{1,2,0}))
                .containsExactly(2,5,0);
    }

    private static Scenario.Region region() {
        var r = new Scenario.Region();r.minLat=48.84;r.maxLat=48.88;r.minLon=8.17;r.maxLon=8.23;return r;
    }

    private static Zone rectangle(Scenario.Region r) {
        var z=new Zone();z.minLat=r.minLat;z.maxLat=r.maxLat;z.minLon=r.minLon;z.maxLon=r.maxLon;
        z.centroidLat=(r.minLat+r.maxLat)/2;z.centroidLon=(r.minLon+r.maxLon)/2;return z;
    }
}
