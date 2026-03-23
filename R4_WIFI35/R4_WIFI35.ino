#include <DIYables_TFT_Touch_Shield.h>
#if __has_include("display_config.local.h")
#include "display_config.local.h"
#else
#include "display_config.h"
#endif
#if __has_include("wifi_config.local.h")
#include "wifi_config.local.h"
#else
#include "wifi_config.h"
#endif
#include <WiFiS3.h>
#if __has_include("app_version.generated.h")
#include "app_version.generated.h"
#endif
#include <WiFiUdp.h>
#include <EEPROM.h>
#include <string.h>

#ifndef DISPLAY_ROTATION_VALUE
#define DISPLAY_ROTATION_VALUE 1
#endif

#ifndef WIFI_TCP_PORT_VALUE
#define WIFI_TCP_PORT_VALUE 5000
#endif

#ifndef WIFI_DEVICE_NAME_VALUE
#define WIFI_DEVICE_NAME_VALUE "R4_WIFI35"
#endif

#ifndef WIFI_TARGET_HOST_VALUE
#define WIFI_TARGET_HOST_VALUE ""
#endif

#ifndef WIFI_TARGET_HOSTNAME_VALUE
#define WIFI_TARGET_HOSTNAME_VALUE ""
#endif

#define BLACK   DIYables_TFT::colorRGB(0, 0, 0)
#define WHITE   DIYables_TFT::colorRGB(255, 255, 255)
#define RED     DIYables_TFT::colorRGB(255, 0, 0)
#define GREEN   DIYables_TFT::colorRGB(0, 255, 0)
#define BLUE    DIYables_TFT::colorRGB(0, 120, 255)
#define CYAN    DIYables_TFT::colorRGB(0, 255, 255)
#define YELLOW  DIYables_TFT::colorRGB(255, 255, 0)
#define ORANGE  DIYables_TFT::colorRGB(255, 165, 0)
#define MAGENTA DIYables_TFT::colorRGB(255, 0, 255)
#define GRAY    DIYables_TFT::colorRGB(60, 60, 60)

#define LEFT_X 851
#define RIGHT_X 543
#define TOP_Y 921
#define BOT_Y 196

DIYables_TFT_RM68140_Shield tft;

char ssid[] = WIFI_SSID_VALUE;
char pass[] = WIFI_PASS_VALUE;

const uint16_t TCP_PORT = WIFI_TCP_PORT_VALUE;
const uint16_t DISCOVERY_PORT = 5001;
const char* DEVICE_NAME = WIFI_DEVICE_NAME_VALUE;
const char* TARGET_HOST = WIFI_TARGET_HOST_VALUE;
const char* TARGET_HOSTNAME = WIFI_TARGET_HOSTNAME_VALUE;
const char* DISCOVERY_MAGIC = "UAM_DISCOVER";
const char* DISCOVERY_RESPONSE_PREFIX = "UAM_HERE";
const char* PAIRING_MAGIC = "UAM_PAIR";
const char* RESET_PAIRING_COMMAND = "CMD|RESET_WIFI_PAIRING|CONFIRM";
const unsigned long WIFI_CLIENT_AUTH_TIMEOUT_MS = 3000;

WiFiServer server(TCP_PORT);
WiFiClient wifiClient;
WiFiUDP discoveryUdp;
String usbLine = "";
String wifiLine = "";
String wifiAuthLine = "";
String linkType = "NONE";
String arduinoWifiIp = "--";
bool wifiSuspendedForUsb = false;
unsigned long lastWifiRetryMs = 0;
unsigned long lastUsbActivityMs = 0;
const unsigned long wifiRetryIntervalMs = 8000;
const unsigned long usbPriorityHoldMs = 15000;
bool usbSessionActive = false;
bool wifiConfigured = false;
bool wifiClientAuthorized = false;
unsigned long wifiClientConnectedAtMs = 0;

const int SCREEN_W = 480;
const int SCREEN_H = 320;
const int GRAPH_POINTS = 62;
const int TOTAL_PAGES = 7;
const int CPU_THREADS = 16;
const int PROCESS_ROWS = 6;
const int STORAGE_LINES = 8;
const int EXTRA_BATTERY_SLOTS = 3;
const int FIELD_COUNT = 73;
#ifndef APP_VERSION
#define APP_VERSION "unknown version"
#endif

int cpuHistory[GRAPH_POINTS];
int ramHistory[GRAPH_POINTS];
int gpuHistory[GRAPH_POINTS];
int vramHistory[GRAPH_POINTS];

int currentPage = 0;
bool touchHeld = false;
unsigned long lastTouchTime = 0;
const unsigned long touchDebounceMs = 350;
unsigned long lastScreenUpdate = 0;
const unsigned long screenUpdateIntervalMs = 70;
bool screenDirty = false;

int cpuTotal = 0;
int cpuThreads[CPU_THREADS];
int ramPct = 0;
int disk0Pct = 0;
int disk1Pct = 0;
int gpuPct = 0;
int gpuMemUsed = 0;
int gpuMemTotal = 0;
int gpuMemPct = 0;
int gpuClock = 0;

String cpuTemp = "--";
String osName = "--";
String hostName = "--";
String ipAddr = "--";
String uptimeStr = "--";
String downStr = "--";
String upStr = "--";
String downTotalStr = "--";
String upTotalStr = "--";
String cpuFreqStr = "--";
String ramUsageStr = "--";
String gpuTemp = "--";
String gpuName = "--";
String batteryPctStr = "N/A";
String batteryStateStr = "DESKTOP";
String batteryModeStr = "DESKTOP";
String batteryDeviceLabel[EXTRA_BATTERY_SLOTS];
String batteryDeviceState[EXTRA_BATTERY_SLOTS];

