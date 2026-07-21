import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
// Upgrades the shared `operation:` filter operator from the built-in
// operationName-member resolver to the real GraphQL body parser. Registered
// here rather than relying on a component import: every search surface shares
// one registry, so making a app-wide operator's behaviour depend on whether
// some component happened to be pulled into the graph means a later change
// (making LogPanel lazy, moving the operation chip) would silently downgrade
// it on other panels with a fully green suite.
import { registerGraphqlOperationField } from './lib/graphqlOperation';

registerGraphqlOperationField();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
