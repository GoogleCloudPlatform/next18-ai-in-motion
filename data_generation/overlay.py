# Copyright 2018 The TensorFlow Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ==============================================================================

from PIL import Image, ImageDraw
import math
import random
import argparse
import os
import re
import numpy as np


PORTION_FOR_TRAINING = 0.8

BALL_RESIZE = 40
BLOCK_RESIZE = 50

xml_template = """<annotation>
    <size>
        <width>448</width>
        <height>448</height>
        <depth>3</depth>
    </size>
    <segmented>0</segmented>
    {}
</annotation>"""

object_template = """
    <object>
        <name>Sphero</name>
        <pose>Unspecified</pose>
        <truncated>0</truncated>
        <difficult>0</difficult>
        <bndbox>
            <xmin>{}</xmin>
            <ymin>{}</ymin>
            <xmax>{}</xmax>
            <ymax>{}</ymax>
        </bndbox>
        <class>
            <text>{}</text>
            <label>{}</label>
        </class>
    </object>
"""

label_map_template = """item {{
      id: {}
      name: '{}'
    }}
"""


class ImageOverlay(object):
    def __init__(self, image, x, y, width, height, label):
        self.image = image
        self.x = x
        self.y = y
        self.width = width
        self.height = height

        # only the bounding box is normalized
        self.bounding_box = []
        self.label = label

    @property
    def min_x(self):
        return self.x

    @property
    def min_y(self):
        return self.y

    @property
    def max_x(self):
        return self.x + self.width

    @property
    def max_y(self):
        return self.y + self.height

    @property
    def radius(self):
        return np.linalg.norm([self.width, self.height]) / 2

    @property
    def center(self):
        return np.mean([[self.min_x, self.max_x], [self.min_y, self.max_y]], axis=1)
    
    def distance(self, other_object):
        return np.linalg.norm(self.center - other_object.center)

    def center_to_upperleft(self, center):
        cx, cy = center
        ulx = cx - self.width / 2
        uly = cy - self.height / 2

        return int(ulx), int(uly)


# Crop image then resize the image to 448x448
def resize_image(image):
    # crop the center square
    width, height = image.size
    s = min(width, height) / 2
    image = image.crop((width/2-s, height/2-s, width/2+s, height/2+s))
    image = image.resize((448, 448))
    return image


def get_x_y(object_, background, avoid_objects=None):
    # Get random coordinates to place the object at
    done = False
    while not done:
        done = True
        if object_.x == None: # Randomly choose an x coordinate
            object_.x = random.randint(0, background.width - object_.width)
        elif object_.x == 'max': # Maximize the x coordinate
            object_.x = background.width - object_.width
        
        if object_.y == None: # Randomly choose an y coordinate
            object_.y = random.randint(0, background.height - object_.height)
        elif object_.y == 'max': # Maximize the y coordinate
            object_.y = background.height - object_.height
        # Ensure the images do not overlap
        if avoid_objects != None:
            reset = False
            for other_object in avoid_objects:
                if object_.distance(other_object) < object_.radius + other_object.radius:
                    reset = True
                    break
            if reset:        
                object_.x = None
                object_.y = None
                done = False

            # at the cost of generating some unrealistic images, encourage images that have objects near each other.
            if random.random() < 0.2:
                other_object = random.choice(avoid_objects)

                radian = random.random() * 2 * math.pi
                vx, vy = math.cos(radian), math.sin(radian)
                d = 0.7 * (object_.radius + other_object.radius)

                # desired center for object_
                center = other_object.center + [d*vx, d*vy]

                object_.x, object_.y = object_.center_to_upperleft(center)

                done = True


def place_item(object_, background, avoid_bounding_box=None, resize=40):
    # random rotation
    angle = random.randint(0, 360)
    object_.image = object_.image.rotate(angle, expand=True)
    # Resize the object
    object_.image.thumbnail((resize,resize), Image.ANTIALIAS)
    # Set the object image dimensions
    width, height = object_.image.size
    object_.width = width
    object_.height = height
    
    # Get random coordinates to place the object at
    get_x_y(object_, background, avoid_bounding_box)

    # Get the normalized bounding box
    object_.bounding_box.append(float(object_.min_x) / background.width)
    object_.bounding_box.append(float(object_.min_y) / background.height)
    object_.bounding_box.append(float(object_.max_x) / background.width)
    object_.bounding_box.append(float(object_.max_y) / background.height)

    # Place the object on the image at the give coordinates
    background.image.paste(object_.image, (object_.min_x, object_.min_y), object_.image)


