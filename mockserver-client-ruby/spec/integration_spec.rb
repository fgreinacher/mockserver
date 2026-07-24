# frozen_string_literal: true

require 'mockserver-client'
require 'net/http'
require 'json'
require 'socket'
require 'timeout'

MOCKSERVER_STATE = { host: nil, port: nil, container_id: nil, external: false }

def start_mockserver_container
  if ENV['MOCKSERVER_HOST'] && ENV['MOCKSERVER_PORT']
    MOCKSERVER_STATE[:host] = ENV['MOCKSERVER_HOST']
    MOCKSERVER_STATE[:port] = ENV['MOCKSERVER_PORT'].to_i
    MOCKSERVER_STATE[:external] = true

    deadline = Time.now + 30
    loop do
      uri = URI("http://#{MOCKSERVER_STATE[:host]}:#{MOCKSERVER_STATE[:port]}/mockserver/status")
      req = Net::HTTP::Put.new(uri)
      resp = Net::HTTP.start(uri.hostname, uri.port) { |http| http.request(req) }
      break if resp.code == '200'
    rescue StandardError
      raise "MockServer did not start within 30s" if Time.now > deadline

      sleep 0.5
      retry
    end
    return
  end

  server = TCPServer.new('127.0.0.1', 0)
  port = server.addr[1]
  server.close

  MOCKSERVER_STATE[:port] = port
  MOCKSERVER_STATE[:host] = 'localhost'
  image = ENV.fetch('MOCKSERVER_IMAGE', 'mockserver/mockserver:snapshot')
  container_name = "mockserver-ruby-integration-#{port}"
  output = `docker run -d --name #{container_name} -p #{port}:1080 -e MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION=false #{image} 2>&1`
  raise "Failed to start container: #{output}" unless $?.success?

  MOCKSERVER_STATE[:container_id] = output.strip

  deadline = Time.now + 30
  loop do
    uri = URI("http://localhost:#{port}/mockserver/status")
    req = Net::HTTP::Put.new(uri)
    resp = Net::HTTP.start(uri.hostname, uri.port) { |http| http.request(req) }
    break if resp.code == '200'
  rescue StandardError
    raise "MockServer did not start within 30s" if Time.now > deadline

    sleep 0.5
    retry
  end
end

def stop_mockserver_container
  return if MOCKSERVER_STATE[:external]

  cid = MOCKSERVER_STATE[:container_id]
  system("docker rm -f #{cid} >/dev/null 2>&1") if cid
end

