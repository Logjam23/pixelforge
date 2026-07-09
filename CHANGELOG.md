# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- NPU (TPU) backend confirmed working on Tensor G5 via LiteRT-LM and AICore dispatch
- Concurrent download coroutine race fix: Job reference tracking prevents orphaned downloads

### Fixed
- fix(download): prevent concurrent download coroutines racing on model file — adds Job tracking and cancellation guard to prevent in-flight downloads from interfering with new ones

### Known Issues
- Model re-downloads on every launch (caching not yet implemented) — each app start re-fetches full model (~2.6GB)
- Status bar insets rendering cosmetic issue
- No visual backend indicator in UI (only in log)

---

## Development Status

- **Branch**: `feature/npu-path-a-integration` (not yet merged to main)
- **Last Updated**: 2026-07-08
