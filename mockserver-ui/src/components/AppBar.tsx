import MuiAppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Chip from '@mui/material/Chip';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import ListSubheader from '@mui/material/ListSubheader';
import Box from '@mui/material/Box';
import DarkModeIcon from '@mui/icons-material/DarkMode';
import LightModeIcon from '@mui/icons-material/LightMode';
import AccessTimeIcon from '@mui/icons-material/AccessTime';
import SettingsIcon from '@mui/icons-material/Settings';
import TroubleshootIcon from '@mui/icons-material/Troubleshoot';
import RuleIcon from '@mui/icons-material/Rule';
import ClockDialog from './ClockDialog';
import ConfigurationDialog from './ConfigurationDialog';
import ExplainUnmatchedDialog from './ExplainUnmatchedDialog';
import MatcherPlaygroundDialog from './MatcherPlaygroundDialog';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import PauseIcon from '@mui/icons-material/Pause';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import LayersClearIcon from '@mui/icons-material/LayersClear';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import RocketLaunchIcon from '@mui/icons-material/RocketLaunch';
import DashboardIcon from '@mui/icons-material/Dashboard';
import TrafficIcon from '@mui/icons-material/Traffic';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import PostAddIcon from '@mui/icons-material/PostAdd';
import Inventory2Icon from '@mui/icons-material/Inventory2';
import SpeedIcon from '@mui/icons-material/Speed';
import SavingsIcon from '@mui/icons-material/Savings';
import MonitorHeartIcon from '@mui/icons-material/MonitorHeart';
import BoltIcon from '@mui/icons-material/Bolt';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import PlaylistAddCheckIcon from '@mui/icons-material/PlaylistAddCheck';
import VerifiedIcon from '@mui/icons-material/Verified';
import FactCheckIcon from '@mui/icons-material/FactCheck';
import HubOutlinedIcon from '@mui/icons-material/HubOutlined';
import PanToolIcon from '@mui/icons-material/PanTool';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import RpcIcon from '@mui/icons-material/Cable';
import Button from '@mui/material/Button';
import Select from '@mui/material/Select';
import type { SelectChangeEvent } from '@mui/material/Select';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import MenuIcon from '@mui/icons-material/Menu';
import KeyboardIcon from '@mui/icons-material/Keyboard';
import type { ReactNode } from 'react';
// Snackbar/Alert removed — mode errors now use the app-wide notification store
import BuildIcon from '@mui/icons-material/Build';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import DownloadIcon from '@mui/icons-material/Download';
import { useState, useEffect } from 'react';
import { useDashboardStore, type ViewMode } from '../store';
import type { ConnectionStatus } from '../types';
import { useConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchMode,
  setMode as setServerMode,
  MOCK_SERVER_MODES,
  MODE_DESCRIPTIONS,
  type MockServerMode,
} from '../lib/mockServerMode';
import { fetchHttp3Status, type Http3Status } from '../lib/http3Status';
import { humanizeError } from '../lib/errorMessage';
import WsdlImportDialog from './WsdlImportDialog';
import OpenApiImportDialog from './OpenApiImportDialog';
import PactExportDialog from './PactExportDialog';
import OidcDialog from './OidcDialog';
import SamlDialog from './SamlDialog';
import ShortcutsDialog from './ShortcutsDialog';
import AsyncApiDialog from './AsyncApiDialog';
import CrudDialog from './CrudDialog';
import FileStoreDialog from './FileStoreDialog';
import DiffRequestsDialog from './DiffRequestsDialog';
import ConfirmDialog from './ConfirmDialog';
import VpnKeyIcon from '@mui/icons-material/VpnKey';
import HubIcon from '@mui/icons-material/Hub';
import StorageIcon from '@mui/icons-material/Storage';
import FolderOpenIcon from '@mui/icons-material/FolderOpen';
import Divider from '@mui/material/Divider';
import BaselineCompareDialog from './BaselineCompareDialog';

function statusColor(status: ConnectionStatus): 'success' | 'warning' | 'error' | 'default' {
  switch (status) {
    case 'connected':
      return 'success';
    case 'connecting':
      return 'warning';
    case 'error':
      return 'error';
    default:
      return 'default';
  }
}

