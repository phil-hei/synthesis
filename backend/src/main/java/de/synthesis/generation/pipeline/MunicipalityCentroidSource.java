package de.synthesis.generation.pipeline;

import java.io.IOException;
import java.util.Map;

@FunctionalInterface
public interface MunicipalityCentroidSource {

    record Point(String ags, double lat, double lon) {
    }

    Map<String, Point> fetch() throws IOException;
}
