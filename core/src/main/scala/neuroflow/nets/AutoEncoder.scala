package neuroflow.nets

import neuroflow.core.Network.Weights
import neuroflow.core._

import scala.collection.Seq

/**
  * Unsupervised Auto Encoder using a [[LBFGSNetwork]].
  *
  * @author bogdanski
  * @since 16.04.17
  */

object AutoEncoder {
  implicit val constructor: Constructor[AutoEncoder] = new Constructor[AutoEncoder] {
    def apply(ls: Seq[Layer], settings: Settings)(implicit weightProvider: WeightProvider): AutoEncoder = {
      AutoEncoder(ls, settings, weightProvider(ls))
    }
  }
}

private[nets] case class AutoEncoder(layers: Seq[Layer],
                                     settings: Settings,
                                     weights: Weights,
                                     identifier: String = Registry.register())
  extends FeedForwardNetwork with UnsupervisedTraining {

  import neuroflow.core.Network._

  private val net = new DefaultNetwork(layers, settings, weights, identifier) {
    override def sayHi(): Unit = ()
  }

  /**
    * Takes the input vector `x` to compute the output vector.
    */
  def apply(x: Vector): Vector = net(x)

  /**
    * Takes a sequence of input vectors `xs` and trains this
    * network using the unsupervised learning strategy.
    */
  def train(xs: Array[Data]): Unit = net.train(xs, xs)

}
