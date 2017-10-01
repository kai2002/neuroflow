package neuroflow.core

/**
  * @author bogdanski
  * @since 03.01.16
  */


/** Base-label for all layers. */
sealed trait Layer extends Serializable {
  val neurons: Int
  val symbol: String
}

sealed trait In
sealed trait Out
sealed trait Hidden


/**
  * A dense input layer is the first fully connected one in a FFN, where:
  *   `neurons`      Number of neurons in this layer
  */
case class Input(neurons: Int) extends Layer with In {
  val symbol: String = "In"
}

/**
  * A dense output layer is the last fully connected one in a FFN, where:
  *   `neurons`      Number of neurons in this layer
  *   `activator`    The activator function gets applied on the output element-wise.
  */
case class Output[V](neurons: Int, activator: Activator[V]) extends Layer with HasActivator[V] with Out {
  val symbol: String = "Out"
}

/**
  * A dense layer is a fully connected one, where:
  *   `neurons`      Number of neurons in this layer
  *   `activator`    The activator function gets applied on the output element-wise.
  */
case class Dense[V](neurons: Int, activator: Activator[V]) extends Layer with Hidden with HasActivator[V] {
  val symbol: String = "Dense"
}

/**
  * A focus layer is used if the desired model output
  * is not the [[Out]] layer, but a hidden one. (AutoEncoders, PCA, ...)
  *   `inner`      The inner layer
  */
case class Focus[V](inner: Layer with HasActivator[V]) extends Layer {
  val symbol: String = s"Focus(${inner.symbol}(${inner.activator.symbol}))"
  val neurons: Int = inner.neurons
}

/**
  *
  * Convolutes the input volume, where:
  *
  *   `dimIn`      Input dimension. (width, height, depth)
  *   `padding`    A padding can be specified to ensure full convolution. (width, height)
  *   `field`      The receptive field. (width, height)
  *   `filters`    Number of independent filters attached to the input.
  *   `stride`     Sliding the receptive field over the input volume with stride. (width, height)
  *   `activator`  The activator function gets applied on the output element-wise.
  *
  */
case class Convolution[V](dimIn:    (Int, Int, Int),
                          padding:  (Int, Int),
                          field:    (Int, Int),
                          stride:   (Int, Int),
                          filters:   Int,
                          activator: Activator[V]) extends Layer with HasActivator[V] with Hidden with In {

  val symbol: String = "Convolution"

  val dimInPadded: (Int, Int, Int) =
    (dimIn._1 + (2 * padding._1),
     dimIn._2 + (2 * padding._2),
     dimIn._3)

  val dimOut: (Int, Int, Int) =
    ((dimIn._1 + (2 * padding._1) - field._1) / stride._1 + 1,
     (dimIn._2 + (2 * padding._2) - field._2) / stride._2 + 1,
      filters)

  val neurons: Int = dimOut._1 * dimOut._2 * dimOut._3 // output relevance

  private val _d1 = dimIn._1 + (2 * padding._1) - field._1
  private val _d2 = dimIn._2 + (2 * padding._2) - field._2

  assert(filters  > 0, "Filters must be positive!")
  assert(stride._1 > 0 && stride._2 > 0, "Strides must be positive!")
  assert(field._1 > 0 && field._2 > 0, "Field must be positive!")
  assert(dimIn._1 > 0 && dimIn._2 > 0 && dimIn._3 > 0, "Input dimension must be positive!")
  assert(_d1 >= 0, s"Field $field is too big for input width ${dimIn._1}!")
  assert(_d2 >= 0, s"Field $field is too big for input height ${dimIn._2}!")
  assert(_d1 % stride._1 == 0, s"Width ${_d1} doesn't match stride ${stride._1}!")
  assert(_d2 % stride._2 == 0, s"Height ${_d2} doesn't match stride ${stride._2}!")

}

object Convolution {

  implicit class IntTupler(i: Int) {
    def `²`: (Int, Int) = (i, i)
    def `³`: (Int, Int, Int) = (i, i, i)
  }

}
