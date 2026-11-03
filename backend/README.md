# ALMUS AI backend

A small Node.js service (no dependencies, Node 18.17 or newer) between the ALMUS APP CREATOR Android app and Google Gemini.

```
Android app  --(HTTPS, short-lived session token)-->  this backend  --(Gemini API key)-->  Google Gemini
```

The **Gemini API key lives only here**. The app stores the backend address and an access code (encrypted with an Android Keystore key).

| Run | Command |
|---|---|
| Tests (30, need no network and no key) | `npm test` |
| Start | `cp .env.example .env`, fill it in, then `npm start` |
| Docker | `docker build -t almus-ai-backend .` then `docker run -p 8080:8080 --env-file .env almus-ai-backend` |

Full setup, deployment and API reference: **[docs/API_CONFIGURATION.md](docs/API_CONFIGURATION.md)**.

What it does: signs users in with an access code, hands out 1-hour session tokens (HMAC-signed), rate-limits per user,
removes secret-looking text from prompts, applies reviewed task instructions (chat, explain, generate/edit code, plan/generate/edit
project, debug, review), calls Gemini with retries and timeouts, streams answers (Server-Sent Events) and turns every failure into
a clear, key-free error. It never logs prompts, answers, tokens, access codes or the API key.
