import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Card from '@mui/material/Card';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Link from '@mui/material/Link';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import LinearProgress from '@mui/material/LinearProgress';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import StopIcon from '@mui/icons-material/Stop';
import EditIcon from '@mui/icons-material/Edit';
import RefreshIcon from '@mui/icons-material/Refresh';
import SaveIcon from '@mui/icons-material/Save';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchLoadScenario,
  registerLoadScenario,
  startScenariosByName,
  stopScenariosByName,
  listLoadScenarios,
  deleteLoadScenario,
  clearLoadScenarios,
  stopLoadScenario,
  loadScenarioReportUrl,
  generateFromOpenAPI,
  generateFromRecording,
  errorRate,
  formatElapsed,
  LoadScenarioError,
  type LoadScenarioDTO,
  type LoadScenarioStatus,
  type LoadScenarioState,
  type RegisteredScenario,
  type LoadStepDTO,
  type LoadStageDTO,
  type LoadStageType,
  type RampCurve,
  type LoadRequestDTO,
  type SocketAddressDTO,
  type LoadShapeDTO,
  type LoadShapeType,
  type LoadShapeMetric,
  type LoadThresholdDTO,
  type LoadThresholdMetric,
  type LoadComparator,
  type ThresholdResult,
  type LoadCaptureDTO,
  type LoadCaptureSource,
  type LoadPacingMode,
  type LoadFeederDTO,
  type LoadFeederStrategy,
  type LoadStepSelection,
  type GenerateRecordingMode,
} from '../lib/loadScenario';
import { buildBaseUrl } from '../lib/mcpClient';
import { useDashboardStore } from '../store';
import HumanErrorAlert from './HumanErrorAlert';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import MetricsLineChart from './MetricsLineChart';
import LoadScenarioReview from './LoadScenarioReview';
import { trackFeature } from '../lib/analytics';

interface LoadScenarioPanelProps {
  connectionParams: ConnectionParams;
}

const RUNNING_POLL_MS = 1000;
const IDLE_POLL_MS = 5000;
const MAX_SAMPLES = 120;

const responsiveWidth = (px: number) => ({ width: { xs: '100%', sm: px } });
// Responsive grid for author-form field rows — fields fill available width and wrap
// uniformly via CSS Grid auto-fit (matching ServiceChaosPanel's CHAOS_GRID), so columns
// line up across rows instead of each row stretching its own items independently.
const FIELD_GRID = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 1, alignItems: 'start' } as const;

/**
 * Sensible starting height (in rows) for the templated body field: the default of 2 when
 * empty/small, growing to fit a loaded multi-line body up to a 20-row cap. The field stays
 * `multiline` with no `maxRows`, so MUI continues to auto-grow beyond this as the user types;
 * this only ensures an existing large body opens expanded instead of clipped.
 */
function bodyMinRows(body: string): number {
  const lines = body ? body.split('\n').length : 0;
  return Math.min(20, Math.max(2, lines));
}

/** Parse a trimmed numeric field, or undefined when blank/NaN. */
function num(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') return undefined;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : undefined;
}

interface KeyValueRow { id: number; key: string; value: string; }

/** One cross-step capture rule editor row. */
interface CaptureFormState {
  id: number;
  name: string;
  source: LoadCaptureSource;
  expression: string;
  defaultValue: string;
}

function emptyCapture(): CaptureFormState {
  return { id: nextId(), name: '', source: 'BODY_JSONPATH', expression: '', defaultValue: '' };
}

/** One in-run threshold editor row. */
interface ThresholdFormState {
  id: number;
  metric: LoadThresholdMetric;
  comparator: LoadComparator;
  threshold: string;
}

function emptyThreshold(): ThresholdFormState {
  return { id: nextId(), metric: 'LATENCY_P95', comparator: 'LESS_THAN', threshold: '500' };
}

interface StepFormState {
  id: number;
  name: string;
  method: string;
  path: string;
  host: string;
  port: string;
  scheme: 'HTTP' | 'HTTPS';
  headers: KeyValueRow[];
  body: string;
  thinkTimeMs: string;
  /** WEIGHTED selection weight (blank → default 1.0); ignored under SEQUENTIAL. */
  weight: string;
  captures: CaptureFormState[];
}

/**
 * Profile authoring mode: an explicit ordered list of stages, or a single declarative named shape
 * that the server expands into stages. A UI-only toggle — the wire form is `profile.stages` or
 * `profile.shape`.
 */
type ProfileMode = 'STAGES' | 'SHAPE';

/** Shape editor card state — only the fields the chosen `type` needs are read. */
interface ShapeFormState {
  type: LoadShapeType;
  metric: LoadShapeMetric;
  curve: RampCurve;
  // SPIKE
  baseline: string;
  peak: string;
  rampUpMillis: string;
  holdMillis: string;
  rampDownMillis: string;
  recoveryHoldMillis: string;
  // STAIRS
  start: string;
  step: string;
  steps: string;
  stepDurationMillis: string;
  // RAMP_HOLD
  target: string;
  rampMillis: string;
}

function emptyShape(): ShapeFormState {
  return {
    type: 'RAMP_HOLD', metric: 'VU', curve: 'LINEAR',
    baseline: '0', peak: '20', rampUpMillis: '15000', holdMillis: '60000', rampDownMillis: '15000', recoveryHoldMillis: '',
    start: '5', step: '5', steps: '4', stepDurationMillis: '30000',
    target: '10', rampMillis: '30000',
  };
}

/** Feeder editor state — inline rows (primary) or raw CSV/JSON data. */
interface FeederFormState {
  enabled: boolean;
  mode: 'ROWS' | 'RAW';
  strategy: LoadFeederStrategy;
  /** Inline rows as a small key/value grid keyed by row index — modelled as columns × rows. */
  rows: FeederRowState[];
  rawFormat: 'CSV' | 'JSON';
  rawData: string;
}

/** One inline feeder row: a list of column=value pairs. */
interface FeederRowState { id: number; cells: KeyValueRow[]; }

function emptyFeederRow(): FeederRowState {
  return { id: nextId(), cells: [{ id: nextId(), key: '', value: '' }] };
}

function emptyFeeder(): FeederFormState {
  return { enabled: false, mode: 'ROWS', strategy: 'CIRCULAR', rows: [emptyFeederRow()], rawFormat: 'CSV', rawData: '' };
}

/**
 * One stage card in the profile stage-builder. `mode` is a UI-only toggle distinguishing a
 * hold (single setpoint) from a ramp (start→end + curve) for VU/RATE stages; PAUSE ignores both.
 */
interface StageFormState {
  id: number;
  type: LoadStageType;
  mode: 'HOLD' | 'RAMP';
  durationMillis: string;
  curve: RampCurve;
  // VU fields
  vus: string;
  startVus: string;
  endVus: string;
  // RATE fields (iterations/sec)
  rate: string;
  startRate: string;
  endRate: string;
  maxVus: string;
}

interface FormState {
  name: string;
  templateType: 'VELOCITY' | 'MUSTACHE';
  maxRequests: string;
  startDelayMillis: string;
  profileMode: ProfileMode;
  stages: StageFormState[];
  shape: ShapeFormState;
  labels: KeyValueRow[];
  steps: StepFormState[];
  stepSelection: LoadStepSelection;
  thresholds: ThresholdFormState[];
  abortOnFail: boolean;
  abortGraceMillis: string;
  pacingMode: LoadPacingMode;
  pacingValue: string;
  feeder: FeederFormState;
}

let idCounter = 1;
const nextId = () => idCounter++;

function emptyStep(): StepFormState {
  return {
    id: nextId(), name: '', method: 'GET', path: '/', host: '', port: '', scheme: 'HTTP', headers: [], body: '', thinkTimeMs: '',
    weight: '', captures: [],
  };
}

/** A fresh VU-hold stage card — the sensible default kind when adding a stage. */
function emptyStage(type: LoadStageType = 'VU', mode: 'HOLD' | 'RAMP' = 'HOLD'): StageFormState {
  return {
    id: nextId(), type, mode, durationMillis: '30000', curve: 'LINEAR',
    vus: '5', startVus: '1', endVus: '10',
    rate: '50', startRate: '10', endRate: '100', maxVus: '',
  };
}

const EMPTY_FORM: FormState = {
  name: '',
  templateType: 'VELOCITY',
  maxRequests: '',
  startDelayMillis: '',
  profileMode: 'STAGES',
  // Sensible default: ramp 1→10 VUs over 30s, then hold 10 VUs for 60s (mirrors the OpenAPI example).
  stages: [
    { ...emptyStage('VU', 'RAMP'), startVus: '1', endVus: '10', durationMillis: '30000', curve: 'LINEAR' },
    { ...emptyStage('VU', 'HOLD'), vus: '10', durationMillis: '60000' },
  ],
  shape: emptyShape(),
  labels: [],
  steps: [emptyStep()],
  stepSelection: 'SEQUENTIAL',
  thresholds: [],
  abortOnFail: false,
  abortGraceMillis: '',
  pacingMode: 'NONE',
  pacingValue: '',
  feeder: emptyFeeder(),
};

/**
 * Build the staged `profile` from the stage cards, or return a validation message. Each card emits a
 * {@link LoadStageDTO} carrying only the fields relevant to its type/mode. Validates: ≥1 stage,
 * ≥1 non-PAUSE (load) stage, every duration > 0, and ramp stages supply both endpoints.
 */
function buildProfile(stages: StageFormState[]): { stages: LoadStageDTO[] } | { error: string } {
  if (stages.length === 0) return { error: 'At least one stage is required' };
  const out: LoadStageDTO[] = [];
  let hasLoadStage = false;
  for (let i = 0; i < stages.length; i++) {
    const s = stages[i]!;
    const label = `Stage ${i + 1}`;
    const durationMillis = num(s.durationMillis);
    if (durationMillis == null || durationMillis <= 0) return { error: `${label}: duration (ms) must be greater than 0` };

    const stage: LoadStageDTO = { type: s.type, durationMillis };
    if (s.type === 'VU') {
      hasLoadStage = true;
      if (s.mode === 'HOLD') {
        const vus = num(s.vus);
        if (vus == null || vus < 1) return { error: `${label}: virtual users (VUs) must be at least 1` };
        stage.vus = vus;
      } else {
        const startVus = num(s.startVus);
        const endVus = num(s.endVus);
        if (startVus == null || startVus < 0) return { error: `${label}: start VUs must be 0 or greater` };
        if (endVus == null || endVus < 1) return { error: `${label}: end VUs must be at least 1` };
        stage.startVus = startVus;
        stage.endVus = endVus;
        stage.curve = s.curve;
      }
    } else if (s.type === 'RATE') {
      hasLoadStage = true;
      if (s.mode === 'HOLD') {
        const rate = num(s.rate);
        if (rate == null || rate <= 0) return { error: `${label}: rate (iterations/sec) must be greater than 0` };
        stage.rate = rate;
      } else {
        const startRate = num(s.startRate);
        const endRate = num(s.endRate);
        if (startRate == null || startRate < 0) return { error: `${label}: start rate must be 0 or greater` };
        if (endRate == null || endRate <= 0) return { error: `${label}: end rate must be greater than 0` };
        stage.startRate = startRate;
        stage.endRate = endRate;
        stage.curve = s.curve;
      }
      const maxVus = num(s.maxVus);
      if (maxVus != null) {
        if (maxVus < 1) return { error: `${label}: max VUs must be at least 1 (or leave blank)` };
        stage.maxVus = maxVus;
      }
    }
    // PAUSE: only durationMillis.
    out.push(stage);
  }
  if (!hasLoadStage) return { error: 'At least one VU or RATE stage is required (a profile of only pauses drives no load)' };
  return { stages: out };
}

/**
 * Build the `profile` from the form, honouring the STAGES/SHAPE mode toggle. In SHAPE mode the
 * declarative shape is emitted (the server expands it into stages); in STAGES mode the explicit
 * stage cards are validated and emitted.
 */
function buildProfileFromForm(form: FormState): { profile: LoadScenarioDTO['profile'] } | { error: string } {
  if (form.profileMode === 'SHAPE') {
    const built = buildShape(form.shape);
    if ('error' in built) return { error: built.error };
    return { profile: { shape: built.shape } };
  }
  const built = buildProfile(form.stages);
  if ('error' in built) return { error: built.error };
  return { profile: { stages: built.stages } };
}

/** Validate + build a LoadShape from the shape card, or return a validation message. */
function buildShape(form: ShapeFormState): { shape: LoadShapeDTO } | { error: string } {
  const shape: LoadShapeDTO = { type: form.type, metric: form.metric };
  const positive = (raw: string, label: string): number | { error: string } => {
    const v = num(raw);
    if (v == null || v <= 0) return { error: `Shape: ${label} must be greater than 0` };
    return v;
  };
  if (form.type === 'SPIKE') {
    shape.curve = form.curve;
    const baseline = num(form.baseline) ?? 0;
    const peak = positive(form.peak, 'peak');
    if (typeof peak === 'object') return peak;
    const rampUp = positive(form.rampUpMillis, 'ramp-up (ms)');
    if (typeof rampUp === 'object') return rampUp;
    const hold = positive(form.holdMillis, 'hold (ms)');
    if (typeof hold === 'object') return hold;
    const rampDown = positive(form.rampDownMillis, 'ramp-down (ms)');
    if (typeof rampDown === 'object') return rampDown;
    shape.baseline = baseline; shape.peak = peak; shape.rampUpMillis = rampUp; shape.holdMillis = hold; shape.rampDownMillis = rampDown;
    const recovery = num(form.recoveryHoldMillis);
    if (recovery != null) {
      if (recovery < 0) return { error: 'Shape: recovery hold (ms) must be 0 or greater (or leave blank)' };
      shape.recoveryHoldMillis = recovery;
    }
  } else if (form.type === 'STAIRS') {
    const start = num(form.start);
    if (start == null || start < 0) return { error: 'Shape: start must be 0 or greater' };
    const step = positive(form.step, 'step rise');
    if (typeof step === 'object') return step;
    const steps = positive(form.steps, 'number of steps');
    if (typeof steps === 'object') return steps;
    const stepDur = positive(form.stepDurationMillis, 'step duration (ms)');
    if (typeof stepDur === 'object') return stepDur;
    shape.start = start; shape.step = step; shape.steps = steps; shape.stepDurationMillis = stepDur;
  } else {
    // RAMP_HOLD
    shape.curve = form.curve;
    const target = positive(form.target, 'target');
    if (typeof target === 'object') return target;
    const ramp = positive(form.rampMillis, 'ramp (ms)');
    if (typeof ramp === 'object') return ramp;
    const hold = positive(form.holdMillis, 'hold (ms)');
    if (typeof hold === 'object') return hold;
    shape.target = target; shape.rampMillis = ramp; shape.holdMillis = hold;
  }
  return { shape };
}

