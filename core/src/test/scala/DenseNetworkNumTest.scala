package neuroflow.nets

import breeze.linalg.DenseVector
import neuroflow.core.Activators.Double._
import neuroflow.core.Network.Weights
import neuroflow.core._
import neuroflow.dsl._
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure


/**
  * @author bogdanski
  * @since 02.08.17
  */
class DenseNetworkNumTest extends Specification {

  def is: SpecStructure =
    s2"""

    This spec will test the gradients from DenseNetwork by
    comparing the analytical gradients with the approximated ones (finite diffs).

      - Check the gradients on CPU                       $gradCheckCPU
      - Check the gradients on GPU                       $gradCheckGPU

  """

  def gradCheckCPU = {

    import neuroflow.nets.cpu.DenseNetwork._
    check()

  }

  def gradCheckGPU = {

    import neuroflow.nets.gpu.DenseNetwork._
    check()

  }

  def check[Net <: FFN[Double]]()(implicit net: Constructor[Double, Net]) = {

    implicit object weights extends neuroflow.core.WeightBreeder.Initializer[Double]

    val f = ReLU

    val L =
         Vector (2)      ::
         Dense  (7, f)   ::
         Dense  (8, f)   ::
         Dense  (7, f)   ::
         Dense  (2, f)   ::  SquaredError()

    val rand = weights.traverseAndBuild(L.toSeq[Double], weights.normalSeed(1.0, 0.1))

    implicit val breeder = new WeightBreeder[Double] {
      def apply(layers: Seq[Layer[Double]]): Weights[Double] = rand.map(_.copy)
    }

    val debuggableA = Debuggable[Double]()
    val debuggableB = Debuggable[Double]()


    val settings = Settings[Double](
      prettyPrint = true,
      learningRate = { case (_, _) => 1.0 },
      iterations = 1
    )

    val netA = Network(L, settings.copy(updateRule = debuggableA))
    val netB = Network(L, settings.copy(updateRule = debuggableB, approximation = Some(FiniteDifferences(1E-5))))

    val xs = Seq(DenseVector(0.5, 0.5), DenseVector(0.7, 0.7))
    val ys = Seq(DenseVector(1.0, 0.0), DenseVector(0.0, 1.0))

    netA.train(xs, ys)
    netB.train(xs, ys)

    val tolerance = 1E-7

    val equal = debuggableA.lastGradients.zip(debuggableB.lastGradients).map {
      case ((i, a), (_, b)) =>
        (a - b).forall { (w, v) =>
          val e = v.abs
          val x = debuggableA.lastGradients(i)(w)
          val y = debuggableB.lastGradients(i)(w)
          if (e == 0.0) true
          else if (x == 0.0 && e < tolerance) true
          else {
            val m = math.max(x.abs, y.abs)
            val r = e / m
            if (r >= tolerance) {
              println(s"i = $i")
              println(s"e = $e")
              println(s"$r >= $tolerance")
              false
            } else true
          }
        }
    }.reduce { (l, r) => l && r }

    if (equal) success else failure

  }

}
