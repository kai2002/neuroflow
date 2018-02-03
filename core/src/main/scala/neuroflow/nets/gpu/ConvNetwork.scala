package neuroflow.nets.gpu

import breeze.linalg._
import breeze.stats._
import jcuda.jcublas.{JCublas2, cublasHandle}
import neuroflow.core.Activator._
import neuroflow.core.IllusionBreaker.SettingsNotSupportedException
import neuroflow.core.Network._
import neuroflow.core._
import neuroflow.cuda._
import neuroflow.dsl._

import scala.annotation.tailrec
import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer

/**
  *
  * Convolutional Neural Network running on CUDA, using
  * gradient descent to optimize the loss function.
  *
  * @author bogdanski
  * @since 31.08.17
  *
  */

object ConvNetwork {

  implicit object double extends Constructor[Double, ConvNetworkDouble] {
    def apply(ls: Seq[Layer], loss: LossFunction[Double], settings: Settings[Double])(implicit weightProvider: WeightProvider[Double]): ConvNetworkDouble = {
      ConvNetworkDouble(ls, loss, settings, weightProvider(ls))
    }
  }

  implicit object weights_double extends neuroflow.core.WeightProvider.CNN[Double]


  implicit object single extends Constructor[Float, ConvNetworkSingle] {
    def apply(ls: Seq[Layer], loss: LossFunction[Float], settings: Settings[Float])(implicit weightProvider: WeightProvider[Float]): ConvNetworkSingle = {
      ConvNetworkSingle(ls, loss, settings, weightProvider(ls))
    }
  }

  implicit object weights_single extends neuroflow.core.WeightProvider.CNN[Float]

}



// <editor-fold defaultstate="collapsed" desc="Single Precision Impl">

