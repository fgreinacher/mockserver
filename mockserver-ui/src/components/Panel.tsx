import { useRef, useEffect, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import { useDashboardStore } from '../store';
import { transitions } from '../theme';
import OperatorSearchField from './OperatorSearchField';

interface PanelProps {
  title: string;
  count: number;
  /** When a filter or search is active, pass the filtered count to show "N / total". */
  filteredCount?: number;
  searchValue: string;
  onSearchChange: (value: string) => void;
  searchInputRef?: React.RefObject<HTMLInputElement | null>;
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
  headerActions,
  liveRegion,
  children,
}: PanelProps) {
  const autoScroll = useDashboardStore((s) => s.autoScroll);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (autoScroll && scrollRef.current) {
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
        />
      </Box>
      <Box
        ref={scrollRef}
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
