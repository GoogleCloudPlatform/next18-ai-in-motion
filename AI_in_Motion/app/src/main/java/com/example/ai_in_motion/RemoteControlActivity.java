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

import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

import com.erz.joysticklibrary.JoyStick;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.orbotix.macro.MacroObject;
import com.orbotix.macro.cmd.Delay;
import com.orbotix.macro.cmd.Fade;

public class RemoteControlActivity extends AppCompatActivity implements JoyStick.JoyStickListener {
    private TextView countDownTimer;
    private boolean isFrozen = false;
    private boolean disableJoystick = false;
    private Vibrator vibrator;

    // Game state listeners
    private ValueEventListener warmupTimerEventListener;
    private ValueEventListener gameTimerEventListener;
    private ValueEventListener scoreBoardEventListener;
    private ValueEventListener vibrateEventListener;
    private ValueEventListener gameStateEventListener;
    private ValueEventListener headingEventListener;
    private Handler fallBackTimer;
    private Runnable runnable;

    // Game states
    private static final int GAME_STATE_OVER = 0;
    private static final int GAME_STATE_PLAYING = 1;
    private static final int GAME_STATE_FROZEN = 2;
    private static final int GAME_STATE_ZOMBIE = 3;
    public static final int GAME_STATE_WAITING = 4;

