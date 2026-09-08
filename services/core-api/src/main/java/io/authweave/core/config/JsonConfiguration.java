package io.authweave.core.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.module.SimpleModule;

@Configuration(proxyBeanMethods = false)
class JsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictJson() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .addModule(exactEnumNames());
    }

    private static SimpleModule exactEnumNames() {
        return new SimpleModule("exact-contract-enum-names")
                .setDeserializerModifier(new ValueDeserializerModifier() {
                    @Override
                    public ValueDeserializer<?> modifyEnumDeserializer(DeserializationConfig config,
                            JavaType type, BeanDescription.Supplier description, ValueDeserializer<?> delegate) {
                        Set<String> names = Arrays.stream(type.getRawClass().getEnumConstants())
                                .map(value -> ((Enum<?>) value).name()).collect(Collectors.toUnmodifiableSet());
                        return new ValueDeserializer<Object>() {
                            @Override
                            public Object deserialize(JsonParser parser, DeserializationContext context) {
                                // Jackson normally trims enum strings; JSON Schema requires an exact match.
                                if (parser.hasToken(JsonToken.VALUE_STRING) && !names.contains(parser.getString())) {
                                    return context.reportInputMismatch(type, "Use an exact enum name from the contract.");
                                }
                                return delegate.deserialize(parser, context);
                            }
                        };
                    }
                });
    }
}
