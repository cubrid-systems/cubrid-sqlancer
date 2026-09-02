# Chained outer join associates right-to-left

Found by the `GROUP_BY` and `DISTINCT` oracles on
`CUBRID 11.5.0 (11.5.0.2513-5f3a30d) (64bit release build for Linux)`,
a release build of upstream `develop`.

## Reproduction

```sql
CREATE TABLE oa (x INT); INSERT INTO oa VALUES (1);
CREATE TABLE ob (y INT); INSERT INTO ob VALUES (10), (20);
CREATE TABLE oc (z INT); INSERT INTO oc VALUES (100), (200);

SELECT oa.x, ob.y, oc.z
FROM oa LEFT OUTER JOIN ob ON (1=1) RIGHT OUTER JOIN oc ON (1=0);
```

```
 x       y       z
 1    NULL     100
 1    NULL     200
```

SQL associates a join chain left to right, so that `FROM` means
`(oa LEFT OUTER JOIN ob ON (1=1)) RIGHT OUTER JOIN oc ON (1=0)`.
Written that way against the same engine:

```sql
SELECT x, y, z
FROM (SELECT oa.x AS x, ob.y AS y FROM oa LEFT OUTER JOIN ob ON (1=1)) lj
     RIGHT OUTER JOIN oc ON (1=0);
```

```
    x       y       z
 NULL    NULL     100
 NULL    NULL     200
```

`ON (1=0)` matches nothing, so every row of `oc` survives with the whole
left side NULL-extended — `x` has to be NULL. The chained form reports
`x = 1`.

The observed values match `oa LEFT OUTER JOIN (ob RIGHT OUTER JOIN oc ON (1=0)) ON (1=1)`:
the inner join yields `(NULL, 100)` and `(NULL, 200)`, and joining `oa`
onto that with a true condition gives `(1, NULL, 100)` and
`(1, NULL, 200)`. That is right-to-left association.

## The engine also disagrees with itself

Whichever association is intended, the filtered and unfiltered forms of
the same query cannot both be right:

```sql
SELECT oa.x FROM oa LEFT OUTER JOIN ob ON (1=1) RIGHT OUTER JOIN oc ON (1=0);
-- 2 rows, x = 1

SELECT oa.x FROM oa LEFT OUTER JOIN ob ON (1=1) RIGHT OUTER JOIN oc ON (1=0)
WHERE oa.x = 1;
-- 0 rows

SELECT oa.x FROM oa LEFT OUTER JOIN ob ON (1=1) RIGHT OUTER JOIN oc ON (1=0)
WHERE NOT (oa.x > oa.x);
-- 0 rows, though NOT FALSE is TRUE for both rows
```

The query reports two rows whose `x` is 1 and then reports that no row
has `x = 1`.

## What does not trigger it

| shape | correct |
|---|---|
| `oa RIGHT OUTER JOIN oc ON (1=0)` — two tables | yes |
| `oa INNER JOIN ob ON (1=1) RIGHT OUTER JOIN oc ON (1=0)` | yes |
| `oa LEFT OUTER JOIN ob ON (…) RIGHT OUTER JOIN oc ON (1=0)` | **no** |

`GROUP BY` is irrelevant — it reproduces with a bare `SELECT`. Two
tables are not enough; the chain of a LEFT OUTER followed by a RIGHT
OUTER is what it takes.

## How the oracle sees it

The three-way partition of a predicate is exhaustive by construction,
so the rows of the unfiltered query must reappear across `p`, `NOT p`
and `p IS NULL`. Here all three come back empty while the unfiltered
query returns two rows, which is what `GROUP_BY` and `DISTINCT` report
as a cardinality mismatch.
