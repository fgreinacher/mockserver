import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OperatorSearchField from '../components/OperatorSearchField';

const placeholderOf = () => screen.getByLabelText('Search').getAttribute('placeholder') ?? '';

describe('OperatorSearchField', () => {
  it('advertises the whole registered operator vocabulary by default', () => {
    render(<OperatorSearchField id="s" value="" onChange={() => {}} />);
    const placeholder = placeholderOf();
    for (const example of ['status:>=400', 'method:POST', 'path:/api/*', 'host:*.example.com', 'operation:GetUser', '/regex/']) {
      expect(placeholder).toContain(example);
    }
  });

  it('advertises only the declared subset when a call site restricts the vocabulary', () => {
    render(<OperatorSearchField id="s" value="" onChange={() => {}} fields={['method', 'path', 'host']} />);
    const placeholder = placeholderOf();
    expect(placeholder).toContain('method:POST');
    expect(placeholder).toContain('path:/api/*');
    expect(placeholder).toContain('host:*.example.com');
    expect(placeholder).not.toContain('status:');
    expect(placeholder).not.toContain('operation:');
  });

  it('documents only the declared subset in the help tooltip', async () => {
    const user = userEvent.setup();
    render(<OperatorSearchField id="s" value="" onChange={() => {}} fields={['method', 'host']} />);
    await user.hover(screen.getByLabelText('Search operator help'));
    const tip = await screen.findByRole('tooltip');
    expect(tip).toHaveTextContent('method:POST');
    expect(tip).toHaveTextContent('host:*.example.com');
    expect(tip).not.toHaveTextContent('status:>=400');
  });

  it('flags an operator the surface cannot satisfy instead of ignoring it', () => {
    render(<OperatorSearchField id="s" value="status:>=400 /orders" onChange={() => {}} fields={['method', 'path']} />);
    const helper = screen.getByText(/not supported here/i);
    expect(helper).toHaveTextContent('status:');
    expect(helper).toHaveTextContent('Supported here: method:, path:');
    expect(screen.getByLabelText('Search')).toHaveAttribute('aria-invalid', 'true');
  });

  it('does not flag a supported operator, nor any operator when unrestricted', () => {
    const { unmount } = render(
      <OperatorSearchField id="s" value="method:POST" onChange={() => {}} fields={['method', 'path']} />,
    );
    expect(screen.queryByText(/not supported here/i)).toBeNull();
    unmount();

    render(<OperatorSearchField id="s2" value="status:>=400 operation:GetUser" onChange={() => {}} />);
    expect(screen.queryByText(/not supported here/i)).toBeNull();
  });

  it('reports edits to the caller', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<OperatorSearchField id="s" value="" onChange={onChange} />);
    await user.type(screen.getByLabelText('Search'), 'a');
    expect(onChange).toHaveBeenCalledWith('a');
  });
});
