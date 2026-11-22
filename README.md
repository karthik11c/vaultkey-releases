# Atomberg Remote

Android app that controls an Atomberg BLDC ceiling fan through the phone's built-in IR blaster
(Xiaomi/Redmi/POCO, some Huawei/Honor, and other phones with an IR emitter).

## Build
Open the folder in Android Studio and run it, or from a terminal:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Buttons
Power (toggle), Speed 1–5, Boost, Sleep, Timer 1h/2h/3h/6h.

## Protocol
NEC at 38 kHz, extended address `0x00 0xF3` (ESPHome `address: 0xF300`).
Codes live in `AtombergIr.kt`. To add a button (such as the LED light on Renesa models), capture
its NEC command with an IR receiver and add one enum entry plus one button.
