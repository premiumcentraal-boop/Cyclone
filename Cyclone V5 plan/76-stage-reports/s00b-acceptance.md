# Experience acceptance script v1 (plan 76 §5.7), run on today's Glass

Run by an automated stand-in, not a person. Every pass is checked on the server or on the screen. `missing` means the feature does not exist yet; `fail` means something that exists did not work.

| # | Task | Result | Time | Note |
|---:|---|---|---:|---|
| 1 | Create a page from the sidebar and name it | PASS | 1.7 s | Made from the sidebar and named; the server has it. |
| 2 | Write a heading, a to-do and a bullet with markdown shortcuts | MISSING | 2.2 s | Shortcuts work only at the start of a paragraph (editor.ts:407): '- ' typed on the to-do line that Enter made stays a to-do. In Notion it turns into a bullet (stage 2, run 2d). |
| 3 | Add a Heading 2 with the slash menu | PASS | 1.9 s | /Heading 2 from the slash menu. |
| 4 | Mention another page with @ | PASS | 2.1 s | @ found the page and inserted a chip. |
| 5 | Undo the last edit with Ctrl/Cmd+Z | MISSING | 0.9 s | Ctrl/Cmd+Z does not undo a new block (stage 2, run 2b). |
| 6 | Find a page by a word in its text and open it | PASS | 0.1 s | Found by a word in the page's text and opened. |
| 7 | Change a number in a 10,000-row table | PASS | 4.3 s | Changed in a table of 10,000 rows. |
| 8 | Move a page inside another page by dragging it in the sidebar | PASS | 3.1 s | Dragged onto another page in the sidebar; it is now inside it. |
| 9 | Move a page to the trash and restore it | PASS | 2.9 s | Moved to the trash and restored. |
| 10 | Duplicate a page | MISSING | 0.4 s | No way to duplicate a page (stage 2, run 2e). |
| 11 | Group the accounts by app | MISSING | 1.5 s | Accounts is a separate screen, not a database: no grouping or views (stage 4). |
| 12 | Open a table row as a full page | MISSING | 1.5 s | A row opens only in the side peek; there is no full-page view (stage 3, run 3b). |

**7 passed, 5 missing, 0 failed, of 12 tasks.**
