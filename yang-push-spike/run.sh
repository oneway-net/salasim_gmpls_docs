#!/usr/bin/env bash
# Reproduces the Phase 0.3 experiments WITHOUT touching any source repo: copies salasim_gmpls_netconf into a scratch
# directory, adds the spike (YangPushPrototype + tests + the push YANG modules), builds offline, runs
#   1. YangPushPrototypeTest   server side, through ODL's real operation router, no sockets
#   2. ClientSideProbe         the ODL netconf client's NetconfMessageTransformer (what a controller mount uses)
#                              parses every notification step 1 emitted and renders the establish-subscription request
# Needs: JDK 25, an offline maven repo with the netconf-library dependencies ($M2_LIB) and a classpath file of the
# controller (lighty 24.0.0 / netconf-client 11.0.0) jars ($CTL_CP). Paths below are the ones used in the spike session.
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25}"
export PATH="${JAVA_HOME}/bin:$PATH"
ROOT=/Users/oneway/dev/salasim_gmpls
SPIKE="$ROOT/docs/yang-push-spike"
M2_LIB="${M2_LIB:-/tmp/claude-501/m2n1}"
CTL_CP="${CTL_CP:-/tmp/claude-501/ctl.cp}"
WORK="${WORK:-${TMPDIR:-/tmp}/yang-push-spike-work}"
export MAVEN_OPTS="-Djava.io.tmpdir=$WORK"

rm -rf "$WORK"; mkdir -p "$WORK/lib"
# the pinned baseline b6b2f83 (before the library gained addOperationServiceFactory, which the patch below adds):
# the spike stays reproducible however the library evolves. The hook itself has since landed in the library (A1).
git -C "$ROOT/salasim_gmpls_netconf" archive b6b2f83 | tar -x -C "$WORK/lib"
cd "$WORK/lib"
patch -p0 src/main/java/net/salasim/netconf/mgmt/NetconfManagementServer.java < "$SPIKE/netconf-server-hook.patch"
mkdir -p src/main/java/net/salasim/netconf/mgmt/yangpush src/test/java/net/salasim/netconf/mgmt/yangpush
cp "$SPIKE"/src/main/java/net/salasim/netconf/mgmt/yangpush/*.java src/main/java/net/salasim/netconf/mgmt/yangpush/
cp "$SPIKE"/src/test/java/net/salasim/netconf/mgmt/yangpush/*.java src/test/java/net/salasim/netconf/mgmt/yangpush/

# the standard modules the push stack needs (vendored in salasim_gmpls_yang/ietf); ietf-datastores, ietf-netconf-acm,
# ietf-inet-types are already served from the ODL jars by YangResources.SERVER_MODULES
for f in ietf-subscribed-notifications@2019-09-09.yang ietf-yang-push@2019-09-09.yang ietf-yang-patch@2017-02-22.yang \
         ietf-restconf@2017-01-26.yang ietf-network-instance@2019-01-21.yang ietf-ip@2018-02-22.yang ietf-yang-schema-mount@2019-01-14.yang; do
  cp "$ROOT/salasim_gmpls_yang/ietf/$f" src/test/resources/yang/
  echo "$(shasum -a 256 "src/test/resources/yang/$f" | cut -d' ' -f1)  $f" >> src/test/resources/yang/SOURCE.txt
done

mvn -o -B -q -Dmaven.repo.local="$M2_LIB" test 2>&1 | grep -E "Tests run:|ERROR|FAIL" | head -20 || true
grep -h "Tests run" target/surefire-reports/*.txt

mvn -o -B -q -Dmaven.repo.local="$M2_LIB" dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" >/dev/null
CP="target/classes:target/test-classes:$(cat "$WORK/cp.txt"):$(cat "$CTL_CP")"
mkdir -p "$WORK/probe"
javac -d "$WORK/probe" -cp "$CP" "$SPIKE/src/probe/java/net/salasim/netconf/mgmt/yangpush/ClientSideProbe.java"
java -cp "$WORK/probe:$CP" net.salasim.netconf.mgmt.yangpush.ClientSideProbe target/captured-notifications.txt \
  | grep -E "^CLIENT-" | cut -c1-400
