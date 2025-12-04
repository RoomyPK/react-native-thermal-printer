package com.reactnativethermalprinter;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;

import com.dantsu.escposprinter.EscPosPrinter;
import com.dantsu.escposprinter.EscPosPrinterCommands;
import com.dantsu.escposprinter.connection.DeviceConnection;
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection;
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections;
import com.dantsu.escposprinter.connection.tcp.TcpConnection;
import com.dantsu.escposprinter.exceptions.EscPosBarcodeException;
import com.dantsu.escposprinter.exceptions.EscPosConnectionException;
import com.dantsu.escposprinter.exceptions.EscPosEncodingException;
import com.dantsu.escposprinter.exceptions.EscPosParserException;
import com.dantsu.escposprinter.textparser.PrinterTextParserImg;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.module.annotations.ReactModule;

import com.reactnativethermalprinter.connection.BluetoothConnectionManager;
import com.reactnativethermalprinter.connection.FastBluetoothConnection;
import com.reactnativethermalprinter.connection.FastDeviceConnection;
import com.reactnativethermalprinter.connection.FastTcpConnection;
import com.reactnativethermalprinter.core.EscPosImagePrinter;
import com.reactnativethermalprinter.settings.PrinterSettings;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

@ReactModule(name = ThermalPrinterModule.NAME)
public class ThermalPrinterModule extends ReactContextBaseJavaModule {
  private static final String TAG = "RNTP.ThermalPrinterModule";

  public static final String NAME = "ThermalPrinterModule";

  public static final int VERSION = 2;

  private Promise jsPromise;

  private ArrayList<BluetoothDevice> btDevicesList = new ArrayList();

  public ThermalPrinterModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void printTcp(String ipAddress, double port, String payload, boolean autoCut, boolean openCashbox,
      double mmFeedPaper, double printerDpi, double printerWidthMM, double printerNbrCharactersPerLine, double timeout,
      boolean payloadIsBase64EncodedImage, boolean usePrinterCommands, boolean useEscAsteriskCommand, Promise promise) {

    if (VERSION == 2 && payloadIsBase64EncodedImage) {
      this.printBase64Image(
          "TCP",
          ipAddress,
          port,
          null,
          timeout,
          payload,
          autoCut,
          openCashbox,
          mmFeedPaper,
          printerDpi,
          printerWidthMM,
          printerNbrCharactersPerLine,
          promise);

      return;
    }

    //
    // 05-05-2021
    // https://reactnative.dev/docs/native-modules-android
    // The following types are currently supported but will not be supported in
    // TurboModules. Please avoid using them:
    //
    // Integer -> ?number
    // int -> number
    // Float -> ?number
    // float -> number
    //

    this.jsPromise = promise;

    try {
      TcpConnection connection = new TcpConnection(ipAddress, (int) port, (int) timeout);
      if (payloadIsBase64EncodedImage) {
        this.printBase64EncodedImage(connection, payload, printerDpi, printerWidthMM, printerNbrCharactersPerLine,
            mmFeedPaper, autoCut, openCashbox, usePrinterCommands, useEscAsteriskCommand);
      } else {
        this.printIt(connection, payload, autoCut, openCashbox, mmFeedPaper, printerDpi, printerWidthMM,
            printerNbrCharactersPerLine);
      }
    } catch (Exception e) {
      this.jsPromise.reject("Connection Error", e.getMessage());
    }
  }

