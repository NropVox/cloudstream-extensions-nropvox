package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** `GET /wp-json/wp/v2/posts`; [content] only comes with the single-post request. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KaPost(
    val id: Int? = null,
    val title: KaRendered? = null,
    val content: KaRendered? = null,
    val categories: List<Int>? = null,
    val featured_media: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KaRendered(val rendered: String? = null)

/** `GET /wp-json/wp/v2/media?include=…&_fields=id,source_url`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KaMedia(val id: Int? = null, val source_url: String? = null)

/** `GET <proxy>/resolve?url=<drive id>`: a file, or a folder whose [children] are one level deep. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KaNode(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val children: List<KaNode>? = null,
)

/** `POST <index folder url>` on a Google Drive Index worker: one page of a folder, 100 entries at most. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KaIndexPage(val nextPageToken: String? = null, val data: KaIndexData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KaIndexData(val files: List<KaIndexFile>? = null)

/** An index entry; folders have the Drive folder [mimeType]. Its `id` is encrypted and not a Drive id. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KaIndexFile(val name: String? = null, val mimeType: String? = null)