RSpec.describe 'Integration', :integration do
  before(:context) do
    unless ENV['MOCKSERVER_HOST']
      skip 'Docker not available' unless system('docker', 'info', out: File::NULL, err: File::NULL)
    end
    start_mockserver_container
  end

  after(:context) do
    stop_mockserver_container
  end

  let(:host) { MOCKSERVER_STATE[:host] }
  let(:port) { MOCKSERVER_STATE[:port] }
  let(:client) { MockServer::Client.new(host, port) }

  before do
    client.reset
  end

  after do
    client.close
  end

  def make_request(host, port, method, path, body: nil, headers: {})
    uri = URI("http://#{host}:#{port}#{path}")
    klass = case method.upcase
            when 'GET'    then Net::HTTP::Get
            when 'POST'   then Net::HTTP::Post
            when 'PUT'    then Net::HTTP::Put
            when 'DELETE' then Net::HTTP::Delete
            else raise "Unknown method: #{method}"
            end
    req = klass.new(uri)
    req.body = body if body
    headers.each { |k, v| req[k] = v }
    Net::HTTP.start(uri.hostname, uri.port) { |http| http.request(req) }
  end

  describe 'connection' do
    it 'reports server has started' do
      expect(client.has_started?).to be true
    end

    it 'works with block form' do
      MockServer::Client.new(host, port) do |c|
        expect(c.has_started?).to be true
      end
    end
  end

  describe 'expectation lifecycle' do
    it 'creates and retrieves expectations' do
      expectation = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.request(path: '/test'),
        http_response: MockServer::HttpResponse.response(body: 'hello', status_code: 200),
        times: MockServer::Times.unlimited
      )
      result = client.upsert(expectation)
      expect(result.length).to eq(1)
      expect(result[0].http_request.path).to eq('/test')

      active = client.retrieve_active_expectations
      expect(active.any? { |e| e.http_request.path == '/test' }).to be true
    end

    it 'clears expectations' do
      client.upsert(MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.request(path: '/to-clear'),
        http_response: MockServer::HttpResponse.response(body: 'gone')
      ))
      client.clear(MockServer::HttpRequest.request(path: '/to-clear'))
      active = client.retrieve_active_expectations
      expect(active.none? { |e| e.http_request.path == '/to-clear' }).to be true
    end

    it 'resets all state' do
      client.upsert(MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.request(path: '/before-reset'),
        http_response: MockServer::HttpResponse.response
      ))
      client.reset
      expect(client.retrieve_active_expectations).to be_empty
    end
  end

  describe 'request matching' do
    it 'matches simple GET' do
      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/api/hello')
      ).respond(
        MockServer::HttpResponse.response(body: 'world', status_code: 200)
      )

      resp = make_request(host, port, 'GET', '/api/hello')
      expect(resp.code).to eq('200')
      expect(resp.body).to eq('world')
    end

    it 'matches POST with JSON body' do
      client.when(
        MockServer::HttpRequest.new(
          method: 'POST',
          path: '/api/data',
          body: MockServer::Body.json({ key: 'value' })
        )
      ).respond(
        MockServer::HttpResponse.response(body: '{"result":"created"}', status_code: 201)
      )

      resp = make_request(host, port, 'POST', '/api/data',
                          body: '{"key":"value"}',
                          headers: { 'Content-Type' => 'application/json' })
      expect(resp.code).to eq('201')
      expect(JSON.parse(resp.body)['result']).to eq('created')
    end

    it 'returns 404 for unmatched requests' do
      resp = make_request(host, port, 'GET', '/no-such-path')
      expect(resp.code).to eq('404')
    end

    it 'returns custom status codes' do
      client.when(
        MockServer::HttpRequest.new(method: 'DELETE', path: '/api/resource')
      ).respond(
        MockServer::HttpResponse.response(status_code: 204)
      )

      resp = make_request(host, port, 'DELETE', '/api/resource')
      expect(resp.code).to eq('204')
    end

    it 'returns custom response headers' do
      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/with-headers')
      ).respond(
        MockServer::HttpResponse.new(
          status_code: 200,
          body: 'ok',
          headers: [MockServer::KeyToMultiValue.new(name: 'X-Custom', values: ['test-value'])]
        )
      )

      resp = make_request(host, port, 'GET', '/with-headers')
      expect(resp['X-Custom']).to eq('test-value')
    end

    it 'honours Times.exactly' do
      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/once-only'),
        times: MockServer::Times.exactly(1)
      ).respond(
        MockServer::HttpResponse.response(body: 'first', status_code: 200)
      )

      resp1 = make_request(host, port, 'GET', '/once-only')
      expect(resp1.code).to eq('200')
      expect(resp1.body).to eq('first')

      resp2 = make_request(host, port, 'GET', '/once-only')
      expect(resp2.code).to eq('404')
    end
  end

  describe 'verification' do
    it 'verifies a request was received' do
      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/verify-me')
      ).respond(
        MockServer::HttpResponse.response(status_code: 200)
      )

      make_request(host, port, 'GET', '/verify-me')

      expect {
        client.verify(
          MockServer::HttpRequest.new(method: 'GET', path: '/verify-me'),
          times: MockServer::VerificationTimes.exactly(1)
        )
      }.not_to raise_error
    end

    it 'raises on verification failure' do
      expect {
        client.verify(
          MockServer::HttpRequest.new(method: 'GET', path: '/never-called'),
          times: MockServer::VerificationTimes.exactly(1)
        )
      }.to raise_error(MockServer::VerificationError)
    end

    it 'verifies zero interactions' do
      expect { client.verify_zero_interactions }.not_to raise_error
    end

    it 'verifies multiple calls' do
      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/multi')
      ).respond(
        MockServer::HttpResponse.response(status_code: 200)
      )

      3.times { make_request(host, port, 'GET', '/multi') }

      expect {
        client.verify(
          MockServer::HttpRequest.new(method: 'GET', path: '/multi'),
          times: MockServer::VerificationTimes.exactly(3)
        )
      }.not_to raise_error
    end

    it 'verifies request sequence' do
      client.when(
        MockServer::HttpRequest.new(path: '/seq')
      ).respond(
        MockServer::HttpResponse.response(status_code: 200)
      )

      make_request(host, port, 'GET', '/seq')
      make_request(host, port, 'POST', '/seq')

      expect {
        client.verify_sequence(
          MockServer::HttpRequest.new(method: 'GET', path: '/seq'),
          MockServer::HttpRequest.new(method: 'POST', path: '/seq')
        )
      }.not_to raise_error
    end
  end

  describe 'retrieval' do
    it 'retrieves recorded requests' do
      make_request(host, port, 'GET', '/record-me')

      requests = client.retrieve_recorded_requests(request: MockServer::HttpRequest.request(path: '/record-me'))
      expect(requests.length).to be >= 1
      expect(requests[0].path).to eq('/record-me')
      expect(requests[0].method).to eq('GET')
    end

    it 'retrieves log messages' do
      make_request(host, port, 'GET', '/log-test')

      logs = client.retrieve_log_messages
      expect(logs.length).to be > 0
    end

    it 'retrieves requests and responses' do
      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/req-resp')
      ).respond(
        MockServer::HttpResponse.response(body: 'matched', status_code: 200)
      )

      make_request(host, port, 'GET', '/req-resp')

      pairs = client.retrieve_recorded_requests_and_responses(
        request: MockServer::HttpRequest.request(path: '/req-resp')
      )
      expect(pairs.length).to be >= 1
      expect(pairs[0].http_request.path).to eq('/req-resp')
      expect(pairs[0].http_response.status_code).to eq(200)
    end
  end

  describe 'fluent API' do
    it 'supports with_id' do
      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/fluent-id')
      ).with_id('my-expectation-id').respond(
        MockServer::HttpResponse.response(body: 'fluent', status_code: 200)
      )

      resp = make_request(host, port, 'GET', '/fluent-id')
      expect(resp.code).to eq('200')
      expect(resp.body).to eq('fluent')
    end

    it 'supports priority ordering' do
      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/priority-test')
      ).with_priority(10).respond(
        MockServer::HttpResponse.response(body: 'high-priority', status_code: 200)
      )

      client.when(
        MockServer::HttpRequest.new(method: 'GET', path: '/priority-test')
      ).with_priority(1).respond(
        MockServer::HttpResponse.response(body: 'low-priority', status_code: 200)
      )

      resp = make_request(host, port, 'GET', '/priority-test')
      expect(resp.code).to eq('200')
      expect(resp.body).to eq('high-priority')
    end
  end

  # Live Server-Sent Events (SSE) streaming: register an `httpSseResponse`
  # expectation via the Ruby client, then open a real streaming HTTP consumer
  # over the socket and assert that every `data:` frame arrives and that the
  # reconstructed message matches. This exercises the wire path end-to-end
  # (the build-time a2a_spec only asserts the JSON keys of the built
  # expectation and never consumes a live stream).
  describe 'SSE streaming' do
    # Open a streaming HTTP GET and buffer the raw response body until the
    # server closes the connection (the expectation sets closeConnection:true).
    # Returns [content_type, raw_body]. Guarded by a timeout so a hung stream
    # fails loudly rather than blocking the suite forever.
    def consume_sse(host, port, path, timeout: 20)
      uri = URI("http://#{host}:#{port}#{path}")
      buffer = +''
      content_type = nil
      Timeout.timeout(timeout) do
        Net::HTTP.start(uri.hostname, uri.port) do |http|
          req = Net::HTTP::Get.new(uri)
          req['Accept'] = 'text/event-stream'
          http.request(req) do |response|
            content_type = response['Content-Type']
            response.read_body { |chunk| buffer << chunk }
          end
        end
      end
      [content_type, buffer]
    end

    # Parse a raw SSE body into an array of { event:, data: } frames. Frames are
    # separated by a blank line; a frame's `data:` value is the (possibly
    # multi-line) payload rejoined with "\n" per the WHATWG EventSource spec.
    def parse_sse(raw)
      raw.split(/\r?\n\r?\n/).reject(&:empty?).map do |frame|
        event = nil
        data_lines = []
        frame.each_line do |line|
          line = line.chomp
          if line.start_with?('event:')
            event = line.delete_prefix('event:').strip
          elsif line.start_with?('data:')
            data_lines << line.delete_prefix('data:').sub(/\A /, '')
          end
        end
        { event: event, data: data_lines.join("\n") }
      end
    end

    it 'streams SSE events that a live consumer receives frame by frame' do
      # The three deltas reconstruct into a single message; a terminal [DONE]
      # frame marks the end of the stream (the OpenAI/LLM streaming convention).
      client.upsert(
        'httpRequest' => { 'method' => 'GET', 'path' => '/live-sse' },
        'httpSseResponse' => {
          'statusCode' => 200,
          'closeConnection' => true,
          'events' => [
            { 'event' => 'message', 'data' => '{"delta": "Hello, "}' },
            { 'event' => 'message', 'data' => '{"delta": "streaming "}' },
            { 'event' => 'message', 'data' => '{"delta": "world!"}' },
            { 'event' => 'done', 'data' => '[DONE]' }
          ]
        }
      )

      content_type, raw = consume_sse(host, port, '/live-sse')

      expect(content_type).to include('text/event-stream')

      frames = parse_sse(raw)
      # Every data: frame must arrive: three message deltas plus the terminal.
      expect(frames.length).to eq(4)
      expect(frames.map { |f| f[:event] }).to eq(%w[message message message done])
      expect(frames.last[:data]).to eq('[DONE]')

      reconstructed = frames
                      .select { |f| f[:event] == 'message' }
                      .map { |f| JSON.parse(f[:data])['delta'] }
                      .join
      expect(reconstructed).to eq('Hello, streaming world!')
    end

    it 'preserves multi-line SSE data payloads across the wire' do
      # A single event whose data spans multiple lines must be emitted as
      # multiple `data:` lines by the server and rejoined with "\n" by the
      # consumer — proving the framing is not flattened or truncated.
      client.upsert(
        'httpRequest' => { 'method' => 'GET', 'path' => '/live-sse-multiline' },
        'httpSseResponse' => {
          'statusCode' => 200,
          'closeConnection' => true,
          'events' => [
            { 'event' => 'chunk', 'data' => "line-one\nline-two\nline-three" }
          ]
        }
      )

      _content_type, raw = consume_sse(host, port, '/live-sse-multiline')
      frames = parse_sse(raw)

      expect(frames.length).to eq(1)
      expect(frames.first[:event]).to eq('chunk')
      expect(frames.first[:data]).to eq("line-one\nline-two\nline-three")
    end
  end
end
