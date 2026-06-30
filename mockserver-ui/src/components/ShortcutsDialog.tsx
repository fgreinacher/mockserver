import Dialog from '@mui/material/Dialog';
import DialogTitle from '@mui/material/DialogTitle';
import DialogContent from '@mui/material/DialogContent';
import DialogActions from '@mui/material/DialogActions';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import { monospaceFontFamily } from '../theme';

/**
 * Lists the global keyboard shortcuts. The list is the single source of truth for the
 * shortcut help and must stay in sync with `src/hooks/useKeyboardShortcuts.ts`:
 *   - Cmd/Ctrl+K  -> focus the log search field
 *   - Cmd/Ctrl+L  -> open the "clear server logs" confirmation
 *   - Esc         -> toggle the request-filter panel
 */
const SHORTCUTS: { keys: string; action: string }[] = [
  { keys: '⌘K  /  Ctrl+K', action: 'Focus the log search field' },
  { keys: '⌘L  /  Ctrl+L', action: 'Clear server logs (asks for confirmation)' },
  { keys: 'Esc', action: 'Show / hide the request filter panel' },
];

export default function ShortcutsDialog({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth fullScreen={fullScreen} aria-labelledby="shortcuts-dialog-title">
      <DialogTitle id="shortcuts-dialog-title">Keyboard Shortcuts</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Shortcuts are ignored while you are typing in a text field.
        </Typography>
        <Box component="dl" sx={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', columnGap: 2, rowGap: 1, m: 0 }}>
          {SHORTCUTS.map((s) => (
            <Box key={s.keys} sx={{ display: 'contents' }}>
              <Typography
                component="dt"
                variant="body2"
                sx={{ fontFamily: monospaceFontFamily, fontWeight: 600, whiteSpace: 'nowrap' }}
              >
                {s.keys}
              </Typography>
              <Typography component="dd" variant="body2" color="text.secondary" sx={{ m: 0 }}>
                {s.action}
              </Typography>
            </Box>
          ))}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