String procName[PROCESS_ROWS];
String procCpu[PROCESS_ROWS];
String procRam[PROCESS_ROWS];
String storageLine[STORAGE_LINES];

struct PairingStore {
  char magic[8];
  char targetHost[64];
  char targetHostname[64];
};

const int PAIRING_EEPROM_ADDR = 0;
PairingStore pairingStore = {};
String pairedTargetHost = "";
String pairedTargetHostname = "";

String cleanIdentityValue(String value, int maxLen) {
  value.trim();
  value.replace("|", "/");
  value.replace("\n", " ");
  value.replace("\r", " ");
  if (value.length() > maxLen) {
    value = value.substring(0, maxLen);
  }
  return value;
}

bool identityMatches(String expected, String actual) {
  expected.trim();
  actual.trim();
  expected.toLowerCase();
  actual.toLowerCase();
  if (expected.length() == 0) return true;
  if (actual.length() == 0) return false;
  return expected == actual;
}

void copyStringToBuffer(const String& value, char* buffer, size_t bufferSize) {
  if (bufferSize == 0) return;
  size_t len = value.length();
  if (len >= bufferSize) len = bufferSize - 1;
  memcpy(buffer, value.c_str(), len);
  buffer[len] = '\0';
}

void persistPairing(String host, String hostname) {
  host = cleanIdentityValue(host, sizeof(pairingStore.targetHost) - 1);
  hostname = cleanIdentityValue(hostname, sizeof(pairingStore.targetHostname) - 1);

  memset(&pairingStore, 0, sizeof(pairingStore));
  memcpy(pairingStore.magic, "UAMPR1", 6);
  copyStringToBuffer(host, pairingStore.targetHost, sizeof(pairingStore.targetHost));
  copyStringToBuffer(hostname, pairingStore.targetHostname, sizeof(pairingStore.targetHostname));
  EEPROM.put(PAIRING_EEPROM_ADDR, pairingStore);
  pairedTargetHost = host;
  pairedTargetHostname = hostname;
}

void clearPersistedPairing() {
  memset(&pairingStore, 0, sizeof(pairingStore));
  EEPROM.put(PAIRING_EEPROM_ADDR, pairingStore);
  pairedTargetHost = "";
  pairedTargetHostname = "";
}

void loadPersistedPairing() {
  EEPROM.get(PAIRING_EEPROM_ADDR, pairingStore);
  if (strncmp(pairingStore.magic, "UAMPR1", 6) == 0) {
    pairedTargetHost = cleanIdentityValue(String(pairingStore.targetHost), sizeof(pairingStore.targetHost) - 1);
    pairedTargetHostname = cleanIdentityValue(String(pairingStore.targetHostname), sizeof(pairingStore.targetHostname) - 1);
  } else {
    pairedTargetHost = "";
    pairedTargetHostname = "";
  }

  String configuredTargetHost = cleanIdentityValue(String(TARGET_HOST), 63);
  String configuredTargetHostname = cleanIdentityValue(String(TARGET_HOSTNAME), 63);
  if (configuredTargetHost.length() > 0 || configuredTargetHostname.length() > 0) {
    pairedTargetHost = configuredTargetHost;
    pairedTargetHostname = configuredTargetHostname;
  }
}

String effectiveTargetHost() {
  return pairedTargetHost;
}

String effectiveTargetHostname() {
  return pairedTargetHostname;
}

bool flashedTargetsConfigured() {
  String configuredTargetHost = cleanIdentityValue(String(TARGET_HOST), 63);
  String configuredTargetHostname = cleanIdentityValue(String(TARGET_HOSTNAME), 63);
  return configuredTargetHost.length() > 0 || configuredTargetHostname.length() > 0;
}

void emitControlResponse(const char* source, const String& message) {
  if (strcmp(source, "USB") == 0) {
    Serial.println(message);
  } else if (strcmp(source, "WIFI") == 0 && wifiClient) {
    wifiClient.println(message);
  }
}

bool handleControlCommand(String line, const char* source) {
  line.trim();
  if (line != RESET_PAIRING_COMMAND) {
    return false;
  }

  clearPersistedPairing();
  if (wifiClient) {
    wifiClient.stop();
  }
  wifiClientAuthorized = false;
  wifiClientConnectedAtMs = 0;
  wifiAuthLine = "";
  wifiLine = "";

  if (flashedTargetsConfigured()) {
    emitControlResponse(source, "PAIRING_RESET:EEPROM_CLEARED_FLASH_TARGET_STILL_ACTIVE");
  } else {
    emitControlResponse(source, "PAIRING_RESET:OK");
  }

  Serial.println("PAIRING RESET: stored Wi-Fi pairing cleared. Next valid monitor may pair.");
  return true;
}

void rejectWifiClient(const char* reason) {
  if (wifiClient) {
    wifiClient.print(String("PAIR_REJECT|") + reason + "\n");
    wifiClient.stop();
  }
  wifiClientAuthorized = false;
  wifiClientConnectedAtMs = 0;
  wifiAuthLine = "";
  wifiLine = "";
}

