# AI in Motion Demo

Disclaimer: This is not an officially supported Google product.

AI in Motion is a demo showing how machine learning models can be trained on Google Cloud Platform with synthesized and simulated data, deployed to mobile devices, and interacting with the physical world.

Some information is included in this video:

[![IMAGE ALT TEXT](https://img.youtube.com/vi/8V94ZODMM-E/0.jpg)](https://www.youtube.com/watch?v=8V94ZODMM-E "AI in Motion")

We published a few blog posts to help explain the work done to create this demo.
- [Blog Post - Part 1](https://cloud.google.com/blog/products/ai-machine-learning/ai-motion-designing-simple-system-see-understand-and-react-real-world-part-i#Design_goals)
- [Blog Post - Part 2](https://cloud.google.com/blog/products/ai-machine-learning/ai-motion-designing-simple-system-see-understand-and-react-real-world-part-ii)
- [Blog Post - Part 3](https://cloud.google.com/blog/products/ai-machine-learning/ai-in-motion-designing-a-simple-system-to-see-understand-and-react-in-the-real-world-part-iii)

## Modules

### [AI in Motion](AI_in_Motion)

Android App for player control.

### [Human Freeze Tag](Human_Freeze_Tag)

Android App running the object detection and commander models.

### [Object Detection](object_detection)

Train a custom object detection model with Cloud TPUs and export it to the TensorFlow Lite format.

### [Data Generation](data_generation)

Generate synthesized labelled imaged for object detection model training.

### [Gym Spheres](gym_spheres)

Custom OpenAI Gym environment for training in simulations.

### [Commander](commander)

Reinforcement learning model training.
