import neuroflow.application.plugin.IO.Json
import neuroflow.core.Activator.Sigmoid
import neuroflow.core._
import neuroflow.nets.cpu.DenseNetwork._
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure
import shapeless._

/**
  * @author bogdanski
  * @since 09.01.16
  */
class IoTest extends Specification {

  sequential // IO race conditions will occur otherwise

  def is: SpecStructure = s2"""

    This spec will test IO related functionality.

    It should:
      - Serialize a net                       $serialize
      - Deserialize a net                     $deserialize

  """

  val layers = Input(2) :: Dense(3, Sigmoid) :: Output(2, Sigmoid) :: HNil
  val measure: FFN[Double] = {
    implicit val wp = neuroflow.core.WeightProvider.FFN[Double].static(0.0)
    Network(layers)
  }
  val asJson = "[{\"rows\":2,\"cols\":3,\"precision\":\"double\",\"data\":[0.0,0.0,0.0,0.0,0.0,0.0]},{\"rows\":3,\"cols\":2,\"precision\":\"double\",\"data\":[0.0,0.0,0.0,0.0,0.0,0.0]}]"

  def serialize = {
    val serialized = Json.write(measure.weights)
    serialized === asJson
  }

  def deserialize = {
    implicit val wp = Json.readDouble(asJson)
    val deserialized = Network(layers)
    deserialized.weights.toArray.map(_.toArray) === measure.weights.toArray.map(_.toArray)
  }

}
