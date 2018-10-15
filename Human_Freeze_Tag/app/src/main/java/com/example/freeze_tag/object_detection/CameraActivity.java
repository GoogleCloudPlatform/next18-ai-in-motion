package com.example.freeze_tag.object_detection;

/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.Manifest;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.example.freeze_tag.MainActivity;
import com.example.freeze_tag.R;
import com.example.freeze_tag.SpheroCalibration;
import com.example.freeze_tag.object_detection.env.ImageUtils;
import com.example.freeze_tag.commander.TfLiteCommander;
import com.orbotix.ConvenienceRobot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public abstract class CameraActivity extends AppCompatActivity
        implements ImageReader.OnImageAvailableListener, Camera.PreviewCallback {

    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    private boolean debug = false;

    private Handler handler;
    private HandlerThread handlerThread;
    private boolean useCamera2API;
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;

    protected int previewWidth = 0;
    protected int previewHeight = 0;

    private Runnable postInferenceCallback;
    private Runnable imageConverter;

    public static TfLiteCommander tfLiteCommander;
    public static Classifier tfLiteObjectDetection;

    // Manual adjustments to properly draw the information on the screen for debug purposes.
    public int CANVAS_WIDTH = 1080; // Get the width of the Canvas that is shown on screen
    public int CANVAS_HEIGHT = 1731; // Get the height of the Canvas that is shown on screen
    public float PERCENTAGE = 0.1733f;
    public float CANVAS_HEIGHT_PERCENTAGE = 1.0f - PERCENTAGE; // Remove the part that is taken by the black bar.

    // Keeps track of each Sphero's and Block's information.
    public static HashMap<Integer, DetectedSpheroBall> detectedSpheroBalls = new HashMap<>();
    public static List<Classifier.Recognition> detectedBlocks = new ArrayList<>();

    // Game State Information
    public static boolean isPlaying = false;
    public static boolean startGame = false;
    private static long prevTimer = -1;
    public static long gameTimer = -1;
    public static long warmupTimer = -1;
    public static CountDownTimer countdownTimerGameTimer;
    public static CountDownTimer countdownTimerWarmupTimer;
    public static FirebaseDatabase database;
    public static DatabaseReference databaseReferenceDevice;
    public static DatabaseReference databaseReferenceLeaderboard;
    public static ArrayList<Integer> HUMAN_COLORS;
    public static DatabaseReference databaseColorBlue;
    public static DatabaseReference databaseColorGreen;
    public static DatabaseReference databaseColorPink;
    public static DatabaseReference databaseColorRed;
    public static ArrayList<DetectedSpheroBall> frozenBotPoints = new ArrayList<>();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(null);

        database = FirebaseDatabase.getInstance();
        databaseReferenceDevice = database.getReference(MainActivity.arenaId);

        // Set game type and setup Information
        databaseReferenceDevice.child("game_type").setValue("Human Freeze Tag");
        databaseReferenceDevice.child("start_game").setValue(false);
        databaseReferenceDevice.child("game_timer").setValue(-1);
        databaseReferenceDevice.child("warmup_timer").setValue(-1);

        // Keep track of the player's score.
        databaseReferenceLeaderboard = database.getReference("leaderboard");

        // Warm up timer to sync the Android devices to when a game starts
        countdownTimerWarmupTimer = new CountDownTimer(15000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                warmupTimer = millisUntilFinished / 1000;
                databaseReferenceDevice.child("warmup_timer").setValue(warmupTimer);
            }

            @Override
            public void onFinish() {
            }
        };

        // Game timer to sync the Android devices to when a game ends.
        countdownTimerGameTimer = new CountDownTimer(60000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                gameTimer = millisUntilFinished / 1000;

                databaseReferenceDevice.child("game_timer").setValue(gameTimer);
                if (gameTimer < 45 && gameTimer != prevTimer) {
                    prevTimer = gameTimer;
                    // During play update each player's score to show them in real time.
                    for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
                        detectedSpheroBall.updateScore();
                        detectedSpheroBall.databaseReference.child("score").setValue(detectedSpheroBall.score);
                    }
                }
            }

            @Override
            public void onFinish() {
            }
        };

        // Create a listener for when a player's device starts a game.
        databaseReferenceDevice.child("start_game").addValueEventListener(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        boolean isStartGame = Boolean.valueOf(String.valueOf(dataSnapshot.getValue()));

                        if (isStartGame) {
                            // Setup a new game.
                            resetSpheros();
                            startGame = true;
                            warmupTimer = 15;
                            gameTimer = 60;
                            databaseReferenceDevice.child("warmup_timer").setValue(warmupTimer);
                            databaseReferenceDevice.child("game_timer").setValue(gameTimer);

                            countdownTimerWarmupTimer.cancel();
                            countdownTimerGameTimer.cancel();

                            countdownTimerWarmupTimer.start();
                            countdownTimerGameTimer.start();
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        //handle databaseError
                    }
                }
        );

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        CANVAS_HEIGHT = displayMetrics.heightPixels;
        CANVAS_WIDTH = displayMetrics.widthPixels;

        // Setup the Sphero robots and their game state listeners.
        setupPlayerListeners();

        // Setup the Object Detection / Commander models.
        try {
            tfLiteObjectDetection = TfLiteObjectDetection.create(getAssets(),
                    MainActivity.TF_LITE_OBJECT_DETECTION_MODEL,
                    MainActivity.TF_LITE_OBJECT_DETECTION_IMAGE_DIMENSION);
        } catch (IOException e) {
            e.printStackTrace();
        }

        tfLiteCommander = new TfLiteCommander(MainActivity.TF_LITE_COMMANDER_MODEL,
                getAssets());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);

        if (hasPermission()) {
            setFragment();
        } else {
            requestPermission();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        for (ConvenienceRobot robot : MainActivity.spheroRobots) {
            robot.setLed(0, 0, 0);
        }
    }

    private void resetSpheros() {
        for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
            detectedSpheroBall.score = 0;
            detectedSpheroBall.isFrozen = false;
        }
    }

    private byte[] lastPreviewFrame;

    protected int[] getRgbBytes() {
        imageConverter.run();
        return rgbBytes;
    }

    protected int getLuminanceStride() {
        return yRowStride;
    }

    protected byte[] getLuminance() {
        return yuvBytes[0];
    }

    /**
     * Callback for android.hardware.Camera API
     */
    @Override
    public void onPreviewFrame(final byte[] bytes, final Camera camera) {
        if (isProcessingFrame) {
            return;
        }

        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                Camera.Size previewSize = camera.getParameters().getPreviewSize();
                previewHeight = previewSize.height;
                previewWidth = previewSize.width;
                rgbBytes = new int[previewWidth * previewHeight];
                onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
            }
        } catch (final Exception e) {
            Log.e("Exception!", e.toString());
            return;
        }

        isProcessingFrame = true;
        lastPreviewFrame = bytes;
        yuvBytes[0] = bytes;
        yRowStride = previewWidth;

        imageConverter =
                new Runnable() {
                    @Override
                    public void run() {
                        ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
                    }
                };

        postInferenceCallback =
                new Runnable() {
                    @Override
                    public void run() {
                        camera.addCallbackBuffer(bytes);
                        isProcessingFrame = false;
                    }
                };
        processImage();
    }

    /**
     * Callback for Camera2 API
     */
    @Override
    public void onImageAvailable(final ImageReader reader) {
        //We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        if (rgbBytes == null) {
            rgbBytes = new int[previewWidth * previewHeight];
        }
        try {
            final Image image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            if (isProcessingFrame) {
                image.close();
                return;
            }
            isProcessingFrame = true;
            Trace.beginSection("imageAvailable");
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

            imageConverter =
                    new Runnable() {
                        @Override
                        public void run() {
                            ImageUtils.convertYUV420ToARGB8888(
                                    yuvBytes[0],
                                    yuvBytes[1],
                                    yuvBytes[2],
                                    previewWidth,
                                    previewHeight,
                                    yRowStride,
                                    uvRowStride,
                                    uvPixelStride,
                                    rgbBytes);
                        }
                    };

            postInferenceCallback =
                    new Runnable() {
                        @Override
                        public void run() {
                            image.close();
                            isProcessingFrame = false;
                        }
                    };

            processImage();
        } catch (final Exception e) {
            Log.e("Exception!", e.toString());
            Trace.endSection();
            return;
        }
        Trace.endSection();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();

        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public synchronized void onPause() {

        if (!isFinishing()) {
            finish();
        }

        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            Log.e( "Exception!", e.toString());
        }

        super.onPause();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, final String[] permissions, final int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                setFragment();
            } else {
                requestPermission();
            }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
                    shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
                Toast.makeText(CameraActivity.this,
                        "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show();
            }
            requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    private boolean isHardwareLevelSupported(
            CameraCharacteristics characteristics, int requiredLevel) {
        int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel;
        }
        // deviceLevel is not LEGACY, can use numerical sort
        return requiredLevel <= deviceLevel;
    }

    private String chooseCamera() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                        || isHardwareLevelSupported(characteristics,
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                return cameraId;
            }
        } catch (CameraAccessException e) {
            Log.e("CameraAccessException", "Not allowed to access camera" + e.toString());
        }

        return null;
    }

    protected void setFragment() {
        String cameraId = chooseCamera();

        Fragment fragment;
        if (useCamera2API) {
            CameraConnectionFragment camera2Fragment =
                    CameraConnectionFragment.newInstance(
                            new CameraConnectionFragment.ConnectionCallback() {
                                @Override
                                public void onPreviewSizeChosen(final Size size, final int rotation) {
                                    previewHeight = size.getHeight();
                                    previewWidth = size.getWidth();
                                    CameraActivity.this.onPreviewSizeChosen(size, rotation);
                                }
                            },
                            this,
                            getLayoutId(),
                            getDesiredPreviewFrameSize());

            camera2Fragment.setCamera(cameraId);
            fragment = camera2Fragment;
        } else {
            fragment = new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
        }

        getFragmentManager()
                .beginTransaction()
                .replace(R.id.container, fragment)
                .commit();
    }

    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    public boolean isDebug() {
        return debug;
    }

    public void requestRender() {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.postInvalidate();
        }
    }

    public void addCallback(final OverlayView.DrawCallback callback) {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.addCallback(callback);
        }
    }

    public void onSetDebug(final boolean debug) {}

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            debug = !debug;
            requestRender();
            onSetDebug(debug);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    protected void readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback.run();
        }
    }

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    protected abstract void processImage();

    protected abstract void onPreviewSizeChosen(final Size size, final int rotation);
    protected abstract int getLayoutId();
    protected abstract Size getDesiredPreviewFrameSize();

    private void setupPlayerListeners() {
        HUMAN_COLORS = new ArrayList<>();
        // Setup Blue Ball
        detectedSpheroBalls.put(Color.BLUE, new DetectedSpheroBall());
        databaseColorBlue = databaseReferenceDevice.child("player_blue");
        databaseColorBlue.child("username").setValue("__reserved__");
        databaseColorBlue.child("game_state").setValue(MainActivity.GAME_STATE_WAITING);
        databaseColorBlue.child("heading").setValue(-1);
        databaseColorBlue.child("offset").setValue(-1);
        databaseColorBlue.child("score").setValue(0);
        databaseColorBlue.child("vibrate").setValue(false);
        databaseColorBlue.child("username").addValueEventListener(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        //Get map of users in datasnapshot
                        String username = String.valueOf(dataSnapshot.getValue());

                        if (username.equals("__reserved__")) {
                            return;
                        }

                        detectedSpheroBalls.get(Color.BLUE).username = username;
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        //handle databaseError
                    }
                }
        );
        detectedSpheroBalls.get(Color.BLUE).databaseReference = databaseColorBlue;
        if (SpheroCalibration.blueCheck == -1) {
            HUMAN_COLORS.add(Color.BLUE);
        } else {
            detectedSpheroBalls.get(Color.BLUE).index = SpheroCalibration.blueCheck;
            detectedSpheroBalls.get(Color.BLUE).bot = true;
        }

        // Setup Green Ball
        detectedSpheroBalls.put(Color.GREEN, new DetectedSpheroBall());
        databaseColorGreen = databaseReferenceDevice.child("player_green");
        databaseColorGreen.child("username").setValue("__reserved__");
        databaseColorGreen.child("game_state").setValue(MainActivity.GAME_STATE_WAITING);
        databaseColorGreen.child("heading").setValue(-1);
        databaseColorGreen.child("offset").setValue(-1);
        databaseColorGreen.child("score").setValue(0);
        databaseColorGreen.child("vibrate").setValue(false);
        databaseColorGreen.child("username").addValueEventListener(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        //Get map of users in datasnapshot
                        String username = String.valueOf(dataSnapshot.getValue());

                        if (username.equals("__reserved__")) {
                            return;
                        }

                        detectedSpheroBalls.get(Color.GREEN).username = username;
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        //handle databaseError
                    }
                }
        );
        detectedSpheroBalls.get(Color.GREEN).databaseReference = databaseColorGreen;
        if (SpheroCalibration.greenCheck == -1) {
            HUMAN_COLORS.add(Color.GREEN);
        } else {
            detectedSpheroBalls.get(Color.GREEN).index = SpheroCalibration.greenCheck;
            detectedSpheroBalls.get(Color.GREEN).bot = true;
        }

        // Setup Pink Ball
        detectedSpheroBalls.put(Color.MAGENTA, new DetectedSpheroBall());
        databaseColorPink = databaseReferenceDevice.child("player_pink");
        databaseColorPink.child("username").setValue("__reserved__");
        databaseColorPink.child("game_state").setValue(MainActivity.GAME_STATE_WAITING);
        databaseColorPink.child("heading").setValue(-1);
        databaseColorPink.child("offset").setValue(-1);
        databaseColorPink.child("score").setValue(0);
        databaseColorPink.child("vibrate").setValue(false);
        databaseColorPink.child("username").addValueEventListener(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        //Get map of users in datasnapshot
                        String username = String.valueOf(dataSnapshot.getValue());

                        if (username.equals("__reserved__")) {
                            return;
                        }

                        detectedSpheroBalls.get(Color.MAGENTA).username = username;
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        //handle databaseError
                    }
                }
        );
        detectedSpheroBalls.get(Color.MAGENTA).databaseReference = databaseColorPink;
        if (SpheroCalibration.pinkCheck == -1) {
            HUMAN_COLORS.add(Color.MAGENTA);
        } else {
            detectedSpheroBalls.get(Color.MAGENTA).index = SpheroCalibration.pinkCheck;
            detectedSpheroBalls.get(Color.MAGENTA).bot = true;
        }


        // Setup Red Ball
        detectedSpheroBalls.put(Color.RED, new DetectedSpheroBall());
        databaseColorRed = databaseReferenceDevice.child("player_red");
        databaseColorRed.child("username").setValue("__reserved__");
        databaseColorRed.child("game_state").setValue(MainActivity.GAME_STATE_WAITING);
        databaseColorRed.child("heading").setValue(-1);
        databaseColorRed.child("offset").setValue(-1);
        databaseColorRed.child("score").setValue(0);
        databaseColorRed.child("vibrate").setValue(false);
        databaseColorRed.child("username").addValueEventListener(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        //Get map of users in datasnapshot
                        String username = String.valueOf(dataSnapshot.getValue());

                        if (username.equals("__reserved__")) {
                            return;
                        }

                        detectedSpheroBalls.get(Color.RED).username = username;
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        //handle databaseError
                    }
                }
        );
        detectedSpheroBalls.get(Color.RED).databaseReference = databaseColorRed;
        if (SpheroCalibration.redCheck == -1) {
            HUMAN_COLORS.add(Color.RED);
        } else {
            detectedSpheroBalls.get(Color.RED).index = SpheroCalibration.redCheck;
            detectedSpheroBalls.get(Color.RED).bot = true;
        }
    }
}
