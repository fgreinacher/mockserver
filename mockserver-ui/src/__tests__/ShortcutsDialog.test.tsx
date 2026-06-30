import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ShortcutsDialog from '../components/ShortcutsDialog';

function renderDialog(open = true, onClose = vi.fn()) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ShortcutsDialog open={open} onClose={onClose} />
    </ThemeProvider>,
  );
}

describe('ShortcutsDialog', () => {
  it('lists all keyboard shortcuts when open', () => {
    renderDialog();
    expect(screen.getByText('Keyboard Shortcuts')).toBeInTheDocument();
    expect(screen.getByText('Focus the log search field')).toBeInTheDocument();
    expect(screen.getByText('Clear server logs (asks for confirmation)')).toBeInTheDocument();
    expect(screen.getByText('Show / hide the request filter panel')).toBeInTheDocument();
    // The key bindings themselves are shown.
    expect(screen.getByText(/⌘K/)).toBeInTheDocument();
    expect(screen.getByText('Esc')).toBeInTheDocument();
  });

  it('does not render content when closed', () => {
    renderDialog(false);
    expect(screen.queryByText('Keyboard Shortcuts')).not.toBeInTheDocument();
  });

  it('calls onClose when Close is clicked', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderDialog(true, onClose);
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledOnce();
  });
});