/** Build a LoadScenarioDTO from the form, or return a validation message. */
function buildScenario(form: FormState): { scenario: LoadScenarioDTO } | { error: string } {
  if (form.name.trim() === '') return { error: 'Scenario name is required' };

  const builtProfile = buildProfileFromForm(form);
  if ('error' in builtProfile) return { error: builtProfile.error };
  const profile = builtProfile.profile;

  if (form.steps.length === 0) return { error: 'At least one step is required' };
  const weighted = form.stepSelection === 'WEIGHTED';
  const steps: LoadStepDTO[] = [];
  for (let i = 0; i < form.steps.length; i++) {
    const s = form.steps[i]!;
    if (s.host.trim() === '') return { error: `Step ${i + 1}: target host is required` };
    const port = num(s.port);
    if (port == null || port < 1 || port > 65535) return { error: `Step ${i + 1}: target port must be 1–65535` };
    if (s.path.trim() === '') return { error: `Step ${i + 1}: path is required` };

    const socketAddress: SocketAddressDTO = { host: s.host.trim(), port, scheme: s.scheme };
    const step: LoadStepDTO = {
      request: { method: s.method.trim() || 'GET', path: s.path.trim(), socketAddress },
    };
    const headers = headerRowsToMultiValue(s.headers);
    if (headers) step.request.headers = headers;
    if (s.body.trim() !== '') step.request.body = s.body;
    if (s.name.trim() !== '') step.name = s.name.trim();
    const thinkTimeMs = num(s.thinkTimeMs);
    if (thinkTimeMs != null) {
      if (thinkTimeMs < 0) return { error: `Step ${i + 1}: think time (ms) must be 0 or greater` };
      step.thinkTime = { timeUnit: 'MILLISECONDS', value: thinkTimeMs };
    }
    const captures = buildCaptures(s.captures);
    if ('error' in captures) return { error: `Step ${i + 1}: ${captures.error}` };
    if (captures.captures.length > 0) step.captures = captures.captures;
    if (weighted) {
      const weight = num(s.weight);
      if (weight != null) {
        if (weight <= 0) return { error: `Step ${i + 1}: weight must be greater than 0 under WEIGHTED selection` };
        step.weight = weight;
      }
    }
    steps.push(step);
  }

  const scenario: LoadScenarioDTO = {
    name: form.name.trim(),
    templateType: form.templateType,
    profile,
    steps,
  };
  const maxRequests = num(form.maxRequests);
  if (maxRequests != null) {
    if (maxRequests < 1) return { error: 'Max requests must be at least 1 (or leave blank for unlimited)' };
    scenario.maxRequests = maxRequests;
  }
  const startDelayMillis = num(form.startDelayMillis);
  if (startDelayMillis != null) {
    if (startDelayMillis < 0) return { error: 'Start delay (ms) must be 0 or greater (or leave blank for none)' };
    scenario.startDelayMillis = startDelayMillis;
  }
  const labels = labelRowsToObject(form.labels);
  if (labels) scenario.labels = labels;

  if (form.stepSelection !== 'SEQUENTIAL') scenario.stepSelection = form.stepSelection;

  const thresholds = buildThresholds(form.thresholds);
  if ('error' in thresholds) return { error: thresholds.error };
  if (thresholds.thresholds.length > 0) {
    scenario.thresholds = thresholds.thresholds;
    if (form.abortOnFail) scenario.abortOnFail = true;
    const abortGraceMillis = num(form.abortGraceMillis);
    if (abortGraceMillis != null) {
      if (abortGraceMillis < 0) return { error: 'Abort grace (ms) must be 0 or greater (or leave blank)' };
      scenario.abortGraceMillis = abortGraceMillis;
    }
  }

  if (form.pacingMode !== 'NONE') {
    const value = num(form.pacingValue);
    if (value == null || value <= 0) return { error: 'Pacing value must be greater than 0 when a pacing mode is selected' };
    scenario.pacing = { mode: form.pacingMode, value };
  }

  const feeder = buildFeeder(form.feeder);
  if ('error' in feeder) return { error: feeder.error };
  if (feeder.feeder) scenario.feeder = feeder.feeder;

  return { scenario };
}

/** Validate + build the threshold list, or return a validation message. */
function buildThresholds(rows: ThresholdFormState[]): { thresholds: LoadThresholdDTO[] } | { error: string } {
  const out: LoadThresholdDTO[] = [];
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i]!;
    const threshold = num(r.threshold);
    if (threshold == null) return { error: `Threshold ${i + 1}: a numeric threshold value is required` };
    out.push({ metric: r.metric, comparator: r.comparator, threshold });
  }
  return { thresholds: out };
}

/** Validate + build a step's capture list, or return a (step-relative) validation message. */
function buildCaptures(rows: CaptureFormState[]): { captures: LoadCaptureDTO[] } | { error: string } {
  const out: LoadCaptureDTO[] = [];
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i]!;
    const name = r.name.trim();
    const expression = r.expression.trim();
    if (name === '' && expression === '') continue; // an untouched row → skip
    if (name === '') return { error: `capture ${i + 1} needs a variable name` };
    if (expression === '') return { error: `capture ${i + 1} (${name}) needs an expression` };
    const capture: LoadCaptureDTO = { name, source: r.source, expression };
    if (r.defaultValue.trim() !== '') capture.defaultValue = r.defaultValue;
    out.push(capture);
  }
  return { captures: out };
}

/** Build the feeder from its editor, or return a validation message; null when disabled/empty. */
function buildFeeder(form: FeederFormState): { feeder: LoadFeederDTO | null } | { error: string } {
  if (!form.enabled) return { feeder: null };
  if (form.mode === 'RAW') {
    if (form.rawData.trim() === '') return { error: 'Feeder: raw data is required (or disable the feeder)' };
    return { feeder: { data: form.rawData, format: form.rawFormat, strategy: form.strategy } };
  }
  const rows: Record<string, string>[] = [];
  for (const row of form.rows) {
    const obj: Record<string, string> = {};
    for (const cell of row.cells) {
      const key = cell.key.trim();
      if (key !== '') obj[key] = cell.value;
    }
    if (Object.keys(obj).length > 0) rows.push(obj);
  }
  if (rows.length === 0) return { error: 'Feeder: at least one non-empty row is required (or disable the feeder)' };
  return { feeder: { rows, strategy: form.strategy } };
}

/**
 * Best-effort map of the form to a {@link LoadScenarioDTO} containing ONLY the fields the user has
 * actually filled in — used to drive the always-on code preview so the generated builder chain grows
 * field-by-field as the user types, never throwing on a half-filled form. Unlike {@link buildScenario}
 * (which gates submission and rejects anything invalid), this validates nothing: empty name → placeholder
 * handled downstream, blank stage/step values → omitted, unset optionals (templateType default, maxRequests,
 * startDelayMillis, zero thinkTime) → omitted. The codegen emitters tolerate the resulting partial shape.
 */
function buildPartialScenario(form: FormState): LoadScenarioDTO {
  const stages: LoadStageDTO[] = [];
  for (const s of form.stages) {
    const durationMillis = num(s.durationMillis);
    const stage: LoadStageDTO = { type: s.type, durationMillis: durationMillis ?? 0 };
    if (s.type === 'VU') {
      if (s.mode === 'HOLD') {
        const vus = num(s.vus);
        if (vus != null) stage.vus = vus;
      } else {
        const startVus = num(s.startVus);
        const endVus = num(s.endVus);
        if (startVus != null) stage.startVus = startVus;
        if (endVus != null) stage.endVus = endVus;
        stage.curve = s.curve;
      }
    } else if (s.type === 'RATE') {
      if (s.mode === 'HOLD') {
        const rate = num(s.rate);
        if (rate != null) stage.rate = rate;
      } else {
        const startRate = num(s.startRate);
        const endRate = num(s.endRate);
        if (startRate != null) stage.startRate = startRate;
        if (endRate != null) stage.endRate = endRate;
        stage.curve = s.curve;
      }
      const maxVus = num(s.maxVus);
      if (maxVus != null) stage.maxVus = maxVus;
    }
    // PAUSE: only durationMillis.
    stages.push(stage);
  }

  const weighted = form.stepSelection === 'WEIGHTED';
  const steps: LoadStepDTO[] = [];
  for (const s of form.steps) {
    const request: LoadRequestDTO = {};
    if (s.method.trim()) request.method = s.method.trim();
    if (s.path.trim()) request.path = s.path.trim();
    const host = s.host.trim();
    const port = num(s.port);
    if (host !== '' || port != null) {
      const socketAddress: SocketAddressDTO = { host, port: port ?? 0, scheme: s.scheme };
      request.socketAddress = socketAddress;
    }
    const headers = headerRowsToMultiValue(s.headers);
    if (headers) request.headers = headers;
    if (s.body.trim() !== '') request.body = s.body;
    const step: LoadStepDTO = { request };
    if (s.name.trim() !== '') step.name = s.name.trim();
    const thinkTimeMs = num(s.thinkTimeMs);
    if (thinkTimeMs != null && thinkTimeMs > 0) {
      step.thinkTime = { timeUnit: 'MILLISECONDS', value: thinkTimeMs };
    }
    const captures: LoadCaptureDTO[] = [];
    for (const c of s.captures) {
      const name = c.name.trim();
      const expression = c.expression.trim();
      if (name === '' || expression === '') continue;
      const capture: LoadCaptureDTO = { name, source: c.source, expression };
      if (c.defaultValue.trim() !== '') capture.defaultValue = c.defaultValue;
      captures.push(capture);
    }
    if (captures.length > 0) step.captures = captures;
    if (weighted) {
      const weight = num(s.weight);
      if (weight != null) step.weight = weight;
    }
    steps.push(step);
  }

  const profile: LoadScenarioDTO['profile'] = form.profileMode === 'SHAPE'
    ? { shape: partialShape(form.shape) }
    : { stages };

  const scenario: LoadScenarioDTO = {
    name: form.name.trim(),
    profile,
    steps,
  };
  if (form.templateType !== 'VELOCITY') scenario.templateType = form.templateType;
  const maxRequests = num(form.maxRequests);
  if (maxRequests != null) scenario.maxRequests = maxRequests;
  const startDelayMillis = num(form.startDelayMillis);
  if (startDelayMillis != null) scenario.startDelayMillis = startDelayMillis;
  const labels = labelRowsToObject(form.labels);
  if (labels) scenario.labels = labels;
  if (form.stepSelection !== 'SEQUENTIAL') scenario.stepSelection = form.stepSelection;

  const thresholds: LoadThresholdDTO[] = [];
  for (const t of form.thresholds) {
    const threshold = num(t.threshold);
    if (threshold != null) thresholds.push({ metric: t.metric, comparator: t.comparator, threshold });
  }
  if (thresholds.length > 0) {
    scenario.thresholds = thresholds;
    if (form.abortOnFail) scenario.abortOnFail = true;
    const abortGraceMillis = num(form.abortGraceMillis);
    if (abortGraceMillis != null) scenario.abortGraceMillis = abortGraceMillis;
  }

  if (form.pacingMode !== 'NONE') {
    const value = num(form.pacingValue);
    if (value != null) scenario.pacing = { mode: form.pacingMode, value };
  }

  const feeder = buildFeeder(form.feeder);
  if (!('error' in feeder) && feeder.feeder) scenario.feeder = feeder.feeder;

  return scenario;
}

/** Best-effort partial shape for the live preview (only filled fields, no validation). */
function partialShape(form: ShapeFormState): LoadShapeDTO {
  const shape: LoadShapeDTO = { type: form.type, metric: form.metric };
  const set = (key: keyof LoadShapeDTO, raw: string) => {
    const v = num(raw);
    if (v != null) (shape as unknown as Record<string, unknown>)[key] = v;
  };
  if (form.type === 'SPIKE') {
    shape.curve = form.curve;
    set('baseline', form.baseline); set('peak', form.peak); set('rampUpMillis', form.rampUpMillis);
    set('holdMillis', form.holdMillis); set('rampDownMillis', form.rampDownMillis); set('recoveryHoldMillis', form.recoveryHoldMillis);
  } else if (form.type === 'STAIRS') {
    set('start', form.start); set('step', form.step); set('steps', form.steps); set('stepDurationMillis', form.stepDurationMillis);
  } else {
    shape.curve = form.curve;
    set('target', form.target); set('rampMillis', form.rampMillis); set('holdMillis', form.holdMillis);
  }
  return shape;
}

function labelRowsToObject(rows: KeyValueRow[]): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key !== '') out[key] = row.value;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Convert the per-step header rows into MockServer's `KeyToMultiValue` object-map form
 * (`{ "Name": ["value"] }`), collecting repeated names into a single multi-value entry.
 * Returns undefined when no non-empty header name exists so the request body stays minimal.
 */
function headerRowsToMultiValue(rows: KeyValueRow[]): Record<string, string[]> | undefined {
  const out: Record<string, string[]> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key === '') continue;
    (out[key] ??= []).push(row.value);
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Read a step's request `headers` back into editor rows. Handles both MockServer
 * `KeyToMultiValue` shapes the server may echo: the object map `{ "Name": ["v1", "v2"] }`
 * and the array form `[{ name, values: ["v1"] }]`. Each value becomes its own row so the
 * round-trip through `headerRowsToMultiValue` is faithful.
 */
function headersToRows(headers: LoadRequestDTO['headers']): KeyValueRow[] {
  if (headers == null) return [];
  const rows: KeyValueRow[] = [];
  const push = (key: string, values: unknown) => {
    if (Array.isArray(values)) {
      for (const v of values) rows.push({ id: nextId(), key, value: String(v) });
    } else if (values != null) {
      rows.push({ id: nextId(), key, value: String(values) });
    }
  };
  if (Array.isArray(headers)) {
    for (const entry of headers as Array<{ name?: unknown; values?: unknown }>) {
      if (entry && typeof entry.name === 'string') push(entry.name, entry.values);
    }
  } else {
    for (const [key, values] of Object.entries(headers as Record<string, unknown>)) {
      if (key === 'keyMatchStyle') continue;
      push(key, values);
    }
  }
  return rows;
}

