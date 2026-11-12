package com.yamking.iptv.data

import kotlinx.serialization.Serializable

@Serializable
data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val groups: List<String> = emptyList(),
    val tvgId: String? = null,
    val quality: String? = null,
    /** Code pays ISO à deux lettres en majuscules (FR, US…), lu dans la playlist ou déduit de l'identifiant. */
    val country: String? = null,
    /** Langue telle que fournie par la playlist (jamais devinée). */
    val language: String? = null,
)

@Serializable
data class SavedState(
    val playlistName: String = "",
    val all: List<Channel> = emptyList(),
    val validUrls: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val scanned: Boolean = false,
    /** 2 = bibliothèque cumulée (les chaînes masquées par un recontrôle restent enregistrées). */
    val version: Int = 1,
    /** Qualité par défaut (hauteur en pixels), null = automatique. */
    val maxHeight: Int? = null,
    /** Adresses des dernières chaînes regardées, la plus récente d'abord. */
    val recents: List<String> = emptyList(),
    /** Date du dernier contrôle complet de la bibliothèque (millisecondes). */
    val lastScanAt: Long = 0,
    /** Chaînes d'un import interrompu qui restent à vérifier. */
    val pending: List<Channel> = emptyList(),
    /** Verrou « contenu pour adultes » : empreinte du code et son sel. Le code lui-même n'est jamais enregistré. */
    val adultPinHash: String? = null,
    val adultPinSalt: String? = null,
)
