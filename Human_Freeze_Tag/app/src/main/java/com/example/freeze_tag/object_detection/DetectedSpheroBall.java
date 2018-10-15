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

import android.graphics.Color;

import com.google.firebase.database.DatabaseReference;
import com.example.freeze_tag.MainActivity;
import com.example.freeze_tag.commander.CommanderInput;
import com.orbotix.macro.MacroObject;
import com.orbotix.macro.cmd.Delay;
import com.orbotix.macro.cmd.Fade;

import java.util.ArrayList;


/**
 * Keeps track of a Sphero Robot.
 * If controlled by an AI, uses the Commander model to play the game.
 */
public class DetectedSpheroBall {
    // General setup
    public static final float NORMALIZED_SHRINK_AMOUNT = 0.016f; // 0.025 0.008
    private static final double OVERLAP_DISTANCE = 0.06;
    private boolean detectedOnce = false;
    public boolean bot = false;
    public int index = -1;

    // Keeps track of where the Sphero is
    private Classifier.Recognition recognition;

    // Contains the information used for the commander model
    private ArrayList<CommanderInput> commanderInputs = new ArrayList<>();
    private float[][] commands = new float[1][20];
    // The target coordinates are used to tell the Sphero where it should go, if it does not need to
    // avoid another obstacle or Sphero.
    public double targetX = -1;
    public double targetY = -1;

    // Game State Information
    public int score = 0;
    public boolean isFrozen = false;
    public String username = "";

    // Used to sync information between devices
    public DatabaseReference databaseReference;

    // Keeps track of the nearest blocks to the Sphero
    private int blockCount = 0;
    private float block1Y = 0f;
    private float block1X = 0f;
    private float block2Y = 1.0f;
    private float block2X = 1.0f;

    public DetectedSpheroBall() {
        username = "__reserved__";
    }

    public boolean isDetectedOnce() {
        return detectedOnce;
    }

    public void setDetectedOnce() {
        this.detectedOnce = true;
    }

    public boolean isBot() {
        return bot;
    }

    public Classifier.Recognition getRecognition() {
        return recognition;
    }

    public void setRecognition(Classifier.Recognition recognition) {
        this.recognition = recognition;
    }

    public int getIndex() {
        return index;
    }


    public void play() {
        // Human Controlled
        if (!bot) {
            return;
        }

        // AI controlled
        runAwayCommanderModel();
    }

    // In this game we use the AI to run away from the Human Player
    private void runAwayCommanderModel() {
        // If frozen, signify to the human player that the ball is frozen by blinking.
        if (isFrozen) {
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
            macro.setRobot( MainActivity.spheroRobots.get(index).getRobot() );
            macro.playMacro();
            return;
        }

        // Use current detected location and the human player's detected location.
        float botY = 1.0f - recognition.getLocation().centerY();
        float botX = recognition.getLocation().centerX();
        DetectedSpheroBall humanSpheroBall = CameraActivity.detectedSpheroBalls.get(CameraActivity.HUMAN_COLORS.get(0));

        // Using the latest information, get a heading from the commander model.
        runCommanderModel(humanSpheroBall, botX, botY);

        if (checkOverlap(humanSpheroBall) && !isFrozen) {
            isFrozen = true;
            CameraActivity.frozenBotPoints.add(this);
            MainActivity.spheroRobots.get(index).setLed(0, 0, 0);
            MainActivity.spheroRobots.get(index).stop();
            humanSpheroBall.databaseReference.child("vibrate").setValue(true); // Vibrate when they freeze someone
        } else {
            for (DetectedSpheroBall detectedSpheroBall : CameraActivity.detectedSpheroBalls.values()) {
                if (!isFrozen && detectedSpheroBall.isBot() && detectedSpheroBall.isFrozen &&
                        detectedSpheroBall.getRecognition().getColor() != recognition.getColor() &&
                        checkOverlap(detectedSpheroBall)) {
                    detectedSpheroBall.isFrozen = false;
                    MainActivity.spheroRobots.get(detectedSpheroBall.index).setLed(0.25f, 0.25f, 0.25f);
                }
            }
        }
    }