def object_to_xml(object_, label_map):
    formatted = object_template.format(
        object_.bounding_box[0],
        object_.bounding_box[1],
        object_.bounding_box[2],
        object_.bounding_box[3],
        object_.label,
        label_map[object_.label]
    )
    return formatted


def generate_image(x, y, index, base_filenames, object_filenames, label_map, num_objects=5, mode='train'):
    # Open a new image and crop/resize the image to 448x448
    base_filename = random.choice(base_filenames)
    background_image = Image.open(base_filename).convert("RGBA")
    background_image = resize_image(background_image)
    width, height = background_image.size
    background_overlay = ImageOverlay(background_image, 0, 0, width, height, label='base')

    placed_objects = []
    placed_labels = []

    # Place the first object and store the bounding box
    labels = label_map.keys()
    label_1 = random.choice(labels)
    filename_1 = random.choice(object_filenames[label_1])
    object_image_1 = Image.open(filename_1).convert("RGBA")
    object_1 = ImageOverlay(object_image_1, x, y, None, None, label=label_1)
    resize = BLOCK_RESIZE if 'block' in label_1 else BALL_RESIZE
    place_item(object_1, background_overlay, resize=resize)

    print('generating with: {}'.format((x, y, index, mode, filename_1, label_1, label_map[label_1])))
    
    placed_objects.append(object_1)
    placed_labels.append(label_1)
    # make sure we don't have balls of the same color
    placed_labels = [l for l in placed_labels if 'block' not in l]

    # Place other objects
    for i in range(num_objects - 1):
        other_label = random.choice([l for l in labels if l not in placed_labels])
        other_filename = random.choice(object_filenames[other_label])
        other_image = Image.open(other_filename).convert("RGBA")
        other_object = ImageOverlay(other_image, None, None, None, None, label=other_label)

        # this step attempts to avoid overlapping with previously placed objects.
        resize = BLOCK_RESIZE if 'block' in other_label else BALL_RESIZE
        place_item(other_object, background_overlay, placed_objects, resize=resize)

        placed_objects.append(other_object)
        placed_labels.append(other_label)
        placed_labels = list(set([l for l in placed_labels if 'block' not in l]))

    background_overlay.image.convert('RGB').save('./{}/{}_{}.jpg'.format(mode, mode, index))
    objects_xml = ''
    for obj in placed_objects:
        # skip objects whose center isn't in the view
        if min(obj.center) < 0 or max(obj.center) > 448: continue
        objects_xml += object_to_xml(obj, label_map)

    xml_formatted = xml_template.format(objects_xml)

    with open('./labels/{}_{}.xml'.format(mode, index), 'w') as f:
        f.write(xml_formatted)


def get_filenames():
    base_filenames = []
    object_filenames = {}

    for root, dirnames, fns in os.walk('resources'):
        for fn in fns:
            path = os.path.join(root, fn)
            print(path)
            # NOTE: changed here when moving to 5x5
            if 'base' in fn:
                base_filenames.append(path)
            else:
                label = re.match(r'resources/([^/]+)/', path).group(1)
                object_filenames.setdefault(label, []).append(path)

    return base_filenames, object_filenames


def get_and_write_label_map(labels, label_map_filename='sphero_label_map.pbtxt'):
    label_pbtxt = ''
    label_map = {}
    for i, label in enumerate(sorted(labels)):
        label_pbtxt += label_map_template.format(i+1, label)
        label_map[label] = i+1

    with open(label_map_filename, 'w') as f:
        f.write(label_pbtxt)

    return label_map


def main(args):
    # get image resources
    base_filenames, object_filenames = get_filenames()

    labels = list(set(object_filenames.keys()))

    # the keys of the label_map dict are labels
    label_map = get_and_write_label_map(labels)

    # prepare the output
    num_training = int(args.num_images * PORTION_FOR_TRAINING)
    num_testing = args.num_images - num_training

    normalized_bounding_boxes = [None] * args.num_images

    randomized_indices = list(range(args.num_images))
    random.shuffle(randomized_indices)

    if not os.path.exists('train'):
        os.mkdir('train')
    if not os.path.exists('test'):
        os.mkdir('test')
    if not os.path.exists('labels'):
        os.mkdir('labels')

    for i, index in enumerate(randomized_indices):
        print('generating image {}'.format(i))
        # get mode
        mode = 'train'
        if random.random() > PORTION_FOR_TRAINING:
            mode = 'test'

        # place the first object at a random position
        x, y = None, None

        # generate image and label, write them to disk
        generate_image(x, y, index, base_filenames, object_filenames, label_map, mode=mode)



if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('num_images', type=int)
    args = parser.parse_args()

    main(args)

