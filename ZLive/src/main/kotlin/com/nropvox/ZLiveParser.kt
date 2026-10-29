package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure parsing for zlive.st: list contracts, page URLs, labels and the sealed request body. */
object ZLiveParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * Baked into the site bundle (`qv(Fv, Vv, Yv)`, three XOR-masked byte arrays) together with the "k6" and "|v6"
     * salts. A site redeploy may rotate all three; `/resolve` then answers with the decoy. Re-extract them with
     * `scripts/hook.js` in the recon folder.
     */
    private const val SECRET = "Asq3YkZdzBICwscY4OO1PFAes1Mv1vJ8vxZbzYQeHZE"
    private const val SALT = "k6"
    private const val TICKET_SUFFIX = "|v6"

    /** What iptv.zlive.st hands out when the request fails its gate (no Origin, browser UA, replayed ticket). */
    private const val DECOY_HOST = "route.transcode.cfd"

    private val pageId = Regex("""/(?:watch|embed)/([A-Za-z0-9._-]+)""")
    private val flagCode = Regex("""[a-z]{2}""")

    fun channels(json: String): List<ZChannel> =
        runCatching { mapper.readValue<List<ZChannel>>(json) }.getOrDefault(emptyList())
            .filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() && !isBroken(it.name) }

    fun events(json: String): List<ZEvent> =
        runCatching { mapper.readValue<List<ZEvent>>(json) }.getOrDefault(emptyList())
            .filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }

    /** The site renames dead channels to e.g. "z[broken]". */
    private fun isBroken(name: String) = name.contains("[broken]", ignoreCase = true)

    fun watchUrl(mainUrl: String, id: String) = "$mainUrl/watch/$id"

    /** The channel or event id from a `/watch/<id>` or `/embed/<id>` URL. */
    fun pageId(url: String): String? = pageId.find(url)?.groupValues?.get(1)

    /** Home rows: `Sports`, `F1`, `Kids`, `Entertainment`; everything else (including "") is `Other`. */
    fun row(channel: ZChannel): String = when (val sport = channel.sport?.trim().orEmpty()) {
        "Sports", "F1", "Kids", "Entertainment" -> sport
        else -> "Other"
    }

    /** `FHD`/`HD`/`4K` from the channel list, as a player quality. */
    fun quality(label: String?): Int? = when (label?.trim()?.uppercase()) {
        "4K", "UHD" -> 2160
        "FHD" -> 1080
        "HD" -> 720
        "SD" -> 480
        else -> null
    }

    /** Channels have no logo, only an ISO country code. */
    fun flagPoster(flag: String?): String? =
        flag?.lowercase()?.takeIf { flagCode.matches(it) }?.let { "https://flagcdn.com/w320/$it.png" }

    /** Event thumbnails sometimes arrive HTML-escaped (`?impolicy=resize&amp;w=1920`). */
    fun cleanUrl(url: String?): String? = url?.replace("&amp;", "&")?.ifBlank { null }

    fun location(json: String): String? =
        runCatching { mapper.readValue<ZResolved>(json) }.getOrNull()?.location?.takeIf { it.startsWith("http") }

    fun isDecoy(location: String): Boolean = location.substringAfter("://").substringBefore('/') == DECOY_HOST

    fun epg(json: String): ZEpgChannel? = runCatching { mapper.readValue<ZEpg>(json) }.getOrNull()?.channel

    /** "Now 01:00–01:30: Family Guy\n<desc>\n\nNext 01:30–02:00: …" in the device's time zone. */
    fun epgPlot(epg: ZEpgChannel, zone: TimeZone = TimeZone.getDefault()): String? = listOfNotNull(
        epg.now?.let { programme("Now", it, zone) },
        epg.next?.let { programme("Next", it, zone) },
    ).joinToString("\n\n").ifBlank { null }

    private fun programme(label: String, p: ZProgramme, zone: TimeZone): String? {
        val title = p.title?.trim()?.ifEmpty { null } ?: return null
        val start = clock(p.start, zone)
        val stop = clock(p.stop, zone)
        val time = if (start != null && stop != null) " $start–$stop" else ""
        return listOfNotNull("$label$time: $title", p.desc?.trim()?.ifEmpty { null }).joinToString("\n")
    }

    /** "Basketball · started Wed 19:00" from an event, in the device's time zone. */
    fun eventPlot(event: ZEvent, now: Long, zone: TimeZone = TimeZone.getDefault()): String? {
        val start = parseUtc(event.startTime)
        val time = start?.let {
            val day = SimpleDateFormat("EEE HH:mm", Locale.US).apply { timeZone = zone }.format(Date(it))
            if (it <= now) "started $day" else "starts $day"
        }
        return listOfNotNull(event.type?.ifBlank { null }, time).joinToString(" · ").ifBlank { null }
    }

    private fun clock(iso: String?, zone: TimeZone): String? = parseUtc(iso)?.let {
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }.format(Date(it))
    }

    private fun parseUtc(iso: String?): Long? = iso?.let {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = UTC }.parse(it)?.time
        }.getOrNull()
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** The `d` field: today's date in UTC, whatever the device's zone. */
    fun utcDay(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = UTC }.format(Date(millis))

    /**
     * Encrypts a JSON payload the way the site's `Ig()` does: key = HMAC(HMAC(SHA256(secret:day), "k6"), ticket|v6),
     * then AES-256-GCM with aad `ticket|day`. `a` is the ciphertext, `b` the iv, `c` the 16-byte tag. Tickets are
     * single-use, so every call needs a fresh one from `/session/ticket`.
     */
    fun seal(payload: String, ticket: String, day: String, iv: ByteArray): ZSealed {
        val base = MessageDigest.getInstance("SHA-256").digest("$SECRET:$day".toByteArray())
        val key = hmac(hmac(base, SALT.toByteArray()), (ticket + TICKET_SUFFIX).toByteArray())
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD("$ticket|$day".toByteArray())
            doFinal(payload.toByteArray())
        }
        val b64 = Base64.getEncoder()
        return ZSealed(
            a = b64.encodeToString(sealed.copyOfRange(0, sealed.size - 16)),
            b = b64.encodeToString(iv),
            c = b64.encodeToString(sealed.copyOfRange(sealed.size - 16, sealed.size)),
            d = day,
            e = ticket,
        )
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(message)
    }

    fun sealedJson(sealed: ZSealed): String = mapper.writeValueAsString(sealed)

    /** `{"s":"<key>","u":<unix s>}` in the order the site writes it. */
    fun resolvePayload(key: String, unixSeconds: Long): String =
        mapper.writeValueAsString(linkedMapOf("s" to key, "u" to unixSeconds))

    fun streamsPayload(unixSeconds: Long): String = mapper.writeValueAsString(mapOf("u" to unixSeconds))
}
