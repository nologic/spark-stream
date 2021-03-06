package com.ripjar.spark.process

import com.ripjar.spark.data._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.spark.streaming.dstream.DStream
import com.ripjar.spark.job.InstanceConfig


/*
 * Logs the stream
 */
object Log {
  val logger = LoggerFactory.getLogger(classOf[Log])
}

class Log(config: InstanceConfig) extends TerminalProcessor with Serializable {
  val log_tag = config.getParameter("tag", "Log")

  override def process(input: DStream[DataItem]): DStream[DataItem] = {
    input.foreachRDD( rdd => {
      rdd.foreach(item => {
        Log.logger.info(log_tag + ">> " + item.toString)
      })
    })

    input
  }

}