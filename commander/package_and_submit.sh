# rebuild the gym_spheres package
export TMP=`mktemp -d`
echo $TMP

cd ../gym_spheres && \
python setup.py egg_info --egg-base=$TMP \
bdist_wheel --universal --dist-dir=$TMP

cd ../commander

cp $TMP/gym_spheres-0.0.3-py2.py3-none-any.whl .
rm -rf $TMP

# submit the training job to CMLE
BUCKET="gs://YOUR_BUCKET/"
BUCKET="gs://llama_llama/"

TRAINER_PACKAGE_PATH="./trainer"
MAIN_TRAINER_MODULE="trainer.task"

now=$(date +"%Y%m%d_%H%M%S")
JOB_NAME="spheres_$now"

JOB_DIR=$BUCKET"training/"$JOB_NAME

gcloud ml-engine jobs submit training $JOB_NAME \
    --job-dir $JOB_DIR  \
    --package-path $TRAINER_PACKAGE_PATH \
    --module-name $MAIN_TRAINER_MODULE \
    --packages gym_spheres-0.0.3-py2.py3-none-any.whl \
    --region us-central1 \
    --config config.yaml \
    --runtime-version 1.9 \
    -- \
    --output-dir $JOB_DIR \
    --learning-rate 0.003 \
    --save-checkpoint-steps 1000 \
    --n-batch 50 \
    --batch-size 100 \
    --n-epoch 100 \
    --delay 5
