# Background music

The render worker picks one track per video from the folder of the mood the script asks for
(`calm`, `upbeat`, `dramatic`, `inspiring`, `mysterious`, `fun`). The same project always gets
the same track. An empty folder is fine: the worker uses a track from another mood, or no music.

- Formats: mp3, m4a, aac, wav, ogg. 1-3 minutes is enough; short tracks loop.
- Only add tracks you may use in videos you publish and sell (your own, or royalty-free
  libraries whose license allows commercial use). Keep a note of where each track came from.
- The files are not committed to git (see `.gitignore`). In production they will live in
  storage (R2), like the other assets.
- Another folder: set `MUSIC_DIR` before `npm run worker`.