bool authorizeWifiClientLine(String line) {
  line.trim();
  if (!line.startsWith(String(PAIRING_MAGIC) + "|")) {
    rejectWifiClient("missing_handshake");
    return false;
  }

  int sep1 = line.indexOf('|');
  int sep2 = line.indexOf('|', sep1 + 1);
  if (sep1 < 0 || sep2 < 0) {
    rejectWifiClient("bad_handshake");
    return false;
  }

  String clientHostname = cleanIdentityValue(line.substring(sep1 + 1, sep2), 63);
  String clientHost = cleanIdentityValue(line.substring(sep2 + 1), 63);
  String expectedHost = effectiveTargetHost();
  String expectedHostname = effectiveTargetHostname();

  if (!identityMatches(expectedHost, clientHost) || !identityMatches(expectedHostname, clientHostname)) {
    rejectWifiClient("wrong_host");
    return false;
  }

  if (expectedHost.length() == 0 && expectedHostname.length() == 0) {
    persistPairing(clientHost, clientHostname);
  }

  wifiClientAuthorized = true;
  wifiClient.print("PAIR_OK\n");
  return true;
}


bool wifiCredentialsProvided() {
  String configuredSsid = String(ssid);
  configuredSsid.trim();
  String configuredPass = String(pass);
  configuredPass.trim();

  if (configuredSsid.length() == 0) return false;
  if (configuredSsid == "YOUR_WIFI_SSID") return false;
  if (configuredPass == "YOUR_WIFI_PASSWORD") return false;
  return true;
}

void clearArea(int x, int y, int w, int h) {
  tft.fillRect(x, y, w, h, BLACK);
}

int parsePercent(String s) {
  s.replace("%", "");
  s.trim();
  int value = s.toInt();
  if (value < 0) value = 0;
  if (value > 100) value = 100;
  return value;
}

uint16_t getColor(int percent) {
  if (percent < 50) return GREEN;
  if (percent < 80) return YELLOW;
  return RED;
}

String fitText(String s, int maxLen) {
  s.trim();
  if (s.length() <= maxLen) return s;
  return s.substring(0, maxLen);
}

String ellipsizeText(String s, int maxLen) {
  s.trim();
  if (maxLen <= 0) return "";
  if (s.length() <= maxLen) return s;
  if (maxLen <= 3) return s.substring(0, maxLen);
  return s.substring(0, maxLen - 3) + "...";
}

void splitBatteryLabel(String input, int firstMax, int secondMax, String &line1, String &line2) {
  String s = input;
  s.trim();
  line1 = "";
  line2 = "";
  if (s.length() <= firstMax) {
    line1 = s;
    return;
  }

  int splitAt = -1;
  int limit = min(firstMax, (int)s.length() - 1);
  for (int i = limit; i >= 0; --i) {
    if (s.charAt(i) == ' ') {
      splitAt = i;
      break;
    }
  }

  if (splitAt <= 0) {
    line1 = ellipsizeText(s, firstMax);
    return;
  }

  line1 = s.substring(0, splitAt);
  line1.trim();
  line2 = s.substring(splitAt + 1);
  line2.trim();
  line2 = ellipsizeText(line2, secondMax);
}

String prettyGPU(String s) {
  s.trim();
  s.replace("(TM)", "");
  s.replace("(R)", "");
  s.replace("Advanced Micro Devices, Inc.", "");
  s.replace("AMD/ATI", "AMD");
  s.replace("Radeon RX 6600 XT", "RX 6600XT");
  s.replace("Radeon RX 6600", "RX 6600");
  while (s.indexOf("  ") != -1) s.replace("  ", " ");
  s.trim();
  return s;
}

String prettyOS(String s) {
  s.trim();

  int idx = s.indexOf(" (");
  if (idx != -1) {
    s = s.substring(0, idx);
  }

  s.trim();
  return s;
}

void drawBar(int x, int y, int w, int h, int percent, uint16_t color) {
  if (percent < 0) percent = 0;
  if (percent > 100) percent = 100;
  tft.drawRect(x, y, w, h, WHITE);
  int fill = (percent * (w - 2)) / 100;
  tft.fillRect(x + 1, y + 1, w - 2, h - 2, BLACK);
  if (fill > 0) tft.fillRect(x + 1, y + 1, fill, h - 2, color);
}

String wifiStateText() {
  return WiFi.status() == WL_CONNECTED ? "WiFi On" : "WiFi Off";
}

uint16_t wifiStateColor() {
  return WiFi.status() == WL_CONNECTED ? GREEN : RED;
}

void refreshArduinoWifiIp() {
  if (WiFi.status() == WL_CONNECTED && WiFi.localIP() != IPAddress(0, 0, 0, 0)) {
    arduinoWifiIp = WiFi.localIP().toString();
  } else {
    arduinoWifiIp = "--";
  }
}

void waitForValidIP(unsigned long timeoutMs = 10000) {
  unsigned long start = millis();
  while (WiFi.status() == WL_CONNECTED &&
         WiFi.localIP() == IPAddress(0, 0, 0, 0) &&
         millis() - start < timeoutMs) {
    delay(250);
    Serial.print("+");
  }
  refreshArduinoWifiIp();
}

void startNetworkServices() {
  if (WiFi.status() != WL_CONNECTED) {
    return;
  }
  waitForValidIP();
  server.begin();
  discoveryUdp.stop();
  discoveryUdp.begin(DISCOVERY_PORT);
}

void handleDiscoveryRequests() {
  if (WiFi.status() != WL_CONNECTED) {
    return;
  }

  int packetSize = discoveryUdp.parsePacket();
  if (packetSize <= 0) {
    return;
  }

  char packet[96];
  int len = discoveryUdp.read(packet, sizeof(packet) - 1);
  if (len < 0) {
    return;
  }
  packet[len] = '\0';

  String request = String(packet);
  request.trim();
  if (request != DISCOVERY_MAGIC) {
    return;
  }

  refreshArduinoWifiIp();
  String response = String(DISCOVERY_RESPONSE_PREFIX) + "|" + arduinoWifiIp + "|" + String(TCP_PORT) + "|" + DEVICE_NAME + "|" + effectiveTargetHost() + "|" + effectiveTargetHostname();
  discoveryUdp.beginPacket(discoveryUdp.remoteIP(), discoveryUdp.remotePort());
  discoveryUdp.print(response);
  discoveryUdp.endPacket();
}

