package com.mouad.doublage

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Service vide : il existe seulement pour que l'utilisateur puisse donner « Accès aux notifications ».
 * Avec cet accès, l'app peut lire le lecteur de YouTube (titre, chaîne, position) comme les commandes média du téléphone.
 */
class EcouteNotifications : NotificationListenerService()

/** Suit le lecteur YouTube : quelle vidéo joue, et où on est rendu dedans. */
class SuiviLecteur(private val ctx: Context, private val principal: Handler,
                   private val nouvelleVideo: (titre: String, chaine: String, dureeMs: Long) -> Unit) {

    companion object {
        val APPS = setOf("com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube",
            "com.vanced.android.youtube", "com.google.android.apps.youtube.music")

        fun autorise(ctx: Context): Boolean = try {
            ctx.getSystemService(android.app.NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(ctx, EcouteNotifications::class.java))
        } catch (_: Exception) { false }
    }

    private var msm: MediaSessionManager? = null
    @Volatile private var controleur: MediaController? = null
    @Volatile private var cle = ""

    private val rappel = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) { lireVideo() }
        override fun onSessionDestroyed() { controleur = null }
    }

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { l -> choisir(l) }

    fun demarrer(): Boolean {
        if (!autorise(ctx)) return false
        return try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            val comp = ComponentName(ctx, EcouteNotifications::class.java)
            m.addOnActiveSessionsChangedListener(sessions, comp, principal)
            msm = m
            choisir(m.getActiveSessions(comp))
            true
        } catch (_: Exception) { false }
    }

    fun arreter() {
        try { msm?.removeOnActiveSessionsChangedListener(sessions) } catch (_: Exception) {}
        controleur?.unregisterCallback(rappel)
        controleur = null
    }

    private fun choisir(l: List<MediaController>?) {
        val c = l?.firstOrNull { it.packageName in APPS } ?: return
        if (c.sessionToken == controleur?.sessionToken) return
        controleur?.unregisterCallback(rappel)
        controleur = c
        c.registerCallback(rappel, principal)
        lireVideo()
    }

    private fun lireVideo() {
        val m = controleur?.metadata ?: return
        val titre = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val chaine = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val duree = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val k = "$titre|$chaine"
        if (k == cle) return
        cle = k
        nouvelleVideo(titre, chaine, duree)
    }

    /** Position actuelle dans la vidéo (ms), ou null si on ne la connaît pas. */
    fun position(): Long? {
        val s = controleur?.playbackState ?: return null
        if (s.position < 0) return null
        if (s.state != PlaybackState.STATE_PLAYING) return s.position
        val ecoule = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return s.position + (ecoule * s.playbackSpeed).toLong()
    }

    fun enLecture(): Boolean = controleur?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun actif(): Boolean = controleur != null
}