/** Map one wire LoadStageDTO back into an editor stage card, inferring hold-vs-ramp mode. */
function stageToForm(stage: LoadStageDTO): StageFormState {
  const base = emptyStage(stage.type);
  const isVuRamp = stage.type === 'VU' && stage.startVus != null && stage.endVus != null;
  const isRateRamp = stage.type === 'RATE' && stage.startRate != null && stage.endRate != null;
  return {
    ...base,
    type: stage.type,
    mode: isVuRamp || isRateRamp ? 'RAMP' : 'HOLD',
    durationMillis: String(stage.durationMillis),
    curve: stage.curve ?? 'LINEAR',
    vus: stage.vus != null ? String(stage.vus) : base.vus,
    startVus: stage.startVus != null ? String(stage.startVus) : base.startVus,
    endVus: stage.endVus != null ? String(stage.endVus) : base.endVus,
    rate: stage.rate != null ? String(stage.rate) : base.rate,
    startRate: stage.startRate != null ? String(stage.startRate) : base.startRate,
    endRate: stage.endRate != null ? String(stage.endRate) : base.endRate,
    maxVus: stage.maxVus != null ? String(stage.maxVus) : '',
  };
}

/** Map a wire LoadShape back into the shape editor card, filling unset fields from the defaults. */
function shapeToForm(shape: LoadShapeDTO): ShapeFormState {
  const base = emptyShape();
  const str = (v: number | undefined, fallback: string) => (v != null ? String(v) : fallback);
  return {
    ...base,
    type: shape.type,
    metric: shape.metric ?? 'VU',
    curve: shape.curve ?? 'LINEAR',
    baseline: str(shape.baseline, base.baseline),
    peak: str(shape.peak, base.peak),
    rampUpMillis: str(shape.rampUpMillis, base.rampUpMillis),
    holdMillis: str(shape.holdMillis, base.holdMillis),
    rampDownMillis: str(shape.rampDownMillis, base.rampDownMillis),
    recoveryHoldMillis: shape.recoveryHoldMillis != null ? String(shape.recoveryHoldMillis) : '',
    start: str(shape.start, base.start),
    step: str(shape.step, base.step),
    steps: str(shape.steps, base.steps),
    stepDurationMillis: str(shape.stepDurationMillis, base.stepDurationMillis),
    target: str(shape.target, base.target),
    rampMillis: str(shape.rampMillis, base.rampMillis),
  };
}

/** Map a wire LoadCapture back into a capture editor row. */
function captureToForm(capture: LoadCaptureDTO): CaptureFormState {
  return {
    id: nextId(),
    name: capture.name ?? '',
    source: capture.source ?? 'BODY_JSONPATH',
    expression: capture.expression ?? '',
    defaultValue: capture.defaultValue ?? '',
  };
}

/** Map a wire LoadFeeder back into the feeder editor (rows form preferred, else raw data). */
function feederToForm(feeder: LoadFeederDTO | undefined): FeederFormState {
  if (!feeder) return emptyFeeder();
  const strategy = feeder.strategy ?? 'CIRCULAR';
  if (feeder.rows && feeder.rows.length > 0) {
    return {
      enabled: true, mode: 'ROWS', strategy, rawFormat: 'CSV', rawData: '',
      rows: feeder.rows.map((row) => ({
        id: nextId(),
        cells: Object.entries(row).map(([key, value]) => ({ id: nextId(), key, value: String(value) })),
      })),
    };
  }
  if (feeder.data != null) {
    return { enabled: true, mode: 'RAW', strategy, rows: [emptyFeederRow()], rawFormat: feeder.format ?? 'CSV', rawData: feeder.data };
  }
  return emptyFeeder();
}

/** Reverse buildScenario: load a running/most-recent scenario back into the editor. */
function scenarioToForm(scenario: LoadScenarioDTO): FormState {
  const p = scenario.profile;
  const hasShape = p?.shape != null && (!p.stages || p.stages.length === 0);
  const stages = (p?.stages && p.stages.length > 0) ? p.stages.map(stageToForm) : [emptyStage('VU', 'HOLD')];
  return {
    name: scenario.name,
    templateType: scenario.templateType ?? 'VELOCITY',
    maxRequests: scenario.maxRequests != null ? String(scenario.maxRequests) : '',
    startDelayMillis: scenario.startDelayMillis != null ? String(scenario.startDelayMillis) : '',
    profileMode: hasShape ? 'SHAPE' : 'STAGES',
    stages,
    shape: hasShape && p?.shape ? shapeToForm(p.shape) : emptyShape(),
    labels: Object.entries(scenario.labels ?? {}).map(([key, value]) => ({ id: nextId(), key, value })),
    steps: (scenario.steps.length > 0 ? scenario.steps : [{ request: {} }]).map((step) => ({
      id: nextId(),
      name: step.name ?? '',
      method: step.request.method ?? 'GET',
      path: step.request.path ?? '/',
      host: step.request.socketAddress?.host ?? '',
      port: step.request.socketAddress?.port != null ? String(step.request.socketAddress.port) : '',
      scheme: step.request.socketAddress?.scheme ?? 'HTTP',
      headers: headersToRows(step.request.headers),
      body: step.request.body ?? '',
      thinkTimeMs: step.thinkTime?.value != null ? String(step.thinkTime.value) : '',
      weight: step.weight != null ? String(step.weight) : '',
      captures: (step.captures ?? []).map(captureToForm),
    })),
    stepSelection: scenario.stepSelection ?? 'SEQUENTIAL',
    thresholds: (scenario.thresholds ?? []).map((t) => ({
      id: nextId(), metric: t.metric, comparator: t.comparator, threshold: String(t.threshold),
    })),
    abortOnFail: scenario.abortOnFail ?? false,
    abortGraceMillis: scenario.abortGraceMillis != null ? String(scenario.abortGraceMillis) : '',
    pacingMode: scenario.pacing?.mode ?? 'NONE',
    pacingValue: scenario.pacing?.value != null ? String(scenario.pacing.value) : '',
    feeder: feederToForm(scenario.feeder),
  };
}

// --- Combined live chart series ---

interface Sample {
  at: number;
  requestsSent: number;
  succeeded: number;
  failed: number;
  currentVus: number;
  inFlight: number;
  p50: number;
  p95: number;
  p99: number;
}

type SeriesKey = 'rps' | 'vus' | 'inFlight' | 'p50' | 'p95' | 'p99' | 'errorRate';

interface SeriesDef {
  key: SeriesKey;
  label: string;
  /** axis group: latencies share a "ms" formatter; counts/rates are plain. */
  format: (v: number) => string;
  /** derive this series' value array from the accumulated samples. */
  derive: (samples: Sample[]) => number[];
  /**
   * How the "all scenarios" total combines the per-scenario lines:
   *  - 'sum'   additive metrics (RPS, VUs, in-flight) — element-wise sum of the per-scenario series.
   *            (Summing the per-scenario RATES, rather than differencing the pooled counter, avoids a
   *            one-frame spike when a scenario joins mid-run: a joiner contributes 0 on its first frame.)
   *  - 'max'   latency percentiles — element-wise worst case (percentiles cannot be summed).
   *  - 'ratio' error rate — pooled Σfailed/Σsent, which can't be recovered from per-scenario rates,
   *            so it is derived from the aggregated snapshot instead.
   */
  combine: 'sum' | 'max' | 'ratio';
}

/** RPS for each sample as Δsent / Δt against the previous sample (first point = 0). */
function ratePerSecond(samples: Sample[]): number[] {
  return samples.map((s, i) => {
    if (i === 0) return 0;
    const prev = samples[i - 1]!;
    const dt = (s.at - prev.at) / 1000;
    if (dt <= 0) return 0;
    return Math.max(0, (s.requestsSent - prev.requestsSent) / dt);
  });
}

