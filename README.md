# Ray Co. Arduino Desktop System Monitor

Displays real-time PC hardware statistics (CPU, RAM, GPU, disks, network, and processes) on an Arduino touchscreen using a Python monitoring script.

**Author:** Ray Barrett  
**Version:** 5.3  
**Last Modified:** March 15, 2026  

---

## Requirements

- Python 3.8+ (3.8 required for Windows 7 compatibility)
- Arduino IDE 1.8.19+
- Arduino UNO R4 (USB-C) or UNO R3 (USB-B)
- Arduino 3.5" TFT Display or 2.8" TFT Display

### Windows Only
- LibreHardwareMonitor

---

## Changelog

```
4.0  - Initial
4.1  - Fixed ReadMe
4.2  - Enlarged Text for OS and Hostname, Fixed on Linux
4.3  - Fixing OS Naming
4.4  - Faster Polling and Improved Logic on Windows
4.5  - Re-added Uptime
5.0  - Major fixes, GPU page fully fixed
5.1  - Timing tuning for Windows 7 & 10
5.2  - Added 2.8 TFT & R3 support
5.3  - Backup versions + ReadMe fixes
5.4  - Laptop support (no charging % yet)
5.5  - Dropped Windows XP support
6.0  - Stable release, Linux packages separated
```

---

## TODO

- Bundle Python + pip + libraries
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

---

## Arduino Setup

1. Install Arduino IDE  
2. Open **Boards Manager** and install:
   - Arduino UNO R4 Boards  
3. Install libraries:
   - Arduino TFT Touchscreen  
   - DIYables TFT Touch Shield  

**IMPORTANT:** Plug in Arduino before continuing

4. Open:
   - `UniversalArduinoMonitor35` (3.5" display)  
   - `UniversalArduinoMonitor28` (2.8" display)  

5. Select correct port and click **Upload**

---

## Python Setup

Install Python 3.8+

Install required libraries:

```bash
pip3 install psutil pyserial
```

or

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

4. Create folder:
```
C:\ArduinoMonitor
```

5. Place:
```
UniversalArduinoMonitor.py
```

6. Press `WIN + R`, type:
```
shell:startup
```

7. Place:
```
DELAYED_RUN_UniversalArduinoMonitor.bat
```

---

## Linux Setup

### Step 1 — Create Script

```bash
nano ~/UniversalArduinoMonitor.py
```

Paste the script, then save:

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

Replace `YOUR_USERNAME` with your Linux username.

---

### Step 3 — Enable Service

```bash
sudo systemctl daemon-reload
sudo systemctl enable arduino-monitor
sudo systemctl start arduino-monitor
```

---

# Running the Program

**IMPORTANT:** Make sure Arduino is plugged in

## Windows

Run:
```
UniversalArduinoMonitor.py
```

or use the batch file.

---

## Linux

Start:
```bash
sudo systemctl start arduino-monitor
```

Stop:
```bash
sudo systemctl stop arduino-monitor
```

Restart:
```bash
sudo systemctl restart arduino-monitor
```

Check status:
```bash
sudo systemctl status arduino-monitor
```

---

# Done

The system should now:
- Start automatically  
- Send stats to Arduino  
- Display live system data  

---

# Troubleshooting

- Use backup versions first  
- Check Arduino port  
- Ensure Python dependencies are installed  
- Verify service is running:

```bash
sudo systemctl status arduino-monitor
```
