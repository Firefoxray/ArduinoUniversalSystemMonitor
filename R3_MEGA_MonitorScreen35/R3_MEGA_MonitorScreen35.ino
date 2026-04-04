#include <DIYables_TFT_Touch_Shield.h>
#if __has_include("display_config.local.h")
#include "display_config.local.h"
#else
#include "display_config.h"
#endif
#if __has_include("page_config.local.h")
#include "page_config.local.h"
#endif
#include <string.h>
#include <stdlib.h>
#if __has_include("app_version.generated.h")
#include "app_version.generated.h"
#endif

#ifndef APP_VERSION
#define APP_VERSION "unknown version"
#endif

#ifndef DISPLAY_ROTATION_VALUE
#define DISPLAY_ROTATION_VALUE 1
#endif

#ifndef UASM_PAGE_HOME_ENABLED
#define UASM_PAGE_HOME_ENABLED 1
#endif
#ifndef UASM_PAGE_CPU_ENABLED
#define UASM_PAGE_CPU_ENABLED 1
#endif
#ifndef UASM_PAGE_PROCESSES_ENABLED
#define UASM_PAGE_PROCESSES_ENABLED 1
#endif
#ifndef UASM_PAGE_NETWORK_ENABLED
#define UASM_PAGE_NETWORK_ENABLED 1
#endif
#ifndef UASM_PAGE_GPU_ENABLED
#define UASM_PAGE_GPU_ENABLED 1
#endif
#ifndef UASM_PAGE_STORAGE_ENABLED
#define UASM_PAGE_STORAGE_ENABLED 1
#endif
#ifndef UASM_PAGE_USAGE_GRAPH_ENABLED
#define UASM_PAGE_USAGE_GRAPH_ENABLED 1
#endif
#ifndef UASM_GRAPH_NET_DOWN_ENABLED
#define UASM_GRAPH_NET_DOWN_ENABLED 0
#endif
#ifndef UASM_GRAPH_NET_UP_ENABLED
#define UASM_GRAPH_NET_UP_ENABLED 0
#endif
#ifndef UASM_PAGE_QBITTORRENT_ENABLED
#define UASM_PAGE_QBITTORRENT_ENABLED 0
#endif
#ifndef UASM_STORAGE_DEBUG
#define UASM_STORAGE_DEBUG 0
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

// Shared DIYables 3.5" shield calibration used on the working UNO R4 build.
// The Mega sketch uses the same shield pin layout through the DIYables shield library.
#define LEFT_X 851
#define RIGHT_X 543
#define TOP_Y 921
#define BOT_Y 196

DIYables_TFT_RM68140_Shield tft;

const int SCREEN_W = 480;
const int SCREEN_H = 320;
const uint8_t TOTAL_PAGES = 8;
const uint8_t GRAPH_POINTS = 32;
const uint8_t CPU_THREADS = 16;
const uint8_t PROCESS_ROWS = 6;
const uint8_t STORAGE_LINES = 7;
const uint8_t FIELD_COUNT = 73;

uint8_t cpuHistory[GRAPH_POINTS];
uint8_t ramHistory[GRAPH_POINTS];
uint8_t gpuHistory[GRAPH_POINTS];
uint8_t vramHistory[GRAPH_POINTS];
uint8_t downHistory[GRAPH_POINTS];
uint8_t upHistory[GRAPH_POINTS];

const bool PAGE_ENABLED[TOTAL_PAGES] = {
  UASM_PAGE_HOME_ENABLED != 0,
  UASM_PAGE_CPU_ENABLED != 0,
  UASM_PAGE_PROCESSES_ENABLED != 0,
  UASM_PAGE_NETWORK_ENABLED != 0,
  UASM_PAGE_GPU_ENABLED != 0,
  UASM_PAGE_STORAGE_ENABLED != 0,
  UASM_PAGE_USAGE_GRAPH_ENABLED != 0,
  UASM_PAGE_QBITTORRENT_ENABLED != 0
};

uint8_t currentPage = 0;
bool touchHeld = false;
bool dataDirty = false;
unsigned long lastTouchTime = 0;
unsigned long lastTouchPoll = 0;
unsigned long lastScreenUpdate = 0;
const unsigned long touchDebounceMs = 350;
const unsigned long touchPollMs = 45;
const unsigned long minRedrawIntervalMs = 120;

