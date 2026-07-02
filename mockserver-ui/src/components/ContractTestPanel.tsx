import { useCallback, useState, type ChangeEvent, type MouseEvent } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Alert from '@mui/material/Alert';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import CircularProgress from '@mui/material/CircularProgress';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CancelIcon from '@mui/icons-material/Cancel';
import HumanErrorAlert from './HumanErrorAlert';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  runContractTest,
  validateRecordedTraffic,
  type ContractTestReport,
  type ContractTestOperationResult,
  type TrafficValidationReport,
  type TrafficValidationResult,
} from '../lib/contractTest';

interface ContractTestPanelProps {
  connectionParams: ConnectionParams;
}

type Mode = 'live' | 'traffic';

/** Renders a bulleted list of validation errors, or an em dash when there are none. */
function ErrorList({ errors }: { errors: string[] }) {
  if (errors.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary">
        —
      </Typography>
    );
  }
  return (
    <Box component="ul" sx={{ m: 0, pl: 2 }}>
      {errors.map((err, j) => (
        <Typography
          key={j}
          component="li"
          variant="caption"
          color="error"
          sx={{ fontFamily: 'monospace' }}
        >
          {err}
        </Typography>
      ))}
    </Box>
  );
}

/** A PASS / FAIL chip in the shared result-table style. */
function ResultChip({ passed }: { passed: boolean }) {
  return passed ? (
    <Chip
      size="small"
      icon={<CheckCircleIcon fontSize="small" />}
      label="PASS"
      color="success"
      variant="outlined"
      sx={{ height: 22, fontSize: '0.65rem' }}
    />
  ) : (
    <Chip
      size="small"
      icon={<CancelIcon fontSize="small" />}
      label="FAIL"
      color="error"
      variant="outlined"
      sx={{ height: 22, fontSize: '0.65rem' }}
    />
  );
}

