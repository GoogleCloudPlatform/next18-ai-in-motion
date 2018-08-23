# Copyright 2018 Google LLC
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


import numpy as np
import math
import random
import gym
from gym import error, spaces, utils
from gym.utils import seeding
from itertools import combinations
from collections import deque
import time

import pygame
from pygame.color import THECOLORS
import pymunk
from pymunk import Vec2d
import pymunk.pygame_util


class SpheresEnv(gym.Env):
    """Goal is to avoid the chaser sphere which always go towards the
    agent sphere, while trying to approach the target location."""
    metadata = {'render.modes': ['human']}

    def __init__(self, n_obstacles=2, shape=(400, 400), delay=0, render_screen=False, episode_length=200, noisy=True):
        self.n_obstacles = n_obstacles

        self.render_screen = render_screen
        self.noisy = noisy

        # delayed states, so that the agent is forced
        # to learn the correct behavior from outdated
        # states
        self.delay = delay

        # in chronological order using append
        self.state_buffer = deque([], maxlen=self.delay+1)

        # aggressiveness
        self.aggressiveness = 0.0

        self.screen = None
        self.clock = None
        self.space = None
        self.draw_options = None
        self.n_steps = None
        self.episode_length = episode_length

        self.shape = shape
        self.width, self.height = shape

        # TODO: move to _make_sphere
        # sphere
        self.mass = 1.0
        self.radius = 15
        self.friction = 0.1
        self.damping = 0.7
        self.chaser_penalty = 0.7
        self.force = 500.0

        self.sphere = None
        self.chaser = None
        self.obstacles = []

        self.reset = self._reset
        self.step = self._step
        self.render = self._render


    def pre_step_reward_fn(self):
        # record the velocity before action
        self.sphere.data['pre_velocity'] = self.sphere.body.velocity


    def post_step_reward_fn(self):
        # calculate the velocity after the action
        def target_force(position, other_position, repulse=True):
            # specify a potential field
            v = Vec2d(tuple(other_position - position))
            l = v.normalize_return_length()

            if repulse:
                v *= (-50.0 / l)
            else:
                v *= (l / 50.0)

            return v

        pre_v = self.sphere.data['pre_velocity']
        v = self.sphere.body.velocity
        change = (v - pre_v).normalized()

        position = self.sphere.body.position
        target = self.sphere.target

        attraction = target_force(position, target, repulse=False)

        chaser_repulsion = target_force(position, self.chaser.body.position)
        obstacle_repulsion = sum([target_force(position, obs.data['position']) for obs in self.obstacles])

        # avoid walls
        wall_repulsion = 50.0 * (Vec2d(1, 0) / position[0] + Vec2d(-1, 0) / (self.width - position[0]) + Vec2d(0, 1) / position[1] + Vec2d(0, -1) / (self.height - position[1]))

        # the base weights will be adjusted with aggressiveness
        base_weights = {
            'target': 1.0,
            'chaser': 8.0,
            'obstacle': 9.0,
            'wall': 5.0,
        }
        w_wall = base_weights['wall']
        w_obstacle = base_weights['obstacle']

        bw_target = base_weights['target']
        bw_chaser = base_weights['chaser']

        # the aggressiveness represents a trade off between
        # avoiding the chaser and approaching the target
        d = (bw_chaser - bw_target) / 2
        w_target = bw_target + self.aggressiveness * d
        w_chaser = bw_chaser - self.aggressiveness * d

        target = w_target * attraction + w_chaser * chaser_repulsion + w_obstacle * obstacle_repulsion + w_wall * wall_repulsion

        target = Vec2d(target).normalized()

        self.sphere.reward += 10.0 * change.dot(target)


    @staticmethod
    def heading_to_vector(heading):
        # heading is in radians
        return Vec2d((math.cos(heading), math.sin(heading)))    


    def _state(self):
        # 20% of the time the position isn't updated
        if self.noisy and random.random() < 0.20:
            sphere_position = tuple(self.sphere.data['previous_position'])
        else:
            sphere_position = tuple(self.sphere.body.position)

        target_position = tuple(self.sphere.target)

        if self.noisy and random.random() < 0.20:
            chaser_position = tuple(self.chaser.data['previous_position'])
        else:
            chaser_position = tuple(self.chaser.body.position)


        obstacle_positions = ()
        for obstacle in self.obstacles:
            op = tuple(obstacle.data['position'])

            obstacle_positions = obstacle_positions + op

        state = sphere_position + target_position + chaser_position + obstacle_positions

        # normalized
        state = np.array(state) / (self.shape * (len(state) / 2))

        # add random noise
        if self.noisy:
            state += np.random.normal(loc=0.0, scale=0.005, size=len(state))

        # add aggressiveness
        state = np.array(list(state) + [self.aggressiveness])

        return state


    def _step(self, action):
        # without this the display is not being rendered.
        if self.render_screen:
            _ = pygame.event.get()

        state = None
        reward = None
        done = False
        info = None

        # ASSUMING: each sphere's heading is RIGHT, and going COUNTERCLOCKWISE

        # sphere' action
        self.pre_step_reward_fn()
        self.sphere.data['previous_position'] = self.sphere.body.position

        vector = self.heading_to_vector(action)

        self.sphere.body.apply_force_at_local_point(self.force*vector, (0, 0))

        # chaser tries to go towards the runner
        self.chaser.data['previous_position'] = self.chaser.body.position

        vector = (self.sphere.body.position - self.chaser.body.position).normalized()

        self.chaser.body.apply_force_at_local_point(self.chaser_penalty*self.force*vector, (0, 0))

        # simulation
        sim_steps = 15
        dt = 1.0 / 10
        for x in range(sim_steps):
            self.space.step(dt / sim_steps)

        self.clock.tick(50)

        self.screen.fill(THECOLORS['black'])
        self.space.debug_draw(self.draw_options)

        state = self._state()

        # delayed state response
        self.state_buffer.append(state)
        state = self.state_buffer[0]

        self.sphere.data['delayed_position'] = np.array(state[0:2]) * self.shape

        self.chaser.data['delayed_position'] = np.array(state[4:6]) * self.shape

        self.post_step_reward_fn()

        reward = self.sphere.reward
        self.sphere.reward = 0.0

        self.n_steps += 1
        if self.n_steps >= self.episode_length:
            done = True

        return state, reward, done, info


    def _sample_center(self):
        x = np.random.randint(self.radius, self.width - self.radius)
        y = np.random.randint(self.radius, self.height - self.radius)
        return x, y


    def _sample_vertices(self, width, height):
        # NOTE: vertices need to be counterclockwise
        angle = np.random.random() * 2 * math.pi

        rotation = np.array([
            [math.sin(angle), math.cos(angle)],
            [-math.cos(angle), math.sin(angle)]
        ])

        w = np.matmul(rotation, [0, width])
        h = np.matmul(rotation, [height, 0])

        c = np.array(self._sample_center())
        
        vs = np.array([
            c + w/2 + h/2,
            c + w/2 - h/2,
            c - w/2 - h/2,
            c - w/2 + h/2
        ])

        return vs, c


    def _reset_space(self):
        if self.render_screen:
            pygame.display.init()
            self.screen = pygame.display.set_mode((self.width, self.height))
        else:
            self.screen = pygame.Surface((self.width, self.height))

        self.draw_options = pymunk.pygame_util.DrawOptions(self.screen)
        
        self.clock = pygame.time.Clock()

        self.space = pymunk.Space()
        self.space.damping = self.damping
        

    def _reset_walls(self):
        wall_size = 5.0
        walls = [
            pymunk.Segment(self.space.static_body, (0, 0), (0, self.height), wall_size),
            pymunk.Segment(self.space.static_body, (0, 0), (self.width, 0), wall_size),
            pymunk.Segment(self.space.static_body, (0, self.height), (self.width, self.height), wall_size),
            pymunk.Segment(self.space.static_body, (self.width, 0), (self.width, self.height), wall_size),
        ]
        for i, wall in enumerate(walls):
            wall.color = THECOLORS['blue']
            wall.friction = 0.0

        self.space.add(walls)


    def _bb_vertices(self, c):
        vertices = [
            c + (self.radius, self.radius),
            c + (-self.radius, self.radius),
            c + (-self.radius, -self.radius),
            c + (self.radius, -self.radius)
        ]
        return vertices


    def _make_sphere(self, color):
        inertia = pymunk.moment_for_circle(self.mass, 0, self.radius, (0, 0))

        body = pymunk.Body(self.mass, inertia)
        body.position = self._sample_center()
        shape = pymunk.Circle(body, self.radius, (0,0))
        shape.friction = self.friction
        shape.color = THECOLORS[color]

        # custom attribute
        shape.reward = 0.0
        shape.data = {
            'previous_position': body.position,
            'delayed_position': body.position
        }

        # add bounding box
        vertices = self._bb_vertices(Vec2d(0,0))
        bbody = pymunk.Body(1, 1)

        bb = pymunk.Poly(bbody, vertices)
        bb.color = THECOLORS[color+'4'] # darker color
        bb.sensor = True

        # references
        shape.data['bb'] = bb
        bbody.data = {'shape': shape}

        # position_func updates the bounding box's
        # position to the sphere's delayed position
        def f(bbody, dt):
            bbody.position = bbody.data['shape'].data['delayed_position']
        bbody.position_func = f

        return body, shape


    def _make_block(self, color):
        vertices, center = self._sample_vertices(width=60, height=40)

        body = self.space.static_body

        shape = pymunk.Poly(body, vertices, radius=1e-5)
        shape.friction = self.friction
        shape.color = THECOLORS[color]

        shape.reward = 0.0
        shape.data = {'position': center}

        return body, shape


    def _reset_sphere(self):
        body, shape = self._make_sphere('red')

        self.space.add(body, shape)
        self.sphere = shape

        bb = shape.data['bb']
        self.space.add(bb.body, bb)


    def _reset_chaser(self):
        body, shape = self._make_sphere('blue')

        self.space.add(body, shape)
        self.chaser = shape

        bb = shape.data['bb']
        self.space.add(bb.body, bb)


    def _reset_obstacles(self):
        for i in xrange(self.n_obstacles):
            body, shape = self._make_block('white')

            self.space.add(shape)
            self.obstacles.append(shape)


    def _reset_target(self):
        self.sphere.target = self._sample_center()

        shape = pymunk.Circle(self.space.static_body, self.radius//2, (0,0))
        shape.color = THECOLORS['yellow']
        shape.sensor = True
        shape.body.position = self.sphere.target

        self.space.add(shape)


    def _reset(self):  
        self.n_steps = 0
        self.aggressiveness = random.random()

        self.space = None
        self.sphere = None
        self.chaser = None
        self.obstacles = []

        self._reset_space()
        self._reset_walls()
        self._reset_obstacles()
        self._reset_sphere()
        self._reset_chaser()
        self._reset_target()

        state = self._state()

        return state


    def _render(self, mode='human', close=False):
        img = None
        if mode == 'rgb_array':
            return img
        elif mode == 'human':
            pygame.display.flip()
            pygame.display.set_caption("fps: {}, n_steps: {}".format(self.clock.get_fps(), self.n_steps))


    def close(self):
        if self.screen is not None:
            pygame.display.quit()
            self.screen = None
