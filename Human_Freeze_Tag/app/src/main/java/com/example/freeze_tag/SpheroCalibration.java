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

package com.example.freeze_tag;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.example.freeze_tag.object_detection.DetectorActivity;

/**
 * Sphero Calibration is setup to calibrate each sphero's gyroscope. The gyroscope inside the sphero
 * needs to be set when the robot is first connected, so that the direction the camera is oriented is
 * the camera's sense of "forward" is the same for the sphero's sense of "forward".
 *
 * To calibrate the user rotates the robot manually until the backlit led is facing the proper
 * direction, then they hit "Verify Calibration", the robot will then start rolling forward, if this
 * is the desired direction, they can hit "Next Ball' or "Done".
 */
public class SpheroCalibration extends AppCompatActivity {
    public static int blueCheck;
    public static int greenCheck;
    public static int pinkCheck;
    public static int redCheck;
    private int count;
    private boolean isCalibrating;
    private boolean calibrationVerified;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sphero_calibration);

        findViewById(R.id.select_game_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setColorsAndPlay(v);
            }
        });

        findViewById(R.id.verifyCalibration).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.spheroRobots.get(count).setZeroHeading();
                MainActivity.spheroRobots.get(count).enableStabilization(true);
                MainActivity.spheroRobots.get(count).drive(0, 0.18f);
                calibrationVerified = true;
                ((TextView) findViewById(R.id.calibrationStatus)).setText("Calibration Status: Verified");
            }
        });

        findViewById(R.id.restartCalibration).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.spheroRobots.get(count).stop();
                MainActivity.spheroRobots.get(count).setBackLedBrightness(1.0f);
                MainActivity.spheroRobots.get(count).enableStabilization(false);
                calibrationVerified = false;
                ((TextView) findViewById(R.id.calibrationStatus)).setText("Calibration Status: Waiting");
            }
        });
    }

    @Override
    protected void onStart() {
        // Reset Calibration each time this activity is started
        super.onStart();
        calibrationVerified = false;
        findViewById(R.id.blueBallCheck).setVisibility(View.VISIBLE);
        findViewById(R.id.greenBallCheck).setVisibility(View.VISIBLE);
        findViewById(R.id.pinkBallCheck).setVisibility(View.VISIBLE);
        findViewById(R.id.redBallCheck).setVisibility(View.VISIBLE);
        ((Button) findViewById(R.id.select_game_play)).setText("Next Ball");
        blueCheck = -1;
        greenCheck = -1;
        pinkCheck = -1;
        redCheck = -1;

        count = 0;
        isCalibrating = calibrating();
    }

    // Notify the user the sphero robot needs to be calibrated.
    private boolean calibrating() {
        calibrationVerified = false;
        ((TextView) findViewById(R.id.calibrationStatus)).setText("Calibration Status: Waiting");
        MainActivity.spheroRobots.get(count).setBackLedBrightness(1.0f);
        MainActivity.spheroRobots.get(count).enableStabilization(false);
        ((TextView) findViewById(R.id.ballCount)).setText(String.format("Calibrating Ball %d of %d", count+1, MainActivity.spheroRobots.size()));

        if (count+1 == MainActivity.spheroRobots.size()) {
            ((Button) findViewById(R.id.select_game_play)).setText("Done");
            return false;
        }
        return true;
    }

    private void setColorsAndPlay(View v) {
        boolean colorSelected = false;

        // If the robot hasn't been calibrated, wait for calibration
        if (!calibrationVerified) {
            Toast.makeText(v.getContext(), "Verify Calibration", Toast.LENGTH_LONG).show();
            return;
        }

        // Determine what color was selected for the Sphero robot
        if (((RadioButton) findViewById(R.id.blueBallCheck)).isChecked() && blueCheck == -1) {
            colorSelected = true;
            blueCheck = count;
            findViewById(R.id.blueBallCheck).setVisibility(View.GONE);
        }

        if (((RadioButton) findViewById(R.id.greenBallCheck)).isChecked() && greenCheck == -1) {
            colorSelected = true;
            greenCheck = count;
            findViewById(R.id.greenBallCheck).setVisibility(View.GONE);
        }

        if (((RadioButton) findViewById(R.id.pinkBallCheck)).isChecked() && pinkCheck == -1) {
            colorSelected = true;
            pinkCheck = count;
            findViewById(R.id.pinkBallCheck).setVisibility(View.GONE);
        }

        if (((RadioButton) findViewById(R.id.redBallCheck)).isChecked() && redCheck == -1) {
            colorSelected = true;
            redCheck = count;
            findViewById(R.id.redBallCheck).setVisibility(View.GONE);
        }

        // If all steps have been completed, calibrate the next robot or continue.
        if (colorSelected && calibrationVerified) {
            MainActivity.spheroRobots.get(count).stop();
            MainActivity.spheroRobots.get(count).setBackLedBrightness(0.0f);
            count++;

            if (isCalibrating) {
                isCalibrating = calibrating();
            } else {
                Intent intent = new Intent(v.getContext(), DetectorActivity.class);
                startActivity(intent);
            }
        } else {
            Toast.makeText(v.getContext(), "Select a color.", Toast.LENGTH_LONG).show();
        }
    }
}
