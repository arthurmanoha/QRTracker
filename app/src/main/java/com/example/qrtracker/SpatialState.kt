
package com.example.qrtracker

/**
 * Position et orientation d'un objet dans le repère global.
 *
 * La matrice de rotation contient 9 valeurs, rangées par lignes.
 */
data class SpatialPose(
    val x: Double,
    val y: Double,
    val z: Double,
    val rotationMatrix: DoubleArray
)

/**
 * État spatial global de QRTracker.
 *
 * Convention :
 * - X : droite
 * - Y : bas
 * - Z : avant
 * - Les matrices utilisent des vecteurs colonnes.
 */
class SpatialState {

    // Transformations fixes : repère global -> repère du marqueur.
    private val markers = mutableMapOf<Int, DoubleArray>()

    // Position actuelle de la caméra dans le repère global.
    var cameraPose: SpatialPose? = null
        private set

    /**
     * Enregistre la pose connue d'un marqueur dans la pièce.
     *
     * rotationMatrix : matrice 3x3 rangée par lignes.
     */
    fun registerMarker(
        id: Int,
        x: Double,
        y: Double,
        z: Double,
        rotationMatrix: DoubleArray
    ) {
        require(rotationMatrix.size == 9) {
            "La matrice de rotation doit contenir 9 valeurs."
        }

        markers[id] = createTransform(
            x, y, z, rotationMatrix
        )
    }

    /**
     * Met à jour la position de la caméra à partir
     * d'un marqueur connu et de sa pose observée.
     */
    fun updateFromMarker(
        id: Int,
        observedPose: MarkerPose
    ): Boolean {

        val worldFromMarker = markers[id] ?: return false

        val cameraFromMarker = createTransform(
            observedPose.x,
            observedPose.y,
            observedPose.z,
            observedPose.rotationMatrix
        )

        // Inverse de la transformation caméra <- marqueur :
        // on obtient marqueur <- caméra.
        val markerFromCamera = invertRigidTransform(
            cameraFromMarker
        )

        // Transformation globale <- caméra.
        val worldFromCamera = multiplyTransforms(
            worldFromMarker,
            markerFromCamera
        )

        cameraPose = SpatialPose(
            x = worldFromCamera[3],
            y = worldFromCamera[7],
            z = worldFromCamera[11],
            rotationMatrix = doubleArrayOf(
                worldFromCamera[0],
                worldFromCamera[1],
                worldFromCamera[2],
                worldFromCamera[4],
                worldFromCamera[5],
                worldFromCamera[6],
                worldFromCamera[8],
                worldFromCamera[9],
                worldFromCamera[10]
            )
        )

        return true
    }

    fun isMarkerKnown(id: Int): Boolean {
        return markers.containsKey(id)
    }

    fun markerCount(): Int {
        return markers.size
    }

    fun resetCameraPose() {
        cameraPose = null
    }

    private fun createTransform(
        x: Double,
        y: Double,
        z: Double,
        r: DoubleArray
    ): DoubleArray {

        return doubleArrayOf(
            r[0], r[1], r[2], x,
            r[3], r[4], r[5], y,
            r[6], r[7], r[8], z,
            0.0, 0.0, 0.0, 1.0
        )
    }

    private fun multiplyTransforms(
        a: DoubleArray,
        b: DoubleArray
    ): DoubleArray {

        val result = DoubleArray(16)

        for (row in 0..3) {
            for (col in 0..3) {
                for (k in 0..3) {
                    result[row * 4 + col] +=
                        a[row * 4 + k] * b[k * 4 + col]
                }
            }
        }

        return result
    }

    /**
     * Inverse d'une transformation rigide :
     * rotation transposée et translation transformée.
     */
    private fun invertRigidTransform(
        transform: DoubleArray
    ): DoubleArray {

        val result = DoubleArray(16)

        // Transposée de la rotation.
        for (row in 0..2) {
            for (col in 0..2) {
                result[row * 4 + col] =
                    transform[col * 4 + row]
            }
        }

        // Translation inverse : -R^T * t.
        for (row in 0..2) {
            result[row * 4 + 3] = -(
                    result[row * 4] * transform[3] +
                            result[row * 4 + 1] * transform[7] +
                            result[row * 4 + 2] * transform[11]
                    )
        }

        result[15] = 1.0

        return result
    }
}
