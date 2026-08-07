# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
(0.x during incubation).

## [Unreleased]

### Added

- Initial project scaffold: backend package skeleton, frontend placeholder,
  community health files and architecture decision records.
- Design phase: ADRs 0003–0011 — two Home Assistant Apps with the
  dashboard never compiling (0003), aiohttp backend with a
  WebSocket-first API (0004), Lit 3 + webawesome + CodeMirror 6 frontend
  (0005), the build-service protocol (0006), wire content and credential
  exposure (0007), state layout and signing-key custody (0008),
  authentication per deployment (0009), the flash-flow ladder (0010),
  and the builder coupling with the firmware-side interface contract
  (0011).

### Changed

- Backend requires Python ≥ 3.13 (was ≥ 3.11) — ADR 0004.
- AGENTS.md reflects the design decisions: "App" instead of "Add-on",
  two-App packaging, always-remote builds, and the in-process builder
  import with a declared version range.
