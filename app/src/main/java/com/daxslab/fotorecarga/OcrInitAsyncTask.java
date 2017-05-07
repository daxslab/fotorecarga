/*
 * Copyright 2011 Robert Theis
 * Copyright 2015 DaxSlab
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.googlecode.tesseract.android.TessBaseAPI;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.util.Log;

/**
 * Installs the language data required for OCR, and initializes the OCR engine using a background 
 * thread.
 */
final class OcrInitAsyncTask extends AsyncTask<String, String, Boolean> {
  private static final String TAG = OcrInitAsyncTask.class.getSimpleName();

  private CaptureActivity activity;
  private Context context;
  private TessBaseAPI baseApi;
  private ProgressDialog dialog;
  private ProgressDialog indeterminateDialog;
  private final String languageCode;
  private int ocrEngineMode;

  /**
   * AsyncTask to asynchronously download data and initialize Tesseract.
   * 
   * @param activity
   *          The calling activity
   * @param baseApi
   *          API to the OCR engine
   * @param dialog
   *          Dialog box with thermometer progress indicator
   * @param indeterminateDialog
   *          Dialog box with indeterminate progress indicator
   * @param languageCode
   *          ISO 639-2 OCR language code
   * @param ocrEngineMode
   *          Whether to use Tesseract, Cube, or both
   */
  OcrInitAsyncTask(CaptureActivity activity, TessBaseAPI baseApi, ProgressDialog dialog, 
      ProgressDialog indeterminateDialog, String languageCode, int ocrEngineMode) {
    this.activity = activity;
    this.context = activity.getBaseContext();
    this.baseApi = baseApi;
    this.dialog = dialog;
    this.indeterminateDialog = indeterminateDialog;
    this.languageCode = languageCode;
    this.ocrEngineMode = ocrEngineMode;
  }

  @Override
  protected void onPreExecute() {
    super.onPreExecute();
    dialog.setTitle("Please wait");
    dialog.setMessage("Checking for data installation...");
    dialog.setIndeterminate(false);
    dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    dialog.setCancelable(false);
    dialog.show();
  }

  /**
   * In background thread, perform required setup, and request initialization of
   * the OCR engine.
   * 
   * @param params
   *          [0] Pathname for the directory for storing language data files to the SD card
   */
  protected Boolean doInBackground(String... params) {

    String destinationFilenameBase = languageCode + ".traineddata";

    // Check for, and create if necessary, folder to hold model data
    String destinationDirBase = params[0]; // The storage directory, minus the
                                           // "tessdata" subdirectory
    File tessdataDir = new File(destinationDirBase + File.separator + "tessdata");
    if (!tessdataDir.exists() && !tessdataDir.mkdirs()) {
      Log.e(TAG, "Couldn't make directory " + tessdataDir);
      return false;
    }

    // Create a reference to the file to save the download tessTrainFiledownloadFile = new File(tessdataDir, destinationFilenameBase);

    // Check if an incomplete download is present. If a *.download file is there, delete it and
    // any (possibly half-unzipped) Tesseract and Cube data files that may be there.
    File incomplete = new File(tessdataDir, destinationFilenameBase + ".download");
    File tesseractTestFile = new File(tessdataDir, languageCode + ".traineddata");
    if (incomplete.exists()) {
      incomplete.delete();
      if (tesseractTestFile.exists()) {
        tesseractTestFile.delete();
      }
    }

    // If language data files are not present, install them
    boolean installSuccess = false;
    if (!tesseractTestFile.exists()) {
      Log.d(TAG, "Language data for " + languageCode + " not found in " + tessdataDir.toString());

      // Check assets for language data to install.
      try {
        Log.d(TAG, "Checking for language data (" + destinationFilenameBase
            + ".zip) in application assets...");
        // Check for a file like "eng.traineddata.zip" or "tesseract-ocr-3.01.eng.tar.zip"
        installSuccess = installFromAssets(destinationFilenameBase + ".zip", tessdataDir, tesseractTestFile);
      } catch (IOException e) {
        Log.e(TAG, "IOException", e);
      } catch (Exception e) {
        Log.e(TAG, "Got exception", e);
      }

      if (!installSuccess) {
        // File was not packaged in assets, so download it
        Log.d(TAG, "Downloading " + destinationFilenameBase + ".gz...");

        installSuccess = true;
        if (!installSuccess) {
          Log.e(TAG, "Download failed");
          return false;
        }
      }

    } else {
      Log.d(TAG, "Language data for " + languageCode + " already installed in "
          + tessdataDir.toString());
      installSuccess = true;
    }

    // Dismiss the progress dialog box, revealing the indeterminate dialog box behind it
    try {
      dialog.dismiss();
    } catch (IllegalArgumentException e) {
      // Catch "View not attached to window manager" error, and continue
    }

    // Initializlang
    try {
      if (baseApi.init(destinationDirBase, languageCode, ocrEngineMode)) {
        return true;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return false;
  }



  /**
   * Install a file from application assets to device external storage.
   * 
   * @param sourceFilename
   *          File in assets to install
   * @param modelRoot
   *          Directory on SD card to install the file to
   * @param destinationFile
   *          File name for destination, excluding path
   * @return True if installZipFromAssets returns true
   * @throws IOException
   */
  private boolean installFromAssets(String sourceFilename, File modelRoot, File destinationFile) throws IOException {

    String[] paths = new String[] { modelRoot.getPath() };

    for (String path : paths) {
      File dir = new File(path);
      if (!dir.exists()) {
        if (!dir.mkdirs()) {
          Log.v(TAG, "ERROR: Creation of directory " + path + " on sdcard failed");
          return false;
        } else {
          Log.v(TAG, "Created directory " + path + " on sdcard");
        }
      }

    }

    // lang.traineddata file with the app (in assets folder)
    // You can get them at:
    // http://code.google.com/p/tesseract-ocr/downloads/list
    if (!destinationFile.exists()) {
      try {
          AssetManager assetManager = context.getAssets();
          InputStream in = assetManager.open("tessdata/" + languageCode + ".traineddata");

          OutputStream out = new FileOutputStream(destinationFile);

          // Transfer bytes from in to out
          byte[] buf = new byte[1024];
          int len;
          while ((len = in.read(buf)) > 0) {
              out.write(buf, 0, len);
          }
          in.close();
          out.close();

          Log.v(TAG, "Copied " + languageCode + " traineddata");
      } catch (IOException e) {
          Log.e(TAG, "Was unable to copy " + languageCode + " traineddata " + e.toString());
      }
    }
    return true;
  }




  /**
   * Update the dialog box with the latest incremental progress.
   * 
   * @param message
   *          [0] Text to be displayed
   * @param message
   *          [1] Numeric value for the progress
   */
  @Override
  protected void onProgressUpdate(String... message) {
    super.onProgressUpdate(message);
    int percentComplete = 0;

    percentComplete = Integer.parseInt(message[1]);
    dialog.setMessage(message[0]);
    dialog.setProgress(percentComplete);
    dialog.show();
  }

  @Override
  protected void onPostExecute(Boolean result) {
    super.onPostExecute(result);
    
    try {    	
      indeterminateDialog.dismiss();
    } catch (IllegalArgumentException e) {
      // Catch "View not attached to window manager" error, and continue
    }

    activity.resumeOCR();

  }
}