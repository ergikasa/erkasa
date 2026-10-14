# erkasa

Prompt-to-video SaaS for Reels, TikTok and YouTube.

## Structure

| Folder | What it is |
|---|---|
| `backend/` | Spring Boot API: auth, credits, payments, video pipeline |
| `frontend/` | React web app |
| `render-worker/` | Node + Remotion: renders scenes, voice, music and subtitles into MP4 |
| `infra/` | Docker Compose for local services and deployment config |

## Requirements

- Java 21
- Docker Desktop
- Git
- Node.js 20+ (from phase 5)

## Local setup

1. Create the environment file and set your own passwords:

   ```powershell
   Copy-Item infra\.env.example infra\.env
   ```

2. Start the local services:

   ```powershell
   cd infra
   docker compose up -d
   docker compose ps
   ```

3. Local services:

   | Service | Address |
   |---|---|
   | PostgreSQL | `localhost:5432` |
   | RabbitMQ | `localhost:5672` |
   | RabbitMQ UI | http://localhost:15672 |
   | S3 storage (SeaweedFS) | http://localhost:8333 |

4. Stop the services (data is kept):

   ```powershell
   docker compose down
   ```

   To delete all local data as well: `docker compose down -v`

## Rules

- Secrets live only in `infra/.env` (local) and in server environment variables (production). Never in Git.
- One sub-task = one branch and one small commit.