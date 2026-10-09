package io.authweave.core.catalog.impact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decision binding only: sorted object keys, preserved array order. Not a signature or RFC 8785. */
public final class DecisionCanonicalizer {
    public static final String VERSION = "SHA256_UTF8_COMPACT_JSON_SORTED_OBJECT_KEYS_PRESERVED_ARRAY_ORDER";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private DecisionCanonicalizer() { }

    public static String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsString(ordered(Objects.requireNonNull(value))).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JsonNode ordered(JsonNode value) {
        if (value.isObject()) {
            var result = MAPPER.createObjectNode();
            value.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), ordered(entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            var result = MAPPER.createArrayNode();
            value.forEach(entry -> result.add(ordered(entry)));
            return result;
        }
        if (value.isMissingNode()) throw new IllegalArgumentException("Cannot bind absent JSON");
        return value;
    }
}
