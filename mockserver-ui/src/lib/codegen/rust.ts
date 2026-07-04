/**
 * Rust client-library emitter.
 *
 * Emits **typed** `mockserver-client` construction — `Expectation::new(HttpRequest…)`
 * with `HttpRequest` / `HttpResponse` / `Body` / `HttpForward` / `HttpChaosProfile`
 * (etc.) builders and typed struct literals — rather than deserialising an embedded
 * JSON blob via `serde_json::from_str`. The transpiler walks the canonical wire
 * object produced by {@link buildExpectationJson} and maps every field onto a builder
 * call, a typed struct literal, or (only for genuinely opaque JSON — a JSON-body
 * matcher's value, a free-text step/DNS payload, or an edit-passthrough field the
 * composer does not model) a `serde_json::json!` fragment carried through the model's
 * typed escape hatches (`Body::object`, the public `extra` catch-all maps).
 *
 * The generated source serialises (`serde_json::to_value`) to a value structurally
 * identical to {@link buildExpectationJson}; the crate's own model is the single
 * source of truth for field names and shapes (see `mockserver-client-rust/src/model.rs`).
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen';
import { clientHostPort } from './shared';

// ---------------------------------------------------------------------------
// Low-level literal helpers
// ---------------------------------------------------------------------------

/** Render a Rust double-quoted string literal. Backtick and `#` are literal in a
 *  Rust string so they need no escaping (unlike the old raw-string form). */
function rustStr(s: string): string {
  let out = '"';
  for (const ch of s) {
    const code = ch.codePointAt(0)!;
    if (ch === '\\') out += '\\\\';
    else if (ch === '"') out += '\\"';
    else if (ch === '\n') out += '\\n';
    else if (ch === '\r') out += '\\r';
    else if (ch === '\t') out += '\\t';
    else if (ch === '\0') out += '\\0';
    else if (code < 0x20) out += '\\u{' + code.toString(16) + '}';
    else out += ch;
  }
  return out + '"';
}

/** Render a JSON number as a Rust/`json!` numeric token. */
function numLit(n: number): string {
  return Number.isFinite(n) ? String(n) : '0';
}

/** Render a number as a Rust `f64` literal (integers get a `.0` suffix so the
 *  literal is not inferred as an integer where a float is required). */
function floatLit(n: number): string {
  if (!Number.isFinite(n)) return '0.0';
  return Number.isInteger(n) ? `${n}.0` : String(n);
}

const pad = (n: number): string => ' '.repeat(n);

// ---------------------------------------------------------------------------
// serde_json::json! literal rendering (for the few opaque-JSON fragments)
// ---------------------------------------------------------------------------

/** Render an arbitrary JSON value as the token stream accepted by the
 *  `serde_json::json!` macro, indented for readability. */
function jsonMacroArg(v: unknown, indent: number): string {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') return numLit(v);
  if (typeof v === 'string') return rustStr(v);
  if (Array.isArray(v)) {
    if (v.length === 0) return '[]';
    const inner = pad(indent + 4);
    return '[\n' + v.map((x) => inner + jsonMacroArg(x, indent + 4)).join(',\n') + '\n' + pad(indent) + ']';
  }
  const entries = Object.entries(v as Record<string, unknown>);
  if (entries.length === 0) return '{}';
  const inner = pad(indent + 4);
  return (
    '{\n' +
    entries.map(([k, val]) => inner + rustStr(k) + ': ' + jsonMacroArg(val, indent + 4)).join(',\n') +
    '\n' +
    pad(indent) +
    '}'
  );
}

/** `serde_json::json!(<value>)` expression whose head sits at column `indent`. */
function jsonMacro(v: unknown, indent: number): string {
  return 'serde_json::json!(' + jsonMacroArg(v, indent) + ')';
}

// ---------------------------------------------------------------------------
// Builder-chain assembly
// ---------------------------------------------------------------------------

/**
 * A rendered value: the expression to use, plus any statements that must precede
 * it (a `let [mut] name = …;` binding and follow-up `name.extra.insert(…)` calls
 * for fields that have no dedicated builder).
 */
interface Rendered {
  setup: string[];
  expr: string;
}

const inline = (expr: string): Rendered => ({ setup: [], expr });

/** Assemble a fluent builder chain: `head` then each call on its own line,
 *  continuation lines indented to `indent + 4`. */
function chain(head: string, calls: string[], indent: number): string {
  if (calls.length === 0) return head;
  const cont = pad(indent + 4);
  return head + '\n' + calls.map((c) => cont + c).join('\n');
}

/** Monotonic temp-name source so nested pre-bound values never collide. */
class Ctx {
  private n = 0;
  temp(): string {
    return `v${this.n++}`;
  }
}

// ---------------------------------------------------------------------------
// Delay / Times / TimeToLive
// ---------------------------------------------------------------------------

function delayExpr(d: Record<string, unknown>): string {
  const unit = String(d['timeUnit'] ?? 'MILLISECONDS');
  const value = Number(d['value'] ?? 0);
  if (unit === 'MILLISECONDS') return `Delay::milliseconds(${numLit(value)})`;
  if (unit === 'SECONDS') return `Delay::seconds(${numLit(value)})`;
  // MINUTES (and any other unit) has no constructor — use a typed struct literal.
  return `Delay { time_unit: ${rustStr(unit)}.to_string(), value: ${numLit(value)} }`;
}

