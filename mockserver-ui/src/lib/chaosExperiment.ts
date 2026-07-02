/**
 * Client for MockServer's chaos experiment orchestrator endpoint
 * (`/mockserver/chaosExperiment`). Start, monitor, and stop multi-stage
 * chaos experiments that progress automatically through ordered stages.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import type { HttpChaosProfileDTO } from './serviceChaos';

/** A single stage in a chaos experiment: duration + host-to-profile mapping. */
export interface ExperimentStageDTO {
  durationMillis: number;
  profiles: Record<string, HttpChaosProfileDTO>;
}

/** The top-level SLO verdict result (AND of all per-objective results). */
export type SloResult = 'PASS' | 'FAIL' | 'INCONCLUSIVE';

/** The service-level indicator computed over the window. */
export type SloSli = 'LATENCY_P50' | 'LATENCY_P95' | 'LATENCY_P99' | 'ERROR_RATE';

/** Comparator applied between the observed indicator and its threshold. */
export type SloComparator =
  | 'LESS_THAN'
  | 'LESS_THAN_OR_EQUAL'
  | 'GREATER_THAN'
  | 'GREATER_THAN_OR_EQUAL';

/**
 * The evaluated outcome of a single SLO objective: the observed indicator value
 * over the window and whether it satisfied the threshold. `observedValue` is null
 * (and `result` INCONCLUSIVE) when the indicator could not be computed — e.g.
 * error rate over zero requests.
 */
export interface SloObjectiveResultDTO {
  sli: SloSli;
  comparator: SloComparator;
  threshold: number;
  observedValue: number | null;
  result: SloResult;
  detail?: string | null;
}

/**
 * Terminal SLO verdict over the experiment window, emitted on termination when
 * the experiment carried `sloCriteria`. The top-level `result` is the AND of the
 * per-objective results.
 */
export interface SloVerdictDTO {
  name?: string | null;
  result: SloResult;
  windowFromEpochMillis: number;
  windowToEpochMillis: number;
  sampleCount: number;
  objectiveResults: SloObjectiveResultDTO[];
}

/** A single SLO objective within an experiment's `sloCriteria`. */
export interface SloObjectiveDTO {
  sli: SloSli;
  comparator: SloComparator;
  threshold: number;
}

/** The SLO criteria attached to an experiment definition (verify-on-terminate). */
export interface SloCriteriaDTO {
  name?: string | null;
  objectives?: SloObjectiveDTO[];
  minimumSampleCount?: number | null;
  upstreamHosts?: string[];
}

/** The experiment definition sent to PUT /mockserver/chaosExperiment. */
export interface ExperimentDefinitionDTO {
  name: string;
  loop?: boolean;
  stages: ExperimentStageDTO[];
  sloCriteria?: SloCriteriaDTO;
}

/**
 * Status snapshot returned by GET /mockserver/chaosExperiment.
 * When no experiment has ever run, status is "none".
 *
 * `halted_by_slo_breach` is a terminal status emitted when an experiment with
 * `sloCriteria` is auto-halted because a live SLO objective was breached; the
 * accompanying `experimentVerdict` is then FAIL.
 */
export interface ExperimentStatusDTO {
  name: string | null;
  status:
    | 'none'
    | 'running'
    | 'completed'
    | 'stopped'
    | 'halted_by_auto_halt'
    | 'halted_by_slo_breach'
    | 'scheduled'
    | 'starting';
  currentStageIndex: number;
  totalStages: number;
  stageElapsedMillis: number;
  stageRemainingMillis: number;
  loopIteration: number;
  totalElapsedMillis: number;
  experiment?: ExperimentDefinitionDTO;
  /**
   * Terminal SLO verdict over the experiment window; present only when the
   * experiment carried `sloCriteria` and a verdict has been produced.
   */
  experimentVerdict?: SloVerdictDTO;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/chaosExperiment`;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body -- keep the status-line message
  }
  throw new Error(message);
}

/** Start a new chaos experiment (PUT). */
export async function startChaosExperiment(
  params: ConnectionParams,
  definition: ExperimentDefinitionDTO,
): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(definition),
  });
  await ensureOk(res);
}

/** Get the current experiment status (GET). */
export async function getChaosExperimentStatus(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<ExperimentStatusDTO> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  return (await res.json()) as ExperimentStatusDTO;
}

/** Stop and clear the current experiment (DELETE). */
export async function stopChaosExperiment(
  params: ConnectionParams,
): Promise<void> {
  const res = await fetch(endpoint(params), { method: 'DELETE' });
  await ensureOk(res);
}

// --- Experiment history ---
//
// GET /mockserver/chaosExperiment/history returns the bounded ring of the most
// recent terminated experiments (newest first). Each entry records the name,
// terminal status, termination time, and — when the experiment carried
// sloCriteria — the terminal SLO verdict.

/** One terminated-experiment record from GET /mockserver/chaosExperiment/history. */
export interface ExperimentHistoryEntryDTO {
  name: string;
  /** Terminal status, e.g. "completed", "stopped", "halted_by_auto_halt". */
  status: string;
  terminatedAtMillis: number;
  /** Terminal SLO verdict; present only when the experiment carried sloCriteria. */
  verdict?: SloVerdictDTO;
}

/** Response envelope for GET /mockserver/chaosExperiment/history. */
export interface ExperimentHistoryDTO {
  count: number;
  history: ExperimentHistoryEntryDTO[];
}

/** Fetch the bounded ring of past experiment runs (GET), newest first. */
export async function getExperimentHistory(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<ExperimentHistoryEntryDTO[]> {
  const res = await fetch(`${endpoint(params)}/history`, { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<ExperimentHistoryDTO>;
  return Array.isArray(body.history) ? body.history : [];
}

// --- ADV3: saved chaos profile library ---
//
// A "profile" is a saved experiment definition (the same shape as
// ExperimentDefinitionDTO) stored under a name on the server so it can be
// re-applied without re-authoring it. Profiles are persisted in the server
// state backend (survive reset; replicate across a cluster).

function profilesEndpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/chaosExperiment/profiles`;
}

/** List the names of all saved chaos profiles (GET). */
export async function listChaosProfiles(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<string[]> {
  const res = await fetch(profilesEndpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as { profiles?: unknown };
  return Array.isArray(body.profiles) ? (body.profiles as string[]) : [];
}

/** Save (or replace) a chaos profile under the given name (PUT). */
export async function saveChaosProfile(
  params: ConnectionParams,
  name: string,
  definition: ExperimentDefinitionDTO,
): Promise<void> {
  const res = await fetch(`${profilesEndpoint(params)}/${encodeURIComponent(name)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(definition),
  });
  await ensureOk(res);
}

/** Apply (start) a saved chaos profile by name (POST). */
export async function applyChaosProfile(
  params: ConnectionParams,
  name: string,
): Promise<void> {
  const res = await fetch(
    `${buildBaseUrl(params)}/mockserver/chaosExperiment/apply/${encodeURIComponent(name)}`,
    { method: 'POST' },
  );
  await ensureOk(res);
}

/** Delete a saved chaos profile by name (DELETE). */
export async function deleteChaosProfile(
  params: ConnectionParams,
  name: string,
): Promise<void> {
  const res = await fetch(`${profilesEndpoint(params)}/${encodeURIComponent(name)}`, {
    method: 'DELETE',
  });
  await ensureOk(res);
}

/** Format elapsed/remaining millis as a compact string (e.g. "1m 05s", "12s"). */
export function formatDuration(millis: number): string {
  const totalSeconds = Math.max(0, Math.round(millis / 1000));
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`;
}
