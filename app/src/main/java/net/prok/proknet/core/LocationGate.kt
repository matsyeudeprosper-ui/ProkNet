package net.prok.proknet.core

/**
 * v0.17.5: ProkNet cannot find anybody without a coarse position, so it has to say so.
 *
 * THE DEFECT THIS FILE EXISTS FOR, found on hardware 2026-09-23. The Network Brain is
 * gated on the zone from end to end:
 *
 *  - `ProviderPresence.of` returns null for [CoverageModel.NO_ZONE], so no presence is
 *    published and no jobs are polled;
 *  - `createDemand` returns "" without a zone, so no demand is ever created;
 *  - `refreshZone` returns early, so the map never colours.
 *
 * On the pilot phone every one of those failed silently. The provider saw nothing at all.
 * The buyer saw "Aucun Internet disponible tout de suite" - which is not a lie, but it
 * describes the wrong problem, and no user could ever work out that a missing permission
 * had switched off the whole network layer.
 *
 * Worse, the app only ever ASKED for the permission on the map tab. A user who never
 * opens the map is never asked, and nothing else in the product hints that the map is
 * where the network lives. That is the "silence is not an answer" rule again: every
 * refusable step needs an explicit refusal the user can act on.
 *
 * So this decides, in one pure place, what the phone should say and which single tap
 * fixes it. Nobody is sent hunting through Android settings: the normal answer is the
 * system dialog, and the settings screens are opened directly only when Android will no
 * longer show that dialog.
 */
object LocationGate {

    enum class Need {
        /** A position is available, or on its way. Say nothing. */
        NONE,

        /** Android will still show the permission dialog: one tap, yes or no. */
        ASK_PERMISSION,

        /**
         * The dialog is exhausted - denied twice, or "don't ask again". Only the app's
         * own settings page can grant it now, so open that page directly.
         */
        OPEN_SETTINGS,

        /** Granted, but the phone's location switch is off. Open that switch directly. */
        TURN_ON_LOCATION,

        /**
         * v0.17.7: this BUILD cannot ask, because the permission is not in its manifest.
         *
         * Sending somebody to a settings page that has no Position entry is worse than
         * saying nothing - they tap Ouvrir, find nothing, and conclude the app is broken.
         * That is exactly what build 73 did to the pilot phone. Nothing the user can do
         * fixes this; only a new build does, so say that instead.
         */
        NOT_IN_BUILD,
    }

    /**
     * @param hasPermission coarse or fine location granted to this app.
     * @param locationServicesOn the phone's own location switch.
     * @param canAskInApp whether `requestPermissions` will still produce a dialog. The
     *   caller works this out from "have we ever asked" plus
     *   `shouldShowRequestPermissionRationale`, because Android cannot tell "never asked"
     *   from "permanently denied" on its own.
     */
    fun need(hasPermission: Boolean, locationServicesOn: Boolean, canAskInApp: Boolean,
             declaredInBuild: Boolean = true): Need = when {
        hasPermission && locationServicesOn -> Need.NONE
        // v0.17.7: before anything else - can this build even ask? If the permission is
        // missing from the manifest, every other answer here is a lie.
        !hasPermission && !declaredInBuild -> Need.NOT_IN_BUILD
        // granted but switched off at the phone level: the permission dialog would be
        // pointless and confusing, because the user already said yes
        hasPermission -> Need.TURN_ON_LOCATION
        canAskInApp -> Need.ASK_PERMISSION
        else -> Need.OPEN_SETTINGS
    }

    /** Is there anything to show at all? */
    fun blocked(n: Need): Boolean = n != Need.NONE

    fun title(n: Need): String = when (n) {
        Need.NONE -> ""
        Need.TURN_ON_LOCATION -> "Activez la localisation"
        Need.NOT_IN_BUILD -> "Cette version ne peut pas demander votre zone"
        else -> "ProkNet a besoin de votre zone"
    }

    /**
     * Why, in one honest paragraph. The privacy promise is repeated every time it is
     * asked for, because a permission granted without understanding is not consent.
     */
    fun message(n: Need): String = when (n) {
        Need.NONE -> ""
        Need.ASK_PERMISSION ->
            "Pour trouver quelqu'un qui partage Internet près de vous, ProkNet doit " +
                "connaître votre zone, à 500 m près.\n\n" +
                "Jamais votre position exacte. Jamais l'historique de vos déplacements. " +
                "Sans cela, ProkNet ne peut voir personne."
        Need.OPEN_SETTINGS ->
            "ProkNet ne peut voir personne autour de vous sans votre zone, à 500 m près.\n\n" +
                "Android ne peut plus poser la question ici. Appuyez sur Ouvrir : " +
                "ProkNet vous emmène directement au bon écran, puis choisissez " +
                "« Position »."
        Need.TURN_ON_LOCATION ->
            "Vous avez autorisé ProkNet, mais la localisation du téléphone est éteinte.\n\n" +
                "Appuyez sur Ouvrir pour l'allumer. ProkNet ne voit personne tant " +
                "qu'elle est éteinte."
        Need.NOT_IN_BUILD ->
            "Cette version de ProkNet ne contient pas l'autorisation de position, donc " +
                "elle ne peut pas vous la demander et les réglages ne l'afficheront pas " +
                "non plus.\n\n" +
                "Ce n'est pas votre téléphone : installez la dernière version de ProkNet."
    }

    /** The one button that fixes it. Never "go and find it yourself". */
    fun button(n: Need): String = when (n) {
        Need.NONE -> ""
        Need.ASK_PERMISSION -> "Autoriser"
        // nothing to open: a settings page with no Position entry is a dead end
        Need.NOT_IN_BUILD -> ""
        else -> "Ouvrir"
    }

    /**
     * The quiet line on Accueil and Gagner, so somebody who dismissed the dialog still
     * sees why nothing is happening instead of concluding the app is broken.
     */
    fun note(n: Need): String = when (n) {
        Need.NONE -> ""
        Need.TURN_ON_LOCATION -> "Localisation éteinte : ProkNet ne voit personne autour de vous."
        Need.NOT_IN_BUILD -> "Cette version ne peut pas demander votre zone : mettez ProkNet à jour."
        else -> "Zone inconnue : autorisez la position pour trouver Internet près de vous."
    }

    /** For the diagnostic, in English like the rest of it. */
    fun diag(n: Need): String = when (n) {
        Need.NONE -> "ok"
        Need.ASK_PERMISSION -> "NO PERMISSION (the in-app dialog can still be shown)"
        Need.OPEN_SETTINGS -> "NO PERMISSION (dialog exhausted; app settings needed)"
        Need.TURN_ON_LOCATION -> "granted, but the phone's location switch is OFF"
        Need.NOT_IN_BUILD -> "NOT DECLARED IN THIS BUILD - no dialog and no settings entry exist"
    }
}
