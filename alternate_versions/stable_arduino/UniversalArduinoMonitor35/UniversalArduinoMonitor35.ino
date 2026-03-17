#include <DIYables_TFT_Touch_Shield.h>

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

const int SCREEN_W = 480;
const int SCREEN_H = 320;

const int BAR_X = 130;
const int BAR_W = 250;
const int BAR_H = 18;

const int GRAPH_POINTS = 62;
int cpuHistory[GRAPH_POINTS];
int ramHistory[GRAPH_POINTS];
int gpuHistory[GRAPH_POINTS];
int vramHistory[GRAPH_POINTS];

String line = "";

int currentPage = 0;
const int TOTAL_PAGES = 7;
bool touchHeld = false;
unsigned long lastTouchTime = 0;
const unsigned long touchDebounceMs = 350;

// New: throttle screen redraws to stop Win7 flicker
unsigned long lastScreenUpdate = 0;
const unsigned long screenUpdateIntervalMs = 70;
bool screenDirty = false;

int cpuTotal = 0;
int cpu0 = 0;
int cpu1 = 0;
int cpu2 = 0;
int cpu3 = 0;

int gpuMemUsed = 0;
int gpuMemTotal = 0;
int gpuMemPct = 0;
int gpuClock = 0;

int ramPct = 0;
int diskPct = 0;
int disk1Pct = 0;
int gpuPct = 0;

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

String proc1 = "--";
String proc2 = "--";
String proc3 = "--";
String proc4 = "--";
String proc5 = "--";
String proc6 = "--";

String proc1Cpu = "--";
String proc2Cpu = "--";
String proc3Cpu = "--";
String proc4Cpu = "--";
String proc5Cpu = "--";
String proc6Cpu = "--";

String proc1Ram = "--";
String proc2Ram = "--";
String proc3Ram = "--";
String proc4Ram = "--";
String proc5Ram = "--";
String proc6Ram = "--";

String disk0Str = "Disk0:N/A";
String disk1Str = "Disk1:N/A";
String disk2Str = "Disk2:N/A";
String disk3Str = "Disk3:N/A";
String dvdStateStr = "N/A";

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

void drawBar(int x, int y, int w, int h, int percent, uint16_t color) {
  if (percent < 0) percent = 0;
  if (percent > 100) percent = 100;

  tft.drawRect(x, y, w, h, WHITE);
  int fill = (percent * (w - 2)) / 100;
  tft.fillRect(x + 1, y + 1, w - 2, h - 2, BLACK);
  if (fill > 0) tft.fillRect(x + 1, y + 1, fill, h - 2, color);
}

void drawLabelText(int y, const char* label, String value, uint16_t color) {
  clearArea(0, y, SCREEN_W, 28);
  tft.setTextSize(2);
  tft.setTextColor(WHITE);
  tft.setCursor(15, y);
  tft.print(label);
  tft.setTextColor(color);
  tft.setCursor(140, y);
  tft.print(value);
}

void drawSmallText(int y, const char* label, String value, uint16_t color) {
  clearArea(0, y, SCREEN_W, 14);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(15, y);
  tft.print(label);
  tft.setTextColor(color);
  tft.setCursor(90, y);
  tft.print(value);
}

void drawDiskLine(int y, String value, uint16_t color) {
  clearArea(0, y, SCREEN_W, 16);
  tft.setTextSize(1);
  tft.setTextColor(color);
  tft.setCursor(15, y);
  tft.print(value);
}

void drawLabelTextCustom(int y, const char* label, String value, uint16_t color, int valueX) {
  clearArea(0, y, SCREEN_W, 28);
  tft.setTextSize(2);
  tft.setTextColor(WHITE);
  tft.setCursor(15, y);
  tft.print(label);
  tft.setTextColor(color);
  tft.setCursor(valueX, y);
  tft.print(value);
}

String fitText(String s, int maxLen) {
  s.trim();
  if (s.length() <= maxLen) return s;
  return s.substring(0, maxLen);
}

String prettyOS(String s) {
  s.trim();
  s.replace("Enterprise", "Ent.");
  s.replace("Professional", "Pro");
  s.replace("Education", "Edu");
  return s;
}

