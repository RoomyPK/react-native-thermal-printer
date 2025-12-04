package com.reactnativethermalprinter.connection;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

public class FastTcpConnection extends FastDeviceConnection {

  private static final String TAG = "RNTP.FastTcpConn";

  // MTU-safe packet size for TCP printers
  private static final int MAX_SAFE_TCP_PACKET = 1460;

  private final String host;
  private final int port;
  private final int timeoutMs;

  private Socket socket;
  private OutputStream out;

  // Final pacing values (TCP needs far less than Bluetooth)
  private static final int PRE_WRITE_DELAY_MS = 0; // TCP is fast, avoid BT-like delays
  private static final int POST_WRITE_DELAY_MS = 0;
  private static final int POST_FINISH_DRAIN_MS = 10; // allow NIC -> printer module drain

  public FastTcpConnection(
      String host,
      int port,
      int timeoutMs,
      int packetSize,
      boolean useQueue,
      int microDelayMs) {

    super(Math.max(1, Math.min(packetSize, MAX_SAFE_TCP_PACKET)), useQueue, microDelayMs);

    if (this.packetSize != packetSize) {
      Log.w(TAG, "Packet size " + this.packetSize + " too large for TCP MTU; clamped to " + MAX_SAFE_TCP_PACKET);
    }

    this.host = host;
    this.port = port;
    this.timeoutMs = timeoutMs;
  }

  // CONNECT
  public void connect() throws IOException {
    final long start = System.nanoTime();

    this.socket = new Socket();

    try {

      this.socket.connect(new InetSocketAddress(this.host, this.port), this.timeoutMs);
      this.socket.setTcpNoDelay(true); // disable Nagle; absolutely required for fast print
      this.socket.setKeepAlive(true);

      this.out = new BufferedOutputStream(this.socket.getOutputStream());

      Log.i(TAG, "Connected to TCP printer: " + this.host + ":" + this.port);

    } catch (IOException e) {

      safeCloseSocket();

      throw new IOException("Failed to connect to " + this.host + ":" + this.port + " - " + e.getMessage(), e);

    } finally {

      Log.i(TAG, "connect took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
    }
  }

  // WRITE PACKET (called by FastDeviceConnection)
  @Override
  protected void writeToDevice(byte[] buffer, int offset, int length) throws IOException {
    if (this.socket == null || this.out == null) {
      throw new IOException("TCP socket not connected");
    }

    try {
      // TCP generally doesn't need pacing, but some serial-bridge modules do
      if (PRE_WRITE_DELAY_MS > 0) {
        try {
          Thread.sleep(PRE_WRITE_DELAY_MS);
        } catch (InterruptedException ignored) {
        }
      }

      this.out.write(buffer, offset, length);
      this.out.flush(); // LAN printers flush safely and quickly

      if (POST_WRITE_DELAY_MS > 0) {
        try {
          Thread.sleep(POST_WRITE_DELAY_MS);
        } catch (InterruptedException ignored) {
        }
      }

    } catch (IOException ex) {
      Log.e(TAG, "TCP write failed: " + ex.getMessage(), ex);
      safeCloseSocket();
      throw ex;
    }
  }

  // CLOSE DEVICE
  @Override
  protected void closeDevice() throws IOException {
    safeCloseSocket();
  }

  private void safeCloseSocket() {

    final long start = System.nanoTime();

    Log.i(TAG, "Closing TCP connection");

    try {
      // SPP transmits asynchronously, closing the socket too early leaves the last
      // bytes unsent. Note: this.conn.flush() only flushes the Java's OutputStream
      // buffer, not the RFComm buffer.

      // --------------------------------------------------------
      // STEP 1 — Write ASCII space padding to force
      // Android -> RF chipset -> printer flush.
      // --------------------------------------------------------
      // Using ASCII 0x20 ensures:
      // - Printer firmware must process it -> forces flush
      // - Does NOT print anything after cut/reset
      // - Works on ALL cheap clone printers

      byte[] padding = new byte[2048];
      Arrays.fill(padding, (byte) 0x20);

      try {
        if (this.out != null) {
          this.out.write(padding);
          this.out.flush(); // First flush: ensure padding exits Java buffers
        }
      } catch (Exception ignored) {
        // Even if padding write fails, continue closing gracefully
      }

      // --------------------------------------------------------
      // STEP 2 — Allow RFCOMM + printer UART to drain fully.
      // --------------------------------------------------------
      if (POST_FINISH_DRAIN_MS > 0) {
        try {
          Thread.sleep(POST_FINISH_DRAIN_MS);
        } catch (InterruptedException ignored) {
        }
      }

      // --------------------------------------------------------
      // STEP 3 — Final flush to ensure NO padding remains
      // inside BufferedOutputStream.
      // --------------------------------------------------------
      try {
        if (this.out != null) {
          this.out.flush();
        }
      } catch (Exception ignored) {
      }

      // --------------------------------------------------------
      // STEP 4 — Short delay after final flush.
      // --------------------------------------------------------
      try {
        Thread.sleep(40);
      } catch (InterruptedException ignored) {
      }

      // --------------------------------------------------------
      // STEP 5 — Close OutputStream & Socket safely.
      // --------------------------------------------------------
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

    } finally {

      this.out = null;
      this.socket = null;

      Log.i(TAG,
          "TCP connection closed; safeCloseSocket took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
    }
  }

  // CONNECTION STATE
  public boolean isConnected() {
    return this.socket != null && this.socket.isConnected();
  }
}
