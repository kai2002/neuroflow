package neuroflow.application.processor

import breeze.linalg.{DenseVector, max, sum}
import neuroflow.application.plugin.Notation.ζ
import neuroflow.core.Network.Vectors

/**
  * @author bogdanski
  * @since 17.06.16
  */
object Normalizer {


  object MaxUnit {
    /**
      * Normalizes `x` such that `x.max == 1.0`
      */
    def apply(x: DenseVector[Double]): DenseVector[Double] = x.map(_ / max(x))
  }

  object Binary {
    /**
      * Turns `x` into a binary representation, where
      * components > `f` are considered to be 1, 0 otherwise.
      */
    def apply(x: DenseVector[Double], f: Double = 0.0): DenseVector[Double] = {
      x.map {
        case i if i > f => 1.0
        case _          => 0.0
      }
    }
  }

  object ScaledVectorSpace {
    /**
      * Scales all components in `xs` by max length vector division.
      */
    def apply(xs: Vectors[Double]): Vectors[Double] = {
      val max = xs.map(x => VectorLength(x)).max
      xs.map(x => x.map(_ / max))
    }
  }

  object VectorLength {
    /**
      * Computes the length of `x`.
      */
    def apply(x: DenseVector[Double]): Double = math.sqrt(sum(x.map(x => x * x)))
  }

  object VectorFlatten {
    /**
      * Extracts the original hot vectors from horizontally merged `x`.
      */
    def apply(x: DenseVector[Double]): Vectors[Double] = x.data.zipWithIndex.flatMap {
      case (v, i) if v >= 1.0 => Some({ val m = ζ[Double](x.size); m.update(i, 1.0); m })
      case (v, i) if v == 0.0 => None
      case (v, i) if v  < 0.0 => Some({ val m = ζ[Double](x.size); m.update(i, -1.0); m })
    }
  }

  object HotVectorIndex {
    /**
      * Locates the index of hot vector `x`.
      */
    def apply(x: DenseVector[Double]): Int = {
      val wi = x.data.zipWithIndex
      wi.find(_._1 == 1.0) match {
        case Some(h1) => h1._2
        case None => wi.find(_._1 == -1.0) match {
          case Some(h2) => -h2._2
          case None => throw new Exception("Doesn't work.")
        }
      }
    }
  }

  object Harmonize {
    /**
      * Harmonizes `x` by using `cap` as min/max.
      */
    def apply(x: DenseVector[Double], cap: Double = 1.0): DenseVector[Double] = x.map {
      case v if v >  cap  =>  cap
      case v if v < -cap  => -cap
      case v              =>   v
    }
  }

}
