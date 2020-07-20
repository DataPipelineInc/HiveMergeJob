#!/bin/bash

path=$1

/usr/local/spark-2.3.0-bin-2.6.0-cdh5.13.0/bin/spark-submit \
  --class com.datapipeline.spark.job.HiveMergeJob \
  --master yarn \
  --deploy-mode cluster \
  --executor-memory 64G \
  --num-executors 16 \
  ./StarbucksDPJob-1.0-SNAPSHOT.jar \
  "${path}"
