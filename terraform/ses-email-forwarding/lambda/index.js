/**
 * AWS SES Email Forwarder
 *
 * Forwards inbound emails received by SES to one or more destination addresses.
 * Triggered by an SES receipt rule (via Lambda action) after the raw email has
 * been written to S3 (via S3 action).
 *
 * Configuration (environment variables):
 *   MAIL_BUCKET     - S3 bucket where SES stores raw emails
 *   MAIL_KEY_PREFIX - S3 key prefix prepended to the message ID (e.g. "incoming/")
 *   FROM_ADDRESS    - Verified sender address for the rewritten From header
 *   FORWARD_TO      - Comma-separated list of destination email addresses
 *
 * The forwarder rewrites the From header so SES will accept the re-send (SES
 * requires the From address to belong to a verified identity). The original
 * sender is preserved in the Reply-To header and in the display name.
 * Return-Path, Sender, and DKIM-Signature headers are removed because the
 * original DKIM signature is invalid after rewriting From.
 *
 * Uses only AWS SDK v3 modules bundled in the nodejs20.x Lambda runtime --
 * no external dependencies or node_modules needed.
 */

"use strict";

const { S3Client, GetObjectCommand } = require("@aws-sdk/client-s3");
const { SESClient, SendRawEmailCommand } = require("@aws-sdk/client-ses");

const s3 = new S3Client();
const ses = new SESClient();

const MAIL_BUCKET = process.env.MAIL_BUCKET;
const MAIL_KEY_PREFIX = process.env.MAIL_KEY_PREFIX || "";
const FROM_ADDRESS = process.env.FROM_ADDRESS;
const FROM_LOCAL_PART = process.env.FROM_LOCAL_PART || "noreply";
const VERIFIED_DOMAINS = (process.env.VERIFIED_DOMAINS || "")
  .split(",")
  .map((s) => s.trim().toLowerCase())
  .filter(Boolean);

/**
 * Pick the From address to rewrite to, based on the domain the mail ARRIVED at.
 *
 * ⚠️  WHY THIS IS NOT JUST FROM_ADDRESS. This stack is a catch-all for more than
 *     one domain. With a single fixed sender, mail to an address at the second
 *     domain arrives appearing to come from the FIRST one — which reads as a
 *     misdirection to anyone who looks, and is exactly wrong for a published
 *     contact address.
 * ⚠️  SES REFUSES A SEND FROM AN UNVERIFIED IDENTITY, so the recipient's domain is
 *     checked against VERIFIED_DOMAINS before it is used. Anything unrecognised —
 *     including the case where SES gives us no recipients at all — falls back to
 *     FROM_ADDRESS rather than constructing an address SES will reject. Failing
 *     over to a working sender beats failing the forward.
 */
function senderFor(recipients) {
  for (const r of recipients || []) {
    const at = String(r).lastIndexOf("@");
    if (at === -1) continue;
    const domain = String(r).slice(at + 1).toLowerCase();
    if (VERIFIED_DOMAINS.includes(domain)) {
      return `${FROM_LOCAL_PART}@${domain}`;
    }
  }
  return FROM_ADDRESS;
}
const FORWARD_TO = (process.env.FORWARD_TO || "").split(",").map((s) => s.trim()).filter(Boolean);

exports.handler = async (event) => {
  const errors = [];
  let forwarded = 0;

  for (const record of event.Records) {
    const messageId = record.ses.mail.messageId;
    const s3Key = `${MAIL_KEY_PREFIX}${messageId}`;

    try {
      console.log(`Processing message ${messageId}`);

      // 1. Read the raw MIME email from S3
      const s3Response = await s3.send(
        new GetObjectCommand({ Bucket: MAIL_BUCKET, Key: s3Key })
      );
      const rawEmail = await s3Response.Body.transformToString("utf-8");

      // 2. Rewrite headers for forwarding
      // SES reports every address this message was accepted for; the first one
      // belonging to a domain we verify decides the From we rewrite to.
      const fromAddress = senderFor(record.ses.receipt?.recipients);
      const rewrittenEmail = rewriteHeaders(rawEmail, messageId, fromAddress);

      // 3. Send the rewritten email via SES
      await ses.send(
        new SendRawEmailCommand({
          Destinations: FORWARD_TO,
          RawMessage: {
            Data: Buffer.from(rewrittenEmail, "utf-8"),
          },
        })
      );

      console.log(`Forwarded message ${messageId} to ${FORWARD_TO.join(", ")}`);
      forwarded++;
    } catch (error) {
      console.error(
        `Failed to forward message ${messageId} (S3 key: ${s3Key}):`,
        error
      );
      errors.push(error);
    }
  }

  if (errors.length > 0) {
    // SES invokes this function asynchronously ("Event"), so throwing makes AWS
    // replay the WHOLE event on retry. Only throw when nothing was forwarded —
    // a retry is then safe and recovers from transient S3/SES failures (this is
    // the realistic case: SES delivers one record per invocation). When a
    // multi-record batch partially succeeded, do NOT throw: replaying would
    // re-forward the already-sent messages (duplicate delivery). Log instead so
    // the CloudWatch error-log alarm still fires.
    if (forwarded === 0) {
      throw new AggregateError(
        errors,
        `Failed to forward all ${event.Records.length} message(s)`
      );
    }
    console.error(
      `Partial failure: forwarded ${forwarded}, failed ${errors.length} of ` +
        `${event.Records.length} message(s); not retrying to avoid duplicate sends`
    );
  }
};

