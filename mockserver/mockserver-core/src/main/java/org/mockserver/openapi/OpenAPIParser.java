package org.mockserver.openapi;

import com.google.common.base.Joiner;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.extensions.SwaggerParserExtension;
import io.swagger.v3.parser.core.models.AuthorizationValue;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.cache.LRUCache;
import org.mockserver.logging.MockServerLogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.swagger.v3.parser.OpenAPIV3Parser.getExtensions;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class OpenAPIParser {

    private final static LRUCache<String, OpenAPI> openAPILRUCache = new LRUCache<>(new MockServerLogger(), 250, MINUTES.toMillis(30));

    public static final String OPEN_API_LOAD_ERROR = "Unable to load API spec";

    public static void clearCache(String specUrlOrPayload) {
        openAPILRUCache.delete(specUrlOrPayload);
    }

    /**
     * Helper function that checks if the provided string is a reference to an API specification file
     * @param specUrlOrPayload - string that might contain an API specification file reference
     * @return <b>true</b> if the provided string ends with special file suffix
     */
    public static boolean isSpecUrl(String specUrlOrPayload) {
        return specUrlOrPayload != null && (
            specUrlOrPayload.endsWith(".json") || specUrlOrPayload.endsWith(".yaml") || specUrlOrPayload.endsWith(".yml")
        );
    }

    public static OpenAPI buildOpenAPI(String specUrlOrPayload, MockServerLogger mockServerLogger) {
        // getOrCompute is atomic (ConcurrentHashMap.computeIfAbsent semantics): for an absent key the
        // parse + addMissingOperationIds runs at most once and the single resulting OpenAPI is shared by
        // all racing callers. A previous get-then-put allowed two threads to each parse and then mutate
        // (operationId dedup) their own copy and clobber the cache, racing on the shared instance.
        return openAPILRUCache.getOrCompute(specUrlOrPayload, key -> parseOpenAPI(key, mockServerLogger));
    }

    private static OpenAPI parseOpenAPI(String specUrlOrPayload, MockServerLogger mockServerLogger) {
        OpenAPI openAPI = null;
        SwaggerParseResult swaggerParseResult = null;
        List<AuthorizationValue> auths = null;
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);
        parseOptions.setResolveCombinators(true);
        parseOptions.setSkipMatches(true);
        parseOptions.setAllowEmptyString(true);
        parseOptions.setCamelCaseFlattenNaming(true);

        List<String> errorMessage = new ArrayList<>();
        try {
            if (OpenAPIParser.isSpecUrl(specUrlOrPayload)) {
                specUrlOrPayload = specUrlOrPayload.replaceAll("\\\\", "/");
                List<SwaggerParserExtension> parserExtensions = getExtensions();
                for (SwaggerParserExtension extension : parserExtensions) {
                    swaggerParseResult = extension.readLocation(specUrlOrPayload, auths, parseOptions);
                    openAPI = swaggerParseResult.getOpenAPI();
                    if (openAPI != null) {
                        break;
                    } else {
                        errorMessage.addAll(swaggerParseResult.getMessages());
                    }
                }
            } else {
                swaggerParseResult = new OpenAPIV3Parser().readContents(specUrlOrPayload, auths, parseOptions);
                openAPI = swaggerParseResult.getOpenAPI();
                if (openAPI == null) {
                    errorMessage.addAll(swaggerParseResult.getMessages());
                }
            }
        } catch (Throwable throwable) {
            throw new IllegalArgumentException(OPEN_API_LOAD_ERROR + (errorMessage.isEmpty() ? ", " + throwable.getMessage() : ", " + Joiner.on(", ").skipNulls().join(errorMessage)), throwable);
        }
        if (openAPI == null) {
            if (swaggerParseResult != null) {
                String message = errorMessage.stream().filter(Objects::nonNull).collect(Collectors.joining(" and ")).trim();
                throw new IllegalArgumentException((OPEN_API_LOAD_ERROR + (isNotBlank(message) ? ", " + message : "")));
            } else {
                throw new IllegalArgumentException(OPEN_API_LOAD_ERROR);
            }
        }
        addMissingOperationIds(openAPI, mockServerLogger);
        return openAPI;
    }

    /**
     * Ensures every operation has a non-blank, globally-unique operationId.
     *
     * <p>Operation ids key the converter's selection maps (and the stable expectation ids), so a
     * collision silently conflates two distinct operations. We therefore enforce global uniqueness
     * across <em>all</em> paths and webhooks in a single pass:
     * <ol>
     *   <li>author-supplied operationIds are reserved first so synthesized ids never steal one;</li>
     *   <li>blank operationIds are synthesized as {@code "<METHOD> <path>"} (paths) or
     *       {@code "<METHOD> webhook:<name>"} (webhooks);</li>
     *   <li>any id (author-supplied duplicate or colliding synthesized id) that has already been
     *       seen is disambiguated deterministically by appending {@code " (2)"}, {@code " (3)"}, …,
     *       and a WARN is logged for genuine duplicates so the conflation is visible.</li>
     * </ol>
     * A unique author-supplied operationId is always left unchanged.
     */
    private static void addMissingOperationIds(OpenAPI openAPI, MockServerLogger mockServerLogger) {
        Set<String> seenOperationIds = new HashSet<>();
        // First pass: reserve all author-supplied (non-blank) operationIds so that synthesized ids
        // are never allowed to collide with an id the author explicitly chose. A genuine duplicate
        // among author-supplied ids is disambiguated (and warned) in this pass too.
        reserveAuthoredOperationIds(openAPI.getPaths() != null ? openAPI.getPaths().values() : null, seenOperationIds, mockServerLogger);
        if (openAPI.getWebhooks() != null) {
            reserveAuthoredOperationIds(openAPI.getWebhooks().values(), seenOperationIds, mockServerLogger);
        }
        // Second pass: synthesize ids for operations with a blank operationId, disambiguating against
        // everything reserved/synthesized so far.
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, pathItem) -> synthesizeOperationIds(pathItem, path, false, seenOperationIds, mockServerLogger));
        }
        if (openAPI.getWebhooks() != null) {
            openAPI.getWebhooks().forEach((webhookName, pathItem) -> synthesizeOperationIds(pathItem, webhookName, true, seenOperationIds, mockServerLogger));
        }
    }

    private static void reserveAuthoredOperationIds(java.util.Collection<PathItem> pathItems, Set<String> seenOperationIds, MockServerLogger mockServerLogger) {
        if (pathItems == null) {
            return;
        }
        for (PathItem pathItem : pathItems) {
            for (Pair<String, Operation> stringOperationPair : mapOperations(pathItem)) {
                Operation operation = stringOperationPair.getRight();
                String operationId = operation.getOperationId();
                if (isNotBlank(operationId)) {
                    operation.setOperationId(ensureUnique(operationId, seenOperationIds, mockServerLogger));
                }
            }
        }
    }

    private static void synthesizeOperationIds(PathItem pathItem, String name, boolean webhook, Set<String> seenOperationIds, MockServerLogger mockServerLogger) {
        for (Pair<String, Operation> stringOperationPair : mapOperations(pathItem)) {
            Operation operation = stringOperationPair.getRight();
            if (isBlank(operation.getOperationId())) {
                String synthesized = stringOperationPair.getLeft() + (webhook ? " webhook:" + name : " " + name);
                operation.setOperationId(ensureUnique(synthesized, seenOperationIds, mockServerLogger));
            }
        }
    }

    /**
     * Returns {@code candidate} if unseen, otherwise the first {@code "candidate (n)"} (n>=2) not yet
     * seen, registering the chosen id in {@code seenOperationIds}. A collision is logged at WARN.
     */
    private static String ensureUnique(String candidate, Set<String> seenOperationIds, MockServerLogger mockServerLogger) {
        if (seenOperationIds.add(candidate)) {
            return candidate;
        }
        int suffix = 2;
        String disambiguated;
        do {
            disambiguated = candidate + " (" + suffix + ")";
            suffix++;
        } while (!seenOperationIds.add(disambiguated));
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(org.slf4j.event.Level.WARN)) {
            mockServerLogger.logEvent(
                new org.mockserver.log.model.LogEntry()
                    .setLogLevel(org.slf4j.event.Level.WARN)
                    .setMessageFormat("duplicate OpenAPI operationId {} - disambiguating to {} to avoid conflating distinct operations")
                    .setArguments(candidate, disambiguated)
            );
        }
        return disambiguated;
    }

    public static List<Pair<String, Operation>> mapOperations(PathItem pathItem) {
        List<Pair<String, Operation>> allOperations = new ArrayList<>();
        if (pathItem.getGet() != null) {
            allOperations.add(new ImmutablePair<>("GET", pathItem.getGet()));
        }
        if (pathItem.getPut() != null) {
            allOperations.add(new ImmutablePair<>("PUT", pathItem.getPut()));
        }
        if (pathItem.getPost() != null) {
            allOperations.add(new ImmutablePair<>("POST", pathItem.getPost()));
        }
        if (pathItem.getPatch() != null) {
            allOperations.add(new ImmutablePair<>("PATCH", pathItem.getPatch()));
        }
        if (pathItem.getDelete() != null) {
            allOperations.add(new ImmutablePair<>("DELETE", pathItem.getDelete()));
        }
        if (pathItem.getHead() != null) {
            allOperations.add(new ImmutablePair<>("HEAD", pathItem.getHead()));
        }
        if (pathItem.getOptions() != null) {
            allOperations.add(new ImmutablePair<>("OPTIONS", pathItem.getOptions()));
        }
        if (pathItem.getTrace() != null) {
            allOperations.add(new ImmutablePair<>("TRACE", pathItem.getTrace()));
        }
        return allOperations;
    }
}
