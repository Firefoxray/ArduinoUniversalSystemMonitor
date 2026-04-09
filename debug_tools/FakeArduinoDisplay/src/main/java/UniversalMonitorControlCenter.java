import com.fazecast.jSerialComm.SerialPort;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UniversalMonitorControlCenter extends JFrame {
    private enum LaunchMode {
        CONTROL_CENTER,
        DASHBOARD_ONLY
    }

    private enum LogChannel {
        OPTIONAL_APP,
        ESSENTIAL,
        COMMAND_OUTPUT
    }

    private record LogEntry(long sequence, LogChannel channel, String text) {}

    private static final String SERVICE_NAME = "arduino-monitor.service";
    private static final String UPDATE_SOURCE_FILE = ".last_update_source";
    private static final String APP_NAME = "Ray Co. Universal Arduino System Monitor - Control Center";
    private static final String APP_VERSION = ProjectVersion.loadVersion(UniversalMonitorControlCenter.class);
    private static final String APP_VERSION_DISPLAY = "Version: " + APP_VERSION;
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
    private static final String DEFAULT_QBITTORRENT_HOST = "127.0.0.1";
    private static final int DEFAULT_QBITTORRENT_PORT = 8080;
    private static final int MAX_MACRO_ENTRIES = 8;
    private static final int DISPLAY_ROTATION_NORMAL = 1;
    private static final int DISPLAY_ROTATION_FLIPPED = 3;
    private static final int FLASH_SERVICE_RESTART_DELAY_SECONDS = 2;
    private static final int UI_TICK_MS = 250;
    private static final int LABEL_REFRESH_MS = 1000;
    private static final int METER_REFRESH_MS = 500;
    private static final int CHART_REFRESH_MS = 1000;
    private static final int BACKGROUND_STATUS_REFRESH_MS = 7000;
    private static final int BACKGROUND_NETWORK_REFRESH_MS = 30000;
    private static final int GAMING_REFRESH_MS = 1800;
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
    private static final List<Pattern> CODEX_BRANCH_PATTERNS = List.of(
            Pattern.compile("^codex/.*"),
            Pattern.compile("^codex-.*")
    );
    // RayFetch quick-command customization:
    // - edit RAYFETCH_ASCII_LOGO to change the displayed RayFetch logo
    // - edit RAYFETCH_COMMAND_ALIASES to change accepted aliases + mapped Python flags
    private static final String RAYFETCH_ASCII_LOGO = """
            ██████╗  █████╗ ██╗   ██╗     ██████╗ ██████╗
            ██╔══██╗██╔══██╗╚██╗ ██╔╝    ██╔════╝██╔═══██╗
            ██████╔╝███████║ ╚████╔╝     ██║     ██║   ██║
            ██╔══██╗██╔══██║  ╚██╔╝      ██║     ██║   ██║
            ██║  ██║██║  ██║   ██║       ╚██████╗╚██████╔╝
            ╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝        ╚═════╝ ╚═════╝
                    Universal System Monitor
            """;
    private static final Map<String, String> RAYFETCH_COMMAND_ALIASES = Map.ofEntries(
            Map.entry("rayfetch", "--rayfetch"),
            Map.entry("rf", "--rayfetch"),
            Map.entry("json", "--json"),
            Map.entry("payload-preview", "--payload-preview"),
            Map.entry("payload", "--payload-preview"),
            Map.entry("arduino-status", "--arduino-status"),
            Map.entry("status", "--arduino-status")
    );

    private final JTextField repoField = new JTextField(40);
    private final JPasswordField sudoPasswordField = new JPasswordField(18);
    private final JCheckBox rememberPasswordToggle = new JCheckBox("Remember Password");
    private final JButton clearSavedPasswordButton = new JButton("Clear Saved Password");

    private final JTextField fakeInField = new JTextField("/tmp/fakearduino_in", 22);
    private final JTextField fakeOutField = new JTextField("/tmp/fakearduino_out", 22);

    private static final Pattern LOG_PREFIX_PATTERN = Pattern.compile("^(\\[\\d{2}:\\d{2}:\\d{2}\\]\\s*)?(\\[[^\\]]+\\](?:\\[[^\\]]+\\])?)");
    private static final Pattern LOG_NUMBER_PATTERN = Pattern.compile("(?<![A-Za-z])\\d+(?:\\.\\d+)?(?:%|[kKmMgG]?[bB]|MHz|ms|s)?");
    private final JTextPane outputArea = new JTextPane();
    private final JTextPane settingsOutputArea = new JTextPane();
    private final JTextPane desktopSettingsOutputArea = new JTextPane();
    private JPanel outputPanelTab;
    private JTabbedPane mainTabs;

    private final JButton startFakePortsButton = new JButton("Start Fake Ports");
    private final JButton stopFakePortsButton = new JButton("Stop Fake Ports");
    private final JButton connectPreviewButton = new JButton("Connect Preview Stream");
    private final JButton disconnectPreviewButton = new JButton("Disconnect Preview Stream");

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
    private final JCheckBox blackModeToggle = new JCheckBox("Black mode");
    private final JCheckBox desktopDashboardLightModeToggle = new JCheckBox("Light mode");
    private final JCheckBox desktopDashboardBlackModeToggle = new JCheckBox("Black mode");
    private final JComboBox<String> unoR3ScreenSizeSelector = new JComboBox<>(new String[]{"2.8\" mode", "3.5\" mode"});
    private final JComboBox<String> megaScreenSizeSelector = new JComboBox<>(new String[]{"2.8\" mode", "3.5\" mode"});
    private final JComboBox<String> r4RotationSelector = new JComboBox<>(new String[]{rotationLabel(DISPLAY_ROTATION_NORMAL), rotationLabel(DISPLAY_ROTATION_FLIPPED)});
    private final JComboBox<String> r3RotationSelector = new JComboBox<>(new String[]{rotationLabel(DISPLAY_ROTATION_NORMAL), rotationLabel(DISPLAY_ROTATION_FLIPPED)});
    private final JComboBox<String> megaRotationSelector = new JComboBox<>(new String[]{rotationLabel(DISPLAY_ROTATION_NORMAL), rotationLabel(DISPLAY_ROTATION_FLIPPED)});
    private final JComboBox<String> arduinoPortSelector = new JComboBox<>();
    private final JComboBox<String> wifiConnectionModeSelector = new JComboBox<>(new String[]{WIFI_MODE_MANUAL, WIFI_MODE_AUTO_DISCOVERY});
    private final JCheckBox wifiDiscoveryDebugToggle = new JCheckBox("Wi-Fi Discovery Debug");
    private final JCheckBox wifiDiscoveryIgnoreBoardFilterToggle = new JCheckBox("Ignore Board Filter (Wi-Fi Discovery Debug)");
    private final JCheckBox graphNetDownToggle = new JCheckBox("Graph: Net Down");
    private final JCheckBox graphNetUpToggle = new JCheckBox("Graph: Net Up");
    private final JComboBox<String> programModeSelector = new JComboBox<>(new String[]{
            PROGRAM_MODE_SYSTEM_MONITOR,
            PROGRAM_MODE_GAMING,
            PROGRAM_MODE_MACRO
    });
    private final JTextField wifiPortField = new JTextField(6);
    private final JTextField wifiHostField = new JTextField(14);
    private final JTextField qbittorrentHostField = new JTextField(14);
    private final JTextField qbittorrentPortField = new JTextField(6);
    private final JTextField qbittorrentUsernameField = new JTextField(12);
    private final JPasswordField qbittorrentPasswordField = new JPasswordField(12);
    private final JTextField wifiBoardNameField = new JTextField(14);
    private final JTextField wifiTargetHostField = new JTextField(14);
    private final JTextField wifiTargetHostnameField = new JTextField(14);
    private final JLabel wifiPortSourceLabel = new JLabel("Effective source: unknown");
    private final JButton refreshMonitorPortsButton = new JButton("Refresh Port List");
    private final JButton loadMonitorSettingsButton = new JButton("Load Monitor Settings");
    private final JButton saveMonitorSettingsButton = new JButton("Save Monitor Settings and Flash WiFi R4");
    private final JButton saveAndRestartMonitorButton = new JButton("Save & Restart Python Monitor");
    private final JButton refreshStorageTargetsButton = new JButton("Refresh Storage Targets");
    private final JButton saveStorageSettingsButton = new JButton("Save Storage Settings");
    private final JLabel storageSelectionSummaryLabel = new JLabel("Disk0: Auto (/), Disk1: Auto (secondary)");
    private final JButton testQbittorrentConnectionButton = new JButton("Test qBittorrent Connection");
    private final JButton resetWifiPairingButton = new JButton("Reset Wi-Fi Pairing");
    private final JTextArea macroEntriesArea = new JTextArea(6, 52);
    private final JPanel storageTargetsPanel = new JPanel();
    private final JComboBox<String> disk0Selector = new JComboBox<>();
    private final JComboBox<String> disk1Selector = new JComboBox<>();
    private final StorageActivityPanel controlCenterStorageActivityPanel = new StorageActivityPanel();
    private final StorageActivityPanel popoutStorageActivityPanel = new StorageActivityPanel();
    private final JScrollPane controlCenterStorageActivityScroll = new JScrollPane(controlCenterStorageActivityPanel);
    private final JScrollPane popoutStorageActivityScroll = new JScrollPane(popoutStorageActivityPanel);
    private final JLabel storageCapacitySnapshotLabel = new JLabel("Storage Capacity / Mounts: waiting for monitor packets");
    private final JLabel popoutStorageCapacityLabel = new JLabel("Storage Capacity / Mounts: waiting for monitor packets");
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
    private final JLabel previewPrimaryIpLabel = new JLabel("--");
    private final JLabel previewSecondaryIpLabel = new JLabel("--");
    private final JButton popOutPreviewButton = new JButton("Open Pop Out Desktop Dashboard");
    private final JPasswordField dashboardSudoPasswordField = new JPasswordField(16);
    private final JCheckBox dashboardRememberPasswordToggle = new JCheckBox("Remember");
    private final JButton scanNetworkButton = new JButton("Start Arduino Scan");
    private final JButton stopNetworkScanButton = new JButton("Stop Scan");
    private final DefaultListModel<String> networkScanResultsModel = new DefaultListModel<>();
    private final JList<String> networkScanResultsList = new JList<>(networkScanResultsModel);

    private final JComboBox<BoardProfileTarget> profileBoardSelector = new JComboBox<>();
    private final JComboBox<String> activeBoardProfileSelector = new JComboBox<>();
    private final JButton applyBoardProfileButton = new JButton("Apply Profile");
    private final JButton saveBoardSettingsButton = new JButton("Save Board Page Settings");
    private final JButton saveAllBoardsAndProfilesButton = new JButton("Save All & Sync Headers");
    private final JComboBox<String> profileActionSelector = new JComboBox<>(new String[]{
            "New Profile",
            "Save As Profile",
            "Update Profile",
            "Delete Profile",
            "Import Profiles...",
            "Export Profiles..."
    });
    private final JButton runProfileActionButton = new JButton("Run");
    private final JButton flashFromProfilesButton = new JButton("Flash Arduinos' (Apply Changes)");
    private final JPanel boardPageTogglePanel = new JPanel(new GridLayout(0, 2, 6, 6));
    private final JPanel moduleTogglePanel = new JPanel(new GridLayout(0, 2, 6, 6));
    private final JLabel boardProfileStatusLabel = new JLabel("No board profile loaded.");

    private final Map<String, BoardPageSettings> boardPageSettings = new LinkedHashMap<>();
    private final Map<String, Map<String, Map<String, Boolean>>> namedPageProfiles = new LinkedHashMap<>();
    private final Map<String, Set<String>> profileEnabledModules = new LinkedHashMap<>();
    private final Map<String, JCheckBox> boardPageCheckboxes = new LinkedHashMap<>();
    private final Map<String, RemoteTargetProfile> remoteTargetProfiles = new LinkedHashMap<>();
    private final Map<String, JCheckBox> storageTargetCheckboxes = new LinkedHashMap<>();
    private final Map<String, StorageTarget> storageTargetsById = new LinkedHashMap<>();

    private final JavaSerialFakeDisplay.FakeDisplayPanel fakeDisplayPanel = new JavaSerialFakeDisplay.FakeDisplayPanel();
    private final JavaSerialFakeDisplay.FakeDisplayPanel desktopDashboardPanel = new JavaSerialFakeDisplay.FakeDisplayPanel();
    private final JTextPane desktopDashboardLogArea = new JTextPane();
    private final JLabel desktopDashboardServiceStateLabel = new JLabel("Service Running: --");
    private final JLabel desktopDashboardDebugStateLabel = new JLabel("Debug Mode: --");
    private final JLabel desktopDashboardServiceDotLabel = new JLabel("\u25cf");
    private final JLabel desktopDashboardDebugDotLabel = new JLabel("\u25cf");
    private final JPanel desktopDashboardStatusPanel = new JPanel(new GridLayout(0, 1, 4, 4));
    private final JPanel desktopDashboardControlsPanel = new JPanel(new GridLayout(0, 2, 6, 6));
    private final JPanel desktopDashboardSummaryPanel = new JPanel(new GridLayout(0, 1, 6, 6));
    private final JLabel desktopDashboardTransportSummaryLabel = new JLabel("Transport: --");
    private final JLabel desktopDashboardUptimeSummaryLabel = new JLabel("Uptime: --");
    private final JLabel desktopDashboardNetworkSummaryLabel = new JLabel("Network: Down -- | Up --");
    private final JLabel desktopDashboardStorageSummaryLabel = new JLabel("Storage: Disk0 -- | Disk1 --");
    private final JLabel desktopDashboardVersionSummaryLabel = new JLabel(APP_VERSION_DISPLAY);
    private final JPanel desktopDashboardSidePanel = new JPanel(new BorderLayout(8, 8));
    private final JPanel desktopDashboardWindowControlsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
    private final JScrollPane desktopDashboardLogScroller = new JScrollPane(desktopDashboardLogArea);
    private final JTabbedPane desktopDashboardTabs = new JTabbedPane();
    private final JTextField sshStatsHostField = new JTextField(14);
    private final JTextField sshStatsUserField = new JTextField(10);
    private final JTextField sshStatsPortField = new JTextField("22", 4);
    private final JTextField sshStatsRepoField = new JTextField(20);
    private final JButton sshStatsProbeButton = new JButton("Probe SSH Monitor");
    private final JButton sshStatsSyncFromRemoteButton = new JButton("Use Remote Target");
    private final JLabel sshStatsConnectionLabel = new JLabel("SSH Monitor: not connected");
    private final JLabel sshStatsRuntimeLabel = new JLabel("Runtime: --");
    private final JLabel sshStatsTransportLabel = new JLabel("Transport: --");
    private final JLabel sshStatsNetworkLabel = new JLabel("Network: --");
    private final JTextArea sshStatsOutputArea = new JTextArea();
    private final JCheckBox desktopSettingsAutoRefreshToggle = new JCheckBox("Auto-refresh summary cards");
    private final JCheckBox efficiencyModeToggle = new JCheckBox("Efficiency mode (lighter effects, same refresh cadence)");
    private final JCheckBox loggingEnabledToggle = new JCheckBox("Enable optional logs");
    private final JCheckBox desktopSettingsShowHistoryLegendToggle = new JCheckBox("Show history legend overlays");
    private final JCheckBox desktopSettingsCompactSidePanelToggle = new JCheckBox("Compact side panel spacing");
    private final JLabel desktopSettingsStatusLabel = new JLabel("Desktop monitor settings framework ready.");
    private final JTextField rayfetchCommandInputField = new JTextField(26);
    private final JButton rayfetchRunCommandButton = new JButton("Run RayFetch Command");
    private final JButton rayfetchClearLogsButton = new JButton("Clear Logs");
    private final JButton desktopDashboardFullscreenToggleButton = new JButton("Fullscreen");
    private final JLabel gamingTelemetrySourceLabel = new JLabel("Telemetry Source: MangoHud (Linux/Fedora path)");
    private final JLabel gamingTelemetryStateLabel = new JLabel("Source Status: waiting");
    private final JLabel gamingSessionStateLabel = new JLabel("Session: idle");
    private final JLabel gamingGameNameLabel = new JLabel("Game/App: --");
    private final JLabel gamingFpsLabel = new JLabel("FPS: --");
    private final JLabel gamingFrametimeLabel = new JLabel("Frametime: -- ms");
    private final JLabel gamingOnePercentLowLabel = new JLabel("1% Low: --");
    private final JLabel gamingAverageFpsLabel = new JLabel("Average FPS: --");
    private final JLabel gamingGpuStatsLabel = new JLabel("GPU: load -- | temp --");
    private final JLabel gamingCpuStatsLabel = new JLabel("CPU: load -- | temp --");
    private final JLabel gamingMemoryStatsLabel = new JLabel("RAM/VRAM: --");
    private final JLabel gamingMangoHudPathLabel = new JLabel("MangoHud log path: --");
    private final MangoHudTelemetryService mangoHudTelemetryService = new MangoHudTelemetryService();
    private final GamingTelemetryCache gamingTelemetryCache = new GamingTelemetryCache();
    private static final int SHARED_LOG_RING_CAPACITY = 1200;
    private static final int COMMAND_OUTPUT_RING_CAPACITY = 1600;
    private final ArrayDeque<LogEntry> sharedLogLines = new ArrayDeque<>();
    private final ArrayDeque<LogEntry> commandOutputLines = new ArrayDeque<>();
    private final ArrayDeque<String> rayfetchCommandHistory = new ArrayDeque<>();
    private int rayfetchHistoryCursor = 0;
    private final DateTimeFormatter dashboardLogTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final LaunchMode launchMode;
    private JFrame desktopDashboardWindow;
    private Rectangle desktopDashboardWindowedBounds;
    private boolean desktopDashboardFullscreen;
    private boolean syncingThemeToggles;
    private JavaSerialFakeDisplay.ParsedPacket latestDashboardPacket;
    private final Timer uiScheduler;
    private long lastLabelRefreshAtMs = 0L;
    private long lastMeterRefreshAtMs = 0L;
    private long lastChartRefreshAtMs = 0L;
    private long lastBackgroundRefreshAtMs = 0L;
    private long lastGamingRefreshAtMs = 0L;
    private long lastDashboardUiApplyAtMs = 0L;
    private long lastDashboardUiRepaintAtMs = 0L;
    private long sharedLogVersion = 0L;
    private long logSequence = 0L;
    private long outputLogRenderedVersion = 0L;
    private long popoutLogRenderedVersion = 0L;
    private long settingsLogRenderedVersion = 0L;
    private long desktopSettingsLogRenderedVersion = 0L;
    private boolean outputLogNeedsFullRender = false;
    private boolean popoutLogNeedsFullRender = false;
    private boolean settingsLogNeedsFullRender = false;
    private boolean desktopSettingsLogNeedsFullRender = false;
    private String lastStorageCapacitySnapshot = "";
    private String lastPopoutStorageCapacitySnapshot = "";

    private final Color darkBackground = new Color(23, 39, 66);
    private final Color darkPanelBackground = new Color(34, 54, 86);
    private final Color darkAccent = new Color(92, 143, 214);
    private final Color darkText = new Color(232, 240, 252);
    private final Color darkFieldBackground = new Color(18, 29, 48);
    private final Color darkButtonBackground = new Color(70, 109, 171);
    private final Color darkCriticalButtonBackground = new Color(176, 58, 58);
    private final Color darkRestartButtonBackground = new Color(196, 74, 74);
    private final Color darkPositiveButtonBackground = new Color(49, 145, 86);
    private final Color blackBackground = new Color(10, 10, 10);
    private final Color blackPanelBackground = new Color(20, 20, 20);
    private final Color blackAccent = new Color(112, 112, 112);
    private final Color blackText = new Color(236, 236, 236);
    private final Color blackFieldBackground = new Color(16, 16, 16);
    private final Color blackButtonBackground = new Color(52, 52, 52);
    private final Color blackCriticalButtonBackground = new Color(144, 52, 52);
    private final Color blackRestartButtonBackground = new Color(172, 72, 72);
    private final Color blackPositiveButtonBackground = new Color(48, 126, 82);
    private final Color lightBackground = new Color(236, 242, 252);
    private final Color lightPanelBackground = new Color(248, 251, 255);
    private final Color lightAccent = new Color(125, 160, 219);
    private final Color lightText = new Color(31, 46, 71);
    private final Color lightFieldBackground = Color.WHITE;
    private final Color lightButtonBackground = new Color(214, 226, 245);
    private final Color lightCriticalButtonBackground = new Color(221, 92, 92);
    private final Color lightRestartButtonBackground = new Color(235, 112, 112);
    private final Color lightPositiveButtonBackground = new Color(91, 182, 127);

    private enum ThemeMode {
        LIGHT,
        DARK_BLUE,
        BLACK
    }

    private ThemeMode themeMode;

    private final DashboardStreamController dashboardStreamController;
    private volatile Process activeCommandProcess;
    private Boolean lastDebugEnabledState;
    private Boolean lastWifiEnabledState;
    private boolean debugStatusMissingLogged;
    private boolean transportStatusMissingLogged;
    private long lastPreviewNetworkSnapshotAtMs = 0L;
    private String lastPreviewHostname = "unknown-host";
    private String lastPreviewPrimaryIp = "No IPv4 address";
    private String lastPreviewSecondaryIp = "--";
    private String lastPreviewCompactIpDisplay = "No IPv4 address";
    private volatile boolean serviceStatusRefreshInFlight;
    private volatile boolean startupStatusRefreshInFlight;
    private volatile boolean networkScanRequested;
    private volatile java.net.DatagramSocket activeNetworkScanSocket;
    private Thread networkScanThread;
    private Path selectedCustomSketchPath;
    private boolean homeDisableWarningAcknowledged;
    private final SharedRuntimeSettings runtimeSettings = new SharedRuntimeSettings();



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
        this(LaunchMode.CONTROL_CENTER);
    }

    public UniversalMonitorControlCenter(LaunchMode launchMode) {
        super(APP_NAME + " " + APP_VERSION);
        this.launchMode = launchMode == null ? LaunchMode.CONTROL_CENTER : launchMode;
        this.dashboardStreamController = new DashboardStreamController(
                this::repoPath,
                () -> fakeInField.getText(),
                () -> fakeOutField.getText(),
                this::ensureSocatInstalled,
                () -> ensureDebugMirrorConfig(fakeInField.getText().trim()),
                this::log,
                this::handleDashboardPacket,
                this::onDashboardStreamStateChanged
        );
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1340, 940);
        setLocationRelativeTo(null);
        applyWindowIcon();

        themeMode = shouldUseDarkModeByDefault() ? ThemeMode.DARK_BLUE : ThemeMode.LIGHT;

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
        qbittorrentHostField.setToolTipText("qBittorrent Web UI host/IP for API calls, for example 127.0.0.1 or 192.168.0.161.");
        qbittorrentPortField.setToolTipText("qBittorrent Web UI port, for example 8080.");
        qbittorrentUsernameField.setToolTipText("Optional qBittorrent Web UI username. Leave blank only if your Web UI API allows anonymous access.");
        qbittorrentPasswordField.setToolTipText("Optional qBittorrent Web UI password used when username is set.");
        wifiBoardNameField.setToolTipText("Easy nickname for the Arduino board, like OFFICE_PC_SCREEN or LIVING_ROOM_MONITOR. This helps Auto Discovery match the right board to the right PC when you have more than one.");
        wifiTargetHostField.setToolTipText("Optional: the IP address or network name of the PC this Arduino belongs to. Example: 192.168.1.20. Use this when each Arduino should pair with one specific computer.");
        wifiTargetHostnameField.setToolTipText("Optional: the computer name this Arduino should look for, like GAMING-PC or OFFICE-DESKTOP. This is another way to lock one Arduino to one PC.");
        refreshMonitorPortsButton.setToolTipText("Re-detects currently connected Arduino serial ports for the selector.");
        loadMonitorSettingsButton.setToolTipText("Reloads the saved settings from the config files on this computer. Use it to bring back what you last saved locally before you flash again.");
        saveMonitorSettingsButton.setToolTipText("Saves machine-local serial/TCP/connection-mode settings, mirrors the Wi-Fi port/pairing values into wifi_config.local.h, stops the monitor service, flashes every detected R4 WiFi board with that same local header, and starts the service again.");
        saveAndRestartMonitorButton.setToolTipText("Saves machine-local monitor/module/profile settings and restarts the Python monitor service only (no Arduino flashing).");
        flashFromProfilesButton.setToolTipText("Quick flash button near profiles so page/module/profile changes can be applied to hardware immediately.");
        runProfileActionButton.setToolTipText("Runs the selected profile action from the compact menu.");
        profileActionSelector.setToolTipText("Compact profile action menu (new/save as/update/delete/import/export).");
        testQbittorrentConnectionButton.setToolTipText("Tests qBittorrent Web UI API login/version reachability using the host, port, username, and password fields.");
        resetWifiPairingButton.setToolTipText("Explicitly clears the saved EEPROM Wi-Fi pairing on one connected UNO R4 WiFi over USB so the next PC can claim it without reflashing.");
        macroEntriesArea.setToolTipText("One macro text entry per line. Used by Macro Mode groundwork and stored in monitor config.");
        macroEntriesArea.setLineWrap(true);
        macroEntriesArea.setWrapStyleWord(true);
        macroTriggerModelSelector.setToolTipText("Staged trigger approach for unreliable touch calibration. Stores macro_trigger_model for future firmware behavior.");
        popOutPreviewButton.setToolTipText("Open the existing monitor preview in a separate resizable desktop window.");
        sshStatsProbeButton.setToolTipText("Framework probe: checks remote monitor service status over SSH and parses key fields.");
        sshStatsSyncFromRemoteButton.setToolTipText("Copies values from the main Remote Actions target fields into this SSH Stats scaffold.");
        rayfetchCommandInputField.setToolTipText("Try: rayfetch, json, payload-preview, arduino-status");
        rayfetchRunCommandButton.setToolTipText("Runs the mapped UniversalArduinoMonitor.py one-shot mode and streams output into logs.");
        rayfetchClearLogsButton.setToolTipText("Clears the shared command/log output used by Logs and settings views.");
        desktopDashboardFullscreenToggleButton.setToolTipText("Toggle the pop-out dashboard window between fullscreen and windowed mode.");
        rayfetchCommandInputField.setText("rayfetch");
        installRayfetchHistoryInputSupport();
        desktopDashboardPanel.setRenderMode(JavaSerialFakeDisplay.FakeDisplayPanel.RenderMode.DESKTOP_OVERVIEW);
        configureLogPane(desktopDashboardLogArea);
        desktopDashboardServiceStateLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        desktopDashboardDebugStateLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        desktopDashboardServiceDotLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        desktopDashboardDebugDotLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        sshStatsOutputArea.setEditable(false);
        sshStatsOutputArea.setRows(10);
        sshStatsOutputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        desktopDashboardTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        desktopDashboardTabs.setTabPlacement(JTabbedPane.TOP);

        mainTabs = buildMainTabs();

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.add(mainTabs, BorderLayout.CENTER);
        root.add(buildPersistentOutputFooter(), BorderLayout.SOUTH);
        add(root, BorderLayout.CENTER);

        applyThemeToggleStateFromMode();

        wireActions();
        applyTheme();
        updatePortButtons();
        setServiceIndicator("UNKNOWN", Color.GRAY);
        setStartupIndicator("UNKNOWN", Color.GRAY);
        setDebugIndicator("UNKNOWN", Color.GRAY);
        loadSavedSudoPassword();
        dashboardSudoPasswordField.setText(new String(sudoPasswordField.getPassword()));
        dashboardRememberPasswordToggle.setSelected(rememberPasswordToggle.isSelected());
        configureLogPane(outputArea);
        setRotationSelectorValue(r4RotationSelector, DISPLAY_ROTATION_NORMAL);
        setRotationSelectorValue(r3RotationSelector, DISPLAY_ROTATION_NORMAL);
        setRotationSelectorValue(megaRotationSelector, DISPLAY_ROTATION_NORMAL);
        initializeBoardProfileState();
        initializeRemoteActionState();
        syncSshStatsFieldsFromRemoteTarget();
        refreshMonitorConnectionSettings(false);
        refreshWifiCredentialsIndicator(false);
        updatePreviewWifiStatus(lastWifiEnabledState != null && lastWifiEnabledState);
        loggingEnabledToggle.setFocusable(false);
        loggingEnabledToggle.setSelected(true);
        runtimeSettings.setLoggingEnabled(true);
        loggingEnabledToggle.setToolTipText("Turns optional UI logs on/off for both Control Center and Pop-out dashboard.");
        loggingEnabledToggle.addActionListener(e -> {
            runtimeSettings.setLoggingEnabled(loggingEnabledToggle.isSelected());
        });
        efficiencyModeToggle.setSelected(true);
        efficiencyModeToggle.setFocusable(false);
        efficiencyModeToggle.addActionListener(e -> applyEfficiencyMode());
        applyEfficiencyMode();

        uiScheduler = new Timer(UI_TICK_MS, e -> runUiUpdateTick());
        uiScheduler.setRepeats(true);
        uiScheduler.start();
        refreshServiceStatus(false);
        refreshServiceStartupStatus(false);
        refreshDebugStatus(false);
        refreshTransportModeStatus(false);
        refreshGamingModeTelemetryCards();
        if (this.launchMode == LaunchMode.DASHBOARD_ONLY) {
            launchStandaloneDashboardMode();
        }
    }

    private void launchStandaloneDashboardMode() {
        setVisible(false);
        openDashboardWindowAndStartStream();
        log("[INFO] Dashboard-only mode enabled. Control Center window remains hidden.");
    }

    private void applyEfficiencyMode() {
        boolean enabled = efficiencyModeToggle.isSelected();
        runtimeSettings.setLowRefreshMode(enabled);
        fakeDisplayPanel.setEfficiencyMode(enabled);
        desktopDashboardPanel.setEfficiencyMode(enabled);
        controlCenterStorageActivityPanel.setEfficiencyMode(enabled);
        popoutStorageActivityPanel.setEfficiencyMode(enabled);
        log("[INFO] Efficiency mode " + (enabled ? "enabled" : "disabled")
                + ". Refresh cadence unchanged -> labels " + LABEL_REFRESH_MS + "ms, meters " + METER_REFRESH_MS
                + "ms, charts " + CHART_REFRESH_MS + "ms (effects only).");
    }

    private void runUiUpdateTick() {
        long now = System.currentTimeMillis();
        boolean labelsDue = (now - lastLabelRefreshAtMs) >= LABEL_REFRESH_MS;
        boolean metersDue = (now - lastMeterRefreshAtMs) >= METER_REFRESH_MS;
        boolean chartsDue = (now - lastChartRefreshAtMs) >= CHART_REFRESH_MS;
        boolean backgroundDue = (now - lastBackgroundRefreshAtMs) >= BACKGROUND_STATUS_REFRESH_MS;
        boolean gamingDue = (now - lastGamingRefreshAtMs) >= GAMING_REFRESH_MS;
        if (labelsDue) {
            lastLabelRefreshAtMs = now;
        }
        if (metersDue) {
            lastMeterRefreshAtMs = now;
        }
        if (chartsDue) {
            lastChartRefreshAtMs = now;
        }
        if (backgroundDue) {
            lastBackgroundRefreshAtMs = now;
            refreshBackgroundStatus(now);
        }
        if (gamingDue && isAnyGamingSurfaceVisible()) {
            lastGamingRefreshAtMs = now;
            refreshGamingModeTelemetryCards();
        }
        final JavaSerialFakeDisplay.ParsedPacket packet = latestDashboardPacket;
        if (packet != null) {
            if (labelsDue) {
                updateDashboardLabels(packet);
            }
            if (metersDue || chartsDue) {
                updateDashboardVisualPanels(chartsDue);
                updateStoragePanels(packet, chartsDue);
            }
        }
        flushVisibleLogViews();
    }

    private void refreshBackgroundStatus(long nowMs) {
        refreshServiceStatus(false);
        refreshServiceStartupStatus(false);
        String mergedConfig = null;
        try {
            mergedConfig = loadMergedMonitorConfigText();
        } catch (Exception ex) {
            // Keep status refresh resilient and avoid noisy background logs for transient file read errors.
        }
        refreshDebugStatusFromConfig(mergedConfig, false);
        refreshTransportModeStatusFromConfig(mergedConfig, false, nowMs);
    }

    private boolean isAnyGamingSurfaceVisible() {
        boolean controlCenterGamingVisible = mainTabs != null && "Dashboard".equals(mainTabs.getTitleAt(mainTabs.getSelectedIndex()));
        boolean popoutGamingVisible = desktopDashboardWindow != null
                && desktopDashboardWindow.isVisible()
                && !isDashboardWindowMinimized()
                && desktopDashboardTabs.getSelectedIndex() >= 0
                && "Gaming Mode".equals(desktopDashboardTabs.getTitleAt(desktopDashboardTabs.getSelectedIndex()));
        return controlCenterGamingVisible || popoutGamingVisible;
    }

    private boolean isDashboardWindowMinimized() {
        return desktopDashboardWindow != null && (desktopDashboardWindow.getExtendedState() & Frame.ICONIFIED) == Frame.ICONIFIED;
    }

    private boolean isPopoutWindowVisibleAndNotMinimized() {
        return desktopDashboardWindow != null && desktopDashboardWindow.isVisible() && !isDashboardWindowMinimized();
    }

    private void updateDashboardLabels(JavaSerialFakeDisplay.ParsedPacket packet) {
        if (mainTabs != null && mainTabs.getSelectedIndex() >= 0 && "Dashboard".equals(mainTabs.getTitleAt(mainTabs.getSelectedIndex()))) {
            refreshDesktopDashboardMiniSummary(packet);
        }
        String capacitySnapshot = buildStorageCapacitySnapshot(packet);
        if (!capacitySnapshot.equals(lastStorageCapacitySnapshot)) {
            storageCapacitySnapshotLabel.setText(capacitySnapshot);
            lastStorageCapacitySnapshot = capacitySnapshot;
        }
        if (!capacitySnapshot.equals(lastPopoutStorageCapacitySnapshot)) {
            popoutStorageCapacityLabel.setText(capacitySnapshot);
            lastPopoutStorageCapacitySnapshot = capacitySnapshot;
        }
    }

    private void updateDashboardVisualPanels(boolean chartsDue) {
        boolean dashboardTabVisible = mainTabs != null
                && mainTabs.getSelectedIndex() >= 0
                && "Dashboard".equals(mainTabs.getTitleAt(mainTabs.getSelectedIndex()));
        if (dashboardTabVisible) {
            fakeDisplayPanel.flushLatest(chartsDue);
        }
        if (desktopDashboardWindow == null || !desktopDashboardWindow.isVisible() || isDashboardWindowMinimized()) {
            return;
        }
        desktopDashboardPanel.flushLatest(chartsDue);
        lastDashboardUiRepaintAtMs = System.currentTimeMillis();
    }

    private void updateStoragePanels(JavaSerialFakeDisplay.ParsedPacket packet, boolean chartsDue) {
        boolean storageTabVisible = mainTabs != null
                && mainTabs.getSelectedIndex() >= 0
                && "Storage I/O".equals(mainTabs.getTitleAt(mainTabs.getSelectedIndex()));
        if (storageTabVisible) {
            controlCenterStorageActivityPanel.updateFromPacket(packet, chartsDue);
        }
        boolean popoutStorageVisible = desktopDashboardWindow != null
                && desktopDashboardWindow.isVisible()
                && !isDashboardWindowMinimized()
                && desktopDashboardTabs.getSelectedIndex() >= 0
                && "Storage".equals(desktopDashboardTabs.getTitleAt(desktopDashboardTabs.getSelectedIndex()));
        if (popoutStorageVisible) {
            popoutStorageActivityPanel.updateFromPacket(packet, chartsDue);
        }
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
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setTabPlacement(JTabbedPane.TOP);
        tabs.addTab("Dashboard", buildDashboardTab());
        tabs.addTab("Flash", buildFlashTab());
        tabs.addTab("Arduino Profiles", buildSettingsProfilesTab());
        tabs.addTab("Monitor", buildMonitorModulesTab());
        tabs.addTab("Storage I/O", buildStorageTab());
        outputPanelTab = buildLogsTab();
        tabs.addTab("LOGS", outputPanelTab);
        tabs.addTab("Settings", buildControlCenterSettingsTab());
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
        rowOne.add(new JLabel("Profile:"));
        activeBoardProfileSelector.setEditable(false);
        activeBoardProfileSelector.setPreferredSize(new Dimension(240, activeBoardProfileSelector.getPreferredSize().height));
        rowOne.add(activeBoardProfileSelector);
        rowOne.add(applyBoardProfileButton);
        rowOne.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(rowOne);

        JPanel rowTwo = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        rowTwo.add(saveBoardSettingsButton);
        rowTwo.add(saveAllBoardsAndProfilesButton);
        rowTwo.add(new JLabel("Profile action:"));
        profileActionSelector.setPreferredSize(new Dimension(190, profileActionSelector.getPreferredSize().height));
        rowTwo.add(profileActionSelector);
        rowTwo.add(runProfileActionButton);
        boardProfileStatusLabel.setBorder(new EmptyBorder(0, 12, 0, 0));
        rowTwo.add(boardProfileStatusLabel);
        rowTwo.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(rowTwo);

        JPanel flashRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        flashFromProfilesButton.setFont(flashFromProfilesButton.getFont().deriveFont(Font.BOLD, flashFromProfilesButton.getFont().getSize2D() + 1f));
        flashRow.add(flashFromProfilesButton);
        JLabel flashHint = new JLabel("<html><b>Profile/page/module changes require flashing to affect board navigation.</b></html>");
        flashRow.add(flashHint);
        flashRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(flashRow);

        JPanel helperPanel = new JPanel(new BorderLayout());
        helperPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        helperPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Profiles Quick Guide"),
                new EmptyBorder(10, 14, 12, 14)));

        JLabel helper = new JLabel("<html><div style='width: 920px; line-height: 1.45;'>"
                + "<b>Flow:</b> choose board type, choose profile, click <b>Apply Profile</b>, adjust page toggles, then <b>Save All & Sync Headers</b>.<br><br>"
                + "Use <b>Profile action</b> menu to create/update/import/export profiles without showing many separate buttons.<br><br>"
                + "<b>Flash Arduinos' (Apply Changes)</b> compiles/flashes the current repo sketches with saved page/profile/module settings."
                + "</div></html>");
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

    private JPanel buildMonitorModulesTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel modulePanel = new JPanel(new BorderLayout(8, 8));
        modulePanel.setBorder(BorderFactory.createTitledBorder("Monitor Modules (enabled per profile)"));
        moduleTogglePanel.setBorder(new EmptyBorder(6, 6, 6, 6));
        JScrollPane moduleScroll = new JScrollPane(moduleTogglePanel);
        moduleScroll.setBorder(BorderFactory.createTitledBorder("Enabled Modules for Active Profile"));
        modulePanel.add(moduleScroll, BorderLayout.CENTER);
        JLabel moduleHelper = new JLabel("<html>Profiles can enable modules independently from page toggles. If a page requires a module (for example qBittorrent), the module is auto-enabled and disabling it while dependent pages are active is blocked with a warning.</html>");
        moduleHelper.setBorder(new EmptyBorder(4, 8, 8, 8));
        modulePanel.add(moduleHelper, BorderLayout.SOUTH);
        modulePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        content.add(modulePanel);
        content.add(Box.createVerticalStrut(6));
        content.add(buildMonitorSettingsPanel());

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStorageTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        JPanel content = new JPanel(new BorderLayout(10, 10));
        JPanel setupContent = new JPanel();
        setupContent.setLayout(new BoxLayout(setupContent, BoxLayout.Y_AXIS));
        setupContent.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(BorderFactory.createTitledBorder("Storage Display Setup"));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        buttonRow.add(refreshStorageTargetsButton);
        controls.add(buttonRow);

        JPanel saveRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        saveRow.add(saveStorageSettingsButton);
        controls.add(saveRow);

        JPanel disk0Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        disk0Row.add(new JLabel("Home Disk0:"));
        disk0Selector.setPreferredSize(new Dimension(280, disk0Selector.getPreferredSize().height));
        disk0Row.add(disk0Selector);
        controls.add(disk0Row);

        JPanel disk1Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        disk1Row.add(new JLabel("Home Disk1:"));
        disk1Selector.setPreferredSize(new Dimension(280, disk1Selector.getPreferredSize().height));
        disk1Row.add(disk1Selector);
        controls.add(disk1Row);
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        setupContent.add(controls);

        storageTargetsPanel.setLayout(new BoxLayout(storageTargetsPanel, BoxLayout.Y_AXIS));
        storageTargetsPanel.setBorder(new EmptyBorder(6, 6, 6, 6));
        JScrollPane targetsScroll = new JScrollPane(storageTargetsPanel);
        targetsScroll.setBorder(BorderFactory.createTitledBorder("Detected Storage Targets (checked = visible on Storage page)"));
        targetsScroll.setPreferredSize(new Dimension(480, 420));
        setupContent.add(targetsScroll);

        storageSelectionSummaryLabel.setBorder(new EmptyBorder(4, 6, 2, 6));
        storageSelectionSummaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        setupContent.add(storageSelectionSummaryLabel);

        JLabel helper = new JLabel("<html>Pick which drives are shown on the Storage page.<br>"
                + "Disk0 and Disk1 choose the two home-screen disk meters and save to your local config.</html>");
        helper.setBorder(new EmptyBorder(6, 6, 6, 6));
        helper.setAlignmentX(Component.LEFT_ALIGNMENT);
        setupContent.add(helper);

        JScrollPane setupScroll = new JScrollPane(setupContent);
        setupScroll.setBorder(BorderFactory.createTitledBorder("Storage Targets / Capacity Setup"));
        setupScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel activityPanel = new JPanel(new BorderLayout(8, 8));
        activityPanel.setBorder(BorderFactory.createTitledBorder("Storage I/O / Drive Activity (Live)"));
        storageCapacitySnapshotLabel.setBorder(new EmptyBorder(4, 6, 2, 6));
        activityPanel.add(storageCapacitySnapshotLabel, BorderLayout.NORTH);
        controlCenterStorageActivityScroll.setBorder(BorderFactory.createEmptyBorder());
        controlCenterStorageActivityScroll.getVerticalScrollBar().setUnitIncrement(16);
        activityPanel.add(controlCenterStorageActivityScroll, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, setupScroll, activityPanel);
        splitPane.setResizeWeight(0.36);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerLocation(470);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        content.add(splitPane, BorderLayout.CENTER);

        panel.add(content, BorderLayout.CENTER);
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

    private JPanel buildControlCenterSettingsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.putClientProperty("uasmSettingsPanel", Boolean.TRUE);

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        modeRow.add(efficiencyModeToggle);
        modeRow.add(loggingEnabledToggle);
        controls.add(modeRow);

        JPanel miscRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        miscRow.add(desktopSettingsAutoRefreshToggle);
        miscRow.add(desktopSettingsShowHistoryLegendToggle);
        miscRow.add(desktopSettingsCompactSidePanelToggle);
        controls.add(miscRow);

        panel.add(controls, BorderLayout.NORTH);
        panel.add(buildOutputPanel(desktopSettingsOutputArea, "Command Output / Logs"), BorderLayout.CENTER);
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
        blackModeToggle.setFocusable(false);
        debugPanel.add(lightModeToggle);
        debugPanel.add(blackModeToggle);

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
        flashButton.setFont(flashButton.getFont().deriveFont(Font.BOLD, flashButton.getFont().getSize2D() + 3f));
        actionRow.add(flashButton);
        actionRow.add(flashPreviewButton);

        JPanel secondaryActionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        secondaryActionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        secondaryActionRow.add(customFlashButton);
        customSketchIndicator.setBorder(new EmptyBorder(0, 8, 0, 0));
        customSketchIndicator.setToolTipText("Shows the currently selected custom sketch folder.");
        secondaryActionRow.add(customSketchIndicator);
        rowTwo.add(actionRow);
        rowTwo.add(secondaryActionRow);

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
        rowThree.add(Box.createHorizontalStrut(8));
        rowThree.add(graphNetDownToggle);
        rowThree.add(graphNetUpToggle);
        monitorSettingsPanel.add(rowThree);

        JPanel rowQbit = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        rowQbit.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel qbitHostLabel = new JLabel("qBittorrent Host:");
        qbitHostLabel.setToolTipText(qbittorrentHostField.getToolTipText());
        rowQbit.add(qbitHostLabel);
        rowQbit.add(qbittorrentHostField);
        JLabel qbitPortLabel = new JLabel("Port:");
        qbitPortLabel.setToolTipText(qbittorrentPortField.getToolTipText());
        rowQbit.add(qbitPortLabel);
        rowQbit.add(qbittorrentPortField);
        JLabel qbitUserLabel = new JLabel("Username:");
        qbitUserLabel.setToolTipText(qbittorrentUsernameField.getToolTipText());
        rowQbit.add(qbitUserLabel);
        rowQbit.add(qbittorrentUsernameField);
        JLabel qbitPasswordLabel = new JLabel("Password:");
        qbitPasswordLabel.setToolTipText(qbittorrentPasswordField.getToolTipText());
        rowQbit.add(qbitPasswordLabel);
        rowQbit.add(qbittorrentPasswordField);
        monitorSettingsPanel.add(rowQbit);

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
        saveMonitorSettingsButton.setFont(saveMonitorSettingsButton.getFont().deriveFont(Font.BOLD));
        saveAndRestartMonitorButton.setFont(saveAndRestartMonitorButton.getFont().deriveFont(Font.BOLD));
        rowFive.add(saveMonitorSettingsButton);
        rowFive.add(saveAndRestartMonitorButton);
        rowFive.add(loadMonitorSettingsButton);
        rowFive.add(wifiPortSourceLabel);
        rowFive.add(testQbittorrentConnectionButton);
        rowFive.add(resetWifiPairingButton);
        monitorSettingsPanel.add(rowFive);

        JLabel helper = new JLabel("<html><b>Recommended setup:</b> set a <b>Wi-Fi Host/IP</b> and use <b>Fixed Host/IP Only</b> for the most reliable path. The monitor will connect directly over TCP without requiring discovery first.<br>"
                + "If you need diagnostics/fallback, switch to <b>Fixed Host/IP + UDP Discovery Fallback</b>. In that mode the monitor still tries fixed host/IP first, then can fall back to UDP discovery.<br>"
                + "<b>Wi-Fi Host/IP</b> = the Arduino's network address for this PC. <b>Board Name</b> = a simple nickname for the Arduino. <b>Target Host/IP</b> and <b>Target Hostname</b> = which PC that Arduino should belong to.<br>"
                + "If you have one Arduino per PC, give each board its own name and flash them <b>one at a time</b> so each one keeps the correct target PC info.<br>"
                + "<b>Program Mode</b> is a staged top-level selector (System Monitor, Gaming Mode, Macro Mode). It is saved to config now and passed into flash workflows so mode-specific sketch behavior can be expanded safely later.<br>"
                + "<b>qBittorrent fields</b> map directly to monitor config keys (qbittorrent_host, qbittorrent_port, qbittorrent_username, qbittorrent_password). Use <b>Test qBittorrent Connection</b> to verify API access before restarting the service.<br>"
                + "<b>Macro Mode groundwork</b>: macro entries + trigger model are saved in config now (Phase 1). Use large/whole-screen-safe trigger options instead of tiny touch targets until touch calibration is reliable.<br>"
                + "<b>Load Monitor Settings</b> reloads the saved config files from this computer, including your last local values if you already saved them. That lets you review them before flashing again.<br>"
                + "<b>Workflow:</b> choose profile, adjust pages/modules, save settings, then restart Python monitor if host-side changes were made, and flash Arduino when firmware/page headers changed.<br>"
                + "<b>Save & Restart Python Monitor</b> writes local monitor/module state and restarts only the Python monitor service (no Arduino flash).<br>"
                + "<b>Save Monitor Settings and Flash WiFi R4</b> writes this PC's local settings, copies the pairing values into <b>R4_WIFI35/wifi_config.local.h</b>, reflashes detected UNO R4 WiFi boards, and restarts the monitor so the change applies now.<br>"
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
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JPanel popOutActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        popOutActions.add(popOutPreviewButton);
        popOutActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(popOutActions);
        header.add(buildPreviewWifiPanel());
        JLabel helper = new JLabel("Preview listens live to the Output port path used by the fake serial pair.");
        helper.setBorder(new EmptyBorder(0, 8, 8, 8));
        helper.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(helper);
        panel.add(header, BorderLayout.NORTH);
        panel.add(fakeDisplayPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildPreviewWifiPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));
        panel.setBorder(new EmptyBorder(8, 8, 4, 8));
        panel.add(buildPreviewInfoValuePanel("Preview Wi-Fi", previewWifiStateLabel));
        panel.add(buildPreviewInfoValuePanel("PC Hostname", previewWifiHostnameLabel));
        panel.add(buildPreviewInfoValuePanel("LAN / Primary IP", previewPrimaryIpLabel));
        panel.add(buildPreviewInfoValuePanel("VPN / Secondary IP", previewSecondaryIpLabel));
        return panel;
    }

    private JPanel buildPreviewInfoValuePanel(String title, JLabel valueLabel) {
        JPanel panel = buildInfoValuePanel(title, valueLabel);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.PLAIN, Math.max(11f, valueLabel.getFont().getSize2D() - 1f)));
        valueLabel.setVerticalAlignment(SwingConstants.TOP);
        valueLabel.setBorder(new EmptyBorder(2, 0, 4, 0));
        panel.setMinimumSize(new Dimension(160, 56));
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

    private JPanel buildLogsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(buildOutputPanel(), BorderLayout.CENTER);

        JPanel cliFooter = new JPanel();
        cliFooter.setLayout(new BoxLayout(cliFooter, BoxLayout.Y_AXIS));
        cliFooter.setBorder(new EmptyBorder(4, 0, 0, 0));
        JPanel commandRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        commandRow.add(new JLabel("RayFetch CLI:"));
        commandRow.add(rayfetchCommandInputField);
        commandRow.add(rayfetchRunCommandButton);
        commandRow.add(rayfetchClearLogsButton);
        cliFooter.add(commandRow);

        JLabel helper = new JLabel("<html>Run a RayFetch alias here and watch output above immediately. "
                + "Examples: <code>rayfetch</code>, <code>json</code>, <code>payload-preview</code>, <code>arduino-status</code>.</html>");
        helper.putClientProperty("uasmSettingsHelpBlock", Boolean.TRUE);
        cliFooter.add(helper);
        panel.add(cliFooter, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildPersistentOutputFooter() {
        JPanel panel = buildOutputPanel(settingsOutputArea, "Command Output / Logs (Persistent Footer)");
        panel.setPreferredSize(new Dimension(1200, 180));
        panel.setMinimumSize(new Dimension(400, 140));
        return panel;
    }

    private JPanel buildOutputPanel(JTextPane area, String title) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        configureLogPane(area);
        installCopyPopup(area);
        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private void installCopyPopup(JTextComponent area) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> area.copy());
        JMenuItem selectAllItem = new JMenuItem("Select All");
        selectAllItem.addActionListener(e -> area.selectAll());
        JMenuItem clearSelectionItem = new JMenuItem("Clear Selection");
        clearSelectionItem.addActionListener(e -> area.select(area.getCaretPosition(), area.getCaretPosition()));
        menu.add(copyItem);
        menu.add(selectAllItem);
        menu.add(clearSelectionItem);
        area.setComponentPopupMenu(menu);
    }

    private void configureLogPane(JTextPane pane) {
        pane.setEditable(false);
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pane.setDocument(new DefaultStyledDocument());
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
        flashFromProfilesButton.addActionListener(e -> runDefaultFlashWorkflow());
        flashPreviewButton.addActionListener(e -> showFlashPreviewDialog());
        customFlashButton.addActionListener(e -> uploadCustomSketch());
        wifiCredentialsButton.addActionListener(e -> promptForWifiCredentials());
        clearSavedPasswordButton.addActionListener(e -> clearSavedSudoPassword(true));
        dashboardRememberPasswordToggle.addActionListener(e -> rememberPasswordToggle.setSelected(dashboardRememberPasswordToggle.isSelected()));
        killRunningTaskButton.addActionListener(e -> killActiveCommand());
        sshStatsSyncFromRemoteButton.addActionListener(e -> syncSshStatsFieldsFromRemoteTarget());
        sshStatsProbeButton.addActionListener(e -> runSshStatsProbe());
        rayfetchRunCommandButton.addActionListener(e -> runRayfetchDashboardCommand());
        rayfetchClearLogsButton.addActionListener(e -> clearSharedCommandOutput());
        desktopDashboardFullscreenToggleButton.addActionListener(e -> toggleDesktopDashboardFullscreen());
        rayfetchCommandInputField.addActionListener(e -> runRayfetchDashboardCommand());
        desktopSettingsAutoRefreshToggle.addActionListener(e -> updateDesktopSettingsStatus());
        desktopSettingsShowHistoryLegendToggle.addActionListener(e -> updateDesktopSettingsStatus());
        desktopSettingsCompactSidePanelToggle.addActionListener(e -> updateDesktopSettingsStatus());
        loggingEnabledToggle.addActionListener(e -> updateDesktopSettingsStatus());

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

        startFakePortsButton.addActionListener(e -> dashboardStreamController.startFakePorts());
        scanNetworkButton.addActionListener(e -> startNetworkScan());
        stopNetworkScanButton.addActionListener(e -> stopNetworkScan());
        stopFakePortsButton.addActionListener(e -> dashboardStreamController.stopFakePorts());
        connectPreviewButton.addActionListener(e -> dashboardStreamController.connectPreviewPort());
        disconnectPreviewButton.addActionListener(e -> dashboardStreamController.disconnectPreviewPort());
        popOutPreviewButton.addActionListener(e -> openDashboardWindowAndStartStream());
        refreshMonitorPortsButton.addActionListener(e -> refreshMonitorPortChoices(true));
        loadMonitorSettingsButton.addActionListener(e -> refreshMonitorConnectionSettings(true));
        saveMonitorSettingsButton.addActionListener(e -> saveMonitorConnectionSettings(true));
        saveAndRestartMonitorButton.addActionListener(e -> saveMonitorConnectionSettings(false));
        refreshStorageTargetsButton.addActionListener(e -> refreshStorageTargetsFromSystem(true));
        saveStorageSettingsButton.addActionListener(e -> saveStorageSelectionsOnly());
        disk0Selector.addActionListener(e -> updateStorageSelectionSummaryLabel());
        disk1Selector.addActionListener(e -> updateStorageSelectionSummaryLabel());
        testQbittorrentConnectionButton.addActionListener(e -> testQbittorrentConnection());
        resetWifiPairingButton.addActionListener(e -> resetWifiPairing());
        wifiConnectionModeSelector.addActionListener(e -> updateWifiHostFieldState());
        profileBoardSelector.addActionListener(e -> { refreshBoardPageToggleView(); refreshModuleToggleView(); });
        activeBoardProfileSelector.addActionListener(e -> {
            BoardPageSettings settings = boardPageSettings.get(currentProfileBoardSelection().id());
            Object selected = activeBoardProfileSelector.getSelectedItem();
            if (settings != null && selected != null) {
                settings.activeProfile = selected.toString();
                refreshModuleToggleView();
            }
        });
        applyBoardProfileButton.addActionListener(e -> applySelectedProfileToCurrentBoard());
        runProfileActionButton.addActionListener(e -> runSelectedProfileAction());
        saveBoardSettingsButton.addActionListener(e -> saveBoardPageSettingsAndSyncHeaders(true));
        saveAllBoardsAndProfilesButton.addActionListener(e -> saveAllBoardsAndProfiles());
        dashboardSudoPasswordField.addActionListener(e -> syncDashboardSudoPassword());
        lightModeToggle.addActionListener(e -> applyThemeSelectionFromToggles(lightModeToggle, blackModeToggle));
        blackModeToggle.addActionListener(e -> applyThemeSelectionFromToggles(blackModeToggle, lightModeToggle));
        desktopDashboardLightModeToggle.addActionListener(e -> applyThemeSelectionFromToggles(desktopDashboardLightModeToggle, desktopDashboardBlackModeToggle));
        desktopDashboardBlackModeToggle.addActionListener(e -> applyThemeSelectionFromToggles(desktopDashboardBlackModeToggle, desktopDashboardLightModeToggle));
    }

    private void focusStorageTab() {
        if (mainTabs == null) {
            return;
        }
        int storageTabIndex = mainTabs.indexOfTab("Storage I/O");
        if (storageTabIndex >= 0) {
            mainTabs.setSelectedIndex(storageTabIndex);
        }
    }

    private String buildStorageCapacitySnapshot(JavaSerialFakeDisplay.ParsedPacket packet) {
        if (packet == null) {
            return "Storage Capacity / Mounts: waiting for monitor packets";
        }
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String value = packet.get("DRV" + i, "").trim();
            if (!value.isEmpty() && !"--".equals(value)) {
                lines.add(value);
            }
        }
        if (lines.isEmpty()) {
            return "Storage Capacity / Mounts: waiting for monitor packets";
        }
        return "Storage Capacity / Mounts: " + String.join("   |   ", lines.subList(0, Math.min(3, lines.size())));
    }


    private JLabel buildVersionBadge() {
        JLabel badge = new JLabel(APP_VERSION_DISPLAY);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD));
        return badge;
    }

    private void applyThemeSelectionFromToggles(JCheckBox selectedToggle, JCheckBox otherToggle) {
        if (syncingThemeToggles) {
            return;
        }
        ThemeMode selectedMode = ThemeMode.DARK_BLUE;
        if (selectedToggle == lightModeToggle || selectedToggle == desktopDashboardLightModeToggle) {
            selectedMode = selectedToggle.isSelected() ? ThemeMode.LIGHT : ThemeMode.DARK_BLUE;
        } else if (selectedToggle == blackModeToggle || selectedToggle == desktopDashboardBlackModeToggle) {
            selectedMode = selectedToggle.isSelected() ? ThemeMode.BLACK : ThemeMode.DARK_BLUE;
        }
        if (selectedToggle.isSelected()) {
            otherToggle.setSelected(false);
        }
        setThemeMode(selectedMode);
    }

    private void applyThemeToggleStateFromMode() {
        if (syncingThemeToggles) {
            return;
        }
        syncingThemeToggles = true;
        try {
            boolean lightModeEnabled = themeMode == ThemeMode.LIGHT;
            boolean blackModeEnabled = themeMode == ThemeMode.BLACK;
            lightModeToggle.setSelected(lightModeEnabled);
            desktopDashboardLightModeToggle.setSelected(lightModeEnabled);
            blackModeToggle.setSelected(blackModeEnabled);
            desktopDashboardBlackModeToggle.setSelected(blackModeEnabled);
        } finally {
            syncingThemeToggles = false;
        }
    }

    private void setThemeMode(ThemeMode nextMode) {
        themeMode = nextMode == null ? ThemeMode.DARK_BLUE : nextMode;
        applyThemeToggleStateFromMode();
        applyTheme();
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
        if (checkResult.blocked()) {
            log("[WARN] " + checkResult.message());
            JOptionPane.showMessageDialog(this, checkResult.message(), "Update blocked", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkResult.canProceed()) {
            log("[WARN] " + checkResult.message());
            JOptionPane.showMessageDialog(this, checkResult.message(), "Update check failed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkResult.updateAvailable()) {
            log("[INFO] " + checkResult.message());
            JOptionPane.showMessageDialog(this, checkResult.message(), "Update status", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String command = "cd " + escape(repo.toString()) + " && chmod +x update.sh UniversalMonitorControlCenter.sh && ./update.sh";
        runCommand(command, repo.toFile(), "Update and restart Control Center", true, true, () -> relaunchApplication(launcher));
    }

    private UpdateCheckResult checkForRemoteUpdate(Path repo) {
        String branch = runGitQuery(repo, "git rev-parse --abbrev-ref HEAD");
        if (branch == null || branch.isBlank() || "HEAD".equals(branch)) {
            return new UpdateCheckResult(false, false, false, "Could not determine the current git branch for the selected repo.");
        }
        branch = branch.trim();

        String fetchStatus = runGitQuery(repo, "git fetch --prune origin " + escape(branch) + " && echo FETCH_OK");
        if (fetchStatus == null) {
            return new UpdateCheckResult(false, false, false, "Could not fetch origin/" + branch + " to check for updates.");
        }

        String remoteRef = "origin/" + branch;
        String remoteHead = runGitQuery(repo, "git rev-parse --verify " + escape(remoteRef));
        if (remoteHead == null || remoteHead.isBlank()) {
            return new UpdateCheckResult(false, false, false, "Could not resolve origin/" + branch + " after fetch.");
        }

        String divergence = runGitQuery(repo, "git rev-list --left-right --count HEAD..." + escape(remoteRef));
        if (divergence == null || divergence.isBlank()) {
            return new UpdateCheckResult(false, false, false, "Could not compare local and remote history for branch '" + branch + "'.");
        }
        String[] counts = divergence.trim().split("\\s+");
        if (counts.length < 2) {
            return new UpdateCheckResult(false, false, false, "Git returned an invalid branch comparison result: '" + divergence.trim() + "'.");
        }
        int localAhead;
        int localBehind;
        try {
            localAhead = Integer.parseInt(counts[0]);
            localBehind = Integer.parseInt(counts[1]);
        } catch (NumberFormatException ex) {
            return new UpdateCheckResult(false, false, false, "Could not parse branch comparison counts: '" + divergence.trim() + "'.");
        }

        String workingTreeState = runGitQuery(repo, "git status --porcelain");
        if (workingTreeState == null) {
            return new UpdateCheckResult(false, false, false, "Could not inspect working tree state for branch '" + branch + "'.");
        }
        boolean dirtyWorkingTree = !workingTreeState.isBlank();

        if (localBehind > 0) {
            if (dirtyWorkingTree) {
                return new UpdateCheckResult(true, false, true,
                        "Updates are available from origin/" + branch + " (behind by " + localBehind + ", ahead by " + localAhead + "), but uncommitted changes are blocking update/branch-switch actions. Commit, stash, or reset changes first.");
            }
            return new UpdateCheckResult(true, true, false,
                    "Updates are available from origin/" + branch + " (behind by " + localBehind + ", ahead by " + localAhead + ").");
        }

        if (localAhead > 0) {
            if (dirtyWorkingTree) {
                return new UpdateCheckResult(true, false, true,
                        "No remote updates available. Local branch is ahead by " + localAhead + " commit(s), and uncommitted changes are blocking update/branch-switch actions. Commit, stash, or reset changes first.");
            }
            return new UpdateCheckResult(true, false, false,
                    "No remote updates needed. Local branch is ahead by " + localAhead + " commit(s), so local-only commits exist.");
        }

        if (dirtyWorkingTree) {
            return new UpdateCheckResult(true, false, true,
                    "No remote updates available, but uncommitted changes are blocking update/branch-switch actions. Commit, stash, or reset changes first.");
        }

        return new UpdateCheckResult(true, false, false,
                "Project is up to date with origin/" + branch + ". No update needed.");
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

    private record UpdateCheckResult(boolean canProceed, boolean updateAvailable, boolean blocked, String message) {}
    private record CodexBranchCandidate(String branch, String hash, String date) {}
    private record BranchEvaluation(String branch, boolean matches, String reason) {}
    private record CodexBranchSelection(
            boolean canProceed,
            String message,
            String currentBranch,
            String currentHash,
            String targetBranch,
            String targetHash,
            String targetDate,
            boolean createTrackingBranch
    ) {}

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
                SwingUtilities.invokeLater(() -> dashboardStreamController.startFakePorts());
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
        if (serviceStatusRefreshInFlight) {
            return;
        }
        serviceStatusRefreshInFlight = true;
        Thread t = new Thread(() -> {
            CommandSpec spec = buildShellCommand("systemctl is-active " + SERVICE_NAME, true, allowPrompt);
            if (spec == null) {
                serviceStatusRefreshInFlight = false;
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
            } finally {
                serviceStatusRefreshInFlight = false;
            }
        }, "service-status-thread");

        t.setDaemon(true);
        t.start();
    }

    private void refreshServiceStartupStatus(boolean allowPrompt) {
        if (startupStatusRefreshInFlight) {
            return;
        }
        startupStatusRefreshInFlight = true;
        Thread t = new Thread(() -> {
            CommandSpec spec = buildShellCommand("systemctl is-enabled " + SERVICE_NAME, true, allowPrompt);
            if (spec == null) {
                startupStatusRefreshInFlight = false;
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
            } finally {
                startupStatusRefreshInFlight = false;
            }
        }, "service-startup-status-thread");

        t.setDaemon(true);
        t.start();
    }

    private void handleDashboardPacket(JavaSerialFakeDisplay.ParsedPacket packet) {
        SwingUtilities.invokeLater(() -> {
            latestDashboardPacket = packet;
            fakeDisplayPanel.updatePacket(packet);
            desktopDashboardPanel.updatePacket(packet);
            fakeDisplayPanel.flushLatest(true);
            desktopDashboardPanel.flushLatest(true);
            lastDashboardUiApplyAtMs = System.currentTimeMillis();
            lastDashboardUiRepaintAtMs = lastDashboardUiApplyAtMs;
        });
    }

    private void onDashboardStreamStateChanged() {
        runtimeSettings.setDashboardStreamActive(dashboardStreamController.isProducerAlive() && dashboardStreamController.isConsumerAlive());
        updatePortButtons();
        updatePreviewButtons();
    }

    private enum ConfigUpdateResult {
        UPDATED,
        UNCHANGED,
        FAILED
    }

    private void setDebugMode(boolean enabled) {
        String fakeIn = fakeInField.getText().trim();
        if (fakeIn.isEmpty()) {
            log("[ERROR] Debug mode toggle failed: fake input path is empty.");
            return;
        }

        ConfigUpdateResult updateResult = updateDebugConfig(enabled, fakeIn, true);
        if (updateResult == ConfigUpdateResult.FAILED) {
            return;
        }

        setDebugIndicator(enabled ? "ON" : "OFF", enabled ? new Color(24, 170, 24) : new Color(190, 35, 35));
        lastDebugEnabledState = enabled;
        updateDebugAdvancedButtonState(enabled);
        if (updateResult == ConfigUpdateResult.UPDATED) {
            runServiceCommand("restart");
        } else {
            log("[INFO] Skipped service restart: debug mode settings were already applied.");
        }
    }

    private void refreshDebugStatus(boolean verbose) {
        String text;
        try {
            text = loadMergedMonitorConfigText();
        } catch (Exception ex) {
            text = null;
        }
        refreshDebugStatusFromConfig(text, verbose);
    }

    private void refreshDebugStatusFromConfig(String text, boolean verbose) {
        try {
            if (text == null || text.isBlank()) {
                setDebugIndicator("UNKNOWN", Color.GRAY);
                lastDebugEnabledState = null;
                updateDebugAdvancedButtonState(false);
                if (verbose || !debugStatusMissingLogged) {
                    log("[WARN] Cannot refresh debug status: no monitor config file was found.");
                    debugStatusMissingLogged = true;
                }
                return;
            }
            debugStatusMissingLogged = false;
            boolean enabled = text.matches("(?s).*\"debug_enabled\"\\s*:\\s*true.*");
            setDebugIndicator(enabled ? "ON" : "OFF", enabled ? new Color(24, 170, 24) : new Color(190, 35, 35));
            updateDebugAdvancedButtonState(enabled);

            if (verbose || lastDebugEnabledState == null || lastDebugEnabledState != enabled) {
                log("[INFO] Debug mode is " + (enabled ? "enabled" : "disabled") + " in the merged monitor config.");
            }
            lastDebugEnabledState = enabled;
        } catch (Exception ex) {
            setDebugIndicator("UNKNOWN", Color.GRAY);
            lastDebugEnabledState = null;
            updateDebugAdvancedButtonState(false);
            log("[WARN] Failed to refresh debug status: " + ex.getMessage());
        }
    }

    private void updateDebugAdvancedButtonState(boolean debugEnabled) {
        // Debug-only advanced button was removed from the dashboard UI.
    }

    private void runCodexDebugUpdateWorkflow() {
        if (!Boolean.TRUE.equals(lastDebugEnabledState)) {
            String message = "Blocked: this action only runs when Debug Mode is ON.";
            log("[WARN] " + message);
            JOptionPane.showMessageDialog(this, message, "Debug-only action blocked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Path repo = repoPath();
        CodexBranchSelection selection = evaluateLatestCodexBranchSelection(repo);
        if (!selection.canProceed()) {
            log("[WARN] " + selection.message());
            JOptionPane.showMessageDialog(this, selection.message(), "Codex branch update blocked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String commandSequence = buildCodexUpdateCommandSequence(repo, selection);
        String confirmationText = "<html><div style='width:420px'>"
                + "<b>Update from latest Codex/debug branch?</b><br>"
                + "Current: <code>" + escapeHtml(selection.currentBranch()) + "</code> @ <code>" + escapeHtml(selection.currentHash()) + "</code><br>"
                + "Target: <code>" + escapeHtml(selection.targetBranch()) + "</code> @ <code>" + escapeHtml(selection.targetHash()) + "</code><br>"
                + "This will update code, restart the monitor service, and relaunch the Control Center."
                + "</div></html>";
        int confirmed = JOptionPane.showConfirmDialog(
                this,
                confirmationText,
                "Confirm Codex Debug Update",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirmed != JOptionPane.YES_OPTION) {
            log("[INFO] Codex branch debug update canceled by user.");
            return;
        }

        log("[INFO] Debug advanced action confirmed. Selected latest Codex branch '" + selection.targetBranch()
                + "' by local committer date (" + selection.targetDate() + ").");
        runCommand(
                commandSequence,
                repo.toFile(),
                "Debug: update from latest Codex branch",
                false,
                true,
                () -> handleCodexUpdateSuccess(selection.currentBranch(), selection.currentHash())
        );
    }

    private CodexBranchSelection evaluateLatestCodexBranchSelection(Path repo) {
        log("[INFO] Codex branch selection: repo path=" + repo.toAbsolutePath().normalize());
        log("[INFO] Codex branch selection rules: local-first using patterns " + codexPatternSummary()
                + ", sorted by committer date (latest first).");

        String insideWorkTree = runGitQuery(repo, "git rev-parse --is-inside-work-tree");
        if (insideWorkTree == null || !"true".equalsIgnoreCase(insideWorkTree.trim())) {
            return new CodexBranchSelection(false, "Blocked: selected path is not a valid git repository.", "", "", "", "", "", false);
        }

        String currentBranch = runGitQuery(repo, "git rev-parse --abbrev-ref HEAD");
        if (currentBranch == null || currentBranch.isBlank() || "HEAD".equals(currentBranch.trim())) {
            return new CodexBranchSelection(false, "Blocked: detached HEAD state detected. Checkout a normal branch first.", "", "", "", "", "", false);
        }
        currentBranch = currentBranch.trim();

        String gitState = runGitQuery(repo, "if [ -f .git/MERGE_HEAD ] || [ -f .git/CHERRY_PICK_HEAD ] || [ -f .git/REVERT_HEAD ] "
                + "|| [ -d .git/rebase-merge ] || [ -d .git/rebase-apply ]; then echo BUSY; else echo CLEAN; fi");
        if (gitState == null || !"CLEAN".equals(gitState.trim())) {
            return new CodexBranchSelection(false, "Blocked: merge/rebase/cherry-pick is in progress. Resolve git state first.", "", "", "", "", "", false);
        }

        String dirty = runGitQuery(repo, "git status --porcelain");
        if (dirty != null && !dirty.isBlank()) {
            return new CodexBranchSelection(false, "Blocked: working tree is dirty. Commit/stash/reset before switching branches.", "", "", "", "", "", false);
        }

        String fetchOutput = runGitQuery(repo, "git fetch --all --prune --quiet && echo FETCH_OK");
        if (fetchOutput == null) {
            log("[WARN] Codex branch selection: fetch/prune failed; continuing with current refs.");
        } else {
            log("[INFO] Codex branch selection: fetch/prune completed before evaluation.");
        }

        String localBranchRows = runGitQuery(repo, "git for-each-ref --sort=-committerdate "
                + "--format='%(refname:short)|%(objectname:short)|%(committerdate:iso8601)' refs/heads");
        if (localBranchRows == null || localBranchRows.isBlank()) {
            return new CodexBranchSelection(false, "Blocked: could not enumerate local branches.", "", "", "", "", "", false);
        }

        List<BranchEvaluation> localEvaluations = new ArrayList<>();
        CodexBranchCandidate latest = null;
        for (String rawLine : localBranchRows.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) {
                continue;
            }
            String branch = parts[0].trim();
            BranchEvaluation evaluation = evaluateCodexBranchName(branch);
            localEvaluations.add(evaluation);
            if (!evaluation.matches()) {
                continue;
            }
            latest = new CodexBranchCandidate(branch, parts[1].trim(), parts[2].trim());
            break;
        }
        logBranchEvaluations("local", localEvaluations);

        boolean createTrackingBranch = false;
        if (latest == null) {
            String remoteBranchRows = runGitQuery(repo, "git for-each-ref --sort=-committerdate "
                    + "--format='%(refname:short)|%(objectname:short)|%(committerdate:iso8601)' refs/remotes/origin");
            CodexBranchCandidate latestRemote = null;
            List<BranchEvaluation> remoteEvaluations = new ArrayList<>();
            if (remoteBranchRows != null && !remoteBranchRows.isBlank()) {
                for (String rawLine : remoteBranchRows.split("\\R")) {
                    String line = rawLine == null ? "" : rawLine.trim();
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split("\\|", 3);
                    if (parts.length < 3) {
                        continue;
                    }
                    String remoteRef = parts[0].trim();
                    String branch = remoteRef.startsWith("origin/") ? remoteRef.substring("origin/".length()) : remoteRef;
                    if ("HEAD".equals(branch) || branch.startsWith("HEAD ->")) {
                        continue;
                    }
                    BranchEvaluation evaluation = evaluateCodexBranchName(branch);
                    remoteEvaluations.add(evaluation);
                    if (!evaluation.matches()) {
                        continue;
                    }
                    latestRemote = new CodexBranchCandidate(branch, parts[1].trim(), parts[2].trim());
                    break;
                }
            }
            logBranchEvaluations("remote(origin)", remoteEvaluations);
            if (latestRemote != null) {
                log("[INFO] No local matching Codex branch found; remote origin/" + latestRemote.branch()
                        + " will be used to create a local tracking branch.");
                latest = latestRemote;
                createTrackingBranch = true;
            } else {
                return new CodexBranchSelection(false,
                        "Blocked: no local branches matched Codex rules " + codexPatternSummary() + ".",
                        "", "", "", "", "", false);
            }
        }

        String currentHash = runGitQuery(repo, "git rev-parse --short HEAD");
        if (currentHash == null || currentHash.isBlank()) {
            return new CodexBranchSelection(false, "Blocked: could not read current commit hash.", "", "", "", "", "", false);
        }

        String remoteHead = runGitQuery(repo, "git ls-remote --heads origin " + escape(latest.branch()));
        if (remoteHead == null || remoteHead.isBlank()) {
            log("[INFO] No matching origin/" + latest.branch() + " branch was found. Checkout will still proceed using local branch state.");
        } else {
            log("[INFO] Verified remote branch origin/" + latest.branch() + " is available.");
        }

        return new CodexBranchSelection(
                true,
                "Ready",
                currentBranch,
                currentHash.trim(),
                latest.branch(),
                latest.hash(),
                latest.date(),
                createTrackingBranch
        );
    }

    private boolean isAllowedCodexBranchName(String branch) {
        return evaluateCodexBranchName(branch).matches();
    }

    private BranchEvaluation evaluateCodexBranchName(String branch) {
        if (branch == null || branch.isBlank()) {
            return new BranchEvaluation(branch == null ? "" : branch, false, "blank branch name");
        }
        for (Pattern pattern : CODEX_BRANCH_PATTERNS) {
            if (pattern.matcher(branch).matches()) {
                return new BranchEvaluation(branch, true, "matched " + pattern.pattern());
            }
        }
        return new BranchEvaluation(branch, false, "did not match any pattern in " + codexPatternSummary());
    }

    private String codexPatternSummary() {
        return CODEX_BRANCH_PATTERNS.stream().map(Pattern::pattern).toList().toString();
    }

    private void logBranchEvaluations(String scopeLabel, List<BranchEvaluation> evaluations) {
        if (evaluations == null || evaluations.isEmpty()) {
            log("[INFO] Codex branch selection " + scopeLabel + " candidates: <none>");
            return;
        }
        log("[INFO] Codex branch selection " + scopeLabel + " candidates (" + evaluations.size() + "):");
        for (BranchEvaluation evaluation : evaluations) {
            String status = evaluation.matches() ? "MATCH" : "SKIP";
            log("[INFO]   [" + status + "] " + evaluation.branch() + " -> " + evaluation.reason());
        }
    }

    private String buildCodexUpdateCommandSequence(Path repo, CodexBranchSelection selection) {
        String targetBranch = selection.targetBranch();
        String safeBranchForEcho = targetBranch == null ? "" : targetBranch.replace("\"", "\\\"");
        String checkoutCommand = selection.createTrackingBranch()
                ? "(git show-ref --verify --quiet refs/heads/" + escape(targetBranch)
                + " && git checkout " + escape(targetBranch)
                + " || git checkout --track -b " + escape(targetBranch) + " origin/" + escape(targetBranch) + ")"
                : "git checkout " + escape(targetBranch);
        return "cd " + escape(repo.toString())
                + " && git fetch --all --prune"
                + " && " + checkoutCommand
                + " && (git ls-remote --exit-code --heads origin " + escape(targetBranch)
                + " >/dev/null 2>&1 && git pull --ff-only origin " + escape(targetBranch)
                + " || echo \"[INFO] origin/" + safeBranchForEcho + " not found; skipped pull\")"
                + " && printf '%s\\n' codex > " + escape(UPDATE_SOURCE_FILE);
    }

    private void logCodexUpdateSummary(String previousBranch, String previousHash) {
        Path repo = repoPath();
        String newBranch = runGitQuery(repo, "git rev-parse --abbrev-ref HEAD");
        String newHash = runGitQuery(repo, "git rev-parse --short HEAD");
        if (newBranch == null || newBranch.isBlank()) {
            newBranch = "<unknown>";
        } else {
            newBranch = newBranch.trim();
        }
        if (newHash == null || newHash.isBlank()) {
            newHash = "<unknown>";
        } else {
            newHash = newHash.trim();
        }
        log("[INFO] Codex debug update summary: previous branch=" + previousBranch + " (" + previousHash
                + "), new branch=" + newBranch + " (" + newHash + "), status=success.");
    }

    private void handleCodexUpdateSuccess(String previousBranch, String previousHash) {
        logCodexUpdateSummary(previousBranch, previousHash);
        boolean restarted = runShellCommandSync(
                "systemctl restart " + SERVICE_NAME,
                "Restart monitor service after Codex debug update",
                true,
                true
        );
        if (!restarted) {
            log("[WARN] Codex update succeeded, but monitor service restart failed. Control Center will stay open.");
            SwingUtilities.invokeLater(() -> setActionButtons(true));
            return;
        }

        Path launcher = repoPath().resolve("UniversalMonitorControlCenter.sh");
        relaunchApplication(launcher);
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
        String text;
        try {
            text = loadMergedMonitorConfigText();
        } catch (Exception ex) {
            text = null;
        }
        refreshTransportModeStatusFromConfig(text, verbose, System.currentTimeMillis());
    }

    private void refreshTransportModeStatusFromConfig(String text, boolean verbose, long nowMs) {
        try {
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
            boolean forceNetworkRefresh = lastWifiEnabledState == null
                    || lastWifiEnabledState != enabled
                    || (nowMs - lastPreviewNetworkSnapshotAtMs) >= BACKGROUND_NETWORK_REFRESH_MS;
            lastWifiEnabledState = enabled;
            updatePreviewWifiStatus(enabled, forceNetworkRefresh, nowMs);
        } catch (Exception ex) {
            setTransportIndicator("UNKNOWN", Color.GRAY);
            lastWifiEnabledState = null;
            updatePreviewWifiStatus(false, false, nowMs);
            log("[WARN] Failed to refresh transport mode: " + ex.getMessage());
        }
    }

    private void updatePreviewWifiStatus(boolean wifiEnabled) {
        updatePreviewWifiStatus(wifiEnabled, true, System.currentTimeMillis());
    }

    private void updatePreviewWifiStatus(boolean wifiEnabled, boolean forceNetworkRefresh, long nowMs) {
        if (forceNetworkRefresh) {
            String hostname = detectLocalHostname();
            List<InterfaceIpv4Info> ipAddresses = resolveInterfaceIpv4Addresses();
            String primaryIp = ipAddresses.isEmpty() ? "No IPv4 address" : formatInterfaceIp(ipAddresses.get(0));
            String secondaryIp = ipAddresses.size() > 1 ? formatInterfaceIp(ipAddresses.get(1)) : "--";
            String compactIpDisplay = ipAddresses.isEmpty()
                    ? "No IPv4 address"
                    : ipAddresses.get(0).ipAddress() + (ipAddresses.size() > 1 ? " | " + ipAddresses.get(1).ipAddress() : "");
            lastPreviewHostname = hostname;
            lastPreviewPrimaryIp = primaryIp;
            lastPreviewSecondaryIp = secondaryIp;
            lastPreviewCompactIpDisplay = compactIpDisplay;
            lastPreviewNetworkSnapshotAtMs = nowMs;
        }
        String stateText = wifiEnabled ? "Enabled" : "Disabled";
        Color stateColor = wifiEnabled ? new Color(24, 170, 24) : new Color(191, 120, 24);
        if (!stateText.equals(previewWifiStateLabel.getText())) {
            previewWifiStateLabel.setText(stateText);
        }
        if (!stateColor.equals(previewWifiStateLabel.getForeground())) {
            previewWifiStateLabel.setForeground(stateColor);
        }
        if (!lastPreviewHostname.equals(previewWifiHostnameLabel.getText())) {
            previewWifiHostnameLabel.setText(lastPreviewHostname);
        }
        String primaryLabel = formatPreviewIpLabel(lastPreviewPrimaryIp);
        if (!primaryLabel.equals(previewPrimaryIpLabel.getText())) {
            previewPrimaryIpLabel.setText(primaryLabel);
        }
        String secondaryLabel = formatPreviewIpLabel(lastPreviewSecondaryIp);
        if (!secondaryLabel.equals(previewSecondaryIpLabel.getText())) {
            previewSecondaryIpLabel.setText(secondaryLabel);
        }
        if (!lastPreviewPrimaryIp.equals(previewPrimaryIpLabel.getToolTipText())) {
            previewPrimaryIpLabel.setToolTipText(lastPreviewPrimaryIp);
        }
        if (!lastPreviewSecondaryIp.equals(previewSecondaryIpLabel.getToolTipText())) {
            previewSecondaryIpLabel.setToolTipText(lastPreviewSecondaryIp);
        }
        int port = parseWifiPort(wifiPortField.getText(), Integer.parseInt(DEFAULT_WIFI_PORT));
        fakeDisplayPanel.setPreviewWifiStatus(wifiEnabled, lastPreviewHostname, lastPreviewCompactIpDisplay, port);
        desktopDashboardPanel.setPreviewWifiStatus(wifiEnabled, lastPreviewHostname, lastPreviewCompactIpDisplay, port);
    }

    private String detectLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            String envHost = System.getenv("HOSTNAME");
            return (envHost == null || envHost.isBlank()) ? "unknown-host" : envHost.trim();
        }
    }

    private List<InterfaceIpv4Info> resolveInterfaceIpv4Addresses() {
        List<InterfaceIpv4Info> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        candidates.add(new InterfaceIpv4Info(
                                network.getName(),
                                address.getHostAddress(),
                                looksLikeVpnInterface(network.getName())
                        ));
                    }
                }
            }
        } catch (SocketException ignored) {
            return List.of();
        }
        candidates.sort((a, b) -> {
            if (a.vpn() != b.vpn()) {
                return a.vpn() ? 1 : -1;
            }
            return a.interfaceName().compareToIgnoreCase(b.interfaceName());
        });
        List<InterfaceIpv4Info> selected = new ArrayList<>();
        for (InterfaceIpv4Info candidate : candidates) {
            boolean duplicateIp = selected.stream().anyMatch(existing -> existing.ipAddress().equals(candidate.ipAddress()));
            if (!duplicateIp) {
                selected.add(candidate);
            }
            if (selected.size() >= 2) {
                break;
            }
        }
        return selected;
    }

    private boolean looksLikeVpnInterface(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.startsWith("wg")
                || normalized.startsWith("tun")
                || normalized.startsWith("tap")
                || normalized.startsWith("ppp")
                || normalized.startsWith("tailscale")
                || normalized.startsWith("zt")
                || normalized.contains("vpn");
    }

    private String formatInterfaceIp(InterfaceIpv4Info info) {
        if (info == null) {
            return "--";
        }
        String label = info.vpn() ? "VPN" : "LAN";
        String interfaceLabel = abbreviateInterfaceLabel(info.interfaceName(), 14);
        return label + " " + interfaceLabel + " · " + info.ipAddress();
    }

    private String formatPreviewIpLabel(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "--";
        }
        String escaped = rawText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(" · ", " <br/>");
        return "<html>" + escaped + "</html>";
    }

    private String abbreviateInterfaceLabel(String interfaceName, int maxLength) {
        if (interfaceName == null || interfaceName.isBlank()) {
            return "(unknown)";
        }
        String cleaned = interfaceName.trim();
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        int head = Math.max(3, (maxLength - 1) / 2);
        int tail = Math.max(2, maxLength - head - 1);
        return cleaned.substring(0, head) + "…" + cleaned.substring(cleaned.length() - tail);
    }

    private record InterfaceIpv4Info(String interfaceName, String ipAddress, boolean vpn) {}

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
                "qbittorrent_enabled",
                "qbittorrent_host",
                "qbittorrent_port",
                "qbittorrent_url",
                "qbittorrent_username",
                "qbittorrent_password",
                "qbittorrent_timeout",
                "qbittorrent_poll_interval",
                "r4_display_rotation",
                "r3_display_rotation",
                "mega_display_rotation",
                "uno_r3_screen_size",
                "mega_screen_size",
                "storage_enabled_targets",
                "storage_disk0_target",
                "storage_disk1_target",
                "graph_net_down_enabled",
                "graph_net_up_enabled"
        )) {
            merged = copyConfigEntryIfPresent(merged, overrideText, key);
        }
        return merged;
    }

    private void refreshStorageTargetsFromSystem(boolean verbose) {
        List<StorageTarget> detected = detectStorageTargets();
        storageTargetsById.clear();
        storageTargetCheckboxes.clear();
        storageTargetsPanel.removeAll();
        List<String> targetLabels = new ArrayList<>();
        for (StorageTarget target : detected) {
            storageTargetsById.put(target.id(), target);
            JCheckBox checkbox = new JCheckBox(target.displayLabel());
            checkbox.setSelected(true);
            checkbox.setToolTipText(target.id());
            storageTargetCheckboxes.put(target.id(), checkbox);
            storageTargetsPanel.add(checkbox);
            storageTargetsPanel.add(Box.createVerticalStrut(4));
            targetLabels.add(target.displayLabel());
        }
        rebuildStorageTargetSelectors();
        controlCenterStorageActivityPanel.setConfiguredTargets(targetLabels);
        popoutStorageActivityPanel.setConfiguredTargets(targetLabels);
        storageTargetsPanel.revalidate();
        storageTargetsPanel.repaint();
        applyTheme();
        if (verbose) {
            log("[INFO] Refreshed storage target list (" + detected.size() + " targets).");
        }
    }

    private List<StorageTarget> detectStorageTargets() {
        List<StorageTarget> targets = new ArrayList<>();
        ProcessBuilder builder = new ProcessBuilder("lsblk", "-nrP", "-o", "NAME,TYPE,SIZE,FSTYPE,MOUNTPOINT,LABEL,MODEL");
        builder.directory(repoPath().toFile());
        try {
            Process process = builder.start();
            List<String> lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).lines().toList();
            process.waitFor();
            for (String line : lines) {
                Map<String, String> row = parseLsblkPairs(line);
                String type = row.getOrDefault("TYPE", "").trim();
                String mount = row.getOrDefault("MOUNTPOINT", "").trim();
                if (!(type.equals("disk") || type.equals("part") || type.equals("lvm") || type.equals("crypt") || type.equals("raid0") || type.equals("raid1"))) {
                    continue;
                }
                if (mount.startsWith("/boot")) {
                    continue;
                }
                String name = row.getOrDefault("NAME", "").trim();
                String label = row.getOrDefault("LABEL", "").trim();
                String model = row.getOrDefault("MODEL", "").trim();
                String fstype = row.getOrDefault("FSTYPE", "").trim();
                String size = row.getOrDefault("SIZE", "").trim();
                String id = !mount.isBlank() ? "mnt:" + mount : "dev:/dev/" + name;
                String display = (label.isBlank() ? (model.isBlank() ? name : model) : label)
                        + " • " + (mount.isBlank() ? "/dev/" + name : mount)
                        + (size.isBlank() ? "" : " • " + size)
                        + (fstype.isBlank() ? "" : " • " + fstype);
                targets.add(new StorageTarget(id, display, mount));
            }
        } catch (Exception ex) {
            log("[WARN] Failed to detect storage targets with lsblk: " + ex.getMessage());
        }
        if (targets.isEmpty()) {
            targets.addAll(detectStorageTargetsFromDf());
            if (!targets.isEmpty()) {
                log("[INFO] lsblk returned no storage targets; used mounted filesystem fallback (df).");
            }
        }
        return targets;
    }

    private List<StorageTarget> detectStorageTargetsFromDf() {
        List<StorageTarget> targets = new ArrayList<>();
        ProcessBuilder builder = new ProcessBuilder("df", "-P", "-T");
        builder.directory(repoPath().toFile());
        try {
            Process process = builder.start();
            List<String> lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).lines().toList();
            process.waitFor();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 7) {
                    continue;
                }
                String filesystem = parts[0];
                String fsType = parts[1];
                String mount = parts[6];
                if (!isRealStorageFs(fsType) || mount.startsWith("/boot")) {
                    continue;
                }
                String id = "mnt:" + mount;
                String display = filesystem + " • " + mount + " • " + fsType;
                targets.add(new StorageTarget(id, display, mount));
            }
        } catch (Exception ex) {
            log("[WARN] Failed fallback storage detection with df: " + ex.getMessage());
        }
        return targets;
    }

    private boolean isRealStorageFs(String fsType) {
        if (fsType == null || fsType.isBlank()) {
            return false;
        }
        String normalized = fsType.trim().toLowerCase(Locale.ROOT);
        return !Set.of(
                "tmpfs", "devtmpfs", "squashfs", "overlay", "proc", "sysfs", "cgroup", "cgroup2",
                "pstore", "debugfs", "tracefs", "mqueue", "ramfs", "nsfs", "autofs", "fusectl",
                "securityfs", "configfs", "selinuxfs", "bpf"
        ).contains(normalized);
    }

    private Map<String, String> parseLsblkPairs(String line) {
        Map<String, String> map = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\w+)=\"([^\"]*)\"").matcher(line == null ? "" : line);
        while (matcher.find()) {
            map.put(matcher.group(1), matcher.group(2));
        }
        return map;
    }

    private void rebuildStorageTargetSelectors() {
        String currentDisk0 = extractStorageTargetId(disk0Selector.getSelectedItem());
        String currentDisk1 = extractStorageTargetId(disk1Selector.getSelectedItem());
        disk0Selector.removeAllItems();
        disk1Selector.removeAllItems();
        disk0Selector.addItem("Auto (use /)");
        disk1Selector.addItem("Auto (best secondary target)");
        for (StorageTarget target : storageTargetsById.values()) {
            String label = target.displayLabel() + " [" + target.id() + "]";
            disk0Selector.addItem(label);
            disk1Selector.addItem(label);
        }
        selectStorageTargetInCombo(disk0Selector, currentDisk0);
        selectStorageTargetInCombo(disk1Selector, currentDisk1);
        updateStorageSelectionSummaryLabel();
    }

    private String ROOT_MOUNT_FALLBACK_LABEL() {
        return "/";
    }

    private void selectStorageTargetInCombo(JComboBox<String> selector, String id) {
        if (id == null || id.isBlank()) {
            selector.setSelectedIndex(0);
            return;
        }
        for (int i = 0; i < selector.getItemCount(); i++) {
            String item = selector.getItemAt(i);
            if (item != null && item.endsWith("[" + id + "]")) {
                selector.setSelectedIndex(i);
                return;
            }
        }
        selector.setSelectedIndex(0);
    }

    private String extractStorageTargetId(Object selectedItem) {
        if (selectedItem == null) {
            return "";
        }
        String text = selectedItem.toString();
        int start = text.lastIndexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start + 1, end).trim();
        }
        return "";
    }

    private List<String> selectedEnabledStorageTargetIds() {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, JCheckBox> entry : storageTargetCheckboxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    private List<String> ensureSelectedDiskTargetsIncluded(List<String> enabledStorageTargets, String disk0Target, String disk1Target) {
        List<String> adjusted = new ArrayList<>(enabledStorageTargets);
        if (!disk0Target.isBlank() && !adjusted.contains(disk0Target)) {
            adjusted.add(disk0Target);
        }
        if (!disk1Target.isBlank() && !adjusted.contains(disk1Target)) {
            adjusted.add(disk1Target);
        }
        return adjusted;
    }

    private void updateStorageSelectionSummaryLabel() {
        String disk0 = extractStorageTargetId(disk0Selector.getSelectedItem());
        String disk1 = extractStorageTargetId(disk1Selector.getSelectedItem());
        String disk0Text = disk0.isBlank() ? "Auto (/)" : disk0;
        String disk1Text = disk1.isBlank() ? "Auto (secondary)" : disk1;
        storageSelectionSummaryLabel.setText("Disk0: " + disk0Text + "   |   Disk1: " + disk1Text);
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
                graphNetDownToggle.setSelected(false);
                graphNetUpToggle.setSelected(false);
                programModeSelector.setSelectedItem(PROGRAM_MODE_SYSTEM_MONITOR);
                macroTriggerModelSelector.setSelectedItem("Whole-screen tap cycles entries");
                macroEntriesArea.setText("");
                wifiHostField.setText("");
                qbittorrentHostField.setText(DEFAULT_QBITTORRENT_HOST);
                qbittorrentPortField.setText(String.valueOf(DEFAULT_QBITTORRENT_PORT));
                qbittorrentUsernameField.setText("");
                qbittorrentPasswordField.setText("");
                wifiBoardNameField.setText(DEFAULT_WIFI_BOARD_NAME);
                wifiTargetHostField.setText("");
                wifiTargetHostnameField.setText("");
                setRotationSelectorValue(r4RotationSelector, DISPLAY_ROTATION_NORMAL);
                setRotationSelectorValue(r3RotationSelector, DISPLAY_ROTATION_NORMAL);
                setRotationSelectorValue(megaRotationSelector, DISPLAY_ROTATION_NORMAL);
                setScreenSizeSelectorValue(unoR3ScreenSizeSelector, "35");
                setScreenSizeSelectorValue(megaScreenSizeSelector, "35");
                refreshStorageTargetsFromSystem(false);
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
            boolean graphNetDownEnabled = readBooleanConfigValue(text, "graph_net_down_enabled", false);
            boolean graphNetUpEnabled = readBooleanConfigValue(text, "graph_net_up_enabled", false);
            String wifiHost = readStringConfigValue(text, "wifi_host", "");
            String selectedProgramMode = normalizeProgramMode(readStringConfigValue(text, "program_mode", PROGRAM_MODE_SYSTEM_MONITOR));
            String macroTriggerModel = normalizeMacroTriggerModel(readStringConfigValue(text, "macro_trigger_model", "Whole-screen tap cycles entries"));
            List<String> macroEntries = readStringArrayConfigValue(text, "macro_entries");
            String qbittorrentUrl = readStringConfigValue(text, "qbittorrent_url", "http://" + DEFAULT_QBITTORRENT_HOST + ":" + DEFAULT_QBITTORRENT_PORT);
            String qbittorrentHost = normalizeQbittorrentHost(readStringConfigValue(text, "qbittorrent_host", ""), qbittorrentUrl);
            int qbittorrentPort = resolveQbittorrentPort(readStringConfigValue(text, "qbittorrent_port", ""), qbittorrentUrl);
            String qbittorrentUsername = readStringConfigValue(text, "qbittorrent_username", "");
            String qbittorrentPassword = readStringConfigValue(text, "qbittorrent_password", "");
            arduinoPortSelector.setSelectedItem(arduinoPort == null || arduinoPort.isBlank() ? "AUTO" : arduinoPort);
            wifiPortField.setText(String.valueOf(wifiResolution.port()));
            wifiConnectionModeSelector.setSelectedItem(wifiAutoDiscovery ? WIFI_MODE_AUTO_DISCOVERY : WIFI_MODE_MANUAL);
            wifiDiscoveryDebugToggle.setSelected(wifiDiscoveryDebug);
            wifiDiscoveryIgnoreBoardFilterToggle.setSelected(wifiDiscoveryIgnoreBoardFilter);
            graphNetDownToggle.setSelected(graphNetDownEnabled);
            graphNetUpToggle.setSelected(graphNetUpEnabled);
            programModeSelector.setSelectedItem(selectedProgramMode);
            macroTriggerModelSelector.setSelectedItem(macroTriggerModel);
            macroEntriesArea.setText(String.join("\n", macroEntries));
            wifiHostField.setText(wifiHost == null ? "" : wifiHost.trim());
            qbittorrentHostField.setText(qbittorrentHost);
            qbittorrentPortField.setText(String.valueOf(qbittorrentPort));
            qbittorrentUsernameField.setText(qbittorrentUsername == null ? "" : qbittorrentUsername.trim());
            qbittorrentPasswordField.setText(qbittorrentPassword == null ? "" : qbittorrentPassword);
            wifiBoardNameField.setText(resolveEffectiveWifiHeaderValue("WIFI_DEVICE_NAME_VALUE", DEFAULT_WIFI_BOARD_NAME));
            wifiTargetHostField.setText(resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOST_VALUE", ""));
            wifiTargetHostnameField.setText(resolveEffectiveWifiHeaderValue("WIFI_TARGET_HOSTNAME_VALUE", ""));
            setRotationSelectorValue(r4RotationSelector, resolveDisplayRotation(text, "r4_display_rotation"));
            setRotationSelectorValue(r3RotationSelector, resolveDisplayRotation(text, "r3_display_rotation"));
            setRotationSelectorValue(megaRotationSelector, resolveDisplayRotation(text, "mega_display_rotation"));
            setScreenSizeSelectorValue(unoR3ScreenSizeSelector, resolveScreenSizeConfig(text, "uno_r3_screen_size", "35"));
            setScreenSizeSelectorValue(megaScreenSizeSelector, resolveScreenSizeConfig(text, "mega_screen_size", "35"));
            refreshStorageTargetsFromSystem(false);
            List<String> enabledStorageTargets = readStringArrayConfigValue(text, "storage_enabled_targets");
            String disk0Target = readStringConfigValue(text, "storage_disk0_target", "");
            String disk1Target = readStringConfigValue(text, "storage_disk1_target", "");
            for (Map.Entry<String, JCheckBox> entry : storageTargetCheckboxes.entrySet()) {
                entry.getValue().setSelected(enabledStorageTargets.isEmpty() || enabledStorageTargets.contains(entry.getKey()));
            }
            selectStorageTargetInCombo(disk0Selector, disk0Target);
            selectStorageTargetInCombo(disk1Selector, disk1Target);
            updateStorageSelectionSummaryLabel();
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
                        + " qBittorrent host=" + qbittorrentHost
                        + ", port=" + qbittorrentPort
                        + ", username=" + (qbittorrentUsername == null || qbittorrentUsername.isBlank() ? "<unset>" : qbittorrentUsername.trim()) + "."
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

    private void saveMonitorConnectionSettings(boolean flashWifiBoards) {
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
        String qbittorrentHost = normalizeQbittorrentHost(qbittorrentHostField.getText() == null ? "" : qbittorrentHostField.getText().trim(), null);
        String qbittorrentPortText = qbittorrentPortField.getText() == null ? "" : qbittorrentPortField.getText().trim();
        int qbittorrentPort;
        try {
            qbittorrentPort = Integer.parseInt(qbittorrentPortText);
        } catch (NumberFormatException ex) {
            log("[WARN] qBittorrent port must be a number.");
            return;
        }
        if (qbittorrentPort < 1 || qbittorrentPort > 65535) {
            log("[WARN] qBittorrent port must be between 1 and 65535.");
            return;
        }
        String qbittorrentUsername = qbittorrentUsernameField.getText() == null ? "" : qbittorrentUsernameField.getText().trim();
        String qbittorrentPassword = new String(qbittorrentPasswordField.getPassword());
        String qbittorrentUrl = buildQbittorrentBaseUrl(qbittorrentHost, qbittorrentPort);
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
        boolean graphNetDownEnabled = graphNetDownToggle.isSelected();
        boolean graphNetUpEnabled = graphNetUpToggle.isSelected();
        String programMode = normalizeProgramMode(String.valueOf(programModeSelector.getSelectedItem()));
        String macroTriggerModel = normalizeMacroTriggerModel(String.valueOf(macroTriggerModelSelector.getSelectedItem()));
        List<String> macroEntries = normalizeMacroEntries(macroEntriesArea.getText());
        List<String> enabledStorageTargets = selectedEnabledStorageTargetIds();
        String disk0Target = extractStorageTargetId(disk0Selector.getSelectedItem());
        String disk1Target = extractStorageTargetId(disk1Selector.getSelectedItem());
        enabledStorageTargets = ensureSelectedDiskTargetsIncluded(enabledStorageTargets, disk0Target, disk1Target);

        applyAutoModuleDependenciesForAllBoards(true);
        applyMonitorModuleStateFromProfiles(true);

        boolean qbittorrentEnabled = resolveEffectiveModuleEnabled(MonitorModule.QBITTORRENT);
        boolean gamingEnabled = resolveEffectiveModuleEnabled(MonitorModule.GAMING);
        boolean macroEnabled = resolveEffectiveModuleEnabled(MonitorModule.MACRO);
        boolean qbittorrentPageEnabled = isPageEnabledInAnyBoard("qbittorrent");
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
            updated = upsertBooleanConfigValue(updated, "graph_net_down_enabled", graphNetDownEnabled);
            updated = upsertBooleanConfigValue(updated, "graph_net_up_enabled", graphNetUpEnabled);
            updated = upsertBooleanConfigValue(updated, "wifi_enabled", true);
            updated = upsertBooleanConfigValue(updated, "prefer_usb", false);
            updated = upsertStringConfigValue(updated, "program_mode", programMode);
            updated = upsertStringConfigValue(updated, "macro_trigger_model", macroTriggerModel);
            updated = upsertStringArrayConfigValue(updated, "macro_entries", macroEntries);
            updated = upsertBooleanConfigValue(updated, "qbittorrent_enabled", qbittorrentEnabled && qbittorrentPageEnabled);
            updated = upsertBooleanConfigValue(updated, "gaming_enabled", gamingEnabled);
            updated = upsertBooleanConfigValue(updated, "macro_enabled", macroEnabled);
            updated = upsertBooleanConfigValue(updated, "page_qbittorrent_enabled", qbittorrentPageEnabled);
            updated = upsertBooleanConfigValue(updated, "page_processes_enabled", isPageEnabledInAnyBoard("processes"));
            updated = upsertBooleanConfigValue(updated, "page_gpu_enabled", isPageEnabledInAnyBoard("gpu"));
            updated = upsertBooleanConfigValue(updated, "page_storage_enabled", isPageEnabledInAnyBoard("storage"));
            updated = upsertStringConfigValue(updated, "qbittorrent_host", qbittorrentHost);
            updated = upsertNumberConfigValue(updated, "qbittorrent_port", qbittorrentPort);
            updated = upsertStringConfigValue(updated, "qbittorrent_url", qbittorrentUrl);
            updated = upsertStringConfigValue(updated, "qbittorrent_username", qbittorrentUsername);
            updated = upsertStringConfigValue(updated, "qbittorrent_password", qbittorrentPassword);
            updated = upsertNumberConfigValue(updated, "r4_display_rotation", r4Rotation);
            updated = upsertNumberConfigValue(updated, "r3_display_rotation", r3Rotation);
            updated = upsertNumberConfigValue(updated, "mega_display_rotation", megaRotation);
            updated = upsertStringConfigValue(updated, "uno_r3_screen_size", unoScreenSize);
            updated = upsertStringConfigValue(updated, "mega_screen_size", megaScreenSize);
            updated = upsertStringArrayConfigValue(updated, "storage_enabled_targets", enabledStorageTargets);
            updated = upsertStringConfigValue(updated, "storage_disk0_target", disk0Target);
            updated = upsertStringConfigValue(updated, "storage_disk1_target", disk1Target);
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
                        + ", qbittorrent_enabled=" + (qbittorrentEnabled && qbittorrentPageEnabled)
                        + ", gaming_enabled=" + gamingEnabled
                        + ", macro_enabled=" + macroEnabled
                        + ", qbittorrent_host=" + qbittorrentHost
                        + ", qbittorrent_port=" + qbittorrentPort
                        + ", qbittorrent_username=" + (qbittorrentUsername.isBlank() ? "<unset>" : qbittorrentUsername)
                        + ", wifi_host=" + (wifiHost.isBlank() ? "<unset>" : wifiHost)
                        + ", r4_display_rotation=" + r4Rotation
                        + ", r3_display_rotation=" + r3Rotation
                        + ", mega_display_rotation=" + megaRotation
                        + ", uno_r3_screen_size=" + unoScreenSize
                        + ", mega_screen_size=" + megaScreenSize
                        + ", storage_enabled_targets=" + enabledStorageTargets.size()
                        + ", storage_disk0_target=" + (disk0Target.isBlank() ? "<auto>" : disk0Target)
                        + ", storage_disk1_target=" + (disk1Target.isBlank() ? "<auto>" : disk1Target) + ").");
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
        if (flashWifiBoards) {
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
        } else {
            log("[INFO] Save & Restart Python Monitor saved monitor/profile/module settings and is restarting only the Python monitor service (no Arduino flash).");
            restartMonitorServiceForSettingsChange();
        }
    }

    private String normalizeQbittorrentHost(String rawHost, String fallbackUrl) {
        String candidate = rawHost == null ? "" : rawHost.trim();
        if (candidate.isBlank() && fallbackUrl != null && !fallbackUrl.isBlank()) {
            try {
                URI parsed = URI.create(fallbackUrl.trim());
                if (parsed.getHost() != null && !parsed.getHost().isBlank()) {
                    candidate = parsed.getHost().trim();
                }
            } catch (Exception ignored) {
            }
        }
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            try {
                URI parsed = URI.create(candidate);
                if (parsed.getHost() != null && !parsed.getHost().isBlank()) {
                    candidate = parsed.getHost().trim();
                }
            } catch (Exception ignored) {
            }
        }
        return candidate.isBlank() ? DEFAULT_QBITTORRENT_HOST : candidate;
    }

    private void saveStorageSelectionsOnly() {
        Path configPath = monitorLocalConfigPath();
        try {
            String text = ensureWritableLocalMonitorConfig();
            List<String> enabledStorageTargets = selectedEnabledStorageTargetIds();
            String disk0Target = extractStorageTargetId(disk0Selector.getSelectedItem());
            String disk1Target = extractStorageTargetId(disk1Selector.getSelectedItem());
            enabledStorageTargets = ensureSelectedDiskTargetsIncluded(enabledStorageTargets, disk0Target, disk1Target);
            String updated = upsertStringArrayConfigValue(text, "storage_enabled_targets", enabledStorageTargets);
            updated = upsertStringConfigValue(updated, "storage_disk0_target", disk0Target);
            updated = upsertStringConfigValue(updated, "storage_disk1_target", disk1Target);
            if (!updated.equals(text)) {
                Files.writeString(configPath, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("[INFO] Saved storage settings to " + configPath + " (enabled=" + enabledStorageTargets.size()
                        + ", disk0=" + (disk0Target.isBlank() ? "<auto>" : disk0Target)
                        + ", disk1=" + (disk1Target.isBlank() ? "<auto>" : disk1Target) + ").");
            } else {
                log("[INFO] Storage settings already matched in " + configPath + ".");
            }
            updateStorageSelectionSummaryLabel();
        } catch (Exception ex) {
            log("[WARN] Failed to save storage settings: " + ex.getMessage());
        }
    }

    private int resolveQbittorrentPort(String rawPort, String fallbackUrl) {
        if (rawPort != null && !rawPort.isBlank()) {
            try {
                int parsed = Integer.parseInt(rawPort.trim());
                if (parsed >= 1 && parsed <= 65535) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (fallbackUrl != null && !fallbackUrl.isBlank()) {
            try {
                URI parsed = URI.create(fallbackUrl.trim());
                if (parsed.getPort() > 0) {
                    return parsed.getPort();
                }
            } catch (Exception ignored) {
            }
        }
        return DEFAULT_QBITTORRENT_PORT;
    }

    private String buildQbittorrentBaseUrl(String host, int port) {
        return "http://" + host.trim() + ":" + port;
    }

    private void testQbittorrentConnection() {
        String host = normalizeQbittorrentHost(qbittorrentHostField.getText() == null ? "" : qbittorrentHostField.getText().trim(), null);
        String portText = qbittorrentPortField.getText() == null ? "" : qbittorrentPortField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            log("[WARN] qBittorrent connection test failed: port must be numeric.");
            return;
        }
        if (port < 1 || port > 65535) {
            log("[WARN] qBittorrent connection test failed: port must be between 1 and 65535.");
            return;
        }

        String username = qbittorrentUsernameField.getText() == null ? "" : qbittorrentUsernameField.getText().trim();
        String password = new String(qbittorrentPasswordField.getPassword());
        String baseUrl = buildQbittorrentBaseUrl(host, port);
        testQbittorrentConnectionButton.setEnabled(false);
        log("[INFO] Testing qBittorrent API endpoint " + baseUrl + " ...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
                try {
                    if (!username.isBlank()) {
                        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
                        HttpRequest loginRequest = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/api/v2/auth/login"))
                                .timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("Referer", baseUrl)
                                .POST(HttpRequest.BodyPublishers.ofString(form))
                                .build();
                        HttpResponse<String> loginResponse = client.send(loginRequest, HttpResponse.BodyHandlers.ofString());
                        String body = loginResponse.body() == null ? "" : loginResponse.body().trim().toLowerCase(Locale.ROOT);
                        if (loginResponse.statusCode() != 200 || !body.contains("ok")) {
                            log("[WARN] qBittorrent login failed (HTTP " + loginResponse.statusCode() + "). Check username/password and Web UI auth settings.");
                            return null;
                        }
                    }

                    HttpRequest versionRequest = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v2/app/version"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
                    HttpResponse<String> versionResponse = client.send(versionRequest, HttpResponse.BodyHandlers.ofString());
                    if (versionResponse.statusCode() == 200) {
                        String version = versionResponse.body() == null ? "" : versionResponse.body().trim();
                        log("[INFO] qBittorrent connection test succeeded (" + baseUrl + ", version "
                                + (version.isBlank() ? "unknown" : version) + ").");
                    } else {
                        log("[WARN] qBittorrent connection test reached host but API returned HTTP " + versionResponse.statusCode() + ".");
                    }
                } catch (Exception ex) {
                    log("[WARN] qBittorrent connection test failed for " + baseUrl + ": " + ex.getMessage());
                }
                return null;
            }

            @Override
            protected void done() {
                testQbittorrentConnectionButton.setEnabled(true);
            }
        };
        worker.execute();
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

    private record StorageTarget(String id, String displayLabel, String mountpoint) {
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

    private ConfigUpdateResult updateDebugConfig(boolean enabled, String fakeIn, boolean applyPort) {
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
                return ConfigUpdateResult.UPDATED;
            } else {
                log("[INFO] config/monitor_config.local.json already had requested debug settings.");
                return ConfigUpdateResult.UNCHANGED;
            }
        } catch (Exception ex) {
            log("[WARN] Could not update config/monitor_config.local.json debug settings: " + ex.getMessage());
            return ConfigUpdateResult.FAILED;
        }
    }

    private void ensureDebugMirrorConfig(String fakeIn) {
        ConfigUpdateResult updateResult = updateDebugConfig(true, fakeIn, true);
        if (updateResult == ConfigUpdateResult.UPDATED) {
            setDebugIndicator("ON", new Color(24, 170, 24));
            log("[INFO] Restarting service so debug mirror changes apply immediately.");
            runServiceCommand("restart");
        } else if (updateResult == ConfigUpdateResult.UNCHANGED) {
            setDebugIndicator("ON", new Color(24, 170, 24));
            log("[INFO] Debug mirror settings already match; skipping redundant service restart.");
        }
    }

    private void showDesktopDashboardWindow() {
        SwingUtilities.invokeLater(() -> {
            if (desktopDashboardWindow == null) {
                desktopDashboardWindow = new JFrame("Ray Co. Desktop Monitor Dashboard (Pop-Out) - " + APP_VERSION_DISPLAY);
                desktopDashboardWindow.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                desktopDashboardWindow.setLayout(new BorderLayout());
                desktopDashboardWindow.setMinimumSize(new Dimension(1180, 760));
                desktopDashboardWindow.setSize(1600, 960);
                desktopDashboardTabs.removeAll();
                desktopDashboardTabs.setTabPlacement(JTabbedPane.TOP);
                desktopDashboardTabs.setBorder(new EmptyBorder(0, 0, 0, 0));
                desktopDashboardTabs.addTab("Main Desktop Monitor", buildDesktopMonitorMainTab());
                desktopDashboardTabs.addTab("Storage", buildDesktopStorageTab());
                desktopDashboardTabs.addTab("SSH Stats", buildDesktopSshStatsTab());
                desktopDashboardTabs.addTab("Gaming Mode", buildDesktopGamingModeTab());
                desktopDashboardTabs.addTab("Desktop Monitor Settings", buildDesktopMonitorSettingsTab());
                desktopDashboardWindow.add(desktopDashboardTabs, BorderLayout.CENTER);
                desktopDashboardWindow.addComponentListener(new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent e) {
                        enforceDashboardThemeStability();
                    }

                    @Override
                    public void componentShown(java.awt.event.ComponentEvent e) {
                        enforceDashboardThemeStability();
                    }
                });
                desktopDashboardWindow.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        closeDesktopDashboardWindow();
                    }

                    @Override
                    public void windowClosed(java.awt.event.WindowEvent e) {
                        desktopDashboardWindow = null;
                        updatePreviewButtons();
                    }
                });
            }
            applyThemeToggleStateFromMode();
            applyTheme();
            desktopDashboardWindow.setVisible(true);
            desktopDashboardWindow.setState(Frame.NORMAL);
            desktopDashboardWindow.toFront();
            desktopDashboardWindow.requestFocus();
            refreshDesktopDashboardQuickStatus();
            refreshDesktopDashboardLogSnapshot();
            updatePreviewButtons();
        });
    }

    private void openDashboardWindowAndStartStream() {
        showDesktopDashboardWindow();
        dashboardStreamController.ensureDashboardStreamActive();
    }

    private void closeDesktopDashboardWindow() {
        SwingUtilities.invokeLater(() -> {
            if (desktopDashboardWindow != null && desktopDashboardFullscreen) {
                GraphicsDevice device = desktopDashboardWindow.getGraphicsConfiguration().getDevice();
                if (device != null && device.getFullScreenWindow() == desktopDashboardWindow) {
                    device.setFullScreenWindow(null);
                }
            }
            if (desktopDashboardWindow != null) {
                desktopDashboardWindow.dispose();
            }
            desktopDashboardWindow = null;
            desktopDashboardFullscreen = false;
            updatePreviewButtons();
        });
    }

    private void toggleDesktopDashboardFullscreen() {
        SwingUtilities.invokeLater(() -> {
            if (desktopDashboardWindow == null || !desktopDashboardWindow.isDisplayable()) {
                showDesktopDashboardWindow();
                return;
            }
            GraphicsDevice device = desktopDashboardWindow.getGraphicsConfiguration().getDevice();
            if (!desktopDashboardFullscreen) {
                desktopDashboardWindowedBounds = desktopDashboardWindow.getBounds();
                if (device != null) {
                    device.setFullScreenWindow(desktopDashboardWindow);
                } else {
                    desktopDashboardWindow.setExtendedState(Frame.MAXIMIZED_BOTH);
                }
                desktopDashboardFullscreen = true;
            } else {
                if (device != null && device.getFullScreenWindow() == desktopDashboardWindow) {
                    device.setFullScreenWindow(null);
                } else {
                    desktopDashboardWindow.setExtendedState(Frame.NORMAL);
                }
                if (desktopDashboardWindowedBounds != null) {
                    desktopDashboardWindow.setBounds(desktopDashboardWindowedBounds);
                }
                desktopDashboardFullscreen = false;
            }
            desktopDashboardWindow.toFront();
            desktopDashboardWindow.requestFocus();
        });
    }

    private JPanel buildDesktopDashboardSidePanel() {
        desktopDashboardSidePanel.removeAll();
        desktopDashboardSidePanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        desktopDashboardSidePanel.setPreferredSize(new Dimension(360, 10));

        desktopDashboardStatusPanel.removeAll();
        JPanel serviceStatusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        serviceStatusRow.add(desktopDashboardServiceDotLabel);
        serviceStatusRow.add(desktopDashboardServiceStateLabel);
        JPanel transportStatusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        transportStatusRow.add(desktopDashboardDebugDotLabel);
        transportStatusRow.add(desktopDashboardDebugStateLabel);
        desktopDashboardStatusPanel.add(serviceStatusRow);
        desktopDashboardStatusPanel.add(transportStatusRow);
        desktopDashboardStatusPanel.putClientProperty("uasmDashboardStatus", Boolean.TRUE);

        desktopDashboardControlsPanel.removeAll();
        desktopDashboardLightModeToggle.setFocusable(false);
        desktopDashboardBlackModeToggle.setFocusable(false);
        JButton startButton = new JButton("Start Service");
        startButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        startButton.addActionListener(e -> runServiceCommand("start"));
        JButton stopButton = new JButton("Stop Service");
        stopButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        stopButton.addActionListener(e -> runServiceCommand("stop"));
        JButton statusButton = new JButton("Service Status");
        statusButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        statusButton.addActionListener(e -> {
            refreshServiceStatus(true);
            refreshServiceStartupStatus(true);
            refreshDesktopDashboardQuickStatus();
        });
        JButton restartButton = new JButton("Restart Service");
        restartButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        restartButton.addActionListener(e -> runServiceCommand("restart"));
        JButton dashboardStartButton = new JButton("Start Dashboard Stream");
        dashboardStartButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        dashboardStartButton.addActionListener(e -> startDashboardPreviewFlow());
        JButton dashboardStopButton = new JButton("Stop Dashboard Stream");
        dashboardStopButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        dashboardStopButton.addActionListener(e -> stopDashboardPreviewFlow());
        JButton refreshPortsButton = new JButton("Refresh Monitor Ports");
        refreshPortsButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        refreshPortsButton.addActionListener(e -> refreshMonitorPortChoices(true));
        JButton updateAndRestartButton = new JButton("Update & Restart");
        updateAndRestartButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        updateAndRestartButton.addActionListener(e -> runUpdateWorkflow());
        JButton logsButton = new JButton("Fetch Service Logs");
        logsButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        logsButton.addActionListener(e -> fetchServiceLogsIntoDashboard());
        JButton clearButton = new JButton("Clear Log");
        clearButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        clearButton.addActionListener(e -> clearSharedCommandOutput());

        desktopDashboardControlsPanel.add(startButton);
        desktopDashboardControlsPanel.add(stopButton);
        desktopDashboardControlsPanel.add(statusButton);
        desktopDashboardControlsPanel.add(restartButton);
        desktopDashboardControlsPanel.add(dashboardStartButton);
        desktopDashboardControlsPanel.add(dashboardStopButton);
        desktopDashboardControlsPanel.add(refreshPortsButton);
        desktopDashboardControlsPanel.add(updateAndRestartButton);
        desktopDashboardControlsPanel.add(logsButton);
        desktopDashboardControlsPanel.add(clearButton);
        desktopDashboardControlsPanel.add(desktopDashboardLightModeToggle);
        desktopDashboardControlsPanel.add(desktopDashboardBlackModeToggle);
        desktopDashboardControlsPanel.putClientProperty("uasmDashboardControls", Boolean.TRUE);
        desktopDashboardSummaryPanel.removeAll();
        desktopDashboardSummaryPanel.putClientProperty("uasmDashboardSummary", Boolean.TRUE);
        desktopDashboardSummaryPanel.add(buildDashboardSummaryCard("Service & Transport", desktopDashboardTransportSummaryLabel));
        desktopDashboardSummaryPanel.add(buildDashboardSummaryCard("Runtime", desktopDashboardUptimeSummaryLabel));
        desktopDashboardSummaryPanel.add(buildDashboardSummaryCard("Network Mini", desktopDashboardNetworkSummaryLabel));
        desktopDashboardSummaryPanel.add(buildDashboardSummaryCard("Storage Mini", desktopDashboardStorageSummaryLabel));
        desktopDashboardSummaryPanel.add(buildDashboardSummaryCard("Project Version", desktopDashboardVersionSummaryLabel));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(desktopDashboardStatusPanel);
        top.add(Box.createVerticalStrut(8));
        top.add(desktopDashboardControlsPanel);

        desktopDashboardSidePanel.add(top, BorderLayout.NORTH);
        desktopDashboardSidePanel.add(desktopDashboardSummaryPanel, BorderLayout.CENTER);
        refreshDesktopDashboardMiniSummary(null);
        return desktopDashboardSidePanel;
    }

    private JPanel buildDesktopMonitorMainTab() {
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, desktopDashboardPanel, buildDesktopDashboardSidePanel());
        mainSplit.setResizeWeight(0.78);
        mainSplit.setContinuousLayout(true);
        mainSplit.setDividerSize(10);
        mainSplit.setBorder(new EmptyBorder(0, 0, 0, 0));
        mainSplit.putClientProperty("uasmDashboardSplit", Boolean.TRUE);

        desktopDashboardLogScroller.setBorder(BorderFactory.createTitledBorder("Live Monitor Log (Footer, Resizable)"));
        desktopDashboardLogScroller.putClientProperty("uasmDashboardLogScroller", Boolean.TRUE);

        JSplitPane rootSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplit, desktopDashboardLogScroller);
        rootSplit.setResizeWeight(0.80);
        rootSplit.setContinuousLayout(true);
        rootSplit.setDividerSize(10);
        rootSplit.setBorder(new EmptyBorder(0, 0, 0, 0));
        rootSplit.putClientProperty("uasmDashboardSplit", Boolean.TRUE);

        JPanel tabPanel = new JPanel(new BorderLayout());
        tabPanel.add(rootSplit, BorderLayout.CENTER);
        return tabPanel;
    }

    private JPanel buildDesktopStorageTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel header = new JLabel("<html><b>Storage Capacity / Mounts + Storage I/O / Drive Activity</b><br>"
                + "Read / Write / Utilization with rolling history from live monitor packets.</html>");
        header.setBorder(new EmptyBorder(0, 0, 8, 0));
        panel.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        popoutStorageActivityScroll.setBorder(BorderFactory.createEmptyBorder());
        popoutStorageActivityScroll.getVerticalScrollBar().setUnitIncrement(16);
        center.add(popoutStorageActivityScroll, BorderLayout.CENTER);
        popoutStorageCapacityLabel.setBorder(new EmptyBorder(4, 2, 2, 2));
        center.add(popoutStorageCapacityLabel, BorderLayout.SOUTH);
        panel.add(center, BorderLayout.CENTER);

        if (latestDashboardPacket != null) {
            popoutStorageActivityPanel.updateFromPacket(latestDashboardPacket, true);
        }
        return panel;
    }

    private JPanel buildDesktopSshStatsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.putClientProperty("uasmSettingsPanel", Boolean.TRUE);

        JPanel targetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        targetRow.add(new JLabel("User:"));
        targetRow.add(sshStatsUserField);
        targetRow.add(new JLabel("Host/IP:"));
        targetRow.add(sshStatsHostField);
        targetRow.add(new JLabel("Port:"));
        targetRow.add(sshStatsPortField);
        targetRow.add(new JLabel("Repo:"));
        targetRow.add(sshStatsRepoField);
        targetRow.add(sshStatsSyncFromRemoteButton);
        targetRow.add(sshStatsProbeButton);
        top.add(targetRow);

        JPanel statusRow = new JPanel(new GridLayout(0, 2, 8, 8));
        statusRow.add(buildInfoValuePanel("Connection", sshStatsConnectionLabel));
        statusRow.add(buildInfoValuePanel("Remote Runtime", sshStatsRuntimeLabel));
        statusRow.add(buildInfoValuePanel("Remote Transport", sshStatsTransportLabel));
        statusRow.add(buildInfoValuePanel("Remote Network", sshStatsNetworkLabel));
        top.add(statusRow);

        JLabel helper = new JLabel("<html><b>SSH Stats framework (v11.6):</b> this page reuses existing SSH target concepts and can probe a remote monitor service now. "
                + "Live packet streaming from a remote Python monitor is a planned follow-up and the stat cards/log area are intentionally scaffold-ready.</html>");
        helper.putClientProperty("uasmSettingsHelpBlock", Boolean.TRUE);
        top.add(helper);
        panel.add(top, BorderLayout.NORTH);

        JScrollPane outputScroll = new JScrollPane(sshStatsOutputArea);
        outputScroll.setBorder(BorderFactory.createTitledBorder("SSH Stats Output / Placeholder Stream Log"));
        panel.add(outputScroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDesktopGamingModeTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel cards = new JPanel(new GridLayout(0, 2, 10, 10));
        cards.add(buildDashboardSummaryCard("Current Game / App", gamingGameNameLabel));
        cards.add(buildDashboardSummaryCard("Session State", gamingSessionStateLabel));
        cards.add(buildDashboardSummaryCard("FPS", gamingFpsLabel));
        cards.add(buildDashboardSummaryCard("Frametime", gamingFrametimeLabel));
        cards.add(buildDashboardSummaryCard("1% Low", gamingOnePercentLowLabel));
        cards.add(buildDashboardSummaryCard("Average FPS", gamingAverageFpsLabel));
        cards.add(buildDashboardSummaryCard("GPU Usage / Temp", gamingGpuStatsLabel));
        cards.add(buildDashboardSummaryCard("CPU Usage / Temp", gamingCpuStatsLabel));
        cards.add(buildDashboardSummaryCard("RAM / VRAM", gamingMemoryStatsLabel));
        cards.add(buildDashboardSummaryCard("Telemetry Source", gamingTelemetrySourceLabel));
        cards.add(buildDashboardSummaryCard("Telemetry Source Status", gamingTelemetryStateLabel));
        cards.add(buildDashboardSummaryCard("MangoHud Log Path", gamingMangoHudPathLabel));
        panel.add(cards, BorderLayout.CENTER);
        JLabel footer = new JLabel("<html><b>Gaming Mode framework (v11.6):</b> Linux/Fedora telemetry is designed around explicit external providers (MangoHud-style logs). "
                + "Live parsing hooks are scaffolded now and fields are aligned for later Arduino gaming-page output.</html>");
        footer.putClientProperty("uasmSettingsHelpBlock", Boolean.TRUE);
        panel.add(footer, BorderLayout.SOUTH);
        refreshGamingModeTelemetryCards();
        return panel;
    }

    private JPanel buildDesktopMonitorSettingsTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.putClientProperty("uasmSettingsPanel", Boolean.TRUE);

        JPanel themeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        themeRow.add(new JLabel("Theme:"));
        themeRow.add(desktopDashboardLightModeToggle);
        themeRow.add(desktopDashboardBlackModeToggle);
        controls.add(themeRow);

        JPanel optionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        optionRow.add(efficiencyModeToggle);
        optionRow.add(loggingEnabledToggle);
        optionRow.add(desktopSettingsAutoRefreshToggle);
        optionRow.add(desktopSettingsShowHistoryLegendToggle);
        optionRow.add(desktopSettingsCompactSidePanelToggle);
        controls.add(optionRow);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JButton updateAndRestartButton = new JButton("Update & Restart");
        updateAndRestartButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        updateAndRestartButton.addActionListener(e -> runUpdateWorkflow());
        JButton refreshNowButton = new JButton("Refresh Dashboard Status");
        refreshNowButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        refreshNowButton.addActionListener(e -> {
            refreshDesktopDashboardQuickStatus();
            refreshDesktopDashboardLogSnapshot();
            updateDesktopSettingsStatus();
        });
        desktopDashboardFullscreenToggleButton.putClientProperty("uasmDashboardCompactButton", Boolean.TRUE);
        actionRow.add(updateAndRestartButton);
        actionRow.add(refreshNowButton);
        actionRow.add(desktopDashboardFullscreenToggleButton);
        controls.add(actionRow);
        panel.add(controls, BorderLayout.NORTH);

        JTextArea notes = new JTextArea(
                "Desktop Monitor Settings page scaffold (v11.6):\n"
                        + "- Efficiency mode is now ON by default and aggressively reduces repaint work.\n"
                        + "- Theme controls are active and shared with the main popout controls.\n"
                        + "- Charts update slower than text cards; non-focused visible windows stay active.\n"
                        + "- This page is intended to become the central desktop-only settings hub.");
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.putClientProperty("uasmSettingsHelpBlock", Boolean.TRUE);
        panel.add(notes, BorderLayout.CENTER);

        desktopSettingsStatusLabel.setBorder(new EmptyBorder(4, 2, 4, 2));
        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.add(desktopSettingsStatusLabel, BorderLayout.NORTH);
        footer.add(buildOutputPanel(desktopSettingsOutputArea, "Command Output / Logs"), BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        updateDesktopSettingsStatus();
        return panel;
    }

    private JPanel buildDashboardSummaryCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.putClientProperty("uasmDashboardSummaryCard", Boolean.TRUE);
        JLabel titleLabel = new JLabel(title);
        titleLabel.putClientProperty("uasmDashboardSummaryTitle", Boolean.TRUE);
        titleLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        valueLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private void refreshGamingModeTelemetryCards() {
        MangoHudTelemetrySnapshot snapshot = mangoHudTelemetryService.readSnapshot();
        gamingTelemetryCache.updateFrom(snapshot);
        gamingGameNameLabel.setText("Game/App: " + gamingTelemetryCache.activeGame);
        gamingSessionStateLabel.setText("Session: " + gamingTelemetryCache.sessionState);
        gamingFpsLabel.setText("FPS: " + gamingTelemetryCache.fps);
        gamingFrametimeLabel.setText("Frametime: " + gamingTelemetryCache.frametimeMs + " ms");
        gamingOnePercentLowLabel.setText("1% Low: " + gamingTelemetryCache.onePercentLow);
        gamingAverageFpsLabel.setText("Average FPS: " + gamingTelemetryCache.averageFps);
        gamingGpuStatsLabel.setText("GPU: load " + gamingTelemetryCache.gpuLoad + " | temp " + gamingTelemetryCache.gpuTemp);
        gamingCpuStatsLabel.setText("CPU: load " + gamingTelemetryCache.cpuLoad + " | temp " + gamingTelemetryCache.cpuTemp);
        gamingMemoryStatsLabel.setText("RAM/VRAM: " + gamingTelemetryCache.ramVram);
        gamingTelemetryStateLabel.setText("Source Status: " + snapshot.sourceStatus);
        gamingMangoHudPathLabel.setText("MangoHud log path: " + snapshot.logPathDisplay);
    }

    private static class GamingTelemetryCache {
        private String activeGame = "--";
        private String sessionState = "idle";
        private String fps = "--";
        private String frametimeMs = "--";
        private String onePercentLow = "--";
        private String averageFps = "--";
        private String gpuLoad = "--";
        private String gpuTemp = "--";
        private String cpuLoad = "--";
        private String cpuTemp = "--";
        private String ramVram = "--";

        private void updateFrom(MangoHudTelemetrySnapshot snapshot) {
            activeGame = snapshot.activeGame;
            sessionState = snapshot.sessionState;
            fps = snapshot.fps;
            frametimeMs = snapshot.frametimeMs;
            onePercentLow = snapshot.onePercentLow;
            averageFps = snapshot.averageFps;
            gpuLoad = snapshot.gpuLoad;
            gpuTemp = snapshot.gpuTemp;
            cpuLoad = snapshot.cpuLoad;
            cpuTemp = snapshot.cpuTemp;
            ramVram = snapshot.ramVram;
        }
    }

    private static class MangoHudTelemetryService {
        private static final List<Path> CANDIDATE_LOG_PATHS = List.of(
                Path.of(System.getProperty("user.home", ".")).resolve(".config/MangoHud/MangoHud.log"),
                Path.of(System.getProperty("user.home", ".")).resolve(".local/share/MangoHud/MangoHud.log"),
                Path.of("/tmp/MangoHud.log")
        );

        private MangoHudTelemetrySnapshot readSnapshot() {
            for (Path candidate : CANDIDATE_LOG_PATHS) {
                MangoHudTelemetrySnapshot parsed = parseSnapshotFromLog(candidate);
                if (parsed != null) {
                    return parsed;
                }
            }
            return MangoHudTelemetrySnapshot.scaffold("waiting for MangoHud log file", "--");
        }

        private MangoHudTelemetrySnapshot parseSnapshotFromLog(Path path) {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0; i--) {
                    String line = lines.get(i).trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    return MangoHudTelemetrySnapshot.fromLogLine(line, path.toString());
                }
                return MangoHudTelemetrySnapshot.scaffold("log file exists but has no parsable lines", path.toString());
            } catch (IOException ex) {
                return MangoHudTelemetrySnapshot.scaffold("log read error: " + ex.getMessage(), path.toString());
            }
        }
    }

    private static class MangoHudTelemetrySnapshot {
        private final String activeGame;
        private final String sessionState;
        private final String fps;
        private final String frametimeMs;
        private final String onePercentLow;
        private final String averageFps;
        private final String gpuLoad;
        private final String gpuTemp;
        private final String cpuLoad;
        private final String cpuTemp;
        private final String ramVram;
        private final String sourceStatus;
        private final String logPathDisplay;

        private MangoHudTelemetrySnapshot(
                String activeGame,
                String sessionState,
                String fps,
                String frametimeMs,
                String onePercentLow,
                String averageFps,
                String gpuLoad,
                String gpuTemp,
                String cpuLoad,
                String cpuTemp,
                String ramVram,
                String sourceStatus,
                String logPathDisplay
        ) {
            this.activeGame = activeGame;
            this.sessionState = sessionState;
            this.fps = fps;
            this.frametimeMs = frametimeMs;
            this.onePercentLow = onePercentLow;
            this.averageFps = averageFps;
            this.gpuLoad = gpuLoad;
            this.gpuTemp = gpuTemp;
            this.cpuLoad = cpuLoad;
            this.cpuTemp = cpuTemp;
            this.ramVram = ramVram;
            this.sourceStatus = sourceStatus;
            this.logPathDisplay = logPathDisplay;
        }

        private static MangoHudTelemetrySnapshot scaffold(String sourceStatus, String logPathDisplay) {
            return new MangoHudTelemetrySnapshot(
                    "--",
                    "idle",
                    "--",
                    "--",
                    "--",
                    "--",
                    "--",
                    "--",
                    "--",
                    "--",
                    "--",
                    sourceStatus,
                    logPathDisplay
            );
        }

        private static MangoHudTelemetrySnapshot fromLogLine(String line, String logPathDisplay) {
            String normalized = line.toLowerCase(Locale.ROOT);
            String fps = extractTrailingNumber(normalized, "fps");
            String frametime = extractTrailingNumber(normalized, "frametime");
            String gpuLoad = extractTrailingNumber(normalized, "gpu");
            String cpuLoad = extractTrailingNumber(normalized, "cpu");
            String gpuTemp = extractTrailingNumber(normalized, "gpu_temp");
            String cpuTemp = extractTrailingNumber(normalized, "cpu_temp");
            String ram = extractTrailingNumber(normalized, "ram");
            String vram = extractTrailingNumber(normalized, "vram");
            String ramVram = "RAM " + (ram.equals("--") ? "--" : ram + " MB")
                    + " | VRAM " + (vram.equals("--") ? "--" : vram + " MB");
            return new MangoHudTelemetrySnapshot(
                    "Detected via MangoHud log",
                    "active",
                    fps,
                    frametime,
                    "--",
                    "--",
                    gpuLoad.equals("--") ? "--" : gpuLoad + "%",
                    gpuTemp.equals("--") ? "--" : gpuTemp + "C",
                    cpuLoad.equals("--") ? "--" : cpuLoad + "%",
                    cpuTemp.equals("--") ? "--" : cpuTemp + "C",
                    ramVram,
                    "MangoHud telemetry scaffold online",
                    logPathDisplay
            );
        }

        private static String extractTrailingNumber(String line, String key) {
            int idx = line.lastIndexOf(key);
            if (idx < 0) {
                return "--";
            }
            String tail = line.substring(idx + key.length()).replaceAll("[^0-9.]+", " ").trim();
            if (tail.isEmpty()) {
                return "--";
            }
            String[] parts = tail.split("\\s+");
            return parts.length == 0 ? "--" : parts[0];
        }
    }

    private void appendSharedLogLine(String message, LogChannel channel) {
        if (!shouldEmitChannel(channel)) {
            return;
        }
        String text = "[" + LocalDateTime.now().format(dashboardLogTimeFormat) + "] " + message;
        LogEntry entry = new LogEntry(++logSequence, channel, text);
        boolean trimmed = false;
        while (sharedLogLines.size() >= SHARED_LOG_RING_CAPACITY) {
            sharedLogLines.pollFirst();
            trimmed = true;
        }
        sharedLogLines.addLast(entry);
        if (channel == LogChannel.COMMAND_OUTPUT) {
            while (commandOutputLines.size() >= COMMAND_OUTPUT_RING_CAPACITY) {
                commandOutputLines.pollFirst();
            }
            commandOutputLines.addLast(entry);
        }
        sharedLogVersion++;
        if (trimmed) {
            outputLogNeedsFullRender = true;
            popoutLogNeedsFullRender = true;
            settingsLogNeedsFullRender = true;
            desktopSettingsLogNeedsFullRender = true;
        }
    }

    private boolean shouldEmitChannel(LogChannel channel) {
        return switch (channel) {
            case OPTIONAL_APP -> runtimeSettings.isOptionalLogsEnabled();
            case ESSENTIAL, COMMAND_OUTPUT -> true;
        };
    }

    private void refreshDesktopDashboardLogSnapshot() {
        outputLogNeedsFullRender = true;
        popoutLogNeedsFullRender = true;
        settingsLogNeedsFullRender = true;
        desktopSettingsLogNeedsFullRender = true;
        flushVisibleLogViews();
    }

    private void flushVisibleLogViews() {
        if (isControlCenterLogViewVisible()) {
            outputLogRenderedVersion = renderLogsIfVisible(outputArea, outputLogRenderedVersion, outputLogNeedsFullRender);
            outputLogNeedsFullRender = false;
        }
        if (isPopoutLogViewVisible()) {
            popoutLogRenderedVersion = renderLogsIfVisible(desktopDashboardLogArea, popoutLogRenderedVersion, popoutLogNeedsFullRender);
            popoutLogNeedsFullRender = false;
        }
        settingsLogRenderedVersion = renderLogsIfVisible(settingsOutputArea, settingsLogRenderedVersion, settingsLogNeedsFullRender);
        settingsLogNeedsFullRender = false;
        desktopSettingsLogRenderedVersion = renderLogsIfVisible(desktopSettingsOutputArea, desktopSettingsLogRenderedVersion, desktopSettingsLogNeedsFullRender);
        desktopSettingsLogNeedsFullRender = false;
    }

    private long renderLogsIfVisible(JTextPane pane, long renderedVersion, boolean forceFullRender) {
        if (forceFullRender || renderedVersion > sharedLogVersion) {
            renderStyledLogLines(pane, sharedLogLines);
            pane.setCaretPosition(pane.getDocument().getLength());
            return sharedLogVersion;
        }
        long delta = sharedLogVersion - renderedVersion;
        if (delta <= 0) {
            return renderedVersion;
        }
        if (delta >= sharedLogLines.size()) {
            renderStyledLogLines(pane, sharedLogLines);
            pane.setCaretPosition(pane.getDocument().getLength());
            return sharedLogVersion;
        }
        int skip = Math.max(0, sharedLogLines.size() - (int) delta);
        int idx = 0;
        for (LogEntry entry : sharedLogLines) {
            if (idx++ < skip) {
                continue;
            }
            appendStyledLogLine(pane, entry.text());
        }
        pane.setCaretPosition(pane.getDocument().getLength());
        return sharedLogVersion;
    }

    private boolean isControlCenterLogViewVisible() {
        return isVisible()
                && mainTabs != null
                && mainTabs.getSelectedIndex() >= 0
                && "LOGS".equals(mainTabs.getTitleAt(mainTabs.getSelectedIndex()));
    }

    private boolean isPopoutLogViewVisible() {
        return isPopoutWindowVisibleAndNotMinimized()
                && desktopDashboardTabs != null
                && desktopDashboardTabs.getSelectedIndex() >= 0
                && "Main Desktop Monitor".equals(desktopDashboardTabs.getTitleAt(desktopDashboardTabs.getSelectedIndex()));
    }

    private LogChannel classifyLogChannel(String message) {
        if (message == null || message.isBlank()) {
            return LogChannel.OPTIONAL_APP;
        }
        String upper = message.toUpperCase(Locale.ROOT);
        if (upper.startsWith("[CLI]") || upper.startsWith("[COMMAND]") || upper.startsWith("[RAYFETCH]")
                || upper.startsWith("[RUN]") || upper.startsWith("[DONE]") || upper.startsWith("[FAIL]")
                || upper.contains("[CLI][OUT]") || upper.contains("[CLI][ERR]") || upper.startsWith("[ARDUINO-CLI INSTALL]")) {
            return LogChannel.COMMAND_OUTPUT;
        }
        if (upper.startsWith("[ERROR]") || upper.startsWith("[WARN]") || upper.startsWith("[FATAL]")
                || upper.contains("[ERR]") || upper.startsWith("[SERVICE]") || upper.startsWith("[STREAM]")
                || upper.startsWith("[WATCHDOG]") || upper.startsWith("[LIFECYCLE]")) {
            return LogChannel.ESSENTIAL;
        }
        return LogChannel.OPTIONAL_APP;
    }

    private void clearLogViewsOnly() {
        SwingUtilities.invokeLater(() -> {
            outputArea.setText("");
            desktopDashboardLogArea.setText("");
            outputLogRenderedVersion = sharedLogVersion;
            popoutLogRenderedVersion = sharedLogVersion;
            settingsOutputArea.setText("");
            desktopSettingsOutputArea.setText("");
            settingsLogRenderedVersion = sharedLogVersion;
            desktopSettingsLogRenderedVersion = sharedLogVersion;
        });
    }

    private void refreshDesktopDashboardQuickStatus() {
        SwingUtilities.invokeLater(() -> {
            desktopDashboardServiceStateLabel.setText("Service Running: " + serviceIndicator.getText());
            desktopDashboardDebugStateLabel.setText("Debug Mode: " + debugIndicator.getText());
            desktopDashboardServiceDotLabel.setForeground("RUNNING".equalsIgnoreCase(serviceIndicator.getText()) ? new Color(74, 212, 120) : new Color(223, 121, 73));
            desktopDashboardDebugDotLabel.setForeground("ON".equalsIgnoreCase(debugIndicator.getText()) ? new Color(74, 212, 120) : new Color(223, 121, 73));
            refreshDesktopDashboardMiniSummary(null);
            enforceDashboardThemeStability();
        });
    }

    private void refreshDesktopDashboardMiniSummary(JavaSerialFakeDisplay.ParsedPacket packet) {
        String transportText = transportIndicator.getText() == null ? "--" : transportIndicator.getText();
        String debugText = debugIndicator.getText() == null ? "--" : debugIndicator.getText();
        desktopDashboardTransportSummaryLabel.setText("Transport: " + transportText + " | Debug: " + debugText);
        if (packet == null) {
            return;
        }
        String uptime = packet.get("UPTIME", packet.get("UP", "--"));
        String down = packet.get("DOWN", packet.get("NETDOWN", "--"));
        String up = packet.get("UPNET", packet.get("NETUP", "--"));
        String disk0 = packet.get("DISK0", packet.get("D0", "--"));
        String disk1 = packet.get("DISK1", packet.get("D1", "--"));
        String mount1 = packet.get("DRV1", "--");
        desktopDashboardUptimeSummaryLabel.setText("Uptime: " + uptime);
        desktopDashboardNetworkSummaryLabel.setText("Network: Down " + down + " | Up " + up);
        desktopDashboardStorageSummaryLabel.setText("Storage: D0 " + disk0 + "% | D1 " + disk1 + "% | " + abbreviateForDashboard(mount1, 20));
    }

    private String abbreviateForDashboard(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "--";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        if (maxChars <= 3) {
            return trimmed.substring(0, Math.max(0, maxChars));
        }
        return trimmed.substring(0, maxChars - 3) + "...";
    }

    private void enforceDashboardThemeStability() {
        Color panelBackground = themePanelBackground();
        Color textColor = themeTextColor();
        Color fieldBackground = themeFieldBackground();
        desktopDashboardLogArea.setBackground(fieldBackground);
        desktopDashboardLogArea.setForeground(textColor);
        desktopDashboardLogArea.setCaretColor(textColor);
        desktopDashboardServiceStateLabel.setForeground(textColor);
        desktopDashboardDebugStateLabel.setForeground(textColor);
        desktopDashboardTransportSummaryLabel.setForeground(textColor);
        desktopDashboardUptimeSummaryLabel.setForeground(textColor);
        desktopDashboardNetworkSummaryLabel.setForeground(textColor);
        desktopDashboardStorageSummaryLabel.setForeground(textColor);
        desktopDashboardSidePanel.setBackground(panelBackground);
        desktopDashboardStatusPanel.setBackground(panelBackground);
        desktopDashboardControlsPanel.setBackground(panelBackground);
        desktopDashboardSummaryPanel.setBackground(panelBackground);
        desktopDashboardLightModeToggle.setBackground(panelBackground);
        desktopDashboardLightModeToggle.setForeground(textColor);
        desktopDashboardBlackModeToggle.setBackground(panelBackground);
        desktopDashboardBlackModeToggle.setForeground(textColor);
    }

    private void renderStyledLogLines(JTextPane pane, Iterable<LogEntry> lines) {
        pane.setText("");
        for (LogEntry entry : lines) {
            appendStyledLogLine(pane, entry.text());
        }
        pane.setCaretPosition(pane.getDocument().getLength());
    }

    private void appendStyledLogLine(JTextPane pane, String line) {
        StyledDocument doc = pane.getStyledDocument();
        String text = line == null ? "" : line;
        try {
            int start = doc.getLength();
            doc.insertString(start, text, styleForColor(pane, themeTextColor()));
            applyLogHighlighting(pane, text, start);
            doc.insertString(doc.getLength(), "\n", styleForColor(pane, themeTextColor()));
        } catch (BadLocationException ignored) {
        }
    }

    private void applyLogHighlighting(JTextPane pane, String text, int baseOffset) {
        Matcher prefixMatcher = LOG_PREFIX_PATTERN.matcher(text);
        if (prefixMatcher.find() && prefixMatcher.group(2) != null) {
            int prefixStart = baseOffset + prefixMatcher.start(2);
            int prefixLength = prefixMatcher.group(2).length();
            pane.getStyledDocument().setCharacterAttributes(prefixStart, prefixLength, styleForColor(pane, colorForPrefix(prefixMatcher.group(2))), false);
        }
        Matcher numberMatcher = LOG_NUMBER_PATTERN.matcher(text);
        while (numberMatcher.find()) {
            int start = baseOffset + numberMatcher.start();
            pane.getStyledDocument().setCharacterAttributes(start, numberMatcher.end() - numberMatcher.start(), styleForColor(pane, new Color(124, 208, 255)), false);
        }
    }

    private Style styleForColor(JTextPane pane, Color color) {
        String key = "log-color-" + color.getRGB();
        StyledDocument doc = pane.getStyledDocument();
        Style style = doc.getStyle(key);
        if (style == null) {
            style = pane.addStyle(key, null);
            StyleConstants.setForeground(style, color);
        }
        return style;
    }

    private Color colorForPrefix(String prefix) {
        String normalized = prefix == null ? "" : prefix.toUpperCase(Locale.ROOT);
        if (normalized.contains("ERROR") || normalized.contains("FAIL") || normalized.contains("ERR")) return new Color(236, 110, 108);
        if (normalized.contains("WARN")) return new Color(234, 190, 95);
        if (normalized.contains("RUN")) return new Color(164, 132, 245);
        if (normalized.contains("RAYFETCH")) return new Color(120, 209, 223);
        if (normalized.contains("ARDUINO") || normalized.contains("FLASH")) return new Color(237, 159, 88);
        if (normalized.contains("PYTHON")) return new Color(112, 207, 156);
        if (normalized.contains("INFO") || normalized.contains("DONE") || normalized.contains("SERVICE")) return new Color(118, 196, 255);
        return new Color(173, 191, 223);
    }

    private boolean isTransportConnectedState(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("wifi on")
                || normalized.contains("serial")
                || normalized.contains("connected")
                || normalized.contains("open")
                || normalized.contains("active");
    }

    private void fetchServiceLogsIntoDashboard() {
        Thread t = new Thread(() -> {
            CommandSpec spec = buildShellCommand("journalctl -u " + SERVICE_NAME + " -n 40 --no-pager", true, false);
            if (spec == null) {
                log("[WARN] Could not build service log command.");
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
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            logCommandOutput("[svc] " + line.trim());
                        }
                    }
                }
                int code = process.waitFor();
                if (code != 0) {
                    log("[WARN] journalctl exited with code " + code + ".");
                }
            } catch (Exception ex) {
                log("[ERROR] Failed to load service logs: " + ex.getMessage());
            }
        }, "dashboard-log-refresh-thread");
        t.setDaemon(true);
        t.start();
    }

    private void syncSshStatsFieldsFromRemoteTarget() {
        sshStatsHostField.setText(remoteHostField.getText().trim());
        sshStatsUserField.setText(remoteUserField.getText().trim());
        String port = remotePortField.getText().trim();
        sshStatsPortField.setText(port.isBlank() ? "22" : port);
        sshStatsRepoField.setText(remoteRepoField.getText().trim());
        appendSshStatsOutput("[INFO] Pulled SSH target values from main Remote Actions fields.");
    }

    private void runSshStatsProbe() {
        String host = sshStatsHostField.getText().trim();
        String user = sshStatsUserField.getText().trim();
        String port = sshStatsPortField.getText().trim();
        String remoteRepo = sshStatsRepoField.getText().trim();
        if (host.isBlank() || user.isBlank()) {
            sshStatsConnectionLabel.setText("SSH Monitor: missing user/host");
            appendSshStatsOutput("[WARN] SSH probe canceled: set both user and host/IP.");
            return;
        }
        if (port.isBlank()) {
            port = "22";
            sshStatsPortField.setText(port);
        }
        if (remoteRepo.isBlank()) {
            sshStatsConnectionLabel.setText("SSH Monitor: missing repo path");
            appendSshStatsOutput("[WARN] SSH probe canceled: set remote project path.");
            return;
        }

        String serviceStatusCmd = buildRemoteActionCommand(RemoteAction.SHOW_MONITOR_STATUS, remoteRepo);
        String monitorConfigProbe = "cd " + escape(remoteRepo)
                + " && python3 - <<'PY'\n"
                + "import json\n"
                + "from pathlib import Path\n"
                + "paths=[Path('config/monitor_config.local.json'),Path('config/monitor_config.json'),Path('config/monitor_config.default.json')]\n"
                + "cfg={}\n"
                + "for p in paths:\n"
                + "  if p.exists():\n"
                + "    try:\n"
                + "      data=json.loads(p.read_text())\n"
                + "      if isinstance(data,dict): cfg.update(data)\n"
                + "    except Exception:\n"
                + "      pass\n"
                + "print('runtime=' + str(cfg.get('send_interval','--')))\n"
                + "print('transport=' + ('WIFI' if cfg.get('wifi_enabled',True) else 'USB ONLY'))\n"
                + "print('wifi_host=' + str(cfg.get('wifi_host','--')))\n"
                + "PY";
        String remoteBundle = serviceStatusCmd + " ; echo __SSH_STATS_SPLIT__ ; " + monitorConfigProbe;
        String finalPort = port;
        String finalHost = host;
        String finalUser = user;
        String sshWrappedCommand = "ssh -p " + escape(finalPort) + " " + escape(finalUser + "@" + finalHost) + " " + escape(remoteBundle);

        Thread probeThread = new Thread(() -> {
            appendSshStatsOutput("[INFO] Probing " + finalUser + "@" + finalHost + ":" + finalPort + " ...");
            try {
                Process process = new ProcessBuilder("bash", "-lc", sshWrappedCommand)
                        .directory(repoPath().toFile())
                        .redirectErrorStream(true)
                        .start();
                StringBuilder all = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (all.length() > 0) all.append('\n');
                        all.append(line);
                        appendSshStatsOutput("[ssh] " + line);
                    }
                }
                int code = process.waitFor();
                if (code != 0) {
                    SwingUtilities.invokeLater(() -> sshStatsConnectionLabel.setText("SSH Monitor: probe failed (exit " + code + ")"));
                    return;
                }
                parseSshProbeOutput(finalHost, all.toString());
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> sshStatsConnectionLabel.setText("SSH Monitor: probe error"));
                appendSshStatsOutput("[ERROR] SSH probe failed: " + ex.getMessage());
            }
        }, "ssh-stats-probe-thread");
        probeThread.setDaemon(true);
        probeThread.start();
    }

    private void parseSshProbeOutput(String host, String output) {
        String runtime = "--";
        String transport = "--";
        String wifiHost = "--";
        boolean serviceSeen = false;
        for (String raw : output.split("\\R")) {
            String line = raw == null ? "" : raw.trim();
            if (line.contains("Active: active (running)")) {
                serviceSeen = true;
            }
            if (line.startsWith("runtime=")) {
                runtime = line.substring("runtime=".length()).trim();
            } else if (line.startsWith("transport=")) {
                transport = line.substring("transport=".length()).trim();
            } else if (line.startsWith("wifi_host=")) {
                wifiHost = line.substring("wifi_host=".length()).trim();
            }
        }
        boolean finalServiceSeen = serviceSeen;
        String finalRuntime = runtime;
        String finalTransport = transport;
        String finalWifiHost = wifiHost;
        SwingUtilities.invokeLater(() -> {
            sshStatsConnectionLabel.setText("SSH Monitor: " + (finalServiceSeen ? "connected" : "reachable (service unknown)") + " @ " + host);
            sshStatsRuntimeLabel.setText("Runtime Interval: " + finalRuntime + "s");
            sshStatsTransportLabel.setText("Transport: " + finalTransport);
            sshStatsNetworkLabel.setText("Wi-Fi Host/IP: " + finalWifiHost);
        });
    }

    private void appendSshStatsOutput(String message) {
        SwingUtilities.invokeLater(() -> {
            String line = "[" + LocalDateTime.now().format(dashboardLogTimeFormat) + "] " + message;
            if (sshStatsOutputArea.getDocument().getLength() > 0) {
                sshStatsOutputArea.append("\n");
            }
            sshStatsOutputArea.append(line);
            String text = sshStatsOutputArea.getText();
            String[] rows = text.split("\\R");
            if (rows.length > 220) {
                sshStatsOutputArea.setText(String.join("\n", Arrays.copyOfRange(rows, rows.length - 220, rows.length)));
            }
            sshStatsOutputArea.setCaretPosition(sshStatsOutputArea.getDocument().getLength());
        });
    }

    private void updateDesktopSettingsStatus() {
        int enabled = 0;
        if (desktopSettingsAutoRefreshToggle.isSelected()) enabled++;
        if (desktopSettingsShowHistoryLegendToggle.isSelected()) enabled++;
        if (desktopSettingsCompactSidePanelToggle.isSelected()) enabled++;
        desktopSettingsStatusLabel.setText("Desktop settings scaffold toggles enabled: " + enabled + "/3"
                + " | Optional logs: " + (runtimeSettings.isOptionalLogsEnabled() ? "ON" : "OFF"));
    }

    private void installRayfetchHistoryInputSupport() {
        rayfetchCommandInputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    recallRayfetchHistory(-1);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    recallRayfetchHistory(1);
                    e.consume();
                }
            }
        });
    }

    private void rememberRayfetchCommand(String command) {
        String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return;
        }
        if (!rayfetchCommandHistory.isEmpty() && normalized.equals(rayfetchCommandHistory.peekLast())) {
            rayfetchHistoryCursor = rayfetchCommandHistory.size();
            return;
        }
        rayfetchCommandHistory.addLast(normalized);
        while (rayfetchCommandHistory.size() > 15) {
            rayfetchCommandHistory.removeFirst();
        }
        rayfetchHistoryCursor = rayfetchCommandHistory.size();
    }

    private void recallRayfetchHistory(int delta) {
        if (rayfetchCommandHistory.isEmpty()) {
            return;
        }
        int size = rayfetchCommandHistory.size();
        int next = Math.max(0, Math.min(size, rayfetchHistoryCursor + delta));
        rayfetchHistoryCursor = next;
        if (next == size) {
            rayfetchCommandInputField.setText("");
            return;
        }
        List<String> items = new ArrayList<>(rayfetchCommandHistory);
        rayfetchCommandInputField.setText(items.get(next));
        rayfetchCommandInputField.setCaretPosition(rayfetchCommandInputField.getText().length());
    }

    private void clearSharedCommandOutput() {
        sharedLogLines.clear();
        commandOutputLines.clear();
        sharedLogVersion = 0L;
        logSequence = 0L;
        outputLogRenderedVersion = 0L;
        popoutLogRenderedVersion = 0L;
        settingsLogRenderedVersion = 0L;
        desktopSettingsLogRenderedVersion = 0L;
        outputLogNeedsFullRender = true;
        popoutLogNeedsFullRender = true;
        settingsLogNeedsFullRender = true;
        desktopSettingsLogNeedsFullRender = true;
        clearLogViewsOnly();
        logCommandOutput("[CLI] Shared command/log output cleared.");
    }

    private void runRayfetchDashboardCommand() {
        String raw = rayfetchCommandInputField.getText() == null ? "" : rayfetchCommandInputField.getText().trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) {
            log("[CLI][WARN] RayFetch CLI input is empty. Try: rayfetch, json, payload-preview, arduino-status");
            return;
        }
        String mappedArg = RAYFETCH_COMMAND_ALIASES.get(raw);
        if (mappedArg == null) {
            log("[CLI][WARN] Unknown RayFetch alias '" + raw + "'. Accepted aliases: " + String.join(", ", RAYFETCH_COMMAND_ALIASES.keySet()));
            return;
        }
        rememberRayfetchCommand(raw);
        logCommandOutput("[CLI] Running: " + raw);
        logCommandOutput("[Command] python3 UniversalArduinoMonitor.py " + mappedArg);
        logCommandOutput("[RayFetch] Running alias '" + raw + "' -> " + mappedArg);
        for (String logoLine : RAYFETCH_ASCII_LOGO.split("\\R")) {
            if (!logoLine.isBlank()) {
                logCommandOutput("[RayFetch] " + logoLine);
            }
        }
        Thread runner = new Thread(() -> {
            try {
                Process process = new ProcessBuilder("bash", "-lc",
                        "cd " + escape(repoPath().toString()) + " && python3 UniversalArduinoMonitor.py " + mappedArg)
                        .directory(repoPath().toFile())
                        .start();
                Thread stdoutReader = new Thread(() -> streamProcessLines(process.getInputStream(), "[CLI][OUT]"), "rayfetch-cli-stdout");
                Thread stderrReader = new Thread(() -> streamProcessLines(process.getErrorStream(), "[CLI][ERR]"), "rayfetch-cli-stderr");
                stdoutReader.setDaemon(true);
                stderrReader.setDaemon(true);
                stdoutReader.start();
                stderrReader.start();
                int code = process.waitFor();
                stdoutReader.join(300);
                stderrReader.join(300);
                logCommandOutput(code == 0
                        ? "[CLI] Command completed successfully."
                        : "[CLI][ERR] Command exited with code " + code + ".");
            } catch (Exception ex) {
                logCommandOutput("[CLI][ERR] Failed to run command: " + ex.getMessage());
            }
        }, "rayfetch-dashboard-cli");
        runner.setDaemon(true);
        runner.start();
    }

    private void streamProcessLines(java.io.InputStream stream, String prefix) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logCommandOutput(prefix + " " + line);
            }
        } catch (IOException ex) {
            logCommandOutput("[CLI][ERR] Failed to read command stream: " + ex.getMessage());
        }
    }

    private void startDashboardPreviewFlow() {
        dashboardStreamController.startDashboardPreviewFlow();
    }

    private void stopDashboardPreviewFlow() {
        dashboardStreamController.stopDashboardPreviewFlow();
    }

    private boolean isLightTheme() {
        return themeMode == ThemeMode.LIGHT;
    }

    private boolean isBlackTheme() {
        return themeMode == ThemeMode.BLACK;
    }

    private Color themeBackground() {
        return isLightTheme() ? lightBackground : (isBlackTheme() ? blackBackground : darkBackground);
    }

    private Color themePanelBackground() {
        return isLightTheme() ? lightPanelBackground : (isBlackTheme() ? blackPanelBackground : darkPanelBackground);
    }

    private Color themeTextColor() {
        return isLightTheme() ? lightText : (isBlackTheme() ? blackText : darkText);
    }

    private Color themeAccent() {
        return isLightTheme() ? lightAccent : (isBlackTheme() ? blackAccent : darkAccent);
    }

    private Color themeBorderColor() {
        return isLightTheme() ? Color.BLACK : themeAccent();
    }

    private Color themeFieldBackground() {
        return isLightTheme() ? lightFieldBackground : (isBlackTheme() ? blackFieldBackground : darkFieldBackground);
    }

    private Color themeButtonBackground() {
        return isLightTheme() ? lightButtonBackground : (isBlackTheme() ? blackButtonBackground : darkButtonBackground);
    }

    private Color themeCriticalButtonBackground() {
        return isLightTheme() ? lightCriticalButtonBackground : (isBlackTheme() ? blackCriticalButtonBackground : darkCriticalButtonBackground);
    }

    private Color themeRestartButtonBackground() {
        return isLightTheme() ? lightRestartButtonBackground : (isBlackTheme() ? blackRestartButtonBackground : darkRestartButtonBackground);
    }

    private Color themePositiveButtonBackground() {
        return isLightTheme() ? lightPositiveButtonBackground : (isBlackTheme() ? blackPositiveButtonBackground : darkPositiveButtonBackground);
    }


    private void applyTheme() {
        SwingUtilities.invokeLater(() -> {
            Color background = themeBackground();
            Color panelBackground = themePanelBackground();
            Color textColor = themeTextColor();
            Color accent = themeAccent();
            Color borderColor = themeBorderColor();
            Color fieldBackground = themeFieldBackground();
            Color buttonBackground = themeButtonBackground();
            Color criticalButtonBackground = themeCriticalButtonBackground();
            Color restartButtonBackground = themeRestartButtonBackground();
            Color positiveButtonBackground = themePositiveButtonBackground();

            getContentPane().setBackground(background);
            styleComponentTree(getContentPane(), background, panelBackground, textColor, accent, borderColor, fieldBackground, buttonBackground, criticalButtonBackground, restartButtonBackground, positiveButtonBackground);
            if (desktopDashboardWindow != null) {
                desktopDashboardWindow.getContentPane().setBackground(background);
                styleComponentTree(desktopDashboardWindow.getContentPane(), background, panelBackground, textColor, accent, borderColor, fieldBackground, buttonBackground, criticalButtonBackground, restartButtonBackground, positiveButtonBackground);
            }
            outputArea.setCaretColor(textColor);
            desktopDashboardLogArea.setCaretColor(textColor);
            outputLogNeedsFullRender = true;
            popoutLogNeedsFullRender = true;
            flushVisibleLogViews();
            versionLabel.setForeground(accent);
            lightModeToggle.setForeground(textColor);
            lightModeToggle.setBackground(panelBackground);
            blackModeToggle.setForeground(textColor);
            blackModeToggle.setBackground(panelBackground);
            updateCustomSketchIndicatorAppearance(accent, textColor);
            fakeDisplayPanel.setBorder(new LineBorder(borderColor, 1, true));
            desktopDashboardPanel.setBorder(new LineBorder(borderColor, 1, true));
            controlCenterStorageActivityPanel.applyTheme(panelBackground, textColor, borderColor, fieldBackground);
            popoutStorageActivityPanel.applyTheme(panelBackground, textColor, borderColor, fieldBackground);
            enforceDashboardThemeStability();

            repaint();
        });
    }

    private void styleComponentTree(Component component, Color background, Color panelBackground, Color textColor, Color accent, Color borderColor,
                                    Color fieldBackground, Color buttonBackground, Color criticalButtonBackground, Color restartButtonBackground, Color positiveButtonBackground) {
        if (component instanceof JPanel panel) {
            panel.setOpaque(true);
            panel.setBackground(panelBackground);
            if (panel.getBorder() instanceof javax.swing.border.TitledBorder titledBorder) {
                titledBorder.setTitleColor(textColor);
                titledBorder.setBorder(BorderFactory.createLineBorder(borderColor));
            } else if (Boolean.TRUE.equals(panel.getClientProperty("uasmSettingsPanel"))) {
                panel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor, 1, true),
                        BorderFactory.createEmptyBorder(6, 6, 6, 6)
                ));
            } else if (Boolean.TRUE.equals(panel.getClientProperty("uasmDashboardStatus"))
                    || Boolean.TRUE.equals(panel.getClientProperty("uasmDashboardControls"))
                    || Boolean.TRUE.equals(panel.getClientProperty("uasmDashboardSummary"))) {
                panel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor, 1, true),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8)
                ));
            } else if (Boolean.TRUE.equals(panel.getClientProperty("uasmDashboardSummaryCard"))) {
                panel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor, 1, true),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8)
                ));
            }
        } else if (component instanceof JSplitPane splitPane) {
            splitPane.setBackground(background);
            if (Boolean.TRUE.equals(splitPane.getClientProperty("uasmDashboardSplit"))) {
                splitPane.setBorder(BorderFactory.createLineBorder(borderColor, 1, true));
            } else {
                splitPane.setBorder(BorderFactory.createLineBorder(borderColor));
            }
        } else if (component instanceof JTabbedPane tabbedPane) {
            tabbedPane.setBackground(panelBackground);
            tabbedPane.setForeground(textColor);
            tabbedPane.setOpaque(true);
        } else if (component instanceof JScrollPane scrollPane) {
            scrollPane.getViewport().setBackground(fieldBackground);
            if (Boolean.TRUE.equals(scrollPane.getClientProperty("uasmDashboardLogScroller"))
                    && scrollPane.getBorder() instanceof javax.swing.border.TitledBorder titledBorder) {
                titledBorder.setTitleColor(textColor);
            } else {
                scrollPane.setBorder(BorderFactory.createLineBorder(borderColor));
            }
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
                    BorderFactory.createLineBorder(borderColor),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)
            ));
        } else if (component instanceof JTextField field) {
            field.setBackground(fieldBackground);
            field.setForeground(textColor);
            field.setCaretColor(textColor);
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor),
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
            boolean isCriticalFlashButton = button == flashButton || button == saveMonitorSettingsButton || button == flashFromProfilesButton;
            boolean isRestartButton = button == saveAndRestartMonitorButton;
            boolean isPositiveUpdateButton = button == updateButton;
            boolean isDashboardCompactButton = Boolean.TRUE.equals(button.getClientProperty("uasmDashboardCompactButton"));
            Color resolvedButtonBackground = isCriticalFlashButton
                    ? criticalButtonBackground
                    : (isRestartButton ? restartButtonBackground : (isPositiveUpdateButton ? positiveButtonBackground : buttonBackground));
            button.setBackground(resolvedButtonBackground);
            button.setForeground(textColor);
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(
                            (isCriticalFlashButton || isRestartButton || isPositiveUpdateButton)
                                    ? resolvedButtonBackground.darker() : borderColor
                    ),
                    BorderFactory.createEmptyBorder(isDashboardCompactButton ? 4 : 6, isDashboardCompactButton ? 8 : 10, isDashboardCompactButton ? 4 : 6, isDashboardCompactButton ? 8 : 10)
            ));
            button.setOpaque(true);
            button.setFocusPainted(false);
            button.setContentAreaFilled(true);
        } else if (component instanceof JLabel label) {
            if (label != serviceIndicator && label != startupIndicator && label != debugIndicator
                    && label != transportIndicator && label != flashTransportIndicator
                    && label != desktopDashboardServiceDotLabel && label != desktopDashboardDebugDotLabel) {
                label.setForeground(textColor);
            }
            if (Boolean.TRUE.equals(label.getClientProperty("uasmSettingsHelpBlock"))) {
                label.setOpaque(true);
                label.setBackground(fieldBackground);
                label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor, 1, true),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8)
                ));
                String html = (String) label.getClientProperty("uasmHtmlSource");
                if (html == null) {
                    html = label.getText();
                    label.putClientProperty("uasmHtmlSource", html);
                }
                label.setText(wrapHtmlWithColor(html, textColor));
            }
        } else if (component instanceof JComboBox<?> comboBox) {
            comboBox.setBackground(fieldBackground);
            comboBox.setForeground(textColor);
        }

        if (component instanceof JComponent jComponent) {
            boolean isIndicatorLabel = jComponent == serviceIndicator
                    || jComponent == startupIndicator
                    || jComponent == debugIndicator
                    || jComponent == transportIndicator
                    || jComponent == flashTransportIndicator;
            if (!isIndicatorLabel) {
                jComponent.setForeground(textColor);
            }
            if (!(jComponent instanceof JTextArea) && !(jComponent instanceof JTextField)
                    && !(jComponent instanceof JPasswordField) && !(jComponent instanceof JScrollPane)
                    && !(jComponent instanceof JSplitPane) && !(jComponent instanceof JPanel)
                    && !(jComponent instanceof AbstractButton)) {
                jComponent.setBackground(panelBackground);
            }
            if (Boolean.TRUE.equals(jComponent.getClientProperty("uasmDashboardSummaryTitle"))) {
                jComponent.setForeground(accent);
            }
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                styleComponentTree(child, background, panelBackground, textColor, accent, borderColor, fieldBackground, buttonBackground, criticalButtonBackground, restartButtonBackground, positiveButtonBackground);
            }
        }
    }

    private String wrapHtmlWithColor(String html, Color textColor) {
        if (html == null || html.isBlank()) {
            return html;
        }
        String lower = html.toLowerCase(Locale.ROOT);
        if (!lower.contains("<html")) {
            return html;
        }
        String colorHex = String.format("#%02x%02x%02x", textColor.getRed(), textColor.getGreen(), textColor.getBlue());
        String startWrapped = html.replaceFirst("(?i)<html>", "<html><div style='color:" + colorHex + ";'>");
        if (startWrapped.toLowerCase(Locale.ROOT).contains("</html>")) {
            return startWrapped.replaceFirst("(?i)</html>", "</div></html>");
        }
        return startWrapped + "</div>";
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
        String syncScript = repoPath().resolve("scripts/arduino/sync_version.py").toString();
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

        logCommandOutput("[RUN] " + label + " in " + workingDirectory.getAbsolutePath());
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
                    logCommandOutput("[" + label + "] " + line);
                }

                int code = process.waitFor();
                if (code == 0) {
                    success = true;
                    logCommandOutput("[DONE] " + label + " completed successfully.");
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                } else {
                    logCommandOutput("[FAIL] " + label + " exited with code " + code + ".");
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

        logCommandOutput("[RUN] " + label + " in " + repoPath().toAbsolutePath());
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
                    logCommandOutput("[" + label + "] " + line);
                }
            }
            int code = process.waitFor();
            if (code == 0) {
                logCommandOutput("[DONE] " + label + " completed successfully.");
                return true;
            }
            logCommandOutput("[FAIL] " + label + " exited with code " + code + ".");
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
        profileEnabledModules.clear();

        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            boardPageSettings.put(board.id(), BoardPageSettings.defaultsFor(board));
        }
        namedPageProfiles.putAll(defaultNamedProfiles());
        profileEnabledModules.putAll(defaultProfileModules());
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
        refreshModuleToggleView();
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

    private Map<String, Set<String>> defaultProfileModules() {
        Map<String, Set<String>> modules = new LinkedHashMap<>();
        for (String profileName : defaultNamedProfiles().keySet()) {
            modules.put(profileName, new java.util.LinkedHashSet<>());
        }
        modules.computeIfAbsent("Gaming HUD", ignored -> new java.util.LinkedHashSet<>()).add(MonitorModule.GAMING.id());
        return modules;
    }

    private boolean pageDefaultEnabled(BoardProfileTarget board, String pageId) {
        return !"qbittorrent".equals(pageId);
    }

    private Map<String, Map<String, Boolean>> defaultsForEveryBoard(boolean enabled) {
        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            Map<String, Boolean> pages = new LinkedHashMap<>();
            for (PageDefinition page : board.pages()) {
                pages.put(page.id(), enabled && pageDefaultEnabled(board, page.id()));
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
            java.util.LinkedHashSet<String> enabledModules = new java.util.LinkedHashSet<>();
            for (MonitorModule module : MonitorModule.values()) {
                if (Boolean.parseBoolean(properties.getProperty("profile." + profileId + ".module." + module.id(), "false"))) {
                    enabledModules.add(module.id());
                }
            }
            profileEnabledModules.put(profileName, enabledModules);
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
                settings.pageEnabled.putIfAbsent(page.id(), pageDefaultEnabled(board, page.id()));
            }
        }
        for (String profileName : namedPageProfiles.keySet()) {
            profileEnabledModules.computeIfAbsent(profileName, ignored -> new java.util.LinkedHashSet<>());
        }
        profileEnabledModules.keySet().removeIf(name -> !namedPageProfiles.containsKey(name));
    }

    private void persistBoardPageProfiles() {
        Properties properties = new Properties();
        properties.setProperty("format_version", "1");

        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            BoardPageSettings settings = boardPageSettings.get(board.id());
            properties.setProperty("board." + board.id() + ".active_profile", settings.activeProfile);
            for (PageDefinition page : board.pages()) {
                properties.setProperty("board." + board.id() + ".page." + page.id(), String.valueOf(settings.pageEnabled.getOrDefault(page.id(), pageDefaultEnabled(board, page.id()))));
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
            Set<String> enabledModules = profileEnabledModules.getOrDefault(profileName, Set.of());
            for (MonitorModule module : MonitorModule.values()) {
                properties.setProperty("profile." + profileId + ".module." + module.id(), String.valueOf(enabledModules.contains(module.id())));
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
            profileEnabledModules.clear();
            for (BoardProfileTarget board : BoardProfileTarget.values()) {
                boardPageSettings.put(board.id(), BoardPageSettings.defaultsFor(board));
            }
            namedPageProfiles.putAll(defaultNamedProfiles());
            profileEnabledModules.putAll(defaultProfileModules());
            loadBoardPageProfilesFromPath(source);
            enforceSafeBoardProfileDefaults();
            persistBoardPageProfiles();
            refreshProfileSelectorChoices();
            refreshBoardPageToggleView();
            refreshModuleToggleView();
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
            JCheckBox box = new JCheckBox(page.label(), settings.pageEnabled.getOrDefault(page.id(), pageDefaultEnabled(board, page.id())));
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
                if (box.isSelected()) {
                    enforcePageModuleDependencies(board, settings.activeProfile, settings.pageEnabled, true);
                    refreshModuleToggleView();
                }
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

    private void applyAutoModuleDependenciesForAllBoards(boolean verbose) {
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            BoardPageSettings settings = boardPageSettings.get(board.id());
            if (settings == null) continue;
            enforcePageModuleDependencies(board, settings.activeProfile, settings.pageEnabled, verbose);
        }
        refreshModuleToggleView();
    }

    private boolean enforcePageModuleDependencies(BoardProfileTarget board, String profileName, Map<String, Boolean> pageState, boolean verbose) {
        Set<String> enabledModules = profileEnabledModules.computeIfAbsent(profileName, ignored -> new java.util.LinkedHashSet<>());
        boolean changed = false;
        for (PageDefinition page : board.pages()) {
            if (!pageState.getOrDefault(page.id(), pageDefaultEnabled(board, page.id()))) continue;
            MonitorModule dependency = requiredModuleForPage(board, page.id());
            if (dependency != null && !enabledModules.contains(dependency.id())) {
                enabledModules.add(dependency.id());
                changed = true;
                if (verbose) {
                    log("[INFO] Auto-enabled module '" + dependency.label() + "' because page '" + page.label() + "' is enabled in profile '" + profileName + "'.");
                }
            }
        }
        if (changed) persistBoardPageProfiles();
        return changed;
    }

    private MonitorModule requiredModuleForPage(BoardProfileTarget board, String pageId) {
        if ("qbittorrent".equals(pageId)) {
            return MonitorModule.QBITTORRENT;
        }
        return null;
    }

    private boolean resolveEffectiveModuleEnabled(MonitorModule module) {
        BoardPageSettings r4 = boardPageSettings.get(BoardProfileTarget.R4_WIFI.id());
        String profileName = r4 == null ? DEFAULT_PAGE_PROFILE_NAME : r4.activeProfile;
        Set<String> enabledModules = profileEnabledModules.getOrDefault(profileName, Set.of());
        return enabledModules.contains(module.id());
    }

    private boolean isPageEnabledInAnyBoard(String pageId) {
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            BoardPageSettings settings = boardPageSettings.get(board.id());
            if (settings == null) continue;
            if (settings.pageEnabled.getOrDefault(pageId, pageDefaultEnabled(board, pageId))) {
                return true;
            }
        }
        return false;
    }

    private void applyMonitorModuleStateFromProfiles(boolean verbose) {
        boolean qbittorrentPageEnabled = isPageEnabledInAnyBoard("qbittorrent");
        boolean qbittorrentEnabled = resolveEffectiveModuleEnabled(MonitorModule.QBITTORRENT) && qbittorrentPageEnabled;
        if (!qbittorrentEnabled && verbose) {
            log("[INFO] qBittorrent module/page combination is disabled, so Python monitor polling for qBittorrent will remain inactive after restart.");
        }
    }

    private void runSelectedProfileAction() {
        String action = String.valueOf(profileActionSelector.getSelectedItem());
        if (action == null) {
            return;
        }
        switch (action) {
            case "New Profile" -> createNewProfile();
            case "Save As Profile" -> saveCurrentBoardAsNamedProfile();
            case "Update Profile" -> updateSelectedProfileFromCurrentBoard();
            case "Delete Profile" -> deleteSelectedProfile();
            case "Import Profiles..." -> importBoardProfilesFromChosenPath();
            case "Export Profiles..." -> exportBoardProfilesToChosenPath();
            default -> log("[WARN] Unknown profile action: " + action);
        }
    }

    private void refreshModuleToggleView() {
        BoardProfileTarget board = currentProfileBoardSelection();
        BoardPageSettings settings = boardPageSettings.get(board.id());
        String profileName = settings == null ? DEFAULT_PAGE_PROFILE_NAME : settings.activeProfile;
        Set<String> enabledModules = profileEnabledModules.computeIfAbsent(profileName, ignored -> new java.util.LinkedHashSet<>());

        moduleTogglePanel.removeAll();
        for (MonitorModule module : MonitorModule.values()) {
            JCheckBox box = new JCheckBox(module.label(), enabledModules.contains(module.id()));
            box.putClientProperty("uasmProfilePageToggle", Boolean.TRUE);
            box.addActionListener(e -> {
                if (box.isSelected()) {
                    enabledModules.add(module.id());
                } else {
                    if (moduleHasEnabledDependentPages(module, profileName)) {
                        JOptionPane.showMessageDialog(this,
                                "One or more enabled pages in this profile depend on " + module.label() + ". Disable those pages first or keep the module enabled.",
                                "Module dependency",
                                JOptionPane.WARNING_MESSAGE);
                        box.setSelected(true);
                        return;
                    }
                    enabledModules.remove(module.id());
                }
                persistBoardPageProfiles();
            });
            JPanel card = new JPanel(new BorderLayout());
            card.putClientProperty("uasmSettingsPanel", Boolean.TRUE);
            card.add(box, BorderLayout.CENTER);
            moduleTogglePanel.add(card);
        }
        moduleTogglePanel.revalidate();
        moduleTogglePanel.repaint();
        applyTheme();
    }

    private boolean moduleHasEnabledDependentPages(MonitorModule module, String profileName) {
        for (BoardProfileTarget board : BoardProfileTarget.values()) {
            BoardPageSettings settings = boardPageSettings.get(board.id());
            if (settings == null || !profileName.equals(settings.activeProfile)) {
                continue;
            }
            for (PageDefinition page : board.pages()) {
                MonitorModule dependency = requiredModuleForPage(board, page.id());
                if (dependency == module && settings.pageEnabled.getOrDefault(page.id(), pageDefaultEnabled(board, page.id()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applySelectedProfileToCurrentBoard() {
        BoardProfileTarget board = currentProfileBoardSelection();
        Object selectedProfile = activeBoardProfileSelector.getSelectedItem();
        String profileName = selectedProfile == null ? "" : selectedProfile.toString().trim();
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
        profileEnabledModules.computeIfAbsent(profileName, ignored -> new java.util.LinkedHashSet<>());
        persistBoardPageProfiles();
        boolean synced = saveBoardPageSettingsAndSyncHeaders(false);
        refreshBoardPageToggleView();
        refreshModuleToggleView();
        log("[INFO] Applied profile '" + profileName + "' to " + board.label() + (synced ? " and synced page headers." : " (header sync reported warnings)."));
    }

    private void saveCurrentBoardAsNamedProfile() {
        BoardProfileTarget board = currentProfileBoardSelection();
        Object selectedProfile = activeBoardProfileSelector.getSelectedItem();
        String profileName = selectedProfile == null ? "" : selectedProfile.toString().trim();
        if (profileName.isBlank()) {
            log("[WARN] Profile name cannot be empty.");
            return;
        }
        Map<String, Map<String, Boolean>> profile = namedPageProfiles.computeIfAbsent(profileName, k -> defaultsForEveryBoard(true));
        BoardPageSettings settings = boardPageSettings.get(board.id());
        Map<String, Boolean> boardProfile = new LinkedHashMap<>();
        for (PageDefinition page : board.pages()) {
            boardProfile.put(page.id(), settings.pageEnabled.getOrDefault(page.id(), pageDefaultEnabled(board, page.id())));
        }
        profile.put(board.id(), boardProfile);
        settings.activeProfile = profileName;
        profileEnabledModules.computeIfAbsent(profileName, ignored -> new java.util.LinkedHashSet<>());
        persistBoardPageProfiles();
        refreshProfileSelectorChoices();
        activeBoardProfileSelector.setSelectedItem(profileName);
        refreshModuleToggleView();
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
            boardProfile.put(page.id(), settings.pageEnabled.getOrDefault(page.id(), pageDefaultEnabled(board, page.id())));
        }
        profile.put(board.id(), boardProfile);
        namedPageProfiles.put(profileName, profile);
        profileEnabledModules.put(profileName, new java.util.LinkedHashSet<>());
        settings.activeProfile = profileName;
        profileEnabledModules.computeIfAbsent(profileName, ignored -> new java.util.LinkedHashSet<>());
        persistBoardPageProfiles();
        refreshProfileSelectorChoices();
        activeBoardProfileSelector.setSelectedItem(profileName);
        refreshModuleToggleView();
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
            boardProfile.put(page.id(), settings.pageEnabled.getOrDefault(page.id(), pageDefaultEnabled(board, page.id())));
        }
        profile.put(board.id(), boardProfile);
        settings.activeProfile = profileName;
        profileEnabledModules.computeIfAbsent(profileName, ignored -> new java.util.LinkedHashSet<>());
        persistBoardPageProfiles();
        refreshModuleToggleView();
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
        profileEnabledModules.remove(profileName);
        for (BoardPageSettings settings : boardPageSettings.values()) {
            if (profileName.equals(settings.activeProfile)) settings.activeProfile = DEFAULT_PAGE_PROFILE_NAME;
        }
        persistBoardPageProfiles();
        refreshProfileSelectorChoices();
        refreshModuleToggleView();
        log("[INFO] Deleted profile '" + profileName + "'.");
    }

    private boolean saveBoardPageSettingsAndSyncHeaders(boolean verbose) {
        ensureAtLeastOnePageEnabledPerBoard();
        applyAutoModuleDependenciesForAllBoards(verbose);
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
            boolean enabled = settings.pageEnabled.getOrDefault(page.id(), pageDefaultEnabled(board, page.id()));
            header.append("#define ").append(macro).append(" ").append(enabled ? "1" : "0").append("\n");
        }
        header.append("#define UASM_GRAPH_NET_DOWN_ENABLED ").append(graphNetDownToggle.isSelected() ? "1" : "0").append("\n");
        header.append("#define UASM_GRAPH_NET_UP_ENABLED ").append(graphNetUpToggle.isSelected() ? "1" : "0").append("\n");

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
            startFakePortsButton.setEnabled(enabled && !dashboardStreamController.isFakePortsRunning());
            stopFakePortsButton.setEnabled(enabled && dashboardStreamController.isFakePortsRunning());
            connectPreviewButton.setEnabled(enabled && !dashboardStreamController.isPreviewConnected());
            disconnectPreviewButton.setEnabled(enabled && dashboardStreamController.isPreviewConnected());
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
            graphNetDownToggle.setEnabled(enabled);
            graphNetUpToggle.setEnabled(enabled);
            programModeSelector.setEnabled(enabled);
            macroTriggerModelSelector.setEnabled(enabled);
            macroEntriesArea.setEnabled(enabled);
            wifiPortField.setEnabled(enabled);
            wifiHostField.setEnabled(enabled);
            qbittorrentHostField.setEnabled(enabled);
            qbittorrentPortField.setEnabled(enabled);
            qbittorrentUsernameField.setEnabled(enabled);
            qbittorrentPasswordField.setEnabled(enabled);
            wifiBoardNameField.setEnabled(enabled);
            wifiTargetHostField.setEnabled(enabled);
            wifiTargetHostnameField.setEnabled(enabled);
            fakeInField.setEnabled(enabled);
            fakeOutField.setEnabled(enabled);
            refreshMonitorPortsButton.setEnabled(enabled);
            loadMonitorSettingsButton.setEnabled(enabled);
            saveMonitorSettingsButton.setEnabled(enabled);
            testQbittorrentConnectionButton.setEnabled(enabled);
            resetWifiPairingButton.setEnabled(enabled);
            profileBoardSelector.setEnabled(enabled);
            activeBoardProfileSelector.setEnabled(enabled);
            applyBoardProfileButton.setEnabled(enabled);
            saveBoardSettingsButton.setEnabled(enabled);
            runProfileActionButton.setEnabled(enabled);
            profileActionSelector.setEnabled(enabled);
            saveAllBoardsAndProfilesButton.setEnabled(enabled);
            flashFromProfilesButton.setEnabled(enabled);
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
            boolean running = dashboardStreamController.isFakePortsRunning();
            startFakePortsButton.setEnabled(!running);
            stopFakePortsButton.setEnabled(running);
        });
        updatePreviewButtons();
    }

    private void setTransportIndicator(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            if (!text.equals(transportIndicator.getText())) {
                transportIndicator.setText(text);
            }
            if (!color.equals(transportIndicator.getBackground())) {
                transportIndicator.setBackground(color);
            }
            if (!Color.WHITE.equals(transportIndicator.getForeground())) {
                transportIndicator.setForeground(Color.WHITE);
            }
            if (!text.equals(flashTransportIndicator.getText())) {
                flashTransportIndicator.setText(text);
            }
            if (!color.equals(flashTransportIndicator.getBackground())) {
                flashTransportIndicator.setBackground(color);
            }
            if (!Color.WHITE.equals(flashTransportIndicator.getForeground())) {
                flashTransportIndicator.setForeground(Color.WHITE);
            }
        });
    }

    private void updatePreviewButtons() {
        SwingUtilities.invokeLater(() -> {
            boolean connected = dashboardStreamController.isPreviewConnected();
            connectPreviewButton.setEnabled(!connected);
            disconnectPreviewButton.setEnabled(connected);
            popOutPreviewButton.setEnabled(true);
        });
    }

    private void setServiceIndicator(String label, Color color) {
        SwingUtilities.invokeLater(() -> {
            if (!label.equals(serviceIndicator.getText())) {
                serviceIndicator.setText(label);
            }
            if (!color.equals(serviceIndicator.getBackground())) {
                serviceIndicator.setBackground(color);
            }
            if (!Color.WHITE.equals(serviceIndicator.getForeground())) {
                serviceIndicator.setForeground(Color.WHITE);
            }
            String dashboardLabel = "Service: " + label;
            if (!dashboardLabel.equals(desktopDashboardServiceStateLabel.getText())) {
                desktopDashboardServiceStateLabel.setText(dashboardLabel);
            }
        });
    }

    private void setStartupIndicator(String label, Color color) {
        SwingUtilities.invokeLater(() -> {
            if (!label.equals(startupIndicator.getText())) {
                startupIndicator.setText(label);
            }
            if (!color.equals(startupIndicator.getBackground())) {
                startupIndicator.setBackground(color);
            }
            if (!Color.WHITE.equals(startupIndicator.getForeground())) {
                startupIndicator.setForeground(Color.WHITE);
            }
        });
    }

    private void setDebugIndicator(String label, Color color) {
        SwingUtilities.invokeLater(() -> {
            if (!label.equals(debugIndicator.getText())) {
                debugIndicator.setText(label);
            }
            if (!color.equals(debugIndicator.getBackground())) {
                debugIndicator.setBackground(color);
            }
            if (!Color.WHITE.equals(debugIndicator.getForeground())) {
                debugIndicator.setForeground(Color.WHITE);
            }
        });
    }

    private void log(String message) {
        appendSharedLogLine(message, classifyLogChannel(message));
        SwingUtilities.invokeLater(this::flushVisibleLogViews);
    }

    private void logCommandOutput(String message) {
        appendSharedLogLine(message, LogChannel.COMMAND_OUTPUT);
        SwingUtilities.invokeLater(this::flushVisibleLogViews);
    }

    private Path repoPath() {
        return Paths.get(repoField.getText().trim());
    }

    private String escape(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private String escapeHtml(String value) {
        String text = value == null ? "" : value;
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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

        boolean dashboardOnly = Arrays.stream(args)
                .anyMatch(arg -> "--dashboard-only".equalsIgnoreCase(arg) || "--popout-only".equalsIgnoreCase(arg));

        SwingUtilities.invokeLater(() -> {
            UniversalMonitorControlCenter app = new UniversalMonitorControlCenter(
                    dashboardOnly ? LaunchMode.DASHBOARD_ONLY : LaunchMode.CONTROL_CENTER
            );
            if (!dashboardOnly) {
                app.setVisible(true);
            }
        });
    }


    private record PageDefinition(String id, String label) {}

    private enum MonitorModule {
        GAMING("gaming", "Gaming module"),
        MACRO("macro", "Macro module"),
        QBITTORRENT("qbittorrent", "qBittorrent module");

        private final String id;
        private final String label;

        MonitorModule(String id, String label) {
            this.id = id;
            this.label = label;
        }

        String id() { return id; }
        String label() { return label; }
    }

    private enum BoardProfileTarget {
        R4_WIFI("r4_wifi", "R4 WiFi", List.of(
                new PageDefinition("home", "Home"),
                new PageDefinition("cpu", "CPU"),
                new PageDefinition("processes", "Processes"),
                new PageDefinition("network", "Network"),
                new PageDefinition("gpu", "GPU"),
                new PageDefinition("storage", "Extra Statistics"),
                new PageDefinition("usage_graph", "Usage Graph"),
                new PageDefinition("qbittorrent", "qBittorrent")
        )),
        UNO_R3_28("uno_r3_28", "UNO R3 2.8", List.of(
                new PageDefinition("home", "Home"),
                new PageDefinition("cpu", "CPU"),
                new PageDefinition("gpu", "GPU"),
                new PageDefinition("network", "Network"),
                new PageDefinition("storage", "Storage"),
                new PageDefinition("power", "Power"),
                new PageDefinition("usage_graph", "Usage Graph"),
                new PageDefinition("qbittorrent", "qBittorrent")
        )),
        UNO_R3_35("uno_r3_35", "UNO R3 3.5", List.of(
                new PageDefinition("home", "Home"),
                new PageDefinition("cpu", "CPU + Processes"),
                new PageDefinition("gpu", "GPU + Network"),
                new PageDefinition("storage", "Storage + Totals"),
                new PageDefinition("usage_graph", "Usage Graph"),
                new PageDefinition("qbittorrent", "qBittorrent")
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
                new PageDefinition("storage", "Storage + Battery Stats"),
                new PageDefinition("usage_graph", "Usage Graph"),
                new PageDefinition("qbittorrent", "qBittorrent")
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
                boolean enabledByDefault = !"qbittorrent".equals(page.id());
                settings.pageEnabled.put(page.id(), enabledByDefault);
            }
            return settings;
        }
    }

    private static class SharedRuntimeSettings {
        private volatile boolean loggingEnabled = true;
        private volatile boolean dashboardStreamActive = false;
        private volatile boolean lowRefreshMode = true;

        void setLoggingEnabled(boolean enabled) {
            this.loggingEnabled = enabled;
        }

        boolean isOptionalLogsEnabled() {
            return loggingEnabled;
        }

        void setDashboardStreamActive(boolean active) {
            this.dashboardStreamActive = active;
        }

        void setLowRefreshMode(boolean enabled) {
            this.lowRefreshMode = enabled;
        }
    }

    private static class StorageActivityPanel extends JPanel {
        private static final int MIN_DRIVES = 3;
        private static final int HISTORY = 72;
        private final List<int[]> readHistory = new ArrayList<>();
        private final List<int[]> writeHistory = new ArrayList<>();
        private final List<int[]> utilHistory = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private final List<String> readText = new ArrayList<>();
        private final List<String> writeText = new ArrayList<>();
        private final List<String> utilText = new ArrayList<>();
        private List<String> configuredTargets = new ArrayList<>();
        private long lastUpdateMs = 0L;
        private Color baseBackground = new Color(17, 24, 40);
        private Color cardBackground = new Color(27, 38, 61);
        private Color borderColor = new Color(80, 107, 148);
        private Color textColor = new Color(240, 245, 255);
        private Color statusColor = new Color(143, 197, 255);
        private Color chartBackground = new Color(17, 23, 38);
        private boolean efficiencyMode = true;
        private int preferredPanelHeight = -1;

        StorageActivityPanel() {
            setOpaque(true);
            setBackground(baseBackground);
            setBorder(new EmptyBorder(8, 8, 8, 8));
            ensureDriveCount(MIN_DRIVES);
            updatePreferredHeight();
        }

        void applyTheme(Color panelBackground, Color textColor, Color borderColor, Color fieldBackground) {
            this.baseBackground = fieldBackground;
            this.cardBackground = panelBackground;
            this.borderColor = borderColor;
            this.textColor = textColor;
            this.statusColor = textColor;
            this.chartBackground = fieldBackground;
            setBackground(baseBackground);
            repaint();
        }

        void setConfiguredTargets(List<String> targets) {
            configuredTargets = targets == null ? new ArrayList<>() : new ArrayList<>(targets);
            ensureDriveCount(Math.max(MIN_DRIVES, configuredTargets.size()));
            boolean changed = false;
            for (int i = 0; i < labels.size(); i++) {
                if (i >= MIN_DRIVES && i < configuredTargets.size()) {
                    String next = configuredTargets.get(i);
                    if (!next.equals(labels.get(i))) {
                        labels.set(i, next);
                        changed = true;
                    }
                }
            }
            if (updatePreferredHeight()) {
                revalidate();
                changed = true;
            }
            if (changed) {
                repaint();
            }
        }

        void setEfficiencyMode(boolean enabled) {
            if (efficiencyMode == enabled) {
                return;
            }
            efficiencyMode = enabled;
            repaint();
        }

        void updateFromPacket(JavaSerialFakeDisplay.ParsedPacket packet, boolean allowHistory) {
            if (packet == null) {
                return;
            }
            lastUpdateMs = System.currentTimeMillis();
            int driveCount = Math.max(MIN_DRIVES, configuredTargets.size());
            ensureDriveCount(driveCount);
            boolean changed = false;
            for (int slot = 0; slot < driveCount; slot++) {
                int idx = slot + 1;
                if (slot < MIN_DRIVES) {
                    String rawLabel = packet.get("SIO" + idx + "LBL", "").trim();
                    String mount = packet.get("SIO" + idx + "MNT", "").trim();
                    String fallback = slot < configuredTargets.size() ? configuredTargets.get(slot) : "";
                    String nextLabel = normalizeLabel(rawLabel, mount, idx, fallback);
                    String nextRead = packet.get("SIO" + idx + "R", "0 B/s");
                    String nextWrite = packet.get("SIO" + idx + "W", "0 B/s");
                    String nextUtil = normalizeUtil(packet.get("SIO" + idx + "UTIL", "--"));
                    changed |= !nextLabel.equals(labels.get(slot));
                    changed |= !nextRead.equals(readText.get(slot));
                    changed |= !nextWrite.equals(writeText.get(slot));
                    changed |= !nextUtil.equals(utilText.get(slot));
                    labels.set(slot, nextLabel);
                    readText.set(slot, nextRead);
                    writeText.set(slot, nextWrite);
                    utilText.set(slot, nextUtil);
                    if (allowHistory) {
                        append(readHistory.get(slot), rateToKbps(readText.get(slot)));
                        append(writeHistory.get(slot), rateToKbps(writeText.get(slot)));
                        append(utilHistory.get(slot), parsePercent(utilText.get(slot)));
                    }
                } else {
                    labels.set(slot, configuredTargets.get(slot));
                    readText.set(slot, "--");
                    writeText.set(slot, "--");
                    utilText.set(slot, "--");
                }
            }
            if (updatePreferredHeight()) {
                revalidate();
                changed = true;
            }
            if (changed || allowHistory) {
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    efficiencyMode ? RenderingHints.VALUE_ANTIALIAS_OFF : RenderingHints.VALUE_ANTIALIAS_ON
            );

            int w = getWidth();
            int h = getHeight();
            g2.setColor(baseBackground);
            g2.fillRect(0, 0, w, h);

            g2.setColor(textColor);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            g2.drawString("Storage I/O / Drive Activity", 10, 22);

            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            String status = lastUpdateMs == 0L
                    ? "Waiting for storage I/O data"
                    : "Last packet: " + Math.max(0, (System.currentTimeMillis() - lastUpdateMs) / 1000) + "s ago";
            g2.setColor(statusColor);
            g2.drawString(status, 10, 40);

            int drives = labels.size();
            int rowGap = 8;
            int rowHeight = Math.max(120, (h - 56 - rowGap * Math.max(0, drives - 1)) / Math.max(1, drives));
            int y = 48;
            for (int slot = 0; slot < drives; slot++) {
                drawDriveRow(g2, 10, y, w - 20, rowHeight, slot);
                y += rowHeight + rowGap;
            }
            g2.dispose();
        }

        private void drawDriveRow(Graphics2D g2, int x, int y, int w, int h, int slot) {
            g2.setColor(cardBackground);
            g2.fillRoundRect(x, y, w, h, 10, 10);
            g2.setColor(borderColor);
            g2.drawRoundRect(x, y, w, h, 10, 10);

            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g2.setColor(textColor);
            g2.drawString(labels.get(slot), x + 8, y + 18);

            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g2.setColor(statusColor);
            g2.drawString("Read " + readText.get(slot) + "   Write " + writeText.get(slot) + "   Busy " + utilText.get(slot), x + 8, y + 34);

            int chartX = x + 8;
            int chartY = y + 42;
            int chartW = w - 16;
            int chartH = Math.max(34, h - 52);
            g2.setColor(chartBackground);
            g2.fillRoundRect(chartX, chartY, chartW, chartH, 8, 8);
            g2.setColor(borderColor);
            g2.drawRoundRect(chartX, chartY, chartW, chartH, 8, 8);

            drawLine(g2, readHistory.get(slot), chartX, chartY, chartW, chartH, new Color(90, 210, 255));
            drawLine(g2, writeHistory.get(slot), chartX, chartY, chartW, chartH, new Color(255, 182, 72));
            drawLine(g2, utilHistory.get(slot), chartX, chartY, chartW, chartH, new Color(140, 255, 168));
        }

        private void drawLine(Graphics2D g2, int[] history, int x, int y, int w, int h, Color color) {
            int max = 1;
            for (int value : history) {
                max = Math.max(max, value);
            }
            g2.setColor(color);
            for (int i = 1; i < history.length; i++) {
                int px0 = x + (i - 1) * (w - 1) / (history.length - 1);
                int px1 = x + i * (w - 1) / (history.length - 1);
                int py0 = y + h - 1 - (history[i - 1] * (h - 4) / max) - 2;
                int py1 = y + h - 1 - (history[i] * (h - 4) / max) - 2;
                g2.drawLine(px0, py0, px1, py1);
            }
        }

        private void append(int[] history, int value) {
            System.arraycopy(history, 1, history, 0, history.length - 1);
            history[history.length - 1] = Math.max(0, value);
        }

        private int parsePercent(String text) {
            if (text == null) {
                return 0;
            }
            String normalized = text.replace("%", "").trim();
            try {
                return Integer.parseInt(normalized);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private String normalizeUtil(String value) {
            String raw = value == null ? "" : value.trim();
            if (raw.isEmpty() || "--".equals(raw)) {
                return "--";
            }
            return raw.endsWith("%") ? raw : raw + "%";
        }

        private String normalizeLabel(String rawLabel, String mount, int slotIndex, String fallback) {
            String base = rawLabel == null ? "" : rawLabel.trim();
            if (base.isEmpty() || "--".equals(base)) {
                base = fallback == null || fallback.isBlank() ? "Drive " + slotIndex : fallback;
            }
            if (mount != null && !mount.isBlank() && !"--".equals(mount)) {
                return base + "  (" + mount + ")";
            }
            return base;
        }

        private void ensureDriveCount(int drives) {
            while (labels.size() < drives) {
                int next = labels.size() + 1;
                labels.add("Drive " + next);
                readText.add("0 B/s");
                writeText.add("0 B/s");
                utilText.add("--");
                readHistory.add(new int[HISTORY]);
                writeHistory.add(new int[HISTORY]);
                utilHistory.add(new int[HISTORY]);
            }
        }

        private boolean updatePreferredHeight() {
            int rows = Math.max(MIN_DRIVES, labels.size());
            int height = Math.max(420, 56 + rows * 128 + Math.max(0, rows - 1) * 8);
            if (height == preferredPanelHeight) {
                return false;
            }
            preferredPanelHeight = height;
            setPreferredSize(new Dimension(760, height));
            return true;
        }

        private int rateToKbps(String text) {
            if (text == null) {
                return 0;
            }
            String normalized = text.trim().toUpperCase(Locale.ROOT).replace(" ", "");
            Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([KMGTP]?B)/S").matcher(normalized);
            if (!matcher.find()) {
                return 0;
            }
            double value;
            try {
                value = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ex) {
                return 0;
            }
            String unit = matcher.group(2);
            double bytes = switch (unit) {
                case "KB" -> value * 1024d;
                case "MB" -> value * 1024d * 1024d;
                case "GB" -> value * 1024d * 1024d * 1024d;
                case "TB" -> value * 1024d * 1024d * 1024d * 1024d;
                default -> value;
            };
            return (int) Math.round(bytes / 1024d);
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
