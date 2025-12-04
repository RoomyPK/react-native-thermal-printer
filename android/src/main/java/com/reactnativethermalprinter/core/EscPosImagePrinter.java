package com.reactnativethermalprinter.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.reactnativethermalprinter.connection.FastDeviceConnection;
import com.reactnativethermalprinter.core.ImageProcessing;
import com.reactnativethermalprinter.settings.PrinterSettings;

public class EscPosImagePrinter {

  private static final String TAG = "RNTP.EscPosImagePrinter";

  private final PrinterSettings settings;
  private final FastDeviceConnection conn;

  // JOB TYPE TRACKING
  private enum PrintJobType {
    NONE,
    RASTER_GSV0,
    RASTER_GSV0_STREAMED,
    RASTER_GSV0_CHUNKED,
    LEGACY_ESC
  }

  private PrintJobType lastJobType = PrintJobType.NONE;

  public EscPosImagePrinter(PrinterSettings settings, FastDeviceConnection conn) {
    this.settings = settings;
    this.conn = conn;
    this.lastJobType = PrintJobType.NONE;
  }

  // BASE64 ENTRY POINT
  public boolean printBase64Image(String base64Image,
      boolean autoCut,
      boolean openCashBox,
      int feedLines)
      throws Exception {
    try {
      Bitmap bmp = ImageProcessing.fromBase64(base64Image);

      if (bmp == null) {
        throw new Exception("Failed to decode Base64 image");
      }

      return printBitmap(bmp, autoCut, openCashBox, feedLines);

    } catch (Exception e) {

      Log.e(TAG, "Error in printBase64: " + e.getMessage(), e);

      return false;
    }
  }

  // MAIN BITMAP PRINTER
  public boolean printBitmap(Bitmap bitmap, boolean autoCut, boolean openCashBox, int feedLines) {
    try {
      Bitmap prepared = ImageProcessing.prepare(bitmap, this.settings);

      this.startJob();

      switch (this.settings.getPrintMode()) {

        case RASTER_GSV0:
          this.lastJobType = PrintJobType.RASTER_GSV0;
          this.printRaster(prepared);
          break;

        case RASTER_GSV0_STREAMED:
          this.lastJobType = PrintJobType.RASTER_GSV0_STREAMED;
          ImageProcessing.streamRasterGSv0(prepared, this.conn);
          break;

        case RASTER_GSV0_CHUNKED:
          this.lastJobType = PrintJobType.RASTER_GSV0_CHUNKED;
          this.printChunkedRaster(prepared);
          break;

        case LEGACY_ESC:
          this.lastJobType = PrintJobType.LEGACY_ESC;
          this.printEscLegacy(prepared);
          break;

        default:
          this.lastJobType = PrintJobType.NONE;
          return false;
      }

      this.finishJob(autoCut, openCashBox, feedLines);

      return true;

    } catch (Exception e) {

      Log.e(TAG, "Printing bitmap failed: " + e.getMessage(), e);

      return false;
    }
  }

  // ----------------------------------------------------------
  // MODE 1 — FULL RASTER (GS v 0)
  // ----------------------------------------------------------
  private void printRaster(Bitmap bw) throws Exception {

    // Build GS v0 raster command
    byte[] raster = ImageProcessing.toRasterGSv0(bw);

    // Connection handles packet splitting internally
    this.conn.write(raster);

    // Flush writer thread if queue mode
    this.conn.finish();
  }

  // ----------------------------------------------------------
  // MODE 2 — CHUNKED RASTER (Vertical GS v0 slices)
  // ----------------------------------------------------------
  private void printChunkedRaster(Bitmap bw) throws Exception {

    int width = bw.getWidth();
    int height = bw.getHeight();
    int sliceH = settings.getChunkHeight(); // typically 256 px

    for (int y = 0; y < height; y += sliceH) {

      int h = Math.min(sliceH, height - y);

      Bitmap slice = Bitmap.createBitmap(bw, 0, y, width, h);

      byte[] rasterSlice = ImageProcessing.toRasterGSv0(slice);

      this.conn.write(rasterSlice); // WRITE chunking done by connection

      this.conn.finish(); // flush queue before next slice
    }
  }

