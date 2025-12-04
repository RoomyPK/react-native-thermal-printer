package com.reactnativethermalprinter;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.dantsu.escposprinter.EscPosPrinter;
import com.dantsu.escposprinter.EscPosPrinterCommands;
import com.dantsu.escposprinter.connection.DeviceConnection;
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
import com.facebook.react.module.annotations.ReactModule;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection;

import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

@ReactModule(name = ThermalPrinterModule.NAME)
public class ThermalPrinterModule extends ReactContextBaseJavaModule {
  private static final String LOG_TAG = "RN_Thermal_Printer";
  public static final String NAME = "ThermalPrinterModule";
  private Promise jsPromise;
  private ArrayList<BluetoothConnection> btDevicesList = new ArrayList();

  public ThermalPrinterModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void printTcp(String ipAddress, double port, String payload, boolean autoCut, boolean openCashbox, double mmFeedPaper, double printerDpi, double printerWidthMM, double printerNbrCharactersPerLine, double timeout, boolean payloadIsBase64EncodedImage, boolean usePrinterCommands, boolean useEscAsteriskCommand, Promise promise) {
//
//        05-05-2021
//        https://reactnative.dev/docs/native-modules-android
//        The following types are currently supported but will not be supported in TurboModules. Please avoid using them:
//
//        Integer -> ?number
//        int -> number
//        Float -> ?number
//        float -> number
//
    this.jsPromise = promise;
    try {
      TcpConnection connection = new TcpConnection(ipAddress, (int) port, (int) timeout);
      if (payloadIsBase64EncodedImage) {
        this.printBase64EncodedImage(connection, payload, printerDpi, printerWidthMM, printerNbrCharactersPerLine, mmFeedPaper, autoCut, openCashbox, usePrinterCommands, useEscAsteriskCommand);
      } else {
        this.printIt(connection, payload, autoCut, openCashbox, mmFeedPaper, printerDpi, printerWidthMM, printerNbrCharactersPerLine);
      }
    } catch (Exception e) {
      this.jsPromise.reject("Connection Error", e.getMessage());
    }
  }

  @ReactMethod
  public void printBluetooth(String macAddress, String payload, boolean autoCut, boolean openCashbox, double mmFeedPaper, double printerDpi, double printerWidthMM, double printerNbrCharactersPerLine, boolean payloadIsBase64EncodedImage, boolean usePrinterCommands, boolean useEscAsteriskCommand, Promise promise) {
    this.jsPromise = promise;
    BluetoothConnection btPrinter;

    if (TextUtils.isEmpty(macAddress)) {
      btPrinter = BluetoothPrintersConnections.selectFirstPaired();
    } else {
      btPrinter = getBluetoothConnectionWithMacAddress(macAddress);
    }

    if (btPrinter == null) {
      this.jsPromise.reject("Connection Error", "Bluetooth Device Not Found");
    }

    if (ContextCompat.checkSelfPermission(getCurrentActivity(), Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(getCurrentActivity(), new String[]{Manifest.permission.BLUETOOTH}, 1);
    } else {
      try {
        if (payloadIsBase64EncodedImage) {
          this.printBase64EncodedImage(btPrinter.connect(), payload, printerDpi, printerWidthMM, printerNbrCharactersPerLine, mmFeedPaper, autoCut, openCashbox, usePrinterCommands, useEscAsteriskCommand);
        } else {
          this.printIt(btPrinter.connect(), payload, autoCut, openCashbox, mmFeedPaper, printerDpi, printerWidthMM, printerNbrCharactersPerLine);
        }
      } catch (Exception e) {
        this.jsPromise.reject("Connection Error", e.getMessage());
      }
    }
  }

  @ReactMethod
  public void getBluetoothDeviceList(Promise promise) {
    this.jsPromise = promise;
    if (ContextCompat.checkSelfPermission(getCurrentActivity(), Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(getCurrentActivity(), new String[]{Manifest.permission.BLUETOOTH}, 1);
    } else {
      try {
        Set<BluetoothDevice> pairedDevices = BluetoothAdapter.getDefaultAdapter().getBondedDevices();
        WritableArray rnArray = new WritableNativeArray();
        if (pairedDevices.size() > 0) {
          int index = 0;
          for (BluetoothDevice device : pairedDevices) {
            btDevicesList.add(new BluetoothConnection(device));
            JSONObject jsonObj = new JSONObject();

            String deviceName = device.getName();
            String macAddress = device.getAddress();

            jsonObj.put("deviceName", deviceName);
            jsonObj.put("macAddress", macAddress);
            WritableMap wmap = convertJsonToMap(jsonObj);
            rnArray.pushMap(wmap);
          }
        }
        jsPromise.resolve(rnArray);


      } catch (Exception e) {
        this.jsPromise.reject("Bluetooth Error", e.getMessage());
      }
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

  private void printIt(DeviceConnection printerConnection, String payload, boolean autoCut, boolean openCashbox, double mmFeedPaper, double printerDpi, double printerWidthMM, double printerNbrCharactersPerLine) {
    try {
      EscPosPrinter printer = new EscPosPrinter(printerConnection, (int) printerDpi, (float) printerWidthMM, (int) printerNbrCharactersPerLine);
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

  private void printBase64EncodedImage(DeviceConnection printerConnection, String base64EncodedImage, double printerDpi, double printerWidthMM, double printerNbrCharactersPerLine, double mmFeedPaper, boolean cutPaper, boolean openCashBox, boolean usePrinterCommands, boolean useEscAsteriskCommand) {
    try {
      final String base64EncodedImageWithoutPrefix = base64EncodedImage.substring(base64EncodedImage.indexOf(",")  + 1);
      final byte[] decodedBytes = Base64.decode(base64EncodedImageWithoutPrefix, Base64.DEFAULT);

      Bitmap originalBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

      final int targetWidth = (int) Math.round(printerWidthMM / 25.4f *  printerDpi);

      Bitmap bitmapToPrint = null;

      if (originalBitmap.getWidth() == targetWidth) {

        Log.i("ThermalPrinterModule.printBase64EncodedImage",
          "Bitmap width matches target width; no need to scale; targetWidth: " + targetWidth);

        bitmapToPrint = originalBitmap;

      } else {

        final int targetHeight = Math.round(((float) originalBitmap.getHeight()) * ((float) targetWidth) / ((float) originalBitmap.getWidth()));

        Log.i("ThermalPrinterModule.printBase64EncodedImage",
          "Bitmap width does not match target width; scaling; bitmap dimensions: " + originalBitmap.getWidth() + " x "
            + originalBitmap.getHeight() + " target dimensions: " + targetWidth + " x " + targetHeight);

        bitmapToPrint = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, false);
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

        printer = new EscPosPrinter(printerConnection, (int) printerDpi, (float) printerWidthMM, (int) printerNbrCharactersPerLine);

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

          textToPrint.append("[L]<img>" + PrinterTextParserImg.bitmapToHexadecimalString(printer, bitmap, false) + "</img>\n");
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
          printer.printFormattedTextAndOpenCashBox(textToPrint.toString(), (float) mmFeedPaper);  // This cuts as well.
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


  private BluetoothConnection getBluetoothConnectionWithMacAddress(String macAddress) {
    for (BluetoothConnection device : btDevicesList) {
      if (device.getDevice().getAddress().contentEquals(macAddress))
        return device;
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
}

