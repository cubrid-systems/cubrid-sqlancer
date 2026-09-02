#!/usr/bin/env bash
# SQLancer 전용으로 격리된 CUBRID 실행 환경(CUBRID_HOME)을 만든다.
#
# 왜 필요한가
# ------------
# CUBRID 는 master 포트(기본 1523), broker 포트(기본 33000/30000), 공유메모리 ID 를
# 설치 트리마다 같은 기본값으로 쓴다. 한 머신에서 여러 CUBRID 설치가 동시에 돌면
# 먼저 뜬 master 가 포트를 잡고, 나중 설치의 명령들이 *남의 master* 에 붙어버린다.
# 그러면 `cubrid server status` 가 남의 DB 를 보여주고, 내 DB 는 관리 주체가 엇갈려
# 조용히 사라진다.
#
# 그래서 원본 설치는 건드리지 않고, bin/lib 는 심볼릭 링크로 공유하되
# conf/log/var/databases 만 독립적으로 갖는 얇은 CUBRID_HOME 을 만든다.
#
# 사용법:
#   eval "$(scripts/cubrid-env.sh --source ~/cubrid/install.out)"   # 환경변수 export 문 출력
#   scripts/cubrid-env.sh --source ~/cubrid/install.out --setup     # 생성만
set -euo pipefail

SOURCE=""
ROOT=""
MASTER_PORT="1723"
BROKER_PORT="34000"
SHM_BASE="34000"
DO_SETUP="no"
PRINT_ENV="yes"

usage() {
  cat <<'USAGE'
사용법: cubrid-env.sh [옵션]

  --source <dir>     원본 CUBRID 설치 경로 (기본: $CUBRID, 없으면 ~/cubrid/install.out)
  --root <dir>       격리 환경을 만들 경로   (기본: <repo>/.cubrid-env)
  --master-port <n>  cubrid_port_id          (기본: 1723)
  --broker-port <n>  BROKER_PORT             (기본: 34000)
  --shm-base <n>     SHM ID 기준값           (기본: 34000)
  --setup            생성만 하고 환경변수는 출력하지 않음
  -h, --help
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --source)      SOURCE="$2"; shift 2 ;;
    --root)        ROOT="$2"; shift 2 ;;
    --master-port) MASTER_PORT="$2"; shift 2 ;;
    --broker-port) BROKER_PORT="$2"; shift 2 ;;
    --shm-base)    SHM_BASE="$2"; shift 2 ;;
    --setup)       DO_SETUP="yes"; PRINT_ENV="no"; shift ;;
    -h|--help)     usage; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -n "$ROOT" ]   || ROOT="$REPO/.cubrid-env"
[ -n "$SOURCE" ] || SOURCE="${CUBRID:-$HOME/cubrid/install.out}"
[ -x "$SOURCE/bin/cubrid" ] || die "원본 CUBRID 설치가 아니다: $SOURCE"

if [ ! -f "$ROOT/.ready" ]; then
  echo "==> 격리 환경 생성: $ROOT (원본: $SOURCE)" >&2
  mkdir -p "$ROOT"
  # 읽기 전용 자산은 링크로 공유 — 디스크와 빌드를 낭비하지 않는다.
  for d in bin lib msg java jdbc cci include share demo locales compat; do
    [ -e "$SOURCE/$d" ] && ln -sfn "$SOURCE/$d" "$ROOT/$d"
  done
  # 쓰기가 일어나는 것만 독립
  mkdir -p "$ROOT/conf" "$ROOT/log" "$ROOT/var" "$ROOT/tmp" "$ROOT/databases"
  cp -f "$SOURCE/conf/"*.conf "$ROOT/conf/" 2>/dev/null || true
  printf '#db-name\tvol-path\t\tdb-host\t\tlog-path\t\tlob-base-path\n' > "$ROOT/databases/databases.txt"

  # --- 포트/SHM 격리 ---
  # master 포트
  if grep -q "^[[:space:]]*cubrid_port_id" "$ROOT/conf/cubrid.conf"; then
    sed -i "s/^[[:space:]]*cubrid_port_id.*/cubrid_port_id=$MASTER_PORT/" "$ROOT/conf/cubrid.conf"
  else
    printf '\ncubrid_port_id=%s\n' "$MASTER_PORT" >> "$ROOT/conf/cubrid.conf"
  fi
  # broker: MASTER_SHM_ID + 각 broker 의 포트/SHM
  # ⚠️ MASTER_SHM_ID 는 어떤 APPL_SERVER_SHM_ID 와도 같으면 안 된다.
  #    같으면 CUBRID 가 "config error, MASTER_SHM_ID = broker <name> APPL_SERVER_SHM_ID" 로 거부한다.
  #    broker 는 SHM_BASE 부터 1씩 증가하므로 master 는 +99 로 충분히 떨어뜨린다.
  sed -i "s/^MASTER_SHM_ID.*/MASTER_SHM_ID           =$((SHM_BASE + 99))/" "$ROOT/conf/cubrid_broker.conf"
  awk -v port="$BROKER_PORT" -v shm="$SHM_BASE" '
    /^\[%/ { n++ }
    /^BROKER_PORT/        { print "BROKER_PORT             =" (port + n - 1); next }
    /^APPL_SERVER_SHM_ID/ { print "APPL_SERVER_SHM_ID      =" (shm  + n - 1); next }
    { print }
  ' "$ROOT/conf/cubrid_broker.conf" > "$ROOT/conf/cubrid_broker.conf.new"
  mv "$ROOT/conf/cubrid_broker.conf.new" "$ROOT/conf/cubrid_broker.conf"

  touch "$ROOT/.ready"
  echo "==> 완료. master=$MASTER_PORT broker=$BROKER_PORT shm=$SHM_BASE" >&2
fi

if [ "$PRINT_ENV" = "yes" ]; then
  echo "export CUBRID='$ROOT'"
  echo "export CUBRID_DATABASES='$ROOT/databases'"
  echo "export PATH='$ROOT/bin':\$PATH"
  echo "export LD_LIBRARY_PATH='$ROOT/lib':\${LD_LIBRARY_PATH:-}"
  echo "export CUBRID_SQLANCER_BROKER_PORT='$BROKER_PORT'"
fi
