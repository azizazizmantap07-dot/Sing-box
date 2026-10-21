# Auto restart (this fork)

Keeps the VPN/proxy up when the **service itself** dies. Switch: *Settings → Service → Auto restart*
(default **on**). The Auto restart and Airplane recovery sections use the same card style as the
rest of the Service screen; the root status shown under *Airplane recovery* also tells whether the
root fallback in layer 3 below can work.

## Layers

1. **`START_STICKY`** – `BoxService.onStartCommand` now returns it (also from the early "already
   running" exit; the value of the *last* call is what Android uses). If Android kills the service,
   it is recreated with a `null` intent and resumes **only if** `Settings.startedByUser` is true.
2. **Start retries** – when the core fails to come up (`StartCommandServer`, `StartService`, and
   `CreateService` for automatic starts or configs that already ran once) the service stays alive in
   the foreground, tears the core down and tries again after 2, 4, 8, 16, 30 s. `startedByUser` stays
   true meanwhile. A **manual first start with a broken config** is not retried (the error shows
   immediately). `EmptyConfiguration` and `RequestLocationPermission` are never retried.
   After 5 failed retries it gives up: alert + notification, `startedByUser = false`.
3. **`ServiceWatchdog` (outside the service)** – an `AlarmManager` heartbeat
   (`setAndAllowWhileIdle`) owned by the system, delivered to the manifest receiver
   `ServiceWatchdogReceiver`. The cadence follows the state: **120 s while the service is alive**
   (the beat only re-arms and returns, without touching the database) and **60 s while it is gone
   and wanted** (restart just attempted, or Android refused it). If `startedByUser` is true but
   the service is not alive it starts it
   again: `startForegroundService` first, then root `am start-foreground-service` as a fallback when
   Android 12+ refuses a background foreground start. Because `AirplaneModeRecovery` is started by
   the service, restarting the service also brings the airplane watchdog back.
4. **Boot** – `BootReceiver` no longer skips the start because of unread crash reports when auto
   restart is on (with it off the old behaviour is kept). The crash-loop guard below replaces that check.

## Crash-loop guard

Every automatic restart (system restart, alarm, boot) consumes a budget: max **6 per 10 minutes**
(`ServiceWatchdog.consumeRestartBudget`). When it is exhausted: heartbeat cancelled,
`startedByUser = false`, notification "sing-box stopped". Starting by hand works again.

## What never restarts

User stop (button, tile, notification, VPN revoked by another app), force stop from system settings,
and switching the setting off. `stopService()` cancels the heartbeat and sets `stopRequested`.

## Notes

- The on/off switch and the restart history are in SharedPreferences (`service_watchdog`), not in
  Room `Settings`: they are read on the main thread while the system recreates the service, and Room
  refuses main-thread queries.
- `Application.libboxReady` is completed after `Libbox.setup`; `BoxService` waits (max 15 s) for it
  after a cold start so a restart does not race the setup.
- Inexact alarm: in Doze it can be late (minutes). `START_STICKY` is the fast path, the alarm is the net.
  That is why the relaxed 120 s beat is safe: it only has to catch a dead process that Android did not
  bring back by itself, and the short 60 s cadence takes over as soon as that is detected.

## Debugging

`adb logcat -s ServiceWatchdog BoxService` – look for `start failed (...), retry n/5`,
`ignoring system restart`, `service is gone but the user wants it running`,
`startForegroundService refused`, `giving up`.
