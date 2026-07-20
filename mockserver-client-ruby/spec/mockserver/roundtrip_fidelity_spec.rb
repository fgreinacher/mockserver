# frozen_string_literal: true

require 'json'

# Cross-language JSON round-trip fidelity harness (Ruby port).
#
# For every fixture in repo-root test-fixtures/expectations/*.json (excluding the
# known-gaps.json manifest) this deserializes with the client model
# (MockServer::Expectation.from_hash) and re-serializes (#to_h), then asserts the
# canonicalized/normalized input and output are semantically equal modulo the
# known-gaps ledger for the "ruby" language.
#
# The comparator (NORM + CANON + DIFFS + EXCUSED) is an exact port of the shared
# reference in .tmp/reference_compare.py / .tmp/roundtrip-spec.md; every client
# language must produce identical diff paths for the same model behaviour.
module RoundtripComparator
  module_function

  LANG = 'ruby'

  # keyToMultiValue fields: object form and [{name,values|value}] array form.
  MULTI_KEYS = %w[headers queryStringParameters trailers].freeze
  # keyToValue fields: object form and [{name,value}] array form.
  SINGLE_KEYS = %w[cookies].freeze

  REPO_ROOT = File.expand_path('../../..', __dir__)
  FIXTURE_DIR = File.join(REPO_ROOT, 'test-fixtures', 'expectations')

  # ---- CANON helpers -------------------------------------------------------

  def canon_multi(value)
    out = {}
    if value.is_a?(Hash)
      value.each { |k, v| out[k] = v.is_a?(Array) ? v : [v] }
    elsif value.is_a?(Array)
      value.each do |e|
        next unless e.is_a?(Hash) && e.key?('name')

        vals = e.key?('values') ? e['values'] : e['value']
        out[e['name']] = vals.is_a?(Array) ? vals : [vals]
      end
    end
    out
  end

  def canon_single(value)
    out = {}
    if value.is_a?(Hash)
      out = value.dup
    elsif value.is_a?(Array)
      value.each do |e|
        out[e['name']] = e['value'] if e.is_a?(Hash) && e.key?('name')
      end
    end
    out
  end

  # NORM (null==absent) + CANON (dual-encoding) in one pass, keyed by parent key.
  def norm(value, key = nil)
    return nil if value.nil?

    if MULTI_KEYS.include?(key)
      return canon_multi(value).transform_values { |vs| vs.map { |x| norm(x) } }
    end
    if SINGLE_KEYS.include?(key)
      return canon_single(value).transform_values { |x| norm(x) }
    end
    if value.is_a?(Hash)
      out = {}
      value.each { |k, v| out[k] = norm(v, k) unless v.nil? }
      return out
    end
    return value.map { |x| norm(x) } if value.is_a?(Array)

    value
  end

  # DIFFS(a, b) -> array of dotted path strings (a = input, b = output).
  def diffs(a, b, path = '')
    res = []
    if a.is_a?(Hash)
      return [path.empty? ? '<root>' : path] unless b.is_a?(Hash)

      a.each do |k, v|
        p = path.empty? ? k : "#{path}.#{k}"
        if b.key?(k)
          res.concat(diffs(v, b[k], p))
        else
          res << p
        end
      end
      b.each_key do |k|
        next if a.key?(k)

        res << "#{path.empty? ? k : "#{path}.#{k}"} [ADDED]"
      end
      return res
    end
    if a.is_a?(Array)
      return [path.empty? ? '<root>' : path] unless b.is_a?(Array)

      a.each_with_index do |v, i|
        p = "#{path}.#{i}"
        if i >= b.length
          res << p
        else
          res.concat(diffs(v, b[i], p))
        end
      end
      return res
    end
    res << (path.empty? ? '<root>' : path) if a != b
    res
  end

  # Replace numeric path segments with '*' (fixture-length-independent manifest).
  def star(path)
    path.split('.').map { |s| s.match?(/\A\d+\z/) ? '*' : s }.join('.')
  end

  # EXCUSED(path, entries): entry G excuses path P iff len(G) <= len(P) and each
  # G segment equals the P segment, or is '*' matching an all-digit P segment.
  def excused?(path, entries)
    ps = path.split('.')
    entries.any? do |g|
      gs = g.split('.')
      next false if gs.length > ps.length

      gs.each_with_index.all? do |seg, i|
        seg == ps[i] || (seg == '*' && ps[i].match?(/\A\d+\z/))
      end
    end
  end

  # ---- fixtures + manifest -------------------------------------------------

  def fixtures
    Dir.glob(File.join(FIXTURE_DIR, '*.json'))
       .reject { |f| File.basename(f) == 'known-gaps.json' }
       .sort
  end

  def load_gaps
    manifest_path = ENV['FIDELITY_KNOWN_GAPS']
    manifest_path = File.join(FIXTURE_DIR, 'known-gaps.json') if manifest_path.nil? || manifest_path.empty?
    parsed = JSON.parse(File.read(manifest_path))
    (parsed[LANG] || []).reject { |e| e.is_a?(String) && e.start_with?('_') }
  end

  # Bare diff paths (with the ` [ADDED]` marker stripped) for one fixture file.
  def bare_diffs(file)
    data = JSON.parse(File.read(file))
    rt = MockServer::Expectation.from_hash(data).to_h
    diffs(norm(data), norm(rt)).map { |p| p.sub(/ \[ADDED\]\z/, '') }
  end
end

RSpec.describe 'JSON round-trip fidelity' do
  C = RoundtripComparator
  FIXTURES = C.fixtures.freeze
  GAPS = C.load_gaps.freeze

  it 'discovers the full fixture set (sanity check)' do
    # lower bound, not an exact count: the shared corpus grows whenever a server feature gains a
    # probe, and an exact count turns every such addition into an unrelated failure in every client
    expect(FIXTURES.length).to be >= 44
  end

  if ENV['FIDELITY_DISCOVER'] == '1'
    it 'FIDELITY_DISCOVER: prints the sorted union of `*`-normalized diff paths' do
      union = FIXTURES.each_with_object([]) do |file, acc|
        C.bare_diffs(file).each { |p| acc << C.star(p) }
      end.uniq.sort
      puts "\n===== FIDELITY_DISCOVER (#{C::LANG}) ====="
      puts JSON.pretty_generate(union)
      puts '===== END FIDELITY_DISCOVER ====='
      expect(true).to be(true)
    end
  else
    FIXTURES.each do |file|
      name = File.basename(file)
      it "round-trips #{name} with no unexcused diffs" do
        unexcused = C.bare_diffs(file).reject { |p| C.excused?(p, GAPS) }
        expect(unexcused).to be_empty,
                             -> { "Unexcused round-trip diffs in #{name}:\n  #{unexcused.sort.join("\n  ")}" }
      end
    end

    it 'ratchet: every known-gaps["ruby"] entry excuses at least one diff' do
      all_bare = FIXTURES.flat_map { |file| C.bare_diffs(file) }
      stale = GAPS.reject { |g| all_bare.any? { |p| C.excused?(p, [g]) } }
      expect(stale).to be_empty,
                       -> { "Stale known-gaps[\"#{C::LANG}\"] entries (excuse nothing; remove them):\n  #{stale.join("\n  ")}" }
    end
  end
end
