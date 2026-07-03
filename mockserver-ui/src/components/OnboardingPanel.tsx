import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import CardActions from '@mui/material/CardActions';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Link from '@mui/material/Link';
import Alert from '@mui/material/Alert';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import PauseCircleIcon from '@mui/icons-material/PauseCircle';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import SavingsIcon from '@mui/icons-material/Savings';
import BoltIcon from '@mui/icons-material/Bolt';
import { useState } from 'react';
import OpenApiImportDialog from './OpenApiImportDialog';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { useDashboardStore, type ViewMode } from '../store';

interface OnboardingPanelProps {
  connectionParams: ConnectionParams;
}

interface ActionCardProps {
  icon: React.ReactNode;
  title: string;
  description: string;
  actionLabel: string;
  onAction: () => void;
  /** Optional second CTA rendered as an outlined button alongside the primary one. */
  secondaryActionLabel?: string;
  onSecondaryAction?: () => void;
}

function ActionCard({
  icon,
  title,
  description,
  actionLabel,
  onAction,
  secondaryActionLabel,
  onSecondaryAction,
}: ActionCardProps) {
  return (
    <Card
      variant="outlined"
      sx={(theme) => ({
        // Wide enough that the longest title ("Performance Testing") stays on one
        // line; whole tiles wrap to the next row rather than being squeezed narrow
        // enough to wrap the heading. The maxWidth stops a lone wrapped tile from
        // stretching across the full row.
        flex: '1 1 200px',
        minWidth: 200,
        maxWidth: 360,
        display: 'flex',
        flexDirection: 'column',
        transition: theme.transitions.create(['transform', 'box-shadow', 'border-color'], {
          duration: theme.transitions.duration.shorter,
        }),
        '&:hover': {
          transform: 'translateY(-3px)',
          boxShadow: 4,
          borderColor: 'primary.main',
        },
      })}
    >
      <CardContent sx={{ flex: 1, p: 1.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 0.75 }}>
          {icon}
          <Typography variant="subtitle2" sx={{ fontWeight: 600, lineHeight: 1.3 }}>
            {title}
          </Typography>
        </Box>
        <Typography variant="caption" component="p" color="text.secondary" sx={{ lineHeight: 1.45 }}>
          {description}
        </Typography>
      </CardContent>
      <CardActions sx={{ px: 1.5, pb: 1.5, pt: 0, gap: 0.75 }}>
        <Button size="small" variant="contained" onClick={onAction}>
          {actionLabel}
        </Button>
        {secondaryActionLabel && onSecondaryAction && (
          <Button size="small" variant="outlined" onClick={onSecondaryAction}>
            {secondaryActionLabel}
          </Button>
        )}
      </CardActions>
    </Card>
  );
}

const OTHER_TABS: { view: ViewMode; label: string; description: string }[] = [
  { view: 'dashboard', label: 'Dashboard', description: 'active mocks & live event log' },
  { view: 'library', label: 'Library', description: 'import / export, Postman, WSDL, HAR' },
  { view: 'verification', label: 'Verification', description: 'assert which requests were received' },
  { view: 'drift', label: 'Drift', description: 'spot when an upstream API changes' },
  { view: 'async', label: 'Async', description: 'Kafka / MQTT / AMQP broker mocking' },
  { view: 'metrics', label: 'Metrics', description: 'Prometheus stats' },
];

