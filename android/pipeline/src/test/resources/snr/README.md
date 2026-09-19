# SNR sweep fixtures

`WakeWordSnrSweepTest` measures how far the wake word gets with music under it. It needs
recordings, and recordings are somebody's voice in somebody's kitchen — they do not go in git,
so the test skips itself when this directory is empty.

To take the measurement:

```
snr/phrases/   one utterance of the wake phrase per file
snr/music/     what the panel gets played over
```

16 kHz mono 16-bit WAV, the format the whole pipeline runs on. Anything else is rejected by
name rather than silently mixed into nonsense.

Record the phrases at the distance the panel is spoken to from, and take the music from what
actually gets played in that room. A clean desk recording mixed with pink noise measures a
different product than the one on the wall.

The curve lands in `build/reports/wakeword-snr.txt`.
