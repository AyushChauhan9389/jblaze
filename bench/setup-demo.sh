#!/usr/bin/env bash
# Recreates demo/: spring-petclinic at the commit the benchmarks were run on, with the two local edits used here
# (Java 21 toolchain, the only JDK installed; Gradle 9.4.1, which was cached when services.gradle.org was slow).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
[[ -d $ROOT/demo ]] && { echo "demo/ exists"; exit 0; }
git clone https://github.com/spring-projects/spring-petclinic "$ROOT/demo"
cd "$ROOT/demo"
git checkout -q 500158f732419217507c7656904b8e6aa1bcc0d6
sed -i 's/JavaLanguageVersion.of(17)/JavaLanguageVersion.of(21)/' build.gradle
sed -i 's/gradle-9.5.1-bin/gradle-9.4.1-bin/' gradle/wrapper/gradle-wrapper.properties
echo "demo/ ready"
