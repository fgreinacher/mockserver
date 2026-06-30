import { useCallback, useMemo, useState, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import IconButton from '@mui/material/IconButton';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import RefreshIcon from '@mui/icons-material/Refresh';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorOutlinedIcon from '@mui/icons-material/ErrorOutlined';
import HubIcon from '@mui/icons-material/Hub';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { getAsyncApiStatus } from '../lib/asyncApi';
import { useAutoRefresh } from '../hooks/useAutoRefresh';
import { humanizeError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';

interface AsyncApiPanelProps {
  connectionParams: ConnectionParams;
}

interface AsyncApiChannel {
  name: string;
  hasSchema?: boolean;
  exampleCount?: number;
}

interface RecordedMessageEntry {
  channel: string;
  key?: string | null;
  payload: string;
  headers?: Record<string, string>;
  timestamp?: string;
  schemaValid?: boolean;
}

interface AsyncApiStatus {
  loaded: boolean;
  specTitle?: string;
  specVersion?: string;
  channels?: AsyncApiChannel[];
  publishers?: number;
  subscribers?: number;
  recordedMessages?: RecordedMessageEntry[];
}

const POLL_INTERVAL_MS = 5000;

function formatTimestamp(ts: string | undefined): string {
  if (!ts) return '-';
  try {
    return new Date(ts).toLocaleTimeString();
  } catch {
    return ts;
  }
}

function truncatePayload(payload: string, maxLength = 120): string {
  if (payload.length <= maxLength) return payload;
  return payload.substring(0, maxLength) + '...';
}

export default function AsyncApiPanel({ connectionParams }: AsyncApiPanelProps) {
  const [status, setStatus] = useState<AsyncApiStatus | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [filterText, setFilterText] = useState('');

  // Auto-refresh the read-only broker status + recorded-message feed.
  const loadStatus = useCallback(async (signal?: AbortSignal) => {
    try {
      const result = await getAsyncApiStatus(connectionParams, signal);
      if (result === null) {
        setUnavailable(true);
        setStatus(null);
      } else {
        setUnavailable(false);
        setStatus(result as unknown as AsyncApiStatus);
      }
      setLoadError(null);
    } catch (e) {
      if (signal?.aborted) return;
      setLoadError(humanizeError(e).message);
    }
  }, [connectionParams]);

  useAutoRefresh(loadStatus, { intervalMs: POLL_INTERVAL_MS });

  // Manual force-refresh (the existing Refresh button) — same fetch, off-cycle.
  const refresh = useCallback(() => {
    void loadStatus();
  }, [loadStatus]);

  const channels = status?.channels ?? [];
  const recordedMessages = useMemo(() => status?.recordedMessages ?? [], [status]);

  const filteredMessages = useMemo(() => {
    if (!filterText.trim()) return recordedMessages;
    const lower = filterText.toLowerCase();
    return recordedMessages.filter(
      (m) =>
        m.channel.toLowerCase().includes(lower) ||
        m.payload.toLowerCase().includes(lower) ||
        (m.key && m.key.toLowerCase().includes(lower)),
    );
  }, [recordedMessages, filterText]);

  const connectionColor: 'success' | 'warning' | 'error' | 'default' = unavailable
    ? 'error'
    : status?.loaded
      ? 'success'
      : 'default';

  const connectionLabel = unavailable
    ? 'unavailable'
    : status?.loaded
      ? 'connected'
      : 'no spec loaded';

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      {/* Header */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <HubIcon fontSize="small" color="primary" />
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          AsyncAPI Broker Mock
        </Typography>
        <Chip
          size="small"
          label={connectionLabel}
          color={connectionColor}
          variant="outlined"
        />
        {status?.loaded && status.specTitle && (
          <Chip
            size="small"
            label={`${status.specTitle} (v${status.specVersion ?? '?'})`}
            variant="outlined"
          />
        )}
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh async status">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Live status of the AsyncAPI broker mock — loaded spec, active channels,
        and messages recorded from broker subscriptions. Load a spec via the Tools
        menu or the <code>PUT /mockserver/asyncapi</code> endpoint.
      </Typography>

      {unavailable && (
        <Alert severity="warning" sx={{ mb: 1.5 }}>
          <AlertTitle>Module Unavailable</AlertTitle>
          The AsyncAPI module (mockserver-async) is not on this server&apos;s classpath.
          Broker mocking requires the mockserver-async jar.
        </Alert>
      )}

      {loadError && (
        <Alert
          severity="error"
          sx={{ mb: 1.5 }}
          action={
            <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry">
              <RefreshIcon fontSize="small" />
            </IconButton>
          }
        >
          <AlertTitle>Could not load async status</AlertTitle>
          {loadError}
        </Alert>
      )}

      {/* Channels table */}
      <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 0.5 }}>
        Channels
      </Typography>
      <Paper variant="outlined" sx={{ p: 1.25, mb: 2 }}>
        {channels.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
            No channels loaded. Load an AsyncAPI spec to start mocking broker channels.
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Channel / Topic</TableCell>
                  <TableCell align="center">Schema</TableCell>
                  <TableCell align="right">Examples</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {channels.map((ch) => (
                  <TableRow key={ch.name}>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                        {ch.name}
                      </Typography>
                    </TableCell>
                    <TableCell align="center">
                      {ch.hasSchema ? (
                        <CheckCircleIcon fontSize="small" color="success" />
                      ) : (
                        <ErrorOutlinedIcon fontSize="small" color="disabled" />
                      )}
                    </TableCell>
                    <TableCell align="right">
                      <Typography variant="caption">{ch.exampleCount ?? 0}</Typography>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      {/* Publisher/subscriber summary */}
      {status?.loaded && (
        <Box sx={{ display: 'flex', gap: 1.5, mb: 2 }}>
          <Chip
            size="small"
            label={`${status.publishers ?? 0} publisher(s)`}
            variant="outlined"
          />
          <Chip
            size="small"
            label={`${status.subscribers ?? 0} subscriber(s)`}
            variant="outlined"
          />
          <Chip
            size="small"
            label={`${recordedMessages.length} recorded message(s)`}
            color={recordedMessages.length > 0 ? 'info' : 'default'}
            variant="outlined"
          />
        </Box>
      )}

      {/* Recorded messages */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
          Recorded Messages
        </Typography>
        <Box sx={{ flex: 1 }} />
        <TextField
          size="small"
          label="Filter"
          placeholder="channel, key, or payload..."
          value={filterText}
          onChange={(e: ChangeEvent<HTMLInputElement>) => setFilterText(e.target.value)}
          sx={{ width: { xs: 140, sm: 220 } }}
        />
      </Box>
      <Paper variant="outlined" sx={{ p: 1.25 }}>
        {filteredMessages.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
            {status?.loaded
              ? 'No messages recorded yet. Enable consumers with "consume": true in your brokerConfig, then publish messages to see them here.'
              : 'Load an AsyncAPI spec with consumer subscriptions to see recorded messages.'}
          </Typography>
        ) : (
          <TableContainer sx={{ maxHeight: 400 }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell>Channel</TableCell>
                  <TableCell>Key</TableCell>
                  <TableCell>Payload</TableCell>
                  <TableCell align="center">Valid</TableCell>
                  <TableCell>Time</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filteredMessages.map((msg, i) => (
                  <TableRow key={`${msg.channel}-${msg.timestamp ?? i}-${i}`}>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                        {msg.channel}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                        {msg.key ?? '-'}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Tooltip title={msg.payload}>
                        <Typography
                          variant="caption"
                          sx={{
                            fontFamily: monospaceFontFamily,
                            maxWidth: 300,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            display: 'block',
                            whiteSpace: 'nowrap',
                          }}
                        >
                          {truncatePayload(msg.payload)}
                        </Typography>
                      </Tooltip>
                    </TableCell>
                    <TableCell align="center">
                      {msg.schemaValid === true && (
                        <CheckCircleIcon fontSize="small" color="success" />
                      )}
                      {msg.schemaValid === false && (
                        <ErrorOutlinedIcon fontSize="small" color="error" />
                      )}
                      {msg.schemaValid == null && (
                        <Typography variant="caption" color="text.secondary">-</Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {formatTimestamp(msg.timestamp)}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>
    </Box>
  );
}
