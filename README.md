# Ray Co. Linux / Windows Arduino Desktop System Monitor

Displays real-time PC hardware statistics (CPU, RAM, GPU, disks, network, and processes) on an Arduino touchscreen using a Python monitoring script.

**Author:** Ray Barrett  
**Version:** 7.2  
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

- This project is **developed and tested on Fedora (KDE 43)**.
- It is now designed to work across **most Linux distributions**.
- GPU detection supports:
  - NVIDIA (`nvidia-smi`)
  - AMD (DRM/sysfs)
  - Intel (DRM/sysfs / intel tools)
- Behavior may vary depending on drivers and system configuration.

---

## Requirements

- Python 3.8+
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
7.2  - Made Linux universal, GPU detection for NVIDIA/AMD/Intel, reduced Fedora-specific dependencies
```

---

## TODO

- Bundle Python, pip, and dependencies
- Arduino programming without IDE

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

```bash
python3 -m pip install psutil pyserial
```

---

## Linux Setup (Automatic)

```bash
git clone https://github.com/Firefoxray/ArduinoUniversalSystemMonitor.git
cd ArduinoUniversalSystemMonitor
chmod +x install.sh
./install.sh
```

---

## Linux Setup (Manual)

### Create Service

```bash
sudo nano /etc/systemd/system/arduino-monitor.service
```

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

### Enable

```bash
sudo systemctl daemon-reload
sudo systemctl enable arduino-monitor
sudo systemctl start arduino-monitor
```

---

### Serial Permissions (IMPORTANT)

```bash
sudo usermod -aG dialout $USER
```

Log out and back in.

---

# Running the Program

## Linux

```bash
sudo systemctl start arduino-monitor
sudo systemctl stop arduino-monitor
sudo systemctl restart arduino-monitor
sudo systemctl status arduino-monitor
```

---

# Troubleshooting

### Check Arduino Port

```bash
ls /dev/ttyACM* /dev/ttyUSB*
```

---

### Detect Plug-In

```bash
dmesg -w
```

---

### Verify Permissions

```bash
ls -l /dev/ttyACM0
```

---

### Check Service

```bash
sudo systemctl status arduino-monitor
```

## Debug Tools

This repository also includes a Java-based Fake Arduino Display tool for testing the serial output without requiring a physical Arduino.

Location:
`debug_tools/FakeArduinoDisplay`

Use it with a virtual serial pair such as:
`/tmp/fakearduino_in` and `/tmp/fakearduino_out`
