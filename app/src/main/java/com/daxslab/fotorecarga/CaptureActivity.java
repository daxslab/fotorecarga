/*
 * Copyright (C) 2008 ZXing authors
 * Copyright 2011 Robert Theis
 * Copyright 2015 DaxsLab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.daxslab.fotorecarga;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.daxslab.fotorecarga.camera.CameraManager;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.Iconify;
import com.joanzapata.iconify.fonts.FontAwesomeModule;

import java.io.File;
import java.io.IOException;


/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the text correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing/
 */
public final class CaptureActivity extends AppCompatActivity implements SurfaceHolder.Callback {

  private static final String TAG = CaptureActivity.class.getSimpleName();

  // Note: These constants will be overridden by any default values defined in preferences.xml.

  /** ISO 639-3 language code indicating the default recognition language. */
  public static final String DEFAULT_SOURCE_LANGUAGE_CODE = "eng";

  /** Flag to display the real-time recognition results at the top of the scanning screen. */
  private static final boolean CONTINUOUS_DISPLAY_RECOGNIZED_TEXT = true;

  // Context menu  
//  private static final int ABOUT_ID = Menu.FIRST;
//
//  private static final int APP_PERMISSIONS_CAMERA = 123;
//  private static final int APP_PERMISSIONS_CALL_PHONE = 456;

  private CameraManager cameraManager;
  private CaptureActivityHandler handler;
  private ViewfinderView viewfinderView;
  private SurfaceView surfaceView;
  private SurfaceHolder surfaceHolder;
  private TextView ocrResultView;
  private FloatingActionButton aboutButton;
  private TextView imeiTextView;
  private View cameraButtonView;
  private View resultView;
  private View progressView;
  private OcrResult lastResult;
  private Bitmap lastBitmap;
  private boolean hasSurface;
  //  private BeepManager beepManager;
  private TessBaseAPI baseApi; // Java interface for the Tesseract OCR engine
  private String sourceLanguageCodeOcr = "eng"; // ISO 639-3 language code
  private int pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD;
  private int ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;
  private boolean isContinuousModeActive = true; // Whether we are doing OCR in continuous mode
  private SharedPreferences prefs;
  private OnSharedPreferenceChangeListener listener;
  private ProgressDialog dialog; // for initOcr - language download & unzip
  private ProgressDialog indeterminateDialog; // also for initOcr - init OCR engine
  private boolean isEngineReady;
  private boolean isPaused;
  private static boolean isFirstLaunch; // True if this is the first time the app is being run


  Handler getHandler() {
    return handler;
  }

  TessBaseAPI getBaseApi() {
    return baseApi;
  }

  CameraManager getCameraManager() {
    return cameraManager;
  }

  @Override
  public void onCreate(Bundle icicle) {
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    super.onCreate(icicle);
    Iconify.with(new FontAwesomeModule());
    checkFirstLaunch();

    if (isFirstLaunch) {
      setDefaultPreferences();
    }

    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setContentView(R.layout.capture);
    viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
    resultView = findViewById(R.id.result_view);

    aboutButton = (FloatingActionButton) findViewById(R.id.fab_button_about);
    aboutButton.setImageDrawable(new IconDrawable(this, "fa-info-circle").sizeDp(24));
    aboutButton.setOnClickListener(onAboutButtonClick(this));

    handler = null;
    lastResult = null;
    hasSurface = false;

    progressView = (View) findViewById(R.id.indeterminate_progress_indicator_view);

    cameraManager = new CameraManager(getApplication());
    viewfinderView.setCameraManager(cameraManager);

    isEngineReady = false;
    // TODO: new api call permissions
//    // check call permissions (for new apis)
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//      int callPermissionCheck = this.checkSelfPermission(Manifest.permission.CALL_PHONE);
//      if (callPermissionCheck == PackageManager.PERMISSION_DENIED) {
//        this.requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, APP_PERMISSIONS_CALL_PHONE);
//      }
//    }

  }