void drawHeader(const char* title, int page) {
  tft.fillScreen(BLACK);
  refreshArduinoWifiIp();

  String pageTitle = (page == 1) ? String("Ray Co. System Monitor") : String(title);
  String pageStr = String(page) + "/" + String(TOTAL_PAGES);
  String versionStr = String(APP_VERSION);
  String wifiStr = wifiStateText();

  if (page == 1) {
    const int titleX = 12;
    const int wifiX = 170;
    const int versionX = 244;
    const int pageX = 432;

    tft.setTextSize(1);
    tft.setTextColor(CYAN);
    tft.setCursor(titleX, 16);
    tft.print(pageTitle);

    tft.setTextColor(wifiStateColor());
    tft.setCursor(wifiX, 16);
    tft.print(fitText(wifiStr, 8));

    tft.setTextColor(WHITE);
    tft.setCursor(versionX, 16);
    tft.print(fitText(versionStr, 10));

    tft.setCursor(pageX, 16);
    tft.print(pageStr);
  } else {
    const int pageX = 424;
    const int versionX = 176;
    const int wifiX = 302;

    tft.setTextSize(2);
    tft.setTextColor(CYAN);
    tft.setCursor(12, 10);
    tft.print(fitText(pageTitle, 17));

    tft.setTextSize(1);
    tft.setTextColor(WHITE);
    tft.setCursor(versionX, 18);
    tft.print(versionStr);

    tft.setTextColor(wifiStateColor());
    tft.setCursor(wifiX, 18);
    tft.print(wifiStr);

    tft.setTextSize(2);
    tft.setTextColor(WHITE);
    tft.setCursor(pageX, 10);
    tft.print(pageStr);
  }

  tft.drawLine(0, 34, SCREEN_W, 34, WHITE);
}

void drawLabelBar(int y, const char* label, int pct, uint16_t color) {
  clearArea(0, y, SCREEN_W, 30);
  tft.setTextSize(2);
  tft.setTextColor(WHITE);
  tft.setCursor(15, y);
  tft.print(label);
  drawBar(145, y + 3, 220, 16, pct, color);
  tft.setCursor(380, y);
  tft.print(pct);
  tft.print("%");
}

void drawInfoLine(int y, const char* label, String value, uint16_t color, int valueX) {
  clearArea(0, y, SCREEN_W, 24);
  tft.setTextSize(2);
  tft.setTextColor(WHITE);
  tft.setCursor(15, y);
  tft.print(label);
  tft.setTextColor(color);
  tft.setCursor(valueX, y);
  tft.print(value);
}

void drawDualInfoLine(
  int y,
  const char* leftLabel,
  String leftValue,
  uint16_t leftColor,
  int leftValueX,
  const char* rightLabel,
  String rightValue,
  uint16_t rightColor,
  int rightLabelX,
  int rightValueX
) {
  clearArea(0, y, SCREEN_W, 24);
  tft.setTextSize(2);

  tft.setTextColor(WHITE);
  tft.setCursor(15, y);
  tft.print(leftLabel);
  tft.setTextColor(leftColor);
  tft.setCursor(leftValueX, y);
  tft.print(leftValue);

  tft.setTextColor(WHITE);
  tft.setCursor(rightLabelX, y);
  tft.print(rightLabel);
  tft.setTextColor(rightColor);
  tft.setCursor(rightValueX, y);
  tft.print(rightValue);
}

void pushHistory(int cpuVal, int ramVal, int gpuVal, int vramVal) {
  cpuVal = constrain(cpuVal, 0, 100);
  ramVal = constrain(ramVal, 0, 100);
  gpuVal = constrain(gpuVal, 0, 100);
  vramVal = constrain(vramVal, 0, 100);
  for (int i = 0; i < GRAPH_POINTS - 1; i++) {
    cpuHistory[i] = cpuHistory[i + 1];
    ramHistory[i] = ramHistory[i + 1];
    gpuHistory[i] = gpuHistory[i + 1];
    vramHistory[i] = vramHistory[i + 1];
  }
  cpuHistory[GRAPH_POINTS - 1] = cpuVal;
  ramHistory[GRAPH_POINTS - 1] = ramVal;
  gpuHistory[GRAPH_POINTS - 1] = gpuVal;
  vramHistory[GRAPH_POINTS - 1] = vramVal;
}

void drawHomeLayout()    { drawHeader("Ray Co. System Monitor", 1); }
void drawCpuLayout()     { drawHeader("CPU Threads", 2); }
void drawProcLayout()    { drawHeader("Processes", 3); }
void drawNetLayout()     { drawHeader("Network", 4); }
void drawGpuLayout()     { drawHeader("GPU", 5); }
void drawStorageLayout() { drawHeader("Extra Statistics", 6); }
void drawGraphLayout()   { drawHeader("Usage Graph", 7); }

void updateHome() {
  drawLabelBar(46,  "CPU", cpuTotal, getColor(cpuTotal));
  drawLabelBar(80,  "RAM", ramPct, CYAN);
  drawLabelBar(114, "Disk0", disk0Pct, MAGENTA);
  drawLabelBar(148, "Disk1", disk1Pct, ORANGE);
  drawInfoLine(184, "Freq", fitText(cpuFreqStr, 18), ORANGE, 95);
  drawInfoLine(210, "RAM", fitText(ramUsageStr, 18), CYAN, 95);
  drawInfoLine(236, "Host", fitText(hostName, 24), GREEN, 95);
  drawDualInfoLine(262, "Up", fitText(uptimeStr, 18), WHITE, 95, "Link", fitText(linkType, 8), YELLOW, 245, 330);
  drawInfoLine(288, "OS", fitText(prettyOS(osName), 28), CYAN, 95);
}

