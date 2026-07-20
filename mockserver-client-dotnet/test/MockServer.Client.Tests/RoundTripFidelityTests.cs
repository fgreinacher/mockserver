using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;
using Xunit.Abstractions;

namespace MockServer.Client.Tests;

/// <summary>
/// Cross-language JSON round-trip fidelity harness for the .NET client.
///
/// For every shared fixture in repo-root <c>test-fixtures/expectations/*.json</c> this
/// deserializes the expectation into <see cref="Expectation"/> via System.Text.Json and
/// re-serializes it, then compares the normalized/canonicalized input against the
/// normalized/canonicalized output. Any diff path that is not excused by the shared
/// known-gaps ledger (language key <c>"dotnet"</c>) fails the test — that is a fidelity
/// regression where the model silently drops or mutates a field.
///
/// The comparator (NORM + CANON + DIFFS + EXCUSED) is an exact port of the shared
/// reference in <c>.tmp/reference_compare.py</c> / <c>.tmp/roundtrip-spec.md</c>. All
/// language ports must produce identical diff paths for the same client behaviour.
///
/// Modes:
///  - [Theory] per fixture: assert no unexcused diffs (failure names the fixture + paths).
///  - [Fact] ratchet: every <c>dotnet</c> gap entry must excuse at least one real diff, so a
///    stale entry (model since fixed) fails CI until removed.
///  - [Fact] discovery: when env <c>FIDELITY_DISCOVER=1</c>, print the sorted, deduped,
///    <c>*</c>-index-normalized union of computed diff paths and pass (harvests the gap list).
///
/// The manifest is read from env <c>FIDELITY_KNOWN_GAPS</c> when set (self-verification against
/// a scratch copy) else from the copied <c>fixtures/known-gaps.json</c> next to the assembly.
/// This test needs no live MockServer — it is a pure serialization round-trip.
/// </summary>
public class RoundTripFidelityTests
{
    private const string Lang = "dotnet";

