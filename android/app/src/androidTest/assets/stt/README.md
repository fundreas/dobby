# STT integration recordings

One 16 kHz mono 16-bit WAV per Tier-1 template, plus `expected.tsv` mapping each file to the
command id it must reach. `SttTemplateTest` skips itself while this directory holds only this
file, so an empty checkout still builds and tests green.

```
# expected.tsv — <wav><TAB><command id>, # for comments
wie_spaet_ist_es.wav	clock.whats_the_time
timer_zehn_minuten.wav	clock.set_timer
```

### The M6c cases — record these next

The milestone that taught the matcher to skip filler words started from a real recording: the
recogniser heard "wie spät ist **das**" for "wie spät ist **es**", and the utterance spent 2-4 s
in the local model for a sentence whose two content words were heard perfectly. That case, and
the ones around it, belong in this suite before anything else:

```
wie_spaet_ist_das.wav	clock.whats_the_time
wie_spaet_ist_es_denn_jetzt.wav	clock.whats_the_time
aehm_wie_spaet_ist_es_bitte.wav	clock.whats_the_time
stell_mir_mal_einen_timer.wav	clock.set_timer
```

All four fail on a checkout from before M6c and pass on one after it — which is the only form
of evidence this plan accepts, because what is under test is what the recogniser *really*
produces, not what anyone types into a test. Record the last one with a unit ("stell mir doch
mal einen Timer auf fünf Minuten"), or it resolves to the unit-less form and asks a question.

While you are there: `Fillers.DE` carries `dobby` on the suspicion that the tail of the wake
word lands in the capture buffer. That is a guess, and `/fallthrough` on the device settles it —
if "dobby" never appears at the front of a transcript, take it off the list.

Record them **in the room the panel lives in, at the distance it is spoken to from**. A clean
desk recording proves the wiring and nothing about the product — the thing under test is whether
German commands survive 2–3 m of a flat (`dobby-plan.md` §8, M1).

Keep the English-title case among them (`spiele_blinding_lights.wav` → `spotify.play_music`).
That one is why the engine was changed at all: it is the difference between an English title
arriving in the query slot and a phonetic guess at one.

For the same reason, record the clock's English phrasings too — `whats_the_time.wav` and
`what_time_is_it.wav`, both → `clock.whats_the_time` (`socks.specs/clock.specs.md` §6). A whole
English sentence is a harder ask of a multilingual recogniser than an English title inside a
German one, and a transcript that comes back German-spelled is a failure only a recording can
show. Record a bare `seit` as well, expecting **no** match: the argument for not claiming a
bare `zeit` is about what the recogniser really hears, so it belongs in the suite that hears.
