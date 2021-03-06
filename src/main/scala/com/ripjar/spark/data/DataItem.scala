package com.ripjar.spark.data

import scala.collection.mutable.HashMap
import scala.reflect.ClassTag
import com.github.nscala_time.time.Imports.DateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._
import java.util.{ Map => JMap }
import java.util.{ List => JList }
import java.lang.{ String => JString }
import java.lang.{ Integer => JInteger }
import java.lang.{ Float => JFloat }
import java.lang.{ Double => JDouble }
import java.lang.{ Boolean => JBoolean }
import java.lang.{ Long => JLong }
import java.lang.{ String => JString }
import java.lang.{ Object => JObject }
import scala.collection.parallel.mutable
import scala.util.parsing.json.JSONFormat

// all the DataItem representations must implement this trait.
trait DataItem extends Serializable {
  def copy(): DataItem
  def getRaw(): Array[Byte]

  def contains[T: ClassTag](key: ItemPath) : Boolean
  def get[T: ClassTag](key: ItemPath): Option[T]
  def getMandatory[T: ClassTag](key: ItemPath): T

  def put(key: ItemPath, value: Any)

  def remove(key: ItemPath): Option[Any]

  def toJSON() : String
  override def toString() : String = toJSON
}

object ItemPath {
  type InternalPath = List[Either[String, (String, Int)]]
}

// container for a pre-parsed path object.
class ItemPath(val key: String) extends Serializable {
  // for now we only have one representation
  var internal: ItemPath.InternalPath = {
    key.split("\\.").map( str => {
      val name = str.split("[\\[\\]]")

      name.length match {
        case 1 => Left(name.head)
        case 2 => Right( (name.head, name.last.toInt) )
      }
    }).toList
  }

  override def toString() : String = {
    key
  }
}

object DataItem {
  val logger = LoggerFactory.getLogger("DataObject")

  def jsonToMap(json: String): Option[Any] = {
    val myNum = { input : String =>
      if(input.indexOf('.') > -1){
        java.lang.Double.parseDouble(input)
      } else {
        java.lang.Long.parseLong(input)
      }
    }

    scala.util.parsing.json.JSON.globalNumberParser = myNum
    scala.util.parsing.json.JSON.perThreadNumberParser = myNum

    scala.util.parsing.json.JSON.parseFull(json)
  }

  def fromJava(jm: JMap[JString, JObject]): DataItem = {
    val di = create()
    merge(di, jm)
    di
  }

  def merge(di: DataItem, jmap: JMap[JString, JObject]) {
    jmap.entrySet().foreach(entry => {
      val key = new ItemPath(entry.getKey)

      entry.getValue() match {
        case s: JString => di.put(key, s)
        case i: JInteger => di.put(key, i.intValue)
        case f: JFloat => di.put(key, f.floatValue)
        case d: JDouble => di.put(key, d.doubleValue)
        case b: JBoolean => di.put(key, b.booleanValue)
        case l: JLong => di.put(key, l.longValue)
        case m: JMap[JString, JObject] => {
          di.get(key) match {
            case Some(sm: DataItem) => merge(sm, m)
            case Some(_) => {
              DataItem.logger.info("Changing type on: %s".format(entry.getKey))
              di.put(key, DataItem.fromJava(m))
            }
            case None => {
              di.put(key, DataItem.fromJava(m))
            }
          }
        }
        case l: JList[JString] => di.put(key, l.map(_.toString).toList)
        case l: JList[JInteger] => di.put(key, l.map(_.intValue.toLong).toList)
        case l: JList[JFloat] => di.put(key, l.map(_.floatValue.toDouble).toList)
        case l: JList[JDouble] => di.put(key, l.map(_.doubleValue).toList)
        case l: JList[JBoolean] => di.put(key, l.map(_.booleanValue).toList)
        case l: JList[JLong] => di.put(key,l.map(_.longValue).toList)
        case l: JList[JMap[JString, JObject]] => di.put(key, l.map(jm => fromJava(jm)).toList)
        case _ => throw new RuntimeException("Not implmented DataObject mapping from type during merge: " + entry.getValue.getClass.getName)
      }
    })
  }

  def create(template: DataItem = null, raw: Array[Byte] = null): DataItem = {
    template match {
      //case di: NestedDataItem => new NestedDataItem(raw)
      case di: HashDataItem => new HashDataItem(raw)
      case _ => new HashDataItem(raw)
    }
  }

  def makeInsertable(item: Any): Any = {
    item match {
      case v: List[Any] => {
        v.map(lst_item => {
          makeInsertable(lst_item)
        })
      }
      case v: Array[Any] => {
        v.toList.map(lst_item => {
          makeInsertable(lst_item)
        })
      }

      case v: Map[String, Any] => fromMap(v)
      case null => ""
      case v => v
    }
  }

  def fromMap(map: Map[String, Any], template: DataItem = null, raw: Array[Byte] = null): DataItem = {
    map.foldLeft[DataItem](create(template, raw))( (di, pair) => {
      val key = new ItemPath(pair._1)
      val value: Any = pair._2

      di.put(key, makeInsertable(value))

      di
    })
  }

  def fromJSON(json: String, template: DataItem = null, raw: Array[Byte] = null): DataItem = {
    val map = jsonToMap(json) match {
      case (Some(maps: Map[String, Any])) => maps
      case _ => Map[String, Any]()
    }

    fromMap(map, template, raw)
  }
}

