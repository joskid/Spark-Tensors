package tensor.ml.kge.predict

import org.apache.spark.sql._

import tensor.ml.kge.models._

class TransE(model: tensor.ml.kge.models.TransE, test: DataFrame, sk: SparkSession) extends Predict(test: DataFrame) {

  def head(i: Int, r: Row) = {
    Row(i, r.getInt(1), r.getInt(2))
  }

  def tail(i: Int, r: Row) = {
    Row(r.getInt(0), r.getInt(1), i)
  }

  def leftRank(row: Row) = {

    var x: Seq[Float] = List()
    val y = model.myL(model.dist(row))

    x = y +: x
    for (i <- 1 until model.entities.length) {
      x = model.myL(model.dist(head(i, row))) +: x
    }

    x.sorted.indexOf(y)
  }

  def rightRank(row: Row) = {

    var x: Seq[Float] = List()
    val y = model.myL(model.dist(row))

    x = y +: x
    for (i <- 1 until model.entities.length) {
      x = model.myL(model.dist(tail(i, row))) +: x
    }

    x.sorted.indexOf(y)
  }

}