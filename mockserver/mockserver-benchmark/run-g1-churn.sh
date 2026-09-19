#!/usr/bin/env bash
#
# G1 research benchmark: candidate-index / sorted-list REBUILD cost under expectation churn.
# See CandidateIndexChurnBenchmark (JMH) and CandidateIndexChurnRebuildProof (the mechanism proof).
#
# This is a RESEARCH benchmark — it is NOT wired into any CI gate.
#
#   ./run-g1-churn.sh                 # proof + the three thread-count JMH runs (t=1,4,8)
#   ./run-g1-churn.sh proof           # just the rebuild-proof (fast, no JMH)
#   ./run-g1-churn.sh 1               # just the JMH matrix at -t 1
#
# One-time prerequisite (install core from THIS worktree so the benchmark measures this source):
#   (cd .. && mvn -o -pl mockserver-core install -DskipTests -Djacoco.skip=true)
#
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIR}"

mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
CP="target/classes:$(cat target/classpath.txt)"

run_proof() {
  echo "=== rebuild proof (must print PROOF: PASS) ==="
  java -cp "${CP}" org.mockserver.mock.CandidateIndexChurnRebuildProof
}

run_jmh() {
  local threads="$1"
  echo "=== JMH matrix at -t ${threads} (-prof gc) ==="
  java -cp "${CP}" org.openjdk.jmh.Main CandidateIndexChurnBenchmark \
    -f 1 -wi 3 -i 5 -t "${threads}" -prof gc
}

case "${1:-all}" in
  proof) run_proof ;;
  1|2|4|8) run_proof; run_jmh "$1" ;;
  all)
    run_proof
    # Run thread counts SEQUENTIALLY — concurrent JVMs would contend for cores and corrupt timings.
    for t in 1 4 8; do run_jmh "$t"; done
    ;;
  *) echo "usage: $0 [proof|1|2|4|8|all]"; exit 2 ;;
esac