export default function OnboardingPanel({ connectionParams }: OnboardingPanelProps) {
  const [openApiOpen, setOpenApiOpen] = useState(false);
  const setView = useDashboardStore((s) => s.setView);
  const activeMockCount = useDashboardStore((s) => s.activeExpectations.length);
  const recordedRequestCount = useDashboardStore((s) => s.recordedRequests.length);
  const hasExistingState = activeMockCount > 0 || recordedRequestCount > 0;

  const go = (view: ViewMode) => () => setView(view);

  // The six key features, rendered as tiles on wide screens and as a compact
  // bulleted list on narrow ones (mobile / the IDE-embedded dashboard).
  // Ordered to lead with the most common first task (Mocking) and otherwise
  // follow the AppBar tab order (Traffic, Breakpoints, Chaos, Performance,
  // LLM Optimise) so the onboarding and the navigation tell a consistent story.
  const primaryActions: ActionCardProps[] = [
    {
      icon: <UploadFileIcon color="primary" />,
      title: 'Mocking',
      description:
        'Build mock responses by hand, or import an OpenAPI / Swagger spec, Postman collection, WSDL, or HAR file to generate stubs automatically.',
      actionLabel: 'Create Mock',
      onAction: go('composer'),
      secondaryActionLabel: 'Import OpenAPI',
      onSecondaryAction: () => setOpenApiOpen(true),
    },
    {
      icon: <SwapHorizIcon color="primary" />,
      title: 'Debugging Proxy',
      description:
        'Sit MockServer between your app and a real API to record, inspect, and replay live traffic — and validate or rewrite it on the way through.',
      actionLabel: 'View Traffic',
      onAction: go('traffic'),
    },
    {
      icon: <PauseCircleIcon color="primary" />,
      title: 'Breakpoints',
      description:
        'Pause requests and responses mid-flight — proxied, mocked, or unmatched — then inspect, edit, continue, or abort them.',
      actionLabel: 'Open Breakpoints',
      onAction: go('breakpoints'),
    },
    {
      icon: <BoltIcon color="primary" />,
      title: 'Chaos Testing',
      description:
        'Inject latency, errors, and dropped connections to test how your system copes when the APIs it depends on misbehave.',
      actionLabel: 'Open Chaos',
      onAction: go('chaos'),
    },
    {
      icon: <TrendingUpIcon color="primary" />,
      title: 'Performance Testing',
      description:
        'Inject load against any target — drive traffic at a chosen arrival rate, ramp it up and down, and watch live throughput and latency.',
      actionLabel: 'Open Performance',
      onAction: go('performance'),
    },
    {
      icon: <SavingsIcon color="primary" />,
      title: 'LLM Optimise',
      description:
        'Capture LLM traffic through the proxy, then analyse it to optimise prompts, inference cost, safety, and speed.',
      actionLabel: 'Open LLM Optimise',
      onAction: go('optimise'),
    },
  ];

  return (
    <Box
      sx={{
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        p: 4,
        overflow: 'auto',
      }}
    >
      <Typography variant="h5" gutterBottom sx={{ fontWeight: 700 }}>
        Welcome to MockServer
      </Typography>
      <Typography
        variant="body1"
        color="text.secondary"
        sx={{ mb: 4, maxWidth: 640, textAlign: 'center' }}
      >
        Mock, proxy, and debug HTTP, HTTPS, and other APIs. These are the key
        things you can do — open any one below, or use the tabs above for the
        full feature set.
      </Typography>

      {/* Returning-user awareness: when this server already holds state, offer a
          jump straight to the Dashboard instead of the empty first-run flow. We
          surface it (never auto-redirect) so the user stays in control of the view. */}
      {hasExistingState && (
        <Alert
          severity="info"
          sx={{ mb: 3, maxWidth: 760, width: '100%' }}
          action={
            <Button color="inherit" size="small" onClick={go('dashboard')}>
              Open Dashboard
            </Button>
          }
        >
          This server has {activeMockCount} active mock{activeMockCount === 1 ? '' : 's'} and{' '}
          {recordedRequestCount} recorded request{recordedRequestCount === 1 ? '' : 's'}.
        </Alert>
      )}

      {/* Responsive switch keyed off the CONTAINER width, not the viewport. The
          dashboard is embedded in a narrow IDE tool window (JCEF) whose CSS
          viewport stays wide regardless of the visible panel size, so a viewport
          media query never fires there. A container query reacts to the actual
          panel width, so this behaves the same in a browser and in the IDE.
          Tiles are the default and only collapse to the bulleted list when the
          panel is narrow — so an engine without container-query support (older
          browsers, jsdom in tests) falls back to tiles rather than nothing. */}
      <Box sx={{ containerType: 'inline-size', width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        {/* Wide panels: feature tiles side by side, wrapping to a second row when
            six no longer fit at their one-line-title minimum width rather than
            squeezing all six abreast (which wrapped the headings). */}
        <Box
          sx={{
            display: 'flex',
            '@container (max-width: 899.98px)': { display: 'none' },
            flexWrap: 'wrap',
            gap: 1.5,
            alignItems: 'stretch',
            justifyContent: 'center',
            width: '100%',
            maxWidth: 1320,
          }}
        >
          {primaryActions.map((action) => (
            <ActionCard key={action.title} {...action} />
          ))}
        </Box>

        {/* Narrow panels (mobile / IDE-embedded): a compact bulleted list, since
            six tiles side by side become unreadably squished. */}
        <Box
          component="ul"
          sx={{
            display: 'none',
            '@container (max-width: 899.98px)': { display: 'block' },
            m: 0,
            pl: 3,
            width: '100%',
            maxWidth: 760,
          }}
        >
          {primaryActions.map((action) => (
            <Box component="li" key={action.title} sx={{ mb: 1 }}>
              <Link
                component="button"
                type="button"
                onClick={action.onAction}
                sx={{ verticalAlign: 'baseline', fontWeight: 600 }}
              >
                {action.title}
              </Link>
              <Typography component="span" variant="body2" color="text.secondary">
                {' '}— {action.description}
              </Typography>
            </Box>
          ))}
        </Box>
      </Box>

      <Box sx={{ mt: 4, maxWidth: 760, width: '100%' }}>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          More in the tabs above (or see{' '}
          <Link
            href="https://www.mock-server.com/mock_server/mockserver_ui.html"
            target="_blank"
            rel="noopener"
          >
            UI docs
          </Link>
          ):
        </Typography>
        <Box component="ul" sx={{ m: 0, pl: 3 }}>
          {OTHER_TABS.map((tab) => (
            <Box component="li" key={tab.view} sx={{ mb: 0.5 }}>
              <Link component="button" type="button" onClick={go(tab.view)} sx={{ verticalAlign: 'baseline' }}>
                {tab.label}
              </Link>
              <Typography component="span" variant="body2" color="text.secondary">
                {' '}— {tab.description}
              </Typography>
            </Box>
          ))}
        </Box>
      </Box>

      <OpenApiImportDialog
        open={openApiOpen}
        onClose={() => setOpenApiOpen(false)}
        connectionParams={connectionParams}
      />
    </Box>
  );
}
