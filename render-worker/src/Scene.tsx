import React from 'react';
import { AbsoluteFill, Audio, Img, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { Captions } from './Captions';
import { Graphic } from './Graphics';
import { src } from './media';
import { AspectRatio, SpecScene, normalizeVisual } from './spec';

/** Different "camera moves" so consecutive scenes do not all look the same. */
const MOVES = [
  { fromScale: 1.0, toScale: 1.15, fromX: 0, toX: -3, fromY: 0, toY: -2 },
  { fromScale: 1.15, toScale: 1.0, fromX: -3, toX: 2, fromY: 1, toY: 0 },
  { fromScale: 1.05, toScale: 1.18, fromX: 2, toX: -1, fromY: -2, toY: 1 },
  { fromScale: 1.18, toScale: 1.05, fromX: 0, toX: 3, fromY: 2, toY: -1 },
];

const FADE_FRAMES = 8;

/**
 * One scene: the image with a slow Ken Burns move, the voice-over, the on-screen text
 * at the start, and word-by-word captions.
 */
export const Scene: React.FC<{ scene: SpecScene; index: number; aspectRatio: AspectRatio; captions: boolean }> = ({
  scene,
  index,
  aspectRatio,
  captions,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();
  const move = MOVES[index % MOVES.length];
  const progress = frame / Math.max(1, durationInFrames - 1);

  const scale = interpolate(progress, [0, 1], [move.fromScale, move.toScale]);
  const x = interpolate(progress, [0, 1], [move.fromX, move.toX]);
  const y = interpolate(progress, [0, 1], [move.fromY, move.toY]);
  const opacity = interpolate(frame, [0, FADE_FRAMES], [0, 1], { extrapolateRight: 'clamp' });
  const visual = normalizeVisual(scene.visual);

  if (visual.type !== 'image') {
    return (
      <AbsoluteFill style={{ opacity }}>
        <Graphic visual={visual} words={scene.words} audioSeconds={scene.audioSeconds} aspectRatio={aspectRatio} />
        {captions ? <Captions words={scene.words} aspectRatio={aspectRatio} /> : null}
        {scene.audio ? <Audio src={src(scene.audio)} /> : null}
      </AbsoluteFill>
    );
  }

  return (
    <AbsoluteFill style={{ opacity }}>
      <AbsoluteFill style={{ overflow: 'hidden' }}>
        <Img
          src={src(scene.image!)}
          style={{
            width: '100%',
            height: '100%',
            objectFit: 'cover',
            transform: `scale(${scale}) translate(${x}%, ${y}%)`,
          }}
        />
      </AbsoluteFill>
      {/* darker at the bottom and top so white text is always readable */}
      <AbsoluteFill
        style={{
          background:
            'linear-gradient(to bottom, rgba(0,0,0,0.45) 0%, rgba(0,0,0,0) 25%, rgba(0,0,0,0) 55%, rgba(0,0,0,0.55) 100%)',
        }}
      />
      {scene.onScreenText ? <OnScreenText text={scene.onScreenText} aspectRatio={aspectRatio} fps={fps} /> : null}
      {captions ? <Captions words={scene.words} aspectRatio={aspectRatio} /> : null}
      {scene.audio ? <Audio src={src(scene.audio)} /> : null}
    </AbsoluteFill>
  );
};

/** Big headline at the top for the first 2.5 seconds of the scene. */
const OnScreenText: React.FC<{ text: string; aspectRatio: AspectRatio; fps: number }> = ({ text, aspectRatio, fps }) => {
  const frame = useCurrentFrame();
  const show = 2.5 * fps;
  if (frame > show + 10) {
    return null;
  }
  const enter = spring({ frame, fps, config: { damping: 14, stiffness: 120 } });
  const leave = interpolate(frame, [show, show + 10], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const portrait = aspectRatio === 'PORTRAIT';
  return (
    <AbsoluteFill style={{ justifyContent: 'flex-start', alignItems: 'center', paddingTop: portrait ? 220 : 90 }}>
      <div
        style={{
          maxWidth: '86%',
          textAlign: 'center',
          fontFamily: 'Inter, "Segoe UI", Arial, sans-serif',
          fontWeight: 900,
          fontSize: portrait ? 84 : 76,
          lineHeight: 1.05,
          color: '#fff',
          textTransform: 'uppercase',
          letterSpacing: -1,
          textShadow: '0 6px 24px rgba(0,0,0,0.6)',
          opacity: leave,
          transform: `scale(${0.7 + 0.3 * enter}) translateY(${(1 - enter) * -40}px)`,
        }}
      >
        {text}
      </div>
    </AbsoluteFill>
  );
};
