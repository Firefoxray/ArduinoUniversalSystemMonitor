import com.fazecast.jSerialComm.SerialPort;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared lifecycle controller for the desktop dashboard preview stream.
 * Keeps fake-port process + preview serial connection ownership outside the UI.
 */
public class DashboardStreamController {
    private final Supplier<Path> repoPathSupplier;
    private final Supplier<String> fakeInPathSupplier;
    private final Supplier<String> fakeOutPathSupplier;
    private final Supplier<Boolean> preflightCheck;
    private final Runnable beforeFakePortsStart;
    private final Consumer<String> log;
    private final Consumer<JavaSerialFakeDisplay.ParsedPacket> packetConsumer;
    private final Runnable stateListener;

    private volatile Process fakePortsProcess;
    private volatile SerialPort previewPort;
    private volatile boolean previewReading;
    private Thread previewReaderThread;
    private volatile long lastPayloadReceivedAtMs;

    public DashboardStreamController(
            Supplier<Path> repoPathSupplier,
            Supplier<String> fakeInPathSupplier,
            Supplier<String> fakeOutPathSupplier,
            Supplier<Boolean> preflightCheck,
            Runnable beforeFakePortsStart,
            Consumer<String> log,
            Consumer<JavaSerialFakeDisplay.ParsedPacket> packetConsumer,
            Runnable stateListener
    ) {
        this.repoPathSupplier = repoPathSupplier;
        this.fakeInPathSupplier = fakeInPathSupplier;
        this.fakeOutPathSupplier = fakeOutPathSupplier;
        this.preflightCheck = preflightCheck;
        this.beforeFakePortsStart = beforeFakePortsStart;
        this.log = log;
        this.packetConsumer = packetConsumer;
        this.stateListener = stateListener;
    }

    public boolean isFakePortsRunning() {
        return fakePortsProcess != null && fakePortsProcess.isAlive();
    }

    public boolean isPreviewConnected() {
        return previewPort != null && previewPort.isOpen();
    }

    public boolean isProducerAlive() {
        return isFakePortsRunning();
    }

    public boolean isConsumerAlive() {
        return previewReading && previewReaderThread != null && previewReaderThread.isAlive() && isPreviewConnected();
    }

    public long getLastPayloadReceivedAtMs() {
        return lastPayloadReceivedAtMs;
    }

    public void startDashboardPreviewFlow() {
        ensureDashboardStreamActive();
    }

    public void ensureDashboardStreamActive() {
        if (!isFakePortsRunning()) {
            startFakePorts();
            return;
        }
        if (!isPreviewConnected()) {
            connectPreviewPort();
        }
    }

    public void stopDashboardPreviewFlow() {
        disconnectPreviewPort();
        stopFakePorts();
    }

    public void startFakePorts() {
        if (!preflightCheck.get()) {
            return;
        }
        if (isFakePortsRunning()) {
            return;
        }

        String fakeIn = fakeInPathSupplier.get().trim();
        String fakeOut = fakeOutPathSupplier.get().trim();
        if (fakeIn.isEmpty() || fakeOut.isEmpty()) {
            log.accept("[ERROR] Fake port paths cannot be empty.");
            return;
        }

        beforeFakePortsStart.run();
        String commandText = "socat -d -d pty,raw,echo=0,link=" + escape(fakeIn) + " pty,raw,echo=0,link=" + escape(fakeOut);
        log.accept("[RUN] " + commandText);

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-lc", commandText);
                pb.directory(repoPathSupplier.get().toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                fakePortsProcess = process;
                notifyStateChanged();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    log.accept("[socat] " + line);
                }
            } catch (Exception ex) {
                log.accept("[ERROR] Failed to start fake ports: " + ex.getMessage());
            } finally {
                fakePortsProcess = null;
                notifyStateChanged();
            }
        }, "dashboard-fake-port-thread");
        t.setDaemon(true);
        t.start();

        Timer delayedConnect = new Timer(900, e -> connectPreviewPort());
        delayedConnect.setRepeats(false);
        delayedConnect.start();

        Timer delayedProbe = new Timer(1700, e -> sendPreviewProbePacket(fakeIn));
        delayedProbe.setRepeats(false);
        delayedProbe.start();
    }

    public void stopFakePorts() {
        Process process = fakePortsProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            log.accept("[INFO] Sent stop signal to fake ports process.");
        }
        notifyStateChanged();
    }

    public void connectPreviewPort() {
        if (isPreviewConnected()) {
            return;
        }

        String portPath = fakeOutPathSupplier.get().trim();
        if (portPath.isEmpty()) {
            log.accept("[ERROR] Output port path is empty.");
            return;
        }

        SerialPort port = SerialPort.getCommPort(portPath);
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 0);

        if (!port.openPort()) {
            log.accept("[ERROR] Failed to open preview port: " + portPath);
            return;
        }

        previewPort = port;
        previewReading = true;
        notifyStateChanged();
        log.accept("[INFO] Connected preview stream to " + portPath);

        previewReaderThread = new Thread(() -> readPreviewLoop(port), "dashboard-preview-reader-thread");
        previewReaderThread.setDaemon(true);
        previewReaderThread.start();
    }

    public void disconnectPreviewPort() {
        previewReading = false;
        SerialPort port = previewPort;
        previewPort = null;

        if (port != null && port.isOpen()) {
            port.closePort();
            log.accept("[INFO] Preview stream disconnected.");
        }
        notifyStateChanged();
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
                    log.accept("[ERROR] Preview read error: " + ex.getMessage());
                    break;
                }

                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                lastPayloadReceivedAtMs = System.currentTimeMillis();
                packetConsumer.accept(JavaSerialFakeDisplay.ParsedPacket.parse(line.trim()));
            }
        } catch (Exception ex) {
            log.accept("[ERROR] Preview stream failure: " + ex.getMessage());
        }

        SwingUtilities.invokeLater(this::disconnectPreviewPort);
    }

    private void sendPreviewProbePacket(String fakeInPath) {
        try {
            SerialPort probePort = SerialPort.getCommPort(fakeInPath);
            probePort.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            probePort.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 1000);

            if (!probePort.openPort()) {
                log.accept("[WARN] Probe packet skipped: could not open fake input port " + fakeInPath);
                return;
            }

            try {
                String probe = "HEADER:Preview Test|CPU:12|RAM:34|DISK0:56|DISK1:12|GPU:8|HOST:debug|OS:linux|IP:127.0.0.1|UPTIME:1m\n";
                probePort.writeBytes(probe.getBytes(StandardCharsets.UTF_8), probe.length());
                log.accept("[INFO] Sent preview probe packet to " + fakeInPath + " (confirms fake port wiring).\n"
                        + "      Python should write to input; preview reads output.");
            } finally {
                probePort.closePort();
            }
        } catch (Exception ex) {
            log.accept("[WARN] Failed to send preview probe packet: " + ex.getMessage());
        }
    }

    private void notifyStateChanged() {
        if (stateListener != null) {
            stateListener.run();
        }
    }

    private String escape(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