/**
 * Rewrite email headers so SES will accept the re-send.
 *
 * - Extracts the original From value and uses it as Reply-To and in the display
 *   name of the new From header.
 * - Removes Return-Path, Sender, and DKIM-Signature headers (the original DKIM
 *   signature is invalid after rewriting From, and Return-Path/Sender would
 *   confuse receiving mail servers).
 *
 * MIME emails use CRLF line endings. Header values can be "folded" across
 * multiple lines: a continuation line starts with whitespace (space or tab).
 * We handle this by processing line-by-line and treating continuation lines
 * as part of the preceding header.
 */
function rewriteHeaders(rawEmail, messageId, fromAddress = FROM_ADDRESS) {
  // Split headers from body. The boundary is the first blank line (CRLF CRLF).
  // Some emails may use bare LF; normalise to CRLF first.
  const normalized = rawEmail.replace(/\r?\n/g, "\r\n");
  const headerBodySeparator = "\r\n\r\n";
  const separatorIndex = normalized.indexOf(headerBodySeparator);

  if (separatorIndex === -1) {
    // Malformed email with no body -- forward as-is rather than crashing
    console.warn("Could not find header/body boundary; forwarding unmodified");
    return rawEmail;
  }

  const headerSection = normalized.substring(0, separatorIndex);
  const body = normalized.substring(separatorIndex); // includes the leading CRLF CRLF

  // Parse headers into an ordered list of { name, raw } objects.
  // "raw" is the full original text including the header name and any folded lines.
  const headers = parseHeaders(headerSection);

  // Find the original From value (for Reply-To and display name)
  const originalFrom = extractHeaderValue(headers, "from");

  // Remove headers that would break forwarding
  const headersToRemove = new Set(["return-path", "sender", "dkim-signature"]);

  // Also remove any existing Reply-To -- we will add our own
  headersToRemove.add("reply-to");

  const filteredHeaders = headers.filter(
    (h) => !headersToRemove.has(h.name.toLowerCase())
  );

  // Rewrite From header
  let hasFromHeader = false;
  for (const h of filteredHeaders) {
    if (h.name.toLowerCase() === "from") {
      hasFromHeader = true;
      // Build a display name from the original From value.
      // If the original already had a display name, extract it; otherwise use
      // the full address.
      const displayName = extractDisplayName(originalFrom);
      const label = displayName
        ? `${displayName} via ${getDomain(fromAddress)}`
        : originalFrom
          ? `${originalFrom} via ${getDomain(fromAddress)}`
          : `Unknown Sender via ${getDomain(fromAddress)}`;

      // Quote the display name and set our verified address
      h.raw = `From: "${escapeQuotes(label)}" <${fromAddress}>`;
      break;
    }
  }

  if (!hasFromHeader) {
    console.warn(
      "Message has no From header after rewrite; SES will likely reject it" +
        ` (message id: ${messageId || "unknown"})`
    );
  }

  // Add Reply-To with the original sender so recipients can reply directly
  if (originalFrom) {
    // Insert Reply-To right after the From header for readability
    const fromIndex = filteredHeaders.findIndex(
      (h) => h.name.toLowerCase() === "from"
    );
    const replyToHeader = {
      name: "Reply-To",
      raw: `Reply-To: ${originalFrom}`,
    };
    filteredHeaders.splice(fromIndex + 1, 0, replyToHeader);
  }

  // Reconstruct the email
  const newHeaderSection = filteredHeaders.map((h) => h.raw).join("\r\n");
  return newHeaderSection + body;
}

/**
 * Parse a header section string into an ordered list of header objects.
 * Handles folded (multi-line) headers correctly.
 */
function parseHeaders(headerSection) {
  const headers = [];
  const lines = headerSection.split("\r\n");

  for (const line of lines) {
    if (line.length === 0) continue;

    // Continuation line (starts with whitespace) -- append to previous header
    if (/^[ \t]/.test(line) && headers.length > 0) {
      headers[headers.length - 1].raw += "\r\n" + line;
    } else {
      // New header -- extract the name (everything before the first colon)
      const colonIndex = line.indexOf(":");
      const name = colonIndex > 0 ? line.substring(0, colonIndex) : line;
      headers.push({ name, raw: line });
    }
  }

  return headers;
}

/**
 * Extract the unfolded value of the first header matching the given name
 * (case-insensitive). Returns the value part only (after "Name: ").
 */
function extractHeaderValue(headers, name) {
  const lowerName = name.toLowerCase();
  for (const h of headers) {
    if (h.name.toLowerCase() === lowerName) {
      // Unfold and strip the "Name: " prefix
      const unfolded = h.raw.replace(/\r\n[ \t]+/g, " ");
      const colonIndex = unfolded.indexOf(":");
      return colonIndex > 0 ? unfolded.substring(colonIndex + 1).trim() : "";
    }
  }
  return "";
}

/**
 * Extract a display name from a From header value.
 * Handles formats like:
 *   "John Doe" <john@example.com>
 *   John Doe <john@example.com>
 *   john@example.com
 */
function extractDisplayName(fromValue) {
  if (!fromValue) return "";

  // Quoted display name: "Name" <addr>
  const quotedMatch = fromValue.match(/^"(.+?)"\s*<.+>$/);
  if (quotedMatch) return quotedMatch[1];

  // Unquoted display name: Name <addr>
  const unquotedMatch = fromValue.match(/^(.+?)\s*<.+>$/);
  if (unquotedMatch) return unquotedMatch[1].trim();

  // Bare address -- no display name
  return "";
}

/**
 * Extract the domain part from an email address.
 */
function getDomain(email) {
  const atIndex = email.lastIndexOf("@");
  return atIndex > 0 ? email.substring(atIndex + 1) : email;
}

/**
 * Escape double quotes inside a display name for use in a quoted string.
 */
function escapeQuotes(str) {
  return str.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}
