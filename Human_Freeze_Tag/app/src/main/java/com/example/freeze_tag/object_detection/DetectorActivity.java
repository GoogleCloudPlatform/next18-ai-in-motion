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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;

import com.example.freeze_tag.MainActivity;
import com.example.freeze_tag.R;
import com.example.freeze_tag.object_detection.OverlayView.DrawCallback;
import com.example.freeze_tag.object_detection.env.BorderedText;
import com.example.freeze_tag.object_detection.env.ImageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Vector;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements ImageReader.OnImageAvailableListener {
    private static final Size DESIRED_PREVIEW_SIZE = new Size(448, 448);

    private static final float TEXT_SIZE_DIP = 10;

    private Integer sensorOrientation;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;

    private boolean computingDetection = false;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private static final boolean MAINTAIN_ASPECT = true;

    private byte[] luminanceCopy;

    private BorderedText borderedText;
    OverlayView trackingOverlay;

    private long idleTime = SystemClock.uptimeMillis();

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        int cropSizex = MainActivity.TF_LITE_OBJECT_DETECTION_IMAGE_DIMENSION;
        int cropSizey = MainActivity.TF_LITE_OBJECT_DETECTION_IMAGE_DIMENSION;

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSizex, cropSizey, Bitmap.Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSizex, cropSizey,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                new DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(12.0f);

                        // Draw the area the camera will actually detect
                        final float h1 = .11f * CANVAS_HEIGHT * CANVAS_HEIGHT_PERCENTAGE;
                        final float h2 = .75f * CANVAS_HEIGHT * CANVAS_HEIGHT_PERCENTAGE;
                        final RectF r = new RectF(0.0f * CANVAS_WIDTH,
                                h1,
                                1f * CANVAS_WIDTH,
                                h2);
                        canvas.drawRect(r, paint);
                        borderedText.drawText(canvas, r.left, r.bottom * (h2 - h1) + h1, "Camera Detection Area");

                        // Draw the detected blocks
                        List<Classifier.Recognition> blocks = new ArrayList<>(detectedBlocks);
                        for (Classifier.Recognition recognition : blocks) {
                            paint.setColor(recognition.getColor());
                            canvas.drawRect(recognition.getLocation().left * CANVAS_WIDTH,
                                    recognition.getLocation().top * (h2 - h1) + h1,
                                    recognition.getLocation().right * CANVAS_WIDTH,
                                    recognition.getLocation().bottom * (h2 - h1) + h1,
                                    paint);
                            borderedText.drawText(canvas, recognition.getLocation().left * CANVAS_WIDTH, recognition.getLocation().bottom * (h2 - h1) + h1, String.valueOf(recognition.getConfidence()));
                        }

                        // Draw the detected Spheros
                        for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
                            if (detectedSpheroBall.isDetectedOnce()) {
                                Classifier.Recognition recognition = detectedSpheroBall.getRecognition();
                                paint.setColor(recognition.getColor());
                                canvas.drawRect((recognition.getLocation().left + DetectedSpheroBall.NORMALIZED_SHRINK_AMOUNT) * CANVAS_WIDTH,
                                        (recognition.getLocation().top + DetectedSpheroBall.NORMALIZED_SHRINK_AMOUNT ) * (h2 - h1) + h1,
                                        (recognition.getLocation().right - DetectedSpheroBall.NORMALIZED_SHRINK_AMOUNT) * CANVAS_WIDTH,
                                        (recognition.getLocation().bottom - DetectedSpheroBall.NORMALIZED_SHRINK_AMOUNT) * (h2 - h1) + h1,
                                        paint);
                                borderedText.drawText(canvas, (recognition.getLocation().left * CANVAS_WIDTH), recognition.getLocation().bottom * (h2 - h1) + h1, String.valueOf(recognition.getConfidence()));

                                if (detectedSpheroBall.targetX >= 0 && detectedSpheroBall.targetY >= 0) {
                                    canvas.drawLine(detectedSpheroBall.getRecognition().getLocation().centerX() * CANVAS_WIDTH,
                                            detectedSpheroBall.getRecognition().getLocation().centerY() * (h2 - h1) + h1,
                                            (float) detectedSpheroBall.targetX * CANVAS_WIDTH,
                                            (float) detectedSpheroBall.targetY * (h2 - h1) + h1,
                                            paint);
                                }
                            }
                        }
                    }
                });

        addCallback(
                new DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        if (!isDebug()) {
                            return;
                        }
                        final Bitmap copy = Bitmap.createBitmap(croppedBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(3.0f);

                        final Canvas canvas2 = new Canvas(copy);

                        if (copy == null) {
                            return;
                        }

                        final int backgroundColor = Color.argb(100, 0, 0, 0);
                        canvas.drawColor(backgroundColor);

                        final Matrix matrix = new Matrix();
                        final float scaleFactor = 2;
                        matrix.postScale(scaleFactor, scaleFactor);
                        matrix.postTranslate(
                                canvas.getWidth() - copy.getWidth() * scaleFactor,
                                canvas.getHeight() - copy.getHeight() * scaleFactor);
                        canvas.drawBitmap(copy, matrix, new Paint());

                        final Vector<String> lines = new Vector<String>();
                        lines.add("");

                        lines.add("Frame: " + previewWidth + "x" + previewHeight);
                        lines.add("Frame: " + canvas2.getWidth() + "x" + canvas2.getHeight());
                        lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
                        lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
                        lines.add("Mine: " + CANVAS_WIDTH + "x" + CANVAS_HEIGHT);
                        lines.add("Rotation: " + sensorOrientation);
                        lines.add("Inference time: " + lastProcessingTimeMs + "ms");

                        borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
                    }
                });
    }

    @Override
    protected void processImage() {
        byte[] originalLuminance = getLuminance();
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        if (luminanceCopy == null) {
            luminanceCopy = new byte[originalLuminance.length];
        }
        System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.length);
        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        // Wait for a game to start
                        if (!isPlaying) {
                            if (startGame) {
                                if (warmupTimer <= 0) {
                                    // Set game state to playing
                                    // Light up the AI controlled Spheros to signify the start of a game
                                    for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
                                        if (detectedSpheroBall.isBot()) {
                                            MainActivity.spheroRobots.get(detectedSpheroBall.getIndex()).setLed(0.25f, 0.25f, 0.25f);
                                        }
                                    }
                                    isPlaying = true;
                                }
                            } else {
                                // Once a minute ping the Spheros to keep them awake while idle.
                                if (idleTime + 60000 < SystemClock.uptimeMillis()) {
                                    idleTime = SystemClock.uptimeMillis();
                                    for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
                                        if (detectedSpheroBall.isBot()) {
                                            MainActivity.spheroRobots.get(detectedSpheroBall.getIndex()).setLed(0, 0, 0);
                                        }
                                    }
                                }
                            }
                        }

                        // Run Object Detection and track the latency.
                        final long startTime = SystemClock.uptimeMillis();
                        tfLiteObjectDetection.recognizeImage(croppedBitmap);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;
                        trackingOverlay.postInvalidate();
                        requestRender();
                        computingDetection = false;

                        // Run the game and game cleanup when the game is over.
                        if (MainActivity.spheroRobots.size() > 0) {
                            if (isPlaying && !isGameOver()) {
                                for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
                                    detectedSpheroBall.play();
                                }
                                updateCurrentScore();
                            } else if (isPlaying && isGameOver()) {
                                isPlaying = false;
                                updateLeaderBoard();
                                gameOver();
                            }
                        }

                    }
                });
    }

    @Override
    protected int getLayoutId() {
        return R.layout.camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    @Override
    public void onSetDebug(final boolean debug) {
//        detector.enableStatLogging(debug);
    }

    private void updateLeaderBoard() {
        String game = "Human Freeze Tag";

        for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
            if (!detectedSpheroBall.isBot() && !detectedSpheroBall.username.equals("__reserved__")) {
                String time = UUID.randomUUID().toString();
                databaseReferenceLeaderboard.child(game).child(time).child("username").setValue(detectedSpheroBall.username);
                databaseReferenceLeaderboard.child(game).child(time).child("score").setValue(detectedSpheroBall.score);
            }
            detectedSpheroBall.score = 0;
            detectedSpheroBall.databaseReference.child("username").setValue("__reserved__");
            detectedSpheroBall.databaseReference.child("score").setValue(0);
            detectedSpheroBall.username = "__reserved__";
        }
    }

    private void updateCurrentScore() {
        for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
            if (!detectedSpheroBall.isBot()) {
                detectedSpheroBall.databaseReference.child("score").setValue(detectedSpheroBall.score);
            }
        }
    }

    private boolean isGameOver() {
        if (gameTimer <= 0) {
            return true;
        }


        for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
            if (detectedSpheroBall.isBot() && !detectedSpheroBall.isFrozen) {
                return false;
            }
        }
        return true;
    }

    private void gameOver() {
        startGame = false;
        countdownTimerGameTimer.cancel();
        countdownTimerWarmupTimer.cancel();
        warmupTimer = -1;
        gameTimer = -1;

        frozenBotPoints = new ArrayList<>();
        for (DetectedSpheroBall detectedSpheroBall : detectedSpheroBalls.values()) {
            detectedSpheroBall.isFrozen = false;
            detectedSpheroBall.targetX = -1;
            detectedSpheroBall.targetY = -1;
            if (detectedSpheroBall.isBot()) {
                MainActivity.spheroRobots.get(detectedSpheroBall.getIndex()).stop();
                MainActivity.spheroRobots.get(detectedSpheroBall.getIndex()).setLed(0, 0, 0);
            } else {
                detectedSpheroBall.username = "__reserved__";
            }
        }

        databaseColorBlue.child("game_state").setValue(MainActivity.GAME_STATE_OVER);
        databaseColorBlue.child("heading").setValue(-1);
        databaseColorBlue.child("score").setValue(0);

        databaseColorGreen.child("game_state").setValue(MainActivity.GAME_STATE_OVER);
        databaseColorGreen.child("heading").setValue(-1);
        databaseColorGreen.child("score").setValue(0);

        databaseColorPink.child("game_state").setValue(MainActivity.GAME_STATE_OVER);
        databaseColorPink.child("heading").setValue(-1);
        databaseColorPink.child("score").setValue(0);

        databaseColorRed.child("game_state").setValue(MainActivity.GAME_STATE_OVER);
        databaseColorRed.child("heading").setValue(-1);
        databaseColorRed.child("score").setValue(0);

        databaseReferenceDevice.child("warmup_timer").setValue(-1);
        databaseReferenceDevice.child("game_timer").setValue(-1);
        databaseReferenceDevice.child("start_game").setValue(false);
    }
}
