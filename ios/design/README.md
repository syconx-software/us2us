# ios/design — the us2us iOS design, as a build spec

The app must look **exactly** like the us2us iOS design (Nocturne design system), with very soft motion. This folder turns the design file into measured, machine- and human-readable specs. Design wins over earlier plans.

| file | use it for |
|---|---|
| `inventory.md` | **start here** — every screen: designer note (TR verbatim + EN), purpose, layout, all texts TR/EN, states, transitions, animation, data needs, plus a measured table of every element and a dark/light colour legend |
| `flows.md` | navigation graph (mermaid), app states (onboarding / locked / unlocked / privacy cover), every transition with its presentation and motion |
| `tokens.json` | design tokens for the Swift design system (mirror names 1:1): colours dark+light, semantic aliases, type styles (91), spacing, radii, sizes, shadows, blur, motion, 3D scene |
| `tokens.md` | the same tokens explained (font rules, line-height conversion, dotted patterns, CSS→SwiftUI shadow/blur mapping) |
| `icons.md`, `icons/` | the 57 Phosphor 2.1.1 glyphs used (SVGs included, MIT) + the custom Face ID glyph |
| `reference/*.png` | pixel references, 1206×2622 (402×874 pt @3x = iPhone 17 Pro): default dark, `light/`, `urgent/` (Ghost last hour), `overview*.png` (the whole design file @1x) |
| `layout/*.json` | per-screen element trees measured in Chrome (frames, declared token styles, computed dark+light colours, typography, layout, shadows, blur, animations); `layout/urgent/` for the urgent variants |

How the references were made: the design file was rendered in headless Google Chrome (DevTools protocol via puppeteer-core) after fonts (Doto ROND/wght variable, Martian Mono, Inter) and the three.js welcome scene had loaded; the 1 s countdown was frozen at its start value (23:41:07 / 00:42:10 urgent) and CSS animations were paused at t = 2 s so the Face ID rings and line packets are captured mid-flight. Each PNG is the IOSDevice screen element (status bar, Dynamic Island and home indicator included as the design draws them) with the 48 pt corner rounding removed.

Owner decisions folded in (see `flows.md`): 6-digit secret code entered twice, trivial codes rejected, increasing waits after wrong attempts, no reset, “Her şeyi sil” needs a second confirmation; separate backup password (7.2); lock on every launch and every return from background; Turkish first, English second; light + dark themes; iPhone portrait, iOS 17+.

Conventions used throughout: colour tokens `text`, `bg`, `surface`, `accent`, `accent100…900`, `neutral100…900`, `online`, `danger`, `divider`; `X·N%` = token X at N % opacity; coordinates in points from the physical top-left of the 402×874 screen.
