# Xs Website

[简体中文](README.md) · [English](README.en.md)

This directory contains the **standalone, runnable static website** for Xs. It is isolated from the Android client source.

## Run

Open `website/index.html` in a browser or serve `website/` as a static directory. No build system, account, or backend is required. All posts, refreshes, recovery actions, searches, favorites, labels and notes are **sample interactions** in memory; the demo is not connected to real X.

## Features

- High-contrast black-and-white visual design, responsive layout, complete Chinese/English interface.
- Android-oriented phone mockup and distinct desktop interface, with Windows/macOS explicitly **planned**, not released.
- Interactive browse → refresh → recover → search → save flow, with editing/unsaving and reset.
- Keyboard controls and reduced-motion support.
- Clearly separated recent buffer, observed/opened history and deliberate durable saves.
- No misleading release or download call to action.

## Publishing

The existing live website uses a **separately managed Sites project**. Committing this file to GitHub does **not** deploy it to `https://xs-local-first.kenneth42.chatgpt.site`. Publish through the original authorized project so its identity and access policy stay intact.

The self-contained HTML is an executable export. The original modular TypeScript source/tests/screenshots package was prepared separately and is not represented as checked-in source in this folder; import that package separately if continued modular development is required.

## Status

Repository: <https://github.com/ForceMind/Xs>  
Android development PR: <https://github.com/ForceMind/Xs/pull/5>  
Recent timeline issue: <https://github.com/ForceMind/Xs/issues/6>

PR #5 remains a draft at the time of writing. Live X capture, physical-device testing and recipient-app testing remain outstanding. No production release is claimed, and Windows/macOS are future targets.

Xs is an independent GPL-3.0 project and is not affiliated with X Corp.