  // ----------------------------------------------------------
  // MODE 3 - ESC * 24-DOT LEGACY BIT IMAGE
  // ----------------------------------------------------------
  private void printEscLegacy(Bitmap bw) throws Exception {

    int width = bw.getWidth();
    int height = bw.getHeight();

    // ESC/POS prints in vertical stripes of 24 dots
    for (int y = 0; y < height; y += 24) {

      int bandHeight = Math.min(24, height - y);

      Bitmap slice = Bitmap.createBitmap(bw, 0, y, width, bandHeight);

      byte[] escData = ImageProcessing.toEscStar24(slice);

      this.conn.write(escData); // Connection handles all packet chunking internally

      this.conn.finish(); // Ensure each 24-dot band is flushed before next block
    }
  }

  private void startJob() throws Exception {

    final long start = System.nanoTime();

    // ---- Initialize printer state (ESC @) ----
    this.conn.write(new byte[] { 0x1B, 0x40 });

    this.conn.finish();

    Log.i(TAG, "startJob took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
  }

  // ----------------------------------------------------------
  // FINISH JOB: Exit GSv0 (if needed), Reset (if needed),
  // Feed, Cut, Drawer Kick, Queue Drain, Final Sleep
  // ----------------------------------------------------------
  private void finishJob(boolean autoCut, boolean openCashBox, int feedLines) throws Exception {

    final long start = System.nanoTime();

    // this.conn.finish();

    // ======================================================
    // STEP 1: CONDITIONAL RASTER FINALIZATION (GSV0 / CHUNKED)
    // ======================================================
    if (this.lastJobType == PrintJobType.RASTER_GSV0 ||
        this.lastJobType == PrintJobType.RASTER_GSV0_CHUNKED ||
        this.lastJobType == PrintJobType.RASTER_GSV0_STREAMED) {

      // Cheap printers do not support "Exit GS v0 Mode" command. A LF works.
      // Log.i(TAG, "finishJob: exiting GS v0 mode");

      // ---- Exit GS v0 Mode ----
      // byte[] exitGsv0 = new byte[] {
      // 0x1D, 0x28, 0x4C, 0x02, 0x00, 0x30, 0x00
      // };
      // this.conn.write(exitGsv0);

      // ---- Flush Raster Data with LF ----
      this.conn.write(new byte[] { 0x0A });

      // ---- Reset printer state (ESC @) ----
      this.conn.write(new byte[] { 0x1B, 0x40 });
    }

    // Drain queue before feed/cut
    this.conn.finish();

    // ======================================================
    // STEP 2: FEED LINES
    // ======================================================
    if (feedLines > 0) {
      byte[] feed = new byte[] { 0x1B, 0x64, (byte) feedLines }; // ESC d n -> feed n lines
      this.conn.write(feed);
    }

    // ======================================================
    // STEP 3: CUT (if selected)
    // ======================================================
    if (autoCut) {
      byte[] cut = new byte[] { 0x1D, 0x56, 0x00 }; // GS V 0 -> full cut
      this.conn.write(cut);
    }

    // OPEN CASH DRAWER
    if (openCashBox) {
      byte[] kick = new byte[] {
          0x1B, 0x70, 0x00, // ESC p m t1 t2
          0x50, 0x50 // 80ms, 80ms pulse
      };
      this.conn.write(kick);
    }

    // Let the executor drain anything still pending
    this.conn.finish();

    // ======================================================
    // STEP 5: FINAL SLEEP FOR BLUETOOTH RELIABILITY
    // ======================================================
    // Allow printer time for mechanical operations (safe delay)
    try {
      Thread.sleep(60); // 50–80 ms recommended for BT
    } catch (InterruptedException ignored) {
    }

    this.lastJobType = PrintJobType.NONE;

    // Close the connection (BT/TCP)
    // this.conn.close();

    Log.i(TAG, "finishJob took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
  }
}
