import { useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Typography from '@mui/material/Typography';
import {
  verifyToJava,
  verifyToJson,
  verifyToCurl,
  verifyToNode,
  verifyToPython,
  verifyToGo,
  verifyToCsharp,
  verifyToRuby,
  verifyToRust,
  type VerificationCodegenInput,
} from '../lib/verificationCodegen';
import type { VerificationTimesSpec } from '../lib/verification';
import CopyButton from './CopyButton';
import { monospaceFontFamily } from '../theme';

export interface VerificationReviewProps {
  mode: 'single' | 'sequence';
  httpRequest: Record<string, unknown>;
  httpResponse: Record<string, unknown>;
  times: VerificationTimesSpec;
  httpRequests: Record<string, unknown>[];
  httpResponses: (Record<string, unknown> | undefined)[];
  baseUrl: string;
}

/**
 * Client-library previews (Java, Node, Python, Go, C#, Ruby, Rust) followed by
 * JSON and curl for the Verification panel. Mirrors StandardReview's UI exactly
 * (same 9 tabs, same order, same styling) so the user has a consistent
 * copy-pasteable code experience across the Composer and Verification panels.
 */
export default function VerificationReview(props: VerificationReviewProps) {
  const [tab, setTab] = useState(0);

  const input: VerificationCodegenInput = useMemo(() => ({
    mode: props.mode,
    httpRequest: props.httpRequest,
    httpResponse: props.httpResponse,
    times: props.times,
    httpRequests: props.httpRequests,
    httpResponses: props.httpResponses,
    baseUrl: props.baseUrl,
  }), [props.mode, props.httpRequest, props.httpResponse, props.times, props.httpRequests, props.httpResponses, props.baseUrl]);

  const javaCode = useMemo(() => verifyToJava(input), [input]);
  const nodeCode = useMemo(() => verifyToNode(input), [input]);
  const pythonCode = useMemo(() => verifyToPython(input), [input]);
  const goCode = useMemo(() => verifyToGo(input), [input]);
  const csharpCode = useMemo(() => verifyToCsharp(input), [input]);
  const rubyCode = useMemo(() => verifyToRuby(input), [input]);
  const rustCode = useMemo(() => verifyToRust(input), [input]);
  const jsonCode = useMemo(() => verifyToJson(input), [input]);
  const curlCode = useMemo(() => verifyToCurl(input), [input]);

  const tabLabels = ['Java', 'Node.js', 'Python', 'Go', 'C#', 'Ruby', 'Rust', 'JSON', 'curl'];
  const outputs = [javaCode, nodeCode, pythonCode, goCode, csharpCode, rubyCode, rustCode, jsonCode, curlCode];
  const safeTab = Math.min(tab, tabLabels.length - 1);

  return (
    <Box sx={{ py: 1 }}>
      <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 0.5 }}>
        Generated Code
      </Typography>
      <Tabs
        value={safeTab}
        onChange={(_, v: number) => setTab(v)}
        sx={{ mb: 1, minHeight: 32, '& .MuiTab-root': { minHeight: 32, py: 0.5, fontSize: '0.8rem' } }}
      >
        {tabLabels.map((label) => (
          <Tab key={label} label={label} />
        ))}
      </Tabs>

      <Box sx={{ position: 'relative' }}>
        <Box sx={{ position: 'absolute', top: 4, right: 4, zIndex: 1 }}>
          <CopyButton text={outputs[safeTab]!} />
        </Box>
        <Box
          component="pre"
          sx={{
            fontFamily: monospaceFontFamily,
            fontSize: '0.75rem',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            m: 0,
            p: 1.5,
            bgcolor: 'action.hover',
            borderRadius: 1,
            overflow: 'auto',
            maxHeight: 500,
          }}
        >
          {outputs[safeTab]}
        </Box>
      </Box>
    </Box>
  );
}
