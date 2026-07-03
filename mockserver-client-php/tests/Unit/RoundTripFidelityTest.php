<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\Expectation;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * Cross-language JSON round-trip fidelity harness for the PHP client.
 *
 * For every shared fixture in {@code test-fixtures/expectations/*.json} this
 * deserializes with the client model ({@see Expectation::fromArray()}),
 * re-serializes ({@code json_encode()} via {@see Expectation::jsonSerialize()})
 * and asserts the round-trip is semantically equal to the input, modulo a
 * known-gaps ledger.
 *
 * The comparator (NORM + CANON + DIFFS + EXCUSED) is a faithful port of the
 * shared reference in {@code .tmp/reference_compare.py} so every language port
 * produces the same diff paths for the same client behaviour.
 *
 * The PHP client stores the decoded array verbatim in {@code rawData} and
 * replays it unchanged from {@code toArray()}, so PHP is expected to round-trip
 * every field with ZERO gaps.
 */
final class RoundTripFidelityTest extends TestCase
{
    private const LANG = 'php';

    /** keyToMultiValue fields (headers/params/trailers dual encoding). */
    private const MULTI = ['headers', 'queryStringParameters', 'trailers'];

    /** keyToValue fields (cookies dual encoding). */
    private const SINGLE = ['cookies'];

    private static function repoRoot(): string
    {
        // tests/Unit -> tests -> mockserver-client-php -> repo root
        return __DIR__ . '/../../../';
    }

    /**
     * @return list<string> absolute fixture paths, excluding the manifest.
     */
    private static function fixtureFiles(): array
    {
        $glob = self::repoRoot() . 'test-fixtures/expectations/*.json';
        $files = glob($glob);
        if ($files === false) {
            return [];
        }
        $files = array_filter(
            $files,
            static fn(string $f): bool => basename($f) !== 'known-gaps.json',
        );
        sort($files);
        return array_values($files);
    }

    /**
     * @return array<string, mixed> the full known-gaps manifest.
     */
    private static function manifest(): array
    {
        $override = getenv('FIDELITY_KNOWN_GAPS');
        $path = ($override !== false && $override !== '')
            ? $override
            : self::repoRoot() . 'test-fixtures/expectations/known-gaps.json';
        $json = file_get_contents($path);
        if ($json === false) {
            return [];
        }
        $decoded = json_decode($json, true);
        return is_array($decoded) ? $decoded : [];
    }

    /**
     * @return list<string> excused path entries for this language.
     */
    private static function gaps(): array
    {
        $manifest = self::manifest();
        $entries = $manifest[self::LANG] ?? [];
        return is_array($entries) ? array_values(array_filter(
            $entries,
            static fn($e): bool => is_string($e),
        )) : [];
    }

    // ------------------------------------------------------------------
    // Comparator port (NORM + CANON + DIFFS + EXCUSED) over assoc arrays.
    // ------------------------------------------------------------------

    private static function isJsonObject(mixed $v): bool
    {
        return is_array($v) && !array_is_list($v);
    }

    private static function isJsonList(mixed $v): bool
    {
        return is_array($v) && array_is_list($v);
    }

    /**
     * @return array<string, list<mixed>> canonical {name -> [values...]} map.
     */
    private static function canonMulti(mixed $v): array
    {
        $out = [];
        if (self::isJsonObject($v)) {
            foreach ($v as $k => $val) {
                $out[$k] = self::isJsonList($val) ? $val : [$val];
            }
        } elseif (self::isJsonList($v)) {
            foreach ($v as $e) {
                if (is_array($e) && array_key_exists('name', $e)) {
                    if (array_key_exists('values', $e)) {
                        $vals = $e['values'];
                    } else {
                        $vals = array_key_exists('value', $e) ? $e['value'] : null;
                    }
                    $out[$e['name']] = self::isJsonList($vals) ? $vals : [$vals];
                }
            }
        }
        return $out;
    }

    /**
     * @return array<string, mixed> canonical {name -> value} map.
     */
    private static function canonSingle(mixed $v): array
    {
        $out = [];
        if (self::isJsonObject($v)) {
            $out = $v;
        } elseif (self::isJsonList($v)) {
            foreach ($v as $e) {
                if (is_array($e) && array_key_exists('name', $e)) {
                    $out[$e['name']] = array_key_exists('value', $e) ? $e['value'] : null;
                }
            }
        }
        return $out;
    }

    /**
     * NORM (null==absent) + CANON (dual-encoding) in one pass, keyed by parent
     * key name.
     */
    private static function norm(mixed $v, ?string $key = null): mixed
    {
        if ($v === null) {
            return null;
        }
        if ($key !== null && in_array($key, self::MULTI, true)) {
            $r = [];
            foreach (self::canonMulti($v) as $k => $vs) {
                $r[$k] = array_map(static fn($x) => self::norm($x), $vs);
            }
            return $r;
        }
        if ($key !== null && in_array($key, self::SINGLE, true)) {
            $r = [];
            foreach (self::canonSingle($v) as $k => $x) {
                $r[$k] = self::norm($x);
            }
            return $r;
        }
        if (self::isJsonList($v)) {
            return array_map(static fn($x) => self::norm($x), $v);
        }
        if (is_array($v)) { // object
            $r = [];
            foreach ($v as $k => $x) {
                if ($x !== null) {
                    $r[(string) $k] = self::norm($x, (string) $k);
                }
            }
            return $r;
        }
        return $v;
    }

    /**
     * @return list<string> diff path strings (input `a` vs output `b`).
     */
    private static function diffs(mixed $a, mixed $b, string $path = ''): array
    {
        $res = [];
        if (self::isJsonObject($a)) {
            if (!self::isJsonObject($b)) {
                return [$path !== '' ? $path : '<root>'];
            }
            foreach ($a as $k => $v) {
                $p = $path !== '' ? "$path.$k" : (string) $k;
                if (!array_key_exists($k, $b)) {
                    $res[] = $p;
                } else {
                    $res = array_merge($res, self::diffs($v, $b[$k], $p));
                }
            }
            foreach ($b as $k => $_) {
                if (!array_key_exists($k, $a)) {
                    $res[] = ($path !== '' ? "$path.$k" : (string) $k) . ' [ADDED]';
                }
            }
            return $res;
        }
        if (self::isJsonList($a)) {
            if (!self::isJsonList($b)) {
                return [$path !== '' ? $path : '<root>'];
            }
            $bCount = count($b);
            foreach ($a as $i => $v) {
                $p = $path !== '' ? "$path.$i" : (string) $i;
                if ($i >= $bCount) {
                    $res[] = $p;
                } else {
                    $res = array_merge($res, self::diffs($v, $b[$i], $p));
                }
            }
            return $res;
        }
        // scalar (or empty-array vs empty-array, both isList -> handled above)
        if (!self::scalarEqual($a, $b)) {
            $res[] = $path !== '' ? $path : '<root>';
        }
        return $res;
    }

    /**
     * Scalar equality matching the reference comparator's Python `!=` semantics.
     *
     * Python compares numbers by value across int/float ({@code 5.0 == 5}), but
     * treats cross-kind scalars (string vs number, etc.) as unequal. PHP's
     * {@code json_encode} renders a whole-number float such as {@code 5.0} as
     * {@code 5}, so after the JSON round-trip a fixture's {@code 5.0} decodes
     * back as the int {@code 5}. That is a representation change, not a semantic
     * one, and the shared comparator (per the Python reference) considers the
     * two numerically-equal scalars equal — so it must NOT be flagged as a diff.
     * Non-numeric scalars use strict comparison to avoid PHP's loose-`==`
     * coercions (e.g. {@code "1" == 1}) that Python does not perform.
     */
    private static function scalarEqual(mixed $a, mixed $b): bool
    {
        $aNum = is_int($a) || is_float($a);
        $bNum = is_int($b) || is_float($b);
        if ($aNum && $bNum) {
            return $a == $b;
        }
        return $a === $b;
    }

    private static function star(string $p): string
    {
        return implode('.', array_map(
            static fn(string $s): string => ctype_digit($s) ? '*' : $s,
            explode('.', $p),
        ));
    }

    /**
     * @param list<string> $entries
     */
    private static function excused(string $path, array $entries): bool
    {
        $pSegs = explode('.', $path);
        foreach ($entries as $g) {
            $gSegs = explode('.', $g);
            if (count($gSegs) > count($pSegs)) {
                continue;
            }
            $ok = true;
            foreach ($gSegs as $i => $gSeg) {
                $pSeg = $pSegs[$i];
                if ($gSeg === $pSeg) {
                    continue;
                }
                if ($gSeg === '*' && ctype_digit($pSeg)) {
                    continue;
                }
                $ok = false;
                break;
            }
            if ($ok) {
                return true;
            }
        }
        return false;
    }

    /**
     * Round-trip a fixture and return the raw diff paths (with any `[ADDED]`
     * suffix preserved).
     *
     * @return list<string>
     */
    private static function computeDiffs(string $fixturePath): array
    {
        $json = file_get_contents($fixturePath);
        if ($json === false) {
            throw new \RuntimeException("Cannot read fixture: $fixturePath");
        }
        $input = json_decode($json, true, 512, JSON_THROW_ON_ERROR);

        // Deserialize with the client model, then re-serialize via json_encode.
        $expectation = Expectation::fromArray($input);
        $reencoded = json_encode($expectation, JSON_THROW_ON_ERROR);
        $output = json_decode($reencoded, true, 512, JSON_THROW_ON_ERROR);

        return self::diffs(self::norm($input), self::norm($output));
    }

    // ------------------------------------------------------------------
    // Data provider — one data set per fixture, named by filename.
    // ------------------------------------------------------------------

    /**
     * @return iterable<string, array{string}>
     */
    public static function fixtureProvider(): iterable
    {
        foreach (self::fixtureFiles() as $path) {
            yield basename($path) => [$path];
        }
    }

    /**
     * Every fixture must round-trip with no unexcused diffs.
     */
    #[DataProvider('fixtureProvider')]
    public function testRoundTripFidelity(string $fixturePath): void
    {
        $gaps = self::gaps();
        $unexcused = [];
        foreach (self::computeDiffs($fixturePath) as $d) {
            $bare = str_replace(' [ADDED]', '', $d);
            if (!self::excused($bare, $gaps)) {
                $unexcused[] = $d;
            }
        }

        $this->assertSame(
            [],
            $unexcused,
            'Unexcused round-trip fidelity diffs for ' . basename($fixturePath)
            . ': ' . implode(', ', $unexcused),
        );
    }

    /**
     * Confirms the fixture set is the expected size (guards against a broken
     * glob / path silently testing nothing).
     */
    public function testFixtureCount(): void
    {
        $this->assertCount(44, self::fixtureFiles(), 'Expected 44 fixtures (excluding known-gaps.json)');
    }

    /**
     * Ratchet: every entry in gaps[php] must excuse at least one real diff path
     * across all fixtures. A stale entry means the model was fixed and the
     * manifest entry should be removed — CI fails until it is.
     *
     * (Trivially passes for an empty gap list, which is the expected PHP state.)
     */
    public function testNoStaleGapEntries(): void
    {
        $gaps = self::gaps();
        if ($gaps === []) {
            $this->assertSame([], $gaps);
            return;
        }

        $allDiffs = [];
        foreach (self::fixtureFiles() as $path) {
            foreach (self::computeDiffs($path) as $d) {
                $allDiffs[] = str_replace(' [ADDED]', '', $d);
            }
        }

        $stale = [];
        foreach ($gaps as $entry) {
            $used = false;
            foreach ($allDiffs as $diff) {
                if (self::excused($diff, [$entry])) {
                    $used = true;
                    break;
                }
            }
            if (!$used) {
                $stale[] = $entry;
            }
        }

        $this->assertSame(
            [],
            $stale,
            'Stale gaps[php] entries excusing no diff (remove them): ' . implode(', ', $stale),
        );
    }

    /**
     * Discovery mode: when FIDELITY_DISCOVER=1, print the sorted, deduped,
     * `*`-index-normalized union of computed diff paths for PHP and pass. This
     * is how the orchestrator harvests the language gap list.
     */
    public function testDiscoverGaps(): void
    {
        if (getenv('FIDELITY_DISCOVER') !== '1') {
            $this->markTestSkipped('Set FIDELITY_DISCOVER=1 to print the computed PHP gap list.');
        }

        $union = [];
        foreach (self::fixtureFiles() as $path) {
            foreach (self::computeDiffs($path) as $d) {
                $union[self::star(str_replace(' [ADDED]', '', $d))] = true;
            }
        }
        $paths = array_keys($union);
        sort($paths);

        fwrite(STDOUT, "\nFIDELITY_DISCOVER php gaps:\n" . json_encode($paths, JSON_PRETTY_PRINT) . "\n");
        $this->assertTrue(true);
    }
}