  @Override
  protected void onResume() {
    super.onResume();
    resetStatusView();


    retrievePreferences();

    // Set up the camera preview surface.
    surfaceView = (SurfaceView) findViewById(R.id.preview_view);
    surfaceHolder = surfaceView.getHolder();
    if (!hasSurface) {
      surfaceHolder.addCallback(this);
      surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    int previousOcrEngineMode = ocrEngineMode;

    // Do OCR engine initialization, if necessary
    boolean doNewInit = (baseApi == null) || ocrEngineMode != previousOcrEngineMode;
    Log.d(TAG, "new init?: "+doNewInit);
    if (doNewInit) {
      // Initialize the OCR engine
      File storageDirectory = getStorageDirectory();
      if (storageDirectory != null) {
        initOcrEngine(storageDirectory, sourceLanguageCodeOcr);
        Log.d(TAG, "Initialized OCR engine");
      }
    } else {
      // We already have the engine initialized, so just start the camera.
      resumeOCR();
    }

    // TODO: new api camera permissions
//    // check camera permissions (for new apis)
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//      Log.d(TAG, "is neew permissions api");
//      int cameraPermissionCheck = this.checkSelfPermission(Manifest.permission.CAMERA);
//      Log.d(TAG, "camera permission: "+cameraPermissionCheck);
//      if (cameraPermissionCheck == PackageManager.PERMISSION_DENIED) {
//        this.requestPermissions(new String[]{Manifest.permission.CAMERA}, APP_PERMISSIONS_CAMERA);
//      } else {
//        handleOCR();
//      }
//
//    } else {
//      handleOCR();
//    }

  }

//  @Override
//  public void onRequestPermissionsResult(int requestCode,
//                                         String permissions[], int[] grantResults) {
//    switch (requestCode) {
//      case APP_PERMISSIONS_CAMERA: {
//        Log.d(TAG, "APP_PERMISSIONS_CAMERA case");
//        // If request is cancelled, the result arrays are empty.
//        if (grantResults.length > 0
//                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//          Log.d(TAG, "APP_PERMISSIONS_CAMERA granted");
//          handleOCR();
//
//        } else {
//          Context context = getApplicationContext();
//          CharSequence text = "Error de permisos";
//          int duration = Toast.LENGTH_SHORT;
//          Toast toast = Toast.makeText(context, text, duration);
//          toast.show();
//        }
//        return;
//      }
//
//    }
//  }

//  public void handleOCR() {
//    Log.d(TAG, "handleOCR()");
//    int previousOcrEngineMode = ocrEngineMode;
//
//    // Do OCR engine initialization, if necessary
//    boolean doNewInit = (baseApi == null) || ocrEngineMode != previousOcrEngineMode;
//    Log.d(TAG, "new init?: "+doNewInit);
//    if (doNewInit) {
//      // Initialize the OCR engine
//      File storageDirectory = getStorageDirectory();
//      if (storageDirectory != null) {
//        initOcrEngine(storageDirectory, sourceLanguageCodeOcr);
//        Log.d(TAG, "Initialized OCR engine");
//      }
//    } else {
//      // We already have the engine initialized, so just start the camera.
//      resumeOCR();
//    }
//  }


  /**
   * Method to start or restart recognition after the OCR engine has been initialized,
   * or after the app regains focus. Sets state related settings and OCR engine parameters,
   * and requests camera initialization.
   */
  void resumeOCR() {
    Log.d(TAG, "resumeOCR()");

    // This method is called when Tesseract has already been successfully initialized, so set 
    // isEngineReady = true here.
    isEngineReady = true;

    isPaused = false;

    if (handler != null) {
      handler.resetState();
    }
    if (baseApi != null) {
      baseApi.setPageSegMode(pageSegmentationMode);
    }

    if (hasSurface) {
      // The activity was paused but not stopped, so the surface still exists. Therefore
      // surfaceCreated() won't be called, so init the camera here.
      Log.d(TAG, "initializing camera");
      initCamera(surfaceHolder);
    }
  }


  /** Called to resume recognition after translation in continuous mode. */
  @SuppressWarnings("unused")
  void resumeContinuousDecoding() {
    isPaused = false;
    resetStatusView();
    DecodeHandler.resetDecodeState();
    handler.resetState();
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    Log.d(TAG, "surfaceCreated()");

    if (holder == null) {
      Log.e(TAG, "surfaceCreated gave us a null surface");
    }

    // Only initialize the camera if the OCR engine is ready to go.
    if (!hasSurface && isEngineReady) {
      Log.d(TAG, "surfaceCreated(): calling initCamera()...");
      initCamera(holder);
    }
    hasSurface = true;
  }

  /** Initializes the camera and starts the handler to begin previewing. */
  private void initCamera(SurfaceHolder surfaceHolder) {
    Log.d(TAG, "initCamera()");
    if (surfaceHolder == null) {
      throw new IllegalStateException("No SurfaceHolder provided");
    }
    try {

      // Open and initialize the camera
      cameraManager.openDriver(surfaceHolder);

      // Creating the handler starts the preview, which can also throw a RuntimeException.
      handler = new CaptureActivityHandler(this, cameraManager, isContinuousModeActive);

    } catch (IOException ioe) {
      ioe.printStackTrace();
      showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
    } catch (RuntimeException e) {
      // Barcode Scanner has seen crashes in the wild of this variety:
      // java.?lang.?RuntimeException: Fail to connect to camera service
      e.printStackTrace();
      showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
    }
  }

  @Override
  protected void onPause() {
    if (handler != null) {
      handler.quitSynchronously();
    }

    // Stop using the camera, to avoid conflicting with other camera-based apps
    cameraManager.closeDriver();

    if (!hasSurface) {
      SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
      SurfaceHolder surfaceHolder = surfaceView.getHolder();
      surfaceHolder.removeCallback(this);
    }
    super.onPause();
  }

  void stopHandler() {
    if (handler != null) {
      handler.stop();
    }
  }

  @Override
  protected void onDestroy() {
    if (baseApi != null) {
      baseApi.end();
    }
    super.onDestroy();
  }


  public View.OnClickListener onAboutButtonClick(final Activity activity) {
    View.OnClickListener listener = new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        Intent intent;
        intent = new Intent(activity, HelpActivity.class);
        intent.putExtra(HelpActivity.REQUESTED_PAGE_KEY, HelpActivity.ABOUT_PAGE);
        startActivity(intent);
      }
    };

