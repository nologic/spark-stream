package com.ripjar.spark.process.store

import com.ripjar.spark.data._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.spark.streaming.dstream.DStream
import java.util.Properties
import kafka.producer._
import com.ripjar.spark.job.InstanceConfig
import com.mongodb.MongoOptions
import com.mongodb.WriteConcern
import com.mongodb.DBCollection
import com.mongodb.DBObject
import com.mongodb.util.JSON
import com.ripjar.spark.process.Processor
import org.apache.spark.streaming.StreamingContext._

/*
 * Used to store a stream in Mongo
 * 
 * Config parameters: 
 *  	brokers
 *   	route
 * 
 * Task parameters:
 * 		"route": "route" 
 * 
 * The tasks route overloads the default route
 */
object Mongo {
  val logger = LoggerFactory.getLogger(classOf[Mongo])

  var collections: Map[String, DBCollection] = Map.empty

  def getDBCollection(config: MongoConfig): DBCollection = {
    collections.get(config.hash) match {
      case Some(c) => c
      case None => {
        collections.synchronized {
          collections.get(config.hash) match {
            case Some(c) => c
            case None => {
              val mo = new MongoOptions()
              mo.connectionsPerHost = config.connectionsPerHost
              mo.threadsAllowedToBlockForConnectionMultiplier = config.threadsAllowedToBlockForConnectionMultiplier
              val mongoDB = new com.mongodb.Mongo(config.mongoHost, mo).getDB(config.mongoDbName)
              val collection = mongoDB.getCollection(config.mongoCollectionName)
              val concern = WriteConcern.NORMAL
              concern.continueOnErrorForInsert(true)
              collection.setWriteConcern(concern)
              collections += ((config.hash, collection))
              collection
            }
          }
        }
      }
    }
  }
}

class MongoConfig(val mongoHost: String, val mongoDbName: String, val mongoCollectionName: String, val connectionsPerHost: Int, val threadsAllowedToBlockForConnectionMultiplier: Int) extends Serializable {
  val hash = mongoHost + ":" + mongoDbName + ":" + mongoCollectionName + ":" + connectionsPerHost + ":" + threadsAllowedToBlockForConnectionMultiplier
}

class Mongo(config: InstanceConfig) extends Processor with Serializable {

  val mongoConfig = new MongoConfig(
    config.getMandatoryParameter("host"),
    config.getMandatoryParameter("db"),
    config.getMandatoryParameter("collection"),
    10,
    100)

  override def process(stream: DStream[DataItem]): DStream[DataItem] = {
    stream.foreachRDD( rdd => {
      rdd.foreachPartition(iter => {
        iter.foreach( item => {
          store(item)
        })
      })
    })

    stream
  }

  def store(input: DataItem): DataItem = {
    val dbObject = JSON.parse(input.toString).asInstanceOf[DBObject]
    Mongo.getDBCollection(mongoConfig).save(dbObject)
    input
  }

}