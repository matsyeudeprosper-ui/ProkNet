package net.prok.proknet.core

/**
 * v0.19: a walking route along real streets, or nothing.
 *
 * `null` means "route unavailable" - the caller shows an APPROXIMATE straight-line distance
 * and never an ETA (launch contract 10.2: "Avoid a fabricated ETA"). A non-null route is
 * along the pedestrian graph of the offline map pack, with the distance in metres and the
 * time at a walking pace.
 */
interface WalkRouter {
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute?
}

/**
 * @param distanceMeters along the graph, snap legs included
 * @param etaSeconds at 4.5 km/h, +10 % on steps and unpaved track
 * @param points `[lat, lon]` pairs from the origin, along the graph, to the destination
 */
class WalkRoute(val distanceMeters: Double, val etaSeconds: Int, val points: List<DoubleArray>)

/**
 * v0.19.0: no map pack installed - never a route. The finder then shows
 * "≈ … à vol d'oiseau" and no ETA. `PlacesActivity.router` starts as this and is replaced
 * by the loaded [ProkMap] when a pack is on the phone.
 */
object StraightLineRouter : WalkRouter {
    override fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute? = null
}
