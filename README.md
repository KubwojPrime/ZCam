# ZCam (Android Skeleton)

ZCam to lokalna aplikacja Android (LAN only) zaprojektowana pod pracę 24/7.

## Stack

- Kotlin
- Jetpack Compose
- CameraX
- Foreground Service
- lokalny HTTP server (NanoHTTPD)
- MJPEG streaming
- DataStore
- Hilt DI

## Moduły

- `app` - bootstrap aplikacji, manifest, Activity, uruchamianie service
- `core` - logger, dispatchery coroutines, wspólne DI
- `core` - logger, dispatchery coroutines, kontrakty domenowe i use-case
- `camera` - runtime kamery + źródło ramek MJPEG (placeholder, gotowe do podpięcia CameraX encoder)
- `server` - lokalny HTTP `/health` i `/mjpeg` (chunked multipart MJPEG)
- `audio` - push-to-talk manager (szkielet runtime)
- `storage` - loop recording manager z retencją segmentów
- `watchdog` - heartbeat monitoring i wykrywanie zastałych komponentów
- `security` - LAN access policy + PIN/token + trusted devices
- `client` - lokalny klient HTTP do sprawdzania serwera
- `service` - foreground runtime coordinator + `ZCamForegroundService`
- `data` - DataStore runtime settings + feature flags + trusted devices
- `ui` - ekran Compose sterujący start/stop runtime

## Przepływ runtime

1. UI uruchamia `ZCamForegroundService`.
2. Service startuje `ZCamRuntimeCoordinator`.
3. Coordinator uruchamia watchdog i komponenty runtime (server/camera/audio/storage).
4. Watchdog monitoruje heartbeat i emituje sygnały recovery.
5. Coordinator wykonuje recovery z retry/backoff/cooldown.
6. Server wystawia LAN-only endpointy:
   - `GET /health`
   - `GET /mjpeg`

## Runtime Health i Recovery

- Obserwowalne health states:
  - `RuntimeHealthRepository.health` (stan runtime per komponent)
  - `WatchdogEngine.health` (stan watchdog i heartbeat)
- Recovery:
  - exponential backoff (`RecoveryPolicy`)
  - limit prób + cooldown (bez pętli awaryjnych)
  - mutex per komponent (brak równoległych recovery tego samego komponentu)
- Auto-restore:
  - restart procesu: `ZCamApplication` odtwarza runtime z `RuntimeStateRepository`
  - reboot urządzenia: `BootReceiver` odtwarza runtime z `RuntimeStateRepository`
- Logowanie produkcyjne:
  - logi runtime/watchdog/restore mają `event IDs` (`LogEventId`, np. `RUN-100`, `REC-101`, `WDG-102`)

## Wątki i stabilność

- Brak ciężkich operacji na UI thread.
- I/O i runtime działają na `Dispatchers.IO` / `Dispatchers.Default` przez `DispatcherProvider`.
- Service działa jako foreground z typami: `camera|microphone|mediaPlayback`.
- Loop recording ma politykę retencji (limit rozmiaru + minimum wolnego miejsca).
- Watchdog raportuje stale heartbeat (bazowy mechanizm recovery).

## Kontrakty Domenowe

- MJPEG streaming: `MjpegStreamingEngine` + use-case start/stop.
- Loop recording: `LoopRecordingEngine` + use-case start/stop/sweep.
- Audio PTT/live/playback: `AudioEngine` + use-case PTT/live/playback.
- Security (PIN/token/trusted devices): `SecurityEngine` + use-case auth/trust/revoke.
- Watchdog/recovery: `WatchdogEngine` + use-case heartbeat/recovery.

## Centralna Konfiguracja

`RuntimeSettingsDefaults`:
- 720p (`1280x720`)
- 15 FPS
- H.264
- segment 5 min
- limit 32 GB
- min free 5 GB

Walidacja odbywa się przez `RuntimeSettingsValidator` przed zapisem do DataStore.

## Feature Flags i Runtime Settings

- Runtime settings są przechowywane w `DataStoreRuntimeSettingsRepository`.
- Feature flags są silnie typowane (`FeatureFlag`, `FeatureFlags`) i zmieniane tylko przez allowlist (`FeatureFlagGuard`).
- Trusted devices są serializowane bezpiecznie (Base64 URL-safe) i walidowane przed utrwaleniem.

## Testy Jednostkowe

- kontrakty/use-case: `core/src/test/.../DomainUseCasesContractTest.kt`
- walidacja konfiguracji: `core/src/test/.../RuntimeSettingsValidatorTest.kt`
- feature flag guard: `core/src/test/.../FeatureFlagGuardTest.kt`
- codec trusted devices: `data/src/test/.../TrustedDeviceCodecTest.kt`
- recovery policy: `service/src/test/.../RecoveryPolicyTest.kt`
- recovery runtime coordinator: `service/src/test/.../ZCamRuntimeCoordinatorRecoveryTest.kt`
- watchdog stale->recovery: `watchdog/src/test/.../ProcessWatchdogManagerRecoveryTest.kt`

## Decyzje Techniczne Pod 24/7

1. Foreground Service jako centralny runtime.
Uzasadnienie: system Android rzadziej ubija proces i daje przewidywalny cykl życia dla stałej pracy.

2. Architektura modułowa (12 modułów funkcjonalnych).
Uzasadnienie: izolacja awarii i prostsze testowanie/restarty pojedynczych obszarów (camera/server/audio/storage).

3. Coroutines + dispatcher injection.
Uzasadnienie: zero blokowania UI i kontrola, gdzie wykonuje się I/O / CPU (łatwiejsze strojenie pod długie sesje).

4. LAN-only policy na wejściu HTTP.
Uzasadnienie: minimalizacja powierzchni ataku i brak zależności od Internetu/chmury.

5. DataStore dla konfiguracji operacyjnej.
Uzasadnienie: transakcyjne, asynchroniczne, odporne na błędy I/O zapisy ustawień runtime.

6. Loop recording z retencją opartą o limit danych i free-space floor.
Uzasadnienie: ochrona przed zapełnieniem pamięci i degradacją systemu podczas pracy ciągłej.

7. Watchdog heartbeat.
Uzasadnienie: szybka detekcja zastałych komponentów i gotowy punkt pod auto-recovery.

8. Produkcyjny logging przez wspólny interfejs (`ZCamLogger`).
Uzasadnienie: jednolite logi dla diagnostyki błędów i utrzymania urządzenia po wdrożeniu.

## Uruchomienie

1. Otwórz projekt w Android Studio (JDK 17 + Android SDK).
2. Zsynchronizuj Gradle.
3. Uruchom moduł `app`.

## Uwaga

W tym szkielecie CameraX i PTT są przygotowane architektonicznie, ale streaming ramek i audio runtime są jeszcze w trybie placeholder (MVP skeleton).
