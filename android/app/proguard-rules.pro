# usb-serial-for-android instantiates drivers and calls getSupportedDevices()/probe() via reflection.
-keep class com.hoho.android.usbserial.driver.** { *; }
