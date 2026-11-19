# Switchboard

**Download:** the APK is attached to the [latest release](https://github.com/smdc19812011/Switchboard/releases/latest) (Android 8.0+). On your phone, open it and allow installing from your browser or files app when asked. A new release is published automatically whenever `apk/Switchboard.apk` is updated.

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
| `send_file` | this phone | puts any file from the tool machine into the chat (up to 200 MB) with Open, Save and Share buttons; no approval needed |

The model loops on its own (call a tool, read the result, fix, retry, up to 40 steps), so it can install packages, write a project, build it and install the APK.

**Where tools run (Settings, then Tools, then "Run on")**
- **This phone** (default, no setup): commands run inside Switchboard with Android's toybox `sh`, and files live in the app's private storage. Android gives apps no package manager or compilers, so this can't build APKs.
- **Termux** (full Linux on the phone, no computer): Switchboard writes `runner.py` into Termux, installs Python if needed, and starts it through Termux's `RUN_COMMAND` API, either automatically or with "Start runner now". One-time setup: install Termux from F-Droid, run `mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings` in Termux, and grant Switchboard the Termux permission. The model is told how to build an APK in Termux without Gradle (aapt2, javac, d8, apksigner).
- **Computer**: run `python3 runner.py --host 0.0.0.0` and enter its URL and token.

**Safety**
- Every action shows Approve / Always / Deny first. "Always" lasts for that chat until the app restarts. You can turn approval off in Settings.
- The runner requires its token, listens only on 127.0.0.1 unless you pass `--host`, and runs with your user's permissions. Stop it with Ctrl+C when you're done.
- The model needs to support tool calling (on OpenRouter, pick a model that lists tool support).

## Interactive terminals

Programs that need someone at the keyboard (installers that ask questions, menus, full-screen text UIs such as htop or nano, REPLs, serial monitors, ssh, adb shell) run in real terminals on the Termux or computer runner (macOS/Linux/Termux; not Windows, not "This phone").
- The model uses `terminal_start` (asks for approval like any command), then `terminal_send` to type, with keys like `{ENTER}`, `{UP}`, `{CTRL+C}`, and `terminal_read` to see the screen.
- The terminal button at the top right of the chat opens the terminal window (it starts minimized). It lights up while the model is running something and shows how many terminals there are.
- The **Activity** tab shows every command the model runs (`run_command`) with its output, live, plus a line for each other tool it uses.
- The window has a tab per terminal, **+** for your own shell, maximize, and minimize. Minimizing keeps the programs running. You can drag it by its title bar.
- It uses xterm.js (MIT), so colors and cursor movement work. Tap the terminal to type. The extra-keys row has Ctrl, ^C, Esc, Tab, arrows, Home/End and PgUp/PgDn, plus text size.
- On a computer, copy the new `runner.py` and restart it to get terminal support. Termux updates itself automatically.

## Local models (GGUF on the phone)

Settings > Add provider > **Local model (GGUF on this phone)** runs a model on the phone with the built-in [llama.cpp](https://github.com/ggml-org/llama.cpp) engine (MIT). It works offline, keeps everything on the phone and needs no API key.
- **Search Hugging Face** right in the dialog: type a name (e.g. "qwen 3b"), open a result to see its files with quantization and size, and tap one to download. Q4_K_M is marked as recommended, files too big for the phone's memory are flagged, and split models download all their parts.
- **Downloads run in the background**: the provider is saved as soon as a download (or copy) starts. The download keeps going with a progress notification, plus wake and Wi-Fi locks, when you close the dialog, turn off the screen or fold the phone. A notification says when it's ready. The provider list shows the progress.
- **Get a model by link**: paste a `.gguf` link and tap Download. Hugging Face `…/blob/…` links are converted automatically, downloads resume, and gated models take an access token. Or tap **Choose file** for a `.gguf` already on the phone: it's used where it is if Switchboard has All files access, otherwise it's copied into the app.
- **Settings**: context size (default 8192 tokens), CPU threads, and minutes idle before the model is unloaded to free memory. Use **Load now to test** to check that it loads.
- How it works: the app starts llama.cpp's `llama-server` on 127.0.0.1 with a random key and talks to it like any OpenAI-compatible provider. So streaming, tool calls (via the model's own chat template), load balancing and fallbacks all work the same. Only one local model is loaded at a time.
- **Bigger than memory (MoE models)**: Mixture of Experts models only use a few experts per token. Examples are Qwen3-30B-A3B (about 17 GB at Q4, 3B used per token) and gpt-oss-20b. The app reads a model's header, on the phone or with a 2 MB ranged request to Hugging Face, to spot MoE models. It marks them "bigger than memory: runs from storage" instead of "too big", up to about twice the phone's RAM. **Bigger than memory mode** turns on automatically for large files and tunes the engine for this:
  - weights are memory-mapped and read from storage as needed, with no warm-up pass
  - the conversation cache is 8-bit (it falls back automatically for models that can't use it)
  - work buffers are smaller and the context defaults to 4096

  It's slower, and dense models that don't fit are still flagged, because every token needs all of their weights.
- **Status bar**: while a local model works, the chat shows what it's doing:
  - loading into memory (GB loaded and a percentage)
  - reading your message (tokens read, from the engine's own progress reports)
  - writing (tokens per second)

  A thin second bar shows how much of the model is in memory right now, read from `/proc`. That's the useful number for MoE and bigger-than-memory models, which read weights from storage as they go. "Load now to test" shows the same bar.
- It needs a 64-bit ARM phone. Small models (1–4B parameters, Q4_K_M) are the practical size, and a model needs a bit more free memory than its file size. The engine is rebuilt with `tools/build-llama-server.sh`.

## Embedded tools

Settings > **Embedded tools** lists tools built into the app. The AI can use them in every chat (with tools on), whatever model you use, even a text-only one: the model writes the request, and the tool makes the result.

- **Open Higgsfield AI** (based on [Open-Higgsfield-AI](https://github.com/smdc19812011/Open-Higgsfield-AI), MIT). Image, video and lip-sync generation through kie.ai, with 21 models. Add your kie.ai API key (stored encrypted, sent only to api.kie.ai) and tap **Test** to see your credits.
  - `studio_image` makes images from text (Nano Banana 2/Pro, GPT Image 2, Grok Imagine). It also edits or combines up to 14 images from the chat (Nano Banana Edit, GPT Image 2 Edit, Qwen).
  - `studio_video` makes videos from text, or animates a start image (Kling 2.1, Seedance 2, Wan 3, Sora 2, Veo 3).
  - `studio_lip_sync` turns a portrait plus audio into a talking video (OmniHuman 1.5), or re-syncs a video to new audio.
  - Switchboard uploads reference files, waits for the job and downloads the result into the chat. You choose default models, and **Ask before each generation** (on by default) confirms every paid job.

- **Heretic** ([p-e-w/heretic](https://github.com/p-e-w/heretic), AGPL-3.0, installed on your computer from PyPI, not bundled). Modifies a Hugging Face model without training. By default it removes refusals; presets reduce slop, sycophancy or humor, shorten answers, or change emotional tone. It needs the **Computer** runner (Python + PyTorch, ideally an NVIDIA GPU; Linux, macOS or WSL), because it can't run on a phone.
  - **Check computer** reports Python, PyTorch, GPU memory and whether Heretic is installed. **Install / update** installs it (and PyTorch if missing), shown live in the terminal window.
  - **Start Heretic** fetches the chosen preset's config, sets 4-bit loading and the number of trials, and runs it in a terminal you can watch. With **Finish by itself**, it picks the best result and saves it to `heretic/out/<model>-heretic`. Without it, you choose in the terminal.
  - **Convert and download** turns the saved model into a GGUF with llama.cpp on the computer (Q8_0, BF16 or F16), downloads it and adds it as a local model provider.
  - The AI gets `heretic_run` and `heretic_to_phone`, and each one asks for approval first.

## Build Android apps on the phone (no Termux)
In **This phone** mode the model has a `build_apk` tool. It writes the app as a web app (`index.html` plus CSS, JS and assets) in the workspace, and Switchboard packages it into a real, signed APK that's attached to the chat with an **Install** button.
- Runtime: a 3 KB WebView activity (`tools/apk-template/`, rebuild with `build.sh`) shows `assets/www/index.html` full screen. JavaScript and localStorage are on, and links to websites open in the browser.
- Switchboard writes the binary AndroidManifest itself (package, version, label, permissions, orientation) and signs with apksig (v2) using a key generated once per phone, so rebuilt versions install as updates.
- Apps use the default Android icon. Apps that need native Java/Kotlin code still need Termux mode.

## Devices: USB, Bluetooth and network
These tools always run on the phone, because that's where the hardware is:

| Tool | What it does |
|---|---|
| `list_devices` | USB devices plugged in (VID/PID, matching serial driver), Bluetooth (paired, plus a Classic and BLE scan), and the network (phone IP, mDNS/Bonjour services) |
| `device_connect` | opens `usb_serial` (built-in drivers: CDC-ACM, CP210x, CH34x, FTDI), `usb_raw` (bulk/interrupt endpoints of any interface), `bt_serial` (SPP), `ble` (GATT; lists services and characteristics), `tcp`, `udp` |
| `device_write` / `device_read` | send and receive bytes as text, hex or base64; BLE reads, writes and notifications by characteristic UUID |
| `device_close` | closes a handle |
| `adb` | built-in ADB client (any mode, nothing to install): `shell`, `push`, `install` on another Android device over ADB-over-TCP (port 5555). The first connection asks the device to allow this phone's key. Android 11+ pairing-code wireless debugging needs `adb tcpip 5555` once, or the Toolbox's Android platform tools in Termux (`adb pair`) |
| `usb_hid` (connect type) | USB HID devices (keyboards, controllers, scanners, custom HID boards): read input reports, write output reports (interrupt OUT or SET_REPORT), shows the report descriptor |
| `bt_keyboard` (connect type) | this phone acts as a Bluetooth keyboard (Android 9+) and types text on a paired computer, tablet or TV |
| `install_tool` | installs a missing tool, driver, SDK or repo and adds it to the Toolbox, so it's kept for every future chat, project, agent and task |
| `http_request` | any HTTP(S) request; plain http is allowed to LAN addresses |
| `download_file` | (This phone mode) saves a URL into the workspace, for example firmware or data to send_file |

Android asks for USB access per device and for Bluetooth permission the first time. Listing, reading and closing don't need approval; connecting, writing, HTTP and downloads follow "Ask before each action".
Android apps can't load kernel drivers, so USB-serial chips are driven from user space, and other USB devices go through `usb_raw`. To install compilers, SDKs and other tools, use Termux or Computer mode (`pkg install`, `pip`, and so on). The phone sandbox can't run downloaded programs.

## Toolbox (preinstalled tools)
Settings, then **Toolbox**: tools installed once on the machine where Tools run, then reused.
- **Common tools** are always listed on the Toolbox page, each with **Add & install**. **Install the developer set** installs the **Android SDK** (sdkmanager, platform 35, build tools, `ANDROID_HOME`; on Termux the ARM build tools come from Termux packages), **ADB & fastboot**, **Node.js + npm**, **Python 3 + pip**, **Git** and **Java 17** in one tap. Also included: Gradle, Kotlin, C/C++, CMake, Go, Rust, curl/wget/zip, jq, OpenSSH, rsync, SQLite, FFmpeg, ImageMagick, Pandoc, network tools, USB utilities, Termux:API USB, esptool, arduino-cli, and the **rea** repo. On "This phone", tools that need Linux are marked and offer a switch to Termux.
- **Add your own**: give it a name and a source address. The type is detected automatically: git repo, download link (zip/tar unpacked), system package, `pip:`/`npm:` package, or an install script. Private repos and downloads can use an access token, which is passed through a temporary file and never appears in the command.
- Tools go in `~/switchboard-tools/<name>` (Termux/computer), or `workspace/tools/<name>` on the phone (GitHub repos and downloads only). "Check what's installed" detects existing tools without reinstalling, and Install/Update runs again to update.
- Chats, agents, skills and scheduled tasks (with tools allowed) are told what's installed and where, so the model uses those copies instead of downloading again.

## Models by priority
Edit a provider and tap **Detect available models**: Switchboard lists every model your API key can use. OpenRouter also reports what each model outputs and whether it's free; for other providers this is guessed from the name. For each task (**Chat, Image, Audio, Video**), add models and order them with the arrows.
- **Chat**: the model in use answers. If it fails (unavailable, out of credits, rate-limited, overloaded), the next one takes over with the full conversation. Switch quickly in the model menu (tap the model name).
- **Image**: the current chat model is asked first, then the image list (or the other chat models if none are chosen). OpenRouter-style image output and the `/images/generations` API are both tried for each model.
- **Audio**: audio models in order (`/audio/speech`, then chat audio output), otherwise the phone's built-in voice.
- **Video**: video models in order (`/videos` jobs, polled until done).
- **Context carries over**: every chat is saved with its full history, files in the chat are described in the text sent to the model, and if a fallback model's context is too small, the oldest messages are left out with a note to the model.

## File generation
On by default (Settings, then File generation). Files appear in the chat with Open, Save and Share buttons, and images show inline. Picture and video requests ("generate an image of…", `/image`, `/video`) go straight to the image or video models, so they work even when the chat model ignores tools.
- `create_document`: **PDF**, **Word .docx**, **Excel .xlsx**, **ZIP**, and any text format.
- `generate_image`, `generate_video` and `text_to_speech` use the priority lists above.
- `write_file` accepts `encoding: base64` for any binary file.

## Connectors, skills, agents, projects and scheduled tasks
Everything below is in **Settings**, at the top of the page.
- **Connectors**: **MCP servers** (remote, Streamable HTTP; their tools appear as `mcp__<server>__<tool>`) and **REST APIs** (one `api__<name>` request tool each). Auth options are none, bearer token, API-key header, username and password, or **OAuth 2.0**: authorization code with PKCE, redirect `switchboard://oauth`, automatic endpoint discovery and client registration for MCP servers, and token refresh. Tokens are encrypted on the device. Connector calls follow "Ask before each action".
- **Skills**: named instructions. The model sees each skill's name and description and loads the full text with `use_skill`. With Tools on, it can save new skills (`create_skill`).
- **Agents**: a name, instructions, and optionally their own provider or model, with or without tools. Pick an agent for a chat in the model menu, or the model can hand a task to one with `run_agent`. With Tools on, it can create agents (`create_agent`).
- **Projects**: group chats in the side menu, with shared instructions and reference files (their text is given to the model).
- **Rename chats**: tap the pencil next to a chat in the side menu. You can move it into or out of a project there too.
- **Scheduled tasks**: once, every N hours, daily or weekly, in your time zone, with an optional project and agent. Runs use Android's background job scheduler, so they work with the app closed. Each run is saved as a chat and you get a notification. Tools and connectors run without asking only if you turn on "Allow tools and connectors" for that task. With Tools on, the model can schedule tasks too (`schedule_task`).

## Layout
- **Wide screens** (an unfolded Fold, tablets, landscape): the chat list stays open on the left, beside the chat. ☰ hides or shows it. Phones use a slide-out list.
- **Chat list**: search (titles and message text), projects at the top, then recent chats, each with when it was last active ("8h", "1d", "20 Oct") and its project, or the provider and model that last answered. Long-press a chat to rename, move or delete it. **New chat** is at the bottom.
- **Tool steps** fold into one line ("Ran 3 steps · Run command, Write file"), which you tap to open. It opens by itself while a step waits for approval or is running, and files the steps made stay visible.
- **🎤 voice input** next to the message box uses the phone's speech recognizer.

## Attaching files (+ button or Share)
Tap **+** next to the message box (any file type, several at once), or share files into Switchboard from other apps. They show as chips until you send.
- **Images** are converted to JPEG (≤1568 px, so HEIC and huge photos work) and sent for the model to see. **PDFs** are sent as documents (Claude, OpenAI, OpenRouter).
- **Text and code files** are included as text. **Word, Excel and PowerPoint** have their text extracted.
- **Everything** (audio, video, archives, APKs, …) is copied to `workspace/uploads/`, and to Termux or the computer when tools run there, and the model is told where, so it can process files with tools.
- If a model rejects images or PDFs, the reply is retried automatically with file notes.

## Providers taking turns, and request limits
- **Load balancing**: tick two or more providers (Settings provider list, or the model menu). Each new message goes to the next one in turn. If it can't answer (unavailable, out of credits, rate-limited, at its limit), its next chat model and then the other providers take over.
- **Memory across models**: every reply gets the full saved conversation, including tool steps and notes about files in the chat. Tool-call IDs from one provider are made valid for the others, and each model is told that earlier replies may come from other models so it continues as one assistant.
- **Limits per provider**: edit a provider to set its overall requests per minute and per day (all its models together, shared by all chats and background tasks). The counter under the message box shows the provider answering next. Settings keeps how daily limits reset and the time zone.

## Unrestricted mode
This switch in Settings asks you to confirm before it turns on. It lifts the app's own limits:
- plain `http://` works for any host (normally only localhost, `10.0.2.2`, `*.local` and `*.lan`)
- max output tokens go up to 1,000,000 and are also sent to OpenAI-compatible providers
- a raw JSON field (for example `{"temperature": 0.7, "provider": {"order": ["..."]}}`) is merged into every request body; `model`, `stream` and `messages` can't be overridden

It does not change what a model will or won't answer. The provider and the model you pick decide that.

## Notes
- Text only for now: no image or file attachments, and no tool use.
