# A WHERE predicate over an outer-join NULL-extended column loses rows

Found by the `AGGREGATE` oracle on
`CUBRID 11.5.0 (11.5.0.2513-5f3a30d) (64bit release build for Linux)`,
a release build of upstream `develop`. Two tables, one join.

## Reproduction

```sql
CREATE TABLE p0 (c2 DATE);   INSERT INTO p0 VALUES (DATE'2000-01-01');
CREATE TABLE p1 (c1 DOUBLE); INSERT INTO p1 VALUES (8.2392), (2.0);
```

`p0.c2 < p0.c2` never holds, so the join keeps both `p1` rows and
NULL-extends `p0`:

```sql
SELECT p0.c2, p1.c1 FROM p0 RIGHT OUTER JOIN p1 ON (p0.c2 < p0.c2);
```

```
 c2        c1
 NULL   8.2392
 NULL   2.0
```

The engine reports the predicate as TRUE for both of those rows:

```sql
SELECT CASE
         WHEN DATE'2012-01-14' NOT BETWEEN p0.c2 AND DATE'1988-06-04' THEN 'TRUE'
         WHEN NOT (DATE'2012-01-14' NOT BETWEEN p0.c2 AND DATE'1988-06-04') THEN 'FALSE'
         ELSE 'UNKNOWN'
       END
FROM p0 RIGHT OUTER JOIN p1 ON (p0.c2 < p0.c2);
```

```
 'TRUE'
 'TRUE'
```

That is the right answer. `x NOT BETWEEN NULL AND smaller` is
`NOT (UNKNOWN AND FALSE)`, and `UNKNOWN AND FALSE` is FALSE, so the
whole thing is TRUE regardless of the NULL.

The same predicate in `WHERE` keeps nothing:

```sql
SELECT COUNT(*) FROM p0 RIGHT OUTER JOIN p1 ON (p0.c2 < p0.c2)
WHERE DATE'2012-01-14' NOT BETWEEN p0.c2 AND DATE'1988-06-04';
```

```
 0     -- must be 2
```

All three exhaustive branches of the predicate come back empty:

| WHERE | rows | should be |
|---|---|---|
| `p` | 0 | 2 |
| `NOT p` | 0 | 0 |
| `p IS NULL` | 0 | 0 |

So the two rows of the join disappear entirely once the predicate is
applied, which is what the oracle reports.

## The join is required

The same predicate against a table that simply holds NULLs behaves
correctly, so this is not about `BETWEEN` and NULL on its own:

```sql
CREATE TABLE bt (c DATE); INSERT INTO bt VALUES (NULL), (NULL);

SELECT COUNT(*) FROM bt WHERE DATE'2012-01-14' NOT BETWEEN bt.c AND DATE'1988-06-04';
-- 2, correct

SELECT COUNT(*) FROM bt WHERE NOT (DATE'2012-01-14' NOT BETWEEN bt.c AND DATE'1988-06-04');
-- 0, correct
```

The NULL has to come from the outer join's null extension rather than
from stored data.

## Likely mechanism

The predicate is not null-rejecting — it is TRUE when its input is
NULL. Pushing such a predicate below an outer join, onto the side that
gets null-extended, evaluates it against the stored `p0.c2` instead of
the NULL the join produces, and the surviving rows are lost. That is
consistent with what the projection and the `WHERE` clause disagree
about.

## Related

`chained-outer-join-association.md` is probably the same root cause
seen from another angle: there a chained `LEFT OUTER JOIN ...
RIGHT OUTER JOIN` reports a left column as its stored value where the
null extension requires NULL. Both involve an outer join's
null-extended side being evaluated as if it still held stored values.
