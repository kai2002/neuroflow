package neuroflow.core

import java.util.concurrent.ThreadLocalRandom

import breeze.linalg.DenseMatrix
import breeze.storage.Zero
import neuroflow.core.Network.Weights

import scala.annotation.implicitNotFound
import scala.reflect.ClassTag

/**
  * @author bogdanski
  * @since 18.01.16
  */

/**
  * A [[WeightProvider]] connects the neurons of a [[Layer]] through the weights, or synapses.
  */
@implicitNotFound(
  "No `WeightProvider` in scope for type ${V}. Add your own or import a predefined provider: " +
  "import neuroflow.core.WeightProvider.${V}.X._ (where X = { FFN | RNN | CNN }).")
trait WeightProvider[V] extends (Seq[Layer] => Weights[V])


trait BaseOps[V] {

  type Weights = Network.Weights[V]

  /**
    * All `layers` are fully connected such that their weight matrices can
    * flow from left to right by regular matrix multiplication.
    * The `seed` determines the initial weight values.
    */
  def fullyConnected(layers: Seq[Layer], seed: Map[Int, () => V])(implicit ct: ClassTag[V], z: Zero[V]): Weights =
    layers.dropRight(1).zipWithIndex.toArray.map {
      case (layer, index) =>
        val (neuronsLeft, neuronsRight) = (layer.neurons, layers(index + 1).neurons)
        val product = neuronsLeft * neuronsRight
        DenseMatrix.create[V](neuronsLeft, neuronsRight, Array.fill(product)(seed(index)()))
    }

  /**
    * Convolutional layers produce im2col-ready weight matrices.
    */
  def convoluted(layers: Seq[Layer], seed: Map[Int, () => V])(implicit ct: ClassTag[V], z: Zero[V]): Weights =
    layers.zipWithIndex.toArray.map {
      case (Convolution(dimIn, filter, filters, _, _), idx) =>
        val depth = dimIn._3
        val field = filter._1 * filter._2 * depth
        DenseMatrix.create[V](filters, field, Array.fill(field * filters)(seed(idx)()))
      case (Focus(inner), idx) =>
        inner match {
          case Convolution(dimIn, filter, filters, _, _) =>
            val depth = dimIn._3
            val field = filter._1 * filter._2 * depth
            DenseMatrix.create[V](filters, field, Array.fill(field * filters)(seed(idx)()))
          case layer =>
            val (neuronsLeft, neuronsRight) = (layers(idx - 1).neurons, layer.neurons)
            val product = neuronsLeft * neuronsRight
            DenseMatrix.create[V](neuronsLeft, neuronsRight, Array.fill(product)(seed(idx)()))
        }
      case (layer, idx)  =>
        val (neuronsLeft, neuronsRight) = (layers(idx - 1).neurons, layer.neurons)
        val product = neuronsLeft * neuronsRight
        DenseMatrix.create[V](neuronsLeft, neuronsRight, Array.fill(product)(seed(idx)()))
    }

  /**
    * Enriches the given `layers` and their `weights` with recurrent LSTM connections.
    */
  def recurrentEnrichment(layers: Seq[Layer], weights: Weights, seed: Map[Int, () => V])(implicit ct: ClassTag[V], z: Zero[V]): Weights =
    weights.dropRight(1).zipWithIndex.flatMap {
      case (ws, index) =>
        val ns = layers(index + 1).neurons
        val in = (1 to 3) map { _ => DenseMatrix.create[V](ws.rows, ws.cols, (1 to ws.rows * ws.cols).map(_ => seed(index)()).toArray) }
        val cells = (1 to 4) map { _ => DenseMatrix.create[V](ns, ns, (1 to ns * ns).map(_ => seed(index)()).toArray) }
        in ++ cells
    }

  /**
    * Gives a seed function to generate weights in range `i`.
    */
  def randomD(i: (Double, Double)): () => Double = () => ThreadLocalRandom.current.nextDouble(i._1, i._2)
  def randomF(i: (Float, Float)): () => Float  = () => ThreadLocalRandom.current.nextDouble(i._1, i._2).toFloat

  def normalD(μ: Double, σ: Double): () => Double = () => breeze.stats.distributions.Gaussian(μ, σ).draw()
  def normalF(μ: Float, σ: Float): () => Float  = () => breeze.stats.distributions.Gaussian(μ, σ).draw().toFloat

}

object WeightProvider {

  object Double {

    object FFN extends BaseOps[Double] {

