import { createTheme } from '@mui/material/styles';
import type { ThemeMode } from './types';

/**
 * logTypeColors — the legacy log-type colour map (light-background variants).
 *
 * Kept as a flat `rgb(...)` map for backwards compatibility (existing imports and
 * tests rely on this exact shape and on every value being an `rgb(...)` string).
 *
 * TODO(theme): consumers should switch to `logTypeColor(type, mode)` so each log
 * type renders with a colour that has adequate contrast on the *current* paper
 * background — several of the values below were picked for a light background and
 * look muddy on the dark `#1e1e1e` paper.
 */
export const logTypeColors = {
  TRACE: 'rgb(215, 216, 154)',
  DEBUG: 'rgb(178, 132, 190)',
  INFO: 'rgb(59, 122, 87)',
  WARN: 'rgb(245, 95, 105)',
  ERROR: 'rgb(179, 97, 122)',
  EXCEPTION: 'rgb(211, 33, 45)',
  CLEARED: 'rgb(139, 146, 52)',
  RETRIEVED: 'rgb(222, 147, 95)',
  UPDATED_EXPECTATION: 'rgb(176, 191, 26)',
  CREATED_EXPECTATION: 'rgb(216, 199, 166)',
  REMOVED_EXPECTATION: 'rgb(124, 185, 232)',
  RECEIVED_REQUEST: 'rgb(114, 160, 193)',
  EXPECTATION_RESPONSE: 'rgb(161, 208, 231)',
  NO_MATCH_RESPONSE: 'rgb(196, 98, 16)',
  EXPECTATION_MATCHED: 'rgb(117, 185, 186)',
  EXPECTATION_NOT_MATCHED: 'rgb(204, 165, 163)',
  VERIFICATION: 'rgb(178, 148, 187)',
  VERIFICATION_FAILED: 'rgb(234, 67, 106)',
  FORWARDED_REQUEST: 'rgb(152, 208, 255)',
  TEMPLATE_GENERATED: 'rgb(241, 186, 27)',
  TEMPLATE_GENERATION_FAILED: 'rgb(211, 33, 45)',
  SERVER_CONFIGURATION: 'rgb(138, 175, 136)',
  DEFAULT: 'rgb(201, 125, 240)',
} as const;

export type LogType = keyof typeof logTypeColors;

/**
 * Dark-background overrides for log-type colours.
 *
 * Only the entries whose light-bg colour (`logTypeColors`) is too pale,
 * low-saturation or low-contrast against the dark `#1e1e1e` paper are overridden
 * here; any type not listed falls back to its `logTypeColors` value (which already
 * reads well on dark). Each override brightens/saturates the hue so it stays
 * recognisably the "same" colour while clearing a readable contrast bar.
 */
const logTypeColorsDark: Partial<Record<LogType, string>> = {
  // Pale beige meant for a light card — washes out on dark; warm it to a clear amber.
  CREATED_EXPECTATION: 'rgb(232, 196, 120)',
  // Muddy desaturated rose — lift to a brighter, more saturated salmon.
  EXPECTATION_NOT_MATCHED: 'rgb(226, 150, 148)',
  // Olive/khaki greens that go murky on dark — brighten to a lime-ish green.
  CLEARED: 'rgb(186, 196, 96)',
  UPDATED_EXPECTATION: 'rgb(206, 222, 70)',
  // Mid green on dark is fine but a touch dim; lift for legibility.
  INFO: 'rgb(102, 187, 142)',
  SERVER_CONFIGURATION: 'rgb(168, 205, 166)',
  // Dark wine/maroon reds disappear on dark — brighten.
  ERROR: 'rgb(214, 132, 156)',
  EXCEPTION: 'rgb(244, 96, 106)',
  // Match the EXCEPTION brightening so a failed template generation reads clearly on dark.
  TEMPLATE_GENERATION_FAILED: 'rgb(244, 96, 106)',
};

/**
 * logTypeColor — mode-aware accessor for log-type colours.
 *
 * Returns a colour with adequate contrast for the given theme mode. Prefer this
 * over indexing `logTypeColors` directly so the dark theme stays readable.
 */
