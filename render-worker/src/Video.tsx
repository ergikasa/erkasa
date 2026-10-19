import React from 'react';
import { AbsoluteFill, Audio, Series, interpolate, useCurrentFrame, useVideoConfig } from 'remotion';
import { Scene } from './Scene';
import { src } from './media';
import { RenderSpec, fpsOf, sceneFrames } from './spec';

/** The whole video: the scenes one after another, music underneath. */
export const Video: React.FC<RenderSpec> = (spec) => {
  const fps = fpsOf(spec);
  return (
    <AbsoluteFill style={{ backgroundColor: '#000' }}>
      <Series>
        {spec.scenes.map((scene, i) => (
          <Series.Sequence key={i} durationInFrames={sceneFrames(scene, fps)} name={`Scene ${i + 1}`}>
            <Scene scene={scene} index={i} aspectRatio={spec.aspectRatio} captions={spec.captions !== false} />
          </Series.Sequence>
        ))}
      </Series>
      {spec.music ? <Music file={spec.music} volume={spec.musicVolume ?? 0.12} /> : null}
    </AbsoluteFill>
  );
};

/** Background music: fades in at the start and out at the end, loops if it is shorter than the video. */
const Music: React.FC<{ file: string; volume: number }> = ({ file, volume }) => {
  const frame = useCurrentFrame();
  const { durationInFrames, fps } = useVideoConfig();
  const fade = fps; // 1 second
  const v = interpolate(frame, [0, fade, durationInFrames - fade, durationInFrames], [0, volume, volume, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  return <Audio src={src(file)} volume={v} loop />;
};