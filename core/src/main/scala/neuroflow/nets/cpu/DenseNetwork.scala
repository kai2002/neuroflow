package neuroflow.nets.cpu

import breeze.linalg._
import breeze.stats._
import neuroflow.core.IllusionBreaker.SettingsNotSupportedException
import neuroflow.core.Network._
import neuroflow.core.WeightProvider.BuildsWeightsFor
import neuroflow.core._
import neuroflow.dsl.{Focus, Layer}

import scala.annotation.tailrec
import scala.collection.Seq


/**
  *
  * This is a feed-forward neural network with fully connected layers.
  * It uses gradient descent to optimize the specified loss function.
  *
  * @author bogdanski
  * @since 15.01.16
  *
  */


object DenseNetwork {

  implicit object double extends Constructor[Double, DenseNetworkDouble] {
    def apply(ls: Seq[Layer], loss: LossFunction[Double], settings: Settings[Double])(implicit weightProvider: WeightProvider[Double]): DenseNetworkDouble = {
      DenseNetworkDouble(ls, loss, settings, weightProvider(ls))
    }
  }

  implicit object weights_double extends neuroflow.core.WeightProvider.FFN[Double]

  implicit object single extends Constructor[Float, DenseNetworkSingle] {
    def apply(ls: Seq[Layer], loss: LossFunction[Float], settings: Settings[Float])(implicit weightProvider: WeightProvider[Float]): DenseNetworkSingle = {
      DenseNetworkSingle(ls, loss, settings, weightProvider(ls))
    }
  }

  implicit object weights_single extends neuroflow.core.WeightProvider.FFN[Float]

}

//<editor-fold defaultstate="collapsed" desc="Double Precision Impl">

