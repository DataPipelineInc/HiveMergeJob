package com.datapipeline.spark.job

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.log4j.Logger
import org.apache.spark.SparkConf
import org.apache.spark.sql.{SQLContext, SaveMode, SparkSession}

object HiveMergeJob {
  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf().setMaster("yarn").setAppName("mergefiles")
    val spark = SparkSession
      .builder().config(sparkConf)
      .getOrCreate()
    val sc = spark.sparkContext
    val logger = Logger.getLogger("org")
    val fileSystem = FileSystem.get(sc.hadoopConfiguration)
    //    val srcDataPath = "/tmp/source"
    if (args.length < 1) {
      println("please input source path")
      sys.exit()
    }
    val srcDataPath = args(0)
    val mergePath = "/tmp/merge"
    val sdf = new SimpleDateFormat("yyyyMMdd-HH")
    val mergeTime = sdf.format(new Date())
    val partitionSize = if (args.length > 1) args(1).toInt else 128
    val result = mergeFiles(spark.sqlContext, fileSystem, mergeTime, srcDataPath, mergePath, partitionSize)
    logger.info("result: " + result)
  }

  /**
   * 合并文件
   * 合并步骤：
   * 1. 将小文件目录(srcDataPath)下的文件移动到临时目录/mergePath/${mergeTime}/src
   * 2. 计算临时目录(/mergePath/${mergeTime}/src)的大小。 根据大小确定分区的数。
   * 3. 使用coalesce或者repartition， 传入分区数。 将临时目录数据写入临时的数据目录(/$mergePath/${mergeTime}/data)
   * 4. 将临时数据目录文件move到文件目录(srcDataPath)
   * 5. 删除临时目录(/tmp/merge)
   *
   * @param sqlContext
   * @param fileSystem
   * @param mergeTime   批次的时间
   * @param srcDataPath 需要合并文件目录
   * @param mergePath   合并文件的临时目录
   * @return
   */
  def mergeFiles(sqlContext: SQLContext, fileSystem: FileSystem, mergeTime: String,
                 srcDataPath: String, mergePath: String, partitionSize: Int): String = {
    val mergeSrcPath = mergePath + "/" + mergeTime + "/src"
    val mergeDataPath = mergePath + "/" + mergeTime + "/data"
    var mergeInfo = "merge success"

    try {
      // 将需要合并的文件mv到临时目录
      moveFiles(fileSystem, mergeTime, srcDataPath, mergeSrcPath, true)
      val partitionNum = computePartitionNum(fileSystem, mergeSrcPath, partitionSize)
      val srcDF = sqlContext.read.format("csv").load(mergeSrcPath + "/")
      // 将合并目录的src子目录下的文件合并后保存到合并目录的data子目录下
      srcDF.coalesce(partitionNum).write.format("csv").mode(SaveMode.Overwrite).save(mergeDataPath)
      // 将合并目录的data目录下的文件移动到原目录
      moveFiles(fileSystem, mergeTime, mergeDataPath, srcDataPath, false)
      // 删除 合并目录src的子目录
      fileSystem.delete(new Path(mergePath + "/" + mergeTime), true)
    } catch {
      case e: Exception => {
        e.printStackTrace()
        mergeInfo = "merge failed"
      }
    }
    mergeInfo
  }


  /**
   *
   * 将源目录中的文件移动到目标目录中
   *
   * @param fileSystem
   * @param mergeTime
   * @param fromDir
   * @param destDir
   * @param ifTruncDestDir
   */
  def moveFiles(fileSystem: FileSystem, mergeTime: String, fromDir: String,
                destDir: String, ifTruncDestDir: Boolean): Unit = {
    val destDirPath = new Path(destDir)
    if (!fileSystem.exists(new Path(destDir))) {
      fileSystem.mkdirs(destDirPath.getParent)
    }
    // 是否清空目标目录下面的所有文件
    if (ifTruncDestDir) {
      fileSystem.globStatus(new Path(destDir + "/*")).foreach(x => fileSystem.delete(x.getPath, true))
    }
    var num = 0
    fileSystem.globStatus(new Path(fromDir + "/*")).foreach(f = x => {
      val fromLocation = x.getPath.toString
      val fileName = fromLocation.substring(fromLocation.lastIndexOf("/") + 1)
      val fromPath = new Path(fromLocation)
      if (fileName != "_SUCCESS") {
        var destLocation = fromLocation.replace(fromDir, destDir)
        val fileSuffix = if (fileName.contains("."))
          fileName.substring(fileName.lastIndexOf(".")) else ""
        val newFileName = mergeTime + "_" + num + fileSuffix
        destLocation = destLocation.substring(0, destLocation.lastIndexOf("/") + 1) + newFileName
        num = num + 1
        val destPath = new Path(destLocation)
        if (!fileSystem.exists(destPath.getParent)) {
          fileSystem.mkdirs(destPath.getParent)
        }
        fileSystem.rename(fromPath, destPath) // hdfs dfs -mv
      }

    })
  }

  /**
   * 根据目录下文件的大小计算partition数
   *
   * @param fileSystem
   * @param filePath
   * @param partitionSize
   * @return
   */
  def computePartitionNum(fileSystem: FileSystem, filePath: String, partitionSize: Int): Int = {
    val path = new Path(filePath)
    try {
      val fileSize = fileSystem.getContentSummary(path).getLength
      val msize = fileSize.asInstanceOf[Double] / 1024 / 1024 / partitionSize
      Math.ceil(msize).toInt
    } catch {
      case e: IOException => e.printStackTrace()
        1
    }
  }

}