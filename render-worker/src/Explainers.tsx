import React from 'react';
import { Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { Icon } from './Infographics';
import { AspectRatio, Message, Word, cueTimes, messageTimes } from './spec';
import { panelStyle, theme } from './theme';

/*
 * Scenes that SHOW something happening instead of only revealing labels (9.4b):
 *
 *  - Exchange: two or three parties send messages back and forth (a sequence diagram). Each
 *    message travels from sender to receiver when the voice says its label: a DNS question and
 *    its answer, a TCP handshake, a payment and its receipt, a login and its token...
 *  - Browser: a browser window; the address is typed, the page loads and is built piece by piece.
 *
 * Both draw their whole layout from the first frame (dimmed), so a scene never starts empty
 * while it waits for a keyword, and both keep moving until the scene ends.
 */

export type Area = { width: number; height: number };

function springAt(frame: number, fps: number, atSeconds: number, damping = 14): number {
  return spring({ frame: frame - Math.round(atSeconds * fps), fps, config: { damping, stiffness: 140 } });
}

// ---------------------------------------------------------------- exchange

export const Exchange: React.FC<{
  actors: string[];
  icons: string[];
  messages: Message[];
  result?: string;
  words: Word[];
  audioSeconds: number;
  aspectRatio: AspectRatio;
  area: Area;
}> = ({ actors, icons, messages, result, words, audioSeconds, aspectRatio, area }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  const portrait = aspectRatio === 'PORTRAIT';
  const n = Math.max(2, Math.min(3, actors.length));
  const times = messageTimes(messages, words, audioSeconds);

  const W = area.width;
  const H = area.height;
  const badge = Math.min(portrait ? 200 : 210, W / (n * 2.4));
  // room under the parties for the first message label, which sits above its arrow
  const headH = badge + (portrait ? 120 : 140);
  // one row per message under the parties; rows fill the stage, and the whole diagram is centred
  const rows = Math.max(1, messages.length);
  const resultH = result ? 120 : 0;
  const rowH = Math.max(100, Math.min(portrait ? 190 : 150, (H - headH - resultH - 30) / rows));
  const contentH = headH + rows * rowH + 20 + resultH;
  const top = Math.max(0, (H - contentH) / 2);
  const x = (i: number) => (W / n) * (i + 0.5);
  const enter = springAt(frame, fps, 0.05, 15);

  // the party that just received something glows for a moment
  const received = (i: number) =>
    messages.reduce((acc, m, k) => {
      if (m.to !== i) return acc;
      const since = t - times[k].arrive;
      return since >= 0 && since < 0.6 ? Math.max(acc, 1 - since / 0.6) : acc;
    }, 0);

  const allDone = times.length ? t >= times[times.length - 1].arrive : true;
  const resultIn = result && times.length ? springAt(frame, fps, times[times.length - 1].arrive + 0.15, 12) : 0;

  return (
    <div style={{ position: 'relative', width: W, height: H, fontFamily: theme.font }}>
      <div style={{ position: 'absolute', left: 0, top, width: W, height: contentH }}>
      {/* lifelines */}
      <svg width={W} height={H} style={{ position: 'absolute', left: 0, top: 0 }}>
        {actors.slice(0, n).map((_, i) => (
          <line
            key={i}
            x1={x(i)}
            y1={headH - 10}
            x2={x(i)}
            y2={headH + rows * rowH + 10}
            stroke={theme.panelBorder}
            strokeWidth={6}
            strokeDasharray="4 14"
            strokeLinecap="round"
            opacity={enter}
          />
        ))}
      </svg>

      {/* the parties */}
      {actors.slice(0, n).map((label, i) => {
        const color = theme.palette[i % theme.palette.length];
        const glow = received(i);
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: x(i) - 200,
              top: 0,
              width: 400,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 14,
              opacity: enter,
              transform: `translateY(${(1 - enter) * -30}px)`,
            }}
          >
            <div
              style={{
                width: badge,
                height: badge,
                borderRadius: '50%',
                background: theme.panel,
                border: `6px solid ${color}`,
                boxShadow: `0 0 ${30 + glow * 50}px ${color}${glow > 0 ? 'AA' : '55'}, ${theme.shadow}`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                transform: `scale(${1 + glow * 0.12})`,
              }}
            >
              <Icon name={icons[i] ?? ''} size={badge * 0.52} color={color} />
            </div>
            <div style={{ color: theme.text, fontWeight: 850, fontSize: portrait ? 48 : 46, textAlign: 'center', lineHeight: 1.05 }}>{label}</div>
          </div>
        );
      })}

      {/* the messages */}
      {messages.map((m, k) => {
        const from = Math.min(n - 1, Math.max(0, m.from));
        const to = Math.min(n - 1, Math.max(0, m.to));
        const y = headH + rowH * (k + 0.5);
        const x1 = x(from);
        const x2 = x(to);
        const dir = x2 >= x1 ? 1 : -1;
        const color = theme.palette[from % theme.palette.length];
        const { start, arrive } = times[k];
        const p = interpolate(t, [start, arrive], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic) });
        const sent = t >= start;
        const lineStart = x1 + dir * (badge * 0.15);
        const lineEnd = x2 - dir * (badge * 0.15);
        const head = lineStart + (lineEnd - lineStart) * p;
        // the label may be a little wider than the gap between the parties
        const labelW = Math.min(W - 20, Math.abs(x2 - x1) + 140, 620);
        const fontSize = m.label.length > 18 ? (portrait ? 38 : 34) : portrait ? 46 : 42;
        return (
          <React.Fragment key={k}>
            {/* a faint guide of where the message will go, from the start */}
            <div
              style={{
                position: 'absolute',
                left: Math.min(lineStart, lineEnd),
                top: y - 3,
                width: Math.abs(lineEnd - lineStart),
                height: 6,
                borderRadius: 3,
                background: theme.panelBorder,
                opacity: sent ? 0 : 0.6 * enter,
              }}
            />
            {/* the arrow, drawn as the message travels */}
            <div
              style={{
                position: 'absolute',
                left: Math.min(lineStart, head),
                top: y - 5,
                width: Math.abs(head - lineStart),
                height: 10,
                borderRadius: 5,
                background: color,
                boxShadow: `0 0 18px ${color}`,
              }}
            />
            {p >= 1 ? (
              <div
                style={{
                  position: 'absolute',
                  left: dir > 0 ? lineEnd - 4 : lineEnd - 22,
                  top: y - 16,
                  width: 0,
                  height: 0,
                  borderTop: '16px solid transparent',
                  borderBottom: '16px solid transparent',
                  ...(dir > 0 ? { borderLeft: `26px solid ${color}` } : { borderRight: `26px solid ${color}` }),
                }}
              />
            ) : null}
            {/* the message itself: rides on the arrow, then stays above it */}
            {sent ? (
              <div
                style={{
                  ...panelStyle(),
                  position: 'absolute',
                  left: (p < 1 ? head : (lineStart + lineEnd) / 2) - labelW / 2,
                  top: y - (p < 1 ? 36 : 36 + 44),
                  width: labelW,
                  boxSizing: 'border-box',
                  padding: '10px 16px',
                  borderRadius: 999,
                  border: `3px solid ${color}`,
                  textAlign: 'center',
                  color: theme.text,
                  fontWeight: 800,
                  fontSize,
                  lineHeight: 1.1,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  transform: `scale(${p < 1 ? 0.92 : 1})`,
                }}
              >
                {m.label}
              </div>
            ) : null}
          </React.Fragment>
        );
      })}

      {/* the outcome, once every message has arrived */}
      {result ? (
        <div
          style={{
            position: 'absolute',
            left: W / 2 - 260,
            top: headH + rows * rowH + 20,
            width: 520,
            display: 'flex',
            justifyContent: 'center',
            opacity: allDone ? resultIn : 0,
            transform: `scale(${0.6 + 0.4 * resultIn})`,
          }}
        >
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 16,
              padding: '16px 34px',
              borderRadius: 999,
              background: theme.good,
              color: theme.onAccent,
              fontWeight: 900,
              fontSize: portrait ? 46 : 42,
              boxShadow: `0 0 50px ${theme.good}88`,
            }}
          >
            <Icon name="circle-check" size={portrait ? 46 : 42} color={theme.onAccent} strokeWidth={2.6} />
            {result}
          </div>
        </div>
      ) : null}
      </div>
    </div>
  );
};

