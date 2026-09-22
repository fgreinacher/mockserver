import { useRef, useLayoutEffect, type ReactNode } from 'react';
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
  /**
   * Called when the reader scrolls away from the top, or back to it. The panel
   * uses this (combined with whether anything is expanded) to HOLD the rows being
   * read, so the live window cannot delete them mid-read — see `useHeldItems`.
   * Scroll position alone cannot live in the panel, because this component owns
   * the scroll container.
   */
  onScrolledAwayChange?: (scrolledAway: boolean) => void;
  /**
   * True when the reader has a row open. Tail-following is suppressed while it is
   * set: someone reading an entry is reading it wherever they opened it, and that
   * includes at the very top of the list.
   */
  hasOpenItem?: boolean;
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
  onScrolledAwayChange,
  hasOpenItem = false,
  children,
}: PanelProps) {
  const autoScroll = useDashboardStore((s) => s.autoScroll);
  const scrollRef = useRef<HTMLDivElement>(null);
  // Whether the user is currently parked at the very top of the list. Auto-scroll
  // only "follows the tail" when this is true, so a new push never yanks the
  // viewport away from a row the user has scrolled down to and opened.
  const atTopRef = useRef(true);
  useLayoutEffect(() => {
    // Tail-following, and nothing else. Holding the reader's position against
    // rows arriving ABOVE them is scroll ANCHORING, and it lives in
    // ProgressiveList: once the list is windowed, the row the reader is looking
    // at can be unmounted by the very update we are trying to compensate for, so
    // it cannot be located in the DOM afterwards. Only the virtualizer knows
    // where it went, because it measures every row whether or not it is mounted.
    // `!hasOpenItem` is the part that was missing, and it is the whole bug a
    // reader actually hits. Opening a row does not require scrolling first: click
    // one near the top, and tail-following pins scrollTop at 0 while new rows
    // prepend ABOVE it, walking the row down the screen and then off it.
    // Measured on a real server at ~10 req/s, an entry opened without scrolling
    // went top=657 -> 853 -> 1049 -> gone in four seconds, with scrollTop stuck
    // at 0 throughout. Holding the ROWS was not enough; the view has to stop
    // chasing the tail as soon as the reader is reading something.
    if (autoScroll && atTopRef.current && !hasOpenItem && scrollRef.current) {
      scrollRef.current.scrollTop = 0;
    }
  }, [count, autoScroll, hasOpenItem]);

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
          const atTop = e.currentTarget.scrollTop <= AT_TOP_THRESHOLD_PX;
          if (atTop !== atTopRef.current) {
            atTopRef.current = atTop;
            // Only on a transition, so this does not fire a state update on every
            // scroll event.
            onScrolledAwayChange?.(!atTop);
          }
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
