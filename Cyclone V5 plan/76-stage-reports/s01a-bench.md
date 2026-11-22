# Command Center benchmark (plan 76, stage 1a: with the event ledger)

- Data: 5,000 accounts, 20,000 tasks, 10,000 table rows, 2,000 pages, 1,000 phones (27,002 audit entries; 52,028 ledger events; file 54.9 MB).
- Seeding took 42.7 s through the stores' own APIs.
- Machine: Linux x86_64, 4 CPUs; Python 3.13.18; SQLite 3.53.1.
- Each query ran once to warm up, then 25 times. Milliseconds.

| Query | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| Accounts: list all | 59.2 | 60.5 | 81.2 | 87.7 |
| Tasks: newest 200 | 20.3 | 20.4 | 20.5 | 20.6 |
| Tasks: open brief (dispatch view) | 31.5 | 52.7 | 59.0 | 59.4 |
| Results: newest 200 | 0.0 | 0.0 | 0.0 | 0.0 |
| Overview (Command Center home) | 0.4 | 0.5 | 0.5 | 0.5 |
| Table: default view, 10,000 rows | 31.3 | 52.5 | 59.5 | 60.1 |
| Table: search one word | 40.6 | 61.8 | 70.2 | 71.3 |
| Pages: sidebar tree | 3.3 | 3.4 | 3.5 | 3.5 |
| Pages: full-text search | 2.7 | 2.7 | 2.8 | 2.8 |
| Identities: list | 33.7 | 34.5 | 54.0 | 60.2 |
| Health: fleet panel | 81.1 | 82.8 | 83.2 | 83.2 |
| Audit: verify the whole chain | 46.3 | 46.7 | 47.1 | 47.2 |
| Ledger: append one event (p95 budget 5 ms) | 0.2 | 0.3 | 0.3 | 0.3 |
| Ledger: one account's events | 0.0 | 0.0 | 0.0 | 0.1 |
| Ledger: newest 200 task states | 0.6 | 0.6 | 0.7 | 0.7 |
| Ledger: verify the whole chain | 175.1 | 178.9 | 180.0 | 180.3 |
| Ledger: rebuild every projection and compare | 117.5 | 151.4 | 152.4 | 152.7 |