void drawThreadCell(int x, int y, int idx, int pct) {
  uint16_t color = getColor(pct);
  clearArea(x, y, 112, 36);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(x, y);
  tft.print("C");
  tft.print(idx);
  tft.setCursor(x + 78, y);
  tft.setTextColor(color);
  tft.print(pct);
  tft.print("%");
  drawBar(x, y + 12, 102, 10, pct, color);
}

void updateCpu() {
  drawLabelBar(44, "Total", cpuTotal, getColor(cpuTotal));

  int startY = 82;
  int startX = 12;
  int colW = 114;
  int rowH = 44;
  for (int i = 0; i < CPU_THREADS; i++) {
    int row = i / 4;
    int col = i % 4;
    drawThreadCell(startX + (col * colW), startY + (row * rowH), i, cpuThreads[i]);
  }

  clearArea(0, 260, SCREEN_W, 34);
  tft.setTextSize(2);
  tft.setTextColor(ORANGE);
  tft.setCursor(12, 266);
  tft.print("Freq ");
  tft.setTextColor(WHITE);
  tft.print(fitText(cpuFreqStr, 10));
  tft.setTextColor(CYAN);
  tft.setCursor(260, 266);
  tft.print("Temp ");
  tft.setTextColor(WHITE);
  tft.print(cpuTemp);
}

void drawProcessRow(int y, int idx, String name, String cpu, String ram, uint16_t color) {
  clearArea(0, y, SCREEN_W, 24);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(10, y + 7);
  tft.print(idx + 1);
  tft.print(".");
  tft.setTextColor(color);
  tft.setCursor(28, y + 7);
  tft.print(fitText(name, 20));
  tft.setTextColor(YELLOW);
  tft.setCursor(280, y + 7);
  tft.print(cpu);
  tft.setTextColor(CYAN);
  tft.setCursor(380, y + 7);
  tft.print(ram);
}

void updateProc() {
  clearArea(0, 42, SCREEN_W, 240);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(280, 44);
  tft.print("CPU");
  tft.setCursor(380, 44);
  tft.print("RAM");

  uint16_t colors[PROCESS_ROWS] = {GREEN, YELLOW, CYAN, MAGENTA, ORANGE, WHITE};
  for (int i = 0; i < PROCESS_ROWS; i++) {
    drawProcessRow(58 + (i * 30), i, procName[i], procCpu[i], procRam[i], colors[i]);
  }

  tft.setTextSize(2);
  tft.setTextColor(GREEN);
  tft.setCursor(12, 246);
  tft.print("CPU ");
  tft.setTextColor(WHITE);
  tft.print(cpuTotal);
  tft.print("%");
  tft.setTextColor(CYAN);
  tft.setCursor(180, 246);
  tft.print("RAM ");
  tft.setTextColor(WHITE);
  tft.print(ramPct);
  tft.print("%");
  tft.setTextColor(YELLOW);
  tft.setCursor(340, 246);
  tft.print("GPU ");
  tft.setTextColor(WHITE);
  tft.print(gpuPct);
  tft.print("%");
}

void updateNet() {
  refreshArduinoWifiIp();
  String arduinoIpWithPort = arduinoWifiIp;
  if (arduinoWifiIp != "--") {
    arduinoIpWithPort += ":" + String(TCP_PORT);
  }

  drawInfoLine(48,  "Host", fitText(hostName, 24), CYAN, 95);
  drawInfoLine(74,  "OS", fitText(prettyOS(osName), 32), ORANGE, 95);
  drawInfoLine(100, "PC IP", fitText(ipAddr, 18), WHITE, 95);
  drawInfoLine(126, "A IP", fitText(arduinoIpWithPort, 18), YELLOW, 95);
  drawInfoLine(152, "WiFi", wifiStateText(), wifiStateColor(), 95);
  drawInfoLine(178, "Down", fitText(downStr, 18), GREEN, 105);
  drawInfoLine(204, "Up", fitText(upStr, 18), YELLOW, 105);
  drawInfoLine(230, "DnTot", fitText(downTotalStr, 18), CYAN, 105);
  drawInfoLine(256, "UpTot", fitText(upTotalStr, 18), ORANGE, 105);
  drawInfoLine(282, "Uptm", fitText(uptimeStr, 18), WHITE, 105);
}

void updateGpu() {
  String gpuPretty = prettyGPU(gpuName);
  drawLabelBar(48, "GPU", gpuPct, getColor(gpuPct));

  clearArea(0, 86, SCREEN_W, 160);
  tft.setTextColor(WHITE);
  tft.setTextSize(2);
  tft.setCursor(15, 88);
  tft.print("Name");
  tft.setTextColor(CYAN);
  if (gpuPretty.length() <= 22) {
    tft.setTextSize(2);
    tft.setCursor(104, 88);
    tft.print(gpuPretty);
  } else {
    tft.setTextSize(2);
    tft.setCursor(104, 88);
    tft.print(fitText(gpuPretty, 28));
  }

  const int labelX = 15;
  const int valueX = 118;
  drawInfoLine(126, "Temp", fitText(gpuTemp, 12), CYAN, valueX);
  drawInfoLine(160, "Usage", String(gpuPct) + "%", GREEN, valueX);
  drawInfoLine(194, "VRAM", String(gpuMemUsed) + "/" + String(gpuMemTotal) + "M", YELLOW, valueX);
  drawInfoLine(228, "CLK Speed", String(gpuClock) + "MHz", ORANGE, 168);
}

