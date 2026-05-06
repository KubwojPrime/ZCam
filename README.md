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
- `camera` - runtime kamery + źródło ramek MJPEG (placeholder, gotowe do podpięcia CameraX encoder)
- `server` - lokalny HTTP `/health` i `/mjpeg` (chunked multipart MJPEG)
- `audio` - push-to-talk manager (szkielet runtime)
- `storage` - loop recording manager z retencją segmentów
- `watchdog` - heartbeat monitoring i wykrywanie zastałych komponentów
- `security` - LAN access policy + walidacja PIN/token
- `client` - lokalny klient HTTP do sprawdzania serwera
- `service` - foreground runtime coordinator + `ZCamForegroundService`
- `data` - DataStore preferences + repo ustawień
- `ui` - ekran Compose sterujący start/stop runtime

## Przepływ runtime

1. UI uruchamia `ZCamForegroundService`.
2. Service startuje `ZCamRuntimeCoordinator`.
3. Coordinator uruchamia: watchdog, camera, audio, storage, HTTP server.
4. Server wystawia LAN-only endpointy:
   - `GET /health`
   - `GET /mjpeg`

## Wątki i stabilność

- Brak ciężkich operacji na UI thread.
- I/O i runtime działają na `Dispatchers.IO` / `Dispatchers.Default` przez `DispatcherProvider`.
- Service działa jako foreground z typami: `camera|microphone|mediaPlayback`.
- Loop recording ma politykę retencji (limit rozmiaru + minimum wolnego miejsca).
- Watchdog raportuje stale heartbeat (bazowy mechanizm recovery).

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
