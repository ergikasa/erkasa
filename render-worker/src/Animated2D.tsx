import React from 'react';
import { Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import PeepModule from 'react-peeps';
import { Icon } from './Infographics';
import { AspectRatio, Visual, Word, cueTimes } from './spec';
import { panelStyle, theme } from './theme';

/*
 * The ANIMATED_2D template scenes (9.4): a 2D person with a speech bubble, icons joined by
 * arrows, a metaphor, before / after and a zoom into an object.
 *
 * People come from Open Peeps (openpeeps.com, CC0, by Pablo Stanley) through react-peeps (MIT).
 * The LLM only picks a pose name from the catalog (backend SceneCatalog.POSES); this file turns
 * it into a body + face. Icons are the same Lucide set as the infographics.
 */

// react-peeps is CommonJS; depending on the bundler the component is the module or its default
const Peep = ((PeepModule as unknown as { default?: unknown }).default ?? PeepModule) as React.FC<Record<string, unknown>>;

type AnimVisual = Extract<Visual, { type: 'character' | 'iconLink' | 'metaphor' | 'beforeAfter' | 'zoom' }>;
type Props = { visual: AnimVisual; words: Word[]; audioSeconds: number; aspectRatio: AspectRatio };

const ANIM_TYPES = new Set(['character', 'iconLink', 'metaphor', 'beforeAfter', 'zoom']);

export function isAnimated2D(v: Visual): v is AnimVisual {
  return ANIM_TYPES.has(v.type);
}

export const Animated2D: React.FC<Props> = ({ visual, words, audioSeconds, aspectRatio }) => {
  switch (visual.type) {
    case 'character':
      return <CharacterScene {...visual} aspectRatio={aspectRatio} />;
    case 'iconLink':
      return <IconLink {...visual} cues={cueTimes(visual.items, words, audioSeconds)} aspectRatio={aspectRatio} />;
    case 'metaphor':
      return <Metaphor {...visual} cues={cueTimes([visual.title, visual.subtitle], words, audioSeconds)} aspectRatio={aspectRatio} />;
    case 'beforeAfter':
      return <BeforeAfter {...visual} words={words} audioSeconds={audioSeconds} aspectRatio={aspectRatio} />;
    case 'zoom':
      return <Zoom {...visual} cues={cueTimes(visual.items, words, audioSeconds)} aspectRatio={aspectRatio} />;
  }
};

// ---------------------------------------------------------------- the cast

/** The people a video can star; one per video, so the same person appears in every scene. */
const CAST = [
  { hair: 'ShortVolumed', facialHair: 'None' },
  { hair: 'Long', facialHair: 'None' },
  { hair: 'BunCurly', facialHair: 'None' },
  { hair: 'ShortWavy', facialHair: 'Goatee' },
];

let actor = CAST[0];

/** Picks the person of this video from its title (the same video always gets the same person). */
export function applyCast(seed: string | undefined): void {
  let h = 0;
  for (const ch of seed ?? '') h = (h * 31 + ch.charCodeAt(0)) | 0;
  actor = CAST[Math.abs(h) % CAST.length];
}

type Shot = 'full' | 'sitting' | 'bust';

/** Catalog pose -> Open Peeps body + face. Keep the names in sync with backend SceneCatalog.POSES. */
const POSES: Record<string, { body: string; face: string; shot: Shot }> = {
  standing: { body: 'ShirtPantsWB', face: 'Smile', shot: 'full' },
  waving: { body: 'EasingWB', face: 'SmileBig', shot: 'full' },
  pointing: { body: 'PointingFingerWB', face: 'Explaining', shot: 'full' },
  presenting: { body: 'Explaining', face: 'Explaining', shot: 'bust' },
  idea: { body: 'PointingUp', face: 'SmileBig', shot: 'bust' },
  thinking: { body: 'CrossedArmsWB', face: 'Serious', shot: 'full' },
  confused: { body: 'RestingWB', face: 'Concerned', shot: 'full' },
  surprised: { body: 'EasingWB', face: 'Awe', shot: 'full' },
  happy: { body: 'EasingWB', face: 'SmileBig', shot: 'full' },
  celebrating: { body: 'RoboDanceWB', face: 'SmileLol', shot: 'full' },
  worried: { body: 'CrossedArmsWB', face: 'ConcernedFear', shot: 'full' },
  working: { body: 'Geek', face: 'Driven', shot: 'bust' },
  phone: { body: 'Device', face: 'Calm', shot: 'bust' },
  walking: { body: 'WalkingWB', face: 'Smile', shot: 'full' },
  sitting: { body: 'ClosedLegWB', face: 'Calm', shot: 'sitting' },
};

/** What part of the drawing to show for each kind of shot (Open Peeps coordinates). */
const VIEW: Record<Shot, { x: string; y: string; width: string; height: string; ratio: number }> = {
  full: { x: '-250', y: '-20', width: '1500', height: '2720', ratio: 1500 / 2720 },
  sitting: { x: '-250', y: '-20', width: '1550', height: '2100', ratio: 1550 / 2100 },
  bust: { x: '-60', y: '-20', width: '980', height: '1240', ratio: 980 / 1240 },
};

/** The person in a pose, with a soft coloured disc behind so the line art reads on any theme. */
export const Person: React.FC<{ pose: string; height: number; enterAt?: number }> = ({ pose, height, enterAt = 0 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = POSES[pose] ?? POSES.standing;
  const view = VIEW[p.shot];
  const width = height * view.ratio;
  const enter = spring({ frame: frame - Math.round(enterAt * fps), fps, config: { damping: 13, stiffness: 120 } });
  // a slow "breathing" bob so the person never looks frozen
  const bob = Math.sin(frame / 14) * 6;
  return (
    <div style={{ position: 'relative', width, height, opacity: enter, transform: `translateY(${(1 - enter) * 80 + bob}px)` }}>
      <div
        style={{
          position: 'absolute',
          left: '50%',
          bottom: p.shot === 'bust' ? '-4%' : '6%',
          width: width * 1.15,
          height: width * 1.15,
          transform: `translateX(-50%) scale(${0.6 + 0.4 * enter})`,
          borderRadius: '50%',
          background: `radial-gradient(circle, ${theme.accent}40, ${theme.accent2}22 60%, transparent 72%)`,
        }}
      />
      <Peep
        style={{ position: 'absolute', inset: 0, width, height, filter: `drop-shadow(0 12px 24px rgba(0,0,0,0.25))` }}
        body={p.body}
        face={p.face}
        hair={actor.hair}
        facialHair={actor.facialHair}
        strokeColor="#111827"
        backgroundColor="#FFFFFF"
        viewBox={{ x: view.x, y: view.y, width: view.width, height: view.height }}
      />
    </div>
  );
};

// ---------------------------------------------------------------- helpers

function springAt(frame: number, fps: number, atSeconds: number, damping = 14): number {
  return spring({ frame: frame - Math.round(atSeconds * fps), fps, config: { damping, stiffness: 140 } });
}

/** An icon in a coloured circle, floating a little. */
const IconBadge: React.FC<{ name: string; size: number; color: string; appear: number; float?: number }> = ({ name, size, color, appear, float = 0 }) => {
  const frame = useCurrentFrame();
  const y = Math.sin(frame / 18 + float) * 8;
  return (
    <div
      style={{
        width: size,
        height: size,
        flex: `0 0 ${size}px`,
        borderRadius: '50%',
        background: theme.panel,
        border: `5px solid ${color}`,
        boxShadow: `0 0 40px ${color}55, ${theme.shadow}`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        opacity: appear,
        transform: `translateY(${y}px) scale(${0.4 + 0.6 * appear})`,
      }}
    >
      <Icon name={name} size={size * 0.52} color={color} />
    </div>
  );
};

// ---------------------------------------------------------------- character

const CharacterScene: React.FC<{ pose: string; text?: string; icons: string[]; aspectRatio: AspectRatio }> = ({ pose, text, icons, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const landscape = aspectRatio === 'LANDSCAPE';
  const portrait = aspectRatio === 'PORTRAIT';
  const bubble = springAt(frame, fps, 0.45, 12);
  const personH = landscape ? 760 : portrait ? 820 : 640;

  const bubbleEl = text ? (
    <div
      style={{
        ...panelStyle(),
        position: 'relative',
        maxWidth: landscape ? 760 : 880,
        padding: landscape ? '34px 44px' : '30px 40px',
        borderRadius: 40,
        background: theme.panel,
        border: `4px solid ${theme.accent}`,
        color: theme.text,
        fontFamily: theme.font,
        fontWeight: 800,
        fontSize: landscape ? 58 : portrait ? 60 : 50,
        lineHeight: 1.15,
        textAlign: 'center',
        opacity: bubble,
        transform: `scale(${0.5 + 0.5 * bubble})`,
        transformOrigin: landscape ? 'left bottom' : 'center bottom',
      }}
    >
      {text}
      {/* the tail of the bubble points at the person */}
      <div
        style={{
          position: 'absolute',
          width: 0,
          height: 0,
          ...(landscape
            ? { left: -34, bottom: 50, borderTop: '22px solid transparent', borderBottom: '22px solid transparent', borderRight: `34px solid ${theme.accent}` }
            : { left: '50%', bottom: -34, marginLeft: -22, borderLeft: '22px solid transparent', borderRight: '22px solid transparent', borderTop: `34px solid ${theme.accent}` }),
        }}
      />
    </div>
  ) : null;

  const iconsEl = icons.length ? (
    <div style={{ display: 'flex', gap: 40, justifyContent: 'center' }}>
      {icons.slice(0, 2).map((name, i) => (
        <IconBadge key={i} name={name} size={landscape ? 170 : 150} color={theme.palette[(i + 1) % theme.palette.length]} appear={springAt(frame, fps, 0.8 + i * 0.3, 12)} float={i * 2} />
      ))}
    </div>
  ) : null;

  if (landscape) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 70, width: '100%' }}>
        <Person pose={pose} height={personH} />
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 50 }}>
          {bubbleEl}
          {iconsEl}
        </div>
      </div>
    );
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 36, width: '100%' }}>
      {bubbleEl}
      <div style={{ position: 'relative', display: 'flex', alignItems: 'flex-end', justifyContent: 'center', gap: 20 }}>
        <Person pose={pose} height={text ? personH * 0.78 : personH} />
        {iconsEl ? <div style={{ alignSelf: 'center' }}>{iconsEl}</div> : null}
      </div>
    </div>
  );
};

