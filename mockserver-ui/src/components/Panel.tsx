import { useRef, useLayoutEffect, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
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
  /**
   * Total held by the SERVER, not the size of the page it sent. Omit entirely for
   * a live feed whose page is capped: a number pinned at the cap is worse than no
   * number, because it silently stops being a count.
   */
  count?: number;
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
   * Console mode: rows are in oldest-first order with the newest appended at the
   * BOTTOM, and the panel offers a Follow control that pins the view to the tail.
   * This is what makes the panel stable to read — a new row lands BELOW the
   * viewport, so nothing the reader is looking at moves and no scroll
   * compensation is needed. Omit for a list that is not a time-ordered feed
   * (Active Expectations is a set, not a stream).
   */
  follow?: boolean;
  onFollowChange?: (follow: boolean) => void;
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
  follow,
  onFollowChange,
  children,
}: PanelProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  // Kept for the at-top signal some callers still use; following is driven by the
  // explicit Follow control, not inferred from this.
  const atTopRef = useRef(true);
  // Console tail-following: stick to the BOTTOM, where the newest row is.
  //
  // This replaces snapping to the top on every update. The difference is not
  // cosmetic — with newest-at-bottom, arrivals land below the viewport, so a
  // reader who has scrolled up sees nothing move at all and needs no scroll
  // compensation. Following is an explicit choice (the Follow control) rather
  // than something inferred from scroll position, so it never fights the reader.
  useLayoutEffect(() => {
    if (follow && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [count, follow, children]);

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
        {count != null && count > 0 && (
          <Chip
            label={
              filteredCount != null && filteredCount !== count
                ? `${filteredCount > 999 ? '999+' : filteredCount} / ${count > 9999 ? '9999+' : count}`
                : count > 9999 ? '9999+' : count
            }
            color="primary"
            size="small"
            sx={{ height: 18, fontSize: '0.65rem', '& .MuiChip-label': { px: 0.75 } }}
          />
        )}
        {follow !== undefined && onFollowChange && (
          <Chip
            label={follow ? 'Following' : 'Follow'}
            size="small"
            color={follow ? 'primary' : 'default'}
            variant={follow ? 'filled' : 'outlined'}
            clickable
            onClick={() => onFollowChange(!follow)}
            title={
              follow
                ? 'Following the newest entries. Scroll up to stop and read — nothing will move.'
                : 'Paused while you read. Click to jump to the newest entries and resume following.'
            }
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
          const el = e.currentTarget;
          // Within a few px of the bottom counts as "at the tail": absorbs
          // sub-pixel offsets on HiDPI and a little inertial overshoot.
          const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= AT_TOP_THRESHOLD_PX;
          atTopRef.current = el.scrollTop <= AT_TOP_THRESHOLD_PX;
          if (onFollowChange && follow !== undefined && follow !== atBottom) {
            // Scrolling away from the tail stops following; scrolling back to it
            // resumes. The reader never has to reach for the control to read.
            onFollowChange(atBottom);
          }
          onScrolledAwayChange?.(!atBottom);
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
