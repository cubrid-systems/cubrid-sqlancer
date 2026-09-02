#!/usr/bin/env bash
# SQLancer 세션 로그를 재생해 CUBRID 서버 크래시를 재현하고, 최소 재현 시퀀스로 줄인다.
#
# SQLancer 는 세션마다 logs/<dbms>/database<N>-cur.log 에 실행한 SQL 을 남긴다.
# 서버가 죽으면 그 파일이 크래시 직전까지의 전체 시퀀스다. 이 스크립트는
#   1) 그 로그를 순수 SQL 로 정규화하고
#   2) csql 로 재생해 서버가 죽는지 확인하고
#   3) --reduce 면 delta debugging 으로 최소 시퀀스를 찾는다.
#
# 크래시 판정: 재생 후 cub_server <db> 프로세스가 사라졌거나 PID 가 바뀌었으면 크래시.
set -uo pipefail

LOG=""
LOGS_DIR=""
SEED=""
DB_NAME="sqlancer"
DO_REDUCE="no"
OUT_DIR=""
TRIALS=1
VIA="jdbc"                 # jdbc | csql — 아래 주석 참조
HOST="localhost"
PORT="34001"

usage() {
  cat <<'USAGE'
사용법: crash-repro.sh --log <sqlancer-session.log> [옵션]

  --log <file>      SQLancer 세션 로그 하나 (logs/cubrid/databaseN-cur.log)
  --seed <n>        SQLancer 를 그 시드로 다시 돌려 재현한다 (권장)

      로그 재생은 SQL 만 다시 태울 뿐, 원래 실행의 타이밍·연결 수명·oracle 경로를
      재현하지 못한다. 게다가 크래시 세션 로그에는 실행된 질의의 일부만 남는다
      (한 실행에서 37,373 질의가 돌았는데 로그에는 319 문장이었다).
      SQLancer 자체를 같은 시드로 다시 돌리면 그 데이터베이스와 질의열이 결정적으로
      재생성된다. 시드는 세션 로그의 "-- seed value:" 줄에 있다.

  --logs-dir <dir>  세션 로그 디렉터리 전체를 시간순으로 이어붙여 재생

      크래시가 한 세션의 SQL 로 재현되지 않으면 누적 상태가 필요한 것이다.
      SQLancer 는 반복마다 테이블을 지우고 다시 만들며, 그 churn 자체가 조건일 수 있다.
      --logs-dir 는 실행 전체를 그대로 다시 태운다.
  --db <name>       테스트 DB 이름            (기본: sqlancer)
  --via jdbc|csql   재생 경로                 (기본: jdbc)

      ⚠️ 기본이 jdbc 인 이유. SQLancer 는 JDBC -> broker -> CAS -> cub_server 로 붙고,
         csql 은 서버에 직접 붙는다. 두 경로가 통과하는 코드가 다르므로 CAS 경유에서만
         나는 크래시는 csql 재생으로 재현되지 않는다. 실제로 크래시 세션 319문장을
         csql 로 3회 재생했을 때 서버는 죽지 않았다.
         jdbc 모드는 target/classes 가 빌드되어 있어야 한다 (mvn package).

  --host <h>        JDBC 호스트               (기본: localhost)
  --port <n>        broker 포트               (기본: 34001)
  --reduce          최소 재현 시퀀스까지 축소
  --trials <n>      각 후보를 n 번 시도 (비결정 크래시 대응, 기본 1)
  --out <dir>       산출물 디렉터리          (기본: <log 디렉터리>/repro)
  -h, --help

전제: $CUBRID 환경이 설정되어 있고 (scripts/cubrid-env.sh), 대상 DB 가 준비되어 있을 것.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --log)      LOG="$2"; shift 2 ;;
    --logs-dir) LOGS_DIR="$2"; shift 2 ;;
    --seed)     SEED="$2"; shift 2 ;;
    --db)     DB_NAME="$2"; shift 2 ;;
    --via)    VIA="$2"; shift 2 ;;
    --host)   HOST="$2"; shift 2 ;;
    --port)   PORT="$2"; shift 2 ;;
    --reduce) DO_REDUCE="yes"; shift ;;
    --trials) TRIALS="$2"; shift 2 ;;
    --out)    OUT_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }
[ -n "$LOG" ] || [ -n "$LOGS_DIR" ] || [ -n "$SEED" ] || { usage; exit 2; }
[ -z "$LOG" ] || [ -f "$LOG" ] || die "로그가 없다: $LOG"
[ -z "$LOGS_DIR" ] || [ -d "$LOGS_DIR" ] || die "디렉터리가 없다: $LOGS_DIR"
[ -n "${CUBRID:-}" ] || die "\$CUBRID 가 설정되지 않았다 (scripts/cubrid-env.sh)"
command -v csql >/dev/null || die "csql 이 PATH 에 없다"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP="$REPO/target/classes:$REPO/target/deps/*"
if [ "$VIA" = "jdbc" ]; then
  [ -d "$REPO/target/classes" ] || die "jdbc 모드는 빌드가 필요하다: mvn package (또는 --via csql)"
  command -v java >/dev/null || die "java 가 PATH 에 없다"
fi
if [ -z "$OUT_DIR" ]; then
  if [ -n "$LOG" ]; then OUT_DIR="$(dirname "$LOG")/repro"; else OUT_DIR="$LOGS_DIR/repro"; fi
fi
mkdir -p "$OUT_DIR"

STMTS="$OUT_DIR/statements.sql"

# --- 시드 모드 -------------------------------------------------------------
if [ -n "$SEED" ]; then
  [ -d "$REPO/target/classes" ] || die "시드 모드는 빌드가 필요하다: mvn package"
  server_pid_seed() { pgrep -f "cub_server $DB_NAME" | head -1; }
  echo "==> 시드 $SEED 로 SQLancer 를 다시 돌린다 (num-tries 1)"
  for attempt in $(seq 1 "$TRIALS"); do
    for i in $(seq 1 30); do
      [ -n "$(server_pid_seed)" ] && break
      ( ulimit -c 0; cubrid server start "$DB_NAME" >/dev/null 2>&1 & ); sleep 2
    done
    before="$(server_pid_seed)"
    rm -rf "$OUT_DIR/logs"
    ( cd "$REPO" && timeout 600 java -cp "$CP" sqlancer.Main \
        --random-seed "$SEED" --num-threads 1 --num-tries 1 --timeout-seconds 540 \
        --host "$HOST" --port "$PORT" --username dba --password "" \
        cubrid --oracle NOREC --test-database "$DB_NAME" > "$OUT_DIR/sqlancer.$attempt.txt" 2>&1 )
    sleep 2
    after="$(server_pid_seed)"
    if [ -z "$after" ] || [ "$after" != "$before" ]; then
      echo "    ✅ 시도 $attempt: 크래시 재현됨 (pid $before -> ${after:-none})"
      [ -d "$REPO/logs/cubrid" ] && cp -r "$REPO/logs/cubrid" "$OUT_DIR/logs" 2>/dev/null
      echo "    실행 로그: $OUT_DIR/sqlancer.$attempt.txt"
      echo "    세션 로그: $OUT_DIR/logs/"
      exit 0
    fi
    echo "    시도 $attempt: 크래시 없음"
  done
  echo "    ❌ 시드 $SEED 로는 재현되지 않았다."
  exit 1
fi

# --- 1. 정규화 -------------------------------------------------------------
# SQLancer 로그는 한 줄에 한 문장이고, 성공한 문장 뒤에 " -- 8ms;" 같은 시간 주석이 붙는다.
# "--" 로 시작하는 메타 줄과 시간 주석을 제거한다.
normalize() {
  grep -hvE '^\s*--' "$@" \
    | sed -E 's/[[:space:]]*--[[:space:]]*[0-9]+ms;[[:space:]]*$//' \
    | sed -E 's/[[:space:]]+$//' \
    | grep -vE '^$'
}