// ---------------------------------------------------------------- icons joined by arrows

const IconLink: React.FC<{ items: string[]; icons: string[]; cues: number[]; aspectRatio: AspectRatio }> = ({ items, icons, cues, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  // tall screens: top to bottom; wide and square: left to right
  const vertical = aspectRatio === 'PORTRAIT';
  const square = aspectRatio === 'SQUARE';
  const n = items.length;
  const size = vertical ? Math.min(210, 900 / n) : square ? Math.min(170, 560 / n) : Math.min(250, 1200 / n);
  const span = square ? 760 : 1500;
  const gap = vertical ? 70 : Math.min(square ? 110 : 200, (span - n * size) / Math.max(1, n - 1));

  return (
    <div style={{ display: 'flex', flexDirection: vertical ? 'column' : 'row', alignItems: 'center', fontFamily: theme.font }}>
      {items.map((label, i) => {
        const a = springAt(frame, fps, cues[i], 12);
        const color = theme.palette[i % theme.palette.length];
        // the link to the next icon grows when the next one is said; then a dot keeps travelling on it
        const next = cues[i + 1];
        const grow = next == null ? 0 : interpolate(t, [next - 0.4, next], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
        const travel = next == null || t < next ? -1 : ((t - next) * 0.8) % 1;
        return (
          <React.Fragment key={i}>
            <div style={{ display: 'flex', flexDirection: vertical ? 'row' : 'column', alignItems: 'center', gap: vertical ? 34 : 22, width: vertical ? 760 : size + (square ? 30 : 60) }}>
              <IconBadge name={icons[i]} size={size} color={color} appear={a} float={i} />
              <div
                style={{
                  color: theme.text,
                  fontWeight: 850,
                  fontSize: vertical ? 54 : square ? 36 : 46,
                  lineHeight: 1.1,
                  textAlign: vertical ? 'left' : 'center',
                  opacity: a,
                }}
              >
                {label}
              </div>
            </div>
            {i < n - 1 ? <Link vertical={vertical} length={vertical ? gap : gap} grow={grow} travel={travel} color={theme.palette[(i + 1) % theme.palette.length]} offset={vertical ? size / 2 - 380 : 0} /> : null}
          </React.Fragment>
        );
      })}
    </div>
  );
};

/** An arrow that grows, with a small dot moving along it (data, a message, a package...). */
const Link: React.FC<{ vertical: boolean; length: number; grow: number; travel: number; color: string; offset: number }> = ({ vertical, length, grow, travel, color, offset }) => (
  <div
    style={{
      position: 'relative',
      width: vertical ? 12 : length,
      height: vertical ? length : 12,
      transform: vertical ? `translateX(${offset}px)` : undefined,
      margin: vertical ? '6px 0' : '0 0 70px',
    }}
  >
    <div
      style={{
        position: 'absolute',
        left: 0,
        top: 0,
        width: vertical ? 10 : `${grow * 100}%`,
        height: vertical ? `${grow * 100}%` : 10,
        borderRadius: 5,
        background: color,
        boxShadow: `0 0 16px ${color}`,
      }}
    />
    {grow > 0.95 ? (
      <div
        style={{
          position: 'absolute',
          ...(vertical
            ? { left: -12, bottom: -20, borderLeft: '17px solid transparent', borderRight: '17px solid transparent', borderTop: `22px solid ${color}` }
            : { right: -20, top: -12, borderTop: '17px solid transparent', borderBottom: '17px solid transparent', borderLeft: `22px solid ${color}` }),
          width: 0,
          height: 0,
        }}
      />
    ) : null}
    {travel >= 0 ? (
      <div
        style={{
          position: 'absolute',
          width: 26,
          height: 26,
          borderRadius: 13,
          background: theme.text,
          boxShadow: `0 0 20px ${color}`,
          ...(vertical ? { left: -8, top: `calc(${travel * 100}% - 13px)` } : { top: -8, left: `calc(${travel * 100}% - 13px)` }),
        }}
      />
    ) : null}
  </div>
);

// ---------------------------------------------------------------- metaphor

const Metaphor: React.FC<{ title: string; subtitle: string; icons: string[]; cues: number[]; aspectRatio: AspectRatio }> = ({ title, subtitle, icons, cues, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const vertical = aspectRatio === 'PORTRAIT';
  const square = aspectRatio === 'SQUARE';
  const first = springAt(frame, fps, Math.min(cues[0], 0.3), 12);
  const second = springAt(frame, fps, Math.max(cues[1], 0.8), 12);
  const sign = springAt(frame, fps, Math.max(cues[1] - 0.3, 0.6), 16);
  const size = vertical ? 300 : square ? 210 : 330;

  const side = (icon: string, text: string, appear: number, color: string, i: number) => (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 30, width: vertical ? 860 : square ? 340 : 620 }}>
      <IconBadge name={icon} size={size} color={color} appear={appear} float={i * 3} />
      <div style={{ color: i === 0 ? theme.text : color, fontWeight: 900, fontSize: vertical ? 64 : square ? 44 : 60, lineHeight: 1.1, textAlign: 'center', opacity: appear }}>
        {text}
      </div>
    </div>
  );

  return (
    <div style={{ display: 'flex', flexDirection: vertical ? 'column' : 'row', alignItems: 'center', gap: vertical ? 30 : square ? 10 : 40, fontFamily: theme.font }}>
      {side(icons[0] ?? '', title, first, theme.accent, 0)}
      <div
        style={{
          color: theme.accent2,
          fontWeight: 900,
          fontSize: square ? 100 : 140,
          lineHeight: 1,
          opacity: sign,
          transform: `scale(${0.3 + 0.7 * sign}) rotate(${(1 - sign) * -90}deg)`,
          textShadow: `0 0 40px ${theme.accent2}88`,
        }}
      >
        ≈
      </div>
      {side(icons[1] ?? '', subtitle, second, theme.accent2, 1)}
    </div>
  );
};

// ---------------------------------------------------------------- before / after

const BeforeAfter: React.FC<{
  leftTitle: string;
  rightTitle: string;
  left: string[];
  right: string[];
  icons: string[];
  words: Word[];
  audioSeconds: number;
  aspectRatio: AspectRatio;
}> = ({ leftTitle, rightTitle, left, right, icons, words, audioSeconds, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const vertical = aspectRatio !== 'LANDSCAPE';
  // before during the first half of the voice, after during the second (like "compare")
  const leftCues = cueTimes([leftTitle, ...left], words, audioSeconds * 0.5);
  const rightCues = cueTimes([rightTitle, ...right], words.filter((w) => w.startSeconds >= audioSeconds * 0.4), audioSeconds)
    .map((c) => Math.max(c, audioSeconds * 0.45));
  const arrow = springAt(frame, fps, rightCues[0] - 0.3, 16);

  const card = (title: string, rows: string[], cues: number[], icon: string, color: string, dim: boolean) => {
    const a = springAt(frame, fps, cues[0], 13);
    // the "before" side fades back a little once the "after" side arrives
    const fade = dim ? interpolate(frame / fps, [rightCues[0], rightCues[0] + 0.6], [1, 0.55], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }) : 1;
    return (
      <div
        style={{
          ...panelStyle(),
          flex: 1,
          width: vertical ? '100%' : undefined,
          boxSizing: 'border-box',
          padding: vertical ? '30px 36px' : '40px 36px',
          display: 'flex',
          flexDirection: vertical ? 'row' : 'column',
          alignItems: 'center',
          gap: vertical ? 34 : 24,
          borderTop: `10px solid ${color}`,
          opacity: a * fade,
          transform: `translateY(${(1 - a) * 50}px) scale(${dim ? 0.97 + 0.03 * fade : 1})`,
          filter: dim && fade < 1 ? `grayscale(${(1 - fade) * 1.6})` : undefined,
        }}
      >
        <IconBadge name={icon} size={vertical ? 170 : 210} color={color} appear={a} />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, alignItems: vertical ? 'flex-start' : 'center' }}>
          <div style={{ color, fontWeight: 900, fontSize: vertical ? 56 : 58, lineHeight: 1.05, textAlign: vertical ? 'left' : 'center' }}>{title}</div>
          {rows.map((row, i) => {
            const r = springAt(frame, fps, cues[i + 1], 15);
            return (
              <div key={i} style={{ color: theme.text, fontWeight: 700, fontSize: vertical ? 40 : 40, lineHeight: 1.15, opacity: r, transform: `translateX(${(1 - r) * 30}px)`, textAlign: vertical ? 'left' : 'center' }}>
                {row}
              </div>
            );
          })}
        </div>
      </div>
    );
  };

  return (
    <div style={{ display: 'flex', flexDirection: vertical ? 'column' : 'row', alignItems: 'center', gap: 30, width: '100%', fontFamily: theme.font }}>
      {card(leftTitle, left, leftCues, icons[0] ?? '', theme.bad, true)}
      <div style={{ color: theme.accent, fontSize: 110, fontWeight: 900, opacity: arrow, transform: `scale(${0.4 + 0.6 * arrow}) rotate(${vertical ? 90 : 0}deg)` }}>➜</div>
      {card(rightTitle, right, rightCues, icons[1] ?? '', theme.good, false)}
    </div>
  );
};

