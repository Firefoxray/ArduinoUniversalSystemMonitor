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
import java.util.ArrayList;
import java.util.List;

public class UniversalMonitorControlCenter extends JFrame {

    private final JTextField repoField = new JTextField(40);
    private final JTextField fakeInField = new JTextField("/tmp/fakearduino_in", 22);
    private final JTextField fakeOutField = new JTextField("/tmp/fakearduino_out", 22);
    private final JTextArea outputArea = new JTextArea();
    private final JButton startFakePortsButton = new JButton("Start Fake Ports");
    private final JButton stopFakePortsButton = new JButton("Stop Fake Ports");
    private final JButton installButton = new JButton("Install on Linux");
    private final JButton uninstallButton = new JButton("Uninstall");
    private final JButton updateButton = new JButton("Update from GitHub");
    private final JButton flashButton = new JButton("Flash Arduino");
    private final JButton launchDebugButton = new JButton("Open Debug Display");

    private volatile Process fakePortsProcess;

    public UniversalMonitorControlCenter() {
        super("Universal Arduino System Monitor - Control Center");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1460, 920);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        repoField.setText(detectRepoRoot().toString());

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
    }

    private JPanel buildRepoPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Project Location"));
        panel.add(new JLabel("Repo Root:"));
        panel.add(repoField);

        JButton browseButton = new JButton("Browse");
        browseButton.addActionListener(e -> browseRepoPath());
        panel.add(browseButton);

        JButton openFolderButton = new JButton("Open Folder");
        openFolderButton.addActionListener(e -> openRepoFolder());
        panel.add(openFolderButton);

        return panel;
    }

    private JPanel buildActionPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 10, 10));

        JPanel appActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        appActions.setBorder(BorderFactory.createTitledBorder("Linux App Management"));
        appActions.add(installButton);
        appActions.add(uninstallButton);
        appActions.add(updateButton);
        appActions.add(flashButton);
        appActions.add(launchDebugButton);

        JPanel fakePorts = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fakePorts.setBorder(BorderFactory.createTitledBorder("Virtual Serial Ports"));
        fakePorts.add(new JLabel("Input:"));
        fakePorts.add(fakeInField);
        fakePorts.add(new JLabel("Output:"));
        fakePorts.add(fakeOutField);
        fakePorts.add(startFakePortsButton);
        fakePorts.add(stopFakePortsButton);

        panel.add(appActions);
        panel.add(fakePorts);
        return panel;
    }

    private JPanel buildPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Arduino Preview"));
        panel.add(new JavaSerialFakeDisplay.FakeDisplayPanel(), BorderLayout.CENTER);

        JLabel helper = new JLabel("Use 'Open Debug Display' for full serial tools.");
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
        installButton.addActionListener(e -> runRepoScript("install.sh"));
        uninstallButton.addActionListener(e -> runRepoScript("uninstall_monitor.sh"));
        updateButton.addActionListener(e -> runUpdateWorkflow());
        flashButton.addActionListener(e -> runRepoScript("arduino_install.sh"));
        launchDebugButton.addActionListener(e -> SwingUtilities.invokeLater(() -> new JavaSerialFakeDisplay().setVisible(true)));

        startFakePortsButton.addActionListener(e -> startFakePorts());
        stopFakePortsButton.addActionListener(e -> stopFakePorts());
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
        List<String> command = new ArrayList<String>();
        command.add("bash");
        command.add("-lc");
        command.add("chmod +x update.sh && ./update.sh");
        runCommand(command, repoPath().toFile(), "update.sh");
    }

    private void runRepoScript(String scriptName) {
        Path script = repoPath().resolve(scriptName);
        if (!Files.exists(script)) {
            log("[ERROR] Missing script: " + script);
            return;
        }

        List<String> command = new ArrayList<String>();
        command.add("bash");
        command.add("-lc");
        command.add("chmod +x " + scriptName + " && ./" + scriptName);
        runCommand(command, repoPath().toFile(), scriptName);
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

        String commandText = "socat -d -d pty,raw,echo=0,link=" + escape(fakeIn) + " pty,raw,echo=0,link=" + escape(fakeOut);
        List<String> command = new ArrayList<String>();
        command.add("bash");
        command.add("-lc");
        command.add(commandText);

        log("[RUN] " + commandText);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessBuilder pb = new ProcessBuilder(command);
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
            }
        }, "fake-port-thread");

        t.setDaemon(true);
        t.start();
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

    private void runCommand(List<String> command, File workingDirectory, String label) {
        log("[RUN] " + label + " in " + workingDirectory.getAbsolutePath());
        setActionButtons(false);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.directory(workingDirectory);
                    pb.redirectErrorStream(true);
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
            }
        }, "cmd-" + label);

        t.setDaemon(true);
        t.start();
    }

    private void setActionButtons(boolean enabled) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                installButton.setEnabled(enabled);
                uninstallButton.setEnabled(enabled);
                updateButton.setEnabled(enabled);
                flashButton.setEnabled(enabled);
                launchDebugButton.setEnabled(enabled);
            }
        });
    }

    private void updatePortButtons() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                boolean running = fakePortsProcess != null && fakePortsProcess.isAlive();
                startFakePortsButton.setEnabled(!running);
                stopFakePortsButton.setEnabled(running);
            }
        });
    }

    private void log(String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                outputArea.append(message + "\n");
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
            }
        });
    }

    private Path repoPath() {
        return Paths.get(repoField.getText().trim());
    }

    private String escape(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
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
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new UniversalMonitorControlCenter().setVisible(true);
            }
        });
    }
}
