# STT integration recordings

One 16 kHz mono 16-bit WAV per Tier-1 template, plus `expected.tsv` mapping each file to the
command id it must reach. `SttTemplateTest` skips itself while this directory holds only this
file, so an empty checkout still builds and tests green.

```
# expected.tsv — <wav><TAB><command id>, # for comments
wie_spaet_ist_es.wav	clock.whats_the_time
timer_zehn_minuten.wav	clock.set_timer
```

Record them **in the room the panel lives in, at the distance it is spoken to from**. A clean
desk recording proves the wiring and nothing about the product — the thing under test is whether
German commands survive 2–3 m of a flat (`dobby-plan.md` §8, M1).

Keep the English-title case among them (`spiele_blinding_lights.wav` → `spotify.play_music`).
That one is why the engine was changed at all: it is the difference between an English title
arriving in the query slot and a phonetic guess at one.
