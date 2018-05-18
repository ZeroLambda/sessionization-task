package com.jx.weblogsessionize.driver

import com.jx.weblogsessionize.utils.SparkHelper
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{Column, Dataset}
import org.apache.spark.sql.expressions._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._


/**
  * Created by jxavier on 10/25/2017.
  */
object sessionizeWebLog {
  @transient lazy val log = Logger.getLogger(getClass.getName)
  Logger.getLogger("org").setLevel(Level.OFF)

  // Declare the entities
  case class logRecord(TIMESTAMP: String, ELB: String, CLIENT_PORT: String, BACKEND_PORT: String,
                       REQUEST_PROCESSING_TIME: String, BACKEND_PROCESSING_TIME: String, RESPONSE_PROCESSING_TIME: String,
                       ELB_STATUS_CODE: String, BACKEND_STATUS_CODE: String, RECEIVED_BYTES: String, SENT_BYTES: String,
                       REQUEST: String, USER_AGENT: String, SSL_CIPHER: String, SSL_PROTOCOL: String)

  case class sessionizedRecord(IP: String, URL: String, TIMESTAMP: String, PREVIOUS_TIMESTAMP: String,
                               TIME_DIFFERENCE: String, NEW_SESSION: String, SESSION_ID: String)

  // Declare values to be used
  object sessionVals {
    val mins = 60D
    val hrs = 3600D
    val dys = 24D * 3600D
    val sessionThreshold = 15D

    val partitionByIp: WindowSpec = Window.partitionBy(col("IP")).orderBy(col("TIMESTAMP"))
    val partitionByIpSessionID: WindowSpec = Window.partitionBy(col("IP"),col("ID_TMP")).orderBy(col("TIMESTAMP"))
    val previousTimestamp: Column = lag(col("TIMESTAMP"), 1).over(partitionByIp)
    val timeDifference: Column = (col("TIMESTAMP").cast("long") - col("PREVIOUS_TIMESTAMP").cast("long")) / mins
    val sessionTime: Column = (col("END_TIME").cast("long") - col("START_TIME").cast("long")) / mins

  }

  def main(args: Array[String]): Unit = {
    // Build the SparkSession
    val sc = SparkHelper.getLocalSparkSession()
    import sc.implicits._

    // Input Data
    //val data_file = "inputs/sample.log"
    val data_file = "inputs/2015_07_22_mktplace_shop_web_log_sample.log"

    // Load the input data and Create a Dataset and cache for further processing
    val webLogData: Dataset[logRecord] = sc.read.option("delimiter", " ")
      .format("csv").load(data_file)
      .map(x => logRecord(x.getString(0), x.getString(1), x.getString(2), x.getString(3), x.getString(4),
        x.getString(5), x.getString(6), x.getString(7), x.getString(8), x.getString(9),
        x.getString(10), x.getString(11), x.getString(12), x.getString(13), x.getString(14))
      )
      //.sample(false,0.001)
      .cache()

    //Validate the loaded data
    webLogData.take(10).foreach(println)

    import sessionVals._
    val sessionizedData: Dataset[sessionizedRecord] =
      webLogData.select(split(col("CLIENT_PORT"), ":")(0).name("IP"),
                        split(col("REQUEST"), " ")(1).name("URL"),
                        'TIMESTAMP.cast(TimestampType).as("TIMESTAMP"))
                .withColumn("PREVIOUS_TIMESTAMP", previousTimestamp)
                .withColumn("TIME_DIFFERENCE", round(timeDifference, 2))
                .withColumn("NEW_SESSION", when('TIME_DIFFERENCE.isNull || 'TIME_DIFFERENCE > sessionThreshold, 1).otherwise(0))
                .withColumn("ID_TMP", sum('NEW_SESSION).over(partitionByIp))
                .withColumn("SESSION_ID", first(monotonically_increasing_id()).over(partitionByIpSessionID))
                //.filter('IP.isin("1.186.79.10","1.186.37.25"))
                .orderBy('IP, 'TIMESTAMP)
                .as[sessionizedRecord]
                .cache()

    //Validate the sessionized schema and data
    sessionizedData.printSchema()
    sessionizedData.show(false)

    //Part 1 Sessionize the web log by IP and write to hdfs
    sessionizedData.select('IP, 'URL, 'TIMESTAMP,
                           'PREVIOUS_TIMESTAMP, 'TIME_DIFFERENCE, 'NEW_SESSION, 'SESSION_ID)
      //.filter('IP.isin("1.186.79.10","1.186.37.25"))
      .show(100, false)

    //Part 2 Determine the average session time
    sessionizedData.agg(round(avg("TIME_DIFFERENCE")).name("AVERAGE_TIME_DIFFERENCE")).show(false)

    //Print 2.1 Session Time In Minutes
    sessionizedData.groupBy('IP, 'SESSION_ID)
      .agg(min('TIMESTAMP).as("START_TIME"),
        max('TIMESTAMP).as("END_TIME"),
        count("*").as("URL_HITS"))
      .withColumn("SESSION_TIME_IN_MINS", round(sessionTime)).show(false)

    //Part 3 Determine unique URL visits per session. To clarify, count a hit to a unique URL only once per session
    sessionizedData.select("SESSION_ID", "URL").groupBy('SESSION_ID).agg(collect_set("URL").name("DISTINCT_URL_LIST")).show(false) // Version 1
    sessionizedData.select("SESSION_ID", "URL").groupBy('SESSION_ID).agg(size(collect_set("URL")).name("DISTINCT_URL_COUNT")).show(false) // Version 2

    //Part 4: Find the most engaged users, ie the IPs with the longest session times
    sessionizedData.groupBy('IP).agg(sum("TIME_DIFFERENCE").name("LONGEST_SESSION")).sort($"LONGEST_SESSION".desc).show(1, false) // Version 1
    sessionizedData.groupBy('IP).agg(sum("TIME_DIFFERENCE").name("LONGEST_SESSION")).orderBy(desc("LONGEST_SESSION")).show(1, false) // Version 2

    // Save the Sessionized data in case of further use
    sessionizedData.select('IP, 'URL, 'TIMESTAMP.cast(StringType), 'PREVIOUS_TIMESTAMP.cast(StringType),
                           'TIME_DIFFERENCE, 'NEW_SESSION, 'SESSION_ID)
                   .repartition(1).write.mode("overwrite")
                   .option("header", true)
                   .option("timestampFormat", "yyyy/MM/dd HH:mm:ss")
                   .format("csv")
                   .save("outputs/session_log")

    //Release the cached Datasets
    sessionizedData.unpersist()
    webLogData.unpersist()

    //End the SparkSession
    sc.stop()
  }
}
