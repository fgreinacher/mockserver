import { useCallback, useState } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import IconButton from '@mui/material/IconButton';
import InputBase from '@mui/material/InputBase';
import Tooltip from '@mui/material/Tooltip';
import AddIcon from '@mui/icons-material/Add';
import CloseIcon from '@mui/icons-material/Close';
import { useDashboardStore } from '../store';

/**
 * The workspace switcher.
 *
 * A workspace is an independent investigation context — its own view and its own
 * per-panel search terms — so one window can hold two separate lines of enquiry
 * without either clobbering the other's filters.
 *
 * PLACEMENT. This renders as its OWN full-width row beneath the app bar, never
 * inside it. An earlier "recent views" feature put quick-access tabs *into* the
 * app bar's navigation region and was reverted because the bar no longer fitted
 * on typical widths (commits d536433f1 / d55d7077d). Two rules keep that from
 * repeating here:
 *   1. the tabs compete for their own row's horizontal space, not the nav's, and
 *      overflow scrolls (`nowrap` + `overflowX: auto`) instead of wrapping the
 *      bar to a second line;
 *   2. the row does not exist at all until there is more than one workspace, so
 *      the default single-workspace dashboard is pixel-identical to before. The
 *      one control that has to live in the app bar is the single icon button
 *      that creates the second workspace.
 */
export default function WorkspaceTabBar() {
  const workspaces = useDashboardStore((s) => s.workspaces);
  const activeWorkspaceId = useDashboardStore((s) => s.activeWorkspaceId);
  const switchWorkspace = useDashboardStore((s) => s.switchWorkspace);
  const closeWorkspace = useDashboardStore((s) => s.closeWorkspace);
  const renameWorkspace = useDashboardStore((s) => s.renameWorkspace);
  const addWorkspace = useDashboardStore((s) => s.addWorkspace);

  // Inline rename: the id being edited and the draft text. Enter or blur commits,
  // Escape abandons. A blank draft is rejected by the store, leaving the old name.
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftName, setDraftName] = useState('');

  const commitRename = useCallback(() => {
    if (editingId !== null) renameWorkspace(editingId, draftName);
    setEditingId(null);
  }, [editingId, draftName, renameWorkspace]);

  // A single workspace needs no switcher — see the placement note above.
  if (workspaces.length <= 1) return null;

  return (
    <Box
      role="group"
      aria-label="Workspaces"
      data-testid="workspace-tab-bar"
      sx={{
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        gap: 0.5,
        px: 1,
        py: 0.25,
        borderBottom: 1,
        borderColor: 'divider',
        bgcolor: 'action.hover',
        // Never wrap: extra workspaces scroll within this row rather than growing
        // the chrome and pushing the view area down.
        flexWrap: 'nowrap',
        overflowX: 'auto',
        overflowY: 'hidden',
      }}
    >
      {workspaces.map((workspace) => {
        const active = workspace.id === activeWorkspaceId;
        return (
          <Box
            key={workspace.id}
            sx={{
              display: 'flex',
              alignItems: 'center',
              flexShrink: 0,
              maxWidth: 220,
              borderRadius: 1,
              bgcolor: active ? 'action.selected' : 'transparent',
            }}
          >
            {editingId === workspace.id ? (
              <InputBase
                autoFocus
                value={draftName}
                inputProps={{ 'aria-label': `Rename workspace ${workspace.name}` }}
                onChange={(e) => setDraftName(e.target.value)}
                onBlur={commitRename}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    commitRename();
                  } else if (e.key === 'Escape') {
                    e.preventDefault();
                    setEditingId(null);
                  }
                }}
                sx={{ px: 1, fontSize: '0.75rem', width: 140 }}
              />
            ) : (
              <Tooltip title={active ? `${workspace.name} (double-click to rename)` : `Switch to ${workspace.name}`}>
                <ButtonBase
                  aria-label={`Workspace ${workspace.name}`}
                  aria-current={active ? 'true' : undefined}
                  onClick={() => switchWorkspace(workspace.id)}
                  onDoubleClick={() => {
                    setDraftName(workspace.name);
                    setEditingId(workspace.id);
                  }}
                  sx={{
                    px: 1,
                    py: 0.25,
                    borderRadius: 1,
                    fontSize: '0.75rem',
                    fontWeight: active ? 600 : 400,
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    display: 'block',
                  }}
                >
                  {workspace.name}
                </ButtonBase>
              </Tooltip>
            )}
            <IconButton
              size="small"
              aria-label={`Close workspace ${workspace.name}`}
              onClick={() => closeWorkspace(workspace.id)}
              sx={{ mr: 0.25 }}
            >
              <CloseIcon sx={{ fontSize: '0.75rem' }} />
            </IconButton>
          </Box>
        );
      })}
      <Tooltip title="New workspace">
        <IconButton size="small" aria-label="New workspace" onClick={addWorkspace} sx={{ flexShrink: 0 }}>
          <AddIcon sx={{ fontSize: '0.875rem' }} />
        </IconButton>
      </Tooltip>
    </Box>
  );
}