  @ReactMethod
  public void printBluetooth(
      String macAddress,
      String payload,
      boolean autoCut,
      boolean openCashbox,
      double mmFeedPaper,
      double printerDpi,
      double printerWidthMM,
      double printerNbrCharactersPerLine,
      boolean payloadIsBase64EncodedImage,
      boolean usePrinterCommands,
      boolean useEscAsteriskCommand,
      Promise promise) {

    if (VERSION == 2 && payloadIsBase64EncodedImage) {
      this.printBase64Image(
          "BLUETOOTH",
          null,
          0,
          macAddress,
          0,
          payload,
          autoCut,
          openCashbox,
          mmFeedPaper,
          printerDpi,
          printerWidthMM,
          printerNbrCharactersPerLine,
          promise);

      return;
    }

    this.jsPromise = promise;

    BluetoothConnection btPrinter = null;

    if (TextUtils.isEmpty(macAddress)) {
      btPrinter = BluetoothPrintersConnections.selectFirstPaired();
    } else {
      BluetoothDevice btDevice = getBluetoothDeviceWithMacAddress(macAddress);
      if (btDevice != null) {
        btPrinter = new BluetoothConnection(btDevice);
      }
    }

    if (btPrinter == null) {
      this.jsPromise.reject("Connection Error", "Bluetooth Device Not Found");
    }

    int permissionCheck = ContextCompat.checkSelfPermission(getCurrentActivity(), Manifest.permission.BLUETOOTH);

    if (permissionCheck != PackageManager.PERMISSION_GRANTED) {

      ActivityCompat.requestPermissions(getCurrentActivity(), new String[] { Manifest.permission.BLUETOOTH }, 1);

      this.jsPromise.reject("Connection Error", "Bluetooth Permission Not Granted");

      return;
    }

    try {

      if (payloadIsBase64EncodedImage) {

        this.printBase64EncodedImage(
            btPrinter.connect(),
            payload,
            printerDpi,
            printerWidthMM,
            printerNbrCharactersPerLine,
            mmFeedPaper,
            autoCut,
            openCashbox,
            usePrinterCommands,
            useEscAsteriskCommand);

      } else {

        this.printIt(
            btPrinter.connect(),
            payload,
            autoCut,
            openCashbox,
            mmFeedPaper,
            printerDpi,
            printerWidthMM,
            printerNbrCharactersPerLine);

      }

    } catch (Exception e) {

      this.jsPromise.reject("Connection Error", e.getMessage());
    }
  }

  @ReactMethod
  public void getBluetoothDeviceList(Promise promise) {

    this.jsPromise = promise;

    int permissionCheck = ContextCompat.checkSelfPermission(getCurrentActivity(), Manifest.permission.BLUETOOTH);

    if (permissionCheck != PackageManager.PERMISSION_GRANTED) {

      ActivityCompat.requestPermissions(getCurrentActivity(), new String[] { Manifest.permission.BLUETOOTH }, 1);

      this.jsPromise.reject("Connection Error", "Bluetooth Permission Not Granted");

      return;
    }

    try {

      Set<BluetoothDevice> pairedDevices = BluetoothAdapter.getDefaultAdapter().getBondedDevices();

      WritableArray rnArray = new WritableNativeArray();

      if (pairedDevices.size() > 0) {

        for (BluetoothDevice device : pairedDevices) {

          this.btDevicesList.add(device);

          String deviceName = device.getName();
          String macAddress = device.getAddress();

          JSONObject jsonObj = new JSONObject();

          jsonObj.put("deviceName", deviceName);
          jsonObj.put("macAddress", macAddress);

          WritableMap wmap = convertJsonToMap(jsonObj);

          rnArray.pushMap(wmap);
        }
      }

      this.jsPromise.resolve(rnArray);

    } catch (Exception e) {

      this.jsPromise.reject("Bluetooth Error", e.getMessage());
    }
  }