void updateStorage() {
  clearArea(0, 42, SCREEN_W, 240);
  const int leftX = 12;
  const int storageRight = 244;
  const int rightPanelX = 258;
  const int panelTop = 52;

  tft.setTextSize(2);
  tft.setTextColor(CYAN);
  tft.setCursor(leftX, 50);
  tft.print("Storage");

  tft.drawFastVLine(storageRight, 46, 234, GRAY);

  for (int i = 0; i < STORAGE_LINES; i++) {
    clearArea(0, 72 + (i * 22), storageRight - 4, 18);
    tft.setTextSize(1);
    tft.setTextColor(WHITE);
    tft.setCursor(leftX, 78 + (i * 22));
    tft.print(fitText(storageLine[i], 32));
  }

  tft.setTextSize(2);
  tft.setTextColor(YELLOW);
  tft.setCursor(rightPanelX, panelTop);
  tft.print("Battery / Devices");

  int lineY = panelTop + 28;
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(rightPanelX, lineY);
  if (batteryModeStr == "DESKTOP") {
    tft.print("System Battery: N/A (Desktop)");
    lineY += 24;
  } else {
    tft.print("System Battery: ");
    tft.setTextColor(GREEN);
    tft.print(batteryPctStr);
    if (batteryPctStr != "N/A" && batteryPctStr != "--") {
      tft.print("%");
    }
    lineY += 18;

    tft.setTextColor((batteryStateStr == "Charging") ? GREEN : ORANGE);
    tft.setCursor(rightPanelX, lineY);
    tft.print(fitText(batteryStateStr, 28));
    lineY += 24;
  }

  for (int i = 0; i < EXTRA_BATTERY_SLOTS; i++) {
    if (batteryDeviceLabel[i].length() == 0 || batteryDeviceLabel[i] == "--") continue;

    String labelLine1;
    String labelLine2;
    splitBatteryLabel(batteryDeviceLabel[i], 24, 24, labelLine1, labelLine2);

    tft.setTextColor(CYAN);
    tft.setCursor(rightPanelX, lineY);
    tft.print(labelLine1);
    lineY += 14;

    if (labelLine2.length() > 0) {
      tft.setCursor(rightPanelX + 10, lineY);
      tft.print(labelLine2);
      lineY += 14;
    }

    if (batteryDeviceState[i].length() > 0 && batteryDeviceState[i] != "--") {
      tft.setTextColor((batteryDeviceState[i] == "Charging") ? GREEN : ORANGE);
      tft.setCursor(rightPanelX + 10, lineY);
      tft.print(ellipsizeText(batteryDeviceState[i], 26));
      lineY += 20;
    } else {
      lineY += 8;
    }
  }
}

void updateGraph() {
  const int gx = 20;
  const int gy = 52;
  const int gw = 430;
  const int gh = 140;

  clearArea(0, 40, SCREEN_W, 240);
  tft.drawRect(gx, gy, gw, gh, WHITE);

  for (int i = 1; i < 5; i++) {
    int y = gy + (gh * i) / 5;
    tft.drawLine(gx, y, gx + gw, y, GRAY);
  }
  for (int i = 1; i < 7; i++) {
    int x = gx + (gw * i) / 7;
    tft.drawLine(x, gy, x, gy + gh, GRAY);
  }

  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(gx + gw + 5, gy - 3);
  tft.print("100");
  tft.setCursor(gx + gw + 5, gy + gh - 3);
  tft.print("0");

  for (int i = 1; i < GRAPH_POINTS; i++) {
    int x1 = gx + ((i - 1) * (gw - 2)) / (GRAPH_POINTS - 1);
    int x2 = gx + (i * (gw - 2)) / (GRAPH_POINTS - 1);

    int y1 = gy + gh - 1 - ((cpuHistory[i - 1] * (gh - 2)) / 100);
    int y2 = gy + gh - 1 - ((cpuHistory[i] * (gh - 2)) / 100);
    tft.drawLine(x1, y1, x2, y2, GREEN);

    y1 = gy + gh - 1 - ((ramHistory[i - 1] * (gh - 2)) / 100);
    y2 = gy + gh - 1 - ((ramHistory[i] * (gh - 2)) / 100);
    tft.drawLine(x1, y1, x2, y2, CYAN);

    y1 = gy + gh - 1 - ((gpuHistory[i - 1] * (gh - 2)) / 100);
    y2 = gy + gh - 1 - ((gpuHistory[i] * (gh - 2)) / 100);
    tft.drawLine(x1, y1, x2, y2, YELLOW);

    y1 = gy + gh - 1 - ((vramHistory[i - 1] * (gh - 2)) / 100);
    y2 = gy + gh - 1 - ((vramHistory[i] * (gh - 2)) / 100);
    tft.drawLine(x1, y1, x2, y2, MAGENTA);
  }

  clearArea(0, 198, SCREEN_W, 88);
  tft.setTextSize(2);
  tft.setTextColor(GREEN);
  tft.setCursor(20, 206);
  tft.print("CPU");
  tft.setTextColor(WHITE);
  tft.setCursor(90, 206);
  tft.print(String(cpuTotal) + "%");

  tft.setTextColor(YELLOW);
  tft.setCursor(250, 206);
  tft.print("GPU");
  tft.setTextColor(WHITE);
  tft.setCursor(320, 206);
  tft.print(String(gpuPct) + "%");

  tft.setTextColor(CYAN);
  tft.setCursor(20, 232);
  tft.print("RAM");
  tft.setTextColor(WHITE);
  tft.setCursor(90, 232);
  tft.print(String(ramPct) + "%");

  tft.setTextColor(MAGENTA);
  tft.setCursor(250, 232);
  tft.print("VRAM");
  tft.setTextColor(WHITE);
  tft.setCursor(350, 232);
  tft.print(String(gpuMemPct) + "%");
}

