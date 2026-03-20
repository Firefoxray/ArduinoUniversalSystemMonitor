#include <DIYables_TFT_Touch_Shield.h>
#include <string.h>
#include <stdlib.h>

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

#define TOUCH_MIN_Z 80
#define TOUCH_MAX_Z 1000
#define TOUCH_RAW_MIN 80
#define TOUCH_RAW_MAX 980

DIYables_TFT_RM68140_Shield tft;

const int SCREEN_W = 480;
const int SCREEN_H = 320;
const uint8_t TOTAL_PAGES = 5;
const uint8_t GRAPH_POINTS = 20;
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
const unsigned long touchDebounceMs = 320;
const unsigned long touchPollMs = 45;
const unsigned long minRedrawIntervalMs = 160;

uint8_t cpuTotal = 0, cpu0 = 0, cpu1 = 0, cpu2 = 0, cpu3 = 0;
uint8_t ramPct = 0, diskPct = 0, disk1Pct = 0, gpuPct = 0, gpuMemPct = 0;
uint16_t gpuMemUsed = 0, gpuMemTotal = 0, gpuClock = 0;

char cpuTemp[8] = "--";
char osName[14] = "--";
char hostName[18] = "--";
char ipAddr[16] = "--";
char uptimeStr[12] = "--";
char downStr[12] = "--";
char upStr[12] = "--";
char downTotalStr[12] = "--";
char upTotalStr[12] = "--";
char cpuFreqStr[12] = "--";
char gpuTemp[8] = "--";
char gpuName[22] = "--";

char proc1[14] = "--", proc2[14] = "--", proc3[14] = "--", proc4[14] = "--";
char proc1Cpu[7] = "--", proc2Cpu[7] = "--", proc3Cpu[7] = "--", proc4Cpu[7] = "--";
char proc1Ram[8] = "--", proc2Ram[8] = "--", proc3Ram[8] = "--", proc4Ram[8] = "--";

char storage1[24] = "Disk: --";
char storage2[24] = "Disk: --";
char storage3[24] = "Disk: --";
char opticalStr[14] = "--";
char ramUsageText[16] = "--";

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
    s[len - 1] = '\0';
    len--;
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

static void shortOS(char* dst, size_t dstSize, const char* src) {
  safeCopy(dst, dstSize, src);
  trimInPlace(dst);
  if (strstr(dst, "Windows 10")) safeCopy(dst, dstSize, "Win10");
  else if (strstr(dst, "Windows 11")) safeCopy(dst, dstSize, "Win11");
  else if (strstr(dst, "Linux Mint")) safeCopy(dst, dstSize, "Mint");
  else if (strstr(dst, "Ubuntu")) safeCopy(dst, dstSize, "Ubuntu");
  else if (strstr(dst, "Debian")) safeCopy(dst, dstSize, "Debian");
}

