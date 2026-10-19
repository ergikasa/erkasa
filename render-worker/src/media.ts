import { staticFile } from 'remotion';

/** http(s) URLs are used as they are; anything else is a file in the bundle's public folder. */
export function src(file: string): string {
  return /^https?:\/\//i.test(file) ? file : staticFile(file);
}