export function logTypeColor(type: LogType, mode: ThemeMode): string {
  if (mode === 'dark') {
    return logTypeColorsDark[type] ?? logTypeColors[type];
  }
  return logTypeColors[type];
}

export const becauseColors = {
  matched: 'rgb(107, 199, 118)',
  didntMatch: 'rgb(216, 88, 118)',
  neutral: 'rgb(255, 255, 255)',
} as const;

/**
 * Monospace stack used for log/JSON/code surfaces. Exported so components can
 * share one definition instead of repeating the bare `'monospace'` keyword.
 */
export const monospaceFontFamily =
  '"Roboto Mono", "SF Mono", "JetBrains Mono", "Menlo", "Consolas", monospace';

/**
 * Shared transition convention. Use these tokens for hover / elevation / colour
 * changes so motion is consistent across the dashboard.
 */
export const transitions = {
  /** Quick UI feedback (hover, focus, small colour shifts). */
  fast: 'all 150ms cubic-bezier(0.4, 0, 0.2, 1)',
  /** Standard surface motion (elevation, expand/collapse). */
  standard: 'all 220ms cubic-bezier(0.4, 0, 0.2, 1)',
  /** Property-scoped helper to avoid animating everything with `all`. */
  forProps: (props: string[], ms = 180) =>
    props.map((p) => `${p} ${ms}ms cubic-bezier(0.4, 0, 0.2, 1)`).join(', '),
} as const;

/**
 * Soft, layered shadow tokens. A `box-shadow` per resting elevation plus a
 * `hover` step that primary surfaces lift to. Mode-aware because shadows need
 * deeper/darker spread to read on the dark canvas.
 */
function elevationShadows(mode: ThemeMode) {
  if (mode === 'dark') {
    return {
      resting: '0 1px 2px rgba(0, 0, 0, 0.5), 0 1px 3px rgba(0, 0, 0, 0.35)',
      raised: '0 2px 6px rgba(0, 0, 0, 0.55), 0 4px 12px rgba(0, 0, 0, 0.4)',
      hover: '0 4px 12px rgba(0, 0, 0, 0.6), 0 8px 24px rgba(0, 0, 0, 0.45)',
      appBar: '0 1px 0 rgba(255, 255, 255, 0.06), 0 2px 8px rgba(0, 0, 0, 0.5)',
      border: 'rgba(255, 255, 255, 0.12)',
    };
  }
  return {
    resting: '0 1px 2px rgba(15, 23, 42, 0.06), 0 1px 3px rgba(15, 23, 42, 0.08)',
    raised: '0 2px 6px rgba(15, 23, 42, 0.08), 0 4px 12px rgba(15, 23, 42, 0.08)',
    hover: '0 4px 12px rgba(15, 23, 42, 0.12), 0 8px 24px rgba(15, 23, 42, 0.1)',
    appBar: '0 1px 0 rgba(15, 23, 42, 0.04), 0 2px 8px rgba(15, 23, 42, 0.08)',
    border: 'rgba(15, 23, 42, 0.12)',
  };
}

