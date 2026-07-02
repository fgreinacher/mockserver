import { MockServerContainer, MockServerContainerOptions } from "../../src";

describe("MockServerContainer (unit)", () => {
  describe("constructor and options", () => {
    it("creates an instance with default options", () => {
      const container = new MockServerContainer();
      expect(container).toBeInstanceOf(MockServerContainer);
    });

    it("creates an instance with custom options", () => {
      const options: MockServerContainerOptions = {
        image: "mockserver/mockserver:latest",
        serverPort: 9090,
        env: { MOCKSERVER_LOG_LEVEL: "DEBUG" },
      };
      const container = new MockServerContainer(options);
      expect(container).toBeInstanceOf(MockServerContainer);
    });
  });

  describe("StartedMockServerContainer URL/port shaping", () => {
    // We test the URL/port shaping logic by constructing a StartedMockServerContainer
    // with a mock StartedTestContainer. This avoids needing Docker.
    let StartedMockServerContainer: typeof import("../../src/mockserver-container").StartedMockServerContainer;

    beforeAll(async () => {
      const mod = await import("../../src/mockserver-container");
      StartedMockServerContainer = mod.StartedMockServerContainer;
    });

    function createMockStartedContainer(host: string, portMapping: Record<number, number>) {
      return {
        getHost: () => host,
        getMappedPort: (port: number) => portMapping[port] ?? 0,
        stop: jest.fn().mockResolvedValue(undefined),
      } as any;
    }

    it("getHost() returns the container host", () => {
      const mock = createMockStartedContainer("localhost", { 1080: 32789 });
      const started = new StartedMockServerContainer(mock, 1080);
      expect(started.getHost()).toBe("localhost");
    });

    it("getPort() returns the mapped port for the server port", () => {
      const mock = createMockStartedContainer("localhost", { 1080: 32789 });
      const started = new StartedMockServerContainer(mock, 1080);
      expect(started.getPort()).toBe(32789);
    });

    it("getUrl() returns http://<host>:<port>", () => {
      const mock = createMockStartedContainer("localhost", { 1080: 32789 });
      const started = new StartedMockServerContainer(mock, 1080);
      expect(started.getUrl()).toBe("http://localhost:32789");
    });

    it("getEndpoint() is an alias for getUrl()", () => {
      const mock = createMockStartedContainer("localhost", { 1080: 45000 });
      const started = new StartedMockServerContainer(mock, 1080);
      expect(started.getEndpoint()).toBe(started.getUrl());
    });

    it("getSecureEndpoint() returns https://<host>:<port>", () => {
      const mock = createMockStartedContainer("localhost", { 1080: 32789 });
      const started = new StartedMockServerContainer(mock, 1080);
      expect(started.getSecureEndpoint()).toBe("https://localhost:32789");
    });

    it("supports custom server port", () => {
      const mock = createMockStartedContainer("127.0.0.1", { 9090: 55555 });
      const started = new StartedMockServerContainer(mock, 9090);
      expect(started.getPort()).toBe(55555);
      expect(started.getUrl()).toBe("http://127.0.0.1:55555");
    });

    it("getMappedPort() delegates to container", () => {
      const mock = createMockStartedContainer("localhost", { 1080: 32789, 5353: 45000 });
      const started = new StartedMockServerContainer(mock, 1080);
      expect(started.getMappedPort(5353)).toBe(45000);
    });

    it("stop() delegates to the underlying container", async () => {
      const mock = createMockStartedContainer("localhost", { 1080: 32789 });
      const started = new StartedMockServerContainer(mock, 1080);
      await started.stop();
      expect(mock.stop).toHaveBeenCalledTimes(1);
    });

    it("getClient() returns a mockserver-client wired to the mapped host/port", () => {
      const mock = createMockStartedContainer("localhost", { 1080: 32789 });
      const started = new StartedMockServerContainer(mock, 1080);
      const client = started.getClient();
      // The mockserver-client factory returns a truthy client object exposing
      // the fluent control-plane API (e.g. mockAnyResponse / reset).
      expect(client).toBeTruthy();
      expect(typeof (client as any).reset).toBe("function");
    });

    it("getClient() caches the client across calls", () => {
      const mock = createMockStartedContainer("localhost", { 1080: 32789 });
      const started = new StartedMockServerContainer(mock, 1080);
      expect(started.getClient()).toBe(started.getClient());
    });

    it("handles non-standard hosts (CI environments)", () => {
      const mock = createMockStartedContainer("docker.internal", { 1080: 8080 });
      const started = new StartedMockServerContainer(mock, 1080);
      expect(started.getUrl()).toBe("http://docker.internal:8080");
      expect(started.getSecureEndpoint()).toBe("https://docker.internal:8080");
    });
  });
});
