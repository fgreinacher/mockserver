import type { RefObject } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import InputAdornment from '@mui/material/InputAdornment';
import Tooltip from '@mui/material/Tooltip';
import SearchIcon from '@mui/icons-material/Search';
import HelpOutlinedIcon from '@mui/icons-material/HelpOutlined';
import type { SxProps, Theme } from '@mui/material/styles';

// Hint shown in the search box. Surfaces the otherwise-hidden operators that
// lib/searchMatcher.ts already supports so users can discover them in passing;
// the full reference lives in the adjacent help tooltip (SEARCH_HELP).
export const SEARCH_PLACEHOLDER = 'Search — try status:>=400, method:POST, path:/api/*, /regex/';

// Operator reference for the search box. Kept in sync with the operators
// implemented in lib/searchMatcher.ts: KNOWN_FIELDS = status, method, path,
// plus a /regex/ free-text form. Field operators are ANDed with free text.
export const SEARCH_HELP = (
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.25, py: 0.25, maxWidth: 320 }}>
    <Typography variant="caption" sx={{ fontWeight: 600 }}>Search operators</Typography>
    <Typography variant="caption">
      status:&gt;=400 — response status (comparators &gt;= &lt;= &gt; &lt; =)
    </Typography>
    <Typography variant="caption">
      method:POST — request method (case-insensitive)
    </Typography>
    <Typography variant="caption">
      path:/api/* — request path glob (* = any characters)
    </Typography>
    <Typography variant="caption">
      /regex/ — free-text regular expression (optional flags, defaults to case-insensitive)
    </Typography>
    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
      Plain text matches anywhere; operators combine with AND.
    </Typography>
  </Box>
);

interface OperatorSearchFieldProps {
  /** Stable DOM id for the input (labels the field for tests / a11y tooling). */
  id: string;
  value: string;
  onChange: (value: string) => void;
  inputRef?: RefObject<HTMLInputElement | null>;
  /** Maximum width of the field. Defaults to 240 (the dashboard-panel width). */
  maxWidth?: number;
  /** Extra sx merged over the base styles (e.g. a caller-specific height). */
  sx?: SxProps<Theme>;
}

/**
 * Operator-aware search field shared by the dashboard panels and the Traffic
 * inspector: a compact search input with the shared operator placeholder and an
 * inline help tooltip. Keeping a single component means both surfaces discover
 * the same operators (status:/method:/path:/`/regex/`) and stay in sync.
 */
export default function OperatorSearchField({
  id,
  value,
  onChange,
  inputRef,
  maxWidth = 240,
  sx,
}: OperatorSearchFieldProps) {
  return (
    <TextField
      id={id}
      size="small"
      placeholder={SEARCH_PLACEHOLDER}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      inputRef={inputRef}
      slotProps={{
        htmlInput: { 'aria-label': 'Search' },
        input: {
          startAdornment: (
            <InputAdornment position="start">
              <SearchIcon fontSize="small" />
            </InputAdornment>
          ),
          endAdornment: (
            <InputAdornment position="end">
              <Tooltip title={SEARCH_HELP} arrow>
                <HelpOutlinedIcon
                  fontSize="small"
                  role="img"
                  aria-label="Search operator help"
                  tabIndex={0}
                  sx={{ color: 'text.secondary', cursor: 'help' }}
                />
              </Tooltip>
            </InputAdornment>
          ),
        },
      }}
      sx={[
        {
          ml: 'auto',
          maxWidth,
          '& .MuiInputBase-root': { height: 28, typography: 'subtitle2', fontWeight: 400 },
          '& .MuiSvgIcon-root': { fontSize: '0.875rem' },
        },
        ...(Array.isArray(sx) ? sx : sx ? [sx] : []),
      ]}
    />
  );
}
