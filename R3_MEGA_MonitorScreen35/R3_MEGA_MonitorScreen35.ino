#include <DIYables_TFT_Touch_Shield.h>
#if __has_include("display_config.local.h")
#include "display_config.local.h"
#else
#include "display_config.h"
#endif
#include <string.h>
#include <stdlib.h>

#ifndef DISPLAY_ROTATION_VALUE
#define DISPLAY_ROTATION_VALUE 1
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
const uint8_t TOTAL_PAGES = 7;
const uint8_t GRAPH_POINTS = 32;
const uint8_t CPU_THREADS = 16;
const uint8_t PROCESS_ROWS = 6;
const uint8_t STORAGE_LINES = 7;
const uint8_t FIELD_COUNT = 64;

uint8_t cpuHistory[GRAPH_POINTS];
uint8_t ramHistory[GRAPH_POINTS];
uint8_t gpuHistory[GRAPH_POINTS];
uint8_t vramHistory[GRAPH_POINTS];

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
char opticalStr[20] = "--";

char procName[PROCESS_ROWS][20];
char procCpu[PROCESS_ROWS][8];
char procRam[PROCESS_ROWS][10];
char storageLine[STORAGE_LINES][32];

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

static void drawHeader(const __FlashStringHelper* title, uint8_t pageNum) {
  tft.fillScreen(BLACK);
  tft.setTextColor(CYAN);
  tft.setTextSize(2);
  tft.setCursor(10, 8); tft.print(title);
  tft.drawFastHLine(0, 34, SCREEN_W, WHITE);
  tft.setTextColor(WHITE);
  tft.setTextSize(1);
  tft.setCursor(430, 10); tft.print(pageNum); tft.print('/'); tft.print(TOTAL_PAGES);
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
  else if (currentPage == 5) drawHeader(F("Storage Inventory"), 6);
  else drawHeader(F("Usage Graph"), 7);
}

static void updateHome() {
  drawPctRow(44, F("CPU"), cpuTotal, colorForPct(cpuTotal));
  drawPctRow(78, F("RAM"), ramPct, CYAN);
  drawPctRow(112, F("Disk0"), disk0Pct, MAGENTA);
  drawPctRow(146, F("Disk1"), disk1Pct, ORANGE);
  drawKV(182, F("Freq"), cpuFreqStr, ORANGE, 96);
  drawKV(208, F("RAM"), ramUsageText, CYAN, 96);
  drawKV(234, F("Up"), uptimeStr, WHITE, 96);
  drawKV(260, F("OS"), osName, CYAN, 96);
  drawKV(286, F("Host"), hostName, GREEN, 96);
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
  drawKV(48, F("IP"), ipAddr, CYAN, 90);
  drawKV(78, F("Down"), downStr, GREEN, 90);
  drawKV(108, F("Up"), upStr, YELLOW, 90);
  drawKV(138, F("DnTot"), downTotalStr, ORANGE, 90);
  drawKV(168, F("UpTot"), upTotalStr, MAGENTA, 90);
  drawKV(198, F("UpTime"), uptimeStr, WHITE, 90);
  drawKV(228, F("Host"), hostName, CYAN, 90);
  drawKV(258, F("OS"), osName, CYAN, 90);
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
  drawKV(268, F("Opt"), opticalStr, ORANGE, 76);
}

static void drawGraphSeries(const uint8_t* values, uint16_t color) {
  const int gx = 16, gy = 56, gw = 448, gh = 190;
  for (uint8_t i = 1; i < GRAPH_POINTS; i++) {
    int x1 = gx + ((i - 1) * (gw - 2)) / (GRAPH_POINTS - 1);
    int x2 = gx + (i * (gw - 2)) / (GRAPH_POINTS - 1);
    int y1 = gy + gh - 1 - ((values[i - 1] * (gh - 2)) / 100);
    int y2 = gy + gh - 1 - ((values[i] * (gh - 2)) / 100);
    tft.drawLine(x1, y1, x2, y2, color);
  }
}

