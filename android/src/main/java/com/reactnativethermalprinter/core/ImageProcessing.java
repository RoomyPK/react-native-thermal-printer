package com.reactnativethermalprinter.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import android.util.Log;

import com.reactnativethermalprinter.settings.PrinterSettings;
import com.reactnativethermalprinter.connection.FastDeviceConnection;

import java.nio.ByteBuffer;

public class ImageProcessing {

  private static final String TAG = "RNTP.ImageProcessing";

  // Reusable working buffers to reduce allocations + GC
  private static int[] pixelBuffer; // Grayscale buffer
  private static byte[] rasterLineBuffer; // GS v0 packed row

  /**
   * Decode a Base64 image string into a Bitmap.
   *
   * Supports both:
   * - raw base64 ("iVBORw0KGgoAAA…")
   * - data URLs ("data:image/png;base64,iVBORw0K…")
   */
  public static Bitmap fromBase64(String base64) {
    final long start = System.nanoTime();
    try {
      if (base64 == null || base64.isEmpty()) {
        return null;
      }

      // Strip prefix if present
      int comma = base64.indexOf(',');
      if (comma != -1) {
        base64 = base64.substring(comma + 1);
      }

      byte[] decoded = Base64.decode(base64, Base64.DEFAULT);

      Bitmap bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
      if (bmp == null) {
        throw new Exception("Failed to decode Base64 image");
      }
      return bmp;
    } catch (Exception e) {
      Log.e(TAG, "Error decoding base64: " + e.getMessage(), e);
      return null;
    } finally {
      Log.i(TAG, "fromBase64 took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
    }
  }

  // ----------------------------------------------------------
  // PREPARE IMAGE FOR ESC/POS:
  // 1. Scale to printer width
  // 2. Convert to grayscale
  // 3. Dither (optional)
  // ----------------------------------------------------------
  public static Bitmap prepare(Bitmap bmp, PrinterSettings settings) {
    final long start = System.nanoTime();

    final int targetWidth = settings.getPrinterWidthPx();
    final PrinterSettings.DitherMode ditherMode = settings.getDitherMode();

    Log.i(TAG, "srcWidth: " + bmp.getWidth() + "; targetWidth: " + targetWidth);

    // 1. SCALE (no upscaling)
    if (bmp.getWidth() > targetWidth) {
      bmp = scaleToWidth(bmp, targetWidth);
    }

    int w = bmp.getWidth();
    int h = bmp.getHeight();

    Log.i(TAG, "scaledWidth: " + w + "; scaledHeight: " + h);

    int size = w * h;

    // Allocate pixel buffer once
    if (pixelBuffer == null || pixelBuffer.length < size) {
      pixelBuffer = new int[size];
    }

    bmp.getPixels(pixelBuffer, 0, w, 0, 0, w, h);

    // 2. GRAYSCALE
    grayscale(pixelBuffer, size);

    // 3. DITHER (Atkinson)
    if (ditherMode == PrinterSettings.DitherMode.ATKINSON) {
      atkinsonDither(pixelBuffer, w, h);
    } else if (ditherMode == PrinterSettings.DitherMode.THRESHOLD) {
      thresholdDither(pixelBuffer, size);
    }

    // Convert buffer -> bitmap (monochrome but kept as ARGB_8888)
    Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

    out.setPixels(pixelBuffer, 0, w, 0, 0, w, h);

    Log.i(TAG, "prepare took " + ((System.nanoTime() - start) / 1_000_000) + " ms");

    return out;
  }

  // Scale a bitmap to a target width while maintaining aspect ratio.
  private static Bitmap scaleToWidth(Bitmap src, int targetWidth) {

    int w = src.getWidth();
    if (w == targetWidth) {
      return src; // already required width
    }

    final long start = System.nanoTime();

    float ratio = (float) targetWidth / (float) w;
    int targetHeight = Math.round(src.getHeight() * ratio);

    Bitmap scaled = Bitmap.createScaledBitmap(src, targetWidth, targetHeight, false);

    Log.i(TAG, "scaleToWidth took " + ((System.nanoTime() - start) / 1_000_000) + " ms");

    return scaled;
  }

  // ----------------------------------------------------------
  // FAST GRAYSCALE (in-place)
  // ----------------------------------------------------------
  private static void grayscale(int[] pix, int size) {

    final long start = System.nanoTime();

    for (int i = 0; i < size; i++) {
      int c = pix[i];

      int r = (c >> 16) & 0xFF;
      int g = (c >> 8) & 0xFF;
      int b = c & 0xFF;

      // Luminance (ITU BT.601)
      int y = (r * 299 + g * 587 + b * 114) / 1000;

      pix[i] = Color.rgb(y, y, y);
    }

    Log.i(TAG, "grayscale took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
  }

  // ----------------------------------------------------------
  // SIMPLE THRESHOLD DITHER (fallback if no Atkinson)
  // ----------------------------------------------------------
  private static void thresholdDither(int[] pix, int size) {

    final long start = System.nanoTime();

    for (int i = 0; i < size; i++) {
      int c = pix[i] & 0xFF; // grayscale already
      int bw = (c < 128) ? 0 : 255;
      pix[i] = Color.rgb(bw, bw, bw);
    }

    Log.i(TAG, "thresholdDither took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
  }

  // ----------------------------------------------------------
  // ATKINSON DITHER (optimized, in-place)
  // ----------------------------------------------------------
  private static void atkinsonDither(int[] pix, int w, int h) {

    final long start = System.nanoTime();

    for (int y = 0; y < h; y++) {

      int row = y * w;

      for (int x = 0; x < w; x++) {

        int i = row + x;

        int old = pix[i] & 0xFF;
        int newVal = (old < 128) ? 0 : 255;
        int err = old - newVal;

        // Set pixel
        pix[i] = Color.rgb(newVal, newVal, newVal);

        // Distribute error to neighbors:
        // (x+1, y)
        if (x + 1 < w) {
          int idx = i + 1;
          int v = (pix[idx] & 0xFF) + (err >> 3);
          v = v < 0 ? 0 : (v > 255 ? 255 : v);
          pix[idx] = Color.rgb(v, v, v);
        }
        // (x+2, y)
        if (x + 2 < w) {
          int idx = i + 2;
          int v = (pix[idx] & 0xFF) + (err >> 3);
          v = v < 0 ? 0 : (v > 255 ? 255 : v);
          pix[idx] = Color.rgb(v, v, v);
        }
        // (x-1, y+1)
        if (y + 1 < h && x - 1 >= 0) {
          int idx = i + w - 1;
          int v = (pix[idx] & 0xFF) + (err >> 3);
          v = v < 0 ? 0 : (v > 255 ? 255 : v);
          pix[idx] = Color.rgb(v, v, v);
        }
        // (x, y+1)
        if (y + 1 < h) {
          int idx = i + w;
          int v = (pix[idx] & 0xFF) + (err >> 3);
          v = v < 0 ? 0 : (v > 255 ? 255 : v);
          pix[idx] = Color.rgb(v, v, v);
        }
        // (x+1, y+1)
        if (y + 1 < h && x + 1 < w) {
          int idx = i + w + 1;
          int v = (pix[idx] & 0xFF) + (err >> 3);
          v = v < 0 ? 0 : (v > 255 ? 255 : v);
          pix[idx] = Color.rgb(v, v, v);
        }
        // (x, y+2)
        if (y + 2 < h) {
          int idx = i + (w << 1);
          int v = (pix[idx] & 0xFF) + (err >> 3);
          v = v < 0 ? 0 : (v > 255 ? 255 : v);
          pix[idx] = Color.rgb(v, v, v);
        }
      }
    }

    Log.i(TAG, "atkinsonDither took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
  }

  // ----------------------------------------------------------
  // GS v0 RASTER BUILDER (Fastest mode for ESC/POS)
  //
  // Output format:
  // GS v 0 m xL xH yL yH [bitmap-bytes]
  //
  // 1 bit per pixel, packed MSB->LSB
  // ----------------------------------------------------------
  public static byte[] toRasterGSv0(Bitmap bmp) {

    final long start = System.nanoTime();

    int w = bmp.getWidth();
    int h = bmp.getHeight();

    // Bytes per row (1 bit per pixel)
    int bytesPerRow = (w + 7) >> 3;
    int imageDataSize = bytesPerRow * h;

    int commandSize = 8 + imageDataSize;
    ByteBuffer buffer = ByteBuffer.allocate(commandSize);

    // Header: GS v 0 m xL xH yL yH
    buffer.put((byte) 0x1D);
    buffer.put((byte) 0x76);
    buffer.put((byte) 0x30);
    buffer.put((byte) 0x00); // m = 0 -> normal density

    // xL, xH
    buffer.put((byte) (bytesPerRow & 0xFF));
    buffer.put((byte) ((bytesPerRow >> 8) & 0xFF));

    // yL, yH
    buffer.put((byte) (h & 0xFF));
    buffer.put((byte) ((h >> 8) & 0xFF));

    // Reuse line buffer
    if (rasterLineBuffer == null || rasterLineBuffer.length < bytesPerRow) {
      rasterLineBuffer = new byte[bytesPerRow];
    }

    // Pack bits line-by-line
    for (int y = 0; y < h; y++) {

      // Pack into raster bytes
      int byteIndex = 0;
      int bitPos = 7;
      byte current = 0;

      for (int x = 0; x < w; x++) {

        int c = bmp.getPixel(x, y) & 0xFF; // already grayscale from prepare()

        if (c < 128) { // black
          current |= (1 << bitPos);
        }

        bitPos--;

        if (bitPos < 0) {
          rasterLineBuffer[byteIndex++] = current;
          current = 0;
          bitPos = 7;
        }
      }

      // Last partial byte
      if (bitPos != 7) {
        rasterLineBuffer[byteIndex++] = current;
      }

      // Pad remaining bytes
      // while (byteIndex < bytesPerRow) {
      // rasterLineBuffer[byteIndex++] = 0;
      // }

      buffer.put(rasterLineBuffer, 0, bytesPerRow);
    }

    byte[] out = buffer.array();

    Log.i(TAG, "toRasterGSv0 took " + ((System.nanoTime() - start) / 1_000_000) + " ms");

    return out;
  }

  /**
   * STREAMING GS v0 RASTER MODE
   * 
   * For very long receipts.
   * 
   * Write image row-by-row directly to connection.
   * 
   * No full bitmap buffer is allocated.
   */
  public static void streamRasterGSv0(Bitmap bmp, FastDeviceConnection conn) throws Exception {

    final long start = System.nanoTime();

    int w = bmp.getWidth();
    int h = bmp.getHeight();

    // Bytes per row (1 bit per pixel)
    int bytesPerRow = (w + 7) >> 3;

    byte[] header = new byte[8];

    // Header: GS v 0 m xL xH yL yH
    header[0] = 0x1D;
    header[1] = 0x76;
    header[2] = 0x30;
    header[3] = 0x00; // m = 0 -> normal density

    // xL, xH
    header[4] = (byte) (bytesPerRow & 0xFF);
    header[5] = (byte) ((bytesPerRow >> 8) & 0xFF);

    // yL, yH
    header[6] = (byte) (h & 0xFF);
    header[7] = (byte) ((h >> 8) & 0xFF);

    // Send header first
    conn.write(header);

    // Reusable row buffer (saves hundreds of allocations)
    byte[] row = new byte[bytesPerRow];

    int[] argbRow = new int[w]; // temp pixel row buffer

    // Pack bits line-by-line
    for (int y = 0; y < h; y++) {

      // Extract 1 row of ARGB
      bmp.getPixels(argbRow, 0, w, 0, y, w, 1);

      // Pack into raster bytes
      int byteIndex = 0;
      int bitPos = 7;
      byte current = 0;

      for (int x = 0; x < w; x++) {

        int c = argbRow[x] & 0xFF; // already grayscale from prepare()

        if (c < 128) { // black
          current |= (1 << bitPos);
        }

        bitPos--;

        if (bitPos < 0) {
          row[byteIndex++] = current;
          current = 0;
          bitPos = 7;
        }
      }

      // Last partial byte
      if (bitPos != 7) {
        row[byteIndex++] = current;
      }

      // // Pad remaining bytes
      // while (byteIndex < bytesPerRow) {
      // row[byteIndex++] = 0;
      // }

      // WRITE ONE ROW TO PRINTER
      conn.write(row);
      // Let the queue drain smoothly
      conn.microDelay();
    }

    // Ensure all streaming data was flushed
    conn.finish();

    Log.i(TAG, "streamRasterGSv0 took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
  }

  // ----------------------------------------------------------
  // ESC * (m = 33 -> 24-dot mode)
  //
  // Format:
  // 1B 2A 21 nL nH [data …]
  //
  // Where data = (width * 24) bitmap bits, column-major.
  // ----------------------------------------------------------
  public static byte[] toEscStar24(Bitmap bmp) {

    final long start = System.nanoTime();

    int w = bmp.getWidth();
    int h = bmp.getHeight();

    // ESC * works in vertical stripes of 24 pixels
    int bandHeight = 24;

    int bytesPerRow = w * 3; // 24 bits = 3 bytes per column
    int totalBands = (h + 23) / 24;

    // Compute max required size (bands × command size)
    // Each ESC* band:
    // 5-byte header + (w * 3) bytes image data
    int maxSize = totalBands * (5 + bytesPerRow);

    ByteBuffer buffer = ByteBuffer.allocate(maxSize);

    // For each 24-pixel band
    for (int band = 0; band < totalBands; band++) {

      int yStart = band * bandHeight;
      int yEnd = Math.min(yStart + bandHeight, h);
      int actualRows = yEnd - yStart;

      // ESC * header
      buffer.put((byte) 0x1B); // ESC
      buffer.put((byte) 0x2A); // '*'
      buffer.put((byte) 0x21); // m = 33 (24-dot double-density)
      buffer.put((byte) (w & 0xFF)); // nL
      buffer.put((byte) ((w >> 8) & 0xFF)); // nH

      // Pack bits
      for (int x = 0; x < w; x++) {

        byte b0 = 0, b1 = 0, b2 = 0;

        // For each row in band (max 24)
        for (int bit = 0; bit < 24; bit++) {

          int yy = yStart + bit;

          boolean black = false;

          if (yy < h) {
            int c = bmp.getPixel(x, yy) & 0xFF;
            black = (c < 128);
          }

          if (black) {
            if (bit < 8) {
              b0 |= (1 << (7 - bit));
            } else if (bit < 16) {
              b1 |= (1 << (15 - bit));
            } else {
              b2 |= (1 << (23 - bit));
            }
          }
        }

        buffer.put(b0);
        buffer.put(b1);
        buffer.put(b2);
      }
    }

    // Trim buffer to actual used size
    byte[] finalOut = new byte[buffer.position()];
    buffer.flip();
    buffer.get(finalOut);

    Log.i(TAG, "toEscStar24 took " + ((System.nanoTime() - start) / 1_000_000) + " ms");

    return finalOut;
  }

}