private class HashDataItem(val raw: Array[Byte]) extends HashMap[String, Any] with DataItem {
  private def copyElement(value: Any): Any = {
    value match {
      case d: DataItem => d.copy()
      case d: List[Any] => d.map(copyElement)
      case d => d
    }
  }

  def copy(): DataItem = {
    this.foldLeft[HashDataItem](DataItem.create(this).asInstanceOf[HashDataItem])( (newItem, valPair: (String, Any)) => {
      val key = valPair._1
      val value = copyElement(valPair._2)

      newItem.put(key, value)

      newItem
    })
  }

  def getRaw(): Array[Byte] = raw

  // convert to specific represenation of key
  def get[T: ClassTag](key: ItemPath): Option[T] = get[T](key.internal)

  def put(key: ItemPath, value: Any) {
    put(key.internal, value)
  }

  def contains[T: ClassTag](key: ItemPath): Boolean = {
    get[T](key) match {
      case Some(x) => true
      case None    => false
    }
  }

  def get[T: ClassTag](key: ItemPath.InternalPath): Option[T] = {
    key match {
      case Nil          => None // not found
      case head :: path => {
        head match {
          case Left(hp) => {
            get(hp) match {
              case Some(data: HashDataItem) => {
                path match {
                  case Nil => Some(data.asInstanceOf[T])
                  case _   => data.get[T](path)
                }
              }

              case Some(data: T) => {
                Some(data)
              }

              case Some(d) => println(" actual type: " + d.getClass + " for path " + key); Thread.dumpStack(); None
              case None => None
            }
          }

          case Right(indexed) => {
            val (hpath, offset) = indexed

            get(hpath) match {
              case Some(lst: List[T]) => {
                lst(offset) match {
                  case data: HashDataItem => {
                    path match {
                      case Nil => Some(data.asInstanceOf[T])
                      case _   => data.get[T](path)
                    }
                  }

                  case data : T => {
                    Some(data)
                  }

                  case _ => None
                }
              }

              case _ => None
            }
          }
        }
      }
    }
  }

  def getMandatory[T: ClassTag](key: ItemPath): T = {
    this.get[T](key) match {
      case Some(v: T) => v
      case _          => throw DataItemException("Field: '%s'.".format(key), DataItemErrorType.CannotFindMandatoryField)
    }
  }

  def put(key: ItemPath.InternalPath, value: Any) {
    key match {
      case Nil => None
      case head :: Nil => {
        head match {
          case Left(str) => put(str, value)
          case Right(indexed) => {
            val (hpath, offset) = indexed

            put(hpath, get(hpath) match {
              case Some(lst: List[Any]) => value :: lst
              case _                    => List[Any](value)
            })
          }
        }
      }

      case head :: path => {
        head match {
          case Left(str) => {
            val next = get(str) match {
              case Some(di: HashDataItem) => di
              case _ => new HashDataItem(null)
            }

            next.put(path, value)
            put(str, next)
          }

          case Right(indexed) => {
            val (hpath, offset) = indexed

            put(hpath, get(hpath) match {
              case Some(lst: List[Any]) => {
                val newItem = new HashDataItem(null)

                newItem.put(path, value)

                newItem :: lst
              }
              case _                    => {
                val newItem = new HashDataItem(null)

                newItem.put(path, value)

                List[Any](newItem)
              }
            })
          }
        }
      }
    }
  }

  def remove(key: ItemPath): Option[Any] = {
    remove(key.internal)
  }

  def remove(key: ItemPath.InternalPath): Option[Any] = {
    def dropIndex[T](xs: List[T], n: Int): List[T] = {
      val (l1, l2) = xs splitAt n
      l1 ::: (l2 drop 1)
    }

    key match {
      case Nil         => None
      case head :: Nil => {
        head match {
          case Left(str) => remove(str)
          case Right(indexed) => {
            val (hpath, offset) = indexed

            get(hpath) match {
              case Some(lst: List[Any]) => {
                val newList = dropIndex(lst, offset)
                put(hpath, newList)

                Some(lst)
              }

              case _ => None
            }
          }
        }
      }

      case head :: path => {
        head match {
          case Left(str) => {
            get(str) match {
              case Some(item: HashDataItem) => item.remove(path)
              case Some(x) => remove(str)
              case None    => None
            }
          }

          case Right(indexed) => {
            val (hpath, offset) = indexed

            get(hpath) match {
              case Some(lst: List[Any]) => lst(offset).asInstanceOf[HashDataItem].remove(path)
              case _ => None
            }
          }
        }
      }
    }
  }

  private def formatter(value: Any): String = {
    val def_format : JSONFormat.ValueFormatter = JSONFormat.defaultFormatter

    value match {
      case item: DataItem => item.toJSON()
      case lst: List[Any] => {
        if (lst.length > 0) {
          "[ " + lst.map(formatter).reduce((s1, s2) => {
            s1 + ", " + s2
          }) + " ]"
        } else {
          "[ ]"
        }
      }
      case v => def_format(v)
    }
  }

  def toJSON(): String = {
    if(this.size == 0) {
      "{ }"
    } else {
      "{ " + this.map( (pair: (String, Any)) => {
        val key = pair._1
        val value = pair._2

        formatter(key) + ":" + formatter(value)
      }).reduce( (s1, s2) => {
        s1 + ", " + s2
      }) + " }"
    }
  }
}