// ---------------------------------------------------------------- zoom

const Zoom: React.FC<{ title: string; icons: string[]; items: string[]; cues: number[]; aspectRatio: AspectRatio }> = ({ title, icons, items, cues, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  const landscape = aspectRatio === 'LANDSCAPE';
  const portrait = aspectRatio === 'PORTRAIT';
  const W = landscape ? 1600 : 940;
  const H = landscape ? 760 : portrait ? 1040 : 760;
  const cx = W / 2;
  const cy = H / 2 + (landscape ? 0 : 20);
  const enter = springAt(frame, fps, 0.1, 13);
  // the camera pushes in until the first detail is said, then holds
  const push = interpolate(t, [0.2, Math.max(0.8, cues[0] ?? 1)], [0.75, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic) });
  const lens = landscape ? 220 : portrait ? 190 : 150;
  const n = items.length;
  // callouts around the lens: left / right in landscape, above / below on tall screens
  const spots = items.map((_, i) => {
    if (!portrait) {
      // wide and square: two columns of callouts, left and right of the lens
      const sideLeft = i % 2 === 0;
      const row = Math.floor(i / 2);
      const rows = Math.ceil(n / 2);
      const dx = landscape ? 560 : W / 2 - 130;
      return { x: sideLeft ? cx - dx : cx + dx, y: cy + (row - (rows - 1) / 2) * (landscape ? 230 : 170) };
    }
    // an ellipse far enough from the lens that no callout covers it
    const angle = -Math.PI / 2 + (i / n) * 2 * Math.PI;
    const rx = W / 2 - 190;
    const ry = lens + (portrait ? 290 : 170);
    return { x: cx + Math.cos(angle) * rx, y: cy + Math.sin(angle) * ry };
  });

  return (
    <div style={{ position: 'relative', width: W, height: H, fontFamily: theme.font }}>
      <svg width={W} height={H} style={{ position: 'absolute', left: 0, top: 0 }}>
        {spots.map((s, i) => {
          const a = interpolate(t, [cues[i], cues[i] + 0.4], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
          const ex = cx + (s.x - cx) * a;
          const ey = cy + (s.y - cy) * a;
          return <line key={i} x1={cx} y1={cy} x2={ex} y2={ey} stroke={theme.palette[i % theme.palette.length]} strokeWidth={6} strokeDasharray="14 10" opacity={0.9} />;
        })}
      </svg>
      {/* the object inside a lens */}
      <div
        style={{
          position: 'absolute',
          left: cx - lens,
          top: cy - lens,
          width: lens * 2,
          height: lens * 2,
          borderRadius: '50%',
          background: theme.panel,
          border: `10px solid ${theme.accent}`,
          boxShadow: `0 0 60px ${theme.accent}66, ${theme.shadow}`,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          opacity: enter,
          transform: `scale(${push * (0.6 + 0.4 * enter)})`,
        }}
      >
        <Icon name={icons[0] ?? ''} size={lens * 0.85} color={theme.accent} strokeWidth={1.6} />
        <div style={{ marginTop: 8, maxWidth: lens * 1.6, color: theme.text, fontWeight: 900, fontSize: Math.round(lens * 0.22), lineHeight: 1.05, textAlign: 'center' }}>{title}</div>
      </div>
      {items.map((detail, i) => {
        const a = springAt(frame, fps, cues[i] + 0.25, 12);
        const color = theme.palette[i % theme.palette.length];
        const w = landscape ? 380 : portrait ? 360 : 280;
        return (
          <div
            key={i}
            style={{
              ...panelStyle(),
              position: 'absolute',
              left: spots[i].x - w / 2,
              top: spots[i].y - 50,
              width: w,
              minHeight: 100,
              boxSizing: 'border-box',
              padding: '18px 22px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              textAlign: 'center',
              borderColor: color,
              color: theme.text,
              fontWeight: 800,
              fontSize: landscape ? 42 : 40,
              lineHeight: 1.1,
              opacity: a,
              transform: `scale(${0.5 + 0.5 * a})`,
            }}
          >
            {detail}
          </div>
        );
      })}
    </div>
  );
};
