# pixelforge 🔥

> OpenAI-compatible LiteRT-LM inference server for Pixel 10 — GPU/NPU-accelerated, Tailscale mesh-ready.

Run Gemma 4 on your Pixel 10 (Tensor G5 GPU/NPU backend) as a persistent background service. 
Expose an OpenAI-compatible API endpoint over your Tailscale mesh.
Drop-in replacement for any local LLM tool that speaks the OpenAI API (Hermes, Open WebUI, Claude Code, etc.).

## Why

Every other Android LLM app is a chat UI. 
**pixelforge** is a server — your Pixel 10 becomes a GPU/NPU-accelerated inference node on your private AI mesh.

- ✅ Runs on the Tensor G5 GPU via LiteRT ML Drift (not CPU)
- ✅ NPU (TPU) backend confirmed working via LiteRT-LM and AICore dispatch
- ✅ OpenAI-compatible `/v1/chat/completions` endpoint
- ✅ Tailscale-aware (binds to mesh IP, accessible from any node)
- ✅ Android foreground service (survives background kill)
- ✅ Wake lock (inference completes even when screen is off)
- ✅ First-run model download — no bloated APK

## Status

🔧 **In development** — feature/npu-path-a-integration branch active, NPU backend validated.

- [x] LiteRT-LM OpenAI endpoint validated (CPU, NightAstro)
- [x] Pre-compiled Gemma 4 E2B Tensor G5 model confirmed on HuggingFace
- [x] Android foreground service scaffold
- [x] LiteRT-LM Android SDK integration
- [x] GPU backend wiring (ML Drift)
- [x] NPU/TPU backend confirmed (AICore dispatch, Tensor G5)
- [ ] Tailscale IP binding (in progress on feature/npu-path-a-integration)
- [ ] First-run model downloader (in progress on feature/npu-path-a-integration)
- [ ] Wake lock + notification (in progress on feature/npu-path-a-integration)

### Known Issues

- **Model re-downloads on every launch** — model caching not yet implemented; each app start re-fetches the full model (~2.6GB). Tracked as pixelforge-caching-001.
- **Status bar insets rendering issue** — cosmetic; status bar layout slightly misaligned in some cases. Low priority.
- **No visual backend indicator** — only backend selection visible in scrolling log; no UI indicator yet. Low priority.

## Current Branch

Work in progress on `feature/npu-path-a-integration`. Not yet merged to main.

## Model

Uses `gemma-4-E2B-it.litertlm` (GPU/CPU-compatible variant) from
`litert-community/gemma-4-E2B-it-litert-lm` on HuggingFace.
Downloaded on first launch (~2.6 GB). Apache 2.0 license.

## Hardware

- **Required**: Google Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL, or Pixel 10 Pro Fold
- **Chip**: Google Tensor G5 with dedicated TPU
- **RAM**: 12GB recommended (model uses ~3GB)

## Building

```bash
./gradlew assembleDebug   # Debug APK for testing on device via `adb install`
./gradlew assembleRelease # Release APK (requires signing)
```

Install and run:
```bash
adb install app/build/outputs/apk/debug/pixelforge-debug.apk
adb shell am startservice com.brainnet.pixelforge/.PixelForgeService
adb logcat | grep PixelForge  # Monitor startup and inference
```

## Architecture

```
Pixel 10 (Android)
└── pixelforge (foreground service)
    ├── LiteRT-LM Android SDK
    │   └── Gemma 4 E2B → Tensor G5 GPU/NPU (ML Drift, AICore dispatch)
    └── OpenAI-compatible HTTP server
        └── Tailscale mesh IP:8080
             ↑
     any OpenAI client on your mesh
```

## Inspiration

Built for [BrainNet](https://github.com/Logjam23/brainnet-ops) — a home AI mesh where every node contributes compute.

## License

Apache 2.0
