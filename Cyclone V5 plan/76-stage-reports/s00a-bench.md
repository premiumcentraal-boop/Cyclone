# Command Center benchmark (plan 76, stage 0)

- Data: 5,000 accounts, 20,000 tasks, 10,000 table rows, 2,000 pages, 1,000 phones (27,002 audit entries; file 21.4 MB).
- Seeding took 22.0 s through the stores' own APIs.
- Machine: Linux x86_64, 4 CPUs; Python 3.13.18; SQLite 3.53.1.
- Each query ran once to warm up, then 25 times. Milliseconds.

| Query | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| Accounts: list all | 59.0 | 60.0 | 78.4 | 84.2 |
| Tasks: newest 200 | 20.4 | 20.6 | 20.6 | 20.6 |
| Tasks: open brief (dispatch view) | 30.6 | 49.2 | 54.7 | 55.1 |
| Results: newest 200 | 0.0 | 0.0 | 0.0 | 0.0 |
| Overview (Command Center home) | 0.4 | 0.4 | 0.5 | 0.5 |
| Table: default view, 10,000 rows | 29.1 | 47.8 | 53.6 | 54.1 |
| Table: search one word | 38.2 | 65.7 | 74.7 | 77.3 |
| Pages: sidebar tree | 4.5 | 4.7 | 4.9 | 4.9 |
| Pages: full-text search | 2.5 | 2.6 | 2.6 | 2.6 |
| Identities: list | 33.4 | 36.6 | 52.2 | 57.0 |
| Health: fleet panel | 81.7 | 99.7 | 115.1 | 119.6 |
| Audit: verify the whole chain | 45.4 | 47.5 | 48.1 | 48.3 |
