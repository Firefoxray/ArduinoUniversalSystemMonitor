import com.fazecast.jSerialComm.SerialPort;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;

public class UniversalMonitorControlCenter extends JFrame {

    private static final String SERVICE_NAME = "arduino-monitor.service";
    private static final String APP_NAME = "Universal Arduino System Monitor - Control Center";
    private static final String APP_VERSION = loadAppVersion();
    private static final String SUDO_PASSWORD_FILE = ".control_center_sudo_password";

    private final JTextField repoField = new JTextField(40);
    private final JPasswordField sudoPasswordField = new JPasswordField(18);
    private final JCheckBox rememberPasswordToggle = new JCheckBox("Remember Password");
    private final JButton clearSavedPasswordButton = new JButton("Clear Saved Password");

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
    private final JButton flashButton = new JButton("Flash Arduino with Included Program");
    private final JButton customFlashButton = new JButton("Upload Custom Sketch");

    private final JButton serviceOnButton = new JButton("Service On");
    private final JButton serviceOffButton = new JButton("Service Off");
    private final JButton serviceRestartButton = new JButton("Service Restart");
    private final JButton serviceStatusButton = new JButton("Service Status");

    private final JButton debugOnButton = new JButton("Enable Debug Mode");
    private final JButton debugOffButton = new JButton("Disable Debug Mode");
    private final JButton debugRefreshButton = new JButton("Refresh Debug Status");

    private final JLabel serviceIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel debugIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel versionLabel = new JLabel("Version " + APP_VERSION);
    private final JCheckBox lightModeToggle = new JCheckBox("Light mode");
    private final JLabel customSketchIndicator = new JLabel("No sketch selected");

    private final JavaSerialFakeDisplay.FakeDisplayPanel fakeDisplayPanel = new JavaSerialFakeDisplay.FakeDisplayPanel();

    private final Color darkBackground = new Color(23, 39, 66);
    private final Color darkPanelBackground = new Color(34, 54, 86);
    private final Color darkAccent = new Color(92, 143, 214);
    private final Color darkText = new Color(232, 240, 252);
    private final Color darkFieldBackground = new Color(18, 29, 48);
    private final Color darkButtonBackground = new Color(70, 109, 171);
    private final Color lightBackground = new Color(236, 242, 252);
    private final Color lightPanelBackground = new Color(248, 251, 255);
    private final Color lightAccent = new Color(125, 160, 219);
    private final Color lightText = new Color(31, 46, 71);
    private final Color lightFieldBackground = Color.WHITE;
    private final Color lightButtonBackground = new Color(214, 226, 245);

    private boolean darkMode;

    private volatile Process fakePortsProcess;
    private volatile SerialPort previewPort;
    private volatile boolean previewReading;
    private Thread previewReaderThread;
    private Boolean lastDebugEnabledState;
    private boolean debugStatusMissingLogged;
    private Path selectedCustomSketchPath;


    public UniversalMonitorControlCenter() {
        super(APP_NAME + " v" + APP_VERSION);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1500, 940);
        setLocationRelativeTo(null);

        darkMode = shouldUseDarkModeByDefault();

        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        repoField.setText(detectRepoRoot().toString());
        sudoPasswordField.setToolTipText("Optional: sudo password used for installer/update/flash/service controls when not root");
        rememberPasswordToggle.setToolTipText("Stores the sudo password in the local " + SUDO_PASSWORD_FILE + " file inside the repo. Git ignores that file so it stays on this machine.");
        clearSavedPasswordButton.setToolTipText("Deletes the saved sudo password file from this repo.");

        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.add(buildRepoPanel(), BorderLayout.NORTH);
        topPanel.add(buildActionPanel(), BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.62);
        splitPane.setLeftComponent(buildPreviewPanel());
        splitPane.setRightComponent(buildOutputPanel());

        add(topPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);

        lightModeToggle.setSelected(!darkMode);

