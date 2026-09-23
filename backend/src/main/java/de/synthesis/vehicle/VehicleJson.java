package de.synthesis.vehicle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Strict, allowlisted polymorphism; external JSON never names a Java class. */
public final class VehicleJson {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .build();
    private VehicleJson() { }
    public static VehicleConfiguration read(String json) throws IOException {
        return MAPPER.readValue(json, VehicleConfiguration.class);
    }
    public static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsBytes(value)));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Fahrzeugkonfiguration kann nicht gehasht werden", e);
        }
    }
    /** Also enforce the strict contract when embedded in the general Scenario API. */
    public static class Deserializer extends JsonDeserializer<VehicleConfiguration> {
        @Override
        public VehicleConfiguration deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return read(parser.readValueAsTree().toString());
        }
    }
}
