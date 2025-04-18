package it.unibo.collektive.examples.gradient

import it.unibo.alchemist.collektive.device.CollektiveDevice
import it.unibo.collektive.aggregate.api.Aggregate
import it.unibo.collektive.aggregate.api.share
import it.unibo.collektive.alchemist.device.sensors.EnvironmentVariables
import it.unibo.collektive.field.operations.min
import it.unibo.collektive.stdlib.doubles.FieldedDoubles.plus
import kotlin.Double.Companion.POSITIVE_INFINITY

/**
 * Extension function to evaluate the gradient in an [Aggregate] context.
 */
fun Aggregate<Int>.gradient(distanceSensor: CollektiveDevice<*>, source: Boolean): Double =
    share(POSITIVE_INFINITY) {
        val dist = with(distanceSensor) { distances() }
        when {
            source -> 0.0
            else -> (it + dist).min(POSITIVE_INFINITY)
        }
    }

/**
 * The entrypoint of the simulation running a gradient.
 */
fun Aggregate<Int>.gradientEntrypoint(env: EnvironmentVariables, distanceSensor: CollektiveDevice<*>): Double =
    gradient(distanceSensor, env["source"])