/**
 * MUI's default outlined-chip colours (`success.main`, `error.main`, …) are
 * dark enough to disappear against the primary-coloured AppBar background in
 * light mode. Override with pale tints of the same hue in light mode only;
 * in dark mode the defaults already contrast against the deep-blue bar so we
 * leave them alone.
 */
function statusChipPaletteSx(themeMode: 'light' | 'dark', status: ConnectionStatus): Record<string, unknown> {
  if (themeMode === 'dark') return {};
  const tints: Record<ConnectionStatus, string> = {
    connected: '#7fffa0',    // pale green
    connecting: '#ffd180',   // pale amber
    error: '#ff8a80',        // pale red
    disconnected: 'rgba(255,255,255,0.85)',
  };
  const tint = tints[status] ?? 'rgba(255,255,255,0.85)';
  return {
    color: tint,
    borderColor: tint,
    '& .MuiChip-label': { color: tint },
  };
}

interface NavTab {
  value: ViewMode;
  label: string;
  ariaLabel: string;
  /**
   * One-line summary of what the tab is for, shown in a bar under the nav.
   * Optional — tabs that omit it (e.g. Get Started, which is self-explanatory)
   * render no description bar.
   */
  description?: string;
  icon: ReactNode;
}

// A labelled group of related views. The nav renders one top-level group button
// per entry; clicking it opens a dropdown Menu of that group's views. Grouping
// the views into a handful of intuitive categories makes the full nav
// discoverable at a glance instead of hiding most views behind a flat "More"
// overflow. Every ViewMode appears in at least one group (asserted by a test);
// a view may deliberately appear in more than one — the Trace view (`sessions`)
// is listed under both Observe and AI — in which case every owning group's
// button highlights when it is active.
interface NavGroup {
  /** Stable id used for keys, aria, and active-group lookup. */
  id: string;
  /** Top-level group button label. */
  label: string;
  ariaLabel: string;
  /** Icon shown on the group button (typically the group's leading view icon). */
  icon: ReactNode;
  tabs: NavTab[];
}

const tabIconSx = { fontSize: '0.875rem', mr: 0.5 } as const;