  private Bitmap getBitmapFromUrl(String url) {
    try {
      Bitmap bitmap = Glide
          .with(getCurrentActivity())
          .asBitmap()
          .load(url)
          .submit()
          .get();
      return bitmap;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Synchronous printing
   */

  private String preprocessImgTag(EscPosPrinter printer, String text) {

    Pattern p = Pattern.compile("(?<=\\<img\\>)(.*)(?=\\<\\/img\\>)");
    Matcher m = p.matcher(text);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String firstGroup = m.group(1);
      m.appendReplacement(sb, PrinterTextParserImg.bitmapToHexadecimalString(printer, getBitmapFromUrl(firstGroup)));
    }
    m.appendTail(sb);

    return sb.toString();
  }

  private void printIt(DeviceConnection printerConnection, String payload, boolean autoCut, boolean openCashbox,
      double mmFeedPaper, double printerDpi, double printerWidthMM, double printerNbrCharactersPerLine) {
    try {
      EscPosPrinter printer = new EscPosPrinter(printerConnection, (int) printerDpi, (float) printerWidthMM,
          (int) printerNbrCharactersPerLine);
      String processedPayload = preprocessImgTag(printer, payload);

      if (openCashbox) {
        printer.printFormattedTextAndOpenCashBox(processedPayload, (float) mmFeedPaper);
      } else if (autoCut) {
        printer.printFormattedTextAndCut(processedPayload, (float) mmFeedPaper);
      } else {
        printer.printFormattedText(processedPayload, (float) mmFeedPaper);
      }

      printer.disconnectPrinter();
      this.jsPromise.resolve(true);
    } catch (EscPosConnectionException e) {
      this.jsPromise.reject("Broken connection", e.getMessage());
    } catch (EscPosParserException e) {
      this.jsPromise.reject("Invalid formatted text", e.getMessage());
    } catch (EscPosEncodingException e) {
      this.jsPromise.reject("Bad selected encoding", e.getMessage());
    } catch (EscPosBarcodeException e) {
      this.jsPromise.reject("Invalid barcode", e.getMessage());
    } catch (Exception e) {
      this.jsPromise.reject("ERROR", e.getMessage());
    }
  }

  private void printBase64EncodedImage(DeviceConnection printerConnection, String base64EncodedImage, double printerDpi,
      double printerWidthMM, double printerNbrCharactersPerLine, double mmFeedPaper, boolean cutPaper,
      boolean openCashBox, boolean usePrinterCommands, boolean useEscAsteriskCommand) {
    try {
      final String base64EncodedImageWithoutPrefix = base64EncodedImage.substring(base64EncodedImage.indexOf(",") + 1);
      final byte[] decodedBytes = Base64.decode(base64EncodedImageWithoutPrefix, Base64.DEFAULT);

      Bitmap originalBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

      final int targetWidth = (int) Math.round(printerWidthMM / 25.4f * printerDpi);

      Bitmap bitmapToPrint = null;

      if (originalBitmap.getWidth() == targetWidth) {

        bitmapToPrint = originalBitmap;

        Log.i(TAG,
            "ThermalPrinterModule.printBase64EncodedImage: Bitmap width matches target width; no need to scale; targetWidth: "
                + targetWidth);

      } else {

        final int targetHeight = Math
            .round(((float) originalBitmap.getHeight()) * ((float) targetWidth) / ((float) originalBitmap.getWidth()));

        bitmapToPrint = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, false);

        Log.i(TAG,
            "ThermalPrinterModule.printBase64EncodedImage: Bitmap width does not match target width; scaling; bitmap dimensions: "
                + originalBitmap.getWidth() + " x " + originalBitmap.getHeight() + " target dimensions: " + targetWidth
                + " x " + targetHeight);
      }

      EscPosPrinterCommands printerCommands = null;
      EscPosPrinter printer = null;

      if (usePrinterCommands) {

        printerCommands = new EscPosPrinterCommands(printerConnection);

        printerCommands.connect();
        printerCommands.reset();

        if (useEscAsteriskCommand) {

          printerCommands.useEscAsteriskCommand(true);
        }

      } else {

        printer = new EscPosPrinter(printerConnection, (int) printerDpi, (float) printerWidthMM,
            (int) printerNbrCharactersPerLine);

        if (useEscAsteriskCommand) {

          printer.useEscAsteriskCommand(true);
        }
      }

      int width = bitmapToPrint.getWidth();
      int height = bitmapToPrint.getHeight();

      StringBuilder textToPrint = new StringBuilder();

      for (int y = 0; y < height; y += 256) {
        Bitmap bitmap = Bitmap.createBitmap(bitmapToPrint, 0, y, width, (y + 256 >= height) ? height - y : 256);

        if (usePrinterCommands) {

          printerCommands.printImage(EscPosPrinterCommands.bitmapToBytes(bitmap, false));

        } else {

          textToPrint
              .append("[L]<img>" + PrinterTextParserImg.bitmapToHexadecimalString(printer, bitmap, false) + "</img>\n");
        }
      }

      if (usePrinterCommands) {

        if (mmFeedPaper > 0) {

          final int dotsFeedPaper = (int) Math.round(mmFeedPaper / 25.4f * printerDpi);

          printerCommands.feedPaper(dotsFeedPaper);
        }

        if (cutPaper) {
          printerCommands.cutPaper();
        }

        if (openCashBox) {
          printerCommands.openCashBox();
        }
      } else {

        textToPrint.append("[C] \n");
        textToPrint.append("[C] \n");
        textToPrint.append("[C] \n");
        textToPrint.append("[C] \n");

        if (openCashBox) {
          printer.printFormattedTextAndOpenCashBox(textToPrint.toString(), (float) mmFeedPaper); // This cuts as well.
        } else if (cutPaper) {
          printer.printFormattedTextAndCut(textToPrint.toString(), (float) mmFeedPaper);
        } else {
          printer.printFormattedText(textToPrint.toString(), (float) mmFeedPaper);
        }
      }

      if (usePrinterCommands) {
        printerCommands.disconnect();
      } else {
        printer.disconnectPrinter();
      }

      this.jsPromise.resolve(true);

    } catch (EscPosConnectionException e) {
      this.jsPromise.reject("Broken connection", e.getMessage());
    } catch (Exception e) {
      this.jsPromise.reject("ERROR", e.getMessage());
    }
  }

