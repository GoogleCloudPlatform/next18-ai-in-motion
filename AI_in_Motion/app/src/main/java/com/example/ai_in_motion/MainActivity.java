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

package com.example.ai_in_motion;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.orbotix.ConvenienceRobot;
import com.orbotix.common.DiscoveryException;
import com.orbotix.common.Robot;
import com.orbotix.common.RobotChangedStateListener;
import com.orbotix.le.DiscoveryAgentLE;
import com.orbotix.le.RobotLE;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements RobotChangedStateListener {
    // Used to connect / control the Sphero Robot
    public static ConvenienceRobot robot;
    private DiscoveryAgentLE mDiscoveryAgent;
    private static final int REQUEST_CODE_LOCATION_PERMISSION = 42;

    // Signify this bot is AI controlled
    public static final String RESERVED_USERNAME = "__reserved__";

    // Used to handle Game State
    public static String arenaId = "arena#";
    public static float SPHERO_SPEED = 0.2f;
    public static FirebaseDatabase database;
    public static DatabaseReference databaseReference;
    public static DatabaseReference scoreboardReference;
    public static DatabaseReference warmupTimerReference;
    public static DatabaseReference gameTimerReference;
    public static int spheroColor = 0;
    private boolean calibrationVerified = false;

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

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
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
    protected void onResume() {
        super.onResume();
        if (robot != null) {
            robot.setBackLedBrightness(1.0f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if( mDiscoveryAgent.isDiscovering() ) {
            mDiscoveryAgent.stopDiscovery();
        }

        if (robot != null) {
            robot.disconnect();
        }

        mDiscoveryAgent.addRobotStateListener(null);
    }

    @Override
    public void handleRobotChangedState(Robot robot, RobotChangedStateListener.RobotChangedStateNotificationType type ) {
        switch( type ) {
            case Online: {
                //If robot uses Bluetooth LE, Developer Mode can be turned on.
                //This turns off DOS protection. This generally isn't required.
                if( robot instanceof RobotLE) {
                    ( (RobotLE) robot ).setDeveloperMode( true );
                }

                //Save the robot as a ConvenienceRobot for additional utility methods
                MainActivity.robot = new ConvenienceRobot(robot);

                MainActivity.robot.setLed(0, 0, 0);
                MainActivity.robot.setBackLedBrightness(1.0f);
                MainActivity.robot.enableStabilization(false);
                TextView spheroList = findViewById(R.id.spheroListTextView);

                String connectedSpheros = spheroList.getText().toString();
                connectedSpheros += String.format("\n%s\n", MainActivity.robot.getRobot().getName());

                spheroList.setText(connectedSpheros);
                break;
            }
        }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {

    }

    private void setupButtons() {
        database = FirebaseDatabase.getInstance();
        findViewById(R.id.connectButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDiscovery();
            }
        });

        findViewById(R.id.verifyCalibration).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (robot != null) {
                    robot.setZeroHeading();
                    robot.enableStabilization(true);
                    robot.drive(0, 0.18f);
                    calibrationVerified = true;
                    ((TextView) findViewById(R.id.calibrationStatus)).setText("Calibration Status: Verified");
                    calibrationVerified = true;
                }
            }
        });

        findViewById(R.id.restartCalibration).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (robot != null) {
                    robot.stop();
                    robot.enableStabilization(false);
                    calibrationVerified = false;
                    ((TextView) findViewById(R.id.calibrationStatus)).setText("Calibration Status: Waiting");
                }
            }
        });

        findViewById(R.id.startGame).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                arenaId = getPairedDeviceId();
                if (arenaId.isEmpty() || robot == null || !calibrationVerified) {
                    Toast.makeText(getBaseContext(), "Select a Device ID, connect to Sphero and calibrate.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                robot.stop();
                robot.setBackLedBrightness(0.0f);

                Intent intent = new Intent(v.getContext(), SetColor.class);
                startActivity(intent);
            }
        });
    }

    private String getPairedDeviceId() {
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
