package net.sansa_stack.ml.kge.model

import ml.dmlc.mxnet._
import ml.dmlc.mxnet.{Symbol => s}
import scala.io.Source
import scala.util.Random
import ml.dmlc.mxnet.optimizer.Adam
import net.sansa_stack.ml.kge.Hits

/**
  * Created by nilesh on 01/06/2017.
  */
class TransE(numEntities: Int, numRelations: Int, latentFactors: Int, numBatches: Int) {
  def getNet(): (Symbol, Seq[String]) = {
    // embedding weight vectors
    val entityWeight = s.Variable("entityWeight")
    val relationWeight = s.Variable("relationWeight")

    def entityEmbedding(data: Symbol) =
      s.Embedding("entity")()(Map("data" -> data, "input_dim" -> numEntities, "output_dim" -> latentFactors))

    def relationEmbedding(data: Symbol) =
      s.Embedding("relation")()(Map("data" -> data, "input_dim" -> numRelations, "output_dim" -> latentFactors))

    // inputs
    var head = s.Variable("subjectEntity")
    var relation = s.Variable("predicateRelation")
    var tail = s.Variable("objectEntity")
    var corruptHead = s.Variable("corruptSubjectEntity")
    var corruptTail = s.Variable("corruptObjectEntity")

    head = entityEmbedding(head)
    relation = relationEmbedding(relation)
    tail = entityEmbedding(tail)
    corruptHead = entityEmbedding(corruptHead)
    corruptTail = entityEmbedding(corruptTail)

    import net.sansa_stack.ml.kge.{L2Similarity, MaxMarginLoss}
    def getScore(head: Symbol, relation: Symbol, tail: Symbol) = {
      L2Similarity(head + relation, tail)
    }

    val posScore = getScore(head, relation, tail)
    val negScore = getScore(corruptHead, relation, corruptTail)
    val loss = MaxMarginLoss(1.0f)(posScore, negScore)

    (loss, Seq("subjectEntity", "predicateRelation", "objectEntity", "corruptSubjectEntity", "corruptObjectEntity"))
  }

  def train() = {
    val batchSize = 1000

    val ctx = Context.cpu()
    //  val numEntities = 40943
    val (transeModel, paramNames) = getNet()

    import ml.dmlc.mxnet.Xavier

    val initializer = new Xavier(rndType = "gaussian")

    val (argShapes, outputShapes, auxShapes) = transeModel.inferShape(
      (for (paramName <- paramNames) yield paramName -> Shape(batchSize, 1))
        toMap)

    val argNames = transeModel.listArguments()
    val argDict = argNames.zip(argShapes.map(NDArray.empty(_, ctx))).toMap
    val gradDict = argNames.zip(argShapes).filter {
      case (name, shape) =>
        !paramNames.contains(name)
    }.map(x => x._1 -> NDArray.empty(x._2, ctx)).toMap
    argDict.foreach {
      case (name, ndArray) =>
        if (!paramNames.contains(name)) {
          initializer.initWeight(name, ndArray)
        }
    }

    def readData(path: String) = {
      val triples = Source.fromFile(path).getLines().map(_.split(","))
      (triples.map(_(0).toFloat).toArray,
        triples.map(_(1).toFloat).toArray,
        triples.map(_(2).toFloat).toArray,
        triples.map(x => Random.nextInt(numEntities).toFloat).toArray,
        triples.map(x => Random.nextInt(numEntities).toFloat).toArray)
    }

    def readDataBatched(path: String) = {
      val triples = Source.fromFile(path).getLines().map(_.split(","))
      (triples.map(_(0).toFloat).toArray.grouped(numBatches).toSeq,
        triples.map(_(1).toFloat).toArray.grouped(numBatches).toSeq,
        triples.map(_(2).toFloat).toArray.grouped(numBatches).toSeq,
        triples.map(x => Random.nextInt(numEntities).toFloat).toArray.grouped(numBatches).toSeq,
        triples.map(x => Random.nextInt(numEntities).toFloat).toArray.grouped(numBatches).toSeq)
    }

    def file(stage: String) = s"/home/nilesh/utils/Spark-Tensors/data/$stage.txt"

    val executor = transeModel.bind(ctx, argDict, gradDict)

    val opt = new Adam(learningRate = 0.00005f, wd = 0.0001f)
    val paramsGrads = gradDict.toList.zipWithIndex.map { case ((name, grad), idx) =>
      (idx, name, grad, opt.createState(idx, argDict(name)))
    }

    val head = argDict("subjectEntity")
    val relation = argDict("predicateRelation")
    val tail = argDict("objectEntity")
    val corruptHead = argDict("corruptSubjectEntity")
    val corruptTail = argDict("corruptObjectEntity")

    val (trainSubjects, trainRelations, trainObjects, trainCorruptSubjects, trainCorruptObjects) = readDataBatched(file("train"))
    val (testSubjects, testRelations, testObjects, _, _) = readDataBatched(file("test"))

    var iter = 0
    var minTestHits = 100f
    for (epoch <- 0 until 100000) {
      head.set(trainSubjects(iter))
      relation.set(trainRelations(iter))
      tail.set(trainObjects(iter))
      corruptHead.set(trainCorruptSubjects(iter))
      corruptTail.set(trainCorruptObjects(iter))
      iter += 1

      if (iter >= trainSubjects.length) iter = 0

      executor.forward(isTrain = true)
      executor.backward()

      paramsGrads.foreach {
        case (idx, name, grad, optimState) =>
          opt.update(idx, argDict(name), grad, optimState)
      }

      println(s"iter $epoch, training Hits@1: ${Math.sqrt(Hits.hitsAt1(NDArray.ones(batchSize), executor.outputs(0)) / batchSize)}, min test Hits@1: $minTestHits")
      if (epoch != 0 && epoch % 50 == 0) {
        val tmp = for (i <- 0 until testSubjects.length) yield {
          head.set(testSubjects(iter))
          relation.set(testRelations(iter))
          tail.set(testObjects(iter))

          executor.forward(isTrain = false)
          Hits.hitsAt1(NDArray.ones(batchSize), executor.outputs(0))
        }
        val testHits = Math.sqrt(tmp.toArray.sum / (testSubjects.length * batchSize))
        if (testHits < minTestHits) minTestHits = testHits.toFloat
      }
    }

  }
}