uint8_t cpuTotal = 0;
uint8_t cpuThreads[CPU_THREADS];
uint8_t ramPct = 0, disk0Pct = 0, disk1Pct = 0, gpuPct = 0, gpuMemPct = 0;
uint16_t gpuMemUsed = 0, gpuMemTotal = 0, gpuClock = 0;

char cpuTemp[12] = "--";
char ramUsageText[20] = "--";
char osName[28] = "--";
char hostName[26] = "--";
char ipAddr[20] = "--";
char uptimeStr[20] = "--";
char downStr[20] = "--";
char upStr[20] = "--";
char downTotalStr[20] = "--";
char upTotalStr[20] = "--";
char cpuFreqStr[18] = "--";
char gpuTemp[12] = "--";
char gpuName[34] = "--";
char batteryPctStr[8] = "--";
char batteryStateStr[20] = "--";
char batteryModeStr[12] = "--";
char batteryLabel[3][16];
char batteryState[3][18];

char procName[PROCESS_ROWS][20];
char procCpu[PROCESS_ROWS][8];
char procRam[PROCESS_ROWS][10];
char storageLine[STORAGE_LINES][32];
char qbtStatus[38] = "qBittorrent unavailable";
char qbtActiveDownloads[8] = "--";
char qbtActiveSeeding[8] = "--";
char qbtDownSpeed[20] = "--";
char qbtUpSpeed[20] = "--";
char qbtTopTorrent[62] = "--";
char qbtTopState[12] = "--";
char qbtProgress[12] = "--";
char qbtEta[20] = "--";

char fieldBuf[40];
uint8_t fieldPos = 0;
uint8_t fieldIndex = 0;
bool lineOverflow = false;
bool commandLine = false;

static void safeCopy(char* dst, size_t dstSize, const char* src) {
  if (!dst || dstSize == 0) return;
  if (!src) src = "";
  strncpy(dst, src, dstSize - 1);
  dst[dstSize - 1] = '\0';
}

static void trimInPlace(char* s) {
  if (!s) return;
  while (*s == ' ' || *s == '\t') memmove(s, s + 1, strlen(s));
  int len = strlen(s);
  while (len > 0 && (s[len - 1] == ' ' || s[len - 1] == '\t' || s[len - 1] == '\r' || s[len - 1] == '\n')) {
    s[--len] = '\0';
  }
}

static uint8_t parsePercentField(const char* src) {
  char buf[10];
  safeCopy(buf, sizeof(buf), src);
  trimInPlace(buf);
  char* pct = strchr(buf, '%');
  if (pct) *pct = '\0';
  long v = atol(buf);
  if (v < 0) v = 0;
  if (v > 100) v = 100;
  return (uint8_t)v;
}

static uint16_t colorForPct(uint8_t pct) {
  if (pct < 50) return GREEN;
  if (pct < 80) return YELLOW;
  return RED;
}

static uint8_t networkRateToPct(const char* speedText) {
  if (!speedText || speedText[0] == '\0' || strcmp(speedText, "--") == 0) return 0;
  float value = atof(speedText);
  String lower = String(speedText);
  lower.toLowerCase();
  float mbps = value;
  if (lower.indexOf("kb/s") >= 0) mbps = value / 1024.0f;
  else if (lower.indexOf("gb/s") >= 0) mbps = value * 1024.0f;
  int pct = (int)(mbps * 10.0f);
  if (pct < 0) pct = 0;
  if (pct > 100) pct = 100;
  return (uint8_t)pct;
}