function timesExpr(t: Record<string, unknown>): string {
  if (t['unlimited'] === true && t['remainingTimes'] == null) return 'Times::unlimited()';
  if (typeof t['remainingTimes'] === 'number') return `Times::exactly(${numLit(t['remainingTimes'] as number)})`;
  return 'Times::unlimited()';
}

function ttlExpr(t: Record<string, unknown>): string {
  if (t['unlimited'] === true && t['timeToLive'] == null) return 'TimeToLive::unlimited()';
  const value = t['timeToLive'];
  if (typeof value === 'number') {
    const unit = String(t['timeUnit'] ?? 'SECONDS');
    if (unit === 'MILLISECONDS') return `TimeToLive::milliseconds(${numLit(value)})`;
    return `TimeToLive::seconds(${numLit(value)})`;
  }
  return 'TimeToLive::unlimited()';
}

// ---------------------------------------------------------------------------
// Body matcher (request side)
// ---------------------------------------------------------------------------

/** True when `o` has exactly the given keys (no more, no less). */
function hasExactKeys(o: Record<string, unknown>, keys: string[]): boolean {
  const ks = Object.keys(o);
  return ks.length === keys.length && keys.every((k) => k in o);
}

/** Render a typed `Body` expression for a request-body matcher object. */
function bodyValueExpr(o: Record<string, unknown>, indent: number): string {
  const type = o['type'];
  if (type === 'ALL_OF' && Array.isArray(o['bodyAllOf'])) {
    const subs = o['bodyAllOf'] as unknown[];
    const inner = pad(indent + 4);
    const items = subs.map((s) => inner + bodyValueExpr(s as Record<string, unknown>, indent + 4) + ',').join('\n');
    return 'Body::all_of(vec![\n' + items + '\n' + pad(indent) + '])';
  }
  if (type === 'REGEX' && hasExactKeys(o, ['type', 'regex'])) return `Body::regex(${rustStr(String(o['regex']))})`;
  if (type === 'JSON_PATH' && hasExactKeys(o, ['type', 'jsonPath'])) return `Body::json_path(${rustStr(String(o['jsonPath']))})`;
  if (type === 'XPATH' && hasExactKeys(o, ['type', 'xpath'])) return `Body::xpath(${rustStr(String(o['xpath']))})`;
  if (type === 'XML' && hasExactKeys(o, ['type', 'xml'])) return `Body::xml(${rustStr(String(o['xml']))})`;
  if (type === 'XML_SCHEMA' && hasExactKeys(o, ['type', 'xmlSchema'])) return `Body::xml_schema(${rustStr(String(o['xmlSchema']))})`;
  if (type === 'JSON_SCHEMA' && hasExactKeys(o, ['type', 'jsonSchema'])) return `Body::json_schema(${rustStr(String(o['jsonSchema']))})`;
  if (type === 'STRING' && hasExactKeys(o, ['type', 'string', 'subString'])) {
    return `Body::string(${rustStr(String(o['string']))}, ${o['subString'] === true ? 'true' : 'false'})`;
  }
  // JSON (value is inherently JSON data), GRAPHQL-with-options, PARAMETERS, BINARY,
  // WASM, or any matcher carrying extra keys: the typed escape hatch Body::object,
  // which serialises the object verbatim.
  return (
    'Body::object(\n' +
    pad(indent + 4) +
    jsonMacro(o, indent + 4) +
    '\n' +
    pad(indent + 4) +
    '.as_object()\n' +
    pad(indent + 4) +
    '.expect("body object")\n' +
    pad(indent + 4) +
    '.clone(),\n' +
    pad(indent) +
    ')'
  );
}

/** The `.body(...)` / `.body_value(...)` call for a request body value. */
function requestBodyCall(body: unknown, indent: number): string {
  if (typeof body === 'string') return `.body(${rustStr(body)})`;
  return `.body_value(${bodyValueExpr(body as Record<string, unknown>, indent + 4)})`;
}

// ---------------------------------------------------------------------------
// Jwt
// ---------------------------------------------------------------------------