static void shortGPU(char* dst, size_t dstSize, const char* src) {
  safeCopy(dst, dstSize, src);
  trimInPlace(dst);
  if (strstr(dst, "NVIDIA GeForce ")) {
    char temp[18];
    safeCopy(temp, sizeof(temp), dst + 15);
    snprintf(dst, dstSize, "NV %s", temp);
  } else if (strstr(dst, "AMD Radeon ")) {
    char temp[18];
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

static void clearBody() {
  tft.fillRect(0, 36, SCREEN_W, SCREEN_H - 40, BLACK);
}

static void drawHeader(const __FlashStringHelper* title, uint8_t pageNum) {
  tft.fillScreen(BLACK);
  tft.setTextColor(CYAN);
  tft.setTextSize(2);
  tft.setCursor(8, 8); tft.print(title);
  tft.drawFastHLine(0, 34, SCREEN_W, WHITE);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(430, 10); tft.print(pageNum); tft.print('/'); tft.print(TOTAL_PAGES);
}

static void drawKV(int y, const __FlashStringHelper* label, const char* value, uint16_t color, int valueX) {
  tft.fillRect(0, y, SCREEN_W, 20, BLACK);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(10, y + 6); tft.print(label);
  tft.setTextColor(color); tft.setCursor(valueX, y + 6); tft.print(value);
}

static void drawPctRow(int y, const __FlashStringHelper* label, uint8_t pct, uint16_t color) {
  char buf[6];
  snprintf(buf, sizeof(buf), "%u%%", pct);
  tft.fillRect(0, y, SCREEN_W, 24, BLACK);
  tft.setTextSize(2);
  tft.setTextColor(WHITE); tft.setCursor(10, y + 4); tft.print(label);
  tft.setTextSize(1);
  tft.setTextColor(color); tft.setCursor(430, y + 8); tft.print(buf);
  tft.drawRect(108, y + 6, 300, 12, WHITE);
  int fill = (pct * 298) / 100;
  tft.fillRect(109, y + 7, 298, 10, BLACK);
  if (fill > 0) tft.fillRect(109, y + 7, fill, 10, color);
}

static void drawBigKV(int y, const __FlashStringHelper* label, const char* value, uint16_t color) {
  tft.fillRect(0, y, SCREEN_W, 18, BLACK);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(10, y + 5); tft.print(label);
  tft.setTextColor(color); tft.setCursor(70, y + 5); tft.print(value);
}

static void drawProcRow(int y, const char* idx, const char* name, const char* cpu, const char* ram, uint16_t color) {
  tft.fillRect(0, y, SCREEN_W, 18, BLACK);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(8, y + 5); tft.print(idx);
  tft.setTextColor(color); tft.setCursor(22, y + 5); tft.print(name);
  tft.setTextColor(YELLOW); tft.setCursor(300, y + 5); tft.print(cpu);
  tft.setTextColor(CYAN); tft.setCursor(390, y + 5); tft.print(ram);
}

static void drawCurrentLayout() {
  if (currentPage == 0) drawHeader(F("Ray Co. System Monitor"), 1);
  else if (currentPage == 1) drawHeader(F("CPU + Processes"), 2);
  else if (currentPage == 2) drawHeader(F("GPU + Network"), 3);
  else if (currentPage == 3) drawHeader(F("Storage + Totals"), 4);
  else drawHeader(F("Usage Graph"), 5);
}

static void updateHome() {
  char osShort[10];
  shortOS(osShort, sizeof(osShort), osName);
  drawPctRow(44,  F("CPU"),   cpuTotal, colorForPct(cpuTotal));
  drawPctRow(72,  F("RAM"),   ramPct,   CYAN);
  drawPctRow(100, F("Disk1"), diskPct,  MAGENTA);
  drawPctRow(128, F("Disk2"), disk1Pct, ORANGE);
  drawBigKV(164, F("Temp"), cpuTemp, CYAN);
  drawBigKV(182, F("Freq"), cpuFreqStr, ORANGE);
  drawBigKV(200, F("RAM"), ramUsageText, CYAN);
  drawBigKV(218, F("OS"), osShort, ORANGE);
  drawBigKV(236, F("Host"), hostName, WHITE);
  drawBigKV(254, F("IP"), ipAddr, WHITE);
  drawBigKV(272, F("Up"), uptimeStr, WHITE);
}

static void updateCpuProc() {
  drawPctRow(44, F("Core0"), cpu0, colorForPct(cpu0));
  drawPctRow(68, F("Core1"), cpu1, colorForPct(cpu1));
  drawPctRow(92, F("Core2"), cpu2, colorForPct(cpu2));
  drawPctRow(116, F("Core3"), cpu3, colorForPct(cpu3));
  drawProcRow(152, "1", proc1, proc1Cpu, proc1Ram, GREEN);
  drawProcRow(170, "2", proc2, proc2Cpu, proc2Ram, YELLOW);
  drawProcRow(188, "3", proc3, proc3Cpu, proc3Ram, CYAN);
  drawProcRow(206, "4", proc4, proc4Cpu, proc4Ram, MAGENTA);
  drawBigKV(236, F("CPU"), cpuFreqStr, ORANGE);
  drawBigKV(254, F("Temp"), cpuTemp, CYAN);
  drawBigKV(272, F("Host"), hostName, WHITE);
}

static void updateGpuNet() {
  char gpuShort[22];
  char vramBuf[14], clockBuf[10];
  shortGPU(gpuShort, sizeof(gpuShort), gpuName);
  snprintf(vramBuf, sizeof(vramBuf), "%u/%uM", gpuMemUsed, gpuMemTotal);
  snprintf(clockBuf, sizeof(clockBuf), "%uMHz", gpuClock);
  drawPctRow(44, F("GPU"), gpuPct, colorForPct(gpuPct));
  drawPctRow(72, F("VRAM"), gpuMemPct, MAGENTA);
  drawBigKV(108, F("GTemp"), gpuTemp, CYAN);
  drawBigKV(126, F("Mem"), vramBuf, YELLOW);
  drawBigKV(144, F("Clock"), clockBuf, ORANGE);
  drawBigKV(162, F("Name"), gpuShort, WHITE);
  drawBigKV(190, F("Down"), downStr, GREEN);
  drawBigKV(208, F("Up"), upStr, YELLOW);
  drawBigKV(226, F("Host"), hostName, WHITE);
  drawBigKV(244, F("IP"), ipAddr, WHITE);
  drawBigKV(262, F("UpTot"), upTotalStr, ORANGE);
 }

static void updateStorage() {
  drawBigKV(48,  F("S1"), storage1, WHITE);
  drawBigKV(68,  F("S2"), storage2, WHITE);
  drawBigKV(88,  F("S3"), storage3, WHITE);
  drawBigKV(116, F("Opt"), opticalStr, CYAN);
  drawBigKV(144, F("DnTot"), downTotalStr, GREEN);
  drawBigKV(162, F("UpTot"), upTotalStr, YELLOW);
  drawBigKV(190, F("OS"), osName, ORANGE);
  drawBigKV(208, F("Host"), hostName, WHITE);
  drawBigKV(226, F("IP"), ipAddr, WHITE);
  drawBigKV(244, F("Up"), uptimeStr, WHITE);
}

static void updateGraph() {
  const int gx = 12, gy = 48, gw = 420, gh = 172;
  clearBody();
  tft.drawRect(gx, gy, gw, gh, WHITE);
  for (uint8_t i = 1; i < 4; i++) {
    tft.drawFastHLine(gx, gy + (gh * i) / 4, gw, GRAY);
    tft.drawFastVLine(gx + (gw * i) / 4, gy, gh, GRAY);
  }
  for (uint8_t i = 1; i < GRAPH_POINTS; i++) {
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
  drawKV(236, F("CPU"), "Green", GREEN, 54);
  drawKV(254, F("RAM"), "Cyan", CYAN, 54);
  drawKV(236, F("GPU"), "Yellow", YELLOW, 214);
  drawKV(254, F("VRAM"), "Magenta", MAGENTA, 214);
  char pctBuf[6];
  snprintf(pctBuf, sizeof(pctBuf), "%u%%", cpuTotal); drawKV(278, F("CPU"), pctBuf, GREEN, 54);
  snprintf(pctBuf, sizeof(pctBuf), "%u%%", ramPct); drawKV(278, F("RAM"), pctBuf, CYAN, 214);
  snprintf(pctBuf, sizeof(pctBuf), "%u%%", gpuPct); drawKV(296, F("GPU"), pctBuf, YELLOW, 54);
  snprintf(pctBuf, sizeof(pctBuf), "%u%%", gpuMemPct); drawKV(296, F("VRM"), pctBuf, MAGENTA, 214);
}

static void updateCurrentPage() {
  if (millis() - lastScreenUpdate < minRedrawIntervalMs) return;
  lastScreenUpdate = millis();
  if (currentPage == 0) updateHome();
  else if (currentPage == 1) updateCpuProc();
  else if (currentPage == 2) updateGpuNet();
  else if (currentPage == 3) updateStorage();
  else updateGraph();
}

static void handleTouch() {
  if (millis() - lastTouchPoll < touchPollMs) return;
  lastTouchPoll = millis();

  int rawX, rawY, rawZ;
  tft.readTouchRaw(rawX, rawY, rawZ);
  bool pressed = false;
  if (rawZ >= TOUCH_MIN_Z && rawZ <= TOUCH_MAX_Z) {
    if (rawX >= TOUCH_RAW_MIN && rawX <= TOUCH_RAW_MAX &&
        rawY >= TOUCH_RAW_MIN && rawY <= TOUCH_RAW_MAX) {
      pressed = true;
    }
  }

  if (pressed && !touchHeld && millis() - lastTouchTime > touchDebounceMs) {
    touchHeld = true;
    lastTouchTime = millis();
    currentPage = (currentPage + 1) % TOTAL_PAGES;
    drawCurrentLayout();
    lastScreenUpdate = 0;
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
  switch (idx) {
    case 0:  cpuTotal = parsePercentField(value); break;
    case 1:  cpu0 = parsePercentField(value); break;
    case 2:  cpu1 = parsePercentField(value); break;
    case 3:  cpu2 = parsePercentField(value); break;
    case 4:  cpu3 = parsePercentField(value); break;
    case 17: ramPct = parsePercentField(value); break;
    case 18: diskPct = parsePercentField(value); break;
    case 19: disk1Pct = parsePercentField(value); break;
    case 20: safeCopy(cpuTemp, sizeof(cpuTemp), value); break;
    case 21: safeCopy(osName, sizeof(osName), value); break;
    case 22: safeCopy(hostName, sizeof(hostName), value); break;
    case 23: safeCopy(ipAddr, sizeof(ipAddr), value); break;
    case 24: safeCopy(uptimeStr, sizeof(uptimeStr), value); break;
    case 25: safeCopy(downStr, sizeof(downStr), value); break;
    case 26: safeCopy(upStr, sizeof(upStr), value); break;
    case 27: safeCopy(downTotalStr, sizeof(downTotalStr), value); break;
    case 28: safeCopy(upTotalStr, sizeof(upTotalStr), value); break;
    case 29: safeCopy(cpuFreqStr, sizeof(cpuFreqStr), value); break;
    case 30: gpuPct = parsePercentField(value); break;
    case 31: safeCopy(gpuTemp, sizeof(gpuTemp), value); break;
    case 32: gpuMemUsed = (uint16_t)atoi(value); break;
    case 33: gpuMemTotal = (uint16_t)atoi(value); break;
    case 34: gpuMemPct = parsePercentField(value); break;
    case 35: gpuClock = (uint16_t)atoi(value); break;
    case 36: safeCopy(gpuName, sizeof(gpuName), value); break;
    case 37: safeCopy(proc1, sizeof(proc1), value); break;
    case 38: safeCopy(proc2, sizeof(proc2), value); break;
    case 39: safeCopy(proc3, sizeof(proc3), value); break;
    case 40: safeCopy(proc4, sizeof(proc4), value); break;
    case 43: safeCopy(proc1Cpu, sizeof(proc1Cpu), value); break;
    case 44: safeCopy(proc2Cpu, sizeof(proc2Cpu), value); break;
    case 45: safeCopy(proc3Cpu, sizeof(proc3Cpu), value); break;
    case 46: safeCopy(proc4Cpu, sizeof(proc4Cpu), value); break;
    case 49: safeCopy(proc1Ram, sizeof(proc1Ram), value); break;
    case 50: safeCopy(proc2Ram, sizeof(proc2Ram), value); break;
    case 51: safeCopy(proc3Ram, sizeof(proc3Ram), value); break;
    case 52: safeCopy(proc4Ram, sizeof(proc4Ram), value); break;
    case 55: safeCopy(storage1, sizeof(storage1), value); break;
    case 56: safeCopy(storage2, sizeof(storage2), value); break;
    case 57: safeCopy(storage3, sizeof(storage3), value); break;
    case 62: safeCopy(opticalStr, sizeof(opticalStr), value); break;
    case 63: safeCopy(ramUsageText, sizeof(ramUsageText), value); break;
    default: break;
  }
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
  tft.setRotation(1);
  tft.setTouchCalibration(LEFT_X, RIGHT_X, TOP_Y, BOT_Y);
  tft.fillScreen(BLACK);

  for (uint8_t i = 0; i < GRAPH_POINTS; i++) {
    cpuHistory[i] = 0;
    ramHistory[i] = 0;
    gpuHistory[i] = 0;
    vramHistory[i] = 0;
  }

  resetLineParser();
  drawCurrentLayout();
  lastScreenUpdate = 0;
  updateCurrentPage();
}

void loop() {
  while (Serial.available()) {
    feedIncomingChar((char)Serial.read());
  }

  if (dataDirty) {
    updateCurrentPage();
    if (millis() - lastScreenUpdate >= minRedrawIntervalMs) {
      dataDirty = false;
    }
  }

  handleTouch();
}