    private void runCommanderModel(DetectedSpheroBall detectedSpheroBall, float botX, float botY) {
        // Get human player's coordinates
        // (the x-y axis between the camera and commander model are different and need to be adjusted)
        float humanY = 1.0f - detectedSpheroBall.getRecognition().getLocation().centerY();
        float humanX = detectedSpheroBall.getRecognition().getLocation().centerX();
        // Get preset target location
        float targetY = getXTarget();
        float targetX = getYTarget();
        // If a teammate is frozen, update the target lcoation
        if (CameraActivity.frozenBotPoints.size() > 0) {
            DetectedSpheroBall frozenBot = CameraActivity.frozenBotPoints.get(0);
            if (!frozenBot.isFrozen) {
                CameraActivity.frozenBotPoints.remove(0);
            } else {
                targetY = 1.0f - frozenBot.getRecognition().getLocation().centerY();
                targetX = frozenBot.getRecognition().getLocation().centerX();
            }
        }

        findClosestBlocks(botX, botY);

        if (blockCount >= 2) {
            commanderInputs.add(new CommanderInput(botY, botX, targetY, targetX, humanY, humanX, block1Y, block1X, block2Y, block2X));

            if (commanderInputs.size() > MainActivity.NUM_COMMANDER_INPUTS) {
                commanderInputs.remove(0);
            }

            if (commanderInputs.size() == MainActivity.NUM_COMMANDER_INPUTS) {
                // Call the commander model and each sphero color has a pre-set aggressiveness rating
                if (bot) {
                    // Send the command to the Sphero
                    MainActivity.spheroRobots.get(index).drive(getHeading(botX, botY, getAggressiveRating()), 0.2f);
                } else {
                    // Depending on game, if no human player hits the play button,
                    // use AI to play for the human, so as not to have an idle Sphero ball.
                    databaseReference.child("heading").setValue(getHeading(botX, botY, getAggressiveRating()));
                }
            }
        }
    }

    private int getHeading(float botX, float botY, float aggressiveness) {
        // Call the commander model
        CameraActivity.tfLiteCommander.getCommands(commanderInputs, commands, aggressiveness);

        // Find the heading with the highest confidence and convert the value to degrees used by the
        // Sphero's directional settings.
        int maxIndex = 0;
        float maxValue = Float.MIN_VALUE;
        for (int i = 0; i < commands[0].length; i++) {
            if (commands[0][i] > maxValue) {
                maxIndex = i;
                maxValue = commands[0][i];
            }
        }

        float radians = (float) (maxIndex * (Math.PI / 10));
        int heading = (int) ((radians * 180.0 / Math.PI) % 360);
        heading = (450 - heading) % 360;

        this.targetX = botX + 0.1 * Math.cos(radians);
        this.targetY = 1 - (botY + 0.1 * Math.sin(radians));

        return heading;
    }

    private boolean checkOverlap(DetectedSpheroBall targetSpheroBall) {
        // Used to determine if a sphero is tagged or not based on the distance from each Sphero's center
        double currentDistance = Math.sqrt(
                Math.pow(targetSpheroBall.recognition.getLocation().centerX() - recognition.getLocation().centerX(), 2) +
                        Math.pow(targetSpheroBall.recognition.getLocation().centerY() - recognition.getLocation().centerY(), 2));

        if (currentDistance <= OVERLAP_DISTANCE) {
            return true;
        }

        return false;
    }

    private float getXTarget() {
        if (recognition.getColor() == Color.BLUE) {
            return 0.1f;
        } else if (recognition.getColor() == Color.GREEN) {
            return 0.9f;
        } else if (recognition.getColor() == Color.MAGENTA) {
            return 0.9f;
        } else {
            return 0.1f;
        }
    }

    private float getYTarget() {
        if (recognition.getColor() == Color.BLUE) {
            return 0.1f;
        } else if (recognition.getColor() == Color.GREEN) {
            return 0.1f;
        } else if (recognition.getColor() == Color.MAGENTA) {
            return 0.9f;
        } else {
            return 0.9f;
        }
    }

    public void updateScore() {
        if (isFrozen || bot) {
            return;
        }
        score += 1;
    }

    private void findClosestBlocks(float botX, float botY) {
        blockCount = 0;
        double minDistance1 = Double.MAX_VALUE;
        double minDistance2 = Double.MAX_VALUE;

        for (Classifier.Recognition r : CameraActivity.detectedBlocks) {
            if (r.getLocation().left != 0 && r.getLocation().top != 0 &&
                    r.getLocation().right != 0 && r.getLocation().bottom != 0) {

                double curentDistance = Math.sqrt(Math.pow(r.getLocation().centerX() - botX, 2) + Math.pow(1.0f-r.getLocation().centerY() - botY, 2));

                if (curentDistance < minDistance1) {
                    blockCount++;
                    minDistance2 = minDistance1;
                    block2Y = block1Y;
                    block2X = block1X;

                    minDistance1 = curentDistance;
                    block1Y = 1.0f - r.getLocation().centerY();
                    block1X = r.getLocation().centerX();
                } else if (curentDistance < minDistance2) {
                    blockCount++;
                    minDistance2 = curentDistance;
                    block2Y = 1.0f - r.getLocation().centerY();
                    block2X = r.getLocation().centerX();
                }
            }
        }
    }

    private float getAggressiveRating() {
        if (recognition.getColor() == Color.GREEN) {
            return 0.0f;
        } else {
            return 0.8f;
        }
    }
}