private[nets] case class DenseNetworkDouble(layers: Seq[Layer], lossFunction: LossFunction[Double], settings: Settings[Double], weights: Weights[Double],
                                            identifier: String = "neuroflow.nets.cpu.DenseNetwork", numericPrecision: String = "Double")
  extends FFN[Double] with WaypointLogic[Double] {

  type Vector   = DenseVector[Double]
  type Matrix   = DenseMatrix[Double]
  type Vectors  = Seq[DenseVector[Double]]
  type Matrices = Seq[DenseMatrix[Double]]

  private val _layers = layers.map {
    case Focus(inner) => inner
    case layer: Layer => layer
  }.toArray

  private val _focusLayer   = layers.collectFirst { case c: Focus[_] => c }
  private val _layersNI     = _layers.tail.map { case h: HasActivator[Float]  => h.activator.map[Double](_.toFloat, _.toDouble) }
  private val _outputDim    = _layers.last.neurons
  private val _lastLayerIdx = weights.size - 1


  /**
    * Checks if the [[Settings]] are properly defined.
    * Might throw a [[SettingsNotSupportedException]].
    */
  override def checkSettings(): Unit = {
    super.checkSettings()
    if (settings.specifics.isDefined)
      warn("No specific settings supported. This has no effect.")
    if (settings.regularization.isDefined) {
      throw new SettingsNotSupportedException("Regularization is not supported.")
    }
  }

  /**
    * Computes output for `x`.
    */
  def apply(x: Vector): Vector = {
    val input = DenseMatrix.create[Double](1, x.size, x.toArray)
    _focusLayer.map { cl =>
      val i = layers.indexOf(cl) - 1
      val r = flow(input, i)
      r
    }.getOrElse {
      val r = flow(input, _lastLayerIdx)
      lossFunction match {
        case _: SquaredMeanError[_] => r
        case _: Softmax[_]          => SoftmaxImpl(r)
        case _                      => r
      }
    }.toDenseVector
  }

  /**
    * Trains this net with input `xs` against output `ys`.
    */
  def train(xs: Vectors, ys: Vectors): Unit = {
    require(xs.size == ys.size, "Mismatch between sample sizes!")
    import settings._
    val batchSize = settings.batchSize.getOrElse(xs.size)
    if (settings.verbose) {
      info(s"Training with ${xs.size} samples, batch size = $batchSize, batches = ${math.ceil(xs.size.toDouble / batchSize.toDouble).toInt}.")
      info(s"Breeding batches ...")
    }
    val xsys = xs.map(_.asDenseMatrix).zip(ys.map(_.asDenseMatrix)).grouped(batchSize).toSeq.map { xy =>
      xy.par.map(_._1).reduce(DenseMatrix.vertcat(_, _)) -> xy.par.map(_._2).reduce(DenseMatrix.vertcat(_, _))
    }
    run(xsys, learningRate(1 -> 1.0), precision, batch = 0, batches = xsys.size, iteration = 1, iterations)
  }

  /**
    * The training loop.
    */
  @tailrec private def run(xsys: Seq[(Matrix, Matrix)], stepSize: Double, precision: Double, batch: Int,
                           batches: Int, iteration: Int, maxIterations: Int): Unit = {
    val (x, y) = (xsys(batch)._1, xsys(batch)._2)
    val loss =
      if (settings.approximation.isDefined) adaptWeightsApprox(x, y, stepSize)
      else adaptWeights(x, y, stepSize)
    val lossMean = mean(loss)
    if (settings.verbose) info(f"Iteration $iteration.${batch + 1}, Avg. Loss = $lossMean%.6g, Vector: $loss")
    maybeGraph(lossMean)
    waypoint(iteration)
    if (lossMean > precision && iteration < maxIterations) {
      run(xsys, settings.learningRate(iteration + 1 -> stepSize), precision, (batch + 1) % batches, batches, iteration + 1, maxIterations)
    } else {
      info(f"Took $iteration of $maxIterations iterations.")
    }
  }

  /**
    * Computes the network recursively.
    */
  private def flow(in: Matrix, outLayer: Int): Matrix = {
    val fa  = collection.mutable.Map.empty[Int, Matrix]
    @tailrec def forward(in: Matrix, i: Int): Unit = {
      val p = in * weights(i)
      val a = p.map(_layersNI(i))
      fa += i -> a
      if (i < outLayer) forward(a, i + 1)
    }
    forward(in, 0)
    fa(outLayer)
  }

  /**
    * Computes gradient for weights with respect to given batch,
    * adapts their value using gradient descent and returns the loss matrix.
    */
  private def adaptWeights(x: Matrix, y: Matrix, stepSize: Double): Matrix = {

    import settings.updateRule

    val loss = DenseMatrix.zeros[Double](x.rows, _outputDim)

    val fa  = collection.mutable.Map.empty[Int, Matrix]
    val fb  = collection.mutable.Map.empty[Int, Matrix]
    val dws = collection.mutable.Map.empty[Int, Matrix]
    val ds  = collection.mutable.Map.empty[Int, Matrix]

    @tailrec def forward(in: Matrix, i: Int): Unit = {
      val p = in * weights(i)
      val a = p.map(_layersNI(i))
      val b = p.map(_layersNI(i).derivative)
      fa += i -> a
      fb += i -> b
      if (i < _lastLayerIdx) forward(a, i + 1)
    }

    @tailrec def derive(i: Int): Unit = {
      if (i == 0 && _lastLayerIdx == 0) {
        val (err, grad) = lossFunction(y, fa(0))
        val d = grad *:* fb(0)
        val dw = x.t * d
        dws += 0 -> dw
        loss += err
      } else if (i == _lastLayerIdx) {
        val (err, grad) = lossFunction(y, fa(i))
        val d = grad *:* fb(i)
        val dw = fa(i - 1).t * d
        dws += i -> dw
        ds += i -> d
        loss += err
        derive(i - 1)
      } else if (i < _lastLayerIdx && i > 0) {
        val d = (ds(i + 1) * weights(i + 1).t) *:* fb(i)
        val dw = fa(i - 1).t * d
        dws += i -> dw
        ds += i -> d
        derive(i - 1)
      } else if (i == 0) {
        val d = (ds(i + 1) * weights(i + 1).t) *:* fb(i)
        val dw = x.t * d
        dws += i -> dw
      }
    }

    forward(x, 0)
    derive(_lastLayerIdx)

    (0 to _lastLayerIdx).foreach(i => updateRule(weights(i), dws(i), stepSize, i))

    val lossReduced = (loss.t * DenseMatrix.ones[Double](loss.rows, 1)).t
    lossReduced

  }

  /** For debugging, approximates the gradients using `settings.approximation`. */
  private def adaptWeightsApprox(xs: Matrix, ys: Matrix, stepSize: Double): Matrix = {

    require(settings.updateRule.isInstanceOf[Debuggable[Double]])
    val _rule: Debuggable[Double] = settings.updateRule.asInstanceOf[Debuggable[Double]]

    def lossFunc(): Matrix = {
      val loss = lossFunction(ys, flow(xs, _lastLayerIdx))._1
      val reduced = (loss.t * DenseMatrix.ones[Double](loss.rows, 1)).t
      reduced
    }

    val out = lossFunc()

    def approximateGradients(weightLayer: Int, weight: (Int, Int)): Double = {
      sum(settings.approximation.get.apply(weights, lossFunc, () => (), weightLayer, weight))
    }

    val updates = collection.mutable.HashMap.empty[(Int, (Int, Int)), Double]
    val grads   = collection.mutable.HashMap.empty[(Int, (Int, Int)), Double]
    val debug   = collection.mutable.HashMap.empty[Int, Matrix]

    weights.zipWithIndex.foreach {
      case (l, idx) =>
        debug += idx -> l.copy
        l.foreachPair { (k, v) =>
          val grad = approximateGradients(idx, k)
          updates += (idx, k) -> (v - (stepSize * grad))
          grads += (idx, k) -> grad
        }
    }

    updates.foreach {
      case ((wl, k), v) =>
        weights(wl).update(k, v)
    }

    grads.foreach {
      case ((wl, k), v) =>
        debug(wl).update(k, v)
    }

    _rule.lastGradients = debug

    out

  }

}

