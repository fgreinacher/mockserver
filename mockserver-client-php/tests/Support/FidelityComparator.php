<?php

declare(strict_types=1);

namespace MockServer\Tests\Support;

/**
 * Shared cross-language JSON fidelity comparator (NORM + CANON + DIFFS + EXCUSED).
 *
 * This is a faithful port of the shared reference in {@code .tmp/reference_compare.py}
 * so every language port produces the same diff paths for the same behaviour. It is
 * extracted here so more than one PHP harness can share the exact same comparator:
 * {@see \MockServer\Tests\Unit\RoundTripFidelityTest} keeps its own inlined copy for
 * historical reasons, and {@see \MockServer\Tests\Unit\TypedRoundTripFidelityTest}
 * uses this one. Keeping a single implementation means the raw-replay and the typed
 * harnesses cannot silently drift in how they measure a diff.
 */
final class FidelityComparator
{
    /** keyToMultiValue fields (headers/params/trailers dual encoding). */
    public const MULTI = ['headers', 'queryStringParameters', 'trailers'];

    /** keyToValue fields (cookies dual encoding). */
    public const SINGLE = ['cookies'];

    public static function isJsonObject(mixed $v): bool
    {
        return is_array($v) && !array_is_list($v);
    }

    public static function isJsonList(mixed $v): bool
    {
        return is_array($v) && array_is_list($v);
    }

    /**
     * @return array<string, list<mixed>> canonical {name -> [values...]} map.
     */
    public static function canonMulti(mixed $v): array
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
    public static function canonSingle(mixed $v): array
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
    public static function norm(mixed $v, ?string $key = null): mixed
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
    public static function diffs(mixed $a, mixed $b, string $path = ''): array
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
        if (!self::scalarEqual($a, $b)) {
            $res[] = $path !== '' ? $path : '<root>';
        }
        return $res;
    }

    /**
     * Scalar equality matching the reference comparator's Python `!=` semantics
     * (numeric equality across int/float; strict otherwise). See the sibling
     * note in {@see \MockServer\Tests\Unit\RoundTripFidelityTest}.
     */
    public static function scalarEqual(mixed $a, mixed $b): bool
    {
        $aNum = is_int($a) || is_float($a);
        $bNum = is_int($b) || is_float($b);
        if ($aNum && $bNum) {
            return $a == $b;
        }
        return $a === $b;
    }

    public static function star(string $p): string
    {
        return implode('.', array_map(
            static fn(string $s): string => ctype_digit($s) ? '*' : $s,
            explode('.', $p),
        ));
    }

    /**
     * A path is excused if some ledger entry is a segment-wise prefix of it,
     * with `*` matching any numeric index segment.
     *
     * @param list<string> $entries
     */
    public static function excused(string $path, array $entries): bool
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
}
