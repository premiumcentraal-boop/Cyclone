-- Widen the agent avatar shape set to the eight creator presets
-- (DESIGN.md §23). 'diamond' stays valid for legacy rows but is no longer
-- offered for new agents.

ALTER TABLE agents DROP CONSTRAINT IF EXISTS agents_avatar_shape_check;
ALTER TABLE agents ADD CONSTRAINT agents_avatar_shape_check
    CHECK (avatar_shape IS NULL OR avatar_shape IN (
        'round', 'blob', 'squircle', 'capsule', 'triangle', 'polygon',
        'cloud', 'droplet', 'diamond', 'pebble'
    ));
