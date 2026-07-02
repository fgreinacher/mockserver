import { useState, useCallback, useEffect, useMemo, useRef, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Paper from '@mui/material/Paper';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import FormControl from '@mui/material/FormControl';
import FormLabel from '@mui/material/FormLabel';
import RadioGroup from '@mui/material/RadioGroup';
import FormControlLabel from '@mui/material/FormControlLabel';
import Radio from '@mui/material/Radio';
import Tooltip from '@mui/material/Tooltip';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined';
import ScienceOutlinedIcon from '@mui/icons-material/ScienceOutlined';
import DownloadIcon from '@mui/icons-material/FileDownloadOutlined';
import ContentCopyIcon from '@mui/icons-material/ContentCopyOutlined';
import RefreshIcon from '@mui/icons-material/RefreshOutlined';
import UploadFileIcon from '@mui/icons-material/UploadFileOutlined';
import { CassetteManagerBody } from './CassetteManager';
import ImportForm from './ImportForm';
import HumanErrorAlert from './HumanErrorAlert';
import { humanizeError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  listWasmModules,
  uploadWasmModule,
  deleteWasmModule,
  testWasmModule,
  type WasmSampleRequest,
  type WasmTestResult,
} from '../lib/wasm';
import { buildBaseUrl } from '../lib/mcpClient';
import { uploadDescriptorSet, listGrpcServices, clearGrpcDescriptors, type GrpcService } from '../lib/grpcDescriptors';
import ConfirmDialog from './ConfirmDialog';
import {
  verifyToJava,
  verifyToNode,
  verifyToPython,
  verifyToGo,
  verifyToCsharp,
  verifyToRuby,
  verifyToRust,
  type VerificationCodegenInput,
} from '../lib/verificationCodegen';
import type { VerificationTimesSpec } from '../lib/verification';

// ---------------------------------------------------------------------------
// Export sub-tab — download captured content in various formats
// ---------------------------------------------------------------------------

// Two independent axes: what to export (scope) and the file format. They are
// chosen separately — a radio for the scope, a dropdown for the format — rather
// than enumerating every scope×format pair in one long list.

type ExportScope = 'expectations' | 'recorded' | 'requests' | 'logs';
type ExportFormat =
  | 'json' | 'java' | 'javascript' | 'python' | 'go' | 'csharp' | 'ruby' | 'rust' | 'php'
  | 'har' | 'openapi' | 'postman' | 'bruno' | 'logentries' | 'curl'
  | 'verify-java' | 'verify-javascript' | 'verify-python' | 'verify-go'
  | 'verify-csharp' | 'verify-ruby' | 'verify-rust';

// A verification language whose code is generated client-side (by
// verificationCodegen.ts) from the retrieved request/response JSON, rather than
// asked of the server's retrieve endpoint.
type VerifyLang = 'java' | 'node' | 'python' | 'go' | 'csharp' | 'ruby' | 'rust';

const SCOPES: { value: ExportScope; label: string; retrieveType: string }[] = [
  { value: 'requests', label: 'Recorded requests', retrieveType: 'REQUEST_RESPONSES' },
  { value: 'expectations', label: 'Active expectations', retrieveType: 'ACTIVE_EXPECTATIONS' },
  { value: 'recorded', label: 'Recorded expectations', retrieveType: 'RECORDED_EXPECTATIONS' },
  { value: 'logs', label: 'Server logs', retrieveType: 'LOGS' },
];

// retrieveFormat is the server's Format enum name; scopes restricts which export
// scopes the server accepts that format for (e.g. JAVA is expectations-only,
// LOG_ENTRIES is the only format for the raw log stream — so the dropdown is filtered
// by the chosen scope).
//
// verifyLang marks a client-side-generated verification snippet: such an entry
// has no server format of its own — the dashboard retrieves the recorded
// request/response pairs as JSON and runs verificationCodegen over them, one
// verify(...) call per recorded request.
const FORMATS: { value: ExportFormat; label: string; retrieveFormat: string; scopes: ExportScope[]; verifyLang?: VerifyLang }[] = [
  { value: 'json', label: 'MockServer JSON', retrieveFormat: 'JSON', scopes: ['expectations', 'recorded', 'requests'] },
  { value: 'java', label: 'MockServer Java DSL', retrieveFormat: 'JAVA', scopes: ['expectations', 'recorded'] },
  { value: 'javascript', label: 'JavaScript client code', retrieveFormat: 'JAVASCRIPT', scopes: ['expectations', 'recorded'] },
  { value: 'python', label: 'Python client code', retrieveFormat: 'PYTHON', scopes: ['expectations', 'recorded'] },
  { value: 'go', label: 'Go client code', retrieveFormat: 'GO', scopes: ['expectations', 'recorded'] },
  { value: 'csharp', label: 'C# client code', retrieveFormat: 'CSHARP', scopes: ['expectations', 'recorded'] },
  { value: 'ruby', label: 'Ruby client code', retrieveFormat: 'RUBY', scopes: ['expectations', 'recorded'] },
  { value: 'rust', label: 'Rust client code', retrieveFormat: 'RUST', scopes: ['expectations', 'recorded'] },
  { value: 'php', label: 'PHP client code', retrieveFormat: 'PHP', scopes: ['expectations', 'recorded'] },
  { value: 'har', label: 'HAR (HTTP Archive)', retrieveFormat: 'HAR', scopes: ['expectations', 'recorded', 'requests'] },
  { value: 'openapi', label: 'OpenAPI 3 spec', retrieveFormat: 'OPENAPI', scopes: ['expectations', 'recorded', 'requests'] },
  { value: 'postman', label: 'Postman collection v2.1', retrieveFormat: 'POSTMAN', scopes: ['expectations', 'recorded', 'requests'] },
  { value: 'bruno', label: 'Bruno collection (.zip)', retrieveFormat: 'BRUNO', scopes: ['expectations', 'recorded', 'requests'] },
  { value: 'logentries', label: 'Log entries (JSON)', retrieveFormat: 'LOG_ENTRIES', scopes: ['recorded', 'requests', 'logs'] },
  { value: 'curl', label: 'cURL commands', retrieveFormat: 'CURL', scopes: ['requests'] },
  // Verification code — generated client-side from the recorded requests.
  { value: 'verify-java', label: 'Verification code (Java)', retrieveFormat: 'JSON', scopes: ['requests'], verifyLang: 'java' },
  { value: 'verify-javascript', label: 'Verification code (JavaScript)', retrieveFormat: 'JSON', scopes: ['requests'], verifyLang: 'node' },
  { value: 'verify-python', label: 'Verification code (Python)', retrieveFormat: 'JSON', scopes: ['requests'], verifyLang: 'python' },
  { value: 'verify-go', label: 'Verification code (Go)', retrieveFormat: 'JSON', scopes: ['requests'], verifyLang: 'go' },
  { value: 'verify-csharp', label: 'Verification code (C#)', retrieveFormat: 'JSON', scopes: ['requests'], verifyLang: 'csharp' },
  { value: 'verify-ruby', label: 'Verification code (Ruby)', retrieveFormat: 'JSON', scopes: ['requests'], verifyLang: 'ruby' },
  { value: 'verify-rust', label: 'Verification code (Rust)', retrieveFormat: 'JSON', scopes: ['requests'], verifyLang: 'rust' },
];

// One verify(...) snippet per recorded request, joined into a single document.
// Each pair from REQUEST_RESPONSES retrieval contributes its httpRequest as a
// single-mode verification asserting the request was received at least once.
const VERIFY_GENERATORS: Record<VerifyLang, (input: VerificationCodegenInput) => string> = {
  java: verifyToJava,
  node: verifyToNode,
  python: verifyToPython,
  go: verifyToGo,
  csharp: verifyToCsharp,
  ruby: verifyToRuby,
  rust: verifyToRust,
};

function generateVerificationCode(pairs: unknown, lang: VerifyLang, baseUrl: string): string {
  const generate = VERIFY_GENERATORS[lang];
  const list = Array.isArray(pairs) ? pairs : [];
  const requests: Record<string, unknown>[] = list
    .map((pair) => {
      const httpRequest = (pair as { httpRequest?: unknown })?.httpRequest;
      return httpRequest && typeof httpRequest === 'object' ? (httpRequest as Record<string, unknown>) : null;
    })
    .filter((r): r is Record<string, unknown> => r !== null);

  const atLeastOnce: VerificationTimesSpec = { mode: 'atLeast', count: 1 };
  if (requests.length === 0) {
    // Still emit a usable, copy-pasteable skeleton (empty request matcher).
    return generate({
      mode: 'single',
      httpRequest: {},
      httpResponse: {},
      times: atLeastOnce,
      httpRequests: [],
      httpResponses: [],
      baseUrl,
    });
  }
  return requests
    .map((httpRequest) =>
      generate({
        mode: 'single',
        httpRequest,
        httpResponse: {},
        times: atLeastOnce,
        httpRequests: [],
        httpResponses: [],
        baseUrl,
      }),
    )
    .join('\n\n');
}

interface ExportDetail {
  description: string;
  filename: string;
}

// Per scope×format description + download filename. Every scope supports every
// format, so the dropdown never needs to change with the selected scope.
const DETAILS: Record<ExportScope, Partial<Record<ExportFormat, ExportDetail>>> = {
  expectations: {
    json: {
      description: 'Round-trippable JSON of every registered expectation. Re-import via PUT /mockserver/expectation.',
      filename: 'mockserver-expectations.json',
    },
    java: {
      description: 'MockServer Java DSL that recreates each expectation — paste into a JUnit test or client.',
      filename: 'mockserver-expectations.java',
    },
    javascript: {
      description: 'Node.js client code — one mockAnyResponse(...) call per expectation. Paste into a script using mockserver-client.',
      filename: 'mockserver-expectations.js',
    },
    python: {
      description: 'Python client code — one client.upsert(...) call per expectation. Paste into a script using the mockserver package.',
      filename: 'mockserver-expectations.py',
    },
    go: {
      description: 'Go client code — one client.Upsert(...) call per expectation. Paste into a program using the mockserver-client-go package.',
      filename: 'mockserver-expectations.go',
    },
    csharp: {
      description: 'C# client code — one client.Upsert(...) call per expectation. Paste into a project using the MockServer.Client NuGet package.',
      filename: 'mockserver-expectations.cs',
    },
    ruby: {
      description: 'Ruby client code — one client.upsert(...) call per expectation. Paste into a script using the mockserver-client gem.',
      filename: 'mockserver-expectations.rb',
    },
    rust: {
      description: 'Rust client code — one client.upsert(...) call per expectation. Paste into a program using the mockserver-client crate.',
      filename: 'mockserver-expectations.rs',
    },
    php: {
      description: 'PHP client code — one $client->upsertExpectation(...) call per expectation. Paste into a script using the mock-server/mockserver-client package.',
      filename: 'mockserver-expectations.php',
    },
    har: {
      description: 'HAR-formatted archive of each expectation as a synthetic request/response pair.',
      filename: 'mockserver-expectations.har',
    },
    openapi: {
      description: 'One operation per (method, path) with the registered response body as an example.',
      filename: 'mockserver-expectations.openapi.json',
    },
    postman: {
      description: 'Postman collection of every expectation as a request item with example response.',
      filename: 'mockserver-expectations.postman.json',
    },
    bruno: {
      description: 'Bruno collection — one .bru file per expectation, packaged as a zip archive.',
      filename: 'mockserver-expectations.bruno.zip',
    },
  },
  requests: {
    json: {
      description: 'Every request/response pair MockServer has logged, as a JSON array.',
      filename: 'mockserver-traffic.json',
    },
    har: {
      description: 'Browser-tooling-compatible archive of every captured request/response pair.',
      filename: 'mockserver-traffic.har',
    },
    openapi: {
      description: 'OpenAPI spec derived from observed traffic; useful for capturing a real API shape.',
      filename: 'mockserver-traffic.openapi.json',
    },
    postman: {
      description: 'Postman collection where each item is a captured request with its observed response.',
      filename: 'mockserver-traffic.postman.json',
    },
    bruno: {
      description: 'Bruno collection (one .bru per captured request) packaged as a zip archive.',
      filename: 'mockserver-traffic.bruno.zip',
    },
    logentries: {
      description: 'Raw MockServer log events for the captured request/response pairs (verbose JSON; mainly for debugging).',
      filename: 'mockserver-traffic.log-entries.json',
    },
    curl: {
      description: 'A cURL command per captured request — paste into a shell to replay the traffic.',
      filename: 'mockserver-traffic.curl.sh',
    },
    'verify-java': {
      description: 'Java verify(...) call per captured request — paste into a JUnit test using the MockServer Java client.',
      filename: 'mockserver-verify.java',
    },
    'verify-javascript': {
      description: 'JavaScript verify(...) call per captured request — paste into a script using mockserver-client.',
      filename: 'mockserver-verify.js',
    },
    'verify-python': {
      description: 'Python client.verify(...) call per captured request — paste into a script using the mockserver package.',
      filename: 'mockserver-verify.py',
    },
    'verify-go': {
      description: 'Go client.Verify(...) call per captured request — paste into a program using the mockserver-client-go package.',
      filename: 'mockserver-verify.go',
    },
    'verify-csharp': {
      description: 'C# client.Verify(...) call per captured request — paste into a project using the MockServer.Client package.',
      filename: 'mockserver-verify.cs',
    },
    'verify-ruby': {
      description: 'Ruby client.verify(...) call per captured request — paste into a script using the mockserver-client gem.',
      filename: 'mockserver-verify.rb',
    },
    'verify-rust': {
      description: 'Rust client.verify(...) call per captured request — paste into a program using the mockserver-client crate.',
      filename: 'mockserver-verify.rs',
    },
  },
  recorded: {
    json: {
      description: 'Expectations auto-recorded while proxying in CAPTURE mode, as round-trippable JSON.',
      filename: 'mockserver-recorded-expectations.json',
    },
    java: {
      description: 'MockServer Java DSL recreating each recorded expectation.',
      filename: 'mockserver-recorded-expectations.java',
    },
    javascript: {
      description: 'Node.js client code recreating each recorded expectation — one mockAnyResponse(...) call per expectation.',
      filename: 'mockserver-recorded-expectations.js',
    },
    python: {
      description: 'Python client code recreating each recorded expectation — one client.upsert(...) call per expectation.',
      filename: 'mockserver-recorded-expectations.py',
    },
    go: {
      description: 'Go client code recreating each recorded expectation — one client.Upsert(...) call per expectation.',
      filename: 'mockserver-recorded-expectations.go',
    },
    csharp: {
      description: 'C# client code recreating each recorded expectation — one client.Upsert(...) call per expectation.',
      filename: 'mockserver-recorded-expectations.cs',
    },
    ruby: {
      description: 'Ruby client code recreating each recorded expectation — one client.upsert(...) call per expectation.',
      filename: 'mockserver-recorded-expectations.rb',
    },
    rust: {
      description: 'Rust client code recreating each recorded expectation — one client.upsert(...) call per expectation.',
      filename: 'mockserver-recorded-expectations.rs',
    },
    php: {
      description: 'PHP client code recreating each recorded expectation — one $client->upsertExpectation(...) call per expectation.',
      filename: 'mockserver-recorded-expectations.php',
    },
    har: {
      description: 'HAR archive of each recorded expectation as a request/response pair.',
      filename: 'mockserver-recorded-expectations.har',
    },
    openapi: {
      description: 'OpenAPI spec derived from the recorded expectations.',
      filename: 'mockserver-recorded-expectations.openapi.json',
    },
    postman: {
      description: 'Postman collection of the recorded expectations.',
      filename: 'mockserver-recorded-expectations.postman.json',
    },
    bruno: {
      description: 'Bruno collection of the recorded expectations, packaged as a zip archive.',
      filename: 'mockserver-recorded-expectations.bruno.zip',
    },
    logentries: {
      description: 'Raw log events behind the recorded expectations (verbose JSON; mainly for debugging).',
      filename: 'mockserver-recorded-expectations.log-entries.json',
    },
  },
  logs: {
    logentries: {
      description: "The server's raw event log as JSON — the full request/response/expectation lifecycle, for debugging.",
      filename: 'mockserver-logs.json',
    },
  },
};

function ExportTab({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [scope, setScope] = useState<ExportScope>('requests');
  const [format, setFormat] = useState<ExportFormat>('har');
  const [downloading, setDownloading] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<ReturnType<typeof humanizeError> | null>(null);

  const scopeMeta = SCOPES.find((s) => s.value === scope)!;
  const availableFormats = FORMATS.filter((f) => f.scopes.includes(scope));
  const formatMeta = FORMATS.find((f) => f.value === format)!;
  const detail = useMemo(
    () => DETAILS[scope][format] ?? { description: '', filename: `mockserver-${scope}` },
    [scope, format],
  );

  // The export caveat depends on the scope: exporting the expectation graph to a
  // request-collection format is lossy (dynamic behaviour becomes placeholders),
  // whereas exporting observed traffic is just a snapshot of what was handled.
  let notice: string | null = null;
  if ((scope === 'expectations' || scope === 'recorded') && ['openapi', 'postman', 'bruno', 'har'].includes(format)) {
    notice =
      'Best-effort export. Positive-string matchers round-trip cleanly, but NottableString ' +
      'negation, regex bodies, and dynamic actions (forward / template / callback / error / LLM) ' +
      'become placeholders — these formats describe client requests and example responses, not ' +
      'the full MockServer expectation graph.';
  } else if (scope === 'requests' && ['openapi', 'postman', 'bruno'].includes(format)) {
    notice =
      'Derived from the traffic MockServer has observed — only the requests it actually handled ' +
      'are included.';
  }

  const handleScopeChange = (next: ExportScope) => {
    setScope(next);
    // The chosen format may not be valid for the new scope (e.g. JAVA only applies to
    // expectations; the Server logs scope only supports LOG_ENTRIES) — fall back to the first
    // format the new scope supports.
    if (!FORMATS.some((f) => f.value === format && f.scopes.includes(next))) {
      const firstValid = FORMATS.find((f) => f.scopes.includes(next));
      if (firstValid) setFormat(firstValid.value);
    }
  };

  // Fetch the text payload for the current scope×format. For a server-rendered
  // format this is just the retrieve response body; for a client-side
  // verification language the server returns the recorded request/response JSON
  // which verificationCodegen turns into verify(...) snippets in the browser.
  const fetchText = useCallback(async (): Promise<string> => {
    const base = buildBaseUrl(connectionParams);
    const path = `/mockserver/retrieve?type=${scopeMeta.retrieveType}&format=${formatMeta.retrieveFormat}`;
    const res = await fetch(`${base}${path}`, { method: 'PUT' });
    if (!res.ok) throw new Error(`MockServer returned ${res.status}: ${res.statusText}`);
    if (formatMeta.verifyLang) {
      const pairs = await res.json();
      return generateVerificationCode(pairs, formatMeta.verifyLang, base);
    }
    return res.text();
  }, [scopeMeta, formatMeta, connectionParams]);

  const handleDownload = useCallback(async () => {
    setDownloading(true);
    setError(null);
    try {
      const base = buildBaseUrl(connectionParams);
      let blob: Blob;
      if (formatMeta.verifyLang) {
        // client-side generated verification code
        blob = new Blob([await fetchText()], { type: 'text/plain' });
      } else {
        const path = `/mockserver/retrieve?type=${scopeMeta.retrieveType}&format=${formatMeta.retrieveFormat}`;
        const res = await fetch(`${base}${path}`, { method: 'PUT' });
        if (!res.ok) throw new Error(`MockServer returned ${res.status}: ${res.statusText}`);
        blob = await res.blob();
      }
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = detail.filename;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(objectUrl);
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setDownloading(false);
    }
  }, [scopeMeta, formatMeta, detail, connectionParams, fetchText]);

  // bruno is a binary zip; every other format is text and can be copied to the
  // clipboard. The "copy as code" affordance is most useful for the code formats
  // (java / javascript / python / go / csharp / ruby / rust / php and the
  // verification snippets) but works for any text format.
  const copyable = format !== 'bruno';

  const handleCopy = useCallback(async () => {
    setError(null);
    try {
      const text = await fetchText();
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (e) {
      setError(humanizeError(e));
    }
  }, [fetchText]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 720 }}>
      <Typography variant="body2" color="text.secondary">
        Download captured traffic or registered expectations as a file. The file is
        produced by the running MockServer instance — what you see in the dashboard
        is what you get.
      </Typography>
      <FormControl>
        <FormLabel sx={{ typography: 'body2' }}>What to export</FormLabel>
        <RadioGroup
          row
          value={scope}
          onChange={(e) => handleScopeChange(e.target.value as ExportScope)}
        >
          {SCOPES.map((s) => (
            <FormControlLabel
              key={s.value}
              value={s.value}
              control={<Radio size="small" />}
              label={s.label}
            />
          ))}
        </RadioGroup>
      </FormControl>
      <TextField
        label="Format"
        size="small"
        select
        value={format}
        onChange={(e) => setFormat(e.target.value as ExportFormat)}
        helperText={detail.description}
        sx={{ maxWidth: 360 }}
      >
        {availableFormats.map((f) => (
          <MenuItem key={f.value} value={f.value}>
            {f.label}
          </MenuItem>
        ))}
      </TextField>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        <Button
          variant="contained"
          size="small"
          startIcon={<DownloadIcon sx={{ fontSize: '0.875rem' }} />}
          onClick={() => void handleDownload()}
          disabled={downloading}
        >
          {downloading ? 'Downloading…' : `Download ${detail.filename}`}
        </Button>
        {copyable && (
          <Button
            variant="outlined"
            size="small"
            startIcon={<ContentCopyIcon sx={{ fontSize: '0.875rem' }} />}
            onClick={() => void handleCopy()}
          >
            {copied ? 'Copied!' : 'Copy as code'}
          </Button>
        )}
      </Box>
      {error && <HumanErrorAlert error={error} variant="outlined" />}
      {notice && (
        <Alert severity="info" variant="outlined" sx={{ typography: 'body2' }}>
          {notice}
        </Alert>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// WASM Modules tab
// ---------------------------------------------------------------------------

const WASM_POLL_INTERVAL_MS = 8000;

const DEFAULT_WASM_SAMPLE_REQUEST = '{\n  "method": "GET",\n  "path": "/"\n}';

/**
 * Dialog to dry-run a loaded WASM module against a sample request via POST /mockserver/wasm/test,
 * showing the module's match decision (and any shaped response) without a live expectation.
 */
function WasmTestDialog({
  connectionParams,
  moduleName,
  onClose,
}: {
  connectionParams: ConnectionParams;
  moduleName: string | null;
  onClose: () => void;
}) {
  // Fresh editor + outcome per module: the render site remounts this via `key={moduleName}`.
  const [requestText, setRequestText] = useState(DEFAULT_WASM_SAMPLE_REQUEST);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ReturnType<typeof humanizeError> | null>(null);
  const [result, setResult] = useState<WasmTestResult | null>(null);

  const handleRun = useCallback(async () => {
    if (moduleName === null) return;
    let parsed: WasmSampleRequest;
    try {
      parsed = JSON.parse(requestText) as WasmSampleRequest;
    } catch {
      setError({ message: 'Sample request must be valid JSON.' });
      return;
    }
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await testWasmModule(connectionParams, moduleName, parsed));
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, moduleName, requestText]);

  return (
    <Dialog open={moduleName !== null} onClose={onClose} maxWidth="sm" fullWidth aria-labelledby="wasm-test-dialog-title">
      <DialogTitle id="wasm-test-dialog-title">Test WASM Module “{moduleName}”</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Dry-run the module against a sample request. The module's match decision (and any shaped
          response) is shown below — nothing is registered.
        </Typography>
        <TextField
          label="Sample request (JSON)"
          multiline
          minRows={6}
          maxRows={16}
          fullWidth
          value={requestText}
          onChange={(e: ChangeEvent<HTMLInputElement>) => setRequestText(e.target.value)}
          slotProps={{ input: { sx: { typography: 'body2', fontFamily: monospaceFontFamily } } }}
        />
        {error && <HumanErrorAlert error={error} sx={{ mt: 1 }} />}
        {result && (
          <Alert severity={result.matched ? 'success' : 'info'} sx={{ mt: 1 }}>
            {result.matched
              ? 'Matched — the module accepted this request.'
              : 'Not matched — the module rejected this request.'}
            {result.shaped !== undefined && result.shaped !== null && (
              <Box component="pre" sx={{ whiteSpace: 'pre-wrap', typography: 'caption', fontFamily: monospaceFontFamily, m: 0, mt: 0.5, maxHeight: 220, overflow: 'auto' }}>
                {JSON.stringify(result.shaped, null, 2)}
              </Box>
            )}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" onClick={() => void handleRun()} disabled={busy || !requestText.trim()}>
          {busy ? 'Testing…' : 'Run test'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function WasmModulesTab({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [modules, setModules] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ReturnType<typeof humanizeError> | null>(null);
  const [actionError, setActionError] = useState<ReturnType<typeof humanizeError> | null>(null);
  const [busy, setBusy] = useState(false);
  const [uploadName, setUploadName] = useState('');
  const [refreshTick, setRefreshTick] = useState(0);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [testTarget, setTestTarget] = useState<string | null>(null);

  // Poll modules list
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await listWasmModules(connectionParams, controller.signal);
        if (cancelled) return;
        setModules(result);
        setError(null);
        setLoading(false);
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setError(humanizeError(e));
        setLoading(false);
      } finally {
        if (!cancelled) timer = setTimeout(() => void poll(), WASM_POLL_INTERVAL_MS);
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, refreshTick]);

  const handleUpload = useCallback(async () => {
    if (!uploadName.trim()) {
      setActionError({ message: 'Module name is required' });
      return;
    }
    const input = fileInputRef.current;
    if (!input?.files?.length) {
      setActionError({ message: 'Select a .wasm file to upload' });
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const file = input.files[0]!;
      const bytes = await file.arrayBuffer();
      await uploadWasmModule(connectionParams, uploadName.trim(), bytes);
      setUploadName('');
      if (input) input.value = '';
      setRefreshTick((t) => t + 1);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams, uploadName]);

  const handleDelete = useCallback(async (name: string) => {
    setBusy(true);
    setActionError(null);
    try {
      await deleteWasmModule(connectionParams, name);
      setRefreshTick((t) => t + 1);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 720 }}>
      <Typography variant="body2" color="text.secondary">
        Upload and manage WASM custom rule modules. Each module can be referenced
        by name in expectation actions.
      </Typography>

      {/* Upload form */}
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <TextField
          size="small"
          label="Module name"
          placeholder="my-rule"
          value={uploadName}
          onChange={(e: ChangeEvent<HTMLInputElement>) => setUploadName(e.target.value)}
          sx={{ minWidth: 180 }}
        />
        <Button
          size="small"
          variant="outlined"
          component="label"
          startIcon={<UploadFileIcon sx={{ fontSize: '0.875rem' }} />}
          sx={{ height: 40 }}
        >
          Select .wasm
          <input
            ref={fileInputRef}
            type="file"
            accept=".wasm"
            hidden
          />
        </Button>
        <Button
          variant="contained"
          size="small"
          disabled={busy}
          onClick={() => void handleUpload()}
          sx={{ height: 40 }}
        >
          Upload
        </Button>
        <Tooltip title="Refresh module list">
          <IconButton size="small" onClick={() => setRefreshTick((t) => t + 1)} aria-label="Refresh WASM modules">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {actionError && (
        <HumanErrorAlert error={actionError} onClose={() => setActionError(null)} />
      )}

      {error && (
        <HumanErrorAlert error={error} variant="outlined" />
      )}

      {/* Modules table */}
      {loading ? (
        <Typography variant="body2" color="text.secondary">Loading…</Typography>
      ) : modules.length === 0 ? (
        <Typography variant="body2" color="text.secondary">No WASM modules loaded.</Typography>
      ) : (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Module Name</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {modules.map((name) => (
                <TableRow key={name}>
                  <TableCell>
                    <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>{name}</Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="Test module against a sample request">
                      <span>
                        <IconButton
                          size="small"
                          aria-label={`Test WASM module ${name}`}
                          disabled={busy}
                          onClick={() => setTestTarget(name)}
                        >
                          <ScienceOutlinedIcon fontSize="small" />
                        </IconButton>
                      </span>
                    </Tooltip>
                    <Tooltip title="Delete module">
                      <span>
                        <IconButton
                          size="small"
                          aria-label={`Delete WASM module ${name}`}
                          disabled={busy}
                          onClick={() => setDeleteTarget(name)}
                        >
                          <DeleteOutlineIcon fontSize="small" />
                        </IconButton>
                      </span>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title={`Delete WASM module "${deleteTarget}"?`}
        message="This permanently removes the module from the server. Any expectations referencing it will fail. This cannot be undone."
        confirmLabel="Delete module"
        onConfirm={() => { if (deleteTarget) void handleDelete(deleteTarget); }}
        onClose={() => setDeleteTarget(null)}
      />

      <WasmTestDialog
        key={testTarget ?? 'closed'}
        connectionParams={connectionParams}
        moduleName={testTarget}
        onClose={() => setTestTarget(null)}
      />
    </Box>
  );
}

// ---------------------------------------------------------------------------
// gRPC descriptors sub-tab — upload a compiled FileDescriptorSet, list services
// ---------------------------------------------------------------------------

function GrpcDescriptorsTab({ connectionParams }: { connectionParams: ConnectionParams }) {
  const [services, setServices] = useState<GrpcService[]>([]);
  const [loadError, setLoadError] = useState<ReturnType<typeof humanizeError> | null>(null);
  const [actionError, setActionError] = useState<ReturnType<typeof humanizeError> | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshTick, setRefreshTick] = useState(0);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [confirmClearOpen, setConfirmClearOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    async function load(): Promise<void> {
      try {
        const result = await listGrpcServices(connectionParams, controller.signal);
        if (cancelled) return;
        setServices(result);
        setLoadError(null);
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setLoadError(humanizeError(e));
      }
    }
    void load();
    return () => { cancelled = true; controller.abort(); };
  }, [connectionParams, refreshTick]);

  const handleUpload = useCallback(async () => {
    const input = fileInputRef.current;
    if (!input?.files?.length) {
      setActionError({ message: 'Select a compiled descriptor set (.desc / .pb / .bin) to upload' });
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const bytes = await input.files[0]!.arrayBuffer();
      await uploadDescriptorSet(connectionParams, bytes);
      if (input) input.value = '';
      setRefreshTick((t) => t + 1);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams]);

  const handleClear = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      await clearGrpcDescriptors(connectionParams);
      setRefreshTick((t) => t + 1);
    } catch (e) {
      setActionError(humanizeError(e));
    } finally {
      setBusy(false);
    }
  }, [connectionParams]);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 820 }}>
      <Typography variant="body2" color="text.secondary">
        Upload a compiled protobuf <code>FileDescriptorSet</code> (e.g. <code>protoc --descriptor_set_out</code>)
        so MockServer can transcode and mock the declared gRPC services.
      </Typography>

      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <Button size="small" variant="outlined" component="label" startIcon={<UploadFileIcon sx={{ fontSize: '0.875rem' }} />} sx={{ height: 40 }}>
          Select descriptor set
          <input ref={fileInputRef} type="file" accept=".desc,.pb,.bin,.protoset,application/octet-stream" hidden />
        </Button>
        <Button variant="contained" size="small" disabled={busy} onClick={() => void handleUpload()} sx={{ height: 40 }}>
          Upload
        </Button>
        <Button size="small" color="error" disabled={busy || services.length === 0} onClick={() => setConfirmClearOpen(true)} sx={{ height: 40 }}>
          Clear all
        </Button>
        <Tooltip title="Refresh service list">
          <IconButton size="small" onClick={() => setRefreshTick((t) => t + 1)} aria-label="Refresh gRPC services">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {actionError && <HumanErrorAlert error={actionError} onClose={() => setActionError(null)} />}
      {loadError && <HumanErrorAlert error={loadError} variant="outlined" />}

      {services.length === 0 ? (
        <Typography variant="body2" color="text.secondary">No gRPC descriptors loaded.</Typography>
      ) : (
        services.map((svc) => (
          <Box key={svc.name}>
            <Typography variant="subtitle2" sx={{ fontFamily: monospaceFontFamily }}>{svc.name}</Typography>
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Method</TableCell>
                    <TableCell>Input → Output</TableCell>
                    <TableCell>Streaming</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {svc.methods.map((m) => (
                    <TableRow key={m.name}>
                      <TableCell><Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>{m.name}</Typography></TableCell>
                      <TableCell><Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>{m.inputType} → {m.outputType}</Typography></TableCell>
                      <TableCell>
                        <Typography variant="caption">
                          {m.clientStreaming && m.serverStreaming ? 'bidi' : m.clientStreaming ? 'client' : m.serverStreaming ? 'server' : 'unary'}
                        </Typography>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Box>
        ))
      )}

      <ConfirmDialog
        open={confirmClearOpen}
        title="Clear all gRPC descriptors?"
        message={`This removes all ${services.length} loaded gRPC service descriptor${services.length === 1 ? '' : 's'}. MockServer will no longer be able to transcode or mock those services. This cannot be undone.`}
        confirmLabel="Clear descriptors"
        onConfirm={() => void handleClear()}
        onClose={() => setConfirmClearOpen(false)}
      />
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Main view — tab strip across Export / Cassettes / WASM Modules / gRPC Descriptors
// ---------------------------------------------------------------------------

export interface LibraryViewProps {
  connectionParams: ConnectionParams;
}

const TABS = ['Import', 'Export', 'Cassettes', 'WASM Modules', 'gRPC Descriptors'];

export default function LibraryView({ connectionParams }: LibraryViewProps) {
  const [tab, setTab] = useState(0);

  return (
    <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', p: 1 }}>
      <Paper variant="outlined" sx={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <Tabs
          value={tab}
          onChange={(_, v: number) => setTab(v)}
          sx={{ borderBottom: 1, borderColor: 'divider', minHeight: 36, '& .MuiTab-root': { minHeight: 36, py: 0.5, typography: 'body2' } }}
        >
          {TABS.map((label) => (
            <Tab key={label} label={label} />
          ))}
        </Tabs>
        <Box sx={{ flex: 1, overflowY: 'auto', p: 2 }}>
          {tab === 0 && (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 720 }}>
              <ImportForm connectionParams={connectionParams} />
            </Box>
          )}
          {tab === 1 && <ExportTab connectionParams={connectionParams} />}
          {tab === 2 && <CassetteManagerBody connectionParams={connectionParams} />}
          {tab === 3 && <WasmModulesTab connectionParams={connectionParams} />}
          {tab === 4 && <GrpcDescriptorsTab connectionParams={connectionParams} />}
        </Box>
      </Paper>
    </Box>
  );
}
