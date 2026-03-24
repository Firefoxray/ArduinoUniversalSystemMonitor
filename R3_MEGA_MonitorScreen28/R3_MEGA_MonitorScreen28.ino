#include <MCUFRIEND_kbv.h>
#if __has_include("display_config.local.h")
#include "display_config.local.h"
#else
#include "display_config.h"
#endif
#if __has_include("page_config.local.h")
#include "page_config.local.h"
#endif
#include <Adafruit_GFX.h>
#include <TouchScreen.h>
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
#ifndef UASM_PAGE_GPU_ENABLED
#define UASM_PAGE_GPU_ENABLED 1
#endif
#ifndef UASM_PAGE_NETWORK_ENABLED
#define UASM_PAGE_NETWORK_ENABLED 1
#endif
#ifndef UASM_PAGE_STORAGE_ENABLED
#define UASM_PAGE_STORAGE_ENABLED 1
#endif
#ifndef UASM_PAGE_POWER_ENABLED
#define UASM_PAGE_POWER_ENABLED 1
#endif
#ifndef UASM_PAGE_USAGE_GRAPH_ENABLED
#define UASM_PAGE_USAGE_GRAPH_ENABLED 1
#endif

MCUFRIEND_kbv tft;

#define BLACK   0x0000
#define WHITE   0xFFFF
#define RED     0xF800
#define GREEN   0x07E0
#define BLUE    0x051F
#define CYAN    0x07FF
#define YELLOW  0xFFE0
#define ORANGE  0xFD20
#define MAGENTA 0xF81F
#define GRAY    0x8410

#define YP A3
#define XM A2
#define YM 9
#define XP 8
TouchScreen ts = TouchScreen(XP, YP, XM, YM, 300);

#define TS_MINX 120
#define TS_MAXX 900
#define TS_MINY 70
#define TS_MAXY 920
#define MINPRESSURE 120
#define MAXPRESSURE 1000

const uint16_t SCREEN_W = 320;
const uint16_t SCREEN_H = 240;
const uint8_t TOTAL_PAGES = 8;
const uint8_t GRAPH_POINTS = 24;
const uint8_t CPU_THREADS = 16;
const uint8_t PROCESS_ROWS = 6;
const uint8_t STORAGE_LINES = 4;
const uint8_t FIELD_COUNT = 73;

uint8_t cpuHistory[GRAPH_POINTS];
uint8_t ramHistory[GRAPH_POINTS];
uint8_t gpuHistory[GRAPH_POINTS];
uint8_t vramHistory[GRAPH_POINTS];

const bool PAGE_ENABLED[TOTAL_PAGES] = {
  UASM_PAGE_HOME_ENABLED != 0,
  UASM_PAGE_CPU_ENABLED != 0,
  UASM_PAGE_PROCESSES_ENABLED != 0,
  UASM_PAGE_GPU_ENABLED != 0,
  UASM_PAGE_NETWORK_ENABLED != 0,
  UASM_PAGE_STORAGE_ENABLED != 0,
  UASM_PAGE_POWER_ENABLED != 0,
  UASM_PAGE_USAGE_GRAPH_ENABLED != 0
};

uint8_t currentPage = 0;
bool touchHeld = false;
bool dataDirty = false;
unsigned long lastTouchTime = 0;
unsigned long lastTouchPoll = 0;
unsigned long lastScreenUpdate = 0;
const unsigned long touchDebounceMs = 260;
const unsigned long touchPollMs = 45;
const unsigned long minRedrawIntervalMs = 140;

uint8_t cpuTotal = 0;
uint8_t cpuThreads[CPU_THREADS];
uint8_t ramPct = 0;
uint8_t disk0Pct = 0;
uint8_t disk1Pct = 0;
uint8_t gpuPct = 0;
uint8_t gpuMemPct = 0;
uint16_t gpuMemUsed = 0;
uint16_t gpuMemTotal = 0;
uint16_t gpuClock = 0;

char cpuTemp[12] = "--";
char ramUsageText[20] = "--";
char osName[18] = "--";
char hostName[24] = "--";
char ipAddr[20] = "--";
char uptimeStr[16] = "--";
char downStr[14] = "--";
char upStr[14] = "--";
char downTotalStr[14] = "--";
char upTotalStr[14] = "--";
char cpuFreqStr[18] = "--";
char gpuTemp[12] = "--";
char gpuName[26] = "--";
char batteryPctStr[10] = "--";
char batteryStateStr[16] = "DESKTOP";
char batteryModeStr[12] = "DESKTOP";
char batteryLabel[3][16];
char batteryState[3][14];

