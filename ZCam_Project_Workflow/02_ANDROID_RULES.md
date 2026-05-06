# 02_ANDROID_RULES.md

## Wymagania Android

- Foreground Service wymagany
- Camera service type
- Microphone service type
- Media playback service type

## Ograniczenia

Android może ubijać aplikacje w tle.
Aplikacja musi:
- działać jako foreground service
- mieć watchdog
- odzyskiwać stan