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

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.orbotix.ConvenienceRobot;
import com.orbotix.common.DiscoveryException;
import com.orbotix.common.Robot;
import com.orbotix.common.RobotChangedStateListener;
import com.orbotix.le.DiscoveryAgentLE;
import com.orbotix.le.RobotLE;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements RobotChangedStateListener {
    // Used to connect / control the Sphero Robots
    public static List<ConvenienceRobot> spheroRobots = new ArrayList<>();
    private DiscoveryAgentLE mDiscoveryAgent;
    private static final int REQUEST_CODE_LOCATION_PERMISSION = 42;

    // Used for Tensorflow Lite
    public static final String TF_LITE_COMMANDER_MODEL = "commander_model.tflite";
    public static final String TF_LITE_OBJECT_DETECTION_MODEL = "detect_model.tflite";
    public static final int TF_LITE_OBJECT_DETECTION_IMAGE_DIMENSION = 300;
    public static final int NUM_RESULTS = 10; // How many results from Object Detection to get

    // Used to handle game state
    public static String arenaId;
    public static final int GAME_STATE_OVER = 0;
    public static final int GAME_STATE_WAITING = 4;
    public static final int NUM_COMMANDER_INPUTS = 2;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupSphero();
        setupButtons();
    }

    private void setupSphero() {
        mDiscoveryAgent = new DiscoveryAgentLE();
        mDiscoveryAgent.setMaxConnectedRobots(1);
        mDiscoveryAgent.addRobotStateListener( this );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int hasLocationPermission = checkSelfPermission( Manifest.permission.ACCESS_COARSE_LOCATION );
            if( hasLocationPermission != PackageManager.PERMISSION_GRANTED ) {
                Log.e( "Sphero", "Location permission has not already been granted" );
                List<String> permissions = new ArrayList<String>();
                permissions.add( Manifest.permission.ACCESS_COARSE_LOCATION);
                requestPermissions(permissions.toArray(new String[permissions.size()] ), REQUEST_CODE_LOCATION_PERMISSION );
            } else {
                Log.d( "Sphero", "Location permission already granted" );
            }
        }
    }

    private void startDiscovery() {
        //If the DiscoveryAgent is not already looking for robots, start discovery.
        if( !mDiscoveryAgent.isDiscovering() ) {
            try {
                mDiscoveryAgent.startDiscovery(getApplicationContext());
            } catch (DiscoveryException e) {
                Log.e("Sphero", "DiscoveryException: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if( mDiscoveryAgent.isDiscovering() ) {
            mDiscoveryAgent.stopDiscovery();
        }

        for (ConvenienceRobot robot : spheroRobots) {
            robot.disconnect();
        }

        mDiscoveryAgent.addRobotStateListener(null);
    }

    @Override
    public void handleRobotChangedState(Robot robot, RobotChangedStateListener.RobotChangedStateNotificationType type ) {
        switch( type ) {
            case Online: {

                // If robot uses Bluetooth LE, Developer Mode can be turned on.
                // This turns off DOS protection. This generally isn't required.
                if( robot instanceof RobotLE) {
                    ( (RobotLE) robot ).setDeveloperMode( true );
                }

                // Save the robot as a ConvenienceRobot for additional utility methods
                ConvenienceRobot convenienceRobot = new ConvenienceRobot(robot);
                spheroRobots.add(convenienceRobot);

                convenienceRobot.setLed(0, 0, 0);
                convenienceRobot.setBackLedBrightness(100);
                TextView spheroList = findViewById(R.id.spheroListTextView);

                String connectedSpheros = spheroList.getText().toString();
                connectedSpheros += String.format("\n%s\n", convenienceRobot.getRobot().getName());

                spheroList.setText(connectedSpheros);
                break;
            }
        }
    }

    private void setupButtons() {
        findViewById(R.id.minusSpheroButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int count = Integer.valueOf((String) ((TextView)findViewById(R.id.spheroCount)).getText());

                count--;
                if (count < 1) {
                    count = 1;
                }
                mDiscoveryAgent.setMaxConnectedRobots(count);
                ((TextView) findViewById(R.id.spheroCount)).setText(String.valueOf(count));
            }
        });

        findViewById(R.id.plusSpheroButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int count = Integer.valueOf((String) ((TextView)findViewById(R.id.spheroCount)).getText());
                count++;
                if (count > 4) {
                    count = 4;
                }
                mDiscoveryAgent.setMaxConnectedRobots(count);
                ((TextView) findViewById(R.id.spheroCount)).setText(String.valueOf(count));
            }
        });


        // Look for Spheros to connect to
        findViewById(R.id.connectButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDiscovery();
            }
        });

        // If the right number of spheros have been setup and a device id has been set, continue.
        findViewById(R.id.playButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int count = Integer.valueOf((String) ((TextView)findViewById(R.id.spheroCount)).getText());
                arenaId = getArenaId();
                if (arenaId.isEmpty() || count != MainActivity.spheroRobots.size()) {
                    Toast.makeText(getBaseContext(), "Select a Device ID and ensure the selected number of Spheros are connected.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                for (ConvenienceRobot robot : MainActivity.spheroRobots) {
                    robot.setBackLedBrightness(0);
                }

                Intent intent = new Intent(v.getContext(), SpheroCalibration.class);
                startActivity(intent);
            }
        });
    }

    // Arena Ids are used to set a multi-player lobby in Firebase.
    private String getArenaId() {
        if (((RadioButton) findViewById(R.id.aCheck)).isChecked()) {
            return "a";
        } else if (((RadioButton) findViewById(R.id.bCheck)).isChecked()) {
            return "b";
        } else if (((RadioButton) findViewById(R.id.cCheck)).isChecked()) {
            return "c";
        } else if (((RadioButton) findViewById(R.id.dCheck)).isChecked()) {
            return "d";
        } else {
            return "";
        }
    }
}
