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

public class CommanderInput {
    public float botY;
    public float botX;
    public float targetY;
    public float targetX;
    public float humanY;
    public float humanX;
    public float block1Y;
    public float block1X;
    public float block2Y;
    public float block2X;

    public CommanderInput(float botY, float botX, float targetY, float targetX, float humanY, float humanX, float block1Y, float block1X, float block2Y, float block2X) {
        this.botY = botY;
        this.botX = botX;
        this.targetY = targetY;
        this.targetX = targetX;
        this.humanY = humanY;
        this.humanX = humanX;
        this.block1Y = block1Y;
        this.block1X = block1X;
        this.block2Y = block2Y;
        this.block2X = block2X;
    }
}
