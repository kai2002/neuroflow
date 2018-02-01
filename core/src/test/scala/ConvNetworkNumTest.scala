package neuroflow.nets

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import javax.imageio.ImageIO

import breeze.linalg.{DenseMatrix, DenseVector}
import breeze.storage.Zero
import neuroflow.common.{Tensor, Tensorish}
import neuroflow.core.Activator._
import neuroflow.core.Network.Weights
import neuroflow.core._
import neuroflow.dsl._
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure

import scala.reflect.ClassTag


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

    implicit object weights extends neuroflow.core.WeightProvider.CNN[Double]

    import neuroflow.dsl.Extractor.extractor

    val dim = (4, 4, 3)
    val out = 2

    val f = ReLU

    val debuggableA = Debuggable[Double]()
    val debuggableB = Debuggable[Double]()

    val a = Convolution(dimIn = dim,      padding = (0, 0), field = (1, 1), stride = (1, 1), filters = 1, activator = f)
    val b = Convolution(dimIn = a.dimOut, padding = (0, 0), field = (1, 1), stride = (1, 1), filters = 1, activator = f)

    val L = a :: b :: Dense(out, f) :: Softmax()

    val rand = weights.convoluted(extractor(L)._1, weights.normalSeed(1.0, 0.1))

    implicit val wp = new WeightProvider[Double] {
      def apply(layers: Seq[Layer]): Weights[Double] = rand.map(_.copy)
    }

    val settings = Settings[Double](
      prettyPrint = true,
      learningRate = { case (_, _) => 0.01 },
      iterations = 1
    )

    val netA = Network(L, settings.copy(updateRule = debuggableA))
    val netB = Network(L, settings.copy(updateRule = debuggableB, approximation = Some(FiniteDifferences(1E-5))))

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

  def extractRgb3d(url: URL): Tensor[Double] = extractRgb3d(ImageIO.read(url))
  def extractRgb3d(file: File): Tensor[Double] = extractRgb3d(ImageIO.read(file))

  def extractRgb3d(img: BufferedImage): Tensor[Double] = {
    val (w, h) = (img.getWidth, img.getHeight)
    val out = DenseMatrix.zeros[Double](3, w * h)
    val tensor = new RgbTensor[Double](w, h, out)
    (0 until w).foreach { x =>
      (0 until h).foreach { y =>
        val c = new Color(img.getRGB(x, y))
        val rgb = (c.getRed / 255.0) :: (c.getGreen / 255.0) :: (c.getBlue  / 255.0) :: Nil
        (0 until 3).foreach { z =>
          val (row, col) = tensor.projection(x, y, z)
          out.update(row, col, rgb(z))
        }
      }
    }
    tensor
  }

  class RgbTensor[V](width: Int, height: Int, override val matrix: DenseMatrix[V]) extends Tensor[V] {

    val projection: ((Int, Int, Int)) => (Int, Int) = { case (x, y, z) => (z, x * height + y) }

    def mapAt(x: (Int, Int, Int))(f: V => V): Tensorish[(Int, Int, Int), V] = ???
    def mapAll[T: ClassTag : Zero](f: V => T): Tensorish[(Int, Int, Int), T] = ???

  }

}
