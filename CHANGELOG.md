# Changelog

## v9.7.1 - 2026-03-27
- Bumped the shared project version to v9.7.1 so VERSION-driven Python/Java/Arduino version displays stay aligned.
- Changed Wi-Fi connection priority so a configured fixed `wifi_host` is attempted first over direct TCP, with UDP discovery treated as optional fallback/debug behavior.
- Updated monitor startup/runtime logging to explicitly call out direct host/IP attempts versus discovery fallback attempts.
- Improved UDP discovery robustness by broadcasting to interface-derived directed broadcast addresses in addition to `255.255.255.255`.
- Updated Control Center Wi-Fi wording/modes to make fixed host/IP the recommended default workflow and discovery the secondary fallback path.
- Kept discovery troubleshooting tooling available (including debug toggles and network scan) while improving scan broadcast coverage on multi-interface Linux hosts.

## v9.7 - 2026-03-24
- Bumped the shared project version to v9.7 so VERSION-driven Python/Java/Arduino version displays stay aligned.
- Made the Control Center Update button use a positive green theme style and renamed the default flash action to "Flash Arduino's".
- Added independent UNO R3 and Mega screen-size selectors for flashing so Mega no longer inherits UNO mode by default.
- Fixed profile page toggle behavior by adding a Home-page disable confirmation prompt and preventing profile-toggle checkbox theme regressions that turned controls white while switching/pressing.
- Corrected the R4 profile-page label mapping from "Storage" to "Extra Statistics" while preserving the existing firmware page macro IDs.
- Moved board profile persistence to a machine-local config path with legacy migration support, and added Control Center import/export actions for manual backup/restore to any filesystem location.
- Ensured imported/saved board profile settings are immediately re-applied/synced to generated page_config.local.h headers so explicit profile choices remain the source of truth.

## v9.6.0 - 2026-03-23
- Updated Control Center profile persistence/import-export behavior so board profile changes are easier to keep consistent per machine.
- Fixed board page naming/styling mismatches in the Control Center profile flow.
- Separated UNO R3 vs Mega screen-size flashing selectors so board families no longer share one size choice.
- Tightened profile-to-page application behavior so selected profile choices apply more predictably.

## v9.5.3 - 2026-03-23
- Bumped the shared project version to v9.5.3 so the repo VERSION file, generated Arduino headers, Python monitor startup banner, Control Center title/header, and flashing output stay aligned.
- Simplified the Mega and UNO R3 2.8-inch touch flow to full-screen tap-to-advance paging with clean looping and no footer navigation buttons.
- Split the Mega / R3 combined GPU + Network screen into separate GPU and Network pages and enlarged the key values for readability.
- Split the 2.8-inch combined Storage + Power details into dedicated Storage and Power pages with less cramped layouts.
- Added hostname and uptime as footer text on the UNO R4 WiFi Usage Graph page without intruding on the graph area.

## v9.5.2 - 2026-03-22
- Added update pre-check logic so the Control Center only runs the full update flow when the remote branch is newer, and now shows a clear already-up-to-date message otherwise.
- Added centralized generated app version headers for the Arduino Mega 3.5, UNO R3 2.8, and UNO R3 3.5 sketches, and rendered the version in each display header so the program header stays on v9.5.2.
- Cleaned up the Flash tab Wi-Fi header area by removing the redundant top-middle Wi-Fi label/status badge while keeping the Mode WIFI indicator and moving R4 Wi-Fi credential controls closer to it.
- Restored horizontal scrolling on the Dashboard and Flash tab scroll containers while keeping the more compact layout changes.
- Changed the Control Center default launch size to 1340 x 940.
- Restored the long-form documented changelog presence in the repo docs and refreshed the lead README preview image to use screenshots/quad1.JPEG.

## v9.5.1 - 2026-03-22
- Refactored runtime version loading to read from the top-level VERSION file so the Python monitor, Java Control Center, and Arduino builds all share one version source.
- Kept generated Arduino app-version headers in sync with repo versioning during the build/update flow.

## v9.5.0 - 2026-03-22
- Centralized versioning and refreshed the dashboard flow.
- Aligned the newer storage, battery, and display UI updates with the v9.5 release line.

## v9.4 - 2026-03-22
- Added simultaneous USB + Wi-Fi monitor output.
- Unified Wi-Fi TCP port persistence across the monitor config, Control Center, and flashed Arduino settings.
- Added automatic Arduino dependency installation before R4 WiFi reflashing.
- Fixed display-rotation persistence, flash-path handling, and broader README/documentation cleanup.

## v9.3 - 2026-03-21
- Added Wi-Fi pairing handshake and persistence support for Arduino UNO R4 WiFi boards.
- Added pairing reset flows and the no-reflash EEPROM reset path.

## v9.2 - 2026-03-21
- Refined branding, Control Center version display, and flash preview/logging.

## v9.1 - 2026-03-21
- Updated icons and branding using the Arduino preview art already in the project.

## v9.0 - 2026-03-21
- Added layered default/shared/local config support.
- Refreshed project branding for the 9.x release line.

## v8.13 - 2026-03-21
- Removed duplicate nested R4 Wi-Fi monitor/sketch copies and consolidated to one canonical workflow.

## v8.12 - 2026-03-20
- Added UNO R3 mode selection, visible display toggles in the action area, and monitor connection port settings.

## v8.11 - 2026-03-20
- Switched real credentials to git-ignored local Wi-Fi config files.

## v8.10 - 2026-03-20
- Added warnings around committing real Wi-Fi credentials and cleaned up .gitignore behavior.

## v8.9 - 2026-03-20
- Added committed Wi-Fi config templates and the GUI save-to-header flow before flashing.

## v8.8 - 2026-03-20
- Improved USB priority behavior, cleaned up the R4 layout, and reduced duplicate USB + Wi-Fi updates.

## v8.7 - 2026-03-20
- Made R4 WiFi the default Linux monitor/config path with USB-only and Wi-Fi mode controls.

## v8.6 - 2026-03-19
- Cleaned up project and Control Center version metadata and refreshed the README.

## v8.5 - 2026-03-19
- Cleaned up the Control Center layout/theme, custom-sketch status, improved labels, and Mega touchscreen behavior.

## v8.4 - 2026-03-19
- Added Control Center update rebuild/relaunch behavior and the visible in-app version display.

## v7.0
- Historical changelog placeholder retained so the documented release chain still includes the pre-8.x project era.

## v6.0
- Historical changelog placeholder retained so the older release trail is not dropped from the docs again.

## v5.0
- Historical changelog placeholder retained to keep the version continuity visible from the older releases you documented.

## v4.0
- Historical changelog placeholder retained so the README and changelog once again preserve the longer-running release chain.

## v3.0
- Historical changelog placeholder retained to preserve the older project history that existed before the recent docs rewrite.

## v2.0
- Historical changelog placeholder retained so the documented version trail continues after the initial release.

## v1.0
- Initial release.
