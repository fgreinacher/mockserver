import { useRef, useMemo } from 'react';
import Typography from '@mui/material/Typography';
import { useDashboardStore } from '../store';
import { isLogGroup } from '../types';
import Panel from './Panel';
import LogEntry from './LogEntry';
import LogGroup from './LogGroup';
import ProgressiveList from './ProgressiveList';
import { useExpansion } from '../hooks/useExpansion';
import { matchesLogSearch, isForwardedLogEntry } from '../lib/searchMatcher';
import { LOG_FILTER_OPTIONS } from '../lib/filterDSL';

// Log rows carry no httpRequest/httpResponse, so `matchesLogSearch` can satisfy
// no field operator at all. Declaring that to the search box (rather than
// leaving the full vocabulary advertised) is what turns a typed `status:>=400`
// into a visible "not supported here" instead of a silent empty list.
const LOG_SEARCH_FIELDS = LOG_FILTER_OPTIONS.fields ?? [];

export default function LogPanel() {
  const logMessages = useDashboardStore((s) => s.logMessages);
  const search = useDashboardStore((s) => s.logSearch);
  const setSearch = useDashboardStore((s) => s.setLogSearch);
  const showForwarded = useDashboardStore((s) => s.logShowForwarded);
  const searchRef = useRef<HTMLInputElement>(null);

  const filtered = useMemo(() => {
    let rows = logMessages;
    if (!showForwarded) rows = rows.filter((m) => !isForwardedLogEntry(m));
    if (search) rows = rows.filter((m) => matchesLogSearch(m, search));
    return rows;
  }, [logMessages, search, showForwarded]);

  const expansion = useExpansion();

  return (
    <Panel
      title="Log Messages"
      count={logMessages.length}
      filteredCount={(search || !showForwarded) ? filtered.length : undefined}
      searchValue={search}
      onSearchChange={setSearch}
      searchInputRef={searchRef}
      searchFields={LOG_SEARCH_FIELDS}
      liveRegion
    >
      {filtered.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
          {logMessages.length === 0 ? 'No log messages yet — server activity appears here as requests are handled.' : 'No matching log messages'}
        </Typography>
      ) : (
        <ProgressiveList
          count={filtered.length}
          getKey={(i) => filtered[i]!.key}
          renderRow={(i) => {
            const message = filtered[i]!;
            return isLogGroup(message) ? (
              <LogGroup
                group={message}
                open={expansion.isExpanded(message.key)}
                onToggleOpen={expansion.toggle}
              />
            ) : (
              <LogEntry
                entry={message.value}
                entryKey={message.key}
                expanded={expansion.isExpanded(message.key)}
                onToggleExpand={expansion.toggle}
                divider
                collapsible
              />
            );
          }}
        />
      )}
    </Panel>
  );
}