private[nets] case class ConvNetworkDouble(layers: Seq[Layer], lossFunction: LossFunction[Double], settings: Settings[Double], weights: Weights[Double],
                                           identifier: String = "neuroflow.nets.gpu.ConvNetwork", numericPrecision: String = "Double")
  extends CNN[Double] with WaypointLogic[Double] {

  implicit val handle = new cublasHandle
  JCublas2.cublasCreate(handle)

  type Vector   = DenseVector[Double]
  type Matrix   = DenseMatrix[Double]
  type Tensor   = neuroflow.common.Tensor[Double]
  type Vectors  = Seq[DenseVector[Double]]
  type Matrices = Seq[DenseMatrix[Double]]
  type Tensors  = Seq[neuroflow.common.Tensor[Double]]

  private val _allLayers  = layers.map {
    case f: Focus[_]         => f.inner
    case d: Dense[_]         => d
    case c: Convolution[_]   => c
  }.toArray

  private val _activators = _allLayers.map { l =>
    l.activator match {
      case ReLU    => CuMatrix.Activators.relu[Double]    ->  CuMatrix.Activators.relu_derivative[Double]
      case Linear  => CuMatrix.Activators.linear[Double]  ->  CuMatrix.Activators.linear_derivative[Double]
      case Sigmoid => CuMatrix.Activators.sigmoid[Double] ->  CuMatrix.Activators.sigmoid_derivative[Double]
      case Tanh    => CuMatrix.Activators.tanh[Double]    ->  CuMatrix.Activators.tanh_derivative[Double]
      case x       => throw new SettingsNotSupportedException(s"This activator is not implemented for CUDA: $x.")
    }
  }

  private val _focusLayer         = layers.collectFirst { case c: Focus[_] => c }
  private val _lastWlayerIdx      = weights.size - 1
  private val _convLayers         = _allLayers.zipWithIndex.map(_.swap).filter {
    case (_, _: Convolution[_])   => true
    case _                        => false
  }.toMap.mapValues {
    case c: Convolution[Double]   => c
  }

  private val _outputDim = _allLayers.last.neurons
  private val _lastC     = _convLayers.maxBy(_._1)._1
  private val _lastL     = _allLayers.indices.last

  private val _cuWeights = weights.map(m => CuMatrix.fromDense(m))

  /**
    * Computes output for `x`.
    */
  def apply(x: Tensor): Vector = {
    _focusLayer.map { cl =>
      flow(x.matrix, layers.indexOf(cl), batchSize = 1)
    }.getOrElse {
      val r = flow(x.matrix, _lastWlayerIdx, batchSize = 1)
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
  def train(xs: Tensors, ys: Vectors): Unit = {
    require(xs.size == ys.size, "Mismatch between sample sizes!")
    import settings._
    val batchSize = settings.batchSize.getOrElse(xs.size)
    if (settings.verbose) {
      info(s"Training with ${xs.size} samples, batch size = $batchSize, batches = ${math.ceil(xs.size.toDouble / batchSize.toDouble).toInt}.")
      info(s"Breeding batches ...")
    }
    val xsys = xs.map(_.matrix).zip(ys.map(_.asDenseMatrix)).grouped(batchSize).toSeq.map { batch =>
      batch.par.reduce((x, y) => DenseMatrix.horzcat(x._1, y._1) -> DenseMatrix.vertcat(x._2, y._2))
    }
    gcThreshold match {
      case Some(bytes) => GcThreshold.set(bytes)
      case None        => GcThreshold.set(this, batchSize * 2)
    }
    run(xsys, learningRate(1 -> 1.0), xs.size, batchSize, precision, batch = 0, batches = xsys.size, iteration = 1, iterations)
  }

  /**
    * The training loop.
    */
  @tailrec private def run(xsys: Seq[(Matrix, Matrix)], stepSize: Double, sampleSize: Double, batchSize: Int,
                           precision: Double, batch: Int, batches: Int, iteration: Int, maxIterations: Int): Unit = {
    val (x, y) = (xsys(batch)._1, xsys(batch)._2)
    val loss =
      if (settings.approximation.isDefined) adaptWeightsApprox(x, y, stepSize, batchSize)
      else adaptWeights(x, y, stepSize, batchSize)
    val lossMean = mean(loss)
    if (settings.verbose) info(f"Iteration $iteration.${batch + 1}, Avg. Loss = $lossMean%.6g, Vector: $loss")
    syncWeights()
    maybeGraph(lossMean)
    waypoint(iteration)
    if (lossMean > precision && iteration < maxIterations) {
      run(xsys, settings.learningRate(iteration + 1 -> stepSize), sampleSize, batchSize,
        precision, (batch + 1) % batches, batches, iteration + 1, maxIterations)
    } else {
      info(f"Took $iteration of $maxIterations iterations.")
    }
  }

  private def flow(in: Matrix, target: Int, batchSize: Int): Matrix = {

    val _fa = ArrayBuffer.empty[CuMatrix[Double]]

    @tailrec def conv(_in: CuMatrix[Double], i: Int): Unit = {
      val l = _convLayers(i)
      val p = _cuWeights(i) * convolute(_in, l, batchSize)
      val a = _activators(i)._1(p)
      _fa += { if (i == _lastC) reshape_batch(a, l.dimOut, batchSize) else a }
      if (i < _lastC) conv(a, i + 1)
    }

    @tailrec def fully(_in: CuMatrix[Double], i: Int): Unit = {
      val l = _allLayers(i)
      val p = _in * _cuWeights(i)
      val a = _activators(i)._1(p)
      _fa += a
      if (i < _lastL) fully(a, i + 1)
    }

    conv(CuMatrix.fromDense(in), 0)
    fully(_fa(_lastC), _lastC + 1)

    _fa(target).toDense

  }


  private def convolute(in: CuMatrix[Double], l: Convolution[_], batchSize: Int): CuMatrix[Double] =
    CuMatrix.ConvOps.convolute(in, l.dimIn._1, l.dimIn._2, l.dimOut._1, l.dimOut._2, l.dimIn._3,
      batchSize, l.field._1, l.field._2, l.stride._1, l.stride._2, l.padding._1, l.padding._2)

  private def convolute_bp(in: CuMatrix[Double], l: Convolution[_], batchSize: Int): CuMatrix[Double] =
    CuMatrix.ConvOps.convolute_bp(in, l.dimIn._1, l.dimIn._2, l.dimOut._1, l.dimOut._2, l.dimOut._3,
      batchSize, l.field._1, l.field._2, l.stride._1, l.stride._2, l.padding._1, l.padding._2)

  private def reshape_batch(in: CuMatrix[Double], dim: (Int, Int, Int), batchSize: Int): CuMatrix[Double] =
    CuMatrix.ConvOps.reshape_batch(in, dim._1, dim._2, dim._3, batchSize)

  private def reshape_batch_bp(in: CuMatrix[Double], dim: (Int, Int, Int), batchSize: Int): CuMatrix[Double] =
    CuMatrix.ConvOps.reshape_batch_bp(in, dim._1, dim._2, dim._3, batchSize)


  /**
    * Computes gradient for weights with respect to given batch,
    * adapts their value using gradient descent and returns the loss matrix.
    */
  private def adaptWeights(x: Matrix, y: Matrix, stepSize: Double, batchSize: Int): Matrix = {

    import settings.updateRule

    val (_x, _y) = (CuMatrix.fromDense(x), CuMatrix.fromDense(y))

    val loss = CuMatrix.zeros[Double](batchSize, _outputDim)

    val fa  = collection.mutable.Map.empty[Int, CuMatrix[Double]]
    val fb  = collection.mutable.Map.empty[Int, CuMatrix[Double]]
    val fc  = collection.mutable.Map.empty[Int, CuMatrix[Double]]
    val dws = collection.mutable.Map.empty[Int, CuMatrix[Double]]
    val ds  = collection.mutable.Map.empty[Int, CuMatrix[Double]]

    @tailrec def conv(_in: CuMatrix[Double], i: Int): Unit = {
      val l = _convLayers(i)
      val c = convolute(_in, l, batchSize)
      val p = _cuWeights(i) * c
      val a = _activators(i)._1(p)
      val b = _activators(i)._2(p)
      fa += i -> { if (i == _lastC) reshape_batch(a, l.dimOut, batchSize) else a }
      fb += i -> b
      fc += i -> c
      if (i < _lastC) conv(a, i + 1)
    }

    @tailrec def fully(_in: CuMatrix[Double], i: Int): Unit = {
      val l = _allLayers(i)
      val p = _in * _cuWeights(i)
      val a = _activators(i)._1(p)
      val b = _activators(i)._2(p)
      fa += i -> a
      fb += i -> b
      if (i < _lastL) fully(a, i + 1)
    }

    @tailrec def derive(i: Int): Unit = {
      if (i == _lastWlayerIdx) {
        val (err, grad) = lossFunction(_y, fa(i))
        val d = grad *:* fb(i)
        val dw = fa(i - 1).t * d
        dws += i -> dw
        ds += i -> d
        loss += err
        derive(i - 1)
      } else if (i < _lastWlayerIdx && i > _lastC) {
        val d = (ds(i + 1) * _cuWeights(i + 1).t) *:* fb(i)
        val dw = fa(i - 1).t * d
        dws += i -> dw
        ds += i -> d
        derive(i - 1)
      } else if (i == _lastC) {
        val l = _convLayers(i)
        val d1 = ds(i + 1) * _cuWeights(i + 1).t
        val d2 = reshape_batch_bp(d1, l.dimOut, batchSize)
        val d = d2 *:* fb(i)
        val dw = d * fc(i).t
        dws += i -> dw
        ds += i -> d
        if (i > 0) derive(i - 1)
      } else {
        val l = _convLayers(i + 1)
        val ww = reshape_batch(_cuWeights(i + 1), (l.field._1, l.field._2, l.filters), l.dimIn._3)
        val dc = convolute_bp(ds(i + 1), l, batchSize)
        val d = ww * dc *:* fb(i)
        val dw = d * fc(i).t
        dws += i -> dw
        ds += i -> d
        if (i > 0) derive(i - 1)
      }
    }

    conv(_x, 0)
    fully(fa(_lastC), _lastC + 1)
    derive(_lastWlayerIdx)

    ds.values.foreach(_.release())
    fa.values.foreach(_.release())
    fb.values.foreach(_.release())
    fc.values.foreach(_.release())

    (0 to _lastWlayerIdx).foreach(i => updateRule(_cuWeights(i), dws(i), stepSize, i))

    dws.values.foreach(_.release())
    _x.release()
    _y.release()

    val lossReduced = (loss.t * CuMatrix.ones[Double](loss.rows, 1)).t
    lossReduced.toDense

  }


  /** For debugging, approximates the gradients using `settings.approximation`. */
  private def adaptWeightsApprox(xs: Matrix, ys: Matrix, stepSize: Double, batchSize: Int): Matrix = {

    require(settings.updateRule.isInstanceOf[Debuggable[Double]])
    val _rule: Debuggable[Double] = settings.updateRule.asInstanceOf[Debuggable[Double]]

    def lossFunc(): Matrix = {
      val loss = lossFunction(ys, flow(xs, _lastWlayerIdx, batchSize))._1
      val reduced = (loss.t * DenseMatrix.ones[Double](loss.rows, 1)).t
      reduced
    }

    val out = lossFunc()

    def approximateGradient(weightLayer: Int, weight: (Int, Int)): Double = {
      sum(settings.approximation.get.apply(weights, lossFunc, syncWithGPU, weightLayer, weight))
    }

    def syncWithGPU(): Unit = {
      weights.zip(_cuWeights).foreach {
        case (w, cw) => cw := w
      }
    }

    val updates = collection.mutable.HashMap.empty[(Int, (Int, Int)), Double]
    val grads   = collection.mutable.HashMap.empty[(Int, (Int, Int)), Double]
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

    syncWithGPU()

    out

  }

  private def syncWeights(): Unit = {
    weights.zip(_cuWeights).foreach {
      case (w, cw) => w := cw.toDense
    }
  }

}

// </editor-fold>

// <editor-fold defaultstate="collapsed" desc="Single Precision Impl">

private[nets] case class ConvNetworkSingle(layers: Seq[Layer], lossFunction: LossFunction[Float], settings: Settings[Float], weights: Weights[Float],
                                           identifier: String = "neuroflow.nets.gpu.ConvNetwork", numericPrecision: String = "Single")
  extends CNN[Float] with WaypointLogic[Float] {

  implicit val handle = new cublasHandle
  JCublas2.cublasCreate(handle)

  type Vector   = DenseVector[Float]
  type Matrix   = DenseMatrix[Float]
  type Tensor   = neuroflow.common.Tensor[Float]
  type Vectors  = Seq[DenseVector[Float]]
  type Matrices = Seq[DenseMatrix[Float]]
  type Tensors  = Seq[neuroflow.common.Tensor[Float]]

  private val _allLayers  = layers.map {
    case f: Focus[_]         => f.inner
    case d: Dense[_]         => d
    case c: Convolution[_]   => c
  }.toArray

  private val _activators = _allLayers.map { l =>
    l.activator match {
      case ReLU    => CuMatrix.Activators.relu[Float]    ->  CuMatrix.Activators.relu_derivative[Float]
      case Linear  => CuMatrix.Activators.linear[Float]  ->  CuMatrix.Activators.linear_derivative[Float]
      case Sigmoid => CuMatrix.Activators.sigmoid[Float] ->  CuMatrix.Activators.sigmoid_derivative[Float]
      case Tanh    => CuMatrix.Activators.tanh[Float]    ->  CuMatrix.Activators.tanh_derivative[Float]
      case x       => throw new SettingsNotSupportedException(s"This activator is not implemented for CUDA: $x.")
    }
  }

  private val _focusLayer         = layers.collectFirst { case c: Focus[_] => c }
  private val _lastWlayerIdx      = weights.size - 1
  private val _convLayers         = _allLayers.zipWithIndex.map(_.swap).filter {
    case (_, _: Convolution[_])   => true
    case _                        => false
  }.toMap.mapValues {
    case c: Convolution[_]        => c
  }

  private val _outputDim = _allLayers.last.neurons
  private val _lastC     = _convLayers.maxBy(_._1)._1
  private val _lastL     = _allLayers.indices.last

  private val _cuWeights = weights.map(m => CuMatrix.fromDense(m))

  /**
    * Computes output for `x`.
    */
  def apply(x: Tensor): Vector = {
    _focusLayer.map { cl =>
      flow(x.matrix, layers.indexOf(cl), batchSize = 1)
    }.getOrElse {
      val r = flow(x.matrix, _lastWlayerIdx, batchSize = 1)
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
  def train(xs: Tensors, ys: Vectors): Unit = {
    require(xs.size == ys.size, "Mismatch between sample sizes!")
    import settings._
    val batchSize = settings.batchSize.getOrElse(xs.size)
    if (settings.verbose) {
      info(s"Training with ${xs.size} samples, batch size = $batchSize, batches = ${math.ceil(xs.size.toFloat / batchSize.toFloat).toInt}.")
      info(s"Breeding batches ...")
    }
    val xsys = xs.map(_.matrix).zip(ys.map(_.asDenseMatrix)).grouped(batchSize).toSeq.map { batch =>
      batch.par.reduce((x, y) => DenseMatrix.horzcat(x._1, y._1) -> DenseMatrix.vertcat(x._2, y._2))
    }
    gcThreshold match {
      case Some(bytes) => GcThreshold.set(bytes)
      case None        => GcThreshold.set(this, batchSize * 2)
    }
    run(xsys, learningRate(1 -> 1.0).toFloat, xs.size, batchSize, precision, batch = 0, batches = xsys.size, iteration = 1, iterations)
  }

  /**
    * The training loop.
    */
  @tailrec private def run(xsys: Seq[(Matrix, Matrix)], stepSize: Float, sampleSize: Double, batchSize: Int,
                           precision: Double, batch: Int, batches: Int, iteration: Int, maxIterations: Int): Unit = {
    val (x, y) = (xsys(batch)._1, xsys(batch)._2)
    val loss =
      if (settings.approximation.isDefined) adaptWeightsApprox(x, y, stepSize, batchSize)
      else adaptWeights(x, y, stepSize, batchSize)
    val lossMean = mean(loss)
    if (settings.verbose) info(f"Iteration $iteration.${batch + 1}, Avg. Loss = $lossMean%.6g, Vector: $loss")
    syncWeights()
    maybeGraph(lossMean)
    waypoint(iteration)
    if (lossMean > precision && iteration < maxIterations) {
      run(xsys, settings.learningRate(iteration + 1 -> stepSize).toFloat, sampleSize, batchSize,
        precision, (batch + 1) % batches, batches, iteration + 1, maxIterations)
    } else {
      info(f"Took $iteration of $maxIterations iterations.")
    }
  }

  private def flow(in: Matrix, target: Int, batchSize: Int): Matrix = {

    val _fa = ArrayBuffer.empty[CuMatrix[Float]]

    @tailrec def conv(_in: CuMatrix[Float], i: Int): Unit = {
      val l = _convLayers(i)
      val p = _cuWeights(i) * convolute(_in, l, batchSize)
      val a = _activators(i)._1(p)
      _fa += { if (i == _lastC) reshape_batch(a, l.dimOut, batchSize) else a }
      if (i < _lastC) conv(a, i + 1)
    }

    @tailrec def fully(_in: CuMatrix[Float], i: Int): Unit = {
      val l = _allLayers(i)
      val p = _in * _cuWeights(i)
      val a = _activators(i)._1(p)
      _fa += a
      if (i < _lastL) fully(a, i + 1)
    }

    conv(CuMatrix.fromDense(in), 0)
    fully(_fa(_lastC), _lastC + 1)

    _fa(target).toDense

  }


  private def convolute(in: CuMatrix[Float], l: Convolution[_], batchSize: Int): CuMatrix[Float] =
    CuMatrix.ConvOps.convolute(in, l.dimIn._1, l.dimIn._2, l.dimOut._1, l.dimOut._2, l.dimIn._3,
      batchSize, l.field._1, l.field._2, l.stride._1, l.stride._2, l.padding._1, l.padding._2)

  private def convolute_bp(in: CuMatrix[Float], l: Convolution[_], batchSize: Int): CuMatrix[Float] =
    CuMatrix.ConvOps.convolute_bp(in, l.dimIn._1, l.dimIn._2, l.dimOut._1, l.dimOut._2, l.dimOut._3,
      batchSize, l.field._1, l.field._2, l.stride._1, l.stride._2, l.padding._1, l.padding._2)

  private def reshape_batch(in: CuMatrix[Float], dim: (Int, Int, Int), batchSize: Int): CuMatrix[Float] =
    CuMatrix.ConvOps.reshape_batch(in, dim._1, dim._2, dim._3, batchSize)

  private def reshape_batch_bp(in: CuMatrix[Float], dim: (Int, Int, Int), batchSize: Int): CuMatrix[Float] =
    CuMatrix.ConvOps.reshape_batch_bp(in, dim._1, dim._2, dim._3, batchSize)


  /**
    * Computes gradient for weights with respect to given batch,
    * adapts their value using gradient descent and returns the loss matrix.
    */
  private def adaptWeights(x: Matrix, y: Matrix, stepSize: Float, batchSize: Int): Matrix = {

    import settings.updateRule

    val (_x, _y) = (CuMatrix.fromDense(x), CuMatrix.fromDense(y))

    val loss = CuMatrix.zeros[Float](batchSize, _outputDim)

    val fa  = collection.mutable.Map.empty[Int, CuMatrix[Float]]
    val fb  = collection.mutable.Map.empty[Int, CuMatrix[Float]]
    val fc  = collection.mutable.Map.empty[Int, CuMatrix[Float]]
    val dws = collection.mutable.Map.empty[Int, CuMatrix[Float]]
    val ds  = collection.mutable.Map.empty[Int, CuMatrix[Float]]

    @tailrec def conv(_in: CuMatrix[Float], i: Int): Unit = {
      val l = _convLayers(i)
      val c = convolute(_in, l, batchSize)
      val p = _cuWeights(i) * c
      val a = _activators(i)._1(p)
      val b = _activators(i)._2(p)
      fa += i -> { if (i == _lastC) reshape_batch(a, l.dimOut, batchSize) else a }
      fb += i -> b
      fc += i -> c
      p.release()
      if (i < _lastC) conv(a, i + 1)
    }

    @tailrec def fully(_in: CuMatrix[Float], i: Int): Unit = {
      val l = _allLayers(i)
      val p = _in * _cuWeights(i)
      val a = _activators(i)._1(p)
      val b = _activators(i)._2(p)
      fa += i -> a
      fb += i -> b
      p.release()
      if (i < _lastL) fully(a, i + 1)
    }

    @tailrec def derive(i: Int): Unit = {
      if (i == _lastWlayerIdx) {
        val (err, grad) = lossFunction(_y, fa(i))
        val d = grad *:* fb(i)
        val dw = fa(i - 1).t * d
        dws += i -> dw
        ds += i -> d
        loss += err
        err.release()
        grad.release()
        derive(i - 1)
      } else if (i < _lastWlayerIdx && i > _lastC) {
        val d = (ds(i + 1) * _cuWeights(i + 1).t) *:* fb(i)
        val dw = fa(i - 1).t * d
        dws += i -> dw
        ds += i -> d
        derive(i - 1)
      } else if (i == _lastC) {
        val l = _convLayers(i)
        val d1 = ds(i + 1) * _cuWeights(i + 1).t
        val d2 = reshape_batch_bp(d1, l.dimOut, batchSize)
        val d = d2 *:* fb(i)
        val dw = d * fc(i).t
        dws += i -> dw
        ds += i -> d
        d1.release()
        d2.release()
        if (i > 0) derive(i - 1)
      } else {
        val l = _convLayers(i + 1)
        val ww = reshape_batch(_cuWeights(i + 1), (l.field._1, l.field._2, l.filters), l.dimIn._3)
        val dc = convolute_bp(ds(i + 1), l, batchSize)
        val d = ww * dc *:* fb(i)
        val dw = d * fc(i).t
        dws += i -> dw
        ds += i -> d
        ww.release()
        dc.release()
        if (i > 0) derive(i - 1)
      }
    }

    conv(_x, 0)
    fully(fa(_lastC), _lastC + 1)
    derive(_lastWlayerIdx)

    ds.values.foreach(_.release())
    fa.values.foreach(_.release())
    fb.values.foreach(_.release())
    fc.values.foreach(_.release())

    (0 to _lastWlayerIdx).foreach(i => updateRule(_cuWeights(i), dws(i), stepSize, i))

    dws.values.foreach(_.release())
    _x.release()
    _y.release()

    val lossReduced = (loss.t * CuMatrix.ones[Float](loss.rows, 1)).t
    lossReduced.toDense

  }


  /** For debugging, approximates the gradients using `settings.approximation`. */
  private def adaptWeightsApprox(xs: Matrix, ys: Matrix, stepSize: Float, batchSize: Int): Matrix = {

    require(settings.updateRule.isInstanceOf[Debuggable[Float]])
    val _rule: Debuggable[Float] = settings.updateRule.asInstanceOf[Debuggable[Float]]

    def lossFunc(): Matrix = {
      val loss = lossFunction(ys, flow(xs, _lastWlayerIdx, batchSize))._1
      val reduced = (loss.t * DenseMatrix.ones[Float](loss.rows, 1)).t
      reduced
    }

    val out = lossFunc()

    def approximateGradient(weightLayer: Int, weight: (Int, Int)): Float = {
      sum(settings.approximation.get.apply(weights, lossFunc, syncWithGPU, weightLayer, weight))
    }

    def syncWithGPU(): Unit = {
      weights.zip(_cuWeights).foreach {
        case (w, cw) => cw := w
      }
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

    syncWithGPU()

    out

  }

  private def syncWeights(): Unit = {
    weights.zip(_cuWeights).foreach {
      case (w, cw) => w := cw.toDense
    }
  }

}

// </editor-fold>

