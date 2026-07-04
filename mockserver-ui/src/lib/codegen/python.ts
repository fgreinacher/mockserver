/**
 * Python client-library emitter.
 *
 * Emits IDIOMATIC, TYPED client code — the same style as the published website
 * examples and the `mockserver-client-python` test-suite:
 *
 *     client.upsert(
 *         Expectation(
 *             http_request=HttpRequest(method="POST", path="/order"),
 *             http_response=HttpResponse(status_code=201, body="..."),
 *         )
 *     )
 *
 * i.e. every expectation field is built with the type-specific dataclass /
 * matcher / body object it maps to (HttpRequest, HttpResponse, Body, Jwt,
 * ConnectionOptions, AfterAction, ExpectationStep, DnsRecord, …) rather than one
 * opaque `Expectation.from_dict({ ...JSON... })` blob.
 *
 * The translator walks the canonical wire object produced by
 * {@link buildExpectationJson} and maps EVERY key the composer can emit onto a
 * typed constructor (see the coverage table in the python-specific test). There
 * is deliberately NO raw-dict passthrough of an expectation field — the only raw
 * Python literals emitted are the genuine *content* of typed fields (a JSON body
 * value, a JSON-schema string, DNS record data), which are data, not structure.
 *
 * Two encodings the typed client emits differ cosmetically from the JSON tab but
 * are accepted identically by MockServer (proven by the round-trip execution
 * test): header/query lists (`[KeyToMultiValue(...)]`) vs the JSON object-map
 * form, and `HttpOverrideForwardedRequest(http_request=...)` which serialises to
 * the `httpRequest` alias of the wire `requestOverride` field.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen.ts';
import { clientHostPort, toPythonLiteral } from './shared.ts';

type Json = Record<string, unknown>;
type Kw = [string, string];

/** A Python string literal. JSON string escaping is a subset of Python's, so a
 *  JSON.stringify result is always a valid Python double-quoted literal. */
function pyStr(s: unknown): string {
  return JSON.stringify(String(s));
}

function pyNum(n: unknown): string {
  return String(n);
}

function pyBool(b: unknown): string {
  return b ? 'True' : 'False';
}

/** Render a constructor call across multiple lines (used for container objects). */
function renderCall(name: string, kwargs: Kw[], indent: number): string {
  if (kwargs.length === 0) return `${name}()`;
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 4);
  const body = kwargs.map(([k, v]) => `${pad2}${k}=${v}`).join(',\n');
  return `${name}(\n${body},\n${pad})`;
}

/** Render a constructor call on a single line (used for small leaf objects like
 *  KeyToMultiValue / Delay / SseEvent whose args are all scalars). */
function renderInline(name: string, kwargs: Kw[]): string {
  return `${name}(${kwargs.map(([k, v]) => `${k}=${v}`).join(', ')})`;
}

/** Render a list; single-element lists stay on one line, longer lists break. */
function renderList(items: string[], indent: number): string {
  if (items.length === 0) return '[]';
  const only = items.length === 1 ? items[0] : undefined;
  if (only !== undefined && !only.includes('\n')) return `[${only}]`;
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 4);
  return `[\n${items.map((i) => pad2 + i).join(',\n')},\n${pad}]`;
}

function strArray(values: unknown): string {
  const arr = Array.isArray(values) ? values : [];
  return `[${arr.map((v) => pyStr(v)).join(', ')}]`;
}

function intArray(values: unknown): string {
  const arr = Array.isArray(values) ? values : [];
  return `[${arr.map((v) => pyNum(v)).join(', ')}]`;
}

// ---------------------------------------------------------------------------
// Emitter — walks buildExpectationJson output into typed constructors.
// ---------------------------------------------------------------------------

class PyBuilder {
  readonly imports = new Set<string>();

  private use(name: string): void {
    this.imports.add(name);
  }

