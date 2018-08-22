import argparse
import os
import tensorflow as tf
import numpy as np
import gym
import json
import random
import time
import sys
import math
import platform

from tensorflow.python.framework import graph_util, tensor_util
from tensorflow.contrib.graph_editor import reroute
from tensorflow.contrib import lite
from tensorflow.python.client import device_lib

from collections import deque
from itertools import product

from gym_spheres.envs import SpheresEnv

ENV_SHAPE = (448, 448)

# the number of past states to be used as input
N_HISTORY = 2

N_OBSTACLES = 2

# (x, y)-coordinates of:
# self, target, chaser, blocks
# the extra last dimension is for aggressiveness
STATE_SHAPE = (2*(1+1+1+N_OBSTACLES)+1,)
OBSERVATION_SHAPE = (N_HISTORY,) + STATE_SHAPE
OBSERVATION_DIM = np.product(OBSERVATION_SHAPE)

MEMORY_CAPACITY = 200000
ROLLOUT_SIZE = 2000

# the space of actions
heading_step = 20
headings = np.linspace(0.0, 2*math.pi, heading_step, endpoint=False)

# build the part of the graph that is shared by rollout and train subgraphs
def build_feedforward(input_):
    flattened = tf.reshape(input_, (-1, OBSERVATION_DIM))
    
    hidden = tf.keras.layers.Dense(args.hidden_dim, activation='relu')(flattened)

    hidden = tf.keras.layers.Dense(args.hidden_dim, activation='relu')(hidden)

    hidden = tf.keras.layers.Dense(args.hidden_dim, activation='relu')(hidden)

    hidden = tf.keras.layers.Dense(args.hidden_dim//2, activation='relu')(hidden)
    
    qs = tf.keras.layers.Dense(len(headings))(hidden)
    best_index = tf.argmax(qs, axis=1)

    return qs, best_index


def build_graph(input_):
    # requirement for graph_fn: takes in input_, and returns (qs, best_index)
    # input_ has shape (None,) + OBSERVATION_SHAPE
    graph_fn = build_feedforward

    with tf.variable_scope('feedforward', reuse=tf.AUTO_REUSE):
        return graph_fn(input_)


def main(args):
    # MEMORY stores tuples:
    # (state, action, reward, done)
    MEMORY = deque([], maxlen=MEMORY_CAPACITY)
    def gen():
        # freeze the memory deque since we have little control over
        # when the dataset fetches data
        for m in list(MEMORY):
            yield m

    # keep some randomly sampled data for evaluation
    eval_data = []
    eval_size = 10000

    args_dict = vars(args)
    print('args: {}'.format(args_dict))

    with tf.Graph().as_default() as g:
        global_step = tf.train.get_or_create_global_step()

        # rollout subgraph
        with tf.name_scope('rollout'):
            observations = tf.placeholder(shape=(None,)+OBSERVATION_SHAPE, dtype=tf.float32, name='input')

            qs, best_index = build_graph(observations)

            # linear decay of epsilon
            max_epsilon = 1.0
            min_epsilon = 0.8 #0.1
            decayed_epsilon = min_epsilon + (max_epsilon - min_epsilon) * (1.0 - tf.cast(global_step, tf.float32) / (args.n_epoch * args.n_batch))
            epsilon = tf.maximum(min_epsilon, decayed_epsilon)

            if args.render:
                epsilon = 0.1

            random_epsilon = tf.random_uniform(shape=())

            random_index = tf.random_uniform(shape=(1,), maxval=len(headings)-1, dtype=tf.int64)

            # epsilon-greedy for choosing the action
            label = tf.cond(random_epsilon > epsilon, lambda: best_index[0], lambda: random_index[0])

            heading = tf.constant(headings)[label]

        with tf.name_scope('eval'):
            # for evaluation, calcualte the average of
            # the best q-values over a sample of states
            best_q = tf.gather(qs, best_index, axis=1)
            mean_best_q = tf.reduce_mean(best_q)

        # dataset subgraph for experience replay
        with tf.name_scope('dataset'):
            # the dataset reads from the shared MEMORY
            # (state, label, reward, done)
            ds = tf.data.Dataset.from_generator(
                generator=gen,
                output_types=(tf.float32, tf.int32, tf.float32, tf.bool),
                output_shapes=(STATE_SHAPE, (), (), ()))

            # get N_HISTORY+1 consecutive frames for each batch
            # NOTE: there is a small chance the sampled batch will come from
            # different rollout phases
            ds = ds.repeat().batch(N_HISTORY+1).shuffle(100).batch(args.batch_size)
            iterator = ds.make_one_shot_iterator()
            next_batch = iterator.get_next()

        # train subgraph
        optimizer = tf.train.RMSPropOptimizer(
            learning_rate=args.learning_rate,
            decay=args.decay
        )

        with tf.name_scope('train'):
            train_states, train_labels, train_rewards, train_dones = next_batch

            train_states.set_shape((args.batch_size, N_HISTORY+1,) + STATE_SHAPE)

            train_observations = train_states[:,:N_HISTORY,:]
            train_next_observations = train_states[:,1:,:]

            # get the best q values
            train_qs, train_best_index = build_graph(train_observations)
            
            train_next_qs, train_next_best_index = build_graph(train_next_observations)

            # left side of Bellman equation
            q_values = tf.matrix_diag_part(tf.gather(train_qs, train_labels[:,-1], axis=1))

            # right side of Bellman equation
            next_q_values = tf.matrix_diag_part(tf.gather(train_next_qs, train_next_best_index, axis=1))

            loss = tf.reduce_mean(tf.nn.l2_loss(q_values - (train_rewards[:,-1] + args.gamma * next_q_values)))

            train_op = optimizer.minimize(loss, global_step=global_step)

        init = tf.global_variables_initializer()
        saver = tf.train.Saver()

        # summaries
        with tf.name_scope('summaries'):
            tf.summary.scalar('loss', loss)
            tf.summary.scalar('mean_best_q', mean_best_q)

            merged = tf.summary.merge_all()

    with g.as_default():
        print('>> Number of trainable parameters: {} ({} variables)'.format(sum(int(np.product(v.shape)) for v in tf.trainable_variables()), len(tf.trainable_variables())))

    env = SpheresEnv(n_obstacles=N_OBSTACLES, shape=ENV_SHAPE, render_screen=args.render, episode_length=200, delay=args.delay)

    if args.restore and args.render:
        env.episode_length = 300

    config = tf.ConfigProto(allow_soft_placement=True)
    with tf.Session(graph=g, config=config) as sess:
        if args.restore:
            # restoring from checkpoint
            restore_path = tf.train.latest_checkpoint(os.path.join(args.output_dir, 'checkpoints'))

            # allow restoring from a specific checkpoint
            if args.checkpoint:
                restore_path = restore_path.split('-')[0] + '-' + str(args.checkpoint)

            print('Restoring from {}'.format(restore_path))
            saver.restore(sess, restore_path)

        else:
            sess.run(init)

        summary_path = os.path.join(args.output_dir, 'summary')
        summary_writer = tf.summary.FileWriter(summary_path, sess.graph)
        
        # also keep a copy of the args for reference
        with tf.gfile.FastGFile(os.path.join(args.output_dir, 'args.json'), 'w') as f:
            json.dump(args_dict, f)

        # training:
        # each epoch consists of one rollout phase and one training phase
        _rollout_reward = 0.0

        for i in range(args.n_epoch):
            print('>>>>>>> epoch {}'.format(i+1))

            print('>>> Rollout phase')
            epoch_memory = []
            episode_memory = []

            # The loop for calling env.step
            state = env.reset()

            # keep past N_HISTORY (state, label, reward, done) 
            # tuples as experience
            state_buffer = deque(np.repeat(state[None], N_HISTORY, 0), maxlen=N_HISTORY)
            _observation = np.array(state_buffer)
            while True:
                # sample an action
                _label, _heading = sess.run([label, heading], feed_dict={observations: [_observation]})

                new_state, reward, done, _ = env.step(_heading)

                experience = (state, _label, reward, done)
                episode_memory.append(experience)
                
                # sample some observations for evaluation
                if len(eval_data) < eval_size and random.random() < 0.01:
                    eval_data.append(_observation)

                state = new_state

                if args.render:
                    env.render()

                state_buffer.append(state)
                _observation = np.array(state_buffer)

                if done:
                    epoch_memory.extend(episode_memory)

                    # reset the buffers
                    episode_memory = []
                    state = env.reset()
                    state_buffer = deque(np.repeat(state[None], N_HISTORY, 0), maxlen=N_HISTORY)
                    _observation = np.array(state_buffer)

                    # do not go to the training phase if rendering
                    if args.render:
                        _ = raw_input('episode done, press Enter to replay')
                        epoch_memory = []

                # a rollout phase is done when enough experience is collected
                if len(epoch_memory) >= ROLLOUT_SIZE:
                    break

            # add to the global memory
            MEMORY.extend(epoch_memory)

            print('>>> Train phase')
            for _ in range(args.n_batch):
                _, _global_step = sess.run([train_op, global_step])

                # save checkpoint and graph_def
                if _global_step % args.save_checkpoint_steps == 0:
                    print('....global step: {}'.format(_global_step))

                    feed_dict = {observations: eval_data}
                    print('Writing summary')
                    summary = sess.run(merged, feed_dict=feed_dict)
                    summary_writer.add_summary(summary, _global_step)

                    save_path = os.path.join(args.output_dir, 'checkpoints', 'model.ckpt')
                    save_path = saver.save(sess, save_path, global_step=_global_step)
                    print('Model checkpoint saved: {}'.format(save_path))

                    frozen_gd = graph_util.convert_variables_to_constants(sess, sess.graph_def, [qs.op.name])
                    # clear device assigments and set batch size to 1
                    # this is needed for exporting the model to tflite format
                    for n in frozen_gd.node:
                        if n.device:
                            n.device = ''
                        if 'shape' in n.attr:
                            n.attr['shape'].shape.dim[0].size = 1

                    # load and test the frozen gd
                    input_op_name = observations.op.name
                    output_op_name = qs.op.name

                    with tf.Graph().as_default() as frozen_g:
                        input_, output = tf.import_graph_def(frozen_gd, name='', return_elements=[input_op_name+':0', output_op_name+':0'])

                        # test run the frozen graph
                        with tf.Session() as frozen_sess:
                            _output = frozen_sess.run(output, {input_: np.random.random((1,)+OBSERVATION_SHAPE)})

                            assert _output.shape == (1, len(headings))

                        # export to tflite format
                        tflite_model = lite.toco_convert(frozen_gd, input_tensors=[input_], output_tensors=[output])
                        tf.gfile.MkDir(os.path.join(args.output_dir, 'tflites'))
                        with tf.gfile.FastGFile(os.path.join(args.output_dir, 'tflites', 'commander_{}.tflite'.format(_global_step)), 'wb') as f:
                            f.write(tflite_model)

                        # export graph_def
                        tf.train.write_graph(frozen_gd, os.path.join(args.output_dir, 'graph_defs'), 'commander_{}.pb'.format(_global_step), as_text=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser('spheres trainer')
    parser.add_argument(
        '--n-epoch',
        type=int,
        default=800)
    parser.add_argument(
        '--n-batch',
        type=int,
        default=7)
    parser.add_argument(
        '--batch-size',
        type=int,
        default=50)
    parser.add_argument(
        '--output-dir',
        type=str,
        default='/tmp/spheres_output')
    parser.add_argument(
        '--job-dir',
        type=str,
        default='/tmp/spheres_output')

    parser.add_argument(
        '--restore',
        default=False,
        action='store_true')
    parser.add_argument(
        '--checkpoint',
        default=None)
    parser.add_argument(
        '--render',
        default=False,
        action='store_true')
    parser.add_argument(
        '--save-checkpoint-steps',
        type=int,
        default=1)

    parser.add_argument(
        '--learning-rate',
        type=float,
        default=1e-3)
    parser.add_argument(
        '--decay',
        type=float,
        default=0.95)
    parser.add_argument(
        '--gamma',
        type=float,
        default=0.95)
    parser.add_argument(
        '--hidden-dim',
        type=int,
        default=512)
    parser.add_argument(
        '--delay',
        type=int,
        default=5)

    args = parser.parse_args()

    args.env_config = {'env_shape': ENV_SHAPE, 'n_obstacles': N_OBSTACLES}

    main(args)
