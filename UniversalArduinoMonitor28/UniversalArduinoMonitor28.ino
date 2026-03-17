#include <MCUFRIEND_kbv.h>
#include <Adafruit_GFX.h>
#include <TouchScreen.h>
#include <string.h>
#include <stdlib.h>

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

const int SCREEN_W = 320;
const int SCREEN_H = 240;
const uint8_t TOTAL_PAGES = 5;
const uint8_t GRAPH_POINTS = 16;
const uint8_t CPU_THREADS_IN = 16;
const uint8_t PROCESS_ROWS_IN = 6;
const uint8_t STORAGE_LINES_IN = 7;
const uint8_t FIELD_COUNT = 63;

uint8_t cpuHistory[GRAPH_POINTS];
uint8_t ramHistory[GRAPH_POINTS];
uint8_t gpuHistory[GRAPH_POINTS];
uint8_t vramHistory[GRAPH_POINTS];

char serialLine[768];
uint16_t serialPos = 0;

uint8_t currentPage = 0;
bool touchHeld = false;
bool dataDirty = false;

unsigned long lastTouchTime = 0;
unsigned long lastTouchPoll = 0;
const unsigned long touchDebounceMs = 260;
const unsigned long touchPollMs = 45;
const unsigned long minRedrawIntervalMs = 180;
unsigned long lastScreenUpdate = 0;

uint8_t cpuTotal = 0, cpu0 = 0, cpu1 = 0, cpu2 = 0, cpu3 = 0;
uint8_t ramPct = 0, diskPct = 0, disk1Pct = 0, gpuPct = 0;
uint16_t gpuMemUsed = 0, gpuMemTotal = 0, gpuClock = 0;
uint8_t gpuMemPct = 0;

char cpuTemp[10] = "--";
char osName[18] = "--";
char hostName[32] = "--";
char ipAddr[18] = "--";
char uptimeStr[14] = "--";
char downStr[18] = "--";
char upStr[18] = "--";
char downTotalStr[18] = "--";
char upTotalStr[18] = "--";
char cpuFreqStr[14] = "--";
char gpuTemp[10] = "--";
char gpuName[24] = "--";

char proc1[14] = "--", proc2[14] = "--", proc3[14] = "--", proc4[14] = "--";
char proc1Cpu[8] = "--", proc2Cpu[8] = "--", proc3Cpu[8] = "--", proc4Cpu[8] = "--";
char proc1Ram[8] = "--", proc2Ram[8] = "--", proc3Ram[8] = "--", proc4Ram[8] = "--";

char storage1[28] = "Disk: --";
char storage2[28] = "Disk: --";
char storage3[28] = "Disk: --";
char opticalStr[18] = "--";

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

static void shortOS(char* dst, size_t dstSize, const char* src) {
  safeCopy(dst, dstSize, src);
  trimInPlace(dst);
  if (strstr(dst, "Windows 10")) safeCopy(dst, dstSize, "Win10");
  else if (strstr(dst, "Windows 11")) safeCopy(dst, dstSize, "Win11");
  else if (strstr(dst, "Windows 7")) safeCopy(dst, dstSize, "Win7");
  else if (strstr(dst, "Windows XP")) safeCopy(dst, dstSize, "WinXP");
  else if (strstr(dst, "Linux Mint")) safeCopy(dst, dstSize, "Mint");
  else if (strstr(dst, "Debian")) safeCopy(dst, dstSize, "Debian");
}

