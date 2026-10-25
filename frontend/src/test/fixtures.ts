import type { VideoOptions } from '../api/projects';

/** Wizard choices for fake projects in tests (the same as backend VideoOptions.classic()). */
export const testOptions: VideoOptions = {
  purpose: 'EXPLAINER',
  template: 'CLASSIC',
  audience: 'BEGINNER',
  tone: 'EDUCATIONAL',
  theme: 'DARK',
  voiceSpeed: 100,
  captionStyle: 'KARAOKE',
  captionPosition: 'BOTTOM',
};