// Single source of truth for the navigation, organised into intuitive groups.
// The order of groups (and of tabs within each group) is the order shown.
const NAV_GROUPS: NavGroup[] = [
  {
    id: 'mock',
    label: 'Mock',
    ariaLabel: 'Mock views',
    icon: <PostAddIcon sx={tabIconSx} />,
    tabs: [
      { value: 'get-started', label: 'Get Started', ariaLabel: 'Get started view', icon: <RocketLaunchIcon sx={tabIconSx} /> },
      { value: 'composer', label: 'Mocks', ariaLabel: 'Mocks view', description: 'Create, edit, and manage mock expectations — quick mode for common cases, advanced mode for full control.', icon: <PostAddIcon sx={tabIconSx} /> },
      { value: 'grpc', label: 'gRPC', ariaLabel: 'gRPC services view', description: 'Mock gRPC services and inspect gRPC calls.', icon: <RpcIcon sx={tabIconSx} /> },
      { value: 'async', label: 'Async', ariaLabel: 'AsyncAPI broker mock view', description: 'Mock event-driven APIs from an AsyncAPI spec — publish test messages to Kafka, MQTT, and AMQP (RabbitMQ) brokers.', icon: <HubIcon sx={tabIconSx} /> },
    ],
  },
  {
    id: 'observe',
    label: 'Observe',
    ariaLabel: 'Observe views',
    icon: <DashboardIcon sx={tabIconSx} />,
    tabs: [
      { value: 'dashboard', label: 'Dashboard', ariaLabel: 'Dashboard view', description: 'Live view of incoming requests, active expectations, and what matched.', icon: <DashboardIcon sx={tabIconSx} /> },
      { value: 'traffic', label: 'Traffic', ariaLabel: 'Traffic inspector view', description: 'Browse recorded request and response traffic — select an item to open its full details.', icon: <TrafficIcon sx={tabIconSx} /> },
      { value: 'sessions', label: 'Trace', ariaLabel: 'Trace inspector view', description: 'Trace related requests grouped together — including LLM agent runs — to debug multi-step flows end to end.', icon: <AccountTreeIcon sx={tabIconSx} /> },
      { value: 'metrics', label: 'Metrics', ariaLabel: 'Metrics view', description: 'Prometheus metrics plus memory and performance monitoring.', icon: <SpeedIcon sx={tabIconSx} /> },
    ],
  },
  {
    id: 'verify',
    label: 'Verify',
    ariaLabel: 'Verify views',
    icon: <FactCheckIcon sx={tabIconSx} />,
    tabs: [
      { value: 'verification', label: 'Verify', ariaLabel: 'Verification view', description: 'Assert which requests were — or were not — received.', icon: <PlaylistAddCheckIcon sx={tabIconSx} /> },
      { value: 'contract', label: 'Contract', ariaLabel: 'Contract test view', description: 'Validate mocks and traffic against an OpenAPI contract.', icon: <FactCheckIcon sx={tabIconSx} /> },
      { value: 'slo', label: 'SLO', ariaLabel: 'SLO verification view', description: 'Assert service-level objectives — latency percentiles and error rate — against recorded traffic.', icon: <VerifiedIcon sx={tabIconSx} /> },
      { value: 'drift', label: 'Drift', ariaLabel: 'Drift detection view', description: 'Detect when your mocks drift away from the real API they stand in for.', icon: <CompareArrowsIcon sx={tabIconSx} /> },
    ],
  },
  {
    id: 'resilience',
    label: 'Resilience',
    ariaLabel: 'Resilience views',
    icon: <BoltIcon sx={tabIconSx} />,
    tabs: [
      { value: 'chaos', label: 'Chaos', ariaLabel: 'Service chaos view', description: 'Inject latency, errors, and faults to test how your system handles failure.', icon: <BoltIcon sx={tabIconSx} /> },
      { value: 'performance', label: 'Performance', ariaLabel: 'Performance testing view', description: 'Create, run, and monitor load scenarios — drive traffic at a target and watch live throughput and latency.', icon: <TrendingUpIcon sx={tabIconSx} /> },
    ],
  },
  {
    id: 'ai',
    label: 'AI',
    ariaLabel: 'AI views',
    icon: <SavingsIcon sx={tabIconSx} />,
    tabs: [
      { value: 'optimise', label: 'LLM Optimise', ariaLabel: 'LLM Optimise view', description: 'Analyse captured LLM traffic to optimise prompts, inference cost, safety, and speed.', icon: <SavingsIcon sx={tabIconSx} /> },
      { value: 'mcp-health', label: 'MCP Health', ariaLabel: 'MCP server health view', description: 'See which MCP servers your proxied tools call are slow or erroring — the MCP server is often the real bottleneck behind a slow coding assistant.', icon: <MonitorHeartIcon sx={tabIconSx} /> },
      { value: 'sessions', label: 'Trace', ariaLabel: 'Trace inspector view', description: 'Trace related requests grouped together — including LLM agent runs — to debug multi-step flows end to end.', icon: <AccountTreeIcon sx={tabIconSx} /> },
    ],
  },
  {
    id: 'inspect',
    label: 'Inspect',
    ariaLabel: 'Inspect views',
    icon: <PanToolIcon sx={tabIconSx} />,
    tabs: [
      { value: 'breakpoints', label: 'Breakpoints', ariaLabel: 'Breakpoints view', description: 'Pause matching requests or responses mid-flight to inspect and edit them.', icon: <PanToolIcon sx={tabIconSx} /> },
      { value: 'library', label: 'Library', ariaLabel: 'Library of captured content', description: 'Browse and reuse captured requests, responses, and content.', icon: <Inventory2Icon sx={tabIconSx} /> },
      { value: 'cluster', label: 'Cluster', ariaLabel: 'Cluster status view', description: 'Monitor MockServer cluster nodes and shared state.', icon: <HubOutlinedIcon sx={tabIconSx} /> },
    ],
  },
];

// Flat list of every tab, derived from the groups — used for label lookup and
// to build the description map. Keeping it derived guarantees it never drifts
// from the grouped source of truth.
const NAV_TABS: NavTab[] = NAV_GROUPS.flatMap((g) => g.tabs);

