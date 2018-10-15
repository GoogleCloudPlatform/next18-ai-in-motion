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

import android.content.Intent;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;


public class UserSetup extends AppCompatActivity {
    public TextView textViewTimeToStartGame;
    private static final int CALIBRATION_RUNNING = 5;
    private static final int CALIBRATION_DONE = 6;

    // Show previous player's username and score at the end of a game.
    public static String prevUsername = "";
    public static int prevScore = 0;

    public static int score = 0;
    public static String username = MainActivity.RESERVED_USERNAME;

    // Keep track of when games start and automatically join them if a player doesn't hit "play"
    public static boolean joinedGame = false;
    public boolean gameStarted = false;
    public static Handler handler;
    public static Runnable runnable;
    private ValueEventListener warmupTimerEventListener;
    private ValueEventListener gameStateEventListener;
    public static int warmupTimer = -1;
    public static int gameTimer = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_setup);
        textViewTimeToStartGame = findViewById(R.id.timeInfo);
        setupButtons();

        handler = new Handler();
        runnable = new Runnable() {
            public void run() {
                MainActivity.robot.setLed(0, 0, 0);
                handler.postDelayed(this, 60000);
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!prevUsername.equals(MainActivity.RESERVED_USERNAME)) {
            ((TextView) findViewById(R.id.scoreInfo)).setText(String.format("%s's Score: %s\n", prevUsername, prevScore));
        }
        joinedGame = false;
        gameStarted = false;

        warmupTimer = -1;
        gameTimer = -1;
        textViewTimeToStartGame.setText("");

         warmupTimerEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                warmupTimer = Integer.valueOf(String.valueOf(dataSnapshot.getValue()));

                if (warmupTimer == -1) {
                    gameStarted = false;
                } else if (warmupTimer == 0) {
                    username = MainActivity.RESERVED_USERNAME;
                    MainActivity.databaseReference.child("username").setValue(MainActivity.RESERVED_USERNAME);
                    textViewTimeToStartGame.setText("");

                    startGame();
                } else {
                    gameStarted = true;
                    textViewTimeToStartGame.setText("Game Starts in: " + warmupTimer);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        };

        gameStateEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                int state = Integer.valueOf(String.valueOf(dataSnapshot.getValue()));
                if (state == CALIBRATION_RUNNING) {
                    MainActivity.robot.setLed(0, 0, 0);
                    MainActivity.robot.drive(0, 0.18f);
                } else if (state == CALIBRATION_DONE) {
                    MainActivity.robot.setLed(0, 0, 0);
                    MainActivity.robot.stop();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        };


        MainActivity.warmupTimerReference.addValueEventListener(warmupTimerEventListener);
        MainActivity.databaseReference.child("game_state").addValueEventListener(gameStateEventListener);

        handler.postDelayed(runnable, 60000);
    }

    @Override
    public void onBackPressed() {
        String username = String.valueOf(((TextView) findViewById(R.id.username_id)).getText());
        if (username.equals("next18")) {
            super.onBackPressed();
        }

    }

    private void setupButtons() {
        findViewById(R.id.playButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                joinedGame = true;
                username = String.valueOf(((TextView) findViewById(R.id.username_id)).getText());
                if (username.isEmpty()) {
                    return;
                }

                if (!gameStarted) {
                    gameStarted = true;
                    MainActivity.database.getReference(MainActivity.arenaId).child("start_game").setValue(true);
                }
                prevUsername = username;
                MainActivity.databaseReference.child("username").setValue(username);

                startGame();
            }
        });
    }

    private void startGame() {
        handler.removeCallbacks(runnable);
        MainActivity.warmupTimerReference.removeEventListener(warmupTimerEventListener);
        MainActivity.databaseReference.child("game_state").removeEventListener(gameStateEventListener);

        Intent intent = new Intent(getApplicationContext(), RemoteControlActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        handler.removeCallbacks(runnable);
        MainActivity.warmupTimerReference.removeEventListener(warmupTimerEventListener);
        MainActivity.databaseReference.child("game_state").removeEventListener(gameStateEventListener);
    }
}