function jwtCall(jwt: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof jwt['header'] === 'string') calls.push(`.header(${rustStr(jwt['header'])})`);
  if (typeof jwt['scheme'] === 'string') calls.push(`.scheme(${rustStr(jwt['scheme'])})`);
  const claims = jwt['claims'];
  if (claims && typeof claims === 'object' && !Array.isArray(claims)) {
    for (const [k, v] of Object.entries(claims as Record<string, unknown>)) {
      calls.push(`.claim(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  if (typeof jwt['issuer'] === 'string') calls.push(`.issuer(${rustStr(jwt['issuer'])})`);
  if (typeof jwt['audience'] === 'string') calls.push(`.audience(${rustStr(jwt['audience'])})`);
  if (typeof jwt['algorithm'] === 'string') calls.push(`.algorithm(${rustStr(jwt['algorithm'])})`);
  return '.jwt(' + chain('Jwt::new()', calls, indent + 4) + ')';
}

// ---------------------------------------------------------------------------
// HttpRequest
// ---------------------------------------------------------------------------

/** Request keys the typed HttpRequest builder models directly. */
const REQUEST_BUILDER_KEYS = new Set([
  'method',
  'path',
  'headers',
  'queryStringParameters',
  'cookies',
  'pathParameters',
  'body',
  'jwt',
  'secure',
]);

function renderHttpRequest(req: Record<string, unknown>, ctx: Ctx, indent: number, name?: string): Rendered {
  const calls: string[] = [];
  if (typeof req['method'] === 'string') calls.push(`.method(${rustStr(req['method'])})`);
  if (typeof req['path'] === 'string') calls.push(`.path(${rustStr(req['path'])})`);

  const headers = req['headers'];
  if (headers && typeof headers === 'object') {
    for (const [k, vs] of Object.entries(headers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.header(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const query = req['queryStringParameters'];
  if (query && typeof query === 'object') {
    for (const [k, vs] of Object.entries(query as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.query_param(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const cookies = req['cookies'];
  if (cookies && typeof cookies === 'object') {
    for (const [k, v] of Object.entries(cookies as Record<string, string>)) {
      calls.push(`.cookie(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const pathParams = req['pathParameters'];
  if (pathParams && typeof pathParams === 'object') {
    for (const [k, vs] of Object.entries(pathParams as Record<string, unknown>)) {
      // Plain multi-value form (the only form the composer emits).
      if (Array.isArray(vs)) for (const v of vs) calls.push(`.path_param(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  if ('body' in req) calls.push(requestBodyCall(req['body'], indent));

  // A JWT with a `claims` object round-trips faithfully via the typed builder. A
  // claims-less JWT cannot (Jwt always serialises `claims: {}`), so it is carried
  // verbatim through `extra` below.
  const jwt = req['jwt'] as Record<string, unknown> | undefined;
  const jwtTyped = !!(jwt && jwt['claims'] && typeof jwt['claims'] === 'object');
  if (jwtTyped) calls.push(jwtCall(jwt!, indent));

  if (req['secure'] === true) calls.push('.secure(true)');

  // Fields with no dedicated builder (dnsName/dnsType/dnsClass DNS matcher, a
  // claims-less jwt, protocol, keepAlive, socketAddress, edit-passthrough fields,
  // …) go into the public `extra` catch-all map verbatim.
  const extras: [string, unknown][] = [];
  for (const [k, v] of Object.entries(req)) {
    if (REQUEST_BUILDER_KEYS.has(k)) {
      if (k === 'jwt' && !jwtTyped) extras.push([k, v]); // claims-less jwt → raw
      continue;
    }
    extras.push([k, v]);
  }

  if (extras.length === 0) {
    return calls.length === 0 ? inline('HttpRequest::new()') : inline(chain('HttpRequest::new()', calls, indent));
  }
  const varName = name ?? ctx.temp();
  const setup: string[] = [];
  setup.push(pad(indent) + `let mut ${varName} = ` + chain('HttpRequest::new()', calls, indent) + ';');
  for (const [k, v] of extras) {
    setup.push(pad(indent) + `${varName}.extra.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, indent)});`);
  }
  return { setup, expr: varName };
}

// ---------------------------------------------------------------------------
// ConnectionOptions
// ---------------------------------------------------------------------------

const CONNECTION_OPTION_FIELDS: Record<string, string> = {
  keepAliveOverride: 'keep_alive_override',
  closeSocket: 'close_socket',
  contentLengthHeaderOverride: 'content_length_header_override',
  suppressContentLengthHeader: 'suppress_content_length_header',
  suppressConnectionHeader: 'suppress_connection_header',
};

function connectionOptionsExpr(co: Record<string, unknown>, indent: number): string {
  const fields: string[] = [];
  for (const [wireKey, rustField] of Object.entries(CONNECTION_OPTION_FIELDS)) {
    if (wireKey in co) {
      const v = co[wireKey];
      const lit = typeof v === 'boolean' ? (v ? 'true' : 'false') : numLit(Number(v));
      fields.push(`${rustField}: Some(${lit}),`);
    }
  }
  const inner = pad(indent + 4);
  return 'ConnectionOptions {\n' + fields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
}

// ---------------------------------------------------------------------------
// HttpResponse
// ---------------------------------------------------------------------------

/** Response keys with a dedicated HttpResponse builder / typed field. */
const RESPONSE_BUILDER_KEYS = new Set([
  'statusCode',
  'headers',
  'body',
  'delay',
  'cookies',
  'reasonPhrase',
  'statusCodeRange',
  'trailers',
  'generateFromSchema',
  'connectionOptions',
]);

function renderHttpResponse(resp: Record<string, unknown>, ctx: Ctx, indent: number, name?: string): Rendered {
  const calls: string[] = [];
  if (typeof resp['statusCode'] === 'number') calls.push(`.status_code(${numLit(resp['statusCode'])})`);

  // A plain string body uses the typed String body field; a FILE/typed-object body
  // has no String-typed builder, so it is carried through `extra`.
  const bodyIsObject = resp['body'] != null && typeof resp['body'] === 'object';
  if (typeof resp['body'] === 'string') calls.push(`.body(${rustStr(resp['body'])})`);

  const headers = resp['headers'];
  if (headers && typeof headers === 'object') {
    for (const [k, vs] of Object.entries(headers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.header(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const trailers = resp['trailers'];
  if (trailers && typeof trailers === 'object') {
    for (const [k, vs] of Object.entries(trailers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.trailer(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const cookies = resp['cookies'];
  if (cookies && typeof cookies === 'object') {
    for (const [k, v] of Object.entries(cookies as Record<string, string>)) {
      calls.push(`.cookie(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  if (typeof resp['reasonPhrase'] === 'string') calls.push(`.reason_phrase(${rustStr(resp['reasonPhrase'])})`);
  if (typeof resp['statusCodeRange'] === 'string') calls.push(`.status_code_range(${rustStr(resp['statusCodeRange'])})`);
  if (typeof resp['generateFromSchema'] === 'string') calls.push(`.generate_from_schema(${rustStr(resp['generateFromSchema'])})`);
  if (resp['connectionOptions'] && typeof resp['connectionOptions'] === 'object') {
    calls.push(`.connection_options(${connectionOptionsExpr(resp['connectionOptions'] as Record<string, unknown>, indent + 4)})`);
  }
  if (resp['delay'] && typeof resp['delay'] === 'object') {
    calls.push(`.delay(${delayExpr(resp['delay'] as Record<string, unknown>)})`);
  }

  const extras: [string, unknown][] = [];
  if (bodyIsObject) extras.push(['body', resp['body']]);
  for (const [k, v] of Object.entries(resp)) {
    if (RESPONSE_BUILDER_KEYS.has(k)) continue;
    extras.push([k, v]);
  }

  if (extras.length === 0) {
    return calls.length === 0 ? inline('HttpResponse::new()') : inline(chain('HttpResponse::new()', calls, indent));
  }
  const varName = name ?? ctx.temp();
  const setup: string[] = [];
  setup.push(pad(indent) + `let mut ${varName} = ` + chain('HttpResponse::new()', calls, indent) + ';');
  for (const [k, v] of extras) {
    setup.push(pad(indent) + `${varName}.extra.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, indent)});`);
  }
  return { setup, expr: varName };
}

// ---------------------------------------------------------------------------
// HttpForward / HttpTemplate / HttpError
// ---------------------------------------------------------------------------

function forwardExpr(f: Record<string, unknown>, indent: number): string {
  const host = rustStr(String(f['host'] ?? ''));
  const port = typeof f['port'] === 'number' ? numLit(f['port']) : '0';
  const calls: string[] = [];
  if (typeof f['scheme'] === 'string') calls.push(`.scheme(${rustStr(f['scheme'])})`);
  if (f['delay'] && typeof f['delay'] === 'object') calls.push(`.delay(${delayExpr(f['delay'] as Record<string, unknown>)})`);
  return chain(`HttpForward::new(${host}, ${port})`, calls, indent);
}

function templateExpr(t: Record<string, unknown>, indent: number): string {
  const type = rustStr(String(t['templateType'] ?? ''));
  const file = t['templateFile'];
  if (typeof file === 'string') {
    const calls: string[] = [];
    if (typeof t['template'] === 'string') calls.push(`.template(${rustStr(t['template'])})`);
    return chain(`HttpTemplate::from_file(${type}, ${rustStr(file)})`, calls, indent);
  }
  return `HttpTemplate::new(${type}, ${rustStr(String(t['template'] ?? ''))})`;
}

function errorExpr(e: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (e['dropConnection'] === true) calls.push('.drop_connection(true)');
  if (typeof e['responseBytes'] === 'string') calls.push(`.response_bytes(${rustStr(e['responseBytes'])})`);
  if (e['delay'] && typeof e['delay'] === 'object') calls.push(`.delay(${delayExpr(e['delay'] as Record<string, unknown>)})`);
  return chain('HttpError::new()', calls, indent);
}

// ---------------------------------------------------------------------------
// SSE / WebSocket / DNS / Binary / gRPC-stream
// ---------------------------------------------------------------------------

function sseEventExpr(ev: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof ev['event'] === 'string') calls.push(`.event(${rustStr(ev['event'])})`);
  if (typeof ev['data'] === 'string') calls.push(`.data(${rustStr(ev['data'])})`);
  if (typeof ev['id'] === 'string') calls.push(`.id(${rustStr(ev['id'])})`);
  if (typeof ev['retry'] === 'number') calls.push(`.retry(${numLit(ev['retry'])})`);
  if (ev['delay'] && typeof ev['delay'] === 'object') calls.push(`.delay(${delayExpr(ev['delay'] as Record<string, unknown>)})`);
  return chain('SseEvent::new()', calls, indent);
}

function sseExpr(sse: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof sse['statusCode'] === 'number') calls.push(`.status_code(${numLit(sse['statusCode'])})`);
  const headers = sse['headers'];
  if (headers && typeof headers === 'object') {
    for (const [k, vs] of Object.entries(headers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.header(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const events = sse['events'];
  if (Array.isArray(events)) for (const ev of events) calls.push(`.event(${sseEventExpr(ev as Record<string, unknown>, indent + 4)})`);
  if (sse['closeConnection'] === true) calls.push('.close_connection(true)');
  if (sse['delay'] && typeof sse['delay'] === 'object') calls.push(`.delay(${delayExpr(sse['delay'] as Record<string, unknown>)})`);
  return chain('HttpSseResponse::new()', calls, indent);
}

function wsMessageExpr(m: Record<string, unknown>): string {
  if (typeof m['binary'] === 'string') return `WebSocketMessage::binary_base64(${rustStr(m['binary'])})`;
  return `WebSocketMessage::text(${rustStr(String(m['text'] ?? ''))})`;
}

function wsMatcherExpr(m: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof m['frameType'] === 'string') calls.push(`.frame_type(${rustStr(m['frameType'])})`);
  if (typeof m['textMatcher'] === 'string') calls.push(`.text_matcher(${rustStr(m['textMatcher'])})`);
  const responses = m['responses'];
  if (Array.isArray(responses)) for (const r of responses) calls.push(`.response(${wsMessageExpr(r as Record<string, unknown>)})`);
  return chain('WebSocketMatcher::new()', calls, indent);
}

function webSocketExpr(ws: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof ws['subprotocol'] === 'string') calls.push(`.subprotocol(${rustStr(ws['subprotocol'])})`);
  const messages = ws['messages'];
  if (Array.isArray(messages)) for (const m of messages) calls.push(`.message(${wsMessageExpr(m as Record<string, unknown>)})`);
  const matchers = ws['matchers'];
  if (Array.isArray(matchers)) for (const m of matchers) calls.push(`.matcher(${wsMatcherExpr(m as Record<string, unknown>, indent + 4)})`);
  if (ws['closeConnection'] === true) calls.push('.close_connection(true)');
  if (ws['delay'] && typeof ws['delay'] === 'object') calls.push(`.delay(${delayExpr(ws['delay'] as Record<string, unknown>)})`);
  return chain('HttpWebSocketResponse::new()', calls, indent);
}

const DNS_RECORD_FIELDS = new Set(['name', 'type', 'ttl', 'value', 'class', 'dnsClass', 'priority', 'weight', 'port']);

/** A DNS record maps to typed DnsRecord builders only when every field is one the
 *  typed model names; otherwise the caller carries the whole dnsResponse as raw JSON. */
function dnsRecordExpr(rec: Record<string, unknown>, indent: number): string | undefined {
  for (const k of Object.keys(rec)) if (!DNS_RECORD_FIELDS.has(k)) return undefined;
  const calls: string[] = [];
  if (typeof rec['name'] === 'string') calls.push(`.name(${rustStr(rec['name'])})`);
  if (typeof rec['type'] === 'string') calls.push(`.record_type(${rustStr(rec['type'])})`);
  const cls = rec['dnsClass'] ?? rec['class'];
  if (typeof cls === 'string') calls.push(`.dns_class(${rustStr(cls)})`);
  if (typeof rec['ttl'] === 'number') calls.push(`.ttl(${numLit(rec['ttl'])})`);
  if (typeof rec['value'] === 'string') calls.push(`.value(${rustStr(rec['value'])})`);
  if (typeof rec['priority'] === 'number') calls.push(`.priority(${numLit(rec['priority'])})`);
  if (typeof rec['weight'] === 'number') calls.push(`.weight(${numLit(rec['weight'])})`);
  if (typeof rec['port'] === 'number') calls.push(`.port(${numLit(rec['port'])})`);
  return chain('DnsRecord::new()', calls, indent);
}

/** Returns a typed DnsResponse expression, or undefined when a record carries a
 *  field the typed DnsRecord cannot represent (then the caller emits raw JSON). */
function dnsResponseExpr(dns: Record<string, unknown>, indent: number): string | undefined {
  const calls: string[] = [];
  if (typeof dns['responseCode'] === 'string') calls.push(`.response_code(${rustStr(dns['responseCode'])})`);
  const sections: [string, string][] = [
    ['answerRecords', 'answer_record'],
    ['authorityRecords', 'authority_record'],
    ['additionalRecords', 'additional_record'],
  ];
  for (const [wireKey, method] of sections) {
    const arr = dns[wireKey];
    if (arr === undefined) continue;
    if (!Array.isArray(arr)) return undefined;
    for (const rec of arr) {
      const recExpr = dnsRecordExpr(rec as Record<string, unknown>, indent + 4);
      if (recExpr === undefined) return undefined;
      calls.push(`.${method}(${recExpr})`);
    }
  }
  return chain('DnsResponse::new()', calls, indent);
}

function grpcStreamExpr(grpc: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof grpc['statusName'] === 'string') calls.push(`.status_name(${rustStr(grpc['statusName'])})`);
  if (typeof grpc['statusMessage'] === 'string') calls.push(`.status_message(${rustStr(grpc['statusMessage'])})`);
  const headers = grpc['headers'];
  if (headers && typeof headers === 'object') {
    for (const [k, vs] of Object.entries(headers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.header(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const messages = grpc['messages'];
  if (Array.isArray(messages)) {
    for (const m of messages) {
      const mo = m as Record<string, unknown>;
      let call = `GrpcStreamMessage::json(${rustStr(String(mo['json'] ?? ''))})`;
      if (typeof mo['templateType'] === 'string') call += `.template_type(${rustStr(mo['templateType'])})`;
      calls.push(`.message(${call})`);
    }
  }
  if (grpc['closeConnection'] === true) calls.push('.close_connection(true)');
  if (grpc['delay'] && typeof grpc['delay'] === 'object') calls.push(`.delay(${delayExpr(grpc['delay'] as Record<string, unknown>)})`);
  return chain('GrpcStreamResponse::new()', calls, indent);
}

// ---------------------------------------------------------------------------
// Chaos — a typed struct literal (only 5 of its fields have builder methods).
// ---------------------------------------------------------------------------

function chaosExpr(c: Record<string, unknown>, indent: number): string {
  const fields: string[] = [];
  if (typeof c['errorStatus'] === 'number') fields.push(`error_status: Some(${numLit(c['errorStatus'])}),`);
  if (typeof c['errorProbability'] === 'number') fields.push(`error_probability: Some(${floatLit(c['errorProbability'])}),`);
  if (typeof c['retryAfter'] === 'string') fields.push(`retry_after: Some(${rustStr(c['retryAfter'])}.to_string()),`);
  if (c['latency'] && typeof c['latency'] === 'object') fields.push(`latency: Some(${delayExpr(c['latency'] as Record<string, unknown>)}),`);
  if (typeof c['seed'] === 'number') fields.push(`seed: Some(${numLit(c['seed'])}),`);
  if (typeof c['succeedFirst'] === 'number') fields.push(`succeed_first: Some(${numLit(c['succeedFirst'])}),`);
  if (typeof c['failRequestCount'] === 'number') fields.push(`fail_request_count: Some(${numLit(c['failRequestCount'])}),`);
  const inner = pad(indent + 4);
  return 'HttpChaosProfile {\n' + fields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
}

// ---------------------------------------------------------------------------
// Side-effect actions (before/after) + capture
// ---------------------------------------------------------------------------

/** Render an ExpectationAction; pre-binds a nested HttpRequest when needed. */
function renderExpectationAction(se: Record<string, unknown>, ctx: Ctx, indent: number): Rendered {
  const setup: string[] = [];
  let reqExpr = 'HttpRequest::new()';
  const req = se['httpRequest'];
  if (req && typeof req === 'object') {
    const r = renderHttpRequest(req as Record<string, unknown>, ctx, indent);
    setup.push(...r.setup);
    reqExpr = r.expr;
  }

  const hasEnvelope =
    (se['delay'] && typeof se['delay'] === 'object') ||
    'blocking' in se ||
    (se['timeout'] && typeof se['timeout'] === 'object') ||
    typeof se['failurePolicy'] === 'string';

  if (!hasEnvelope) return { setup, expr: `ExpectationAction::request(${reqExpr})` };

  // Envelope fields have no builder methods — use a typed struct literal.
  const inner = pad(indent + 4);
  const fields: string[] = [`http_request: Some(${reqExpr}),`];
  if (se['delay'] && typeof se['delay'] === 'object') fields.push(`delay: Some(${delayExpr(se['delay'] as Record<string, unknown>)}),`);
  if ('blocking' in se) fields.push(`blocking: Some(${se['blocking'] === true ? 'true' : 'false'}),`);
  if (se['timeout'] && typeof se['timeout'] === 'object') fields.push(`timeout: Some(${delayExpr(se['timeout'] as Record<string, unknown>)}),`);
  if (typeof se['failurePolicy'] === 'string') fields.push(`failure_policy: Some(${rustStr(se['failurePolicy'])}.to_string()),`);
  const expr = 'ExpectationAction {\n' + fields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
  return { setup, expr };
}

function captureRuleExpr(c: Record<string, unknown>): string {
  return `CaptureRule::new(${rustStr(String(c['source'] ?? ''))}, ${rustStr(String(c['expression'] ?? ''))}, ${rustStr(String(c['into'] ?? ''))})`;
}

// ---------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------

/** Action-payload key → the ExpectationStep field it maps to (typed). */
const STEP_ACTION_FIELD: Record<string, { field: string; render: (o: Record<string, unknown>, ctx: Ctx, indent: number) => Rendered }> = {
  httpResponse: { field: 'http_response', render: renderHttpResponse },
  httpRequest: { field: 'http_request', render: renderHttpRequest },
};

function renderStep(step: Record<string, unknown>, ctx: Ctx, indent: number): Rendered {
  const setup: string[] = [];
  const fields: string[] = [];
  const rawExtras: [string, unknown][] = [];

  for (const [k, v] of Object.entries(step)) {
    if (k === 'responder') {
      if (v === true) fields.push('responder: Some(true),');
      continue;
    }
    if (k === 'delay' && v && typeof v === 'object') {
      fields.push(`delay: Some(${delayExpr(v as Record<string, unknown>)}),`);
      continue;
    }
    if (k === 'timeout' && v && typeof v === 'object') {
      fields.push(`timeout: Some(${delayExpr(v as Record<string, unknown>)}),`);
      continue;
    }
    if (k === 'blocking') {
      fields.push(`blocking: Some(${v === true ? 'true' : 'false'}),`);
      continue;
    }
    if (k === 'failurePolicy' && typeof v === 'string') {
      fields.push(`failure_policy: Some(${rustStr(v)}.to_string()),`);
      continue;
    }
    const mapping = STEP_ACTION_FIELD[k];
    if (mapping && v && typeof v === 'object' && !Array.isArray(v)) {
      const r = mapping.render(v as Record<string, unknown>, ctx, indent);
      setup.push(...r.setup);
      fields.push(`${mapping.field}: Some(${r.expr}),`);
    } else {
      // httpForward/httpError/httpClassCallback/httpOverrideForwardedRequest or a
      // free-text payload the typed step fields cannot structurally guarantee:
      // carry it verbatim through the step's `extra` catch-all.
      rawExtras.push([k, v]);
    }
  }

  const inner = pad(indent + 4);
  const structFields = [...fields];
  if (rawExtras.length > 0) {
    const mapEntries = rawExtras
      .map(([k, v]) => inner + '    ' + `m.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, indent + 8)});`)
      .join('\n');
    structFields.push(
      'extra: {\n' + inner + '    let mut m = serde_json::Map::new();\n' + mapEntries + '\n' + inner + '    m\n' + inner + '},',
    );
  }
  const expr =
    'ExpectationStep {\n' + structFields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
  return { setup, expr };
}

// ---------------------------------------------------------------------------
// Primary action dispatch
// ---------------------------------------------------------------------------

type PrimaryRendered = Rendered | { expectationExtra: [string, unknown] };

const PRIMARY_ACTION_KEYS = [
  'httpResponse',
  'httpForward',
  'httpResponseTemplate',
  'httpForwardTemplate',
  'httpError',
  'httpResponseClassCallback',
  'httpForwardClassCallback',
  'httpSseResponse',
  'httpWebSocketResponse',
  'binaryResponse',
  'grpcStreamResponse',
  'dnsResponse',
  'httpOverrideForwardedRequest',
  'httpForwardWithFallback',
];

/** Map a top-level action-family key onto the Expectation terminal builder call. */
function renderPrimaryAction(key: string, value: unknown, ctx: Ctx, indent: number): PrimaryRendered {
  const obj = value as Record<string, unknown>;
  switch (key) {
    case 'httpResponse': {
      const r = renderHttpResponse(obj, ctx, indent + 4, 'response');
      return { setup: r.setup, expr: `.respond(${r.expr})` };
    }
    case 'httpForward':
      return inline(`.forward(${forwardExpr(obj, indent + 4)})`);
    case 'httpResponseTemplate':
      return inline(`.respond_template(${templateExpr(obj, indent + 4)})`);
    case 'httpForwardTemplate':
      return inline(`.forward_template(${templateExpr(obj, indent + 4)})`);
    case 'httpError':
      return inline(`.error(${errorExpr(obj, indent + 4)})`);
    case 'httpResponseClassCallback':
      return inline(`.respond_with_class_callback(${rustStr(String(obj['callbackClass'] ?? ''))})`);
    case 'httpForwardClassCallback':
      return inline(`.forward_with_class_callback(${rustStr(String(obj['callbackClass'] ?? ''))})`);
    case 'httpSseResponse':
      return inline(`.respond_sse(${sseExpr(obj, indent + 4)})`);
    case 'httpWebSocketResponse':
      return inline(`.respond_web_socket(${webSocketExpr(obj, indent + 4)})`);
    case 'binaryResponse':
      return inline(`.respond_binary(BinaryResponse::from_base64(${rustStr(String(obj['binaryData'] ?? ''))}))`);
    case 'grpcStreamResponse':
      return inline(`.respond_grpc_stream(${grpcStreamExpr(obj, indent + 4)})`);
    case 'dnsResponse': {
      const typed = dnsResponseExpr(obj, indent + 4);
      if (typed) return inline(`.respond_dns(${typed})`);
      return { expectationExtra: ['dnsResponse', obj] }; // record shape the typed model cannot name
    }
    case 'httpOverrideForwardedRequest': {
      const setup: string[] = [];
      const calls: string[] = [];
      const ro = obj['requestOverride'];
      if (ro && typeof ro === 'object') {
        const r = renderHttpRequest(ro as Record<string, unknown>, ctx, indent, 'override_request');
        setup.push(...r.setup);
        calls.push(`.request_override(${r.expr})`);
      }
      const rso = obj['responseOverride'];
      if (rso && typeof rso === 'object') {
        const r = renderHttpResponse(rso as Record<string, unknown>, ctx, indent, 'override_response');
        setup.push(...r.setup);
        calls.push(`.response_override(${r.expr})`);
      }
      return { setup, expr: `.override_forwarded_request(${chain('HttpOverrideForwardedRequest::new()', calls, indent + 4)})` };
    }
    case 'httpForwardWithFallback': {
      const setup: string[] = [];
      const fwd = (obj['httpForward'] as Record<string, unknown>) ?? {};
      const fbResp = (obj['fallbackResponse'] as Record<string, unknown>) ?? {};
      const fbRendered = renderHttpResponse(fbResp, ctx, indent + 4, 'fallback_response');
      setup.push(...fbRendered.setup);
      const calls: string[] = [];
      const codes = obj['fallbackOnStatusCodes'];
      if (Array.isArray(codes)) calls.push(`.fallback_on_status_codes(vec![${codes.map((c) => numLit(Number(c))).join(', ')}])`);
      if (obj['fallbackOnTimeout'] === true) calls.push('.fallback_on_timeout(true)');
      const head = `HttpForwardWithFallback::new(${forwardExpr(fwd, indent + 4)}, ${fbRendered.expr})`;
      return { setup, expr: `.forward_with_fallback(${chain(head, calls, indent + 4)})` };
    }
    default:
      return { expectationExtra: [key, value] };
  }
}

const KNOWN_TOP_LEVEL_KEYS = new Set([
  'httpRequest',
  ...PRIMARY_ACTION_KEYS,
  'chaos',
  'beforeActions',
  'afterActions',
  'steps',
  'capture',
  'scenarioName',
  'scenarioState',
  'newScenarioState',
  'namespace',
  'id',
  'priority',
  'times',
  'timeToLive',
]);

// ---------------------------------------------------------------------------
// Top-level emitter
// ---------------------------------------------------------------------------

export function standardToRust(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const exp = buildExpectationJson(matcher, action);
  const ctx = new Ctx();

  const setup: string[] = [];
  const expCalls: string[] = [];
  const expectationExtras: [string, unknown][] = [];

  // Request matcher.
  const reqObj = (exp['httpRequest'] as Record<string, unknown>) ?? {};
  const reqRendered = renderHttpRequest(reqObj, ctx, 4, 'request');
  let requestExpr: string;
  if (reqRendered.setup.length > 0) {
    setup.push(...reqRendered.setup);
    requestExpr = reqRendered.expr;
  } else {
    setup.push(pad(4) + `let request = ${reqRendered.expr};`);
    requestExpr = 'request';
  }

  // Primary (mutually-exclusive) action.
  for (const key of PRIMARY_ACTION_KEYS) {
    if (key in exp) {
      const rendered = renderPrimaryAction(key, exp[key], ctx, 4);
      if ('expectationExtra' in rendered) expectationExtras.push(rendered.expectationExtra);
      else {
        setup.push(...rendered.setup);
        expCalls.push(rendered.expr);
      }
      break;
    }
  }

  // Steps replace the primary action when present.
  if (Array.isArray(exp['steps'])) {
    const inner = pad(8);
    const stepExprs: string[] = [];
    for (const step of exp['steps'] as unknown[]) {
      const r = renderStep(step as Record<string, unknown>, ctx, 8);
      setup.push(...r.setup);
      stepExprs.push(inner + r.expr + ',');
    }
    expCalls.length = 0; // steps take priority over any primary action
    expCalls.push('.steps(vec![\n' + stepExprs.join('\n') + '\n' + pad(4) + '])');
  }

  // Side-effect actions.
  for (const [wireKey, method] of [
    ['beforeActions', 'before_action'],
    ['afterActions', 'after_action'],
  ] as const) {
    const arr = exp[wireKey];
    if (Array.isArray(arr)) {
      for (const se of arr) {
        const r = renderExpectationAction(se as Record<string, unknown>, ctx, 8);
        setup.push(...r.setup);
        expCalls.push(`.${method}(${r.expr})`);
      }
    }
  }

  // Capture rules.
  if (Array.isArray(exp['capture'])) {
    for (const c of exp['capture'] as unknown[]) expCalls.push(`.capture_rule(${captureRuleExpr(c as Record<string, unknown>)})`);
  }

  // Chaos.
  if (exp['chaos'] && typeof exp['chaos'] === 'object') expCalls.push(`.chaos(${chaosExpr(exp['chaos'] as Record<string, unknown>, 8)})`);

  // Scenario bindings.
  if (typeof exp['scenarioName'] === 'string') expCalls.push(`.scenario_name(${rustStr(exp['scenarioName'])})`);
  if (typeof exp['scenarioState'] === 'string') expCalls.push(`.scenario_state(${rustStr(exp['scenarioState'])})`);
  if (typeof exp['newScenarioState'] === 'string') expCalls.push(`.new_scenario_state(${rustStr(exp['newScenarioState'])})`);

  // Namespace / id / priority / times / ttl.
  if (typeof exp['namespace'] === 'string') expCalls.push(`.namespace(${rustStr(exp['namespace'])})`);
  if (typeof exp['id'] === 'string') expCalls.push(`.id(${rustStr(exp['id'])})`);
  if (typeof exp['priority'] === 'number') expCalls.push(`.priority(${numLit(exp['priority'])})`);
  if (exp['times'] && typeof exp['times'] === 'object') expCalls.push(`.times(${timesExpr(exp['times'] as Record<string, unknown>)})`);
  if (exp['timeToLive'] && typeof exp['timeToLive'] === 'object') expCalls.push(`.time_to_live(${ttlExpr(exp['timeToLive'] as Record<string, unknown>)})`);

  // Any remaining unmodelled top-level key → the expectation's `extra` catch-all.
  for (const [k, v] of Object.entries(exp)) {
    if (!KNOWN_TOP_LEVEL_KEYS.has(k)) expectationExtras.push([k, v]);
  }

  // Assemble the Expectation binding.
  const needsMut = expectationExtras.length > 0;
  const usesSerde =
    expectationExtras.length > 0 || [...setup, ...expCalls].some((l) => l.includes('serde_json::'));

  const body: string[] = [];
  body.push('// Cargo.toml: mockserver-client = "7"' + (usesSerde ? ', serde_json = "1"' : ''));
  body.push('use mockserver_client::*;');
  body.push('');
  body.push('fn main() -> mockserver_client::Result<()> {');
  body.push(pad(4) + `let client = ClientBuilder::new(${rustStr(host)}, ${port}).build()?;`);
  body.push('');
  for (const line of setup) body.push(line);
  body.push('');
  body.push(pad(4) + `let ${needsMut ? 'mut ' : ''}expectation = ` + chain(`Expectation::new(${requestExpr})`, expCalls, 4) + ';');
  for (const [k, v] of expectationExtras) {
    body.push(pad(4) + `expectation.extra.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, 4)});`);
  }
  body.push(pad(4) + 'client.upsert(&[expectation])?;');
  body.push(pad(4) + 'Ok(())');
  body.push('}');
  return body.join('\n');
}
