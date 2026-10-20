/**
 * The render worker: waits for render requests from the backend on RabbitMQ, renders each
 * video with Remotion, uploads the MP4, and reports progress and the result back.
 *
 *   npm run worker
 *
 * Settings (environment variables, same names as infra/.env):
 *   RABBITMQ_URL                        full URL, or built from the next three
 *   RABBITMQ_USER / RABBITMQ_PASSWORD   default erkasa / erkasa
 *   RABBITMQ_HOST / RABBITMQ_PORT       default localhost / 5673 (the port docker compose maps)
 *   REMOTION_BROWSER_EXECUTABLE         optional: use an installed Chrome
 *
 * One video at a time (prefetch 1). A request is acknowledged only after the result is sent,
 * so if the worker is stopped mid-render, RabbitMQ gives the request to the next worker start.
 */
import fs from 'node:fs';
import path from 'node:path';
import amqp from 'amqplib';
import { Request, Result, handle } from './job';

/**
 * Reads infra/.env (the same file docker compose and the backend use), so the worker gets the
 * same RabbitMQ user, password and port. Variables already set in the shell win.
 */
function loadInfraEnv(): string | null {
  for (const candidate of ['../infra/.env', './infra/.env', '../../infra/.env']) {
    const file = path.resolve(process.cwd(), candidate);
    if (!fs.existsSync(file)) continue;
    for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith('#') || !line.includes('=')) continue;
      const key = line.slice(0, line.indexOf('=')).trim();
      let value = line.slice(line.indexOf('=') + 1).trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      if (process.env[key] === undefined) process.env[key] = value;
    }
    return file;
  }
  return null;
}

/** Node reports "cannot connect to localhost" as an AggregateError with an empty message. */
function describe(e: unknown): string {
  if (e instanceof AggregateError && e.errors?.length) {
    return e.errors.map(describe).join(' | ');
  }
  const err = e as { code?: string; message?: string; address?: string; port?: number };
  const where = err.address ? ` ${err.address}:${err.port}` : '';
  return [err.code, err.message].filter(Boolean).join(' ') + where || String(e);
}

const REQUESTS = 'render.requests';
const RESULTS = 'render.results';

function rabbitUrl(): string {
  if (process.env.RABBITMQ_URL) return process.env.RABBITMQ_URL;
  const user = encodeURIComponent(process.env.RABBITMQ_USER ?? 'erkasa');
  const pass = encodeURIComponent(process.env.RABBITMQ_PASSWORD ?? 'erkasa');
  const host = process.env.RABBITMQ_HOST ?? 'localhost';
  const port = process.env.RABBITMQ_PORT ?? '5673';
  return `amqp://${user}:${pass}@${host}:${port}`;
}

function log(...args: unknown[]) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

async function main() {
  const envFile = loadInfraEnv();
  log(envFile ? `settings from ${envFile}` : 'no infra/.env found, using defaults / shell variables');
  const url = rabbitUrl();
  log(`connecting to ${url.replace(/\/\/[^@]*@/, '//***@')}`);
  const conn = await amqp.connect(url);
  const channel = await conn.createChannel();
  await channel.assertQueue(REQUESTS, { durable: true });
  await channel.assertQueue(RESULTS, { durable: true });
  await channel.prefetch(1);

  const report = (r: Result) => {
    channel.sendToQueue(RESULTS, Buffer.from(JSON.stringify(r)), {
      persistent: true,
      contentType: 'application/json',
    });
  };

  await channel.consume(REQUESTS, async (msg) => {
    if (!msg) return;
    let req: Request | null = null;
    try {
      req = JSON.parse(msg.content.toString('utf8')) as Request;
      await handle(req, report);
    } catch (e) {
      const error = e instanceof Error ? e.message : String(e);
      log(`job ${req?.jobId ?? '?'} FAILED: ${error}`);
      if (req?.jobId) {
        report({ jobId: req.jobId, type: 'FAILED', error: error.slice(0, 450) });
      }
    } finally {
      channel.ack(msg);
    }
  });
  log(`waiting for render jobs on "${REQUESTS}" (Ctrl+C to stop)`);

  const stop = async () => {
    log('stopping');
    await channel.close().catch(() => undefined);
    await conn.close().catch(() => undefined);
    process.exit(0);
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  conn.on('close', () => {
    log('RabbitMQ connection closed; exiting so the process can be restarted');
    process.exit(1);
  });
}

main().catch((e) => {
  console.error('Worker failed to start:', describe(e));
  console.error('Check: Docker running, `docker compose ps` shows rabbitmq healthy, and RABBITMQ_PORT / USER / PASSWORD in infra/.env.');
  process.exit(1);
});