#!/usr/bin/env bash
# Builds and packages the Muse connector without Gradle.
#
# Why not Gradle: the Gradle daemon cannot run in this sandbox (its bootstrap
# crashes during launcher-to-daemon hand-off), so the com.boomi.connector
# plugin's connectorArchive task is unavailable. This script reproduces what
# that task produces: a zip of lib/** (connector jar + bundled dependencies)
# and META-INF/ (connector-config.xml + connector-descriptor.xml), with
# strict class loading and Java 8 bytecode.
#
# Deviation from the skill's supported path is deliberate and documented here.
# If you build on a machine where Gradle works, prefer ./gw connectorArchive.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPS="$PROJECT_DIR/build/deps"
CLASSES="$PROJECT_DIR/build/classes"
DIST="$PROJECT_DIR/build/distributions"
STAGE="$PROJECT_DIR/build/stage"
SDK_VERSION="2.31.0"

# connector-sdk-api is provided by the runtime and must NOT be bundled.
BUNDLE_JARS=(
  "connector-sdk-util-${SDK_VERSION}.jar"
  "boomi-util-3.0.14.jar"
  "httpclient-4.5.14.jar"
  "httpcore-4.4.16.jar"
  "commons-logging-1.2.jar"
  "commons-codec-1.16.1.jar"
  "jackson-databind-2.17.3.jar"
  "jackson-core-2.17.3.jar"
  "jackson-annotations-2.17.3.jar"
)

echo "== dependencies =="
mkdir -p "$DEPS"
fetch() { # $1 = filename, $2 = url; skips jars already present
  if [[ -f "$DEPS/$1" ]]; then return 0; fi
  echo "downloading $1"
  curl -fsSL -m 120 -o "$DEPS/$1" "$2"
}
S3="https://boomisdk.s3.amazonaws.com/releases"
C="https://repo1.maven.org/maven2"
fetch "connector-sdk-api-2.31.0.jar"  "$S3/com/boomi/connsdk/connector-sdk-api/2.31.0/connector-sdk-api-2.31.0.jar"
fetch "connector-sdk-util-2.31.0.jar" "$S3/com/boomi/connsdk/connector-sdk-util/2.31.0/connector-sdk-util-2.31.0.jar"
fetch "boomi-util-3.0.14.jar"         "$S3/com/boomi/util/boomi-util/3.0.14/boomi-util-3.0.14.jar"
fetch "httpclient-4.5.14.jar"         "$C/org/apache/httpcomponents/httpclient/4.5.14/httpclient-4.5.14.jar"
fetch "httpcore-4.4.16.jar"           "$C/org/apache/httpcomponents/httpcore/4.4.16/httpcore-4.4.16.jar"
fetch "commons-logging-1.2.jar"       "$C/commons-logging/commons-logging/1.2/commons-logging-1.2.jar"
fetch "commons-codec-1.16.1.jar"      "$C/commons-codec/commons-codec/1.16.1/commons-codec-1.16.1.jar"
fetch "jackson-databind-2.17.3.jar"   "$C/com/fasterxml/jackson/core/jackson-databind/2.17.3/jackson-databind-2.17.3.jar"
fetch "jackson-core-2.17.3.jar"       "$C/com/fasterxml/jackson/core/jackson-core/2.17.3/jackson-core-2.17.3.jar"
fetch "jackson-annotations-2.17.3.jar" "$C/com/fasterxml/jackson/core/jackson-annotations/2.17.3/jackson-annotations-2.17.3.jar"

echo "== descriptor lint =="
if command -v xmllint >/dev/null 2>&1; then
  XSD_CACHE="$PROJECT_DIR/build/xsd-cache"
  XSD="$XSD_CACHE/genericconnectordesc.xsd"
  if [[ ! -f "$XSD" ]]; then
    echo "fetching descriptor schema for SDK $SDK_VERSION"
    mkdir -p "$XSD_CACHE"
    tmp="$(mktemp -d)"
    curl -fsSL -m 60 -o "$tmp/model.jar" \
      "$S3/com/boomi/connsdk/connector-sdk-model/$SDK_VERSION/connector-sdk-model-$SDK_VERSION.jar"
    unzip -q -j -o "$tmp/model.jar" "com/boomi/model/connector/*.xsd" -d "$XSD_CACHE"
    rm -rf "$tmp"
  fi
  xmllint --noout --schema "$XSD" "$PROJECT_DIR/src/main/resources/META-INF/connector-descriptor.xml" \
    && echo "descriptor validates"
else
  echo "xmllint not found; skipping descriptor validation"
fi

echo "== compile =="
CP=$(ls "$DEPS"/*.jar | tr '\n' ':')
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
# shellcheck disable=SC2046
javac --release 8 -encoding UTF-8 -nowarn -cp "$CP" -d "$CLASSES" \
  $(find "$PROJECT_DIR/src/main/java" -name "*.java")
echo "compiled $(find "$CLASSES" -name '*.class' | wc -l) classes"

echo "== stage =="
rm -rf "$STAGE" "$DIST" && mkdir -p "$STAGE/lib" "$STAGE/META-INF" "$DIST"

jar --create --file "$STAGE/lib/muse-connector.jar" -C "$CLASSES" .
for jar in "${BUNDLE_JARS[@]}"; do
  if [[ ! -f "$DEPS/$jar" ]]; then
    echo "ERROR: missing dependency $DEPS/$jar" >&2
    exit 1
  fi
  cp "$DEPS/$jar" "$STAGE/lib/"
done

cat > "$STAGE/META-INF/connector-config.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<GenericConnector sdkApiVersion="${SDK_VERSION}" strictClassLoading="true">
    <connectorClassName>com.boomi.connector.muse.MuseConnector</connectorClassName>
</GenericConnector>
EOF
cp "$PROJECT_DIR/src/main/resources/META-INF/connector-descriptor.xml" "$STAGE/META-INF/"

echo "== archive =="
(cd "$STAGE" && zip -qr "$DIST/muse-connector.zip" lib META-INF)
echo "wrote $DIST/muse-connector.zip"
unzip -l "$DIST/muse-connector.zip"