if [ -n "$LOGS_DIR" ]; then
  # 시간순. -cur.log 는 마지막 세션이므로 맨 뒤로 간다.
  mapfile -t FILES < <(ls -1tr "$LOGS_DIR"/*.log 2>/dev/null)
  [ "${#FILES[@]}" -gt 0 ] || die "로그가 없다: $LOGS_DIR"
  echo "==> 세션 ${#FILES[@]}개를 시간순으로 이어붙인다"
  normalize "${FILES[@]}" > "$STMTS"
else
  normalize "$LOG" > "$STMTS"
fi

TOTAL=$(wc -l < "$STMTS")
echo "==> 정규화: $TOTAL 문장  ($STMTS)"
[ "$TOTAL" -gt 0 ] || die "재생할 문장이 없다"

# --- 2. 서버 제어 ----------------------------------------------------------
server_pid() { pgrep -f "cub_server $DB_NAME" | head -1; }

ensure_server() {
  local i
  for i in $(seq 1 30); do
    if [ -n "$(server_pid)" ] && echo "SELECT 1 FROM db_root;" | csql -u dba "$DB_NAME" >/dev/null 2>&1; then
      return 0
    fi
    if [ -z "$(server_pid)" ]; then
      ( ulimit -c 0; cubrid server start "$DB_NAME" >/dev/null 2>&1 & )
    fi
    sleep 2
  done
  return 1
}

# --- 3. 한 번의 시도 -------------------------------------------------------
# 반환 0 = 크래시 재현, 1 = 재현 안 됨
trial() {
  local sqlfile="$1" before after
  ensure_server || { echo "   (서버를 올리지 못했다)"; return 1; }
  before="$(server_pid)"
  case "$VIA" in
    jdbc) java -cp "$CP" sqlancer.cubrid.tools.CUBRIDReplay \
            --file "$sqlfile" --host "$HOST" --port "$PORT" --db "$DB_NAME" >/dev/null 2>&1 ;;
    csql) csql -u dba -i "$sqlfile" "$DB_NAME" >/dev/null 2>&1 ;;
    *)    die "알 수 없는 --via: $VIA" ;;
  esac
  sleep 2
  after="$(server_pid)"
  if [ -z "$after" ] || [ "$after" != "$before" ]; then
    return 0
  fi
  return 1
}

trial_n() {
  local f="$1" i
  for i in $(seq 1 "$TRIALS"); do
    if trial "$f"; then return 0; fi
  done
  return 1
}

# --- 4. 기준선 -------------------------------------------------------------
echo "==> 기준선: 전체 $TOTAL 문장 재생 (경로: $VIA)"
if trial_n "$STMTS"; then
  echo "    ✅ 크래시 재현됨"
else
  echo "    ❌ 크래시 재현 안 됨 — 비결정적이거나 상태 의존적이다."
  echo "       --trials 를 올려 다시 시도하거나, 로그가 크래시 세션의 것인지 확인하라."
  [ "$VIA" = "csql" ] && echo "       csql 로 안 되면 --via jdbc 로 다시 시도하라 (경로가 다르다)."
  exit 1
fi
[ "$DO_REDUCE" = "yes" ] || exit 0

# --- 5. 축소 ---------------------------------------------------------------
CAND="$OUT_DIR/candidate.sql"
MIN="$OUT_DIR/minimal.sql"
cp "$STMTS" "$MIN"

# 5-1. 접두 이분 탐색 — 크래시를 유발하는 가장 짧은 접두를 찾는다.
echo "==> 접두 이분 탐색"
lo=1; hi=$TOTAL
while [ "$lo" -lt "$hi" ]; do
  mid=$(( (lo + hi) / 2 ))
  head -n "$mid" "$MIN" > "$CAND"
  if trial_n "$CAND"; then hi=$mid; else lo=$(( mid + 1 )); fi
  echo "    범위 [$lo, $hi]"
done
head -n "$lo" "$MIN" > "$CAND"; cp "$CAND" "$MIN"
echo "    접두 축소: $TOTAL -> $lo 문장"

# 5-2. 문장 단위 제거 — 뒤에서부터 하나씩 빼며 크래시가 유지되는지 본다.
echo "==> 문장 단위 제거"
n=$(wc -l < "$MIN")
i=$(( n - 1 ))
while [ "$i" -ge 1 ]; do
  sed "${i}d" "$MIN" > "$CAND"
  if [ -s "$CAND" ] && trial_n "$CAND"; then
    cp "$CAND" "$MIN"
    n=$(( n - 1 ))
    echo "    문장 $i 제거 가능 -> $n 문장"
  fi
  i=$(( i - 1 ))
done

echo
echo "==> 최소 재현 시퀀스: $(wc -l < "$MIN") 문장"
echo "    $MIN"
echo "-----------------------------------------------"
cat "$MIN"
echo "-----------------------------------------------"
