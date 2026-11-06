package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpornerSearch(
    val page: Int? = null,
    val total_pages: Int? = null,
    val videos: List<EpornerVideo>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpornerThumb(val src: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpornerVideo(
    val id: String? = null,
    val title: String? = null,
    val keywords: String? = null,
    val views: Long? = null,
    val rate: String? = null,
    val url: String? = null,
    val added: String? = null,
    val length_min: String? = null,
    val default_thumb: EpornerThumb? = null,
)