static void pushHistory(uint8_t cpuVal, uint8_t ramVal, uint8_t gpuVal, uint8_t vramVal, uint8_t downVal, uint8_t upVal) {
  for (uint8_t i = 0; i < GRAPH_POINTS - 1; i++) {
    cpuHistory[i] = cpuHistory[i + 1];
    ramHistory[i] = ramHistory[i + 1];
    gpuHistory[i] = gpuHistory[i + 1];
    vramHistory[i] = vramHistory[i + 1];
    downHistory[i] = downHistory[i + 1];
    upHistory[i] = upHistory[i + 1];
  }
  cpuHistory[GRAPH_POINTS - 1] = cpuVal;
  ramHistory[GRAPH_POINTS - 1] = ramVal;
  gpuHistory[GRAPH_POINTS - 1] = gpuVal;
  vramHistory[GRAPH_POINTS - 1] = vramVal;
  downHistory[GRAPH_POINTS - 1] = downVal;
  upHistory[GRAPH_POINTS - 1] = upVal;
}

static void clearArea(int x, int y, int w, int h) {
  tft.fillRect(x, y, w, h, BLACK);
}


static uint8_t enabledPageCount() {
  uint8_t count = 0;
  for (uint8_t i = 0; i < TOTAL_PAGES; i++) if (PAGE_ENABLED[i]) count++;
  return count > 0 ? count : TOTAL_PAGES;
}

static uint8_t firstEnabledPage() {
  for (uint8_t i = 0; i < TOTAL_PAGES; i++) if (PAGE_ENABLED[i]) return i;
  return 0;
}

static uint8_t nextEnabledPage(uint8_t page) {
  for (uint8_t step = 1; step <= TOTAL_PAGES; step++) {
    uint8_t candidate = (page + step) % TOTAL_PAGES;
    if (PAGE_ENABLED[candidate]) return candidate;
  }
  return firstEnabledPage();
}

static uint8_t pageDisplayNumber(uint8_t physicalPageOneBased) {
  uint8_t physicalIndex = physicalPageOneBased - 1;
  uint8_t display = 0;
  for (uint8_t i = 0; i <= physicalIndex && i < TOTAL_PAGES; i++) if (PAGE_ENABLED[i]) display++;
  return display > 0 ? display : 1;
}

static void drawHeader(const __FlashStringHelper* title, uint8_t pageNum) {
  tft.fillScreen(BLACK);
  tft.setTextColor(CYAN);
  tft.setTextSize(2);
  tft.setCursor(10, 8); tft.print(title);
  tft.setTextColor(WHITE);
  tft.setTextSize(1);
  tft.setCursor(360, 12); tft.print(APP_VERSION);
  tft.drawFastHLine(0, 34, SCREEN_W, WHITE);
  tft.setTextColor(WHITE);
  tft.setTextSize(1);
  tft.setCursor(430, 10); tft.print(pageDisplayNumber(pageNum)); tft.print('/'); tft.print(enabledPageCount());
}

static void drawPctRow(int y, const __FlashStringHelper* label, uint8_t pct, uint16_t color) {
  clearArea(0, y, SCREEN_W, 28);
  tft.setTextSize(2);
  tft.setTextColor(WHITE); tft.setCursor(12, y + 4); tft.print(label);
  tft.drawRect(134, y + 6, 220, 14, WHITE);
  tft.fillRect(135, y + 7, 218, 12, BLACK);
  int fill = (pct * 218) / 100;
  if (fill > 0) tft.fillRect(135, y + 7, fill, 12, color);
  tft.setTextColor(color); tft.setCursor(370, y + 4); tft.print(pct); tft.print('%');
}

static void drawKV(int y, const __FlashStringHelper* label, const char* value, uint16_t color, int valueX) {
  clearArea(0, y, SCREEN_W, 22);
  tft.setTextSize(2);
  tft.setTextColor(WHITE); tft.setCursor(12, y + 2); tft.print(label);
  tft.setTextColor(color); tft.setCursor(valueX, y + 2); tft.print(value);
}

static void drawThreadCell(int x, int y, uint8_t idx, uint8_t pct) {
  clearArea(x, y, 112, 34);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(x, y); tft.print('C'); tft.print(idx);
  tft.setTextColor(colorForPct(pct)); tft.setCursor(x + 76, y); tft.print(pct); tft.print('%');
  tft.drawRect(x, y + 12, 100, 10, WHITE);
  tft.fillRect(x + 1, y + 13, 98, 8, BLACK);
  int fill = (pct * 98) / 100;
  if (fill > 0) tft.fillRect(x + 1, y + 13, fill, 8, colorForPct(pct));
}

