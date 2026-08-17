#!/usr/bin/env bash

set -e

export MAVEN_OPTS="$MAVEN_OPTS -Xmx2048m"
export JAVA_OPTS="$JAVA_OPTS -Xmx2048m"

cd mockserver

function printModule {
    echo
    printf -v str "%-$((${#1} + 8))s" ' '; echo "${str// /=}"
    echo "Module: $1"
    printf -v str "%-$((${#1} + 8))s" ' '; echo "${str// /=}"
    echo
}

function runSubModule {
    printModule "$1"
    ./mvnw install -pl "$1" -Dmaven-invoker-parallel-threads=2 -Djava.security.egd=file:/dev/./urandom
}

MODULE_LIST="mockserver-testing mockserver-core mockserver-client-java mockserver-integration-testing mockserver-netty mockserver-war mockserver-proxy-war mockserver-junit-rule mockserver-junit-jupiter mockserver-spring-test-listener"

for module in $MODULE_LIST; do
    (runSubModule "$module");
done

if [[ -d mockserver-maven-plugin ]]; then
    printModule "mockserver-maven-plugin"
    (cd mockserver-maven-plugin && ./mvnw install -Dmaven-invoker-parallel-threads=2 -Djava.security.egd=file:/dev/./urandom)
fi

# examples/java is no longer a module of the mockserver reactor (it was removed
# from mockserver/pom.xml so /mockserver is a self-contained directory for
# Dependabot — a `<module>../examples/java</module>` broke every grouped Maven
# update). It is a standalone build whose parent is ../../mockserver/pom.xml, so
# it resolves the reactor SNAPSHOTs the loop above just installed into ~/.m2.
# Build it here so this module-by-module helper still exercises the examples,
# just as CI does after the reactor build (scripts/buildkite_quick_build.sh).
# NOTE: examples/java has no Maven wrapper of its own (only pom.xml/src), so this
# must drive the reactor's wrapper with -f rather than `cd ../examples/java &&
# ./mvnw`. The sibling mockserver-maven-plugin block above can cd because that
# directory does carry a wrapper; copying its shape here silently breaks the
# script under `set -e` with "./mvnw: No such file or directory".
if [[ -d ../examples/java ]]; then
    printModule "mockserver-examples (../examples/java)"
    ./mvnw -f ../examples/java/pom.xml install -Dmaven-invoker-parallel-threads=2 -Djava.security.egd=file:/dev/./urandom
fi

cd ..
