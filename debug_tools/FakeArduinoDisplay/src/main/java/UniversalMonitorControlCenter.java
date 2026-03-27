import com.fazecast.jSerialComm.SerialPort;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;

public class UniversalMonitorControlCenter extends JFrame {

    private static final String SERVICE_NAME = "arduino-monitor.service";
    private static final String APP_NAME = "Universal Arduino System Monitor - Control Center";
    private static final String APP_VERSION = ProjectVersion.loadVersion(UniversalMonitorControlCenter.class);
    private static final String APP_VERSION_DISPLAY = APP_VERSION;
    private static final String SUDO_PASSWORD_FILE = ".control_center_sudo_password";
    private static final String WIFI_SETTINGS_BACKUP_FILE = ".control_center_wifi_settings.properties";
    private static final String REMOTE_TARGETS_FILE = ".control_center_remote_targets.properties";
    private static final int TRANSPORT_SWITCH_DELAY_SECONDS = 8;
    private static final String DEFAULT_WIFI_PORT = "5000";
    private static final String DEFAULT_WIFI_BOARD_NAME = "R4_WIFI35";
    private static final String WIFI_MODE_MANUAL = "Fixed Host/IP Only (Recommended)";
    private static final String WIFI_MODE_AUTO_DISCOVERY = "Fixed Host/IP + UDP Discovery Fallback";
    private static final String PROGRAM_MODE_SYSTEM_MONITOR = "System Monitor";
    private static final String PROGRAM_MODE_GAMING = "Gaming Mode";
    private static final String PROGRAM_MODE_MACRO = "Macro Mode";
    private static final int MAX_MACRO_ENTRIES = 8;
    private static final int DISPLAY_ROTATION_NORMAL = 1;
    private static final int DISPLAY_ROTATION_FLIPPED = 3;
    private static final int FLASH_SERVICE_RESTART_DELAY_SECONDS = 2;
    private static final String DEFAULT_PAGE_PROFILE_NAME = "Desktop Default";
    private static final List<String> REQUIRED_ARDUINO_CORE_PACKAGES = List.of(
            "arduino:avr",
            "arduino:renesas_uno"
    );
    private static final List<ArduinoLibraryRequirement> REQUIRED_ARDUINO_LIBRARIES = List.of(
            new ArduinoLibraryRequirement("MCUFRIEND_kbv", "MCUFRIEND_kbv", "MCUFRIEND_kbv.h"),
            new ArduinoLibraryRequirement("Adafruit GFX Library", "Adafruit GFX Library", "Adafruit_GFX.h"),
            new ArduinoLibraryRequirement("Adafruit TouchScreen", "TouchScreen", "TouchScreen.h"),
            new ArduinoLibraryRequirement("DIYables TFT Touch Shield", "DIYables TFT Touch Shield", "DIYables_TFT_Touch_Shield.h")
    );

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

    private final JButton desktopInstallButton = new JButton("Reinstall Monitor");
    private final JButton desktopAppletButton = new JButton("Install Desktop Applet");
    private final JButton uninstallButton = new JButton("Uninstall");
    private final JButton updateButton = new JButton("Update and Restart GUI");
    private final JComboBox<String> remoteActionSelector = new JComboBox<>();
    private final JButton runRemoteActionButton = new JButton("Run Action");
    private final JCheckBox remoteUseSshToggle = new JCheckBox("Run over SSH");
    private final JTextField remoteHostField = new JTextField(13);
    private final JTextField remoteUserField = new JTextField(10);
    private final JTextField remotePortField = new JTextField("22", 4);
    private final JTextField remoteRepoField = new JTextField(26);
    private final JComboBox<String> remoteProfileSelector = new JComboBox<>();
    private final JButton saveRemoteProfileButton = new JButton("Save Target");
    private final JButton deleteRemoteProfileButton = new JButton("Delete Target");
    private final JButton flashButton = new JButton("Flash Arduinos'");
    private final JButton flashPreviewButton = new JButton("Show Flash Diff");
    private final JButton customFlashButton = new JButton("Upload Custom Sketch");
    private final JButton wifiCredentialsButton = new JButton("Set R4 Wi-Fi Credentials");
    private final JButton killRunningTaskButton = new JButton("Kill Running Flash/Task");

    private final JButton serviceOnButton = new JButton("Service On");
    private final JButton serviceOffButton = new JButton("Service Off");
    private final JButton serviceRestartButton = new JButton("Service Restart");
    private final JButton serviceStatusButton = new JButton("Service Status");
    private final JButton serviceStartupEnableButton = new JButton("Enable Monitor on Startup");
    private final JButton serviceStartupDisableButton = new JButton("Disable Monitor on Startup");
    private final JButton serviceStartupRefreshButton = new JButton("Refresh Startup State");

    private final JButton debugOnButton = new JButton("Enable Debug Mode");
    private final JButton debugOffButton = new JButton("Disable Debug Mode");
    private final JButton debugRefreshButton = new JButton("Refresh Debug Status");
    private final JButton wifiModeOnButton = new JButton("Enable Wi-Fi Mode");
    private final JButton wifiModeOffButton = new JButton("Use USB Only");
    private final JButton wifiModeRefreshButton = new JButton("Refresh Transport");

    private final JLabel serviceIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel startupIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel debugIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel transportIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel flashTransportIndicator = new JLabel("UNKNOWN", SwingConstants.CENTER);
    private final JLabel versionLabel = new JLabel(APP_VERSION_DISPLAY);
    private final JCheckBox alwaysShowFlashPreviewToggle = new JCheckBox("Always show preview before flashing");
    private final JCheckBox lightModeToggle = new JCheckBox("Light mode");
    private final JComboBox<String> unoR3ScreenSizeSelector = new JComboBox<>(new String[]{"2.8\" mode", "3.5\" mode"});
    private final JComboBox<String> megaScreenSizeSelector = new JComboBox<>(new String[]{"2.8\" mode", "3.5\" mode"});
    private final JComboBox<String> r4RotationSelector = new JComboBox<>(new String[]{rotationLabel(DISPLAY_ROTATION_NORMAL), rotationLabel(DISPLAY_ROTATION_FLIPPED)});
    private final JComboBox<String> r3RotationSelector = new JComboBox<>(new String[]{rotationLabel(DISPLAY_ROTATION_NORMAL), rotationLabel(DISPLAY_ROTATION_FLIPPED)});
    private final JComboBox<String> megaRotationSelector = new JComboBox<>(new String[]{rotationLabel(DISPLAY_ROTATION_NORMAL), rotationLabel(DISPLAY_ROTATION_FLIPPED)});
    private final JComboBox<String> arduinoPortSelector = new JComboBox<>();
    private final JComboBox<String> wifiConnectionModeSelector = new JComboBox<>(new String[]{WIFI_MODE_MANUAL, WIFI_MODE_AUTO_DISCOVERY});
    private final JCheckBox wifiDiscoveryDebugToggle = new JCheckBox("Wi-Fi Discovery Debug");
    private final JCheckBox wifiDiscoveryIgnoreBoardFilterToggle = new JCheckBox("Ignore Board Filter (Wi-Fi Discovery Debug)");
    private final JComboBox<String> programModeSelector = new JComboBox<>(new String[]{
            PROGRAM_MODE_SYSTEM_MONITOR,
            PROGRAM_MODE_GAMING,
            PROGRAM_MODE_MACRO
    });
    private final JTextField wifiPortField = new JTextField(6);
    private final JTextField wifiHostField = new JTextField(14);
    private final JTextField wifiBoardNameField = new JTextField(14);
    private final JTextField wifiTargetHostField = new JTextField(14);
    private final JTextField wifiTargetHostnameField = new JTextField(14);
    private final JLabel wifiPortSourceLabel = new JLabel("Effective source: unknown");
    private final JButton refreshMonitorPortsButton = new JButton("Refresh Port List");
    private final JButton loadMonitorSettingsButton = new JButton("Load Monitor Settings");
    private final JButton saveMonitorSettingsButton = new JButton("Save Monitor Settings & Flash R4 WiFi");
    private final JButton resetWifiPairingButton = new JButton("Reset Wi-Fi Pairing");
    private final JTextArea macroEntriesArea = new JTextArea(6, 52);
    private final JComboBox<String> macroTriggerModelSelector = new JComboBox<>(new String[]{
            "Whole-screen tap cycles entries",
            "Large left/right screen halves",
            "Dedicated full-screen zones",
            "External button trigger"
    });
    private final JLabel customSketchIndicator = new JLabel("No sketch selected");
    private final JLabel wifiCredentialsIndicator = new JLabel("Credentials not saved");
    private final JLabel previewWifiStateLabel = new JLabel("Disabled");
    private final JLabel previewWifiHostnameLabel = new JLabel("--");
    private final JLabel previewWifiIpLabel = new JLabel("--");
    private final JPasswordField dashboardSudoPasswordField = new JPasswordField(16);
    private final JCheckBox dashboardRememberPasswordToggle = new JCheckBox("Remember");
    private final JButton scanNetworkButton = new JButton("Start Arduino Scan");
    private final JButton stopNetworkScanButton = new JButton("Stop Scan");
    private final DefaultListModel<String> networkScanResultsModel = new DefaultListModel<>();
    private final JList<String> networkScanResultsList = new JList<>(networkScanResultsModel);

    private final JComboBox<BoardProfileTarget> profileBoardSelector = new JComboBox<>();
    private final JComboBox<String> activeBoardProfileSelector = new JComboBox<>();
    private final JButton applyBoardProfileButton = new JButton("Apply Profile to Board");
    private final JButton saveBoardSettingsButton = new JButton("Save Board Page Settings");
    private final JButton newProfileButton = new JButton("New Profile");
    private final JButton saveProfileAsButton = new JButton("Save As Profile");
    private final JButton updateProfileButton = new JButton("Update Profile");
    private final JButton deleteProfileButton = new JButton("Delete Profile");
    private final JButton exportProfilesButton = new JButton("Export Profiles...");
    private final JButton importProfilesButton = new JButton("Import Profiles...");
    private final JButton saveAllBoardsAndProfilesButton = new JButton("Save All Boards & Current Profiles");
    private final JPanel boardPageTogglePanel = new JPanel(new GridLayout(0, 4, 6, 6));
    private final JLabel boardProfileStatusLabel = new JLabel("No board profile loaded.");

    private final Map<String, BoardPageSettings> boardPageSettings = new LinkedHashMap<>();
    private final Map<String, Map<String, Map<String, Boolean>>> namedPageProfiles = new LinkedHashMap<>();
    private final Map<String, JCheckBox> boardPageCheckboxes = new LinkedHashMap<>();
    private final Map<String, RemoteTargetProfile> remoteTargetProfiles = new LinkedHashMap<>();

    private final JavaSerialFakeDisplay.FakeDisplayPanel fakeDisplayPanel = new JavaSerialFakeDisplay.FakeDisplayPanel();

    private final Color darkBackground = new Color(23, 39, 66);
    private final Color darkPanelBackground = new Color(34, 54, 86);
    private final Color darkAccent = new Color(92, 143, 214);
    private final Color darkText = new Color(232, 240, 252);
    private final Color darkFieldBackground = new Color(18, 29, 48);
    private final Color darkButtonBackground = new Color(70, 109, 171);
    private final Color darkCriticalButtonBackground = new Color(176, 58, 58);
    private final Color darkPositiveButtonBackground = new Color(49, 145, 86);
    private final Color lightBackground = new Color(236, 242, 252);
    private final Color lightPanelBackground = new Color(248, 251, 255);
    private final Color lightAccent = new Color(125, 160, 219);
    private final Color lightText = new Color(31, 46, 71);
    private final Color lightFieldBackground = Color.WHITE;
    private final Color lightButtonBackground = new Color(214, 226, 245);
    private final Color lightCriticalButtonBackground = new Color(221, 92, 92);
    private final Color lightPositiveButtonBackground = new Color(91, 182, 127);

    private boolean darkMode;

    private volatile Process fakePortsProcess;
    private volatile Process activeCommandProcess;
    private volatile SerialPort previewPort;
    private volatile boolean previewReading;
    private Thread previewReaderThread;
    private Boolean lastDebugEnabledState;
    private Boolean lastWifiEnabledState;
    private boolean debugStatusMissingLogged;
    private boolean transportStatusMissingLogged;
    private volatile boolean networkScanRequested;
    private volatile java.net.DatagramSocket activeNetworkScanSocket;
    private Thread networkScanThread;
    private Path selectedCustomSketchPath;
    private boolean homeDisableWarningAcknowledged;



