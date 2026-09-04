#!/bin/sh
set -e
cd "$(dirname "$0")"
mkdir -p out
javac --release 21 -encoding UTF-8 -d out src/main/java/com/goody/screensaver/*.java
java -cp out com.goody.screensaver.StarfieldSaver "$@"
