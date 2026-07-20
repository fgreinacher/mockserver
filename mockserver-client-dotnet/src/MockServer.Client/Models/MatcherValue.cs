using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A single matcher value for a header, query-string parameter, cookie or path parameter.
/// </summary>
/// <remarks>
/// MockServer's plain-string wire form encodes negation and optionality as leading markers, and the
/// server strips them unconditionally when reading. A value whose own first character is
/// <c>'!'</c> or <c>'?'</c> therefore cannot be sent as a bare string: "header X is exactly
/// <c>!foo</c>" would be read back as "header X is anything but <c>foo</c>" — which matches almost
/// every request, so the expectation silently passes for the wrong reason instead of failing loudly.
/// <para>
/// <see cref="MatcherValue"/> keeps the value and the flags apart, and falls back to the object form
/// (<c>{"not":false,"value":"!foo"}</c>) only when the plain form would be misread. Everything else
/// stays byte-identical on the wire, so existing expectations are unaffected. Use
/// <see cref="Literal"/> for an exact value and <see cref="NotLiteral"/> to negate one:
/// </para>
/// <code>
/// HttpRequest.Request().WithHeaderMatcher("X-Tag", MatcherValue.Literal("!foo"));   // X-Tag IS "!foo"
/// HttpRequest.Request().WithHeaderMatcher("X-Tag", MatcherValue.NotLiteral("foo")); // X-Tag is NOT "foo"
/// </code>
/// This mirrors the escape the Java client already had and the Go client gained.
/// </remarks>
[JsonConverter(typeof(MatcherValueConverter))]
public readonly struct MatcherValue : IEquatable<MatcherValue>
{
    // The markers MockServer strips from the front of a plain matcher string:
    // '!' negates the matcher, '?' makes it optional.
    private const char NotChar = '!';
    private const char OptionalChar = '?';

    /// <summary>The matcher text, taken verbatim — markers inside it are not parsed.</summary>
    public string Value { get; }

    /// <summary>Negates the matcher (match anything except <see cref="Value"/>).</summary>
    public bool Not { get; }

    /// <summary>Marks the header / parameter / cookie as optional.</summary>
    public bool Optional { get; }

    /// <summary>Creates a matcher value from explicit fields.</summary>
    public MatcherValue(string value, bool not = false, bool optional = false)
    {
        Value = value ?? string.Empty;
        Not = not;
        Optional = optional;
    }

    /// <summary>A matcher for exactly <paramref name="value"/>, even when it starts with <c>'!'</c> or <c>'?'</c>.</summary>
    public static MatcherValue Literal(string value) => new(value);

    /// <summary>A matcher that matches anything except exactly <paramref name="value"/>.</summary>
    public static MatcherValue NotLiteral(string value) => new(value, not: true);

    /// <summary>A matcher for exactly <paramref name="value"/> that need not be present.</summary>
    public static MatcherValue OptionalLiteral(string value) => new(value, optional: true);

    /// <summary>
    /// Parses a plain wire string into a <see cref="MatcherValue"/> exactly as the server would, so an
    /// existing <c>"!foo"</c> keeps meaning "not foo". The meaning is preserved; only the representation
    /// changes from an encoded string to explicit fields. Enables a plain string to stand in for a
    /// <see cref="MatcherValue"/>.
    /// </summary>
    public static implicit operator MatcherValue(string value) => ParsePlain(value);

    /// <summary>
    /// Renders the plain-string form, matching the server's <c>NottableString.serialise()</c>: an
    /// optional marker, then a not marker, then the value (a blank value contributes nothing).
    /// </summary>
    public string Serialise()
    {
        var builder = new StringBuilder();
        if (Optional)
        {
            builder.Append(OptionalChar);
        }
        if (Not)
        {
            builder.Append(NotChar);
        }
        if (!string.IsNullOrWhiteSpace(Value))
        {
            builder.Append(Value);
        }
        return builder.ToString();
    }

    /// <summary>
    /// Mirrors the server's <c>NottableString.string(String)</c>: strip an optional marker, then a not
    /// marker, then an optional marker again.
    /// </summary>
    public static MatcherValue ParsePlain(string? s)
    {
        var value = s ?? string.Empty;
        var not = false;
        var optional = false;
        if (!string.IsNullOrWhiteSpace(value))
        {
            if (value.Length > 0 && value[0] == OptionalChar)
            {
                optional = true;
                value = value.Substring(1);
            }
            if (value.Length > 0 && value[0] == NotChar)
            {
                not = true;
                value = value.Substring(1);
            }
            if (value.Length > 0 && value[0] == OptionalChar)
            {
                optional = true;
                value = value.Substring(1);
            }
        }
        return new MatcherValue(value, not, optional);
    }

    /// <summary>
    /// Reports whether re-reading the plain form would change the value, the negation or the
    /// optionality. Decided by actually re-parsing rather than by testing for a leading marker, so it
    /// stays correct for compound markers (<c>"?!"</c>, <c>"!?"</c>) and any marker added later. A
    /// blank value is never ambiguous: it has no marker to misread, and the server's object-form reader
    /// ignores a blank <c>value</c>, so escaping one would silently drop the matcher.
    /// </summary>
    public bool IsAmbiguous()
    {
        if (string.IsNullOrWhiteSpace(Value))
        {
            return false;
        }
        var reparsed = ParsePlain(Serialise());
        return reparsed.Not != Not || reparsed.Optional != Optional || reparsed.Value != Value;
    }

    /// <inheritdoc/>
    public bool Equals(MatcherValue other) =>
        Not == other.Not && Optional == other.Optional && string.Equals(Value, other.Value, StringComparison.Ordinal);

    /// <inheritdoc/>
    public override bool Equals(object? obj) => obj is MatcherValue other && Equals(other);

    /// <inheritdoc/>
    public override int GetHashCode()
    {
        unchecked
        {
            var hash = Value is null ? 0 : StringComparer.Ordinal.GetHashCode(Value);
            hash = (hash * 397) ^ Not.GetHashCode();
            hash = (hash * 397) ^ Optional.GetHashCode();
            return hash;
        }
    }

    public static bool operator ==(MatcherValue left, MatcherValue right) => left.Equals(right);

    public static bool operator !=(MatcherValue left, MatcherValue right) => !left.Equals(right);

    /// <summary>Returns the plain-string form, for logging.</summary>
    public override string ToString() => Serialise();
}

