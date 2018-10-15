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
import android.graphics.Color;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioButton;
import android.widget.Toast;

public class SetColor extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.set_color);

        // Set the Sphero's color.
        findViewById(R.id.colorSet).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (((RadioButton) findViewById(R.id.blueBallCheck)).isChecked()) {
                    MainActivity.spheroColor = Color.BLUE;
                    MainActivity.databaseReference = MainActivity.database.getReference(MainActivity.arenaId).child("player_blue");
                } else if (((RadioButton) findViewById(R.id.greenBallCheck)).isChecked()) {
                    MainActivity.spheroColor = Color.GREEN;
                    MainActivity.databaseReference = MainActivity.database.getReference(MainActivity.arenaId).child("player_green");
                    MainActivity.SPHERO_SPEED = 0.23f;
                } else if (((RadioButton) findViewById(R.id.pinkBallCheck)).isChecked()) {
                    MainActivity.spheroColor = Color.MAGENTA;
                    MainActivity.databaseReference = MainActivity.database.getReference(MainActivity.arenaId).child("player_pink");
                    MainActivity.SPHERO_SPEED = 0.23f;
                } else if (((RadioButton) findViewById(R.id.redBallCheck)).isChecked()) {
                    MainActivity.spheroColor = Color.RED;
                    MainActivity.databaseReference = MainActivity.database.getReference(MainActivity.arenaId).child("player_red");
                } else {
                    Toast.makeText(v.getContext(), "Select a color.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                MainActivity.warmupTimerReference = MainActivity.database.getReference(MainActivity.arenaId).child("warmup_timer");
                MainActivity.gameTimerReference = MainActivity.database.getReference(MainActivity.arenaId).child("game_timer");
                MainActivity.scoreboardReference = MainActivity.databaseReference.child("score");

                Intent intent = new Intent(v.getContext(), UserSetup.class);
                startActivity(intent);
            }
        });
    }
}