export default function ContractTestPanel({ connectionParams }: ContractTestPanelProps) {
  const [mode, setMode] = useState<Mode>('live');
  const [spec, setSpec] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [operationId, setOperationId] = useState('');
  const [running, setRunning] = useState(false);
  const [report, setReport] = useState<ContractTestReport | null>(null);
  const [trafficReport, setTrafficReport] = useState<TrafficValidationReport | null>(null);
  const [error, setError] = useState<HumanError | null>(null);

  const canRun =
    !running &&
    spec.trim().length > 0 &&
    (mode === 'live' ? baseUrl.trim().length > 0 : true);

  const handleModeChange = useCallback((_e: MouseEvent<HTMLElement>, next: Mode | null) => {
    if (next === null) return;
    setMode(next);
    setReport(null);
    setTrafficReport(null);
    setError(null);
  }, []);

  const handleRun = useCallback(async () => {
    setRunning(true);
    setError(null);
    setReport(null);
    setTrafficReport(null);
    try {
      if (mode === 'live') {
        const result = await runContractTest(connectionParams, {
          spec: spec.trim(),
          baseUrl: baseUrl.trim(),
          operationId: operationId.trim() || undefined,
        });
        setReport(result);
      } else {
        const result = await validateRecordedTraffic(connectionParams, { spec: spec.trim() });
        setTrafficReport(result);
      }
    } catch (e) {
      setError(humanizeError(e));
    } finally {
      setRunning(false);
    }
  }, [connectionParams, mode, spec, baseUrl, operationId]);

  const headingChip = (() => {
    if (mode === 'live' && report) {
      return (
        <Chip
          size="small"
          label={report.allPassed ? 'All passed' : `${report.failed} failed`}
          color={report.allPassed ? 'success' : 'error'}
          variant="outlined"
        />
      );
    }
    if (mode === 'traffic' && trafficReport && trafficReport.totalRequests > 0) {
      return (
        <Chip
          size="small"
          label={trafficReport.allPassed ? 'All valid' : `${trafficReport.failed} invalid`}
          color={trafficReport.allPassed ? 'success' : 'error'}
          variant="outlined"
        />
      );
    }
    return null;
  })();

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Contract Test
        </Typography>
        {headingChip}
      </Box>

      <ToggleButtonGroup
        value={mode}
        exclusive
        size="small"
        onChange={handleModeChange}
        aria-label="Contract mode"
        sx={{ mb: 1.5 }}
      >
        <ToggleButton value="live">Live Contract Test</ToggleButton>
        <ToggleButton value="traffic">Validate Recorded Traffic</ToggleButton>
      </ToggleButtonGroup>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {mode === 'live'
          ? "Run an OpenAPI spec's operations against a live service and validate every response against the spec. Supply the spec as a URL, file path, or inline document."
          : 'Validate the traffic MockServer has already recorded against an OpenAPI spec — no live service is contacted. Every recorded request and response is checked against the spec. Supply the spec as a URL, file path, or inline document.'}
      </Typography>

      <Paper variant="outlined" sx={{ p: 1.5, mb: 1.5 }}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <TextField
            label="OpenAPI spec (URL or inline)"
            placeholder="https://example.com/openapi.json  —  or paste the spec here"
            value={spec}
            onChange={(e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setSpec(e.target.value)}
            multiline
            minRows={2}
            maxRows={10}
            fullWidth
            size="small"
          />
          {mode === 'live' && (
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5 }}>
              <TextField
                label="Target base URL"
                placeholder="http://localhost:8080"
                value={baseUrl}
                onChange={(e: ChangeEvent<HTMLInputElement>) => setBaseUrl(e.target.value)}
                size="small"
                sx={{ flex: '1 1 280px' }}
              />
              <TextField
                label="operationId (optional)"
                placeholder="all operations if blank"
                value={operationId}
                onChange={(e: ChangeEvent<HTMLInputElement>) => setOperationId(e.target.value)}
                size="small"
                sx={{ flex: '1 1 220px' }}
              />
            </Box>
          )}
          <Box>
            <Tooltip
              title={
                canRun
                  ? mode === 'live'
                    ? 'Run the contract test'
                    : 'Validate the recorded traffic'
                  : mode === 'live'
                    ? 'A spec and a target base URL are required'
                    : 'A spec is required'
              }
            >
              <span>
                <Button
                  variant="contained"
                  size="small"
                  disabled={!canRun}
                  startIcon={running ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon fontSize="small" />}
                  onClick={() => { void handleRun(); }}
                >
                  {mode === 'live'
                    ? (running ? 'Running…' : 'Run contract test')
                    : (running ? 'Validating…' : 'Validate Traffic')}
                </Button>
              </span>
            </Tooltip>
          </Box>
        </Box>
      </Paper>

      {error !== null && <HumanErrorAlert error={error} sx={{ mb: 1.5 }} />}

      {mode === 'live' && report && (
        <Paper variant="outlined" sx={{ p: 1.25 }}>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, mb: 1.25 }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {report.baseUrl}
            </Typography>
            <Box sx={{ flex: 1 }} />
            <Chip size="small" variant="outlined" label={`${report.totalOperations} operation${report.totalOperations === 1 ? '' : 's'}`} />
            <Chip size="small" color="success" variant="outlined" label={`${report.passed} passed`} />
            <Chip
              size="small"
              color={report.failed > 0 ? 'error' : 'default'}
              variant="outlined"
              label={`${report.failed} failed`}
            />
          </Box>

          {report.results.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
              The spec produced no testable operations.
            </Typography>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Result</TableCell>
                    <TableCell>Operation</TableCell>
                    <TableCell>Method</TableCell>
                    <TableCell>Path</TableCell>
                    <TableCell align="right">Status</TableCell>
                    <TableCell>Validation errors</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {report.results.map((result: ContractTestOperationResult, i: number) => (
                    <TableRow key={`${result.operationId}-${result.method}-${result.path}-${i}`}>
                      <TableCell>
                        <ResultChip passed={result.passed} />
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.operationId}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.method}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.path}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="caption">
                          {result.statusCodeReceived}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <ErrorList errors={result.validationErrors} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Paper>
      )}

      {mode === 'traffic' && trafficReport && (
        trafficReport.totalRequests === 0 ? (
          <Alert severity="info">
            No recorded traffic to validate. Send some requests through MockServer (record or proxy
            traffic) and run again.
          </Alert>
        ) : (
          <Paper variant="outlined" sx={{ p: 1.25 }}>
            <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, mb: 1.25 }}>
              <Box sx={{ flex: 1 }} />
              <Chip size="small" variant="outlined" label={`${trafficReport.totalRequests} request${trafficReport.totalRequests === 1 ? '' : 's'}`} />
              <Chip size="small" color="success" variant="outlined" label={`${trafficReport.passed} passed`} />
              <Chip
                size="small"
                color={trafficReport.failed > 0 ? 'error' : 'default'}
                variant="outlined"
                label={`${trafficReport.failed} failed`}
              />
            </Box>

            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Result</TableCell>
                    <TableCell>Method</TableCell>
                    <TableCell>Path</TableCell>
                    <TableCell>Operation</TableCell>
                    <TableCell>Request errors</TableCell>
                    <TableCell>Response errors</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {trafficReport.results.map((result: TrafficValidationResult, i: number) => (
                    <TableRow key={`${result.method}-${result.path}-${i}`}>
                      <TableCell>
                        <ResultChip passed={result.passed} />
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.method}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.path}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                          {result.matchedOperation ? result.matchedOperation : '—'}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <ErrorList errors={result.requestErrors} />
                      </TableCell>
                      <TableCell>
                        <ErrorList errors={result.responseErrors} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Paper>
        )
      )}
    </Box>
  );
}
