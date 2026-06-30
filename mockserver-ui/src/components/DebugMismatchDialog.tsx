import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Button from '@mui/material/Button';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Typography from '@mui/material/Typography';
import IconButton from '@mui/material/IconButton';
import CloseIcon from '@mui/icons-material/Close';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CancelIcon from '@mui/icons-material/Cancel';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import AddCircleOutlineIcon from '@mui/icons-material/AddCircleOutlined';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import { useState, useMemo, useCallback } from 'react';
import { useDashboardStore } from '../store';
import type { DebugMismatchExpectationResult, DebugMismatchResult } from '../types';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import type { GenericParsed } from '../lib/llmTraffic';
import { mismatchDifferencesToDiffResult } from '../lib/diff';
import { monospaceFontFamily } from '../theme';
import CaptureAsMockDialog from './CaptureAsMockDialog';
import DiffPanel from './DiffPanel';

function scoreColor(matched: number, total: number): 'success' | 'warning' | 'error' {
  // Guard divide-by-zero: a matcher with no fields hasn't "failed" anything, so don't paint it red.
  if (total <= 0) return 'success';
  const ratio = matched / total;
  if (ratio >= 0.8) return 'success';
  if (ratio >= 0.5) return 'warning';
  return 'error';
}

function ExpectationResultRow({ result, isClosest }: { result: DebugMismatchExpectationResult; isClosest: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const hasDiffs = result.differences && Object.keys(result.differences).length > 0;

  return (
    <Box
      sx={{
        borderBottom: 1,
        borderColor: 'divider',
        '&:last-child': { borderBottom: 0 },
        ...(isClosest && {
          borderLeft: 3,
          borderLeftColor: 'warning.main',
          pl: 1,
        }),
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          py: 0.75,
          px: 1,
          cursor: hasDiffs ? 'pointer' : 'default',
          '&:hover': hasDiffs ? { bgcolor: 'action.hover' } : {},
        }}
        onClick={() => hasDiffs && setExpanded((prev) => !prev)}
      >
        {hasDiffs && (
          <IconButton size="small" sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: '1rem' } }}>
            {expanded ? <ExpandMoreIcon /> : <ChevronRightIcon />}
          </IconButton>
        )}
        {result.matches ? (
          <CheckCircleIcon sx={{ fontSize: '1rem', color: 'success.main' }} />
        ) : (
          <CancelIcon sx={{ fontSize: '1rem', color: 'error.main' }} />
        )}
        <Chip
          label={`${result.matchedFieldCount}/${result.totalFieldCount}`}
          size="small"
          color={scoreColor(result.matchedFieldCount, result.totalFieldCount)}
          variant="outlined"
          sx={{ fontFamily: monospaceFontFamily, fontSize: '0.75rem', height: 20, minWidth: 48 }}
        />
        <Box component="span" sx={{ fontFamily: monospaceFontFamily, fontSize: '0.8rem', color: 'text.secondary' }}>
          {result.expectationMethod && result.expectationPath
            ? `${result.expectationMethod} ${result.expectationPath}`
            : result.expectationId ?? 'unknown'}
        </Box>
        {isClosest && (
          <Chip label="closest" size="small" color="warning" sx={{ fontSize: '0.7rem', height: 18 }} />
        )}
      </Box>
      {expanded && hasDiffs && (
        <Box sx={{ pl: 5, pb: 1, pr: 1 }}>
          {Object.entries(result.differences!).map(([field, diffs]) => (
            <Box key={field} sx={{ mb: 0.5 }}>
              <Typography
                variant="caption"
                sx={{ fontFamily: monospaceFontFamily, fontWeight: 600, color: 'error.main', display: 'block' }}
              >
                {field}
              </Typography>
              {diffs.map((diff, i) => (
                <Typography
                  key={i}
                  variant="caption"
                  sx={{
                    fontFamily: monospaceFontFamily,
                    display: 'block',
                    pl: 2,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                    color: 'text.secondary',
                    lineHeight: 1.4,
                  }}
                >
                  {diff}
                </Typography>
              ))}
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Helpers to build a GenericParsed + itemValue from the unmatched request
// ---------------------------------------------------------------------------

function unmatchedRequestToParsed(request: Record<string, unknown>): { parsed: GenericParsed; path: string; itemValue: Record<string, unknown> } {
  const method = typeof request['method'] === 'string' ? request['method'] : 'GET';
  const path = typeof request['path'] === 'string' ? request['path'] : '/';

  const parsed: GenericParsed = {
    kind: 'generic',
    method,
    path,
    statusCode: 200,
  };

  // Wrap the bare httpRequest in the { httpRequest, httpResponse } shape that
  // extractGenericExpectationFromCapture expects, with a sensible 200 default.
  const itemValue: Record<string, unknown> = {
    httpRequest: request,
    httpResponse: { statusCode: 200 },
  };

  return { parsed, path, itemValue };
}

/**
 * Pick the expectation whose field-level differences should drive the "Visual
 * diff" view. Prefers the server-nominated {@link DebugMismatchResult.closestMatch}
 * (looked up by `expectationId`); if there is no closest match — or it has no
 * field-level differences attached — falls back to the highest-scoring result
 * that actually carries `differences`, so the user still sees a side-by-side diff
 * when several candidates are near misses. Returns `undefined` when no result has
 * any differences (e.g. no expectations, or no field-level detail available).
 */
function selectDiffSource(
  result: DebugMismatchResult,
): DebugMismatchExpectationResult | undefined {
  const hasDiffs = (r: DebugMismatchExpectationResult) =>
    r.differences && Object.keys(r.differences).length > 0;

  if (result.closestMatch) {
    const closest = result.results.find(
      (r) => r.expectationId === result.closestMatch!.expectationId,
    );
    if (closest && hasDiffs(closest)) return closest;
  }

  // Fall back to the best-scoring candidate that has field-level differences.
  return result.results
    .filter(hasDiffs)
    .reduce<DebugMismatchExpectationResult | undefined>((best, r) => {
      if (!best) return r;
      const score = (x: DebugMismatchExpectationResult) =>
        x.totalFieldCount > 0 ? x.matchedFieldCount / x.totalFieldCount : 0;
      return score(r) > score(best) ? r : best;
    }, undefined);
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

interface DebugMismatchDialogProps {
  connectionParams: ConnectionParams;
}

export default function DebugMismatchDialog({ connectionParams }: DebugMismatchDialogProps) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const open = useDashboardStore((s) => s.debugMismatchOpen);
  const result = useDashboardStore((s) => s.debugMismatchResult);
  const loading = useDashboardStore((s) => s.debugMismatchLoading);
  const error = useDashboardStore((s) => s.debugMismatchError);
  const closeStore = useDashboardStore((s) => s.closeDebugMismatch);

  const [captureOpen, setCaptureOpen] = useState(false);
  const [tab, setTab] = useState<'results' | 'diff'>('results');

  const close = useCallback(() => {
    setCaptureOpen(false);
    setTab('results');
    closeStore();
  }, [closeStore]);

  // Transform the closest expectation's field-level mismatch reasons into the
  // structured expected-vs-actual rows that DiffPanel renders.
  const diffSource = useMemo(
    () => (result ? selectDiffSource(result) : undefined),
    [result],
  );
  const diffResult = useMemo(
    () => mismatchDifferencesToDiffResult(diffSource?.differences),
    [diffSource],
  );

  // Derive the capture-dialog inputs from the unmatched request stored in the result
  const captureData = useMemo(() => {
    if (!result?.unmatchedRequest) return null;
    return unmatchedRequestToParsed(result.unmatchedRequest);
  }, [result]);

  const hasUnmatchedRequest = captureData !== null;

  return (
    <>
      <Dialog open={open} onClose={close} maxWidth="md" fullWidth fullScreen={fullScreen}>
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', pr: 1 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            Why Didn&apos;t This Match?
            {result && (
              <Chip
                label={`${result.totalExpectations} expectation${result.totalExpectations !== 1 ? 's' : ''}`}
                size="small"
                variant="outlined"
                sx={{ fontSize: '0.75rem' }}
              />
            )}
          </Box>
          <IconButton size="small" onClick={close}>
            <CloseIcon fontSize="small" />
          </IconButton>
        </DialogTitle>
        <DialogContent dividers sx={{ p: 0 }}>
          {loading && (
            <Typography sx={{ p: 3, textAlign: 'center' }} color="text.secondary">
              Analyzing match results...
            </Typography>
          )}
          {error && (
            <Typography sx={{ p: 3, textAlign: 'center' }} color="error">
              {error}
            </Typography>
          )}
          {result && !loading && (
            <>
              {result.truncated && (
                <Typography variant="caption" sx={{ display: 'block', px: 2, py: 0.5, bgcolor: 'warning.dark', color: 'warning.contrastText' }}>
                  Showing first {result.maxExpectationsEvaluated} of {result.totalExpectations} expectations
                </Typography>
              )}
              <Tabs
                value={tab}
                onChange={(_e, v: 'results' | 'diff') => setTab(v)}
                variant="fullWidth"
                sx={{ borderBottom: 1, borderColor: 'divider', minHeight: 40 }}
              >
                <Tab value="results" label="Match Results" sx={{ minHeight: 40, py: 0 }} />
                <Tab value="diff" label="Visual Diff" sx={{ minHeight: 40, py: 0 }} />
              </Tabs>
              {tab === 'results' &&
                (result.results.length === 0 ? (
                  <Typography sx={{ p: 3, textAlign: 'center' }} color="text.secondary">
                    No active expectations
                  </Typography>
                ) : (
                  result.results.map((r, i) => (
                    <ExpectationResultRow
                      key={r.expectationId ?? i}
                      result={r}
                      isClosest={result.closestMatch?.expectationId === r.expectationId}
                    />
                  ))
                ))}
              {tab === 'diff' && (
                <Box sx={{ p: 2 }}>
                  {diffSource ? (
                    <>
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
                        Comparing the request against the closest expectation
                        {diffSource.expectationMethod && diffSource.expectationPath
                          ? ` (${diffSource.expectationMethod} ${diffSource.expectationPath})`
                          : diffSource.expectationId
                            ? ` (${diffSource.expectationId})`
                            : ''}
                        . Expected = what the matcher required, Actual = what the request contained.
                      </Typography>
                      <DiffPanel
                        result={diffResult}
                        loading={false}
                        error={null}
                        title="Request vs Closest Expectation"
                        identicalMessage="No field-level differences were reported for the closest expectation."
                      />
                    </>
                  ) : (
                    <Typography sx={{ py: 2, textAlign: 'center' }} color="text.secondary">
                      No closest expectation with field-level differences to diff. See the Match results tab for the full reasons.
                    </Typography>
                  )}
                </Box>
              )}
            </>
          )}
        </DialogContent>
        <DialogActions>
          {hasUnmatchedRequest && (
            <Button
              size="small"
              startIcon={<AddCircleOutlineIcon />}
              onClick={() => setCaptureOpen(true)}
            >
              Create Expectation
            </Button>
          )}
          <Button onClick={close} size="small">
            Close
          </Button>
        </DialogActions>
      </Dialog>

      {captureData && (
        <CaptureAsMockDialog
          open={captureOpen}
          onClose={() => setCaptureOpen(false)}
          parsed={captureData.parsed}
          path={captureData.path}
          connectionParams={connectionParams}
          itemValue={captureData.itemValue}
        />
      )}
    </>
  );
}