String prettyGPU(String s) {
  s.trim();
  s.replace("(TM)", "");
  s.replace("(R)", "");

  // Keep vendor, remove extra branding fluff
  s.replace("NVIDIA GeForce ", "NVIDIA ");
  s.replace("AMD Radeon ", "AMD ");
  s.replace("Intel(R) ", "Intel ");
  s.replace("Intel ", "Intel ");

  // Common model cleanup so names fit better
  s.replace("GTX 750 Ti", "GTX 750Ti");
  s.replace("GTX 1050 Ti", "GTX 1050Ti");
  s.replace("GTX 1650 Ti", "GTX 1650Ti");
  s.replace("RTX 2060 Super", "RTX 2060S");
  s.replace("RTX 2070 Super", "RTX 2070S");
  s.replace("RTX 2080 Super", "RTX 2080S");
  s.replace("RX 5700 XT", "RX 5700XT");
  s.replace("RX 6600 XT", "RX 6600XT");

  while (s.indexOf("  ") != -1) s.replace("  ", " ");
  s.trim();
  return s;
}

void drawInfoLine(int y, const char* label, String value, uint16_t color, int valueX) {
  clearArea(0, y, SCREEN_W, 26);
  tft.setTextSize(2);
  tft.setTextColor(WHITE);
  tft.setCursor(15, y);
  tft.print(label);

  tft.setTextColor(color);
  tft.setCursor(valueX, y);
  tft.print(value);
}

void drawProcessRow(int y, const char* label, String name, String cpu, String ram, uint16_t color) {
  clearArea(0, y, SCREEN_W, 18);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(8, y + 4);
  tft.print(label);
  tft.setTextColor(color);
  tft.setCursor(28, y + 4);
  tft.print(name);
  tft.setTextColor(YELLOW);
  tft.setCursor(250, y + 4);
  tft.print(cpu);
  tft.setTextColor(CYAN);
  tft.setCursor(360, y + 4);
  tft.print(ram);
}

void drawLabelBar(int y, const char* label, int pct, uint16_t color) {
  clearArea(0, y, SCREEN_W, 32);
  tft.setTextSize(2);
  tft.setTextColor(WHITE);
  tft.setCursor(15, y);
  tft.print(label);
  drawBar(BAR_X, y + 2, BAR_W, BAR_H, pct, color);
  tft.setCursor(BAR_X + BAR_W + 12, y);
  tft.setTextColor(WHITE);
  tft.print(pct);
  tft.print("%");
}

void pushHistory(int cpuVal, int ramVal, int gpuVal, int vramVal) {
  cpuVal = constrain(cpuVal, 0, 100);
  ramVal = constrain(ramVal, 0, 100);
  gpuVal = constrain(gpuVal, 0, 100);
  vramVal = constrain(vramVal, 0, 100);

  for (int i = 0; i < GRAPH_POINTS - 1; i++) {
    cpuHistory[i]  = cpuHistory[i + 1];
    ramHistory[i]  = ramHistory[i + 1];
    gpuHistory[i]  = gpuHistory[i + 1];
    vramHistory[i] = vramHistory[i + 1];
  }

  cpuHistory[GRAPH_POINTS - 1]  = cpuVal;
  ramHistory[GRAPH_POINTS - 1]  = ramVal;
  gpuHistory[GRAPH_POINTS - 1]  = gpuVal;
  vramHistory[GRAPH_POINTS - 1] = vramVal;
}

void drawHeader(const char* title, int page) {
  tft.fillScreen(BLACK);
  tft.setTextSize(2);
  tft.setTextColor(CYAN);
  tft.setCursor(12, 10);
  tft.print(title);
  tft.drawLine(0, 34, SCREEN_W, 34, WHITE);

  if (page == 1 || page == 2 || page == 3 || page == 7) {
    clearArea(0, SCREEN_H - 16, SCREEN_W, 16);
    tft.setTextSize(1);
    tft.setTextColor(WHITE);
    tft.setCursor(12, SCREEN_H - 12);
    tft.print("Tap to switch ");
    tft.print(page);
    tft.print("/");
    tft.print(TOTAL_PAGES);
  } else {
    clearArea(0, SCREEN_H - 28, SCREEN_W, 28);
    tft.setTextSize(2);
    tft.setTextColor(WHITE);
    tft.setCursor(12, SCREEN_H - 24);
    tft.print("Tap anywhere to switch page ");
    tft.print(page);
    tft.print("/");
    tft.print(TOTAL_PAGES);
  }
}

void drawHomeLayout()      { drawHeader("Ray Co. Universal System Monitor", 1); }
void drawCpuLayout()       { drawHeader("CPU Details", 2); }
void drawProcLayout()      { drawHeader("Processes Details", 3); }
void drawNetLayout()       { drawHeader("Network Details", 4); }
void drawGpuLayout()       { drawHeader("GPU Details", 5); }
void drawStorageLayout()   { drawHeader("Storage / Optical Drive Details", 6); }
void drawGraphLayout()     { drawHeader("Usage Graph", 7); }

