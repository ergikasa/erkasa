# Sound effects (5.6)

One folder per sound. The worker picks one file per use (always the same one for the same job).

```
npm run make-sfx        # writes the free starter pack: sfx/<key>/erkasa-<key>.wav
```

Better sounds: put more `.wav` / `.mp3` files into the same folders, e.g. from
[Pixabay](https://pixabay.com/sound-effects/) or [Mixkit](https://mixkit.co/free-sound-effects/).
Only use files whose license allows commercial use without attribution. Keep them short (under 2 s)
and trimmed: the sound starts at the first sample.

| key | used for | added by |
|---|---|---|
| whoosh | cut between scenes | automatic |
| pop | a list / flow / compare item appears | automatic |
| swoosh, riser, impact, click, tick, ding, chime, notification, success, error, typing, beep, glitch, cash, coin, camera, heartbeat, boing | accents on a word of the narration | the AI (0-2 per scene) |

The keys must match `SoundEffects.CATALOG` in the backend and `SOUNDS` in `src/sfx.ts`.