static void shortGPU(char* dst, size_t dstSize, const char* src) {
  safeCopy(dst, dstSize, src);
  trimInPlace(dst);
  if (strstr(dst, "NVIDIA GeForce ")) {
    char temp[24]; safeCopy(temp, sizeof(temp), dst + 15); snprintf(dst, dstSize, "NVIDIA %s", temp);
  } else if (strstr(dst, "AMD Radeon ")) {
    char temp[24]; safeCopy(temp, sizeof(temp), dst + 11); snprintf(dst, dstSize, "AMD %s", temp);
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
  tft.fillRect(0, 22, SCREEN_W, SCREEN_H - 36, BLACK);
}

static void drawHeader(const __FlashStringHelper* title, uint8_t pageNum) {
  tft.fillScreen(BLACK);
  tft.setTextColor(CYAN);
  tft.setTextSize(1);
  tft.setCursor(6, 7); tft.print(title);
  tft.drawFastHLine(0, 22, SCREEN_W, WHITE);
  tft.setTextSize(1); tft.setTextColor(WHITE);
  tft.setCursor(6, 229); tft.print(F("Tap "));
  tft.print(pageNum); tft.print('/'); tft.print(TOTAL_PAGES);
}

static void drawKV(int y, const __FlashStringHelper* label, const char* value, uint16_t color) {
  tft.fillRect(64, y, SCREEN_W - 64, 14, BLACK);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(6, y + 3); tft.print(label);
  tft.setTextColor(color); tft.setCursor(68, y + 3); tft.print(value);
}

static void drawPctRow(int y, const __FlashStringHelper* label, uint8_t pct, uint16_t color) {
  char buf[8];
  snprintf(buf, sizeof(buf), "%u%%", pct);
  tft.fillRect(64, y, SCREEN_W - 64, 14, BLACK);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(6, y + 3); tft.print(label);
  tft.setTextColor(color); tft.setCursor(68, y + 3); tft.print(buf);
  tft.drawRect(106, y + 2, 110, 10, WHITE);
  int fill = (pct * 108) / 100;
  tft.fillRect(107, y + 3, 108, 8, BLACK);
  if (fill > 0) tft.fillRect(107, y + 3, fill, 8, color);
}

static void drawBigPctRow(int y, const __FlashStringHelper* label, uint8_t pct, uint16_t color) {
  char buf[8];
  snprintf(buf, sizeof(buf), "%u%%", pct);

  tft.fillRect(0, y, SCREEN_W, 24, BLACK);

  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(6, y + 7);
  tft.print(label);

  tft.setTextSize(2);
  tft.setTextColor(color);
  tft.setCursor(68, y + 4);
  tft.print(buf);

  tft.drawRect(126, y + 6, 188, 12, WHITE);
  int fill = (pct * 186) / 100;
  tft.fillRect(127, y + 7, 186, 10, BLACK);
  if (fill > 0) tft.fillRect(127, y + 7, fill, 10, color);
}

static void drawBigKV(int y, const __FlashStringHelper* label, const char* value, uint16_t color) {
  tft.fillRect(0, y, SCREEN_W, 16, BLACK);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(6, y + 4); tft.print(label);
  tft.setTextColor(color); tft.setCursor(44, y + 4); tft.print(value);
}

static void drawProcRow(int y, const char* idx, const char* name, const char* cpu, const char* ram, uint16_t color) {
  tft.fillRect(16, y, SCREEN_W - 16, 14, BLACK);
  tft.setTextSize(1);
  tft.setTextColor(WHITE); tft.setCursor(4, y + 3); tft.print(idx);
  tft.setTextColor(color); tft.setCursor(18, y + 3); tft.print(name);
  tft.setTextColor(YELLOW); tft.setCursor(166, y + 3); tft.print(cpu);
  tft.setTextColor(CYAN); tft.setCursor(230, y + 3); tft.print(ram);
}

static void drawCurrentLayout() {
  if (currentPage == 0) drawHeader(F("Ray Co. Universal System Monitor"), 1);
  else if (currentPage == 1) drawHeader(F("CPU + Processes"), 2);
  else if (currentPage == 2) drawHeader(F("GPU + Network"), 3);
  else if (currentPage == 3) drawHeader(F("Storage + Totals"), 4);
  else drawHeader(F("Usage Graph"), 5);
}

static void updateHome() {
  char osShort[10]; shortOS(osShort, sizeof(osShort), osName);
  drawBigPctRow(28,  F("CPU"),   cpuTotal, GREEN);
  drawBigPctRow(54,  F("RAM"),   ramPct,   YELLOW);
  drawBigPctRow(80,  F("Disk1"), diskPct,  CYAN);
  drawBigPctRow(106, F("Disk2"), disk1Pct, MAGENTA);
  drawBigKV(136, F("Temp"), cpuTemp, CYAN);
  drawBigKV(150, F("Freq"), cpuFreqStr, ORANGE);
  drawBigKV(164, F("OS"), osShort, ORANGE);
  drawBigKV(178, F("Host"), hostName, WHITE);
  drawBigKV(192, F("IP"), ipAddr, WHITE);
  drawBigKV(206, F("Up"), uptimeStr, WHITE);
}

static void updateCpuProc() {
  drawPctRow(30, F("C0"), cpu0, GREEN);
  drawPctRow(46, F("C1"), cpu1, GREEN);
  drawPctRow(62, F("C2"), cpu2, GREEN);
  drawPctRow(78, F("C3"), cpu3, GREEN);
  drawProcRow(104, "1", proc1, proc1Cpu, proc1Ram, GREEN);
  drawProcRow(118, "2", proc2, proc2Cpu, proc2Ram, YELLOW);
  drawProcRow(132, "3", proc3, proc3Cpu, proc3Ram, CYAN);
  drawProcRow(146, "4", proc4, proc4Cpu, proc4Ram, MAGENTA);
  char d0[8], d1[8];
  snprintf(d0, sizeof(d0), "%u%%", diskPct);
  snprintf(d1, sizeof(d1), "%u%%", disk1Pct);
  drawKV(170, F("Disk0"), d0, ORANGE);
  drawKV(184, F("Disk1"), d1, ORANGE);
}

static void updateGpuNet() {
  char gpuShort[24]; shortGPU(gpuShort, sizeof(gpuShort), gpuName);
  char vramBuf[18], clockBuf[12];
  snprintf(vramBuf, sizeof(vramBuf), "%u/%uM", gpuMemUsed, gpuMemTotal);
  snprintf(clockBuf, sizeof(clockBuf), "%uMHz", gpuClock);
  drawPctRow(30, F("GPU"), gpuPct, CYAN);
  drawPctRow(46, F("VRAM"), gpuMemPct, MAGENTA);
  drawKV(68, F("GTemp"), gpuTemp, CYAN);
  drawKV(82, F("VRAM"), vramBuf, YELLOW);
  drawKV(96, F("Clock"), clockBuf, ORANGE);
  drawKV(110, F("Name"), gpuShort, WHITE);
  drawKV(132, F("Down"), downStr, GREEN);
  drawKV(146, F("Up"), upStr, YELLOW);
  drawKV(160, F("Host"), hostName, WHITE);
  drawKV(174, F("IP"), ipAddr, WHITE);
  drawKV(188, F("UpT"), upTotalStr, ORANGE);
}

static void updateStorage() {
  drawKV(30, F("S1"), storage1, WHITE);
  drawKV(44, F("S2"), storage2, WHITE);
  drawKV(58, F("S3"), storage3, WHITE);
  drawKV(76, F("Opt"), opticalStr, CYAN);
  drawKV(96, F("DnTot"), downTotalStr, GREEN);
  drawKV(110, F("UpTot"), upTotalStr, YELLOW);
  drawKV(128, F("OS"), osName, ORANGE);
  drawKV(142, F("Host"), hostName, WHITE);
  drawKV(156, F("IP"), ipAddr, WHITE);
  drawKV(170, F("Up"), uptimeStr, WHITE);
}

static void updateGraph() {
  const int gx = 10, gy = 30, gw = 280, gh = 120;
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

  tft.setTextSize(1);
  tft.setTextColor(GREEN);   tft.setCursor(12, 162);  tft.print(F("CPU ")); tft.print(cpuTotal); tft.print('%');
  tft.setTextColor(CYAN);    tft.setCursor(84, 162);  tft.print(F("RAM ")); tft.print(ramPct); tft.print('%');
  tft.setTextColor(YELLOW);  tft.setCursor(156, 162); tft.print(F("GPU ")); tft.print(gpuPct); tft.print('%');
  tft.setTextColor(MAGENTA); tft.setCursor(228, 162); tft.print(F("VRM ")); tft.print(gpuMemPct); tft.print('%');
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

static bool readTouchPressed() {
  if (millis() - lastTouchPoll < touchPollMs) return touchHeld;
  lastTouchPoll = millis();

  TSPoint p = ts.getPoint();
  pinMode(XM, OUTPUT);
  pinMode(YP, OUTPUT);
  digitalWrite(XM, HIGH);
  digitalWrite(YP, HIGH);

  if (p.z < MINPRESSURE || p.z > MAXPRESSURE) return false;
  if (p.x < TS_MINX - 80 || p.x > TS_MAXX + 80) return false;
  if (p.y < TS_MINY - 80 || p.y > TS_MAXY + 80) return false;
  return true;
}

static void handleTouch() {
  bool pressed = readTouchPressed();
  if (pressed && !touchHeld && millis() - lastTouchTime > touchDebounceMs) {
    touchHeld = true;
    lastTouchTime = millis();
    currentPage = (currentPage + 1) % TOTAL_PAGES;
    drawCurrentLayout();
    updateCurrentPage();
  }
  if (!pressed) touchHeld = false;
}

static void parseIncomingLine(char* s) {
  char* fields[FIELD_COUNT];
  uint8_t count = 0;
  char* tok = strtok(s, "|");
  while (tok && count < FIELD_COUNT) {
    fields[count++] = tok;
    tok = strtok(NULL, "|");
  }
  if (count != FIELD_COUNT) return;

  cpuTotal = parsePercentField(fields[0]);
  cpu0     = parsePercentField(fields[1]);
  cpu1     = parsePercentField(fields[2]);
  cpu2     = parsePercentField(fields[3]);
  cpu3     = parsePercentField(fields[4]);
  ramPct   = parsePercentField(fields[17]);
  diskPct  = parsePercentField(fields[18]);
  disk1Pct = parsePercentField(fields[19]);

  safeCopy(cpuTemp, sizeof(cpuTemp), fields[20]);
  safeCopy(osName, sizeof(osName), fields[21]);
  safeCopy(hostName, sizeof(hostName), fields[22]);
  safeCopy(ipAddr, sizeof(ipAddr), fields[23]);
  safeCopy(uptimeStr, sizeof(uptimeStr), fields[24]);
  safeCopy(downStr, sizeof(downStr), fields[25]);
  safeCopy(upStr, sizeof(upStr), fields[26]);
  safeCopy(downTotalStr, sizeof(downTotalStr), fields[27]);
  safeCopy(upTotalStr, sizeof(upTotalStr), fields[28]);
  safeCopy(cpuFreqStr, sizeof(cpuFreqStr), fields[29]);

  gpuPct      = parsePercentField(fields[30]);
  safeCopy(gpuTemp, sizeof(gpuTemp), fields[31]);
  gpuMemUsed  = (uint16_t)atoi(fields[32]);
  gpuMemTotal = (uint16_t)atoi(fields[33]);
  gpuMemPct   = parsePercentField(fields[34]);
  gpuClock    = (uint16_t)atoi(fields[35]);
  safeCopy(gpuName, sizeof(gpuName), fields[36]);

  safeCopy(proc1, sizeof(proc1), fields[37]);
  safeCopy(proc2, sizeof(proc2), fields[38]);
  safeCopy(proc3, sizeof(proc3), fields[39]);
  safeCopy(proc4, sizeof(proc4), fields[40]);

  safeCopy(proc1Cpu, sizeof(proc1Cpu), fields[43]);
  safeCopy(proc2Cpu, sizeof(proc2Cpu), fields[44]);
  safeCopy(proc3Cpu, sizeof(proc3Cpu), fields[45]);
  safeCopy(proc4Cpu, sizeof(proc4Cpu), fields[46]);

  safeCopy(proc1Ram, sizeof(proc1Ram), fields[49]);
  safeCopy(proc2Ram, sizeof(proc2Ram), fields[50]);
  safeCopy(proc3Ram, sizeof(proc3Ram), fields[51]);
  safeCopy(proc4Ram, sizeof(proc4Ram), fields[52]);

  safeCopy(storage1, sizeof(storage1), fields[55]);
  safeCopy(storage2, sizeof(storage2), fields[56]);
  safeCopy(storage3, sizeof(storage3), fields[57]);
  safeCopy(opticalStr, sizeof(opticalStr), fields[62]);

  pushHistory(cpuTotal, ramPct, gpuPct, gpuMemPct);
  dataDirty = true;
}


void setup() {
  Serial.begin(115200);

  uint16_t id = tft.readID();
  if (id == 0xD3D3 || id == 0xFFFF || id == 0x0000) id = 0x9341;

  tft.begin(id);
  tft.setRotation(1);
  tft.fillScreen(BLACK);

  for (uint8_t i = 0; i < GRAPH_POINTS; i++) {
    cpuHistory[i] = 0;
    ramHistory[i] = 0;
    gpuHistory[i] = 0;
    vramHistory[i] = 0;
  }

  drawCurrentLayout();
  updateCurrentPage();
}

void loop() {
  while (Serial.available()) {
    char c = (char)Serial.read();

    if (c == '\n') {
      serialLine[serialPos] = '\0';
      if (serialPos > 0) parseIncomingLine(serialLine);
      serialPos = 0;
      serialLine[0] = '\0';
    } else if (c != '\r') {
      if (serialPos < sizeof(serialLine) - 1) {
        serialLine[serialPos++] = c;
      } else {
        // overflow guard: drop this busted line cleanly
        serialPos = 0;
        serialLine[0] = '\0';
      }
    }
  }

  if (dataDirty) {
    updateCurrentPage();
    if (millis() - lastScreenUpdate >= minRedrawIntervalMs) {
      dataDirty = false;
    }
  }

  handleTouch();
}