static void drawProcRow(int y, uint8_t idx, const char* name, const char* cpu, const char* ram, uint16_t color) {
  clearArea(0, y, SCREEN_W, 22);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(8, y + 7); tft.print(idx + 1); tft.print('.');
  tft.setTextColor(color); tft.setCursor(28, y + 7); tft.print(name);
  tft.setTextColor(YELLOW); tft.setCursor(300, y + 7); tft.print(cpu);
  tft.setTextColor(CYAN); tft.setCursor(370, y + 7); tft.print(ram);
}

static void drawStorageRow(int y, uint8_t idx, const char* text, uint16_t color) {
  clearArea(0, y, SCREEN_W, 22);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(8, y + 7); tft.print(idx + 1); tft.print('.');
  tft.setTextColor(color); tft.setCursor(28, y + 7); tft.print(text);
}

static void drawCurrentLayout() {
  if (currentPage == 0) drawHeader(F("Ray Co. System Monitor"), 1);
  else if (currentPage == 1) drawHeader(F("CPU Threads"), 2);
  else if (currentPage == 2) drawHeader(F("Processes"), 3);
  else if (currentPage == 3) drawHeader(F("Network"), 4);
  else if (currentPage == 4) drawHeader(F("GPU"), 5);
  else if (currentPage == 5) drawHeader(F("Extra Statistics"), 6);
  else if (currentPage == 6) drawHeader(F("Usage Graph"), 7);
  else drawHeader(F("qBittorrent"), 8);
}

static void updateHome() {
  drawPctRow(44, F("CPU"), cpuTotal, colorForPct(cpuTotal));
  drawPctRow(78, F("RAM"), ramPct, CYAN);
  drawPctRow(112, F("Disk0"), disk0Pct, MAGENTA);
  drawPctRow(146, F("Disk1"), disk1Pct, ORANGE);
  drawKV(182, F("Freq"), cpuFreqStr, ORANGE, 96);
  drawKV(208, F("RAM"), ramUsageText, CYAN, 96);
  drawKV(234, F("Host"), hostName, GREEN, 96);
  drawKV(260, F("Up"), uptimeStr, WHITE, 96);
  drawKV(286, F("OS"), osName, CYAN, 96);
}

static void updateCpu() {
  drawPctRow(42, F("Total"), cpuTotal, colorForPct(cpuTotal));
  int startY = 84;
  for (uint8_t i = 0; i < CPU_THREADS; i++) {
    int row = i / 4;
    int col = i % 4;
    drawThreadCell(12 + (col * 116), startY + (row * 44), i, cpuThreads[i]);
  }
  drawKV(268, F("Freq"), cpuFreqStr, ORANGE, 84);
  drawKV(292, F("Temp"), cpuTemp, CYAN, 84);
}

static void updateProcesses() {
  for (uint8_t i = 0; i < PROCESS_ROWS; i++) {
    drawProcRow(48 + (i * 34), i, procName[i], procCpu[i], procRam[i], (i % 2 == 0) ? GREEN : CYAN);
  }
}

static void updateNetwork() {
  drawKV(48, F("Host"), hostName, CYAN, 90);
  drawKV(78, F("OS"), osName, ORANGE, 90);
  drawKV(108, F("PC IP"), ipAddr, WHITE, 90);
  drawKV(138, F("Down"), downStr, GREEN, 90);
  drawKV(168, F("Up"), upStr, YELLOW, 90);
  drawKV(198, F("DnTot"), downTotalStr, CYAN, 90);
  drawKV(228, F("UpTot"), upTotalStr, ORANGE, 90);
  drawKV(258, F("Uptm"), uptimeStr, WHITE, 90);
}

static void updateGpu() {
  char vramBuf[18], clkBuf[12];
  snprintf(vramBuf, sizeof(vramBuf), "%u/%uM", gpuMemUsed, gpuMemTotal);
  snprintf(clkBuf, sizeof(clkBuf), "%uMHz", gpuClock);
  drawPctRow(44, F("GPU"), gpuPct, colorForPct(gpuPct));
  drawPctRow(78, F("VRAM"), gpuMemPct, CYAN);
  drawKV(116, F("Temp"), gpuTemp, ORANGE, 96);
  drawKV(142, F("Clock"), clkBuf, YELLOW, 96);
  drawKV(168, F("Mem"), vramBuf, CYAN, 96);
  drawKV(194, F("Name"), gpuName, GREEN, 96);
}

