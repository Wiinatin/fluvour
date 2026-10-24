# Language art for zh, ko, es, fr, de, vi, id

Each of these seven languages has its own **mark** (a 140x140 illustration with CSS-style animation) and its own **ambient layer**
(296x300, the moving background behind screens). They were designed as HTML/SVG boards and converted to Kotlin by a script.

**Status: written without a Kotlin compiler available. It has not been built or run on a device.** The conversion itself was checked
against Chromium renders of the original HTML (all 7 marks and 7 ambient layers, same time offset: only anti-aliasing differences).
Expect to fix small compile errors (imports, names) on first build.

## What is where

| File | What |
|---|---|
| `ui/art/LangArt.kt` | Runtime. Draws scene data: transforms, keyframe animation, gradients, clips. Also the scene keys and helpers used by `Art.kt` / `Ambient.kt`. |
| `ui/art/lang/ZhArt.kt` ... `IdArt.kt` | **Generated.** One object per language with `mark` and `ambient` scenes. Do not hand-edit. |
| `ui/art/Art.kt` (patched) | Palettes for the 7 languages, `lx:` / `lxc:` / `lxs:` keys, `stageKey`, `coverKeyFor`. |
| `ui/art/Ambient.kt` (patched) | `ambient`, `languageMark`, `LanguageMotif` use the new art for these languages. |
| `ui/theme/Theme.kt` (patched) | `LanguagePalettes` entries (accent, tint) for the 7 languages. |
| `ui/screens/HomeScreen.kt` (patched) | Next-up cover uses the mark crop. |
| `tools/langart/` | The converter (`svg2scene.py`, `emit.py`), the 7 source HTML boards, and `verify.py` (renders original vs converted in Chromium). |

## Scene keys (Art.kt)

* `lx:es` the mark, 140x140 (languageMark, LanguageMotif)
* `lxc:es` the mark cropped to the Home cover (viewBox 30 14 84 108)
* `lxs:es` the Watch banner: mark cropped (viewBox 0 22 140 71) with the ambient layer over it at 85%

The grain is drawn over all three by `paintArt`, same as the old art. Reduce motion draws a still frame (clock = -1).

## How scene data works

* `LangNode`: one path, fill/stroke as ink 1..5 (palette il1..il5), gradient 10+i, or literal colour 30+i.
* `chains`: per node, the transform steps from outermost group down: `lm(i)` static matrix, `la(i)` animation, `lk(i)` clip.
* `LangAnim`: duration, delay, easing, transform origin, and keyframe stops for transform (translate, rotate, scale) and opacity.
  The CSS custom properties used in the designs (`--dx`, `--rt`, ...) are already resolved per instance.
* Inks come from `ArtPalettes.of(lang)`, so changing a language's colours is a one-line change in `Art.kt`.

## Changing a design

1. Edit the HTML board in `tools/langart/sources/` (SVG + CSS keyframes only: path/circle/ellipse/rect/g/use/symbol, transform and opacity animations, linear/radial gradients, rect clip-paths).
2. `cd tools/langart && python3 -m pip install svgelements && python3 emit.py sources ../../app/src/main/kotlin/app/fluvour/ui/art/lang`
3. Optional check: `python3 verify.py 3.7` (needs Playwright + Chromium).

## Known approximations

* Group `opacity` is multiplied into each shape's alpha instead of compositing the group (only shows where shapes in one faded group overlap).
* Gradients use the shape's bounding box (circles and beams; no elliptical radial stretch).
* Dark-mode accents/tints for these languages in `Theme.kt` are additions (the design is light only). Tints are il2 mixed 50% with white.
* The ambient layer fades with the existing 420dp mask in `ambient()`, not the HTML's exact mask percentages.

## Prompt for another Claude

> Read `docs/LANG_ART.md`. The Kotlin for the new language art (zh, ko, es, fr, de, vi, id) is already in `ui/art/LangArt.kt`, `ui/art/lang/*.kt`
> and patched into `Art.kt`, `Ambient.kt`, `Theme.kt`, `HomeScreen.kt`. It was written without a compiler. Build the app, fix any compile errors
> without changing the design or the generated files (fix the runtime or the call sites instead), then check each of those languages on Home,
> Read, Speak and Watch: the faint mark in the corner, the ambient background, the Watch banner and the Home cover. Report anything that looks wrong
> versus the HTML boards in `tools/langart/sources/` (open them in a browser).
