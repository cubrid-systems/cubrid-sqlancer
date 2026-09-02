# OPEN: a view query loses one group under partitioning — not reproduced standalone

Status: **not reproduced outside the generated run.** Recorded so the
lead is not lost, not as a confirmed defect.

Reported by the `GROUP_BY` oracle on
`CUBRID 11.5.0 (11.5.0.2513-5f3a30d) (64bit release build for Linux)`.

## What the oracle saw

```sql
SELECT v0.c0 FROM v0 GROUP BY v0.c0;
-- 9 rows
```

```sql
SELECT v0.c0 FROM v0 WHERE (TIME'01:55:40' NOT BETWEEN TIME'16:56:07' AND v0.c0) GROUP BY v0.c0
UNION SELECT v0.c0 FROM v0 WHERE (NOT (TIME'01:55:40' NOT BETWEEN TIME'16:56:07' AND v0.c0)) GROUP BY v0.c0
UNION SELECT v0.c0 FROM v0 WHERE (((TIME'01:55:40' NOT BETWEEN TIME'16:56:07' AND v0.c0)) IS NULL) GROUP BY v0.c0;
-- 8 rows
```

No join is involved, which is what makes it worth chasing — the two
confirmed findings in this directory are both outer-join rewrites.

The predicate is a tautology: `TIME'01:55:40' >= TIME'16:56:07'` is
FALSE for every row, so `FALSE AND anything` is FALSE and the
negation is TRUE regardless of `v0.c0`, NULL included. The first
branch alone should therefore return all nine groups.

## What was ruled out

Each of these behaves correctly, so none of them is the cause:

| tried | result |
|---|---|
| the same predicate on a plain table with a NULL | 2 = 2 + 0 + 0, correct |
| the same predicate on a plain view over that table | correct |
| a filtered view, both referencing and not referencing the column | correct |
| an aggregate (`GROUP BY`) view | correct |
| the tautology as the only `WHERE`, table and view, 9 distinct values with a NULL | 9 = 9, correct |
| `GROUP BY` vs `DISTINCT` vs `UNION` equality on CHAR with trailing spaces and on VARCHAR | all agree (2/2/2 and 3/3/3) |

That last one matters for the oracle itself: if `UNION` deduplication
disagreed with `GROUP BY` grouping, the GROUP BY and DISTINCT oracles
would produce false positives. It does not.

## What is missing

The definition of `v0` and the data behind it. The generated view is
dropped when the database is reset, so the shape has to come back
either from the run's `-- seed value:` through
`scripts/crash-repro.sh --seed`, or from logging the view definition
at the point the oracle fails.

Until one of those is done this stays open. It may yet turn out to be
the same outer-join rewrite seen through a view, or something in the
view itself.