    return listener;
  }

  public void surfaceDestroyed(SurfaceHolder holder) {
    hasSurface = false;
  }

  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
  }

  /** Finds the proper location on the SD card where we can save files. */
  private File getStorageDirectory() {

    return getFilesDir();
  }

  /**
   * Requests initialization of the OCR engine with the given parameters.
   *
   * @param storageRoot Path to location of the tessdata directory to use
   * @param languageCode Three-letter ISO 639-3 language code for OCR
   */
  private void initOcrEngine(File storageRoot, String languageCode) {
    isEngineReady = false;

    // Set up the dialog box for the thermometer-style download progress indicator
    if (dialog != null) {
      dialog.dismiss();
    }
    dialog = new ProgressDialog(this);

    // set the ocrEngineMode to Tesseract
    ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;

    // Display the name of the OCR engine we're initializing in the indeterminate progress dialog box
    indeterminateDialog = new ProgressDialog(this);
    indeterminateDialog.setTitle(getString(R.string.ocr_engine_init_dialog_wellcome));
    indeterminateDialog.setMessage(getString(R.string.ocr_engine_init_dialog_body));
    indeterminateDialog.setCancelable(false);
    indeterminateDialog.show();

    if (handler != null) {
      handler.quitSynchronously();
    }

    // Start AsyncTask to install language data and init OCR
    baseApi = new TessBaseAPI();
    new OcrInitAsyncTask(this, baseApi, dialog, indeterminateDialog, languageCode, ocrEngineMode)
            .execute(storageRoot.toString());
  }

  /**
   * Remove not numeric chars from ocrResult text, if there is no numbers 
   * or the length is not 16 (the etecsa code length) ocrResult text = null. 
   */
  OcrResult setOnlyNumbers(OcrResult ocrResult) {
    String codeString = ocrResult.getText();

    String codeNumber = "";
    for (int i = 0; i < codeString.length(); i++) {
      char codeChar = codeString.charAt(i);
      if (codeChar == '0' || codeChar == '1' || codeChar == '2' || codeChar == '3' ||
              codeChar == '4' || codeChar == '5' || codeChar == '6' || codeChar == '7' ||
              codeChar == '8' || codeChar == '9') {
        codeNumber += codeChar;
      }
    }
    if (codeNumber.equals("") || codeNumber.length() != 16)
      ocrResult.setText(null);
    else
      ocrResult.setText(codeNumber);

    return ocrResult;
  }

  /**
   * Displays information relating to the results of a successful real-time OCR request.
   *
   * @param ocrResult Object representing successful OCR results
   */
  void handleOcrContinuousDecode(OcrResult ocrResult) {
    ocrResult = setOnlyNumbers(ocrResult);
    lastResult = ocrResult;

    // Send an OcrResultText object to the ViewfinderView for text rendering
    viewfinderView.addResultText(new OcrResultText(ocrResult.getText(),
            ocrResult.getWordConfidences(),
            ocrResult.getMeanConfidence(),
            ocrResult.getBitmapDimensions(),
            ocrResult.getRegionBoundingBoxes(),
            ocrResult.getTextlineBoundingBoxes(),
            ocrResult.getStripBoundingBoxes(),
            ocrResult.getWordBoundingBoxes(),
            ocrResult.getCharacterBoundingBoxes()));

    ocrResult.getMeanConfidence();

    String code = lastResult.getText();

    if (CONTINUOUS_DISPLAY_RECOGNIZED_TEXT && code.length() == 16) {
      String encodedHash = Uri.encode("#");

      // TODO: new api call permissions check
//      if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
//        Context context = getApplicationContext();
//        CharSequence text = "No se han otorgado permisos para realizar la llamada";
//        int duration = Toast.LENGTH_SHORT;
//        Toast toast = Toast.makeText(context, text, duration);
//        toast.show();
//        return;
//      }
      startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:*662*" + lastResult.getText() + encodedHash)));
//      startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:*222" + encodedHash)));

    }
  }
  
  /**
   * Version of handleOcrContinuousDecode for failed OCR requests. Displays a failure message.
   * 
   * @param obj Metadata for the failed OCR request.
   */
  void handleOcrContinuousDecode(OcrResultFailure obj) {
    lastResult = null;
    viewfinderView.removeResultText();    
  }
  

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
    super.onCreateContextMenu(menu, v, menuInfo);    
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {    
    return super.onContextItemSelected(item);
  }

  /**
   * Resets view elements.
   */
  private void resetStatusView() {
    resultView.setVisibility(View.GONE);
    viewfinderView.setVisibility(View.VISIBLE);        
    lastResult = null;
    viewfinderView.removeResultText();
  }

  /** Request the viewfinder to be invalidated. */
  void drawViewfinder() {
    viewfinderView.drawViewfinder();
  }
  

  /**
   * We want the help screen to be shown automatically the first time a new version of the app is
   * run. The easiest way to do this is to check android:versionCode from the manifest, and compare
   * it to a value stored as a preference.
   */
  private boolean checkFirstLaunch() {
    try {
      PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
      int currentVersion = info.versionCode;
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);     
      
    } catch (PackageManager.NameNotFoundException e) {
      Log.w(TAG, e);
    }
    return false;
  }
  

  /**
   * Gets values from shared preferences and sets the corresponding data members in this activity.
   */
  private void retrievePreferences() {
      prefs = PreferenceManager.getDefaultSharedPreferences(this);
      
      // Retrieve from preferences, and set in this Activity, the language preferences
      PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
      isContinuousModeActive = true;
      
      prefs.registerOnSharedPreferenceChangeListener(listener);
      
//      beepManager.updatePrefs();
  }
  
  /**
   * Sets default values for preferences. To be called the first time this app is run.
   */
  private void setDefaultPreferences() {
    
  }
  
  void displayProgressDialog() {
    // Set up the indeterminate progress dialog box
    indeterminateDialog = new ProgressDialog(this);
    indeterminateDialog.setTitle(getString(R.string.progres_dialog_title));
    indeterminateDialog.setMessage(getString(R.string.progres_dialog_message));
    indeterminateDialog.setCancelable(false);
    indeterminateDialog.show();
  }
  
  ProgressDialog getProgressDialog() {
    return indeterminateDialog;
  }
  
  /**
   * Displays an error message dialog box to the user on the UI thread.
   * 
   * @param title The title for the dialog box
   * @param message The error message to be displayed
   */
  void showErrorMessage(String title, String message) {
	  new AlertDialog.Builder(this)
	    .setTitle(title)
	    .setMessage(message)
	    .setOnCancelListener(new FinishListener(this))
	    .setPositiveButton( "Done", new FinishListener(this))
	    .show();
  }
}