  private BluetoothDevice getBluetoothDeviceWithMacAddress(String macAddress) {
    for (BluetoothDevice device : this.btDevicesList) {
      if (device.getAddress().contentEquals(macAddress)) {
        return device;
      }
    }
    return null;
  }

  private static WritableMap convertJsonToMap(JSONObject jsonObject) throws JSONException {
    WritableMap map = new WritableNativeMap();

    Iterator<String> iterator = jsonObject.keys();
    while (iterator.hasNext()) {
      String key = iterator.next();
      Object value = jsonObject.get(key);
      if (value instanceof JSONObject) {
        map.putMap(key, convertJsonToMap((JSONObject) value));
      } else if (value instanceof Boolean) {
        map.putBoolean(key, (Boolean) value);
      } else if (value instanceof Integer) {
        map.putInt(key, (Integer) value);
      } else if (value instanceof Double) {
        map.putDouble(key, (Double) value);
      } else if (value instanceof String) {
        map.putString(key, (String) value);
      } else {
        map.putString(key, value.toString());
      }
    }
    return map;
  }

  @ReactMethod
  public void printBase64Image(
      String connectionMode,

      String ipAddress,
      double port,

      String macAddress,

      double timeoutMs,

      String base64Image,

      boolean autoCut,
      boolean openCashBox,
      double mmFeedPaper,

      double printerDpi,
      double printerWidthMM,
      double printerNbrCharactersPerLine,

      Promise promise) {

    final long start = System.nanoTime();

    FastDeviceConnection conn = null;

    try {
      PrinterSettings settings = null;

      if ("TCP".equals(connectionMode)) {

        settings = new PrinterSettings(ipAddress, (int) port);

      } else if ("BLUETOOTH".equals(connectionMode)) {

        settings = new PrinterSettings(macAddress);

      } else {

        throw new Exception("Invalid connection mode");
      }

      settings.setTimeoutMs((int) timeoutMs);

      settings.setPrinterDpi((int) printerDpi);
      settings.setPrinterWidthMm((int) printerWidthMM);
      settings.setPrinterCharPerLine((int) printerNbrCharactersPerLine);

      settings.setPrintMode(PrinterSettings.PrintMode.RASTER_GSV0);
      settings.setDitherMode(PrinterSettings.DitherMode.ATKINSON);

      settings.setChunkHeight(256);

      settings.setPacketSize(1024);

      settings.setPacketMicroDelayMs(2);

      settings.setUseQueueForWrites(true);

      if ("TCP".equals(connectionMode)) {

        conn = new FastTcpConnection(
            settings.getIpAddress(),
            settings.getIpPort(),
            settings.getTimeoutMs(),
            settings.getPacketSize(),
            settings.getUseQueueForWrites(),
            settings.getPacketMicroDelayMs());

      } else if ("BLUETOOTH".equals(connectionMode)) {

        BluetoothConnectionManager connectionManager = new BluetoothConnectionManager(
            getCurrentActivity(),
            settings.getMacAddress());

        if (!connectionManager.hasBluetoothPermissions()) {

          connectionManager.requestBluetoothPermissions();

          throw new Exception("Bluetooth permissions not granted");
        }

        conn = new FastBluetoothConnection(
            settings.getMacAddress(),
            settings.getPacketSize(),
            settings.getUseQueueForWrites(),
            settings.getPacketMicroDelayMs());
      }

      conn.connect();

      EscPosImagePrinter printer = new EscPosImagePrinter(settings, conn);

      boolean status = printer.printBase64Image(base64Image, autoCut, openCashBox, 4);

      promise.resolve(status);

    } catch (Exception e) {

      Log.e(TAG, "Encountered exception: " + e.getMessage(), e);

      promise.reject("ThermalPrinterModule.print: encountered exception", e.getMessage(), e);

    } finally {

      if (conn != null) {
        conn.close();
      }

      Log.i(TAG, "printBase64Image took " + ((System.nanoTime() - start) / 1_000_000) + " ms");
    }
  }

}