void drawCurrentLayout() {
  if (currentPage == 0) drawHomeLayout();
  else if (currentPage == 1) drawCpuLayout();
  else if (currentPage == 2) drawProcLayout();
  else if (currentPage == 3) drawNetLayout();
  else if (currentPage == 4) drawGpuLayout();
  else if (currentPage == 5) drawStorageLayout();
  else drawGraphLayout();
}

void updateCurrentPage() {
  if (currentPage == 0) updateHome();
  else if (currentPage == 1) updateCpu();
  else if (currentPage == 2) updateProc();
  else if (currentPage == 3) updateNet();
  else if (currentPage == 4) updateGpu();
  else if (currentPage == 5) updateStorage();
  else updateGraph();
}

void handleTouch() {
  int x, y;
  bool pressed = tft.getTouch(x, y);
  if (pressed && !touchHeld && millis() - lastTouchTime > touchDebounceMs) {
    touchHeld = true;
    lastTouchTime = millis();
    currentPage = (currentPage + 1) % TOTAL_PAGES;
    drawCurrentLayout();
    updateCurrentPage();
    screenDirty = false;
    lastScreenUpdate = millis();
  }
  if (!pressed) touchHeld = false;
}

bool splitFields(String s, String out[], int expectedCount) {
  int start = 0;
  int idx = 0;
  while (idx < expectedCount - 1) {
    int sep = s.indexOf('|', start);
    if (sep == -1) return false;
    out[idx++] = s.substring(start, sep);
    start = sep + 1;
  }
  out[idx] = s.substring(start);
  return true;
}

void parseIncomingLine(String s, const char* source) {
  if (handleControlCommand(s, source)) {
    return;
  }

  String f[FIELD_COUNT];
  if (!splitFields(s, f, FIELD_COUNT)) return;

  int idx = 0;
  cpuTotal = parsePercent(f[idx++]);
  for (int i = 0; i < CPU_THREADS; i++) cpuThreads[i] = parsePercent(f[idx++]);
  ramPct = parsePercent(f[idx++]);
  disk0Pct = parsePercent(f[idx++]);
  disk1Pct = parsePercent(f[idx++]);
  cpuTemp = f[idx++];
  osName = f[idx++];
  hostName = f[idx++];
  ipAddr = f[idx++];
  uptimeStr = f[idx++];
  downStr = f[idx++];
  upStr = f[idx++];
  downTotalStr = f[idx++];
  upTotalStr = f[idx++];
  cpuFreqStr = f[idx++];
  ramUsageStr = f[idx++];
  gpuPct = parsePercent(f[idx++]);
  gpuTemp = f[idx++];
  gpuMemUsed = f[idx++].toInt();
  gpuMemTotal = f[idx++].toInt();
  gpuMemPct = parsePercent(f[idx++]);
  gpuClock = f[idx++].toInt();
  gpuName = f[idx++];

  for (int i = 0; i < PROCESS_ROWS; i++) procName[i] = f[idx++];
  for (int i = 0; i < PROCESS_ROWS; i++) procCpu[i] = f[idx++];
  for (int i = 0; i < PROCESS_ROWS; i++) procRam[i] = f[idx++];
  for (int i = 0; i < STORAGE_LINES; i++) storageLine[i] = f[idx++];
  batteryPctStr = f[idx++];
  batteryStateStr = f[idx++];
  batteryModeStr = f[idx++];
  for (int i = 0; i < EXTRA_BATTERY_SLOTS; i++) batteryDeviceLabel[i] = f[idx++];
  for (int i = 0; i < EXTRA_BATTERY_SLOTS; i++) batteryDeviceState[i] = f[idx++];

  pushHistory(cpuTotal, ramPct, gpuPct, gpuMemPct);
  screenDirty = true;
}



void parseIncomingChar(char c, String &buffer, const char* source) {
  if (c == '\n') {
    parseIncomingLine(buffer, source);
    buffer = "";
    linkType = source;
  } else if (c != '\r') {
    buffer += c;
    if (buffer.length() > 768) {
      buffer = "";
    }
  }
}

bool usbTransportActive() {
  return usbSessionActive && (millis() - lastUsbActivityMs) < usbPriorityHoldMs;
}

void suspendWiFiForUsb() {
  refreshArduinoWifiIp();
  if (wifiClient) {
    wifiClient.stop();
  }

  if (WiFi.status() == WL_CONNECTED) {
    server.end();
    discoveryUdp.stop();
    WiFi.disconnect();
    refreshArduinoWifiIp();
  }

  wifiSuspendedForUsb = true;
}

void resumeWiFiAfterUsb() {
  if (!wifiSuspendedForUsb) {
    return;
  }
  wifiSuspendedForUsb = false;
  lastWifiRetryMs = 0;
}