static void updateGraph() {
  const int gx = 16, gy = 56, gw = 448, gh = 190;
  clearArea(0, 35, SCREEN_W, SCREEN_H - 35);
  tft.drawRect(gx, gy, gw, gh, WHITE);
  drawGraphSeries(cpuHistory, GREEN);
  drawGraphSeries(ramHistory, CYAN);
  drawGraphSeries(gpuHistory, YELLOW);
  drawGraphSeries(vramHistory, MAGENTA);
  drawKV(258, F("CPU"), "Green", GREEN, 76);
  drawKV(280, F("RAM"), "Cyan", CYAN, 76);
  drawKV(258, F("GPU"), "Yellow", YELLOW, 236);
  drawKV(280, F("VRAM"), "Magenta", MAGENTA, 236);
}

static void updateCurrentPage() {
  if (currentPage == 0) updateHome();
  else if (currentPage == 1) updateCpu();
  else if (currentPage == 2) updateProcesses();
  else if (currentPage == 3) updateNetwork();
  else if (currentPage == 4) updateGpu();
  else if (currentPage == 5) updateStorage();
  else updateGraph();
}

static void handleTouch() {
  if (millis() - lastTouchPoll < touchPollMs) return;
  lastTouchPoll = millis();

  int x, y;
  bool pressed = tft.getTouch(x, y);
  if (pressed && !touchHeld && millis() - lastTouchTime > touchDebounceMs) {
    touchHeld = true;
    lastTouchTime = millis();
    currentPage = (currentPage + 1) % TOTAL_PAGES;
    drawCurrentLayout();
    updateCurrentPage();
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
  else if (idx == 30) gpuPct = parsePercentField(value);
  else if (idx == 31) safeCopy(gpuTemp, sizeof(gpuTemp), value);
  else if (idx == 32) gpuMemUsed = (uint16_t)atoi(value);
  else if (idx == 33) gpuMemTotal = (uint16_t)atoi(value);
  else if (idx == 34) gpuMemPct = parsePercentField(value);
  else if (idx == 35) gpuClock = (uint16_t)atoi(value);
  else if (idx == 36) safeCopy(gpuName, sizeof(gpuName), value);
  else if (idx >= 37 && idx <= 42) safeCopy(procName[idx - 37], sizeof(procName[0]), value);
  else if (idx >= 43 && idx <= 48) safeCopy(procCpu[idx - 43], sizeof(procCpu[0]), value);
  else if (idx >= 49 && idx <= 54) safeCopy(procRam[idx - 49], sizeof(procRam[0]), value);
  else if (idx >= 55 && idx <= 61) safeCopy(storageLine[idx - 55], sizeof(storageLine[0]), value);
  else if (idx == 62) safeCopy(opticalStr, sizeof(opticalStr), value);
  else if (idx == 63) safeCopy(ramUsageText, sizeof(ramUsageText), value);
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
  tft.begin();
  tft.setRotation(DISPLAY_ROTATION_VALUE);
  tft.setTouchCalibration(LEFT_X, RIGHT_X, TOP_Y, BOT_Y);
  tft.fillScreen(BLACK);

  for (uint8_t i = 0; i < GRAPH_POINTS; i++) cpuHistory[i] = ramHistory[i] = gpuHistory[i] = vramHistory[i] = 0;
  for (uint8_t i = 0; i < PROCESS_ROWS; i++) {
    safeCopy(procName[i], sizeof(procName[i]), "--");
    safeCopy(procCpu[i], sizeof(procCpu[i]), "--");
    safeCopy(procRam[i], sizeof(procRam[i]), "--");
  }
  for (uint8_t i = 0; i < STORAGE_LINES; i++) safeCopy(storageLine[i], sizeof(storageLine[i]), "Disk: --");

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