/// <summary>
/// <see cref="System.Text.Json"/> converter for <see cref="MatcherValue"/>. Writes the plain string
/// when it round-trips and the object form (<c>{"not":false,"value":"!foo"}</c>) when it would not;
/// reads both the plain-string and the object form.
/// </summary>
public sealed class MatcherValueConverter : JsonConverter<MatcherValue>
{
    public override MatcherValue Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        switch (reader.TokenType)
        {
            case JsonTokenType.String:
                return MatcherValue.ParsePlain(reader.GetString());
            case JsonTokenType.Null:
                return MatcherValue.Literal(string.Empty);
            case JsonTokenType.StartObject:
                using (var doc = JsonDocument.ParseValue(ref reader))
                {
                    var root = doc.RootElement;
                    var value = root.TryGetProperty("value", out var v) && v.ValueKind == JsonValueKind.String
                        ? v.GetString()!
                        : string.Empty;
                    var not = root.TryGetProperty("not", out var n) && n.ValueKind == JsonValueKind.True;
                    var optional = root.TryGetProperty("optional", out var o) && o.ValueKind == JsonValueKind.True;
                    return new MatcherValue(value, not, optional);
                }
            default:
                throw new JsonException($"Unexpected token {reader.TokenType} reading a MatcherValue");
        }
    }

    public override void Write(Utf8JsonWriter writer, MatcherValue value, JsonSerializerOptions options)
    {
        if (!value.IsAmbiguous())
        {
            writer.WriteStringValue(value.Serialise());
            return;
        }

        // The object property names are the literal wire names ("not"/"optional"/"value"), independent
        // of any camelCase policy. "not" is written even when false so the intent is unmistakable to a
        // reader and to the other clients, rather than relying on absent-means-false.
        writer.WriteStartObject();
        writer.WriteBoolean("not", value.Not);
        if (value.Optional)
        {
            writer.WriteBoolean("optional", value.Optional);
        }
        writer.WriteString("value", value.Value);
        writer.WriteEndObject();
    }
}