//</editor-fold>

//<editor-fold defaultstate="collapsed" desc="Single Precision Impl">

private[nets] case class DenseNetworkSingle(layers: Seq[Layer], lossFunction: LossFunction[Float], settings: Settings[Float], weights: Weights[Float],
                                            identifier: String = "neuroflow.nets.cpu.DenseNetwork", numericPrecision: String = "Single")
  extends FFN[Float] with WaypointLogic[Float] {

  type Vector   = DenseVector[Float]
  type Matrix   = DenseMatrix[Float]
  type Vectors  = Seq[DenseVector[Float]]
  type Matrices = Seq[DenseMatrix[Float]]

  private val _layers = layers.map {
    case Focus(inner) => inner
    case layer: Layer => layer
  }.toArray

  private val _focusLayer   = layers.collectFirst { case c: Focus[_] => c }
  private val _layersNI     = _layers.tail.map { case h: HasActivator[Double] => h.activator.map[Float](_.toDouble, _.toFloat) }
  private val _outputDim    = _layers.last.neurons
  private val _lastLayerIdx = weights.size - 1


  /**
    * Checks if the [[Settings]] are properly defined.
    * Might throw a [[SettingsNotSupportedException]].
    */
  override def checkSettings(): Unit = {
    super.checkSettings()
    if (settings.specifics.isDefined)
      warn("No specific settings supported. This has no effect.")
    if (settings.regularization.isDefined) {
      throw new SettingsNotSupportedException("Regularization is not supported.")
    }
  }

  /**
    * Computes output for `x`.
    */
  def apply(x: Vector): Vector = {
    val input = DenseMatrix.create[Float](1, x.size, x.toArray)
    _focusLayer.map { cl =>
      val i = layers.indexOf(cl) - 1
      val r = flow(input, i)
      r
    }.getOrElse {
      val r = flow(input, _lastLayerIdx)
      lossFunction match {
        case _: SquaredMeanError[_] => r
        case _: Softmax[_]          => SoftmaxImpl(r)
        case _                      => r
      }
    }.toDenseVector
  }

  /**
    * Trains this net with input `xs` against output `ys`.
    */
  def train(xs: Vectors, ys: Vectors): Unit = {
    require(xs.size == ys.size, "Mismatch between sample sizes!")
    import settings._
    val batchSize = settings.batchSize.getOrElse(xs.size)
    if (settings.verbose) {
      info(s"Training with ${xs.size} samples, batch size = $batchSize, batches = ${math.ceil(xs.size.toDouble / batchSize.toDouble).toInt}.")
      info(s"Breeding batches ...")
    }
    val xsys = xs.map(_.asDenseMatrix).zip(ys.map(_.asDenseMatrix)).grouped(batchSize).toSeq.map { xy =>
      xy.par.map(_._1).reduce(DenseMatrix.vertcat(_, _)) -> xy.par.map(_._2).reduce(DenseMatrix.vertcat(_, _))
    }
    run(xsys, learningRate(1 -> 1.0).toFloat, precision, batch = 0, batches = xsys.size, iteration = 1, iterations)
  }

  /**
    * The training loop.
    */
  @tailrec private def run(xsys: Seq[(Matrix, Matrix)], stepSize: Float, precision: Double, batch: Int,
                           batches: Int, iteration: Int, maxIterations: Int): Unit = {
    val (x, y) = (xsys(batch)._1, xsys(batch)._2)
    val loss =
      if (settings.approximation.isDefined) adaptWeightsApprox(x, y, stepSize)
      else adaptWeights(x, y, stepSize)
    val lossMean = mean(loss)
    if (settings.verbose) info(f"Iteration $iteration.${batch + 1}, Avg. Loss = $lossMean%.6g, Vector: $loss")
    maybeGraph(lossMean)
    waypoint(iteration)
    if (lossMean > precision && iteration < maxIterations) {
      run(xsys, settings.learningRate(iteration + 1 -> stepSize).toFloat, precision, (batch + 1) % batches, batches, iteration + 1, maxIterations)
    } else {
      info(f"Took $iteration of $maxIterations iterations.")
    }
  }

  /**
    * Computes the network recursively.
    */
  private def flow(in: Matrix, outLayer: Int): Matrix = {
    val fa  = collection.mutable.Map.empty[Int, Matrix]
    @tailrec def forward(in: Matrix, i: Int): Unit = {
      val p = in * weights(i)
      val a = p.map(_layersNI(i))
      fa += i -> a
      if (i < outLayer) forward(a, i + 1)
    }
    forward(in, 0)
    fa(outLayer)
  }

  /**
    * Computes gradient for weights with respect to given batch,
    * adapts their value using gradient descent and returns the loss matrix.
    */
  private def adaptWeights(x: Matrix, y: Matrix, stepSize: Float): Matrix = {

    import settings.updateRule

    val loss = DenseMatrix.zeros[Float](x.rows, _outputDim)

    val fa  = collection.mutable.Map.empty[Int, Matrix]
    val fb  = collection.mutable.Map.empty[Int, Matrix]
    val dws = collection.mutable.Map.empty[Int, Matrix]
    val ds  = collection.mutable.Map.empty[Int, Matrix]

    @tailrec def forward(in: Matrix, i: Int): Unit = {
      val p = in * weights(i)
      val a = p.map(_layersNI(i))
      val b = p.map(_layersNI(i).derivative)
      fa += i -> a
      fb += i -> b
      if (i < _lastLayerIdx) forward(a, i + 1)
    }

    @tailrec def derive(i: Int): Unit = {
      if (i == 0 && _lastLayerIdx == 0) {
        val (err, grad) = lossFunction(y, fa(0))
        val d = grad *:* fb(0)
        val dw = x.t * d
        dws += 0 -> dw
        loss += err
      } else if (i == _lastLayerIdx) {
        val (err, grad) = lossFunction(y, fa(i))
        val d = grad *:* fb(i)
        val dw = fa(i - 1).t * d
        dws += i -> dw
        ds += i -> d
        loss += err
        derive(i - 1)
      } else if (i < _lastLayerIdx && i > 0) {
        val d = (ds(i + 1) * weights(i + 1).t) *:* fb(i)
        val dw = fa(i - 1).t * d
        dws += i -> dw
        ds += i -> d
        derive(i - 1)
      } else if (i == 0) {
        val d = (ds(i + 1) * weights(i + 1).t) *:* fb(i)
        val dw = x.t * d
        dws += i -> dw
      }
    }

    forward(x, 0)
    derive(_lastLayerIdx)

    (0 to _lastLayerIdx).foreach(i => updateRule(weights(i), dws(i), stepSize, i))

    val lossReduced = (loss.t * DenseMatrix.ones[Float](loss.rows, 1)).t
    lossReduced

  }

  /** For debugging, approximates the gradients using `settings.approximation`. */
  private def adaptWeightsApprox(xs: Matrix, ys: Matrix, stepSize: Float): Matrix = {

    require(settings.updateRule.isInstanceOf[Debuggable[Float]])
    val _rule: Debuggable[Float] = settings.updateRule.asInstanceOf[Debuggable[Float]]

    def lossFunc(): Matrix = {
      val loss = lossFunction(ys, flow(xs, _lastLayerIdx))._1
      val reduced = (loss.t * DenseMatrix.ones[Float](loss.rows, 1)).t
      reduced
    }

    val out = lossFunc()

    def approximateGradient(weightLayer: Int, weight: (Int, Int)): Float = {
      sum(settings.approximation.get.apply(weights, lossFunc, () => (), weightLayer, weight))
    }

    val updates = collection.mutable.HashMap.empty[(Int, (Int, Int)), Float]
    val grads   = collection.mutable.HashMap.empty[(Int, (Int, Int)), Float]
    val debug   = collection.mutable.HashMap.empty[Int, Matrix]

    weights.zipWithIndex.foreach {
      case (l, idx) =>
        debug += idx -> l.copy
        l.foreachPair { (k, v) =>
          val grad = approximateGradient(idx, k)
          updates += (idx, k) -> (v - (stepSize * grad))
          grads += (idx, k) -> grad
        }
    }

    updates.foreach {
      case ((wl, k), v) =>
        weights(wl).update(k, v)
    }

    grads.foreach {
      case ((wl, k), v) =>
        debug(wl).update(k, v)
    }

    _rule.lastGradients = debug

    out

  }

}

//</editor-fold>