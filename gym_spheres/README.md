# Gym Spheres

This is a custom environment with the OpenAI Gym interface.  The Blue sphere is scripted to always go towards the Red sphere.  The Red sphere is controlled through the `env.step` interface, possibly by a trained agent.

The goal is for Red to learn to approach its target point Yellow while avoiding Blue, and the obstacle blocks.

To test the environment:

```
python test.py
```