void updateHome() {
  drawLabelBar(48,  "CPU", cpuTotal, getColor(cpuTotal));
  drawLabelBar(84,  "RAM", ramPct, YELLOW);
  drawLabelBar(120, "D0",  diskPct, MAGENTA);
  drawLabelBar(156, "D1",  disk1Pct, ORANGE);

  drawInfoLine(186, "Freq", fitText(cpuFreqStr, 18), ORANGE, 90);
  drawInfoLine(212, "Up",   fitText(uptimeStr, 18), WHITE, 90);
  drawInfoLine(238, "OS",   fitText(prettyOS(osName), 24), ORANGE, 82);
  drawInfoLine(264, "Host", fitText(hostName, 28), CYAN, 72);
}

void updateCpu() {
  drawLabelBar(45,  "TOT", cpuTotal, getColor(cpuTotal));
  drawLabelBar(75,  "C0", cpu0, getColor(cpu0));
  drawLabelBar(105, "C1", cpu1, getColor(cpu1));
  drawLabelBar(135, "C2", cpu2, getColor(cpu2));
  drawLabelBar(165, "C3", cpu3, getColor(cpu3));
  drawLabelText(198, "Freq", cpuFreqStr, ORANGE);
  drawLabelText(224, "Temp", cpuTemp, CYAN);
  drawSmallText(252, "P1", proc1, WHITE);
  drawSmallText(264, "P2", proc2, WHITE);
  drawSmallText(276, "Up", uptimeStr, WHITE);
}

void updateProc() {
  drawProcessRow(48,  "1", proc1, proc1Cpu, proc1Ram, GREEN);
  drawProcessRow(66,  "2", proc2, proc2Cpu, proc2Ram, YELLOW);
  drawProcessRow(84,  "3", proc3, proc3Cpu, proc3Ram, CYAN);
  drawProcessRow(102, "4", proc4, proc4Cpu, proc4Ram, MAGENTA);
  drawProcessRow(120, "5", proc5, proc5Cpu, proc5Ram, ORANGE);
  drawProcessRow(138, "6", proc6, proc6Cpu, proc6Ram, WHITE);

  drawSmallText(164, "Top 6 CPU processes", "", WHITE);
  drawSmallText(182, "CPU Total", String(cpuTotal) + "%", GREEN);
  drawSmallText(194, "RAM Total", String(ramPct) + "%", CYAN);
  drawSmallText(206, "GPU Total", String(gpuPct) + "%", YELLOW);
  drawSmallText(218, "CPU Freq", cpuFreqStr, ORANGE);
}

void updateNet() {
  drawLabelTextCustom(48,  "Host", fitText(hostName, 28), CYAN, 72);
  drawLabelTextCustom(78,  "OS", fitText(prettyOS(osName), 24), ORANGE, 68);
  drawLabelTextCustom(108, "IP", ipAddr, WHITE, 78);
  drawLabelTextCustom(138, "Down", downStr, GREEN, 95);
  drawLabelTextCustom(168, "Up", upStr, YELLOW, 95);
  drawLabelTextCustom(198, "DnTot", downTotalStr, CYAN, 95);
  drawLabelTextCustom(228, "UpTot", upTotalStr, ORANGE, 95);
  drawLabelTextCustom(258, "Up", uptimeStr, WHITE, 95);
}

void updateGpu() {
  String gpuPretty = prettyGPU(gpuName);

  drawLabelBar(50, "GPU", gpuPct, getColor(gpuPct));

  clearArea(0, 88, SCREEN_W, 28);
  tft.setTextColor(WHITE);
  tft.setTextSize(2);
  tft.setCursor(15, 88);
  tft.print("Name");

  // Draw GPU name with adaptive sizing
  if (gpuPretty.length() <= 20) {
    tft.setTextSize(2);
    tft.setTextColor(WHITE);
    tft.setCursor(120, 88);
    tft.print(gpuPretty);
  } else {
    tft.setTextSize(1);
    tft.setTextColor(WHITE);
    tft.setCursor(120, 94);
    tft.print(fitText(gpuPretty, 34));
  }

  drawLabelText(122, "Temp", gpuTemp, CYAN);
  drawLabelText(156, "VRAM", String(gpuMemUsed) + "/" + String(gpuMemTotal) + "M", YELLOW);
  drawLabelText(190, "VRAM%", String(gpuMemPct) + "%", GREEN);
  drawLabelText(224, "Clk", String(gpuClock) + "MHz", ORANGE);
}

