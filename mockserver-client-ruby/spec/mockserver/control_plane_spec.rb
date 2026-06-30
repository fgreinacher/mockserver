# frozen_string_literal: true

RSpec.describe MockServer::Client do
  let(:host) { 'localhost' }
  let(:port) { 1080 }
  let(:base_url) { "http://#{host}:#{port}" }
  let(:client) { MockServer::Client.new(host, port) }

  after { client.close }

  # -------------------------------------------------------------------
  # Metrics
  # -------------------------------------------------------------------
  describe '#retrieve_metrics' do
    it 'sends PUT /mockserver/retrieve?type=METRICS and returns the snapshot' do
      snapshot = { 'EXPECTATIONS_CREATED' => 3, 'REQUESTS_RECEIVED' => 7 }
      stub_request(:put, "#{base_url}/mockserver/retrieve")
        .with(query: { 'type' => 'METRICS' })
        .to_return(status: 200, body: JSON.generate(snapshot))

      expect(client.retrieve_metrics).to eq(snapshot)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/retrieve")
        .with(query: { 'type' => 'METRICS' })
    end

    it 'returns an empty Hash when metrics are disabled' do
      stub_request(:put, "#{base_url}/mockserver/retrieve")
        .with(query: { 'type' => 'METRICS' })
        .to_return(status: 200, body: '{}')

      expect(client.retrieve_metrics).to eq({})
    end
  end

  describe '#scrape_metrics' do
    it 'sends GET /mockserver/metrics and returns the exposition text' do
      text = "# HELP foo\nfoo 1\n"
      stub_request(:get, "#{base_url}/mockserver/metrics")
        .to_return(status: 200, body: text)

      expect(client.scrape_metrics).to eq(text)
      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/metrics")
    end

    it 'raises Error on 404 when metrics are disabled' do
      stub_request(:get, "#{base_url}/mockserver/metrics")
        .to_return(status: 404, body: '')

      expect { client.scrape_metrics }
        .to raise_error(MockServer::Error, /metricsEnabled=true/)
    end
  end

  # -------------------------------------------------------------------
  # Configuration
  # -------------------------------------------------------------------
  describe '#retrieve_configuration' do
    it 'sends GET /mockserver/configuration and returns the body' do
      body = JSON.generate({ 'logLevel' => 'INFO' })
      stub_request(:get, "#{base_url}/mockserver/configuration")
        .to_return(status: 200, body: body)

      expect(client.retrieve_configuration).to eq(body)
      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/configuration")
    end
  end

  describe '#update_configuration' do
    it 'sends PUT /mockserver/configuration with the JSON body and returns the updated config' do
      config = JSON.generate({ 'logLevel' => 'DEBUG' })
      updated = JSON.generate({ 'logLevel' => 'DEBUG', 'applied' => true })
      stub_request(:put, "#{base_url}/mockserver/configuration")
        .to_return(status: 200, body: updated)

      expect(client.update_configuration(config)).to eq(updated)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/configuration")
        .with(body: config)
    end

    it 'sends an empty body when given nil' do
      stub_request(:put, "#{base_url}/mockserver/configuration")
        .to_return(status: 200, body: '{}')

      client.update_configuration(nil)

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/configuration")
        .with(body: '')
    end

    it 'raises Error on 400 invalid configuration' do
      stub_request(:put, "#{base_url}/mockserver/configuration")
        .to_return(status: 400, body: 'Invalid configuration JSON')

      expect { client.update_configuration('not json') }
        .to raise_error(MockServer::Error, /status=400/)
    end
  end

  # -------------------------------------------------------------------
  # Pact (import / export / verify)
  # -------------------------------------------------------------------
  describe '#pact_import' do
    let(:pact_json) { JSON.generate({ 'consumer' => { 'name' => 'c' }, 'interactions' => [] }) }

    it 'sends PUT /mockserver/pact/import and returns the body' do
      created = JSON.generate([{ 'id' => 'e1' }])
      stub_request(:put, "#{base_url}/mockserver/pact/import")
        .to_return(status: 201, body: created)

      expect(client.pact_import(pact_json)).to eq(created)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/pact/import")
        .with(body: pact_json)
    end

    it 'raises ArgumentError on a blank document' do
      expect { client.pact_import('   ') }.to raise_error(ArgumentError)
      expect { client.pact_import(nil) }.to raise_error(ArgumentError)
    end
  end

  describe '#pact_export' do
    it 'sends PUT /mockserver/pact with consumer and provider query params' do
      contract = JSON.generate({ 'interactions' => [] })
      stub_request(:put, "#{base_url}/mockserver/pact")
        .with(query: { 'consumer' => 'shopping_cart', 'provider' => 'inventory' })
        .to_return(status: 200, body: contract)

      expect(client.pact_export(consumer: 'shopping_cart', provider: 'inventory')).to eq(contract)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/pact")
        .with(query: { 'consumer' => 'shopping_cart', 'provider' => 'inventory' })
    end

    it 'omits blank query params' do
      stub_request(:put, "#{base_url}/mockserver/pact")
        .to_return(status: 200, body: '{}')

      client.pact_export

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/pact")
        .with(query: {})
    end
  end

  describe '#pact_verify' do
    let(:pact_json) { JSON.generate({ 'interactions' => [] }) }

    it 'returns the report verbatim on 202 (pass)' do
      report = JSON.generate({ 'verified' => true })
      stub_request(:put, "#{base_url}/mockserver/pact/verify")
        .to_return(status: 202, body: report)

      expect(client.pact_verify(pact_json)).to eq(report)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/pact/verify")
        .with(body: pact_json)
    end

    it 'returns the report verbatim on 406 (fail) without raising' do
      report = JSON.generate({ 'verified' => false })
      stub_request(:put, "#{base_url}/mockserver/pact/verify")
        .to_return(status: 406, body: report)

      expect(client.pact_verify(pact_json)).to eq(report)
    end

    it 'raises Error on 400 bad input' do
      stub_request(:put, "#{base_url}/mockserver/pact/verify")
        .to_return(status: 400, body: 'bad')

      expect { client.pact_verify(pact_json) }
        .to raise_error(MockServer::Error, /status=400/)
    end

    it 'raises ArgumentError on a blank document' do
      expect { client.pact_verify('') }.to raise_error(ArgumentError)
    end
  end

  # -------------------------------------------------------------------
  # File store (store / retrieve / list / delete)
  # -------------------------------------------------------------------
  describe '#store_file' do
    it 'sends PUT /mockserver/files/store with name and content and returns the meta' do
      meta = { 'name' => 'a.txt', 'size' => 5 }
      stub_request(:put, "#{base_url}/mockserver/files/store")
        .to_return(status: 201, body: JSON.generate(meta))

      expect(client.store_file('a.txt', 'hello')).to eq(meta)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/files/store")
        .with { |r|
          parsed = JSON.parse(r.body)
          parsed['name'] == 'a.txt' && parsed['content'] == 'hello'
        }
    end
  end

  describe '#retrieve_file' do
    it 'sends PUT /mockserver/files/retrieve and returns the raw body' do
      stub_request(:put, "#{base_url}/mockserver/files/retrieve")
        .to_return(status: 200, body: 'raw-bytes')

      expect(client.retrieve_file('a.txt')).to eq('raw-bytes')
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/files/retrieve")
        .with { |r| JSON.parse(r.body)['name'] == 'a.txt' }
    end

    it 'raises Error on 404 (no null fallback)' do
      stub_request(:put, "#{base_url}/mockserver/files/retrieve")
        .to_return(status: 404, body: 'file not found: a.txt')

      expect { client.retrieve_file('a.txt') }
        .to raise_error(MockServer::Error, /status=404/)
    end
  end

  describe '#list_files' do
    it 'sends PUT /mockserver/files/list and returns the array of names' do
      stub_request(:put, "#{base_url}/mockserver/files/list")
        .to_return(status: 200, body: JSON.generate(%w[a.txt b.txt]))

      expect(client.list_files).to eq(%w[a.txt b.txt])
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/files/list")
    end
  end

  describe '#delete_file' do
    it 'sends PUT /mockserver/files/delete and returns nil' do
      stub_request(:put, "#{base_url}/mockserver/files/delete")
        .to_return(status: 200, body: '')

      expect(client.delete_file('a.txt')).to be_nil
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/files/delete")
        .with { |r| JSON.parse(r.body)['name'] == 'a.txt' }
    end

    it 'raises Error on 404' do
      stub_request(:put, "#{base_url}/mockserver/files/delete")
        .to_return(status: 404, body: 'file not found: a.txt')

      expect { client.delete_file('a.txt') }
        .to raise_error(MockServer::Error, /status=404/)
    end
  end

  # -------------------------------------------------------------------
  # Import (HAR / Postman)
  # -------------------------------------------------------------------
  describe '#import_har' do
    let(:har_json) { JSON.generate({ 'log' => { 'entries' => [] } }) }

    it 'sends PUT /mockserver/import?format=har and returns expectations' do
      stub_request(:put, "#{base_url}/mockserver/import")
        .with(query: { 'format' => 'har' })
        .to_return(status: 201, body: JSON.generate([{ 'httpRequest' => { 'path' => '/a' } }]))

      result = client.import_har(har_json)

      expect(result).to be_an(Array)
      expect(result.length).to eq(1)
      expect(result.first).to be_a(MockServer::Expectation)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/import")
        .with(query: { 'format' => 'har' }, body: har_json)
    end

    it 'raises ArgumentError on a blank document' do
      expect { client.import_har('') }.to raise_error(ArgumentError)
    end
  end

  describe '#import_postman_collection' do
    let(:collection_json) { JSON.generate({ 'info' => {}, 'item' => [] }) }

    it 'sends PUT /mockserver/import?format=postman and returns expectations' do
      stub_request(:put, "#{base_url}/mockserver/import")
        .with(query: { 'format' => 'postman' })
        .to_return(status: 201, body: JSON.generate([{ 'httpRequest' => { 'path' => '/b' } }]))

      result = client.import_postman_collection(collection_json)

      expect(result.length).to eq(1)
      expect(result.first).to be_a(MockServer::Expectation)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/import")
        .with(query: { 'format' => 'postman' }, body: collection_json)
    end

    it 'raises ArgumentError on a blank document' do
      expect { client.import_postman_collection(nil) }.to raise_error(ArgumentError)
    end
  end

  # -------------------------------------------------------------------
  # Operating mode (SIMULATE / SPY / CAPTURE)
  # -------------------------------------------------------------------
  describe '#set_mode' do
    it 'sends PUT /mockserver/mode?mode=SPY and returns the body' do
      body = { 'mode' => 'SPY', 'proxyUnmatchedRequests' => true }
      stub_request(:put, "#{base_url}/mockserver/mode")
        .with(query: { 'mode' => 'SPY' })
        .to_return(status: 200, body: JSON.generate(body))

      expect(client.set_mode(:spy)).to eq(body)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/mode")
        .with(query: { 'mode' => 'SPY' })
    end

    it 'upcases a String mode and accepts the constants' do
      stub_request(:put, "#{base_url}/mockserver/mode")
        .with(query: { 'mode' => 'CAPTURE' })
        .to_return(status: 200, body: '{}')

      client.set_mode(MockServer::Client::MODE_CAPTURE)

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/mode")
        .with(query: { 'mode' => 'CAPTURE' })
    end

    it 'raises Error on 400 invalid mode' do
      stub_request(:put, "#{base_url}/mockserver/mode")
        .with(query: { 'mode' => 'BOGUS' })
        .to_return(status: 400, body: 'unknown mode')

      expect { client.set_mode('bogus') }
        .to raise_error(MockServer::Error, /status=400/)
    end
  end

  describe '#retrieve_mode' do
    it 'sends GET /mockserver/mode and returns the body' do
      body = { 'mode' => 'SIMULATE', 'proxyUnmatchedRequests' => false }
      stub_request(:get, "#{base_url}/mockserver/mode")
        .to_return(status: 200, body: JSON.generate(body))

      expect(client.retrieve_mode).to eq(body)
      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/mode")
    end
  end

  # -------------------------------------------------------------------
  # WSDL -> expectations
  # -------------------------------------------------------------------
  describe '#wsdl_expectation' do
    let(:wsdl) { '<definitions xmlns="http://schemas.xmlsoap.org/wsdl/"></definitions>' }

    it 'sends PUT /mockserver/wsdl with the raw XML body and returns expectations' do
      stub_request(:put, "#{base_url}/mockserver/wsdl")
        .to_return(status: 201, body: JSON.generate([{ 'httpRequest' => { 'path' => '/ws' } }]))

      result = client.wsdl_expectation(wsdl)

      expect(result.length).to eq(1)
      expect(result.first).to be_a(MockServer::Expectation)
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/wsdl")
        .with(body: wsdl, headers: { 'Content-Type' => 'application/xml' })
    end

    it 'raises ArgumentError on a blank WSDL' do
      expect { client.wsdl_expectation('') }.to raise_error(ArgumentError)
    end

    it 'raises Error on 400 invalid WSDL' do
      stub_request(:put, "#{base_url}/mockserver/wsdl")
        .to_return(status: 400, body: 'invalid wsdl')

      expect { client.wsdl_expectation(wsdl) }
        .to raise_error(MockServer::Error, /status=400/)
    end
  end
end
