# GROUP BY over an indexed column drops the NULL group

Found by the `GROUP_BY` oracle on
`CUBRID 11.5.0 (11.5.0.2513-5f3a30d) (64bit release build for Linux)`,
a release build of upstream `develop`. One table, one index, no join
and no view.

## Reproduction

```sql
CREATE TABLE m1 (c TIME);
INSERT INTO m1 VALUES (TIME'10:00:00'), (NULL);
CREATE INDEX ix ON m1 (c);
```

```sql
SELECT c FROM m1 GROUP BY c;
-- 2 rows: 10:00:00 and NULL
```

```sql
SELECT c FROM m1 WHERE TIME'01:55:40' NOT BETWEEN TIME'16:56:07' AND c;
-- 2 rows
```

```sql
SELECT c FROM m1 WHERE TIME'01:55:40' NOT BETWEEN TIME'16:56:07' AND c
GROUP BY c;
-- 1 row      <- the NULL group is gone
```

The predicate is TRUE for every row, NULL included.
`TIME'01:55:40' >= TIME'16:56:07'` is FALSE, so
`NOT (FALSE AND anything)` is TRUE whatever `c` holds — the `WHERE`
cannot remove anything. Each of the three clauses on its own agrees.
Only the combination loses the row.

## The index is the switch

```
CREATE INDEX ix ON m1 (c)   ->  1 row     wrong
DROP INDEX ix ON m1         ->  2 rows    correct
CREATE INDEX ix ON m1 (c)   ->  1 row     wrong
```

Toggling the index toggles the defect, with the data and the query
unchanged.

## Which predicates reach it

The indexed column has to be the **upper bound of a `NOT BETWEEN`**.
Other predicates that are also TRUE for NULL do not trigger it, so
this is narrower than "NULL rows are lost under GROUP BY":

| WHERE | GROUP BY | plain WHERE | |
|---|---|---|---|
| `TIME'01:55:40' NOT BETWEEN TIME'16:56:07' AND c` | 1 | 2 | **mismatch** |
| `c IS NULL OR c > TIME'00:00:00'` | 2 | 2 | agree |
| `NOT (c BETWEEN TIME'16:56:07' AND TIME'23:00:00')` | 1 | 1 | agree |
| `c IS NULL` | 1 | 1 | agree |
| `c IS NULL OR c IS NOT NULL` | 2 | 2 | agree |
| `1 = 1` | 2 | 2 | agree |

## Likely mechanism

The predicate looks like a range on `c` and the grouping is over the
same column, so the natural plan is to group by walking the index. A
B-tree scan reaches the NULL key only if it is asked to; here the
derived range appears to exclude it, even though the predicate is
satisfied by NULL. Without the grouping, the predicate is evaluated
per row and the NULL survives; without the index there is no scan to
derive a range from.

## How it was found

The oracle reported nine groups without the predicate and eight with
the three-way partition of it, on a generated view. Several
hand-written views and tables did not reproduce it, because the
missing ingredient was the index rather than the view. The generated
run's own log carries the DDL, and replaying that block reproduced it
immediately; removing the view, then the extra rows, then the second
index left the four statements above.
