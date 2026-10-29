package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class StreamCornerDrmTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun extractsTheCapturedPageAndPlayerDecodersWithoutExecutingJavaScript() {
        for ((fixture, size, hash) in listOf(
            Triple("page-wasm-carrier.js", 126088, "2fd0a2e398dae0d88e3896f1b9573d64adf7de1f1acbf73cabc42fac5914d4be"),
            Triple("key-wasm-carrier.js", 90128, "a35aeebd9d575944f194f6d6de02a85a24ee391ba65309dde261f1462a624cfd"),
        )) {
            val module = StreamCornerDrm.extract("<script>${text(fixture)}</script>").single()
            assertEquals(size, module.bytes.size)
            assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(module.bytes).joinToString("") { "%02x".format(it) })
        }
    }

    @Test fun doesNotTreatMentionedKeysOrInvalidCarriersAsDecoderModules() {
        assertTrue(StreamCornerDrm.extract("const source = 'license clearkey AGFzbQ not a module';").isEmpty())
        assertTrue(StreamCornerDrm.extract("var a = [callSomething()];").isEmpty())
    }

    @Test fun legacyEmbedCarriesUrlAndClearKeyTogether() {
        // Synthetic values encoded using the legacy contract observed in the FOX embed; no production key.
        val token = "SZO2THDYMGQZHEUAKNNWNG4NSJGH7DNATSULVEENU6WZJILFLRQGQW5AVCGFGYTPUSOGTIETTSUIW53BUZOEWWSZTBGU2VMSVGWY5D4NUCOKROVGOBKVFG5KR2UJ5KMOU6RIZPSPJ5IFAUKRKJJMHFUYSZHUWWSMK5OFKTDCOCQ2CVNBUGQKBH3PLNRFWUSLK6S2DFNBTWIMCUKQKBHU6TSOJW6Y3EENUCHFW"
        val stream = StreamCornerParser.legacyDrmStream(StreamCornerDrm.legacyPayload(token)!!)!!
        assertEquals("https://media.example/cenc.mpd", stream.url)
        assertEquals("00112233445566778899aabbccddeeff", stream.keys.single().kid)
        assertEquals("ffeeddccbbaa99887766554433221100", stream.keys.single().key)
        assertTrue(StreamCornerDrm.legacyPayload("invalid!") == null)
        assertTrue(StreamCornerParser.legacyDrmStream("""{"api_url":"https://example.test/resolve"}""") == null)
    }
}
