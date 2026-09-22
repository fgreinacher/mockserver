// Browser harness for the Panel scroll-anchoring regression test
// (e2e/scroll-anchor.pw.ts). It renders the REAL <Panel> and <ProgressiveList>
// — the exact components the dashboard ships — over a data source the test can
// drive: prepend rows to the front (as the newest-first live feed does) and
// expand a row.
//
// It deliberately does NOT need MockServer: the bug is a pure client-side
// virtualization/scroll interaction, and jsdom cannot reproduce it because it
// has no layout engine (scrollTop/scrollHeight/offsetHeight are always 0). A
// real browser via Vite serves the actual component source, so a failure here
// is a failure of the shipped code, not of a mock.
import { StrictMode, useCallback, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import Box from '@mui/material/Box';
import { buildTheme } from '../../src/theme';
import Panel from '../../src/components/Panel';
import ProgressiveList from '../../src/components/ProgressiveList';

interface Row {
  key: string;
  label: string;
}

// One expandable row. Collapsed it is short; expanded it is tall — like a log
// entry that opens to reveal its request/response. Expansion state is held by
// the parent and keyed by row id (mirroring the dashboard's useExpansion), so a
// row that momentarily unmounts and remounts keeps its expanded state — which is
// exactly why the test must assert on the row STAYING in the viewport, not merely
// on an "expanded" flag that could survive an unmount.
function HarnessRow({
  row,
  expanded,
  onToggle,
}: {
  row: Row;
  expanded: boolean;
  onToggle: (key: string) => void;
}) {
  return (
    <Box
      data-testid={`row-${row.key}`}
      data-expanded={expanded ? 'true' : 'false'}
      sx={{ borderBottom: 1, borderColor: 'divider' }}
    >
      <Box
        component="button"
        type="button"
        data-testid={`toggle-${row.key}`}
        onClick={() => onToggle(row.key)}
        sx={{
          display: 'block',
          width: '100%',
          textAlign: 'left',
          border: 0,
          bgcolor: 'transparent',
          color: 'inherit',
          font: 'inherit',
          px: 1,
          py: 1.25,
          cursor: 'pointer',
        }}
      >
        {row.label}
      </Box>
      {expanded && (
        <Box data-testid={`detail-${row.key}`} sx={{ px: 2, pb: 2, height: 320 }}>
          expanded detail for {row.label}
        </Box>
      )}
    </Box>
  );
}

function Harness() {
  // Newest-first, like every live panel: index 0 is the newest row. `seq` only
  // ever increments, so a prepended row always carries a brand-new, stable key.
  const [rows, setRows] = useState<Row[]>(() =>
    Array.from({ length: 60 }, (_, i) => {
      const n = 60 - i; // row-60 (newest) at the top … row-1 (oldest) at the bottom
      return { key: `r${n}`, label: `row-${n}` };
    }),
  );
  // The highest row number issued so far. A ref (not state) so prepend derives
  // fresh keys without nesting one setState inside another updater.
  const seqRef = useRef(60);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [search, setSearch] = useState('');

  const onToggle = useCallback((key: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  // Prepend `count` brand-new rows to the FRONT — the exact shape of a live push.
  const prepend = useCallback((count: number) => {
    const start = seqRef.current;
    seqRef.current = start + count;
    const added: Row[] = Array.from({ length: count }, (_, i) => {
      const n = start + count - i; // keep them newest-first among themselves
      return { key: `r${n}`, label: `row-${n}` };
    });
    setRows((prev) => [...added, ...prev]);
  }, []);

  // Prepend `count` new rows AND drop `count` from the tail, so the list length
  // never changes. This is what a live dashboard panel actually does once the
  // server-side cap is reached: DashboardWebSocketHandler sends at most
  // DEFAULT_LOG_UPDATE_ITEM_LIMIT (100) rows, so on any server that has handled
  // more than that, every push REPLACES the oldest row rather than adding one.
  // The list length is then CONSTANT for the rest of the session — which is the
  // steady state the fix has to survive, not the brief initial growth phase.
  const push = useCallback((count: number) => {
    const start = seqRef.current;
    seqRef.current = start + count;
    const added: Row[] = Array.from({ length: count }, (_, i) => {
      const n = start + count - i;
      return { key: `r${n}`, label: `row-${n}` };
    });
    setRows((prev) => [...added, ...prev].slice(0, prev.length));
  }, []);

  // Expose the list actions to the test without a visible control that could
  // itself shift layout. (This is test scaffolding in a harness file, never in
  // product code.)
  const w = window as unknown as {
    prependRows: (n: number) => void;
    pushRows: (n: number) => void;
    rowCount: number;
  };
  w.prependRows = prepend;
  w.pushRows = push;
  // The DATA length, so a test can tell "the list is the same length" from
  // "the virtualizer happens to have the same number of rows mounted".
  w.rowCount = rows.length;

  const theme = useMemo(() => buildTheme('light'), []);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {/* Fixed-height frame so Panel's height:100% resolves and the list scrolls. */}
      <Box data-testid="frame" sx={{ height: 480, width: 520, m: 2 }}>
        <Panel
          title="Harness"
          count={rows.length}
          searchValue={search}
          onSearchChange={setSearch}
        >
          <ProgressiveList
            count={rows.length}
            getKey={(i) => rows[i]!.key}
            renderRow={(i) => (
              <HarnessRow
                row={rows[i]!}
                expanded={expanded.has(rows[i]!.key)}
                onToggle={onToggle}
              />
            )}
          />
        </Panel>
      </Box>
    </ThemeProvider>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Harness />
  </StrictMode>,
);
