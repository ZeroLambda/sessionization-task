package com.jx.weblogsessionize.utils

import org.apache.commons.lang.SystemUtils
import org.apache.spark.SparkConf
import org.apache.log4j.Logger
import org.apache.spark.sql.SparkSession

object SparkHelper {
  @transient lazy val log = Logger.getLogger(getClass.getName)

  def getLocalSparkSession(): SparkSession = {
    val conf = new SparkConf()

    if (SystemUtils.IS_OS_WINDOWS) {
      System.setProperty("hadoop.home.dir", "C:/app/winutils")
      System.setProperty("spark.sql.warehouse.dir", "C:/Users/jxavier/workspace/weblogsessionize/spark-warehouse")
    }

    conf.set("spark.executor.memory", "1g")
    conf.set("spark.sql.shuffle.partitions", "10")

    val sc = SparkSession
      .builder()
      .appName("SparkPractise")
      .master("local")
      .config(conf)
      .getOrCreate()

    return sc
  }
 
}