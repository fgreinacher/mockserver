# mockserver-ui 

> A dashboard to view the expectations, requests, and logs in [MockServer](https://mock-server.com/)

[![Build status](https://badge.buildkite.com/a1d7b386b768855f167d5104bc4e71cd6176e84af4faf09024.svg?style=square&theme=slack)](https://buildkite.com/mockserver/mockserver-ui)

# Community

* Roadmap:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/orgs/mock-server/projects/1"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Project"></a>
* Feature Requests:&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Github Issues"></a>
* Issues / Bugs:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Github Issues"></a>
* Discussions:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/discussions"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Discussions"></a>

## Getting Started

> **Node version:** build and develop with **Node 22** (CI and the Maven `build-ui` profile both use v22.14.0; see `.nvmrc` and `package.json` `engines`). With [nvm](https://github.com/nvm-sh/nvm) just run `nvm use` in this directory. Newer majors (e.g. Node 26 from Homebrew) have historically broken the Vite/rolldown build with a cryptic `@mui/x-charts` `MISSING_EXPORT` error — pin to Node 22 to avoid it.

This node module is built using [Vite](https://vitejs.dev/) and TypeScript. It is not intended to be used standalone (except for development) and is bundled into [MockServer](https://mock-server.com/) on path `/mockserver/dashboard`, for example:
```
https://localhost:1080/mockserver/dashboard
```

For development this node module can be run using `npm start` and can be pointed at a running version of [MockServer](https://mock-server.com/) using `host`, `port` and `context` query parameters as required, for example: 
```
http://localhost:3000/?host=localhost&port=1080&context=
```

To run locally:
```bash
# 1. run node 
npm start
# 2. navigate to UI
open http://localhost:3000/?port=1080
```

## Demo data (manual UI testing)

To exercise every dashboard view against a richly populated server, run:
```bash
npm run demo
```
This builds (if needed) and launches the MockServer backend, loads a varied demo
dataset, starts the UI dev server, and opens the dashboard. The dataset covers
plain HTTP expectations (varied verbs / status / bodies / headers / query /
cookies / delay / times / priority), a forward, LLM response mocks for every
provider (Anthropic, OpenAI, OpenAI Responses, Gemini, Ollama) including tool
calls, streaming and a chaos profile, multi-turn agent loops that group into
**Sessions** with call graphs, token/cost usage, and a conversation expectation
exercising every predicate pill. Press Ctrl+C to stop both servers.

Options: `--rebuild`, `--no-browser`, `--port <n>`, `--ui-port <n>`.

To (re-)load the data into an already-running MockServer:
```bash
npm run demo:data            # defaults to http://localhost:1080
npm run demo:data -- --url http://localhost:9090
```
The populate logic lives in [`scripts/populate-demo-data.mjs`](scripts/populate-demo-data.mjs)
and the launcher in [`scripts/launch-with-demo-data.sh`](scripts/launch-with-demo-data.sh).

## Contributing
In lieu of a formal styleguide, take care to maintain the existing coding style. Add unit tests for any new or changed functionality.
