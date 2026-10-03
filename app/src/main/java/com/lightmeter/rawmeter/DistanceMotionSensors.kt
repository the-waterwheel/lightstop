package com.lightmeter.rawmeter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/** Bounded, timestamped IMU history. Sensor callbacks share the camera handler. */
internal class DistanceMotionSensors(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val acceleration = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val history = DistanceImuHistory()
    private var omega = DistanceVector.ZERO
    private var gyroTimestamp = 0L
    private var gyroReliable = false
    var running = false
        private set

    fun start(handler: Handler): Boolean {
        if (running) return true
        if (gyro == null || acceleration == null) return false
        running = manager?.registerListener(this, gyro, 10_000, handler) == true &&
            manager.registerListener(this, acceleration, 10_000, handler)
        if (!running) stop()
        return running
    }

    fun stop() {
        manager?.unregisterListener(this)
        running = false
        history.clear()
        gyroTimestamp = 0L
        gyroReliable = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val vector = DistanceVector(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        if (!vector.finite()) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            omega = vector
            gyroTimestamp = event.timestamp
            gyroReliable = event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION && gyroReliable &&
            event.timestamp - gyroTimestamp in 0..25_000_000L
        ) history.add(DistanceImuSample(event.timestamp, vector, omega))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** No extrapolation or nearest-clock substitution: both image times must be bracketed. */
    fun between(startNs: Long, endNs: Long) = history.between(startNs, endNs)
    fun quietAt(timestampNs: Long) = history.quietAt(timestampNs)
    fun focusReliability(timestampNs: Long) = history.focusReliability(timestampNs)
}
