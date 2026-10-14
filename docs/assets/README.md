# App icon

The cyan location pin with a Wi-Fi cutout represents finding nearby shared
networks. Colors: cyan #38BDF8 and navy #0F172A.

- `app-icon.svg`: editable vector source for the store icon.
- `play-store-icon.png`: opaque 512×512 store upload, with square corners
  and no baked-in shadow or launcher mask.
- Android foreground and monochrome vectors live in
  `android/app/src/main/res/drawable/ic_launcher_*.xml`. Their 108×108
  viewport leaves room for adaptive launcher masks; Android 13+ can use
  the monochrome cutout for themed icons.

Keep the SVG paths and Android vector paths in sync when changing the mark.
The store SVG crops the Android viewport to 18,18–90,90 to match the
nominal 72×72 launcher viewport. Render the SVG at 512×512 to regenerate
its PNG (for example, with CairoSVG).

# Feature graphic

- `feature-graphic.svg`: editable 1024×500 store banner using the same mark.
- `feature-graphic.png`: opaque 1024×500 PNG export for Play Console.

The SVG uses DejaVu Sans; render with that font installed to preserve the
layout. Regenerate the PNG with CairoSVG after editing the source.
