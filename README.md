-Ray Co. Arduino Desktop System Monitor – Installation Guide -
-Displays real-time PC hardware statistics (CPU, RAM, GPU, disks, network, and processes) on an Arduino touchscreen using a Python monitoring script.-
-Ray Barrett-
-Version 5.3-
-Last Modified: March 15, 2026; 11:13 AM

-Requirements
Python 3.8+ recommended (3.8 required for Windows 7 compatibility)
Arduino IDE 1.8.19+
Arduino UNO R4 (USB C Cable) or Arduino UNO R3 (USB B Cable)
Arduino 3.5inch TFT Display or Arduino 2.8inch TFT Display

-Windows Only
LibreHardwareMonitor

-Changelog-
4.0 - Initial
4.1 - Fixed ReadMe
4.2 - Enlarged Text for OS and Hostname, Fixed on Linux
4.3 - Fixing OS Naming
4.4 - Faster Polling and Improved Logic on Windows
4.5 - ReAdded Uptime
5.0 - Fully Fixed A Bunch, GPU Page fully fixed.
5.1 - Fixed timing tuning for 7 & 10
5.2 - Added SLOW 2.8 TFT & R3 Uno Support
5.3 - Added Faster and Slower Backup Versions; Fixed readme
5.4 - Added Laptop Support (No Charging % Yet)
5.5 - Removed V4 From Readme; Dropped all Windows XP Future Support
6.0 - Stable Release, Linux Packages Included Seperated

-TODO-
Ship with Python, PIP, and the Libraries already installed
Find a way to program Arduino without IDE.
Maybe make it 8-12 Core compatible

-CONFIRMED WORKING SYSTEMS-
Windows 11 Pro
Windows 7 Ultimate
Linux Mint 22.3
Windows 10 LTSC


-Arduino Install-

Install Arduino IDE

Open Boards Manager and install "Arduino UNO R4 Boards".

Go to libraries and find "Arduino TFT Touchscreen" and "DIYables TFT Touch Shield" libraries and install

MAKE SURE THE ARDUINO IS NOW PLUGGED IN

Open "UniversalArduinoMonitor35" in the Arduino IDE if you have a 3.5inch TFT Display
Open "UniversalArduinoMonitor28" in the Arduino IDE if you have a 2.8inch TFT Display

In the Arduino IDE, choose the correct port and press Upload.

-Python Script Install-

Install Python 3.8+ recommended (3.8 required for Windows 7 compatibility)

Run this to install the libraries: pip3 install psutil pyserial (or: python3 -m pip install psutil pyserial)


-WINDOWS INSTRUCTIONS-
Install LibreHardwareMonitor and open it.

Navigate to the options button, press it, then enable Start Minimized, Minimize To Tray, Minimize On Close, and Run On Windows Startup

Navigate to "Remote Web Server" under options, then press Start.

Create folder C:\ArduinoMonitor and place "UniversalArduinoMonitor.py" inside it

Press WIN + R, type "shell:startup", and place "DELAYED_RUN_UniversalArduinoMonitor.bat" there.

-LINUX INSTRUCTIONS-
Open a terminal and create the script file:

	nano ~/UniversalArduinoMonitor.py
	
Paste the contents of UniversalArduinoMonitor.py into this file. 

Save by pressing
CTRL + O
ENTER
CTRL + X

Open a terminal and create the script/service file: 

	sudo nano /etc/systemd/system/arduino-monitor.service
	
Paste this code snippet inside (Replace YOUR_USERNAME with your actual LOWERCASE Linux username.)

-----
[Unit]
Description=Arduino System Monitor
After=network.target

[Service]
ExecStart=/usr/bin/python3 /home/YOUR_USERNAME/UniversalArduinoMonitor.py
Restart=always
User=YOUR_USERNAME

[Install]
WantedBy=multi-user.target
-----

Save by pressing
CTRL + O
ENTER
CTRL + X

Then enable the service with the following commands:

-----
	sudo systemctl daemon-reload
	sudo systemctl enable arduino-monitor
	sudo systemctl start arduino-monitor
----

-Final Run Instructions-
MAKE SURE ARDUINO IS NOW PLUGGED IN

-Windows-
Run "UniversalArduinoMonitor.py" or the provided batch file to start the program.

-Linux-
With the Terminal open, run this code to start the program:

	sudo systemctl start arduino-monitor

To stop the program, run this:

	sudo systemctl stop arduino-monitor

To restart the program and check the status, run these:

	sudo systemctl restart arduino-monitor
	sudo systemctl status arduino-monitor

-END-

The program should now startup with Windows & Linux, and should display and update stats on the plugged in Arduino. Enjoy.

-TROUBLESHOOTING-

Use backup's first
