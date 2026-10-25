import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ScriptEditor, cleanScript } from '../components/ScriptEditor';
import type { Visual, VideoScript } from '../api/projects';

/* 9.3, 9.4, 9.4b: the script review can edit the infographic and 2D scenes without breaking their pairs. */

const visual = (patch: Partial<Visual>): Visual => ({
  type: 'image', title: '', subtitle: '', items: [], value: 0, prefix: '', suffix: '', label: '',
  leftTitle: '', rightTitle: '', left: [], right: [], code: '', language: '', text: '', handle: '',
  values: [], labels: [], icons: [], pose: '',
  ...patch,
});

const script = (v: Visual): VideoScript => ({
  title: 'Online orders',
  musicMood: 'calm',
  scenes: [{ narration: 'Sales grew a lot.', onScreenText: '', imagePrompt: '', visual: v, sfx: [] }],
});

describe('infographic scenes in the script editor', () => {
  it('edits one bar label and its number', () => {
    const onChange = vi.fn();
    const bars = visual({ type: 'bars', title: 'Sales', items: ['2020', '2023'], values: [4.2, 5.8], suffix: 'T' });
    render(<ScriptEditor script={script(bars)} targetSeconds={30} onChange={onChange} />);

    const scene = screen.getByRole('article', { name: 'Scene 1' });
    expect(within(scene).getByText('Bar chart')).toBeInTheDocument();
    fireEvent.change(within(scene).getAllByLabelText('Number')[1], { target: { value: '6,1' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual.values).toEqual([4.2, 6.1]);

    fireEvent.change(within(scene).getAllByLabelText('Label')[0], { target: { value: '2021' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual.items).toEqual(['2021', '2023']);
  });

  it('shows the icon of each grid point and edits the text', () => {
    const onChange = vi.fn();
    const grid = visual({ type: 'iconGrid', items: ['Fast', 'Safe', 'Cheap'], icons: ['zap', 'shield-check', 'coins'] });
    render(<ScriptEditor script={script(grid)} targetSeconds={30} onChange={onChange} />);

    expect(screen.getByText('shield-check')).toBeInTheDocument();
    fireEvent.change(screen.getAllByLabelText('Point')[2], { target: { value: 'Cheaper' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual.items).toEqual(['Fast', 'Safe', 'Cheaper']);
  });

  it('saving keeps paired positions (an emptied bar label is not dropped)', () => {
    const bars = visual({ type: 'bars', items: [' 2020 ', '', '2026'], values: [1, 2, 3] });
    expect(cleanScript(script(bars)).scenes[0].visual!.items).toEqual(['2020', '', '2026']);

    const list = visual({ type: 'list', items: [' One ', '', 'Two'] });
    expect(cleanScript(script(list)).scenes[0].visual!.items).toEqual(['One', 'Two']);
  });
});

describe('2D scenes in the script editor', () => {
  it('shows the pose and edits the speech bubble', () => {
    const onChange = vi.fn();
    const person = visual({ type: 'character', pose: 'thinking', text: 'How does it work?', icons: ['circle-question-mark'] });
    render(<ScriptEditor script={script(person)} targetSeconds={30} onChange={onChange} />);

    expect(screen.getByText('Character')).toBeInTheDocument();
    expect(screen.getByText('Thinking')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Speech bubble'), { target: { value: 'What is an API?' } });
    const v = onChange.mock.calls.at(-1)![0].scenes[0].visual;
    expect(v.text).toBe('What is an API?');
    expect(v.pose).toBe('thinking');
    expect(v.icons).toEqual(['circle-question-mark']);
  });

  it('edits the two sides of a metaphor', () => {
    const onChange = vi.fn();
    const m = visual({ type: 'metaphor', title: 'An API', subtitle: 'like a waiter', icons: ['server', 'utensils'] });
    render(<ScriptEditor script={script(m)} targetSeconds={30} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('Is like…'), { target: { value: 'like a restaurant waiter' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual).toMatchObject({ title: 'An API', subtitle: 'like a restaurant waiter' });
  });
});

describe('action scenes in the script editor', () => {
  it('edits one message of an exchange and keeps who sends it', () => {
    const onChange = vi.fn();
    const dns = visual({
      type: 'exchange',
      items: ['Browser', 'DNS server'],
      icons: ['monitor', 'server'],
      messages: [{ from: 0, to: 1, label: 'where is example.com?' }, { from: 1, to: 0, label: 'IP 93.184.216.34' }],
    });
    render(<ScriptEditor script={script(dns)} targetSeconds={30} onChange={onChange} />);

    expect(screen.getByText('Messages')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Message 2: DNS server → Browser'), { target: { value: 'IP address' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual.messages).toEqual([
      { from: 0, to: 1, label: 'where is example.com?' },
      { from: 1, to: 0, label: 'IP address' },
    ]);
  });

  it('edits the address of a browser scene', () => {
    const onChange = vi.fn();
    const page = visual({ type: 'browser', title: 'example.com', items: ['HTML', 'Styles'] });
    render(<ScriptEditor script={script(page)} targetSeconds={30} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('Web address'), { target: { value: 'erkasa.com' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual.title).toBe('erkasa.com');
  });
});
