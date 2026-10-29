# Real-time 1-to-1 Chat

A small WhatsApp/Telegram-style web app: sign up, find another user, and exchange messages in real time across browsers and devices. Built as an MVP with a clear path to production.

- **Backend:** Java 21, Spring Boot 4, Postgres 16, Flyway. The messaging layer (connection handling, routing, ordering, deduplication) is written by hand on top of raw WebSockets. No STOMP, no broker, no chat framework.
- **Frontend:** React 19 + Vite, plain browser `WebSocket` and `fetch`, with a hand-written reconnecting socket client.
- **Runs locally in Docker:** `docker compose up --build`. No external services.

## In plain words

Everyone who signs in keeps one open "live line" to the server. When Alice sends a message, the server first **saves** it, then pushes it down Bob's live line if he is online. If Bob is offline, or his connection drops, nothing is lost: the message is already stored, and when he reconnects his browser asks "what did I miss?" and catches up. Every message gets a number inside its conversation, so both people always see the same order, and a message sent twice by accident (for example a retry after a bad connection) is stored only once.

## Quick start

**Prerequisite:** Docker Desktop (running).

```bash
docker compose up --build
```

Open <http://localhost:3000>.

To see it work you need two separate sessions (tabs in the same browser share one session cookie):

1. Window A (normal): register `alice`.
2. Window B (incognito, or another browser): register `bob`.
3. In A, type `bob` in "Find a user", click him, and send a message. It appears in B instantly.
4. Reload either window: the history comes back from Postgres.

Passwords must be 8 to 72 characters; usernames 3 to 30 letters, digits or `_`.

| Command | Effect |
|---|---|
| `docker compose up --build` | Build and start everything |
| `docker compose logs -f backend` | Follow backend logs |
| `docker compose down` | Stop, keep the data |
| `docker compose down -v` | Stop and wipe the database |

### Local development (hot reload)

```bash
docker compose up -d db            # Postgres only, published on localhost:5432
cd backend && mvn spring-boot:run  # API + WebSocket on :8080  (or run from the IDE)
cd frontend && npm install && npm run dev   # UI on http://localhost:5173
```

Vite proxies `/api` and `/ws` to `localhost:8080`, so the browser only ever talks to one origin.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | see `application.yml` | Database connection |
| `APP_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:8080` | Browser origins allowed to open a WebSocket (comma separated). Compose sets `http://localhost:3000`. |

## Architecture

```
Browser (React)
   |  HTTPS/HTTP  /api/*   (register, login, users, history)
   |  WebSocket   /ws      (send, ack, deliver)
   v
nginx  (serves the built UI, proxies /api and /ws)         [docker: frontend]
   v
Spring Boot  --  ConnectionRegistry (userId -> open sockets, in memory)   [docker: backend]
   v
Postgres  (users, conversations, messages; Flyway migrations)             [docker: db]
```

```
backend/src/main/java/com/chat/backend/
  auth/          register, login, logout, session cookie, security config
  user/          user search
  conversation/  conversation list, message history and catch-up (REST)
  message/       MessageService: persistence, sequencing, deduplication
  realtime/      WebSocket handler, connection registry, config  <- the hand-built core
backend/src/main/resources/db/migration/   V1 users, V2 conversations+messages, V3 index
frontend/src/    App, Login, ChatApp (state + socket lifecycle), ChatWindow, ChatSocket, api
```

### API

| Endpoint | Purpose |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | Create account / sign in. Sets the session cookie. |
| `POST /api/auth/logout`, `GET /api/auth/me` | Sign out / who am I |
| `GET /api/users?q=` | Prefix search of other users (max 20) |
| `GET /api/conversations` | My conversations with the last message |
| `GET /api/conversations/{id}/messages` | Latest page. `?beforeSeq=N` for older, `?afterSeq=N` to catch up. `limit` is capped at 100. |

### WebSocket protocol (`/ws`, JSON text frames)

