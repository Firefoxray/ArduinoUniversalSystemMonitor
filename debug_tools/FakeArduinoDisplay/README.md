# Fake Arduino Display (Java Debug Tool)

A desktop Java application that emulates the Arduino TFT display used by
the Universal Arduino System Monitor.

This tool allows you to test and visualize serial output from the Python
monitor **without requiring a physical Arduino**.

------------------------------------------------------------------------

## Features

-   Live serial parsing of monitor data
-   Multi-page display (Home, CPU, Processes, Network, GPU, Storage,
    Graph)
-   Click-to-switch pages (matches Arduino touchscreen behavior)
-   Raw serial log viewer
-   Parsed field breakdown
-   Designed to mirror the real Arduino UI layout

------------------------------------------------------------------------

## How It Works

This program connects to a serial port and reads formatted packets like:

    CPU:24|RAM:51|DISK0:72|DISK1:44|...

It then renders a visual representation of the Arduino display.

------------------------------------------------------------------------

## Usage (Recommended with Virtual Serial)

### 1. Create virtual serial ports

``` bash
socat -d -d \
pty,raw,echo=0,link=/tmp/fakearduino_in \
pty,raw,echo=0,link=/tmp/fakearduino_out
```

------------------------------------------------------------------------

### 2. Configure Python monitor

Enable debug mode in `monitor_config.json`:

``` json
{
  "debug_enabled": true,
  "debug_port": "/tmp/fakearduino_in"
}
```

------------------------------------------------------------------------

### 3. Run the Java display

-   Open project in IntelliJ
-   Run `JavaSerialFakeDisplay`

------------------------------------------------------------------------

### 4. Select port

In the app, choose:

    /tmp/fakearduino_out

------------------------------------------------------------------------

## Controls

-   Click display area → switch pages
-   Refresh Ports → reload available serial ports
-   Raw Log → shows incoming serial data
-   Parsed Fields → shows extracted values

------------------------------------------------------------------------

## Project Structure

    src/main/java/
        JavaSerialFakeDisplay.java

------------------------------------------------------------------------

## Notes

-   Designed for Linux (uses `/tmp/` virtual ports)
-   Works alongside real Arduino (mirror mode)
-   Intended as a debugging and development tool

------------------------------------------------------------------------

## Future Improvements

-   Replay recorded sessions
-   Theme customization
-   Packet validation and error highlighting
-   Cross-platform serial support improvements

------------------------------------------------------------------------

## License

Part of the Universal Arduino System Monitor project.
