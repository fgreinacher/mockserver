package org.mockserver.templates.engine;

import com.google.common.collect.ImmutableMap;
import net.datafaker.Faker;
import org.mockserver.serialization.Base64Converter;
import org.mockserver.templates.engine.helpers.CryptoTemplateHelper;
import org.mockserver.templates.engine.helpers.CsvTemplateHelper;
import org.mockserver.templates.engine.helpers.DateTemplateHelper;
import org.mockserver.templates.engine.helpers.HtmlTemplateHelper;
import org.mockserver.templates.engine.helpers.JsonTemplateHelper;
import org.mockserver.templates.engine.helpers.JwtTemplateHelper;
import org.mockserver.templates.engine.helpers.MathTemplateHelper;
import org.mockserver.templates.engine.helpers.RegexTemplateHelper;
import org.mockserver.templates.engine.helpers.ScenarioTemplateHelper;
import org.mockserver.templates.engine.helpers.StringTemplateHelper;
import org.mockserver.templates.engine.helpers.XmlXPathTemplateHelper;
import org.mockserver.templates.engine.helpers.YamlTemplateHelper;
import org.mockserver.time.TimeService;
import org.mockserver.uuid.UUIDService;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

public class TemplateFunctions implements Supplier<Object> {
    private static final SecureRandom random = new SecureRandom();
    private static final Base64Converter base64Converter = new Base64Converter();
    private static final Faker FAKER = new Faker();

    public static final Map<String, Supplier<Object>> BUILT_IN_FUNCTIONS = ImmutableMap.<String, Supplier<Object>>builder()
        .put("now", new TemplateFunctions(() -> DateTimeFormatter.ISO_INSTANT.format(TimeService.now())))
        .put("now_epoch", new TemplateFunctions(() -> String.valueOf(TimeService.now().getEpochSecond())))
        .put("now_iso_8601", new TemplateFunctions(() -> DateTimeFormatter.ISO_INSTANT.format(TimeService.now())))
        .put("now_rfc_1123", new TemplateFunctions(() -> DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow())))
        .put("uuid", new TemplateFunctions(UUIDService::getUUID))
        .put("rand_int", new TemplateFunctions(() -> TemplateFunctions.randomInteger(10)))
        .put("rand_int_10", new TemplateFunctions(() -> TemplateFunctions.randomInteger(10)))
        .put("rand_int_100", new TemplateFunctions(() -> TemplateFunctions.randomInteger(100)))
        .put("rand_bytes", new TemplateFunctions(() -> TemplateFunctions.randomBytes(16)))
        .put("rand_bytes_16", new TemplateFunctions(() -> TemplateFunctions.randomBytes(16)))
        .put("rand_bytes_32", new TemplateFunctions(() -> TemplateFunctions.randomBytes(32)))
        .put("rand_bytes_64", new TemplateFunctions(() -> TemplateFunctions.randomBytes(64)))
        .put("rand_bytes_128", new TemplateFunctions(() -> TemplateFunctions.randomBytes(128)))
        .build();

    public static final Map<String, Object> BUILT_IN_HELPERS = ImmutableMap.<String, Object>builder()
        .put("jwt", new JwtTemplateHelper())
        .put("strings", new StringTemplateHelper())
        .put("jsonTransform", new JsonTemplateHelper())
        .put("dates", new DateTemplateHelper())
        .put("calc", new MathTemplateHelper())
        .put("faker", FAKER)
        .put("crypto", new CryptoTemplateHelper())
        .put("regex", new RegexTemplateHelper())
        .put("scenario", new ScenarioTemplateHelper())
        .put("html", new HtmlTemplateHelper())
        .put("csv", new CsvTemplateHelper())
        .put("xpath", new XmlXPathTemplateHelper())
        .put("yaml", new YamlTemplateHelper())
        .build();

    private final Supplier<String> supplier;

    public TemplateFunctions(Supplier<String> supplier) {
        this.supplier = supplier;
    }

    /**
     * Resolve the {@code faker} helper bound into template contexts.
     * <p>
     * When {@code templateFakerSeed} is 0 (the default) the shared, unseeded {@link #FAKER} is
     * returned, so faker-driven sample data stays time/random-based and behaviour is unchanged. When a
     * non-zero seed is configured a fresh {@link Faker} backed by a {@link Random} seeded with that
     * value is returned, so faker-driven templates produce reproducible fixtures across runs (the
     * template analogue of the OpenAPI {@code SampleDataGenerator} fixed-seed model). The returned
     * seeded faker is intended to be created once per template engine and reused across renders, so its
     * value sequence is deterministic for a given order of renders.
     *
     * @param templateFakerSeed faker seed, 0 to leave faker unseeded (random)
     * @return the shared unseeded faker when the seed is 0, otherwise a freshly seeded faker
     */
    public static Faker resolveFaker(long templateFakerSeed) {
        return templateFakerSeed == 0L ? FAKER : new Faker(new Random(templateFakerSeed));
    }

    public static String randomInteger(int max) {
        return String.valueOf(random.nextInt(max));
    }

    public static String randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return String.valueOf(base64Converter.bytesToBase64String(bytes));
    }

    @Override
    public Object get() {
        return supplier.get();
    }

    @Override
    public String toString() {
        return supplier.get();
    }
}
