# Glass design audit (plan 76, stage 0, run 0b)

What defines the look outside the design system (`styles/tokens.css` and `ui/icons.ts`). Stage 2 removes it.

| Kind | Count | Where most (file, count) |
|---|---:|---|
| Colour literals outside the tokens | 191 | `styles/tables.css` 74, `styles/fleet.css` 49, `styles/workspace.css` 40, `styles/cyber.css` 7, `styles/board.css` 5, `styles/phone.css` 3, `styles/home.css` 2, `styles/pc.css` 2 |
| Font sizes in px | 137 | `styles/workspace.css` 43, `styles/tables.css` 26, `styles/ports.css` 22, `styles/ai.css` 17, `styles/board.css` 11, `styles/cyber.css` 6, `styles/fleet.css` 4, `styles/runs.css` 4 |
| Spacing (margin, padding, gap) in px | 742 | `styles/workspace.css` 172, `styles/tables.css` 146, `styles/ports.css` 104, `styles/ai.css` 99, `styles/cyber.css` 65, `styles/command.css` 52, `styles/board.css` 46, `styles/fleet.css` 40 |
| Radii in px | 129 | `styles/workspace.css` 31, `styles/tables.css` 30, `styles/ai.css` 23, `styles/ports.css` 21, `styles/board.css` 7, `styles/fleet.css` 7, `styles/command.css` 4, `styles/market.css` 2 |
| Symbol glyphs typed into the interface | 102 | `workspace/editor.ts` 13, `workspace/aiPanel.ts` 9, `workspace/tableView.ts` 9, `pages/cloudPhonesView.ts` 7, `pages/labPage.ts` 6, `workspace/views.ts` 4, `pages/devicesPage.ts` 4, `pages/numbersView.ts` 4 |
| Emoji in code (content such as page icons; kept) | 99 | `workspace/pageView.ts` 26, `workspace/editor.ts` 18, `workspace/aiPanel.ts` 12, `workspace/views.ts` 11, `workspace/tableView.ts` 10, `workspace/home.ts` 9, `workspace/aiSettings.ts` 2, `workspace/sidebar.ts` 2 |

Most used glyphs: `→` U+2192 ×38, `×` U+00D7 ×9, `☑` U+2611 ×6, `▶` U+25B6 ×6, `⚠` U+26A0 ×5, `↑` U+2191 ×4, `▾` U+25BE ×3, `▸` U+25B8 ×3, `↓` U+2193 ×3, `←` U+2190 ×3, `☐` U+2610 ×2, `↗` U+2197 ×2, `⋮` U+22EE ×2, `▫` U+25AB ×2, `▦` U+25A6 ×2.

Counts are of literals, not of rules: one declaration with two pixel values counts twice. Zero values are not counted.
