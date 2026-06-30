namespace MockServer.Client.Models;

/// <summary>
/// The high-level operating mode of the MockServer (set/read via <c>/mockserver/mode</c>).
/// Mirrors the server-side <c>org.mockserver.mock.MockMode</c> enum.
/// </summary>
public enum MockMode
{
    /// <summary>
    /// Match expectations; unmatched requests return <c>404</c>
    /// (proxy-on-no-match disabled — this is the default).
    /// </summary>
    Simulate,

    /// <summary>
    /// Match expectations; unmatched requests are forwarded to the real upstream and
    /// recorded (proxy-on-no-match enabled).
    /// </summary>
    Spy,

    /// <summary>
    /// Forward and record; with no expectations defined this captures all traffic.
    /// </summary>
    Capture
}
