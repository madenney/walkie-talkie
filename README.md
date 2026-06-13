# Walkie Talkie

A hands-free, voice-driven interface to the **real Claude Code engine** from your
phone. Hold a button, talk, and Claude edits code and runs commands in your
projects — on the move. Push-to-talk in, spoken replies out.

```
Android app  ──WebSocket──▶  Python server (FastAPI)  ──▶  Claude Agent SDK
 (Compose)     JSON + audio    Whisper STT / OpenAI TTS      (real claude_code engine)
```

## How it works

- **Real Claude Code, not a clone.** The server runs Claude through the
  [Claude Agent SDK](https://docs.claude.com) (`server/src/claude/agent_engine.py`),
  so you get its actual tools and behaviour — not a hand-rolled tool loop.
- **Subscription auth.** The server unsets `ANTHROPIC_API_KEY` and authenticates
  through the logged-in `claude` CLI, so usage draws on your Claude subscription
  rather than pay-as-you-go API credits.
- **Phone-surfaced approvals.** Risky tools (by default just `Bash`) pause and ask
  for permission on the phone. Approve/deny with a tap **or by saying "yes"/"no"**.
  No answer within the timeout auto-denies.
- **Persistent per-workspace sessions.** Each project keeps one continuous
  conversation. Close the app (or restart the server) and reselecting the
  workspace resumes exactly where you left off — Claude's memory *and* the
  on-screen scrollback are restored.
- **Voice in / voice out.** faster-whisper transcribes your speech; replies inside
  `<speak>…</speak>` tags are streamed to OpenAI TTS and played back, while the
  full text shows on screen.
- **One multiplexed WebSocket.** JSON text frames carry messages; binary frames
  carry audio with a 1-byte prefix (`0x01` = mic, `0x02` = TTS).

## Repository layout

```
server/        FastAPI + uvicorn server (Python 3.12)
  src/
    main.py            app entry point (src.main:app)
    ws/                WebSocket handler, protocol, session state
    claude/            agent_engine, session_store, transcript, system_prompt
    stt/ tts/          Whisper STT, OpenAI TTS
android/       Jetpack Compose app (OkHttp WS, Media3 ExoPlayer)
scripts/       server.sh (run server), deploy-app.sh (build + install on phone)
```

## Setup

### Prerequisites
- Python 3.12
- The `claude` CLI, **logged in** (`claude` → `/login`) for subscription auth.
- An OpenAI API key (optional — enables spoken TTS replies).
- Android Studio / SDK for building the app. The phone reaches the server over
  [Tailscale](https://tailscale.com) (or any reachable IP).

### Server
```bash
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -e .

cp config.example.yaml config.yaml      # edit workspaces, voice, etc.
cp ../.env.example ../.env              # optional: OPENAI_API_KEY, SERVER_IP

# Run (background). Also: start -f (foreground), stop, restart, status
../scripts/server.sh start
```

> **Do not set `ANTHROPIC_API_KEY`.** The server intentionally clears it so the
> Agent SDK uses your `claude` CLI subscription. Setting it switches to billed
> API credits.

Configure your projects in `config.yaml` under `workspaces:` (name + path), or
point `projws_path:` at a projws `projects.json`. The list is shown
alphabetically in the app.

### Android app
```bash
./scripts/deploy-app.sh        # builds the debug APK and installs over wireless ADB
```
Set the server URL in the app's settings (default `ws://100.64.0.1:8765/ws` — use
your server's Tailscale IP).

## Using it

1. Open the app and pick a project (workspace) from the top selector.
2. **Hold** the big button to talk; **release** to send. Claude replies in text and
   (if TTS is enabled) speaks the `<speak>` parts.
3. When Claude wants to run a shell command, an approval card appears. Tap
   **Approve**/**Deny**, or hold the card's mic button and say "yes" / "no".
4. Reopen any time — selecting the project resumes its conversation.

## Configuration notes

`server/config.yaml` highlights:
- `claude.model` — leave blank (`""`) to use the CLI's configured model.
- `safety.require_approval` — list of tools that prompt for approval (default
  `["Bash"]`). `safety.approval_timeout` — seconds before auto-deny.
- `stt.model_size` — Whisper model (`base.en` default). The server loads it from
  the local cache to avoid network stalls; the first run downloads it.
- `tts.voice` / `tts.speed` — OpenAI TTS voice and rate.
