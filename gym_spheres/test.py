from gym_spheres.envs import SpheresEnv
import numpy as np
import math

def sample_action():
	return np.random.random() * 2 * math.pi

n_spheres = 4
env = SpheresEnv(render_screen=True, delay=5)

env.reset()
env.render()

n_steps = 500

for _ in xrange(n_steps):
	env.render()
	action = sample_action()
	state, reward, done, info = env.step(action)

raw_input('press Enter to exit')
env.close()
