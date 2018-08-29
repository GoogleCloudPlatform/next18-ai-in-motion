# Data Generation

## Generate training images

The `overlay.py` script would overlay object images on the background image, and create labels.  The script will attempt to create directories `train`, `test`, and `labels`, and creates a file `sphero_label_map.pbtxt`.

To run it:

```
python overlay.py 100
```


## Convert to TensorFlow records

TensorFlow Object Detection API requires a specific format of the input data.  The `convert_to_tfrecord.py` script takes care of that, after images have been generated.

To create `train.record`:

```
python convert_to_tfrecord.py --output_path train.record --images_dir train --labels_dir labels
```
