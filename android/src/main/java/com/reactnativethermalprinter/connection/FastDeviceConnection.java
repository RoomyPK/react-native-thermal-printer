package com.reactnativethermalprinter.connection;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class FastDeviceConnection {

  private static final String TAG = "RNTP.FastDeviceConnection";

  protected final ConcurrentLinkedQueue<WriteChunk> queue = new ConcurrentLinkedQueue<>();

  protected ExecutorService executor;
  protected boolean useQueue = true;

  protected int packetSize = 1024;
  protected int microDelayMs = 2;

  private volatile boolean closed = false;
  private volatile boolean writerRunning = false;

  public FastDeviceConnection(int packetSize, boolean useQueue, int microDelayMs) {
    this.packetSize = packetSize;
    this.useQueue = useQueue;
    this.microDelayMs = microDelayMs;
    this.executor = Executors.newSingleThreadExecutor();
  }

  // ----------------------------------------------------------
  // Inner class representing a chunk to be written
  // ----------------------------------------------------------
  protected static class WriteChunk {
    final byte[] data;
    final int offset;
    final int length;

    WriteChunk(byte[] data, int offset, int length) {
      this.data = data;
      this.offset = offset;
      this.length = length;
    }
  }

  public abstract void connect() throws Exception;

  protected abstract void writeToDevice(byte[] buffer, int offset, int length) throws IOException;

  protected abstract void closeDevice() throws IOException;

  // PUBLIC WRITE API
  public void write(byte[] data) throws IOException {
    if (data == null || data.length == 0) {
      return;
    }

    write(data, 0, data.length);
  }

  public void write(byte[] data, int offset, int length) throws IOException {
    if (this.closed) {
      throw new IOException("Connection closed");
    }

    if (!this.useQueue) {
      // DIRECT WRITE MODE (synchronous)
      writeDirect(data, offset, length);
      return;
    }

    // QUEUE MODE
    this.queue.add(new WriteChunk(data, offset, length));
    startWriterIfNeeded();

    // Backpressure warning
    if (this.queue.size() > 50) {
      Log.w(TAG, "⚠️ Print queue growing large: " + this.queue.size() + " items");
    }
  }

  // Start writer thread if not running
  private synchronized void startWriterIfNeeded() {
    if (this.writerRunning || this.closed) {
      return;
    }

    this.writerRunning = true;

    this.executor.execute(() -> {
      try {
        processQueue();
      } catch (Exception e) {
        Log.e(TAG, "Writer thread crashed: " + e.getMessage(), e);
      } finally {
        this.writerRunning = false;
      }
    });
  }

  // DIRECT WRITE MODE (no queue)
  private void writeDirect(byte[] data, int offset, int length) throws IOException {
    if (this.packetSize <= 0 || length <= this.packetSize) {
      // Small enough: write once
      writeToDevice(data, offset, length);
      microDelay();
      return;
    }

    // Split into packets
    int pos = offset;
    int end = offset + length;

    while (pos < end && !this.closed) {
      int chunkLen = Math.min(this.packetSize, end - pos);
      writeToDevice(data, pos, chunkLen);
      microDelay();
      pos += chunkLen;
    }
  }

  // PROCESS QUEUE (Queued Write Mode)
  private void processQueue() {

    while (!this.closed) {

      WriteChunk chunk = this.queue.poll();

      if (chunk == null) {
        // Queue empty -> exit writer
        return;
      }

      try {
        // Split chunk into packets
        if (this.packetSize <= 0 || chunk.length <= this.packetSize) {

          // Small chunk -> send directly
          writeToDevice(chunk.data, chunk.offset, chunk.length);
          microDelay();

        } else {

          // Split into packets
          int pos = chunk.offset;
          int end = chunk.offset + chunk.length;

          while (pos < end && !this.closed) {
            int n = Math.min(this.packetSize, end - pos);

            writeToDevice(chunk.data, pos, n);
            microDelay();

            pos += n;
          }
        }

      } catch (IOException writeErr) {
        Log.e(TAG, "Writer error: " + writeErr.getMessage(), writeErr);

        // Clear queue so no further writes happen
        this.queue.clear();
        return;
      }
    }
  }

  // MICRO DELAY (Bluetooth requires pacing)
  public void microDelay() {
    if (this.microDelayMs <= 0) {
      return;
    }
    try {
      Thread.sleep(this.microDelayMs);
    } catch (InterruptedException ignored) {
    }
  }

  // FINISH - Drain queue fully AND give transport time to flush
  public void finish() {
    if (!this.useQueue) {
      return; // direct mode doesn't need draining
    }

    final long start = System.nanoTime();

    // Wait for queue drain AND write-thread exit
    while (this.writerRunning || !this.queue.isEmpty()) {
      try {
        Log.i(TAG, "waiting to finish; queueSize: " + this.queue.size());
        Thread.sleep(5);
      } catch (InterruptedException ignored) {
      }
    }

    // CRITICAL: Allow OS -> SPP -> printer hardware buffer to drain
    try {
      Thread.sleep(50);
    } catch (InterruptedException ignored) {
    }

    Log.i(TAG, "finish took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
  }

  // CLOSE CONNECTION (safe shutdown)
  public synchronized void close() {
    if (this.closed) {
      return;
    }

    Log.i(TAG, "Closing connection");

    this.closed = true;

    try {
      finish(); // ensure queue fully written
    } catch (Exception ignored) {
    }

    try {
      // 🔥 Extra pacing before close to avoid BT truncation
      Thread.sleep(40);
    } catch (InterruptedException ignored) {
    }

    try {
      closeDevice();
    } catch (IOException e) {
      Log.e(TAG, "Error closing device: " + e.getMessage());
    }

    // Shutdown executor
    try {
      this.executor.shutdownNow();
    } catch (Exception ignored) {
    }
  }

  // CHECK IF CLOSED
  public boolean isClosed() {
    return this.closed;
  }

  // DEBUG: LOG QUEUE SIZE
  public int getQueueSize() {
    return this.queue.size();
  }
}
