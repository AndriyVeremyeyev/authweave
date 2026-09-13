package io.authweave.core.catalog.draft;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Application-specific canonicalization, not RFC 8785 or a signature. All v1 arrays are unordered collections. */
final class CatalogDraftCanonicalizer {
    static final String VERSION = "catalog-draft-canonical-json-1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private CatalogDraftCanonicalizer() { }

    static String sha256(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String json(Object value) { return MAPPER.writeValueAsString(ordered(MAPPER.valueToTree(value))); }

    private static JsonNode ordered(JsonNode input) {
        if (input.isObject()) {
            var output = MAPPER.createObjectNode();
            input.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> output.set(entry.getKey(), ordered(entry.getValue())));
            return output;
        }
        if (input.isArray()) {
            var values = new ArrayList<JsonNode>();
            input.forEach(value -> values.add(ordered(value)));
            values.sort(Comparator.comparing(MAPPER::writeValueAsString));
            var output = MAPPER.createArrayNode(); values.forEach(output::add); return output;
        }
        return input;
    }
}
