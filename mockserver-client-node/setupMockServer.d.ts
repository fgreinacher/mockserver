/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

import { MockServerClient } from './mockServerClient';

export interface SetupMockServerOptions {
  /** Port to start MockServer on (default: 1080). */
  serverPort?: number;
  /** Host the returned client connects to (default: 'localhost'). */
  host?: string;
  /** Verbose launcher logging (default: false). */
  verbose?: boolean;
  /** Optional client context path. */
  contextPath?: string;
  /** Connect the client over TLS. */
  tls?: boolean;
  /** CA certificate PEM file path for the TLS client. */
  caCertPemFilePath?: string;
  /** Optional MockServer initialization JSON path (passed to mockserver-node). */
  initializationJsonPath?: string;
  /** Additional JVM options (passed to mockserver-node). */
  jvmOptions?: string[] | string;
  /** Enable trace logging (passed to mockserver-node). */
  trace?: boolean;
  /** MockServer version to download/launch (passed to mockserver-node). */
  mockServerVersion?: string;
  /** Number of startup retries (passed to mockserver-node). */
  startupRetries?: number;
  /** Any other option is passed straight through to mockserver-node.start_mockserver. */
  [key: string]: unknown;
}

export interface MockServerHandle {
  /** A ready MockServerClient connected to the started server. */
  client: MockServerClient;
  /** The host the server/client use. */
  host: string;
  /** The port the server is listening on. */
  serverPort: number;
  /** Stop the server. Idempotent - safe to call more than once. */
  stop: () => Promise<void>;
}

/**
 * Start MockServer (via mockserver-node) and return a ready client plus a
 * teardown function. Usable from jest, vitest, mocha, node:test, or scripts.
 */
export function setupMockServer(options?: SetupMockServerOptions): Promise<MockServerHandle>;
