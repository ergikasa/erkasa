import React from 'react';
import { Composition } from 'remotion';
import { Video } from './Video';
import { DEFAULT_FPS, RenderSpec, SIZES, fpsOf, totalFrames } from './spec';

/** Shown in Remotion Studio when no spec is given: two plain scenes. */
const PREVIEW: RenderSpec = {
  aspectRatio: 'PORTRAIT',
  title: 'Preview',
  scenes: [
    {
      image: 'https://picsum.photos/id/1018/1080/1920',
      audio: '',
      audioSeconds: 3,
      onScreenText: 'erkasa preview',
      words: [
        { word: 'This', startSeconds: 0.2, endSeconds: 0.5 },
        { word: 'is', startSeconds: 0.5, endSeconds: 0.7 },
        { word: 'a', startSeconds: 0.7, endSeconds: 0.8 },
        { word: 'preview.', startSeconds: 0.8, endSeconds: 1.4 },
      ],
    },
  ],
};

export const Root: React.FC = () => (
  <Composition
    id="Video"
    component={Video}
    defaultProps={PREVIEW}
    fps={DEFAULT_FPS}
    width={SIZES.PORTRAIT.width}
    height={SIZES.PORTRAIT.height}
    durationInFrames={totalFrames(PREVIEW)}
    // Size and length come from the spec of each video.
    calculateMetadata={({ props }) => {
      const spec = props as RenderSpec;
      const size = SIZES[spec.aspectRatio] ?? SIZES.PORTRAIT;
      return {
        fps: fpsOf(spec),
        width: size.width,
        height: size.height,
        durationInFrames: totalFrames(spec),
      };
    }}
  />
);