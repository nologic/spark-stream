package com.ripjar.spark.process

import com.ripjar.spark.data._
import org.apache.spark.streaming.dstream.DStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.ripjar.product.orac.analytic.FeatureExtraction
import com.ripjar.product.orac.analytic.process.FeatureProcessor
import scala.collection.JavaConversions
import com.ripjar.spark.job.Instance


/*
 * Performs feature enrichment on the stream
 * 
 * Config parameters: 
 *  	input
 *   	resources path 
 *  
 *  input - default location where to read the text from
 *     	
 * 
 * Task parameters:
 * 		"enrich": {
 *   		"field": "twitter.message"
 *   	} 
 * 
 * The tasks route overloads the default route
 */
//TODO: Test
//TODO: Set resources
object LegacyFeatureEnrich {
  val logger = LoggerFactory.getLogger(classOf[LegacyFeatureEnrich])

  var featureProcessor: FeatureProcessor = null

  def getFeatureProcessor(): FeatureProcessor = {
    if (featureProcessor == null) {
      featureProcessor.synchronized {
        if (featureProcessor == null) {
          featureProcessor = new FeatureProcessor
        }
      }
    }
    featureProcessor
  }

}

// Performs enrichment using methods form MM phase 1
class LegacyFeatureEnrich(config: Instance) extends Processor with Serializable {

  val defaultTextPath = DataItem.toPathElements(config.getMandatoryParameter("input"))
  val taskTextPath = DataItem.toPathElements("task.enrich.field")
 
  override def process(stream: DStream[DataItem]): DStream[DataItem] = {
    stream.map(enrich(_))
  }

  def enrich(item: DataItem): DataItem = {
    val textOption : Option[String] = item.getTyped[String](taskTextPath) match {
      case Some(path) => {
        item.getTyped[String](DataItem.toPathElements(path))
      }
      case _ =>  item.getTyped[String](defaultTextPath)
    }
    
    textOption match {
      case Some(value: String) => {
        val lm: java.util.Map[java.lang.String, java.lang.Object] = new java.util.HashMap[java.lang.String, java.lang.Object]()
        lm.put("message", value)
        LegacyFeatureEnrich.getFeatureProcessor.process(lm)

        //TODO - merge to the DI - currently a map of maps
        val md = lm.get(FeatureExtraction.FEATURES_TAG)
        val tran = lm.get(FeatureExtraction.TRANSIENT_TAG)
      }
      case _ => //Do nothing
    }
    
    
    
    item
  }
}
