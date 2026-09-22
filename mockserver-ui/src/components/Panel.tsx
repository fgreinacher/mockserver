import { useRef, useEffect, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import { useDashboardStore } from '../store';
import { transitions } from '../theme';
import OperatorSearchField from './OperatorSearchField';

// A scroll offset at or below this many pixels counts as "at the top" for
// tail-following auto-scroll. 8 is not arbitrary: it has to absorb sub-pixel
// scrollTop values on HiDPI displays (up to ~1px at 2x) AND the few pixels of
// inertial overshoot a trackpad or touch fling leaves behind (typically 5-8px).
// Tighter than that and a user parked at the top stops being recognised as such,
// so live pushes would silently stop following the tail; much looser and someone
// who deliberately scrolled down a little would still get yanked back.
const AT_TOP_THRESHOLD_PX = 8;

interface PanelProps {
  title: string;
  count: number;
  /** When a filter or search is active, pass the filtered count to show "N / total". */
  filteredCount?: number;
  searchValue: string;
  onSearchChange: (value: string) => void;
  searchInputRef?: React.RefObject<HTMLInputElement | null>;
  /**
   * Field operators this panel's rows can actually satisfy (`lib/filterDSL`
   * field names). Omit to advertise the whole vocabulary; pass `[]` for a panel
   * whose rows have no request/response to compare against (the Log panel). The
   * search box then advertises only what it can answer and flags an operator it
   * cannot, instead of silently returning an empty list.
   */
  searchFields?: readonly string[];
  /**
   * Optional controls rendered in the panel header, between the count chip and
   * the search box (e.g. a sort toggle). Omitted by most panels.
   */
  headerActions?: ReactNode;
  /**
   * When true, the scrollable content region is announced to assistive tech as
   * a polite live region (`role="log"` + `aria-live="polite"`) so newly
   * appended rows are read out. Used by the Log panel.
   */
  liveRegion?: boolean;
  children: ReactNode;
}

export default function Panel({
  title,
  count,
  filteredCount,
  searchValue,
  onSearchChange,
  searchInputRef,
  searchFields,
  headerActions,
  liveRegion,
  children,
}: PanelProps) {
  const autoScroll = useDashboardStore((s) => s.autoScroll);
  const scrollRef = useRef<HTMLDivElement>(null);
  // Whether the user is currently parked at the very top of the list. Auto-scroll
  // only "follows the tail" when this is true, so a new push never yanks the
  // viewport away from a row the user has scrolled down to and opened.
  const atTopRef = useRef(true);

  useEffect(() => {
    // Snap to the top on new data ONLY when the user is already at the top
    // (tail-following). If they have scrolled down to inspect or expand a row,
    // leave their position alone — otherwise every ~1/sec push scrolls that row
    // out of view (and virtualization then unmounts it), which reads as the
    // opened item "closing" and made the live panels unusable.
    if (autoScroll && atTopRef.current && scrollRef.current) {
      scrollRef.current.scrollTop = 0;
    }
  }, [count, autoScroll]);

  return (
    <Paper
      variant="outlined"
      sx={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        overflow: 'hidden',
        // Gentle affordance: the panel lifts and its border warms on hover so the
        // dashboard feels responsive rather than inert. Uses the shared transition
        // token and theme shadow ramp so motion stays consistent.
        transition: transitions.forProps(['box-shadow', 'border-color']),
        '&:hover': {
          boxShadow: (theme) => theme.shadows[2],
          borderColor: 'primary.main',
        },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          px: 1,
          py: 0.25,
          borderBottom: 1,
          borderColor: 'divider',
          flexShrink: 0,
        }}
      >
        <Typography variant="subtitle2">{title}</Typography>
        {count > 0 && (
          <Chip
            label={
              filteredCount != null && filteredCount !== count
                ? `${filteredCount > 999 ? '999+' : filteredCount} / ${count > 999 ? '999+' : count}`
                : count > 999 ? '999+' : count
            }
            color="primary"
            size="small"
            sx={{ height: 18, fontSize: '0.65rem', '& .MuiChip-label': { px: 0.75 } }}
          />
        )}
        {headerActions && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
            {headerActions}
          </Box>
        )}
        <OperatorSearchField
          id={`${title.toLowerCase().replace(/\s+/g, '-')}-search`}
          value={searchValue}
          onChange={onSearchChange}
          inputRef={searchInputRef}
          fields={searchFields}
        />
      </Box>
      <Box
        ref={scrollRef}
        onScroll={(e) => {
          // Treat "within a few px of the top" as at-top so a hair of momentum
          // scroll or a sub-pixel offset does not switch off tail-following.
          atTopRef.current = e.currentTarget.scrollTop <= AT_TOP_THRESHOLD_PX;
        }}
        {...(liveRegion ? { role: 'log', 'aria-live': 'polite' as const, 'aria-relevant': 'additions' as const } : {})}
        sx={{
          flex: 1,
          overflowY: 'auto',
          bgcolor: 'background.default',
          p: 0.5,
        }}
      >
        {children}
      </Box>
    </Paper>
  );
}
