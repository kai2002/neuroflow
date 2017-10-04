package neuroflow.nets.cpu

import breeze.linalg._
import breeze.numerics._
import breeze.stats._
import neuroflow.core.EarlyStoppingLogic.CanAverage
import neuroflow.core.IllusionBreaker.SettingsNotSupportedException
import neuroflow.core.Network._
import neuroflow.core._

import scala.annotation.tailrec
import scala.collection.Seq
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool


/**
  *
  * This is a feed-forward neural network with fully connected layers.
  * It uses gradient descent to optimize the specified loss function.
  *
  * Use the parallelism parameter with care, as it greatly affects memory usage.
  *
  * @author bogdanski
  * @since 15.01.16
  *
  */


object DenseNetwork {

  implicit val double: Constructor[Double, DenseNetworkDouble] = new Constructor[Double, DenseNetworkDouble] {
    def apply(ls: Seq[Layer], settings: Settings[Double])(implicit weightProvider: WeightProvider[Double]): DenseNetworkDouble = {
      DenseNetworkDouble(ls, settings, weightProvider(ls))
    }
  }

  implicit val single: Constructor[Float, DenseNetworkSingle] = new Constructor[Float, DenseNetworkSingle] {
    def apply(ls: Seq[Layer], settings: Settings[Float])(implicit weightProvider: WeightProvider[Float]): DenseNetworkSingle = {
      DenseNetworkSingle(ls, settings, weightProvider(ls))
    }
  }

}

//<editor-fold defaultstate="collapsed" desc="Double Precision Impl">

