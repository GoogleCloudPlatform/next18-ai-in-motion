from gym.envs.registration import register
import envs

register(
    id='Spheres-v0',
    entry_point='envs:SpheresEnv',
)