void ensureWiFi() {
  if (!wifiConfigured) {
    return;
  }

  if (usbTransportActive()) {
    suspendWiFiForUsb();
    return;
  }

  resumeWiFiAfterUsb();

  if (WiFi.status() == WL_CONNECTED) {
    return;
  }
  if (millis() - lastWifiRetryMs < wifiRetryIntervalMs) {
    return;
  }

  lastWifiRetryMs = millis();
  Serial.println("WiFi lost. Reconnecting...");
  WiFi.disconnect();
  delay(250);
  WiFi.begin(ssid, pass);

  unsigned long reconnectStart = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - reconnectStart < 15000) {
    delay(500);
    Serial.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWiFi reconnected!");
    startNetworkServices();
    Serial.print("IP: ");
    Serial.println(arduinoWifiIp);
    Serial.print("Signal strength (RSSI): ");
    Serial.println(WiFi.RSSI());
    Serial.print("Discovery UDP port: ");
    Serial.println(DISCOVERY_PORT);
  } else {
    refreshArduinoWifiIp();
    Serial.println("\nWiFi reconnect failed.");
  }
}

void handleUsbInput() {
  while (Serial.available()) {
    usbSessionActive = true;
    lastUsbActivityMs = millis();
    parseIncomingChar((char)Serial.read(), usbLine, "USB");
  }
}

void handleWifiInput() {
  ensureWiFi();

  if (usbTransportActive()) {
    return;
  }

  handleDiscoveryRequests();

  if (WiFi.status() != WL_CONNECTED) {
    if (wifiClient) {
      wifiClient.stop();
    }
    return;
  }

  if (!wifiClient || !wifiClient.connected()) {
    WiFiClient incoming = server.available();
    if (incoming) {
      if (wifiClient) {
        wifiClient.stop();
      }
      wifiClient = incoming;
      wifiClient.setTimeout(5);
      wifiClientAuthorized = false;
      wifiClientConnectedAtMs = millis();
      wifiAuthLine = "";
      wifiLine = "";
    }
  }

  while (wifiClient && wifiClient.connected() && wifiClient.available()) {
    char incomingChar = (char)wifiClient.read();
    if (!wifiClientAuthorized) {
      if (incomingChar == '\r') {
        continue;
      }
      if (incomingChar == '\n') {
        if (!authorizeWifiClientLine(wifiAuthLine)) {
          return;
        }
        wifiAuthLine = "";
        continue;
      }
      if (wifiAuthLine.length() < 96) {
        wifiAuthLine += incomingChar;
      }
      continue;
    }
    parseIncomingChar(incomingChar, wifiLine, "WIFI");
  }

  if (wifiClient && !wifiClientAuthorized && wifiClientConnectedAtMs > 0 && millis() - wifiClientConnectedAtMs > WIFI_CLIENT_AUTH_TIMEOUT_MS) {
    rejectWifiClient("timeout");
    return;
  }

  if (wifiClient && !wifiClient.connected()) {
    wifiClient.stop();
    wifiClientAuthorized = false;
    wifiClientConnectedAtMs = 0;
  }


}

void setup() {
  Serial.begin(115200);
  delay(2000);
  Serial.println("BOOT: setup start");
  loadPersistedPairing();

  tft.begin();
  tft.setRotation(DISPLAY_ROTATION_VALUE);
  tft.setTouchCalibration(LEFT_X, RIGHT_X, TOP_Y, BOT_Y);

  wifiConfigured = wifiCredentialsProvided();
  if (wifiConfigured) {
    Serial.println("BOOT: starting WiFi...");
    WiFi.begin(ssid, pass);

    unsigned long wifiStart = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - wifiStart < 20000) {
      delay(500);
      Serial.print(".");
    }
  } else {
    Serial.println("BOOT: WiFi credentials not set. Waiting for USB or future WiFi setup.");
  }

  if (wifiConfigured && WiFi.status() == WL_CONNECTED) {
    Serial.println("\nBOOT: WiFi connected, waiting for valid IP...");
    startNetworkServices();
    Serial.println("BOOT: WiFi ready");
    Serial.print("IP: ");
    Serial.println(arduinoWifiIp);
    Serial.print("Signal strength (RSSI): ");
    Serial.println(WiFi.RSSI());
    Serial.print("TCP server started on port ");
    Serial.println(TCP_PORT);
    Serial.print("Discovery UDP port: ");
    Serial.println(DISCOVERY_PORT);
  } else {
    refreshArduinoWifiIp();
    if (wifiConfigured) {
      Serial.println("\nBOOT: WiFi connection failed. USB mode is still available.");
    } else {
      Serial.println("BOOT: USB mode is active until WiFi credentials are provided.");
    }
  }

  for (int i = 0; i < GRAPH_POINTS; i++) {
    cpuHistory[i] = 0;
    ramHistory[i] = 0;
    gpuHistory[i] = 0;
    vramHistory[i] = 0;
  }
  for (int i = 0; i < PROCESS_ROWS; i++) {
    procName[i] = "--";
    procCpu[i] = "--";
    procRam[i] = "--";
  }
  for (int i = 0; i < STORAGE_LINES; i++) {
    storageLine[i] = "Storage: --";
  }
  batteryPctStr = "N/A";
  batteryStateStr = "DESKTOP";
  batteryModeStr = "DESKTOP";
  for (int i = 0; i < EXTRA_BATTERY_SLOTS; i++) {
    batteryDeviceLabel[i] = "--";
    batteryDeviceState[i] = "--";
  }

  drawCurrentLayout();
  updateCurrentPage();
  lastScreenUpdate = millis();
  screenDirty = false;
  Serial.println("BOOT: setup complete");
}

void loop() {
  handleUsbInput();
  handleWifiInput();
  handleTouch();

  if (screenDirty && millis() - lastScreenUpdate >= screenUpdateIntervalMs) {
    updateCurrentPage();
    lastScreenUpdate = millis();
    screenDirty = false;
  }
}