char procName[PROCESS_ROWS][18];
char procCpu[PROCESS_ROWS][8];
char procRam[PROCESS_ROWS][10];
char storageLine[STORAGE_LINES][28];

char fieldBuf[40];
uint8_t fieldPos = 0;
uint8_t fieldIndex = 0;
bool lineOverflow = false;

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
  long value = atol(buf);
  if (value < 0) value = 0;
  if (value > 100) value = 100;
  return (uint8_t)value;
}

static uint16_t colorForPct(uint8_t pct) {
  if (pct < 50) return GREEN;
  if (pct < 80) return YELLOW;
  return RED;
}

static void shortOS(char* dst, size_t dstSize, const char* src) {
  safeCopy(dst, dstSize, src);
  trimInPlace(dst);
  if (strstr(dst, "Windows 11")) safeCopy(dst, dstSize, "Win11");
  else if (strstr(dst, "Windows 10")) safeCopy(dst, dstSize, "Win10");
  else if (strstr(dst, "Linux Mint")) safeCopy(dst, dstSize, "Mint");
  else if (strstr(dst, "Ubuntu")) safeCopy(dst, dstSize, "Ubuntu");
  else if (strstr(dst, "Debian")) safeCopy(dst, dstSize, "Debian");
  else if (strstr(dst, "Fedora")) safeCopy(dst, dstSize, "Fedora");
}

static void shortGPU(char* dst, size_t dstSize, const char* src) {
  safeCopy(dst, dstSize, src);
  trimInPlace(dst);
  if (strstr(dst, "NVIDIA GeForce ")) {
    char temp[20];
    safeCopy(temp, sizeof(temp), dst + 15);
    snprintf(dst, dstSize, "NV %s", temp);
  } else if (strstr(dst, "AMD Radeon ")) {
    char temp[20];
    safeCopy(temp, sizeof(temp), dst + 11);
    snprintf(dst, dstSize, "AMD %s", temp);
  }
}

