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

package com.example.freeze_tag.commander;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import com.example.freeze_tag.MainActivity;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

public class TfLiteCommander {
    private String MODEL_PATH;
    private static AssetManager ASSET_MANAGER;
    protected ByteBuffer imgData = null;
    private Interpreter tflite;

    public TfLiteCommander(String fileName, AssetManager assetManager) {
        MODEL_PATH = fileName;
        ASSET_MANAGER = assetManager;
        // the model expect an input of a float 32 at each pixel-channel
        imgData = ByteBuffer.allocateDirect(1 * MainActivity.NUM_COMMANDER_INPUTS * 11 * 4);
        imgData.order(ByteOrder.nativeOrder());

        try {
            tflite = new Interpreter(loadModelFile());
        } catch (IOException e) {
            Log.d(">>>>>>> ", "Failed to load model file.");
            Log.e(">>>>>>> ", e.toString());
        }
    }

    /** Memory-map the model file in Assets. */
    public MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = ASSET_MANAGER.openFd(MODEL_PATH);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /** Using the 2 latest frames from the Object Detection model, get commander model's results. */
    public void getCommands(ArrayList<CommanderInput> input, float[][] results, float aggressiveness) {
        imgData.clear();
        double distance = 0.02;
        CommanderInput input2 = input.get(0);
        CommanderInput input1 = input.get(1);
        float x1 = input1.botX;
        float y1 = input1.botY;
        float x2 = input2.botX;
        float y2 = input2.botY;
        double radian = Math.atan2(y2-y1, x2-x1);

        x2 = x1 + (float) (distance * Math.cos(radian));
        y2 = y1 + (float) (distance * Math.sin(radian));

        float h_x1 = input1.humanX;
        float h_y1 = input1.humanY;
        float h_x2 = input2.humanX;
        float h_y2 = input2.humanY;
        double h_radian = Math.atan2(h_y2-h_y1, h_x2-h_x1);

        h_x2 = h_x1 + (float) (distance * Math.cos(h_radian));
        h_y2 = h_y1 + (float) (distance * Math.sin(h_radian));

        // Put oldest frame first
        imgData.putFloat(x2);
        imgData.putFloat(y2);
        imgData.putFloat(input2.targetX);
        imgData.putFloat(input2.targetY);
        imgData.putFloat(h_x2);
        imgData.putFloat(h_y2);
        imgData.putFloat(input2.block1X);
        imgData.putFloat(input2.block1Y);
        imgData.putFloat(input2.block2X);
        imgData.putFloat(input2.block2Y);
        imgData.putFloat(aggressiveness);

        imgData.putFloat(x1);
        imgData.putFloat(y1);
        imgData.putFloat(input1.targetX);
        imgData.putFloat(input1.targetY);
        imgData.putFloat(h_x1);
        imgData.putFloat(h_y1);
        imgData.putFloat(input1.block1X);
        imgData.putFloat(input1.block1Y);
        imgData.putFloat(input1.block2X);
        imgData.putFloat(input1.block2Y);
        imgData.putFloat(aggressiveness);

        tflite.run(imgData, results);
    }

}

