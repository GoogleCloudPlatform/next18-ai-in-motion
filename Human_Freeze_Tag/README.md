# Human Freeze Tag App
This app is part of a demo from Google Cloud NEXT 18 that demonstrates how AI can "see", "understand" and "react" to the environment around it. 

This app is used to run the demo's game from the AI side. The attendee tries to tag all the AI robots in as short a time as possible and the AI robots try to run away and unfreeze their frozen (tagged) teammates. 

The app handles:
- communication with [Sphero](https://www.sphero.com/) robots
- communication with Firebase
- calling the ML models
  - running object detection (seeing / understanding)
  - running the commander model (reacting)
- running the game

To run the app, you'll need a Sphero Robot and to setup [Firebase](https://firebase.google.com/docs/android/setup)