void updateStorage() {
  drawDiskLine(55,  disk0Str, WHITE);
  drawDiskLine(75,  disk1Str, WHITE);
  drawDiskLine(95,  disk2Str, WHITE);
  drawDiskLine(115, disk3Str, WHITE);

  drawLabelTextCustom(150, "OS", fitText(prettyOS(osName), 24), ORANGE, 68);
  drawLabelText(185, "Optical", dvdStateStr, CYAN);
  drawLabelText(220, "DSK0", String(diskPct) + "%", MAGENTA);
  drawLabelText(255, "DSK1", String(disk1Pct) + "%", YELLOW);
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

  clearArea(0, SCREEN_H - 16, SCREEN_W, 16);
  tft.setTextSize(1);
  tft.setTextColor(WHITE);
  tft.setCursor(12, SCREEN_H - 12);
  tft.print("Tap to switch 7/");
  tft.print(TOTAL_PAGES);
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
  const int FIELD_COUNT = 48;
  String f[FIELD_COUNT];
  if (!splitFields(s, f, FIELD_COUNT)) return;

  cpuTotal     = parsePercent(f[0]);
  cpu0         = parsePercent(f[1]);
  cpu1         = parsePercent(f[2]);
  cpu2         = parsePercent(f[3]);
  cpu3         = parsePercent(f[4]);
  ramPct       = parsePercent(f[5]);
  diskPct      = parsePercent(f[6]);
  disk1Pct     = parsePercent(f[7]);

  cpuTemp      = f[8];
  osName       = f[9];
  hostName     = f[10];
  ipAddr       = f[11];
  uptimeStr    = f[12];
  downStr      = f[13];
  upStr        = f[14];
  downTotalStr = f[15];
  upTotalStr   = f[16];
  cpuFreqStr   = f[17];

  gpuPct       = parsePercent(f[18]);
  gpuTemp      = f[19];
  gpuMemUsed   = f[20].toInt();
  gpuMemTotal  = f[21].toInt();
  gpuMemPct    = parsePercent(f[22]);
  gpuClock     = f[23].toInt();
  gpuName      = f[24];

  proc1        = f[25];
  proc2        = f[26];
  proc3        = f[27];
  proc4        = f[28];
  proc5        = f[29];
  proc6        = f[30];

  proc1Cpu     = f[31];
  proc1Ram     = f[32];
  proc2Cpu     = f[33];
  proc2Ram     = f[34];
  proc3Cpu     = f[35];
  proc3Ram     = f[36];
  proc4Cpu     = f[37];
  proc4Ram     = f[38];
  proc5Cpu     = f[39];
  proc5Ram     = f[40];
  proc6Cpu     = f[41];
  proc6Ram     = f[42];

  disk0Str     = f[43];
  disk1Str     = f[44];
  disk2Str     = f[45];
  disk3Str     = f[46];
  dvdStateStr  = f[47];

  gpuPct = constrain(gpuPct, 0, 100);
  gpuMemPct = constrain(gpuMemPct, 0, 100);
  cpuTotal = constrain(cpuTotal, 0, 100);
  ramPct = constrain(ramPct, 0, 100);
  diskPct = constrain(diskPct, 0, 100);
  disk1Pct = constrain(disk1Pct, 0, 100);

  pushHistory(cpuTotal, ramPct, gpuPct, gpuMemPct);
  screenDirty = true;
}

void setup() {
  Serial.begin(115200);
  tft.begin();
  tft.setRotation(3);
  tft.setTouchCalibration(LEFT_X, RIGHT_X, TOP_Y, BOT_Y);

  for (int i = 0; i < GRAPH_POINTS; i++) {
    cpuHistory[i] = 0;
    ramHistory[i] = 0;
    gpuHistory[i] = 0;
    vramHistory[i] = 0;
  }

  drawCurrentLayout();
  updateCurrentPage();
  lastScreenUpdate = millis();
  screenDirty = false;
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      parseIncomingLine(line);
      line = "";
    } else if (c != '\r') {
      line += c;
    }
  }

  handleTouch();

  if (screenDirty && millis() - lastScreenUpdate >= screenUpdateIntervalMs) {
    updateCurrentPage();
    lastScreenUpdate = millis();
    screenDirty = false;
  }
}
