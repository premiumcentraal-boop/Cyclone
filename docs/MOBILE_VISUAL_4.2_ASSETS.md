# 4.2 supplied assets

The two separately uploaded alpine JPEGs are bundled unchanged in `drawable-nodpi`: `1B97889A-B754-4B5E-AB49-235878F8BBC1.jpeg` is night; `572D5FB5-87B6-40F2-B651-111E258369E2.jpeg` is day. Ask selects them with the existing light/dark theme. A scrim and opaque message surfaces preserve reading contrast.

Brand mark and primary navigation vector geometry come from the user's `Cyclone_Assets_Pack_v1(1).zip`. SVG geometry is converted to native Android VectorDrawables; unsupported SVG shadows are omitted. Navigation strokes use the theme's icon tint; the mark retains the supplied blue/cyan gradient. No unrelated replacement artwork or generated production activity is used. Reference boards and their example metrics remain outside production resources.

The ZIP also contains optional utility icons, aurora/mesh backgrounds and glossy reference PNGs. These are not all shipped simply because they exist: Android's existing action icons remain for familiar controls, while the supplied alpine pair is the chosen Ask background.
