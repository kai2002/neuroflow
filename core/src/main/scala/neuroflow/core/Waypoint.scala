package neuroflow.core

import neuroflow.core.Network.Weights

/**
  * @author bogdanski
  * @since 14.09.17
  */

/**
  * Performs function `action` every `nth` step.
  * The function is passed iteration count and a snapshot of the weights.
  */
case class Waypoint[V](nth: Int, action: (Int, Weights[V]) => Unit)

trait WaypointLogic[V] { self: Network[V, _, _] =>

  def waypoint(iteration: Int): Unit = {
    self.settings.waypoint match {
      case Some(Waypoint(nth, action)) =>
        if (iteration % nth == 0) {
          info("Waypoint ...")
          action(iteration, self.weights)
        }
      case _ =>
    }
  }

}