static void pushHistory(uint8_t cpuVal, uint8_t ramVal, uint8_t gpuVal, uint8_t vramVal) {
  for (uint8_t i = 0; i < GRAPH_POINTS - 1; i++) {
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
  tft.setTextSize(1);
  tft.setTextColor(CYAN);
  tft.setCursor(6, 7);
  tft.print(title);
  tft.setTextColor(WHITE);
  tft.setCursor(212, 7);
  tft.print(APP_VERSION);
  tft.setCursor(286, 7);
  tft.print(pageDisplayNumber(pageNum));
  tft.print('/');
  tft.print(enabledPageCount());
  tft.drawFastHLine(0, 20, SCREEN_W, WHITE);
}


static void drawKV(int y, const __FlashStringHelper* label, const char* value, uint16_t color, int valueX) {
  clearArea(0, y, SCREEN_W, 14);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(6, y + 3);
  tft.print(label);
  tft.setTextColor(color);
  tft.setCursor(valueX, y + 3);
  tft.print(value);
}

static void drawPctRow(int y, const __FlashStringHelper* label, uint8_t pct, uint16_t color) {
  char buf[6];
  snprintf(buf, sizeof(buf), "%u%%", pct);
  clearArea(0, y, SCREEN_W, 24);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(6, y + 8);
  tft.print(label);
  tft.setTextSize(2);
  tft.setTextColor(color);
  tft.setCursor(54, y + 4);
  tft.print(buf);
  tft.drawRect(114, y + 8, 194, 10, WHITE);
  tft.fillRect(115, y + 9, 192, 8, BLACK);
  int fill = (pct * 192) / 100;
  if (fill > 0) tft.fillRect(115, y + 9, fill, 8, color);
}

static void drawThreadCell(int x, int y, uint8_t idx, uint8_t pct) {
  clearArea(x, y, 76, 20);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(x, y);
  tft.print('C');
  tft.print(idx);
  tft.setTextColor(colorForPct(pct));
  tft.setCursor(x + 48, y);
  tft.print(pct);
  tft.print('%');
  tft.drawRect(x, y + 9, 68, 8, WHITE);
  tft.fillRect(x + 1, y + 10, 66, 6, BLACK);
  int fill = (pct * 66) / 100;
  if (fill > 0) tft.fillRect(x + 1, y + 10, fill, 6, colorForPct(pct));
}

static void drawProcRow(int y, uint8_t idx, const char* name, const char* cpu, const char* ram, uint16_t color) {
  clearArea(0, y, SCREEN_W, 14);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(4, y + 3);
  tft.print(idx + 1);
  tft.print('.');
  tft.setTextColor(color);
  tft.setCursor(18, y + 3);
  tft.print(name);
  tft.setTextColor(YELLOW);
  tft.setCursor(212, y + 3);
  tft.print(cpu);
  tft.setTextColor(CYAN);
  tft.setCursor(268, y + 3);
  tft.print(ram);
}

static void drawStorageRow(int y, const char* text, uint16_t color) {
  clearArea(0, y, SCREEN_W, 14);
  tft.setTextSize(1);
  tft.setTextColor(color);
  tft.setCursor(8, y + 3);
  tft.print(text);
}

static void drawGraphSeries(const uint8_t* values, uint16_t color) {
  const int gx = 12;
  const int gy = 34;
  const int gw = 296;
  const int gh = 146;
  for (uint8_t i = 1; i < GRAPH_POINTS; i++) {
    int x1 = gx + ((i - 1) * (gw - 2)) / (GRAPH_POINTS - 1);
    int x2 = gx + (i * (gw - 2)) / (GRAPH_POINTS - 1);
    int y1 = gy + gh - 1 - ((values[i - 1] * (gh - 2)) / 100);
    int y2 = gy + gh - 1 - ((values[i] * (gh - 2)) / 100);
    tft.drawLine(x1, y1, x2, y2, color);
  }
}

static void drawCurrentLayout() {
  if (currentPage == 0) drawHeader(F("Ray Co. System Monitor"), 1);
  else if (currentPage == 1) drawHeader(F("CPU Threads"), 2);
  else if (currentPage == 2) drawHeader(F("Processes"), 3);
  else if (currentPage == 3) drawHeader(F("GPU"), 4);
  else if (currentPage == 4) drawHeader(F("Network"), 5);
  else if (currentPage == 5) drawHeader(F("Storage"), 6);
  else if (currentPage == 6) drawHeader(F("Power"), 7);
  else drawHeader(F("Usage Graph"), 8);
}

static void updateHome() {
  char osShort[10];
  shortOS(osShort, sizeof(osShort), osName);
  drawPctRow(28, F("CPU"), cpuTotal, colorForPct(cpuTotal));
  drawPctRow(54, F("RAM"), ramPct, CYAN);
  drawPctRow(80, F("Disk0"), disk0Pct, MAGENTA);
  drawPctRow(106, F("Disk1"), disk1Pct, ORANGE);
  drawKV(136, F("Freq"), cpuFreqStr, ORANGE, 48);
  drawKV(150, F("Temp"), cpuTemp, CYAN, 48);
  drawKV(164, F("RAM"), ramUsageText, CYAN, 48);
  drawKV(178, F("OS"), osShort, WHITE, 48);
  drawKV(192, F("Host"), hostName, GREEN, 48);
  drawKV(206, F("Up"), uptimeStr, WHITE, 48);
}

static void updateCpu() {
  drawPctRow(26, F("Total"), cpuTotal, colorForPct(cpuTotal));
  for (uint8_t i = 0; i < CPU_THREADS; i++) {
    uint8_t row = i / 4;
    uint8_t col = i % 4;
    drawThreadCell(8 + (col * 78), 58 + (row * 34), i, cpuThreads[i]);
  }
  drawKV(194, F("Freq"), cpuFreqStr, ORANGE, 48);
  drawKV(206, F("Temp"), cpuTemp, CYAN, 48);
}

static void updateProcesses() {
  drawKV(28, F("Host"), hostName, WHITE, 48);
  drawKV(42, F("CPU"), cpuFreqStr, ORANGE, 48);
  for (uint8_t i = 0; i < PROCESS_ROWS; i++) {
    drawProcRow(64 + (i * 20), i, procName[i], procCpu[i], procRam[i], (i % 2 == 0) ? GREEN : CYAN);
  }
  drawKV(188, F("CPU%"), "yellow", YELLOW, 48);
  drawKV(202, F("RAM%"), "cyan", CYAN, 48);
}

static void updateGpu() {
  char gpuShort[22];
  char vramBuf[16];
  char clockBuf[12];
  shortGPU(gpuShort, sizeof(gpuShort), gpuName);
  snprintf(vramBuf, sizeof(vramBuf), "%u/%uM", gpuMemUsed, gpuMemTotal);
  snprintf(clockBuf, sizeof(clockBuf), "%uMHz", gpuClock);
  drawPctRow(28, F("GPU"), gpuPct, colorForPct(gpuPct));
  drawPctRow(58, F("VRAM"), gpuMemPct, MAGENTA);
  drawKV(94, F("Temp"), gpuTemp, CYAN, 64);
  drawKV(112, F("Clock"), clockBuf, YELLOW, 64);
  drawKV(130, F("Mem"), vramBuf, CYAN, 64);
  drawKV(148, F("Name"), gpuShort, WHITE, 64);
}

static void updateNetwork() {
  drawKV(34, F("Host"), hostName, CYAN, 64);
  drawKV(56, F("IP"), ipAddr, WHITE, 64);
  drawKV(84, F("Down"), downStr, GREEN, 64);
  drawKV(106, F("Up"), upStr, YELLOW, 64);
  drawKV(128, F("DnTot"), downTotalStr, ORANGE, 64);
  drawKV(150, F("UpTot"), upTotalStr, MAGENTA, 64);
  drawKV(178, F("Up"), uptimeStr, WHITE, 64);
}

static void updateStorage() {
  for (uint8_t i = 0; i < STORAGE_LINES; i++) {
    drawStorageRow(34 + (i * 24), storageLine[i], (i % 2 == 0) ? CYAN : YELLOW);
  }
  drawKV(144, F("RAM"), ramUsageText, CYAN, 64);
  char d0[6], d1[6];
  snprintf(d0, sizeof(d0), "%u%%", disk0Pct);
  snprintf(d1, sizeof(d1), "%u%%", disk1Pct);
  drawKV(170, F("Disk0"), d0, ORANGE, 64);
  drawKV(192, F("Disk1"), d1, MAGENTA, 64);
}

static void updatePower() {
  drawKV(34, F("Mode"), batteryModeStr, WHITE, 64);
  drawKV(58, F("System"), batteryPctStr, GREEN, 64);
  drawKV(82, F("State"), batteryStateStr, ORANGE, 64);
  drawKV(114, F("Bat1"), batteryLabel[0], CYAN, 64);
  drawKV(136, F("St1"), batteryState[0], WHITE, 64);
  drawKV(160, F("Bat2"), batteryLabel[1], CYAN, 64);
  drawKV(182, F("St2"), batteryState[1], WHITE, 64);
  drawKV(204, F("Host"), hostName, GREEN, 64);
}

static void updateGraph() {
  const int gx = 12;
  const int gy = 34;
  const int gw = 296;
  const int gh = 146;
  clearArea(0, 21, SCREEN_W, SCREEN_H - 21);
  tft.drawRect(gx, gy, gw, gh, WHITE);
  for (uint8_t i = 1; i < 4; i++) {
    tft.drawFastHLine(gx, gy + (gh * i) / 4, gw, GRAY);
  }
  drawGraphSeries(cpuHistory, GREEN);
  drawGraphSeries(ramHistory, CYAN);
  drawGraphSeries(gpuHistory, YELLOW);
  drawGraphSeries(vramHistory, MAGENTA);

  tft.setTextSize(1);
  tft.setTextColor(GREEN);   tft.setCursor(12, 186);  tft.print(F("CPU "));  tft.print(cpuTotal);   tft.print('%');
  tft.setTextColor(CYAN);    tft.setCursor(84, 186);  tft.print(F("RAM "));  tft.print(ramPct);     tft.print('%');
  tft.setTextColor(YELLOW);  tft.setCursor(156, 186); tft.print(F("GPU "));  tft.print(gpuPct);     tft.print('%');
  tft.setTextColor(MAGENTA); tft.setCursor(228, 186); tft.print(F("VRM "));  tft.print(gpuMemPct);  tft.print('%');
  drawKV(204, F("Host"), hostName, WHITE, 52);
  drawKV(218, F("Up"), uptimeStr, WHITE, 52);
}

static void updateCurrentPage() {
  if (currentPage == 0) updateHome();
  else if (currentPage == 1) updateCpu();
  else if (currentPage == 2) updateProcesses();
  else if (currentPage == 3) updateGpu();
  else if (currentPage == 4) updateNetwork();
  else if (currentPage == 5) updateStorage();
  else if (currentPage == 6) updatePower();
  else updateGraph();
}

static bool readTouchPoint(int& screenX, int& screenY) {
  if (millis() - lastTouchPoll < touchPollMs) return false;
  lastTouchPoll = millis();

  TSPoint p = ts.getPoint();
  pinMode(XM, OUTPUT);
  pinMode(YP, OUTPUT);
  digitalWrite(XM, HIGH);
  digitalWrite(YP, HIGH);

  if (p.z < MINPRESSURE || p.z > MAXPRESSURE) return false;
  if (p.x < TS_MINX - 80 || p.x > TS_MAXX + 80) return false;
  if (p.y < TS_MINY - 80 || p.y > TS_MAXY + 80) return false;

  screenX = map(p.y, TS_MINY, TS_MAXY, 0, SCREEN_W);
  screenY = map(p.x, TS_MAXX, TS_MINX, 0, SCREEN_H);
  if (DISPLAY_ROTATION_VALUE == 3) {
    screenX = SCREEN_W - screenX;
    screenY = SCREEN_H - screenY;
  }
  screenX = constrain(screenX, 0, SCREEN_W - 1);
  screenY = constrain(screenY, 0, SCREEN_H - 1);
  return true;
}

static void goToPage(uint8_t page) {
  currentPage = page % TOTAL_PAGES;
  if (!PAGE_ENABLED[currentPage]) currentPage = nextEnabledPage(currentPage);
  if (!PAGE_ENABLED[currentPage]) currentPage = firstEnabledPage();
  drawCurrentLayout();
  updateCurrentPage();
}

static void handleTouch() {
  int x = 0;
  int y = 0;
  bool pressed = readTouchPoint(x, y);

  if (pressed && !touchHeld && millis() - lastTouchTime > touchDebounceMs) {
    touchHeld = true;
    lastTouchTime = millis();
    goToPage(nextEnabledPage(currentPage));
  }

  if (!pressed) touchHeld = false;
}

static void resetLineParser() {
  fieldPos = 0;
  fieldIndex = 0;
  lineOverflow = false;
  fieldBuf[0] = '\0';
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
  else if (idx >= 56 && idx <= 59) safeCopy(storageLine[idx - 56], sizeof(storageLine[0]), value);
  else if (idx >= 60 && idx <= 63) { /* not rendered on compact 2.8 layout */ }
  else if (idx == 64) safeCopy(batteryPctStr, sizeof(batteryPctStr), value);
  else if (idx == 65) safeCopy(batteryStateStr, sizeof(batteryStateStr), value);
  else if (idx == 66) safeCopy(batteryModeStr, sizeof(batteryModeStr), value);
  else if (idx >= 67 && idx <= 69) safeCopy(batteryLabel[idx - 67], sizeof(batteryLabel[0]), value);
  else if (idx >= 70 && idx <= 72) safeCopy(batteryState[idx - 70], sizeof(batteryState[0]), value);
}

static void finalizeField() {
  if (lineOverflow) return;
  fieldBuf[fieldPos] = '\0';
  if (fieldIndex < FIELD_COUNT) {
    applyField(fieldIndex, fieldBuf);
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
    if (!lineOverflow && fieldIndex == FIELD_COUNT) {
      pushHistory(cpuTotal, ramPct, gpuPct, gpuMemPct);
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

  uint16_t id = tft.readID();
  if (id == 0xD3D3 || id == 0xFFFF || id == 0x0000) id = 0x9341;
  tft.begin(id);
  tft.setRotation(DISPLAY_ROTATION_VALUE);
  tft.fillScreen(BLACK);

  for (uint8_t i = 0; i < GRAPH_POINTS; i++) cpuHistory[i] = ramHistory[i] = gpuHistory[i] = vramHistory[i] = 0;
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

  resetLineParser();
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
