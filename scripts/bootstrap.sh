#!/usr/bin/env bash
# 로컬 Maven 저장소에 이 프로젝트의 두 의존을 설치한다.
#
#  1. com.sqlancer:sqlancer  — Maven Central 의 2.0.0 은 2022-01 게시본이라
#     NoRECGenerator / NoRECOracle / Reproducer 가 없다. 반드시 소스 빌드.
#  2. cubrid:cubrid-jdbc     — Maven Central 에 없다. $CUBRID/jdbc 에서 가져온다.
set -euo pipefail

SQLANCER_SRC="${SQLANCER_SRC:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/sqlancer-upstream}"
CUBRID_HOME="${CUBRID:-}"
JDBC_VERSION="${JDBC_VERSION:-11.5.0}"

die() { echo "error: $*" >&2; exit 1; }

command -v mvn >/dev/null || die "mvn 이 PATH 에 없다"

# --- 1. SQLancer ------------------------------------------------------------
[ -d "$SQLANCER_SRC" ] || die "SQLancer 소스가 없다: $SQLANCER_SRC
  git clone https://github.com/sqlancer/sqlancer.git \"$SQLANCER_SRC\""

echo "==> SQLancer 빌드/설치 ($SQLANCER_SRC)"
( cd "$SQLANCER_SRC" && mvn -q -B install -DskipTests \
    -Dspotless.check.skip=true -Dspotless.apply.skip=true \
    -Dcheckstyle.skip=true -Dpmd.skip=true -Dspotbugs.skip=true -Djacoco.skip=true )

# --- 2. CUBRID JDBC ---------------------------------------------------------
if [ -z "$CUBRID_HOME" ]; then
  for c in "$HOME/cubrid/install.out" "$HOME/CUBRID" /opt/cubrid; do
    [ -f "$c/jdbc/cubrid_jdbc.jar" ] && CUBRID_HOME="$c" && break
  done
fi
[ -n "$CUBRID_HOME" ] || die "\$CUBRID 를 설정하거나 CUBRID 설치 경로를 찾을 수 없다"

JDBC_JAR="$CUBRID_HOME/jdbc/cubrid_jdbc.jar"
[ -f "$JDBC_JAR" ] || die "JDBC 드라이버가 없다: $JDBC_JAR"

echo "==> CUBRID JDBC 설치 ($JDBC_JAR → cubrid:cubrid-jdbc:$JDBC_VERSION)"
mvn -q -B install:install-file \
  -Dfile="$JDBC_JAR" -DgroupId=cubrid -DartifactId=cubrid-jdbc \
  -Dversion="$JDBC_VERSION" -Dpackaging=jar

echo "==> 완료. 이제 'mvn package' 가능."
