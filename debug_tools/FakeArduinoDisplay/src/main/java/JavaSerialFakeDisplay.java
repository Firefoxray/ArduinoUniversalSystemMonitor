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
        private final Map<String, String> values = new LinkedHashMap<>();
        private String raw = "";

        static ParsedPacket parse(String line) {
            ParsedPacket packet = new ParsedPacket();
            packet.raw = line;

            String[] parts = line.split("\\|");
            for (String part : parts) {
                String[] kv = part.split(":", 2);
                if (kv.length == 2) {
                    packet.values.put(kv[0].trim().toUpperCase(), kv[1].trim());
                }
            }
            return packet;
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
        private static final Color BG = new Color(8, 12, 26);
        private static final Color CYAN = new Color(60, 240, 255);
        private static final Color WHITE = new Color(235, 240, 255);
        private static final Color LIME = new Color(95, 255, 60);
        private static final Color YELLOW = new Color(255, 220, 40);
        private static final Color MAGENTA = new Color(255, 70, 220);
        private static final Color GRID = new Color(95, 120, 155);
        private static final Color PANEL_LINE = new Color(215, 225, 255);
        private static final Color ORANGE = new Color(255, 170, 60);

        private ParsedPacket packet = new ParsedPacket();
        private int pageIndex = 0;
        private final String[] pageNames = {
                "Home", "CPU", "Processes", "Network", "GPU", "Storage", "Graph"
        };

        FakeDisplayPanel() {
            setPreferredSize(new Dimension(920, 560));
            setBackground(Color.BLACK);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    pageIndex = (pageIndex + 1) % 7;
                    repaint();
                }
            });
        }

        void updatePacket(ParsedPacket packet) {
            this.packet = packet;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int m = 18;

            g2.setColor(BG);
            g2.fillRect(0, 0, w, h);

            switch (pageIndex) {
                case 0 -> drawHome(g2, w, h, m);
                case 1 -> drawCpu(g2, w, h, m);
                case 2 -> drawProcesses(g2, w, h, m);
                case 3 -> drawNetwork(g2, w, h, m);
                case 4 -> drawGpu(g2, w, h, m);
                case 5 -> drawStorage(g2, w, h, m);
                case 6 -> drawGraph(g2, w, h, m);
            }

            g2.dispose();
        }

        private void drawHeader(Graphics2D g2, String title, int w, int m) {
            g2.setColor(CYAN);
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 30));
            g2.drawString(title, m + 8, m + 28);
            g2.setColor(PANEL_LINE);
            g2.drawLine(m, m + 42, w - m, m + 42);
        }

        private void drawFooter(Graphics2D g2, int w, int h) {
            g2.setColor(WHITE);
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
            g2.drawString("Tap to switch " + (pageIndex + 1) + "/7", 28, h - 18);
            g2.drawString("[click preview to switch]", w - 270, h - 18);
        }

        private void drawBar(Graphics2D g2, int x, int y, int bw, int bh, int percent, Color fill) {
            g2.setColor(PANEL_LINE);
            g2.drawRect(x, y, bw, bh);
            int fw = Math.max(0, Math.min(bw - 1, (int) ((bw - 1) * (percent / 100.0))));
            g2.setColor(fill);
            g2.fillRect(x + 1, y + 1, fw, Math.max(1, bh - 1));
        }

        private void drawLabelValue(Graphics2D g2, String label, String value, int x, int y, Color valueColor) {
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 24));
            g2.setColor(WHITE);
            g2.drawString(label, x, y);
            g2.setColor(valueColor);
            g2.drawString(value, x + 170, y);
        }

        private void drawHome(Graphics2D g2, int w, int h, int m) {
            drawHeader(g2, packet.get("HEADER", "Ray Co. Universal System Monitor"), w, m);
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));

            int labelX = 60;
            int barX = 280;
            int pctX = w - 120;
            int startY = 110;
            int row = 62;
            int barW = 420;
            int barH = 28;

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
                drawBar(g2, barX, y - 24, barW, barH, vals[i], colors[i]);
                g2.setColor(WHITE);
                g2.drawString(vals[i] + "%", pctX, y);
            }

            int infoY = 380;
            drawLabelValue(g2, "Freq", packet.get("FREQ", packet.get("CPUFREQ", "0 MHz")), 60, infoY, YELLOW);
            drawLabelValue(g2, "Up", packet.get("UPTIME", packet.get("UP", "0m")), 60, infoY + 44, WHITE);
            drawLabelValue(g2, "OS", packet.get("OS", "Linux"), 60, infoY + 88, YELLOW);
            drawLabelValue(g2, "Host", packet.get("HOST", "fedora"), 60, infoY + 132, CYAN);
            drawFooter(g2, w, h);
        }

        private void drawCpu(Graphics2D g2, int w, int h, int m) {
            drawHeader(g2, "CPU Threads", w, m);
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
            g2.setColor(WHITE);
            g2.drawString("Total", 38, 108);
            int total = packet.getInt("CPU", 0);
            drawBar(g2, 275, 78, 450, 34, total, LIME);
            g2.drawString(total + "%", 750, 108);

            int startX = 36;
            int startY = 165;
            int colW = 225;
            int rowH = 86;
            int barW = 165;
            int barH = 22;

            for (int i = 0; i < 16; i++) {
                int col = i % 4;
                int row = i / 4;
                int x = startX + col * colW;
                int y = startY + row * rowH;
                int val = packet.getInt("C" + i, packet.getInt("CPU" + i, i % 5));
                g2.setColor(WHITE);
                g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
                g2.drawString("C" + i, x, y);
                g2.setColor(LIME);
                g2.drawString(val + "%", x + 120, y);
                drawBar(g2, x, y + 12, barW, barH, val, LIME);
            }

            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
            g2.setColor(ORANGE);
            g2.drawString("Freq", 48, h - 78);
            g2.setColor(WHITE);
            g2.drawString(packet.get("FREQ", packet.get("CPUFREQ", "800 MHz")), 155, h - 78);
            g2.setColor(CYAN);
            g2.drawString("Temp", 500, h - 78);
            g2.setColor(WHITE);
            g2.drawString(packet.get("TEMP", packet.get("CPUTEMP", "33.0C")), 620, h - 78);
            drawFooter(g2, w, h);
        }

        private void drawProcesses(Graphics2D g2, int w, int h, int m) {
            drawHeader(g2, "Processes", w, m);
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 24));
            g2.setColor(WHITE);
            g2.drawString("CPU", 640, 100);
            g2.drawString("RAM", 820, 100);

            for (int i = 1; i <= 6; i++) {
                int y = 140 + (i - 1) * 58;
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
                g2.drawString(i + ".", 34, y);
                g2.setColor((i % 2 == 0) ? YELLOW : LIME);
                g2.drawString(name, 70, y);
                g2.setColor(YELLOW);
                g2.drawString(cpu, 640, y);
                g2.setColor(CYAN);
                g2.drawString(ram, 820, y);
            }

            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 34));
            g2.setColor(LIME);
            g2.drawString("CPU", 48, h - 84);
            g2.setColor(WHITE);
            g2.drawString(packet.get("CPU", "4") + "%", 150, h - 84);
            g2.setColor(CYAN);
            g2.drawString("RAM", 370, h - 84);
            g2.setColor(WHITE);
            g2.drawString(packet.get("RAM", "23") + "%", 490, h - 84);
            g2.setColor(YELLOW);
            g2.drawString("GPU", 720, h - 84);
            g2.setColor(WHITE);
            g2.drawString(packet.get("GPU", "0") + "%", 840, h - 84);
            drawFooter(g2, w, h);
        }

        private void drawNetwork(Graphics2D g2, int w, int h, int m) {
            drawHeader(g2, "Network", w, m);
            int y = 120;
            int row = 52;
            drawLabelValue(g2, "Host", packet.get("HOST", "fedora"), 42, y, CYAN);
            drawLabelValue(g2, "OS", packet.get("OS", "Fedora Linux 43"), 42, y + row, YELLOW);
            drawLabelValue(g2, "IP", packet.get("IP", "192.168.0.104"), 42, y + row * 2, WHITE);
            drawLabelValue(g2, "Down", packet.get("DOWN", packet.get("NETDOWN", "3 KB/s")), 42, y + row * 3, LIME);
            drawLabelValue(g2, "Up", packet.get("UPNET", packet.get("NETUP", "1 KB/s")), 42, y + row * 4, YELLOW);
            drawLabelValue(g2, "DnTot", packet.get("DNTOT", packet.get("DOWNTOTAL", "212.1 GB")), 42, y + row * 5, CYAN);
            drawLabelValue(g2, "UpTot", packet.get("UPTOT", packet.get("UPTOTAL", "4.3 GB")), 42, y + row * 6, YELLOW);
            drawLabelValue(g2, "Up", packet.get("UPTIME", packet.get("UP", "4h 29m")), 42, y + row * 7, WHITE);
            drawFooter(g2, w, h);
        }

        private void drawGpu(Graphics2D g2, int w, int h, int m) {
            drawHeader(g2, "GPU", w, m);
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
            g2.setColor(WHITE);
            g2.drawString("GPU", 42, 110);
            int gpu = packet.getInt("GPU", 0);
            drawBar(g2, 330, 78, 420, 34, gpu, LIME);
            g2.drawString(gpu + "%", 790, 110);

            int y = 190;
            int row = 62;
            drawLabelValue(g2, "Name", packet.get("GPUNAME", packet.get("NAME", "AMD RX 6600")), 42, y, WHITE);
            drawLabelValue(g2, "Temp", packet.get("GPUTEMP", packet.get("TEMP", "56.0C")), 42, y + row, CYAN);
            drawLabelValue(g2, "VRAM", packet.get("VRAMUSED", packet.get("VRAM", "1936/8176M")), 42, y + row * 2, YELLOW);
            drawLabelValue(g2, "VRAM%", packet.get("VRAMPCT", packet.get("VRAM_PERCENT", "24%")), 42, y + row * 3, LIME);
            drawLabelValue(g2, "Clk", packet.get("GPUCLK", packet.get("CLK", "0MHz")), 42, y + row * 4, YELLOW);
            drawFooter(g2, w, h);
        }

        private void drawStorage(Graphics2D g2, int w, int h, int m) {
            drawHeader(g2, "Storage Inventory", w, m);
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
            g2.setColor(WHITE);

            String[] lines = {
                    packet.get("DRV1", "FireMain-Win 931.3G unmnt"),
                    packet.get("DRV2", "SP SSD 953.9G unmnt"),
                    packet.get("DRV3", "linux_storage 15% linux-st"),
                    packet.get("DRV4", "Second HDD 2.2T unmnt"),
                    packet.get("DRV5", "SD/MMC 0B unmnt"),
                    packet.get("DRV6", "Compact Flas 0B unmnt"),
                    packet.get("DRV7", "SM/xD-Pictur 0B unmnt")
            };

            for (int i = 0; i < lines.length; i++) {
                g2.drawString(lines[i], 34, 128 + i * 46);
            }

            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 30));
            g2.setColor(CYAN);
            g2.drawString("Opt", 46, h - 96);
            g2.setColor(WHITE);
            g2.drawString(packet.get("OPTICAL", packet.get("OPT", "Empty")), 160, h - 96);
            drawFooter(g2, w, h);
        }

        private void drawGraph(Graphics2D g2, int w, int h, int m) {
            drawHeader(g2, "Usage Graph", w, m);
            int gx = 110;
            int gy = 110;
            int gw = w - 200;
            int gh = 280;

            g2.setColor(PANEL_LINE);
            g2.drawRect(gx, gy, gw, gh);
            for (int i = 1; i < 8; i++) {
                int x = gx + i * gw / 8;
                g2.setColor(GRID);
                g2.drawLine(x, gy, x, gy + gh);
            }
            for (int i = 1; i < 5; i++) {
                int y = gy + i * gh / 5;
                g2.setColor(i == 3 ? MAGENTA : (i == 4 ? CYAN : new Color(70, 210, 120)));
                g2.drawLine(gx, y, gx + gw, y);
            }

            int[] fake = {4, 1, 7, 2, 3, 8, 2, 5, 1, 12, 3, 2, 4, 6, 3, 4, 5, 2, 2, 6, 3, 4, 4, 7, 5, 6, 8, 3};
            g2.setColor(LIME);
            for (int i = 1; i < fake.length; i++) {
                int x1 = gx + (i - 1) * gw / (fake.length - 1);
                int x2 = gx + i * gw / (fake.length - 1);
                int y1 = gy + gh - (fake[i - 1] * gh / 100);
                int y2 = gy + gh - (fake[i] * gh / 100);
                g2.drawLine(x1, y1, x2, y2);
            }

            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
            g2.setColor(WHITE);
            g2.drawString("100", gx + gw + 10, gy + 8);
            g2.drawString("0", gx + gw + 24, gy + gh);

            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 34));
            g2.setColor(LIME);
            g2.drawString("CPU", 120, h - 120);
            g2.setColor(WHITE);
            g2.drawString(packet.get("CPU", "0") + "%", 240, h - 120);
            g2.setColor(CYAN);
            g2.drawString("RAM", 120, h - 76);
            g2.setColor(WHITE);
            g2.drawString(packet.get("RAM", "23") + "%", 240, h - 76);
            g2.setColor(YELLOW);
            g2.drawString("GPU", 560, h - 120);
            g2.setColor(WHITE);
            g2.drawString(packet.get("GPU", "0") + "%", 680, h - 120);
            g2.setColor(MAGENTA);
            g2.drawString("VRAM", 560, h - 76);
            g2.setColor(WHITE);
            g2.drawString(packet.get("VRAMPCT", packet.get("VRAM_PERCENT", "24%")), 710, h - 76);
            drawFooter(g2, w, h);
        }
    }
}
