# Switchboard

A native Android chat app that lets you pick where your replies come from. You can switch between providers and models at any point in a conversation, and the whole history goes to whichever model is active.

## Supported providers

| Format | Examples | Base URL |
|---|---|---|
| Anthropic Messages API | Anthropic | `https://api.anthropic.com/v1` |
| OpenAI-compatible `/chat/completions` | OpenRouter, OpenAI, Groq, Together, DeepSeek, LM Studio, Ollama | `https://openrouter.ai/api/v1`, `http://10.0.2.2:11434/v1` (Ollama running on the emulator's host) |

## Features
- Replies stream in as they're generated, and you can stop them at any time
- Model picker that reads each provider's `/models` list live, with search (OpenRouter returns hundreds of models)
- Regenerate the last reply on a different provider or model, so you can compare them
- Chat history is saved on the device, with a drawer to reopen or delete past chats
- API keys are encrypted with an Android Keystore AES-GCM key and are only sent to the base URL you set for that provider
- One system prompt for all providers, plus a max-output-tokens setting
- Light and dark themes that follow the system setting
- Markdown rendering for code blocks, headings, lists, **bold**, *italic* and `inline code`

## Build
Requires JDK 17+ and the Android SDK (platform 35).

```
gradle assembleRelease     # app/build/outputs/apk/release/app-release.apk
gradle testReleaseUnitTest # streaming and protocol tests against a local mock server
```

The release build is signed with the debug key so it installs straight away. Sign it with your own key before you distribute it.

## Tools: let the model run commands
A phone can't run Android Studio, so Switchboard gives the model four tools and runs them through `runner.py` (Python standard library only, in `app/src/main/assets/`) on a machine you choose:

| Tool | Runs on | What it does |
|---|---|---|
| `run_command` | runner machine | shell command (bash), up to 1 hour, returns exit code + output tail |
| `write_file` / `read_file` | runner machine | create, overwrite or read text files (relative paths are inside `~/switchboard-workspace`) |
| `install_apk` | this phone | downloads a built `.apk` from the runner and opens Android's installer; you tap Install |

The model loops on its own (call a tool, read the result, fix, retry, up to 40 steps), so it can install packages, write a project, build it and install the APK.

**Setup (Settings, then Tools)**
- *Termux on the phone* (install Termux from F-Droid): save runner.py to Downloads, then run `pkg install -y python && termux-setup-storage`, then `python ~/storage/downloads/runner.py`, and paste the token it prints into the app. URL: `http://127.0.0.1:8765`.
- *A computer*: run `python3 runner.py --host 0.0.0.0`, then use `http://<computer-ip>:8765` with Unrestricted mode on (plain http on your network). A computer is the most reliable way to build Android projects; in Termux, Gradle needs the `aapt2` package and `android.aapt2FromMavenOverride`.

**Safety**
- Every action shows Approve / Always / Deny first. "Always" lasts for that chat until the app restarts. You can turn approval off in Settings.
- The runner requires its token, listens only on 127.0.0.1 unless you pass `--host`, and runs with your user's permissions. Stop it with Ctrl+C when you're done.
- The model needs to support tool calling (on OpenRouter, pick a model that lists tool support).

## Unrestricted mode
This switch in Settings asks you to confirm before it turns on. It lifts the app's own limits:
- plain `http://` works for any host (normally only localhost, `10.0.2.2`, `*.local` and `*.lan`)
- max output tokens go up to 1,000,000 and are also sent to OpenAI-compatible providers
- a raw JSON field (for example `{"temperature": 0.7, "provider": {"order": ["..."]}}`) is merged into every request body; `model`, `stream` and `messages` can't be overridden

It does not change what a model will or won't answer. The provider and the model you pick decide that.

## Notes
- Text only for now: no image or file attachments, and no tool use.
