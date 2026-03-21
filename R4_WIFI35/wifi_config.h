#pragma once

// Shared template for the UNO R4 WiFi monitor sketch.
// Prefer putting real machine-specific values in wifi_config.local.h so they
// stay local and do not get committed by accident.
//
// Suggested multi-board naming example:
//   Office board  -> WIFI_DEVICE_NAME_VALUE "R4_OFFICE"
//   Gaming board  -> WIFI_DEVICE_NAME_VALUE "R4_GAMING"
//
// Suggested fixed-target example:
//   WIFI_TARGET_HOST_VALUE "192.168.1.100"
//   WIFI_TARGET_HOSTNAME_VALUE "office-desktop"
//
// Leave the target host fields blank if you do not want discovery filtering.

#define WIFI_SSID_VALUE "YOUR_WIFI_SSID"
#define WIFI_PASS_VALUE "YOUR_WIFI_PASSWORD"
#define WIFI_TCP_PORT_VALUE 5000
#define WIFI_DEVICE_NAME_VALUE "R4_WIFI35"
#define WIFI_TARGET_HOST_VALUE ""
#define WIFI_TARGET_HOSTNAME_VALUE ""
