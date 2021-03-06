package hr.ivica.android.ocr;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.ClipboardManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import com.googlecode.tesseract.android.TessBaseAPI;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.LinkedList;
import java.util.List;

import hr.ivica.android.ocr.camera.CameraResource;
import hr.ivica.android.ocr.graphics.MatTransform;
import hr.ivica.android.ocr.graphics.ScaleSize;
import hr.ivica.android.ocr.ocr.DetectTextAsync;
import hr.ivica.android.ocr.ocr.Ocr;
import hr.ivica.android.ocr.ocr.OcrEngineInitAsync;
import hr.ivica.android.ocr.ocr.RecognizeTextAsync;
import hr.ivica.android.ocr.ocr.TesseractTrainingData;
import hr.ivica.android.ocr.util.OnErrorCallback;
import hr.ivica.android.ocr.util.OnSuccessCallback;

public final class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "MainActivity";
    private static final int CAMERA_PREVIEW_VIEW = 0;
    private static final int DETECTED_TEXT_VIEW = 1;
    private static final int RECOGNIZED_TEXT_VIEW = 2;
    private static final int MAX_CAPTURE_IMAGE_WIDTH = 2560;
    private static final int MAX_CAPTURE_IMAGE_HEIGHT = 1920;

    private FrameLayout mPreviewFrame;
    private SurfaceView mPreviewView;
    private CameraResource mCameraResource;
    private ViewFlipper mViewFlipper;
    private ImageView mImgPreview;
    private ProgressDialog mCtrlActivityIndicator;
    private Button mDetectTextButton;
    private Button mRecognizeTextButton;

    private OcrEngineInitAsync mOcrEngineInitTask;
    private List<AsyncTask> mStartedTasks = new LinkedList<>();
    private Ocr mOcrEngine;
    private Mat mOcrImage;
    private List<Rect> mTextRegions;
    private OnSuccessCallback<List<Rect>> mDetectTextOnSuccessCallback = new DetectTextOnSuccessCallback();
    private OnSuccessCallback<String> mRecognizeTextOnSuccessCallback = new RecognizeTextOnSuccessCallback();
    private OnErrorCallback mOnErrorCallback = new OnErrorCallback() {
        @Override
        public void execute(Throwable throwable, int stringId) {
            showAlertAndFinish(stringId);
        }
    };

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded successfully");
                }
                break;
                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }
    };

    static {
        System.loadLibrary("pngt");
        System.loadLibrary("lept");
        System.loadLibrary("tess");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (!isSdCardMounted()) {
            Log.e(TAG, "External storage is not mounted");
            showAlertAndFinish(R.string.sd_card_missing);
        }

        mCameraResource = new CameraResource(this);
        mPreviewView = new SurfaceView(this);
        mPreviewView.getHolder().addCallback(this);
        mPreviewView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        mPreviewFrame = (FrameLayout) findViewById(R.id.camera_preview);
        mImgPreview = (ImageView) findViewById(R.id.imgPreview);

        mDetectTextButton = (Button) findViewById(R.id.button_detect_text);

        assert mDetectTextButton != null;
        mDetectTextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDetectTextButton.setEnabled(false);
                mCameraResource.autoFocus(new OcrAutoFocusCallback());
            }
        });

        mRecognizeTextButton = (Button) findViewById(R.id.button_recognize_text);

        assert mRecognizeTextButton != null;
        mRecognizeTextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRecognizeTextButton.setEnabled(false);
                mCtrlActivityIndicator.setMessage(getString(R.string.progress_recognize_text));
                mCtrlActivityIndicator.show();

                RecognizeTextAsync.Param params = new RecognizeTextAsync.Param(mTextRegions, mOcrImage);
                RecognizeTextAsync task = new RecognizeTextAsync(mOcrEngine, mRecognizeTextOnSuccessCallback, mOnErrorCallback);
                task.execute(params);
                mStartedTasks.add(task);
            }
        });

        mViewFlipper = (ViewFlipper) findViewById(R.id.ViewFlipper01);

        mCtrlActivityIndicator = new ProgressDialog(MainActivity.this);
        mCtrlActivityIndicator.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        mCtrlActivityIndicator.setIndeterminate(true);
    }

    @Override
    public void onBackPressed(){
        // exit the activity if the user presses the back button
        // while the camera preview view is visible
        if (mViewFlipper.getDisplayedChild() == CAMERA_PREVIEW_VIEW) {
            super.onBackPressed();
            return;
        }

        // go back to the camera preview if the user presses the
        // back button while viewing the captured image
        if (mViewFlipper.getDisplayedChild() == DETECTED_TEXT_VIEW) {
            mDetectTextButton.setEnabled(true);
            mCameraResource.startPreview();
            BitmapDrawable drawable = (BitmapDrawable) mImgPreview.getDrawable();
            if (drawable != null) {
                drawable.getBitmap().recycle();
            }
        }

        if (mViewFlipper.getDisplayedChild() == RECOGNIZED_TEXT_VIEW) {
            mRecognizeTextButton.setEnabled(true);
        }

        mViewFlipper.showPrevious();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d(TAG, "onCreateOptionsMenu called");
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.menu_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case R.id.menu_about:
                return false;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart called");

        ocrEngineInitAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called");

        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, this, mLoaderCallback);
        mCameraResource.aquire();
        mPreviewFrame.addView(mPreviewView);
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();

        mPreviewFrame.removeView(mPreviewView);
        mCameraResource.release();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop called");

        for (AsyncTask task : mStartedTasks) {
            task.cancel(true);
        }

        if (mOcrImage != null) {
            mOcrImage.release();
        };
        mOcrEngine.end();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.d(TAG, "surfaceChanged - format: " + format + " width: " + w + " height: " + h);
        if (holder.getSurface() == null) {
            // previewFrame surface does not exist
            return;
        }

        mCameraResource.stopPreview();
        mCameraResource.setCameraDisplayOrientation();
        mCameraResource.setCameraParameters(w, h);

        // start previewFrame with new settings
        try {
           mCameraResource.setPreviewDisplay(holder);
           mCameraResource.startPreview();
        } catch (Exception e) {
            Log.e(TAG, "Error starting camera preview in surfaceChanged: " + e.getMessage(), e);
        }
    }

    private void showAlertAndFinish(int msgResourceId) {
        Intent intent = new Intent(this, AlertActivity.class);
        intent.putExtra("alertText", getResources().getText(msgResourceId).toString());
        startActivity(intent);
        finish();
    }

    private boolean isSdCardMounted() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }

    private void ocrEngineInitAsync() {
        TesseractTrainingData trainingData = new TesseractTrainingData(getAssets());

        mOcrEngine = new Ocr(new TessBaseAPI());
        mOcrEngineInitTask = new OcrEngineInitAsync(trainingData, mOcrEngine, mOnErrorCallback);
        mOcrEngineInitTask.execute();
    }

    private class OcrAutoFocusCallback implements Camera.AutoFocusCallback {

        @Override
        public void onAutoFocus(boolean success, Camera camera) {
            if (success) {
                mCameraResource.takePicture(new OcrPictureCallback());
            } else {
                mDetectTextButton.setEnabled(true);
            }
        }
    }

    private class OcrPictureCallback implements Camera.PictureCallback {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            mCtrlActivityIndicator.setMessage(getString(R.string.progress_detect_text));
            mCtrlActivityIndicator.show();

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            int inSampleSize = new ScaleSize().calculate(options.outWidth, options.outHeight, MAX_CAPTURE_IMAGE_WIDTH, MAX_CAPTURE_IMAGE_HEIGHT);

            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;

            Bitmap bmp = null;
            try {
                bmp = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                Log.d(TAG, "picture taken - width: " + bmp.getWidth() + ", height: " + bmp.getHeight());

                mOcrImage = new Mat();
                Utils.bitmapToMat(bmp, mOcrImage);
            } finally {
                if (bmp != null) bmp.recycle();
            }

            new MatTransform(mOcrImage)
                .rotate(mCameraResource.getCurrentRotation());

            DetectTextAsync task = new DetectTextAsync(mOcrEngine, mDetectTextOnSuccessCallback, mOnErrorCallback);
            task.execute(mOcrImage);
            mStartedTasks.add(task);
       }
    }

    private class DetectTextOnSuccessCallback implements OnSuccessCallback<List<Rect>> {

        @Override
        public void execute(List<Rect> rects) {
            mTextRegions = rects;
            Mat imageWithText = null;

            try {
                imageWithText = mOcrImage.clone();

                for (Rect rect : mTextRegions) {
                    Point p1 = new Point(rect.x, rect.y);
                    Point p2 = new Point(rect.x + rect.width, rect.y + rect.height);
                    Imgproc.rectangle(imageWithText, p1, p2, new Scalar(255, 255, 255, 255), 10);
                }

                Bitmap result = Bitmap.createBitmap(imageWithText.width(), imageWithText.height(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(imageWithText, result);

                mCtrlActivityIndicator.dismiss();
                mImgPreview.setImageBitmap(result);
                mViewFlipper.setDisplayedChild(DETECTED_TEXT_VIEW);
            } finally {
                if (imageWithText != null) imageWithText.release();
            }
        }
    }

    private class RecognizeTextOnSuccessCallback implements OnSuccessCallback<String> {

        @Override
        public void execute(String result) {
            mCtrlActivityIndicator.dismiss();

            final TextView textView = (TextView) findViewById(R.id.recognized_text_view);
            textView.setText(result);

            if (Build.VERSION.SDK_INT < 11) {
                textView.setClickable(true);
                textView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        ClipboardManager cm = (ClipboardManager) MainActivity.this.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setText(textView.getText());
                        Toast.makeText(MainActivity.this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            mViewFlipper.setDisplayedChild(RECOGNIZED_TEXT_VIEW);
        }
    }
}

