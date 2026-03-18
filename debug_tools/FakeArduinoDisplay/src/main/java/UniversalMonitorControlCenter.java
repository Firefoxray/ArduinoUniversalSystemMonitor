import com.fazecast.jSerialComm.SerialPort;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class UniversalMonitorControlCenter extends JFrame {

    private static final String SERVICE_NAME = "arduino-monitor.service";

    private final JTextField repoField = new JTextField(40);
    private final JPasswordField sudoPasswordField = new JPasswordField(18);

    private final JTextField fakeInField = new JTextField("/tmp/fakearduino_in", 22);
    private final JTextField fakeOutField = new JTextField("/tmp/fakearduino_out", 22);

    private final JTextArea outputArea = new JTextArea();

    private final JButton startFakePortsButton = new JButton("Start Fake Ports");
    private final JButton stopFakePortsButton = new JButton("Stop Fake Ports");
    private final JButton connectPreviewButton = new JButton("Connect Preview Port");
    private final JButton disconnectPreviewButton = new JButton("Disconnect Preview Port");

    private final JButton installButton = new JButton("Install on Linux");
    private final JButton uninstallButton = new JButton("Uninstall");
    private final JButton updateButton = new JButton("Update from GitHub");
    private final JButton flashButton = new JButton("Flash Arduino");
    private final JButton launchDebugButton = new JButton("Open Debug Display");

    private final JButton serviceOnButton = new JButton("Service On");
    private final JButton serviceOffButton = new JButton("Service Off");
    private final JButton serviceRestartButton = new JButton("Service Restart");
    private final JButton serviceStatusButton = new JButton("Service Status");

    private final JLabel serviceIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);

    private final JavaSerialFakeDisplay.FakeDisplayPanel fakeDisplayPanel = new JavaSerialFakeDisplay.FakeDisplayPanel();

    private volatile Process fakePortsProcess;
    private volatile SerialPort previewPort;
    private volatile boolean previewReading;
    private Thread previewReaderThread;

    public UniversalMonitorControlCenter() {
        super("Universal Arduino System Monitor - Control Center");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1500, 940);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        repoField.setText(detectRepoRoot().toString());
        sudoPasswordField.setToolTipText("Optional: sudo password used for installer/update/flash/service controls when not root");

        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.add(buildRepoPanel(), BorderLayout.NORTH);
        topPanel.add(buildActionPanel(), BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.62);
        splitPane.setLeftComponent(buildPreviewPanel());
        splitPane.setRightComponent(buildOutputPanel());

        add(topPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);

        wireActions();
        updatePortButtons();
        setServiceIndicator("UNKNOWN", Color.GRAY);

        Timer serviceTimer = new Timer(7000, e -> refreshServiceStatus(false));
        serviceTimer.setRepeats(true);
        serviceTimer.start();
        refreshServiceStatus(false);
    }

    private JPanel buildRepoPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Project Location / Privileges"));
        panel.add(new JLabel("Program Root:"));
        panel.add(repoField);

        JButton browseButton = new JButton("Browse");
        browseButton.addActionListener(e -> browseRepoPath());
        panel.add(browseButton);

        JButton openFolderButton = new JButton("Open Folder");
        openFolderButton.addActionListener(e -> openRepoFolder());
        panel.add(openFolderButton);

        panel.add(new JLabel("sudo password:"));
        panel.add(sudoPasswordField);

        return panel;
    }

    private JPanel buildActionPanel() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 10, 10));

        JPanel appActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        appActions.setBorder(BorderFactory.createTitledBorder("Linux App Management (uses sudo when needed)"));
        appActions.add(installButton);
        appActions.add(uninstallButton);
        appActions.add(updateButton);
        appActions.add(flashButton);
        appActions.add(launchDebugButton);

        JPanel servicePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        servicePanel.setBorder(BorderFactory.createTitledBorder("Service Controls: " + SERVICE_NAME));
        serviceIndicator.setOpaque(true);
        serviceIndicator.setPreferredSize(new Dimension(100, 30));
        servicePanel.add(serviceOnButton);
        servicePanel.add(serviceOffButton);
        servicePanel.add(serviceRestartButton);
        servicePanel.add(serviceStatusButton);
        servicePanel.add(new JLabel("Indicator:"));
        servicePanel.add(serviceIndicator);

        JPanel fakePorts = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fakePorts.setBorder(BorderFactory.createTitledBorder("Virtual Serial Ports + Preview Feed"));
        fakePorts.add(new JLabel("Input:"));
        fakePorts.add(fakeInField);
        fakePorts.add(new JLabel("Output:"));
        fakePorts.add(fakeOutField);
        fakePorts.add(startFakePortsButton);
        fakePorts.add(stopFakePortsButton);
        fakePorts.add(connectPreviewButton);
        fakePorts.add(disconnectPreviewButton);

        panel.add(appActions);
        panel.add(servicePanel);
        panel.add(fakePorts);
        return panel;
    }

    private JPanel buildPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Arduino Preview (live from output port)"));
        panel.add(fakeDisplayPanel, BorderLayout.CENTER);

        JLabel helper = new JLabel("Preview listens to Output port path. For full parser/log view use 'Open Debug Display'.");
        helper.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.add(helper, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Command Output / Logs"));
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        return panel;
    }

    private void wireActions() {
        installButton.addActionListener(e -> runRepoScript("install.sh", true));
        uninstallButton.addActionListener(e -> runRepoScript("uninstall_monitor.sh", true));
        updateButton.addActionListener(e -> runRepoScript("update.sh", true));
        flashButton.addActionListener(e -> runRepoScript("arduino_install.sh", true));
        launchDebugButton.addActionListener(e -> SwingUtilities.invokeLater(() -> new JavaSerialFakeDisplay().setVisible(true)));

        serviceOnButton.addActionListener(e -> runServiceCommand("start"));
        serviceOffButton.addActionListener(e -> runServiceCommand("stop"));
        serviceRestartButton.addActionListener(e -> runServiceCommand("restart"));
        serviceStatusButton.addActionListener(e -> refreshServiceStatus(true));

        startFakePortsButton.addActionListener(e -> startFakePorts());
        stopFakePortsButton.addActionListener(e -> stopFakePorts());
        connectPreviewButton.addActionListener(e -> connectPreviewPort());
        disconnectPreviewButton.addActionListener(e -> disconnectPreviewPort());
    }

    private void browseRepoPath() {
        JFileChooser chooser = new JFileChooser(repoField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            repoField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openRepoFolder() {
        try {
            File file = repoPath().toFile();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            }
        } catch (Exception ex) {
            log("[WARN] Cannot open folder: " + ex.getMessage());
        }
    }

    private void runRepoScript(String scriptName, boolean needsSudo) {
        Path script = repoPath().resolve(scriptName);
        if (!Files.exists(script)) {
            log("[ERROR] Missing script: " + script);
            return;
        }

        String command = "cd " + escape(repoPath().toString()) + " && chmod +x " + scriptName + " && ./" + scriptName;
        runCommand(command, repoPath().toFile(), scriptName, needsSudo, true);
    }

    private void runServiceCommand(String action) {
        String label = "service " + action;
        String command = "systemctl " + action + " " + SERVICE_NAME;
        runCommand(command, repoPath().toFile(), label, true, true);

        Timer delayed = new Timer(1200, e -> refreshServiceStatus(false));
        delayed.setRepeats(false);
        delayed.start();
    }

    private void refreshServiceStatus(boolean allowPrompt) {
        Thread t = new Thread(() -> {
            CommandSpec spec = buildShellCommand("systemctl is-active " + SERVICE_NAME, true, allowPrompt);
            if (spec == null) {
                return;
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(spec.command);
                pb.directory(repoPath().toFile());
                pb.redirectErrorStream(true);
                if (spec.sudoPassword != null) {
                    pb.environment().put("SUDO_PASS", spec.sudoPassword);
                }
                Process process = pb.start();

                StringBuilder out = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        out.append(line.trim()).append("\n");
                    }
                }
                int code = process.waitFor();
                String status = out.toString().trim();

                if (code == 0 && status.contains("active")) {
                    setServiceIndicator("ON", new Color(24, 170, 24));
                } else if (status.contains("inactive") || status.contains("failed") || status.contains("unknown")) {
                    setServiceIndicator("OFF", new Color(190, 35, 35));
                } else {
                    setServiceIndicator("UNKNOWN", Color.GRAY);
                }

                if (allowPrompt) {
                    log("[SERVICE] status output: " + (status.isEmpty() ? "<no output>" : status.replace("\n", " | ")));
                }
            } catch (Exception ex) {
                setServiceIndicator("UNKNOWN", Color.GRAY);
                if (allowPrompt) {
                    log("[ERROR] Failed service status check: " + ex.getMessage());
                }
            }
        }, "service-status-thread");

        t.setDaemon(true);
        t.start();
    }

    private void startFakePorts() {
        if (fakePortsProcess != null && fakePortsProcess.isAlive()) {
            log("[INFO] Fake ports already running.");
            return;
        }

        String fakeIn = fakeInField.getText().trim();
        String fakeOut = fakeOutField.getText().trim();
        if (fakeIn.isEmpty() || fakeOut.isEmpty()) {
            log("[ERROR] Fake port paths cannot be empty.");
            return;
        }

        ensureDebugMirrorConfig(fakeIn);

        String commandText = "socat -d -d pty,raw,echo=0,link=" + escape(fakeIn) + " pty,raw,echo=0,link=" + escape(fakeOut);
        log("[RUN] " + commandText);

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-lc", commandText);
                pb.directory(repoPath().toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                fakePortsProcess = process;
                updatePortButtons();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    log("[socat] " + line);
                }
            } catch (Exception ex) {
                log("[ERROR] Failed to start fake ports: " + ex.getMessage());
            } finally {
                fakePortsProcess = null;
                updatePortButtons();
            }
        }, "fake-port-thread");

        t.setDaemon(true);
        t.start();

        Timer delayedConnect = new Timer(900, e -> connectPreviewPort());
        delayedConnect.setRepeats(false);
        delayedConnect.start();

        Timer delayedProbe = new Timer(1700, e -> sendPreviewProbePacket(fakeIn));
        delayedProbe.setRepeats(false);
        delayedProbe.start();
    }

    private void ensureDebugMirrorConfig(String fakeIn) {
        Path configPath = repoPath().resolve("monitor_config.json");
        if (!Files.exists(configPath)) {
            log("[WARN] monitor_config.json not found at " + configPath + "; preview depends on Python debug settings.");
            return;
        }

        try {
            String text = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            String updated = text;

            if (updated.contains("\"debug_enabled\"")) {
                updated = updated.replaceAll("\\\"debug_enabled\\\"\\s*:\\s*(true|false)", "\"debug_enabled\": true");
            }
            if (updated.contains("\"debug_port\"")) {
                updated = updated.replaceAll("\\\"debug_port\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\"debug_port\": \"" + escapeJson(fakeIn) + "\"");
            }

            if (!updated.equals(text)) {
                Files.write(configPath, updated.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Updated monitor_config.json: debug_enabled=true, debug_port=" + fakeIn);
                log("[INFO] Restart service to apply debug mirror settings if monitor is running as a service.");
            }
        } catch (Exception ex) {
            log("[WARN] Could not update monitor_config.json for debug mirror: " + ex.getMessage());
        }
    }

    private void sendPreviewProbePacket(String fakeInPath) {
        try {
            SerialPort probePort = SerialPort.getCommPort(fakeInPath);
            probePort.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            probePort.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 1000);

            if (!probePort.openPort()) {
                log("[WARN] Probe packet skipped: could not open fake input port " + fakeInPath);
                return;
            }

            try {
                String probe = "HEADER:Preview Test|CPU:12|RAM:34|DISK0:56|DISK1:12|GPU:8|HOST:debug|OS:linux|IP:127.0.0.1|UPTIME:1m\n";
                probePort.writeBytes(probe.getBytes(StandardCharsets.UTF_8), probe.length());
                log("[INFO] Sent preview probe packet to " + fakeInPath + " (confirms fake port wiring).\n"
                        + "      Python should write to input; preview reads output.");
            } finally {
                probePort.closePort();
            }
        } catch (Exception ex) {
            log("[WARN] Failed to send preview probe packet: " + ex.getMessage());
        }
    }

    private void stopFakePorts() {
        Process process = fakePortsProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            log("[INFO] Sent stop signal to fake ports process.");
        } else {
            log("[INFO] Fake ports are not running.");
        }
        updatePortButtons();
    }

    private void connectPreviewPort() {
        if (previewPort != null && previewPort.isOpen()) {
            log("[INFO] Preview port already connected.");
            return;
        }

        String portPath = fakeOutField.getText().trim();
        if (portPath.isEmpty()) {
            log("[ERROR] Output port path is empty.");
            return;
        }

        SerialPort port = SerialPort.getCommPort(portPath);
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 0);

        if (!port.openPort()) {
            log("[ERROR] Failed to open preview port: " + portPath);
            return;
        }

        previewPort = port;
        previewReading = true;
        updatePreviewButtons();
        log("[INFO] Connected preview stream to " + portPath);

        previewReaderThread = new Thread(() -> readPreviewLoop(port), "preview-reader-thread");
        previewReaderThread.setDaemon(true);
        previewReaderThread.start();
    }

    private void disconnectPreviewPort() {
        previewReading = false;
        SerialPort port = previewPort;
        previewPort = null;

        if (port != null && port.isOpen()) {
            port.closePort();
        }

        updatePreviewButtons();
        log("[INFO] Preview stream disconnected.");
    }

    private void readPreviewLoop(SerialPort port) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8))) {
            while (previewReading && port.isOpen()) {
                String line;
                try {
                    line = reader.readLine();
                } catch (Exception ex) {
                    String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                    if (msg.contains("timed out")) {
                        continue;
                    }
                    log("[ERROR] Preview read error: " + ex.getMessage());
                    break;
                }

                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                JavaSerialFakeDisplay.ParsedPacket packet = JavaSerialFakeDisplay.ParsedPacket.parse(trimmed);
                SwingUtilities.invokeLater(() -> fakeDisplayPanel.updatePacket(packet));
            }
        } catch (Exception ex) {
            log("[ERROR] Preview stream failure: " + ex.getMessage());
        }

        SwingUtilities.invokeLater(this::disconnectPreviewPort);
    }

    private void runCommand(String shellCommand, File workingDirectory, String label, boolean needsSudo, boolean allowPrompt) {
        CommandSpec spec = buildShellCommand(shellCommand, needsSudo, allowPrompt);
        if (spec == null) {
            log("[WARN] " + label + " canceled (no sudo password). Running as root avoids this.");
            return;
        }

        log("[RUN] " + label + " in " + workingDirectory.getAbsolutePath());
        setActionButtons(false);

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(spec.command);
                pb.directory(workingDirectory);
                pb.redirectErrorStream(true);
                if (spec.sudoPassword != null) {
                    pb.environment().put("SUDO_PASS", spec.sudoPassword);
                }
                Process process = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    log("[" + label + "] " + line);
                }

                int code = process.waitFor();
                if (code == 0) {
                    log("[DONE] " + label + " completed successfully.");
                } else {
                    log("[FAIL] " + label + " exited with code " + code + ".");
                }
            } catch (Exception ex) {
                log("[ERROR] Command failed for " + label + ": " + ex.getMessage());
            } finally {
                setActionButtons(true);
            }
        }, "cmd-" + label);

        t.setDaemon(true);
        t.start();
    }

    private CommandSpec buildShellCommand(String shellCommand, boolean needsSudo, boolean allowPrompt) {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add("-lc");

        if (!needsSudo || runningAsRoot()) {
            command.add(shellCommand);
            return new CommandSpec(command, null);
        }

        String password = resolveSudoPassword(allowPrompt);
        if (password == null || password.isEmpty()) {
            return null;
        }

        String wrapped = "printf '%s\\n' \"$SUDO_PASS\" | sudo -S -p '' bash -lc " + escape(shellCommand);
        command.add(wrapped);
        return new CommandSpec(command, password);
    }

    private String resolveSudoPassword(boolean allowPrompt) {
        String fromField = new String(sudoPasswordField.getPassword());
        if (!fromField.trim().isEmpty()) {
            return fromField;
        }
        if (!allowPrompt) {
            return null;
        }

        JPasswordField passwordField = new JPasswordField();
        int result = JOptionPane.showConfirmDialog(
                this,
                passwordField,
                "Enter sudo password",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            String value = new String(passwordField.getPassword());
            sudoPasswordField.setText(value);
            return value;
        }
        return null;
    }

    private boolean runningAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    private void setActionButtons(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            installButton.setEnabled(enabled);
            uninstallButton.setEnabled(enabled);
            updateButton.setEnabled(enabled);
            flashButton.setEnabled(enabled);
            launchDebugButton.setEnabled(enabled);
            serviceOnButton.setEnabled(enabled);
            serviceOffButton.setEnabled(enabled);
            serviceRestartButton.setEnabled(enabled);
            serviceStatusButton.setEnabled(enabled);
        });
    }

    private void updatePortButtons() {
        SwingUtilities.invokeLater(() -> {
            boolean running = fakePortsProcess != null && fakePortsProcess.isAlive();
            startFakePortsButton.setEnabled(!running);
            stopFakePortsButton.setEnabled(running);
        });
        updatePreviewButtons();
    }

    private void updatePreviewButtons() {
        SwingUtilities.invokeLater(() -> {
            boolean connected = previewPort != null && previewPort.isOpen();
            connectPreviewButton.setEnabled(!connected);
            disconnectPreviewButton.setEnabled(connected);
        });
    }

    private void setServiceIndicator(String label, Color color) {
        SwingUtilities.invokeLater(() -> {
            serviceIndicator.setText(label);
            serviceIndicator.setBackground(color);
            serviceIndicator.setForeground(Color.WHITE);
        });
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            outputArea.append(message + "\n");
            outputArea.setCaretPosition(outputArea.getDocument().getLength());
        });
    }

    private Path repoPath() {
        return Paths.get(repoField.getText().trim());
    }

    private String escape(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Path detectRepoRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path found = findRepoWithInstallScript(current);
        if (found != null) {
            return found;
        }

        Path fromClass = Paths.get(".").toAbsolutePath();
        found = findRepoWithInstallScript(fromClass);
        if (found != null) {
            return found;
        }

        return current;
    }

    private Path findRepoWithInstallScript(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.exists(cursor.resolve("install.sh")) && Files.exists(cursor.resolve("README.md"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new UniversalMonitorControlCenter().setVisible(true));
    }

    private static class CommandSpec {
        final List<String> command;
        final String sudoPassword;

        CommandSpec(List<String> command, String sudoPassword) {
            this.command = command;
            this.sudoPassword = sudoPassword;
        }
    }
}