    /// <summary>Serializer options the .NET client uses everywhere (null-omitting, camelCase).</summary>
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        // The client registers this so a matcher value starting with '!' or '?' round-trips through the
        // nottable object form instead of being read as a marker; the harness must mirror the client's
        // real options. See HttpRequestConverter.
        Converters = { new MockServer.Client.Models.HttpRequestConverter() }
    };

    // keyToMultiValue fields: object form OR [{name, value(s)}] array form — canonicalized to {name -> [values]}.
    private static readonly HashSet<string> Multi = new() { "headers", "queryStringParameters", "trailers" };
    // keyToValue field: object form OR [{name, value}] array form — canonicalized to {name -> value}.
    private static readonly HashSet<string> Single = new() { "cookies" };

    private readonly ITestOutputHelper _output;

    public RoundTripFidelityTests(ITestOutputHelper output) => _output = output;

    private static string FixturesDir => Path.Combine(AppContext.BaseDirectory, "fixtures");

    /// <summary>MemberData source: one row per fixture filename (excluding the manifest).</summary>
    public static IEnumerable<object[]> Fixtures()
    {
        foreach (var file in Directory.EnumerateFiles(FixturesDir, "*.json")
                     .Where(p => Path.GetFileName(p) != "known-gaps.json")
                     .OrderBy(p => p, StringComparer.Ordinal))
        {
            yield return new object[] { Path.GetFileName(file) };
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    [Theory]
    [MemberData(nameof(Fixtures))]
    public void RoundTrip_HasNoUnexcusedDiffs(string fixtureName)
    {
        var gaps = LoadGaps();
        var path = Path.Combine(FixturesDir, fixtureName);
        var unexcused = ComputeDiffs(path)
            .Select(StripAdded)
            .Where(p => !Excused(p, gaps))
            .Distinct()
            .OrderBy(p => p, StringComparer.Ordinal)
            .ToList();

        unexcused.Should().BeEmpty(
            "fixture {0} must round-trip with no unexcused diffs but dropped/mutated:\n  {1}",
            fixtureName, string.Join("\n  ", unexcused));
    }

    [Fact]
    public void Ratchet_EveryGapEntryExcusesAtLeastOneDiff()
    {
        var gaps = LoadGaps();
        var allPaths = new List<string>();
        foreach (var row in Fixtures())
        {
            var path = Path.Combine(FixturesDir, (string)row[0]);
            allPaths.AddRange(ComputeDiffs(path).Select(StripAdded));
        }

        var stale = gaps
            .Where(g => !allPaths.Any(p => Excused(p, new[] { g })))
            .OrderBy(g => g, StringComparer.Ordinal)
            .ToList();

        stale.Should().BeEmpty(
            "these known-gaps[\"{0}\"] entries excuse no diff (the model was fixed — remove them):\n  {1}",
            Lang, string.Join("\n  ", stale));
    }

    [Fact]
    public void Discover_PrintsComputedGapPaths()
    {
        if (Environment.GetEnvironmentVariable("FIDELITY_DISCOVER") != "1")
        {
            return; // discovery is opt-in; normal runs just pass this trivially
        }

        var union = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var row in Fixtures())
        {
            var path = Path.Combine(FixturesDir, (string)row[0]);
            foreach (var diff in ComputeDiffs(path))
            {
                union.Add(Star(StripAdded(diff)));
            }
        }

        var json = "[\n" +
                   string.Join(",\n", union.Select(u => "  " + JsonSerializer.Serialize(u))) +
                   "\n]";
        var banner = $"FIDELITY_DISCOVER {Lang} gaps ({union.Count} paths):";
        _output.WriteLine(banner);
        _output.WriteLine(json);
        Console.WriteLine(banner);
        Console.WriteLine(json);
    }

    // ---------------------------------------------------------------------------------------------
    // Round-trip + comparator (exact port of .tmp/reference_compare.py)
    // ---------------------------------------------------------------------------------------------

    /// <summary>
    /// Sentinel diff path emitted when the model cannot deserialize a fixture at all (System.Text.Json
    /// hard-errors — e.g. a matcher shape the model's field types reject, which loses the WHOLE
    /// expectation rather than one field). Without this the exception escapes and the fixture cannot be
    /// excused by the known-gaps ledger at all, so a genuine, recorded model limitation reads as a crash.
    /// Kept as a single opaque token (no '.') so it is excused in isolation and never masks the precise
    /// per-field drops of other, parseable fixtures. Mirrors the Rust harness.
    /// </summary>
    private const string Unrepresentable = "<unrepresentable-expectation>";

    /// <summary>
    /// Per-fixture form of <see cref="Unrepresentable"/>: <c>&lt;unrepresentable-expectation&gt;:name</c>.
    /// An unscoped token would be excused globally by one ledger entry, so a SECOND fixture becoming
    /// unparseable later would be silently absorbed and the ratchet would never re-arm — a gap entry
    /// that quietly widens its own scope over time.
    /// </summary>
    private static string UnrepresentableFor(string fixtureName) =>
        $"{Unrepresentable}:{Path.GetFileNameWithoutExtension(fixtureName)}";

    private static List<string> ComputeDiffs(string fixturePath)
    {
        var inputText = File.ReadAllText(fixturePath);
        var original = JsonNode.Parse(inputText)!;

        // Deserialize with the client's real options and re-serialize. Unknown fields are ignored and
        // modeled fields must survive intact — any dropped/mutated field surfaces as a diff below.
        Expectation? expectation;
        try
        {
            expectation = JsonSerializer.Deserialize<Expectation>(inputText, JsonOptions);
        }
        catch (JsonException)
        {
            return new List<string> { UnrepresentableFor(Path.GetFileName(fixturePath)) };
        }

        var outputText = JsonSerializer.Serialize(expectation, JsonOptions);

        var input = Norm(original, null);
        var output = Norm(JsonNode.Parse(outputText), null);
        return Diffs(input, output, "");
    }

    /// <summary>
    /// NORM (null == absent) + CANON (dual-encoding canonicalization) in one recursive pass,
    /// keyed by the parent object's key name. Produces a plain model: Dictionary / List / JsonValue / null.
    /// </summary>
    private static object? Norm(JsonNode? node, string? key)
    {
        if (node is null)
        {
            return null;
        }

        if (key is not null && Multi.Contains(key))
        {
            var result = new Dictionary<string, object?>();
            foreach (var kv in CanonMulti(node))
            {
                result[kv.Key] = kv.Value.Select(x => Norm(x, null)).ToList();
            }
            return result;
        }

        if (key is not null && Single.Contains(key))
        {
            var result = new Dictionary<string, object?>();
            foreach (var kv in CanonSingle(node))
            {
                result[kv.Key] = Norm(kv.Value, null);
            }
            return result;
        }

        if (node is JsonObject obj)
        {
            var result = new Dictionary<string, object?>();
            foreach (var kv in obj)
            {
                if (kv.Value is null)
                {
                    continue; // drop null-valued keys (absent key == null value)
                }
                result[kv.Key] = Norm(kv.Value, kv.Key);
            }
            return result;
        }

        if (node is JsonArray arr)
        {
            var list = new List<object?>();
            foreach (var element in arr)
            {
                list.Add(Norm(element, null));
            }
            return list;
        }

        return node; // scalar JsonValue (kept for value comparison)
    }

    /// <summary>Canonicalize a keyToMultiValue field to {name -> [value nodes]} (values NOT sorted).</summary>
    private static Dictionary<string, List<JsonNode?>> CanonMulti(JsonNode node)
    {
        var result = new Dictionary<string, List<JsonNode?>>();
        if (node is JsonObject obj)
        {
            foreach (var kv in obj)
            {
                result[kv.Key] = kv.Value is JsonArray a
                    ? a.ToList()
                    : new List<JsonNode?> { kv.Value };
            }
        }
        else if (node is JsonArray arr)
        {
            foreach (var element in arr)
            {
                if (element is JsonObject entry && entry.ContainsKey("name"))
                {
                    var name = entry["name"]!.GetValue<string>();
                    var vals = entry.ContainsKey("values") ? entry["values"]
                             : entry.ContainsKey("value") ? entry["value"]
                             : null;
                    result[name] = vals is JsonArray va
                        ? va.ToList()
                        : new List<JsonNode?> { vals };
                }
            }
        }
        return result;
    }

    /// <summary>Canonicalize a keyToValue field (cookies) to {name -> value node}.</summary>
    private static Dictionary<string, JsonNode?> CanonSingle(JsonNode node)
    {
        var result = new Dictionary<string, JsonNode?>();
        if (node is JsonObject obj)
        {
            foreach (var kv in obj)
            {
                result[kv.Key] = kv.Value;
            }
        }
        else if (node is JsonArray arr)
        {
            foreach (var element in arr)
            {
                if (element is JsonObject entry && entry.ContainsKey("name"))
                {
                    result[entry["name"]!.GetValue<string>()] = entry.ContainsKey("value") ? entry["value"] : null;
                }
            }
        }
        return result;
    }

    /// <summary>
    /// DIFFS(a, b): walk the normalized INPUT (a) against the normalized OUTPUT (b) and emit a path
    /// for every key/index in a missing from b or with an unequal scalar. Extra keys in b get "[ADDED]".
    /// </summary>
    private static List<string> Diffs(object? a, object? b, string path)
    {
        var res = new List<string>();

        if (a is Dictionary<string, object?> da)
        {
            if (b is not Dictionary<string, object?> db)
            {
                res.Add(path.Length == 0 ? "<root>" : path);
                return res;
            }
            foreach (var kv in da)
            {
                var p = path.Length == 0 ? kv.Key : path + "." + kv.Key;
                if (!db.ContainsKey(kv.Key))
                {
                    res.Add(p);
                }
                else
                {
                    res.AddRange(Diffs(kv.Value, db[kv.Key], p));
                }
            }
            foreach (var kv in db)
            {
                if (!da.ContainsKey(kv.Key))
                {
                    res.Add((path.Length == 0 ? kv.Key : path + "." + kv.Key) + " [ADDED]");
                }
            }
            return res;
        }

        if (a is List<object?> la)
        {
            if (b is not List<object?> lb)
            {
                res.Add(path.Length == 0 ? "<root>" : path);
                return res;
            }
            for (var i = 0; i < la.Count; i++)
            {
                var p = path + "." + i;
                if (i >= lb.Count)
                {
                    res.Add(p);
                }
                else
                {
                    res.AddRange(Diffs(la[i], lb[i], p));
                }
            }
            return res;
        }

        // scalar (or type mismatch against a container in b)
        if (!ScalarEqual(a, b))
        {
            res.Add(path.Length == 0 ? "<root>" : path);
        }
        return res;
    }

    private static bool ScalarEqual(object? a, object? b)
    {
        if (a is null && b is null)
        {
            return true;
        }
        if (a is JsonValue va && b is JsonValue vb)
        {
            return ScalarKey(va) == ScalarKey(vb);
        }
        return false; // null vs value, or scalar vs container => unequal
    }

    /// <summary>Type-tagged canonical key for a scalar so number/string/bool never compare equal across kinds.</summary>
    private static string ScalarKey(JsonValue value)
    {
        switch (value.GetValueKind())
        {
            case JsonValueKind.True:
                return "b:1";
            case JsonValueKind.False:
                return "b:0";
            case JsonValueKind.String:
                return "s:" + value.GetValue<string>();
            case JsonValueKind.Number:
                return "n:" + decimal.Parse(value.ToJsonString(), CultureInfo.InvariantCulture)
                    .ToString(CultureInfo.InvariantCulture);
            default:
                return "x:" + value.ToJsonString();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Manifest + path helpers
    // ---------------------------------------------------------------------------------------------

    /// <summary>Replace numeric path segments with * so a manifest entry is fixture-length-independent.</summary>
    private static string Star(string p) =>
        string.Join(".", p.Split('.').Select(s => s.Length > 0 && s.All(char.IsDigit) ? "*" : s));

    private static string StripAdded(string p) => p.Replace(" [ADDED]", "");

    /// <summary>
    /// A manifest entry G excuses path P iff len(G) &lt;= len(P) and every segment G[i] equals P[i]
    /// or is "*" against an all-digit P[i]. A shorter G excuses its whole subtree.
    /// </summary>
    private static bool Excused(string path, IReadOnlyList<string> entries)
    {
        var pp = path.Split('.');
        foreach (var entry in entries)
        {
            var gg = entry.Split('.');
            if (gg.Length > pp.Length)
            {
                continue;
            }
            var match = true;
            for (var i = 0; i < gg.Length; i++)
            {
                if (gg[i] == pp[i])
                {
                    continue;
                }
                if (gg[i] == "*" && pp[i].Length > 0 && pp[i].All(char.IsDigit))
                {
                    continue;
                }
                match = false;
                break;
            }
            if (match)
            {
                return true;
            }
        }
        return false;
    }

    private static List<string> LoadGaps()
    {
        var path = Environment.GetEnvironmentVariable("FIDELITY_KNOWN_GAPS");
        if (string.IsNullOrEmpty(path))
        {
            path = Path.Combine(FixturesDir, "known-gaps.json");
        }

        var list = new List<string>();
        if (JsonNode.Parse(File.ReadAllText(path)) is JsonObject root
            && root.TryGetPropertyValue(Lang, out var arr)
            && arr is JsonArray ja)
        {
            foreach (var element in ja)
            {
                if (element is not null)
                {
                    list.Add(element.GetValue<string>());
                }
            }
        }
        return list;
    }
}
