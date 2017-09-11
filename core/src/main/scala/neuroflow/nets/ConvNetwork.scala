package neuroflow.nets

import breeze.linalg._
import breeze.numerics._
import breeze.stats._
import neuroflow.core.Network._
import neuroflow.core._

import scala.annotation.tailrec
import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool

/**
  * @author bogdanski
  * @since 31.08.17
  */

object ConvNetwork {
  implicit val constructor: Constructor[ConvNetwork] = new Constructor[ConvNetwork] {
    def apply(ls: Seq[Layer], settings: Settings)(implicit weightProvider: WeightProvider): ConvNetwork = {
      ConvNetwork(ls, settings, weightProvider(ls))
    }
  }
}

private[nets] case class ConvNetwork(layers: Seq[Layer], settings: Settings, weights: Weights,
                                     identifier: String = Registry.register()) extends ConvolutionalNetwork with KeepBestLogic {

  import Network._
  import Convolution.IntTupler

  private val _forkJoinTaskSupport = new ForkJoinTaskSupport(new ForkJoinPool(settings.parallelism))

  private val _allLayers = layers.map {
    case Focus(inner) => inner
    case layer: Layer with HasActivator[Double]  => layer
  }.toArray

  private val _clusterLayer   = layers.collect { case c: Focus => c }.headOption

  private val _lastWlayerIdx  = weights.size - 1
  private val _convLayers     = layers.zipWithIndex.map(_.swap).filter {
    case (_, _: Convolution) => true
    case (_, Focus(_: Convolution)) => true
    case _ => false
  }.toMap.mapValues {
    case c: Convolution => c
    case Focus(c: Convolution) => c
  }

  private val _outputDim = _allLayers.last.neurons
  private val _lastC     = _convLayers.maxBy(_._1)._1
  private val _lastL     = _allLayers.indices.last

  private type Indices   = Map[(Int, Int), Matrix]
  private val _indices   = collection.mutable.Map.empty[Int, Indices]

  /**
    * Computes output for `x`.
    */
  def apply(x: Matrices): Vector = {
    _clusterLayer.map { cl =>
      flow(x, layers.indexOf(cl)).toDenseVector
    }.getOrElse {
      flow(x, _lastWlayerIdx).toDenseVector
    }
  }

  /**
    * Trains this net with input `xs` against output `ys`.
    */
  def train(xs: Seq[Matrices], ys: Vectors): Unit = {
    import settings._
    if (settings.verbose) info(s"Training with ${xs.size} samples ...")
    run(xs, ys.map(_.asDenseMatrix), learningRate(0), precision, 1, iterations)
  }

  /**
    * The training loop.
    */
  @tailrec private def run(xs: Seq[Matrices], ys: Seq[Matrix], stepSize: Double, precision: Double,
                           iteration: Int, maxIterations: Int): Unit = {
    val error =
      if (settings.approximation.isDefined)
        adaptWeightsApprox(xs, ys, stepSize)
      else adaptWeights(xs, ys, stepSize)
    val errorMean = mean(error)
    if (settings.verbose) info(f"Iteration $iteration - Mean Error $errorMean%.6g - Error Vector $error")
    maybeGraph(errorMean)
    keepBest(errorMean, weights)
    if (errorMean > precision && iteration < maxIterations) {
      run(xs, ys, settings.learningRate(iteration + 1), precision, iteration + 1, maxIterations)
    } else {
      if (settings.verbose) info(f"Took $iteration iterations of $maxIterations with Mean Error = $errorMean%.6g")
      takeBest()
    }
  }

  private def flow(in: Matrices, target: Int): Matrix = {

    val _fa = ArrayBuffer.empty[Matrix]

    @tailrec def conv(_in: Matrices, i: Int): Unit = {
      val l = _convLayers(i)
      val p = weights(i) * im2col(_in, l.field, l.stride`²`)._1
      val a = p.map(l.activator)
      _fa += a
      if (i < _lastC) conv(col2im(a, l.dimOut), i + 1)
    }

    @tailrec def fully(_in: Matrix, i: Int): Unit = {
      val l = _allLayers(i)
      val p = _in * weights(i)
      val a = p.map(l.activator)
      _fa += a
      if (i < _lastL) fully(a, i + 1)
    }

    conv(in, 0)
    fully(_fa(_lastC).reshape(1, _convLayers(_lastC).neurons), _lastC + 1)

    _fa(target)

  }

  private def im2col(ms: Matrices, field: (Int, Int), stride: (Int, Int), withIndices: Boolean = false): (Matrix, Indices) = {
    val dim = (ms.head.rows, ms.head.cols, ms.size)
    val dimOut = ((dim._1 - field._1) / stride._1 + 1, (dim._2 - field._2) / stride._2 + 1)
    val fieldSq = field._1 * field._2
    val out = DenseMatrix.zeros[Double](fieldSq * dim._3, dimOut._1 * dimOut._2)
    val idc = if (withIndices) {
      ms.head.keysIterator.map { k =>
        k -> DenseMatrix.zeros[Double](field._1, field._2)
      }.toMap
    } else null
    var (w, h, i) = (0, 0, 0)
    while (w < ((dim._1 - field._1) / stride._1) + 1) {
      while (h < ((dim._2 - field._2) / stride._2) + 1) {
        var (x, y, z, wi) = (0, 0, 0, 0)
        while (x < field._1) {
          while (y < field._2) {
            while (z < dim._3) {
              val (a, b, c) = (x + (w * stride._1), y + (h * stride._2), z * fieldSq)
              val value = ms(z)(a, b)
              val lin = c + wi
              out.update(lin, i, value)
              if (withIndices) idc(a, b).update(x, y, i + 1)
              z += 1
            }
            z   = 0
            wi += 1
            y += 1
          }
          y  = 0
          x += 1
        }
        i += 1
        h += 1
      }
      h  = 0
      w += 1
    }
    (out, idc)
  }

  private def col2im(matrix: Matrix, dim: (Int, Int, Int)): Matrices = {
    var i = 0
    val out = new Array[Matrix](dim._3)
    while (i < dim._3) {
      val m = DenseMatrix.zeros[Double](dim._1, dim._2)
      val v = matrix.t(::, i)
      var (x, y, w) = (0, 0, 0)
      while (x < dim._1) {
        while (y < dim._2) {
          m.update(x, y, v(w))
          y += 1
          w += 1
        }
        y = 0
        x += 1
      }
      out(i) = m
      i += 1
    }
    out
  }

  private def adaptWeights(xs: Seq[Matrices], ys: Seq[Matrix], stepSize: Double): Matrix = {
    val xsys = xs.par.zip(ys)
    xsys.tasksupport = _forkJoinTaskSupport

    val _ds = (0 to _lastWlayerIdx).map { i =>
      i -> DenseMatrix.zeros[Double](weights(i).rows, weights(i).cols)
    }.toMap

    val _errSum  = DenseMatrix.zeros[Double](1, _outputDim)
    val _square  = DenseMatrix.zeros[Double](1, _outputDim)
    _square := 2.0

    xsys.map { xy =>
      val (x, y) = xy
      val fa  = collection.mutable.Map.empty[Int, Matrix]
      val fb  = collection.mutable.Map.empty[Int, Matrix]
      val fc  = collection.mutable.Map.empty[Int, Matrix]
      val dws = collection.mutable.Map.empty[Int, Matrix]
      val ds  = collection.mutable.Map.empty[Int, Matrix]
      val e   = DenseMatrix.zeros[Double](1, _outputDim)

      @tailrec def conv(_in: Matrices, i: Int): Unit = {
        val l = _convLayers(i)
        val seen = _indices.isDefinedAt(i)
        val (c, x) = im2col(_in, l.field, l.stride`²`, withIndices = !seen)
        val p = weights(i) * c
        var a = p.map(l.activator)
        var b = p.map(l.activator.derivative)
        if (i == _lastC) {
          a = a.reshape(1, l.neurons)
          b = b.reshape(1, l.neurons)
        }
        fa += i -> a
        fb += i -> b
        fc += i -> c
        if (!seen) _indices += i -> x
        if (i < _lastC) conv(col2im(a, l.dimOut), i + 1)
      }

      @tailrec def fully(_in: Matrix, i: Int): Unit = {
        val l = _allLayers(i)
        val p = _in * weights(i)
        val a = p.map(l.activator)
        val b = p.map(l.activator.derivative)
        fa += i -> a
        fb += i -> b
        if (i < _lastL) fully(a, i + 1)
      }

      @tailrec def derive(i: Int): Unit = {
        if (i == _lastWlayerIdx) {
          val yf = y - fa(i)
          val d = -yf *:* fb(i)
          val dw = fa(i - 1).t * d
          dws += i -> dw
          ds += i -> d
          e += yf
          derive(i - 1)
        } else if (i < _lastWlayerIdx && i > _lastC) {
          val d = (ds(i + 1) * weights(i + 1).t) *:* fb(i)
          val dw = fa(i - 1).t * d
          dws += i -> dw
          ds += i -> d
          derive(i - 1)
        } else if (i == _lastC) {
          val l = _convLayers(i)
          val d = ((ds(i + 1) * weights(i + 1).t) *:* fb(i))
            .reshape(l.filters, l.dimOut._1 * l.dimOut._2)
          val dw = d * fc(i).t
          dws += i -> dw
          ds += i -> d
          if (i > 0) derive(i - 1)
        } else {
          val l = _convLayers(i)
          val l2 = _convLayers(i + 1)
          val id = _indices(i + 1)
          val de = ds(i + 1)
          val wr = weights(i + 1)
          val ep = new Array[Matrix](de.rows)
          var f = 0
          while (f < de.rows) {
            val out = DenseMatrix.zeros[Double](l2.dimIn._1 * l2.field._1, l2.dimIn._2 * l2.field._2)
            var (x, y) = (0, 0)
            while (x < l2.dimIn._1) {
              while (y < l2.dimIn._2) {
                id(x, y).foreachPair { (k, v) =>
                  val t = (x * l2.field._1 + k._1, y * l2.field._2 + k._2)
                  out.update(t, if (v > 0.0) de(f, v.toInt - 1) else 0.0)
                }
                y += 1
              }
              y = 0
              x += 1
            }
            ep.update(f, out)
            f += 1
          }
          val dc = im2col(ep, l2.field, l2.field)._1
          val _fieldSq = l2.field._1 * l2.field._2
          val _WW = DenseMatrix.zeros[Double](l.filters, l2.filters * _fieldSq)
          var (filter, depth) = (0, 0)
          while (filter < l2.filters) {
            while (depth < l.filters) {
              val ws = wr(filter, (depth * _fieldSq) until ((depth * _fieldSq) + _fieldSq))
              var i = 0
              while (i < _fieldSq) {
                _WW.update(depth, filter * _fieldSq + i, ws(i))
                i += 1
              }
              depth += 1
            }
            depth = 0
            filter +=1
          }
          val d = _WW * dc *:* fb(i)
          val dw = d * fc(i).t
          dws += i -> dw
          ds += i -> d
          if (i > 0) derive(i - 1)
        }
      }
      conv(x, 0)
      fully(fa(_lastC), _lastC + 1)
      derive(_lastWlayerIdx)
      e :^= _square
      e *= 0.5
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
  private def adaptWeightsApprox(xs: Seq[Matrices], ys: Matrices, stepSize: Double): Matrix = {

    require(settings.updateRule.isInstanceOf[Debuggable])
    val _rule: Debuggable = settings.updateRule.asInstanceOf[Debuggable]

    def errorFunc(): Matrix = {
      val xsys = xs.zip(ys).par
      xsys.tasksupport = _forkJoinTaskSupport
      xsys.map { case (x, y) => 0.5 * pow(y - flow(x, _lastWlayerIdx), 2) }.reduce(_ + _)
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
