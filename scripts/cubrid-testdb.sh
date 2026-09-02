#!/usr/bin/env bash
# SQLancer 전용 CUBRID 테스트 DB 를 준비한다.
#
# CUBRID 는 SQL 로 데이터베이스를 만들 수 없다 (CREATE DATABASE 문이 없다).
# 따라서 provider 가 아니라 이 스크립트가 DB 를 준비하고, provider 는 준비된
# DB 에 접속해 *테이블* 만 지우고 다시 만든다.
set -euo pipefail

DB_NAME="sqlancer"
DB_PATH=""                    # 비우면 $CUBRID/databases/<name>
CHARSET="en_US.utf8"
BROKER_PORT="33000"
MODE="create"                 # create | reuse | recreate | status
CORE_DUMP="off"               # off | on  — 아래 주석 참조

usage() {
  cat <<'USAGE'
사용법: cubrid-testdb.sh [옵션]

  --name <db>       DB 이름                     (기본: sqlancer)
  --path <dir>      DB 파일을 둘 디렉터리        (기본: $CUBRID/databases/<name>)
  --charset <cs>    생성 charset                (기본: en_US.utf8)
  --port <n>        broker 포트                 (기본: 33000)

  --core-dump on|off  서버 코어 파일 생성 여부 (기본: off)

      ⚠️ CUBRID 서버의 코어는 공유메모리 때문에 **10~20GB** 에 이른다. SQLancer 처럼
         크래시를 유도하는 워크로드에서는 몇 분 만에 디스크를 채운다.
         CUBRID 는 코어가 없어도 $CUBRID/log/coredump/*.coredump 에 크래시 시각과
         **스택 정보**를 남기므로, 원인 파악에는 그것으로 충분한 경우가 많다.
         전체 코어가 꼭 필요할 때만 --core-dump on 을 쓴다.

  --reuse           이미 있으면 그대로 사용, 없으면 새로 생성  ← CI/반복 실행용
  --recreate        있으면 지우고 새로 생성                    ← 깨끗한 상태 보장
  --status          현재 상태만 출력하고 종료
  -h, --help        이 도움말

기본 동작(--reuse/--recreate 없음)은 새로 생성이며, 이미 존재하면 중단한다.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --name)     DB_NAME="$2"; shift 2 ;;
    --path)     DB_PATH="$2"; shift 2 ;;
    --charset)  CHARSET="$2"; shift 2 ;;
    --port)     BROKER_PORT="$2"; shift 2 ;;
    --core-dump) CORE_DUMP="$2"; shift 2 ;;
    --reuse)    MODE="reuse"; shift ;;
    --recreate) MODE="recreate"; shift ;;
    --status)   MODE="status"; shift ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; usage; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }
[ -n "${CUBRID:-}" ] || die "\$CUBRID 가 설정되지 않았다"
command -v cubrid >/dev/null || die "cubrid 명령이 PATH 에 없다"

# ⚠️ 반드시 필요하다. CUBRID_DATABASES 가 없으면 cubrid 는 *현재 디렉터리* 의
# databases.txt 를 레지스트리로 삼는다. createdb 를 DB 디렉터리 안에서 실행하면
# 그 안에 별도 databases.txt 가 생겨 공용 레지스트리에 등록되지 않고,
# 이후 'cubrid server start' 가 DB 를 찾지 못한다.
export CUBRID_DATABASES="${CUBRID_DATABASES:-$CUBRID/databases}"
REGISTRY="$CUBRID_DATABASES/databases.txt"
[ -f "$REGISTRY" ] || die "레지스트리가 없다: $REGISTRY"

[ -n "$DB_PATH" ] || DB_PATH="$CUBRID_DATABASES/$DB_NAME"

db_exists() { grep -qE "^$DB_NAME[[:space:]]" "$REGISTRY" 2>/dev/null; }

if [ "$MODE" = "status" ]; then
  echo "name    : $DB_NAME"
  echo "path    : $DB_PATH"
  echo "registry: $REGISTRY"
  echo "exists  : $(db_exists && echo yes || echo no)"
  echo "server  : $(cubrid server status 2>/dev/null | grep -c "$DB_NAME") 개 실행 중"
  echo "jdbc    : jdbc:CUBRID:localhost:$BROKER_PORT:$DB_NAME:::"
  echo "cores   : $(ls -1 "$CUBRID/log/coredump"/*.coredump 2>/dev/null | wc -l) 개 스택 기록"
  exit 0
fi

if db_exists; then
  case "$MODE" in
    reuse)
      echo "==> 기존 DB 재사용: $DB_NAME"
      ;;
    recreate)
      echo "==> 기존 DB 삭제 후 재생성: $DB_NAME"
      cubrid server stop "$DB_NAME" >/dev/null 2>&1 || true
      cubrid deletedb "$DB_NAME"
      mkdir -p "$DB_PATH"
      ( cd "$DB_PATH" && cubrid createdb --db-volume-size=64M --log-volume-size=64M "$DB_NAME" "$CHARSET" )
      ;;
    create)
      die "DB '$DB_NAME' 가 이미 있다. --reuse 또는 --recreate 를 지정하라."
      ;;
  esac
else
  echo "==> DB 생성: $DB_NAME ($DB_PATH, $CHARSET)"
  mkdir -p "$DB_PATH"
  ( cd "$DB_PATH" && cubrid createdb --db-volume-size=64M --log-volume-size=64M "$DB_NAME" "$CHARSET" )
fi

# 코어 크기 제한은 서버 프로세스를 fork 하기 *전에* 걸어야 한다.
#
# ⚠️ 셸에 ulimit 을 거는 것만으로는 부족하다. 서버가 크래시하면 **cub_master 가
#    서버를 재기동**하는데, 그때 자식은 셸이 아니라 *master 의* limit 을 상속한다.
#    master 가 unlimited 로 떠 있으면 재기동된 서버가 다시 10~20GB 코어를 남긴다.
#    따라서 master 자체를 제한된 limit 으로 (재)기동해야 한다.
if [ "$CORE_DUMP" = "off" ]; then
  ulimit -c 0
  if pgrep -f "cub_master" >/dev/null 2>&1; then
    mpid=$(pgrep -f "cub_master" | head -1)
    mlimit=$(awk '/Max core file size/{print $6}' "/proc/$mpid/limits" 2>/dev/null)
    if [ "$mlimit" != "0" ]; then
      echo "==> cub_master 가 코어 unlimited 로 떠 있다 — 재기동해 제한을 상속시킨다"
      cubrid service stop >/dev/null 2>&1 || true
      sleep 2
      ( ulimit -c 0; cubrid master start >/dev/null 2>&1 || cubrid service start >/dev/null 2>&1 ) &
      sleep 5
    fi
  fi
  echo "==> 코어 파일 비활성 (스택 정보는 \$CUBRID/log/coredump 에 남는다)"
else
  ulimit -c unlimited
  echo "==> ⚠️ 코어 파일 활성 — CUBRID 서버 코어는 10~20GB 에 이른다"
fi

cubrid server start "$DB_NAME" >/dev/null 2>&1 || true
cubrid broker start          >/dev/null 2>&1 || true

echo "==> 준비 완료"
echo "    JDBC URL: jdbc:CUBRID:localhost:$BROKER_PORT:$DB_NAME:::"
