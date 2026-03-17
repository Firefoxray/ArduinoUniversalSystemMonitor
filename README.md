## Quick Start (Linux)

```bash
git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git
cd ArduinoUniversalSystemMonitor
chmod +x install.sh
./install.sh
```

# Ray Co. Arduino Desktop System Monitor

Displays real-time PC hardware statistics (CPU, RAM, GPU, disks, network, and processes) on an Arduino touchscreen using a Python monitoring script.

**Author:** Ray Barrett  
**Version:** 7.1  
**Last Modified:** March 17, 2026  

---

## Preview

### Main View
<p align="center">
  <img src="screenshots/home1.JPEG" width="500"/>
</p>

### Other Pages
<p align="center">
  <img src="screenshots/cpu1.JPEG" width="160"/>
  <img src="screenshots/gpu1.JPEG" width="160"/>
  <img src="screenshots/network1.JPEG" width="160"/>
  <img src="screenshots/storage1.JPEG" width="160"/>
  <img src="screenshots/processes1.JPEG" width="160"/>
  <img src="screenshots/graph1.JPEG" width="160"/>
</p>

---

## Important Notes

- The main `UniversalArduinoMonitor.py` is optimized and tested for **Fedora (KDE 43)**.
- It may also work on other Linux distributions, but behavior can vary.
- Alternative versions for Windows and other Linux systems are available in:
  
```text
alternate_versions/
```

---

## Requirements

- Python 3.8+ (3.8 required for Windows 7 compatibility)
- Arduino IDE 1.8.19+
- Arduino UNO R4 (USB-C) or UNO R3 (USB-B)
- Arduino 3.5" TFT Display

### Windows Only
- LibreHardwareMonitor

---

## Changelog

```text
4.0  - Initial
4.1  - Fixed README
4.2  - Enlarged text for OS and hostname, fixed Linux issues
4.3  - Fixed OS naming
4.4  - Faster polling and improved Windows logic
4.5  - Re-added uptime
5.0  - Major fixes, GPU page fully fixed
5.1  - Timing tuning for Windows 7 & 10
5.2  - Added R3 support
5.3  - Backup versions + README fixes
5.4  - Laptop support (no charging % yet)
5.5  - Dropped Windows XP support
6.0  - Stable release, Linux packages separated
7.0  - Added Fedora KDE 43 support
7.1  - Reorganized folder structure, separated Windows components
```

---

## TODO

- Bundle Python, pip, and dependencies
- Arduino programming without IDE
- Improve multi-core CPU support (8–12 cores)

---

## Confirmed Working Systems

- Windows 11 Pro  
- Windows 10 LTSC  
- Windows 7 Ultimate  
- Linux Mint 22.3  
- Fedora KDE 43  

---

# Installation

## Arduino Setup

1. Install Arduino IDE  
2. Open **Boards Manager** and install:
   - Arduino UNO R4 Boards  
3. Install libraries:
   - Arduino TFT Touchscreen  
   - DIYables TFT Touch Shield  

**IMPORTANT:** Plug in the Arduino before continuing.

4. Open:
   - `UniversalArduinoMonitor35` for the 3.5" display  

5. Select the correct board and port, then click **Upload**

---

## Python Setup

Install Python 3.8+

Install required libraries:

```bash
python3 -m pip install psutil pyserial
```

---

## Windows Setup

1. Install LibreHardwareMonitor and open it  
2. Enable:
   - Start Minimized  
   - Minimize to Tray  
   - Run on Startup  
3. Enable **Remote Web Server**

4. Create the folder:

```text
C:\ArduinoMonitor
```

5. Place:

```text
UniversalArduinoMonitor.py
```

6. Press `WIN + R`, type:

```text
shell:startup
```

7. Place:

```text
DELAYED_RUN_UniversalArduinoMonitor.bat
```

---

## Linux Setup (Automatic)

```bash
chmod +x install.sh
./install.sh
```

This will:
- Install dependencies  
- Configure serial permissions  
- Set up auto-start service  

---

## Linux Setup (Manual)

### Step 1 — Create Script

```bash
nano ~/UniversalArduinoMonitor.py
```

Save with:

```
CTRL + O → ENTER → CTRL + X
```

---

### Step 2 — Create Systemd Service

```bash
sudo nano /etc/systemd/system/arduino-monitor.service
```

Paste:

```ini
[Unit]
Description=Arduino System Monitor
After=network.target

[Service]
ExecStart=/usr/bin/python3 /home/YOUR_USERNAME/UniversalArduinoMonitor.py
Restart=always
User=YOUR_USERNAME

[Install]
WantedBy=multi-user.target
```

---

### Step 3 — Enable Service

```bash
sudo systemctl daemon-reload
sudo systemctl enable arduino-monitor
sudo systemctl start arduino-monitor
```

---

### Step 4 — Serial Permissions Fix (IMPORTANT)

```bash
sudo usermod -aG dialout $USER
```

Log out and back in.

---

# Running the Program

**Make sure the Arduino is plugged in**

## Windows
Run:
```
UniversalArduinoMonitor.py
```

---

## Linux

```bash
sudo systemctl start arduino-monitor
sudo systemctl stop arduino-monitor
sudo systemctl restart arduino-monitor
sudo systemctl status arduino-monitor
```

---

# Troubleshooting

### Verify Python and Dependencies

```bash
python3 --version
python3 -m pip install psutil pyserial
python3 -c "import psutil, serial; print('OK')"
```

---

### Check Arduino Port

```bash
ls /dev/ttyACM* /dev/ttyUSB*
```

---

### Detect Arduino on Plug-In

```bash
dmesg -w
```

---

### Verify Permissions

```bash
ls -l /dev/ttyACM0
```

Ensure your user is in `dialout` or `uucp`.

---

### Check Service Status

```bash
sudo systemctl status arduino-monitor
```
