package io.github.jman9700.quartermaster.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BarcodeTest {

    private fun valid(raw: String, hint: BarcodeHint = BarcodeHint.UNKNOWN): String =
        assertIs<BarcodeResult.Valid>(Barcode.normalize(raw, hint), "expected $raw to be valid").gtin14

    @Test
    fun upcAIsPaddedToGtin14() {
        assertEquals("00036000291452", valid("036000291452"))
    }

    @Test
    fun upcAAndItsEan13FormMatch() {
        assertEquals(valid("036000291452"), valid("0036000291452"))
    }

    @Test
    fun ean13IsAccepted() {
        assertEquals("04006381333931", valid("4006381333931"))
    }

    @Test
    fun leadingZerosSurvive() {
        // The alpha stored UPCs as ints, which turned this into 12345678905.
        assertEquals("012345678905", Barcode.display(valid("012345678905")))
    }

    @Test
    fun spacesAndDashesAreIgnored() {
        assertEquals(valid("036000291452"), valid("0 36000-29145 2"))
    }

    @Test
    fun wrongCheckDigitIsRejected() {
        assertIs<BarcodeResult.Invalid>(Barcode.normalize("036000291453"))
    }

    @Test
    fun badInputIsRejected() {
        assertIs<BarcodeResult.Invalid>(Barcode.normalize(""))
        assertIs<BarcodeResult.Invalid>(Barcode.normalize("12345"))
        assertIs<BarcodeResult.Invalid>(Barcode.normalize("03600029145A"))
    }

    @Test
    fun upcEExpandsToTheSameProductAsUpcA() {
        assertEquals("042100005264", Barcode.expandUpcE("04252614"))
        assertEquals(valid("042100005264"), valid("04252614"))
        assertEquals(valid("042100005264"), valid("04252614", BarcodeHint.UPC_E))
    }

    @Test
    fun upcEWithoutNumberSystemOrCheckDigitIsCompleted() {
        assertEquals(valid("042100005264"), valid("425261", BarcodeHint.UPC_E))
        assertEquals(valid("042100005264"), valid("0425261", BarcodeHint.UPC_E))
    }

    @Test
    fun upcEExpansionRules() {
        // Compare without the check digit, which is passed through unchanged.
        assertEquals("01200000345", Barcode.expandUpcE("01234500")?.dropLast(1))
        assertEquals("01230000045", Barcode.expandUpcE("01234530")?.dropLast(1))
        assertEquals("01234000005", Barcode.expandUpcE("01234540")?.dropLast(1))
        assertEquals("01234500006", Barcode.expandUpcE("01234560")?.dropLast(1))
    }

    @Test
    fun ean8IsAccepted() {
        assertEquals("00000096385074", valid("96385074"))
        assertEquals("96385074", Barcode.display("00000096385074"))
    }

    @Test
    fun displayAndLookupForms() {
        val upcA = valid("036000291452")
        assertEquals("036000291452", Barcode.display(upcA))
        assertEquals("0036000291452", Barcode.lookupCode(upcA))
        val ean = valid("4006381333931")
        assertEquals("4006381333931", Barcode.display(ean))
        assertEquals("4006381333931", Barcode.lookupCode(ean))
    }
}
