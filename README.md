# cubrid-sqlancer

CUBRID 용 [SQLancer](https://github.com/sqlancer/sqlancer) provider. **논리 버그(wrong-result)** 를
의미 등가 질의 쌍으로 검증한다.

- 축: `cubrid-testkit` ROADMAP §6a-E3 (Logic bug detection)
- 1차 oracle: **NoREC** (TLP 는 후속)
- 상류 SQLancer 에는 CUBRID provider 가 **없다** (2026-09 기준 23개 provider 중 미포함). 이 저장소가 그것을 제공한다.

---

## 왜 별도 저장소인가

SQLancer 는 provider 를 `ServiceLoader` 로 찾는다. `Main.java` 의 주석이 명시한다:

> *"To register a new provider, it is necessary to implement the DatabaseProvider interface and add an
> additional configuration file... we use an `@AutoService` annotation to create the configuration file
> automatically. **This allows SQLancer to pick up providers in other JARs on the classpath.**"*

즉 SQLancer 본체를 fork 하지 않고 **classpath 에 얹는 별도 jar** 로 provider 를 공급할 수 있다.
이 저장소는 그 방식을 쓴다 — 상류 rebase 부채가 없고, 경계가 분명하다.

⚠️ 단, fat-jar(shade)로 묶으면 `META-INF/services` 병합이 깨져 provider 가 **조용히 사라질 수 있다**
([sqlancer#799](https://github.com/sqlancer/sqlancer/issues/799)). 그래서 이 프로젝트는 shade 를 쓰지 않고
`target/classes` + `target/deps/*` 를 그대로 classpath 에 얹는다.

---

## Bootstrap

### 1. 의존 설치

```bash
scripts/bootstrap.sh
```

두 가지를 로컬 Maven 저장소에 넣는다.

| 의존 | 왜 로컬 설치인가 |
|---|---|
| `com.sqlancer:sqlancer` | Maven Central 의 `2.0.0` 은 **2022-01 게시본**이라 `NoRECGenerator` · `NoRECOracle` · `Reproducer` 가 없다. 반드시 소스에서 빌드해야 한다. |
| `cubrid:cubrid-jdbc` | Maven Central 에 없다. `$CUBRID/jdbc/cubrid_jdbc.jar` 를 설치한다. |

필요 도구: JDK 11+ (SQLancer 가 `--release 11`), Maven 3.9+.

### 2. 격리된 CUBRID 실행 환경

```bash
eval "$(scripts/cubrid-env.sh --source ~/cubrid/install.out)"
```

**왜 격리가 필요한가.** CUBRID 는 master 포트(기본 1523) · broker 포트(기본 33000/30000) · 공유메모리 ID 를
설치 트리마다 같은 기본값으로 쓴다. 한 머신에서 여러 CUBRID 설치가 동시에 돌면 **먼저 뜬 master 가 포트를 잡고,
나중 설치의 `cubrid` 명령들이 남의 master 에 붙는다.** 그러면 `cubrid server status` 가 남의 DB 를 보여주고,
내 DB 는 관리 주체가 엇갈려 조용히 사라진다. (이 저장소를 만들면서 실제로 겪었다.)

`cubrid-env.sh` 는 원본 설치를 **건드리지 않고** `bin`/`lib` 는 심볼릭 링크로 공유하되
`conf`/`log`/`var`/`databases` 만 독립인 얇은 `CUBRID_HOME` 을 만든다. 기본 격리 값:

| 항목 | 기본 |
|---|---|
| master 포트 (`cubrid_port_id`) | 1723 |
| broker 포트 | query_editor 34000 / **BROKER1 34001** |
| `APPL_SERVER_SHM_ID` | 34000, 34001 |
| `MASTER_SHM_ID` | 34099 |

⚠️ `MASTER_SHM_ID` 는 어떤 `APPL_SERVER_SHM_ID` 와도 달라야 한다. 같으면 CUBRID 가
`config error, MASTER_SHM_ID = broker <name> APPL_SERVER_SHM_ID` 로 거부한다.

### 3. 테스트 DB

```bash
scripts/cubrid-testdb.sh --reuse --port 34001
```

| 옵션 | 의미 |
|---|---|
| `--name <db>` | DB 이름 (기본 `sqlancer`) |
| `--path <dir>` | DB 파일 위치 (기본 `$CUBRID_DATABASES/<name>`) |
| `--reuse` | 있으면 그대로 쓰고, 없으면 생성 — 반복 실행/CI 용 |
| `--recreate` | 있으면 지우고 새로 생성 — 깨끗한 상태 보장 |
| `--status` | 현재 상태만 출력 |

⚠️ 스크립트는 `CUBRID_DATABASES` 를 반드시 export 한다. 없으면 `cubrid createdb` 가 **현재 디렉터리**의
`databases.txt` 를 레지스트리로 삼아, 공용 레지스트리에 등록되지 않은 유령 DB 가 만들어진다.

---

## 실행

```bash
mvn package
java -cp "target/classes:target/deps/*" sqlancer.Main \
  --num-threads 1 --num-tries 100 --timeout-seconds 600 \
  --host localhost --port 34001 --username dba --password "" \
  cubrid --oracle NOREC --test-database sqlancer
```

`sqlancer.Main --help` 의 command 목록에 `cubrid` 가 보이면 provider 가 정상 등록된 것이다.

---

## CUBRID 고유 설계 결정

### 1. 데이터베이스를 만들지 않는다

CUBRID 에는 `CREATE DATABASE` SQL 문이 없다. 데이터베이스는 OS 명령 `cubrid createdb` 로만 생긴다.
그래서 `CUBRIDProvider.createDatabase()` 는 **미리 준비된 DB 에 접속해 사용자 테이블/뷰만 지운다.**

결과적으로 **`--num-threads 1` 이어야 한다.** 모든 스레드가 같은 DB 의 같은 테이블을 지우고 만들기 때문이며,
provider 가 시작 시 이를 거부한다.

### 2. BOOLEAN 은 컬럼 타입이 아니다

CUBRID 에는 저장 가능한 boolean 타입이 없다. 술어는 비교/논리 연산의 결과로만 존재한다.
그래서 `CUBRIDDataType.BOOLEAN` 은 **식 생성 전용**이고 `getRandomStorable()` 이 절대 돌려주지 않는다.
boolean 상수도 이식성 있는 리터럴이 없어 `(1 = 1)` / `(1 = 0)` 으로 만든다.

### 3. NoREC 질의 형태 — 상류 두 provider 와 다르게 썼다

```sql
-- 최적화되는 쪽
SELECT COUNT(*) FROM t WHERE <pred>;

-- 최적화되지 않는 쪽 (reference engine)
SELECT SUM(norec_count) FROM (
    SELECT CASE WHEN <pred> THEN 1 ELSE 0 END AS norec_count FROM t
) AS res;
```

두 가지가 중요하다.

- **WHERE 를 반드시 지운다.** 술어가 안쪽 WHERE 에 남으면 두 질의가 같은 것을 세게 되어 oracle 이 아무것도
  검출하지 못한다. 상류 **DuckDB** provider 는 `generateUnoptimizedQueryString` 에서 WHERE 를 지우지 않고,
  **HSQLDB** provider 는 술어 자체를 `COUNT(*)` 로 대체해 술어를 잃는다. 둘 다 따르지 않았다.
- **`CASE WHEN` 을 쓴다.** CUBRID 에 저장 가능한 BOOLEAN 이 없어 `CAST(pred AS INT)` 를 신뢰할 수 없다.
  `CASE WHEN` 은 UNKNOWN 을 `ELSE` 로 보내므로 NoREC 이 요구하는 `IS TRUE` 의미와 정확히 같다.
- **별칭을 `count` 로 쓰면 안 된다.** CUBRID 에서 `count` 는 예약어(집계 함수)라 `SUM(count)` 가 함수 호출로
  파싱되어 `Syntax error: unexpected ')', expecting '('` 가 난다. 상류 DuckDB/HSQLDB provider 는 별칭으로
  `count` 를 쓰지만 CUBRID 에서는 불가능하다. 이 저장소는 `norec_count` 를 쓴다.

### 4. 스키마 조회

`INFORMATION_SCHEMA` 대신 CUBRID 시스템 카탈로그를 쓴다.

```sql
SELECT class_name FROM db_class WHERE is_system_class = 'NO' AND class_type = 'CLASS';
SELECT attr_name, data_type, prec FROM db_attribute WHERE class_name = ? ORDER BY def_order;
```

`def_order` 정렬이 필요하다 — INSERT 의 컬럼 순서와 맞춰야 한다.

---

## 알려진 함정 (직접 겪은 것)

| 증상 | 원인 | 대응 |
|---|---|---|
| `Syntax error: unexpected ')', expecting '('` | `count` 가 CUBRID 예약어 | 별칭을 `norec_count` 로 |
| `cubrid server status` 가 남의 DB 를 보여주고 내 DB 가 사라진다 | 다른 CUBRID 설치가 master 포트 1523 을 선점 | `scripts/cubrid-env.sh` 로 격리 |
| `config error, MASTER_SHM_ID = broker <name> APPL_SERVER_SHM_ID` | MASTER_SHM_ID 가 어떤 APPL_SERVER_SHM_ID 와 같음 | 충분히 떨어뜨린다 (기본 +99) |
| `cubrid server start` 가 DB 를 못 찾는다 | `CUBRID_DATABASES` 미설정 → createdb 가 cwd 에 별도 databases.txt 생성 | 스크립트가 항상 export |
| 몇 분 만에 디스크 100% | CUBRID 서버 코어가 10~20GB. 크래시마다 하나씩 | `--core-dump off` (기본) |
| `--core-dump off` 인데도 코어가 생긴다 | 크래시 후 **cub_master 가 서버를 재기동**하며 자식이 *master 의* limit 을 상속한다 | master 자체를 `ulimit -c 0` 으로 재기동 (스크립트가 자동 처리) |
| `log/coredump/*.coredump` 가 0바이트 | 크래시 핸들러가 파일은 만들었으나 백트레이스를 쓰지 못했다 | 원인 미상 — CUBRID 측 동작 |
| `cubrid broker start` / `service start` 가 반환하지 않는다 | 디버그 빌드 특성 | 포트로 기동 확인 (`ss -ltnp`) |

## 현재 상태

| 항목 | 상태 |
|---|---|
| provider 등록 (ServiceLoader) | ✅ `sqlancer.Main --help` 에 `cubrid` 노출 |
| 스키마 조회 / DDL / DML 생성 | ✅ CREATE TABLE · INSERT · UPDATE 동작 |
| NoREC oracle | ✅ **실행 검증** — 1회 세션에서 14,582 질의 실행, 불일치 0 |
| TLP (WHERE) oracle | ⚠️ 코드는 있으나 미검증 |
| 지원 타입 | INTEGER · SMALLINT · BIGINT · DOUBLE · CHAR · VARCHAR · DATE · TIME · DATETIME |
| 미지원 | NUMERIC · BIT · BLOB/CLOB · ENUM · JSON · SET/컬렉션 · 인덱스 · 뷰 생성 |
| 멀티스레드 | ❌ 설계상 불가 (§1) |

`CUBRIDErrors` 의 목록은 실행하면서 계속 보강해야 한다. **논리 버그를 감출 수 있는 광범위한 패턴은 넣지 않는다.**

## 미해결 — CUBRID 서버 크래시

이 워크로드에서 CUBRID 11.5.0 **디버그 빌드** 서버가 수 분 간격으로 반복 크래시한다
(`$CUBRID/log/coredump/` 에 크래시마다 파일이 하나씩 생긴다). 다만:

- 크래시 시점 세션의 SQL 을 `csql` 로 그대로 재현했을 때는 서버가 죽지 않았다
- `*.coredump` 스택 파일이 0바이트라 원인을 특정하지 못했다
- 디버그 빌드의 assertion 인지, 릴리스 빌드에서도 재현되는지 미확인

**따라서 아직 CUBRID 버그로 보고할 단계가 아니다.** 결정적 재현 절차를 먼저 확보해야 한다.
