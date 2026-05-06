# ZCam v1 — Project Brief & Codex Workflow

## Projekt
ZCam to lokalny system monitoringu psa działający na Androidzie.

Priorytety:
1. Stabilność
2. Prostota
3. Praca 24/7
4. Odporność na błędy
5. Brak zależności od chmury

## Główne funkcje
- Monitoring LAN/VPN
- Push-to-talk
- Dwukierunkowe audio
- Odtwarzanie zapisanych dźwięków
- Nagrywanie w pętli
- Automatyczne kasowanie starych nagrań
- Panel WWW awaryjny
- Watchdog i recovery

## Architektura
Jedna aplikacja:
- TRYB SERWER
- TRYB KLIENT

## Stack
- Kotlin
- Jetpack Compose
- CameraX
- Foreground Service
- Ktor/NanoHTTPD
- ExoPlayer
- DataStore

## Parametry domyślne
- 720p
- 15 FPS
- H.264
- segmenty 5 min
- limit nagrań 32 GB
- minimalne wolne miejsce 5 GB

## Bezpieczeństwo
- LAN only
- VPN użytkownika
- brak chmury
- PIN
- token API
- QR pairing

## Priorytety CODEX
- modularność
- production-ready
- brak hacków
- logging
- recovery