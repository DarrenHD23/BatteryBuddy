package com.example.fiskertemp

// ---------------------------------------------------------------------------
// Android app: read battery values from the Fisker Ocean BMS through a
// vLinker FD+ (ELM327/STN-compatible, dual-mode Bluetooth 3.0 + BLE 4.0).
//
// The FD+ advertises two ways:
//   "vLinker FD-Android"  -> Bluetooth Classic (SPP)   <- tried first
//   "vLinker FD-iOS"      -> Bluetooth LE (GATT)       <- used as fallback / when selected
// Pair "vLinker FD-Android" in Android's Bluetooth settings (PIN 1234 or 0000 if asked).
// If it doesn't show up, use "Scan BLE" in the app to find the BLE one.
//
// AndroidManifest.xml needs:
//   <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
//   <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
//                    android:usesPermissionFlags="neverForLocation"/>
//   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30"/>
//
// The BMS address (ATSH7E1 / ATCRA7E9) and the DIDs in PRESETS come from the
// Fisker-Ocean-Apps community repo and have not been verified by me against a
// car. Check each reading against the car's own display before trusting it.
// This app only sends read requests (service 01 / 22).
//
// NOTE: I could not test this with a physical FD+, so the transport code
// (especially the BLE fallback) is untested on real hardware.