| Direction | Frame | Meaning |
|---|---|---|
| client to server | `{type:"send", clientMsgId, to, body}` | Send a message. `clientMsgId` is a client-generated UUID. |
| server to client | `{type:"ack", clientMsgId, conversationId, seq}` | Stored. `seq` is the message's position in the conversation. |
| server to client | `{type:"deliver", conversationId, seq, from, body, createdAt}` | A message from the other user (also sent to the sender's other tabs). |
| server to client | `{type:"error", clientMsgId, code}` | `bad_json`, `unknown_type`, `bad_request`, `recipient_not_found`, `server_error` |

The sender's identity always comes from the authenticated session, never from the payload. Unknown fields in frames are ignored, so the protocol can grow without breaking old clients.

### Data model

- `users(id, username citext unique, password_hash, created_at)`
- `conversations(id, user_a, user_b, last_seq, a_read_seq, b_read_seq)` with `CHECK (user_a < user_b)` and `UNIQUE (user_a, user_b)`: exactly one row per pair, regardless of who wrote first.
- `messages(id, conversation_id, seq, sender_id, client_msg_id, body, created_at)` with `UNIQUE (conversation_id, seq)` and `UNIQUE (sender_id, client_msg_id)`.

## Design decisions and why

**1. Raw WebSocket handler instead of STOMP.** STOMP plus a broker would do routing and subscriptions for us, which is the core of the assignment. The hand-written part is small: a registry of sessions per user, a JSON protocol, and persistence.

**2. A per-conversation sequence number, not timestamps.** Two messages can share a millisecond, and servers' clocks drift, so ordering by time is unreliable. `seq` is allocated with `UPDATE conversations SET last_seq = last_seq + 1 ... RETURNING last_seq` inside the same transaction as the insert. The row lock serializes concurrent senders of one conversation, giving gapless, duplicate-free numbers without a global counter. Alternatives rejected: `SELECT MAX(seq)+1` (two concurrent inserts compute the same value), a global sequence (gaps and no per-conversation meaning), advisory locks (more moving parts for the same effect). The trade-off: a very hot conversation serializes on one row. For 1-to-1 chat the write rate per pair is tiny, so this is the right place to pay.

**3. Exactly-once storage on top of at-least-once delivery.** Clients retry until they get an `ack`, always with the same `clientMsgId`. The unique `(sender_id, client_msg_id)` constraint turns a retry into a no-op, and the server answers with the original `seq`. The duplicate-key exception is caught **outside** the transaction, because a failed statement aborts the whole Postgres transaction. That rollback also undoes the `last_seq` bump, so retries never leave gaps. A "check if exists, then insert" would race with a concurrent retry.

**4. Persist first, deliver after commit.** Pushing inside the transaction could show a recipient a message that then rolls back. If a push fails or the recipient is offline, nothing is lost, because the client fetches by `seq` on reconnect.

**5. Writes to a socket are serialized.** A `WebSocketSession` is not safe for concurrent sends, and two senders can target the same recipient at once. Sessions are wrapped in `ConcurrentWebSocketSessionDecorator` with a 5 s send timeout and a 512 KB buffer cap, so one stuck client is disconnected instead of growing memory without bound. The registry uses atomic `compute()` so adding and removing a user's last session cannot race.

**6. Session cookie authentication instead of JWT.** The browser attaches the cookie to the WebSocket handshake automatically, revocation is trivial (invalidate the session), and the cookie is `HttpOnly` so scripts cannot read it. Passwords use BCrypt (72-byte input cap enforced), login does a dummy hash comparison for unknown users so timing does not reveal which usernames exist, and the session id is rotated at login (session fixation). Trade-off: sessions live in memory (see limitations).

**7. WebSocket `Origin` allowlist.** CORS does not apply to WebSockets, so a malicious site could open a socket using a visitor's cookie. The server rejects origins not on the list (`APP_ALLOWED_ORIGINS`).

**8. Reconnect and catch-up on the client** (`ChatSocket.js`, `ChatApp.jsx`):
- Unacknowledged messages sit in an outbox and are replayed on every reconnect with the same ids.
- Reconnect uses exponential backoff with jitter (0.5 s doubling to a 15 s cap) so a restarted server is not hit by every client at once.
- Messages are merged by `seq` into a map, so live pushes, history pages and sync results can overlap without duplicates (the operation is idempotent).
- A live message whose `seq` skips ahead reveals a lost push, which triggers a catch-up request.
- If the handshake fails because the session is gone (401), the client stops retrying and returns to the login page.

**9. Small traps, documented in code comments.** Java's `UUID.compareTo` and Postgres compare UUIDs differently, so the canonical `user_a < user_b` ordering is done on the string form. Spring Security 6 does not persist the security context automatically, so login saves it explicitly. nginx closes idle proxied connections after 60 s by default, so `/ws` has a long `proxy_read_timeout`. Flyway migrations are append-only; changes ship as new versions.

## Known limitations and edge cases

Not handled in the MVP, listed deliberately:

| Area | Limitation | What I would do |
|---|---|---|
| Scaling | Connection registry and HTTP sessions are in memory, so only **one backend instance** works. A message for a user connected to another instance would not be pushed. | Redis pub/sub for fan-out (publish to a per-user channel, each instance subscribes for its connected users) and Spring Session on Redis. The only call sites to change are the two fan-out lines in `ChatWebSocketHandler`. |
| Restarts | Sessions are lost when the backend restarts, so users are sent back to the login page. Message history is unaffected. | Shared session store (above). |
| Liveness | No application-level heartbeat. Half-open connections are only noticed when a write fails or a proxy times out. | Ping/pong every 30 s, close on missed pongs. |
| Client outbox | The outbox lives in memory. A message still "sending..." when the tab is closed is lost. | Persist the outbox in `localStorage` or IndexedDB. |
| Abuse | No rate limiting on login or on sending. | Per-user and per-IP limits (gateway/WAF plus an application limit), lockout or backoff on failed logins. |
| CSRF | Disabled for the MVP: same-origin JSON API, `SameSite=Lax` cookie, no state change on GET. | Re-enable with a token, or require a custom header, before production. |
| Transport | HTTP only locally, so the cookie is not marked `Secure`. | TLS at the load balancer, `Secure` cookie, HSTS. |
| Privacy | Any user can search all usernames by prefix. Messages are stored in plaintext. | Exact-match or minimum-length search, optional end-to-end encryption. |
| Features | No read receipts (the `a_read_seq` and `b_read_seq` columns exist but are unused), typing indicators, attachments, edit/delete, groups, or push notifications. | Read cursors are the natural next step. |
| Secrets | Database credentials in `docker-compose.yml` are for local use. | Secrets manager, never in the repo. |
| Testing | No automated tests in the MVP. Behavior was verified manually (see below). | Integration tests for `MessageService` with Testcontainers (concurrent senders, duplicate retries), a WebSocket handler test, and component tests for `Login`. |

## How I verified it manually

- Two users in two browser sessions: messages arrive live in both directions; reload restores history from the database.
- **Ordering:** `seq` increases 1, 2, 3... regardless of who sends.
- **Retry safety:** sending the same `clientMsgId` twice from the browser console returns the same `seq` in the ack, and the recipient receives it once.
- **Access control:** a third user requesting someone else's conversation id gets 404.
- **Validation:** short passwords and bad usernames return 400, duplicate usernames 409, wrong credentials 401.
- **Backend restart (`docker compose restart backend`):** the sockets drop, the clients detect the lost session and return to login; history is intact after signing in again.

## From MVP to production on AWS

| Concern | Direction |
|---|---|
| Compute | Containers on ECS Fargate behind an Application Load Balancer. ALB supports WebSockets; raise the idle timeout (default 60 s) or rely on heartbeats. |
| Database | RDS for Postgres, Multi-AZ, automated backups. The schema and Flyway migrations work as-is. |
| Fan-out and sessions | ElastiCache (Redis) for cross-instance pub/sub and shared sessions, which removes the single-instance limit. |
| Frontend | Static build on S3 behind CloudFront instead of an nginx container (or keep nginx on ECS). |
| Edge and security | Route 53 + ACM certificates for TLS, AWS WAF for rate limiting, Secrets Manager for credentials, optionally Cognito instead of home-grown auth. |
| Delivery | Images in ECR, infrastructure as code (Terraform or CDK), CI running the tests and building the images. |
| Operations | CloudWatch metrics and alarms on connected sockets, send-to-ack latency, reconnect rate, and error frames. Autoscale on connection count, not CPU, since idle sockets cost little CPU. |
| Deploys | Rolling deploys with connection draining are safe by design: clients reconnect with backoff and then catch up by `seq`, so a deploy loses no messages. |

AWS's managed WebSocket offerings (for example API Gateway WebSocket APIs) were deliberately not used: they would hide the core feature this project is meant to demonstrate, and the assignment requires running fully locally.

## Maintaining it over time

- Database changes are new Flyway migrations only; applied migrations are never edited.
- The protocol tolerates unknown fields and answers unknown frame types with an `error`, so servers and clients can be upgraded independently. A `version` field can be added when a breaking change is needed.
- Load-bearing invariants (gapless `seq`, idempotent sends, persist-before-push) are documented in the code next to the lines that enforce them, so they are not broken by accident.