static void updateStorage() {
  for (uint8_t i = 0; i < STORAGE_LINES; i++) {
    drawStorageRow(48 + (i * 30), i, storageLine[i], (i % 2 == 0) ? CYAN : YELLOW);
  }
  drawKV(262, F("Mode"), batteryModeStr, CYAN, 78);
  drawKV(284, F("Sys"), batteryStateStr, ORANGE, 78);
  drawKV(306, F("Bat1"), batteryState[0], GREEN, 78);
  drawKV(306, F("Bat2"), batteryState[1], YELLOW, 246);
}

static void drawGraphSeries(const uint8_t* values, uint16_t color) {
  const int gx = 18, gy = 62, gw = 358, gh = 148;
  const int graphScaleMax = 120;
  for (uint8_t i = 1; i < GRAPH_POINTS; i++) {
    int x1 = gx + ((i - 1) * (gw - 2)) / (GRAPH_POINTS - 1);
    int x2 = gx + (i * (gw - 2)) / (GRAPH_POINTS - 1);
    int y1 = gy + gh - 1 - ((values[i - 1] * (gh - 2)) / graphScaleMax);
    int y2 = gy + gh - 1 - ((values[i] * (gh - 2)) / graphScaleMax);
    tft.drawLine(x1, y1, x2, y2, color);
  }
}

static void updateGraph() {
  const int gx = 18, gy = 62, gw = 358, gh = 148;
  clearArea(0, 35, SCREEN_W, SCREEN_H - 35);
  tft.drawRect(gx, gy, gw, gh, WHITE);
  tft.drawFastHLine(gx + 1, gy + (gh / 2), gw - 2, GRAY);
  tft.drawFastHLine(gx + 1, gy + (gh * 3 / 4), gw - 2, GRAY);
  drawGraphSeries(cpuHistory, GREEN);
  drawGraphSeries(ramHistory, CYAN);
  drawGraphSeries(gpuHistory, YELLOW);
  drawGraphSeries(vramHistory, MAGENTA);
#if UASM_GRAPH_NET_DOWN_ENABLED
  drawGraphSeries(downHistory, ORANGE);
#endif
#if UASM_GRAPH_NET_UP_ENABLED
  drawGraphSeries(upHistory, BLUE);
#endif
  drawKV(236, F("CPU"), "Green", GREEN, 70);
  drawKV(258, F("RAM"), "Cyan", CYAN, 70);
  drawKV(280, F("GPU"), "Yellow", YELLOW, 70);
  drawKV(302, F("VRAM"), "Magenta", MAGENTA, 70);
#if UASM_GRAPH_NET_DOWN_ENABLED
  drawKV(236, F("Down"), downStr, ORANGE, 220);
#endif
#if UASM_GRAPH_NET_UP_ENABLED
  drawKV(258, F("Up"), upStr, BLUE, 220);
#endif
}

static void updateQbittorrent() {
  drawKV(48, F("State"), qbtStatus, CYAN, 90);
  drawKV(78, F("Downl"), qbtActiveDownloads, GREEN, 90);
  drawKV(108, F("Down"), qbtDownSpeed, GREEN, 90);
  drawKV(138, F("Up"), qbtUpSpeed, YELLOW, 90);
  drawKV(168, F("Mode"), qbtTopState, ORANGE, 90);
  drawKV(198, F("ETA"), qbtEta, MAGENTA, 90);
  drawKV(228, F("Top Torrent"), qbtTopTorrent, WHITE, 90);
  drawKV(258, F("Progress"), qbtProgress, WHITE, 90);
  drawKV(288, F("Host"), hostName, WHITE, 90);
}

static void updateCurrentPage() {
  if (currentPage == 0) updateHome();
  else if (currentPage == 1) updateCpu();
  else if (currentPage == 2) updateProcesses();
  else if (currentPage == 3) updateNetwork();
  else if (currentPage == 4) updateGpu();
  else if (currentPage == 5) updateStorage();
  else if (currentPage == 6) updateGraph();
  else updateQbittorrent();
}

