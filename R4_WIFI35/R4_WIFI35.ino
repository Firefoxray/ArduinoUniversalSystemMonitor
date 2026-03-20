#include <DIYables_TFT_Touch_Shield.h>
#include "wifi_config.h"
#include <WiFiS3.h>
#include <WiFiUdp.h>

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

const uint16_t TCP_PORT = 5000;
const uint16_t DISCOVERY_PORT = 5001;
const char* DEVICE_NAME = "R4_WIFI35";
const char* DISCOVERY_MAGIC = "UAM_DISCOVER";
const char* DISCOVERY_RESPONSE_PREFIX = "UAM_HERE";

WiFiServer server(TCP_PORT);
WiFiClient wifiClient;
WiFiUDP discoveryUdp;
String usbLine = "";
String wifiLine = "";
String linkType = "NONE";
String arduinoWifiIp = "--";
bool wifiSuspendedForUsb = false;
unsigned long lastWifiRetryMs = 0;
unsigned long lastUsbActivityMs = 0;
const unsigned long wifiRetryIntervalMs = 8000;
const unsigned long usbPriorityHoldMs = 15000;

const int SCREEN_W = 480;
const int SCREEN_H = 320;
const int GRAPH_POINTS = 62;
const int TOTAL_PAGES = 7;
const int CPU_THREADS = 16;
const int PROCESS_ROWS = 6;
const int STORAGE_LINES = 7;
const int FIELD_COUNT = 64;

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
String gpuTemp = "--";
String gpuName = "--";
String opticalStr = "--";
String ramUsageStr = "--";

String procName[PROCESS_ROWS];
String procCpu[PROCESS_ROWS];
String procRam[PROCESS_ROWS];
String storageLine[STORAGE_LINES];

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
  return WiFi.status() == WL_CONNECTED ? "WIFI OK" : "WIFI OFF";
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
  String response = String(DISCOVERY_RESPONSE_PREFIX) + "|" + arduinoWifiIp + "|" + String(TCP_PORT) + "|" + DEVICE_NAME;
  discoveryUdp.beginPacket(discoveryUdp.remoteIP(), discoveryUdp.remotePort());
  discoveryUdp.print(response);
  discoveryUdp.endPacket();
}

void drawHeader(const char* title, int page) {
  tft.fillScreen(BLACK);
  refreshArduinoWifiIp();

  tft.setTextSize(2);
  tft.setTextColor(CYAN);
  tft.setCursor(12, 10);
  tft.print(title);

  tft.setTextSize(1);
  tft.setTextColor(wifiStateColor());
  tft.setCursor(295, 18);
  tft.print(wifiStateText());

  String pageStr = String(page) + "/" + String(TOTAL_PAGES);
  tft.setTextSize(2);
  tft.setTextColor(WHITE);
  tft.setCursor(430, 10);
  tft.print(pageStr);

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
void drawStorageLayout() { drawHeader("Storage Inventory", 6); }
void drawGraphLayout()   { drawHeader("Usage Graph", 7); }

void updateHome() {
  drawLabelBar(46,  "CPU", cpuTotal, getColor(cpuTotal));
  drawLabelBar(80,  "RAM", ramPct, CYAN);
  drawLabelBar(114, "Disk0", disk0Pct, MAGENTA);
  drawLabelBar(148, "Disk1", disk1Pct, ORANGE);
  drawInfoLine(184, "Freq", fitText(cpuFreqStr, 18), ORANGE, 95);
  drawInfoLine(210, "RAM", fitText(ramUsageStr, 18), CYAN, 95);
  drawInfoLine(236, "Host", fitText(hostName, 24), GREEN, 95);
  drawInfoLine(262, "Up",   fitText(uptimeStr, 18), WHITE, 95);
  drawInfoLine(262, "Link", fitText(linkType, 8), YELLOW, 330);
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
  drawInfoLine(48,  "Host", fitText(hostName, 24), CYAN, 95);
  drawInfoLine(74,  "OS", fitText(prettyOS(osName), 32), ORANGE, 95);
  drawInfoLine(100, "PC IP", fitText(ipAddr, 18), WHITE, 95);
  drawInfoLine(126, "A IP", fitText(arduinoWifiIp, 18), YELLOW, 95);
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

  clearArea(0, 88, SCREEN_W, 30);
  tft.setTextSize(2);
  tft.setTextColor(WHITE);
  tft.setCursor(15, 88);
  tft.print("Name");
  if (gpuPretty.length() <= 20) {
    tft.setCursor(110, 88);
    tft.print(gpuPretty);
  } else {
    tft.setTextSize(1);
    tft.setCursor(110, 95);
    tft.print(fitText(gpuPretty, 36));
  }

  drawInfoLine(122, "Temp", fitText(gpuTemp, 12), CYAN, 110);
  drawInfoLine(156, "VRAM", String(gpuMemUsed) + "/" + String(gpuMemTotal) + "M", YELLOW, 110);
  drawInfoLine(190, "VRAM%", String(gpuMemPct) + "%", GREEN, 128);
  drawInfoLine(224, "Clk", String(gpuClock) + "MHz", ORANGE, 105);
}

void updateStorage() {
  clearArea(0, 42, SCREEN_W, 240);
  for (int i = 0; i < STORAGE_LINES; i++) {
    clearArea(0, 48 + (i * 26), SCREEN_W, 22);
    tft.setTextSize(1);
    tft.setTextColor(WHITE);
    tft.setCursor(12, 56 + (i * 26));
    tft.print(storageLine[i]);
  }

  tft.setTextSize(2);
  tft.setTextColor(CYAN);
  tft.setCursor(12, 236);
  tft.print("Opt");
  tft.setTextColor(WHITE);
  tft.setCursor(80, 236);
  tft.print(fitText(opticalStr, 18));
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

void parseIncomingLine(String s) {
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
  opticalStr = f[idx++];
  ramUsageStr = f[idx++];

  pushHistory(cpuTotal, ramPct, gpuPct, gpuMemPct);
  screenDirty = true;
}



void parseIncomingChar(char c, String &buffer, const char* source) {
  if (c == '\n') {
    parseIncomingLine(buffer);
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
  return Serial || (millis() - lastUsbActivityMs) < usbPriorityHoldMs;
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
    }
  }

  while (wifiClient && wifiClient.connected() && wifiClient.available()) {
    parseIncomingChar((char)wifiClient.read(), wifiLine, "WIFI");
  }

  if (wifiClient && !wifiClient.connected()) {
    wifiClient.stop();
  }


}

void setup() {
  Serial.begin(115200);
  delay(2000);
  Serial.println("BOOT: setup start");

  tft.begin();
  tft.setRotation(3); //1 for charger left, 3 for charger right
  tft.setTouchCalibration(LEFT_X, RIGHT_X, TOP_Y, BOT_Y);

  if (usbTransportActive()) {
    wifiSuspendedForUsb = true;
    refreshArduinoWifiIp();
    Serial.println("BOOT: USB host detected, delaying WiFi until USB is idle.");
  } else {
    Serial.println("BOOT: starting WiFi...");
    WiFi.begin(ssid, pass);

    unsigned long wifiStart = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - wifiStart < 20000) {
      delay(500);
      Serial.print(".");
    }

    if (WiFi.status() == WL_CONNECTED) {
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
      Serial.println("\nBOOT: WiFi connection failed. USB mode is still available.");
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
    storageLine[i] = "Disk: --";
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