    // Sound Effects
    private MediaPlayer mediaPlayer;
    private MediaPlayer effectPlayer;
    private boolean startedMusic = false;
    private boolean startedSoundEffect = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_control);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // Setup Joystick UI to match sphero ball color
        JoyStick joy1 = findViewById(R.id.joy1);
        joy1.setListener(this);
        joy1.setPadBackground(R.drawable.gray_pad);
        if (MainActivity.spheroColor == Color.BLUE) {
            joy1.setButtonDrawable(R.drawable.blue_btn);
        } else if (MainActivity.spheroColor == Color.GREEN) {
            joy1.setButtonDrawable(R.drawable.green_btn);
        } else if (MainActivity.spheroColor == Color.YELLOW) {
            joy1.setButtonDrawable(R.drawable.orange_btn);
        } else if (MainActivity.spheroColor == Color.MAGENTA) {
            joy1.setButtonDrawable(R.drawable.pink_btn);
        } else {
            joy1.setButtonDrawable(R.drawable.btn_1);
        }


        fallBackTimer = new Handler();
        runnable = new Runnable() {
            public void run() {
                UserSetup.joinedGame = false;
                finish();
            }
        };

        fallBackTimer.postDelayed(runnable, 60000);

        countDownTimer = findViewById(R.id.countDownText);

        setEventListeners();


        if (!UserSetup.joinedGame) {
            headingEventListener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    int angle = Integer.valueOf(String.valueOf(dataSnapshot.getValue()));
                    if (!UserSetup.joinedGame && angle >= 0) {
                        MainActivity.robot.drive((float) angle, 0.18f);
                        disableJoystick = true;
                    }
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                }
            };

            MainActivity.databaseReference.child("heading").addValueEventListener(headingEventListener);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
        }
        if (effectPlayer != null) {
            effectPlayer.stop();
            effectPlayer.release();
        }

        fallBackTimer.removeCallbacks(runnable);

        MainActivity.robot.abortMacro();
        MainActivity.robot.setLed(0, 0, 0);

        if (headingEventListener != null) {
            MainActivity.databaseReference.child("heading").removeEventListener(headingEventListener);
        }
        if (warmupTimerEventListener != null) {
            MainActivity.warmupTimerReference.removeEventListener(warmupTimerEventListener);
        }
        MainActivity.gameTimerReference.removeEventListener(gameTimerEventListener);
        MainActivity.scoreboardReference.removeEventListener(scoreBoardEventListener);
        MainActivity.databaseReference.child("vibrate").removeEventListener(vibrateEventListener);
        MainActivity.databaseReference.child("game_state").removeEventListener(gameStateEventListener);
        // Reset game state
        MainActivity.databaseReference.child("game_state").setValue(GAME_STATE_WAITING);
    }

    @Override
    public void onMove(JoyStick joyStick, double angle, double power, int direction) {
        if (disableJoystick) {
            disableJoystick = false;
            UserSetup.joinedGame = true;
        }

        if (isFrozen) {
            return;
        }

        switch (joyStick.getId()) {
            case R.id.joy1:
                angle = (450 - joyStick.getAngleDegrees()) % 360;
                angle = (360 - angle) % 360;
                if (MainActivity.robot != null) {
                    if (power < 1.0) {
                        MainActivity.robot.stop();
                    } else {
                        MainActivity.robot.drive((float) angle, MainActivity.SPHERO_SPEED);
                    }
                }
                break;
        }
    }

    @Override
    public void onTap() {
        MainActivity.robot.stop();
    }

    @Override
    public void onDoubleTap() {

    }

    @Override
    protected void onStart() {
        super.onStart();
        startedMusic = false;
        isFrozen = false;
        MainActivity.robot.setLed(0, 0, 0);
    }

    public void isFrozenLightDance() {
        if (!isFrozen) {
            return;
        }

        MacroObject macro = new MacroObject();
        // Blink over the period of 1 second
        macro.addCommand( new Fade( 64, 64, 64, 250 ) );
        macro.addCommand( new Delay( 250 ) );
        macro.addCommand( new Fade( 0, 0, 0, 250 ) );
        macro.addCommand( new Delay( 250 ) );
        macro.addCommand( new Fade( 64, 64, 64, 250 ) );
        macro.addCommand( new Delay( 250 ) );
        macro.addCommand( new Fade( 0, 0, 0, 250 ) );
        macro.addCommand( new Delay( 250 ) );

        //Send the macro to the robot and play
        macro.setMode( MacroObject.MacroObjectMode.Normal );
        macro.setRobot( MainActivity.robot.getRobot() );
        macro.playMacro();
    }

    private void setEventListeners() {
        warmupTimerEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                UserSetup.warmupTimer = Integer.valueOf(String.valueOf(dataSnapshot.getValue()));

                countDownTimer.setText(String.format("Game Starts in: %d", UserSetup.warmupTimer));
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        };

        gameTimerEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                UserSetup.gameTimer = Integer.valueOf(String.valueOf(dataSnapshot.getValue()));
                if (UserSetup.gameTimer <= 45) {
                    startSoundEffect();
                    countDownTimer.setText(String.format("Seconds remaining: %d", UserSetup.gameTimer));
                    isFrozenLightDance();
                } else if (UserSetup.gameTimer >= 58) {
                    startMusic();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        };

        scoreBoardEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                //Get map of users in datasnapshot
                String score = String.valueOf(dataSnapshot.getValue());

                if (Integer.valueOf(score) != 0) {
                    UserSetup.score = Integer.valueOf(score);
                    UserSetup.prevScore = UserSetup.score;
                    ((TextView) findViewById(R.id.scoreText)).setText(String.format("Score: %s\n", score));
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                //handle databaseError
            }
        };

        vibrateEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                boolean vibrate = Boolean.valueOf(String.valueOf(dataSnapshot.getValue()));

                if (vibrate) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500,VibrationEffect.DEFAULT_AMPLITUDE));
                    }else{
                        //deprecated in API 26
                        vibrator.vibrate(500);
                    }

                    if (effectPlayer != null) {
                        effectPlayer.start();
                    }
                    MainActivity.databaseReference.child("vibrate").setValue(false);
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

                if (state == GAME_STATE_OVER) { // Game Over
                    isFrozen = false;
                    MainActivity.robot.setLed(0, 0, 0);
                    UserSetup.joinedGame = false;
                    UserSetup.score = 0;
                    finish();
                } else if (state == GAME_STATE_PLAYING) { // UnFrozen (move state)
                    MainActivity.robot.abortMacro();
                    isFrozen = false;
                    MainActivity.robot.setLed(0, 0, 0);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500,VibrationEffect.DEFAULT_AMPLITUDE));
                    }else{
                        //deprecated in API 26
                        vibrator.vibrate(500);
                    }

                    return;
                } else if (state == GAME_STATE_FROZEN || state == GAME_STATE_ZOMBIE) { // Frozen/Zombie
                    MainActivity.robot.setLed(.25f, 0.25f, 0.25f);
                    if (state == GAME_STATE_FROZEN) {
                        MainActivity.robot.stop();
                        isFrozen = true;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500,VibrationEffect.DEFAULT_AMPLITUDE));
                    }else{
                        //deprecated in API 26
                        vibrator.vibrate(500);
                    }
                    if (effectPlayer != null) {
                        effectPlayer.start();
                    }

                    return;
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                System.out.println("Cancelled");
            }
        };

        MainActivity.warmupTimerReference.addValueEventListener(warmupTimerEventListener);
        MainActivity.gameTimerReference.addValueEventListener(gameTimerEventListener);
        MainActivity.scoreboardReference.addValueEventListener(scoreBoardEventListener);
        MainActivity.databaseReference.child("vibrate").addValueEventListener(vibrateEventListener);
        MainActivity.databaseReference.child("game_state").addValueEventListener(gameStateEventListener);
    }

    private void startSoundEffect() {
        if (startedSoundEffect) {
            return;
        }
        startedSoundEffect = true;
//        effectPlayer = MediaPlayer.create(this, R.raw.sound_effect);
//        effectPlayer.setVolume(1.0f, 1.0f);
//        effectPlayer.setAuxEffectSendLevel(1.0f);
    }

    private void startMusic() {
        if (startedMusic) {
            return;
        }
        startedMusic = true;

//        mediaPlayer = MediaPlayer.create(this, R.raw.human_freeze);
//        mediaPlayer.setVolume(0.5f, 0.5f);
//        mediaPlayer.start();
    }
}
