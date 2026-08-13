-- Distinct identity colors for the seeded agents, drawn from the
-- ten-character creator palette (DESIGN.md §22). The previous palette left
-- Developer (#5A6BE1) and Research (#6665E1) nearly identical.

UPDATE agents SET avatar_color = '#1084FE' WHERE slug = 'chief';    -- blue
UPDATE agents SET avatar_color = '#9159FE' WHERE slug = 'research'; -- purple
UPDATE agents SET avatar_color = '#FF9800' WHERE slug = 'writer';   -- amber
UPDATE agents SET avatar_color = '#00BCA6' WHERE slug = 'developer';-- teal
UPDATE agents SET avatar_color = '#FF263C' WHERE slug = 'reviewer'; -- red
