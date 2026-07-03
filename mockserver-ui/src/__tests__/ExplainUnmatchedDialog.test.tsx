import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import ExplainUnmatchedDialog from '../components/ExplainUnmatchedDialog';
import { explainUnmatched } from '../lib/explainUnmatched';

vi.mock('../lib/explainUnmatched', () => ({
  explainUnmatched: vi.fn(),
}));

const connectionParams = { host: 'localhost', port: '1080', secure: false };
const mockExplain = vi.mocked(explainUnmatched);

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ExplainUnmatchedDialog chip wording', () => {
  it('shows an honest "differs on N field(s)" chip, not an inflated matched-count', async () => {
    // A method+path matcher: the server reports 15/16 possible fields "matched"
    // even though only one field actually differs. The chip must show the honest count.
    mockExplain.mockResolvedValue({
      correlationId: 'c',
      timestamp: 't',
      unmatchedRequestCount: 1,
      truncated: false,
      unmatchedRequests: [
        {
          method: 'GET',
          path: '/api/items',
          totalExpectationsEvaluated: 1,
          closestExpectations: [
            {
              expectationId: 'exp-1',
              expectationMethod: 'GET',
              expectationPath: '/api/users',
              matches: false,
              matchedFieldCount: 15,
              totalFieldCount: 16,
              differingFieldCount: 1,
              differences: { path: ['expected /api/users but was /api/items'] },
              remediation: { path: 'check the request path' },
            },
          ],
        },
      ],
    });

    render(<ExplainUnmatchedDialog open onClose={() => {}} connectionParams={connectionParams} />);

    await waitFor(() => expect(screen.getByText('differs on 1 field')).toBeInTheDocument());
    expect(screen.queryByText(/matched 15\/16/)).not.toBeInTheDocument();
  });
});
