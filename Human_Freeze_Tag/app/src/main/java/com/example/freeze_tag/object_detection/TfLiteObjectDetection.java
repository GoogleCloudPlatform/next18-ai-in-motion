/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.freeze_tag.object_detection;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Trace;

import com.example.freeze_tag.MainActivity;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper for frozen detection models trained using the Tensorflow Object Detection API:
 * github.com/tensorflow/models/tree/master/research/object_detection
 */
public class TfLiteObjectDetection implements Classifier {

    // Only return this many results.
    private static final int NUM_DETECTIONS = 10;

    // Config values.
    private int inputSize = 300;

    // Pre-allocated buffers.
    private int[] intValues;
    private float[][][] outputLocations;
    private float[][] outputClasses;
    private float[][] outputScores;
    private float[] numDetections;

    protected ByteBuffer imgData = null;

    private Interpreter tfLite;

    private boolean foundRed = false;
    private boolean foundBlue = false;
    private boolean foundGreen = false;
    private boolean foundPink = false;

    private static final float MIN_CONFIDENCE = 0.55f;
    private static final float MIN_BLOCK_CONFIDENCE = 0.75f;

    /**
     * Memory-map the model file in Assets.
     */
    private static MappedByteBuffer loadModelFile(AssetManager assets, String modelFilename)
            throws IOException {
        AssetFileDescriptor fileDescriptor = assets.openFd(modelFilename);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Initializes a native TensorFlow session for classifying images.
     *
     * @param assetManager  The asset manager to be used to load assets.
     * @param modelFilename The filepath of the model GraphDef protocol buffer.
     */
    public static Classifier create(
            final AssetManager assetManager,
            final String modelFilename,
            final int inputSize) throws IOException {
        final TfLiteObjectDetection d = new TfLiteObjectDetection();


        d.inputSize = inputSize;

        try {
            d.tfLite = new Interpreter(loadModelFile(assetManager, modelFilename));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Pre-allocate buffers.
        d.imgData =
                ByteBuffer.allocateDirect(1 * d.inputSize * d.inputSize * 3 * 1);
        d.imgData.order(ByteOrder.nativeOrder());
        d.intValues = new int[d.inputSize * d.inputSize];
        d.outputLocations = new float[1][NUM_DETECTIONS][4];
        d.outputClasses = new float[1][NUM_DETECTIONS];
        d.outputScores = new float[1][NUM_DETECTIONS];
        d.numDetections = new float[1];
        return d;
    }

    private TfLiteObjectDetection() {
    }

    @Override
    public void recognizeImage(final Bitmap bitmap) {
        // Log this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage");

        Trace.beginSection("preprocessBitmap");
        // Preprocess the image data from 0-255 int to normalized float based
        // on the provided parameters.
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        imgData.rewind();
        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                int pixelValue = intValues[i * inputSize + j];
                imgData.put((byte) ((pixelValue >> 16) & 0xFF));
                imgData.put((byte) ((pixelValue >> 8) & 0xFF));
                imgData.put((byte) (pixelValue & 0xFF));
            }
        }
        Trace.endSection(); // preprocessBitmap

        // Copy the input data into TensorFlow.
        Trace.beginSection("feed");
        outputLocations = new float[1][NUM_DETECTIONS][4];
        outputClasses = new float[1][NUM_DETECTIONS];
        outputScores = new float[1][NUM_DETECTIONS];
        numDetections = new float[1];

        Object[] inputArray = {imgData};
        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, outputLocations);
        outputMap.put(1, outputClasses);
        outputMap.put(2, outputScores);
        outputMap.put(3, numDetections);

        // Run the inference call.
        Trace.beginSection("run");
        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);
        Trace.endSection();

        // There's an off by 1 error in the post processing op
        for (int k = 0; k < NUM_DETECTIONS; ++k) {
            outputClasses[0][k] += 1;
        }

        Trace.endSection();

        foundRed = false;
        foundBlue = false;
        foundGreen = false;
        foundPink = false;

        int blocksIndex = 0;
        for (int i = 0; i < MainActivity.NUM_RESULTS; i++) {
            float confidence = outputScores[0][i];
            int color = getColor((int) outputClasses[0][i]);

            float left = outputLocations[0][i][1];
            float top = outputLocations[0][i][0];
            float right = outputLocations[0][i][3];
            float bottom = outputLocations[0][i][2];

            if (color != Color.BLACK && color != Color.WHITE && color != Color.LTGRAY && confidence > MIN_CONFIDENCE) {
                DetectedSpheroBall detectedSpheroBall = CameraActivity.detectedSpheroBalls.get(color);
                if (detectedSpheroBall.isDetectedOnce()) {
                    Classifier.Recognition recognition = detectedSpheroBall.getRecognition();
                    recognition.setConfidence(confidence);
                    RectF rectF = recognition.getLocation();
                    rectF.set(left, top, right, bottom);
                } else {
                    RectF location = new RectF(left, top, right, bottom);
                    Classifier.Recognition recognition = new Classifier.Recognition("Top", "Top", confidence, location, color);
                    detectedSpheroBall.setRecognition(recognition);
                    detectedSpheroBall.setDetectedOnce();
                }
            } else if ((color == Color.WHITE || color == Color.LTGRAY) && confidence > MIN_BLOCK_CONFIDENCE && blocksIndex < NUM_DETECTIONS) {
                if (blocksIndex == CameraActivity.detectedBlocks.size()) {
                    RectF location = new RectF(left, top, right, bottom);
                    CameraActivity.detectedBlocks.add(new Classifier.Recognition("Block", "Block", confidence, location, color));
                } else {
                    Recognition recognition = CameraActivity.detectedBlocks.get(blocksIndex);
                    recognition.getLocation().set(left, top, right, bottom);
                    recognition.setConfidence(confidence);
                    recognition.setColor(color);
                }
                blocksIndex++;
            }
        }

        // So as not to break the display, only update the blocks to have meaningless values.
        while (blocksIndex < NUM_DETECTIONS && blocksIndex < CameraActivity.detectedBlocks.size()) {
            Recognition recognition = CameraActivity.detectedBlocks.get(blocksIndex);
            recognition.getLocation().set(0, 0, 0, 0);
            recognition.setConfidence(1.0f);
            recognition.setColor(Color.WHITE);
            blocksIndex++;
        }
    }

    private int getColor(int color) {
        if (color == 1 && !foundBlue) {
            foundBlue = true;
            return Color.BLUE;
        } else if (color == 2 && !foundGreen) {
            foundGreen = true;
            return Color.GREEN;
        } else if (color == 4 && !foundPink) {
            foundPink = true;
            return Color.MAGENTA;
        } else if (color == 5 && !foundRed) {
            foundRed = true;
            return Color.RED;
        } else if (color == 6) {
            return Color.WHITE;
        } else if (color == 7) {
            return Color.WHITE;
        } else if (color == 8) {
            return Color.LTGRAY;
        } else if (color == 9) {
            return Color.LTGRAY;
        }else {
            return Color.BLACK;
        }
    }

    @Override
    public void enableStatLogging(boolean debug) {

    }

    @Override
    public String getStatString() {
        return null;
    }

    @Override
    public void close() {

    }
}