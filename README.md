# pixelforge 🔥

> OpenAI-compatible LiteRT-LM inference server for Pixel 10 — NPU-accelerated, Tailscale mesh-ready.

Run Gemma 4 on your Pixel 10's Tensor G5 NPU as a persistent background service. 
Expose an OpenAI-compatible API endpoint over your Tailscale mesh.
Drop-in replacement for any local LLM tool that speaks the OpenAI API (Hermes, Open WebUI, etc.).

## Why

Every other Android LLM app is a chat UI. 
**pixelforge** is a server — your Pixel 10 becomes an NPU-accelerated inference node on your private AI mesh.

- ✅ Runs on Tensor G5 NPU (not CPU)
- ✅ OpenAI-compatible `/v1/chat/completions` endpoint
- ✅ Tailscale-aware (binds to mesh IP, accessible from any node)
- ✅ Android foreground service (survives background kill)
- ✅ Wake lock (inference completes even when screen is off)
- ✅ First-run model download — no bloated APK

## Status

🚧 **Early development** — API design validated, Android app in progress.

- [x] LiteRT-LM OpenAI endpoint validated (CPU, NightAstro)
- [x] Pre-compiled Gemma 4 E2B Tensor G5 model confirmed on HuggingFace
- [ ] Android foreground service scaffold
- [ ] LiteRT-LM Android SDK integration
- [ ] NPU backend wiring (Tensor G5)
- [ ] Tailscale IP binding
- [ ] First-run model downloader
- [ ] Wake lock + notification

## Model

Uses `gemma-4-E2B-it_Google_Tensor_G5.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm` on HuggingFace.
Downloaded on first launch (~2.4 GB). Apache 2.0 license.

## Hardware

- **Required**: Google Pixel 10, Pixel 10 Pro, Pixel 10 Pro XL, or Pixel 10 Pro Fold
- **Chip**: Google Tensor G5 with dedicated TPU
- **RAM**: 12GB recommended (model uses ~3GB)

## Architecture

```
Pixel 10 (Android)
└── pixelforge (foreground service)
    ├── LiteRT-LM Android SDK
    │   └── Gemma 4 E2B → Tensor G5 NPU
    └── OpenAI-compatible HTTP server
        └── Tailscale mesh IP:8080
             ↑
     any OpenAI client on your mesh
```

## Inspiration

Built for [BrainNet](https://github.com/Logjam23/brainnet-ops) — a home AI mesh where every node contributes compute.

## License

Apache 2.0
