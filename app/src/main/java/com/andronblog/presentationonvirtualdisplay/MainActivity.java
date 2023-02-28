package com.andronblog.presentationonvirtualdisplay;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRouter;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private static final int PERMISSION_CODE = 1;

    private int mWidth;
    private int mHeight;
    private DisplayMetrics mMetrics = new DisplayMetrics();

    private DisplayManager mDisplayManager;
    private VirtualDisplay mVirtualDisplay;

    private int mResultCode;
    private Intent mResultData;

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mProjection;
    private MediaProjection.Callback mProjectionCallback;

    private Surface mSurface;
    private Button mButtonCreate;
    private Button mButtonDestroy;

    private ImageReader mImageReader;

    private Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mSurface = surfaceView.getHolder().getSurface();

        final HandlerThread handlerThread = new HandlerThread("virtual display dump");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());

        // Obtain display metrics of current display to know its density (dpi)
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getMetrics(mMetrics);
        // Initialize resolution of virtual display in pixels to show
        // the surface view on full screen
        mWidth = surfaceView.getLayoutParams().width;
        mHeight = surfaceView.getLayoutParams().height;

        mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        mButtonCreate = (Button) findViewById(R.id.btn_create_virtual_display);
        mButtonCreate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScreenCapture();
            }
        });

        mButtonDestroy = (Button) findViewById(R.id.btn_destroy_virtual_display);
        mButtonDestroy.setEnabled(false);
        mButtonDestroy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopScreenCapture();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        destroyVirtualDisplay();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        if (mProjection != null) {
            Log.i(TAG, "Stop media projection");
            mProjection.unregisterCallback(mProjectionCallback);
            mProjection.stop();
            mProjection = null;
        }
    }

    private void startScreenCapture() {
        if (mProjection != null) {
            // start virtual display
            Log.i(TAG, "The media projection is already gotten");
            createVirtualDisplay();
        } else if (mResultCode != 0 && mResultData != null) {
            // get media projection
            Log.i(TAG, "Get media projection with the existing permission");
            mProjection = getProjection();
            createVirtualDisplay();
        } else {
            Log.i(TAG, "Request the permission for media projection");
            startActivityForResult(mProjectionManager.createScreenCaptureIntent(), PERMISSION_CODE);
        }
    }

    private void stopScreenCapture() {
        destroyVirtualDisplay();

        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
        }
        if (mBitmap != null) {
            mBitmap = null;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mResultCode = resultCode;
        mResultData = data;
        if (requestCode != PERMISSION_CODE) {
            Toast.makeText(this, "Unknown request code: " + requestCode, Toast.LENGTH_SHORT).show();
            return;
        }
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "Screen Cast Permission Denied", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "Get media projection with the new permission");
        mProjection = getProjection();
        createVirtualDisplay();
    }

    private MediaProjection getProjection() {
        MediaProjection projection = mProjectionManager.getMediaProjection(mResultCode, mResultData);
        // Add a callback to be informed if the projection
        // will be stopped from the status bar.
        mProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection.Callback onStop obj:" + toString());
                destroyVirtualDisplay();
                mProjection = null;
            }
        };
        projection.registerCallback(mProjectionCallback, null);
        return projection;
    }

    private void createVirtualDisplay() {
        if (mProjection != null && mVirtualDisplay == null) {

            mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
            mImageReader.setOnImageAvailableListener(imageReader -> {
                final Image image = imageReader.acquireLatestImage();
                writeImage(image);
                image.close();
            }, mHandler);

            Log.d(TAG, "createVirtualDisplay WxH (px): " + mWidth + "x" + mHeight +
                    ", dpi: " + mMetrics.densityDpi);
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
            //flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
            mVirtualDisplay = mProjection.createVirtualDisplay("MyVirtualDisplay",
                    mWidth, mHeight, mMetrics.densityDpi, flags, mImageReader.getSurface(),
                    null /*Callbacks*/, null /*Handler*/);
            mButtonCreate.setEnabled(false);
            mButtonDestroy.setEnabled(true);
        }
    }

    private long mCount;

    private Bitmap mBitmap;

    // ref: https://myenv.net/blog/przechwytywanie-ekranu-za-pomoca-mediaprojection/
    private void writeImage(Image image) {
        mCount++;
        Log.v(TAG, "writeImage - count: " + mCount);

        final Image.Plane[] planes = image.getPlanes();
        final ByteBuffer buffer = planes[0].getBuffer();
        final int pixelStride = planes[0].getPixelStride();
        final int rowStride = planes[0].getRowStride();
        final int rowPadding = rowStride - pixelStride * mWidth;

        if (mBitmap == null) {
            mBitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888);
        }

        mBitmap.copyPixelsFromBuffer(buffer);

        try (final FileOutputStream fos = new FileOutputStream(
                new File(Environment.getExternalStorageDirectory(), "virtual_display_" + mCount + ".jpg"))) {
            mBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void destroyVirtualDisplay() {
        Log.d(TAG, "destroyVirtualDisplay");
        if (mVirtualDisplay != null) {
            Log.d(TAG, "destroyVirtualDisplay release");
            mVirtualDisplay.release();
            mVirtualDisplay = null;
            mButtonDestroy.setEnabled(false);
            mButtonCreate.setEnabled(true);
        }
    }


    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {

        private boolean mNewDisplayAdded = false;
        private int mCurrentDisplayId = -1;
        private MyPresentation mPresentation;

        @Override
        public void onDisplayAdded(int i) {
            Log.d(TAG, "onDisplayAdded id=" + i);
            if (!mNewDisplayAdded && mCurrentDisplayId == -1) {
                mNewDisplayAdded = true;
                mCurrentDisplayId = i;
            }
        }

        @Override
        public void onDisplayRemoved(int i) {
            Log.d(TAG, "onDisplayRemoved id=" + i);
            if (mCurrentDisplayId == i) {
                mNewDisplayAdded = false;
                mCurrentDisplayId = -1;
                if (mPresentation != null) {
                    mPresentation.dismiss();
                    mPresentation = null;
                }
            }
        }

        @Override
        public void onDisplayChanged(int i) {
            Log.d(TAG, "onDisplayChanged id=" + i);
            if (mCurrentDisplayId == i) {
                if (mNewDisplayAdded) {
                    // create a presentation
                    mNewDisplayAdded = false;
                    Display display = mDisplayManager.getDisplay(i);
                    mPresentation = new MyPresentation(MainActivity.this, display);
                    mPresentation.show();
                }
            }
        }
    };

    private final static class MyPresentation extends Presentation {

        public MyPresentation(Context context, Display display) {
            super(context, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Log.i(TAG, "MyPresentation onCreate");
            setContentView(R.layout.my_presentation);

            TextView tv = (TextView) findViewById(R.id.textView);
            Animation myRotation = AnimationUtils.loadAnimation(getContext(), R.anim.rotator);
            tv.startAnimation(myRotation);
        }
    }
}
