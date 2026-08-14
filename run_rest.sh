#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REST_SOURCE="$PROJECT_DIR/src/de/affect/rest/AlmaRestServer.java"
readonly REST_CLASS="$PROJECT_DIR/lib/de/affect/rest/AlmaRestServer.class"
readonly CORE_JAR="$PROJECT_DIR/lib/affect.jar"
build_classpath() {
  local jar classpath="$PROJECT_DIR/lib:$PROJECT_DIR"

  # affect.jar 的類別在完整建置後也會保留於 lib/de；刻意排除該 JAR，
  # 避免舊版 JAR 中格式錯誤的 Manifest 阻止 REST adaptor 重新編譯。
  for jar in "$PROJECT_DIR"/lib/*.jar "$PROJECT_DIR"/lib/processing/*.jar; do
    [[ -f "$jar" ]] || continue
    [[ "$jar" == "$CORE_JAR" ]] && continue
    classpath+="${classpath:+:}$jar"
  done

  printf '%s' "$classpath"
}

usage() {
  cat <<'EOF'
用法: ./run_rest.sh [AlmaRestServer 參數]

預設啟動:
  ./run_rest.sh

指定 port:
  ./run_rest.sh --port 9090

指定設定檔:
  ./run_rest.sh \
    --comp conf/AffectComputationExample.aml \
    --def conf/AffectDefinitionExample.aml \
    --port 8080

預設值:
  --comp conf/AffectComputationExample.aml
  --def  conf/AffectDefinitionExample.aml
  --port 8080
EOF
}

die() {
  echo "錯誤：$*" >&2
  exit 1
}

find_java_home() {
  local candidate javac_bin

  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" ]]; then
    return 0
  fi

  if command -v javac >/dev/null 2>&1; then
    javac_bin="$(readlink -f "$(command -v javac)")"
    JAVA_HOME="$(dirname "$(dirname "$javac_bin")")"
    export JAVA_HOME
    return 0
  fi

  for candidate in \
    /usr/lib/jvm/java-11-openjdk-amd64 \
    /usr/lib/jvm/java-11-openjdk-* \
    /usr/lib/jvm/java-*-openjdk-* \
    /opt/jdk-*; do
    if [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]]; then
      JAVA_HOME="$candidate"
      export JAVA_HOME
      return 0
    fi
  done

  return 1
}

case "${1:-}" in
  help|-h|--help)
    usage
    exit 0
    ;;
esac

find_java_home || die "找不到 JDK。請安裝 JDK 11，或設定 JAVA_HOME。"
export PATH="$JAVA_HOME/bin:$PATH"

[[ -f "$REST_SOURCE" ]] || die "找不到 REST server 原始碼：$REST_SOURCE"
[[ -f "$CORE_JAR" ]] || die "找不到 $CORE_JAR，請先執行 ./build.sh"

CLASSPATH="$(build_classpath)"

if [[ ! -f "$REST_CLASS" || "$REST_SOURCE" -nt "$REST_CLASS" || "$CORE_JAR" -nt "$REST_CLASS" ]]; then
  echo "正在編譯 REST adaptor..."
  javac -cp "$CLASSPATH" -d "$PROJECT_DIR/lib" "$REST_SOURCE"
fi

echo "啟動 ALMA REST API（按 Ctrl+C 停止）"
cd "$PROJECT_DIR"
exec java -Djava.awt.headless=true -cp "$CLASSPATH" de.affect.rest.AlmaRestServer "$@"