// Compile-time exhaustiveness guard. This `Record<ViewMode, string>` must name
// every ViewMode at least once — TypeScript errors if a value is added to the
// ViewMode union without an entry here. The build of NAV_TABS above guarantees
// each of these values is grouped, so the missing-key error effectively means
// "a new view was added without being placed in a NAV_GROUPS group". (A runtime
// assertion below also proves every listed view is actually rendered.) The map
// is used only for this existence check; for a view that appears in more than
// one group the reduce keeps the last group id, so do NOT use it to decide
// "the owning group" — use `groupsForView` (membership), which returns all of
// them.
const NAV_VIEW_GROUP_ID: Record<ViewMode, string> = NAV_GROUPS.reduce<Record<string, string>>(
  (acc, group) => {
    for (const tab of group.tabs) acc[tab.value] = group.id;
    return acc;
  },
  {},
) as Record<ViewMode, string>;
// Fail fast at module load if a ViewMode is missing a group (defence in depth
// behind the type — also catches a hand-edited NAV_GROUPS that drops a value).
{
  const ALL_VIEW_MODES: Record<ViewMode, true> = {
    'get-started': true, dashboard: true, traffic: true, sessions: true,
    composer: true, library: true, chaos: true, performance: true,
    metrics: true, drift: true, verification: true, slo: true, async: true,
    grpc: true, breakpoints: true, contract: true, cluster: true, optimise: true,
    'mcp-health': true,
  };
  for (const v of Object.keys(ALL_VIEW_MODES) as ViewMode[]) {
    if (!(v in NAV_VIEW_GROUP_ID)) {
      throw new Error(`Navigation misconfiguration: ViewMode "${v}" is not in any NAV_GROUPS group`);
    }
  }
}

// The groups a given view belongs to, for active-group highlighting. A view may
// live in more than one group (e.g. Trace under both Observe and AI), so every
// owning group is returned and each highlights when the view is active.
function groupsForView(view: ViewMode): NavGroup[] {
  return NAV_GROUPS.filter((g) => g.tabs.some((t) => t.value === view));
}

// Lookup of the active view's one-line description, for the bar under the nav.
// Exported so App can render it without duplicating the per-tab copy.
export const NAV_TAB_DESCRIPTIONS: Partial<Record<ViewMode, string>> = Object.fromEntries(
  NAV_TABS
    .filter((t): t is NavTab & { description: string } => Boolean(t.description))
    .map((t) => [t.value, t.description]),
);

interface AppBarProps {
  onClearServer: () => Promise<void>;
  onClearLogs: () => Promise<void>;
  onClearExpectations: () => Promise<void>;
}

