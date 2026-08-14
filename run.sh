#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /usr/lib/jvm/java-11-openjdk-amd64 /usr/lib/jvm/openjdk-11; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "找不到 JDK 11。請安裝 openjdk-11-jdk 或手動設定 JAVA_HOME。" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

mode="${1:-cb}"

usage() {
  cat <<EOF
用法: ./run.sh [mode]

  cb        CharacterBuilder GUI（預設，Anne + Bob 範例角色）
  plain     空白 AffectManager GUI（不載入範例角色）
  script    Script Player：跑 scripts/AffectScriptExample.aml
  test      headless 測試：解析 AffectOutputExample.aml，不開 GUI
  rest      啟動 REST API server（port 8080，headless）
  build     重新編譯（ant -f bin/build.xml jar）
  clean     清乾淨後重編（ant clean jar）
  stop      殺掉正在跑的 AffectManager / REST server
  help      顯示這個說明

範例：
  ./run.sh              # 開 CharacterBuilder
  ./run.sh test         # 驗 classpath，不用 X
  ./run.sh stop         # 關掉
EOF
}

case "$mode" in
  cb)      exec ant -f bin/build.xml run-characterbuilder ;;
  plain)   exec ant -f bin/build.xml run-almagui-plain ;;
  script)  exec ant -f bin/build.xml run-scriptplayer ;;
  test)    exec ant -f bin/build.xml run-output-example ;;
  rest)    exec ant -f bin/build.xml run-rest ;;
  build)   exec ant -f bin/build.xml jar ;;
  clean)   exec ant -f bin/build.xml clean jar ;;
  stop)
    killed=0
    if pkill -f 'de.affect.manage.AffectManager'; then echo "已停止 AffectManager"; killed=1; fi
    if pkill -f 'de.affect.rest.AlmaRestServer'; then echo "已停止 REST server"; killed=1; fi
    if [[ $killed -eq 0 ]]; then echo "沒有正在跑的 ALMA 相關 process"; fi
    ;;
  help|-h|--help) usage ;;
  *) echo "未知 mode: $mode" >&2; usage; exit 2 ;;
esac
