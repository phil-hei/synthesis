package de.synthesis.generation.pipeline;

import de.synthesis.scenario.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LogisticsGeneratorTest {

    @Test
    void writesVersionedParcelDemandContractWithSafeReturnsDefault(@TempDir Path tempDir)
            throws Exception {
        Scenario.Logistics logistics = new Scenario.Logistics();
        logistics.parcelsPerPersonWeek = 1.25;
        logistics.parcelStdDev = 0.35;
        logistics.maxParcelsPerPerson = 7;
        logistics.shareHome = 0.7;
        logistics.shareWork = 0.2;
        logistics.sharePackstation = 0.1;

        Path config = tempDir.resolve("parcel-demand-config.yaml");
        LogisticsGenerator.writeParcelDemandConfig(config, logistics);
        String yaml = Files.readString(config);

        assertThat(yaml)
                .contains("contractVersion: " + LogisticsGenerator.PARCEL_DEMAND_CONTRACT)
                .contains("mean: 1.2500")
                .contains("stdDev: 0.3500")
                .contains("max: 7")
                .contains("HOME: 0.7000")
                .contains("WORK: 0.2000")
                .contains("PACKSTATION: 0.1000")
                .contains("returns:\n  enabled: false")
                .contains("shareOfPrivateOrders: 0.0");
    }
}