// ---------------------------------------------------------------- browser

export const Browser: React.FC<{
  url: string;
  items: string[];
  words: Word[];
  audioSeconds: number;
  aspectRatio: AspectRatio;
  area: Area;
}> = ({ url, items, words, audioSeconds, aspectRatio, area }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  const portrait = aspectRatio === 'PORTRAIT';
  const W = Math.min(area.width, portrait ? 920 : 1260);
  const H = Math.min(area.height, portrait ? 980 : 760);
  const enter = springAt(frame, fps, 0.05, 15);

  // 1. the address is typed (about 14 letters a second), 2. Enter, 3. the page loads
  const typeFrom = 0.35;
  const typed = Math.max(0, Math.min(url.length, Math.floor((t - typeFrom) * 14)));
  const typedDone = typeFrom + url.length / 14;
  const cues = items.length ? cueTimes(items, words, audioSeconds) : [];
  const loadEnd = items.length ? cues[cues.length - 1] + 0.5 : Math.max(typedDone + 1.2, audioSeconds * 0.85);
  const progress = interpolate(t, [typedDone + 0.2, loadEnd], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const pressed = t >= typedDone && t < typedDone + 0.25;
  const caret = typed < url.length || Math.floor(t * 2) % 2 === 0;
  const layer = (i: number) => (cues[i] == null ? 0 : springAt(frame, fps, cues[i], 14));

  const barH = portrait ? 120 : 100;
  const pageH = H - barH;
  const pad = portrait ? 40 : 46;

  return (
    <div
      style={{
        ...panelStyle(),
        position: 'relative',
        width: W,
        height: H,
        overflow: 'hidden',
        borderRadius: 34,
        fontFamily: theme.font,
        opacity: enter,
        transform: `translateY(${(1 - enter) * 40}px) scale(${0.94 + 0.06 * enter})`,
      }}
    >
      {/* window bar + address bar */}
      <div style={{ height: barH, display: 'flex', alignItems: 'center', gap: 22, padding: '0 30px', borderBottom: `2px solid ${theme.panelBorder}` }}>
        <div style={{ display: 'flex', gap: 12 }}>
          {['#F87171', '#FBBF24', '#34D399'].map((c) => (
            <div key={c} style={{ width: 22, height: 22, borderRadius: 11, background: c }} />
          ))}
        </div>
        <div
          style={{
            flex: 1,
            height: barH * 0.56,
            borderRadius: 999,
            background: `${theme.text}0F`,
            border: `3px solid ${pressed ? theme.accent : theme.panelBorder}`,
            display: 'flex',
            alignItems: 'center',
            gap: 14,
            padding: '0 24px',
            color: theme.text,
            fontSize: portrait ? 40 : 36,
            fontWeight: 700,
          }}
        >
          <Icon name={progress >= 1 ? 'lock' : 'search'} size={portrait ? 34 : 30} color={progress >= 1 ? theme.good : theme.muted} />
          <span>
            {url.slice(0, typed)}
            <span style={{ opacity: caret && progress < 1 ? 1 : 0, color: theme.accent }}>|</span>
          </span>
        </div>
      </div>
      {/* the loading bar under the address */}
      <div style={{ position: 'absolute', left: 0, top: barH - 3, height: 6, width: `${progress * 100}%`, background: theme.accent, boxShadow: `0 0 16px ${theme.accent}` }} />

      {/* the page */}
      <div style={{ position: 'relative', height: pageH, padding: pad, boxSizing: 'border-box' }}>
        {items.length === 0 || layer(0) === 0 ? <Spinner visible={t >= typedDone} size={portrait ? 110 : 96} /> : null}
        {items.length > 0 ? (
          <Page layers={items.map((_, i) => layer(i))} portrait={portrait} width={W - pad * 2} height={pageH - pad * 2} />
        ) : null}
        {/* what is being loaded, ticked off as the voice says it */}
        {items.length > 0 ? (
          <div style={{ position: 'absolute', right: pad, bottom: pad, display: 'flex', flexDirection: 'column', gap: 12, alignItems: 'flex-end' }}>
            {items.map((item, i) => {
              const a = layer(i);
              return (
                <div
                  key={i}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '8px 18px',
                    borderRadius: 999,
                    background: theme.panel,
                    border: `3px solid ${a > 0.5 ? theme.good : theme.panelBorder}`,
                    color: theme.text,
                    fontWeight: 800,
                    fontSize: portrait ? 34 : 30,
                    opacity: 0.35 + 0.65 * a,
                    boxShadow: theme.shadow,
                  }}
                >
                  <Icon name={a > 0.5 ? 'circle-check' : 'hourglass'} size={portrait ? 32 : 28} color={a > 0.5 ? theme.good : theme.muted} />
                  {item}
                </div>
              );
            })}
          </div>
        ) : null}
      </div>
    </div>
  );
};

