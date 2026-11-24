package es.bicilab.bicicam.camera

import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Última posición y velocidad que da el Karoo, para pasárselas a las cámaras que guardan el GPS con el vídeo.
 * La extensión las va actualizando; quien las lee decide si aún son recientes.
 */
object LocationFeed {
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** Rumbo en grados (0 si el Karoo no lo da). */
        val bearing: Double,
        /** Velocidad en metros por segundo: la del Karoo si la da; si no, calculada entre posiciones. */
        val speedMps: Double,
        /** [SystemClock.elapsedRealtime] del momento en que llegó. */
        val at: Long,
    )

    @Volatile
    private var last: Fix? = null

    @Volatile
    private var karooSpeed: Double? = null

    @Volatile
    private var karooSpeedAt = 0L

    /** La posición más reciente si no tiene más de [maxAgeMs]; si no, null. */
    fun current(maxAgeMs: Long = 5_000): Fix? {
        val now = SystemClock.elapsedRealtime()
        val fix = last?.takeIf { now - it.at <= maxAgeMs } ?: return null
        val speed = karooSpeed?.takeIf { now - karooSpeedAt <= 5_000 }
        return if (speed != null) fix.copy(speedMps = speed) else fix
    }

    /** Velocidad que mide el Karoo (sensor de rueda o GPS), en metros por segundo. */
    fun updateSpeed(metersPerSecond: Double) {
        if (metersPerSecond < 0 || metersPerSecond > 60) return
        karooSpeed = metersPerSecond
        karooSpeedAt = SystemClock.elapsedRealtime()
    }

    fun update(latitude: Double, longitude: Double, bearing: Double?) {
        val now = SystemClock.elapsedRealtime()
        val previous = last
        var speed = 0.0
        if (previous != null) {
            val seconds = (now - previous.at) / 1000.0
            if (seconds in 0.2..10.0) {
                val raw = distanceMeters(previous.latitude, previous.longitude, latitude, longitude) / seconds
                // Suavizado, y sin saltos imposibles en bici por un mal punto de GPS
                speed = if (raw < 40.0) previous.speedMps * 0.4 + raw * 0.6 else previous.speedMps
            }
        }
        last = Fix(latitude, longitude, bearing ?: previous?.bearing ?: 0.0, speed, now)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
