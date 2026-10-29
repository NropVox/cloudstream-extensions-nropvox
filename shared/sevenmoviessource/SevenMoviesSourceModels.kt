package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

internal data class SevenLink(val id: Int, val type: String, val season: Int? = null, val episode: Int? = null)
internal data class SevenGrantRequest(val tmdbId: Int, val type: String, val season: Int?, val episode: Int?)
internal data class SevenGrant(val token: String? = null)
internal data class SevenBoot(val meta: SevenMeta? = null, val shell: SevenShell? = null)
internal data class SevenMeta(
    @JsonProperty("playbackToken") val token: String? = null,
    val parent: String? = null, val unreleased: String? = null, val anime: JsonNode? = null,
    val evionUrl: String? = null, val vexonUrl: String? = null,
    val selfhostUrl: String? = null, val selfhostKind: String? = null,
    val orionStreams: List<SevenStream>? = null,
)
internal data class SevenShell(val subtitles: List<SevenSubtitle>? = null)
internal data class SevenSources(
    val provider: String? = null, val maxRes: Int? = null,
    val streams: List<SevenStream>? = null, val subtitles: List<SevenSubtitle>? = null,
)
internal data class SevenStream(
    val url: String? = null, val proxyUrl: String? = null, val type: String? = null,
    val name: String? = null, val rungs: List<SevenRung>? = null, val subtitleListUrl: String? = null,
)
internal data class SevenRung(val height: Int? = null)
internal data class SevenSubtitle(val url: String? = null, val label: String? = null, val lang: String? = null)
internal data class SevenTracks(val tracks: List<SevenSubtitle>? = null)
