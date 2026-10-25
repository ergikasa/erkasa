import React, { useMemo } from 'react';
import { AbsoluteFill, Audio, Sequence, Series, interpolate, useVideoConfig } from 'remotion';
import { Scene } from './Scene';
import { src } from './media';
import { RenderSpec, fpsOf, sceneFrames } from './spec';
import { applyTheme, theme } from './theme';

/** The whole video: the scenes one after another, music underneath. */
export const Video: React.FC<RenderSpec> = (spec) => {
  const fps = fpsOf(spec);
  // before any scene is drawn: every graphic reads its colours from `theme` (9.3)
  applyTheme(spec.theme);
  return (
    <AbsoluteFill style={{ backgroundColor: theme.bg }}>
      <Series>
        {spec.scenes.map((scene, i) => (
          <Series.Sequence key={i} durationInFrames={sceneFrames(scene, fps)} name={`Scene ${i + 1}`}>
            <Scene scene={scene} index={i} aspectRatio={spec.aspectRatio} captions={spec.captions !== false} />
          </Series.Sequence>
        ))}
      </Series>
      {spec.music ? <Music spec={spec} file={spec.music} volume={spec.musicVolume ?? 0.06} /> : null}
      {(spec.effects ?? []).map((e, i) => (
        <Sequence key={`sfx-${i}`} from={e.frame} name={`sfx ${i + 1}`} layout="none">
          <Audio src={src(e.file)} volume={e.volume} />
        </Sequence>
      ))}
    </AbsoluteFill>
  );
};

/** Music under speech; louder in pauses and between scenes (x this). */
const PAUSE_BOOST = 2.4;
/** How fast the music follows: ~0.25 s to duck or come back up. */
const SMOOTHING = 0.18;

/**
 * Background music with "ducking": quiet while the voice speaks, louder in the pauses, so the
 * words are always clear. Fades in at the start and out at the end; loops if it is too short.
 */
const Music: React.FC<{ spec: RenderSpec; file: string; volume: number }> = ({ spec, file, volume }) => {
  const { durationInFrames, fps } = useVideoConfig();
  const levels = useMemo(() => duckingCurve(spec, durationInFrames, fps, volume), [spec, durationInFrames, fps, volume]);
  return <Audio src={src(file)} loop volume={(f) => levels[Math.min(levels.length - 1, Math.max(0, f))]} />;
};

/** The music volume for every frame of the video. */
export function duckingCurve(spec: RenderSpec, frames: number, fps: number, speechVolume: number): number[] {
  const speaking = new Array<boolean>(frames).fill(false);
  let offset = 0;
  for (const scene of spec.scenes) {
    for (const w of scene.words ?? []) {
      // a little before and after each word, so short gaps between words do not pump the music
      const from = Math.max(0, Math.floor(offset + (w.startSeconds - 0.12) * fps));
      const to = Math.min(frames - 1, Math.ceil(offset + (w.endSeconds + 0.25) * fps));
      for (let f = from; f <= to; f++) speaking[f] = true;
    }
    offset += sceneFrames(scene, fps);
  }
  const pause = Math.min(0.4, speechVolume * PAUSE_BOOST);
  const fade = fps; // 1 s
  const out = new Array<number>(frames);
  let level = 0;
  for (let f = 0; f < frames; f++) {
    const target = speaking[f] ? speechVolume : pause;
    level += (target - level) * SMOOTHING;
    const edge = interpolate(f, [0, fade, frames - fade, frames - 1], [0, 1, 1, 0], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    });
    out[f] = +(level * edge).toFixed(4);
  }
  return out;
}
