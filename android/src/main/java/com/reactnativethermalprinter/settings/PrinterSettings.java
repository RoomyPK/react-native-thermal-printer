package com.reactnativethermalprinter.settings;

public class PrinterSettings {

  public enum PrintMode {
    RASTER_GSV0, // GS v 0 full-image raster (fastest)
    RASTER_GSV0_STREAMED, // GS v 0 full-image raster (fastest) streamed (for very long receipts)
    RASTER_GSV0_CHUNKED, // GS v 0 vertically-chunked image raster (safe for weak printers)
    LEGACY_ESC // ESC * 24-dot bit-image mode (universal fallback)
  }

  public enum DitherMode {
    ATKINSON, // Default, fast FLOYD_STEINBERG variant, best for thermal printers
    FLOYD_STEINBERG, // highest quality, slowest
    BAYER, // 8x8, very fast, clean, best for QR/text
    THRESHOLD, // fastest, sharpest, not great for photos
    NONE
  }

  private static final int DEFAULT_PRINTER_DPI = 203;

  private static final int DEFAULT_PRINTER_WIDTH_MM = 80;

  // 58mm (384px @ 203dpi)
  // 80mm (576px @ 203dpi)
  // 80mm (640px @ 203dpi)
  // private static final int DEFAULT_PRINTER_WIDTH_PX = 576;

  private static final int DEFAULT_CHUNK_HEIGHT = 256; // Chunk height (for RASTER_GSV0_CHUNKED)

  private static final int DEFAULT_PACKET_SIZE = 1024; // Packet size for Bluetooth/TCP chunking
                                                       // 4096, 2048, 1024, 512, 256, 128

  private String ipAddress = null;
  private int ipPort = 9100;

  private String macAddress = null;

  private int timeoutMs = 4000;

  private int printerDpi = DEFAULT_PRINTER_DPI;
  private int printerWidthMm = DEFAULT_PRINTER_WIDTH_MM;

  private int printerCharPerLine = 32;

  private PrintMode printMode = PrintMode.RASTER_GSV0;

  private DitherMode ditherMode = DitherMode.ATKINSON;

  private int chunkHeight = DEFAULT_CHUNK_HEIGHT;

  private int packetSize = DEFAULT_PACKET_SIZE;

  // Micro delay per packet (BT only)
  private int packetMicroDelayMs = 2;

  // (true = queued writer thread; false = direct writes)
  private boolean useQueueForWrites = true;

  public String getIpAddress() {
    return this.ipAddress;
  }

  public void setIpAddress(String ip) {
    this.ipAddress = ip;
  }

  public int getIpPort() {
    return this.ipPort;
  }

  public void setIpPort(int port) {
    this.ipPort = port;
  }

  public String getMacAddress() {
    return this.macAddress;
  }

  public void setMacAddress(String mac) {
    this.macAddress = mac;
  }

  public int getTimeoutMs() {
    return this.timeoutMs;
  }

  public void setTimeoutMs(int ms) {
    this.timeoutMs = ms;
  }

  public int getPrinterDpi() {
    return this.printerDpi;
  }

  public void setPrinterDpi(int dpi) {
    this.printerDpi = dpi;
  }

  public int getPrinterWidthMm() {
    return this.printerWidthMm;
  }

  public void setPrinterWidthMm(int mm) {
    this.printerWidthMm = mm;
  }

  public int getPrinterWidthPx() {
    return Math.round((this.printerWidthMm / 25.4f) * this.printerDpi);
  }

  public int getPrinterCharPerLine() {
    return this.printerCharPerLine;
  }

  public void setPrinterCharPerLine(int c) {
    this.printerCharPerLine = c;
  }

  public PrintMode getPrintMode() {
    return this.printMode;
  }

  public void setPrintMode(PrintMode mode) {
    this.printMode = mode;
  }

  public DitherMode getDitherMode() {
    return this.ditherMode;
  }

  public void setDitherMode(DitherMode mode) {
    this.ditherMode = mode;
  }

  public int getChunkHeight() {
    return this.chunkHeight;
  }

  public void setChunkHeight(int height) {
    this.chunkHeight = height;
  }

  public int getPacketSize() {
    return this.packetSize;
  }

  public void setPacketSize(int size) {
    this.packetSize = size;
  }

  public int getPacketMicroDelayMs() {
    return this.packetMicroDelayMs;
  }

  public void setPacketMicroDelayMs(int ms) {
    this.packetMicroDelayMs = ms;
  }

  public boolean getUseQueueForWrites() {
    return this.useQueueForWrites;
  }

  public void setUseQueueForWrites(boolean useQueue) {
    this.useQueueForWrites = useQueue;
  }

  public PrinterSettings(
      String ipAddress,
      int ipPort,
      String macAddress,
      int timeoutMs,
      int printerDpi,
      int printerWidthMm,
      int printerCharPerLine,
      PrintMode printMode,
      DitherMode ditherMode,
      int chunkHeight,
      int packetSize,
      int packetMicroDelayMs,
      boolean useQueueForWrites) {
    this.ipAddress = ipAddress;
    this.ipPort = ipPort;
    this.macAddress = macAddress;
    this.timeoutMs = timeoutMs;
    this.printerDpi = printerDpi;
    this.printerWidthMm = printerWidthMm;
    this.printerCharPerLine = printerCharPerLine;
    this.printMode = printMode;
    this.ditherMode = ditherMode;
    this.chunkHeight = chunkHeight;
    this.packetSize = packetSize;
    this.packetMicroDelayMs = packetMicroDelayMs;
    this.useQueueForWrites = useQueueForWrites;
  }

  public PrinterSettings(String ipAddress, int ipPort) {
    this.ipAddress = ipAddress;
    this.ipPort = ipPort;
  }

  public PrinterSettings(String macAddress) {
    this.macAddress = macAddress;
  }

  // For debugging
  @Override
  public String toString() {
    return "PrinterSettings {" +
        "\n\tipAddress: " + this.ipAddress +
        "\n\tipPort: " + this.ipPort +
        "\n\tmacAddress: " + this.macAddress +
        "\n\ttimeoutMs: " + this.timeoutMs +
        "\n\tprinterDpi: " + this.printerDpi +
        "\n\tprinterWidthMm: " + this.printerWidthMm +
        "\n\tprinterWidthPx: " + this.getPrinterWidthPx() +
        "\n\tprinterCharPerLine: " + this.printerCharPerLine +
        "\n\tprintMode: " + this.printMode +
        "\n\tditherMode: " + this.ditherMode +
        "\n\tchunkHeight: " + this.chunkHeight +
        "\n\tpacketSize: " + this.packetSize +
        "\n\tpacketMicroDelayMs: " + this.packetMicroDelayMs +
        "\n\tuseQueueForWrites: " + this.useQueueForWrites +
        "\n}";
  }

}
