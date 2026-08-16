import { ChildProcess } from 'child_process';

export interface StartServerOptions {
  serverPort: number;
  jvmOptions?: string[] | string;
  artifactoryHost?: string;
  artifactoryPath?: string;
  mockServerVersion?: string;
  initializationJsonPath?: string;
  trace?: boolean;
  verbose?: boolean;
  startupRetries?: number;
  javaDebugPort?: number;
  proxyRemotePort?: number;
  proxyRemoteHost?: string;
  runForked?: boolean;
}

export interface StopServerOptions {
  serverPort: number;
  verbose?: boolean;
}

/** Exit status of the launched MockServer java process once it has terminated. */
export interface MockServerExit {
  code: number | null;
  signal: NodeJS.Signals | null;
}

declare const mockserverNode: {
  start_mockserver: (options: StartServerOptions) => Promise<void>,
  stop_mockserver: (options: StopServerOptions) => Promise<void>,
  /** The launched MockServer java child process, or undefined before the first start. */
  getMockServerProcess: () => ChildProcess | undefined,
  /** The java process exit status once it has died; undefined while it is still running. */
  getMockServerExit: () => MockServerExit | undefined,
  /** The captured stdout+stderr tail (optionally limited to the last `maxLines` non-empty lines). */
  getMockServerOutput: (maxLines?: number) => string,
};

export default mockserverNode;

/** Platform detection result: os name, arch, and archive extension. */
export interface PlatformInfo {
  osName: 'linux' | 'darwin' | 'windows';
  arch: 'x86_64' | 'aarch64';
  ext: 'tar.gz' | 'zip';
}

/** Bundle base name and archive extension. */
export interface BundleMeta {
  name: string;
  ext: string;
}

/** Options for ensureBinary and runBinary. */
export interface BinaryOptions {
  /** Logging callback (default: no-op). */
  log?: (message: string) => void;
  /** Additional spawn options for runBinary. */
  spawnOptions?: object;
}

/** Map Node's platform/arch to the bundle's {os}-{arch} naming. */
export function resolvePlatform(): PlatformInfo;

/** Compute the bundle base name (without extension) for a version. */
export function bundleBaseName(version: string): BundleMeta;

/** Resolve the cache base directory. */
export function cacheDir(): string;

/** Build the download URL for an asset file within a version's release. */
export function assetUrl(version: string, file: string): string;

/**
 * Ensure the platform bundle is present, downloading + verifying + extracting
 * on first use. Returns the path to the launcher binary.
 */
export function ensureBinary(version: string, opts?: BinaryOptions): Promise<string>;

/**
 * Download (if needed) and spawn the binary with the given args.
 * Returns a Promise that resolves to the child process.
 */
export function runBinary(version: string, args?: string[], opts?: BinaryOptions): Promise<ChildProcess>;

/**
 * Remove old version directories from the cache, keeping the current version
 * and at most maxPrevious older versions.
 */
export function pruneOldVersions(base: string, currentVer: string, maxPrevious?: number): void;
