import com.fazecast.jSerialComm.SerialPort;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JavaSerialFakeDisplay extends JFrame {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String APP_VERSION = ProjectVersion.loadVersion(JavaSerialFakeDisplay.class);

    private final JComboBox<PortOption> portCombo = new JComboBox<>();
    private final JTextField manualPortField = new JTextField(22);
    private final JButton refreshButton = new JButton("Refresh Ports");
    private final JButton connectButton = new JButton("Connect");
    private final JTextArea rawLog = new JTextArea();
    private final JTextArea parsedView = new JTextArea();
    private final FakeDisplayPanel fakeDisplayPanel = new FakeDisplayPanel();
    private final JLabel statusLabel = new JLabel("Disconnected");

    private volatile SerialPort currentPort;
    private volatile boolean reading;
    private Thread readerThread;

    public JavaSerialFakeDisplay() {
        super("Arduino Monitor - Fake Display Debugger");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1380, 860);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel topBar = new JPanel(new BorderLayout(10, 10));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Serial Port:"));
        portCombo.setPreferredSize(new Dimension(360, 28));
        controls.add(portCombo);
        controls.add(new JLabel("Or Path:"));
        manualPortField.setToolTipText("Example: /tmp/fakearduino_out or /dev/ttyACM0");
        controls.add(manualPortField);
        controls.add(refreshButton);
        controls.add(connectButton);
        topBar.add(controls, BorderLayout.WEST);
        topBar.add(statusLabel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setResizeWeight(0.35);

        JPanel leftPanel = new JPanel(new GridLayout(2, 1, 10, 10));
        rawLog.setEditable(false);
        rawLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        parsedView.setEditable(false);
        parsedView.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        leftPanel.add(wrap("Raw Serial Log", new JScrollPane(rawLog)));
        leftPanel.add(wrap("Parsed Fields", new JScrollPane(parsedView)));

        mainSplit.setLeftComponent(leftPanel);
        mainSplit.setRightComponent(wrap("Fake Display Preview (click preview to switch pages)", fakeDisplayPanel));
        add(mainSplit, BorderLayout.CENTER);

        refreshButton.addActionListener(e -> refreshPorts());
        connectButton.addActionListener(e -> toggleConnection());

        refreshPorts();
    }

    private JPanel wrap(String title, Component component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private void refreshPorts() {
        portCombo.removeAllItems();

        addPreferredPortIfExists("/tmp/fakearduino_out", "Preferred fake output");
        addPreferredPortIfExists("/tmp/fakearduino_in", "Preferred fake input");

        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            String path = port.getSystemPortPath();
            if (path == null || path.isBlank()) {
                path = "/dev/" + port.getSystemPortName();
            }
            String label = port.getSystemPortName() + "  |  " + port.getDescriptivePortName();
            portCombo.addItem(new PortOption(path, label));
        }

        if (portCombo.getItemCount() == 0) {
            connectButton.setEnabled(false);
            portCombo.addItem(new PortOption("", "<no serial ports found>"));
        } else {
            connectButton.setEnabled(true);
        }
    }

    private void addPreferredPortIfExists(String path, String description) {
        if (new File(path).exists()) {
            portCombo.addItem(new PortOption(path, path + "  |  " + description));
        }
    }

    private void toggleConnection() {
        if (currentPort != null && currentPort.isOpen()) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        String manualPath = manualPortField.getText().trim();
        String portPath;

        if (!manualPath.isEmpty()) {
            portPath = manualPath;
        } else {
            PortOption selected = (PortOption) portCombo.getSelectedItem();
            if (selected == null || selected.path.isBlank()) {
                setStatus("No valid port selected");
                return;
            }
            portPath = selected.path;
        }

        SerialPort port = SerialPort.getCommPort(portPath);
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 0);

        if (!port.openPort()) {
            setStatus("Failed to open " + portPath);
            return;
        }

        currentPort = port;
        reading = true;
        connectButton.setText("Disconnect");
        setStatus("Connected to " + portPath);
        logRaw("[INFO] Connected to " + portPath);

        readerThread = new Thread(this::readLoop, "serial-reader-thread");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void disconnect() {
        reading = false;
        SerialPort port = currentPort;
        currentPort = null;

        if (port != null && port.isOpen()) {
            port.closePort();
        }

        connectButton.setText("Connect");
        setStatus("Disconnected");
        logRaw("[INFO] Disconnected");
    }

    private void readLoop() {
        SerialPort port = currentPort;
        if (port == null) return;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8))) {

            while (reading && port.isOpen()) {
                try {
                    String line = reader.readLine();
                    if (line == null) continue;

                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;

                    logRaw(trimmed);
                    ParsedPacket packet = ParsedPacket.parse(trimmed);
                    updateParsed(packet);
                    fakeDisplayPanel.updatePacket(packet);

                } catch (Exception ex) {
                    String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                    if (msg.contains("timed out")) {
                        continue;
                    }
                    logRaw("[ERROR] " + ex.getMessage());
                    SwingUtilities.invokeLater(() ->
                            setStatus("Read error: " + ex.getClass().getSimpleName()));
                    break;
                }
            }

        } catch (Exception ex) {
            logRaw("[ERROR] Failed to open reader: " + ex.getMessage());
            SwingUtilities.invokeLater(() ->
                    setStatus("Reader error: " + ex.getClass().getSimpleName()));
        }

        SwingUtilities.invokeLater(this::disconnect);
    }

    private void logRaw(String text) {
        SwingUtilities.invokeLater(() -> {
            rawLog.append("[" + LocalTime.now().format(TIME_FMT) + "] " + text + "\n");
            rawLog.setCaretPosition(rawLog.getDocument().getLength());
        });
    }

    private void updateParsed(ParsedPacket packet) {
        SwingUtilities.invokeLater(() -> parsedView.setText(packet.toPrettyString()));
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new JavaSerialFakeDisplay().setVisible(true));
    }

    static class PortOption {
        final String path;
        final String label;

        PortOption(String path, String label) {
            this.path = path;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static class ParsedPacket {
        private static final int CPU_THREADS = 16;
        private static final int PROCESS_ROWS = 6;
        private static final int STORAGE_LINES = 8;
        private static final int EXTRA_BATTERY_SLOTS = 1;
        private static final int FIELD_COUNT = 69;

        private final Map<String, String> values = new LinkedHashMap<>();
        private String raw = "";

        static ParsedPacket parse(String line) {
            ParsedPacket packet = new ParsedPacket();
            packet.raw = line;

            if (!line.contains(":")) {
                if (packet.tryParseLegacyPositional(line)) {
                    return packet;
                }
            }

            String[] parts = line.split("\\|");
            for (String part : parts) {
                String[] kv = part.split(":", 2);
                if (kv.length == 2) {
                    packet.values.put(kv[0].trim().toUpperCase(), kv[1].trim());
                }
            }
            return packet;
        }

        private boolean tryParseLegacyPositional(String line) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != FIELD_COUNT) {
                return false;
            }

            int idx = 0;
            values.put("CPU", fields[idx++].trim());
            for (int i = 0; i < CPU_THREADS; i++) {
                values.put("C" + i, fields[idx++].trim());
            }
            values.put("RAM", fields[idx++].trim());
            values.put("DISK0", fields[idx++].trim());
            values.put("DISK1", fields[idx++].trim());
            values.put("TEMP", fields[idx++].trim());
            values.put("OS", fields[idx++].trim());
            values.put("HOST", fields[idx++].trim());
            values.put("IP", fields[idx++].trim());
            values.put("UPTIME", fields[idx++].trim());
            values.put("DOWN", fields[idx++].trim());
            values.put("UPNET", fields[idx++].trim());
            values.put("DNTOT", fields[idx++].trim());
            values.put("UPTOT", fields[idx++].trim());
            values.put("FREQ", fields[idx++].trim());
            values.put("RAMGB", fields[idx++].trim());
            values.put("GPU", fields[idx++].trim());
            values.put("GPUTEMP", fields[idx++].trim());
            String vramUsed = fields[idx++].trim();
            String vramTotal = fields[idx++].trim();
            values.put("VRAMUSED", vramUsed + "/" + vramTotal + "M");
            values.put("VRAMPCT", fields[idx++].trim() + "%");
            values.put("GPUCLK", fields[idx++].trim() + "MHz");
            values.put("GPUNAME", fields[idx++].trim());

            for (int i = 1; i <= PROCESS_ROWS; i++) {
                values.put("P" + i, fields[idx++].trim());
            }
            for (int i = 1; i <= PROCESS_ROWS; i++) {
                values.put("P" + i + "CPU", fields[idx++].trim());
            }
            for (int i = 1; i <= PROCESS_ROWS; i++) {
                values.put("P" + i + "RAM", fields[idx++].trim());
            }
            for (int i = 1; i <= STORAGE_LINES; i++) {
                values.put("DRV" + i, fields[idx++].trim());
            }
            values.put("BATTPCT", fields[idx++].trim());
            values.put("BATTSTATE", fields[idx++].trim());
            values.put("BATTMODE", fields[idx++].trim());
            for (int i = 1; i <= EXTRA_BATTERY_SLOTS; i++) {
                values.put("BATTDEV" + i, fields[idx++].trim());
            }
            for (int i = 1; i <= EXTRA_BATTERY_SLOTS; i++) {
                values.put("BATTDEV" + i + "STATE", fields[idx++].trim());
            }
            values.put("HEADER", "Ray Co. Universal System Monitor");
            return true;
        }

        String get(String key, String fallback) {
            return values.getOrDefault(key.toUpperCase(), fallback);
        }

        int getInt(String key, int fallback) {
            try {
                String v = get(key, String.valueOf(fallback)).replace("%", "").trim();
                if (v.isEmpty()) return fallback;
                if (v.contains(".")) return (int) Math.round(Double.parseDouble(v));
                return Integer.parseInt(v);
            } catch (Exception ignored) {
                return fallback;
            }
        }

        Map<String, String> snapshotValues() {
            return Map.copyOf(values);
        }

        List<String> getList(String prefix, int count, String fallbackPrefix) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.add(get(prefix + i, fallbackPrefix + i));
            }
            return out;
        }

        String toPrettyString() {
            if (values.isEmpty()) {
                return "Raw packet (unparsed):\n" + raw + "\n\nUse KEY:VALUE|KEY:VALUE packets for the preview.";
            }

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                sb.append(String.format("%-18s = %s%n", entry.getKey(), entry.getValue()));
            }
            return sb.toString();
        }
    }

    static class FakeDisplayPanel extends JPanel {
        enum RenderMode {
            ARDUINO_PREVIEW,
            DESKTOP_OVERVIEW
        }

        private static final int SCREEN_W = 480;
        private static final int SCREEN_H = 320;
        private static final int GRAPH_POINTS = 62;
        private static final Color BG = new Color(8, 12, 26);
        private static final Color CYAN = new Color(60, 240, 255);
        private static final Color WHITE = new Color(235, 240, 255);
        private static final Color LIME = new Color(95, 255, 60);
        private static final Color YELLOW = new Color(255, 220, 40);
        private static final Color MAGENTA = new Color(255, 70, 220);
        private static final Color GRID = new Color(95, 120, 155);
        private static final Color PANEL_LINE = new Color(215, 225, 255);
        private static final Color ORANGE = new Color(255, 170, 60);
        private static final Color BEZEL = new Color(25, 29, 38);
        private static final Color BEZEL_EDGE = new Color(74, 82, 98);
        private static final Color SCREEN_SHADOW = new Color(0, 0, 0, 100);
        private static final String MONO = Font.MONOSPACED;

        private ParsedPacket packet = new ParsedPacket();
        private int pageIndex = 0;
        private RenderMode renderMode = RenderMode.ARDUINO_PREVIEW;
        private final int[] cpuHistory = new int[GRAPH_POINTS];
        private final int[] ramHistory = new int[GRAPH_POINTS];
        private final int[] gpuHistory = new int[GRAPH_POINTS];
        private final int[] vramHistory = new int[GRAPH_POINTS];
        private final int[] netDownHistory = new int[GRAPH_POINTS];
        private final int[] netUpHistory = new int[GRAPH_POINTS];
        private boolean previewWifiEnabled;
        private String previewWifiHostname = "unknown-host";
        private String previewWifiIp = "No IPv4 address";
        private int previewWifiPort = 5000;
        private final String[] pageNames = {
                "Home", "CPU", "Processes", "Network", "GPU", "Storage", "Graph"
        };

        FakeDisplayPanel() {
            setPreferredSize(new Dimension(920, 560));
            setBackground(Color.BLACK);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (renderMode != RenderMode.ARDUINO_PREVIEW) {
                        return;
                    }
                    pageIndex = (pageIndex + 1) % 7;
                    repaint();
                }
            });
        }

        void setRenderMode(RenderMode mode) {
            renderMode = mode == null ? RenderMode.ARDUINO_PREVIEW : mode;
            repaint();
        }

        void updatePacket(ParsedPacket packet) {
            this.packet = packet;
            pushHistory(packet);
            repaint();
        }

        void setPreviewWifiStatus(boolean enabled, String hostname, String ipAddress, int port) {
            previewWifiEnabled = enabled;
            previewWifiHostname = (hostname == null || hostname.isBlank()) ? "unknown-host" : hostname.trim();
            previewWifiIp = (ipAddress == null || ipAddress.isBlank()) ? "No IPv4 address" : ipAddress.trim();
            previewWifiPort = port > 0 ? port : 5000;
            repaint();
        }

        private void pushHistory(ParsedPacket packet) {
            shiftAppendPercent(cpuHistory, packet.getInt("CPU", 0));
            shiftAppendPercent(ramHistory, packet.getInt("RAM", 0));
            shiftAppendPercent(gpuHistory, packet.getInt("GPU", 0));
            shiftAppendPercent(vramHistory, packet.getInt("VRAMPCT", packet.getInt("VRAM_PERCENT", 0)));
            shiftAppendRaw(netDownHistory, parseRateKbps(packet.get("DOWN", packet.get("NETDOWN", "0"))));
            shiftAppendRaw(netUpHistory, parseRateKbps(packet.get("UPNET", packet.get("NETUP", "0"))));
        }

        private void shiftAppendPercent(int[] history, int value) {
            value = Math.max(0, Math.min(100, value));
            System.arraycopy(history, 1, history, 0, history.length - 1);
            history[history.length - 1] = value;
        }

        private void shiftAppendRaw(int[] history, int value) {
            value = Math.max(0, value);
            System.arraycopy(history, 1, history, 0, history.length - 1);
            history[history.length - 1] = value;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            if (renderMode == RenderMode.DESKTOP_OVERVIEW) {
                drawDesktopOverview(g2, w, h);
                g2.dispose();
                return;
            }
            g2.setColor(new Color(15, 20, 32));
            g2.fillRect(0, 0, w, h);

            double scale = Math.min((w - 36.0) / SCREEN_W, (h - 36.0) / SCREEN_H);
            scale = Math.max(scale, 0.1);
            int screenW = (int) Math.round(SCREEN_W * scale);
            int screenH = (int) Math.round(SCREEN_H * scale);
            int screenX = (w - screenW) / 2;
            int screenY = (h - screenH) / 2;

            int bezelPad = Math.max(12, (int) Math.round(18 * scale));
            int bezelRadius = Math.max(18, (int) Math.round(22 * scale));
            int bezelX = screenX - bezelPad;
            int bezelY = screenY - bezelPad;
            int bezelW = screenW + bezelPad * 2;
            int bezelH = screenH + bezelPad * 2;

            g2.setColor(SCREEN_SHADOW);
            g2.fillRoundRect(bezelX + Math.max(4, bezelPad / 3), bezelY + Math.max(6, bezelPad / 2), bezelW, bezelH, bezelRadius, bezelRadius);
            g2.setColor(BEZEL);
            g2.fillRoundRect(bezelX, bezelY, bezelW, bezelH, bezelRadius, bezelRadius);
            g2.setColor(BEZEL_EDGE);
            g2.setStroke(new BasicStroke(Math.max(2f, (float) scale * 2f)));
            g2.drawRoundRect(bezelX, bezelY, bezelW, bezelH, bezelRadius, bezelRadius);

            Shape oldClip = g2.getClip();
            g2.clipRect(screenX, screenY, screenW, screenH);
            g2.translate(screenX, screenY);
            g2.scale(scale, scale);

            int m = 12;
            g2.setColor(BG);
            g2.fillRect(0, 0, SCREEN_W, SCREEN_H);

            switch (pageIndex) {
                case 0 -> drawHome(g2, SCREEN_W, SCREEN_H, m);
                case 1 -> drawCpu(g2, SCREEN_W, SCREEN_H, m);
                case 2 -> drawProcesses(g2, SCREEN_W, SCREEN_H, m);
                case 3 -> drawNetwork(g2, SCREEN_W, SCREEN_H, m);
                case 4 -> drawGpu(g2, SCREEN_W, SCREEN_H, m);
                case 5 -> drawStorage(g2, SCREEN_W, SCREEN_H, m);
                case 6 -> drawGraph(g2, SCREEN_W, SCREEN_H, m);
            }

            g2.setClip(oldClip);

            g2.dispose();
        }

        private void drawDesktopOverview(Graphics2D g2, int w, int h) {
            GradientPaint background = new GradientPaint(0, 0, new Color(11, 19, 36), 0, h, new Color(6, 10, 20));
            g2.setPaint(background);
            g2.fillRect(0, 0, w, h);

            int outerPad = 16;
            int top = outerPad + 8;
            int left = outerPad;
            int contentW = Math.max(320, w - outerPad * 2);
            int contentH = Math.max(280, h - outerPad * 2 - 12);
            int rightColumnW = Math.max(340, (int) (contentW * 0.44));
            int leftColumnW = contentW - rightColumnW - 12;
            int rowGap = 10;

            g2.setColor(CYAN);
            g2.setFont(new Font(MONO, Font.BOLD, 22));
            g2.drawString("Ray Co. Desktop Monitor Dashboard", left, top + 18);
            g2.setFont(new Font(MONO, Font.PLAIN, 13));
            g2.setColor(WHITE);
            g2.drawString("All monitor sections combined (same live packet stream)", left, top + 38);

            int y = top + 50;
            int summaryH = 84;
            drawDesktopSummary(g2, left, y, contentW, summaryH);

            y += summaryH + rowGap;
            int remainingH = contentH - (y - top);
            int leftTopH = Math.max(64, (int) (remainingH * 0.22));
            int leftBottomH = Math.max(120, remainingH - leftTopH - rowGap);
            int rightTopH = Math.max(150, (int) (remainingH * 0.42));
            int rightBottomH = Math.max(120, remainingH - rightTopH - rowGap);

            drawCpuThreadsDesktop(g2, left, y, leftColumnW, leftTopH);
            int processY = y + leftTopH + rowGap;
            int processH = Math.max(90, (int) (leftBottomH * 0.58));
            int gpuH = Math.max(86, leftBottomH - processH - rowGap);
            drawProcessesDesktop(g2, left, processY, leftColumnW, processH);
            drawDesktopGpuCard(g2, left, processY + processH + rowGap, leftColumnW, gpuH);
            int rightX = left + leftColumnW + 12;
            drawNetworkStorageDesktop(g2, rightX, y, rightColumnW, rightTopH);
            drawHistoryDesktop(g2, rightX, y + rightTopH + rowGap, rightColumnW, rightBottomH);
        }

        private void drawDesktopSummary(Graphics2D g2, int x, int y, int w, int h) {
            drawDesktopCard(g2, "System Summary", x, y, w, h);
            int innerX = x + 12;
            int innerY = y + 38;
            int colW = (w - 28) / 3;

            g2.setFont(new Font(MONO, Font.BOLD, 14));
            g2.setColor(WHITE);
            g2.drawString("CPU " + packet.get("CPU", "0") + "%", innerX, innerY);
            g2.drawString("RAM " + packet.get("RAM", "0") + "%", innerX + colW, innerY);
            g2.drawString("GPU " + packet.get("GPU", "0") + "%", innerX + colW * 2, innerY);

            g2.setFont(new Font(MONO, Font.PLAIN, 12));
            g2.setColor(CYAN);
            g2.drawString("Host: " + truncate(resolvePreviewHostname(), 26), innerX, innerY + 24);
            g2.drawString("OS: " + truncate(packet.get("OS", "Linux"), 26), innerX + colW, innerY + 24);
            g2.drawString("Up: " + packet.get("UPTIME", packet.get("UP", "--")), innerX + colW * 2, innerY + 24);

            g2.setColor(previewWifiEnabled ? LIME : ORANGE);
            g2.drawString(previewWifiEnabled ? "Wi-Fi Connected" : "Wi-Fi Disabled", innerX, innerY + 40);
            g2.setColor(WHITE);
            g2.drawString("IP: " + truncate(resolvePreviewIpDisplay(), 24), innerX + colW, innerY + 40);
            g2.drawString("Version: " + APP_VERSION, innerX + colW * 2, innerY + 40);
        }

        private void drawCpuThreadsDesktop(Graphics2D g2, int x, int y, int w, int h) {
            drawDesktopCard(g2, "CPU Threads (Mini)", x, y, w, h);
            int cols = 8;
            int rows = 2;
            int cellW = Math.max(52, (w - 24) / cols);
            int cellH = Math.max(16, (h - 36) / rows);
            g2.setFont(new Font(MONO, Font.BOLD, 9));
            for (int i = 0; i < 16; i++) {
                int col = i % cols;
                int row = i / cols;
                int cellX = x + 10 + col * cellW;
                int cellY = y + 26 + row * cellH;
                int val = packet.getInt("C" + i, packet.getInt("CPU" + i, 0));
                g2.setColor(WHITE);
                g2.drawString("C" + i, cellX, cellY);
                g2.setColor(getHeatColor(val));
                g2.drawString(val + "%", cellX + 18, cellY + 8);
            }
            int total = packet.getInt("CPU", 0);
            int barY = y + h - 12;
            g2.setColor(WHITE);
            g2.drawString("Total", x + 10, barY);
            drawBar(g2, x + 46, barY - 8, Math.max(80, w - 122), 7, total, getHeatColor(total));
            g2.setColor(getHeatColor(total));
            g2.drawString(total + "%", x + w - 52, barY);
        }

        private void drawProcessesDesktop(Graphics2D g2, int x, int y, int w, int h) {
            drawDesktopCard(g2, "Top Processes", x, y, w, h);
            g2.setFont(new Font(MONO, Font.BOLD, 12));
            g2.setColor(WHITE);
            g2.drawString("Name", x + 12, y + 30);
            g2.drawString("CPU", x + w - 128, y + 30);
            g2.drawString("RAM", x + w - 66, y + 30);
            g2.setFont(new Font(MONO, Font.PLAIN, 11));
            int rowY = y + 48;
            int maxRows = Math.max(4, Math.min(6, (h - 122) / 18));
            for (int i = 1; i <= maxRows; i++) {
                String name = truncate(packet.get("P" + i, "--"), 30);
                String cpu = packet.get("P" + i + "CPU", "--");
                String ram = packet.get("P" + i + "RAM", "--");
                int rowTop = rowY - 12;
                g2.setColor(new Color(15, 24, 42, 180));
                g2.fillRoundRect(x + 10, rowTop, w - 20, 16, 8, 8);
                g2.setColor(WHITE);
                g2.drawString(i + ". " + name, x + 12, rowY);
                g2.setColor(YELLOW);
                g2.drawString(cpu, x + w - 128, rowY);
                g2.setColor(CYAN);
                g2.drawString(ram, x + w - 66, rowY);
                rowY += 19;
                if (rowY > y + h - 86) break;
            }

            int statsTop = y + h - 60;
            drawDesktopMiniBar(g2, "CPU", packet.getInt("CPU", 0), x + 12, statsTop, w - 24, LIME);
            drawDesktopMiniBar(g2, "RAM", packet.getInt("RAM", 0), x + 12, statsTop + 16, w - 24, CYAN);
            int netBlend = Math.min(100, Math.max(packet.getInt("CPU", 0), packet.getInt("RAM", 0)));
            drawDesktopMiniBar(g2, "Load", netBlend, x + 12, statsTop + 32, w - 24, ORANGE);
        }

        private void drawDesktopGpuCard(Graphics2D g2, int x, int y, int w, int h) {
            drawDesktopCard(g2, "GPU", x, y, w, h);
            int gpu = packet.getInt("GPU", 0);
            int vram = packet.getInt("VRAMPCT", packet.getInt("VRAM_PERCENT", 0));
            int tempPct = parseTemperaturePct(packet.get("GPUTEMP", packet.get("GPU_TEMP", "0")));
            String gpuTempDisplay = normalizeTemperatureText(packet.get("GPUTEMP", packet.get("GPU_TEMP", packet.get("TEMP", "--"))));
            drawDesktopMiniBar(g2, "GPU", gpu, x + 12, y + 30, w - 24, ORANGE);
            drawDesktopMiniBar(g2, "VRAM", vram, x + 12, y + 46, w - 24, MAGENTA);
            drawDesktopMiniBar(g2, "TEMP", tempPct, x + 12, y + 62, w - 24, YELLOW);
            g2.setFont(new Font(MONO, Font.PLAIN, 11));
            g2.setColor(CYAN);
            g2.drawString("Temp: " + gpuTempDisplay + "  Name: " + truncate(packet.get("GPUNAME", "GPU"), 20), x + 12, y + h - 12);
        }

        private void drawNetworkStorageDesktop(Graphics2D g2, int x, int y, int w, int h) {
            drawDesktopCard(g2, "Network / Storage", x, y, w, h);
            g2.setFont(new Font(MONO, Font.PLAIN, 12));
            int rowY = y + 30;
            drawDesktopField(g2, "Down", packet.get("DOWN", packet.get("NETDOWN", "--")), x + 10, rowY, LIME);
            rowY += 18;
            drawDesktopField(g2, "Up", packet.get("UPNET", packet.get("NETUP", "--")), x + 10, rowY, YELLOW);
            rowY += 18;
            drawDesktopField(g2, "DnTot", packet.get("DNTOT", packet.get("DOWNTOTAL", "--")), x + 10, rowY, CYAN);
            rowY += 18;
            drawDesktopField(g2, "UpTot", packet.get("UPTOT", packet.get("UPTOTAL", "--")), x + 10, rowY, WHITE);
            rowY += 22;

            int rootPct = packet.getInt("DISK0", packet.getInt("D0", 0));
            int srvPct = packet.getInt("DISK1", packet.getInt("D1", 0));
            drawStorageUsageRow(g2, "root", rootPct, "system", x + 10, rowY, w - 20, CYAN);
            rowY += 20;
            drawStorageUsageRow(g2, "srv", srvPct, "secondary", x + 10, rowY, w - 20, YELLOW);

            List<StorageMountEntry> mounts = collectStorageMounts();
            int extraY = rowY + 22;
            int maxRows = Math.max(0, (y + h - 18 - extraY) / 19);
            int shown = 0;
            for (StorageMountEntry mount : mounts) {
                if (shown >= maxRows) {
                    break;
                }
                if (mount.percent >= 0 && (mount.percent == rootPct || mount.percent == srvPct)) {
                    continue;
                }
                drawStorageUsageRow(
                        g2,
                        shown == 0 ? "other" : ("other" + shown),
                        mount.percent,
                        mount.displayText,
                        x + 10,
                        extraY,
                        w - 20,
                        ORANGE
                );
                extraY += 19;
                shown++;
            }
        }

        private void drawHistoryDesktop(Graphics2D g2, int x, int y, int w, int h) {
            drawDesktopCard(g2, "Live History", x, y, w, h);
            int gx = x + 12;
            int gy = y + 34;
            int gw = w - 20;
            int gh = h - 76;
            GradientPaint chartPaint = new GradientPaint(gx, gy, new Color(18, 28, 46), gx, gy + gh, new Color(10, 16, 30));
            g2.setPaint(chartPaint);
            g2.fillRect(gx, gy, gw, gh);
            g2.setColor(GRID);
            g2.drawRect(gx, gy, gw, gh);
            for (int i = 1; i < 4; i++) {
                int yLine = gy + (gh * i) / 4;
                g2.drawLine(gx, yLine, gx + gw, yLine);
            }
            for (int i = 1; i < 6; i++) {
                int xLine = gx + (gw * i) / 6;
                g2.drawLine(xLine, gy, xLine, gy + gh);
            }
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(2f));
            drawDesktopHistoryLine(g2, cpuHistory, gx, gy, gw, gh, LIME);
            drawDesktopHistoryLine(g2, ramHistory, gx, gy, gw, gh, CYAN);
            drawDesktopHistoryLine(g2, gpuHistory, gx, gy, gw, gh, ORANGE);
            drawDesktopHistoryLine(g2, vramHistory, gx, gy, gw, gh, MAGENTA);
            g2.setStroke(oldStroke);
            g2.setFont(new Font(MONO, Font.PLAIN, 11));
            int legendY = y + h - 16;
            g2.setColor(WHITE);
            g2.drawString("CPU", x + 12, legendY);
            g2.setColor(CYAN);
            g2.drawString("RAM", x + 56, legendY);
            g2.setColor(ORANGE);
            g2.drawString("GPU", x + 102, legendY);
            g2.setColor(MAGENTA);
            g2.drawString("VRAM", x + 146, legendY);
        }

        private void drawDesktopMiniBar(Graphics2D g2, String label, int value, int x, int y, int w, Color color) {
            g2.setFont(new Font(MONO, Font.BOLD, 10));
            g2.setColor(WHITE);
            g2.drawString(label, x, y + 8);
            int barX = x + 36;
            int barW = Math.max(40, w - 82);
            drawBar(g2, barX, y + 1, barW, 8, value, color);
            g2.setColor(color);
            g2.drawString(value + "%", x + w - 34, y + 8);
        }

        private void drawDesktopHistoryLine(Graphics2D g2, int[] history, int x, int y, int w, int h, Color color) {
            g2.setColor(color);
            for (int i = 1; i < history.length; i++) {
                int x1 = x + (i - 1) * (w - 1) / (history.length - 1);
                int x2 = x + i * (w - 1) / (history.length - 1);
                int y1 = y + h - 1 - (history[i - 1] * (h - 2) / 100);
                int y2 = y + h - 1 - (history[i] * (h - 2) / 100);
                g2.drawLine(x1, y1, x2, y2);
            }
        }

        private void drawDesktopHistoryLineScaled(Graphics2D g2, int[] history, int x, int y, int w, int h, int scaleMax, Color color) {
            if (scaleMax <= 0) {
                return;
            }
            g2.setColor(color);
            for (int i = 1; i < history.length; i++) {
                int x1 = x + (i - 1) * (w - 1) / (history.length - 1);
                int x2 = x + i * (w - 1) / (history.length - 1);
                int y1 = y + h - 1 - (Math.min(history[i - 1], scaleMax) * (h - 2) / scaleMax);
                int y2 = y + h - 1 - (Math.min(history[i], scaleMax) * (h - 2) / scaleMax);
                g2.drawLine(x1, y1, x2, y2);
            }
        }

        private void drawDesktopField(Graphics2D g2, String label, String value, int x, int y, Color valueColor) {
            g2.setColor(WHITE);
            g2.drawString(label + ":", x, y);
            g2.setColor(valueColor);
            g2.drawString(truncate(value, 22), x + 66, y);
        }

        private void drawStorageUsageRow(Graphics2D g2, String label, int percent, String detail, int x, int y, int width, Color color) {
            int clamped = Math.max(0, Math.min(100, percent));
            g2.setFont(new Font(MONO, Font.BOLD, 11));
            g2.setColor(WHITE);
            g2.drawString(label + ":", x, y);
            int barX = x + 58;
            int barW = Math.max(56, width - 126);
            drawBar(g2, barX, y - 9, barW, 8, clamped, color);
            g2.setColor(color);
            g2.drawString(clamped + "%", x + width - 52, y);
            g2.setFont(new Font(MONO, Font.PLAIN, 10));
            g2.setColor(new Color(205, 220, 240));
            g2.drawString(truncate(detail, 16), barX, y + 10);
        }

        private int parseTemperaturePct(String text) {
            if (text == null) {
                return 0;
            }
            String normalized = text.trim();
            java.util.regex.Matcher valueMatcher = java.util.regex.Pattern.compile("(-?\\d+(?:\\.\\d+)?)").matcher(normalized);
            if (!valueMatcher.find()) {
                return 0;
            }
            double value;
            try {
                value = Double.parseDouble(valueMatcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
            String upper = normalized.toUpperCase();
            double celsius = upper.contains("F") && !upper.contains("C")
                    ? ((value - 32.0) * 5.0 / 9.0)
                    : value;
            int pct = (int) Math.round(((celsius - 20.0) / 80.0) * 100.0);
            return Math.max(0, Math.min(100, pct));
        }

        private String normalizeTemperatureText(String text) {
            if (text == null || text.isBlank()) {
                return "--";
            }
            String trimmed = text.trim();
            if (trimmed.endsWith("C") || trimmed.endsWith("°C") || trimmed.endsWith("F") || trimmed.endsWith("°F")) {
                return trimmed;
            }
            String digits = trimmed.replaceAll("[^0-9.\\-]", "");
            if (digits.isBlank()) {
                return trimmed;
            }
            return digits + "C";
        }

        private int parseRateKbps(String rateText) {
            if (rateText == null || rateText.isBlank()) {
                return 0;
            }
            String normalized = rateText.trim().toUpperCase();
            String digits = normalized.replaceAll("[^0-9.]", "");
            if (digits.isBlank()) {
                return 0;
            }
            double value;
            try {
                value = Double.parseDouble(digits);
            } catch (NumberFormatException ex) {
                return 0;
            }
            if (normalized.contains("GB")) {
                value *= 1024 * 1024;
            } else if (normalized.contains("MB")) {
                value *= 1024;
            } else if (normalized.contains("B") && !normalized.contains("KB")) {
                value /= 1024.0;
            }
            return Math.max(0, (int) Math.round(value));
        }

        private int maxValue(int[] values) {
            int max = 0;
            for (int value : values) {
                max = Math.max(max, value);
            }
            return max;
        }

        private List<StorageMountEntry> collectStorageMounts() {
            List<StorageMountEntry> mounts = new ArrayList<>();
            Map<String, String> values = packet.snapshotValues();
            List<String> dedup = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                String entry = values.get("DRV" + i);
                if (entry == null || entry.isBlank() || "--".equals(entry.trim())) {
                    continue;
                }
                StorageMountEntry parsed = parseStorageMountEntry(entry);
                String dedupKey = parsed.label.toLowerCase() + "|" + parsed.percent + "|" + parsed.displayText.toLowerCase();
                if (!dedup.contains(dedupKey)) {
                    dedup.add(dedupKey);
                    mounts.add(parsed);
                }
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                if (!(key.startsWith("DISK") && key.length() > 4)) {
                    continue;
                }
                String value = entry.getValue();
                if (value == null || value.isBlank() || "--".equals(value.trim())) {
                    continue;
                }
                int pct = parsePercentValue(value);
                String label = key.equals("DISK0") ? "root" : (key.equals("DISK1") ? "srv" : key.toLowerCase());
                StorageMountEntry parsed = new StorageMountEntry(label, pct, key.toLowerCase());
                String dedupKey = parsed.label.toLowerCase() + "|" + parsed.percent + "|" + parsed.displayText.toLowerCase();
                if (!dedup.contains(dedupKey)) {
                    dedup.add(dedupKey);
                    mounts.add(parsed);
                }
            }
            return mounts;
        }

        private StorageMountEntry parseStorageMountEntry(String rawValue) {
            String cleaned = rawValue == null ? "" : rawValue.trim();
            if (cleaned.isBlank()) {
                return new StorageMountEntry("disk", 0, "--");
            }
            int pct = parsePercentValue(cleaned);
            String compact = cleaned.replaceAll("\\s+", " ").trim();
            String[] parts = compact.split(" ");
            String label = parts.length > 0 ? parts[0] : "disk";
            String detail = compact;
            if (parts.length > 1) {
                String lastToken = parts[parts.length - 1].toLowerCase();
                if (!lastToken.endsWith("%")) {
                    detail = lastToken;
                }
            }
            return new StorageMountEntry(truncate(label, 10), pct, truncate(detail, 16));
        }

        private int parsePercentValue(String valueText) {
            if (valueText == null || valueText.isBlank()) {
                return 0;
            }
            String matcherSource = valueText.trim();
            java.util.regex.Matcher percentMatcher = java.util.regex.Pattern.compile("(\\d{1,3})\\s*%").matcher(matcherSource);
            if (percentMatcher.find()) {
                try {
                    return Math.max(0, Math.min(100, Integer.parseInt(percentMatcher.group(1))));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            String digits = matcherSource.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                return 0;
            }
            try {
                return Math.max(0, Math.min(100, Integer.parseInt(digits)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private static class StorageMountEntry {
            final String label;
            final int percent;
            final String displayText;

            StorageMountEntry(String label, int percent, String displayText) {
                this.label = label;
                this.percent = percent;
                this.displayText = displayText;
            }
        }

        private void drawDesktopCard(Graphics2D g2, String title, int x, int y, int w, int h) {
            GradientPaint cardPaint = new GradientPaint(x, y, new Color(24, 38, 66), x, y + h, new Color(15, 24, 43));
            g2.setPaint(cardPaint);
            g2.fillRoundRect(x, y, w, h, 12, 12);
            g2.setColor(PANEL_LINE);
            g2.drawRoundRect(x, y, w, h, 12, 12);
            g2.setFont(new Font(MONO, Font.BOLD, 12));
            g2.setColor(CYAN);
            g2.drawString(title, x + 10, y + 16);
        }

        private int drawHeader(Graphics2D g2, String title, int w, int m) {
            int left = m + 8;
            int right = w - m - 8;
            int top = m + 4;
            int ruleY = top + 24;
            String pageCounter = (pageIndex + 1) + "/7";
            String versionText = "Version: " + APP_VERSION;
            boolean homePage = pageIndex == 0;
            String wifiText = previewWifiEnabled ? "WiFi On" : "WiFi Off";

            g2.setColor(WHITE);
            g2.setFont(new Font(MONO, Font.BOLD, 10));
            FontMetrics counterMetrics = g2.getFontMetrics();
            int counterX = right - counterMetrics.stringWidth(pageCounter);

            if (homePage) {
                String titleText = "Ray Co. System Monitor";
                g2.setColor(CYAN);
                g2.setFont(fitFont(g2, titleText, Font.BOLD, 16, 12, right - left));
                FontMetrics titleMetrics = g2.getFontMetrics();
                int titleBaseline = top + titleMetrics.getAscent() + 2;
                g2.drawString(titleText, left, titleBaseline);

                int infoBaseline = titleBaseline + 15;
                g2.setFont(new Font(MONO, Font.PLAIN, 9));
                FontMetrics infoMetrics = g2.getFontMetrics();
                g2.setColor(previewWifiEnabled ? LIME : ORANGE);
                g2.drawString(wifiText, left, infoBaseline);

                g2.setColor(WHITE);
                int versionX = Math.max(left + 120, (w - infoMetrics.stringWidth(versionText)) / 2);
                g2.drawString(versionText, versionX, infoBaseline);
                g2.drawString(pageCounter, counterX, infoBaseline);
                ruleY = infoBaseline + 10;
            } else {
                g2.drawString(pageCounter, counterX, top + 11);
                g2.setColor(CYAN);
                g2.setFont(fitFont(g2, title, Font.BOLD, 14, 10, Math.max(120, right - left - 62)));
                FontMetrics titleMetrics = g2.getFontMetrics();
                int titleBaseline = top + titleMetrics.getAscent();
                g2.drawString(title, left, titleBaseline);

                int versionX = counterX - 56;
                g2.setColor(WHITE);
                g2.setFont(new Font(MONO, Font.BOLD, 10));
                g2.drawString(versionText, versionX, titleBaseline);

                g2.setFont(new Font(MONO, Font.PLAIN, 9));
                g2.setColor(previewWifiEnabled ? LIME : ORANGE);
                g2.drawString(wifiText, versionX - 72, titleBaseline);
            }

            g2.setColor(PANEL_LINE);
            g2.drawLine(m, ruleY, w - m, ruleY);
            return ruleY;
        }

        private void drawFooter(Graphics2D g2, int w, int h) {
            g2.setColor(WHITE);
            g2.setFont(new Font(MONO, Font.PLAIN, 9));
            g2.drawString("Tap to switch " + (pageIndex + 1) + "/7", 14, h - 10);
            String pageText = pageNames[pageIndex];
            int pageTextWidth = g2.getFontMetrics().stringWidth(pageText);
            g2.drawString(pageText, (w - pageTextWidth) / 2, h - 10);
            String helpText = "[click preview to switch]";
            int helpWidth = g2.getFontMetrics().stringWidth(helpText);
            g2.drawString(helpText, w - helpWidth - 14, h - 10);
        }

        private void drawBar(Graphics2D g2, int x, int y, int bw, int bh, int percent, Color fill) {
            g2.setColor(PANEL_LINE);
            g2.drawRect(x, y, bw, bh);
            int fw = Math.max(0, Math.min(bw - 1, (int) ((bw - 1) * (percent / 100.0))));
            g2.setColor(fill);
            g2.fillRect(x + 1, y + 1, fw, Math.max(1, bh - 1));
        }

        private void drawLabelValue(Graphics2D g2, String label, String value, int x, int y, Color valueColor) {
            g2.setFont(new Font(MONO, Font.BOLD, 13));
            g2.setColor(WHITE);
            g2.drawString(label, x, y);
            g2.setColor(valueColor);
            g2.setFont(fitFont(g2, value, Font.BOLD, 13, 9, 250));
            g2.drawString(value, x + 72, y);
        }

        private Font fitFont(Graphics2D g2, String text, int style, int preferredSize, int minSize, int maxWidth) {
            int size = preferredSize;
            while (size > minSize) {
                Font candidate = new Font(MONO, style, size);
                if (g2.getFontMetrics(candidate).stringWidth(text) <= maxWidth) {
                    return candidate;
                }
                size--;
            }
            return new Font(MONO, style, minSize);
        }

        private void drawHome(Graphics2D g2, int w, int h, int m) {
            int headerBottom = drawHeader(g2, packet.get("HEADER", "Ray Co. Universal System Monitor"), w, m);
            g2.setFont(new Font(MONO, Font.BOLD, 14));

            int labelX = 16;
            int barX = 106;
            int pctX = w - 54;
            int startY = headerBottom + 22;
            int row = 22;
            int barW = 272;
            int barH = 10;

            String[] labels = {"CPU", "RAM", "D0", "D1"};
            int[] vals = {
                    packet.getInt("CPU", 0),
                    packet.getInt("RAM", 0),
                    packet.getInt("DISK0", packet.getInt("D0", 0)),
                    packet.getInt("DISK1", packet.getInt("D1", 0))
            };
            Color[] colors = {LIME, CYAN, MAGENTA, YELLOW};

            for (int i = 0; i < labels.length; i++) {
                int y = startY + i * row;
                g2.setColor(WHITE);
                g2.drawString(labels[i], labelX, y);
                drawBar(g2, barX, y - 11, barW, barH, vals[i], colors[i]);
                g2.setColor(WHITE);
                g2.drawString(vals[i] + "%", pctX, y);
            }

            int infoY = headerBottom + 112;
            drawLabelValue(g2, "Freq", packet.get("FREQ", packet.get("CPUFREQ", "0 MHz")), 16, infoY, ORANGE);
            drawLabelValue(g2, "RAM", packet.get("RAMGB", packet.get("RAMTEXT", "--")), 16, infoY + 21, CYAN);
            drawLabelValue(g2, "Up", packet.get("UPTIME", packet.get("UP", "0m")), 16, infoY + 42, WHITE);
            drawLabelValue(g2, "OS", truncate(packet.get("OS", "Linux"), 28), 16, infoY + 63, CYAN);
            drawLabelValue(g2, "Host", truncate(resolvePreviewHostname(), 28), 16, infoY + 84, LIME);
            drawLabelValue(g2, "WiFi", previewWifiEnabled ? "ENABLED" : "DISABLED", 16, infoY + 105, previewWifiEnabled ? LIME : ORANGE);
            drawFooter(g2, w, h);
        }

        private void drawCpu(Graphics2D g2, int w, int h, int m) {
            int headerBottom = drawHeader(g2, "CPU Threads", w, m);
            g2.setFont(new Font(MONO, Font.BOLD, 15));
            g2.setColor(WHITE);
            g2.drawString("Total", 15, headerBottom + 20);
            int total = packet.getInt("CPU", 0);
            drawBar(g2, 102, headerBottom + 8, 270, 10, total, getHeatColor(total));
            g2.drawString(total + "%", 386, headerBottom + 20);

            int startX = 12;
            int startY = headerBottom + 46;
            int colW = 114;
            int rowH = 38;
            int barW = 100;
            int barH = 8;

            for (int i = 0; i < 16; i++) {
                int row = i / 4;
                int col = i % 4;
                int x = startX + col * colW;
                int y = startY + row * rowH;
                int val = packet.getInt("C" + i, packet.getInt("CPU" + i, i % 5));
                g2.setColor(WHITE);
                g2.setFont(new Font(MONO, Font.BOLD, 9));
                g2.drawString("C" + i, x, y);
                g2.setColor(getHeatColor(val));
                g2.drawString(val + "%", x + 72, y);
                drawBar(g2, x, y + 10, barW, barH, val, LIME);
            }

            g2.setFont(new Font(MONO, Font.BOLD, 12));
            g2.setColor(ORANGE);
            g2.drawString("Freq", 12, h - 46);
            g2.setColor(WHITE);
            g2.drawString(truncate(packet.get("FREQ", packet.get("CPUFREQ", "800 MHz")), 14), 58, h - 46);
            g2.setColor(CYAN);
            g2.drawString("Temp", 268, h - 46);
            g2.setColor(WHITE);
            g2.drawString(packet.get("TEMP", packet.get("CPUTEMP", "33.0C")), 320, h - 46);
            drawFooter(g2, w, h);
        }

        private void drawProcesses(Graphics2D g2, int w, int h, int m) {
            int headerBottom = drawHeader(g2, "Processes", w, m);
            int columnHeaderY = headerBottom + 16;
            int firstRowY = headerBottom + 36;
            int rowHeight = 24;
            g2.setFont(new Font(MONO, Font.BOLD, 9));
            g2.setColor(WHITE);
            g2.drawString("CPU", 318, columnHeaderY);
            g2.drawString("RAM", 400, columnHeaderY);

            for (int i = 1; i <= 6; i++) {
                int y = firstRowY + (i - 1) * rowHeight;
                String name = packet.get("P" + i, switch (i) {
                    case 1 -> "bluetoothd";
                    case 2 -> "conky";
                    case 3 -> "steam";
                    case 4 -> "Xwayland";
                    case 5 -> "Isolated Web Co";
                    default -> "Discord";
                });
                String cpu = packet.get("P" + i + "CPU", switch (i) {
                    case 1 -> "5%";
                    case 2 -> "4%";
                    case 3 -> "3%";
                    default -> "1%";
                });
                String ram = packet.get("P" + i + "RAM", switch (i) {
                    case 3 -> "1.2%";
                    case 5 -> "4.9%";
                    default -> "0.0%";
                });
                g2.setColor(WHITE);
                g2.drawString(i + ".", 10, y);
                g2.setColor(switch (i) {
                    case 1 -> LIME;
                    case 2 -> YELLOW;
                    case 3 -> CYAN;
                    case 4 -> MAGENTA;
                    case 5 -> ORANGE;
                    default -> WHITE;
                });
                g2.drawString(truncate(name, 28), 24, y);
                g2.setColor(YELLOW);
                g2.drawString(cpu, 318, y);
                g2.setColor(CYAN);
                g2.drawString(ram, 400, y);
            }

            g2.setFont(new Font(MONO, Font.BOLD, 12));
            g2.setColor(LIME);
            g2.drawString("CPU", 12, h - 46);
            g2.setColor(WHITE);
            g2.drawString(packet.get("CPU", "4") + "%", 54, h - 46);
            g2.setColor(CYAN);
            g2.drawString("RAM", 130, h - 46);
            g2.setColor(WHITE);
            g2.drawString(packet.get("RAM", "23") + "%", 172, h - 46);
            g2.setColor(YELLOW);
            g2.drawString("GPU", 270, h - 46);
            g2.setColor(WHITE);
            g2.drawString(packet.get("GPU", "0") + "%", 312, h - 46);
            drawFooter(g2, w, h);
        }

        private void drawNetwork(Graphics2D g2, int w, int h, int m) {
            int headerBottom = drawHeader(g2, "Network", w, m);
            int y = headerBottom + 26;
            int row = 25;
            drawLabelValue(g2, "Host", truncate(resolvePreviewHostname(), 28), 42, y, CYAN);
            drawLabelValue(g2, "OS", truncate(packet.get("OS", "Fedora Linux 43"), 28), 42, y + row, YELLOW);
            drawLabelValue(g2, "IP", truncate(resolvePreviewIpDisplay(), 28), 42, y + row * 2, WHITE);
            drawLabelValue(g2, "WiFi", previewWifiEnabled ? "WIFI OK" : "WIFI OFF", 42, y + row * 3, previewWifiEnabled ? LIME : ORANGE);
            drawLabelValue(g2, "Down", packet.get("DOWN", packet.get("NETDOWN", "3 KB/s")), 42, y + row * 4, LIME);
            drawLabelValue(g2, "Up", packet.get("UPNET", packet.get("NETUP", "1 KB/s")), 42, y + row * 5, YELLOW);
            drawLabelValue(g2, "DnTot", packet.get("DNTOT", packet.get("DOWNTOTAL", "212.1 GB")), 42, y + row * 6, CYAN);
            drawLabelValue(g2, "UpTot", packet.get("UPTOT", packet.get("UPTOTAL", "4.3 GB")), 42, y + row * 7, YELLOW);
            drawLabelValue(g2, "Up", packet.get("UPTIME", packet.get("UP", "4h 29m")), 42, y + row * 8, WHITE);
            drawFooter(g2, w, h);
        }

        private void drawGpu(Graphics2D g2, int w, int h, int m) {
            int headerBottom = drawHeader(g2, "GPU", w, m);
            int gaugeBaselineY = headerBottom + 28;
            g2.setFont(new Font(MONO, Font.BOLD, 15));
            g2.setColor(WHITE);
            g2.drawString("GPU", 15, gaugeBaselineY);
            int gpu = packet.getInt("GPU", 0);
            drawBar(g2, 102, gaugeBaselineY - 12, 270, 10, gpu, getHeatColor(gpu));
            g2.drawString(gpu + "%", 386, gaugeBaselineY);

            int y = headerBottom + 48;
            int row = 24;
            String gpuName = truncate(packet.get("GPUNAME", packet.get("NAME", "AMD RX 6600")), 28);
            g2.setFont(fitFont(g2, gpuName, Font.BOLD, 15, 11, 310));
            g2.setColor(WHITE);
            g2.drawString("Name", 15, y);
            g2.setColor(CYAN);
            g2.drawString(gpuName, 102, y);
            drawLabelValue(g2, "Temp", normalizeTemperatureText(packet.get("GPUTEMP", packet.get("TEMP", "56.0C"))), 15, y + row, CYAN);
            drawLabelValue(g2, "Usage", packet.get("GPU", "0") + "%", 15, y + row * 2, LIME);
            drawLabelValue(g2, "VRAM", packet.get("VRAMUSED", packet.get("VRAM", "1936/8176M")), 15, y + row * 3, YELLOW);
            drawLabelValue(g2, "CLK Speed", packet.get("GPUCLK", packet.get("CLK", "0MHz")), 15, y + row * 4, ORANGE);
            drawFooter(g2, w, h);
        }

        private void drawStorage(Graphics2D g2, int w, int h, int m) {
            int headerBottom = drawHeader(g2, "Extra Statistics", w, m);
            g2.setFont(new Font(MONO, Font.BOLD, 9));
            g2.setColor(CYAN);
            g2.drawString("Storage", 12, headerBottom + 12);
            g2.setColor(WHITE);

            String[] lines = {
                    packet.get("DRV1", "fedora 42% root"),
                    packet.get("DRV2", "linux_storage 15% home"),
                    packet.get("DRV3", "games 68% steam"),
                    packet.get("DRV4", "backup 2.2T"),
                    packet.get("DRV5", "media 31% media"),
                    packet.get("DRV6", "archive 78% archive"),
                    packet.get("DRV7", "scratch 14% data"),
                    packet.get("DRV8", "Storage: --")
            };

            for (int i = 0; i < lines.length; i++) {
                g2.drawString(truncate(lines[i], 31), 12, headerBottom + 30 + i * 20);
            }

            int dividerX = 244;
            int panelX = 258;
            g2.setColor(PANEL_LINE);
            g2.drawLine(dividerX, headerBottom + 12, dividerX, h - 12);
            g2.setFont(new Font(MONO, Font.BOLD, 14));
            g2.setColor(YELLOW);
            g2.drawString("Battery / Devices", panelX, headerBottom + 20);

            int lineY = headerBottom + 42;
            String batteryMode = packet.get("BATTMODE", "DESKTOP");
            String batteryPct = packet.get("BATTPCT", "N/A");
            String batteryState = packet.get("BATTSTATE", "DESKTOP");
            g2.setFont(new Font(MONO, Font.PLAIN, 10));
            g2.setColor(WHITE);
            if ("DESKTOP".equalsIgnoreCase(batteryMode)) {
                g2.drawString("System battery: N/A", panelX, lineY);
                lineY += 18;
                if (!"DESKTOP".equalsIgnoreCase(batteryState) && !"--".equals(batteryState)) {
                    g2.setColor(ORANGE);
                    g2.drawString(truncate(batteryState, 24), panelX, lineY);
                    lineY += 18;
                }
            } else {
                g2.drawString("System battery: ", panelX, lineY);
                g2.setColor(LIME);
                g2.drawString(batteryPct + "%", panelX + 90, lineY);
                lineY += 18;
                g2.setColor("Charging".equalsIgnoreCase(batteryState) ? LIME : ORANGE);
                g2.drawString(truncate(batteryState, 24), panelX, lineY);
                lineY += 24;
            }

            String extraBattery = packet.get("BATTDEV1", "--");
            String extraBatteryState = packet.get("BATTDEV1STATE", "--");
            if (!"--".equals(extraBattery)) {
                g2.setFont(new Font(MONO, Font.PLAIN, 9));
                g2.setColor(CYAN);
                g2.drawString(truncate(extraBattery, 24), panelX, lineY);
                lineY += 16;
                if (!"--".equals(extraBatteryState)) {
                    g2.setColor("Charging".equalsIgnoreCase(extraBatteryState) ? LIME : ORANGE);
                    g2.drawString(truncate(extraBatteryState, 24), panelX, lineY);
                }
            }
        }

        private void drawGraph(Graphics2D g2, int w, int h, int m) {
            int headerBottom = drawHeader(g2, "Usage Graph", w, m);
            int gx = 20;
            int gy = headerBottom + 12;
            int gw = 430;
            int gh = 122;

            g2.setColor(PANEL_LINE);
            g2.drawRect(gx, gy, gw, gh);
            for (int i = 1; i < 7; i++) {
                int x = gx + i * gw / 7;
                g2.setColor(GRID);
                g2.drawLine(x, gy, x, gy + gh);
            }
            for (int i = 1; i < 5; i++) {
                int y = gy + i * gh / 5;
                g2.setColor(GRID);
                g2.drawLine(gx, y, gx + gw, y);
            }

            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(2f));
            drawHistoryLine(g2, cpuHistory, gx, gy, gw, gh, LIME);
            drawHistoryLine(g2, ramHistory, gx, gy, gw, gh, CYAN);
            drawHistoryLine(g2, gpuHistory, gx, gy, gw, gh, YELLOW);
            drawHistoryLine(g2, vramHistory, gx, gy, gw, gh, MAGENTA);
            g2.setStroke(oldStroke);

            g2.setFont(new Font(MONO, Font.BOLD, 8));
            g2.setColor(WHITE);
            g2.drawString("100", gx + gw + 10, gy + 8);
            g2.drawString("0", gx + gw + 24, gy + gh);

            int legendY = gy + gh + 16;
            g2.setFont(new Font(MONO, Font.BOLD, 11));
            g2.setColor(LIME);
            g2.drawString("CPU", 20, legendY);
            g2.setColor(WHITE);
            g2.drawString(packet.get("CPU", "0") + "%", 84, legendY);
            g2.setColor(YELLOW);
            g2.drawString("GPU", 240, legendY);
            g2.setColor(WHITE);
            g2.drawString(packet.get("GPU", "0") + "%", 302, legendY);
            g2.setColor(CYAN);
            g2.drawString("RAM", 20, legendY + 22);
            g2.setColor(WHITE);
            g2.drawString(packet.get("RAM", "23") + "%", 84, legendY + 22);
            g2.setColor(MAGENTA);
            g2.drawString("VRAM", 240, legendY + 22);
            g2.setColor(WHITE);
            g2.drawString(packet.get("VRAMPCT", packet.get("VRAM_PERCENT", "24%")), 320, legendY + 22);
            drawFooter(g2, w, h);
        }

        private void drawHistoryLine(Graphics2D g2, int[] history, int gx, int gy, int gw, int gh, Color color) {
            g2.setColor(color);
            for (int i = 1; i < history.length; i++) {
                int x1 = gx + ((i - 1) * (gw - 2)) / (history.length - 1);
                int x2 = gx + (i * (gw - 2)) / (history.length - 1);
                int y1 = gy + gh - 1 - ((history[i - 1] * (gh - 2)) / 100);
                int y2 = gy + gh - 1 - ((history[i] * (gh - 2)) / 100);
                g2.drawLine(x1, y1, x2, y2);
            }
        }

        private Color getHeatColor(int percent) {
            if (percent < 50) return LIME;
            if (percent < 80) return YELLOW;
            return new Color(255, 110, 110);
        }

        private String truncate(String value, int maxLen) {
            if (value == null) return "--";
            return value.length() <= maxLen ? value : value.substring(0, maxLen);
        }

        private String resolvePreviewHostname() {
            return previewWifiEnabled ? previewWifiHostname : packet.get("HOST", "fedora");
        }

        private String resolvePreviewIpDisplay() {
            if (!previewWifiEnabled) {
                return "USB only";
            }
            return previewWifiIp + ":" + previewWifiPort;
        }
    }
}
