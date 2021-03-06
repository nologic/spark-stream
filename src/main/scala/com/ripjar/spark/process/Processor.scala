package com.ripjar.spark.process

import com.ripjar.spark.data._
import org.apache.spark.streaming.dstream.DStream



trait Processor extends Serializable {

  def process(stream: DStream[DataItem]): DStream[DataItem]
}

trait TerminalProcessor extends Processor {

}

abstract class MultiProcessor extends Processor {
  override def process(stream: DStream[DataItem]) : DStream[DataItem] = {
    process(Array[DStream[DataItem]](stream))
  }

  def process(streams: Array[DStream[DataItem]]) : DStream[DataItem]
}

