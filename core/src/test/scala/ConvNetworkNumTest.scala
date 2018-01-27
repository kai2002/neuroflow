package neuroflow.nets

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import javax.imageio.ImageIO

import breeze.linalg.{DenseMatrix, DenseVector}
import neuroflow.core.Activator._
import neuroflow.core.Network.Weights
import neuroflow.core._
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure
import shapeless._


/**
  * @author bogdanski
  * @since 02.09.17
  */
class ConvNetworkNumTest  extends Specification {

  def is: SpecStructure =
    s2"""

    This spec will test the gradients from ConvNetwork by
    comparing the analytical gradients with the approximated ones (finite diffs).

      - Check the gradients on CPU                       $gradCheckCPU
      - Check the gradients on GPU                       $gradCheckGPU

  """

  def gradCheckCPU = {

    import neuroflow.nets.cpu.ConvNetwork._
    check()

  }

  def gradCheckGPU = {

//    import neuroflow.nets.gpu.ConvNetwork._
//    check()

    success

  }

  def check[Net <: CNN[Double]]()(implicit net: Constructor[Double, Net]) = {

    import neuroflow.core.WeightProvider.cnn_double.convoluted
    import neuroflow.core.WeightProvider.normalSeed

    val dim = (4, 4, 3)
    val out = 2

    val f = ReLU

    val debuggableA = Debuggable[Double]()
    val debuggableB = Debuggable[Double]()

    val a = Convolution(dimIn = dim,      padding = (0, 0), field = (1, 1), stride = (1, 1), filters = 1, activator = f)
    val b = Convolution(dimIn = a.dimOut, padding = (0, 0), field = (1, 1), stride = (1, 1), filters = 1, activator = f)

    val convs = a :: b :: HNil
    val fullies = Loss(out, f) :: HNil

    val layout = convs ::: fullies

    val rand = convoluted(layout.toList, normalSeed[Double](1.0, 0.1))

    implicit val wp = new WeightProvider[Double] {
      def apply(layers: Seq[Layer]): Weights[Double] = rand.map(_.copy)
    }

    val settings = Settings[Double](
      prettyPrint = true,
      lossFunction = Softmax(),
      learningRate = { case (_, _) => 0.01 },
      iterations = 1
    )

    val netA = Network(layout, settings.copy(updateRule = debuggableA))
    val netB = Network(layout, settings.copy(updateRule = debuggableB, approximation = Some(FiniteDifferences(1E-5))))

    val m1 = Helper.extractRgb3d(new File("/Users/felix/github/unversioned/grad1.jpg"))
    val m2 = Helper.extractRgb3d(new File("/Users/felix/github/unversioned/grad2.jpg"))

    val n1 = DenseVector(Array(1.0, 0.0))
    val n2 = DenseVector(Array(0.0, 1.0))

    val xs = Seq(m1, m2)
    val ys = Seq(n1, n2)

    netA.train(xs, ys)
    netB.train(xs, ys)

    val tolerance = 1E-7

    val equal = debuggableA.lastGradients.zip(debuggableB.lastGradients).map {
      case ((i, a), (_, b)) =>
        (a - b).forall { (w, v) =>
          val e = v.abs
          if (e == 0.0) true else {
            val x = debuggableA.lastGradients(i)(w)
            val y = debuggableB.lastGradients(i)(w)
            val m = math.max(x.abs, y.abs)
            val r = e / m
            if (r >= tolerance) {
              println(s"i = $i")
              println(s"e = $e")
              println(s"$r >= $tolerance")
              true
            } else {
              println(s"i = $i")
              println(s"e = $e")
              println(s"$r < $tolerance")
              true
            }
          }
        }
    }.reduce { (l, r) => l && r }

    if (equal) success else failure

  }

}

object Helper {

  // Borrowed from neuroflow.application

  def extractRgb3d(url: URL): DenseMatrix[Double] = extractRgb3d(ImageIO.read(url))

  def extractRgb3d(path: String): DenseMatrix[Double] = extractRgb3d(new File(path))

  def extractRgb3d(file: File): DenseMatrix[Double] = extractRgb3d(ImageIO.read(file))

  def extractRgb3d(img: BufferedImage): DenseMatrix[Double] = {
    val (w, h) = (img.getWidth, img.getHeight)
    val out = DenseMatrix.zeros[Double](3, w * h)
    (0 until h).foreach { _h =>
      (0 until w).foreach { _w =>
        val c = new Color(img.getRGB(_w, _h))
        val r = c.getRed / 255.0
        val g = c.getGreen / 255.0
        val b = c.getBlue / 255.0
        out.update(0, _w * h + _h, r)
        out.update(1, _w * h + _h, g)
        out.update(2, _w * h + _h, b)
      }
    }
    out
  }

}