  /** `{name: [values]}` object-map (headers / query params) → `[KeyToMultiValue(...)]`. */
  private keyMultiList(map: unknown, indent: number): string {
    this.use('KeyToMultiValue');
    const entries = Object.entries((map ?? {}) as Json);
    const items = entries.map(([k, vals]) =>
      renderInline('KeyToMultiValue', [['name', pyStr(k)], ['values', strArray(vals)]]),
    );
    return renderList(items, indent);
  }

  /** `{name: value}` single-value cookie map → `[KeyToMultiValue(name=.., values=[..])]`. */
  private cookieList(map: unknown, indent: number): string {
    this.use('KeyToMultiValue');
    const entries = Object.entries((map ?? {}) as Json);
    const items = entries.map(([k, v]) =>
      renderInline('KeyToMultiValue', [['name', pyStr(k)], ['values', `[${pyStr(v)}]`]]),
    );
    return renderList(items, indent);
  }

  private delay(v: unknown): string {
    this.use('Delay');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['timeUnit'] != null) kw.push(['time_unit', pyStr(o['timeUnit'])]);
    if (o['value'] != null) kw.push(['value', pyNum(o['value'])]);
    return renderInline('Delay', kw);
  }

  /** A request/response body wire value → the typed Body / *Body matcher. */
  private body(v: unknown, indent: number): string {
    if (typeof v === 'string') return pyStr(v);
    if (!v || typeof v !== 'object') return pyStr(v);
    const o = v as Json;
    const type = o['type'];
    switch (type) {
      case 'JSON': {
        this.use('Body');
        const kw: Kw[] = [['type', '"JSON"'], ['json', toPythonLiteral(o['json'], indent + 4)]];
        if (o['matchType'] != null) kw.push(['match_type', pyStr(o['matchType'])]);
        return renderCall('Body', kw, indent);
      }
      case 'STRING': {
        this.use('Body');
        const kw: Kw[] = [['type', '"STRING"'], ['string', pyStr(o['string'])]];
        if (o['subString']) kw.push(['sub_string', 'True']);
        return renderInline('Body', kw);
      }
      case 'BINARY':
        this.use('Body');
        return renderInline('Body', [['type', '"BINARY"'], ['base64_bytes', pyStr(o['base64Bytes'])]]);
      case 'FILE': {
        this.use('Body');
        const kw: Kw[] = [['type', '"FILE"'], ['file_path', pyStr(o['filePath'])]];
        if (o['templateType'] != null) kw.push(['template_type', pyStr(o['templateType'])]);
        if (o['contentType'] != null) kw.push(['content_type', pyStr(o['contentType'])]);
        return renderInline('Body', kw);
      }
      case 'GRAPHQL': {
        this.use('GraphQLBody');
        const kw: Kw[] = [['query', pyStr(o['query'])]];
        if (o['selectionSetMatchType'] != null) kw.push(['selection_set_match_type', pyStr(o['selectionSetMatchType'])]);
        if (o['fields'] != null) kw.push(['fields', strArray(o['fields'])]);
        return renderCall('GraphQLBody', kw, indent);
      }
      case 'JSON_SCHEMA':
        this.use('JsonSchemaBody');
        return renderCall('JsonSchemaBody', [['json_schema', pyStr(o['jsonSchema'])]], indent);
      case 'JSON_PATH':
        this.use('JsonPathBody');
        return renderInline('JsonPathBody', [['json_path', pyStr(o['jsonPath'])]]);
      case 'XML':
        this.use('XmlBody');
        return renderInline('XmlBody', [['xml', pyStr(o['xml'])]]);
      case 'XML_SCHEMA':
        this.use('XmlSchemaBody');
        return renderCall('XmlSchemaBody', [['xml_schema', pyStr(o['xmlSchema'])]], indent);
      case 'XPATH':
        this.use('XPathBody');
        return renderInline('XPathBody', [['xpath', pyStr(o['xpath'])]]);
      case 'REGEX':
        this.use('RegexBody');
        return renderInline('RegexBody', [['regex', pyStr(o['regex'])]]);
      case 'WASM':
        this.use('WasmBody');
        return renderInline('WasmBody', [['module_name', pyStr(o['moduleName'])]]);
      case 'PARAMETERS':
        this.use('ParameterBody');
        return renderCall('ParameterBody', [
          ['parameters', this.keyMultiList(o['parameters'], indent + 4)],
          ['parameters_as_map', 'True'],
        ], indent);
      case 'ALL_OF': {
        this.use('AllOfBody');
        const subs = (o['bodyAllOf'] as unknown[]) ?? [];
        const items = subs.map((s) => this.body(s, indent + 8));
        return renderCall('AllOfBody', [['body_all_of', renderList(items, indent + 4)]], indent);
      }
      default:
        // Should never occur for composer output; fall back to a Python literal
        // so no data is silently lost.
        return toPythonLiteral(v, indent);
    }
  }

  private jwt(v: unknown, indent: number): string {
    this.use('Jwt');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['claims'] != null) kw.push(['claims', toPythonLiteral(o['claims'], indent + 4)]);
    if (o['issuer'] != null) kw.push(['issuer', pyStr(o['issuer'])]);
    if (o['audience'] != null) kw.push(['audience', pyStr(o['audience'])]);
    if (o['algorithm'] != null) kw.push(['algorithm', pyStr(o['algorithm'])]);
    if (o['header'] != null) kw.push(['header', pyStr(o['header'])]);
    if (o['scheme'] != null) kw.push(['scheme', pyStr(o['scheme'])]);
    return renderCall('Jwt', kw, indent);
  }

  private request(v: Json, indent: number): string {
    this.use('HttpRequest');
    const kw: Kw[] = [];
    if (v['method'] != null) kw.push(['method', pyStr(v['method'])]);
    if (v['path'] != null) kw.push(['path', pyStr(v['path'])]);
    if (v['headers'] != null) kw.push(['headers', this.keyMultiList(v['headers'], indent + 4)]);
    if (v['queryStringParameters'] != null) kw.push(['query_string_parameters', this.keyMultiList(v['queryStringParameters'], indent + 4)]);
    if (v['cookies'] != null) kw.push(['cookies', this.cookieList(v['cookies'], indent + 4)]);
    if (v['pathParameters'] != null) kw.push(['path_parameters', this.keyMultiList(v['pathParameters'], indent + 4)]);
    if (v['body'] != null) kw.push(['body', this.body(v['body'], indent + 4)]);
    if (v['jwt'] != null) kw.push(['jwt', this.jwt(v['jwt'], indent + 4)]);
    if (v['secure']) kw.push(['secure', 'True']);
    return renderCall('HttpRequest', kw, indent);
  }

  private dnsRequest(v: Json, indent: number): string {
    this.use('DnsRequestDefinition');
    const kw: Kw[] = [['dns_name', pyStr(v['dnsName'])]];
    if (v['dnsType'] != null) kw.push(['dns_type', pyStr(v['dnsType'])]);
    if (v['dnsClass'] != null) kw.push(['dns_class', pyStr(v['dnsClass'])]);
    return renderCall('DnsRequestDefinition', kw, indent);
  }

  private requestOrDns(v: Json, indent: number): string {
    return 'dnsName' in v ? this.dnsRequest(v, indent) : this.request(v, indent);
  }

  private connectionOptions(v: unknown, indent: number): string {
    this.use('ConnectionOptions');
    const o = v as Json;
    const map: [string, string][] = [
      ['keepAliveOverride', 'keep_alive_override'],
      ['closeSocket', 'close_socket'],
      ['contentLengthHeaderOverride', 'content_length_header_override'],
      ['suppressContentLengthHeader', 'suppress_content_length_header'],
      ['suppressConnectionHeader', 'suppress_connection_header'],
    ];
    const kw: Kw[] = [];
    for (const [wire, py] of map) {
      if (o[wire] == null) continue;
      const val = o[wire];
      kw.push([py, typeof val === 'boolean' ? pyBool(val) : pyNum(val)]);
    }
    return renderCall('ConnectionOptions', kw, indent);
  }

  private response(v: unknown, indent: number): string {
    this.use('HttpResponse');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['statusCode'] != null) kw.push(['status_code', pyNum(o['statusCode'])]);
    if (o['reasonPhrase'] != null) kw.push(['reason_phrase', pyStr(o['reasonPhrase'])]);
    if (o['body'] != null) kw.push(['body', this.body(o['body'], indent + 4)]);
    if (o['headers'] != null) kw.push(['headers', this.keyMultiList(o['headers'], indent + 4)]);
    if (o['cookies'] != null) kw.push(['cookies', this.cookieList(o['cookies'], indent + 4)]);
    if (o['delay'] != null) kw.push(['delay', this.delay(o['delay'])]);
    if (o['connectionOptions'] != null) kw.push(['connection_options', this.connectionOptions(o['connectionOptions'], indent + 4)]);
    return renderCall('HttpResponse', kw, indent);
  }

  private forward(v: unknown, indent: number): string {
    this.use('HttpForward');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['scheme'] != null) kw.push(['scheme', pyStr(o['scheme'])]);
    if (o['host'] != null) kw.push(['host', pyStr(o['host'])]);
    if (o['port'] != null) kw.push(['port', pyNum(o['port'])]);
    return renderCall('HttpForward', kw, indent);
  }

  private override(v: unknown, indent: number): string {
    this.use('HttpOverrideForwardedRequest');
    const o = v as Json;
    const req = o['requestOverride'] as Json | undefined;
    const kw: Kw[] = [];
    if (req) kw.push(['http_request', this.request(req, indent + 4)]);
    return renderCall('HttpOverrideForwardedRequest', kw, indent);
  }

  private classCallback(v: unknown, indent: number): string {
    this.use('HttpClassCallback');
    const o = v as Json;
    return renderCall('HttpClassCallback', [['callback_class', pyStr(o['callbackClass'])]], indent);
  }

  private template(v: unknown, indent: number): string {
    this.use('HttpTemplate');
    const o = v as Json;
    const kw: Kw[] = [['template_type', pyStr(o['templateType'])]];
    if (o['template'] != null) kw.push(['template', pyStr(o['template'])]);
    if (o['templateFile'] != null) kw.push(['template_file', pyStr(o['templateFile'])]);
    return renderCall('HttpTemplate', kw, indent);
  }

  private error(v: unknown, indent: number): string {
    this.use('HttpError');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['dropConnection'] != null) kw.push(['drop_connection', pyBool(o['dropConnection'])]);
    if (o['responseBytes'] != null) kw.push(['response_bytes', pyStr(o['responseBytes'])]);
    if (o['delay'] != null) kw.push(['delay', this.delay(o['delay'])]);
    return renderCall('HttpError', kw, indent);
  }

  private forwardWithFallback(v: unknown, indent: number): string {
    this.use('HttpForwardWithFallback');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['httpForward'] != null) kw.push(['http_forward', this.forward(o['httpForward'], indent + 4)]);
    if (o['fallbackResponse'] != null) kw.push(['fallback_response', this.response(o['fallbackResponse'], indent + 4)]);
    if (o['fallbackOnStatusCodes'] != null) kw.push(['fallback_on_status_codes', intArray(o['fallbackOnStatusCodes'])]);
    if (o['fallbackOnTimeout'] != null) kw.push(['fallback_on_timeout', pyBool(o['fallbackOnTimeout'])]);
    return renderCall('HttpForwardWithFallback', kw, indent);
  }

  private webSocket(v: unknown, indent: number): string {
    this.use('HttpWebSocketResponse');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['subprotocol'] != null) kw.push(['subprotocol', pyStr(o['subprotocol'])]);
    if (o['messages'] != null) kw.push(['messages', this.wsMessages(o['messages'], indent + 4)]);
    if (o['closeConnection'] != null) kw.push(['close_connection', pyBool(o['closeConnection'])]);
    if (o['matchers'] != null) {
      this.use('WebSocketFrameMatcher');
      const matchers = (o['matchers'] as Json[]).map((m) => {
        const mk: Kw[] = [];
        if (m['frameType'] != null) mk.push(['frame_type', pyStr(m['frameType'])]);
        if (m['textMatcher'] != null) mk.push(['text_matcher', pyStr(m['textMatcher'])]);
        if (m['responses'] != null) mk.push(['responses', this.wsMessages(m['responses'], indent + 12)]);
        return renderCall('WebSocketFrameMatcher', mk, indent + 8);
      });
      kw.push(['matchers', renderList(matchers, indent + 4)]);
    }
    return renderCall('HttpWebSocketResponse', kw, indent);
  }

  private wsMessages(v: unknown, indent: number): string {
    this.use('WebSocketMessage');
    const items = (v as Json[]).map((m) => renderInline('WebSocketMessage', [['text', pyStr(m['text'])]]));
    return renderList(items, indent);
  }

  private sse(v: unknown, indent: number): string {
    this.use('HttpSseResponse');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['statusCode'] != null) kw.push(['status_code', pyNum(o['statusCode'])]);
    if (o['headers'] != null) kw.push(['headers', this.keyMultiList(o['headers'], indent + 4)]);
    if (o['events'] != null) {
      this.use('SseEvent');
      const events = (o['events'] as Json[]).map((e) => {
        const ek: Kw[] = [];
        if (e['event'] != null) ek.push(['event', pyStr(e['event'])]);
        if (e['data'] != null) ek.push(['data', pyStr(e['data'])]);
        if (e['id'] != null) ek.push(['id', pyStr(e['id'])]);
        if (e['retry'] != null) ek.push(['retry', pyNum(e['retry'])]);
        return renderInline('SseEvent', ek);
      });
      kw.push(['events', renderList(events, indent + 4)]);
    }
    if (o['closeConnection'] != null) kw.push(['close_connection', pyBool(o['closeConnection'])]);
    return renderCall('HttpSseResponse', kw, indent);
  }

  private binary(v: unknown, indent: number): string {
    this.use('BinaryResponse');
    const o = v as Json;
    return renderCall('BinaryResponse', [['binary_data', pyStr(o['binaryData'])]], indent);
  }

  private dnsResponse(v: unknown, indent: number): string {
    this.use('DnsResponse');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['responseCode'] != null) kw.push(['response_code', pyStr(o['responseCode'])]);
    if (Array.isArray(o['answerRecords'])) {
      this.use('DnsRecord');
      const fieldMap: [string, string][] = [
        ['name', 'name'], ['type', 'type'], ['dnsClass', 'dns_class'], ['ttl', 'ttl'],
        ['value', 'value'], ['priority', 'priority'], ['weight', 'weight'], ['port', 'port'],
      ];
      const records = (o['answerRecords'] as Json[]).map((r) => {
        const rk: Kw[] = [];
        for (const [wire, py] of fieldMap) {
          if (r[wire] == null) continue;
          rk.push([py, typeof r[wire] === 'number' ? pyNum(r[wire]) : pyStr(r[wire])]);
        }
        return renderInline('DnsRecord', rk);
      });
      kw.push(['answer_records', renderList(records, indent + 4)]);
    }
    return renderCall('DnsResponse', kw, indent);
  }

  private grpc(v: unknown, indent: number): string {
    this.use('GrpcStreamResponse');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['statusName'] != null) kw.push(['status_name', pyStr(o['statusName'])]);
    if (o['statusMessage'] != null) kw.push(['status_message', pyStr(o['statusMessage'])]);
    if (o['headers'] != null) kw.push(['headers', this.keyMultiList(o['headers'], indent + 4)]);
    if (o['messages'] != null) {
      this.use('GrpcStreamMessage');
      const msgs = (o['messages'] as Json[]).map((m) => renderInline('GrpcStreamMessage', [['json', pyStr(m['json'])]]));
      kw.push(['messages', renderList(msgs, indent + 4)]);
    }
    if (o['closeConnection'] != null) kw.push(['close_connection', pyBool(o['closeConnection'])]);
    return renderCall('GrpcStreamResponse', kw, indent);
  }

  private chaos(v: unknown, indent: number): string {
    this.use('HttpChaosProfile');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['errorStatus'] != null) kw.push(['error_status', pyNum(o['errorStatus'])]);
    if (o['errorProbability'] != null) kw.push(['error_probability', pyNum(o['errorProbability'])]);
    if (o['retryAfter'] != null) kw.push(['retry_after', pyStr(o['retryAfter'])]);
    if (o['latency'] != null) kw.push(['latency', this.delay(o['latency'])]);
    if (o['seed'] != null) kw.push(['seed', pyNum(o['seed'])]);
    if (o['succeedFirst'] != null) kw.push(['succeed_first', pyNum(o['succeedFirst'])]);
    if (o['failRequestCount'] != null) kw.push(['fail_request_count', pyNum(o['failRequestCount'])]);
    return renderCall('HttpChaosProfile', kw, indent);
  }

  private actions(v: unknown, indent: number): string {
    this.use('AfterAction');
    const items = (v as Json[]).map((a) => {
      const ak: Kw[] = [];
      if (a['httpRequest'] != null) ak.push(['http_request', this.request(a['httpRequest'] as Json, indent + 8)]);
      if (a['delay'] != null) ak.push(['delay', this.delay(a['delay'])]);
      if (a['blocking'] != null) ak.push(['blocking', pyBool(a['blocking'])]);
      if (a['timeout'] != null) ak.push(['timeout', this.delay(a['timeout'])]);
      if (a['failurePolicy'] != null) ak.push(['failure_policy', pyStr(a['failurePolicy'])]);
      return renderCall('AfterAction', ak, indent + 4);
    });
    return renderList(items, indent);
  }

  private steps(v: unknown, indent: number): string {
    this.use('ExpectationStep');
    const stepActions: [string, string, (x: unknown, i: number) => string][] = [
      ['httpResponse', 'http_response', (x, i) => this.response(x, i)],
      ['httpForward', 'http_forward', (x, i) => this.forward(x, i)],
      ['httpOverrideForwardedRequest', 'http_override_forwarded_request', (x, i) => this.override(x, i)],
      ['httpError', 'http_error', (x, i) => this.error(x, i)],
      ['httpRequest', 'http_request', (x, i) => this.request(x as Json, i)],
      ['httpClassCallback', 'http_class_callback', (x, i) => this.classCallback(x, i)],
    ];
    const items = (v as Json[]).map((s) => {
      const sk: Kw[] = [];
      for (const [wire, py, emit] of stepActions) {
        if (s[wire] != null) {
          sk.push([py, emit(s[wire], indent + 8)]);
          break;
        }
      }
      if (s['responder'] != null) sk.push(['responder', pyBool(s['responder'])]);
      if (s['delay'] != null) sk.push(['delay', this.delay(s['delay'])]);
      if (s['blocking'] != null) sk.push(['blocking', pyBool(s['blocking'])]);
      if (s['timeout'] != null) sk.push(['timeout', this.delay(s['timeout'])]);
      if (s['failurePolicy'] != null) sk.push(['failure_policy', pyStr(s['failurePolicy'])]);
      return renderCall('ExpectationStep', sk, indent + 4);
    });
    return renderList(items, indent);
  }

  private capture(v: unknown, indent: number): string {
    this.use('CaptureRule');
    const items = (v as Json[]).map((c) =>
      renderInline('CaptureRule', [
        ['source', pyStr(c['source'])],
        ['expression', pyStr(c['expression'])],
        ['into', pyStr(c['into'])],
      ]),
    );
    return renderList(items, indent);
  }

  private times(v: unknown): string {
    this.use('Times');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['remainingTimes'] != null) kw.push(['remaining_times', pyNum(o['remainingTimes'])]);
    if (o['unlimited'] != null) kw.push(['unlimited', pyBool(o['unlimited'])]);
    return renderInline('Times', kw);
  }

  private ttl(v: unknown): string {
    this.use('TimeToLive');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['timeUnit'] != null) kw.push(['time_unit', pyStr(o['timeUnit'])]);
    if (o['timeToLive'] != null) kw.push(['time_to_live', pyNum(o['timeToLive'])]);
    if (o['unlimited'] != null) kw.push(['unlimited', pyBool(o['unlimited'])]);
    return renderInline('TimeToLive', kw);
  }

  /** Build the ordered kwargs of the top-level Expectation(...) constructor. */
  expectation(json: Json, indent: number): Kw[] {
    this.use('Expectation');
    const kw: Kw[] = [];
    const emit = (wire: string, py: string, fn: (x: unknown, i: number) => string) => {
      if (json[wire] != null) kw.push([py, fn(json[wire], indent + 4)]);
    };

    // Request matcher first, then the (mutually-exclusive) action, then modifiers —
    // matching the reading order of the website examples.
    emit('httpRequest', 'http_request', (x, i) => this.requestOrDns(x as Json, i));
    emit('httpResponse', 'http_response', (x, i) => this.response(x, i));
    emit('httpForward', 'http_forward', (x, i) => this.forward(x, i));
    emit('httpOverrideForwardedRequest', 'http_override_forwarded_request', (x, i) => this.override(x, i));
    emit('httpResponseClassCallback', 'http_response_class_callback', (x, i) => this.classCallback(x, i));
    emit('httpResponseTemplate', 'http_response_template', (x, i) => this.template(x, i));
    emit('httpError', 'http_error', (x, i) => this.error(x, i));
    emit('httpForwardWithFallback', 'http_forward_with_fallback', (x, i) => this.forwardWithFallback(x, i));
    emit('httpWebSocketResponse', 'http_websocket_response', (x, i) => this.webSocket(x, i));
    emit('httpSseResponse', 'http_sse_response', (x, i) => this.sse(x, i));
    emit('binaryResponse', 'binary_response', (x, i) => this.binary(x, i));
    emit('dnsResponse', 'dns_response', (x, i) => this.dnsResponse(x, i));
    emit('httpForwardTemplate', 'http_forward_template', (x, i) => this.template(x, i));
    emit('httpForwardClassCallback', 'http_forward_class_callback', (x, i) => this.classCallback(x, i));
    emit('grpcStreamResponse', 'grpc_stream_response', (x, i) => this.grpc(x, i));
    emit('steps', 'steps', (x, i) => this.steps(x, i));
    emit('beforeActions', 'before_actions', (x, i) => this.actions(x, i));
    emit('afterActions', 'after_actions', (x, i) => this.actions(x, i));
    emit('chaos', 'chaos', (x, i) => this.chaos(x, i));
    emit('capture', 'capture', (x, i) => this.capture(x, i));
    emit('scenarioName', 'scenario_name', (x) => pyStr(x));
    emit('scenarioState', 'scenario_state', (x) => pyStr(x));
    emit('newScenarioState', 'new_scenario_state', (x) => pyStr(x));
    emit('id', 'id', (x) => pyStr(x));
    emit('priority', 'priority', (x) => pyNum(x));
    emit('times', 'times', (x) => this.times(x));
    emit('timeToLive', 'time_to_live', (x) => this.ttl(x));
    emit('namespace', 'namespace', (x) => pyStr(x));
    return kw;
  }
}

/** Top-level names that are re-exported from the `mockserver` package. Everything
 *  this emitter references is importable directly from `mockserver`. */
export function standardToPython(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const json = buildExpectationJson(matcher, action) as Json;

  const builder = new PyBuilder();
  const kwargs = builder.expectation(json, 4);
  const expectation = renderCall('Expectation', kwargs, 4);

  const names = ['MockServerClient', ...builder.imports];
  const importBlock = renderImports(names);

  return [
    importBlock,
    '',
    `MockServerClient("${host}", ${port}).upsert(`,
    `    ${expectation}`,
    ')',
  ].join('\n');
}

/** Render the `from mockserver import (...)` block, one sorted name per line. */
function renderImports(names: string[]): string {
  const sorted = Array.from(new Set(names)).sort((a, b) => a.localeCompare(b));
  if (sorted.length <= 3) {
    return `from mockserver import ${sorted.join(', ')}`;
  }
  return `from mockserver import (\n${sorted.map((n) => `    ${n},`).join('\n')}\n)`;
}
