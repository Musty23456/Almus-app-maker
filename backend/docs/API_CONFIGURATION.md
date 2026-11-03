# API configuration guide (Phase 9)

This guide connects ALMUS APP CREATOR to Google Gemini **without putting a Gemini key in the app**.

## 1. What you need

1. A **Gemini API key** from Google AI Studio (https://aistudio.google.com/apikey). It is a secret: it goes only into the backend's environment, never into the app, never into GitHub.
2. A place to run the backend: your computer (to try it), or any host that runs Node.js or Docker and gives you an **HTTPS** address (for example Google Cloud Run, Render, Fly.io, a VPS behind Caddy or nginx).
3. Two random secrets that you generate yourself:
   - `ALMUS_TOKEN_SECRET` (at least 32 characters): `openssl rand -hex 32`
   - an **access code** per person or phone (at least 16 characters): `openssl rand -base64 24`

## 2. Configure

Copy `backend/.env.example` to `backend/.env` (or set the same names as secrets/environment variables on your host) and fill in:

| Name | Required | Meaning |
|---|---|---|
| `GEMINI_API_KEY` | yes | Your key. Stays on the server. |
| `ALMUS_TOKEN_SECRET` | yes | Signs session tokens. Changing it signs everyone out. |
| `ALMUS_ACCESS_CODES` | yes | `name=code` pairs, comma separated, e.g. `musty=Zk3...long-random...`. A code may be stored hashed: `musty=sha256:<hex>` (create with `node scripts/hash-code.js <code>`). Each name has its own rate limit. |
| `GEMINI_MODELS` | no | Models the app may choose from, comma separated. **Check Google's current model list** and set the ones you want; the defaults are only examples. |
| `GEMINI_DEFAULT_MODEL` | no | Used when the app does not choose. Must be in `GEMINI_MODELS`. |
| `GEMINI_MAX_OUTPUT_TOKENS` | no | Output limit per answer (default 32768). Whole-project generation needs a large limit; a cut-off answer is reported to the user. |
| `RATE_LIMIT_PER_MINUTE`, `RATE_LIMIT_PER_DAY` | no | Per user (defaults 20 and 600). Protects your Gemini quota and bill. |
| `GEMINI_TIMEOUT_MS`, `GEMINI_MAX_RETRIES` | no | Upstream timeout (120000) and retries for 429/5xx/network errors (2). |
| `TOKEN_TTL_SECONDS` | no | Session lifetime (3600). The app signs in again automatically. |
| `MAX_PROMPT_CHARS`, `MAX_BODY_BYTES` | no | Request size limits. |
| `TRUST_PROXY` | no | Set to `1` **only** behind a reverse proxy you control, so failed-login limits use the real client address. |
| `PORT`, `HOST`, `GEMINI_API_BASE` | no | Listening port/host (8080, 0.0.0.0) and the Gemini address (leave default). |

The backend refuses to start (and says why, without printing secrets) if a required value is missing or weak.

## 3. Run

**On your computer (to try it):**
```
cd backend
cp .env.example .env     # then edit it
npm start
```
In the Android **emulator** use the address `http://10.0.2.2:8080` (plain http is accepted only for 10.0.2.2, localhost and 127.0.0.1). A real phone needs an `https://` address.

**With Docker:** `docker build -t almus-ai-backend backend` then `docker run -p 8080:8080 --env-file backend/.env almus-ai-backend`.

**On a host:** deploy the `backend` folder (or the Docker image), set the variables above as the host's *secrets*, and use the HTTPS address the host gives you. Run **one instance**: rate limits and login lockouts are kept in memory per instance. Set `TRUST_PROXY=1` there.

Check it: open `https://your-address/health` - you should see `{"status":"ok"}`.

## 4. Connect the app

1. Open the app: **Settings > AI assistant (Gemini) > AI backend settings**.
2. Enter the backend address (`https://...`) and your access code, tap **Save**, then **Test connection**. It lists the allowed models; pick one or keep the default.
3. Use the assistant: in a project, editor menu > **AI assistant**; or **Projects > AI** / Settings > **Generate an app with AI** for a new app.

To remove everything: AI backend settings > **Disconnect**.

## 5. How the app stays safe

- No Gemini key, no Google address and no model secret exist in the app (tests scan the sources for this).
- The access code is stored only in the Keystore-encrypted secret store; the app exchanges it for a 1-hour session token that is kept in memory only. The code is sent only to `/v1/auth/login`.
- Code leaves the phone only after you see and confirm the **list of files**. Credential files (keystores, `local.properties`, `.env`, `google-services.json`, secret-named resources) and build output are never offered; secret-looking text in the rest is replaced before sending, and the backend scans again.
- AI output is never written silently: files are checked (path, type, size, secrets), shown as before/after, and applied only when you tap **Apply**; every applied change can be undone.
- The backend only ever talks to Gemini with the key in a request header, never in a URL; it never logs prompts or answers.

## 6. HTTP API (for reference and testing)

All bodies are JSON. Errors: `{"error":{"code","message","retryable","retryAfterSeconds?"}}`.

| Call | Auth | Purpose |
|---|---|---|
| `GET /health` | none | `{"status":"ok"}` |
| `POST /v1/auth/login` `{"accessCode"}` | none | `{"token","expiresInSeconds","user"}`. 10 failures per 15 min per address lock sign-in. |
| `GET /v1/models` | Bearer | `{"default","models":[{"id"}]}` |
| `POST /v1/generate` `{"task","model?","messages":[{"role":"user"|"model","content"}],"temperature?"}` | Bearer | `{"text","model","finishReason","usage","redactions"}` |
| `POST /v1/generate/stream` (same body) | Bearer | Server-Sent Events: `meta`, `delta*`, `done` or `error`. Errors before the first byte are normal HTTP errors. |

Tasks: `chat, explain, generate_code, edit_code, plan_project, generate_project, edit_project_select, edit_project, debug, review`. The structured tasks make Gemini answer in JSON; `finishReason: "MAX_TOKENS"` means an answer was cut off.

Quick test with curl:
```
TOKEN=$(curl -s -X POST $URL/v1/auth/login -H 'content-type: application/json' -d '{"accessCode":"YOUR-CODE"}' | node -pe 'JSON.parse(require("fs").readFileSync(0)).token')
curl -s $URL/v1/generate -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{"task":"chat","messages":[{"role":"user","content":"Say hi"}]}'
```

## 7. Troubleshooting

| Message in the app | Cause / fix |
|---|---|
| "Could not reach the AI backend" | Wrong address, backend not running, or no internet. Open `/health` in a browser. |
| "The access code is not correct" / "Sign-in ... failed" | Code differs from `ALMUS_ACCESS_CODES`. After 10 failures wait 15 minutes. |
| "The AI service is not configured correctly on the server" | The Gemini key is missing, invalid, or lacks permission/billing. Fix it on the backend; see the backend log (`error` field, no secrets). |
| "model ... not available" | A model in `GEMINI_MODELS` does not exist for your key. Check Google's model list and update the variable. |
| "busy or its quota is used up" / "Too many requests" | Gemini quota or the per-user limit. Wait, or raise `RATE_LIMIT_*` / your Gemini quota. |
| "The answer was cut off" | The project or answer is too large. Ask for a smaller app, or raise `GEMINI_MAX_OUTPUT_TOKENS` (up to your model's limit). |
| "declined to answer (safety)" | Gemini's safety filter blocked the prompt. Rephrase. |
