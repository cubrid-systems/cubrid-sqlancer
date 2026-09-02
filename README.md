![cubrid-sqlancer — goal: find the wrong-result bugs in CUBRID that an expected-answer suite structurally cannot reach. Two queries must count the same thing, and the optimizer only gets to rewrite one of them.](docs/assets/banner.svg)

**cubrid-sqlancer** is a [SQLancer](https://github.com/sqlancer/sqlancer) provider for CUBRID.
It looks for **wrong-result bugs** — the kind where the engine returns an answer that is simply
not the right one — and it does so without needing to know the right answer in advance.

Upstream SQLancer ships providers for 23 systems. CUBRID is not one of them. This repository is that
provider, supplied as a jar on the classpath rather than as a fork.

For engine developers and QA. Part of
[CUBRID Systems Research](https://github.com/cubrid-systems).

> **Where it is:** both oracles run. NoREC executed 91,513 queries at ~1,100 q/s and TLP 90,938,
> neither with a mismatch. The type and action surface is still narrow — see [Status](#status).

## Prerequisites

| | Why |
|---|---|
| **JDK 11+** | SQLancer targets `--release 11`. A JRE is not enough — you need `javac` |
| **Maven 3.9+** | to build both SQLancer and this provider |
| **A CUBRID install tree** | the engine under test. Your own build (`install.out`) or an unpacked release. It is never modified — `cubrid-env.sh` symlinks `bin` and `lib` and gives the test environment its own `conf`, `log`, `var` and `databases` |
| **A SQLancer checkout** | Maven Central's `com.sqlancer:sqlancer:2.0.0` was published in January 2022 and predates `NoRECGenerator`, `NoRECOracle` and `Reproducer`. It has to be built from source |

Linux x86-64. A debug build is fine. The engine under test is whatever `$CUBRID` points at.

## Install

```bash
git clone https://github.com/cubrid-systems/cubrid-sqlancer
cd cubrid-sqlancer

git clone https://github.com/sqlancer/sqlancer.git ../sqlancer-upstream
scripts/bootstrap.sh          # builds SQLancer, installs it and the CUBRID JDBC jar into ~/.m2

mvn package                   # target/classes + target/deps
```

`scripts/bootstrap.sh` finds the JDBC driver at `$CUBRID/jdbc/cubrid_jdbc.jar`, or looks in
`~/cubrid/install.out`, `~/CUBRID` and `/opt/cubrid` if `$CUBRID` is not set.

## Getting started

Stand up an isolated engine, give it a database, and run the oracle against it.

```bash
$ eval "$(scripts/cubrid-env.sh --source ~/cubrid/install.out)"
==> creating isolated environment: .cubrid-env (source: /home/you/cubrid/install.out)
==> done. master=1723 broker=34000 shm=34000

$ scripts/cubrid-testdb.sh --reuse --port 34001
==> creating database: sqlancer (.cubrid-env/databases/sqlancer, en_US.utf8)
==> core files disabled (stack traces still land in $CUBRID/log/coredump)
==> ready
    JDBC URL: jdbc:CUBRID:localhost:34001:sqlancer:::

$ java -cp "target/classes:target/deps/*" sqlancer.Main \
    --num-threads 1 --num-tries 200 --timeout-seconds 600 \
    --host localhost --port 34001 --username dba --password "" \
    cubrid --oracle NOREC --test-database sqlancer
[18:40:31] Executed 37373 queries (543 queries/s; successful statements: 93%).
```

If `sqlancer.Main --help` lists `cubrid` among its commands, the provider was found. If it does not,
see the note about fat jars in [Architecture](#architecture).

`--num-threads` must be `1`. The provider refuses anything higher, and the reason is in
[Design decisions](#design-decisions).

## Why it exists

`cubrid-testkit` (and CTP before it) compares query output against a stored `.answer` file. That
finds regressions in cases somebody already wrote down. It cannot find a wrong result in a query
nobody has ever written, because there is nothing to compare against.

NoREC gets around this by not needing an expected answer at all. It builds two queries that must
count the same rows, and lets the optimizer rewrite only one of them:

```sql
-- optimized: the optimizer is free to rewrite the predicate
SELECT COUNT(*) FROM t WHERE <pred>;

-- reference: the predicate is pushed into the projection, where there is nothing to rewrite
SELECT SUM(norec_count) FROM (
    SELECT CASE WHEN <pred> THEN 1 ELSE 0 END AS norec_count FROM t
) AS res;
```

Two different counts mean the optimizer rewrote the predicate into something that does not mean the
same thing. That is a wrong-result bug, and it is an axis `cubrid-testkit` has no coverage on today.

## Architecture

![Architecture: upstream SQLancer discovers this repo's provider through ServiceLoader, the provider drives an isolated CUBRID home, and every run leaves session logs and crash stacks as evidence](docs/assets/architecture.svg)

Upstream is built from source and left alone. `sqlancer.Main` calls
`ServiceLoader.load(DatabaseProvider.class)`, and its own Javadoc explains why that matters:

> *"To register a new provider, it is necessary to implement the DatabaseProvider interface and add
> an additional configuration file... we use an `@AutoService` annotation to create the configuration
> file automatically. **This allows SQLancer to pick up providers in other JARs on the classpath.**"*

So there is no fork to rebase and no patch to carry. Should the provider mature, the path upstream
stays open, because it follows the same package layout as the providers already there.

⚠️ **Do not build a fat jar.** Shading merges away `META-INF/services`, and SQLancer's response to
finding no providers is to quietly fall back to its built-in list
([sqlancer#799](https://github.com/sqlancer/sqlancer/issues/799)). Nothing fails; CUBRID is simply
absent. That is why `pom.xml` copies dependencies to `target/deps` instead.

## Design decisions

**It does not create a database.** CUBRID has no `CREATE DATABASE` statement — databases come from
the OS command `cubrid createdb`. So `CUBRIDProvider.createDatabase()` connects to a database that
`cubrid-testdb.sh` prepared earlier and drops the user tables and views inside it. Every thread would
be dropping the same tables, which is why `--num-threads 1` is enforced rather than suggested.

**Replay is by seed, not by log.** A session log holds only a fraction of a run — one 37,373-query
run left 319 statements in its log — so a log cannot be shrunk into a minimal reproduction. The
`-- seed value:` in the log is what matters: `crash-repro.sh --seed` makes SQLancer regenerate the
same database and query stream deterministically. Replay also defaults to JDBC, because SQLancer
reaches the server through broker and CAS while `csql` attaches to it directly, and the two paths do
not run the same code.

**BOOLEAN is not a column type.** CUBRID has no storable boolean; a predicate exists only as the
result of a comparison. `CUBRIDDataType.BOOLEAN` is therefore an expression-only pseudo-type that
`getRandomStorable()` never returns, and boolean constants are written `(1 = 1)` and `(1 = 0)`
because there is no literal to rely on.

**The reference query differs from both upstream examples.** Two details matter, and the two
providers closest to CUBRID each get one of them wrong:

- The `WHERE` clause has to be cleared. If the predicate stays in the inner query, both sides count
  the same thing and the oracle can never fire. Upstream's **DuckDB** provider does not clear it.
- The predicate has to survive into the projection. Upstream's **HSQLDB** provider replaces it with
  `COUNT(*)`, losing the predicate entirely.
- The alias must not be `count`. It is a reserved word in CUBRID, so `SUM(count)` parses as a
  function call and fails with `Syntax error: unexpected ')', expecting '('`. This one cost a full
  run: the oracle looked healthy because DDL and DML kept succeeding, while every single oracle query
  died on a syntax error and the executed-query counter sat at zero.

`CASE WHEN` rather than `CAST(pred AS INT)`, for the same reason BOOLEAN is not a column type — and
it happens to be exactly right, since `CASE WHEN` sends UNKNOWN to `ELSE`, which is the `IS TRUE`
semantics NoREC asks for.

**Schema comes from the CUBRID catalog**, not `INFORMATION_SCHEMA`:

```sql
SELECT class_name FROM db_class WHERE is_system_class = 'NO' AND class_type = 'CLASS';
SELECT attr_name, data_type, prec FROM db_attribute WHERE class_name = ? ORDER BY def_order;
```

`def_order` is not decoration — it is what makes generated `INSERT` column lists line up.

**The engine runs in its own home.** CUBRID defaults the master port (1523), the broker ports
(33000, 30000) and the shared-memory ids identically in every install tree. Put two trees on one
machine and whichever master starts first owns the port; every `cubrid` command from the other tree
then talks to *someone else's* master. `cubrid server status` shows you a stranger's databases and
your own quietly disappears. While this repository was being written, six `cub_master` processes from
six different trees were running on the development machine.

| | default |
|---|---|
| master port (`cubrid_port_id`) | 1723 |
| broker ports | query_editor 34000, **BROKER1 34001** |
| `APPL_SERVER_SHM_ID` | 34000, 34001 |
| `MASTER_SHM_ID` | 34099 — it must differ from every `APPL_SERVER_SHM_ID` |

**Core files are off by default.** A CUBRID server core is 10–20 GB because of the shared memory
segment, and a crash-inducing workload writes one every few minutes; four of them filled a 228 GB
root filesystem here. CUBRID writes a symbolised backtrace to `$CUBRID/log/coredump/*.coredump`
without the core, which is usually what you actually wanted. `--core-dump on` if you need the whole
thing.

The subtlety: `ulimit -c 0` in your shell is not enough. When the server crashes, `cub_master`
restarts it, and the new process inherits the *master's* limit. `cubrid-testdb.sh` reads
`/proc/<pid>/limits` and restarts the master under the limit when it has to.

## Status

| | |
|---|---|
| engine under test | whatever `$CUBRID` resolves to — `cubrid-env.sh` records the build in `.cubrid-env/.ready` and prints it on every run |
| provider discovery | ✅ `cubrid` appears in `sqlancer.Main --help` |
| schema, DDL, DML | ✅ `CREATE TABLE`, `INSERT`, `UPDATE`, `DELETE`, `CREATE INDEX` |
| **NoREC oracle** | ✅ 91,513 queries at ~1,100 q/s, no mismatch |
| **TLP (`WHERE`) oracle** | ✅ 90,938 queries, 95% statement success, no mismatch |
| types | `INTEGER` `SMALLINT` `BIGINT` `DOUBLE` `CHAR` `VARCHAR` `DATE` `TIME` `DATETIME` |
| types not covered | `NUMERIC` `FLOAT` `BIT` `BLOB` `CLOB` `ENUM` `JSON`, collections |
| known generator gap | a `BOOLEAN` constant can come out as a bare `NULL`, which CUBRID rejects as a predicate — it needs the `(1 = NULL)` form, the identity-comparison convention the `TRUE`/`FALSE` constants already follow |
| actions not covered | `CREATE VIEW`, `ALTER TABLE` |
| concurrency | ❌ single-threaded by construction |

## Layout

```
cubrid-sqlancer/
├── pom.xml                          no shade plugin, on purpose
├── docs/assets/                     banner.svg, architecture.svg
├── scripts/
│   ├── bootstrap.sh                 build SQLancer, install it and the JDBC driver
│   ├── cubrid-env.sh                an isolated CUBRID_HOME with its own ports and shm ids
│   ├── cubrid-testdb.sh             create / reuse / recreate the test database; core-file policy
│   └── crash-repro.sh               replay a run by seed, over JDBC, to reproduce a crash
└── src/main/java/sqlancer/cubrid/
    ├── CUBRIDProvider.java          @AutoService entry point; empties the prepared database
    ├── CUBRIDOptions.java           --oracle, --test-database
    ├── CUBRIDSchema.java            catalog queries and the type model
    ├── CUBRIDErrors.java            errors that are expected rather than interesting
    ├── CUBRIDOracleFactory.java     NOREC, WHERE
    ├── CUBRIDToStringVisitor.java   CUBRID syntax, including LIMIT offset, count
    ├── ast/                         9 expression node types
    └── gen/                         table, insert, update and expression generators
```

## Pitfalls

Every one of these cost time here.

| What you see | What it is | What to do |
|---|---|---|
| `Syntax error: unexpected ')', expecting '('` | `count` is a reserved word | alias it `norec_count` |
| `cubrid server status` shows databases you did not create, and yours vanishes | another install tree's master owns port 1523 | `scripts/cubrid-env.sh` |
| `config error, MASTER_SHM_ID = broker <name> APPL_SERVER_SHM_ID` | the master shm id collides with a broker's | keep them apart (default is +99) |
| `cubrid server start` cannot find a database you just created | `CUBRID_DATABASES` was unset, so `createdb` wrote its own `databases.txt` into the current directory | the scripts always export it |
| the disk fills in minutes | one 10–20 GB core per crash | `--core-dump off`, the default |
| cores appear even with `--core-dump off` | `cub_master` restarted the server, and it inherited the master's limit | restart the master under the limit — the script does this |
| `*.coredump` is zero bytes | the crash handler opened the file but wrote no backtrace | read the other files in that directory — not every crash writes one |
| `cubrid broker start` never returns | debug-build behaviour | check the port instead: `ss -ltnp` |
| `cubrid` missing from `--help` | `META-INF/services` was shaded away | do not build a fat jar |
| `UnsupportedClassVersionError ... class file version 55.0` | the `java` on `PATH` is 8 | invoke a JDK 11+ explicitly — sourcing the CUBRID environment does not change `java` |
| `Failed to load libjvm from 'CUBRID_JAVA_HOME'` | the isolated environment did not link `vm/`, where the PL server finds the bundled JVM | recreate it — `cubrid-env.sh` links it now |

## Relationships

[`cubrid-testkit`](https://github.com/cubrid-systems/cubrid-testkit) carries this repository as a
submodule. The decision record is `docs/adr/ADR-EXT-003-sqlancer-cubrid.md` there.

For now this is a standalone tool. Integration waits for Phase 2 of testkit, which is where the
case-format ingestion interface gets defined; the shape it will take is already settled — a
subprocess and an artifact to ingest, not a library call. Dialect knowledge is shared as *data*, a
catalog file, rather than as code, because the other tools on this axis (SQLsmith in C++,
sqllogictest in Rust) cannot share provider code with a Java one.

Mismatch and regression corpora do not go into `cubrid-testcases*`. Those repositories are frozen
input (testkit NG1).

## Contributing

`main` is the only branch. Changes arrive as a branch and a pull request.

CI on every push and pull request builds SQLancer from source, builds the provider, and then asserts
the two things that fail silently rather than loudly: that `@AutoService` wrote
`META-INF/services/sqlancer.DatabaseProvider`, and that `sqlancer.Main --help` actually lists
`cubrid`. A provider that compiles but is never discovered looks exactly like a healthy run.

When `main` moves, a bot opens a pull request against `cubrid-testkit` advancing the submodule
pointer, with the intervening commits in the body. It is driven three ways so a lost webhook does not
mean a stale pointer: a `repository_dispatch` from here for immediacy, a manual trigger, and a daily
schedule that catches up regardless.

Two secrets make that work. Both repositories are private, so `GITHUB_TOKEN` is not enough — it is
scoped to one repository and cannot read a sibling.

| Secret | Where | Scope |
|---|---|---|
| `TESTKIT_DISPATCH_TOKEN` | this repository | send `repository_dispatch` to `cubrid-systems/cubrid-testkit` |
| `SUBMODULE_PAT` | `cubrid-testkit` | read this repository, write contents and pull requests on `cubrid-testkit` |

Until they exist the bot fails and nothing else does; the daily job will pick things up the moment
they are added.

When you add to `CUBRIDErrors`, prefer the most specific string you can. A broad prefix silences real
bugs, and the list already carries `Semantic:`, which is about as wide as it should ever get.
