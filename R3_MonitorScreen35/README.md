# R3_MonitorScreen35

Memory-conscious Arduino Uno R3 sketch for the DIYables 3.5" touchscreen shield.

## Notes
- Uses `DIYables_TFT_Touch_Shield.h` and the same touch calibration values as the working 3.5" builds.
- Follows the lightweight parsing strategy from the `R3_MonitorScreen28` sketch: no `String`, small fixed buffers, and streaming field parsing.
- Keeps the Uno R3 footprint down by limiting the UI to 5 pages, 4 CPU cores, 4 process rows, 3 storage lines, and a 20-point graph history.