        wireActions();
        applyTheme();
        updatePortButtons();
        setServiceIndicator("UNKNOWN", Color.GRAY);
        setDebugIndicator("UNKNOWN", Color.GRAY);
        loadSavedSudoPassword();

        Timer serviceTimer = new Timer(7000, e -> {
            refreshServiceStatus(false);
            refreshDebugStatus(false);
        });
        serviceTimer.setRepeats(true);
        serviceTimer.start();
        refreshServiceStatus(false);
        refreshDebugStatus(false);
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
        rememberPasswordToggle.setFocusable(false);
        panel.add(rememberPasswordToggle);
        panel.add(clearSavedPasswordButton);
        versionLabel.setFont(versionLabel.getFont().deriveFont(Font.BOLD));
        panel.add(Box.createHorizontalStrut(12));
        panel.add(versionLabel);
        panel.add(Box.createHorizontalStrut(18));
        lightModeToggle.setFocusable(false);
        panel.add(lightModeToggle);

        return panel;
    }

    private JPanel buildActionPanel() {
        JPanel panel = new JPanel(new GridLayout(4, 1, 10, 10));

        JPanel appActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        appActions.setBorder(BorderFactory.createTitledBorder("Linux App Management (uses sudo when needed)"));
        appActions.add(installButton);
        appActions.add(uninstallButton);
        appActions.add(updateButton);
        appActions.add(flashButton);
        appActions.add(customFlashButton);
        customSketchIndicator.setBorder(new EmptyBorder(0, 8, 0, 0));
        customSketchIndicator.setToolTipText("Shows the currently selected custom sketch folder.");
        appActions.add(customSketchIndicator);

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

        JPanel debugPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        debugPanel.setBorder(BorderFactory.createTitledBorder("Python Debug Mirror"));
        debugIndicator.setOpaque(true);
        debugIndicator.setPreferredSize(new Dimension(100, 30));
        debugPanel.add(debugOnButton);
        debugPanel.add(debugOffButton);
        debugPanel.add(debugRefreshButton);
        debugPanel.add(new JLabel("Indicator:"));
        debugPanel.add(debugIndicator);

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
        panel.add(debugPanel);
        panel.add(fakePorts);
        return panel;
    }

    private JPanel buildPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Arduino Preview (live from output port)"));
        panel.add(fakeDisplayPanel, BorderLayout.CENTER);

        JLabel helper = new JLabel("Preview listens live to the Output port path used by the fake serial pair.");
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
        updateButton.addActionListener(e -> runUpdateWorkflow());
        flashButton.addActionListener(e -> runRepoScript("arduino_install.sh", true));
        customFlashButton.addActionListener(e -> uploadCustomSketch());
        clearSavedPasswordButton.addActionListener(e -> clearSavedSudoPassword(true));

        serviceOnButton.addActionListener(e -> runServiceCommand("start"));
        serviceOffButton.addActionListener(e -> runServiceCommand("stop"));
        serviceRestartButton.addActionListener(e -> runServiceCommand("restart"));
        serviceStatusButton.addActionListener(e -> refreshServiceStatus(true));

        debugOnButton.addActionListener(e -> setDebugMode(true));
        debugOffButton.addActionListener(e -> setDebugMode(false));
        debugRefreshButton.addActionListener(e -> refreshDebugStatus(true));

        startFakePortsButton.addActionListener(e -> startFakePorts());
        stopFakePortsButton.addActionListener(e -> stopFakePorts());
        connectPreviewButton.addActionListener(e -> connectPreviewPort());
        disconnectPreviewButton.addActionListener(e -> disconnectPreviewPort());
        lightModeToggle.addActionListener(e -> {
            darkMode = !lightModeToggle.isSelected();
            applyTheme();
        });
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

    private void runUpdateWorkflow() {
        Path repo = repoPath();
        Path launcher = repo.resolve("UniversalMonitorControlCenter.sh");
        String command = "cd " + escape(repo.toString()) + " && chmod +x update.sh UniversalMonitorControlCenter.sh && ./update.sh";
        runCommand(command, repo.toFile(), "Update and restart Control Center", true, true, () -> relaunchApplication(launcher));
    }

    private void runRepoScript(String scriptName, boolean needsSudo) {
        Path script = repoPath().resolve(scriptName);
        if (!Files.exists(script)) {
            log("[ERROR] Missing script: " + script);
            return;
        }

        String command = buildRepoScriptCommand(scriptName);
        if (command == null) {
            return;
        }
        runCommand(command, repoPath().toFile(), scriptName, needsSudo, true);
    }

    private String buildRepoScriptCommand(String scriptName) {
        StringBuilder command = new StringBuilder();
        command.append("cd ").append(escape(repoPath().toString()))
                .append(" && chmod +x ").append(escape(scriptName))
                .append(" && ");

        if ("arduino_install.sh".equals(scriptName)) {
            String unoScreenSize = resolveUnoR3ScreenSizeForControlCenter();
            if ("".equals(unoScreenSize)) {
                return null;
            }
            if (unoScreenSize != null) {
                command.append("UNO_R3_SCREEN_SIZE=").append(escape(unoScreenSize)).append(' ');
            }
        }

        command.append("./").append(scriptName);
        return command.toString();
    }

    private String resolveUnoR3ScreenSizeForControlCenter() {
        List<DetectedBoard> boards = detectConnectedBoards();
        long unoR3Count = boards.stream()
                .filter(board -> "arduino:avr:uno".equals(board.fqbn))
                .count();

        if (unoR3Count <= 1) {
            if (unoR3Count == 1) {
                log("[INFO] Exactly one Arduino UNO R3 detected; forcing the default 2.8\" TFT sketch.");
                return "28";
            }
            return null;
        }

        String[] options = {"2.8\" TFT shield", "3.5\" TFT shield"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "Two Arduino UNO R3 boards were detected.\n"
                        + "Choose which sketch to use for this flashing run.\n\n"
                        + "If you need different R3 shield sizes flashed separately, unplug one R3 and rerun the flasher.",
                "Select UNO R3 TFT Size",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == 0) {
            log("[INFO] Control Center selected the 2.8\" TFT sketch for the detected Arduino UNO R3 boards.");
            return "28";
        }
        if (choice == 1) {
            log("[INFO] Control Center selected the 3.5\" TFT sketch for the detected Arduino UNO R3 boards.");
            return "35";
        }

        log("[INFO] Arduino flashing canceled before upload because no UNO R3 TFT size was selected.");
        return "";
    }

    private void runServiceCommand(String action) {
        String label = "service " + action;
        String command = "systemctl " + action + " " + SERVICE_NAME;
        runCommand(command, repoPath().toFile(), label, true, true);

        Timer delayed = new Timer(1200, e -> refreshServiceStatus(false));
        delayed.setRepeats(false);
        delayed.start();
    }

    private boolean ensureSocatInstalled() {
        if (commandExists("socat")) {
            return true;
        }

        log("[WARN] socat is required before fake ports can be started.");
        int choice = JOptionPane.showConfirmDialog(
                this,
                "socat is required before you can start fake ports. Install it now?",
                "Install socat",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (choice != JOptionPane.YES_OPTION) {
            log("[WARN] Fake ports were not started because socat is not installed.");
            return false;
        }

        runCommand(buildSocatInstallCommand(), repoPath().toFile(), "Install socat", true, true, () -> {
            if (commandExists("socat")) {
                SwingUtilities.invokeLater(this::startFakePorts);
            } else {
                log("[ERROR] socat installation completed but the command is still unavailable.");
            }
        });
        return false;
    }

    private boolean commandExists(String commandName) {
        try {
            Process process = new ProcessBuilder("bash", "-lc", "command -v " + escape(commandName) + " >/dev/null 2>&1")
                    .directory(repoPath().toFile())
                    .start();
            return process.waitFor() == 0;
        } catch (Exception ex) {
            log("[WARN] Failed to verify dependency '" + commandName + "': " + ex.getMessage());
            return false;
        }
    }

    private String buildSocatInstallCommand() {
        return "if command -v socat >/dev/null 2>&1; then "
                + "echo 'socat is already installed.'; "
                + "elif [ -f /etc/fedora-release ]; then dnf install -y socat; "
                + "elif [ -f /etc/debian_version ]; then apt update && apt install -y socat; "
                + "elif [ -f /etc/arch-release ]; then pacman -Sy --noconfirm socat; "
                + "else echo 'Unsupported distro: please install socat manually.'; exit 1; fi";
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
        if (!ensureSocatInstalled()) {
            return;
        }

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

    private void setDebugMode(boolean enabled) {
        String fakeIn = fakeInField.getText().trim();
        if (fakeIn.isEmpty()) {
            log("[ERROR] Debug mode toggle failed: fake input path is empty.");
            return;
        }

        if (!updateDebugConfig(enabled, fakeIn, true)) {
            return;
        }

        setDebugIndicator(enabled ? "ON" : "OFF", enabled ? new Color(24, 170, 24) : new Color(190, 35, 35));
        runServiceCommand("restart");
    }

    private void refreshDebugStatus(boolean verbose) {
        Path configPath = repoPath().resolve("monitor_config.json");
        if (!Files.exists(configPath)) {
            setDebugIndicator("UNKNOWN", Color.GRAY);
            lastDebugEnabledState = null;
            if (verbose || !debugStatusMissingLogged) {
                log("[WARN] Cannot refresh debug status: monitor_config.json not found at " + configPath);
                debugStatusMissingLogged = true;
            }
            return;
        }

        debugStatusMissingLogged = false;

        try {
            String text = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            boolean enabled = text.matches("(?s).*\"debug_enabled\"\s*:\s*true.*");
            setDebugIndicator(enabled ? "ON" : "OFF", enabled ? new Color(24, 170, 24) : new Color(190, 35, 35));

            if (verbose || lastDebugEnabledState == null || lastDebugEnabledState != enabled) {
                log("[INFO] Debug mode is " + (enabled ? "enabled" : "disabled") + " in monitor_config.json");
            }
            lastDebugEnabledState = enabled;
        } catch (Exception ex) {
            setDebugIndicator("UNKNOWN", Color.GRAY);
            lastDebugEnabledState = null;
            log("[WARN] Failed to refresh debug status: " + ex.getMessage());
        }
    }

    private boolean updateDebugConfig(boolean enabled, String fakeIn, boolean applyPort) {
        Path configPath = repoPath().resolve("monitor_config.json");
        if (!Files.exists(configPath)) {
            log("[WARN] monitor_config.json not found at " + configPath);
            return false;
        }

        try {
            String text = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            String updated = text;

            if (updated.contains("\"debug_enabled\"")) {
                updated = updated.replaceAll("\\\"debug_enabled\\\"\\s*:\\s*(true|false)", "\"debug_enabled\": " + (enabled ? "true" : "false"));
            }
            if (applyPort && updated.contains("\"debug_port\"")) {
                updated = updated.replaceAll("\\\"debug_port\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\"debug_port\": \"" + escapeJson(fakeIn) + "\"");
            }

            if (!updated.equals(text)) {
                Files.write(configPath, updated.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Updated monitor_config.json: debug_enabled=" + enabled + (applyPort ? ", debug_port=" + fakeIn : ""));
            } else {
                log("[INFO] monitor_config.json already had requested debug settings.");
            }
            return true;
        } catch (Exception ex) {
            log("[WARN] Could not update monitor_config.json debug settings: " + ex.getMessage());
            return false;
        }
    }

    private void ensureDebugMirrorConfig(String fakeIn) {
        if (updateDebugConfig(true, fakeIn, true)) {
            setDebugIndicator("ON", new Color(24, 170, 24));
            log("[INFO] Restarting service so debug mirror changes apply immediately.");
            runServiceCommand("restart");
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



    private void applyTheme() {
        SwingUtilities.invokeLater(() -> {
            Color background = darkMode ? darkBackground : lightBackground;
            Color panelBackground = darkMode ? darkPanelBackground : lightPanelBackground;
            Color textColor = darkMode ? darkText : lightText;
            Color accent = darkMode ? darkAccent : lightAccent;
            Color fieldBackground = darkMode ? darkFieldBackground : lightFieldBackground;
            Color buttonBackground = darkMode ? darkButtonBackground : lightButtonBackground;

            getContentPane().setBackground(background);
            styleComponentTree(getContentPane(), background, panelBackground, textColor, accent, fieldBackground, buttonBackground);
            outputArea.setCaretColor(textColor);
            versionLabel.setForeground(accent);
            lightModeToggle.setForeground(textColor);
            lightModeToggle.setBackground(panelBackground);
            updateCustomSketchIndicatorAppearance(accent, textColor);
            fakeDisplayPanel.setBorder(new LineBorder(accent, 1, true));

            repaint();
        });
    }

    private void styleComponentTree(Component component, Color background, Color panelBackground, Color textColor, Color accent,
                                    Color fieldBackground, Color buttonBackground) {
        if (component instanceof JPanel panel) {
            panel.setOpaque(true);
            panel.setBackground(panelBackground);
            if (panel.getBorder() instanceof javax.swing.border.TitledBorder titledBorder) {
                titledBorder.setTitleColor(textColor);
            }
        } else if (component instanceof JSplitPane splitPane) {
            splitPane.setBackground(background);
            splitPane.setBorder(BorderFactory.createLineBorder(accent));
        } else if (component instanceof JScrollPane scrollPane) {
            scrollPane.getViewport().setBackground(fieldBackground);
            scrollPane.setBorder(BorderFactory.createLineBorder(accent));
            scrollPane.setBackground(panelBackground);
        } else if (component instanceof JTextArea area) {
            area.setBackground(fieldBackground);
            area.setForeground(textColor);
            area.setSelectionColor(accent.darker());
            area.setSelectedTextColor(Color.WHITE);
            area.setBorder(new EmptyBorder(6, 6, 6, 6));
        } else if (component instanceof JPasswordField field) {
            field.setBackground(fieldBackground);
            field.setForeground(textColor);
            field.setCaretColor(textColor);
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(accent),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)
            ));
        } else if (component instanceof JTextField field) {
            field.setBackground(fieldBackground);
            field.setForeground(textColor);
            field.setCaretColor(textColor);
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(accent),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)
            ));
        } else if (component instanceof AbstractButton button) {
            button.setBackground(buttonBackground);
            button.setForeground(textColor);
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(accent),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
            ));
            button.setOpaque(true);
        } else if (component instanceof JLabel label) {
            if (label != serviceIndicator && label != debugIndicator) {
                label.setForeground(textColor);
            }
        } else if (component instanceof JComboBox<?> comboBox) {
            comboBox.setBackground(fieldBackground);
            comboBox.setForeground(textColor);
        }

        if (component instanceof JComponent jComponent) {
            jComponent.setForeground(textColor);
            if (!(jComponent instanceof JTextArea) && !(jComponent instanceof JTextField)
                    && !(jComponent instanceof JPasswordField) && !(jComponent instanceof JScrollPane)
                    && !(jComponent instanceof JSplitPane) && !(jComponent instanceof JPanel)
                    && !(jComponent instanceof AbstractButton)) {
                jComponent.setBackground(panelBackground);
            }
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                styleComponentTree(child, background, panelBackground, textColor, accent, fieldBackground, buttonBackground);
            }
        }
    }

    private boolean shouldUseDarkModeByDefault() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Object desktopPreference = Toolkit.getDefaultToolkit().getDesktopProperty("win.darkMode.on");
        if (desktopPreference instanceof Boolean preference) {
            return preference;
        }

        String gtkTheme = firstNonBlank(System.getenv("GTK_THEME"), System.getenv("GTK_THEME_VARIANT"));
        if (gtkTheme != null) {
            return gtkTheme.toLowerCase(Locale.ROOT).contains("dark");
        }

        if (osName.contains("linux")) {
            Boolean gnomePreference = queryGnomeDarkMode();
            if (gnomePreference != null) {
                return gnomePreference;
            }
        }

        if (osName.contains("mac")) {
            String appearance = System.getProperty("apple.awt.application.appearance", "");
            if (!appearance.isBlank()) {
                return appearance.toLowerCase(Locale.ROOT).contains("dark");
            }
        }

        String colorScheme = firstNonBlank(System.getenv("COLORFGBG"), System.getenv("XDG_CURRENT_DESKTOP"));
        return colorScheme != null && colorScheme.toLowerCase(Locale.ROOT).contains("dark");
    }

    private Boolean queryGnomeDarkMode() {
        String[] commands = {
                "gsettings get org.gnome.desktop.interface color-scheme",
                "gsettings get org.gnome.desktop.interface gtk-theme"
        };

        for (String command : commands) {
            try {
                Process process = new ProcessBuilder("bash", "-lc", command).start();
                String output;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    output = reader.readLine();
                }
                if (process.waitFor() == 0 && output != null && !output.isBlank()) {
                    return output.toLowerCase(Locale.ROOT).contains("dark");
                }
            } catch (Exception ignored) {
                // Fall back to env-based detection below.
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void uploadCustomSketch() {
        Path sketchPath = selectedCustomSketchPath;
        if (sketchPath == null || !Files.exists(sketchPath)) {
            sketchPath = chooseSketchPath();
            if (sketchPath == null) {
                log("[INFO] Custom sketch upload canceled.");
                return;
            }
        }
        setSelectedCustomSketchPath(sketchPath);

        List<DetectedBoard> boards = detectConnectedBoards();
        if (boards.isEmpty()) {
            log("[WARN] No supported Arduino boards were detected for custom upload.");
            return;
        }

        JComboBox<DetectedBoard> boardCombo = new JComboBox<>(boards.toArray(new DetectedBoard[0]));
        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.add(new JLabel("Sketch: " + sketchPath));
        panel.add(new JLabel("Target board / port:"));
        panel.add(boardCombo);

        int choice = JOptionPane.showConfirmDialog(
                this,
                panel,
                "Upload Custom Arduino Sketch",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            log("[INFO] Custom sketch upload canceled before flashing.");
            return;
        }

        DetectedBoard target = (DetectedBoard) boardCombo.getSelectedItem();
        if (target == null) {
            log("[ERROR] No target board selected for custom upload.");
            return;
        }

        String command = "cd " + escape(repoPath().toString())
                + " && arduino-cli compile --fqbn " + escape(target.fqbn) + " " + escape(sketchPath.toString())
                + " && sleep 2"
                + " && arduino-cli upload -p " + escape(target.port) + " --fqbn " + escape(target.fqbn) + " " + escape(sketchPath.toString());
        runCommand(command, repoPath().toFile(), "Upload custom sketch to " + target.port, true, true);
    }

    private Path chooseSketchPath() {
        JFileChooser chooser = new JFileChooser(repoField.getText());
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("Choose an Arduino sketch folder or .ino file");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return null;
        }
        Path path = selected.toPath().toAbsolutePath().normalize();
        if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".ino")) {
            path = path.getParent();
        }
        setSelectedCustomSketchPath(path);
        return path;
    }

    private void setSelectedCustomSketchPath(Path sketchPath) {
        selectedCustomSketchPath = sketchPath == null ? null : sketchPath.toAbsolutePath().normalize();
        updateCustomSketchIndicatorText();
    }

    private void updateCustomSketchIndicatorText() {
        String text = "No sketch selected";
        String toolTip = "Choose a custom sketch to enable quick re-uploads.";
        if (selectedCustomSketchPath != null) {
            Path fileName = selectedCustomSketchPath.getFileName();
            String label = fileName == null ? selectedCustomSketchPath.toString() : fileName.toString();
            text = "Selected: " + label;
            toolTip = selectedCustomSketchPath.toString();
        }
        customSketchIndicator.setText(text);
        customSketchIndicator.setToolTipText(toolTip);
    }

    private void updateCustomSketchIndicatorAppearance(Color accent, Color textColor) {
        customSketchIndicator.setForeground(selectedCustomSketchPath == null ? textColor : accent);
    }

    private List<DetectedBoard> detectConnectedBoards() {
        List<DetectedBoard> boards = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("bash", "-lc", "arduino-cli board list | tail -n +2")
                    .directory(repoPath().toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    String[] parts = trimmed.split("\\s+");
                    String port = parts.length > 0 ? parts[0] : "";
                    if (port.isEmpty()) {
                        continue;
                    }
                    if (trimmed.contains("Arduino UNO R4 WiFi")) {
                        boards.add(new DetectedBoard(port, "Arduino UNO R4 WiFi", "arduino:renesas_uno:unor4wifi"));
                    } else if (trimmed.contains("Mega 2560") || trimmed.contains("Arduino Mega")) {
                        boards.add(new DetectedBoard(port, "Arduino Mega 2560", "arduino:avr:mega"));
                    } else if (trimmed.contains("Arduino UNO")) {
                        boards.add(new DetectedBoard(port, "Arduino UNO R3", "arduino:avr:uno"));
                    }
                }
            }
            process.waitFor();
        } catch (Exception ex) {
            log("[WARN] Failed to detect connected boards for custom upload: " + ex.getMessage());
        }
        return boards;
    }

    private void runCommand(String shellCommand, File workingDirectory, String label, boolean needsSudo, boolean allowPrompt) {
        runCommand(shellCommand, workingDirectory, label, needsSudo, allowPrompt, null);
    }

    private void runCommand(String shellCommand, File workingDirectory, String label, boolean needsSudo, boolean allowPrompt, Runnable onSuccess) {
        CommandSpec spec = buildShellCommand(shellCommand, needsSudo, allowPrompt);
        if (spec == null) {
            log("[WARN] " + label + " canceled (no sudo password). Running as root avoids this.");
            return;
        }

        log("[RUN] " + label + " in " + workingDirectory.getAbsolutePath());
        setActionButtons(false);

        Thread t = new Thread(() -> {
            boolean success = false;
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
                    success = true;
                    log("[DONE] " + label + " completed successfully.");
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                } else {
                    log("[FAIL] " + label + " exited with code " + code + ".");
                }
            } catch (Exception ex) {
                log("[ERROR] Command failed for " + label + ": " + ex.getMessage());
            } finally {
                if (!(success && onSuccess != null)) {
                    setActionButtons(true);
                }
            }
        }, "cmd-" + label);

        t.setDaemon(true);
        t.start();
    }

    private void relaunchApplication(Path launcherPath) {
        try {
            if (!Files.exists(launcherPath)) {
                log("[ERROR] Restart launcher not found: " + launcherPath);
                SwingUtilities.invokeLater(() -> setActionButtons(true));
                return;
            }

            log("[INFO] Restarting Control Center using " + launcherPath);
            ProcessBuilder restartBuilder = new ProcessBuilder("bash", "-lc",
                    "cd " + escape(launcherPath.getParent().toString()) + " && chmod +x " + escape(launcherPath.getFileName().toString()) + " && nohup ./" + launcherPath.getFileName() + " >/tmp/universal-monitor-control-center.log 2>&1 &");
            restartBuilder.directory(launcherPath.getParent().toFile());
            restartBuilder.redirectErrorStream(true);
            restartBuilder.start();

            SwingUtilities.invokeLater(() -> {
                dispose();
                System.exit(0);
            });
        } catch (IOException ex) {
            log("[ERROR] Failed to restart Control Center: " + ex.getMessage());
            SwingUtilities.invokeLater(() -> setActionButtons(true));
        }
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
            persistSudoPasswordIfRequested(fromField);
            return fromField;
        }

        String saved = readSavedSudoPassword();
        if (saved != null && !saved.isBlank()) {
            sudoPasswordField.setText(saved);
            rememberPasswordToggle.setSelected(true);
            return saved;
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
            persistSudoPasswordIfRequested(value);
            return value;
        }
        return null;
    }

    private void loadSavedSudoPassword() {
        String saved = readSavedSudoPassword();
        if (saved == null || saved.isBlank()) {
            return;
        }
        sudoPasswordField.setText(saved);
        rememberPasswordToggle.setSelected(true);
        log("[INFO] Loaded sudo password from " + passwordFilePath());
    }

    private String readSavedSudoPassword() {
        Path path = passwordFilePath();
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            log("[WARN] Failed to read saved sudo password file: " + ex.getMessage());
            return null;
        }
    }

    private void persistSudoPasswordIfRequested(String password) {
        if (!rememberPasswordToggle.isSelected()) {
            return;
        }
        if (password == null || password.isBlank()) {
            return;
        }

        Path path = passwordFilePath();
        try {
            Files.writeString(
                    path,
                    password + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            restrictPasswordFilePermissions(path);
        } catch (IOException ex) {
            log("[WARN] Failed to save sudo password file: " + ex.getMessage());
        }
    }

    private void clearSavedSudoPassword(boolean clearField) {
        Path path = passwordFilePath();
        try {
            Files.deleteIfExists(path);
            rememberPasswordToggle.setSelected(false);
            if (clearField) {
                sudoPasswordField.setText("");
            }
            log("[INFO] Removed saved sudo password file: " + path);
        } catch (IOException ex) {
            log("[WARN] Failed to remove saved sudo password file: " + ex.getMessage());
        }
    }

    private void restrictPasswordFilePermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Ignore on non-POSIX filesystems.
        } catch (IOException ex) {
            log("[WARN] Failed to tighten password file permissions: " + ex.getMessage());
        }
    }

    private Path passwordFilePath() {
        return repoPath().resolve(SUDO_PASSWORD_FILE);
    }

    private static final class DetectedBoard {
        private final String port;
        private final String label;
        private final String fqbn;

        private DetectedBoard(String port, String label, String fqbn) {
            this.port = port;
            this.label = label;
            this.fqbn = fqbn;
        }

        @Override
        public String toString() {
            return label + " [" + port + "]";
        }
    }

    private static String loadAppVersion() {
        Properties properties = new Properties();
        try (var input = UniversalMonitorControlCenter.class.getResourceAsStream("/version.properties")) {
            if (input != null) {
                properties.load(input);
                String version = properties.getProperty("app.version", "8.5").trim();
                if (!version.isEmpty() && !version.contains("${")) {
                    return version;
                }
            }
        } catch (IOException ignored) {
            // Fall back to a safe default below.
        }
        return "8.5";
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
            customFlashButton.setEnabled(enabled);
            clearSavedPasswordButton.setEnabled(enabled);
            serviceOnButton.setEnabled(enabled);
            serviceOffButton.setEnabled(enabled);
            serviceRestartButton.setEnabled(enabled);
            serviceStatusButton.setEnabled(enabled);
            debugOnButton.setEnabled(enabled);
            debugOffButton.setEnabled(enabled);
            debugRefreshButton.setEnabled(enabled);
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

    private void setDebugIndicator(String label, Color color) {
        SwingUtilities.invokeLater(() -> {
            debugIndicator.setText(label);
            debugIndicator.setBackground(color);
            debugIndicator.setForeground(Color.WHITE);
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
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Error: Universal Monitor Control Center needs a graphical desktop session.");
            System.err.println("Start it from KDE/GNOME, or set DISPLAY/XAUTHORITY correctly before launching.");
            System.exit(1);
        }

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