export function buildTheme(mode: ThemeMode) {
  const isDark = mode === 'dark';
  const elevation = elevationShadows(mode);

  return createTheme({
    palette: {
      mode,
      ...(isDark
        ? {
            background: {
              default: '#121212',
              paper: '#1e1e1e',
            },
            primary: {
              main: '#00bcd4',
            },
            secondary: {
              main: '#ff9800',
            },
          }
        : {
            background: {
              default: '#fafafa',
              paper: '#ffffff',
            },
            primary: {
              main: '#00838f',
            },
            secondary: {
              main: '#e65100',
            },
          }),
    },
    shape: {
      borderRadius: 8,
    },
    typography: {
      fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
      // Base 14 preserved — variant sizes below are tuned to the dense rem
      // clusters the dashboard already hardcodes (0.65 / 0.7 / 0.75 / 0.78 /
      // 0.82 / 0.875rem) so components can migrate to variants without resizing.
      fontSize: 14,
      // Larger panel/section titles (cluster around 0.875–1rem).
      h5: { fontSize: '1rem', fontWeight: 600, lineHeight: 1.4 },
      h6: { fontSize: '0.875rem', fontWeight: 600, lineHeight: 1.4 },
      // Subtitles — the dense label rows (0.78 / 0.75rem clusters).
      subtitle1: { fontSize: '0.78rem', fontWeight: 600, lineHeight: 1.45 },
      subtitle2: { fontSize: '0.75rem', fontWeight: 600, lineHeight: 1.45 },
      // Body text — secondary/compact rows (0.82 / 0.78rem clusters).
      body2: { fontSize: '0.78rem', lineHeight: 1.5 },
      // Smallest metadata / chip-ish text (0.7 / 0.65rem clusters).
      caption: { fontSize: '0.7rem', lineHeight: 1.4 },
    },
    transitions: {
      duration: {
        shortest: 120,
        shorter: 150,
        short: 180,
        standard: 220,
      },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: {
            margin: 0,
          },
          // Subtle scrollbar styling that matches the canvas in both modes.
          '*::-webkit-scrollbar': {
            width: 10,
            height: 10,
          },
          '*::-webkit-scrollbar-thumb': {
            backgroundColor: isDark
              ? 'rgba(255, 255, 255, 0.18)'
              : 'rgba(15, 23, 42, 0.2)',
            borderRadius: 8,
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          root: {
            backgroundImage: 'none',
            borderRadius: 8,
          },
          // Outlined Paper keeps the crisp 1px border; elevated Paper gets the
          // soft layered shadow instead of MUI's default flat ramp.
          outlined: {
            border: `1px solid ${elevation.border}`,
          },
          elevation1: {
            boxShadow: elevation.resting,
          },
          elevation2: {
            boxShadow: elevation.raised,
          },
          elevation3: {
            boxShadow: elevation.raised,
          },
        },
      },
      MuiCard: {
        defaultProps: {
          elevation: 1,
        },
        styleOverrides: {
          root: {
            borderRadius: 8,
            border: `1px solid ${elevation.border}`,
            boxShadow: elevation.resting,
            transition: transitions.forProps(['box-shadow', 'border-color']),
          },
        },
      },
      MuiAppBar: {
        styleOverrides: {
          root: {
            boxShadow: elevation.appBar,
            backgroundImage: 'none',
          },
        },
      },
      MuiButton: {
        defaultProps: {
          disableElevation: true,
        },
        styleOverrides: {
          root: {
            // Dev-tool house style: no shouty uppercase, rounded to the system radius.
            textTransform: 'none',
            borderRadius: 8,
            fontWeight: 600,
            transition: transitions.forProps([
              'background-color',
              'border-color',
              'box-shadow',
              'color',
            ]),
          },
        },
      },
      MuiChip: {
        defaultProps: {
          size: 'small',
        },
        styleOverrides: {
          root: {
            borderRadius: 6,
            fontSize: '0.7rem',
            fontWeight: 600,
            height: 22,
          },
          label: {
            paddingLeft: 8,
            paddingRight: 8,
          },
        },
      },
      MuiToggleButton: {
        styleOverrides: {
          root: {
            textTransform: 'none',
            borderRadius: 8,
            fontWeight: 600,
            transition: transitions.forProps([
              'background-color',
              'border-color',
              'color',
            ]),
            '&.Mui-selected': {
              boxShadow: elevation.resting,
            },
          },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: {
            fontSize: '0.7rem',
            borderRadius: 6,
            backgroundColor: isDark
              ? 'rgba(40, 40, 40, 0.96)'
              : 'rgba(33, 33, 33, 0.94)',
            boxShadow: elevation.raised,
            padding: '6px 10px',
          },
          arrow: {
            color: isDark ? 'rgba(40, 40, 40, 0.96)' : 'rgba(33, 33, 33, 0.94)',
          },
        },
      },
      MuiTableRow: {
        styleOverrides: {
          root: {
            transition: transitions.forProps(['background-color']),
            // Selected rows get a faint layered shadow so they lift off the table.
            '&.Mui-selected': {
              boxShadow: `inset 0 0 0 9999px ${
                isDark ? 'rgba(0, 188, 212, 0.10)' : 'rgba(0, 131, 143, 0.08)'
              }`,
            },
          },
        },
      },
    },
  });
}
