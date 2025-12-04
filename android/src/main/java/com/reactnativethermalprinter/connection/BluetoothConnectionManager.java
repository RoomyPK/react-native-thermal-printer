package com.reactnativethermalprinter.connection;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

public class BluetoothConnectionManager {

  private static final String TAG = "RNTP.BTConnectionManager";

  private final String macAddress;
  private final Activity activity;

  public BluetoothConnectionManager(Activity activity, String macAddress) {
    this.activity = activity;
    this.macAddress = macAddress;
  }

  // Permission Checking for Android 12+
  public boolean hasBluetoothPermissions() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      // Pre-Android 12: BLUETOOTH + ADMIN are enough
      return true;
    }

    boolean scan = ActivityCompat.checkSelfPermission(this.activity,
        Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;

    boolean connect = ActivityCompat.checkSelfPermission(this.activity,
        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;

    return scan && connect;
  }

  public boolean requestBluetoothPermissions(/* int requestCode */) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true;
    }

    ActivityCompat.requestPermissions(
        this.activity,
        new String[] {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        },
        /* requestCode */1);

    return false;
  }

}
