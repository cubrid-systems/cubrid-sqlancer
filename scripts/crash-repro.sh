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
DB_NAME="sqlancer"
DO_REDUCE="no"
OUT_DIR=""
TRIALS=1

usage() {
  cat <<'USAGE'
사용법: crash-repro.sh --log <sqlancer-session.log> [옵션]

  --log <file>      SQLancer 세션 로그 (logs/cubrid/databaseN-cur.log)
  --db <name>       테스트 DB 이름            (기본: sqlancer)
  --reduce          최소 재현 시퀀스까지 축소
  --trials <n>      각 후보를 n 번 시도 (비결정 크래시 대응, 기본 1)
  --out <dir>       산출물 디렉터리          (기본: <log 디렉터리>/repro)
  -h, --help

전제: $CUBRID 환경이 설정되어 있고 (scripts/cubrid-env.sh), 대상 DB 가 준비되어 있을 것.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --log)    LOG="$2"; shift 2 ;;
    --db)     DB_NAME="$2"; shift 2 ;;
    --reduce) DO_REDUCE="yes"; shift ;;
    --trials) TRIALS="$2"; shift 2 ;;
    --out)    OUT_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }
[ -n "$LOG" ] || { usage; exit 2; }
[ -f "$LOG" ] || die "로그가 없다: $LOG"
[ -n "${CUBRID:-}" ] || die "\$CUBRID 가 설정되지 않았다 (scripts/cubrid-env.sh)"
command -v csql >/dev/null || die "csql 이 PATH 에 없다"
[ -n "$OUT_DIR" ] || OUT_DIR="$(dirname "$LOG")/repro"
mkdir -p "$OUT_DIR"

STMTS="$OUT_DIR/statements.sql"

# --- 1. 정규화 -------------------------------------------------------------
# SQLancer 로그는 한 줄에 한 문장이고, 성공한 문장 뒤에 " -- 8ms;" 같은 시간 주석이 붙는다.
# "--" 로 시작하는 메타 줄과 시간 주석을 제거한다.
grep -vE '^\s*--' "$LOG" \
  | sed -E 's/[[:space:]]*--[[:space:]]*[0-9]+ms;[[:space:]]*$//' \
  | sed -E 's/[[:space:]]+$//' \
  | grep -vE '^$' > "$STMTS"

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
  csql -u dba -i "$sqlfile" "$DB_NAME" >/dev/null 2>&1
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
echo "==> 기준선: 전체 $TOTAL 문장 재생"
if trial_n "$STMTS"; then
  echo "    ✅ 크래시 재현됨"
else
  echo "    ❌ 크래시 재현 안 됨 — 비결정적이거나 상태 의존적이다."
  echo "       --trials 를 올려 다시 시도하거나, 로그가 크래시 세션의 것인지 확인하라."
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