      implicit val zeroWeights = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, () => 0.0)).toMap)
      }

      implicit val oneWeights = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, () => 1.0)).toMap)
      }

      implicit val minusOneWeights = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, () => -1.0)).toMap)
      }

      implicit val randomWeights: WeightProvider[Double] = apply(-1, 1)

      /**
        * Gives a weight provider with random weights in range `r`.
        */
      def apply(r: (Double, Double)): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, randomD(r))).toMap)
      }

      def static(seed: Double): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, () => seed)).toMap)
      }

      /**
        * Gives a weight provider with weights drawn from normal distribution.
        */
      def normal(μ: Double, σ: Double): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, normalD(μ, σ))).toMap)
      }

      /**
        * Gives a weight provider with weights drawn from normal distribution.
        * The parameters are specified for each layer individually using `config` (0-index based).
        */
      def normal(config: Map[Int, (Double, Double)]): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, config.mapValues { case (μ, σ) => normalD(μ, σ) } )
      }

    }

    object CNN extends BaseOps[Double] {

      implicit val zeroWeights = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, () => 0.0)).toMap)
      }

      implicit val oneWeights = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, () => 1.0)).toMap)
      }

      implicit val minusOneWeights = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, () => -1.0)).toMap)
      }

      implicit val randomWeights: WeightProvider[Double] = apply(-1, 1)

      /**
        * Gives a weight provider with random weights in range `r`.
        */
      def apply(r: (Double, Double)): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, randomD(r))).toMap)
      }

      def static(seed: Double): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, () => seed)).toMap)
      }

      /**
        * Gives a weight provider with weights drawn from normal distribution.
        */
      def normal(μ: Double, σ: Double): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, normalD(μ, σ))).toMap)
      }

      /**
        * Gives a weight provider with weights drawn from normal distribution.
        * The parameters are specified for each layer individually using `config` (0-index based).
        */
      def normal(config: Map[Int, (Double, Double)]): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, config.mapValues { case (μ, σ) => normalD(μ, σ) } )
      }

    }

    object RNN extends BaseOps[Double] {

      /**
        * Gives a weight provider with random weights in range `r`.
        */
      def apply(r: (Double, Double)): WeightProvider[Double] = new WeightProvider[Double] {
        def apply(layers: Seq[Layer]): Weights = {
          val fc = fullyConnected(layers, layers.indices.map((_, randomD(r))).toMap)
          fc ++ recurrentEnrichment(layers, fc, layers.indices.map((_, randomD(r))).toMap)
        }
      }

      implicit val randomWeights: WeightProvider[Double] = apply(-1, 1)

    }

  }

  object Float {

    object FFN extends BaseOps[Float] {

      implicit val zeroWeights = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, () => 0.0f)).toMap)
      }

      implicit val oneWeights = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, () => 1.0f)).toMap)
      }

      implicit val minusOneWeights = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, () => -1.0f)).toMap)
      }

      implicit val randomWeights: WeightProvider[Float] = apply(-1, 1)

      /**
        * Gives a weight provider with random weights in range `r`.
        */
      def apply(r: (Float, Float)): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, randomF(r))).toMap)
      }

      def static(seed: Float): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, () => seed)).toMap)
      }

      /**
        * Gives a weight provider with weights drawn from normal distribution.
        */
      def normal(μ: Float, σ: Float): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, layers.indices.map((_, normalF(μ, σ))).toMap)
      }

      /**
        * Gives a weight provider with weights drawn from normal distribution.
        * The parameters are specified for each layer individually using `config` (0-index based).
        */
      def normal(config: Map[Int, (Float, Float)]): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, config.mapValues { case (μ, σ) => normalF(μ, σ) } )
      }

    }

    object CNN extends BaseOps[Float] {

      implicit val zeroWeights = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, () => 0.0f)).toMap)
      }

      implicit val oneWeights = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, () => 1.0f)).toMap)
      }

      implicit val minusOneWeights = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, () => -1.0f)).toMap)
      }

      implicit val randomWeights: WeightProvider[Float] = apply(-1, 1)

      /**
        * Gives a weight provider with random weights in range `r`.
        */
      def apply(r: (Float, Float)): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, randomF(r))).toMap)
      }

      def static(seed: Float): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, () => seed)).toMap)
      }

      /**
        * Gives a weight provider with weights drawn from normal distribution.
        */
      def normal(μ: Float, σ: Float): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = convoluted(layers, layers.indices.map((_, normalF(μ, σ))).toMap)
      }

      /**
        * Gives a weight provider with weights drawn from normal distribution.
        * The parameters are specified for each layer individually using `config` (0-index based).
        */
      def normal(config: Map[Int, (Float, Float)]): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = fullyConnected(layers, config.mapValues { case (μ, σ) => normalF(μ, σ) } )
      }

    }

    object RNN extends BaseOps[Float] {

      /**
        * Gives a weight provider with random weights in range `r`.
        */
      def apply(r: (Float, Float)): WeightProvider[Float] = new WeightProvider[Float] {
        def apply(layers: Seq[Layer]): Weights = {
          val fc = fullyConnected(layers, layers.indices.map((_, randomF(r))).toMap)
          fc ++ recurrentEnrichment(layers, fc, layers.indices.map((_, randomF(r))).toMap)
        }
      }

      implicit val randomWeights: WeightProvider[Float] = apply(-1, 1)

    }

  }

}
