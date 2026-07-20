<?php

declare(strict_types=1);

namespace MockServer\Tests\Support;

/**
 * Access to the cross-client expectation corpus in {@code test-fixtures/expectations}.
 *
 * A builder test that claims to match a shared fixture must READ that fixture. Hard-coding
 * the expected array and naming the file in a comment looks like a cross-client contract
 * test but enforces nothing: change the fixture and the test stays green while the comment
 * quietly becomes false.
 */
final class SharedFixtures
{
    /**
     * Decode a fixture from the shared corpus.
     *
     * @return array<string, mixed>
     */
    public static function expectation(string $fileName): array
    {
        $path = __DIR__ . '/../../../test-fixtures/expectations/' . $fileName;
        $raw = file_get_contents($path);
        if ($raw === false) {
            throw new \RuntimeException("cannot read shared fixture: $path");
        }
        $decoded = json_decode($raw, true, 512, JSON_THROW_ON_ERROR);
        if (!is_array($decoded)) {
            throw new \RuntimeException("shared fixture is not a JSON object: $path");
        }
        return $decoded;
    }

    /**
     * Decode a fixture and return one action's payload, asserting the shape on the way.
     *
     * A fixture-loading test can pass vacuously if the load path is wrong and both sides end up
     * empty. expectation() already throws on an unreadable file, but a fixture that exists and
     * simply lacks the action would otherwise compare empty-against-empty, so require the key and
     * a non-trivial payload here.
     *
     * @return array<string, mixed>
     */
    public static function action(string $fileName, string $actionKey, int $minProperties = 1): array
    {
        $expectation = self::expectation($fileName);
        if (!array_key_exists($actionKey, $expectation) || !is_array($expectation[$actionKey])) {
            throw new \RuntimeException("shared fixture $fileName has no '$actionKey' action");
        }
        $action = $expectation[$actionKey];
        if (count($action) < $minProperties) {
            throw new \RuntimeException(
                "shared fixture $fileName '$actionKey' has " . count($action)
                . " properties, expected at least $minProperties — the fixture or the path is wrong",
            );
        }
        return $action;
    }

    /**
     * Recursively sort an array by key so two payloads can be compared for CONTENT
     * regardless of key order — JSON object key order is not significant, and the
     * fixtures were hand-written in a different order from the one the builders emit.
     *
     * Returned sorted so callers can still use assertSame: assertEquals would compare
     * loosely and stop distinguishing false from 0, or 0 from "0", which is exactly the
     * distinction several of these fields depend on.
     *
     * @param array<array-key, mixed> $value
     * @return array<array-key, mixed>
     */
    public static function sortedDeep(array $value): array
    {
        $sorted = [];
        foreach ($value as $k => $v) {
            $sorted[$k] = is_array($v) ? self::sortedDeep($v) : $v;
        }
        // list arrays (e.g. header values) are order-significant — only sort string-keyed maps
        if (!array_is_list($sorted)) {
            ksort($sorted);
        }
        return $sorted;
    }
}