static void handleTouch() {
  if (millis() - lastTouchPoll < touchPollMs) return;
  lastTouchPoll = millis();

  int x, y;
  bool pressed = tft.getTouch(x, y);
  if (pressed && !touchHeld && millis() - lastTouchTime > touchDebounceMs) {
    touchHeld = true;
    lastTouchTime = millis();
    currentPage = nextEnabledPage(currentPage);
    if (!PAGE_ENABLED[currentPage]) currentPage = firstEnabledPage();
    drawCurrentLayout();
    updateCurrentPage();
  }
  if (!pressed) touchHeld = false;
}

static void resetLineParser() {
  fieldPos = 0;
  fieldIndex = 0;
  lineOverflow = false;
  commandLine = false;
  fieldBuf[0] = '\0';
}

static void applyQbtField(uint8_t idx, const char* value) {
  if (idx == 1) safeCopy(qbtStatus, sizeof(qbtStatus), value);
  else if (idx == 2) safeCopy(qbtActiveDownloads, sizeof(qbtActiveDownloads), value);
  else if (idx == 3) safeCopy(qbtActiveSeeding, sizeof(qbtActiveSeeding), value);
  else if (idx == 4) safeCopy(qbtDownSpeed, sizeof(qbtDownSpeed), value);
  else if (idx == 5) safeCopy(qbtUpSpeed, sizeof(qbtUpSpeed), value);
  else if (idx == 6) safeCopy(qbtTopTorrent, sizeof(qbtTopTorrent), value);
  else if (idx == 7) safeCopy(qbtTopState, sizeof(qbtTopState), value);
  else if (idx == 8) safeCopy(qbtProgress, sizeof(qbtProgress), value);
  else if (idx == 9) safeCopy(qbtEta, sizeof(qbtEta), value);
}

static void applyField(uint8_t idx, const char* value) {
  if (idx == 0) cpuTotal = parsePercentField(value);
  else if (idx >= 1 && idx <= 16) cpuThreads[idx - 1] = parsePercentField(value);
  else if (idx == 17) ramPct = parsePercentField(value);
  else if (idx == 18) disk0Pct = parsePercentField(value);
  else if (idx == 19) disk1Pct = parsePercentField(value);
  else if (idx == 20) safeCopy(cpuTemp, sizeof(cpuTemp), value);
  else if (idx == 21) safeCopy(osName, sizeof(osName), value);
  else if (idx == 22) safeCopy(hostName, sizeof(hostName), value);
  else if (idx == 23) safeCopy(ipAddr, sizeof(ipAddr), value);
  else if (idx == 24) safeCopy(uptimeStr, sizeof(uptimeStr), value);
  else if (idx == 25) safeCopy(downStr, sizeof(downStr), value);
  else if (idx == 26) safeCopy(upStr, sizeof(upStr), value);
  else if (idx == 27) safeCopy(downTotalStr, sizeof(downTotalStr), value);
  else if (idx == 28) safeCopy(upTotalStr, sizeof(upTotalStr), value);
  else if (idx == 29) safeCopy(cpuFreqStr, sizeof(cpuFreqStr), value);
  else if (idx == 30) safeCopy(ramUsageText, sizeof(ramUsageText), value);
  else if (idx == 31) gpuPct = parsePercentField(value);
  else if (idx == 32) safeCopy(gpuTemp, sizeof(gpuTemp), value);
  else if (idx == 33) gpuMemUsed = (uint16_t)atoi(value);
  else if (idx == 34) gpuMemTotal = (uint16_t)atoi(value);
  else if (idx == 35) gpuMemPct = parsePercentField(value);
  else if (idx == 36) gpuClock = (uint16_t)atoi(value);
  else if (idx == 37) safeCopy(gpuName, sizeof(gpuName), value);
  else if (idx >= 38 && idx <= 43) safeCopy(procName[idx - 38], sizeof(procName[0]), value);
  else if (idx >= 44 && idx <= 49) safeCopy(procCpu[idx - 44], sizeof(procCpu[0]), value);
  else if (idx >= 50 && idx <= 55) safeCopy(procRam[idx - 50], sizeof(procRam[0]), value);
  else if (idx >= 56 && idx <= 62) safeCopy(storageLine[idx - 56], sizeof(storageLine[0]), value);
  else if (idx == 63) { /* eighth storage line is unused on Mega layout */ }
  else if (idx == 64) safeCopy(batteryPctStr, sizeof(batteryPctStr), value);
  else if (idx == 65) safeCopy(batteryStateStr, sizeof(batteryStateStr), value);
  else if (idx == 66) safeCopy(batteryModeStr, sizeof(batteryModeStr), value);
  else if (idx >= 67 && idx <= 69) safeCopy(batteryLabel[idx - 67], sizeof(batteryLabel[0]), value);
  else if (idx >= 70 && idx <= 72) safeCopy(batteryState[idx - 70], sizeof(batteryState[0]), value);
#if UASM_STORAGE_DEBUG
  if (idx >= 56 && idx <= 62) {
    Serial.print("STORAGE FIELD ");
    Serial.print(idx);
    Serial.print(": ");
    Serial.println(value);
  }
#endif
}

