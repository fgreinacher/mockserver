# frozen_string_literal: true

# Round-trip coverage for the previously-dropped Expectation model features.
#
# Each example builds a wire-shaped Hash (camelCase keys, exactly as MockServer
# emits) and asserts that +from_hash(wire).to_h == wire+ so that no field is
# silently dropped on a load/serialise cycle. The wire field names and shapes
# mirror the server DTOs (ExpectationDTO, BodyDTO family, RateLimitDTO,
# CaptureRuleDTO, HttpForwardValidateActionDTO, HttpForwardWithFallbackDTO,
# DnsRequestDefinitionDTO, HttpLlmResponseDTO).
RSpec.describe 'Expectation round-trip completeness' do
  # Assert a symmetric from_hash -> to_h round-trip for the given class.
  def self.round_trips(klass, wire)
    it "round-trips #{klass} without dropping fields" do
      expect(klass.from_hash(wire).to_h).to eq(wire)
    end
  end

  # -------------------------------------------------------------------
  # Body sub-fields: subString / matchType / jsonSchema / xmlSchema /
  # parameters / moduleName (WASM) / optional
  # -------------------------------------------------------------------
  describe MockServer::Body do
    round_trips MockServer::Body,
                { 'type' => 'STRING', 'string' => 'abc', 'subString' => true, 'optional' => true }

    round_trips MockServer::Body,
                { 'type' => 'JSON', 'json' => '{"a":1}', 'matchType' => 'STRICT',
                  'matchNumbersAsStrings' => true }

    round_trips MockServer::Body,
                { 'type' => 'JSON_SCHEMA',
                  'jsonSchema' => '{"type":"object"}',
                  'parameterStyles' => { 'id' => 'SIMPLE' } }

    round_trips MockServer::Body,
                { 'type' => 'XML_SCHEMA', 'xmlSchema' => '<xs:schema/>' }

    round_trips MockServer::Body,
                { 'type' => 'PARAMETERS',
                  'parameters' => { 'q' => %w[one two], 'lang' => ['en'] } }

    round_trips MockServer::Body,
                { 'type' => 'WASM', 'moduleName' => 'my-matcher' }

    it 'builds a JSON_SCHEMA body via the factory' do
      body = MockServer::Body.json_schema('{"type":"object"}', parameter_styles: { 'id' => 'SIMPLE' })
      expect(body.to_h).to eq('type' => 'JSON_SCHEMA',
                              'jsonSchema' => '{"type":"object"}',
                              'parameterStyles' => { 'id' => 'SIMPLE' })
    end

    it 'builds a sub_string body via the factory' do
      expect(MockServer::Body.sub_string('needle').to_h)
        .to eq('type' => 'STRING', 'string' => 'needle', 'subString' => true)
    end

    it 'builds a WASM body via the factory' do
      expect(MockServer::Body.wasm('mod').to_h).to eq('type' => 'WASM', 'moduleName' => 'mod')
    end

    it 'deserialises a WASM body inside a request (not a raw hash)' do
      req = MockServer::HttpRequest.from_hash('body' => { 'type' => 'WASM', 'moduleName' => 'm' })
      expect(req.body).to be_a(MockServer::Body)
      expect(req.body.module_name).to eq('m')
    end

    it 'deserialises a PARAMETERS body inside a request (not a raw hash)' do
      req = MockServer::HttpRequest.from_hash(
        'body' => { 'type' => 'PARAMETERS', 'parameters' => { 'q' => ['x'] } }
      )
      expect(req.body).to be_a(MockServer::Body)
      expect(req.body.parameters).to eq('q' => ['x'])
    end
  end

  # -------------------------------------------------------------------
  # DNS request matcher (dnsName / dnsType / dnsClass on httpRequest)
  # -------------------------------------------------------------------
  describe 'DNS request matcher' do
    round_trips MockServer::HttpRequest,
                { 'dnsName' => 'example.com', 'dnsType' => 'A', 'dnsClass' => 'IN' }

    it 'preserves the DNS matcher through a full Expectation round-trip' do
      wire = {
        'httpRequest' => { 'dnsName' => 'example.com', 'dnsType' => 'AAAA' },
        'dnsResponse' => { 'responseCode' => 'NOERROR' }
      }
      expect(MockServer::Expectation.from_hash(wire).to_h).to eq(wire)
    end

    it 'builds a DNS request matcher via the factory' do
      expect(MockServer::HttpRequest.dns_request('example.com', dns_type: 'A').to_h)
        .to eq('dnsName' => 'example.com', 'dnsType' => 'A')
    end
  end

  # -------------------------------------------------------------------
  # RateLimit
  # -------------------------------------------------------------------
  describe MockServer::RateLimit do
    round_trips MockServer::RateLimit,
                { 'name' => 'api', 'algorithm' => 'fixed_window', 'limit' => 100,
                  'windowMillis' => 1000, 'errorStatus' => 429, 'retryAfter' => '5' }

    round_trips MockServer::RateLimit,
                { 'algorithm' => 'token_bucket', 'burst' => 20, 'refillPerSecond' => 2.5 }

    it 'exposes fixed_window / token_bucket factories' do
      expect(MockServer::RateLimit.fixed_window(100, 1000).to_h)
        .to eq('algorithm' => 'fixed_window', 'limit' => 100, 'windowMillis' => 1000)
      expect(MockServer::RateLimit.token_bucket(20, 2.5, name: 'b').to_h)
        .to eq('name' => 'b', 'algorithm' => 'token_bucket', 'burst' => 20, 'refillPerSecond' => 2.5)
    end
  end

  # -------------------------------------------------------------------
  # CaptureRule (expectation-level capture list)
  # -------------------------------------------------------------------
  describe MockServer::CaptureRule do
    round_trips MockServer::CaptureRule,
                { 'source' => 'jsonPath', 'expression' => '$.id', 'into' => 'orderId' }

    it 'round-trips a capture list on an Expectation' do
      wire = {
        'httpRequest' => { 'path' => '/orders' },
        'capture' => [
          { 'source' => 'jsonPath', 'expression' => '$.id', 'into' => 'orderId' },
          { 'source' => 'header', 'expression' => 'X-Trace', 'into' => 'trace' }
        ]
      }
      expect(MockServer::Expectation.from_hash(wire).to_h).to eq(wire)
    end
  end

  # -------------------------------------------------------------------
  # HttpForwardValidateAction
  # -------------------------------------------------------------------
  describe MockServer::HttpForwardValidateAction do
    round_trips MockServer::HttpForwardValidateAction,
                { 'specUrlOrPayload' => 'https://example.com/openapi.json',
                  'host' => 'upstream', 'port' => 8080, 'scheme' => 'HTTPS',
                  'validateRequest' => true, 'validateResponse' => false,
                  'validationMode' => 'LOG_ONLY', 'primary' => true,
                  'delay' => { 'timeUnit' => 'MILLISECONDS', 'value' => 10 } }
  end

  # -------------------------------------------------------------------
  # HttpForwardWithFallback
  # -------------------------------------------------------------------
  describe MockServer::HttpForwardWithFallback do
    round_trips MockServer::HttpForwardWithFallback,
                { 'httpForward' => { 'host' => 'upstream', 'port' => 443, 'scheme' => 'HTTPS' },
                  'fallbackResponse' => { 'statusCode' => 503, 'body' => 'unavailable' },
                  'fallbackOnStatusCodes' => [500, 502, 503],
                  'fallbackOnTimeout' => true }
  end

  # -------------------------------------------------------------------
  # httpLlmResponse passthrough (carried verbatim as the wire Hash)
  # -------------------------------------------------------------------
  describe 'httpLlmResponse' do
    it 'round-trips a complete httpLlmResponse without dropping nested fields' do
      wire = {
        'httpRequest' => { 'path' => '/v1/chat/completions' },
        'httpLlmResponse' => {
          'provider' => 'OPENAI',
          'model' => 'gpt-4o',
          'completion' => {
            'text' => 'Hello!',
            'finishReason' => 'stop',
            'usage' => { 'promptTokens' => 5, 'completionTokens' => 2, 'totalTokens' => 7 },
            'toolUses' => [{ 'id' => 'call_1', 'name' => 'lookup', 'arguments' => { 'q' => 'x' } }],
            'streamingPhysics' => { 'tokensPerSecond' => 40, 'timeToFirstTokenMillis' => 120 }
          },
          'chaos' => { 'errorProbability' => 0.1 }
        }
      }
      round = MockServer::Expectation.from_hash(wire)
      expect(round.http_llm_response).to eq(wire['httpLlmResponse'])
      expect(round.to_h).to eq(wire)
    end

    it 'accepts an httpLlmResponse Hash produced by the LLM builder module' do
      llm = MockServer::LLM.build_llm_response(
        MockServer::LLM::Provider::OPENAI, 'gpt-4o',
        MockServer::LLM.completion.with_text('Hi'), nil, nil, nil
      )
      exp = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/v1/chat/completions'),
        http_llm_response: llm
      )
      expect(exp.to_h['httpLlmResponse']).to eq(llm)
      expect(MockServer::Expectation.from_hash(exp.to_h).to_h).to eq(exp.to_h)
    end
  end

  # -------------------------------------------------------------------
  # namespace
  # -------------------------------------------------------------------
  describe 'namespace' do
    it 'round-trips the namespace field' do
      wire = { 'httpRequest' => { 'path' => '/x' }, 'namespace' => 'tenant-a',
               'httpResponse' => { 'statusCode' => 200 } }
      expect(MockServer::Expectation.from_hash(wire).to_h).to eq(wire)
    end
  end

  # -------------------------------------------------------------------
  # Kitchen sink: every newly-modelled feature on a single Expectation
  # -------------------------------------------------------------------
  describe 'kitchen sink' do
    it 'round-trips an Expectation exercising all added features at once' do
      wire = {
        'id' => 'exp-1',
        'priority' => 10,
        'percentage' => 50,
        'namespace' => 'tenant-a',
        'httpRequest' => {
          'method' => 'POST',
          'path' => '/orders',
          'body' => { 'type' => 'JSON_SCHEMA', 'jsonSchema' => '{"type":"object"}',
                      'parameterStyles' => { 'id' => 'SIMPLE' }, 'optional' => true }
        },
        'httpForwardWithFallback' => {
          'httpForward' => { 'host' => 'upstream', 'port' => 443, 'scheme' => 'HTTPS' },
          'fallbackResponse' => { 'statusCode' => 503 },
          'fallbackOnStatusCodes' => [500, 502],
          'fallbackOnTimeout' => true
        },
        'httpForwardValidateAction' => {
          'specUrlOrPayload' => 'https://example.com/openapi.json',
          'host' => 'upstream', 'port' => 8080, 'scheme' => 'HTTP',
          'validateRequest' => true, 'validateResponse' => true, 'validationMode' => 'STRICT'
        },
        'rateLimit' => { 'name' => 'orders', 'algorithm' => 'fixed_window',
                         'limit' => 100, 'windowMillis' => 60_000, 'errorStatus' => 429 },
        'capture' => [{ 'source' => 'jsonPath', 'expression' => '$.id', 'into' => 'orderId' }],
        'httpLlmResponse' => { 'provider' => 'ANTHROPIC', 'model' => 'claude-3',
                               'completion' => { 'text' => 'ok' } },
        'times' => { 'remainingTimes' => 3, 'unlimited' => false },
        'timeToLive' => { 'timeUnit' => 'SECONDS', 'timeToLive' => 60, 'unlimited' => false }
      }
      expect(MockServer::Expectation.from_hash(wire).to_h).to eq(wire)
    end

    it 'round-trips a DNS-matcher Expectation with a rate limit and capture' do
      wire = {
        'httpRequest' => { 'dnsName' => 'db.internal', 'dnsType' => 'SRV', 'dnsClass' => 'IN' },
        'dnsResponse' => { 'responseCode' => 'NOERROR',
                           'answerRecords' => [{ 'name' => 'db.internal', 'type' => 'A',
                                                 'value' => '10.0.0.1' }] },
        'rateLimit' => { 'algorithm' => 'token_bucket', 'burst' => 5, 'refillPerSecond' => 1.0 },
        'capture' => [{ 'source' => 'header', 'expression' => 'X-Trace', 'into' => 'trace' }]
      }
      expect(MockServer::Expectation.from_hash(wire).to_h).to eq(wire)
    end
  end
end
