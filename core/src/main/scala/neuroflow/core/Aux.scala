package neuroflow.core

import scala.annotation.implicitNotFound

/**
  * @author bogdanski
  * @since 14.10.17
  */

@implicitNotFound("No `${A} CanProduce ${B}` in scope.")
trait CanProduce[A, B] {
  def apply(a: A): B
}

object CanProduce {

  implicit object DoubleCanProduceDouble extends (Double CanProduce Double) {
    def apply(double: Double) = double
  }

  implicit object DoubleCanProduceFloat extends (Double CanProduce Float) {
    def apply(double: Double) = double.toFloat
  }

}

@implicitNotFound("No `TypeSize[${V}]` in scope.")
trait TypeSize[V] {
  def apply(): Int
}

object TypeSize {

  implicit object TypeSizeInt extends TypeSize[Int] {
    def apply(): Int = 4
  }

  implicit object TypeSizeFloat extends TypeSize[Float] {
    def apply(): Int = 4
  }

  implicit object TypeSizeDouble extends TypeSize[Double] {
    def apply(): Int = 8
  }

}
