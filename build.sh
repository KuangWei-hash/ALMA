#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BUILD_FILE="$PROJECT_DIR/bin/build.xml"

usage() {
  cat <<'EOF'
用法: ./build.sh [模式]

模式:
  build, jar   完整編譯並產生 lib/affect.jar（預設）
  fast         使用現有 XMLBeans 產物快速編譯
  clean        清除編譯產物
  rebuild      清除後重新完整編譯
  test         編譯後執行 headless 範例測試
  <target>     執行 bin/build.xml 中的其他 Ant target
  help         顯示此說明

環境變數:
  JAVA_HOME    指定要使用的 JDK；未設定時會自動搜尋常見位置

範例:
  ./build.sh
  ./build.sh fast
  ./build.sh rebuild
  JAVA_HOME=/path/to/jdk ./build.sh
EOF
}

die() {
  echo "錯誤：$*" >&2
  exit 1
}

find_java_home() {
  local candidate java_bin

  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" ]]; then
    return 0
  fi

  if command -v javac >/dev/null 2>&1; then
    java_bin="$(readlink -f "$(command -v javac)")"
    JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
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

mode="${1:-build}"
case "$mode" in
  help|-h|--help)
    usage
    exit 0
    ;;
  build|jar)
    targets=(jar)
    ;;
  fast)
    targets=(jar-fast)
    ;;
  clean)
    targets=(clean)
    ;;
  rebuild)
    targets=(clean jar)
    ;;
  test)
    targets=(jar run-output-example)
    ;;
  *)
    targets=("$mode")
    ;;
esac

[[ -f "$BUILD_FILE" ]] || die "找不到 Ant 設定檔：$BUILD_FILE"
find_java_home || die "找不到 JDK。請安裝 JDK 11，或設定 JAVA_HOME 後重試。"
command -v ant >/dev/null 2>&1 || die "找不到 Ant。請先安裝 Apache Ant。"

export PATH="$JAVA_HOME/bin:$PATH"

echo "ALMA build"
echo "  JAVA_HOME: $JAVA_HOME"
echo "  Java:      $(java -version 2>&1 | head -n 1)"
echo "  Target:    ${targets[*]}"

cd "$PROJECT_DIR"
ant -f "$BUILD_FILE" "${targets[@]}"

if [[ "$mode" != "clean" && -f "$PROJECT_DIR/lib/affect.jar" ]]; then
  echo "編譯完成：$PROJECT_DIR/lib/affect.jar"
else
  echo "操作完成。"
fi
