#!/bin/sh
# PeerConnect fallback build (JDK 17+ only, no Maven). Produces the same jars as "mvn package".
# Authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2
set -e
cd "$(dirname "$0")/.."
rm -rf build_tmp && mkdir -p build_tmp/classes
javac --release 17 -encoding UTF-8 -d build_tmp/classes $(find common server client tests -name '*.java')
mk() { # module  main-class  jar-name
  mkdir -p "$1/target"
  printf 'Main-Class: %s\n' "$2" > build_tmp/manifest.txt
  jar cfm "$1/target/$3.jar" build_tmp/manifest.txt -C build_tmp/classes .
}
mk server com.peerconnect.server.ServerMain peerconnect-server
mk client com.peerconnect.client.ClientMain peerconnect-client
mk tests  com.peerconnect.tests.IntegrationTests peerconnect-tests
rm -rf build_tmp
echo "BUILD OK (javac fallback): server/target/peerconnect-server.jar client/target/peerconnect-client.jar tests/target/peerconnect-tests.jar"
