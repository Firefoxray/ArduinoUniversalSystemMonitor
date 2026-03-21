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
    private static final int TRANSPORT_SWITCH_DELAY_SECONDS = 8;
    private static final String DEFAULT_WIFI_PORT = "5000";
    private static final String DEFAULT_WIFI_BOARD_NAME = "R4_WIFI35";
    private static final String WIFI_MODE_AUTO_DISCOVERY = "Auto Discovery (UDP)";
    private static final String WIFI_MODE_MANUAL = "Manual / Fixed IP";

    private final JTextField repoField = new JTextField(40);
    private final JPasswordField sudoPasswordField = new JPasswordField(18);
    private final JCheckBox rememberPasswordToggle = new JCheckBox("Remember Password");
    private final JButton clearSavedPasswordButton = new JButton("Clear Saved Password");

    private final JTextField fakeInField = new JTextField("/tmp/fakearduino_in", 22);
    private final JTextField fakeOutField = new JTextField("/tmp/fakearduino_out", 22);

    private final JTextArea outputArea = new JTextArea();
    private final JTextArea settingsOutputArea = new JTextArea();

    private final JButton startFakePortsButton = new JButton("Start Fake Ports");
    private final JButton stopFakePortsButton = new JButton("Stop Fake Ports");
    private final JButton connectPreviewButton = new JButton("Connect Preview Port");
    private final JButton disconnectPreviewButton = new JButton("Disconnect Preview Port");

    private final JButton installButton = new JButton("Install on Linux");
    private final JButton uninstallButton = new JButton("Uninstall");
    private final JButton updateButton = new JButton("Update from GitHub");
    private final JButton flashButton = new JButton("Flash Arduino with Included Program");
    private final JButton customFlashButton = new JButton("Upload Custom Sketch");
    private final JButton wifiCredentialsButton = new JButton("Set R4 Wi-Fi Credentials");

    private final JButton serviceOnButton = new JButton("Service On");
    private final JButton serviceOffButton = new JButton("Service Off");
    private final JButton serviceRestartButton = new JButton("Service Restart");
    private final JButton serviceStatusButton = new JButton("Service Status");

    private final JButton debugOnButton = new JButton("Enable Debug Mode");
    private final JButton debugOffButton = new JButton("Disable Debug Mode");
    private final JButton debugRefreshButton = new JButton("Refresh Debug Status");
    private final JButton wifiModeOnButton = new JButton("Enable Wi-Fi Mode");
    private final JButton wifiModeOffButton = new JButton("Use USB Only");
    private final JButton wifiModeRefreshButton = new JButton("Refresh Transport");

    private final JLabel serviceIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel debugIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel transportIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel versionLabel = new JLabel("Version " + APP_VERSION);
    private final JCheckBox lightModeToggle = new JCheckBox("Light mode");
    private final JComboBox<String> unoR3ScreenSizeSelector = new JComboBox<>(new String[]{"2.8\" mode", "3.5\" mode"});
    private final JComboBox<String> arduinoPortSelector = new JComboBox<>();
    private final JComboBox<String> wifiConnectionModeSelector = new JComboBox<>(new String[]{WIFI_MODE_AUTO_DISCOVERY, WIFI_MODE_MANUAL});
    private final JTextField wifiPortField = new JTextField(6);
    private final JTextField wifiHostField = new JTextField(14);
    private final JTextField wifiBoardNameField = new JTextField(14);
    private final JTextField wifiTargetHostField = new JTextField(14);
    private final JTextField wifiTargetHostnameField = new JTextField(14);
    private final JLabel wifiPortSourceLabel = new JLabel("Effective source: unknown");
    private final JButton refreshMonitorPortsButton = new JButton("Refresh Port List");
    private final JButton loadMonitorSettingsButton = new JButton("Load Monitor Settings");
    private final JButton saveMonitorSettingsButton = new JButton("Save Monitor Settings & Flash R4 WiFi");
    private final JLabel customSketchIndicator = new JLabel("No sketch selected");
    private final JLabel wifiCredentialsIndicator = new JLabel("Credentials not saved");

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
    private Boolean lastWifiEnabledState;
    private boolean debugStatusMissingLogged;
    private boolean transportStatusMissingLogged;
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
        flashButton.setToolTipText("Builds and uploads the repo's included monitor sketch for the detected board.");
        customFlashButton.setToolTipText("Lets you choose a local .ino or sketch folder and upload that custom sketch.");
        wifiCredentialsButton.setToolTipText("Saves the SSID and password into the local R4 Wi-Fi sketch config before flashing.");
        debugRefreshButton.setToolTipText("Re-checks whether the Python debug mirror mode is currently enabled.");
        wifiModeRefreshButton.setToolTipText("Re-checks whether the monitor is currently using Wi-Fi mode or USB-only mode.");
        startFakePortsButton.setToolTipText("Creates a linked fake serial port pair for testing the preview without hardware.");
        connectPreviewButton.setToolTipText("Connects the built-in preview window to the fake output serial port.");
        unoR3ScreenSizeSelector.setToolTipText("Choose which sketch size to flash onto every detected Arduino UNO R3. R4 boards are not affected.");
        arduinoPortSelector.setToolTipText("Sets the monitor's preferred Arduino serial port. Use AUTO to let the monitor auto-detect.");
        wifiPortField.setToolTipText("Sets the monitor TCP port used for Wi-Fi transport and discovery fallback.");
        wifiConnectionModeSelector.setToolTipText("Choose whether this PC discovers R4 Wi-Fi boards automatically or connects to a fixed board IP/hostname.");
        wifiHostField.setToolTipText("Used when Manual / Fixed IP mode is selected. Enter the intended Arduino IP or hostname for this PC.");
        wifiBoardNameField.setToolTipText("Optional board name flashed into the R4 Wi-Fi sketch and used during discovery matching.");
        wifiTargetHostField.setToolTipText("Optional intended computer IP/host identity flashed into the R4 Wi-Fi sketch for pairing.");
        wifiTargetHostnameField.setToolTipText("Optional intended computer hostname flashed into the R4 Wi-Fi sketch for pairing.");
        refreshMonitorPortsButton.setToolTipText("Re-detects currently connected Arduino serial ports for the selector.");
        loadMonitorSettingsButton.setToolTipText("Loads the current monitor port settings from the default/shared/local monitor config files.");
        saveMonitorSettingsButton.setToolTipText("Saves machine-local serial/TCP/connection-mode settings, mirrors the Wi-Fi port/pairing values into wifi_config.local.h, stops the monitor service, flashes every detected R4 WiFi board with that same local header, and starts the service again.");

        JTabbedPane mainTabs = buildMainTabs();

        add(mainTabs, BorderLayout.CENTER);

        lightModeToggle.setSelected(!darkMode);

        wireActions();
        applyTheme();
        updatePortButtons();
        setServiceIndicator("UNKNOWN", Color.GRAY);
        setDebugIndicator("UNKNOWN", Color.GRAY);
        loadSavedSudoPassword();
        settingsOutputArea.setDocument(outputArea.getDocument());
        refreshMonitorConnectionSettings(false);
        refreshWifiCredentialsIndicator(false);

        Timer serviceTimer = new Timer(7000, e -> {
            refreshServiceStatus(false);
            refreshDebugStatus(false);
            refreshTransportModeStatus(false);
        });
        serviceTimer.setRepeats(true);
        serviceTimer.start();
        refreshServiceStatus(false);
        refreshDebugStatus(false);
        refreshTransportModeStatus(false);
    }

    private JPanel buildRepoPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
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

        return panel;
    }

    private JTabbedPane buildMainTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Dashboard", buildDashboardTab());
        tabs.addTab("Settings", buildSettingsTab());
        return tabs;
    }

    private JPanel buildDashboardTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.add(buildQuickStatusPanel(), BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.62);
        splitPane.setLeftComponent(buildPreviewPanel());
        splitPane.setRightComponent(buildOutputPanel());
        panel.add(splitPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildSettingsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(buildRepoPanel());
        content.add(Box.createVerticalStrut(10));
        content.add(buildDisplayAndFlashPanel());
        content.add(Box.createVerticalStrut(10));
        content.add(buildAppManagementPanel());
        content.add(Box.createVerticalStrut(10));
        content.add(buildMonitorSettingsPanel());
        content.add(Box.createVerticalStrut(10));
        content.add(buildPreviewControlsPanel());
        content.add(Box.createVerticalStrut(10));
        content.add(buildSettingsOutputPanel());

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildQuickStatusPanel() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 10, 10));
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

        JPanel transportPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        transportPanel.setBorder(BorderFactory.createTitledBorder("Monitor Transport Mode"));
        transportIndicator.setOpaque(true);
        transportIndicator.setPreferredSize(new Dimension(120, 30));
        transportPanel.add(wifiModeOnButton);
        transportPanel.add(wifiModeOffButton);
        transportPanel.add(wifiModeRefreshButton);
        transportPanel.add(new JLabel("Indicator:"));
        transportPanel.add(transportIndicator);

        panel.add(servicePanel);
        panel.add(debugPanel);
        panel.add(transportPanel);
        return panel;
    }

    private JPanel buildDisplayAndFlashPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Display / Flash Settings"));
        lightModeToggle.setFocusable(false);
        panel.add(lightModeToggle);
        panel.add(new JLabel("UNO R3 mode:"));
        unoR3ScreenSizeSelector.setSelectedIndex(0);
        panel.add(unoR3ScreenSizeSelector);
        panel.add(flashButton);
        panel.add(customFlashButton);
        customSketchIndicator.setBorder(new EmptyBorder(0, 8, 0, 0));
        customSketchIndicator.setToolTipText("Shows the currently selected custom sketch folder.");
        panel.add(customSketchIndicator);
        panel.add(wifiCredentialsButton);
        wifiCredentialsIndicator.setBorder(new EmptyBorder(0, 8, 0, 0));
        wifiCredentialsIndicator.setOpaque(true);
        wifiCredentialsIndicator.setHorizontalAlignment(SwingConstants.CENTER);
        wifiCredentialsIndicator.setPreferredSize(new Dimension(150, wifiCredentialsIndicator.getPreferredSize().height + 6));
        panel.add(wifiCredentialsIndicator);
        return panel;
    }

    private JPanel buildAppManagementPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Linux App Management (uses sudo when needed)"));
        panel.add(installButton);
        panel.add(uninstallButton);
        panel.add(updateButton);
        return panel;
    }

    private JPanel buildPreviewControlsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Preview / Virtual Serial Ports (test bench only)"));
        panel.add(new JLabel("Input:"));
        panel.add(fakeInField);
        panel.add(new JLabel("Output:"));
        panel.add(fakeOutField);
        panel.add(startFakePortsButton);
        panel.add(stopFakePortsButton);
        panel.add(connectPreviewButton);
        panel.add(disconnectPreviewButton);
        return panel;
    }

    private JPanel buildMonitorSettingsPanel() {
        JPanel monitorSettingsPanel = new JPanel();
        monitorSettingsPanel.setLayout(new BoxLayout(monitorSettingsPanel, BoxLayout.Y_AXIS));
        monitorSettingsPanel.setBorder(BorderFactory.createTitledBorder("Physical Arduino Monitor Connection Settings"));

        JPanel controlsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        arduinoPortSelector.setEditable(true);
        arduinoPortSelector.setPreferredSize(new Dimension(170, arduinoPortSelector.getPreferredSize().height));
        controlsRow.add(new JLabel("Arduino USB Port:"));
        controlsRow.add(arduinoPortSelector);
        controlsRow.add(refreshMonitorPortsButton);
        controlsRow.add(Box.createHorizontalStrut(12));
        controlsRow.add(new JLabel("Arduino Wi-Fi TCP Port:"));
        controlsRow.add(wifiPortField);
        controlsRow.add(new JLabel("Connection Mode:"));
        controlsRow.add(wifiConnectionModeSelector);
        controlsRow.add(new JLabel("Wi-Fi Host/IP:"));
        controlsRow.add(wifiHostField);
        controlsRow.add(new JLabel("Board Name:"));
        controlsRow.add(wifiBoardNameField);
        controlsRow.add(new JLabel("Target Host/IP:"));
        controlsRow.add(wifiTargetHostField);
        controlsRow.add(new JLabel("Target Hostname:"));
        controlsRow.add(wifiTargetHostnameField);
        wifiPortSourceLabel.setBorder(new EmptyBorder(0, 4, 0, 0));
        controlsRow.add(wifiPortSourceLabel);
        controlsRow.add(loadMonitorSettingsButton);
        controlsRow.add(saveMonitorSettingsButton);
        monitorSettingsPanel.add(controlsRow);

        JLabel helper = new JLabel("<html>Writes <b>monitor_config.local.json</b> + <b>R4_WIFI35/wifi_config.local.h</b><br>"
                + "Choose <b>Auto Discovery (UDP)</b> to search for boards automatically, or <b>Manual / Fixed IP</b> to require a specific <b>wifi_host</b> on this PC.<br>"
                + "Board name / target host values are also mirrored into <b>wifi_config.local.h</b> so discovery can match each PC with the intended board.<br>"
                + "Important: <b>Save Monitor Settings & Flash R4 WiFi</b> reflashes every detected UNO R4 WiFi with the same local header, so for unique per-board names/targets you should flash one R4 at a time.<br>"
                + "Environment override <b>ARDUINO_MONITOR_WIFI_PORT</b> wins at runtime; otherwise local JSON, then shared JSON, then flashed header values are used.<br>"
                + "For an immediate port or pairing change, keep those layers aligned, then stop the service, flash the R4 WiFi sketch, and start the monitor again.</html>");
        helper.setFont(helper.getFont().deriveFont(helper.getFont().getSize2D() - 1f));
        helper.setBorder(new EmptyBorder(0, 10, 8, 10));
        helper.setAlignmentX(Component.LEFT_ALIGNMENT);
        monitorSettingsPanel.add(helper);
        return monitorSettingsPanel;
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
        return buildOutputPanel(outputArea, "Command Output / Logs");
    }

    private JPanel buildSettingsOutputPanel() {
        JPanel panel = buildOutputPanel(settingsOutputArea, "Command Output / Logs (also shown here while working in Settings)");
        panel.setPreferredSize(new Dimension(1000, 240));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel buildOutputPanel(JTextArea area, String title) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    private void wireActions() {
        installButton.addActionListener(e -> runRepoScript("install.sh", true));
        uninstallButton.addActionListener(e -> runRepoScript("uninstall_monitor.sh", true));
        updateButton.addActionListener(e -> runUpdateWorkflow());
        flashButton.addActionListener(e -> runRepoScript("arduino_install.sh", true));
        customFlashButton.addActionListener(e -> uploadCustomSketch());
        wifiCredentialsButton.addActionListener(e -> promptForWifiCredentials());
        clearSavedPasswordButton.addActionListener(e -> clearSavedSudoPassword(true));

        serviceOnButton.addActionListener(e -> runServiceCommand("start"));
        serviceOffButton.addActionListener(e -> runServiceCommand("stop"));
        serviceRestartButton.addActionListener(e -> runServiceCommand("restart"));
        serviceStatusButton.addActionListener(e -> refreshServiceStatus(true));

        debugOnButton.addActionListener(e -> setDebugMode(true));
        debugOffButton.addActionListener(e -> setDebugMode(false));
        debugRefreshButton.addActionListener(e -> refreshDebugStatus(true));
        wifiModeOnButton.addActionListener(e -> setTransportMode(true));
        wifiModeOffButton.addActionListener(e -> setTransportMode(false));
        wifiModeRefreshButton.addActionListener(e -> refreshTransportModeStatus(true));

        startFakePortsButton.addActionListener(e -> startFakePorts());
        stopFakePortsButton.addActionListener(e -> stopFakePorts());
        connectPreviewButton.addActionListener(e -> connectPreviewPort());
        disconnectPreviewButton.addActionListener(e -> disconnectPreviewPort());
        refreshMonitorPortsButton.addActionListener(e -> refreshMonitorPortChoices(true));
        loadMonitorSettingsButton.addActionListener(e -> refreshMonitorConnectionSettings(true));
        saveMonitorSettingsButton.addActionListener(e -> saveMonitorConnectionSettings());
        wifiConnectionModeSelector.addActionListener(e -> updateWifiHostFieldState());
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
            refreshMonitorConnectionSettings(true);
            refreshWifiCredentialsIndicator(false);
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
        Object selected = unoR3ScreenSizeSelector.getSelectedItem();
        String choice = selected == null ? "2.8\" mode" : selected.toString();
        String unoScreenSize = choice.startsWith("3.5") ? "35" : "28";
        log("[INFO] Control Center will flash Arduino UNO R3 boards with the "
                + ("35".equals(unoScreenSize) ? "3.5\"" : "2.8\"")
                + " TFT sketch. R4 boards are unchanged.");
        return unoScreenSize;
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
        try {
            String text = loadMergedMonitorConfigText();
            if (text == null || text.isBlank()) {
                setDebugIndicator("UNKNOWN", Color.GRAY);
                lastDebugEnabledState = null;
                if (verbose || !debugStatusMissingLogged) {
                    log("[WARN] Cannot refresh debug status: no monitor config file was found.");
                    debugStatusMissingLogged = true;
                }
                return;
            }
            debugStatusMissingLogged = false;
            boolean enabled = text.matches("(?s).*\"debug_enabled\"\\s*:\\s*true.*");
            setDebugIndicator(enabled ? "ON" : "OFF", enabled ? new Color(24, 170, 24) : new Color(190, 35, 35));

            if (verbose || lastDebugEnabledState == null || lastDebugEnabledState != enabled) {
                log("[INFO] Debug mode is " + (enabled ? "enabled" : "disabled") + " in the merged monitor config.");
            }
            lastDebugEnabledState = enabled;
        } catch (Exception ex) {
            setDebugIndicator("UNKNOWN", Color.GRAY);
            lastDebugEnabledState = null;
            log("[WARN] Failed to refresh debug status: " + ex.getMessage());
        }
    }

    private void setTransportMode(boolean wifiEnabled) {
        if (!updateBooleanConfig("wifi_enabled", wifiEnabled)) {
            return;
        }
        if (!updateBooleanConfig("prefer_usb", !wifiEnabled)) {
            return;
        }

        setTransportIndicator(
                wifiEnabled ? "WIFI" : "USB ONLY",
                wifiEnabled ? new Color(24, 170, 24) : new Color(191, 120, 24)
        );
        log("[INFO] Cycling " + SERVICE_NAME + " so the Arduino monitor can switch transports cleanly.");
        cycleServiceForTransportChange();
    }

    private void refreshTransportModeStatus(boolean verbose) {
        try {
            String text = loadMergedMonitorConfigText();
            if (text == null || text.isBlank()) {
                setTransportIndicator("UNKNOWN", Color.GRAY);
                lastWifiEnabledState = null;
                if (verbose || !transportStatusMissingLogged) {
                    log("[WARN] Cannot refresh transport mode: no monitor config file was found.");
                    transportStatusMissingLogged = true;
                }
                return;
            }
            transportStatusMissingLogged = false;
            boolean enabled = readBooleanConfigValue(text, "wifi_enabled", true);
            boolean preferUsb = readBooleanConfigValue(text, "prefer_usb", true);
            setTransportIndicator(
                    enabled ? "WIFI" : "USB ONLY",
                    enabled ? new Color(24, 170, 24) : new Color(191, 120, 24)
            );

            if (verbose || lastWifiEnabledState == null || lastWifiEnabledState != enabled) {
                String mode = enabled
                        ? (preferUsb ? "USB first with Wi-Fi fallback" : "Wi-Fi first with USB fallback")
                        : "USB only";
                log("[INFO] Monitor transport is set to " + mode + " in the merged monitor config.");
            }
            lastWifiEnabledState = enabled;
        } catch (Exception ex) {
            setTransportIndicator("UNKNOWN", Color.GRAY);
            lastWifiEnabledState = null;
            log("[WARN] Failed to refresh transport mode: " + ex.getMessage());
        }
    }

    private Path monitorDefaultConfigPath() {
        return repoPath().resolve("monitor_config.default.json");
    }

    private Path monitorSharedConfigPath() {
        return repoPath().resolve("monitor_config.json");
    }

    private Path monitorLocalConfigPath() {
        return repoPath().resolve("monitor_config.local.json");
    }

    private String readConfigFileIfPresent(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
    }

    private String loadMergedMonitorConfigText() throws IOException {
        String merged = null;
        for (Path path : List.of(monitorDefaultConfigPath(), monitorSharedConfigPath(), monitorLocalConfigPath())) {
            String text = readConfigFileIfPresent(path);
            if (text == null || text.isBlank()) {
                continue;
            }
            merged = mergeConfigTexts(merged, text);
        }
        return merged;
    }

    private String ensureWritableLocalMonitorConfig() throws IOException {
        Path localPath = monitorLocalConfigPath();
        if (Files.exists(localPath)) {
            return Files.readString(localPath, StandardCharsets.UTF_8);
        }

        Path sharedPath = monitorSharedConfigPath();
        String seedText = readConfigFileIfPresent(sharedPath);
        if (seedText == null || seedText.isBlank()) {
            seedText = readConfigFileIfPresent(monitorDefaultConfigPath());
        }
        if (seedText == null || seedText.isBlank()) {
            seedText = "{\n}\n";
        }

        Files.writeString(localPath, seedText, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return seedText;
    }

    private String mergeConfigTexts(String baseText, String overrideText) {
        String merged = baseText == null || baseText.isBlank() ? "{\n}\n" : baseText;
        for (String key : List.of(
                "arduino_port",
                "baud",
                "debug_enabled",
                "debug_port",
                "root_mount",
                "secondary_mount",
                "send_interval",
                "wifi_enabled",
                "wifi_host",
                "wifi_port",
                "prefer_usb",
                "wifi_retry_delay",
                "wifi_auto_discovery",
                "wifi_discovery_port",
                "wifi_discovery_timeout",
                "wifi_discovery_refresh",
                "wifi_discovery_magic"
        )) {
            merged = copyConfigEntryIfPresent(merged, overrideText, key);
        }
        return merged;
    }

    private String copyConfigEntryIfPresent(String targetText, String sourceText, String key) {
        String rawValue = findRawConfigValue(sourceText, key);
        if (rawValue == null) {
            return targetText;
        }
        return upsertRawConfigValue(targetText, key, rawValue);
    }

    private String findRawConfigValue(String text, String key) {
        String quotedKey = "\"" + key + "\"";
        int keyIndex = text.indexOf(quotedKey);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = text.indexOf(':', keyIndex + quotedKey.length());
        if (colonIndex < 0) {
            return null;
        }
        int index = colonIndex + 1;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        if (index >= text.length()) {
            return null;
        }
        char first = text.charAt(index);
        if (first == '"') {
            int end = index + 1;
            boolean escaped = false;
            while (end < text.length()) {
                char ch = text.charAt(end);
                if (ch == '"' && !escaped) {
                    return text.substring(index, end + 1);
                }
                escaped = ch == '\\' && !escaped;
                if (ch != '\\') {
                    escaped = false;
                }
                end++;
            }
            return null;
        }
        int end = index;
        while (end < text.length()) {
            char ch = text.charAt(end);
            if (ch == ',' || ch == '}' || ch == '\n' || ch == '\r') {
                break;
            }
            end++;
        }
        String raw = text.substring(index, end).trim();
        return raw.isEmpty() ? null : raw;
    }

    private String upsertRawConfigValue(String text, String key, String rawValue) {
        String pattern = "\\\"" + key + "\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"])*\\\"|true|false|-?\\d+(?:\\.\\d+)?)";
        String replacement = "\"" + key + "\": " + rawValue;
        return text.matches("(?s).*" + pattern + ".*")
                ? text.replaceAll(pattern, replacement)
                : appendConfigEntry(text, "  \"" + key + "\": " + rawValue);
    }

    private void refreshMonitorPortChoices(boolean verbose) {
        List<String> ports = new ArrayList<>();
        ports.add("AUTO");
        for (DetectedBoard board : detectConnectedBoards()) {
            if (!board.port.isBlank() && !ports.contains(board.port)) {
                ports.add(board.port);
            }
        }
        for (SerialPort port : SerialPort.getCommPorts()) {
            String systemPort = port.getSystemPortName();
            if (systemPort != null && !systemPort.isBlank() && !ports.contains(systemPort)) {
                ports.add(systemPort);
            }
            String devicePath = port.getSystemPortPath();
            if (devicePath != null && !devicePath.isBlank() && !ports.contains(devicePath)) {
                ports.add(devicePath);
            }
        }

        Object currentSelection = arduinoPortSelector.isEditable()
                ? arduinoPortSelector.getEditor().getItem()
                : arduinoPortSelector.getSelectedItem();
        String selectedValue = currentSelection == null ? "AUTO" : currentSelection.toString().trim();

        arduinoPortSelector.removeAllItems();
        for (String port : ports) {
            arduinoPortSelector.addItem(port);
        }

        if (selectedValue.isEmpty()) {
            selectedValue = "AUTO";
        }
        arduinoPortSelector.setSelectedItem(selectedValue);

        if (verbose) {
            log("[INFO] Refreshed Arduino port list (" + Math.max(ports.size() - 1, 0) + " detected + AUTO).");
        }
    }

    private void refreshMonitorConnectionSettings(boolean verbose) {
        refreshMonitorPortChoices(false);

        try {
            String text = loadMergedMonitorConfigText();
            if (text == null || text.isBlank()) {
                arduinoPortSelector.setSelectedItem("AUTO");
                wifiPortField.setText("5000");
                wifiConnectionModeSelector.setSelectedItem(WIFI_MODE_AUTO_DISCOVERY);
                wifiHostField.setText("");
                wifiBoardNameField.setText(DEFAULT_WIFI_BOARD_NAME);
                wifiTargetHostField.setText("");
                wifiTargetHostnameField.setText("");
                wifiPortSourceLabel.setText("Effective source: default fallback (5000)");
                updateWifiHostFieldState();
                if (verbose) {
                    log("[WARN] Cannot load monitor settings: no monitor config file was found.");
                }
                return;
            }
            String arduinoPort = readStringConfigValue(text, "arduino_port", "AUTO");
            WifiPortResolution wifiResolution = resolveEffectiveWifiPort();
            boolean wifiAutoDiscovery = readBooleanConfigValue(text, "wifi_auto_discovery", true);
            String wifiHost = readStringConfigValue(text, "wifi_host", "");
            arduinoPortSelector.setSelectedItem(arduinoPort == null || arduinoPort.isBlank() ? "AUTO" : arduinoPort);
            wifiPortField.setText(String.valueOf(wifiResolution.port()));
            wifiConnectionModeSelector.setSelectedItem(wifiAutoDiscovery ? WIFI_MODE_AUTO_DISCOVERY : WIFI_MODE_MANUAL);
            wifiHostField.setText(wifiHost == null ? "" : wifiHost.trim());
            wifiBoardNameField.setText(resolveEffectiveWifiHeaderValue("WIFI_DEVICE_NAME_VALUE", DEFAULT_WIFI_BOARD_NAME));
            wifiTargetHostField.setText(resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOST_VALUE", ""));
            wifiTargetHostnameField.setText(resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOSTNAME_VALUE", ""));
            wifiPortSourceLabel.setText("Effective source: " + wifiResolution.source());
            updateWifiHostFieldState();
            if (verbose) {
                log("[INFO] Loaded merged monitor connection settings (default/shared/local). Effective Wi-Fi TCP port source: "
                        + wifiResolution.source() + " (" + wifiResolution.port() + ")."
                        + " Connection mode=" + (wifiAutoDiscovery ? "auto discovery" : "manual/fixed IP")
                        + ", wifi_host=" + (wifiHost == null || wifiHost.isBlank() ? "<unset>" : wifiHost.trim()) + "."
                        + " Board name=" + normalizeWifiBoardName(wifiBoardNameField.getText().trim())
                        + ", target host/ip=" + wifiTargetHostField.getText().trim()
                        + ", target hostname=" + wifiTargetHostnameField.getText().trim() + ".");
            }
        } catch (Exception ex) {
            if (verbose) {
                log("[WARN] Failed to load monitor connection settings: " + ex.getMessage());
            }
        }
    }

    private void saveMonitorConnectionSettings() {
        String arduinoPort = "";
        Object portValue = arduinoPortSelector.isEditable()
                ? arduinoPortSelector.getEditor().getItem()
                : arduinoPortSelector.getSelectedItem();
        if (portValue != null) {
            arduinoPort = portValue.toString().trim();
        }
        if (arduinoPort.isEmpty()) {
            arduinoPort = "AUTO";
        }

        String wifiPortText = wifiPortField.getText() == null ? "" : wifiPortField.getText().trim();
        int wifiPort;
        try {
            wifiPort = Integer.parseInt(wifiPortText);
        } catch (NumberFormatException ex) {
            log("[WARN] Wi-Fi TCP port must be a number.");
            return;
        }
        if (wifiPort < 1 || wifiPort > 65535) {
            log("[WARN] Wi-Fi TCP port must be between 1 and 65535.");
            return;
        }
        boolean wifiAutoDiscovery = WIFI_MODE_AUTO_DISCOVERY.equals(wifiConnectionModeSelector.getSelectedItem());
        String wifiHost = wifiHostField.getText() == null ? "" : wifiHostField.getText().trim();
        if (!wifiAutoDiscovery && wifiHost.isBlank()) {
            log("[WARN] Wi-Fi host/IP is required when Manual / Fixed IP mode is selected.");
            return;
        }
        String wifiBoardName = normalizeWifiBoardName(wifiBoardNameField.getText() == null ? "" : wifiBoardNameField.getText().trim());
        String wifiTargetHost = wifiTargetHostField.getText() == null ? "" : wifiTargetHostField.getText().trim();
        String wifiTargetHostname = wifiTargetHostnameField.getText() == null ? "" : wifiTargetHostnameField.getText().trim();

        boolean savedMonitorConfig = false;
        boolean syncedWifiHeader = false;
        Path configPath = monitorLocalConfigPath();
        try {
            String text = ensureWritableLocalMonitorConfig();
            String updated = upsertStringConfigValue(text, "arduino_port", arduinoPort);
            updated = upsertNumberConfigValue(updated, "wifi_port", wifiPort);
            updated = upsertStringConfigValue(updated, "wifi_host", wifiAutoDiscovery ? "" : wifiHost);
            updated = upsertBooleanConfigValue(updated, "wifi_auto_discovery", wifiAutoDiscovery);
            updated = upsertBooleanConfigValue(updated, "wifi_enabled", true);
            updated = upsertBooleanConfigValue(updated, "prefer_usb", false);
            if (!updated.equals(text)) {
                Files.writeString(configPath, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Saved machine-local monitor settings to " + configPath
                        + " (arduino_port=" + arduinoPort
                        + ", wifi_enabled=true, prefer_usb=false, wifi_port=" + wifiPort
                        + ", wifi_auto_discovery=" + wifiAutoDiscovery
                        + ", wifi_host=" + ((wifiAutoDiscovery || wifiHost.isBlank()) ? "<unset>" : wifiHost) + ").");
            } else {
                log("[INFO] Machine-local monitor settings already matched in " + configPath + " (Wi-Fi remains preferred over USB).");
            }
            savedMonitorConfig = true;
        } catch (Exception ex) {
            log("[WARN] Failed to save machine-local monitor settings to " + configPath + ": " + ex.getMessage());
        }

        syncedWifiHeader = syncWifiHeaderIntoLocalHeader(wifiPort, wifiBoardName, wifiTargetHost, wifiTargetHostname);
        refreshWifiCredentialsIndicator(false);

        if (!savedMonitorConfig) {
            return;
        }

        setTransportIndicator("WIFI", new Color(24, 170, 24));
        if (syncedWifiHeader) {
            log("[INFO] Synced Wi-Fi port/pairing settings into wifi_config.local.h so the flashed sketch uses the matching board identity immediately.");
        }
        log("[INFO] Save Monitor Settings now recompiles/uploads the R4 WiFi sketch so TCP port " + wifiPort
                + ", connection mode " + (wifiAutoDiscovery ? "Auto Discovery (UDP)" : "Manual / Fixed IP")
                + ", wifi host/ip " + (wifiAutoDiscovery || wifiHost.isBlank() ? "<unset>" : wifiHost)
                + ", board name " + wifiBoardName + ", target host/ip " + (wifiTargetHost.isBlank() ? "<unset>" : wifiTargetHost)
                + ", and target hostname " + (wifiTargetHostname.isBlank() ? "<unset>" : wifiTargetHostname) + " apply right away.");
        reflashWifiBoardsAndRestartMonitor(wifiPort);
    }

    private boolean updateBooleanConfig(String key, boolean enabled) {
        Path configPath = monitorLocalConfigPath();
        try {
            String text = ensureWritableLocalMonitorConfig();
            String pattern = "\\\"" + key + "\\\"\\s*:\\s*(true|false)";
            String replacement = "\"" + key + "\": " + (enabled ? "true" : "false");
            String updated = text.matches("(?s).*" + pattern + ".*")
                    ? text.replaceAll(pattern, replacement)
                    : appendConfigEntry(text, "  \"" + key + "\": " + (enabled ? "true" : "false"));

            if (!updated.equals(text)) {
                Files.writeString(configPath, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Updated monitor_config.local.json: " + key + "=" + enabled);
            } else {
                log("[INFO] monitor_config.local.json already had " + key + "=" + enabled + ".");
            }
            return true;
        } catch (Exception ex) {
            log("[WARN] Could not update monitor_config.local.json entry " + key + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean readBooleanConfigValue(String text, String key, boolean defaultValue) {
        String quotedKey = "\"" + key + "\"";
        int keyIndex = text.indexOf(quotedKey);
        if (keyIndex < 0) {
            return defaultValue;
        }
        int colonIndex = text.indexOf(':', keyIndex + quotedKey.length());
        if (colonIndex < 0) {
            return defaultValue;
        }

        String tail = text.substring(colonIndex + 1).trim();
        if (tail.startsWith("true")) {
            return true;
        }
        if (tail.startsWith("false")) {
            return false;
        }
        return defaultValue;
    }

    private String readStringConfigValue(String text, String key, String defaultValue) {
        String quotedKey = "\"" + key + "\"";
        int keyIndex = text.indexOf(quotedKey);
        if (keyIndex < 0) {
            return defaultValue;
        }
        int colonIndex = text.indexOf(':', keyIndex + quotedKey.length());
        if (colonIndex < 0) {
            return defaultValue;
        }
        int firstQuote = text.indexOf('"', colonIndex + 1);
        if (firstQuote < 0) {
            return defaultValue;
        }
        int secondQuote = text.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return defaultValue;
        }
        return text.substring(firstQuote + 1, secondQuote);
    }

    private int readIntConfigValue(String text, String key, int defaultValue) {
        String quotedKey = "\"" + key + "\"";
        int keyIndex = text.indexOf(quotedKey);
        if (keyIndex < 0) {
            return defaultValue;
        }
        int colonIndex = text.indexOf(':', keyIndex + quotedKey.length());
        if (colonIndex < 0) {
            return defaultValue;
        }
        String tail = text.substring(colonIndex + 1).trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char ch = tail.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            } else if (!Character.isWhitespace(ch)) {
                break;
            }
        }
        if (digits.length() == 0) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String upsertStringConfigValue(String text, String key, String value) {
        String pattern = "\\\"" + key + "\\\"\\s*:\\s*\\\"[^\\\"]*\\\"";
        String replacement = "\"" + key + "\": \"" + escapeJson(value) + "\"";
        return text.matches("(?s).*" + pattern + ".*")
                ? text.replaceAll(pattern, replacement)
                : appendConfigEntry(text, "  \"" + key + "\": \"" + escapeJson(value) + "\"");
    }

    private String upsertNumberConfigValue(String text, String key, int value) {
        String pattern = "\\\"" + key + "\\\"\\s*:\\s*\\d+";
        String replacement = "\"" + key + "\": " + value;
        return text.matches("(?s).*" + pattern + ".*")
                ? text.replaceAll(pattern, replacement)
                : appendConfigEntry(text, "  \"" + key + "\": " + value);
    }

    private String upsertBooleanConfigValue(String text, String key, boolean value) {
        String pattern = "\\\"" + key + "\\\"\\s*:\\s*(true|false)";
        String replacement = "\"" + key + "\": " + (value ? "true" : "false");
        return text.matches("(?s).*" + pattern + ".*")
                ? text.replaceAll(pattern, replacement)
                : appendConfigEntry(text, "  \"" + key + "\": " + (value ? "true" : "false"));
    }

    private String appendConfigEntry(String text, String newEntry) {
        int closingBrace = text.lastIndexOf('}');
        if (closingBrace < 0) {
            return text;
        }

        String before = text.substring(0, closingBrace).stripTrailing();
        String suffix = before.endsWith("{") ? "\n" : ",\n";
        return before + suffix + newEntry + "\n}\n";
    }

    private void updateWifiHostFieldState() {
        boolean manualMode = WIFI_MODE_MANUAL.equals(wifiConnectionModeSelector.getSelectedItem());
        wifiHostField.setEnabled(manualMode);
        wifiHostField.setEditable(manualMode);
        if (!manualMode) {
            wifiHostField.setToolTipText("Auto Discovery (UDP) is selected, so wifi_host is not required on this PC.");
        } else {
            wifiHostField.setToolTipText("Manual / Fixed IP mode is selected. Enter the intended Arduino IP or hostname for this PC.");
        }
    }

    private Path wifiLocalConfigPath() {
        return repoPath().resolve("R4_WIFI35/wifi_config.local.h");
    }

    private Path wifiDefaultConfigPath() {
        return repoPath().resolve("R4_WIFI35/wifi_config.h");
    }

    private WifiPortResolution resolveEffectiveWifiPort() throws IOException {
        String envWifiPort = System.getenv("ARDUINO_MONITOR_WIFI_PORT");
        if (envWifiPort != null && !envWifiPort.isBlank()) {
            return new WifiPortResolution(parseWifiPort(envWifiPort, 5000), "environment override (ARDUINO_MONITOR_WIFI_PORT)");
        }

        String localText = readConfigFileIfPresent(monitorLocalConfigPath());
        String localRawPort = localText == null ? null : findRawConfigValue(localText, "wifi_port");
        if (localRawPort != null && !localRawPort.isBlank()) {
            return new WifiPortResolution(parseWifiPort(localRawPort, 5000), "monitor_config.local.json");
        }

        for (Path path : List.of(monitorSharedConfigPath(), monitorDefaultConfigPath())) {
            String sourceText = readConfigFileIfPresent(path);
            String rawPort = sourceText == null ? null : findRawConfigValue(sourceText, "wifi_port");
            if (rawPort != null && !rawPort.isBlank()) {
                return new WifiPortResolution(parseWifiPort(rawPort, 5000), "shared JSON config (" + path.getFileName() + ")");
            }
        }

        if (Files.exists(wifiLocalConfigPath())) {
            return new WifiPortResolution(parseWifiPort(
                    readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_TCP_PORT_VALUE", DEFAULT_WIFI_PORT),
                    5000
            ), "R4_WIFI35/wifi_config.local.h");
        }

        return new WifiPortResolution(parseWifiPort(
                readWifiHeaderDefine(wifiDefaultConfigPath(), "WIFI_TCP_PORT_VALUE", DEFAULT_WIFI_PORT),
                5000
        ), "R4_WIFI35/wifi_config.h");
    }

    private int parseWifiPort(String rawValue, int defaultValue) {
        try {
            int parsed = Integer.parseInt(rawValue.replace("\"", "").trim());
            return parsed >= 1 && parsed <= 65535 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String resolveEffectiveWifiHeaderValue(String defineName, String defaultValue) {
        String localValue = readWifiHeaderDefine(wifiLocalConfigPath(), defineName, "");
        if (!localValue.isEmpty()) {
            return localValue;
        }
        return readWifiHeaderDefine(wifiDefaultConfigPath(), defineName, defaultValue);
    }

    private String normalizeWifiBoardName(String value) {
        return value == null || value.isBlank() ? DEFAULT_WIFI_BOARD_NAME : value.trim();
    }

    private boolean saveWifiHeaderSettings(String ssid, String password, int tcpPort, String boardName, String targetHost, String targetHostname) {
        Path target = wifiLocalConfigPath();
        String header = "#pragma once\n\n"
                + "#define WIFI_SSID_VALUE \"" + escapeCppString(ssid) + "\"\n"
                + "#define WIFI_PASS_VALUE \"" + escapeCppString(password) + "\"\n"
                + "#define WIFI_TCP_PORT_VALUE " + tcpPort + "\n"
                + "#define WIFI_DEVICE_NAME_VALUE \"" + escapeCppString(normalizeWifiBoardName(boardName)) + "\"\n"
                + "#define WIFI_TARGET_HOST_VALUE \"" + escapeCppString(targetHost == null ? "" : targetHost.trim()) + "\"\n"
                + "#define WIFI_TARGET_HOSTNAME_VALUE \"" + escapeCppString(targetHostname == null ? "" : targetHostname.trim()) + "\"\n";
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(
                    target,
                    header,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            log("[INFO] Saved shared Wi-Fi settings to " + target);
            return true;
        } catch (IOException ex) {
            log("[WARN] Failed to write Wi-Fi settings to " + target + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean syncWifiHeaderIntoLocalHeader(int tcpPort, String boardName, String targetHost, String targetHostname) {
        String ssid = readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_SSID_VALUE", "");
        if (ssid.isEmpty()) {
            ssid = readWifiHeaderDefine(wifiDefaultConfigPath(), "WIFI_SSID_VALUE", "");
        }
        String password = readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_PASS_VALUE", "");
        if (password.isEmpty()) {
            password = readWifiHeaderDefine(wifiDefaultConfigPath(), "WIFI_PASS_VALUE", "");
        }
        return saveWifiHeaderSettings(
                ssid,
                password,
                tcpPort,
                boardName,
                targetHost,
                targetHostname
        );
    }

    private void restartMonitorServiceForSettingsChange() {
        log("[INFO] Restarting the monitor service so the new Arduino/Wi-Fi port settings apply now.");
        cycleServiceForTransportChange();
    }

    private void reflashWifiBoardsAndRestartMonitor(int wifiPort) {
        String arduinoCli = ensureArduinoCliAvailable("reflash the UNO R4 WiFi monitor sketch");
        if (arduinoCli == null) {
            log("[WARN] Reflash skipped. TCP port " + wifiPort
                    + " was still saved into monitor_config.local.json and wifi_config.local.h.");
            restartMonitorServiceForSettingsChange();
            return;
        }

        List<DetectedBoard> wifiBoards = detectConnectedBoards().stream()
                .filter(board -> "arduino:renesas_uno:unor4wifi".equals(board.fqbn))
                .toList();
        if (wifiBoards.isEmpty()) {
            log("[WARN] No Arduino UNO R4 WiFi boards were detected, so TCP port " + wifiPort
                    + " was only saved into monitor_config.local.json and wifi_config.local.h.");
            restartMonitorServiceForSettingsChange();
            return;
        }

        String sketchPath = repoPath().resolve("R4_WIFI35").toString();
        StringBuilder command = new StringBuilder("systemctl stop ")
                .append(SERVICE_NAME)
                .append(" && sleep ")
                .append(TRANSPORT_SWITCH_DELAY_SECONDS)
                .append(" && cd ")
                .append(escape(repoPath().toString()))
                .append(" && ")
                .append(escape(arduinoCli))
                .append(" compile --fqbn arduino:renesas_uno:unor4wifi ")
                .append(escape(sketchPath));
        for (DetectedBoard board : wifiBoards) {
            command.append(" && sleep 2")
                    .append(" && ")
                    .append(escape(arduinoCli))
                    .append(" upload -p ")
                    .append(escape(board.port))
                    .append(" --fqbn ")
                    .append(escape(board.fqbn))
                    .append(" ")
                    .append(escape(sketchPath));
        }
        command.append(" && sleep 2")
                .append(" ; flash_status=$?")
                .append(" ; systemctl start ")
                .append(SERVICE_NAME)
                .append(" ; exit $flash_status");

        String boardSummary = wifiBoards.stream()
                .map(board -> board.port)
                .collect(java.util.stream.Collectors.joining(", "));
        runCommand(command.toString(), repoPath().toFile(),
                "Reflash UNO R4 WiFi monitor sketch on " + boardSummary,
                true, true,
                () -> {
                    log("[INFO] Reflash completed for TCP port " + wifiPort + ". The Python monitor service was restarted after flashing.");
                    refreshServiceStatus(false);
                    refreshTransportModeStatus(false);
                    refreshMonitorPortChoices(false);
                    SwingUtilities.invokeLater(() -> setActionButtons(true));
                });
    }

    private void refreshWifiCredentialsIndicator(boolean verbose) {
        List<Path> targets = List.of(
                repoPath().resolve("R4_WIFI35/wifi_config.local.h")
        );

        boolean saved = false;
        for (Path target : targets) {
            try {
                if (!Files.exists(target)) {
                    continue;
                }
                String text = Files.readString(target, StandardCharsets.UTF_8);
                if (text.contains("#define WIFI_SSID_VALUE \"") && !text.contains("#define WIFI_SSID_VALUE \"\"")) {
                    saved = true;
                    break;
                }
            } catch (IOException ex) {
                if (verbose) {
                    log("[WARN] Could not inspect saved Wi-Fi credentials at " + target + ": " + ex.getMessage());
                }
            }
        }

        final boolean credentialsSaved = saved;
        SwingUtilities.invokeLater(() -> {
            wifiCredentialsIndicator.setText(credentialsSaved ? "Credentials saved" : "Credentials not saved");
            wifiCredentialsIndicator.setBackground(credentialsSaved ? new Color(24, 170, 24) : new Color(170, 80, 24));
            wifiCredentialsIndicator.setForeground(Color.WHITE);
        });
    }

    private String readWifiHeaderDefine(Path path, String defineName, String defaultValue) {
        if (!Files.exists(path)) {
            return defaultValue;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String marker = "#define " + defineName;
            for (String rawLine : text.split("\\R")) {
                String line = rawLine.trim();
                if (!line.startsWith(marker)) {
                    continue;
                }
                String value = line.substring(marker.length()).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    return value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? defaultValue : value;
            }
        } catch (IOException ex) {
            log("[WARN] Failed to read Wi-Fi config header " + path + ": " + ex.getMessage());
        }
        return defaultValue;
    }

    private record WifiPortResolution(int port, String source) {
    }

    private void cycleServiceForTransportChange() {
        String command = "systemctl stop " + SERVICE_NAME
                + " && sleep " + TRANSPORT_SWITCH_DELAY_SECONDS
                + " && systemctl start " + SERVICE_NAME;
        runCommand(command, repoPath().toFile(),
                "Apply transport change (stop/wait/start)",
                true, true,
                () -> {
                    refreshServiceStatus(false);
                    refreshTransportModeStatus(false);
                    SwingUtilities.invokeLater(() -> setActionButtons(true));
                });
    }

    private void promptForWifiCredentials() {
        Path localConfigPath = wifiLocalConfigPath();
        Path defaultConfigPath = wifiDefaultConfigPath();

        JTextField ssidField = new JTextField(24);
        JPasswordField passwordField = new JPasswordField(24);
        JTextField tcpPortField = new JTextField(8);
        JTextField boardNameField = new JTextField(24);
        JTextField targetHostField = new JTextField(24);
        JTextField targetHostnameField = new JTextField(24);

        String savedSsid = readWifiHeaderDefine(localConfigPath, "WIFI_SSID_VALUE", "");
        if (savedSsid.isEmpty()) {
            savedSsid = readWifiHeaderDefine(defaultConfigPath, "WIFI_SSID_VALUE", "");
        }
        if (!savedSsid.equals("YOUR_WIFI_SSID")) {
            ssidField.setText(savedSsid);
        }

        String savedPassword = readWifiHeaderDefine(localConfigPath, "WIFI_PASS_VALUE", "");
        if (savedPassword.isEmpty()) {
            savedPassword = readWifiHeaderDefine(defaultConfigPath, "WIFI_PASS_VALUE", "");
        }
        if (!savedPassword.equals("YOUR_WIFI_PASSWORD")) {
            passwordField.setText(savedPassword);
        }

        String savedTcpPort = readWifiHeaderDefine(localConfigPath, "WIFI_TCP_PORT_VALUE", "");
        if (savedTcpPort.isEmpty()) {
            savedTcpPort = readWifiHeaderDefine(defaultConfigPath, "WIFI_TCP_PORT_VALUE", "5000");
        }
        tcpPortField.setText(savedTcpPort.isEmpty() ? "5000" : savedTcpPort);
        boardNameField.setText(normalizeWifiBoardName(resolveEffectiveWifiHeaderValue("WIFI_DEVICE_NAME_VALUE", DEFAULT_WIFI_BOARD_NAME)));
        targetHostField.setText(resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOST_VALUE", ""));
        targetHostnameField.setText(resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOSTNAME_VALUE", ""));

        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 8));
        panel.add(new JLabel("Wi-Fi SSID"));
        panel.add(ssidField);
        panel.add(new JLabel("Wi-Fi Password"));
        panel.add(passwordField);
        panel.add(new JLabel("Wi-Fi TCP Port"));
        panel.add(tcpPortField);
        panel.add(new JLabel("Board Name"));
        panel.add(boardNameField);
        panel.add(new JLabel("Target Host/IP"));
        panel.add(targetHostField);
        panel.add(new JLabel("Target Hostname"));
        panel.add(targetHostnameField);

        int result = JOptionPane.showConfirmDialog(
                this,
                panel,
                "Set R4 Wi-Fi Settings",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (result != JOptionPane.OK_OPTION) {
            log("[INFO] Wi-Fi credential update canceled.");
            return;
        }

        String ssid = ssidField.getText() == null ? "" : ssidField.getText().trim();
        String password = new String(passwordField.getPassword());
        String tcpPortText = tcpPortField.getText() == null ? "" : tcpPortField.getText().trim();
        String boardName = normalizeWifiBoardName(boardNameField.getText() == null ? "" : boardNameField.getText().trim());
        String targetHost = targetHostField.getText() == null ? "" : targetHostField.getText().trim();
        String targetHostname = targetHostnameField.getText() == null ? "" : targetHostnameField.getText().trim();
        if (ssid.isEmpty()) {
            log("[WARN] Wi-Fi credentials not saved: SSID is required.");
            return;
        }
        int tcpPort;
        try {
            tcpPort = Integer.parseInt(tcpPortText);
        } catch (NumberFormatException ex) {
            log("[WARN] Wi-Fi settings not saved: TCP port must be a number.");
            return;
        }
        if (tcpPort < 1 || tcpPort > 65535) {
            log("[WARN] Wi-Fi settings not saved: TCP port must be between 1 and 65535.");
            return;
        }

        boolean savedHeader = saveWifiHeaderSettings(ssid, password, tcpPort, boardName, targetHost, targetHostname);
        Path configPath = monitorLocalConfigPath();
        boolean savedMonitorConfig = false;
        try {
            String text = ensureWritableLocalMonitorConfig();
            String updated = upsertNumberConfigValue(text, "wifi_port", tcpPort);
            if (!updated.equals(text)) {
                Files.writeString(configPath, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Synced monitor_config.local.json to Wi-Fi TCP port " + tcpPort + ".");
            } else {
                log("[INFO] monitor_config.local.json already matched Wi-Fi TCP port " + tcpPort + ".");
            }
            savedMonitorConfig = true;
        } catch (IOException ex) {
            log("[WARN] Failed to sync monitor_config.local.json Wi-Fi port: " + ex.getMessage());
        }

        if (savedHeader) {
            wifiPortField.setText(String.valueOf(tcpPort));
            wifiBoardNameField.setText(boardName);
            wifiTargetHostField.setText(targetHost);
            wifiTargetHostnameField.setText(targetHostname);
            refreshWifiCredentialsIndicator(false);
            log("[INFO] Wi-Fi settings saved for the R4 Wi-Fi sketch (TCP port " + tcpPort
                    + ", board name " + boardName
                    + ", target host/ip " + (targetHost.isBlank() ? "<unset>" : targetHost)
                    + ", target hostname " + (targetHostname.isBlank() ? "<unset>" : targetHostname) + "). Reflash the board to apply them.");
            log("[INFO] Settings were written to wifi_config.local.h, which is git-ignored for safe testing/pushing.");
            if (savedMonitorConfig) {
                restartMonitorServiceForSettingsChange();
            }
        } else {
            refreshWifiCredentialsIndicator(false);
            log("[ERROR] Wi-Fi settings were not saved to any sketch folder.");
        }
    }

    private boolean updateDebugConfig(boolean enabled, String fakeIn, boolean applyPort) {
        Path configPath = monitorLocalConfigPath();
        try {
            String text = ensureWritableLocalMonitorConfig();
            String updated = text;

            if (updated.contains("\"debug_enabled\"")) {
                updated = updated.replaceAll("\\\"debug_enabled\\\"\\s*:\\s*(true|false)", "\"debug_enabled\": " + (enabled ? "true" : "false"));
            } else {
                updated = appendConfigEntry(updated, "  \"debug_enabled\": " + (enabled ? "true" : "false"));
            }
            if (applyPort && updated.contains("\"debug_port\"")) {
                updated = updated.replaceAll("\\\"debug_port\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\"debug_port\": \"" + escapeJson(fakeIn) + "\"");
            } else if (applyPort) {
                updated = appendConfigEntry(updated, "  \"debug_port\": \"" + escapeJson(fakeIn) + "\"");
            }

            if (!updated.equals(text)) {
                Files.write(configPath, updated.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Updated monitor_config.local.json: debug_enabled=" + enabled + (applyPort ? ", debug_port=" + fakeIn : ""));
            } else {
                log("[INFO] monitor_config.local.json already had requested debug settings.");
            }
            return true;
        } catch (Exception ex) {
            log("[WARN] Could not update monitor_config.local.json debug settings: " + ex.getMessage());
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
        } else if (component instanceof JTabbedPane tabbedPane) {
            tabbedPane.setBackground(panelBackground);
            tabbedPane.setForeground(textColor);
            tabbedPane.setOpaque(true);
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
            if (label != serviceIndicator && label != debugIndicator && label != transportIndicator) {
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

        String arduinoCli = ensureArduinoCliAvailable("upload a custom Arduino sketch");
        if (arduinoCli == null) {
            return;
        }

        String command = "cd " + escape(repoPath().toString())
                + " && " + escape(arduinoCli) + " compile --fqbn "
                + escape(target.fqbn) + " " + escape(sketchPath.toString())
                + " && sleep 2"
                + " && " + escape(arduinoCli) + " upload -p "
                + escape(target.port) + " --fqbn " + escape(target.fqbn) + " " + escape(sketchPath.toString());
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
        return detectConnectedBoards(false);
    }

    private List<DetectedBoard> detectConnectedBoards(boolean waitForR4Wifi) {
        List<DetectedBoard> boards = new ArrayList<>();
        String arduinoCli = ensureArduinoCliAvailable("detect connected Arduino boards");
        if (arduinoCli == null) {
            return boards;
        }
        int attempts = waitForR4Wifi ? 4 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            boards = detectConnectedBoardsOnce(arduinoCli);
            boolean hasR4Wifi = boards.stream().anyMatch(board -> "arduino:renesas_uno:unor4wifi".equals(board.fqbn));
            if (!waitForR4Wifi || hasR4Wifi || attempt == attempts) {
                return boards;
            }
            log("[INFO] Waiting for the UNO R4 WiFi board to reappear after reset (" + attempt + "/" + attempts + ")...");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return boards;
            }
        }
        return boards;
    }

    private List<DetectedBoard> detectConnectedBoardsOnce(String arduinoCli) {
        List<DetectedBoard> boards = new ArrayList<>();
        if (arduinoCli == null) {
            return boards;
        }
        try {
            Process process = new ProcessBuilder("bash", "-lc", escape(arduinoCli) + " board list | tail -n +2")
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
                    String normalized = trimmed.toLowerCase(Locale.ROOT);
                    if (normalized.contains("arduino:renesas_uno:unor4wifi")
                            || normalized.contains("arduino uno r4 wifi")
                            || normalized.contains("uno r4 wifi")
                            || normalized.contains("uno r4")) {
                        boards.add(new DetectedBoard(port, "Arduino UNO R4 WiFi", "arduino:renesas_uno:unor4wifi"));
                    } else if (normalized.contains("mega 2560") || normalized.contains("arduino mega")) {
                        boards.add(new DetectedBoard(port, "Arduino Mega 2560", "arduino:avr:mega"));
                    } else if (normalized.contains("arduino uno")) {
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

    private String ensureArduinoCliAvailable(String reason) {
        Path[] candidates = new Path[] {
                Paths.get(System.getProperty("user.home"), ".local", "bin", "arduino-cli")
        };

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }

        try {
            Process whichProcess = new ProcessBuilder("bash", "-lc", "command -v arduino-cli")
                    .directory(repoPath().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(whichProcess.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            int code = whichProcess.waitFor();
            if (code == 0 && output != null && !output.trim().isEmpty()) {
                return output.trim();
            }
        } catch (Exception ex) {
            log("[WARN] Failed to inspect arduino-cli availability: " + ex.getMessage());
        }

        log("[INFO] arduino-cli was not found. Installing it to ~/.local/bin so Control Center can " + reason + ".");
        try {
            Process installProcess = new ProcessBuilder("bash", "-lc",
                    "mkdir -p \"$HOME/.local/bin\" && curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=\"$HOME/.local/bin\" sh")
                    .directory(repoPath().toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(installProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log("[arduino-cli install] " + line);
                }
            }
            int code = installProcess.waitFor();
            Path installedCli = Paths.get(System.getProperty("user.home"), ".local", "bin", "arduino-cli");
            if (code == 0 && Files.isRegularFile(installedCli) && Files.isExecutable(installedCli)) {
                log("[INFO] Installed arduino-cli to " + installedCli + ".");
                return installedCli.toAbsolutePath().normalize().toString();
            }
            log("[WARN] arduino-cli installation exited with code " + code + ".");
        } catch (Exception ex) {
            log("[WARN] Failed to install arduino-cli automatically: " + ex.getMessage());
        }

        log("[ERROR] arduino-cli is unavailable, so Control Center could not " + reason + ".");
        return null;
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
                String version = properties.getProperty("app.version", "9.0 beta").trim();
                if (!version.isEmpty() && !version.contains("${")) {
                    return version;
                }
            }
        } catch (IOException ignored) {
            // Fall back to a safe default below.
        }
        return "9.0 beta";
    }

    private boolean runningAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    private String escapeCppString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private void setActionButtons(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            installButton.setEnabled(enabled);
            uninstallButton.setEnabled(enabled);
            updateButton.setEnabled(enabled);
            flashButton.setEnabled(enabled);
            customFlashButton.setEnabled(enabled);
            wifiCredentialsButton.setEnabled(enabled);
            clearSavedPasswordButton.setEnabled(enabled);
            serviceOnButton.setEnabled(enabled);
            serviceOffButton.setEnabled(enabled);
            serviceRestartButton.setEnabled(enabled);
            serviceStatusButton.setEnabled(enabled);
            debugOnButton.setEnabled(enabled);
            debugOffButton.setEnabled(enabled);
            debugRefreshButton.setEnabled(enabled);
            wifiModeOnButton.setEnabled(enabled);
            wifiModeOffButton.setEnabled(enabled);
            wifiModeRefreshButton.setEnabled(enabled);
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

    private void setTransportIndicator(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            transportIndicator.setText(text);
            transportIndicator.setBackground(color);
            transportIndicator.setForeground(Color.WHITE);
        });
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
