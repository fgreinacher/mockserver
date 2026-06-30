import TextField from '@mui/material/TextField';
import MenuItem from '@mui/material/MenuItem';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import ToggleButton from '@mui/material/ToggleButton';
import { PROVIDERS, type ProviderName } from '../lib/expectationFromCapture';
import type { IsolationConfig } from '../lib/conversationCodegen';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface Step1Props {
  provider: ProviderName;
  path: string;
  model: string;
  isolateBy?: IsolationConfig;
  onProviderChange: (provider: ProviderName) => void;
  onPathChange: (path: string) => void;
  onModelChange: (model: string) => void;
  onIsolateByChange: (isolation?: IsolationConfig) => void;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ConversationWizardStep1({
  provider,
  path,
  model,
  isolateBy,
  onProviderChange,
  onPathChange,
  onModelChange,
  onIsolateByChange,
}: Step1Props) {
  const isolationEnabled = isolateBy != null;

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, py: 1 }}>
      <TextField
        label="Provider"
        size="small"
        select
        fullWidth
        value={provider}
        onChange={(e) => onProviderChange(e.target.value as ProviderName)}
      >
        {PROVIDERS.map((p) => (
          <MenuItem key={p} value={p}>
            {p}
          </MenuItem>
        ))}
      </TextField>

      <TextField
        label="Path"
        size="small"
        fullWidth
        placeholder="/v1/messages"
        value={path}
        onChange={(e) => onPathChange(e.target.value)}
      />

      <TextField
        label="Model (optional)"
        size="small"
        fullWidth
        placeholder="e.g. claude-sonnet-4-20250514"
        value={model}
        onChange={(e) => onModelChange(e.target.value)}
      />

      {/* Isolation toggle */}
      <Box>
        <Typography variant="subtitle2" sx={{ mb: 1 }}>
          Per-Session Isolation (optional)
        </Typography>
        <ToggleButtonGroup
          value={isolationEnabled ? (isolateBy?.source ?? 'header') : null}
          exclusive
          size="small"
          onChange={(_, value: string | null) => {
            if (value === null) {
              onIsolateByChange(undefined);
            } else {
              onIsolateByChange({
                source: value as IsolationConfig['source'],
                name: isolateBy?.name ?? '',
              });
            }
          }}
          sx={{
            mb: 1,
            '& .MuiToggleButton-root': { py: 0.5, px: 1.5, fontSize: '0.75rem', textTransform: 'none' },
          }}
        >
          <ToggleButton value="header">Header</ToggleButton>
          <ToggleButton value="queryParameter">Query Param</ToggleButton>
          <ToggleButton value="cookie">Cookie</ToggleButton>
        </ToggleButtonGroup>
        {isolationEnabled && (
          <TextField
            label="Isolation key name"
            size="small"
            fullWidth
            placeholder="e.g. x-session-id"
            value={isolateBy?.name ?? ''}
            onChange={(e) =>
              onIsolateByChange({
                source: isolateBy?.source ?? 'header',
                name: e.target.value,
              })
            }
          />
        )}
      </Box>
    </Box>
  );
}
