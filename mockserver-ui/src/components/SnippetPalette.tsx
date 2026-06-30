/**
 * SnippetPalette — a popover menu of curated template snippets that can be
 * inserted into the Composer's template body field.
 *
 * The palette is engine-aware: it shows the correct Velocity / Mustache /
 * JavaScript syntax for each snippet based on the currently selected template
 * engine. Each snippet also shows a short description and an example rendering.
 */
import { useState, useCallback } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Popover from '@mui/material/Popover';
import Typography from '@mui/material/Typography';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import type { TemplateEngine } from '../lib/templateSnippets';
import { SNIPPET_CATEGORIES } from '../lib/templateSnippets';
import { monospaceFontFamily } from '../theme';

interface SnippetPaletteProps {
  /** The currently selected template engine — determines which syntax to show and insert. */
  engine: TemplateEngine;
  /** Called when the user clicks a snippet; receives the engine-specific syntax string. */
  onInsert: (snippet: string) => void;
}

export default function SnippetPalette({ engine, onInsert }: SnippetPaletteProps) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const open = Boolean(anchorEl);

  const handleOpen = useCallback((e: React.MouseEvent<HTMLElement>) => {
    setAnchorEl(e.currentTarget);
  }, []);

  const handleClose = useCallback(() => {
    setAnchorEl(null);
  }, []);

  const handleInsert = useCallback(
    (syntax: string) => {
      onInsert(syntax);
      handleClose();
    },
    [onInsert, handleClose],
  );

  return (
    <>
      <Button
        size="small"
        variant="outlined"
        onClick={handleOpen}
        aria-label="Insert snippet"
        sx={{ textTransform: 'none', fontSize: '0.78rem' }}
      >
        Insert snippet
      </Button>
      <Popover
        open={open}
        anchorEl={anchorEl}
        onClose={handleClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
        transformOrigin={{ vertical: 'top', horizontal: 'left' }}
        slotProps={{
          paper: {
            // Cap the width to the viewport (less a small margin) so the popover
            // never overflows on a narrow phone (e.g. 375px), while keeping the
            // comfortable 440px on wider screens.
            sx: { maxHeight: 420, width: 'min(440px, calc(100vw - 32px))', overflow: 'auto' },
          },
        }}
      >
        <Box sx={{ px: 2, pt: 1.5, pb: 0.5 }}>
          <Typography variant="subtitle2">
            Template Snippets
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Click a snippet to insert {engine === 'VELOCITY' ? 'Velocity' : engine === 'MUSTACHE' ? 'Mustache' : 'JavaScript'} syntax
          </Typography>
        </Box>

        {SNIPPET_CATEGORIES.map((category, catIdx) => (
          <Box key={category.id}>
            {catIdx > 0 && <Divider />}
            <Typography
              variant="overline"
              sx={{ px: 2, pt: 1, pb: 0.5, display: 'block', lineHeight: 1.5 }}
            >
              {category.label}
            </Typography>
            <List dense disablePadding>
              {category.snippets.map((snippet) => {
                const syntax = snippet.syntax[engine];
                return (
                  <ListItemButton
                    key={snippet.id}
                    onClick={() => handleInsert(syntax)}
                    sx={{ py: 0.5, px: 2, alignItems: 'flex-start' }}
                  >
                    <ListItemText
                      primary={
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                          <Typography variant="body2" sx={{ fontWeight: 500 }}>
                            {snippet.label}
                          </Typography>
                          <Chip
                            label={syntax}
                            size="small"
                            sx={{
                              fontFamily: monospaceFontFamily,
                              fontSize: '0.7rem',
                              height: 20,
                              maxWidth: 220,
                              '& .MuiChip-label': { overflow: 'hidden', textOverflow: 'ellipsis' },
                            }}
                          />
                        </Box>
                      }
                      secondary={
                        <Box component="span" sx={{ display: 'flex', flexDirection: 'column' }}>
                          <Typography
                            component="span"
                            variant="caption"
                            color="text.secondary"
                          >
                            {snippet.description}
                          </Typography>
                          <Typography
                            component="span"
                            variant="caption"
                            sx={{
                              fontFamily: monospaceFontFamily,
                              color: 'success.main',
                              mt: 0.25,
                            }}
                          >
                            {snippet.exampleOutput.length > 60
                              ? snippet.exampleOutput.slice(0, 57) + '...'
                              : snippet.exampleOutput}
                          </Typography>
                        </Box>
                      }
                    />
                  </ListItemButton>
                );
              })}
            </List>
          </Box>
        ))}
      </Popover>
    </>
  );
}