const Spinner: React.FC<{ visible: boolean; size: number }> = ({ visible, size }) => {
  const frame = useCurrentFrame();
  return (
    <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', opacity: visible ? 1 : 0.25 }}>
      <div
        style={{
          width: size,
          height: size,
          borderRadius: '50%',
          border: `12px solid ${theme.panelBorder}`,
          borderTopColor: theme.accent,
          transform: `rotate(${frame * 9}deg)`,
        }}
      />
    </div>
  );
};

/**
 * A web page that builds up in layers: 0 = the structure (grey blocks), 1 = the styles (colours
 * and type), 2 = the media (pictures), 3+ = extra cards. Each layer comes in when its item is said.
 */
const Page: React.FC<{ layers: number[]; portrait: boolean; width: number; height: number }> = ({ layers, portrait, width, height }) => {
  const [structure = 0, styles = 0, media = 0, extra = 0] = layers;
  const grey = `${theme.muted}33`;
  const mix = (a: string, b: string, k: number) => (k > 0.5 ? b : a);
  const block = (style: React.CSSProperties) => <div style={{ borderRadius: 14, ...style }} />;
  const cols = portrait ? 1 : 3;
  const cardW = (width * (portrait ? 0.62 : 0.66) - (cols - 1) * 20) / cols;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 22, width: portrait ? width * 0.62 : width * 0.66, opacity: structure, transform: `translateY(${(1 - structure) * 30}px)` }}>
      {/* header */}
      {block({
        height: portrait ? 70 : 56,
        width: '100%',
        background: mix(grey, `linear-gradient(90deg, ${theme.accent}, ${theme.accent2})`, styles),
        transition: 'none',
      })}
      {/* headline + text lines */}
      {block({ height: portrait ? 44 : 38, width: '70%', background: mix(grey, theme.text, styles) })}
      {block({ height: 20, width: '92%', background: grey })}
      {block({ height: 20, width: '84%', background: grey })}
      {/* cards: pictures arrive with the media layer */}
      <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
        {Array.from({ length: cols * (portrait ? 2 : 1) }).map((_, i) => (
          <div
            key={i}
            style={{
              width: portrait ? '100%' : cardW,
              height: portrait ? Math.min(150, height * 0.18) : Math.min(170, height * 0.3),
              borderRadius: 18,
              background: mix(grey, `${theme.palette[i % theme.palette.length]}33`, styles),
              border: `3px solid ${mix('transparent', theme.palette[i % theme.palette.length], styles)}`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <div style={{ opacity: media, transform: `scale(${0.5 + 0.5 * media})` }}>
              <Icon name="camera" size={portrait ? 64 : 58} color={theme.palette[i % theme.palette.length]} />
            </div>
          </div>
        ))}
      </div>
      {extra > 0 ? block({ height: 60, width: '40%', background: theme.accent, opacity: extra }) : null}
    </div>
  );
};
