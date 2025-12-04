package com.reactnativethermalprinter.connection;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public class FastBluetoothConnection extends FastDeviceConnection {

  private static final String TAG = "RNTP.FastBluetoothConn";

  // Standard SPP UUID
  private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

  // Prevent the printer from being overloaded by huge packet sizes (esp.
  // Bluetooth)
  private static final int MAX_SAFE_BT_PACKET = 1024;

  // BT timing constants
  // Prevent GS-v0 fragmentation
  // Cheap ESC/POS modules cannot handle back-to-back packets without pacing
  private static final int PRE_WRITE_DELAY_MS = 1; // before each packet
  private static final int POST_WRITE_DELAY_MS = 1; // after flush
  private static final int POST_FINISH_DRAIN_MS = 60; // before closing socket

  private final String macAddress;
  private BluetoothSocket socket;
  private OutputStream out;

  public FastBluetoothConnection(
      String macAddress,
      int packetSize,
      boolean useQueue,
      int microDelayMs) {

    super(Math.max(1, Math.min(packetSize, MAX_SAFE_BT_PACKET)), useQueue, microDelayMs);

    if (this.packetSize != packetSize) {
      Log.w(TAG, "Packet size " + this.packetSize + " too large for BT; clamped to " + MAX_SAFE_BT_PACKET);
    }

    this.macAddress = macAddress;
  }

  public void connect() throws Exception {
    final long start = System.nanoTime();

    if (this.macAddress == null || this.macAddress.isEmpty()) {
      throw new Exception("MAC address not specified");
    }

    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) {
      throw new Exception("Bluetooth not available");
    }

    if (!adapter.isEnabled()) {
      throw new Exception("Bluetooth is OFF");
    }

    BluetoothDevice device = adapter.getRemoteDevice(this.macAddress);
    if (device == null) {
      throw new Exception("Bluetooth device not found: " + this.macAddress);
    }

    if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
      throw new Exception("Bluetooth device not paired");
    }

    // Clean stale sessions
    if (isConnected()) {
      Log.i(TAG, "Already connected - closing state connection first.");
      close();
    }

    Log.i(TAG, "Connecting to Bluetooth device: " + this.macAddress);

    try {

      this.socket = device.createRfcommSocketToServiceRecord(SPP_UUID);

      this.socket.connect();

      this.out = new BufferedOutputStream(this.socket.getOutputStream());

      Log.i(TAG, "Connected to Bluetooth device: " + this.macAddress);

    } catch (IOException e) {

      safeCloseSocket();

      throw new Exception("Failed to connect to " + this.macAddress + ": " + e.getMessage(), e);

    } finally {

      Log.i(TAG, "connect took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
    }
  }

  // WRITE PACKET (called from parent class)
  @Override
  protected void writeToDevice(byte[] buffer, int offset, int length) throws IOException {
    if (this.socket == null || this.out == null) {
      throw new IOException("Bluetooth socket not connected");
    }

    try {
      // Bluetooth SPP is extremely sensitive to pacing
      if (PRE_WRITE_DELAY_MS > 0) {
        try {
          Thread.sleep(PRE_WRITE_DELAY_MS);
        } catch (InterruptedException ignored) {
        }
      }

      this.out.write(buffer, offset, length);
      this.out.flush(); // REQUIRED for cheap BT printers

      if (POST_WRITE_DELAY_MS > 0) {
        try {
          Thread.sleep(POST_WRITE_DELAY_MS);
        } catch (InterruptedException ignored) {
        }
      }
    } catch (IOException ex) {
      Log.e(TAG, "BT write failed: " + ex.getMessage(), ex);
      safeCloseSocket();
      throw ex;
    }
  }

  // CLOSE BLUETOOTH SOCKET SAFELY
  @Override
  protected void closeDevice() throws IOException {
    safeCloseSocket();
  }

  private void safeCloseSocket() {
    // SPP transmits asynchronously, closing the socket too early leaves the last
    // bytes unsent. Final drain guarantees last chunk hits printer before shutdown.
    if (POST_FINISH_DRAIN_MS > 0) {
      try {
        Thread.sleep(POST_FINISH_DRAIN_MS);
      } catch (InterruptedException ignored) {
      }
    }

    try {
      if (this.out != null) {
        this.out.flush();
      }
    } catch (Exception ignored) {
    }

    try {
      if (this.out != null) {
        this.out.close();
      }
    } catch (Exception ignored) {
    }

    try {
      if (this.socket != null) {
        this.socket.close();
      }
    } catch (Exception ignored) {
    }

    this.out = null;
    this.socket = null;

    Log.i(TAG, "Bluetooth connection closed");
  }

  // OPTIONAL MANUAL RECONNECT
  public boolean reconnect() {
    try {
      safeCloseSocket();
      connect();
      return true;
    } catch (Exception e) {
      Log.e(TAG, "Reconnect failed: " + e.getMessage(), e);
      return false;
    }
  }

  // CONNECTION STATE
  public boolean isConnected() {
    return this.socket != null && this.socket.isConnected();
  }

  // ----------------------------------------------------------
  // BLUETOOTH-SPECIFIC PACING (OPTIONAL EXTRA DELAY)
  // ----------------------------------------------------------
  // Some cheap printers (ZJ, POS-80 clones) require slightly
  // longer pacing after certain operations.
  //
  // FastDeviceConnection microDelay() handles per-packet
  // pacing. This method is for adding extended delays if
  // needed for debugging or compatibility.
  // ----------------------------------------------------------
  public void longPace(int ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ignored) {
    }
  }

}