    private void applyWindowIcon() {
        Path repoRoot = detectRepoRoot();
        List<Path> candidates = List.of(
                repoRoot.resolve("screenshots/arduinoPreview1.png"),
                repoRoot.resolve("screenshots/arduinoPreview.png"),
                repoRoot.resolve("screenshots/home1.JPEG")
        );

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                Image icon = Toolkit.getDefaultToolkit().getImage(candidate.toAbsolutePath().toString());
                if (icon != null) {
                    setIconImage(icon);
                    return;
                }
            }
        }
    }

    public UniversalMonitorControlCenter() {
        super(APP_NAME + " " + APP_VERSION);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1340, 940);
        setLocationRelativeTo(null);
        applyWindowIcon();

        darkMode = shouldUseDarkModeByDefault();

        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        repoField.setText(detectRepoRoot().toString());
        sudoPasswordField.setToolTipText("Optional: sudo password used for installer/update/flash/service controls when not root");
        rememberPasswordToggle.setToolTipText("Stores the sudo password in the local " + SUDO_PASSWORD_FILE + " file inside the repo. Git ignores that file so it stays on this machine.");
        clearSavedPasswordButton.setToolTipText("Deletes the saved sudo password file from this repo.");
        flashButton.setToolTipText("Builds and uploads from the repo's current sketches plus the latest generated local headers/config state saved in this UI.");
        customFlashButton.setToolTipText("Lets you choose a local .ino or sketch folder and upload that custom sketch.");
        wifiCredentialsButton.setToolTipText("Saves the SSID and password into the local R4 Wi-Fi sketch config before flashing.");
        killRunningTaskButton.setToolTipText("Force-stops the currently running flash/task command if it gets stuck, then refreshes the UI.");
        killRunningTaskButton.setEnabled(false);
        debugRefreshButton.setToolTipText("Re-checks whether the Python debug mirror mode is currently enabled.");
        wifiModeRefreshButton.setToolTipText("Re-checks whether the monitor is currently using Wi-Fi mode or USB-only mode.");
        serviceStartupEnableButton.setToolTipText("Runs systemctl enable and start for the Arduino monitor service so it starts at boot and now.");
        serviceStartupDisableButton.setToolTipText("Runs systemctl disable and stop for the Arduino monitor service so it does not start at boot.");
        serviceStartupRefreshButton.setToolTipText("Re-checks whether the Arduino monitor service is enabled at boot.");
        startFakePortsButton.setToolTipText("Creates a linked fake serial port pair for testing the preview without hardware.");
        connectPreviewButton.setToolTipText("Connects the built-in preview window to the fake output serial port.");
        unoR3ScreenSizeSelector.setToolTipText("Choose which sketch size to flash onto detected Arduino UNO R3 boards only.");
        megaScreenSizeSelector.setToolTipText("Choose which sketch size to flash onto detected Arduino Mega boards only.");
        r4RotationSelector.setToolTipText("Set the saved screen rotation for every UNO R4 WiFi flash: Normal = setRotation(1), Flipped = setRotation(3).");
        r3RotationSelector.setToolTipText("Set the saved screen rotation for every UNO R3 flash, including the 2.8\" and 3.5\" sketches: Normal = setRotation(1), Flipped = setRotation(3).");
        megaRotationSelector.setToolTipText("Set the saved screen rotation for every Arduino Mega 3.5\" flash: Normal = setRotation(1), Flipped = setRotation(3).");
        arduinoPortSelector.setToolTipText("Sets the monitor's preferred Arduino serial port. Use AUTO to let the monitor auto-detect.");
        wifiPortField.setToolTipText("Sets the monitor TCP port used for direct fixed host/IP connection and optional discovery fallback.");
        wifiConnectionModeSelector.setToolTipText("Recommended: Fixed Host/IP Only. Optional mode: keep Fixed Host/IP first and allow UDP discovery fallback.");
        wifiDiscoveryDebugToggle.setToolTipText("Diagnostic toggle for verbose UDP auto-discovery matching logs in Python monitor output (wifi_discovery_debug).");
        wifiDiscoveryIgnoreBoardFilterToggle.setToolTipText("Diagnostic override that bypasses board-name filtering during discovery troubleshooting (wifi_discovery_ignore_board_filter).");
        remoteActionSelector.setToolTipText("Structured command actions only (safe starter set). Pick one then click Run Action.");
        runRemoteActionButton.setToolTipText("Runs the selected action locally or through SSH using the target fields.");
        remoteUseSshToggle.setToolTipText("When enabled, the selected action runs on a remote Linux host over SSH.");
        remoteHostField.setToolTipText("Remote hostname or IP, for example 192.168.1.20.");
        remoteUserField.setToolTipText("Remote SSH username, for example pi or your Linux account.");
        remotePortField.setToolTipText("Remote SSH port (default 22).");
        remoteRepoField.setToolTipText("Project path on the target machine where install/update/flash scripts live.");
        remoteProfileSelector.setToolTipText("Saved SSH targets. Choose one to fill host/user/port/repo quickly.");
        programModeSelector.setToolTipText("Select overall program/board mode. Stored in JSON config as program_mode and passed into flash workflows.");
        wifiHostField.setToolTipText("Primary/recommended path: set this to the Arduino board address this PC should talk to, like 192.168.1.50 or a hostname. The monitor always tries this first when set.");
        wifiBoardNameField.setToolTipText("Easy nickname for the Arduino board, like OFFICE_PC_SCREEN or LIVING_ROOM_MONITOR. This helps Auto Discovery match the right board to the right PC when you have more than one.");
        wifiTargetHostField.setToolTipText("Optional: the IP address or network name of the PC this Arduino belongs to. Example: 192.168.1.20. Use this when each Arduino should pair with one specific computer.");
        wifiTargetHostnameField.setToolTipText("Optional: the computer name this Arduino should look for, like GAMING-PC or OFFICE-DESKTOP. This is another way to lock one Arduino to one PC.");
        refreshMonitorPortsButton.setToolTipText("Re-detects currently connected Arduino serial ports for the selector.");
        loadMonitorSettingsButton.setToolTipText("Reloads the saved settings from the config files on this computer. Use it to bring back what you last saved locally before you flash again.");
        saveMonitorSettingsButton.setToolTipText("Saves machine-local serial/TCP/connection-mode settings, mirrors the Wi-Fi port/pairing values into wifi_config.local.h, stops the monitor service, flashes every detected R4 WiFi board with that same local header, and starts the service again.");
        resetWifiPairingButton.setToolTipText("Explicitly clears the saved EEPROM Wi-Fi pairing on one connected UNO R4 WiFi over USB so the next PC can claim it without reflashing.");
        macroEntriesArea.setToolTipText("One macro text entry per line. Used by Macro Mode groundwork and stored in monitor config.");
        macroEntriesArea.setLineWrap(true);
        macroEntriesArea.setWrapStyleWord(true);
        macroTriggerModelSelector.setToolTipText("Staged trigger approach for unreliable touch calibration. Stores macro_trigger_model for future firmware behavior.");

        JTabbedPane mainTabs = buildMainTabs();

        add(mainTabs, BorderLayout.CENTER);
        JPanel footerOutputPanel = buildOutputPanel();
        footerOutputPanel.setPreferredSize(new Dimension(1000, 240));
        add(footerOutputPanel, BorderLayout.SOUTH);

        lightModeToggle.setSelected(!darkMode);

        wireActions();
        applyTheme();
        updatePortButtons();
        setServiceIndicator("UNKNOWN", Color.GRAY);
        setStartupIndicator("UNKNOWN", Color.GRAY);
        setDebugIndicator("UNKNOWN", Color.GRAY);
        loadSavedSudoPassword();
        dashboardSudoPasswordField.setText(new String(sudoPasswordField.getPassword()));
        dashboardRememberPasswordToggle.setSelected(rememberPasswordToggle.isSelected());
        settingsOutputArea.setDocument(outputArea.getDocument());
        setRotationSelectorValue(r4RotationSelector, DISPLAY_ROTATION_NORMAL);
        setRotationSelectorValue(r3RotationSelector, DISPLAY_ROTATION_NORMAL);
        setRotationSelectorValue(megaRotationSelector, DISPLAY_ROTATION_NORMAL);
        initializeBoardProfileState();
        initializeRemoteActionState();
        refreshMonitorConnectionSettings(false);
        refreshWifiCredentialsIndicator(false);
        updatePreviewWifiStatus(lastWifiEnabledState != null && lastWifiEnabledState);

        Timer serviceTimer = new Timer(7000, e -> {
            refreshServiceStatus(false);
            refreshServiceStartupStatus(false);
            refreshDebugStatus(false);
            refreshTransportModeStatus(false);
        });
        serviceTimer.setRepeats(true);
        serviceTimer.start();
        refreshServiceStatus(false);
        refreshServiceStartupStatus(false);
        refreshDebugStatus(false);
        refreshTransportModeStatus(false);
    }

    private JPanel buildRepoPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Project Location / Privileges"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel repoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        repoRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        repoRow.add(new JLabel("Program Root:"));
        repoRow.add(repoField);

        JButton browseButton = new JButton("Browse");
        browseButton.addActionListener(e -> browseRepoPath());
        repoRow.add(browseButton);

        JButton openFolderButton = new JButton("Open Folder");
        openFolderButton.addActionListener(e -> openRepoFolder());
        repoRow.add(openFolderButton);
        panel.add(repoRow);

        JPanel credentialsRow = new JPanel();
        credentialsRow.setLayout(new BoxLayout(credentialsRow, BoxLayout.Y_AXIS));
        credentialsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel passwordRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        passwordRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordRow.add(new JLabel("sudo password:"));
        passwordRow.add(sudoPasswordField);
        credentialsRow.add(passwordRow);

        JPanel passwordActionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        passwordActionsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        rememberPasswordToggle.setFocusable(false);
        passwordActionsRow.add(rememberPasswordToggle);
        passwordActionsRow.add(clearSavedPasswordButton);
        credentialsRow.add(passwordActionsRow);

        versionLabel.setFont(versionLabel.getFont().deriveFont(Font.BOLD));
        dashboardRememberPasswordToggle.setFocusable(false);
        alwaysShowFlashPreviewToggle.setFocusable(false);
        dashboardSudoPasswordField.setToolTipText("Optional duplicate sudo password field for quick access on Dashboard.");
        panel.add(credentialsRow);

        return panel;
    }

    private JTabbedPane buildMainTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Dashboard", buildDashboardTab());
        tabs.addTab("Flash", buildFlashTab());
        tabs.addTab("Settings / Profiles", buildSettingsProfilesTab());
        return tabs;
    }

    private JPanel buildDashboardTab() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(buildDashboardStatusPanel());
        content.add(Box.createVerticalStrut(6));
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.30);
        splitPane.setContinuousLayout(true);
        splitPane.setOneTouchExpandable(false);
        splitPane.setLeftComponent(buildPreviewPanel());
        splitPane.setRightComponent(buildDashboardSidePanel());
        splitPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setDividerLocation(380);
        splitPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 900));
        content.add(splitPane);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildFlashTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(buildTransportPanel());
        content.add(Box.createVerticalStrut(6));
        content.add(buildDisplayAndFlashPanel());
        content.add(Box.createVerticalStrut(6));
        content.add(buildMonitorSettingsPanel());

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildSettingsProfilesTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createTitledBorder("Board Page Settings / Profiles"));
        top.putClientProperty("uasmSettingsPanel", Boolean.TRUE);

        JPanel rowOne = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        rowOne.add(new JLabel("Board Type:"));
        rowOne.add(profileBoardSelector);
        rowOne.add(new JLabel("Active Profile:"));
        activeBoardProfileSelector.setEditable(true);
        activeBoardProfileSelector.setPreferredSize(new Dimension(220, activeBoardProfileSelector.getPreferredSize().height));
        rowOne.add(activeBoardProfileSelector);
        rowOne.add(applyBoardProfileButton);
        rowOne.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(rowOne);
        top.add(Box.createVerticalStrut(6));

        JPanel rowTwo = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        rowTwo.add(saveBoardSettingsButton);
        rowTwo.add(newProfileButton);
        rowTwo.add(saveProfileAsButton);
        rowTwo.add(updateProfileButton);
        rowTwo.add(deleteProfileButton);
        rowTwo.add(exportProfilesButton);
        rowTwo.add(importProfilesButton);
        rowTwo.add(saveAllBoardsAndProfilesButton);
        boardProfileStatusLabel.setBorder(new EmptyBorder(0, 12, 0, 0));
        rowTwo.add(boardProfileStatusLabel);
        rowTwo.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(rowTwo);
        top.add(Box.createVerticalStrut(8));

        JPanel helperPanel = new JPanel(new BorderLayout());
        helperPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        helperPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Settings / Profiles quick guide"),
                new EmptyBorder(10, 14, 12, 14)));

        JLabel helper = new JLabel("<html><div style='width: 960px; line-height: 1.45;'>"
                + "<b>What is a profile?</b> A reusable page-toggle preset. Each profile stores page visibility <b>per board type</b> (R4, UNO R3, Mega), so one profile name can hold different page maps for each board.<br><br>"
                + "<b>Enable/disable pages</b> to control touch navigation availability after flashing. Disabled pages are skipped on-device.<br><br>"
                + "<b>Apply Profile</b>: loads the selected profile onto this board's toggles.<br><br>"
                + "<b>Save Board Page Settings</b>: writes current toggles + active profile and updates board page headers used at compile time.<br><br>"
                + "<b>Save As / New Profile</b>: create a named custom profile from current toggles. <b>Update</b> overwrites the selected profile for this board. <b>Delete</b> removes a custom profile.<br><br>"
                + "<b>Import / Export</b>: move profile definitions between systems using board_page_profiles.properties.<br><br>"
                + "<b>Flash Arduinos'</b> compiles/flashes the <i>current repo sketches</i> with the <i>current generated local headers</i> (rotations, page toggles, and local Wi-Fi header values). Reflash is required for profile/toggle changes to take effect on hardware."
                + "</div></html>");
        helper.setBorder(new EmptyBorder(0, 0, 0, 0));
        helper.putClientProperty("uasmSettingsHelpBlock", Boolean.TRUE);
        helperPanel.add(helper, BorderLayout.WEST);
        top.add(helperPanel);

        boardPageTogglePanel.setBorder(new EmptyBorder(6, 6, 6, 6));
        boardPageTogglePanel.putClientProperty("uasmSettingsPanel", Boolean.TRUE);
        JScrollPane togglesScroll = new JScrollPane(boardPageTogglePanel);
        togglesScroll.setBorder(BorderFactory.createTitledBorder("Page Toggles for Selected Board"));

        panel.add(top, BorderLayout.NORTH);
        panel.add(togglesScroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDashboardStatusPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(buildServiceControlsPanel());
        panel.add(Box.createVerticalStrut(6));
        panel.add(buildDebugPanel());
        return panel;
    }

    private JPanel buildServiceControlsPanel() {
        JPanel servicePanel = new JPanel(new BorderLayout(10, 8));
        servicePanel.setBorder(BorderFactory.createTitledBorder("Service Controls: " + SERVICE_NAME));
        JPanel controlsColumn = new JPanel();
        controlsColumn.setLayout(new BoxLayout(controlsColumn, BoxLayout.Y_AXIS));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        serviceIndicator.setOpaque(true);
        serviceIndicator.setPreferredSize(new Dimension(110, 30));
        buttonPanel.add(serviceOnButton);
        buttonPanel.add(serviceOffButton);
        buttonPanel.add(serviceRestartButton);
        buttonPanel.add(serviceStatusButton);
        buttonPanel.add(new JLabel("Running:"));
        buttonPanel.add(serviceIndicator);
        controlsColumn.add(buttonPanel);

        JPanel startupRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        startupIndicator.setOpaque(true);
        startupIndicator.setPreferredSize(new Dimension(110, 30));
        startupRow.add(serviceStartupEnableButton);
        startupRow.add(serviceStartupDisableButton);
        startupRow.add(serviceStartupRefreshButton);
        startupRow.add(new JLabel("Startup:"));
        startupRow.add(startupIndicator);
        controlsColumn.add(startupRow);

        servicePanel.add(controlsColumn, BorderLayout.CENTER);
        lightModeToggle.setFocusable(false);
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setBorder(new EmptyBorder(6, 6, 6, 6));
        JPanel sudoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        sudoRow.add(new JLabel("sudo:"));
        sudoRow.add(dashboardSudoPasswordField);
        sudoRow.add(dashboardRememberPasswordToggle);
        rightPanel.add(sudoRow);
        JPanel versionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        versionRow.add(buildVersionBadge());
        rightPanel.add(versionRow);
        servicePanel.add(rightPanel, BorderLayout.EAST);
        servicePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return servicePanel;
    }

    private JPanel buildDebugPanel() {
        JPanel debugPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        debugPanel.setBorder(BorderFactory.createTitledBorder("Python Debug Mirror"));
        debugIndicator.setOpaque(true);
        debugIndicator.setPreferredSize(new Dimension(100, 30));
        debugPanel.add(debugOnButton);
        debugPanel.add(debugOffButton);
        debugPanel.add(debugRefreshButton);
        debugPanel.add(new JLabel("Indicator:"));
        debugPanel.add(debugIndicator);
        debugPanel.add(Box.createHorizontalGlue());
        lightModeToggle.setFocusable(false);
        debugPanel.add(lightModeToggle);

        debugPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return debugPanel;
    }

    private JPanel buildTransportPanel() {
        JPanel transportPanel = new JPanel();
        transportPanel.setLayout(new BoxLayout(transportPanel, BoxLayout.Y_AXIS));
        transportPanel.setBorder(BorderFactory.createTitledBorder("Monitor Transport Mode"));

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        transportIndicator.setOpaque(true);
        transportIndicator.setPreferredSize(new Dimension(120, 30));
        modeRow.add(wifiModeOnButton);
        modeRow.add(wifiModeOffButton);
        modeRow.add(wifiModeRefreshButton);
        modeRow.add(new JLabel("Mode:"));
        modeRow.add(transportIndicator);
        modeRow.add(Box.createHorizontalStrut(12));
        modeRow.add(wifiCredentialsButton);
        wifiCredentialsIndicator.setBorder(new EmptyBorder(0, 8, 0, 8));
        wifiCredentialsIndicator.setOpaque(true);
        wifiCredentialsIndicator.setHorizontalAlignment(SwingConstants.CENTER);
        wifiCredentialsIndicator.setPreferredSize(new Dimension(150, wifiCredentialsIndicator.getPreferredSize().height + 6));
        modeRow.add(wifiCredentialsIndicator);
        modeRow.add(Box.createHorizontalStrut(12));
        modeRow.add(killRunningTaskButton);

        transportPanel.add(modeRow);
        transportPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return transportPanel;
    }

    private JPanel buildDisplayAndFlashPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Display / Flash Settings"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel rowOne = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        rowOne.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowOne.add(new JLabel("UNO R3 mode:"));
        unoR3ScreenSizeSelector.setSelectedIndex(1);
        rowOne.add(unoR3ScreenSizeSelector);
        rowOne.add(new JLabel("Mega mode:"));
        megaScreenSizeSelector.setSelectedIndex(1);
        rowOne.add(megaScreenSizeSelector);
        rowOne.add(new JLabel("R4 rotation:"));
        rowOne.add(r4RotationSelector);
        rowOne.add(new JLabel("R3 rotation:"));
        rowOne.add(r3RotationSelector);
        rowOne.add(new JLabel("Mega rotation:"));
        rowOne.add(megaRotationSelector);
        panel.add(rowOne);

        JPanel rowTwo = new JPanel();
        rowTwo.setLayout(new BoxLayout(rowTwo, BoxLayout.Y_AXIS));
        rowTwo.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionRow.add(flashButton);
        actionRow.add(flashPreviewButton);
        actionRow.add(customFlashButton);
        customSketchIndicator.setBorder(new EmptyBorder(0, 8, 0, 0));
        customSketchIndicator.setToolTipText("Shows the currently selected custom sketch folder.");
        actionRow.add(customSketchIndicator);
        rowTwo.add(actionRow);

        JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        optionsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        optionsRow.setBorder(new EmptyBorder(6, 0, 0, 0));
        optionsRow.add(alwaysShowFlashPreviewToggle);
        rowTwo.add(optionsRow);
        JLabel flashHelper = new JLabel("<html><b>Flash Arduinos'</b> always compiles/uploads from the current repo files on disk, including the latest generated local config headers (page toggles, board rotations, and Wi-Fi local header values).</html>");
        flashHelper.setBorder(new EmptyBorder(6, 0, 0, 0));
        rowTwo.add(flashHelper);

        panel.add(rowTwo);
        return panel;
    }

    private JPanel buildAppManagementPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Linux App Management (uses sudo when needed)"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(desktopInstallButton);
        panel.add(desktopAppletButton);
        panel.add(uninstallButton);
        panel.add(updateButton);
        return panel;
    }

    private JPanel buildCliRemoteToolsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("CLI / Remote Tools (safe predefined actions)"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionRow.add(new JLabel("Action:"));
        remoteActionSelector.setPreferredSize(new Dimension(260, remoteActionSelector.getPreferredSize().height));
        actionRow.add(remoteActionSelector);
        actionRow.add(runRemoteActionButton);
        actionRow.add(remoteUseSshToggle);
        panel.add(actionRow);

        JPanel sshRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        sshRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        sshRow.add(new JLabel("User:"));
        sshRow.add(remoteUserField);
        sshRow.add(new JLabel("Host/IP:"));
        sshRow.add(remoteHostField);
        sshRow.add(new JLabel("Port:"));
        sshRow.add(remotePortField);
        panel.add(sshRow);

        JPanel repoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        repoRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        repoRow.add(new JLabel("Target project path:"));
        repoRow.add(remoteRepoField);
        panel.add(repoRow);

        JPanel profileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        profileRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        profileRow.add(new JLabel("Saved target:"));
        remoteProfileSelector.setEditable(false);
        remoteProfileSelector.setPreferredSize(new Dimension(220, remoteProfileSelector.getPreferredSize().height));
        profileRow.add(remoteProfileSelector);
        profileRow.add(saveRemoteProfileButton);
        profileRow.add(deleteRemoteProfileButton);
        panel.add(profileRow);

        JLabel helper = new JLabel("<html><b>Phase 1 local actions:</b> update project, flash Arduinos, restart/status monitor service, and run Wi-Fi discovery debug logs.<br>"
                + "<b>Phase 2 SSH:</b> enable <b>Run over SSH</b> to run those same actions on another Linux machine using this structured target config.<br>"
                + "This panel intentionally runs only predefined commands (not a free-form shell) for safer remote management.</html>");
        helper.setBorder(new EmptyBorder(4, 8, 8, 8));
        helper.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(helper);

        return panel;
    }

    private JPanel buildPreviewControlsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Preview / Virtual Serial Ports (test bench only)"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel portsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        portsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        portsRow.add(new JLabel("Input:"));
        portsRow.add(fakeInField);
        portsRow.add(new JLabel("Output:"));
        portsRow.add(fakeOutField);
        panel.add(portsRow);

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttonsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonsRow.add(startFakePortsButton);
        buttonsRow.add(stopFakePortsButton);
        buttonsRow.add(connectPreviewButton);
        buttonsRow.add(disconnectPreviewButton);
        panel.add(buttonsRow);

        JPanel networkRow = new JPanel(new BorderLayout(6, 6));
        networkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel networkButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        networkButtons.add(scanNetworkButton);
        networkButtons.add(stopNetworkScanButton);
        networkRow.add(networkButtons, BorderLayout.NORTH);
        networkScanResultsList.setVisibleRowCount(3);
        networkRow.add(new JScrollPane(networkScanResultsList), BorderLayout.CENTER);
        networkRow.setBorder(BorderFactory.createTitledBorder("Arduino Network Scan"));
        panel.add(networkRow);
        return panel;
    }

    private JPanel buildDashboardSidePanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(buildRepoPanel());
        panel.add(Box.createVerticalStrut(6));
        panel.add(buildAppManagementPanel());
        panel.add(Box.createVerticalStrut(6));
        panel.add(buildCliRemoteToolsPanel());
        panel.add(Box.createVerticalStrut(6));
        panel.add(buildPreviewControlsPanel());
        panel.setMinimumSize(new Dimension(560, 600));
        panel.setPreferredSize(new Dimension(620, 720));
        return panel;
    }

    private JPanel buildMonitorSettingsPanel() {
        JPanel monitorSettingsPanel = new JPanel();
        monitorSettingsPanel.setLayout(new BoxLayout(monitorSettingsPanel, BoxLayout.Y_AXIS));
        monitorSettingsPanel.setBorder(BorderFactory.createTitledBorder("Physical Arduino Monitor Connection Settings"));
        monitorSettingsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        arduinoPortSelector.setEditable(true);
        arduinoPortSelector.setPreferredSize(new Dimension(170, arduinoPortSelector.getPreferredSize().height));

        JPanel rowOne = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        rowOne.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowOne.add(new JLabel("Arduino USB Port:"));
        rowOne.add(arduinoPortSelector);
        rowOne.add(refreshMonitorPortsButton);
        rowOne.add(Box.createHorizontalStrut(12));
        rowOne.add(new JLabel("Arduino Wi-Fi TCP Port:"));
        rowOne.add(wifiPortField);
        rowOne.add(new JLabel("Connection Mode:"));
        rowOne.add(wifiConnectionModeSelector);
        monitorSettingsPanel.add(rowOne);

        JPanel rowTwo = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        rowTwo.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel wifiHostLabel = new JLabel("Wi-Fi Host/IP:");
        wifiHostLabel.setToolTipText(wifiHostField.getToolTipText());
        rowTwo.add(wifiHostLabel);
        rowTwo.add(wifiHostField);
        JLabel boardNameLabel = new JLabel("Board Name:");
        boardNameLabel.setToolTipText(wifiBoardNameField.getToolTipText());
        rowTwo.add(boardNameLabel);
        rowTwo.add(wifiBoardNameField);
        JLabel targetHostLabel = new JLabel("Target Host/IP:");
        targetHostLabel.setToolTipText(wifiTargetHostField.getToolTipText());
        rowTwo.add(targetHostLabel);
        rowTwo.add(wifiTargetHostField);
        JLabel targetHostnameLabel = new JLabel("Target Hostname:");
        targetHostnameLabel.setToolTipText(wifiTargetHostnameField.getToolTipText());
        rowTwo.add(targetHostnameLabel);
        rowTwo.add(wifiTargetHostnameField);
        monitorSettingsPanel.add(rowTwo);

        JPanel rowThree = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        rowThree.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowThree.add(new JLabel("Program Mode:"));
        rowThree.add(programModeSelector);
        rowThree.add(Box.createHorizontalStrut(8));
        rowThree.add(wifiDiscoveryDebugToggle);
        rowThree.add(wifiDiscoveryIgnoreBoardFilterToggle);
        monitorSettingsPanel.add(rowThree);

        JLabel wifiDebugHelper = new JLabel("<html><b>Wi-Fi discovery debug help:</b><br>"
                + "<b>Wi-Fi Discovery Debug</b> turns on detailed UDP discovery logging in the Python monitor. Use it when Auto Discovery is not finding your board, pairing is inconsistent, or the wrong board appears selected. Expect many extra log lines showing broadcast attempts, replies seen, board-name matching decisions, and fallback decisions.<br>"
                + "<b>Ignore Board Filter</b> temporarily bypasses board-name matching while debug is enabled. Use it when you suspect your configured board name is wrong/mismatched and you want to confirm any UNO R4 WiFi is replying on the network. Expect logs to show replies that would normally be filtered out, which can include boards for other PCs.<br>"
                + "Typical workflow: enable debug first, retry discovery, review output for dropped/mismatched replies, then enable Ignore Board Filter only as a temporary diagnostic and disable it after fixing board names.</html>");
        wifiDebugHelper.setBorder(new EmptyBorder(0, 10, 2, 10));
        wifiDebugHelper.setAlignmentX(Component.LEFT_ALIGNMENT);
        monitorSettingsPanel.add(wifiDebugHelper);

        JPanel rowFour = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        rowFour.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowFour.add(new JLabel("Macro Trigger Model:"));
        rowFour.add(macroTriggerModelSelector);
        rowFour.add(Box.createHorizontalStrut(8));
        rowFour.add(new JLabel("Macro Entries (one per line):"));
        JScrollPane macroScrollPane = new JScrollPane(macroEntriesArea);
        macroScrollPane.setPreferredSize(new Dimension(520, 110));
        rowFour.add(macroScrollPane);
        monitorSettingsPanel.add(rowFour);

        JPanel rowFive = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        rowFive.setAlignmentX(Component.LEFT_ALIGNMENT);
        wifiPortSourceLabel.setBorder(new EmptyBorder(0, 4, 0, 0));
        rowFive.add(wifiPortSourceLabel);
        rowFive.add(loadMonitorSettingsButton);
        rowFive.add(saveMonitorSettingsButton);
        rowFive.add(resetWifiPairingButton);
        monitorSettingsPanel.add(rowFive);

        JLabel helper = new JLabel("<html><b>Recommended setup:</b> set a <b>Wi-Fi Host/IP</b> and use <b>Fixed Host/IP Only</b> for the most reliable path. The monitor will connect directly over TCP without requiring discovery first.<br>"
                + "If you need diagnostics/fallback, switch to <b>Fixed Host/IP + UDP Discovery Fallback</b>. In that mode the monitor still tries fixed host/IP first, then can fall back to UDP discovery.<br>"
                + "<b>Wi-Fi Host/IP</b> = the Arduino's network address for this PC. <b>Board Name</b> = a simple nickname for the Arduino. <b>Target Host/IP</b> and <b>Target Hostname</b> = which PC that Arduino should belong to.<br>"
                + "If you have one Arduino per PC, give each board its own name and flash them <b>one at a time</b> so each one keeps the correct target PC info.<br>"
                + "<b>Program Mode</b> is a staged top-level selector (System Monitor, Gaming Mode, Macro Mode). It is saved to config now and passed into flash workflows so mode-specific sketch behavior can be expanded safely later.<br>"
                + "<b>Macro Mode groundwork</b>: macro entries + trigger model are saved in config now (Phase 1). Use large/whole-screen-safe trigger options instead of tiny touch targets until touch calibration is reliable.<br>"
                + "<b>Load Monitor Settings</b> reloads the saved config files from this computer, including your last local values if you already saved them. That lets you review them before flashing again.<br>"
                + "<b>Save Monitor Settings & Flash R4 WiFi</b> writes this PC's local settings, copies the pairing values into <b>R4_WIFI35/wifi_config.local.h</b>, reflashes detected UNO R4 WiFi boards, and restarts the monitor so the change applies now.<br>"
                + "<b>Reset Wi-Fi Pairing</b> uses a deliberate USB admin command to clear only the saved EEPROM pairing on one connected UNO R4 WiFi so you can move that board to another PC without reflashing.</html>");
        helper.setFont(helper.getFont().deriveFont(helper.getFont().getSize2D() - 1f));
        helper.setBorder(new EmptyBorder(0, 10, 8, 10));
        helper.setAlignmentX(Component.LEFT_ALIGNMENT);
        monitorSettingsPanel.add(helper);
        return monitorSettingsPanel;
    }

    private JPanel buildPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Arduino Preview (live from output port)"));
        panel.setMinimumSize(new Dimension(320, 420));
        panel.setPreferredSize(new Dimension(380, 620));
        panel.add(fakeDisplayPanel, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.add(buildPreviewWifiPanel());
        JLabel helper = new JLabel("Preview listens live to the Output port path used by the fake serial pair.");
        helper.setBorder(new EmptyBorder(0, 8, 8, 8));
        helper.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(helper);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildPreviewWifiPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 3, 8, 8));
        panel.setBorder(new EmptyBorder(8, 8, 4, 8));
        panel.add(buildInfoValuePanel("Preview Wi-Fi", previewWifiStateLabel));
        panel.add(buildInfoValuePanel("PC Hostname", previewWifiHostnameLabel));
        panel.add(buildInfoValuePanel("PC IP", previewWifiIpLabel));
        return panel;
    }

    private JPanel buildInfoValuePanel(String title, JLabel valueLabel) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        valueLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(valueLabel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildOutputPanel() {
        return buildOutputPanel(outputArea, "Command Output / Logs");
    }

    private JPanel buildOutputPanel(JTextArea area, String title) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private void wireActions() {
        desktopInstallButton.addActionListener(e -> runInstallAndDesktopWorkflow());
        desktopAppletButton.addActionListener(e -> runRepoScript("install_control_center_desktop.sh", true));
        uninstallButton.addActionListener(e -> runRepoScript("uninstall_monitor.sh", true));
        updateButton.addActionListener(e -> runUpdateWorkflow());
        runRemoteActionButton.addActionListener(e -> runSelectedRemoteAction());
        remoteUseSshToggle.addActionListener(e -> updateRemoteTargetFieldState());
        saveRemoteProfileButton.addActionListener(e -> saveCurrentRemoteTargetProfile());
        deleteRemoteProfileButton.addActionListener(e -> deleteSelectedRemoteTargetProfile());
        remoteProfileSelector.addActionListener(e -> applySelectedRemoteTargetProfile());
        flashButton.addActionListener(e -> runDefaultFlashWorkflow());
        flashPreviewButton.addActionListener(e -> showFlashPreviewDialog());
        customFlashButton.addActionListener(e -> uploadCustomSketch());
        wifiCredentialsButton.addActionListener(e -> promptForWifiCredentials());
        clearSavedPasswordButton.addActionListener(e -> clearSavedSudoPassword(true));
        dashboardRememberPasswordToggle.addActionListener(e -> rememberPasswordToggle.setSelected(dashboardRememberPasswordToggle.isSelected()));
        killRunningTaskButton.addActionListener(e -> killActiveCommand());

        serviceOnButton.addActionListener(e -> runServiceCommand("start"));
        serviceOffButton.addActionListener(e -> runServiceCommand("stop"));
        serviceRestartButton.addActionListener(e -> runServiceCommand("restart"));
        serviceStatusButton.addActionListener(e -> refreshServiceStatus(true));
        serviceStartupEnableButton.addActionListener(e -> setServiceStartupEnabled(true));
        serviceStartupDisableButton.addActionListener(e -> setServiceStartupEnabled(false));
        serviceStartupRefreshButton.addActionListener(e -> refreshServiceStartupStatus(true));

        debugOnButton.addActionListener(e -> setDebugMode(true));
        debugOffButton.addActionListener(e -> setDebugMode(false));
        debugRefreshButton.addActionListener(e -> refreshDebugStatus(true));
        wifiModeOnButton.addActionListener(e -> setTransportMode(true));
        wifiModeOffButton.addActionListener(e -> setTransportMode(false));
        wifiModeRefreshButton.addActionListener(e -> refreshTransportModeStatus(true));

        startFakePortsButton.addActionListener(e -> startFakePorts());
        scanNetworkButton.addActionListener(e -> startNetworkScan());
        stopNetworkScanButton.addActionListener(e -> stopNetworkScan());
        stopFakePortsButton.addActionListener(e -> stopFakePorts());
        connectPreviewButton.addActionListener(e -> connectPreviewPort());
        disconnectPreviewButton.addActionListener(e -> disconnectPreviewPort());
        refreshMonitorPortsButton.addActionListener(e -> refreshMonitorPortChoices(true));
        loadMonitorSettingsButton.addActionListener(e -> refreshMonitorConnectionSettings(true));
        saveMonitorSettingsButton.addActionListener(e -> saveMonitorConnectionSettings());
        resetWifiPairingButton.addActionListener(e -> resetWifiPairing());
        wifiConnectionModeSelector.addActionListener(e -> updateWifiHostFieldState());
        profileBoardSelector.addActionListener(e -> refreshBoardPageToggleView());
        applyBoardProfileButton.addActionListener(e -> applySelectedProfileToCurrentBoard());
        saveBoardSettingsButton.addActionListener(e -> saveBoardPageSettingsAndSyncHeaders(true));
        newProfileButton.addActionListener(e -> createNewProfile());
        saveProfileAsButton.addActionListener(e -> saveCurrentBoardAsNamedProfile());
        updateProfileButton.addActionListener(e -> updateSelectedProfileFromCurrentBoard());
        deleteProfileButton.addActionListener(e -> deleteSelectedProfile());
        exportProfilesButton.addActionListener(e -> exportBoardProfilesToChosenPath());
        importProfilesButton.addActionListener(e -> importBoardProfilesFromChosenPath());
        saveAllBoardsAndProfilesButton.addActionListener(e -> saveAllBoardsAndProfiles());
        dashboardSudoPasswordField.addActionListener(e -> syncDashboardSudoPassword());
        lightModeToggle.addActionListener(e -> {
            darkMode = !lightModeToggle.isSelected();
            applyTheme();
        });
    }


    private JLabel buildVersionBadge() {
        JLabel badge = new JLabel(APP_VERSION_DISPLAY);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD));
        return badge;
    }

    private void syncDashboardSudoPassword() {
        sudoPasswordField.setText(new String(dashboardSudoPasswordField.getPassword()));
    }

    private void runDefaultFlashWorkflow() {
        syncDashboardSudoPassword();
        persistCurrentDisplayRotationSelections(true, true);
        syncCurrentWifiHeaderFromUi(false);
        saveBoardPageSettingsAndSyncHeaders(false);
        if (alwaysShowFlashPreviewToggle.isSelected()) {
            showFlashPreviewDialog();
        }
        runRepoScript("arduino_install.sh", true);
    }

    private void showFlashPreviewDialog() {
        JTextArea previewArea = new JTextArea(buildFlashPreviewReport());
        previewArea.setEditable(false);
        previewArea.setCaretPosition(0);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(previewArea);
        scrollPane.setPreferredSize(new Dimension(860, 420));
        JButton copyButton = new JButton("Copy Diff");
        copyButton.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(previewArea.getText()), null);
            log("[INFO] Copied flash preview diff to the clipboard.");
        });
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.add(scrollPane, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footer.add(copyButton);
        content.add(footer, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, content, "Board-specific flash preview / diff", JOptionPane.PLAIN_MESSAGE);
        log("[INFO] Generated board-specific flash preview / diff before flashing.\n" + previewArea.getText());
    }

    private String buildFlashPreviewReport() {
        int r4Rotation = selectedRotationValue(r4RotationSelector);
        int r3Rotation = selectedRotationValue(r3RotationSelector);
        int megaRotation = selectedRotationValue(megaRotationSelector);
        String boardName = normalizeWifiBoardName(wifiBoardNameField.getText().trim());
        String targetHost = wifiTargetHostField.getText().trim();
        String targetHostname = wifiTargetHostnameField.getText().trim();
        String programMode = normalizeProgramMode(String.valueOf(programModeSelector.getSelectedItem()));
        List<String> macroEntries = normalizeMacroEntries(macroEntriesArea.getText());
        StringBuilder out = new StringBuilder();
        out.append("Program mode: ").append(programMode).append('\n');
        out.append("Macro trigger model: ").append(normalizeMacroTriggerModel(String.valueOf(macroTriggerModelSelector.getSelectedItem()))).append('\n');
        out.append("Macro entries: ").append(macroEntries.size()).append('\n');
        if (!macroEntries.isEmpty()) {
            for (int i = 0; i < macroEntries.size(); i++) {
                out.append("  ").append(i + 1).append(". ").append(macroEntries.get(i)).append('\n');
            }
        }
        out.append('\n');
        appendFlashBoardPreview(out, "R4 / UNO R4 WiFi", r4DisplayConfigPath(), r4Rotation, true, boardName, targetHost, targetHostname);
        out.append("\n");
        appendFlashBoardPreview(out, "R3 / UNO R3", r3DisplayConfig28Path(), r3Rotation, false, "N/A", "N/A", "N/A");
        out.append("\n");
        appendFlashBoardPreview(out, "Mega / 2560", megaDisplayConfigPath(), megaRotation, false, "N/A", "N/A", "N/A");
        return out.toString();
    }

    private void appendFlashBoardPreview(StringBuilder out, String title, Path displayHeaderPath, int rotation, boolean includeWifi, String boardName, String targetHost, String targetHostname) {
        out.append("Arduino Target: ").append(title).append("\n");
        out.append("Rotation: ").append(currentDisplayHeaderValue(displayHeaderPath)).append(" -> ").append(rotation).append(" (").append(rotationLabel(rotation)).append(")\n");
        if (includeWifi) {
            out.append("Wi-Fi Enabled: ").append(lastWifiEnabledState == null ? "unknown" : lastWifiEnabledState).append(" -> true\n");
            out.append("Wi-Fi Port: ").append(readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_TCP_PORT_VALUE", DEFAULT_WIFI_PORT)).append(" -> ").append(wifiPortField.getText().trim()).append("\n");
            out.append("Board Name: ").append(resolveEffectiveWifiHeaderValue("WIFI_DEVICE_NAME_VALUE", DEFAULT_WIFI_BOARD_NAME)).append(" -> ").append(boardName).append("\n");
            out.append("Target Host/IP: ").append(readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_TARGET_HOST_VALUE", "")).append(" -> ").append(targetHost.isBlank() ? "<unset>" : targetHost).append("\n");
            out.append("Target Hostname: ").append(readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_TARGET_HOSTNAME_VALUE", "")).append(" -> ").append(targetHostname.isBlank() ? "<unset>" : targetHostname).append("\n");
        } else {
            out.append("Wi-Fi Enabled: not applicable\n");
            out.append("Wi-Fi Port: not applicable\n");
            out.append("Board Name: not applicable\n");
            out.append("Target Host/IP: not applicable\n");
            out.append("Target Hostname: not applicable\n");
        }
        out.append("Diff preview:\n");
        out.append("- #define DISPLAY_ROTATION_VALUE ").append(currentDisplayHeaderValue(displayHeaderPath)).append("\n");
        out.append("+ #define DISPLAY_ROTATION_VALUE ").append(rotation).append("\n");
        if (includeWifi) {
            out.append("- #define WIFI_TCP_PORT_VALUE ").append(readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_TCP_PORT_VALUE", DEFAULT_WIFI_PORT)).append("\n");
            out.append("+ #define WIFI_TCP_PORT_VALUE ").append(wifiPortField.getText().trim()).append("\n");
            out.append("- #define WIFI_DEVICE_NAME_VALUE \"").append(resolveEffectiveWifiHeaderValue("WIFI_DEVICE_NAME_VALUE", DEFAULT_WIFI_BOARD_NAME)).append("\"\n");
            out.append("+ #define WIFI_DEVICE_NAME_VALUE \"").append(boardName).append("\"\n");
        }
    }

    private String currentDisplayHeaderValue(Path path) {
        try {
            String text = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("DISPLAY_ROTATION_VALUE\\s+(\\d+)").matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        } catch (IOException ignored) {
        }
        return "1";
    }

    private void startNetworkScan() {
        if (networkScanThread != null && networkScanThread.isAlive()) {
            log("[INFO] Arduino network scan is already running.");
            return;
        }
        networkScanRequested = true;
        updateNetworkScanButtons();
        networkScanResultsModel.clear();
        networkScanResultsModel.addElement("Scanning local network for Arduino UDP discovery replies...");
        networkScanThread = new Thread(() -> {
            try {
                while (networkScanRequested) {
                    performNetworkScanPass();
                    if (!networkScanRequested) {
                        break;
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                activeNetworkScanSocket = null;
                networkScanRequested = false;
                networkScanThread = null;
                SwingUtilities.invokeLater(() -> {
                    if (networkScanResultsModel.isEmpty()) {
                        networkScanResultsModel.addElement("Network scan stopped.");
                    }
                    updateNetworkScanButtons();
                });
            }
        }, "arduino-network-scan");
        networkScanThread.setDaemon(true);
        networkScanThread.start();
        log("[INFO] Started Arduino network scan. Scanning only while enabled.");
    }

    private void stopNetworkScan() {
        networkScanRequested = false;
        java.net.DatagramSocket socket = activeNetworkScanSocket;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (networkScanThread != null) {
            networkScanThread.interrupt();
        }
        SwingUtilities.invokeLater(() -> {
            if (!networkScanResultsModel.isEmpty()) {
                networkScanResultsModel.addElement("Scan stopped.");
            }
            updateNetworkScanButtons();
        });
        log("[INFO] Stopped Arduino network scan.");
    }

    private void performNetworkScanPass() {
        List<String> results = new ArrayList<>();
        String magic = "UAM_DISCOVER";
        int port = 5001;
        try {
            String merged = loadMergedMonitorConfigText();
            magic = readStringConfigValue(merged == null ? "" : merged, "wifi_discovery_magic", magic);
            port = readIntConfigValue(merged == null ? "" : merged, "wifi_discovery_port", port);
        } catch (Exception ignored) {
        }
        try (java.net.DatagramSocket sock = new java.net.DatagramSocket()) {
            activeNetworkScanSocket = sock;
            sock.setBroadcast(true);
            sock.setSoTimeout(1200);
            byte[] payload = magic.getBytes(StandardCharsets.UTF_8);
            List<String> broadcastTargets = new ArrayList<>(detectBroadcastTargets());
            if (!broadcastTargets.contains("255.255.255.255")) {
                broadcastTargets.add("255.255.255.255");
            }
            for (String target : broadcastTargets) {
                try {
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(
                            payload,
                            payload.length,
                            java.net.InetAddress.getByName(target),
                            port
                    );
                    sock.send(packet);
                } catch (Exception ex) {
                    results.add("Broadcast send warning (" + target + "): " + ex.getMessage());
                }
            }
            long deadline = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < deadline) {
                byte[] buffer = new byte[512];
                java.net.DatagramPacket response = new java.net.DatagramPacket(buffer, buffer.length);
                try {
                    sock.receive(response);
                } catch (java.net.SocketTimeoutException ex) {
                    break;
                } catch (java.net.SocketException ex) {
                    if (!networkScanRequested) {
                        break;
                    }
                    throw ex;
                }
                String raw = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8).trim();
                String[] parts = raw.split("\\|", -1);
                if (parts.length >= 4 && "UAM_HERE".equals(parts[0])) {
                    String ip = parts[1];
                    String tcpPort = parts[2];
                    String boardName = parts[3].isBlank() ? "unknown" : parts[3];
                    String host = response.getAddress().getCanonicalHostName();
                    results.add(ip + " | " + host + " | " + boardName + " | TCP " + tcpPort);
                }
            }
        } catch (Exception ex) {
            results.add("Scan failed: " + ex.getMessage());
        } finally {
            activeNetworkScanSocket = null;
        }
        if (results.isEmpty()) {
            results.add("No Arduino UDP replies this scan.");
        }
        List<String> finalResults = results;
        SwingUtilities.invokeLater(() -> {
            networkScanResultsModel.clear();
            finalResults.forEach(networkScanResultsModel::addElement);
            updateNetworkScanButtons();
        });
        log("[INFO] Network scan pass complete: " + String.join("; ", results));
    }

    private List<String> detectBroadcastTargets() {
        List<String> targets = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface netIf = interfaces.nextElement();
                if (!netIf.isUp() || netIf.isLoopback()) {
                    continue;
                }
                for (java.net.InterfaceAddress addr : netIf.getInterfaceAddresses()) {
                    InetAddress broadcast = addr.getBroadcast();
                    if (broadcast instanceof Inet4Address) {
                        String ip = broadcast.getHostAddress();
                        if (ip != null && !ip.isBlank() && !targets.contains(ip)) {
                            targets.add(ip);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return targets;
    }

    private void updateNetworkScanButtons() {
        boolean enabled = activeCommandProcess == null || !activeCommandProcess.isAlive();
        scanNetworkButton.setEnabled(enabled && !networkScanRequested);
        stopNetworkScanButton.setEnabled(enabled && networkScanRequested);
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
        UpdateCheckResult checkResult = checkForRemoteUpdate(repo);
        if (!checkResult.canProceed()) {
            log("[WARN] " + checkResult.message());
            JOptionPane.showMessageDialog(this, checkResult.message(), "Update check failed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkResult.updateAvailable()) {
            log("[INFO] " + checkResult.message());
            JOptionPane.showMessageDialog(this, checkResult.message(), "Already up to date", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String command = "cd " + escape(repo.toString()) + " && chmod +x update.sh UniversalMonitorControlCenter.sh && ./update.sh";
        runCommand(command, repo.toFile(), "Update and restart Control Center", true, true, () -> relaunchApplication(launcher));
    }

    private UpdateCheckResult checkForRemoteUpdate(Path repo) {
        String branch = runGitQuery(repo, "git rev-parse --abbrev-ref HEAD");
        if (branch == null || branch.isBlank() || "HEAD".equals(branch)) {
            return new UpdateCheckResult(false, false, "Could not determine the current git branch for the selected repo.");
        }

        String localHead = runGitQuery(repo, "git rev-parse HEAD");
        if (localHead == null || localHead.isBlank()) {
            return new UpdateCheckResult(false, false, "Could not read the local git commit for the selected repo.");
        }

        String remoteHeadLine = runGitQuery(repo, "git ls-remote --heads origin " + escape(branch));
        if (remoteHeadLine == null || remoteHeadLine.isBlank()) {
            return new UpdateCheckResult(false, false, "Could not contact origin/" + branch + " to check for updates.");
        }

        String remoteHead = remoteHeadLine.split("\\s+")[0].trim();
        if (remoteHead.isBlank()) {
            return new UpdateCheckResult(false, false, "Git returned an invalid remote hash for origin/" + branch + ".");
        }

        if (localHead.equals(remoteHead)) {
            return new UpdateCheckResult(true, false, "This project is already up to date on branch '" + branch + "'.");
        }

        return new UpdateCheckResult(true, true, "New updates are available on origin/" + branch + ". Running the update now.");
    }

    private String runGitQuery(Path repo, String commandText) {
        try {
            Process process = new ProcessBuilder("bash", "-lc", "cd " + escape(repo.toString()) + " && " + commandText)
                    .directory(repo.toFile())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                }
            }
            int code = process.waitFor();
            String outputText = output.toString().trim();
            if (code != 0) {
                log("[WARN] Git query failed (" + commandText + "): " + (outputText.isBlank() ? "exit code " + code : outputText));
                return null;
            }
            return outputText;
        } catch (Exception ex) {
            log("[WARN] Git query failed (" + commandText + "): " + ex.getMessage());
            return null;
        }
    }

    private record UpdateCheckResult(boolean canProceed, boolean updateAvailable, String message) {}

    private void runInstallAndDesktopWorkflow() {
        Path repo = repoPath();
        Path launcher = repo.resolve("UniversalMonitorControlCenter.sh");
        String command = "cd " + escape(repo.toString())
                + " && chmod +x install.sh install_control_center_desktop.sh UniversalMonitorControlCenter.sh"
                + " && RESET_LOCAL_STATE=1 SKIP_GIT_PULL=1 CONTROL_CENTER_NONINTERACTIVE=1 ./install.sh"
                + " && ./install_control_center_desktop.sh";
        runCommand(command, repo.toFile(), "Install monitor and desktop entry", true, true, () -> relaunchApplication(launcher));
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
        runCommand(command, repoPath().toFile(), scriptName, needsSudo, true, null, "arduino_install.sh".equals(scriptName));
    }

    private String buildRepoScriptCommand(String scriptName) {
        StringBuilder command = new StringBuilder();
        command.append("cd ").append(escape(repoPath().toString()))
                .append(" && chmod +x ").append(escape(scriptName))
                .append(" && ");

        if ("arduino_install.sh".equals(scriptName)) {
            String unoScreenSize = resolveUnoR3ScreenSizeForControlCenter();
            String megaScreenSize = resolveMegaScreenSizeForControlCenter();
            if ("".equals(unoScreenSize)) {
                return null;
            }
            if ("".equals(megaScreenSize)) {
                return null;
            }
            if (unoScreenSize != null && megaScreenSize != null) {
                command.append("UNO_R3_SCREEN_SIZE=").append(escape(unoScreenSize)).append(' ');
                command.append("MEGA_SCREEN_SIZE=").append(escape(megaScreenSize)).append(' ');
                String selectedProgramMode = normalizeProgramMode(String.valueOf(programModeSelector.getSelectedItem()));
                command.append("UASM_PROGRAM_MODE=").append(escape(selectedProgramMode)).append(' ');
            }
        }

        command.append("./").append(scriptName);
        return command.toString();
    }

    private void initializeRemoteActionState() {
        remoteActionSelector.removeAllItems();
        for (RemoteAction action : RemoteAction.values()) {
            remoteActionSelector.addItem(action.label);
        }
        remoteRepoField.setText(repoPath().toString());
        loadRemoteTargetProfiles();
        refreshRemoteProfileChoices();
        updateRemoteTargetFieldState();
    }

    private void runSelectedRemoteAction() {
        RemoteAction action = selectedRemoteAction();
        if (action == null) {
            log("[WARN] No CLI/remote action is selected.");
            return;
        }
        boolean overSsh = remoteUseSshToggle.isSelected();
        String command = buildRemoteActionCommand(action, repoPath().toString());
        String label = "CLI action: " + action.label + (overSsh ? " (SSH)" : " (local)");
        if (!overSsh) {
            runCommand(command, repoPath().toFile(), label, action.needsSudo, true);
            return;
        }

        String sshHost = remoteHostField.getText().trim();
        String sshUser = remoteUserField.getText().trim();
        String sshPort = remotePortField.getText().trim();
        String remoteRepo = remoteRepoField.getText().trim();
        if (sshHost.isBlank() || sshUser.isBlank()) {
            log("[WARN] SSH action canceled: fill both remote user and host.");
            return;
        }
        if (sshPort.isBlank()) {
            sshPort = "22";
            remotePortField.setText(sshPort);
        }
        if (remoteRepo.isBlank()) {
            log("[WARN] SSH action canceled: set the target project path.");
            return;
        }

        String remoteCommand = buildRemoteActionCommand(action, remoteRepo);
        String sshWrappedCommand = "ssh -p " + escape(sshPort) + " " + escape(sshUser + "@" + sshHost) + " " + escape(remoteCommand);
        runCommand(sshWrappedCommand, repoPath().toFile(), label + " -> " + sshUser + "@" + sshHost, false, true);
    }

    private RemoteAction selectedRemoteAction() {
        String selected = String.valueOf(remoteActionSelector.getSelectedItem());
        for (RemoteAction action : RemoteAction.values()) {
            if (action.label.equals(selected)) {
                return action;
            }
        }
        return null;
    }

    private String buildRemoteActionCommand(RemoteAction action, String targetRepoPath) {
        return switch (action) {
            case UPDATE_PROJECT -> "cd " + escape(targetRepoPath) + " && chmod +x update.sh && ./update.sh";
            case FLASH_ARDUINOS -> "cd " + escape(targetRepoPath) + " && chmod +x arduino_install.sh && ./arduino_install.sh";
            case RESTART_MONITOR_SERVICE -> "systemctl restart " + SERVICE_NAME;
            case SHOW_MONITOR_STATUS -> "systemctl status --no-pager " + SERVICE_NAME;
            case WIFI_DISCOVERY_DEBUG_LOGS ->
                    "journalctl -u " + SERVICE_NAME + " -n 200 --no-pager | grep -i 'wifi\\|discovery\\|udp\\|board' || true";
        };
    }

    private void updateRemoteTargetFieldState() {
        boolean sshEnabled = remoteUseSshToggle.isSelected();
        remoteHostField.setEnabled(sshEnabled);
        remoteUserField.setEnabled(sshEnabled);
        remotePortField.setEnabled(sshEnabled);
        remoteRepoField.setEnabled(sshEnabled);
        remoteProfileSelector.setEnabled(sshEnabled);
        saveRemoteProfileButton.setEnabled(sshEnabled);
        deleteRemoteProfileButton.setEnabled(sshEnabled);
    }

    private void saveCurrentRemoteTargetProfile() {
        if (!remoteUseSshToggle.isSelected()) {
            log("[WARN] Enable 'Run over SSH' before saving a remote target profile.");
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Target profile name:", "Save SSH Target", JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        String trimmedName = name.trim();
        if (trimmedName.isBlank()) {
            log("[WARN] Remote target profile was not saved because the name was blank.");
            return;
        }
        RemoteTargetProfile profile = new RemoteTargetProfile(
                remoteHostField.getText().trim(),
                remoteUserField.getText().trim(),
                remotePortField.getText().trim().isBlank() ? "22" : remotePortField.getText().trim(),
                remoteRepoField.getText().trim()
        );
        if (profile.host.isBlank() || profile.user.isBlank() || profile.repoPath.isBlank()) {
            log("[WARN] Fill user, host, and target project path before saving a remote target.");
            return;
        }
        remoteTargetProfiles.put(trimmedName, profile);
        persistRemoteTargetProfiles();
        refreshRemoteProfileChoices();
        remoteProfileSelector.setSelectedItem(trimmedName);
        log("[INFO] Saved SSH target profile '" + trimmedName + "'.");
    }

    private void deleteSelectedRemoteTargetProfile() {
        Object selectedItem = remoteProfileSelector.getSelectedItem();
        String selected = selectedItem == null ? "" : String.valueOf(selectedItem).trim();
        if (selected.isBlank() || "<none>".equals(selected)) {
            log("[WARN] Select a saved SSH target profile before deleting.");
            return;
        }
        if (remoteTargetProfiles.remove(selected) != null) {
            persistRemoteTargetProfiles();
            refreshRemoteProfileChoices();
            log("[INFO] Deleted SSH target profile '" + selected + "'.");
        }
    }

    private void applySelectedRemoteTargetProfile() {
        Object selectedItem = remoteProfileSelector.getSelectedItem();
        String selected = selectedItem == null ? "" : String.valueOf(selectedItem).trim();
        if (selected.isBlank() || "<none>".equals(selected)) {
            return;
        }
        RemoteTargetProfile profile = remoteTargetProfiles.get(selected);
        if (profile == null) {
            return;
        }
        remoteHostField.setText(profile.host);
        remoteUserField.setText(profile.user);
        remotePortField.setText(profile.port);
        remoteRepoField.setText(profile.repoPath);
    }

    private void loadRemoteTargetProfiles() {
        remoteTargetProfiles.clear();
        Path path = repoPath().resolve(REMOTE_TARGETS_FILE);
        if (!Files.isRegularFile(path)) {
            return;
        }
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            Set<String> names = new java.util.TreeSet<>();
            for (String key : properties.stringPropertyNames()) {
                int idx = key.indexOf('.');
                if (idx > 0) {
                    names.add(key.substring(0, idx));
                }
            }
            for (String name : names) {
                String host = properties.getProperty(name + ".host", "").trim();
                String user = properties.getProperty(name + ".user", "").trim();
                String port = properties.getProperty(name + ".port", "22").trim();
                String repoPathValue = properties.getProperty(name + ".repo", "").trim();
                if (!host.isBlank() && !user.isBlank() && !repoPathValue.isBlank()) {
                    remoteTargetProfiles.put(name, new RemoteTargetProfile(host, user, port.isBlank() ? "22" : port, repoPathValue));
                }
            }
        } catch (Exception ex) {
            log("[WARN] Failed to load saved remote targets: " + ex.getMessage());
        }
    }

    private void persistRemoteTargetProfiles() {
        Properties properties = new Properties();
        for (Map.Entry<String, RemoteTargetProfile> entry : remoteTargetProfiles.entrySet()) {
            String name = entry.getKey();
            RemoteTargetProfile profile = entry.getValue();
            properties.setProperty(name + ".host", profile.host);
            properties.setProperty(name + ".user", profile.user);
            properties.setProperty(name + ".port", profile.port);
            properties.setProperty(name + ".repo", profile.repoPath);
        }
        Path path = repoPath().resolve(REMOTE_TARGETS_FILE);
        try {
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                properties.store(writer, "Control Center saved SSH targets");
            }
        } catch (Exception ex) {
            log("[WARN] Failed to save remote targets: " + ex.getMessage());
        }
    }

    private void refreshRemoteProfileChoices() {
        remoteProfileSelector.removeAllItems();
        if (remoteTargetProfiles.isEmpty()) {
            remoteProfileSelector.addItem("<none>");
            return;
        }
        for (String name : remoteTargetProfiles.keySet()) {
            remoteProfileSelector.addItem(name);
        }
    }

    private record RemoteTargetProfile(String host, String user, String port, String repoPath) {}

    private enum RemoteAction {
        UPDATE_PROJECT("Update project", true),
        FLASH_ARDUINOS("Flash Arduinos", true),
        RESTART_MONITOR_SERVICE("Restart monitor service", true),
        SHOW_MONITOR_STATUS("Show monitor status", true),
        WIFI_DISCOVERY_DEBUG_LOGS("Run Wi-Fi discovery debug logs", true);

        final String label;
        final boolean needsSudo;

        RemoteAction(String label, boolean needsSudo) {
            this.label = label;
            this.needsSudo = needsSudo;
        }
    }

    private String resolveUnoR3ScreenSizeForControlCenter() {
        String unoScreenSize = resolveScreenSizeSelectorValue(unoR3ScreenSizeSelector, "35");
        log("[INFO] Control Center will flash Arduino UNO R3 boards with the "
                + ("35".equals(unoScreenSize) ? "3.5\"" : "2.8\"")
                + " TFT sketch. R4 boards are unchanged.");
        return unoScreenSize;
    }

    private String resolveMegaScreenSizeForControlCenter() {
        String megaScreenSize = resolveScreenSizeSelectorValue(megaScreenSizeSelector, "35");
        log("[INFO] Control Center will flash Arduino Mega boards with the "
                + ("35".equals(megaScreenSize) ? "3.5\"" : "2.8\"")
                + " TFT sketch.");
        return megaScreenSize;
    }

    private void runServiceCommand(String action) {
        String label = "service " + action;
        String command = "systemctl " + action + " " + SERVICE_NAME;
        runCommand(command, repoPath().toFile(), label, true, true);

        Timer delayed = new Timer(1200, e -> refreshServiceStatus(false));
        delayed.setRepeats(false);
        delayed.start();
    }

    private void setServiceStartupEnabled(boolean enabled) {
        String label = enabled ? "enable startup service" : "disable startup service";
        String command = enabled
                ? "systemctl enable " + SERVICE_NAME + " && systemctl start " + SERVICE_NAME
                : "systemctl disable " + SERVICE_NAME + " && systemctl stop " + SERVICE_NAME;
        runCommand(command, repoPath().toFile(), label, true, true);
        Timer delayed = new Timer(1400, e -> {
            refreshServiceStartupStatus(false);
            refreshServiceStatus(false);
        });
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
                    setServiceIndicator("RUNNING", new Color(24, 170, 24));
                } else if (status.contains("inactive") || status.contains("failed") || status.contains("unknown")) {
                    setServiceIndicator("STOPPED", new Color(190, 35, 35));
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

    private void refreshServiceStartupStatus(boolean allowPrompt) {
        Thread t = new Thread(() -> {
            CommandSpec spec = buildShellCommand("systemctl is-enabled " + SERVICE_NAME, true, allowPrompt);
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
                String status = out.toString().trim().toLowerCase(Locale.ROOT);

                if (code == 0 && (status.contains("enabled") || status.contains("static"))) {
                    setStartupIndicator("ENABLED", new Color(24, 170, 24));
                } else if (status.contains("disabled") || status.contains("masked")) {
                    setStartupIndicator("DISABLED", new Color(190, 35, 35));
                } else {
                    setStartupIndicator("UNKNOWN", Color.GRAY);
                }

                if (allowPrompt) {
                    log("[SERVICE] startup output: " + (status.isEmpty() ? "<no output>" : status.replace("\n", " | ")));
                }
            } catch (Exception ex) {
                setStartupIndicator("UNKNOWN", Color.GRAY);
                if (allowPrompt) {
                    log("[ERROR] Failed startup status check: " + ex.getMessage());
                }
            }
        }, "service-startup-status-thread");

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
        updatePreviewWifiStatus(wifiEnabled);
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
            updatePreviewWifiStatus(enabled);
        } catch (Exception ex) {
            setTransportIndicator("UNKNOWN", Color.GRAY);
            lastWifiEnabledState = null;
            updatePreviewWifiStatus(false);
            log("[WARN] Failed to refresh transport mode: " + ex.getMessage());
        }
    }

    private void updatePreviewWifiStatus(boolean wifiEnabled) {
        String hostname = detectLocalHostname();
        String ipAddress = resolveLocalIpv4Address();
        previewWifiStateLabel.setText(wifiEnabled ? "Enabled" : "Disabled");
        previewWifiStateLabel.setForeground(wifiEnabled ? new Color(24, 170, 24) : new Color(191, 120, 24));
        previewWifiHostnameLabel.setText(hostname);
        previewWifiIpLabel.setText(ipAddress);
        int port = parseWifiPort(wifiPortField.getText(), Integer.parseInt(DEFAULT_WIFI_PORT));
        fakeDisplayPanel.setPreviewWifiStatus(wifiEnabled, hostname, ipAddress, port);
    }

    private String detectLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            String envHost = System.getenv("HOSTNAME");
            return (envHost == null || envHost.isBlank()) ? "unknown-host" : envHost.trim();
        }
    }

    private String resolveLocalIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // Fall through to unknown state below.
        }
        return "No IPv4 address";
    }

    private Path configDir() {
        return repoPath().resolve("config");
    }

    private Path monitorDefaultConfigPath() {
        return configDir().resolve("monitor_config.default.json");
    }

    private Path monitorSharedConfigPath() {
        return configDir().resolve("monitor_config.json");
    }

    private Path monitorLocalConfigPath() {
        return configDir().resolve("monitor_config.local.json");
    }

    private Path wifiSettingsBackupPath() {
        return repoPath().resolve(WIFI_SETTINGS_BACKUP_FILE);
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
                "wifi_discovery_magic",
                "wifi_discovery_debug",
                "wifi_discovery_ignore_board_filter",
                "program_mode",
                "macro_trigger_model",
                "macro_entries",
                "r4_display_rotation",
                "r3_display_rotation",
                "mega_display_rotation",
                "uno_r3_screen_size",
                "mega_screen_size"
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
        String pattern = "\\\"" + key + "\\\"\\s*:\\s*(\\\"(?:\\\\.|[^\\\"])*\\\"|true|false|-?\\d+(?:\\.\\d+)?|\\[(?:.|\\R)*?\\])";
        String replacement = "\"" + key + "\": " + rawValue;
        return text.matches("(?s).*" + pattern + ".*")
                ? text.replaceAll(pattern, replacement)
                : appendConfigEntry(text, "  \"" + key + "\": " + rawValue);
    }

    private String normalizeProgramMode(String selectedValue) {
        if (PROGRAM_MODE_GAMING.equals(selectedValue)) {
            return PROGRAM_MODE_GAMING;
        }
        if (PROGRAM_MODE_MACRO.equals(selectedValue)) {
            return PROGRAM_MODE_MACRO;
        }
        return PROGRAM_MODE_SYSTEM_MONITOR;
    }

    private String normalizeMacroTriggerModel(String selectedValue) {
        List<String> allowed = List.of(
                "Whole-screen tap cycles entries",
                "Large left/right screen halves",
                "Dedicated full-screen zones",
                "External button trigger"
        );
        return allowed.contains(selectedValue) ? selectedValue : allowed.get(0);
    }

    private List<String> normalizeMacroEntries(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String line : rawText.split("\\R")) {
            String cleaned = line == null ? "" : line.strip();
            if (cleaned.isBlank()) {
                continue;
            }
            entries.add(cleaned);
            if (entries.size() >= MAX_MACRO_ENTRIES) {
                break;
            }
        }
        return entries;
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
                wifiConnectionModeSelector.setSelectedItem(WIFI_MODE_MANUAL);
                wifiDiscoveryDebugToggle.setSelected(false);
                wifiDiscoveryIgnoreBoardFilterToggle.setSelected(false);
                programModeSelector.setSelectedItem(PROGRAM_MODE_SYSTEM_MONITOR);
                macroTriggerModelSelector.setSelectedItem("Whole-screen tap cycles entries");
                macroEntriesArea.setText("");
                wifiHostField.setText("");
                wifiBoardNameField.setText(DEFAULT_WIFI_BOARD_NAME);
                wifiTargetHostField.setText("");
                wifiTargetHostnameField.setText("");
                setRotationSelectorValue(r4RotationSelector, DISPLAY_ROTATION_NORMAL);
                setRotationSelectorValue(r3RotationSelector, DISPLAY_ROTATION_NORMAL);
                setRotationSelectorValue(megaRotationSelector, DISPLAY_ROTATION_NORMAL);
                setScreenSizeSelectorValue(unoR3ScreenSizeSelector, "35");
                setScreenSizeSelectorValue(megaScreenSizeSelector, "35");
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
            boolean wifiDiscoveryDebug = readBooleanConfigValue(text, "wifi_discovery_debug", false);
            boolean wifiDiscoveryIgnoreBoardFilter = readBooleanConfigValue(text, "wifi_discovery_ignore_board_filter", false);
            String wifiHost = readStringConfigValue(text, "wifi_host", "");
            String selectedProgramMode = normalizeProgramMode(readStringConfigValue(text, "program_mode", PROGRAM_MODE_SYSTEM_MONITOR));
            String macroTriggerModel = normalizeMacroTriggerModel(readStringConfigValue(text, "macro_trigger_model", "Whole-screen tap cycles entries"));
            List<String> macroEntries = readStringArrayConfigValue(text, "macro_entries");
            arduinoPortSelector.setSelectedItem(arduinoPort == null || arduinoPort.isBlank() ? "AUTO" : arduinoPort);
            wifiPortField.setText(String.valueOf(wifiResolution.port()));
            wifiConnectionModeSelector.setSelectedItem(wifiAutoDiscovery ? WIFI_MODE_AUTO_DISCOVERY : WIFI_MODE_MANUAL);
            wifiDiscoveryDebugToggle.setSelected(wifiDiscoveryDebug);
            wifiDiscoveryIgnoreBoardFilterToggle.setSelected(wifiDiscoveryIgnoreBoardFilter);
            programModeSelector.setSelectedItem(selectedProgramMode);
            macroTriggerModelSelector.setSelectedItem(macroTriggerModel);
            macroEntriesArea.setText(String.join("\n", macroEntries));
            wifiHostField.setText(wifiHost == null ? "" : wifiHost.trim());
            wifiBoardNameField.setText(resolveEffectiveWifiHeaderValue("WIFI_DEVICE_NAME_VALUE", DEFAULT_WIFI_BOARD_NAME));
            wifiTargetHostField.setText(resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOST_VALUE", ""));
            wifiTargetHostnameField.setText(resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOSTNAME_VALUE", ""));
            setRotationSelectorValue(r4RotationSelector, resolveDisplayRotation(text, "r4_display_rotation"));
            setRotationSelectorValue(r3RotationSelector, resolveDisplayRotation(text, "r3_display_rotation"));
            setRotationSelectorValue(megaRotationSelector, resolveDisplayRotation(text, "mega_display_rotation"));
            setScreenSizeSelectorValue(unoR3ScreenSizeSelector, resolveScreenSizeConfig(text, "uno_r3_screen_size", "35"));
            setScreenSizeSelectorValue(megaScreenSizeSelector, resolveScreenSizeConfig(text, "mega_screen_size", "35"));
            wifiPortSourceLabel.setText("Effective source: " + wifiResolution.source());
            updateWifiHostFieldState();
            if (verbose) {
                log("[INFO] Loaded merged monitor connection settings (default/shared/local). Effective Wi-Fi TCP port source: "
                        + wifiResolution.source() + " (" + wifiResolution.port() + ")."
                        + " Connection mode=" + (wifiAutoDiscovery ? "fixed host/IP + UDP discovery fallback" : "fixed host/IP only")
                        + ", wifi_discovery_debug=" + wifiDiscoveryDebug
                        + ", wifi_discovery_ignore_board_filter=" + wifiDiscoveryIgnoreBoardFilter
                        + ", program_mode=" + selectedProgramMode
                        + ", macro_trigger_model=" + macroTriggerModel
                        + ", macro_entries=" + macroEntries.size()
                        + ", wifi_host=" + (wifiHost == null || wifiHost.isBlank() ? "<unset>" : wifiHost.trim()) + "."
                        + " Board name=" + normalizeWifiBoardName(wifiBoardNameField.getText().trim())
                        + ", target host/ip=" + wifiTargetHostField.getText().trim()
                        + ", target hostname=" + wifiTargetHostnameField.getText().trim()
                        + ", R4 rotation=" + selectedRotationSummary(r4RotationSelector)
                        + ", R3 rotation=" + selectedRotationSummary(r3RotationSelector)
                        + ", Mega rotation=" + selectedRotationSummary(megaRotationSelector) + ".");
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
            log("[WARN] Wi-Fi host/IP is required when Fixed Host/IP Only (Recommended) mode is selected.");
            return;
        }
        String wifiBoardName = normalizeWifiBoardName(wifiBoardNameField.getText() == null ? "" : wifiBoardNameField.getText().trim());
        int r4Rotation = selectedRotationValue(r4RotationSelector);
        int r3Rotation = selectedRotationValue(r3RotationSelector);
        int megaRotation = selectedRotationValue(megaRotationSelector);
        String unoScreenSize = resolveScreenSizeSelectorValue(unoR3ScreenSizeSelector, "35");
        String megaScreenSize = resolveScreenSizeSelectorValue(megaScreenSizeSelector, "35");
        String wifiTargetHost = wifiTargetHostField.getText() == null ? "" : wifiTargetHostField.getText().trim();
        String wifiTargetHostname = wifiTargetHostnameField.getText() == null ? "" : wifiTargetHostnameField.getText().trim();
        boolean wifiDiscoveryDebug = wifiDiscoveryDebugToggle.isSelected();
        boolean wifiDiscoveryIgnoreBoardFilter = wifiDiscoveryIgnoreBoardFilterToggle.isSelected();
        String programMode = normalizeProgramMode(String.valueOf(programModeSelector.getSelectedItem()));
        String macroTriggerModel = normalizeMacroTriggerModel(String.valueOf(macroTriggerModelSelector.getSelectedItem()));
        List<String> macroEntries = normalizeMacroEntries(macroEntriesArea.getText());

        boolean savedMonitorConfig = false;
        boolean syncedWifiHeader = false;
        Path configPath = monitorLocalConfigPath();
        try {
            String text = ensureWritableLocalMonitorConfig();
            String updated = upsertStringConfigValue(text, "arduino_port", arduinoPort);
            updated = upsertNumberConfigValue(updated, "wifi_port", wifiPort);
            updated = upsertStringConfigValue(updated, "wifi_host", wifiHost);
            updated = upsertBooleanConfigValue(updated, "wifi_auto_discovery", wifiAutoDiscovery);
            updated = upsertBooleanConfigValue(updated, "wifi_discovery_debug", wifiDiscoveryDebug);
            updated = upsertBooleanConfigValue(updated, "wifi_discovery_ignore_board_filter", wifiDiscoveryIgnoreBoardFilter);
            updated = upsertBooleanConfigValue(updated, "wifi_enabled", true);
            updated = upsertBooleanConfigValue(updated, "prefer_usb", false);
            updated = upsertStringConfigValue(updated, "program_mode", programMode);
            updated = upsertStringConfigValue(updated, "macro_trigger_model", macroTriggerModel);
            updated = upsertStringArrayConfigValue(updated, "macro_entries", macroEntries);
            updated = upsertNumberConfigValue(updated, "r4_display_rotation", r4Rotation);
            updated = upsertNumberConfigValue(updated, "r3_display_rotation", r3Rotation);
            updated = upsertNumberConfigValue(updated, "mega_display_rotation", megaRotation);
            updated = upsertStringConfigValue(updated, "uno_r3_screen_size", unoScreenSize);
            updated = upsertStringConfigValue(updated, "mega_screen_size", megaScreenSize);
            if (!updated.equals(text)) {
                Files.writeString(configPath, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Saved machine-local monitor settings to " + configPath
                        + " (arduino_port=" + arduinoPort
                        + ", wifi_enabled=true, prefer_usb=false, wifi_port=" + wifiPort
                        + ", wifi_auto_discovery=" + wifiAutoDiscovery
                        + ", wifi_discovery_debug=" + wifiDiscoveryDebug
                        + ", wifi_discovery_ignore_board_filter=" + wifiDiscoveryIgnoreBoardFilter
                        + ", program_mode=" + programMode
                        + ", macro_trigger_model=" + macroTriggerModel
                        + ", macro_entries=" + macroEntries.size()
                        + ", wifi_host=" + (wifiHost.isBlank() ? "<unset>" : wifiHost)
                        + ", r4_display_rotation=" + r4Rotation
                        + ", r3_display_rotation=" + r3Rotation
                        + ", mega_display_rotation=" + megaRotation
                        + ", uno_r3_screen_size=" + unoScreenSize
                        + ", mega_screen_size=" + megaScreenSize + ").");
            } else {
                log("[INFO] Machine-local monitor settings already matched in " + configPath + " (Wi-Fi remains preferred over USB).");
            }
            savedMonitorConfig = true;
        } catch (Exception ex) {
            log("[WARN] Failed to save machine-local monitor settings to " + configPath + ": " + ex.getMessage());
        }

        syncedWifiHeader = syncWifiHeaderIntoLocalHeader(wifiPort, wifiBoardName, wifiTargetHost, wifiTargetHostname);
        boolean syncedDisplayHeaders = syncDisplayRotationHeaders(r4Rotation, r3Rotation, megaRotation);
        boolean syncedPageHeaders = saveBoardPageSettingsAndSyncHeaders(false);
        refreshWifiCredentialsIndicator(false);

        if (!savedMonitorConfig) {
            return;
        }

        setTransportIndicator("WIFI", new Color(24, 170, 24));
        if (syncedWifiHeader) {
            log("[INFO] Synced Wi-Fi port/pairing settings into wifi_config.local.h so the flashed sketch uses the matching board identity immediately.");
        }
        if (syncedDisplayHeaders) {
            log("[INFO] Synced per-board display rotation headers so the next compile enforces the saved GUI rotation for R4, R3, and Mega boards.");
        }
        if (syncedPageHeaders) {
            log("[INFO] Synced board page toggle headers so disabled pages are skipped during on-device navigation.");
        }
        log("[INFO] Save Monitor Settings now recompiles/uploads the R4 WiFi sketch so TCP port " + wifiPort
                + ", connection mode " + (wifiAutoDiscovery ? "Auto Discovery (UDP)" : "Manual / Fixed IP")
                + ", program mode " + programMode
                + ", macro trigger model " + macroTriggerModel
                + ", macro entries " + macroEntries.size()
                + ", wifi host/ip " + (wifiAutoDiscovery || wifiHost.isBlank() ? "<unset>" : wifiHost)
                + ", board name " + wifiBoardName + ", target host/ip " + (wifiTargetHost.isBlank() ? "<unset>" : wifiTargetHost)
                + ", target hostname " + (wifiTargetHostname.isBlank() ? "<unset>" : wifiTargetHostname)
                + ", R4 rotation " + rotationLabel(r4Rotation)
                + ", R3 rotation " + rotationLabel(r3Rotation)
                + ", and Mega rotation " + rotationLabel(megaRotation) + " apply right away.");
        reflashWifiBoardsAndRestartMonitor(wifiPort);
    }

    private void resetWifiPairing() {
        syncDashboardSudoPassword();
        List<DetectedBoard> wifiBoards = detectConnectedBoards().stream()
                .filter(board -> "arduino:renesas_uno:unor4wifi".equals(board.fqbn))
                .toList();
        if (wifiBoards.isEmpty()) {
            log("[WARN] No Arduino UNO R4 WiFi boards are connected over USB, so Wi-Fi pairing could not be reset.");
            return;
        }

        DetectedBoard selectedBoard = chooseWifiBoardForPairingReset(wifiBoards);
        if (selectedBoard == null) {
            log("[INFO] Wi-Fi pairing reset canceled.");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "<html><b>Reset Wi-Fi pairing for " + selectedBoard + "?</b><br>"
                        + "This clears the saved EEPROM pairing only.<br>"
                        + "The next PC that connects can become the new paired device.<br><br>"
                        + "If the flashed sketch still has explicit target host/hostname values, those flashed values will still win until you clear or change them.</html>",
                "Reset Wi-Fi Pairing",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirm != JOptionPane.OK_OPTION) {
            log("[INFO] Wi-Fi pairing reset canceled before sending the reset command.");
            return;
        }

        setActionButtons(false);
        Thread worker = new Thread(() -> {
            boolean resetSucceeded = false;
            try {
                if (!runShellCommandSync("systemctl stop " + SERVICE_NAME,
                        "Stop monitor service for Wi-Fi pairing reset", true, true)) {
                    return;
                }
                resetSucceeded = sendWifiPairingResetCommand(selectedBoard);
            } finally {
                runShellCommandSync("systemctl start " + SERVICE_NAME,
                        "Start monitor service after Wi-Fi pairing reset", true, false);
                refreshServiceStatus(false);
                setActionButtons(true);
            }

            if (resetSucceeded) {
                log("[INFO] Wi-Fi pairing reset finished for " + selectedBoard + ". The next monitor PC may claim the board without reflashing.");
            }
        }, "wifi-pairing-reset");
        worker.setDaemon(true);
        worker.start();
    }

    private DetectedBoard chooseWifiBoardForPairingReset(List<DetectedBoard> wifiBoards) {
        if (wifiBoards.size() == 1) {
            return wifiBoards.get(0);
        }
        Object choice = JOptionPane.showInputDialog(
                this,
                "Choose the UNO R4 WiFi board to reset pairing on:",
                "Select Wi-Fi Board",
                JOptionPane.PLAIN_MESSAGE,
                null,
                wifiBoards.toArray(),
                wifiBoards.get(0)
        );
        if (choice instanceof DetectedBoard board) {
            return board;
        }
        return null;
    }

    private boolean sendWifiPairingResetCommand(DetectedBoard board) {
        SerialPort port = SerialPort.getCommPort(board.port);
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 700, 0);
        if (!port.openPort()) {
            log("[ERROR] Failed to open " + board.port + " for Wi-Fi pairing reset. Disconnect other apps/services from the board and try again.");
            return false;
        }

        try {
            log("[INFO] Opened " + board.port + " for explicit Wi-Fi pairing reset.");
            Thread.sleep(1800L);
            try {
                port.flushIOBuffers();
            } catch (Exception ignored) {
                // Best effort only.
            }

            String command = "CMD|RESET_WIFI_PAIRING|CONFIRM\n";
            byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
            int written = port.writeBytes(bytes, bytes.length);
            if (written != bytes.length) {
                log("[ERROR] Failed to send the Wi-Fi pairing reset command to " + board.port + ".");
                return false;
            }
            log("[INFO] Sent explicit Wi-Fi pairing reset command over USB to " + board + ".");

            long deadline = System.currentTimeMillis() + 6000L;
            BufferedReader reader = new BufferedReader(new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8));
            try {
                while (System.currentTimeMillis() < deadline) {
                    String line;
                    try {
                        line = reader.readLine();
                    } catch (IOException ex) {
                        log("[WARN] Waiting for Wi-Fi pairing reset response on " + board.port + ": " + ex.getMessage());
                        continue;
                    }
                    if (line == null) {
                        continue;
                    }
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    log("[PAIRING RESET] " + trimmed);
                    if ("PAIRING_RESET:OK".equals(trimmed)) {
                        return true;
                    }
                    if ("PAIRING_RESET:EEPROM_CLEARED_FLASH_TARGET_STILL_ACTIVE".equals(trimmed)) {
                        log("[WARN] EEPROM pairing was cleared on " + board + ", but flashed WIFI_TARGET_* values are still set. Update those if you want a different PC to claim the board next.");
                        return true;
                    }
                }
            } finally {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Port shutdown already handles the important cleanup.
                }
            }

            log("[ERROR] Timed out waiting for Wi-Fi pairing reset confirmation from " + board + ".");
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("[ERROR] Wi-Fi pairing reset was interrupted: " + ex.getMessage());
            return false;
        } finally {
            port.closePort();
        }
    }

    private boolean persistCurrentDisplayRotationSelections(boolean verbose, boolean syncHeaders) {
        int r4Rotation = selectedRotationValue(r4RotationSelector);
        int r3Rotation = selectedRotationValue(r3RotationSelector);
        int megaRotation = selectedRotationValue(megaRotationSelector);
        Path configPath = monitorLocalConfigPath();
        boolean saved = false;
        try {
            String text = ensureWritableLocalMonitorConfig();
            String updated = upsertNumberConfigValue(text, "r4_display_rotation", r4Rotation);
            updated = upsertNumberConfigValue(updated, "r3_display_rotation", r3Rotation);
            updated = upsertNumberConfigValue(updated, "mega_display_rotation", megaRotation);
            if (!updated.equals(text)) {
                Files.writeString(configPath, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                if (verbose) {
                    log("[INFO] Saved per-board display rotation settings to " + configPath
                            + " (r4_display_rotation=" + r4Rotation
                            + ", r3_display_rotation=" + r3Rotation
                            + ", mega_display_rotation=" + megaRotation + ").");
                }
            } else if (verbose) {
                log("[INFO] Per-board display rotation settings already matched in " + configPath + ".");
            }
            saved = true;
        } catch (Exception ex) {
            log("[WARN] Failed to persist display rotation settings to " + configPath + ": " + ex.getMessage());
        }

        if (syncHeaders) {
            boolean synced = syncDisplayRotationHeaders(r4Rotation, r3Rotation, megaRotation);
            if (verbose && synced) {
                log("[INFO] Synced per-board display rotation headers from the current GUI selection for R4, R3, and Mega boards.");
            }
            return saved && synced;
        }
        return saved;
    }

    private int resolveDisplayRotation(String text, String key) {
        int raw = readIntConfigValue(text, key, DISPLAY_ROTATION_NORMAL);
        return raw == DISPLAY_ROTATION_FLIPPED ? DISPLAY_ROTATION_FLIPPED : DISPLAY_ROTATION_NORMAL;
    }

    private static String rotationLabel(int rotation) {
        return rotation == DISPLAY_ROTATION_FLIPPED ? "Flipped (setRotation(3))" : "Normal (setRotation(1))";
    }

    private void setRotationSelectorValue(JComboBox<String> selector, int rotation) {
        selector.setSelectedItem(rotationLabel(rotation));
    }

    private int selectedRotationValue(JComboBox<String> selector) {
        Object selected = selector.getSelectedItem();
        return selected != null && selected.toString().startsWith("Flipped")
                ? DISPLAY_ROTATION_FLIPPED
                : DISPLAY_ROTATION_NORMAL;
    }

    private String selectedRotationSummary(JComboBox<String> selector) {
        return rotationLabel(selectedRotationValue(selector));
    }

    private String resolveScreenSizeConfig(String text, String key, String fallback) {
        String value = readStringConfigValue(text, key, fallback);
        return "28".equals(value) ? "28" : "35";
    }

    private void setScreenSizeSelectorValue(JComboBox<String> selector, String sizeValue) {
        selector.setSelectedItem("28".equals(sizeValue) ? "2.8\" mode" : "3.5\" mode");
    }

    private String resolveScreenSizeSelectorValue(JComboBox<String> selector, String fallback) {
        Object selected = selector.getSelectedItem();
        if (selected != null && selected.toString().startsWith("2.8")) {
            return "28";
        }
        return "28".equals(fallback) ? "28" : "35";
    }

    private Path r4DisplayConfigPath() {
        return repoPath().resolve("R4_WIFI35/display_config.local.h");
    }

    private Path r3DisplayConfig28Path() {
        return repoPath().resolve("R3_MonitorScreen28/display_config.local.h");
    }

    private Path r3DisplayConfig35Path() {
        return repoPath().resolve("R3_MonitorScreen35/display_config.local.h");
    }

    private Path megaDisplayConfigPath() {
        return repoPath().resolve("R3_MEGA_MonitorScreen35/display_config.local.h");
    }

    private boolean writeDisplayRotationHeader(Path target, int rotation) {
        int normalized = rotation == DISPLAY_ROTATION_FLIPPED ? DISPLAY_ROTATION_FLIPPED : DISPLAY_ROTATION_NORMAL;
        String header = "#pragma once\n\n#define DISPLAY_ROTATION_VALUE " + normalized + "\n";
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return true;
        } catch (IOException ex) {
            log("[WARN] Failed to write display rotation config " + target + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean syncDisplayRotationHeaders(int r4Rotation, int r3Rotation, int megaRotation) {
        boolean ok = true;
        ok &= writeDisplayRotationHeader(r4DisplayConfigPath(), r4Rotation);
        ok &= writeDisplayRotationHeader(r3DisplayConfig28Path(), r3Rotation);
        ok &= writeDisplayRotationHeader(r3DisplayConfig35Path(), r3Rotation);
        ok &= writeDisplayRotationHeader(megaDisplayConfigPath(), megaRotation);
        return ok;
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
                log("[INFO] Updated config/monitor_config.local.json: " + key + "=" + enabled);
            } else {
                log("[INFO] config/monitor_config.local.json already had " + key + "=" + enabled + ".");
            }
            return true;
        } catch (Exception ex) {
            log("[WARN] Could not update config/monitor_config.local.json entry " + key + ": " + ex.getMessage());
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

    private List<String> readStringArrayConfigValue(String text, String key) {
        String quotedKey = "\"" + key + "\"";
        int keyIndex = text.indexOf(quotedKey);
        if (keyIndex < 0) {
            return List.of();
        }
        int colonIndex = text.indexOf(':', keyIndex + quotedKey.length());
        if (colonIndex < 0) {
            return List.of();
        }
        int openIndex = text.indexOf('[', colonIndex + 1);
        if (openIndex < 0) {
            return List.of();
        }
        int closeIndex = text.indexOf(']', openIndex + 1);
        if (closeIndex < 0) {
            return List.of();
        }
        String raw = text.substring(openIndex + 1, closeIndex);
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"((?:\\\\.|[^\\\\\"])*)\"").matcher(raw);
        while (matcher.find()) {
            String value = matcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r");
            if (!value.isBlank()) {
                values.add(value.strip());
            }
            if (values.size() >= MAX_MACRO_ENTRIES) {
                break;
            }
        }
        return values;
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

    private String upsertStringArrayConfigValue(String text, String key, List<String> values) {
        String rawValue = values.stream()
                .map(value -> "\"" + escapeJson(value) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        return upsertRawConfigValue(text, key, "[" + rawValue + "]");
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
        boolean fixedOnlyMode = WIFI_MODE_MANUAL.equals(wifiConnectionModeSelector.getSelectedItem());
        wifiHostField.setEnabled(true);
        wifiHostField.setEditable(true);
        if (fixedOnlyMode) {
            wifiHostField.setToolTipText("Fixed Host/IP Only (Recommended) mode is on. Enter the exact Arduino address that this PC should use, like 192.168.1.50.");
        } else {
            wifiHostField.setToolTipText("Discovery fallback mode is on. Fixed host/IP is still attempted first when set; UDP discovery is only a fallback/debug path.");
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
            return new WifiPortResolution(parseWifiPort(localRawPort, 5000), "config/monitor_config.local.json");
        }

        for (Path path : List.of(monitorSharedConfigPath(), monitorDefaultConfigPath())) {
            String sourceText = readConfigFileIfPresent(path);
            String rawPort = sourceText == null ? null : findRawConfigValue(sourceText, "wifi_port");
            if (rawPort != null && !rawPort.isBlank()) {
                return new WifiPortResolution(parseWifiPort(rawPort, 5000), "shared JSON config (" + path.getFileName() + ")");
            }
        }

        return new WifiPortResolution(parseWifiPort(DEFAULT_WIFI_PORT, 5000), "merged JSON config default");
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

    private WifiSettingsSnapshot loadSavedWifiSettings() {
        Properties properties = new Properties();
        Path backupPath = wifiSettingsBackupPath();
        if (Files.exists(backupPath)) {
            try (var input = Files.newInputStream(backupPath)) {
                properties.load(input);
            } catch (IOException ex) {
                log("[WARN] Failed to read saved Wi-Fi settings backup " + backupPath + ": " + ex.getMessage());
            }
        }

        String ssid = firstNonBlank(
                properties.getProperty("ssid"),
                readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_SSID_VALUE", ""),
                readWifiHeaderDefine(wifiDefaultConfigPath(), "WIFI_SSID_VALUE", "")
        );
        String password = firstNonBlank(
                properties.getProperty("password"),
                readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_PASS_VALUE", ""),
                readWifiHeaderDefine(wifiDefaultConfigPath(), "WIFI_PASS_VALUE", "")
        );
        String tcpPort = DEFAULT_WIFI_PORT;
        try {
            tcpPort = String.valueOf(resolveEffectiveWifiPort().port());
        } catch (IOException ex) {
            log("[WARN] Failed to resolve active Wi-Fi TCP port from monitor config: " + ex.getMessage());
            tcpPort = firstNonBlank(
                    properties.getProperty("tcp_port"),
                    readWifiHeaderDefine(wifiLocalConfigPath(), "WIFI_TCP_PORT_VALUE", ""),
                    readWifiHeaderDefine(wifiDefaultConfigPath(), "WIFI_TCP_PORT_VALUE", DEFAULT_WIFI_PORT),
                    DEFAULT_WIFI_PORT
            );
        }
        String boardName = normalizeWifiBoardName(firstNonBlank(
                properties.getProperty("board_name"),
                resolveEffectiveWifiHeaderValue("WIFI_DEVICE_NAME_VALUE", DEFAULT_WIFI_BOARD_NAME),
                DEFAULT_WIFI_BOARD_NAME
        ));
        String targetHost = firstNonBlank(
                properties.getProperty("target_host"),
                resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOST_VALUE", ""),
                ""
        );
        String targetHostname = firstNonBlank(
                properties.getProperty("target_hostname"),
                resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOSTNAME_VALUE", ""),
                ""
        );

        if ("YOUR_WIFI_SSID".equals(ssid)) {
            ssid = "";
        }
        if ("YOUR_WIFI_PASSWORD".equals(password)) {
            password = "";
        }

        return new WifiSettingsSnapshot(ssid, password, tcpPort, boardName, targetHost, targetHostname);
    }

    private boolean saveWifiSettingsBackup(String ssid, String password, int tcpPort, String boardName, String targetHost, String targetHostname) {
        Path backupPath = wifiSettingsBackupPath();
        Properties properties = new Properties();
        properties.setProperty("ssid", ssid == null ? "" : ssid.trim());
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("tcp_port", String.valueOf(tcpPort));
        properties.setProperty("board_name", normalizeWifiBoardName(boardName));
        properties.setProperty("target_host", targetHost == null ? "" : targetHost.trim());
        properties.setProperty("target_hostname", targetHostname == null ? "" : targetHostname.trim());
        try {
            try (var output = Files.newOutputStream(
                    backupPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                properties.store(output, "Control Center Wi-Fi settings backup");
            }
            log("[INFO] Saved local Wi-Fi settings backup to " + backupPath + ".");
            return true;
        } catch (IOException ex) {
            log("[WARN] Failed to write Wi-Fi settings backup " + backupPath + ": " + ex.getMessage());
            return false;
        }
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
        WifiSettingsSnapshot snapshot = loadSavedWifiSettings();
        boolean savedHeader = saveWifiHeaderSettings(
                snapshot.ssid(),
                snapshot.password(),
                tcpPort,
                boardName,
                targetHost,
                targetHostname
        );
        boolean savedBackup = saveWifiSettingsBackup(
                snapshot.ssid(),
                snapshot.password(),
                tcpPort,
                boardName,
                targetHost,
                targetHostname
        );
        return savedHeader && savedBackup;
    }

    private void restartMonitorServiceForSettingsChange() {
        log("[INFO] Restarting the monitor service so the new Arduino/Wi-Fi port settings apply now.");
        cycleServiceForTransportChange();
    }

    private void reflashWifiBoardsAndRestartMonitor(int wifiPort) {
        syncDisplayRotationHeaders(selectedRotationValue(r4RotationSelector), selectedRotationValue(r3RotationSelector), selectedRotationValue(megaRotationSelector));
        String arduinoCli = ensureArduinoCliAvailable("reflash the UNO R4 WiFi monitor sketch");
        if (arduinoCli == null) {
            log("[WARN] Reflash skipped. TCP port " + wifiPort
                    + " was still saved into config/monitor_config.local.json and wifi_config.local.h.");
            restartMonitorServiceForSettingsChange();
            return;
        }

        List<DetectedBoard> wifiBoards = detectConnectedBoards().stream()
                .filter(board -> "arduino:renesas_uno:unor4wifi".equals(board.fqbn))
                .toList();
        if (wifiBoards.isEmpty()) {
            log("[WARN] No Arduino UNO R4 WiFi boards were detected, so TCP port " + wifiPort
                    + " was only saved into config/monitor_config.local.json and wifi_config.local.h.");
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
                .append(buildArduinoDependencyInstallCommand(arduinoCli))
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
                }, true);
    }

    private void refreshWifiCredentialsIndicator(boolean verbose) {
        WifiSettingsSnapshot snapshot = loadSavedWifiSettings();
        boolean saved = snapshot.hasCredentials();
        if (!saved && verbose) {
            log("[INFO] No saved Wi-Fi credentials were found in the local backup or Wi-Fi header files.");
        }

        final boolean credentialsSaved = saved;
        SwingUtilities.invokeLater(() -> {
            wifiCredentialsIndicator.setText(credentialsSaved ? "Credentials Saved" : "Credentials not saved");
            wifiCredentialsIndicator.setBackground(credentialsSaved ? new Color(24, 170, 24) : new Color(170, 80, 24));
            wifiCredentialsIndicator.setForeground(Color.WHITE);
        });
    }

    private String buildArduinoDependencyInstallCommand(String arduinoCli) {
        String cli = escape(arduinoCli);
        StringBuilder command = new StringBuilder();
        command.append("index_updated=0");

        for (String corePackage : REQUIRED_ARDUINO_CORE_PACKAGES) {
            command.append(" && if ! ").append(cli).append(" core list | grep -q '^")
                    .append(escapeSingleQuotes(corePackage)).append("'; then ")
                    .append("echo \"[INFO] Installing missing Arduino core ").append(escapeDoubleQuotes(corePackage)).append(".\"")
                    .append(" && if [ \"$index_updated\" -eq 0 ]; then ")
                    .append(cli).append(" core update-index && index_updated=1; fi")
                    .append(" && ").append(cli).append(" core install ").append(escape(corePackage))
                    .append("; else echo \"[INFO] Arduino core ").append(escapeDoubleQuotes(corePackage)).append(" already installed.\"; fi");
        }

        for (ArduinoLibraryRequirement requirement : REQUIRED_ARDUINO_LIBRARIES) {
            command.append(" && if ")
                    .append(cli).append(" lib list | grep -Fqi ").append(escape(requirement.detectName()))
                    .append(" || find \"$HOME/Arduino/libraries\" -maxdepth 4 -type f -name ")
                    .append(escape(requirement.headerName()))
                    .append(" 2>/dev/null | grep -q .; then ")
                    .append("echo \"[INFO] Arduino library ").append(escapeDoubleQuotes(requirement.installName())).append(" already installed.\"")
                    .append("; else ")
                    .append("echo \"[INFO] Installing missing Arduino library ").append(escapeDoubleQuotes(requirement.installName())).append(".\"")
                    .append(" && if [ \"$index_updated\" -eq 0 ]; then ")
                    .append(cli).append(" lib update-index && index_updated=1; fi")
                    .append(" && (")
                    .append(cli).append(" lib install ").append(escape(requirement.installName()))
                    .append(" || ").append(cli).append(" lib install ").append(escape(requirement.detectName()))
                    .append(")")
                    .append("; fi");
        }

        return command.toString();
    }

    private String escapeSingleQuotes(String value) {
        return value.replace("\\", "\\\\").replace("'", "'\"'\"'");
    }

    private String escapeDoubleQuotes(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
        JTextField ssidField = new JTextField(24);
        JPasswordField passwordField = new JPasswordField(24);
        JTextField tcpPortField = new JTextField(8);
        JTextField boardNameField = new JTextField(24);
        JTextField targetHostField = new JTextField(24);
        JTextField targetHostnameField = new JTextField(24);

        WifiSettingsSnapshot snapshot = loadSavedWifiSettings();
        ssidField.setText(snapshot.ssid());
        passwordField.setText(snapshot.password());
        tcpPortField.setText(snapshot.tcpPort());
        boardNameField.setText(snapshot.boardName());
        targetHostField.setText(snapshot.targetHost());
        targetHostnameField.setText(snapshot.targetHostname());

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

        boolean savedBackup = saveWifiSettingsBackup(ssid, password, tcpPort, boardName, targetHost, targetHostname);
        boolean savedHeader = saveWifiHeaderSettings(ssid, password, tcpPort, boardName, targetHost, targetHostname);
        Path configPath = monitorLocalConfigPath();
        boolean savedMonitorConfig = false;
        try {
            String text = ensureWritableLocalMonitorConfig();
            String updated = upsertNumberConfigValue(text, "wifi_port", tcpPort);
            if (!updated.equals(text)) {
                Files.writeString(configPath, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Synced config/monitor_config.local.json to Wi-Fi TCP port " + tcpPort + ".");
            } else {
                log("[INFO] config/monitor_config.local.json already matched Wi-Fi TCP port " + tcpPort + ".");
            }
            savedMonitorConfig = true;
        } catch (IOException ex) {
            log("[WARN] Failed to sync config/monitor_config.local.json Wi-Fi port: " + ex.getMessage());
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
            if (savedBackup) {
                log("[INFO] The Control Center also saved a local backup file so SSID/password and pairing values come back after reopening or updating.");
            }
            log("[INFO] Settings were written to wifi_config.local.h and the local backup file for safe testing/pushing.");
            syncDisplayRotationHeaders(selectedRotationValue(r4RotationSelector), selectedRotationValue(r3RotationSelector), selectedRotationValue(megaRotationSelector));
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
                log("[INFO] Updated config/monitor_config.local.json: debug_enabled=" + enabled + (applyPort ? ", debug_port=" + fakeIn : ""));
            } else {
                log("[INFO] config/monitor_config.local.json already had requested debug settings.");
            }
            return true;
        } catch (Exception ex) {
            log("[WARN] Could not update config/monitor_config.local.json debug settings: " + ex.getMessage());
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
            Color criticalButtonBackground = darkMode ? darkCriticalButtonBackground : lightCriticalButtonBackground;
            Color positiveButtonBackground = darkMode ? darkPositiveButtonBackground : lightPositiveButtonBackground;

            getContentPane().setBackground(background);
            styleComponentTree(getContentPane(), background, panelBackground, textColor, accent, fieldBackground, buttonBackground, criticalButtonBackground, positiveButtonBackground);
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
                                    Color fieldBackground, Color buttonBackground, Color criticalButtonBackground, Color positiveButtonBackground) {
        if (component instanceof JPanel panel) {
            panel.setOpaque(true);
            panel.setBackground(panelBackground);
            if (panel.getBorder() instanceof javax.swing.border.TitledBorder titledBorder) {
                titledBorder.setTitleColor(textColor);
            } else if (Boolean.TRUE.equals(panel.getClientProperty("uasmSettingsPanel"))) {
                panel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(accent, 1, true),
                        BorderFactory.createEmptyBorder(6, 6, 6, 6)
                ));
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
        } else if (component instanceof JCheckBox checkBox) {
            checkBox.setOpaque(true);
            checkBox.setBackground(panelBackground);
            checkBox.setForeground(textColor);
            checkBox.setContentAreaFilled(false);
            checkBox.setFocusPainted(false);
            if (Boolean.TRUE.equals(checkBox.getClientProperty("uasmProfilePageToggle"))) {
                checkBox.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(accent, 2, true),
                        BorderFactory.createEmptyBorder(4, 8, 4, 8)
                ));
            } else {
                checkBox.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            }
        } else if (component instanceof AbstractButton button) {
            Color resolvedButtonBackground = button == flashButton
                    ? criticalButtonBackground
                    : (button == updateButton ? positiveButtonBackground : buttonBackground);
            button.setBackground(resolvedButtonBackground);
            button.setForeground(textColor);
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(
                            (button == flashButton || button == updateButton)
                                    ? resolvedButtonBackground.darker() : accent
                    ),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
            ));
            button.setOpaque(true);
        } else if (component instanceof JLabel label) {
            if (label != serviceIndicator && label != startupIndicator && label != debugIndicator
                    && label != transportIndicator && label != flashTransportIndicator) {
                label.setForeground(textColor);
            }
            if (Boolean.TRUE.equals(label.getClientProperty("uasmSettingsHelpBlock"))) {
                label.setOpaque(true);
                label.setBackground(fieldBackground);
                label.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(accent, 1, true),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8)
                ));
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
                styleComponentTree(child, background, panelBackground, textColor, accent, fieldBackground, buttonBackground, criticalButtonBackground, positiveButtonBackground);
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

        String flashWork = buildCustomUploadCommand(arduinoCli, target, sketchPath);
        String command = buildManagedFlashCommand(flashWork);
        runCommand(command, repoPath().toFile(), "Upload custom sketch to " + target.port, true, true, null, true);
    }

    private String buildCustomUploadCommand(String arduinoCli, DetectedBoard target, Path sketchPath) {
        String syncScript = repoPath().resolve("scripts/sync_version.py").toString();
        return "python3 " + escape(syncScript) + " --sync"
                + " && " + escape(arduinoCli) + " compile --fqbn "
                + escape(target.fqbn) + " " + escape(sketchPath.toString())
                + " && port=" + escape(target.port)
                + " && fqbn=" + escape(target.fqbn)
                + " && sketch=" + escape(sketchPath.toString())
                + " && cli=" + escape(arduinoCli)
                + " && upload_once() { \"$cli\" upload -p \"$port\" --fqbn \"$fqbn\" \"$sketch\"; }"
                + " && if upload_once; then true"
                + " elif [[ \"$fqbn\" == 'arduino:renesas_uno:unor4wifi' ]]; then "
                + "echo 'UNO R4 WiFi upload reset detected; waiting for /dev/ttyACM* to re-enumerate...'; "
                + "uploaded=0; "
                + "for _ in $(seq 1 18); do "
                + "new_port=$(\"$cli\" board list | awk 'NR>1 && /Arduino UNO R4 WiFi|arduino:renesas_uno:unor4wifi|UNO R4 WiFi/ {print $1; exit}'); "
                + "if [[ -n \"$new_port\" ]]; then port=\"$new_port\"; echo \"Retrying upload on $port\"; if upload_once; then uploaded=1; break; fi; fi; "
                + "sleep 1; "
                + "done; "
                + "[[ $uploaded -eq 1 ]] || { echo 'UNO R4 WiFi did not re-enumerate in time.'; exit 1; }"
                + " else exit 1; fi";
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
        Path localCli = Paths.get(System.getProperty("user.home"), ".local", "bin", "arduino-cli");

        if (Files.isRegularFile(localCli) && Files.isExecutable(localCli)) {
            log("[INFO] Reusing existing arduino-cli at " + localCli + " by adding ~/.local/bin to PATH for this run.");
            return localCli.toAbsolutePath().normalize().toString();
        }

        try {
            Process whichProcess = new ProcessBuilder("bash", "-lc", "export PATH=\"$HOME/.local/bin:$PATH\" && command -v arduino-cli")
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
            String installCommand = "tmpdir=$(mktemp -d)"
                    + " && mkdir -p \"$HOME/.local/bin\""
                    + " && curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=\"$tmpdir\" sh"
                    + " && test -x \"$tmpdir/arduino-cli\""
                    + " && install -m 755 \"$tmpdir/arduino-cli\" \"$HOME/.local/bin/arduino-cli.new\""
                    + " && mv -f \"$HOME/.local/bin/arduino-cli.new\" \"$HOME/.local/bin/arduino-cli\""
                    + " ; status=$?"
                    + " ; rm -rf \"$tmpdir\""
                    + " ; exit $status";
            Process installProcess = new ProcessBuilder("bash", "-lc", installCommand)
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
            if (code == 0 && Files.isRegularFile(localCli) && Files.isExecutable(localCli)) {
                log("[INFO] Installed arduino-cli to " + localCli + ".");
                return localCli.toAbsolutePath().normalize().toString();
            }
            log("[WARN] arduino-cli installation exited with code " + code + ".");
        } catch (Exception ex) {
            log("[WARN] Failed to install arduino-cli automatically: " + ex.getMessage());
        }

        log("[ERROR] arduino-cli is unavailable, so Control Center could not " + reason + ".");
        return null;
    }

    private void runCommand(String shellCommand, File workingDirectory, String label, boolean needsSudo, boolean allowPrompt) {
        runCommand(shellCommand, workingDirectory, label, needsSudo, allowPrompt, null, false);
    }

    private void runCommand(String shellCommand, File workingDirectory, String label, boolean needsSudo, boolean allowPrompt, Runnable onSuccess) {
        runCommand(shellCommand, workingDirectory, label, needsSudo, allowPrompt, onSuccess, false);
    }

    private void runCommand(String shellCommand, File workingDirectory, String label, boolean needsSudo, boolean allowPrompt, Runnable onSuccess, boolean isFlashOperation) {
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
                activeCommandProcess = process;
                updateKillRunningTaskButton();

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
                activeCommandProcess = null;
                updateKillRunningTaskButton();
                if (!(success && onSuccess != null)) {
                    setActionButtons(true);
                }
            }
        }, "cmd-" + label);

        t.setDaemon(true);
        t.start();
    }

    private boolean runShellCommandSync(String shellCommand, String label, boolean needsSudo, boolean allowPrompt) {
        CommandSpec spec = buildShellCommand(shellCommand, needsSudo, allowPrompt);
        if (spec == null) {
            log("[WARN] " + label + " canceled (no sudo password). Running as root avoids this.");
            return false;
        }

        log("[RUN] " + label + " in " + repoPath().toAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(spec.command);
            pb.directory(repoPath().toFile());
            pb.redirectErrorStream(true);
            if (spec.sudoPassword != null) {
                pb.environment().put("SUDO_PASS", spec.sudoPassword);
            }
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log("[" + label + "] " + line);
                }
            }
            int code = process.waitFor();
            if (code == 0) {
                log("[DONE] " + label + " completed successfully.");
                return true;
            }
            log("[FAIL] " + label + " exited with code " + code + ".");
            return false;
        } catch (Exception ex) {
            log("[ERROR] Command failed for " + label + ": " + ex.getMessage());
            return false;
        }
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
        if (fromField.isBlank()) {
            fromField = new String(dashboardSudoPasswordField.getPassword());
            if (!fromField.isBlank()) {
                sudoPasswordField.setText(fromField);
            }
        }
        if (!fromField.trim().isEmpty()) {
            persistSudoPasswordIfRequested(fromField);
            return fromField;
        }

        String saved = readSavedSudoPassword();
        if (saved != null && !saved.isBlank()) {
            sudoPasswordField.setText(saved);
            rememberPasswordToggle.setSelected(true);
            dashboardSudoPasswordField.setText(saved);
            dashboardRememberPasswordToggle.setSelected(true);
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
            dashboardSudoPasswordField.setText(value);
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
        dashboardSudoPasswordField.setText(saved);
        dashboardRememberPasswordToggle.setSelected(true);
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
            dashboardRememberPasswordToggle.setSelected(false);
            if (clearField) {
                sudoPasswordField.setText("");
                dashboardSudoPasswordField.setText("");
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

    private record ArduinoLibraryRequirement(String installName, String detectName, String headerName) {
    }

    private record WifiSettingsSnapshot(String ssid, String password, String tcpPort, String boardName,
                                        String targetHost, String targetHostname) {
        private boolean hasCredentials() {
            return ssid != null && !ssid.isBlank();
        }
    }

    private Path boardPageProfilesPath() {
        return localStateDir().resolve("board_page_profiles.properties");
    }

    private Path legacyBoardPageProfilesPath() {
        return configDir().resolve("board_page_profiles.properties");
    }

    private Path localStateDir() {
        Path home = Paths.get(System.getProperty("user.home", ".")).toAbsolutePath();
        return home.resolve(".config").resolve("arduino-universal-system-monitor");
    }

    private void initializeBoardProfileState() {
        boardPageSettings.clear();
        namedPageProfiles.clear();

        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            boardPageSettings.put(board.id(), BoardPageSettings.defaultsFor(board));
        }
        namedPageProfiles.putAll(defaultNamedProfiles());
        migrateLegacyBoardProfilesIfNeeded();
        loadBoardPageProfilesFromDisk();
        enforceSafeBoardProfileDefaults();

        profileBoardSelector.removeAllItems();
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            profileBoardSelector.addItem(board);
        }
        profileBoardSelector.setSelectedItem(BoardProfileTarget.R4_WIFI);
        refreshProfileSelectorChoices();
        refreshBoardPageToggleView();
        saveBoardPageSettingsAndSyncHeaders(false);
    }

    private Map<String, Map<String, Map<String, Boolean>>> defaultNamedProfiles() {
        Map<String, Map<String, Map<String, Boolean>>> profiles = new LinkedHashMap<>();
        profiles.put(DEFAULT_PAGE_PROFILE_NAME, defaultsForEveryBoard(true));
        profiles.put("Minimal", minimalForEveryBoard());
        profiles.put("Gaming HUD", gamingHudForEveryBoard());
        profiles.put("Network Focus", networkFocusForEveryBoard());
        return profiles;
    }

    private Map<String, Map<String, Boolean>> defaultsForEveryBoard(boolean enabled) {
        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            Map<String, Boolean> pages = new LinkedHashMap<>();
            for (PageDefinition page : board.pages()) {
                pages.put(page.id(), enabled);
            }
            result.put(board.id(), pages);
        }
        return result;
    }

    private Map<String, Map<String, Boolean>> minimalForEveryBoard() {
        Map<String, Map<String, Boolean>> result = defaultsForEveryBoard(false);
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            result.get(board.id()).put("home", true);
            if (result.get(board.id()).containsKey("network")) result.get(board.id()).put("network", true);
        }
        return result;
    }

    private Map<String, Map<String, Boolean>> gamingHudForEveryBoard() {
        Map<String, Map<String, Boolean>> result = defaultsForEveryBoard(false);
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            Map<String, Boolean> pages = result.get(board.id());
            pages.put("home", true);
            if (pages.containsKey("cpu")) pages.put("cpu", true);
            if (pages.containsKey("gpu")) pages.put("gpu", true);
            if (pages.containsKey("usage_graph")) pages.put("usage_graph", true);
        }
        return result;
    }

    private Map<String, Map<String, Boolean>> networkFocusForEveryBoard() {
        Map<String, Map<String, Boolean>> result = defaultsForEveryBoard(false);
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            Map<String, Boolean> pages = result.get(board.id());
            pages.put("home", true);
            if (pages.containsKey("network")) pages.put("network", true);
            if (pages.containsKey("storage")) pages.put("storage", true);
        }
        return result;
    }

    private void loadBoardPageProfilesFromDisk() {
        Path path = boardPageProfilesPath();
        if (!Files.exists(path)) {
            return;
        }
        loadBoardPageProfilesFromPath(path);
    }

    private void loadBoardPageProfilesFromPath(Path path) {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException ex) {
            log("[WARN] Failed to read board page profiles from " + path + ": " + ex.getMessage());
            return;
        }

        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            String active = properties.getProperty("board." + board.id() + ".active_profile");
            if ((active == null || active.isBlank()) && board.id().startsWith("mega_")) {
                active = properties.getProperty("board.mega.active_profile");
            }
            if (active != null && !active.isBlank()) {
                boardPageSettings.get(board.id()).activeProfile = active.trim();
            }
            for (PageDefinition page : board.pages()) {
                String raw = properties.getProperty("board." + board.id() + ".page." + page.id());
                if ((raw == null || raw.isBlank()) && board.id().startsWith("mega_")) {
                    raw = properties.getProperty("board.mega.page." + page.id());
                }
                if (raw != null) {
                    boardPageSettings.get(board.id()).pageEnabled.put(page.id(), Boolean.parseBoolean(raw));
                }
            }
        }
        homeDisableWarningAcknowledged = Boolean.parseBoolean(
                properties.getProperty("ui.home_disable_warning_acknowledged", "false")
        );

        Map<String, String> profileIdsToNames = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("profile.") && key.endsWith(".name")) {
                String id = key.substring("profile.".length(), key.length() - ".name".length());
                String name = properties.getProperty(key, "").trim();
                if (!name.isBlank()) {
                    profileIdsToNames.put(id, name);
                }
            }
        }

        for (Map.Entry<String, String> profileMeta : profileIdsToNames.entrySet()) {
            String profileId = profileMeta.getKey();
            String profileName = profileMeta.getValue();
            Map<String, Map<String, Boolean>> boardMap = defaultsForEveryBoard(true);
            for (BoardProfileTarget board : BoardProfileTarget.values()) {
                for (PageDefinition page : board.pages()) {
                    String raw = properties.getProperty("profile." + profileId + "." + board.id() + "." + page.id());
                    if ((raw == null || raw.isBlank()) && board.id().startsWith("mega_")) {
                        raw = properties.getProperty("profile." + profileId + ".mega." + page.id());
                    }
                    if (raw != null) {
                        boardMap.get(board.id()).put(page.id(), Boolean.parseBoolean(raw));
                    }
                }
            }
            namedPageProfiles.put(profileName, boardMap);
        }
    }

    private void migrateLegacyBoardProfilesIfNeeded() {
        Path current = boardPageProfilesPath();
        Path legacy = legacyBoardPageProfilesPath();
        if (Files.exists(current) || !Files.exists(legacy)) {
            return;
        }
        try {
            Files.createDirectories(current.getParent());
            Files.copy(legacy, current);
            log("[INFO] Migrated board profiles from " + legacy + " to machine-local path " + current + ".");
        } catch (IOException ex) {
            log("[WARN] Failed to migrate legacy board profiles from " + legacy + ": " + ex.getMessage());
        }
    }

    private void enforceSafeBoardProfileDefaults() {
        if (!namedPageProfiles.containsKey(DEFAULT_PAGE_PROFILE_NAME)) {
            namedPageProfiles.put(DEFAULT_PAGE_PROFILE_NAME, defaultsForEveryBoard(true));
        }
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            BoardPageSettings settings = boardPageSettings.get(board.id());
            if (settings == null) {
                continue;
            }
            if (settings.activeProfile == null || settings.activeProfile.isBlank() || !namedPageProfiles.containsKey(settings.activeProfile)) {
                settings.activeProfile = DEFAULT_PAGE_PROFILE_NAME;
            }
            for (PageDefinition page : board.pages()) {
                settings.pageEnabled.putIfAbsent(page.id(), true);
            }
        }
    }

    private void persistBoardPageProfiles() {
        Properties properties = new Properties();
        properties.setProperty("format_version", "1");

        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            BoardPageSettings settings = boardPageSettings.get(board.id());
            properties.setProperty("board." + board.id() + ".active_profile", settings.activeProfile);
            for (PageDefinition page : board.pages()) {
                properties.setProperty("board." + board.id() + ".page." + page.id(), String.valueOf(settings.pageEnabled.getOrDefault(page.id(), true)));
            }
        }
        properties.setProperty("ui.home_disable_warning_acknowledged", String.valueOf(homeDisableWarningAcknowledged));

        for (String profileName : namedPageProfiles.keySet()) {
            String profileId = slugifyProfileName(profileName);
            properties.setProperty("profile." + profileId + ".name", profileName);
            Map<String, Map<String, Boolean>> profileBoards = namedPageProfiles.get(profileName);
            for (BoardProfileTarget board : BoardProfileTarget.values()) {
                Map<String, Boolean> pages = profileBoards.get(board.id());
                if (pages == null) continue;
                for (PageDefinition page : board.pages()) {
                    properties.setProperty("profile." + profileId + "." + board.id() + "." + page.id(), String.valueOf(pages.getOrDefault(page.id(), true)));
                }
            }
        }

        Path path = boardPageProfilesPath();
        try {
            Files.createDirectories(path.getParent());
            try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                properties.store(output, "UASM board page profiles");
            }
        } catch (IOException ex) {
            log("[WARN] Failed to save board page profile settings to " + path + ": " + ex.getMessage());
        }
    }

    private void exportBoardProfilesToChosenPath() {
        persistBoardPageProfiles();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export board profiles");
        chooser.setSelectedFile(new File("board_page_profiles.properties"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destination = chooser.getSelectedFile().toPath().toAbsolutePath();
        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.copy(boardPageProfilesPath(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log("[INFO] Exported board profiles to " + destination + ".");
        } catch (IOException ex) {
            log("[WARN] Failed to export board profiles to " + destination + ": " + ex.getMessage());
        }
    }

    private void importBoardProfilesFromChosenPath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import board profiles");
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (!Files.exists(source)) {
            log("[WARN] Import failed: file not found: " + source);
            return;
        }
        try {
            boardPageSettings.clear();
            namedPageProfiles.clear();
            for (BoardProfileTarget board : BoardProfileTarget.values()) {
                boardPageSettings.put(board.id(), BoardPageSettings.defaultsFor(board));
            }
            namedPageProfiles.putAll(defaultNamedProfiles());
            loadBoardPageProfilesFromPath(source);
            enforceSafeBoardProfileDefaults();
            persistBoardPageProfiles();
            refreshProfileSelectorChoices();
            refreshBoardPageToggleView();
            saveBoardPageSettingsAndSyncHeaders(false);
            log("[INFO] Imported board profiles from " + source + ".");
        } catch (Exception ex) {
            log("[WARN] Failed to import board profiles from " + source + ": " + ex.getMessage());
        }
    }

    private String slugifyProfileName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_", "").replaceAll("_$", "");
        return normalized.isBlank() ? "custom_profile" : normalized;
    }

    private BoardProfileTarget currentProfileBoardSelection() {
        Object selected = profileBoardSelector.getSelectedItem();
        return selected instanceof BoardProfileTarget board ? board : BoardProfileTarget.R4_WIFI;
    }

    private void refreshProfileSelectorChoices() {
        Object current = activeBoardProfileSelector.getSelectedItem();
        activeBoardProfileSelector.removeAllItems();
        for (String profileName : namedPageProfiles.keySet()) {
            activeBoardProfileSelector.addItem(profileName);
        }
        BoardPageSettings settings = boardPageSettings.get(currentProfileBoardSelection().id());
        activeBoardProfileSelector.setSelectedItem(settings == null ? current : settings.activeProfile);
    }

    private void refreshBoardPageToggleView() {
        BoardProfileTarget board = currentProfileBoardSelection();
        BoardPageSettings settings = boardPageSettings.get(board.id());
        if (settings == null) return;

        boardPageTogglePanel.removeAll();
        boardPageCheckboxes.clear();
        for (PageDefinition page : board.pages()) {
            JCheckBox box = new JCheckBox(page.label(), settings.pageEnabled.getOrDefault(page.id(), true));
            box.setFont(box.getFont().deriveFont(Font.BOLD));
            box.putClientProperty("uasmProfilePageToggle", Boolean.TRUE);
            box.addActionListener(e -> {
                if ("home".equals(page.id()) && !box.isSelected() && !homeDisableWarningAcknowledged) {
                    int answer = JOptionPane.showConfirmDialog(
                            this,
                            "The Home page may not be safe to disable the first time. Are you sure you want to disable it?",
                            "Disable Home page?",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE
                    );
                    if (answer != JOptionPane.YES_OPTION) {
                        box.setSelected(true);
                        return;
                    }
                    homeDisableWarningAcknowledged = true;
                    persistBoardPageProfiles();
                }
                settings.pageEnabled.put(page.id(), box.isSelected());
            });
            boardPageCheckboxes.put(page.id(), box);
            JPanel card = new JPanel(new BorderLayout());
            card.putClientProperty("uasmSettingsPanel", Boolean.TRUE);
            card.add(box, BorderLayout.CENTER);
            boardPageTogglePanel.add(card);
        }
        boardProfileStatusLabel.setText("Editing " + board.label() + " pages");
        refreshProfileSelectorChoices();
        boardPageTogglePanel.revalidate();
        boardPageTogglePanel.repaint();
        applyTheme();
    }

    private void applySelectedProfileToCurrentBoard() {
        BoardProfileTarget board = currentProfileBoardSelection();
        String profileName = String.valueOf(activeBoardProfileSelector.getEditor().getItem()).trim();
        if (profileName.isBlank()) {
            log("[WARN] Choose or type a profile name before applying.");
            return;
        }
        Map<String, Map<String, Boolean>> profile = namedPageProfiles.get(profileName);
        if (profile == null) {
            log("[WARN] Profile '" + profileName + "' does not exist yet. Save it first with Save As Profile.");
            return;
        }
        Map<String, Boolean> boardProfile = profile.get(board.id());
        if (boardProfile == null) {
            log("[WARN] Profile '" + profileName + "' has no settings for " + board.label() + ".");
            return;
        }
        BoardPageSettings settings = boardPageSettings.get(board.id());
        for (PageDefinition page : board.pages()) {
            settings.pageEnabled.put(page.id(), boardProfile.getOrDefault(page.id(), true));
        }
        settings.activeProfile = profileName;
        refreshBoardPageToggleView();
        log("[INFO] Applied profile '" + profileName + "' to " + board.label() + ".");
    }

    private void saveCurrentBoardAsNamedProfile() {
        BoardProfileTarget board = currentProfileBoardSelection();
        String profileName = String.valueOf(activeBoardProfileSelector.getEditor().getItem()).trim();
        if (profileName.isBlank()) {
            log("[WARN] Profile name cannot be empty.");
            return;
        }
        Map<String, Map<String, Boolean>> profile = namedPageProfiles.computeIfAbsent(profileName, k -> defaultsForEveryBoard(true));
        BoardPageSettings settings = boardPageSettings.get(board.id());
        Map<String, Boolean> boardProfile = new LinkedHashMap<>();
        for (PageDefinition page : board.pages()) {
            boardProfile.put(page.id(), settings.pageEnabled.getOrDefault(page.id(), true));
        }
        profile.put(board.id(), boardProfile);
        settings.activeProfile = profileName;
        persistBoardPageProfiles();
        refreshProfileSelectorChoices();
        activeBoardProfileSelector.setSelectedItem(profileName);
        log("[INFO] Saved board profile '" + profileName + "' for " + board.label() + ".");
    }

    private void createNewProfile() {
        String proposed = JOptionPane.showInputDialog(this, "Enter a new profile name:", "New Profile", JOptionPane.PLAIN_MESSAGE);
        if (proposed == null) {
            return;
        }
        String profileName = proposed.trim();
        if (profileName.isBlank()) {
            log("[WARN] New profile name cannot be empty.");
            return;
        }
        if (namedPageProfiles.containsKey(profileName)) {
            log("[WARN] Profile '" + profileName + "' already exists. Choose another name.");
            activeBoardProfileSelector.setSelectedItem(profileName);
            return;
        }
        Map<String, Map<String, Boolean>> profile = defaultsForEveryBoard(true);
        BoardProfileTarget board = currentProfileBoardSelection();
        BoardPageSettings settings = boardPageSettings.get(board.id());
        Map<String, Boolean> boardProfile = new LinkedHashMap<>();
        for (PageDefinition page : board.pages()) {
            boardProfile.put(page.id(), settings.pageEnabled.getOrDefault(page.id(), true));
        }
        profile.put(board.id(), boardProfile);
        namedPageProfiles.put(profileName, profile);
        settings.activeProfile = profileName;
        persistBoardPageProfiles();
        refreshProfileSelectorChoices();
        activeBoardProfileSelector.setSelectedItem(profileName);
        log("[INFO] Created new profile '" + profileName + "' from current " + board.label() + " toggles.");
    }

    private void saveAllBoardsAndProfiles() {
        boolean rotationsSaved = persistCurrentDisplayRotationSelections(true, true);
        boolean wifiSynced = syncCurrentWifiHeaderFromUi(true);
        boolean pagesSaved = saveBoardPageSettingsAndSyncHeaders(true);
        persistBoardPageProfiles();
        if (rotationsSaved && wifiSynced && pagesSaved) {
            log("[INFO] Saved all board settings and current profiles. Use Flash Arduinos' to compile/upload this exact saved state.");
        } else {
            log("[WARN] Save all completed with warnings. Check logs above and retry if needed.");
        }
    }

    private boolean syncCurrentWifiHeaderFromUi(boolean verbose) {
        String wifiPortText = wifiPortField.getText() == null ? "" : wifiPortField.getText().trim();
        int wifiPort;
        try {
            wifiPort = Integer.parseInt(wifiPortText);
        } catch (NumberFormatException ex) {
            if (verbose) {
                log("[WARN] Cannot sync Wi-Fi local header: Wi-Fi TCP port must be a number.");
            }
            return false;
        }
        if (wifiPort < 1 || wifiPort > 65535) {
            if (verbose) {
                log("[WARN] Cannot sync Wi-Fi local header: Wi-Fi TCP port must be between 1 and 65535.");
            }
            return false;
        }
        String wifiBoardName = normalizeWifiBoardName(wifiBoardNameField.getText() == null ? "" : wifiBoardNameField.getText().trim());
        String wifiTargetHost = wifiTargetHostField.getText() == null ? "" : wifiTargetHostField.getText().trim();
        String wifiTargetHostname = wifiTargetHostnameField.getText() == null ? "" : wifiTargetHostnameField.getText().trim();
        boolean synced = syncWifiHeaderIntoLocalHeader(wifiPort, wifiBoardName, wifiTargetHost, wifiTargetHostname);
        if (verbose && synced) {
            log("[INFO] Synced current Wi-Fi board values into wifi_config.local.h for immediate flashing.");
        }
        return synced;
    }

    private void updateSelectedProfileFromCurrentBoard() {
        BoardProfileTarget board = currentProfileBoardSelection();
        Object selectedItem = activeBoardProfileSelector.getSelectedItem();
        String profileName = selectedItem == null ? "" : selectedItem.toString().trim();
        if (!namedPageProfiles.containsKey(profileName)) {
            log("[WARN] Select an existing profile to update.");
            return;
        }
        Map<String, Map<String, Boolean>> profile = namedPageProfiles.get(profileName);
        BoardPageSettings settings = boardPageSettings.get(board.id());
        Map<String, Boolean> boardProfile = new LinkedHashMap<>();
        for (PageDefinition page : board.pages()) {
            boardProfile.put(page.id(), settings.pageEnabled.getOrDefault(page.id(), true));
        }
        profile.put(board.id(), boardProfile);
        settings.activeProfile = profileName;
        persistBoardPageProfiles();
        log("[INFO] Updated profile '" + profileName + "' using current " + board.label() + " toggles.");
    }

    private void deleteSelectedProfile() {
        Object selectedItem = activeBoardProfileSelector.getSelectedItem();
        String profileName = selectedItem == null ? "" : selectedItem.toString().trim();
        if (profileName.isBlank()) {
            log("[WARN] Select a profile to delete.");
            return;
        }
        if (!namedPageProfiles.containsKey(profileName)) {
            log("[WARN] Profile '" + profileName + "' was not found.");
            return;
        }
        if (List.of(DEFAULT_PAGE_PROFILE_NAME, "Minimal", "Gaming HUD", "Network Focus").contains(profileName)) {
            log("[WARN] Built-in profiles cannot be deleted.");
            return;
        }
        namedPageProfiles.remove(profileName);
        for (BoardPageSettings settings : boardPageSettings.values()) {
            if (profileName.equals(settings.activeProfile)) settings.activeProfile = DEFAULT_PAGE_PROFILE_NAME;
        }
        persistBoardPageProfiles();
        refreshProfileSelectorChoices();
        log("[INFO] Deleted profile '" + profileName + "'.");
    }

    private boolean saveBoardPageSettingsAndSyncHeaders(boolean verbose) {
        ensureAtLeastOnePageEnabledPerBoard();
        persistBoardPageProfiles();
        boolean synced = syncBoardPageConfigHeaders();
        if (verbose) {
            if (synced) {
                log("[INFO] Saved board page settings/profiles and synced page_config.local.h headers for R4, UNO R3 (2.8/3.5), and Mega.");
            } else {
                log("[WARN] Saved profile data, but one or more page_config.local.h headers failed to sync.");
            }
        }
        return synced;
    }

    private void ensureAtLeastOnePageEnabledPerBoard() {
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            BoardPageSettings settings = boardPageSettings.get(board.id());
            boolean anyEnabled = settings.pageEnabled.values().stream().anyMatch(Boolean::booleanValue);
            if (!anyEnabled) {
                settings.pageEnabled.put(board.pages().get(0).id(), true);
            }
        }
    }

    private boolean syncBoardPageConfigHeaders() {
        boolean ok = true;
        ok &= writeBoardPageConfigHeader(BoardProfileTarget.R4_WIFI, repoPath().resolve("R4_WIFI35/page_config.local.h"));
        ok &= writeBoardPageConfigHeader(BoardProfileTarget.UNO_R3_28, repoPath().resolve("R3_MonitorScreen28/page_config.local.h"));
        ok &= writeBoardPageConfigHeader(BoardProfileTarget.UNO_R3_35, repoPath().resolve("R3_MonitorScreen35/page_config.local.h"));
        ok &= writeBoardPageConfigHeader(BoardProfileTarget.MEGA_35, repoPath().resolve("R3_MEGA_MonitorScreen35/page_config.local.h"));
        ok &= writeBoardPageConfigHeader(BoardProfileTarget.MEGA_28, repoPath().resolve("R3_MEGA_MonitorScreen28/page_config.local.h"));
        return ok;
    }

    private boolean writeBoardPageConfigHeader(BoardProfileTarget board, Path target) {
        BoardPageSettings settings = boardPageSettings.get(board.id());
        if (settings == null) return false;

        StringBuilder header = new StringBuilder("#pragma once\n\n");
        for (PageDefinition page : board.pages()) {
            String macro = "UASM_PAGE_" + page.id().toUpperCase(Locale.ROOT) + "_ENABLED";
            boolean enabled = settings.pageEnabled.getOrDefault(page.id(), true);
            header.append("#define ").append(macro).append(" ").append(enabled ? "1" : "0").append("\n");
        }

        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, header.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return true;
        } catch (IOException ex) {
            log("[WARN] Failed to write page config header " + target + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean runningAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    private String escapeCppString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String buildManagedFlashCommand(String flashCommand) {
        return "systemctl stop " + SERVICE_NAME
                + " && cd " + escape(repoPath().toString())
                + " && " + flashCommand
                + " ; flash_status=$?"
                + " ; sleep " + FLASH_SERVICE_RESTART_DELAY_SECONDS
                + " ; systemctl start " + SERVICE_NAME
                + " ; exit $flash_status";
    }

    private void killActiveCommand() {
        Process process = activeCommandProcess;
        if (process == null || !process.isAlive()) {
            log("[INFO] No running flash/task process is active right now.");
            updateKillRunningTaskButton();
            return;
        }

        log("[WARN] Force-stopping the active command process on request.");
        try {
            process.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (Exception ignored) {
                    // Best-effort cleanup for child processes.
                }
            });
            process.destroyForcibly();
            log("[INFO] Sent kill signal to the running flash/task process. If it stopped mid-flash, reconnect the board and retry.");
        } catch (Exception ex) {
            log("[ERROR] Failed to kill the active command process: " + ex.getMessage());
        } finally {
            activeCommandProcess = null;
            SwingUtilities.invokeLater(() -> setActionButtons(true));
            updateKillRunningTaskButton();
            refreshServiceStatus(false);
        }
    }

    private void setActionButtons(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            desktopInstallButton.setEnabled(enabled);
            desktopAppletButton.setEnabled(enabled);
            uninstallButton.setEnabled(enabled);
            updateButton.setEnabled(enabled);
            remoteActionSelector.setEnabled(enabled);
            runRemoteActionButton.setEnabled(enabled);
            remoteUseSshToggle.setEnabled(enabled);
            flashButton.setEnabled(enabled);
            flashPreviewButton.setEnabled(enabled);
            customFlashButton.setEnabled(enabled);
            wifiCredentialsButton.setEnabled(enabled);
            clearSavedPasswordButton.setEnabled(enabled);
            rememberPasswordToggle.setEnabled(enabled);
            startFakePortsButton.setEnabled(enabled && (fakePortsProcess == null || !fakePortsProcess.isAlive()));
            stopFakePortsButton.setEnabled(enabled && fakePortsProcess != null && fakePortsProcess.isAlive());
            connectPreviewButton.setEnabled(enabled && previewPort == null);
            disconnectPreviewButton.setEnabled(enabled && previewPort != null);
            serviceOnButton.setEnabled(enabled);
            serviceOffButton.setEnabled(enabled);
            serviceRestartButton.setEnabled(enabled);
            serviceStatusButton.setEnabled(enabled);
            serviceStartupEnableButton.setEnabled(enabled);
            serviceStartupDisableButton.setEnabled(enabled);
            serviceStartupRefreshButton.setEnabled(enabled);
            debugOnButton.setEnabled(enabled);
            debugOffButton.setEnabled(enabled);
            debugRefreshButton.setEnabled(enabled);
            wifiModeOnButton.setEnabled(enabled);
            wifiModeOffButton.setEnabled(enabled);
            wifiModeRefreshButton.setEnabled(enabled);
            unoR3ScreenSizeSelector.setEnabled(enabled);
            megaScreenSizeSelector.setEnabled(enabled);
            r4RotationSelector.setEnabled(enabled);
            r3RotationSelector.setEnabled(enabled);
            megaRotationSelector.setEnabled(enabled);
            arduinoPortSelector.setEnabled(enabled);
            wifiConnectionModeSelector.setEnabled(enabled);
            wifiDiscoveryDebugToggle.setEnabled(enabled);
            wifiDiscoveryIgnoreBoardFilterToggle.setEnabled(enabled);
            programModeSelector.setEnabled(enabled);
            macroTriggerModelSelector.setEnabled(enabled);
            macroEntriesArea.setEnabled(enabled);
            wifiPortField.setEnabled(enabled);
            wifiHostField.setEnabled(enabled);
            wifiBoardNameField.setEnabled(enabled);
            wifiTargetHostField.setEnabled(enabled);
            wifiTargetHostnameField.setEnabled(enabled);
            fakeInField.setEnabled(enabled);
            fakeOutField.setEnabled(enabled);
            refreshMonitorPortsButton.setEnabled(enabled);
            loadMonitorSettingsButton.setEnabled(enabled);
            saveMonitorSettingsButton.setEnabled(enabled);
            resetWifiPairingButton.setEnabled(enabled);
            profileBoardSelector.setEnabled(enabled);
            activeBoardProfileSelector.setEnabled(enabled);
            applyBoardProfileButton.setEnabled(enabled);
            saveBoardSettingsButton.setEnabled(enabled);
            newProfileButton.setEnabled(enabled);
            saveProfileAsButton.setEnabled(enabled);
            updateProfileButton.setEnabled(enabled);
            deleteProfileButton.setEnabled(enabled);
            exportProfilesButton.setEnabled(enabled);
            importProfilesButton.setEnabled(enabled);
            saveAllBoardsAndProfilesButton.setEnabled(enabled);
            updateNetworkScanButtons();
            updateKillRunningTaskButton();
            if (enabled) {
                updateRemoteTargetFieldState();
            } else {
                remoteHostField.setEnabled(false);
                remoteUserField.setEnabled(false);
                remotePortField.setEnabled(false);
                remoteRepoField.setEnabled(false);
                remoteProfileSelector.setEnabled(false);
                saveRemoteProfileButton.setEnabled(false);
                deleteRemoteProfileButton.setEnabled(false);
            }
        });
    }

    private void updateKillRunningTaskButton() {
        SwingUtilities.invokeLater(() -> killRunningTaskButton.setEnabled(activeCommandProcess != null && activeCommandProcess.isAlive()));
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
            flashTransportIndicator.setText(text);
            flashTransportIndicator.setBackground(color);
            flashTransportIndicator.setForeground(Color.WHITE);
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

    private void setStartupIndicator(String label, Color color) {
        SwingUtilities.invokeLater(() -> {
            startupIndicator.setText(label);
            startupIndicator.setBackground(color);
            startupIndicator.setForeground(Color.WHITE);
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
            if (Files.exists(cursor.resolve("README.md"))
                    && (Files.exists(cursor.resolve("install.sh")) || Files.exists(cursor.resolve("scripts/install.sh")))) {
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


    private record PageDefinition(String id, String label) {}

    private enum BoardProfileTarget {
        R4_WIFI("r4_wifi", "R4 WiFi", List.of(
                new PageDefinition("home", "Home"),
                new PageDefinition("cpu", "CPU"),
                new PageDefinition("processes", "Processes"),
                new PageDefinition("network", "Network"),
                new PageDefinition("gpu", "GPU"),
                new PageDefinition("storage", "Extra Statistics"),
                new PageDefinition("usage_graph", "Usage Graph")
        )),
        UNO_R3_28("uno_r3_28", "UNO R3 2.8", List.of(
                new PageDefinition("home", "Home"),
                new PageDefinition("cpu", "CPU"),
                new PageDefinition("gpu", "GPU"),
                new PageDefinition("network", "Network"),
                new PageDefinition("storage", "Storage"),
                new PageDefinition("power", "Power"),
                new PageDefinition("usage_graph", "Usage Graph")
        )),
        UNO_R3_35("uno_r3_35", "UNO R3 3.5", List.of(
                new PageDefinition("home", "Home"),
                new PageDefinition("cpu", "CPU + Processes"),
                new PageDefinition("gpu", "GPU + Network"),
                new PageDefinition("storage", "Storage + Totals"),
                new PageDefinition("usage_graph", "Usage Graph")
        )),
        MEGA_28("mega_28", "Mega 2.8", List.of(
                new PageDefinition("home", "Home"),
                new PageDefinition("cpu", "CPU"),
                new PageDefinition("processes", "Processes"),
                new PageDefinition("gpu", "GPU"),
                new PageDefinition("network", "Network"),
                new PageDefinition("storage", "Storage"),
                new PageDefinition("power", "Power"),
                new PageDefinition("usage_graph", "Usage Graph")
        )),
        MEGA_35("mega_35", "Mega 3.5", List.of(
                new PageDefinition("home", "Home"),
                new PageDefinition("cpu", "CPU"),
                new PageDefinition("processes", "Processes"),
                new PageDefinition("network", "Network"),
                new PageDefinition("gpu", "GPU"),
                new PageDefinition("storage", "Storage"),
                new PageDefinition("usage_graph", "Usage Graph")
        ));

        private final String id;
        private final String label;
        private final List<PageDefinition> pages;

        BoardProfileTarget(String id, String label, List<PageDefinition> pages) {
            this.id = id;
            this.label = label;
            this.pages = pages;
        }

        String id() { return id; }
        String label() { return label; }
        List<PageDefinition> pages() { return pages; }

        @Override
        public String toString() {
            return label;
        }
    }

    private static class BoardPageSettings {
        final Map<String, Boolean> pageEnabled = new LinkedHashMap<>();
        String activeProfile = DEFAULT_PAGE_PROFILE_NAME;

        static BoardPageSettings defaultsFor(BoardProfileTarget board) {
            BoardPageSettings settings = new BoardPageSettings();
            for (PageDefinition page : board.pages()) {
                settings.pageEnabled.put(page.id(), true);
            }
            return settings;
        }
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