export default function AppBar({ onClearServer, onClearLogs, onClearExpectations }: AppBarProps) {
  const connectionStatus = useDashboardStore((s) => s.connectionStatus);
  const themeMode = useDashboardStore((s) => s.themeMode);
  const toggleTheme = useDashboardStore((s) => s.toggleThemeMode);
  const autoScroll = useDashboardStore((s) => s.autoScroll);
  const toggleAutoScroll = useDashboardStore((s) => s.toggleAutoScroll);
  const view = useDashboardStore((s) => s.view);
  const setView = useDashboardStore((s) => s.setView);
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);
  const theme = useTheme();
  // Below this width the grouped group-button bar would crowd the toolbar, so
  // collapse the whole nav into a single "hamburger" Menu (with grouped
  // sections) instead.
  const compactNav = useMediaQuery(theme.breakpoints.down('lg'));
  const [navAnchorEl, setNavAnchorEl] = useState<null | HTMLElement>(null);
  // Wide-screen grouped nav: the group whose dropdown is currently open (by id),
  // and the element it anchors to. Only one group menu is open at a time.
  const [openGroupId, setOpenGroupId] = useState<string | null>(null);
  const [groupMenuAnchorEl, setGroupMenuAnchorEl] = useState<null | HTMLElement>(null);

  // The groups that own the active view — each of their group buttons is
  // highlighted (a view may belong to more than one group, e.g. Trace).
  const activeGroupIds = new Set(groupsForView(view).map((g) => g.id));

  const connectionParams = useConnectionParams();
  const [mode, setModeState] = useState<MockServerMode | null>(null);
  const [modeMenuOpen, setModeMenuOpen] = useState(false);
  const [modeTooltipOpen, setModeTooltipOpen] = useState(false);
  const [toolsAnchorEl, setToolsAnchorEl] = useState<null | HTMLElement>(null);
  const [clockOpen, setClockOpen] = useState(false);
  const [configOpen, setConfigOpen] = useState(false);
  const [explainOpen, setExplainOpen] = useState(false);
  const [playgroundOpen, setPlaygroundOpen] = useState(false);
  const [oidcOpen, setOidcOpen] = useState(false);
  const [samlOpen, setSamlOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const [asyncApiOpen, setAsyncApiOpen] = useState(false);
  const [wsdlOpen, setWsdlOpen] = useState(false);
  const [openApiOpen, setOpenApiOpen] = useState(false);
  const [pactOpen, setPactOpen] = useState(false);
  const [crudOpen, setCrudOpen] = useState(false);
  const [fileStoreOpen, setFileStoreOpen] = useState(false);
  const [diffOpen, setDiffOpen] = useState(false);
  const [baselineOpen, setBaselineOpen] = useState(false);
  // Mode errors are now surfaced through the app-wide notification store.
  const [http3Status, setHttp3Status] = useState<Http3Status | null>(null);
  const setNotification = useDashboardStore((s) => s.setNotification);
  // Confirmation for destructive actions (reset / bulk clear). Holds the pending action.
  const [confirm, setConfirm] = useState<{ title: string; message: string; confirmLabel: string; onConfirm: () => void } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetchMode(connectionParams, controller.signal)
      .then((r) => {
        if (!controller.signal.aborted) setModeState(r.mode);
      })
      .catch(() => {
        /* mode endpoint unavailable (older server) — hide the control */
      });
    return () => controller.abort();
  }, [connectionParams]);

  useEffect(() => {
    const controller = new AbortController();
    const poll = () => {
      void fetchHttp3Status(connectionParams, controller.signal)
        .then((status) => {
          if (!controller.signal.aborted) setHttp3Status(status);
        })
        .catch(() => {
          /* endpoint unavailable (older server or H3 not compiled in) */
        });
    };
    poll();
    // poll every 5 seconds so active connection count stays reasonably fresh
    const interval = setInterval(poll, 5000);
    return () => {
      controller.abort();
      clearInterval(interval);
    };
  }, [connectionParams]);

  const handleModeChange = (event: SelectChangeEvent) => {
    const next = event.target.value as MockServerMode;
    const previous = mode;
    setModeState(next);
    void setServerMode(connectionParams, next)
      .then((r) => {
        setModeState(r.mode);
        setNotification({ message: `Operating mode set to ${r.mode}`, severity: 'success' });
      })
      .catch((e) => {
        setModeState(previous); // revert on failure
        setNotification({ message: humanizeError(e).message, severity: 'error' });
      });
  };

  // Open a group's dropdown menu, anchored to its group button.
  const handleOpenGroup = (groupId: string, anchor: HTMLElement) => {
    setOpenGroupId(groupId);
    setGroupMenuAnchorEl(anchor);
  };
  const handleCloseGroupMenu = () => {
    setOpenGroupId(null);
    setGroupMenuAnchorEl(null);
  };
  // Select a view (preserves the store setView path: view persistence + URL hash)
  // and close any open menu.
  const handleSelectView = (value: ViewMode) => {
    setView(value);
    handleCloseGroupMenu();
    setNavAnchorEl(null);
  };

  // Base styling for a top-level group button. Light mode forces white text +
  // translucent border so it reads against the primary-coloured AppBar; dark
  // mode keeps MUI defaults which already contrast against the bar. The active
  // group (the one owning the current view) gets a theme-appropriate highlight:
  // a pale-white tint in light mode (matching the old ToggleButton `.Mui-selected`
  // styling) and the theme's translucent action-selected overlay in dark mode,
  // so selected nav reads consistently with other selected controls in each theme.
  const groupButtonSx = (active: boolean) => {
    const activeBg = themeMode === 'light'
      ? 'rgba(255, 255, 255, 0.18)'
      : theme.palette.action.selected;
    return {
      ml: 0.5,
      py: 0.25,
      px: 1,
      fontSize: '0.7rem',
      textTransform: 'none' as const,
      lineHeight: 1.4,
      whiteSpace: 'nowrap' as const,
      flexShrink: 0,
      color: 'inherit',
      ...(themeMode === 'light' ? { borderColor: 'rgba(255, 255, 255, 0.3)' } : {}),
      '&:hover': {
        backgroundColor: themeMode === 'light'
          ? 'rgba(255, 255, 255, 0.08)'
          : theme.palette.action.hover,
      },
      ...(active ? { backgroundColor: activeBg } : {}),
    };
  };

  return (
    <MuiAppBar position="static" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
      <Toolbar variant="dense" sx={{ gap: 1, minHeight: 36, flexWrap: 'wrap', rowGap: 0.5, py: 0.5 }}>
        <Typography variant="h6" sx={{ fontWeight: 700, fontSize: '0.9rem' }}>
          MockServer
        </Typography>
        <Chip
          label={connectionStatus}
          size="small"
          color={statusColor(connectionStatus)}
          variant="outlined"
          sx={{
            textTransform: 'capitalize',
            ...statusChipPaletteSx(themeMode, connectionStatus),
          }}
        />
        {http3Status?.enabled && (
          <Tooltip title={`HTTP/3 (QUIC) on UDP port ${http3Status.port} -- ${http3Status.activeConnections} active connection${http3Status.activeConnections === 1 ? '' : 's'}`}>
            <Chip
              label={`H3 :${http3Status.port} (${http3Status.activeConnections})`}
              size="small"
              color="info"
              variant="outlined"
              sx={{
                fontSize: '0.7rem',
                ...(themeMode === 'light' ? {
                  color: 'rgba(255,255,255,0.9)',
                  borderColor: 'rgba(255,255,255,0.4)',
                  '& .MuiChip-label': { color: 'rgba(255,255,255,0.9)' },
                } : {}),
              }}
            />
          </Tooltip>
        )}
        {compactNav ? (
          <>
            <Tooltip title="Navigate">
              <IconButton
                size="small"
                color="inherit"
                aria-label="Open navigation menu"
                onClick={(e) => setNavAnchorEl(e.currentTarget)}
                sx={{ ml: 1 }}
              >
                <MenuIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Typography variant="subtitle2" sx={{ fontWeight: 600, fontSize: '0.8rem', whiteSpace: 'nowrap' }}>
              {NAV_TABS.find((t) => t.value === view)?.label ?? ''}
            </Typography>
            {/* Single hamburger Menu containing every view, organised into
                labelled group sections so the full nav stays discoverable on
                narrow screens. */}
            <Menu
              anchorEl={navAnchorEl}
              open={Boolean(navAnchorEl)}
              onClose={() => setNavAnchorEl(null)}
            >
              {NAV_GROUPS.flatMap((group, groupIndex) => [
                groupIndex > 0 ? <Divider key={`${group.id}-divider`} /> : null,
                <ListSubheader key={`${group.id}-heading`} disableSticky sx={{ lineHeight: '2em', bgcolor: 'transparent' }}>
                  {group.label}
                </ListSubheader>,
                ...group.tabs.map((tab) => (
                  <MenuItem
                    key={`${group.id}-${tab.value}`}
                    selected={view === tab.value}
                    aria-label={tab.ariaLabel}
                    onClick={() => handleSelectView(tab.value)}
                  >
                    <ListItemIcon>{tab.icon}</ListItemIcon>
                    <ListItemText>{tab.label}</ListItemText>
                  </MenuItem>
                )),
              ])}
            </Menu>
          </>
        ) : (
          <Box
            sx={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
          >
            {/* One top-level button per group; each opens a dropdown of its
                views. The active view's group button is highlighted so the
                current location is always indicated. */}
            {NAV_GROUPS.map((group) => {
              const isActiveGroup = activeGroupIds.has(group.id);
              const isOpen = openGroupId === group.id;
              return (
                <Button
                  key={group.id}
                  size="small"
                  color="inherit"
                  aria-label={group.ariaLabel}
                  aria-haspopup="menu"
                  aria-expanded={isOpen}
                  endIcon={<ExpandMoreIcon sx={{ fontSize: '0.875rem' }} />}
                  onClick={(e) => handleOpenGroup(group.id, e.currentTarget)}
                  sx={groupButtonSx(isActiveGroup)}
                >
                  {group.icon}
                  {group.label}
                </Button>
              );
            })}
            {/* Dropdown for whichever group button was clicked. Anchored to that
                button; one menu reused across all groups. */}
            <Menu
              anchorEl={groupMenuAnchorEl}
              open={Boolean(groupMenuAnchorEl) && openGroupId !== null}
              onClose={handleCloseGroupMenu}
            >
              {(NAV_GROUPS.find((g) => g.id === openGroupId)?.tabs ?? []).map((tab) => (
                <MenuItem
                  key={tab.value}
                  selected={view === tab.value}
                  aria-label={tab.ariaLabel}
                  onClick={() => handleSelectView(tab.value)}
                >
                  <ListItemIcon>{tab.icon}</ListItemIcon>
                  <ListItemText>{tab.label}</ListItemText>
                </MenuItem>
              ))}
            </Menu>
          </Box>
        )}
        <Box sx={{ flex: compactNav ? 1 : '0 0 auto' }} />
        <Tooltip title="Keyboard shortcuts">
          <IconButton size="small" color="inherit" onClick={() => setShortcutsOpen(true)} aria-label="Keyboard shortcuts">
            <KeyboardIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Server clock (freeze / advance time)">
          <IconButton size="small" color="inherit" onClick={() => setClockOpen(true)} aria-label="Server clock">
            <AccessTimeIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Explain unmatched requests">
          <IconButton size="small" color="inherit" onClick={() => setExplainOpen(true)} aria-label="Explain unmatched requests">
            <TroubleshootIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Matcher test playground — try a request against a candidate expectation">
          <IconButton size="small" color="inherit" onClick={() => setPlaygroundOpen(true)} aria-label="Matcher test playground">
            <RuleIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Server configuration">
          <IconButton size="small" color="inherit" onClick={() => setConfigOpen(true)} aria-label="Server configuration">
            <SettingsIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title={autoScroll ? 'Pause auto-scroll' : 'Resume auto-scroll'}>
          <IconButton size="small" color="inherit" onClick={toggleAutoScroll} aria-label={autoScroll ? 'Pause auto-scroll' : 'Resume auto-scroll'}>
            {autoScroll ? <PauseIcon fontSize="small" /> : <PlayArrowIcon fontSize="small" />}
          </IconButton>
        </Tooltip>
        <Tooltip title={`Switch to ${themeMode === 'dark' ? 'light' : 'dark'} mode`}>
          <IconButton size="small" color="inherit" onClick={toggleTheme} aria-label={`Switch to ${themeMode === 'dark' ? 'light' : 'dark'} mode`}>
            {themeMode === 'dark' ? <LightModeIcon fontSize="small" /> : <DarkModeIcon fontSize="small" />}
          </IconButton>
        </Tooltip>
        {mode !== null && (
          <Tooltip
            title={MODE_DESCRIPTIONS[mode]}
            open={modeTooltipOpen && !modeMenuOpen}
            onOpen={() => setModeTooltipOpen(true)}
            onClose={() => setModeTooltipOpen(false)}
          >
            <Select
              value={mode}
              onChange={handleModeChange}
              open={modeMenuOpen}
              onOpen={() => setModeMenuOpen(true)}
              onClose={() => setModeMenuOpen(false)}
              size="small"
              aria-label="Operating mode"
              sx={{
                color: 'inherit',
                fontSize: '0.7rem',
                height: 28,
                '.MuiOutlinedInput-notchedOutline': { borderColor: 'rgba(255, 255, 255, 0.3)' },
                '.MuiSvgIcon-root': { color: 'inherit' },
              }}
            >
              {MOCK_SERVER_MODES.map((m) => (
                <MenuItem key={m} value={m} sx={{ fontSize: '0.8rem' }}>
                  {m}
                </MenuItem>
              ))}
            </Select>
          </Tooltip>
        )}
        <Tooltip title="Import / export">
          <IconButton
            size="small"
            color="inherit"
            aria-label="Import / export tools"
            onClick={(e) => setToolsAnchorEl(e.currentTarget)}
          >
            <BuildIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Clear">
          <IconButton
            size="small"
            color="inherit"
            aria-label="Clear logs, expectations, or reset server"
            onClick={(e) => setAnchorEl(e.currentTarget)}
          >
            <DeleteSweepIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Menu
          anchorEl={anchorEl}
          open={Boolean(anchorEl)}
          onClose={() => setAnchorEl(null)}
        >
          <MenuItem
            onClick={() => {
              void onClearLogs();
              setAnchorEl(null);
            }}
          >
            <ListItemIcon><LayersClearIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Clear server logs</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setAnchorEl(null);
              setConfirm({
                title: 'Clear all expectations?',
                message: 'This removes every registered expectation from the server. Recorded requests and logs are kept. This cannot be undone.',
                confirmLabel: 'Clear expectations',
                onConfirm: () => { void onClearExpectations(); },
              });
            }}
          >
            <ListItemIcon><LayersClearIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Clear server expectations</ListItemText>
          </MenuItem>
          <Divider />
          <MenuItem
            onClick={() => {
              setAnchorEl(null);
              setConfirm({
                title: 'Reset the entire server?',
                message: 'This clears ALL expectations, recorded requests and logs, and resets server state. This cannot be undone.',
                confirmLabel: 'Reset server',
                onConfirm: () => { void onClearServer(); },
              });
            }}
            sx={{ color: 'error.main' }}
          >
            <ListItemIcon><RestartAltIcon fontSize="small" color="error" /></ListItemIcon>
            <ListItemText>Reset server (all)</ListItemText>
          </MenuItem>
        </Menu>
        <Menu
          anchorEl={toolsAnchorEl}
          open={Boolean(toolsAnchorEl)}
          onClose={() => setToolsAnchorEl(null)}
        >
          <MenuItem
            onClick={() => {
              setOpenApiOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><UploadFileIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Import OpenAPI…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setWsdlOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><UploadFileIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Import WSDL…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setPactOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><DownloadIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Pact contract (export / verify)…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setOidcOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><VpnKeyIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Mock OIDC provider…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setSamlOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><VpnKeyIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Mock SAML provider…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setAsyncApiOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><HubIcon fontSize="small" /></ListItemIcon>
            <ListItemText>AsyncAPI broker mock…</ListItemText>
          </MenuItem>
          <Divider />
          <MenuItem
            onClick={() => {
              setCrudOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><StorageIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Register CRUD resource…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setFileStoreOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><FolderOpenIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Mock file store…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setDiffOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><CompareArrowsIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Diff two requests…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setBaselineOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><CompareArrowsIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Compare against baseline…</ListItemText>
          </MenuItem>
        </Menu>
        <OpenApiImportDialog
          open={openApiOpen}
          onClose={() => setOpenApiOpen(false)}
          connectionParams={connectionParams}
        />
        <WsdlImportDialog
          open={wsdlOpen}
          onClose={() => setWsdlOpen(false)}
          connectionParams={connectionParams}
        />
        <PactExportDialog
          open={pactOpen}
          onClose={() => setPactOpen(false)}
          connectionParams={connectionParams}
        />
        {/* Mode errors are surfaced through the app-wide notification store */}
      </Toolbar>
      <ClockDialog open={clockOpen} onClose={() => setClockOpen(false)} connectionParams={connectionParams} />
      <ConfigurationDialog open={configOpen} onClose={() => setConfigOpen(false)} connectionParams={connectionParams} />
      <ExplainUnmatchedDialog open={explainOpen} onClose={() => setExplainOpen(false)} connectionParams={connectionParams} />
      <MatcherPlaygroundDialog open={playgroundOpen} onClose={() => setPlaygroundOpen(false)} />
      <OidcDialog open={oidcOpen} onClose={() => setOidcOpen(false)} connectionParams={connectionParams} />
      <SamlDialog open={samlOpen} onClose={() => setSamlOpen(false)} connectionParams={connectionParams} />
      <ShortcutsDialog open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} />
      <AsyncApiDialog open={asyncApiOpen} onClose={() => setAsyncApiOpen(false)} connectionParams={connectionParams} />
      <CrudDialog open={crudOpen} onClose={() => setCrudOpen(false)} connectionParams={connectionParams} />
      <FileStoreDialog open={fileStoreOpen} onClose={() => setFileStoreOpen(false)} connectionParams={connectionParams} />
      <DiffRequestsDialog open={diffOpen} onClose={() => setDiffOpen(false)} connectionParams={connectionParams} />
      <BaselineCompareDialog open={baselineOpen} onClose={() => setBaselineOpen(false)} connectionParams={connectionParams} />
      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.title ?? ''}
        message={confirm?.message ?? ''}
        confirmLabel={confirm?.confirmLabel ?? 'Confirm'}
        onConfirm={() => confirm?.onConfirm()}
        onClose={() => setConfirm(null)}
      />
    </MuiAppBar>
  );
}
