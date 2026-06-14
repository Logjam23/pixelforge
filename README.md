# pixelforge 🔥

> OpenAI-compatible LiteRT-LM inference server for Pixel 10 — GPU-accelerated, Tailscale mesh-ready.

Run Gemma 4 on your Pixel 10 (ML Drift GPU backend) as a persistent background service. 
Expose an OpenAI-compatible API endpoint over your Tailscale mesh.
Drop-in replacement for any local LLM tool that speaks the OpenAI API (Hermes, Open WebUI, Claude Code, etc.).

## Why

Every other Android LLM app is a chat UI. 
**pixelforge** is a server — your Pixel 10 becomes a GPU-accelerated inference node on your private AI mesh.

- ✅ Runs on the Tensor G5 GPU via LiteRT ML Drift (not CPU)
- ℹ️ NPU (TPU) backend deferred: the LiteRT dispatch runtime that talks to the
  on-device TPU drivers ships via Play Feature Delivery / AI Packs, not in the
  `litertlm-android` AAR, and isn't reliably loadable from a sideloaded APK.
  See task pixelforge-018.
- ✅ OpenAI-compatible `/v1/chat/completions` endpoint
- ✅ Tailscale-aware (binds to mesh IP, accessible from any node)
- ✅ Android foreground service (survives background kill)
- ✅ Wake lock (inference completes even when screen is off)
- ✅ First-run model download — no bloated APK

## Status

🚧 **Early development** — API design validated, Android app in progress.

- [x] LiteRT-LM OpenAI endpoint validated (CPU, NightAstro)
- [x] Pre-compiled Gemma 4 E2B Tensor G5 model confirmed on HuggingFace
- [x] Android foreground service scaffold
- [x] LiteRT-LM Android SDK integration
- [x] GPU backend wiring (ML Drift) — NPU/TPU deferred (see pixelforge-018)
- [ ] Tailscale IP binding
- [ ] First-run model downloader
- [ ] Wake lock + notification

## Model

Uses `gemma-4-E2B-it.litertlm` (GPU/CPU-compatible variant) from
`litert-community/gemma-4-E2B-it-litert-lm` on HuggingFace.
Downloaded on first launch (~2.6 GB). Apache 2.0 license.
(The `_Google_Tensor_G5` NPU-precompiled variant requires the TPU dispatch
runtime — see the NPU note above.)

## Hardware

- **Required**: Google Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL, or Pixel 10 Pro Fold
- **Chip**: Google Tensor G5 with dedicated TPU
- **RAM**: 12GB recommended (model uses ~3GB)

## Architecture

```
Pixel 10 (Android)
└── pixelforge (foreground service)
    ├── LiteRT-LM Android SDK
    │   └── Gemma 4 E2B → Tensor G5 GPU (ML Drift)
    └── OpenAI-compatible HTTP server
        └── Tailscale mesh IP:8080
             ↑
     any OpenAI client on your mesh
```

## Inspiration

Built for [BrainNet](https://github.com/Logjam23/brainnet-ops) — a home AI mesh where every node contributes compute.

## License

Apache 2.0