const SERIES_DEFS: SeriesDef[] = [
  { key: 'rps', label: 'RPS', format: (v) => `${v.toFixed(1)}/s`, derive: ratePerSecond, combine: 'sum' },
  { key: 'vus', label: 'Active VUs', format: (v) => Math.round(v).toLocaleString(), derive: (s) => s.map((x) => x.currentVus), combine: 'sum' },
  { key: 'inFlight', label: 'In-flight', format: (v) => Math.round(v).toLocaleString(), derive: (s) => s.map((x) => x.inFlight), combine: 'sum' },
  { key: 'p50', label: 'p50 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p50), combine: 'max' },
  { key: 'p95', label: 'p95 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p95), combine: 'max' },
  { key: 'p99', label: 'p99 ms', format: (v) => `${v.toFixed(0)} ms`, derive: (s) => s.map((x) => x.p99), combine: 'max' },
  { key: 'errorRate', label: 'Error rate %', format: (v) => `${v.toFixed(1)}%`, derive: (s) => s.map((x) => (x.requestsSent > 0 ? (x.failed / x.requestsSent) * 100 : 0)), combine: 'ratio' },
];

// Default-visible subset — RPS + p95 + active VUs (per the brief).
const DEFAULT_VISIBLE: SeriesKey[] = ['rps', 'p95', 'vus'];

// --- Multi-scenario chart timeline ---
//
// The chart plots every concurrently-running scenario, split by scenario, plus an aggregate
// "all scenarios" total. We accumulate a shared timeline of frames (one per registry poll):
// each frame holds a snapshot of every scenario running at that instant, keyed by scenario
// name. A scenario absent from a frame leaves a null gap in its line, so lines start and stop
// independently as scenarios come and go.

/** A point on the shared timeline: each running scenario's snapshot, keyed by scenario name. */
interface Frame {
  at: number;
  tracks: Record<string, Sample>;
}

/** Legend/toggle label for the aggregate "all scenarios" line. */
const TOTAL_TRACK = 'All scenarios';

/** Build a chart Sample from a live status snapshot (a registry entry or the legacy run). */
function sampleOf(status: LoadScenarioStatus, at: number): Sample {
  const sent = status.requestsSent ?? 0;
  const succeeded = status.succeeded ?? 0;
  const failed = status.failed ?? 0;
  return {
    at,
    requestsSent: sent,
    succeeded,
    failed,
    currentVus: status.currentVus ?? 0,
    // in-flight ≈ requests dispatched but not yet completed.
    inFlight: Math.max(0, sent - succeeded - failed),
    p50: status.p50Millis ?? 0,
    p95: status.p95Millis ?? 0,
    p99: status.p99Millis ?? 0,
  };
}

/**
 * Pool several scenarios' snapshots into a single "all scenarios" sample for one frame: counts /
 * VUs / in-flight sum across scenarios; latency percentiles take the worst (max) case, since
 * percentiles cannot be summed. Used to back the pooled error-rate total (Σfailed / Σsent), which —
 * unlike the additive and latency totals — cannot be recombined from the per-scenario lines. The
 * additive (sum) and latency (max) totals are instead built element-wise from those lines, so a
 * scenario joining mid-run contributes 0 to the additive total on its first frame (no spike).
 */
function aggregateSamples(samples: Sample[], at: number): Sample {
  return samples.reduce<Sample>(
    (acc, s) => ({
      at,
      requestsSent: acc.requestsSent + s.requestsSent,
      succeeded: acc.succeeded + s.succeeded,
      failed: acc.failed + s.failed,
      currentVus: acc.currentVus + s.currentVus,
      inFlight: acc.inFlight + s.inFlight,
      p50: Math.max(acc.p50, s.p50),
      p95: Math.max(acc.p95, s.p95),
      p99: Math.max(acc.p99, s.p99),
    }),
    { at, requestsSent: 0, succeeded: 0, failed: 0, currentVus: 0, inFlight: 0, p50: 0, p95: 0, p99: 0 },
  );
}

/**
 * Derive one metric's line for a single track across the whole frame timeline. `aligned[i]` is the
 * track's snapshot at frame `i`, or null when the track was not running then. The metric is derived
 * over only the present snapshots (so RPS deltas use consecutive samples of THIS track) and scattered
 * back to their frame positions, leaving null gaps where the track was idle.
 */
function deriveTrackSeries(aligned: (Sample | null)[], def: SeriesDef): (number | null)[] {
  const indices: number[] = [];
  const present: Sample[] = [];
  aligned.forEach((s, i) => {
    if (s) {
      indices.push(i);
      present.push(s);
    }
  });
  const derived = def.derive(present);
  const out: (number | null)[] = new Array(aligned.length).fill(null);
  indices.forEach((frameIndex, k) => {
    out[frameIndex] = derived[k] ?? null;
  });
  return out;
}

export default function LoadScenarioPanel({ connectionParams }: LoadScenarioPanelProps) {
  const setView = useDashboardStore((s) => s.setView);

  // Sub-tab: 'run' focuses on running scenarios + live metrics; 'author' on creating/editing a
  // scenario (form + generated code). Defaults to 'run' so monitoring is what you land on.
  const [view, setPanelView] = useState<'run' | 'author'>('run');

  const [status, setStatus] = useState<LoadScenarioStatus | null>(null);
  const [disabled, setDisabled] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<HumanError | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshTick, setRefreshTick] = useState(0);

  // Registry (Wave C): all scenarios registered on the server + the user's selection.
  const [registry, setRegistry] = useState<RegisteredScenario[]>([]);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());

  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  // "Generate from…" dialog: null = closed, otherwise which source.
  const [generateSource, setGenerateSource] = useState<'openapi' | 'recording' | null>(null);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [visible, setVisible] = useState<Set<SeriesKey>>(() => new Set(DEFAULT_VISIBLE));
  // The runId whose samples are currently accumulated — reset when a new run starts.
  const sampleRunId = useRef<string | null>(null);

  // Multi-scenario chart timeline: one frame per registry poll, each holding every running
  // scenario's snapshot. Scenarios the user has hidden from the chart (default: none → all shown).
  const [frames, setFrames] = useState<Frame[]>([]);
  const [hiddenScenarios, setHiddenScenarios] = useState<Set<string>>(() => new Set());
  // Whether any scenario was running on the previous registry poll — lets a new batch of runs
  // start a fresh timeline instead of appending to a stale one.
  const framesActiveRef = useRef(false);
  // Latest legacy single-run status, read by the registry poll so a legacy-only run still charts.
  const latestStatusRef = useRef<LoadScenarioStatus | null>(null);

  const refresh = useCallback(() => setRefreshTick((t) => t + 1), []);

  // Poll the load scenario status. Fast (1s) while running, slower when idle.
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const result = await fetchLoadScenario(connectionParams, controller.signal);
        if (cancelled) return;
        setStatus(result);
        latestStatusRef.current = result;
        setDisabled(false);
        setLoadError(null);
        // Accumulate a sample only while a run is active and identifiable.
        if (result.state === 'running' && result.runId) {
          setSamples((prev) => {
            const fresh = sampleRunId.current !== result.runId;
            if (fresh) sampleRunId.current = result.runId ?? null;
            const base = fresh ? [] : prev;
            return [...base, sampleOf(result, Date.now())].slice(-MAX_SAMPLES);
          });
        }
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        if (e instanceof LoadScenarioError && e.status === 403) {
          setDisabled(true);
          setLoadError(null);
        } else {
          setLoadError(e instanceof Error ? e.message : String(e));
        }
      } finally {
        if (!cancelled) {
          const running = !cancelled && status?.state === 'running';
          timer = setTimeout(() => void poll(), running ? RUNNING_POLL_MS : IDLE_POLL_MS);
        }
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
    // status?.state intentionally drives the poll cadence (running → 1s).
  }, [connectionParams, refreshTick, status?.state]);

  // Poll the registry listing. Faster while any scenario is RUNNING/PENDING (or a legacy run is
  // active) so the concurrent-running view, state badges, and live chart stay live.
  const anyActive = registry.some((s) => s.state === 'RUNNING' || s.state === 'PENDING') || status?.state === 'running';
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll(): Promise<void> {
      try {
        const list = await listLoadScenarios(connectionParams, controller.signal);
        if (cancelled) return;
        setRegistry(list);
        // Drop selections for scenarios that no longer exist.
        setSelected((prev) => {
          const names = new Set(list.map((s) => s.name));
          const next = new Set([...prev].filter((n) => names.has(n)));
          return next.size === prev.size ? prev : next;
        });
        // Accumulate a chart frame from every actively-running scenario, plus the legacy run
        // when it is not already represented by a registry entry (older single-run servers).
        const now = Date.now();
        const tracks: Record<string, Sample> = {};
        for (const entry of list) {
          if (entry.state === 'RUNNING' && entry.status) tracks[entry.name] = sampleOf(entry.status, now);
        }
        const legacy = latestStatusRef.current;
        if (legacy?.state === 'running') {
          const key = legacy.name && legacy.name.length > 0 ? legacy.name : 'current run';
          if (!(key in tracks)) tracks[key] = sampleOf(legacy, now);
        }
        const active = Object.keys(tracks).length > 0;
        const wasActive = framesActiveRef.current;
        framesActiveRef.current = active;
        if (active) {
          setFrames((prev) => [...(wasActive ? prev : []), { at: now, tracks }].slice(-MAX_SAMPLES));
        }
      } catch {
        // Registry listing failures are non-fatal — the legacy status poll surfaces
        // the disabled/error states. Leave the last-known registry in place.
        if (cancelled || controller.signal.aborted) return;
      } finally {
        if (!cancelled) {
          timer = setTimeout(() => void poll(), anyActive ? RUNNING_POLL_MS : IDLE_POLL_MS);
        }
      }
    }

    void poll();
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [connectionParams, refreshTick, anyActive]);

  const running = status?.state === 'running';
  const showSummary = status != null && (status.state === 'completed' || status.state === 'stopped');

  const runAction = useCallback(async (action: () => Promise<void>) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      refresh();
    } catch (e) {
      if (e instanceof LoadScenarioError && e.status === 403) {
        setDisabled(true);
      } else {
        setActionError(humanizeError(e));
      }
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  const handleStop = useCallback(() => {
    void runAction(async () => {
      await stopLoadScenario(connectionParams);
    });
  }, [connectionParams, runAction]);

  // Fallback for "Edit running" when the server does not echo a definition (older servers):
  // remember the last scenario this tab submitted so its full config can still be reloaded.
  const lastSubmitted = useRef<LoadScenarioDTO | null>(null);

  // Load the running scenario's config back into the editor for tweak-and-restart. Prefer the
  // server-echoed definition (works for ANY run — a client's or another tab's), and fall back to
  // this tab's last-submitted scenario only when the server did not provide one.
  const handleEditRunning = useCallback(() => {
    const definition = status?.definition ?? lastSubmitted.current;
    if (definition) {
      setForm(scenarioToForm(definition));
    }
    setActionError(null);
    setPanelView('author'); // editing → jump to the Author sub-tab
  }, [status?.definition]);

  // (Author flow below registers/runs explicitly via handleLoad / handleLoadAndRun.)

  // "Load" — validate + REGISTER the scenario (does not run it). Allowed even when
  // load generation is disabled. Records the submitted scenario so "Edit running"
  // can reload it. Returns the registered scenario (or null on validation error).
  const registerForm = useCallback(async (): Promise<LoadScenarioDTO | null> => {
    const built = buildScenario(form);
    if ('error' in built) {
      setActionError({ message: built.error });
      return null;
    }
    lastSubmitted.current = built.scenario;
    let ok = false;
    await runAction(async () => {
      await registerLoadScenario(connectionParams, built.scenario);
      ok = true;
    });
    return ok ? built.scenario : null;
  }, [connectionParams, form, runAction]);

  const handleLoad = useCallback(() => {
    void registerForm();
  }, [registerForm]);

  // "Load & Run" — register, then start it by name (requires loadGenerationEnabled).
  const handleLoadAndRun = useCallback(() => {
    void (async () => {
      const scenario = await registerForm();
      if (!scenario) return;
      setPanelView('run'); // starting a run → jump to the Run & Monitor sub-tab
      await runAction(async () => {
        await startScenariosByName(connectionParams, [scenario.name]);
        trackFeature('load_run_started');
        setSamples([]);
        sampleRunId.current = null;
      });
    })();
  }, [connectionParams, registerForm, runAction]);

  // --- registry (Wave C) handlers ---
  const toggleSelected = useCallback((name: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  }, []);

  const startSelected = useCallback(() => {
    const names = [...selected];
    if (names.length === 0) return;
    setPanelView('run'); // starting runs → jump to the Run & Monitor sub-tab
    void runAction(async () => {
      await startScenariosByName(connectionParams, names);
      trackFeature('load_run_started');
    });
  }, [connectionParams, runAction, selected]);

  const startSelectedOne = useCallback((name: string) => {
    setPanelView('run'); // starting a run → jump to the Run & Monitor sub-tab
    void runAction(async () => {
      await startScenariosByName(connectionParams, [name]);
      trackFeature('load_run_started');
    });
  }, [connectionParams, runAction]);

  const stopOne = useCallback((name: string) => {
    void runAction(async () => {
      await stopScenariosByName(connectionParams, [name]);
    });
  }, [connectionParams, runAction]);

  const deleteOne = useCallback((name: string) => {
    void runAction(async () => {
      await deleteLoadScenario(connectionParams, name);
    });
  }, [connectionParams, runAction]);

  const clearAll = useCallback(() => {
    void runAction(async () => {
      await clearLoadScenarios(connectionParams);
    });
  }, [connectionParams, runAction]);

  // Load a registered scenario's definition back into the author form for tweaking.
  const editRegistered = useCallback((entry: RegisteredScenario) => {
    if (entry.definition) {
      setForm(scenarioToForm(entry.definition));
      setPanelView('author');
      setActionError(null);
    }
  }, []);

  // Load a freshly-generated scenario (from OpenAPI / recording) into the author form.
  const onGenerated = useCallback((scenario: LoadScenarioDTO) => {
    setForm(scenarioToForm(scenario));
    setGenerateSource(null);
    setPanelView('author');
    setActionError(null);
    refresh();
  }, [refresh]);

  // Download a run's end-of-run report (JSON or JUnit-XML) by opening its URL in a new tab.
  const downloadReport = useCallback((name: string, format?: 'junit') => {
    const reportUrl = loadScenarioReportUrl(connectionParams, name, format);
    // The URL is assembled from user-configured connection params, so validate the scheme
    // before handing it to window.open — only ever open an http(s) report URL (rules out
    // javascript:/data: redirection should a connection value ever carry an unexpected scheme).
    let parsed: URL;
    try {
      parsed = new URL(reportUrl, window.location.href);
    } catch {
      setActionError({ message: 'Could not build a valid report URL.' });
      return;
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      setActionError({ message: 'Report URL must use http or https.' });
      return;
    }
    window.open(parsed.href, '_blank', 'noopener,noreferrer');
  }, [connectionParams]);

  // --- form field setters ---
  const setField = <K extends keyof FormState>(field: K) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  // --- shape editor ---
  const setShapeField = (field: keyof ShapeFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, shape: { ...prev.shape, [field]: e.target.value } }));
  const setShapeValue = <K extends keyof ShapeFormState>(field: K, value: ShapeFormState[K]) =>
    setForm((prev) => ({ ...prev, shape: { ...prev.shape, [field]: value } }));

  // --- thresholds ---
  const addThreshold = () => setForm((prev) => ({ ...prev, thresholds: [...prev.thresholds, emptyThreshold()] }));
  const removeThreshold = (index: number) => setForm((prev) => ({ ...prev, thresholds: prev.thresholds.filter((_, i) => i !== index) }));
  const setThresholdValue = <K extends keyof ThresholdFormState>(index: number, field: K, value: ThresholdFormState[K]) =>
    setForm((prev) => ({ ...prev, thresholds: prev.thresholds.map((t, i) => (i === index ? { ...t, [field]: value } : t)) }));

  // --- per-step captures ---
  const updateStepCaptures = (stepIndex: number, fn: (captures: CaptureFormState[]) => CaptureFormState[]) =>
    setForm((prev) => ({ ...prev, steps: prev.steps.map((s, i) => (i === stepIndex ? { ...s, captures: fn(s.captures) } : s)) }));
  const addStepCapture = (stepIndex: number) => updateStepCaptures(stepIndex, (c) => [...c, emptyCapture()]);
  const removeStepCapture = (stepIndex: number, captureIndex: number) =>
    updateStepCaptures(stepIndex, (c) => c.filter((_, i) => i !== captureIndex));
  const setStepCaptureValue = <K extends keyof CaptureFormState>(stepIndex: number, captureIndex: number, field: K, value: CaptureFormState[K]) =>
    updateStepCaptures(stepIndex, (c) => c.map((x, i) => (i === captureIndex ? { ...x, [field]: value } : x)));

  // --- feeder ---
  const setFeederValue = <K extends keyof FeederFormState>(field: K, value: FeederFormState[K]) =>
    setForm((prev) => ({ ...prev, feeder: { ...prev.feeder, [field]: value } }));
  const addFeederRow = () => setForm((prev) => ({ ...prev, feeder: { ...prev.feeder, rows: [...prev.feeder.rows, emptyFeederRow()] } }));
  const removeFeederRow = (rowIndex: number) =>
    setForm((prev) => ({ ...prev, feeder: { ...prev.feeder, rows: prev.feeder.rows.filter((_, i) => i !== rowIndex) } }));
  const addFeederCell = (rowIndex: number) =>
    setForm((prev) => ({
      ...prev,
      feeder: { ...prev.feeder, rows: prev.feeder.rows.map((r, i) => (i === rowIndex ? { ...r, cells: [...r.cells, { id: nextId(), key: '', value: '' }] } : r)) },
    }));
  const removeFeederCell = (rowIndex: number, cellIndex: number) =>
    setForm((prev) => ({
      ...prev,
      feeder: { ...prev.feeder, rows: prev.feeder.rows.map((r, i) => (i === rowIndex ? { ...r, cells: r.cells.filter((_, j) => j !== cellIndex) } : r)) },
    }));
  const setFeederCellField = (rowIndex: number, cellIndex: number, field: 'key' | 'value') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({
      ...prev,
      feeder: { ...prev.feeder, rows: prev.feeder.rows.map((r, i) => (i === rowIndex ? { ...r, cells: r.cells.map((c, j) => (j === cellIndex ? { ...c, [field]: e.target.value } : c)) } : r)) },
    }));

  const setStepField = (index: number, field: keyof StepFormState) =>
    (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm((prev) => ({ ...prev, steps: prev.steps.map((s, i) => (i === index ? { ...s, [field]: e.target.value } : s)) }));

  const addStep = () => setForm((prev) => ({ ...prev, steps: [...prev.steps, emptyStep()] }));
  const removeStep = (index: number) => setForm((prev) => ({ ...prev, steps: prev.steps.filter((_, i) => i !== index) }));

  // --- stage builder handlers ---
  const setStageField = (index: number, field: keyof StageFormState) =>
    (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm((prev) => ({ ...prev, stages: prev.stages.map((s, i) => (i === index ? { ...s, [field]: e.target.value } : s)) }));
  const setStageValue = <K extends keyof StageFormState>(index: number, field: K, value: StageFormState[K]) =>
    setForm((prev) => ({ ...prev, stages: prev.stages.map((s, i) => (i === index ? { ...s, [field]: value } : s)) }));
  const addStage = () => setForm((prev) => ({ ...prev, stages: [...prev.stages, emptyStage('VU', 'HOLD')] }));
  const removeStage = (index: number) => setForm((prev) => ({ ...prev, stages: prev.stages.filter((_, i) => i !== index) }));
  const moveStage = (index: number, delta: number) =>
    setForm((prev) => {
      const target = index + delta;
      if (target < 0 || target >= prev.stages.length) return prev;
      const stages = [...prev.stages];
      const [moved] = stages.splice(index, 1);
      stages.splice(target, 0, moved!);
      return { ...prev, stages };
    });

  const updateStepHeaders = (stepIndex: number, fn: (headers: KeyValueRow[]) => KeyValueRow[]) =>
    setForm((prev) => ({ ...prev, steps: prev.steps.map((s, i) => (i === stepIndex ? { ...s, headers: fn(s.headers) } : s)) }));
  const addStepHeader = (stepIndex: number) =>
    updateStepHeaders(stepIndex, (headers) => [...headers, { id: nextId(), key: '', value: '' }]);
  const removeStepHeader = (stepIndex: number, headerIndex: number) =>
    updateStepHeaders(stepIndex, (headers) => headers.filter((_, i) => i !== headerIndex));
  const setStepHeaderField = (stepIndex: number, headerIndex: number, field: 'key' | 'value') =>
    (e: ChangeEvent<HTMLInputElement>) =>
      updateStepHeaders(stepIndex, (headers) => headers.map((h, i) => (i === headerIndex ? { ...h, [field]: e.target.value } : h)));

  const addLabel = () => setForm((prev) => ({ ...prev, labels: [...prev.labels, { id: nextId(), key: '', value: '' }] }));
  const removeLabel = (index: number) => setForm((prev) => ({ ...prev, labels: prev.labels.filter((_, i) => i !== index) }));
  const setLabelField = (index: number, field: 'key' | 'value') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, labels: prev.labels.map((l, i) => (i === index ? { ...l, [field]: e.target.value } : l)) }));

  const toggleSeries = (key: SeriesKey) =>
    setVisible((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const toggleScenario = (name: string) =>
    setHiddenScenarios((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });

  const chartTimestamps = useMemo(() => frames.map((f) => f.at), [frames]);

  // Every scenario that has appeared on the timeline, in stable sorted order — drives the
  // per-scenario toggles. A scenario is shown unless the user has explicitly hidden it.
  const scenarioNames = useMemo(() => {
    const names = new Set<string>();
    for (const f of frames) for (const n of Object.keys(f.tracks)) names.add(n);
    return [...names].sort();
  }, [frames]);
  const enabledScenarios = useMemo(
    () => scenarioNames.filter((n) => !hiddenScenarios.has(n)),
    [scenarioNames, hiddenScenarios],
  );

  // Build the chart lines: one per (visible metric × enabled scenario), plus an aggregate
  // "all scenarios" line per metric when 2+ scenarios are enabled (with a single scenario the
  // total would just duplicate it). Scenarios that come and go leave null gaps in their lines.
  const chartSeries = useMemo(() => {
    const visibleDefs = SERIES_DEFS.filter((d) => visible.has(d.key));
    if (frames.length === 0 || visibleDefs.length === 0 || enabledScenarios.length === 0) return [];
    const showTotal = enabledScenarios.length > 1;
    const series: { label: string; data: (number | null)[] }[] = [];
    for (const def of visibleDefs) {
      // Per-scenario lines first — also reused to build the additive/max total.
      const perScenario = enabledScenarios.map((name) => ({
        name,
        data: deriveTrackSeries(frames.map((f) => f.tracks[name] ?? null), def),
      }));
      if (showTotal) {
        let totalData: (number | null)[];
        if (def.combine === 'ratio') {
          // Error rate of the pooled totals (Σfailed/Σsent) — not recoverable from per-scenario rates.
          const totalAligned = frames.map((f) => {
            const present = enabledScenarios.map((n) => f.tracks[n]).filter((s): s is Sample => !!s);
            return present.length > 0 ? aggregateSamples(present, f.at) : null;
          });
          totalData = deriveTrackSeries(totalAligned, def);
        } else {
          // Sum (counts/rates) or max (latencies) the per-scenario lines element-wise; null where
          // no enabled scenario was running in that frame.
          totalData = frames.map((_, i) => {
            const vals = perScenario.map((p) => p.data[i]).filter((v): v is number => v != null);
            if (vals.length === 0) return null;
            return def.combine === 'max' ? Math.max(...vals) : vals.reduce((a, b) => a + b, 0);
          });
        }
        series.push({ label: `${def.label} · ${TOTAL_TRACK}`, data: totalData });
      }
      for (const p of perScenario) {
        // Suffix the scenario only when more than one line shares this metric, so a single-scenario
        // chart keeps the clean legacy labels ("RPS", "p95 ms", …).
        series.push({ label: showTotal ? `${def.label} · ${p.name}` : def.label, data: p.data });
      }
    }
    return series;
  }, [frames, visible, enabledScenarios]);

  const baseUrl = useMemo(() => buildBaseUrl(connectionParams), [connectionParams]);

  // Every registered scenario that is currently RUNNING — drives the concurrent-running view.
  const runningEntries = useMemo(
    () => registry.filter((s) => s.state === 'RUNNING' && s.status),
    [registry],
  );

  // The code preview always renders from a best-effort PARTIAL scenario (only the filled-in fields),
  // so the generated builder chain grows field-by-field as the user types — never blocked on full
  // validity. The strict buildScenario still gates the Load / Load & Run submit actions above; here
  // we only surface its message as a non-blocking inline hint about what is still needed to run.
  const codeScenario = useMemo(() => buildPartialScenario(form), [form]);
  const validation = useMemo(() => buildScenario(form), [form]);
  const codeError = 'error' in validation ? validation.error : null;

  const statusColor = running ? 'success' : status?.state === 'completed' ? 'info' : status?.state === 'stopped' ? 'warning' : 'default';

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }} data-testid="load-scenario-panel">
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, flexWrap: 'wrap' }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>Performance — Load Scenarios</Typography>
        <Chip size="small" label={status?.state ?? 'none'} color={statusColor} variant="outlined" />
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh load scenario status">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {disabled && (
        <Alert severity="info" sx={{ mb: 1.5 }} data-testid="load-disabled-alert">
          <AlertTitle>Load generation is disabled</AlertTitle>
          Load scenarios are off by default. Start MockServer with load generation enabled to drive
          traffic from here:
          <Box component="pre" sx={{ mt: 1, mb: 1, p: 1, bgcolor: 'action.hover', borderRadius: 1, typography: 'subtitle2', fontWeight: 400, overflow: 'auto' }}>
{`-Dmockserver.loadGenerationEnabled=true
# or environment variable:
MOCKSERVER_LOAD_GENERATION_ENABLED=true`}
          </Box>
          For deeper analysis, the{' '}
          <Link component="button" type="button" onClick={() => setView('metrics')}>Metrics tab</Link>{' '}
          surfaces the <code>mock_server_load_*</code> Prometheus/OTEL series. See the{' '}
          <Link href="https://www.mock-server.com/mock_server/load_injection.html" target="_blank" rel="noopener noreferrer">
            load injection docs
          </Link>.
        </Alert>
      )}

      {loadError && !disabled && (
        <Alert severity="error" sx={{ mb: 1.5 }} action={
          <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry"><RefreshIcon fontSize="small" /></IconButton>
        }>
          <AlertTitle>Could not load scenario status</AlertTitle>
          {loadError}
        </Alert>
      )}

      {actionError && (
        <HumanErrorAlert error={actionError} onClose={() => setActionError(null)} sx={{ mb: 1.5 }} data-testid="load-action-error" />
      )}

      {/* Registered scenarios (Wave C) — shared across both sub-tabs */}
      <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-registry">
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>Registered Scenarios</Typography>
          <Chip size="small" label={registry.length} variant="outlined" />
          <Box sx={{ flex: 1 }} />
          <Button
            size="small" variant="contained" startIcon={<PlayArrowIcon />}
            disabled={busy || disabled || selected.size === 0} onClick={startSelected}
            data-testid="load-start-selected"
          >
            Start selected{selected.size > 0 ? ` (${selected.size})` : ''}
          </Button>
          <Button
            size="small" color="error" variant="outlined" startIcon={<DeleteSweepIcon />}
            disabled={busy || registry.length === 0} onClick={clearAll}
            data-testid="load-clear-all"
          >
            Clear all
          </Button>
        </Box>
        {registry.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No scenarios registered yet. Use <strong>Load</strong> below to register one without running it,
            or <strong>Load &amp; Run</strong> to register and start it.
          </Typography>
        ) : (
          registry.map((entry) => (
            <Box
              key={entry.name}
              data-testid={`load-registry-row-${entry.name}`}
              sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.5, borderTop: 1, borderColor: 'divider', flexWrap: 'wrap' }}
            >
              <Checkbox
                size="small"
                checked={selected.has(entry.name)}
                onChange={() => toggleSelected(entry.name)}
                slotProps={{ input: { 'aria-label': `Select ${entry.name}` } }}
              />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{entry.name}</Typography>
              <Chip
                size="small" variant="outlined"
                color={registryStateColor(entry.state)}
                label={entry.state}
                data-testid={`load-registry-state-${entry.name}`}
              />
              {entry.status && (entry.state === 'RUNNING' || entry.state === 'COMPLETED' || entry.state === 'STOPPED') && (
                <Typography variant="caption" color="text.secondary">
                  {(entry.status.requestsSent ?? 0).toLocaleString()} sent · {(errorRate(entry.status) * 100).toFixed(1)}% err
                  {entry.status.currentVus != null ? ` · ${entry.status.currentVus} VUs` : ''}
                </Typography>
              )}
              {entry.status?.verdict && (
                <Chip
                  size="small"
                  color={entry.status.verdict === 'PASS' ? 'success' : 'error'}
                  label={entry.status.verdict}
                  data-testid={`load-registry-verdict-${entry.name}`}
                />
              )}
              <Box sx={{ flex: 1 }} />
              {entry.definition && (
                <Tooltip title="Load into author form">
                  <span>
                    <IconButton size="small" onClick={() => editRegistered(entry)} aria-label={`Edit ${entry.name}`}>
                      <EditIcon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              )}
              {(entry.state === 'RUNNING' || entry.state === 'PENDING') ? (
                <Tooltip title="Stop">
                  <span>
                    <IconButton size="small" color="error" disabled={busy} onClick={() => stopOne(entry.name)} aria-label={`Stop ${entry.name}`}>
                      <StopIcon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              ) : (
                <Tooltip title={disabled ? 'Load generation disabled' : 'Start'}>
                  <span>
                    <IconButton size="small" color="primary" disabled={busy || disabled} onClick={() => startSelectedOne(entry.name)} aria-label={`Start ${entry.name}`}>
                      <PlayArrowIcon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              )}
              <Tooltip title="Remove from registry">
                <span>
                  <IconButton size="small" disabled={busy} onClick={() => deleteOne(entry.name)} aria-label={`Delete ${entry.name}`}>
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
            </Box>
          ))
        )}
      </Paper>

      {/* Sub-tabs: focus on running/metrics, or on authoring a scenario */}
      <Tabs
        value={view === 'author' ? 1 : 0}
        onChange={(_, v: number) => setPanelView(v === 1 ? 'author' : 'run')}
        sx={{ mb: 1.5, minHeight: 36, '& .MuiTab-root': { minHeight: 36, py: 0.5 } }}
      >
        <Tab label="Run & Monitor" />
        <Tab label="Create / Edit" />
      </Tabs>

      {view === 'run' ? (
      <>

      {/* Concurrent running view (Wave C): every actively-running registered scenario. */}
      {runningEntries.length > 0 && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-running-scenarios">
          <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1 }}>
            Running Now ({runningEntries.length})
          </Typography>
          {runningEntries.map((entry) => {
            const st = entry.status;
            return (
              <Box key={entry.name} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid={`load-running-${entry.name}`}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{entry.name}</Typography>
                  {st?.elapsedMillis != null && (
                    <Chip size="small" variant="outlined" label={`${formatElapsed(st.elapsedMillis)} elapsed`} />
                  )}
                  {st && stageReadout(st) && (
                    <Chip size="small" color="primary" variant="outlined" label={stageReadout(st)} />
                  )}
                  {st && <VerdictBadge status={st} />}
                  <Box sx={{ flex: 1 }} />
                  <Button size="small" color="error" variant="outlined" startIcon={<StopIcon />} disabled={busy} onClick={() => stopOne(entry.name)}>
                    Stop
                  </Button>
                </Box>
                <RunProgressBar status={st} definition={entry.definition} />
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(110px, 1fr))', gap: 1 }}>
                  <Stat label="Active VUs" value={(st?.currentVus ?? 0).toLocaleString()} />
                  <Stat label="Requests sent" value={(st?.requestsSent ?? 0).toLocaleString()} />
                  <Stat label="Succeeded" value={(st?.succeeded ?? 0).toLocaleString()} />
                  <Stat label="Failed" value={(st?.failed ?? 0).toLocaleString()} />
                  <Stat label="Error rate" value={st ? `${(errorRate(st) * 100).toFixed(1)}%` : '—'} />
                  <Stat label="p95" value={`${(st?.p95Millis ?? 0).toFixed(0)} ms`} />
                  {st?.p999Millis != null && <Stat label="p99.9" value={`${st.p999Millis.toFixed(0)} ms`} />}
                  {st?.droppedIterations != null && st.droppedIterations > 0 && (
                    <Stat label="Dropped" value={st.droppedIterations.toLocaleString()} />
                  )}
                </Box>
                {st && <ThresholdResultsTable results={st.thresholdResults} />}
                <ReportButtons name={entry.name} onDownload={downloadReport} />
              </Box>
            );
          })}
        </Paper>
      )}

      {/* Live status while a run is active */}
      {running && status && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-live-status">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
            <Typography variant="caption" color="text.secondary">
              Running{status.name ? ` · ${status.name}` : ''}{status.runId ? ` · run ${status.runId}` : ''}
            </Typography>
            <Chip size="small" label={`${formatElapsed(status.elapsedMillis ?? 0)} elapsed`} variant="outlined" />
            {stageReadout(status) && (
              <Chip size="small" color="primary" variant="outlined" label={stageReadout(status)} data-testid="load-stage-readout" />
            )}
            <VerdictBadge status={status} />
            <Box sx={{ flex: 1 }} />
            <Button size="small" variant="outlined" startIcon={<EditIcon />} disabled={busy} onClick={handleEditRunning}>
              Edit running
            </Button>
            <Button size="small" color="error" variant="contained" startIcon={<StopIcon />} disabled={busy} onClick={handleStop}>
              Stop
            </Button>
          </Box>
          <RunProgressBar status={status} definition={status.definition} />
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 1 }}>
            <Stat label="Active VUs" value={(status.currentVus ?? 0).toLocaleString()} />
            <Stat label="Requests sent" value={(status.requestsSent ?? 0).toLocaleString()} />
            <Stat label="Succeeded" value={(status.succeeded ?? 0).toLocaleString()} />
            <Stat label="Failed" value={(status.failed ?? 0).toLocaleString()} />
            <Stat label="Error rate" value={`${(errorRate(status) * 100).toFixed(1)}%`} />
            <Stat label="p50" value={`${(status.p50Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p95" value={`${(status.p95Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p99" value={`${(status.p99Millis ?? 0).toFixed(0)} ms`} />
            {status.p999Millis != null && <Stat label="p99.9" value={`${status.p999Millis.toFixed(0)} ms`} />}
            {status.droppedIterations != null && status.droppedIterations > 0 && (
              <Stat label="Dropped" value={status.droppedIterations.toLocaleString()} />
            )}
          </Box>
          <ThresholdResultsTable results={status.thresholdResults} />
          {status.name && <ReportButtons name={status.name} onDownload={downloadReport} />}
          {status.labels && Object.keys(status.labels).length > 0 && (
            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', mt: 1 }}>
              {Object.entries(status.labels).map(([k, v]) => (
                <Chip key={k} size="small" variant="outlined" label={`${k}=${v}`} />
              ))}
            </Box>
          )}
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Re-submitting the form below (Start) replaces and restarts the active run.
          </Typography>
        </Paper>
      )}

      {/* Live chart — total across scenarios + a line per scenario, with metric and scenario toggles */}
      {frames.length > 0 && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-chart">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 0.5 }}>
            <Typography variant="caption" color="text.secondary">
              Live throughput &amp; latency{enabledScenarios.length > 1 ? ' — total + per scenario' : ''}
            </Typography>
          </Box>
          {/* Metric-type toggles (which series to plot) */}
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mb: scenarioNames.length > 1 ? 0.25 : 0.5 }} role="group" aria-label="Chart metric toggles">
            {SERIES_DEFS.map((d) => (
              <FormControlLabel
                key={d.key}
                sx={{ mr: 1 }}
                control={
                  <Checkbox
                    size="small"
                    checked={visible.has(d.key)}
                    onChange={() => toggleSeries(d.key)}
                  />
                }
                label={<Typography variant="caption">{d.label}</Typography>}
              />
            ))}
          </Box>
          {/* Per-scenario toggles — only when more than one scenario has data to compare */}
          {scenarioNames.length > 1 && (
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mb: 0.5, alignItems: 'center' }} role="group" aria-label="Chart scenario toggles">
              <Typography variant="caption" color="text.secondary" sx={{ mr: 0.5 }}>Scenarios:</Typography>
              {scenarioNames.map((name) => (
                <FormControlLabel
                  key={name}
                  sx={{ mr: 1 }}
                  control={
                    <Checkbox
                      size="small"
                      checked={!hiddenScenarios.has(name)}
                      onChange={() => toggleScenario(name)}
                    />
                  }
                  label={<Typography variant="caption">{name}</Typography>}
                />
              ))}
            </Box>
          )}
          {chartSeries.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
              {visible.size === 0 ? 'Select at least one metric to plot.' : 'Select at least one scenario to plot.'}
            </Typography>
          ) : (
            <MetricsLineChart series={chartSeries} timestamps={chartTimestamps} height={240} />
          )}
        </Paper>
      )}

      {/* Key metrics summary on stop/completion */}
      {showSummary && status && (
        <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-summary">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
            <Typography variant="caption" color="text.secondary">
              {status.state === 'completed' ? 'Run complete' : 'Run stopped'} — key metrics
              {status.name ? ` · ${status.name}` : ''}
            </Typography>
            <VerdictBadge status={status} />
          </Box>
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 1, mt: 1 }}>
            <Stat label="Requests sent" value={(status.requestsSent ?? 0).toLocaleString()} />
            <Stat label="Succeeded" value={(status.succeeded ?? 0).toLocaleString()} />
            <Stat label="Failed" value={(status.failed ?? 0).toLocaleString()} />
            <Stat label="Error rate" value={`${(errorRate(status) * 100).toFixed(1)}%`} />
            <Stat label="Peak VUs" value={peakVus(samples, status).toLocaleString()} />
            <Stat label="Throughput" value={`${throughput(status).toFixed(1)}/s`} />
            <Stat label="p50" value={`${(status.p50Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p95" value={`${(status.p95Millis ?? 0).toFixed(0)} ms`} />
            <Stat label="p99" value={`${(status.p99Millis ?? 0).toFixed(0)} ms`} />
            {status.p999Millis != null && <Stat label="p99.9" value={`${status.p999Millis.toFixed(0)} ms`} />}
            {status.droppedIterations != null && <Stat label="Dropped" value={status.droppedIterations.toLocaleString()} />}
          </Box>
          <ThresholdResultsTable results={status.thresholdResults} />
          {status.name && <ReportButtons name={status.name} onDownload={downloadReport} />}
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Deeper analysis lives in the{' '}
            <Link component="button" type="button" onClick={() => setView('metrics')}>Metrics tab</Link>{' '}
            (the <code>mock_server_load_*</code> series).
          </Typography>
        </Paper>
      )}

      {/* Empty state — nothing has run yet in this view */}
      {runningEntries.length === 0 && !running && frames.length === 0 && !showSummary && (
        <Paper variant="outlined" sx={{ p: 2, mb: 1.5, textAlign: 'center' }} data-testid="load-run-empty">
          <Typography variant="body2" color="text.secondary">
            Nothing running yet. Start a scenario from the registry above, or switch to the{' '}
            <Link component="button" type="button" onClick={() => setPanelView('author')}>Create / Edit</Link>{' '}
            tab to create one. Live throughput, latency and per-scenario charts appear here while runs are active.
          </Typography>
        </Paper>
      )}

      </>
      ) : (
      <>

      {/* Author form */}
      <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-author-form">
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
            {running ? 'Edit / Restart Scenario' : 'Create a Load Scenario'}
          </Typography>
          <Box sx={{ flex: 1 }} />
          <Button size="small" variant="outlined" onClick={() => setGenerateSource('openapi')} data-testid="load-generate-openapi-open">
            Generate from OpenAPI
          </Button>
          <Button size="small" variant="outlined" onClick={() => setGenerateSource('recording')} data-testid="load-generate-recording-open">
            Generate from recording
          </Button>
        </Box>

        {/* Scenario-level fields in their own uniform grid (matching ServiceChaosPanel's
            CHAOS_GRID idiom). The profile fields follow as a separate labelled group using
            the same FIELD_GRID so column widths line up across both groups. */}
        <Box sx={FIELD_GRID}>
          <TextField
            label="Scenario name" size="small" required value={form.name}
            onChange={setField('name')} fullWidth
          />
          <TextField
            select label="Template type" size="small" value={form.templateType}
            onChange={(e) => setForm((p) => ({ ...p, templateType: e.target.value as FormState['templateType'] }))}
            helperText="Engine for rendering templated step path/body (whole scenario)"
            fullWidth
          >
            <MenuItem value="VELOCITY">Velocity</MenuItem>
            <MenuItem value="MUSTACHE">Mustache</MenuItem>
          </TextField>
          <TextField
            label="Max requests (optional)" size="small" value={form.maxRequests}
            onChange={setField('maxRequests')} fullWidth placeholder="unlimited"
          />
          <TextField
            label="Start delay (ms)" size="small" value={form.startDelayMillis}
            onChange={setField('startDelayMillis')} fullWidth placeholder="none"
            helperText="Wait this long after Start before driving load"
          />
        </Box>

        {/* Profile — EITHER an explicit ordered list of stages OR a single declarative shape. */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5, flexWrap: 'wrap' }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Profile</Typography>
          <Tabs
            value={form.profileMode === 'SHAPE' ? 1 : 0}
            onChange={(_, v: number) => setForm((p) => ({ ...p, profileMode: v === 1 ? 'SHAPE' : 'STAGES' }))}
            sx={{ minHeight: 30, '& .MuiTab-root': { minHeight: 30, py: 0.25, fontSize: '0.75rem', minWidth: 0, px: 1.5 } }}
          >
            <Tab label="Explicit Stages" data-testid="load-profile-mode-stages" />
            <Tab label="Named Shape" data-testid="load-profile-mode-shape" />
          </Tabs>
          {form.profileMode === 'STAGES' && (
            <Button size="small" startIcon={<AddIcon />} onClick={addStage}>Add stage</Button>
          )}
        </Box>

        {form.profileMode === 'SHAPE' && (
          <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid="load-shape">
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
              A declarative load shape; the server expands it into stages.
            </Typography>
            <Box sx={FIELD_GRID}>
              <TextField
                select label="Shape type" size="small" value={form.shape.type}
                onChange={(e) => setShapeValue('type', e.target.value as LoadShapeType)}
                fullWidth data-testid="load-shape-type"
              >
                <MenuItem value="SPIKE">Spike</MenuItem>
                <MenuItem value="STAIRS">Stairs</MenuItem>
                <MenuItem value="RAMP_HOLD">Ramp &amp; hold</MenuItem>
              </TextField>
              <TextField
                select label="Drives" size="small" value={form.shape.metric}
                onChange={(e) => setShapeValue('metric', e.target.value as LoadShapeMetric)}
                helperText="VU (closed) or arrival rate (open)" fullWidth
              >
                <MenuItem value="VU">VUs</MenuItem>
                <MenuItem value="RATE">Rate (iterations/sec)</MenuItem>
              </TextField>
              {form.shape.type !== 'STAIRS' && (
                <TextField
                  select label="Curve" size="small" value={form.shape.curve}
                  onChange={(e) => setShapeValue('curve', e.target.value as RampCurve)}
                  fullWidth
                >
                  <MenuItem value="LINEAR">Linear</MenuItem>
                  <MenuItem value="EXPONENTIAL">Exponential</MenuItem>
                  <MenuItem value="QUADRATIC">Quadratic</MenuItem>
                </TextField>
              )}
              {form.shape.type === 'SPIKE' && (
                <>
                  <TextField label="Baseline" size="small" value={form.shape.baseline} onChange={setShapeField('baseline')} fullWidth />
                  <TextField label="Peak" size="small" value={form.shape.peak} onChange={setShapeField('peak')} fullWidth />
                  <TextField label="Ramp-up (ms)" size="small" value={form.shape.rampUpMillis} onChange={setShapeField('rampUpMillis')} fullWidth />
                  <TextField label="Hold at peak (ms)" size="small" value={form.shape.holdMillis} onChange={setShapeField('holdMillis')} fullWidth />
                  <TextField label="Ramp-down (ms)" size="small" value={form.shape.rampDownMillis} onChange={setShapeField('rampDownMillis')} fullWidth />
                  <TextField label="Recovery hold (ms, optional)" size="small" value={form.shape.recoveryHoldMillis} onChange={setShapeField('recoveryHoldMillis')} fullWidth placeholder="none" />
                </>
              )}
              {form.shape.type === 'STAIRS' && (
                <>
                  <TextField label="Start level" size="small" value={form.shape.start} onChange={setShapeField('start')} fullWidth />
                  <TextField label="Step rise" size="small" value={form.shape.step} onChange={setShapeField('step')} fullWidth />
                  <TextField label="Number of steps" size="small" value={form.shape.steps} onChange={setShapeField('steps')} fullWidth />
                  <TextField label="Step duration (ms)" size="small" value={form.shape.stepDurationMillis} onChange={setShapeField('stepDurationMillis')} fullWidth />
                </>
              )}
              {form.shape.type === 'RAMP_HOLD' && (
                <>
                  <TextField label="Target" size="small" value={form.shape.target} onChange={setShapeField('target')} fullWidth />
                  <TextField label="Ramp (ms)" size="small" value={form.shape.rampMillis} onChange={setShapeField('rampMillis')} fullWidth />
                  <TextField label="Hold (ms)" size="small" value={form.shape.holdMillis} onChange={setShapeField('holdMillis')} fullWidth />
                </>
              )}
            </Box>
          </Box>
        )}

        {form.profileMode === 'STAGES' && form.stages.map((stage, i) => (
          <Box key={stage.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid={`load-stage-${i}`}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">Stage {i + 1}</Typography>
              <Box sx={{ flex: 1 }} />
              <IconButton size="small" onClick={() => moveStage(i, -1)} aria-label={`Move stage ${i + 1} up`} disabled={i === 0}>
                <ArrowUpwardIcon fontSize="small" />
              </IconButton>
              <IconButton size="small" onClick={() => moveStage(i, 1)} aria-label={`Move stage ${i + 1} down`} disabled={i === form.stages.length - 1}>
                <ArrowDownwardIcon fontSize="small" />
              </IconButton>
              <IconButton size="small" onClick={() => removeStage(i)} aria-label={`Remove stage ${i + 1}`} disabled={form.stages.length <= 1}>
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Box>
            <Box sx={FIELD_GRID}>
              <TextField
                select label="Stage type" size="small" value={stage.type}
                onChange={(e) => setStageValue(i, 'type', e.target.value as LoadStageType)}
                fullWidth
              >
                <MenuItem value="VU">VU (virtual users)</MenuItem>
                <MenuItem value="RATE">Rate (iterations/sec)</MenuItem>
                <MenuItem value="PAUSE">Pause (no load)</MenuItem>
              </TextField>
              {stage.type !== 'PAUSE' && (
                <TextField
                  select label="Mode" size="small" value={stage.mode}
                  onChange={(e) => setStageValue(i, 'mode', e.target.value as StageFormState['mode'])}
                  fullWidth
                >
                  <MenuItem value="HOLD">Hold</MenuItem>
                  <MenuItem value="RAMP">Ramp</MenuItem>
                </TextField>
              )}
              {stage.type === 'VU' && stage.mode === 'HOLD' && (
                <TextField label="Virtual users (VUs)" size="small" value={stage.vus} onChange={setStageField(i, 'vus')} fullWidth />
              )}
              {stage.type === 'VU' && stage.mode === 'RAMP' && (
                <>
                  <TextField label="Start VUs" size="small" value={stage.startVus} onChange={setStageField(i, 'startVus')} fullWidth />
                  <TextField label="End VUs" size="small" value={stage.endVus} onChange={setStageField(i, 'endVus')} fullWidth />
                </>
              )}
              {stage.type === 'RATE' && stage.mode === 'HOLD' && (
                <TextField label="Rate (iterations/sec)" size="small" value={stage.rate} onChange={setStageField(i, 'rate')} fullWidth />
              )}
              {stage.type === 'RATE' && stage.mode === 'RAMP' && (
                <>
                  <TextField label="Start rate (iterations/sec)" size="small" value={stage.startRate} onChange={setStageField(i, 'startRate')} fullWidth />
                  <TextField label="End rate (iterations/sec)" size="small" value={stage.endRate} onChange={setStageField(i, 'endRate')} fullWidth />
                </>
              )}
              {stage.type === 'RATE' && (
                <TextField label="Max VUs (optional)" size="small" value={stage.maxVus} onChange={setStageField(i, 'maxVus')} fullWidth placeholder="global cap" />
              )}
              {stage.type !== 'PAUSE' && stage.mode === 'RAMP' && (
                <TextField
                  select label="Curve" size="small" value={stage.curve}
                  onChange={(e) => setStageValue(i, 'curve', e.target.value as RampCurve)}
                  fullWidth
                >
                  <MenuItem value="LINEAR">Linear</MenuItem>
                  <MenuItem value="EXPONENTIAL">Exponential</MenuItem>
                  <MenuItem value="QUADRATIC">Quadratic</MenuItem>
                </TextField>
              )}
              <TextField label="Duration (ms)" size="small" value={stage.durationMillis} onChange={setStageField(i, 'durationMillis')} fullWidth />
            </Box>
          </Box>
        ))}

        {/* Labels */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Labels (optional)</Typography>
          <Button size="small" startIcon={<AddIcon />} onClick={addLabel}>Add label</Button>
        </Box>
        {form.labels.map((label, i) => (
          <Box key={label.id} sx={{ display: 'flex', gap: 1, mt: 0.5, mb: 1.25, alignItems: 'center', flexWrap: 'wrap' }}>
            <TextField label="Key" size="small" value={label.key} onChange={setLabelField(i, 'key')} sx={responsiveWidth(160)} />
            <TextField label="Value" size="small" value={label.value} onChange={setLabelField(i, 'value')} sx={responsiveWidth(160)} />
            <IconButton size="small" onClick={() => removeLabel(i)} aria-label={`Remove label ${i + 1}`}><DeleteIcon fontSize="small" /></IconButton>
          </Box>
        ))}

        {/* Thresholds — in-run pass/fail criteria; run carries a PASS verdict iff all hold. */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Thresholds (optional)</Typography>
          <Button size="small" startIcon={<AddIcon />} onClick={addThreshold} data-testid="load-add-threshold">Add threshold</Button>
        </Box>
        {form.thresholds.map((t, i) => (
          <Box key={t.id} sx={{ display: 'flex', gap: 1, mb: 1, alignItems: 'center', flexWrap: 'wrap' }} data-testid={`load-threshold-${i}`}>
            <TextField
              select label="Metric" size="small" value={t.metric}
              onChange={(e) => setThresholdValue(i, 'metric', e.target.value as LoadThresholdMetric)}
              sx={responsiveWidth(180)}
            >
              <MenuItem value="LATENCY_P50">Latency p50 (ms)</MenuItem>
              <MenuItem value="LATENCY_P95">Latency p95 (ms)</MenuItem>
              <MenuItem value="LATENCY_P99">Latency p99 (ms)</MenuItem>
              <MenuItem value="LATENCY_P999">Latency p99.9 (ms)</MenuItem>
              <MenuItem value="ERROR_RATE">Error rate (0–1)</MenuItem>
              <MenuItem value="THROUGHPUT_RPS">Throughput (req/s)</MenuItem>
            </TextField>
            <TextField
              select label="Comparator" size="small" value={t.comparator}
              onChange={(e) => setThresholdValue(i, 'comparator', e.target.value as LoadComparator)}
              sx={responsiveWidth(150)}
            >
              <MenuItem value="LESS_THAN">&lt;</MenuItem>
              <MenuItem value="LESS_THAN_OR_EQUAL">≤</MenuItem>
              <MenuItem value="GREATER_THAN">&gt;</MenuItem>
              <MenuItem value="GREATER_THAN_OR_EQUAL">≥</MenuItem>
            </TextField>
            <TextField
              label="Threshold" size="small" value={t.threshold}
              onChange={(e) => setThresholdValue(i, 'threshold', e.target.value)}
              sx={responsiveWidth(120)}
            />
            <IconButton size="small" onClick={() => removeThreshold(i)} aria-label={`Remove threshold ${i + 1}`}><DeleteIcon fontSize="small" /></IconButton>
          </Box>
        ))}
        {form.thresholds.length > 0 && (
          <Box sx={{ display: 'flex', gap: 1, mb: 1, alignItems: 'center', flexWrap: 'wrap' }}>
            <FormControlLabel
              control={
                <Checkbox
                  size="small" checked={form.abortOnFail}
                  onChange={(e) => setForm((p) => ({ ...p, abortOnFail: e.target.checked }))}
                  data-testid="load-abort-on-fail"
                />
              }
              label={<Typography variant="caption">Abort run on a FAIL verdict</Typography>}
            />
            {form.abortOnFail && (
              <TextField
                label="Abort grace (ms)" size="small" value={form.abortGraceMillis}
                onChange={setField('abortGraceMillis')} sx={responsiveWidth(160)} placeholder="0"
                helperText="Ignore breaches for the first N ms"
              />
            )}
          </Box>
        )}

        {/* Pacing — adaptive per-VU iteration cycle (closed-model VU loop only). */}
        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600, display: 'block', mt: 1.5, mb: 0.5 }}>Pacing (optional)</Typography>
        <Box sx={{ display: 'flex', gap: 1, mb: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <TextField
            select label="Pacing mode" size="small" value={form.pacingMode}
            onChange={(e) => setForm((p) => ({ ...p, pacingMode: e.target.value as LoadPacingMode }))}
            sx={responsiveWidth(220)} data-testid="load-pacing-mode"
          >
            <MenuItem value="NONE">None (immediate reschedule)</MenuItem>
            <MenuItem value="CONSTANT_PACING">Constant pacing (cycle ms)</MenuItem>
            <MenuItem value="CONSTANT_THROUGHPUT">Constant throughput (iters/s per VU)</MenuItem>
          </TextField>
          {form.pacingMode !== 'NONE' && (
            <TextField
              label={form.pacingMode === 'CONSTANT_PACING' ? 'Cycle (ms)' : 'Iterations/sec per VU'}
              size="small" value={form.pacingValue} onChange={setField('pacingValue')} sx={responsiveWidth(180)}
            />
          )}
        </Box>

        {/* Feeder — inline parameterized test data exposed per iteration. */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5, flexWrap: 'wrap' }}>
          <FormControlLabel
            control={
              <Checkbox
                size="small" checked={form.feeder.enabled}
                onChange={(e) => setFeederValue('enabled', e.target.checked)}
                data-testid="load-feeder-enabled"
              />
            }
            label={<Typography variant="caption" sx={{ fontWeight: 600 }}>Data feeder (optional)</Typography>}
          />
        </Box>
        {form.feeder.enabled && (
          <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid="load-feeder">
            <Box sx={{ display: 'flex', gap: 1, mb: 1, flexWrap: 'wrap' }}>
              <TextField
                select label="Source" size="small" value={form.feeder.mode}
                onChange={(e) => setFeederValue('mode', e.target.value as FeederFormState['mode'])}
                sx={responsiveWidth(160)}
              >
                <MenuItem value="ROWS">Inline rows</MenuItem>
                <MenuItem value="RAW">Raw CSV/JSON</MenuItem>
              </TextField>
              <TextField
                select label="Strategy" size="small" value={form.feeder.strategy}
                onChange={(e) => setFeederValue('strategy', e.target.value as LoadFeederStrategy)}
                sx={responsiveWidth(160)}
              >
                <MenuItem value="CIRCULAR">Circular</MenuItem>
                <MenuItem value="RANDOM">Random</MenuItem>
                <MenuItem value="SEQUENTIAL">Sequential (replay once)</MenuItem>
              </TextField>
              {form.feeder.mode === 'RAW' && (
                <TextField
                  select label="Format" size="small" value={form.feeder.rawFormat}
                  onChange={(e) => setFeederValue('rawFormat', e.target.value as 'CSV' | 'JSON')}
                  sx={responsiveWidth(120)}
                >
                  <MenuItem value="CSV">CSV</MenuItem>
                  <MenuItem value="JSON">JSON</MenuItem>
                </TextField>
              )}
            </Box>
            {form.feeder.mode === 'RAW' ? (
              <TextField
                label={form.feeder.rawFormat === 'CSV' ? 'CSV data (first line = header row)' : 'JSON data (array of flat objects)'}
                size="small" value={form.feeder.rawData} onChange={(e) => setFeederValue('rawData', e.target.value)}
                fullWidth multiline minRows={3}
                slotProps={{ htmlInput: { style: { resize: 'vertical', overflow: 'auto' } } }}
              />
            ) : (
              <>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
                  One row selected per iteration, exposed as <code>{'$iteration.data.<column>'}</code>.
                </Typography>
                {form.feeder.rows.map((row, ri) => (
                  <Box key={row.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 0.75, mb: 0.75 }} data-testid={`load-feeder-row-${ri}`}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                      <Typography variant="caption" color="text.secondary">Row {ri + 1}</Typography>
                      <Button size="small" startIcon={<AddIcon />} onClick={() => addFeederCell(ri)}>Add column</Button>
                      <Box sx={{ flex: 1 }} />
                      <IconButton size="small" onClick={() => removeFeederRow(ri)} aria-label={`Remove feeder row ${ri + 1}`} disabled={form.feeder.rows.length <= 1}>
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Box>
                    {row.cells.map((cell, ci) => (
                      <Box key={cell.id} sx={{ display: 'flex', gap: 1, mb: 0.5, alignItems: 'center', flexWrap: 'wrap' }}>
                        <TextField label="Column" size="small" value={cell.key} onChange={setFeederCellField(ri, ci, 'key')} sx={responsiveWidth(160)} />
                        <TextField label="Value" size="small" value={cell.value} onChange={setFeederCellField(ri, ci, 'value')} sx={responsiveWidth(180)} />
                        <IconButton size="small" onClick={() => removeFeederCell(ri, ci)} aria-label={`Remove column ${ci + 1} of feeder row ${ri + 1}`}>
                          <DeleteIcon fontSize="small" />
                        </IconButton>
                      </Box>
                    ))}
                  </Box>
                ))}
                <Button size="small" startIcon={<AddIcon />} onClick={addFeederRow}>Add row</Button>
              </>
            )}
          </Box>
        )}

        {/* Steps */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5, mb: 0.5, flexWrap: 'wrap' }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Steps</Typography>
          <TextField
            select label="Step selection" size="small" value={form.stepSelection}
            onChange={(e) => setForm((p) => ({ ...p, stepSelection: e.target.value as LoadStepSelection }))}
            sx={responsiveWidth(220)} data-testid="load-step-selection"
          >
            <MenuItem value="SEQUENTIAL">Sequential (all steps in order)</MenuItem>
            <MenuItem value="WEIGHTED">Weighted (one step per iteration)</MenuItem>
          </TextField>
          <Button size="small" startIcon={<AddIcon />} onClick={addStep}>Add step</Button>
        </Box>
        {form.steps.map((step, i) => (
          <Box key={step.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1, mb: 1 }} data-testid={`load-step-${i}`}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">Step {i + 1}</Typography>
              <Box sx={{ flex: 1 }} />
              <IconButton size="small" onClick={() => removeStep(i)} aria-label={`Remove step ${i + 1}`} disabled={form.steps.length <= 1}>
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Box>
            {/* Step fields on TWO rows so Path gets ample width. Row 1: name, method,
                Path (widest column). Row 2: the remaining four fields in the uniform
                auto-fit FIELD_GRID. */}
            <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(140px, 1fr) minmax(110px, 0.6fr) minmax(260px, 2.5fr)', gap: 1, alignItems: 'start', mb: 1 }}>
              <TextField label="Step name (optional)" size="small" value={step.name} onChange={setStepField(i, 'name')} fullWidth />
              <TextField select label="Method" size="small" value={step.method}
                onChange={(e) => setForm((p) => ({ ...p, steps: p.steps.map((s, j) => (j === i ? { ...s, method: e.target.value } : s)) }))}
                fullWidth>
                {['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'].map((m) => <MenuItem key={m} value={m}>{m}</MenuItem>)}
              </TextField>
              <TextField label="Path" size="small" value={step.path} onChange={setStepField(i, 'path')} fullWidth />
            </Box>
            <Box sx={FIELD_GRID}>
              <TextField label="Target host" size="small" value={step.host} onChange={setStepField(i, 'host')} fullWidth />
              <TextField label="Target port" size="small" value={step.port} onChange={setStepField(i, 'port')} fullWidth />
              <TextField select label="Scheme" size="small" value={step.scheme}
                onChange={(e) => setForm((p) => ({ ...p, steps: p.steps.map((s, j) => (j === i ? { ...s, scheme: e.target.value as 'HTTP' | 'HTTPS' } : s)) }))}
                fullWidth>
                <MenuItem value="HTTP">HTTP</MenuItem>
                <MenuItem value="HTTPS">HTTPS</MenuItem>
              </TextField>
              <TextField label="Delay after step (ms)" size="small" value={step.thinkTimeMs} onChange={setStepField(i, 'thinkTimeMs')} helperText="Wait this long after this step's request before the next step (a.k.a. think-time)" fullWidth placeholder="none" />
              {form.stepSelection === 'WEIGHTED' && (
                <TextField label="Weight" size="small" value={step.weight} onChange={setStepField(i, 'weight')} fullWidth placeholder="1.0" helperText="Relative selection weight (> 0)" data-testid={`load-step-weight-${i}`} />
              )}
            </Box>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Request headers (optional)</Typography>
              <Button size="small" startIcon={<AddIcon />} onClick={() => addStepHeader(i)}>Add header</Button>
            </Box>
            {step.headers.map((header, h) => (
              <Box key={header.id} sx={{ display: 'flex', gap: 1, mt: 0.5, mb: 1.25, alignItems: 'center', flexWrap: 'wrap' }}>
                <TextField label="Header name" size="small" value={header.key} onChange={setStepHeaderField(i, h, 'key')} sx={responsiveWidth(180)} />
                <TextField label="Header value" size="small" value={header.value} onChange={setStepHeaderField(i, h, 'value')} sx={responsiveWidth(200)} />
                <IconButton size="small" onClick={() => removeStepHeader(i, h)} aria-label={`Remove header ${h + 1} of step ${i + 1}`}>
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </Box>
            ))}
            <TextField
              label="Body (optional, templated)" size="small" value={step.body}
              onChange={setStepField(i, 'body')}
              fullWidth multiline minRows={bodyMinRows(step.body)} sx={{ mt: 1 }}
              slotProps={{ htmlInput: { style: { resize: 'vertical', overflow: 'auto' } } }}
            />
            {/* Captures — extract from this step's response into variables later steps can use. */}
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1, mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Captures (optional)</Typography>
              <Button size="small" startIcon={<AddIcon />} onClick={() => addStepCapture(i)} data-testid={`load-add-capture-${i}`}>Add capture</Button>
            </Box>
            {step.captures.map((capture, c) => (
              <Box key={capture.id} sx={{ display: 'flex', gap: 1, mt: 0.5, mb: 0.75, alignItems: 'center', flexWrap: 'wrap' }} data-testid={`load-capture-${i}-${c}`}>
                <TextField label="Variable name" size="small" value={capture.name} onChange={(e) => setStepCaptureValue(i, c, 'name', e.target.value)} sx={responsiveWidth(150)} />
                <TextField
                  select label="Source" size="small" value={capture.source}
                  onChange={(e) => setStepCaptureValue(i, c, 'source', e.target.value as LoadCaptureSource)}
                  sx={responsiveWidth(160)}
                >
                  <MenuItem value="BODY_JSONPATH">Body JSONPath</MenuItem>
                  <MenuItem value="HEADER">Header</MenuItem>
                  <MenuItem value="BODY_REGEX">Body regex</MenuItem>
                </TextField>
                <TextField label="Expression" size="small" value={capture.expression} onChange={(e) => setStepCaptureValue(i, c, 'expression', e.target.value)} sx={responsiveWidth(180)} />
                <TextField label="Default (optional)" size="small" value={capture.defaultValue} onChange={(e) => setStepCaptureValue(i, c, 'defaultValue', e.target.value)} sx={responsiveWidth(140)} placeholder="unset" />
                <IconButton size="small" onClick={() => removeStepCapture(i, c)} aria-label={`Remove capture ${c + 1} of step ${i + 1}`}><DeleteIcon fontSize="small" /></IconButton>
              </Box>
            ))}
          </Box>
        ))}

        <Box sx={{ display: 'flex', gap: 1, mt: 1, flexWrap: 'wrap', alignItems: 'center' }}>
          <Button
            variant="outlined" color="primary" startIcon={<SaveIcon />}
            disabled={busy} onClick={handleLoad}
            data-testid="load-register"
          >
            Load
          </Button>
          <Button
            variant="contained" color="primary" startIcon={<PlayArrowIcon />}
            disabled={busy || disabled} onClick={handleLoadAndRun}
            data-testid="load-register-run"
          >
            Load &amp; Run
          </Button>
          <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center' }}>
            <strong>Load</strong> registers without running (allowed even when load generation is off).{' '}
            <strong>Load &amp; Run</strong> registers then starts it.
          </Typography>
        </Box>
      </Paper>

      {/* Generated client code — inline below the form, always rendered from the best-effort
          partial scenario so it grows field-by-field as the user types. */}
      <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }} data-testid="load-codegen">
        {codeError && (
          <Alert severity="info" data-testid="load-code-incomplete" sx={{ mb: 1 }}>
            <AlertTitle>Preview updates as you type</AlertTitle>
            Still needed before you can run it: {codeError}
          </Alert>
        )}
        <LoadScenarioReview scenario={codeScenario} baseUrl={baseUrl} />
      </Paper>

      </>
      )}

      {generateSource && (
        <GenerateDialog
          source={generateSource}
          connectionParams={connectionParams}
          onClose={() => setGenerateSource(null)}
          onGenerated={onGenerated}
        />
      )}
    </Box>
  );
}

/**
 * Modal that seeds a load scenario from an OpenAPI spec or recorded proxy traffic, then loads the
 * returned scenario into the author form. Both endpoints register the scenario server-side (LOADED,
 * no traffic) and echo it back; on success the parent loads it into the editor for review/tweaking.
 */
function GenerateDialog({
  source,
  connectionParams,
  onClose,
  onGenerated,
}: {
  source: 'openapi' | 'recording';
  connectionParams: ConnectionParams;
  onClose: () => void;
  onGenerated: (scenario: LoadScenarioDTO) => void;
}) {
  const [name, setName] = useState('');
  const [spec, setSpec] = useState('');
  const [mode, setMode] = useState<GenerateRecordingMode>('VERBATIM');
  const [maxSteps, setMaxSteps] = useState('');
  const [targetHost, setTargetHost] = useState('');
  const [targetPort, setTargetPort] = useState('');
  const [targetScheme, setTargetScheme] = useState<'http' | 'https'>('http');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<HumanError | null>(null);

  const buildTarget = () => {
    const host = targetHost.trim();
    const port = num(targetPort);
    if (host === '' && port == null) return undefined;
    return { host: host || undefined, port: port ?? undefined, scheme: targetScheme };
  };

  const submit = () => {
    void (async () => {
      setBusy(true);
      setError(null);
      try {
        if (source === 'openapi') {
          const scenario = await generateFromOpenAPI(connectionParams, {
            name: name.trim(),
            specUrlOrPayload: spec,
            target: buildTarget(),
          });
          onGenerated(scenario);
        } else {
          const ms = num(maxSteps);
          const scenario = await generateFromRecording(connectionParams, {
            name: name.trim(),
            mode,
            maxSteps: ms ?? undefined,
            target: buildTarget(),
          });
          onGenerated(scenario);
        }
      } catch (e) {
        setError(humanizeError(e));
      } finally {
        setBusy(false);
      }
    })();
  };

  const title = source === 'openapi' ? 'Generate from OpenAPI' : 'Generate from Recording';
  const canSubmit = name.trim() !== '' && (source === 'recording' || spec.trim() !== '');

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="load-generate-dialog">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        {error && <HumanErrorAlert error={error} onClose={() => setError(null)} sx={{ mb: 1.5 }} />}
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, mt: 0.5 }}>
          <TextField
            label="Scenario name" size="small" required value={name}
            onChange={(e) => setName(e.target.value)} fullWidth autoFocus
            data-testid="load-generate-name"
          />
          {source === 'openapi' ? (
            <TextField
              label="OpenAPI spec (inline JSON/YAML, URL, or file/classpath)" size="small" required
              value={spec} onChange={(e) => setSpec(e.target.value)} fullWidth multiline minRows={4}
              data-testid="load-generate-spec"
              slotProps={{ htmlInput: { style: { resize: 'vertical', overflow: 'auto' } } }}
            />
          ) : (
            <>
              <TextField
                select label="Mode" size="small" value={mode}
                onChange={(e) => setMode(e.target.value as GenerateRecordingMode)} fullWidth
                helperText="VERBATIM: one step per recorded request. TEMPLATIZED: one per unique route."
                data-testid="load-generate-mode"
              >
                <MenuItem value="VERBATIM">Verbatim</MenuItem>
                <MenuItem value="TEMPLATIZED">Templatized</MenuItem>
              </TextField>
              {mode === 'VERBATIM' && (
                <TextField
                  label="Max steps (optional)" size="small" value={maxSteps}
                  onChange={(e) => setMaxSteps(e.target.value)} fullWidth placeholder="all recorded requests"
                />
              )}
            </>
          )}
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
            Target (optional — overrides the spec/recording routing)
          </Typography>
          <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
            <TextField label="Host" size="small" value={targetHost} onChange={(e) => setTargetHost(e.target.value)} sx={responsiveWidth(180)} />
            <TextField label="Port" size="small" value={targetPort} onChange={(e) => setTargetPort(e.target.value)} sx={responsiveWidth(120)} />
            <TextField
              select label="Scheme" size="small" value={targetScheme}
              onChange={(e) => setTargetScheme(e.target.value as 'http' | 'https')} sx={responsiveWidth(120)}
            >
              <MenuItem value="http">http</MenuItem>
              <MenuItem value="https">https</MenuItem>
            </TextField>
          </Box>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={busy || !canSubmit} data-testid="load-generate-submit">
          Generate
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** MUI Chip colour for each registry lifecycle state. */
function registryStateColor(
  state: LoadScenarioState,
): 'default' | 'success' | 'info' | 'warning' | 'primary' {
  switch (state) {
    case 'RUNNING': return 'success';
    case 'PENDING': return 'primary';
    case 'COMPLETED': return 'info';
    case 'STOPPED': return 'warning';
    case 'LOADED':
    default: return 'default';
  }
}

/** A small label/value stat cell used in the live + summary metric grids. */
function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card elevation={0} sx={{ p: 1, bgcolor: 'action.hover' }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2 }}>{value}</Typography>
    </Card>
  );
}

const THRESHOLD_METRIC_LABEL: Record<LoadThresholdMetric, string> = {
  LATENCY_P50: 'p50 latency',
  LATENCY_P95: 'p95 latency',
  LATENCY_P99: 'p99 latency',
  LATENCY_P999: 'p99.9 latency',
  ERROR_RATE: 'error rate',
  THROUGHPUT_RPS: 'throughput',
};

const COMPARATOR_SYMBOL: Record<LoadComparator, string> = {
  LESS_THAN: '<',
  LESS_THAN_OR_EQUAL: '≤',
  GREATER_THAN: '>',
  GREATER_THAN_OR_EQUAL: '≥',
};

/** Format a per-run metric value for display (ms for latency, % for error rate, /s for throughput). */
function formatMetricValue(metric: LoadThresholdMetric, value: number | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  if (metric === 'ERROR_RATE') return `${(value * 100).toFixed(2)}%`;
  if (metric === 'THROUGHPUT_RPS') return `${value.toFixed(1)}/s`;
  return `${value.toFixed(0)} ms`;
}

/**
 * The threshold verdict badge (PASS / FAIL) plus an "aborted early" note when the run was stopped by
 * an abortOnFail breach. Renders nothing when no verdict was computed (the scenario had no thresholds).
 */
function VerdictBadge({ status }: { status: LoadScenarioStatus }) {
  if (!status.verdict) return null;
  return (
    <>
      <Chip
        size="small"
        color={status.verdict === 'PASS' ? 'success' : 'error'}
        label={status.verdict === 'PASS' ? 'Thresholds PASS' : 'Thresholds FAIL'}
        data-testid="load-verdict"
      />
      {status.abortedByThreshold && (
        <Chip size="small" color="error" variant="outlined" label="aborted early" />
      )}
    </>
  );
}

/** Per-threshold results table behind the verdict. Renders nothing when there are no results. */
function ThresholdResultsTable({ results }: { results?: ThresholdResult[] }) {
  if (!results || results.length === 0) return null;
  return (
    <Box sx={{ mt: 1 }} data-testid="load-threshold-results">
      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Threshold results</Typography>
      <Box sx={{ display: 'grid', gridTemplateColumns: 'auto auto 1fr auto', gap: 0.5, mt: 0.5, alignItems: 'center' }}>
        {results.map((r, i) => (
          <Box key={i} sx={{ display: 'contents' }}>
            <Chip
              size="small"
              color={r.satisfied ? 'success' : 'error'}
              variant="outlined"
              label={r.satisfied ? 'PASS' : 'FAIL'}
            />
            <Typography variant="caption">{THRESHOLD_METRIC_LABEL[r.metric]}</Typography>
            <Typography variant="caption" color="text.secondary">
              {COMPARATOR_SYMBOL[r.comparator]} {formatMetricValue(r.metric, r.threshold)}
            </Typography>
            <Typography variant="caption" sx={{ fontWeight: 600 }}>
              observed {formatMetricValue(r.metric, r.observed)}
            </Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}

/** Two buttons that download the run's end-of-run report as JSON or JUnit-XML. */
function ReportButtons({ name, onDownload }: { name: string; onDownload: (name: string, format?: 'junit') => void }) {
  return (
    <Box sx={{ display: 'flex', gap: 1, mt: 1, flexWrap: 'wrap' }}>
      <Button size="small" variant="outlined" onClick={() => onDownload(name)} data-testid={`load-report-json-${name}`}>
        Download report (JSON)
      </Button>
      <Button size="small" variant="outlined" onClick={() => onDownload(name, 'junit')} data-testid={`load-report-junit-${name}`}>
        Download report (JUnit XML)
      </Button>
    </Box>
  );
}

/** Sum of all stage durations (ms) for a scenario definition; 0 when unknown. */
function totalDurationMillis(definition?: LoadScenarioDTO): number {
  return (definition?.profile?.stages ?? []).reduce((sum, s) => sum + (s.durationMillis ?? 0), 0);
}

/**
 * Determinate run-progress bar: fills with elapsed/total time so you can see how far through the
 * scenario a run is, and is coloured by phase — green while driving load, amber during a PAUSE
 * stage. Replaces the old indeterminate bar, whose constant sweep was distracting. Falls back to an
 * empty bar when the total duration is unknown (e.g. an older server that doesn't echo the
 * definition).
 */
function RunProgressBar({ status, definition }: { status?: LoadScenarioStatus; definition?: LoadScenarioDTO }) {
  const total = totalDurationMillis(definition ?? status?.definition);
  const elapsed = status?.elapsedMillis ?? 0;
  const value = total > 0 ? Math.min(100, Math.max(0, (elapsed / total) * 100)) : 0;
  const paused = status?.stageType === 'PAUSE';
  return (
    <LinearProgress
      variant="determinate"
      value={value}
      color={paused ? 'warning' : 'success'}
      aria-label={paused ? 'Run paused' : 'Run progress'}
      sx={{ mb: 1, height: 6, borderRadius: 1 }}
    />
  );
}

/**
 * Compact readout of which staged-profile stage is currently active, e.g. "Stage 2/3 · RATE · target 50/s".
 * Uses the server's `stageIndex`/`stageType`/`currentTarget`; the total stage count comes from the echoed
 * definition when available. Returns '' when the server doesn't report a running stage (older servers).
 */
function stageReadout(status: LoadScenarioStatus): string {
  if (status.stageIndex == null && status.stageType == null) return '';
  const total = status.definition?.profile?.stages?.length;
  const position = status.stageIndex != null
    ? `Stage ${status.stageIndex + 1}${total ? `/${total}` : ''}`
    : 'Stage';
  const parts = [position];
  if (status.stageType) parts.push(status.stageType);
  if (status.currentTarget != null && status.stageType && status.stageType !== 'PAUSE') {
    const target = status.stageType === 'RATE'
      ? `target ${Number(status.currentTarget.toFixed(1))}/s`
      : `target ${Math.round(status.currentTarget)} VUs`;
    parts.push(target);
  }
  return parts.join(' · ');
}

/** Peak active VUs observed across samples, falling back to the final status. */
function peakVus(samples: Sample[], status: LoadScenarioStatus): number {
  const fromSamples = samples.reduce((m, s) => Math.max(m, s.currentVus), 0);
  return Math.max(fromSamples, status.currentVus ?? 0);
}

/** Overall throughput (req/s) = requestsSent / elapsed seconds. */
function throughput(status: LoadScenarioStatus): number {
  const sent = status.requestsSent ?? 0;
  const seconds = (status.elapsedMillis ?? 0) / 1000;
  if (seconds <= 0) return 0;
  return sent / seconds;
}