static void finalizeField() {
  if (lineOverflow) return;
  fieldBuf[fieldPos] = '\0';
  if (fieldIndex == 0 && strcmp(fieldBuf, "QBT") == 0) {
    commandLine = true;
    fieldIndex = 1;
    fieldPos = 0;
    return;
  }
  if (fieldIndex < FIELD_COUNT) {
    if (commandLine) applyQbtField(fieldIndex, fieldBuf);
    else applyField(fieldIndex, fieldBuf);
    fieldIndex++;
  } else {
    lineOverflow = true;
  }
  fieldPos = 0;
}

static void feedIncomingChar(char c) {
  if (c == '\r') return;
  if (c == '\n') {
    finalizeField();
    if (commandLine) {
      if (!lineOverflow && fieldIndex >= 8) dataDirty = true;
    } else if (!lineOverflow && fieldIndex == FIELD_COUNT) {
      pushHistory(cpuTotal, ramPct, gpuPct, gpuMemPct, networkRateToPct(downStr), networkRateToPct(upStr));
      dataDirty = true;
    }
    resetLineParser();
    return;
  }
  if (c == '|') {
    finalizeField();
    return;
  }
  if (fieldPos < sizeof(fieldBuf) - 1) fieldBuf[fieldPos++] = c;
  else lineOverflow = true;
}

void setup() {
  Serial.begin(115200);
  tft.begin();
  tft.setRotation(DISPLAY_ROTATION_VALUE);
  tft.setTouchCalibration(LEFT_X, RIGHT_X, TOP_Y, BOT_Y);
  tft.fillScreen(BLACK);

  for (uint8_t i = 0; i < GRAPH_POINTS; i++) cpuHistory[i] = ramHistory[i] = gpuHistory[i] = vramHistory[i] = downHistory[i] = upHistory[i] = 0;
  for (uint8_t i = 0; i < PROCESS_ROWS; i++) {
    safeCopy(procName[i], sizeof(procName[i]), "--");
    safeCopy(procCpu[i], sizeof(procCpu[i]), "--");
    safeCopy(procRam[i], sizeof(procRam[i]), "--");
  }
  for (uint8_t i = 0; i < STORAGE_LINES; i++) safeCopy(storageLine[i], sizeof(storageLine[i]), "Disk: --");
  for (uint8_t i = 0; i < 3; i++) {
    safeCopy(batteryLabel[i], sizeof(batteryLabel[i]), "--");
    safeCopy(batteryState[i], sizeof(batteryState[i]), "--");
  }

  currentPage = firstEnabledPage();
  drawCurrentLayout();
  updateCurrentPage();
}

void loop() {
  while (Serial.available() > 0) feedIncomingChar((char)Serial.read());
  handleTouch();
  if (dataDirty && millis() - lastScreenUpdate >= minRedrawIntervalMs) {
    updateCurrentPage();
    lastScreenUpdate = millis();
    dataDirty = false;
  }
}
