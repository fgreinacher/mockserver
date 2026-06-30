import { useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Typography from '@mui/material/Typography';
import {
  loadToJava,
  loadToJson,
  loadToCurl,
  loadToNode,
  loadToPython,
  loadToGo,
  loadToCsharp,
  loadToRuby,
  loadToRust,
  type LoadScenarioCodegenInput,
} from '../lib/loadScenarioCodegen';
import type { LoadScenarioDTO } from '../lib/loadScenario';
import CopyButton from './CopyButton';
import { monospaceFontFamily } from '../theme';

export interface LoadScenarioReviewProps {
  scenario: LoadScenarioDTO;
  baseUrl: string;
}

/**
 * Client-library previews (Java, Node, Python, Go, C#, Ruby, Rust) followed by
 * JSON and curl for the Performance panel. Mirrors VerificationReview's UI
 * exactly (same 9 tabs, same order, same styling) so registering + starting a
 * load scenario is a consistent copy-pasteable experience across panels. Every
 * snippet performs the two REST calls: register (PUT /loadScenario) then start
 * (PUT /loadScenario/start).
 */
export default function LoadScenarioReview(props: LoadScenarioReviewProps) {
  const [tab, setTab] = useState(0);

  const input: LoadScenarioCodegenInput = useMemo(
    () => ({ scenario: props.scenario, baseUrl: props.baseUrl }),
    [props.scenario, props.baseUrl],
  );

  const javaCode = useMemo(() => loadToJava(input), [input]);
  const nodeCode = useMemo(() => loadToNode(input), [input]);
  const pythonCode = useMemo(() => loadToPython(input), [input]);
  const goCode = useMemo(() => loadToGo(input), [input]);
  const csharpCode = useMemo(() => loadToCsharp(input), [input]);
  const rubyCode = useMemo(() => loadToRuby(input), [input]);
  const rustCode = useMemo(() => loadToRust(input), [input]);
  const jsonCode = useMemo(() => loadToJson(input), [input]);
  const curlCode = useMemo(() => loadToCurl(input), [input]);

  const tabLabels = ['Java', 'Node.js', 'Python', 'Go', 'C#', 'Ruby', 'Rust', 'JSON', 'curl'];
  const outputs = [javaCode, nodeCode, pythonCode, goCode, csharpCode, rubyCode, rustCode, jsonCode, curlCode];
  const safeTab = Math.min(tab, tabLabels.length - 1);

  return (
    <Box sx={{ py: 1 }} data-testid="load-code-review">
      <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 0.5 }}>
        Generated Code — Register &amp; Start
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
