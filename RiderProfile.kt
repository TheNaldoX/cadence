package fr.velo.cadence.model

/**
 * Niveau du cycliste. Les valeurs servent a la fois au filtrage des parcours
 * proposes et au modele physique d'estimation de duree.
 *
 * [enduranceWattsPerKg] : puissance soutenable sur plusieurs heures (zone
 * endurance, environ 60-70 % du FTP), rapportee au poids total.
 * [cda] : surface frontale effective, position typique sur le velo.
 * [maxDescentSpeedKmh] : vitesse a laquelle le cycliste cesse d'accelerer en
 * descente, par confort ou par prudence.
 */
enum class RiderLevel(
    val label: String,
    val enduranceWattsPerKg: Double,
    val cda: Double,
    val maxDescentSpeedKmh: Double,
    val typicalWeeklyKm: Int,
    val comfortableAscentPerKm: Double,
    val maxAscentPerKm: Double,
) {
    DEBUTANT(
        label = "Débutant",
        enduranceWattsPerKg = 1.5,
        cda = 0.40,
        maxDescentSpeedKmh = 40.0,
        typicalWeeklyKm = 60,
        comfortableAscentPerKm = 5.0,
        maxAscentPerKm = 10.0,
    ),
    LOISIR(
        label = "Loisir",
        enduranceWattsPerKg = 2.0,
        cda = 0.36,
        maxDescentSpeedKmh = 48.0,
        typicalWeeklyKm = 120,
        comfortableAscentPerKm = 8.0,
        maxAscentPerKm = 14.0,
    ),
    SPORTIF(
        label = "Sportif",
        enduranceWattsPerKg = 2.6,
        cda = 0.33,
        maxDescentSpeedKmh = 55.0,
        typicalWeeklyKm = 200,
        comfortableAscentPerKm = 11.0,
        maxAscentPerKm = 18.0,
    ),
    EXPERT(
        label = "Expert",
        enduranceWattsPerKg = 3.2,
        cda = 0.30,
        maxDescentSpeedKmh = 62.0,
        typicalWeeklyKm = 300,
        comfortableAscentPerKm = 14.0,
        maxAscentPerKm = 22.0,
    ),
    COMPETITEUR(
        label = "Compétiteur",
        enduranceWattsPerKg = 3.9,
        cda = 0.28,
        maxDescentSpeedKmh = 70.0,
        typicalWeeklyKm = 450,
        comfortableAscentPerKm = 18.0,
        maxAscentPerKm = 28.0,
    );

    companion object {
        fun fromName(name: String?): RiderLevel =
            entries.firstOrNull { it.name == name } ?: LOISIR
    }
}

/** Type de relief recherche, exprime en metres de denivele par kilometre. */
enum class TerrainPreference(
    val label: String,
    val minAscentPerKm: Double,
    val maxAscentPerKm: Double,
) {
    PLAT("Plat", 0.0, 6.0),
    VALLONNE("Vallonné", 6.0, 13.0),
    MONTAGNEUX("Montagneux", 13.0, 40.0),
    INDIFFERENT("Peu importe", 0.0, 40.0);

    val targetAscentPerKm: Double
        get() = (minAscentPerKm + maxAscentPerKm) / 2.0

    companion object {
        fun fromName(name: String?): TerrainPreference =
            entries.firstOrNull { it.name == name } ?: INDIFFERENT
    }
}

/**
 * Style de route recherche. Determine le profil de routage BRouter utilise.
 */
enum class RoadStyle(
    val label: String,
    val brouterProfile: String,
    val description: String,
) {
    TRANQUILLE(
        label = "Petites routes",
        brouterProfile = "fastbike-lowtraffic",
        description = "Privilégie les routes peu fréquentées, quitte à rallonger",
    ),
    DIRECT(
        label = "Direct",
        brouterProfile = "fastbike",
        description = "Le plus roulant, accepte les axes plus passants",
    ),
    POLYVALENT(
        label = "Polyvalent",
        brouterProfile = "trekking",
        description = "Mélange routes et voies vertes, tolère quelques portions non revêtues",
    ),
    GRAVEL(
        label = "Gravel",
        brouterProfile = "gravel",
        description = "Cherche les chemins carrossables et les revêtements légers",
    );

    companion object {
        fun fromName(name: String?): RoadStyle =
            entries.firstOrNull { it.name == name } ?: TRANQUILLE
    }
}

/** Profil utilisateur, persiste dans le DataStore. */
data class RiderProfile(
    val displayName: String = "",
    val level: RiderLevel = RiderLevel.LOISIR,
    val riderWeightKg: Double = 72.0,
    val bikeWeightKg: Double = 8.5,
    val maxHeartRate: Int = 190,
    val restingHeartRate: Int = 55,
    val ftpWatts: Int = 0,
    val preferredRoadStyle: RoadStyle = RoadStyle.TRANQUILLE,
    val autoLevelFromHistory: Boolean = true,
) {
    val totalMassKg: Double get() = riderWeightKg + bikeWeightKg

    /**
     * Puissance d'endurance retenue pour les estimations : le FTP saisi prime
     * sur la valeur deduite du niveau, car il est mesure.
     */
    val enduranceWatts: Double
        get() = if (ftpWatts > 0) ftpWatts * 0.68 else level.enduranceWattsPerKg * riderWeightKg
}
