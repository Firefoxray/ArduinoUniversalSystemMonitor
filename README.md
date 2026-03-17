# Ray Co. Arduino Desktop System Monitor

Displays real-time PC hardware statistics (CPU, RAM, GPU, disks, network, and processes) on an Arduino touchscreen using a Python monitoring script.

**Author:** Ray Barrett  
**Version:** 7.0  
**Last Modified:** March 17, 2026, 1:52 AM  

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

```text
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
7.0  - Added Fedora KDE 43 support separately
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

## Arduino Setup

1. Install Arduino IDE.
2. Open **Boards Manager** and install:
   - Arduino UNO R4 Boards
3. Install libraries:
   - Arduino TFT Touchscreen
   - DIYables TFT Touch Shield

**IMPORTANT:** Plug in the Arduino before continuing.

4. Open:
   - `UniversalArduinoMonitor35` for the 3.5" display
   - `UniversalArduinoMonitor28` for the 2.8" display

5. Select the correct board and port, then click **Upload**.

---

## Python Setup

Install Python 3.8+.

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

1. Install LibreHardwareMonitor and open it.
2. Enable:
   - Start Minimized
   - Minimize to Tray
   - Run on Startup
3. Enable **Remote Web Server**.
4. Create the folder:

```text
C:\ArduinoMonitor
```

5. Place this file inside:

```text
UniversalArduinoMonitor.py
```

6. Press `WIN + R`, type:

```text
shell:startup
```

7. Place this file there:

```text
DELAYED_RUN_UniversalArduinoMonitor.bat
```

---

## Linux Setup (Automatic)

Run:

```bash
chmod +x install.sh
./install.sh
```

This will:
- Install dependencies
- Configure serial permissions
- Set up the auto-start service

---

## Linux Setup (Manual)

### Step 1 — Create Script

```bash
nano ~/UniversalArduinoMonitor.py
```

Paste the script, then save with:

```text
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

### Step 4 — Serial Permissions Fix (IMPORTANT)

If the Arduino is not detected or you get permission errors, run:

```bash
sudo usermod -aG dialout $USER
```

Then log out and log back in, or reboot.

This allows your user to access serial devices like `/dev/ttyACM0`.

---

# Running the Program

**IMPORTANT:** Make sure the Arduino is plugged in.

## Windows

Run:

```text
UniversalArduinoMonitor.py
```

or use the provided batch file.

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
- Send stats to the Arduino
- Display live system data

---

# Troubleshooting

- Use alternate versions first

### Verify Python and Dependencies

Make sure Python is installed:

```bash
python3 --version
```

If not installed, install it:

**Fedora:**
```bash
sudo dnf install python3 python3-pip
```

**Debian/Ubuntu:**
```bash
sudo apt update
sudo apt install python3 python3-pip
```

**Arch:**
```bash
sudo pacman -Sy python python-pip
```

---

Install required Python libraries:

```bash
python3 -m pip install psutil pyserial
```

---

Verify they are installed correctly:

```bash
python3 -c "import psutil, serial; print('OK')"
```

If this prints `OK`, everything is working.
- 
- Verify the service is running:

```bash
sudo systemctl status arduino-monitor
```

### Check Arduino Port (Linux)

To see which port your Arduino is using, run:

```bash
ls /dev/ttyACM* /dev/ttyUSB*
```

Typical outputs:
- `/dev/ttyACM0` → Most Arduino boards (UNO R4, etc.)
- `/dev/ttyUSB0` → Some older boards or clones

---

### Detect Arduino When Plugging In

You can also watch the device appear in real time:

```bash
dmesg -w
```

Then plug in the Arduino and look for lines like:

```
ttyACM0: USB ACM device
```

---

### Verify Permissions

Check if you have access:

```bash
ls -l /dev/ttyACM0
```

If it shows `dialout` or `uucp`, make sure your user is in that group.