private[nets] case class DenseNetworkDouble(layers: Seq[Layer], settings: Settings[Double], weights: Weights[Double],
                                            identifier: String = "neuroflow.nets.cpu.DenseNetwork", numericPrecision: String = "Double")
  extends FFN[Double] with EarlyStoppingLogic[Double] with KeepBestLogic[Double] with WaypointLogic[Double] {

  type Vector   = Network.Vector[Double]
  type Vectors  = Network.Vectors[Double]
  type Matrix   = Network.Matrix[Double]
  type Matrices = Network.Matrices[Double]

  private val _layers = layers.map {
    case Focus(inner) => inner
    case layer: Layer => layer
  }.toArray

  private val _focusLayer     = layers.collect { case c: Focus[_] => c }.headOption

  private val _layersNI       = _layers.tail.map { case h: HasActivator[Double] => h }
  private val _outputDim      = _layers.last.neurons
  private val _lastWlayerIdx  = weights.size - 1

  private val _forkJoinTaskSupport = new ForkJoinTaskSupport(new ForkJoinPool(settings.parallelism.getOrElse(1)))

  private implicit object Average extends CanAverage[Double, DenseNetworkDouble, Vector, Vector] {
    def averagedError(xs: Vectors, ys: Vectors): Double = {
      val errors = xs.map(evaluate).zip(ys).map {
        case (a, b) => mean(abs(a - b))
      }
      mean(errors)
    }
  }

  /**
    * Checks if the [[Settings]] are properly defined.
    * Might throw a [[SettingsNotSupportedException]].
    */
  override def checkSettings(): Unit = {
    super.checkSettings()
    if (settings.specifics.isDefined)
      warn("No specific settings supported. This has no effect.")
    settings.regularization.foreach {
      case _: EarlyStopping[_, _] | KeepBest =>
      case _ => throw new SettingsNotSupportedException("This regularization is not supported.")
    }
  }

  /**
    * Trains this net with input `xs` against output `ys`.
    */
  def train(xs: Vectors, ys: Vectors): Unit = {
    require(xs.size == ys.size, "Mismatch between sample sizes!")
    import settings._
    val batchSize = settings.batchSize.getOrElse(xs.size)
    if (settings.verbose) info(s"Training with ${xs.size} samples, batchSize = $batchSize ...")
    val xsys = xs.map(_.asDenseMatrix).zip(ys.map(_.asDenseMatrix)).grouped(batchSize).toSeq
    run(xsys, learningRate(1 -> 1.0), xs.size, precision, 1, iterations)
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
      val r = flow(input, _lastWlayerIdx)
      settings.lossFunction match {
        case _: SquaredMeanError[_] => r
        case _: Softmax[_]          => SoftmaxImpl(r)
        case _                      => r
      }
    }.toDenseVector
  }

  /**
    * The training loop.
    */
  @tailrec private def run(xsys: Seq[Seq[(Matrix, Matrix)]], stepSize: Double, sampleSize: Double, precision: Double,
                           iteration: Int, maxIterations: Int): Unit = {
    val _em = xsys.map { batch =>
      val (x, y) = (batch.map(_._1), batch.map(_._2))
      val error =
        if (settings.approximation.isDefined)
          adaptWeightsApprox(x, y, stepSize)
        else adaptWeights(x, y, stepSize)
      error
    }.reduce(_ + _)
    val errorPerS = _em / sampleSize
    val errorMean = mean(errorPerS)
    if (settings.verbose) info(f"Iteration $iteration - Loss $errorMean%.6g - Loss Vector $errorPerS")
    maybeGraph(errorMean)
    keepBest(errorMean)
    waypoint(iteration)
    if (errorMean > precision && iteration < maxIterations && !shouldStopEarly) {
      run(xsys, settings.learningRate(iteration + 1 -> stepSize), sampleSize, precision, iteration + 1, maxIterations)
    } else {
      info(f"Took $iteration iterations of $maxIterations with Loss = $errorMean%.6g")
      takeBest()
    }
  }

  /**
    * Computes the network recursively.
    */
  private def flow(in: Matrix, outLayer: Int): Matrix = {
    val fa  = collection.mutable.Map.empty[Int, Matrix]
    @tailrec def forward(in: Matrix, i: Int): Unit = {
      val p = in * weights(i)
      val a = p.map(_layersNI(i).activator)
      fa += i -> a
      if (i < outLayer) forward(a, i + 1)
    }
    forward(in, 0)
    fa(outLayer)
  }

  /**
    * Computes gradient for all weights in parallel,
    * adapts their value using gradient descent and returns the error matrix.
    */
  private def adaptWeights(xs: Matrices, ys: Matrices, stepSize: Double): Matrix = {

    import settings.lossFunction

    val xsys = xs.par.zip(ys)
    xsys.tasksupport = _forkJoinTaskSupport

    val _ds = (0 to _lastWlayerIdx).map { i =>
      i -> DenseMatrix.zeros[Double](weights(i).rows, weights(i).cols)
    }.toMap

    val _errSum = DenseMatrix.zeros[Double](1, _outputDim)

    xsys.map { xy =>

      val (x, y) = xy
      val fa  = collection.mutable.Map.empty[Int, Matrix]
      val fb  = collection.mutable.Map.empty[Int, Matrix]
      val dws = collection.mutable.Map.empty[Int, Matrix]
      val ds  = collection.mutable.Map.empty[Int, Matrix]
      val e   = DenseMatrix.zeros[Double](1, _outputDim)

      @tailrec def forward(in: Matrix, i: Int): Unit = {
        val p = in * weights(i)
        val a = p.map(_layersNI(i).activator)
        val b = p.map(_layersNI(i).activator.derivative)
        fa += i -> a
        fb += i -> b
        if (i < _lastWlayerIdx) forward(a, i + 1)
      }

      @tailrec def derive(i: Int): Unit = {
        if (i == 0 && _lastWlayerIdx == 0) {
          val (err, grad) = lossFunction(y, fa(0))
          val d = grad *:* fb(0)
          val dw = x.t * d
          dws += 0 -> dw
          e += err
        } else if (i == _lastWlayerIdx) {
          val (err, grad) = lossFunction(y, fa(i))
          val d = grad *:* fb(i)
          val dw = fa(i - 1).t * d
          dws += i -> dw
          ds += i -> d
          e += err
          derive(i - 1)
        } else if (i < _lastWlayerIdx && i > 0) {
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
      derive(_lastWlayerIdx)

      (dws, e)

    }.seq.foreach { ab =>
      _errSum += ab._2
      var i = 0
      while (i <= _lastWlayerIdx) {
        val m = _ds(i)
        val n = ab._1(i)
        m += n
        i += 1
      }
    }

    var i = 0
    while (i <= _lastWlayerIdx) {
      settings.updateRule(weights(i), _ds(i), stepSize, i)
      i += 1
    }

    _errSum

  }

  /** Approximates the gradient based on finite central differences. (For debugging) */
  private def adaptWeightsApprox(xs: Matrices, ys: Matrices, stepSize: Double): Matrix = {

    require(settings.updateRule.isInstanceOf[Debuggable[Double]])
    val _rule: Debuggable[Double] = settings.updateRule.asInstanceOf[Debuggable[Double]]

    def errorFunc(): Matrix = {
      val xsys = xs.zip(ys).par
      xsys.tasksupport = _forkJoinTaskSupport
      xsys.map { case (x, y) => settings.lossFunction(y, flow(x, _lastWlayerIdx))._1 }.reduce(_ + _)
    }

    def approximateErrorFuncDerivative(weightLayer: Int, weight: (Int, Int)): Matrix = {
      val Δ = settings.approximation.get.Δ
      val v = weights(weightLayer)(weight)
      weights(weightLayer).update(weight, v - Δ)
      val a = errorFunc()
      weights(weightLayer).update(weight, v + Δ)
      val b = errorFunc()
      weights(weightLayer).update(weight, v)
      (b - a) / (2 * Δ)
    }

    val updates = collection.mutable.HashMap.empty[(Int, (Int, Int)), Double]
    val grads   = collection.mutable.HashMap.empty[(Int, (Int, Int)), Double]
    val debug   = collection.mutable.HashMap.empty[Int, Matrix]

    weights.zipWithIndex.foreach {
      case (l, idx) =>
        debug += idx -> l.copy
        l.foreachPair { (k, v) =>
          val grad = sum(approximateErrorFuncDerivative(idx, k))
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

    errorFunc()

  }

}

//</editor-fold>

//<editor-fold defaultstate="collapsed" desc="Single Precision Impl">

private[nets] case class DenseNetworkSingle(layers: Seq[Layer], settings: Settings[Float], weights: Weights[Float],
                                            identifier: String = "neuroflow.nets.cpu.DenseNetwork", numericPrecision: String = "Single")
  extends FFN[Float] with EarlyStoppingLogic[Float] with KeepBestLogic[Float] with WaypointLogic[Float] {

  type Vector   = Network.Vector[Float]
  type Vectors  = Network.Vectors[Float]
  type Matrix   = Network.Matrix[Float]
  type Matrices = Network.Matrices[Float]

  private val _layers = layers.map {
    case Focus(inner) => inner
    case layer: Layer => layer
  }.toArray

  private val _focusLayer     = layers.collect { case c: Focus[_] => c }.headOption

  private val _layersNI       = _layers.tail.map { case h: HasActivator[Double] => h.activator.map[Float](_.toDouble, _.toFloat) }
  private val _outputDim      = _layers.last.neurons
  private val _lastWlayerIdx  = weights.size - 1

  private val _forkJoinTaskSupport = new ForkJoinTaskSupport(new ForkJoinPool(settings.parallelism.getOrElse(1)))

  private implicit object Average extends CanAverage[Float, DenseNetworkSingle, Vector, Vector] {
    def averagedError(xs: Vectors, ys: Vectors): Double = {
      val errors = xs.map(evaluate).zip(ys).map {
        case (a, b) => mean(abs(a - b))
      }
      mean(errors)
    }
  }

  /**
    * Checks if the [[Settings]] are properly defined.
    * Might throw a [[SettingsNotSupportedException]].
    */
  override def checkSettings(): Unit = {
    super.checkSettings()
    if (settings.specifics.isDefined)
      warn("No specific settings supported. This has no effect.")
    settings.regularization.foreach {
      case _: EarlyStopping[_, _] | KeepBest =>
      case _ => throw new SettingsNotSupportedException("This regularization is not supported.")
    }
  }

  /**
    * Trains this net with input `xs` against output `ys`.
    */
  def train(xs: Vectors, ys: Vectors): Unit = {
    require(xs.size == ys.size, "Mismatch between sample sizes!")
    import settings._
    val batchSize = settings.batchSize.getOrElse(xs.size)
    if (settings.verbose) info(s"Training with ${xs.size} samples, batchSize = $batchSize ...")
    val xsys = xs.map(_.asDenseMatrix).zip(ys.map(_.asDenseMatrix)).grouped(batchSize).toSeq
    run(xsys, learningRate(1 -> 1.0).toFloat, xs.size, precision, 1, iterations)
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
      val r = flow(input, _lastWlayerIdx)
      settings.lossFunction match {
        case _: SquaredMeanError[_] => r
        case _: Softmax[_]          => SoftmaxImpl(r)
        case _                      => r
      }
    }.toDenseVector
  }

  /**
    * The training loop.
    */
  @tailrec private def run(xsys: Seq[Seq[(Matrix, Matrix)]], stepSize: Float, sampleSize: Float, precision: Double,
                           iteration: Int, maxIterations: Int): Unit = {
    val _em = xsys.map { batch =>
      val (x, y) = (batch.map(_._1), batch.map(_._2))
      val error =
        if (settings.approximation.isDefined)
          adaptWeightsApprox(x, y, stepSize)
        else adaptWeights(x, y, stepSize)
      error
    }.reduce(_ + _)
    val errorPerS = _em / sampleSize
    val errorMean = mean(errorPerS)
    if (settings.verbose) info(f"Iteration $iteration - Loss $errorMean%.6g - Loss Vector $errorPerS")
    maybeGraph(errorMean)
    keepBest(errorMean)
    waypoint(iteration)
    if (errorMean > precision && iteration < maxIterations && !shouldStopEarly) {
      run(xsys, settings.learningRate(iteration + 1 -> stepSize).toFloat, sampleSize, precision, iteration + 1, maxIterations)
    } else {
      info(f"Took $iteration iterations of $maxIterations with Loss = $errorMean%.6g")
      takeBest()
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
    * Computes gradient for all weights in parallel,
    * adapts their value using gradient descent and returns the error matrix.
    */
  private def adaptWeights(xs: Matrices, ys: Matrices, stepSize: Float): Matrix = {

    import settings.lossFunction

    val xsys = xs.par.zip(ys)
    xsys.tasksupport = _forkJoinTaskSupport

    val _ds = (0 to _lastWlayerIdx).map { i =>
      i -> DenseMatrix.zeros[Float](weights(i).rows, weights(i).cols)
    }.toMap

    val _errSum = DenseMatrix.zeros[Float](1, _outputDim)

    xsys.map { xy =>

      val (x, y) = xy
      val fa  = collection.mutable.Map.empty[Int, Matrix]
      val fb  = collection.mutable.Map.empty[Int, Matrix]
      val dws = collection.mutable.Map.empty[Int, Matrix]
      val ds  = collection.mutable.Map.empty[Int, Matrix]
      val e   = DenseMatrix.zeros[Float](1, _outputDim)

      @tailrec def forward(in: Matrix, i: Int): Unit = {
        val p = in * weights(i)
        val a = p.map(_layersNI(i))
        val b = p.map(_layersNI(i).derivative)
        fa += i -> a
        fb += i -> b
        if (i < _lastWlayerIdx) forward(a, i + 1)
      }

      @tailrec def derive(i: Int): Unit = {
        if (i == 0 && _lastWlayerIdx == 0) {
          val (err, grad) = lossFunction(y, fa(0))
          val d = grad *:* fb(0)
          val dw = x.t * d
          dws += 0 -> dw
          e += err
        } else if (i == _lastWlayerIdx) {
          val (err, grad) = lossFunction(y, fa(i))
          val d = grad *:* fb(i)
          val dw = fa(i - 1).t * d
          dws += i -> dw
          ds += i -> d
          e += err
          derive(i - 1)
        } else if (i < _lastWlayerIdx && i > 0) {
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
      derive(_lastWlayerIdx)

      (dws, e)

    }.seq.foreach { ab =>
      _errSum += ab._2
      var i = 0
      while (i <= _lastWlayerIdx) {
        val m = _ds(i)
        val n = ab._1(i)
        m += n
        i += 1
      }
    }

    var i = 0
    while (i <= _lastWlayerIdx) {
      settings.updateRule(weights(i), _ds(i), stepSize, i)
      i += 1
    }

    _errSum

  }

  /** Approximates the gradient based on finite central differences. (For debugging) */
  private def adaptWeightsApprox(xs: Matrices, ys: Matrices, stepSize: Float): Matrix = {

    require(settings.updateRule.isInstanceOf[Debuggable[Float]])
    val _rule: Debuggable[Float] = settings.updateRule.asInstanceOf[Debuggable[Float]]

    def errorFunc(): Matrix = {
      val xsys = xs.zip(ys).par
      xsys.tasksupport = _forkJoinTaskSupport
      xsys.map { case (x, y) => settings.lossFunction(y, flow(x, _lastWlayerIdx))._1 }.reduce(_ + _)
    }

    def approximateErrorFuncDerivative(weightLayer: Int, weight: (Int, Int)): Matrix = {
      val Δ = settings.approximation.get.Δ.toFloat
      val v = weights(weightLayer)(weight)
      weights(weightLayer).update(weight, v - Δ)
      val a = errorFunc()
      weights(weightLayer).update(weight, v + Δ)
      val b = errorFunc()
      weights(weightLayer).update(weight, v)
      (b - a) / (2 * Δ)
    }

    val updates = collection.mutable.HashMap.empty[(Int, (Int, Int)), Float]
    val grads   = collection.mutable.HashMap.empty[(Int, (Int, Int)), Float]
    val debug   = collection.mutable.HashMap.empty[Int, Matrix]

    weights.zipWithIndex.foreach {
      case (l, idx) =>
        debug += idx -> l.copy
        l.foreachPair { (k, v) =>
          val grad = sum(approximateErrorFuncDerivative(idx, k))
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

    errorFunc()

  }

}

//</editor-fold>