package io.github.jman9700.quartermaster.domain

/** What the scanner reported, used to disambiguate 8-digit codes (UPC-E vs EAN-8). */
enum class BarcodeHint { UPC_A, UPC_E, EAN_13, EAN_8, UNKNOWN }

sealed interface BarcodeResult {
    /** [gtin14] is the canonical key stored in the database. */
    data class Valid(val gtin14: String) : BarcodeResult
    data class Invalid(val reason: String) : BarcodeResult
}

/**
 * Retail barcode handling. Every UPC-A, UPC-E, EAN-13, EAN-8 and GTIN-14 is normalized to a
 * 14-digit GTIN so the same product always matches, however it was scanned or typed.
 */
object Barcode {

    fun normalize(raw: String, hint: BarcodeHint = BarcodeHint.UNKNOWN): BarcodeResult {
        val digits = raw.filterNot { it == ' ' || it == '-' }
        if (digits.isEmpty()) return BarcodeResult.Invalid("Barcode is empty")
        if (!digits.all { it in '0'..'9' }) return BarcodeResult.Invalid("Barcode must contain only digits")

        return when (digits.length) {
            // Some scanners report UPC-E without the number system and/or check digit.
            6, 7 -> if (hint == BarcodeHint.UPC_E) completeUpcE(digits) else BarcodeResult.Invalid("Expected 8, 12, 13 or 14 digits, got ${digits.length}")
            8 -> normalizeEight(digits, hint)
            12, 13, 14 -> if (hasValidCheckDigit(digits)) {
                BarcodeResult.Valid(digits.padStart(14, '0'))
            } else {
                BarcodeResult.Invalid("Check digit doesn't match. Re-scan or re-type the code")
            }
            else -> BarcodeResult.Invalid("Expected 8, 12, 13 or 14 digits, got ${digits.length}")
        }
    }

    /** The form people recognize: 12 digits for UPC-A, 13 for EAN-13, 8 for EAN-8. */
    fun display(gtin14: String): String = when {
        gtin14.startsWith("000000") -> gtin14.substring(6)
        gtin14.startsWith("00") -> gtin14.substring(2)
        gtin14.startsWith("0") -> gtin14.substring(1)
        else -> gtin14
    }

    /** Code format used by OpenFoodFacts (EAN-13 for UPC-A products). */
    fun lookupCode(gtin14: String): String = when {
        gtin14.startsWith("000000") -> gtin14.substring(6)
        gtin14.startsWith("0") -> gtin14.substring(1)
        else -> gtin14
    }

    /** GS1 mod-10 check digit for [body] (all digits except the check digit). */
    fun checkDigit(body: String): Int {
        var sum = 0
        body.reversed().forEachIndexed { i, c ->
            val d = c - '0'
            sum += if (i % 2 == 0) d * 3 else d
        }
        return (10 - sum % 10) % 10
    }

    fun hasValidCheckDigit(code: String): Boolean =
        code.length >= 2 && checkDigit(code.dropLast(1)) == code.last() - '0'

    /** Expands an 8-digit UPC-E (number system, 6 data digits, check digit) to 12-digit UPC-A. */
    fun expandUpcE(upcE: String): String? {
        if (upcE.length != 8 || upcE[0] !in "01") return null
        val ns = upcE[0]
        val s = upcE.substring(1, 7)
        val check = upcE[7]
        val body = when (s[5]) {
            '0', '1', '2' -> "${s[0]}${s[1]}${s[5]}0000${s[2]}${s[3]}${s[4]}"
            '3' -> "${s[0]}${s[1]}${s[2]}00000${s[3]}${s[4]}"
            '4' -> "${s[0]}${s[1]}${s[2]}${s[3]}00000${s[4]}"
            else -> "${s[0]}${s[1]}${s[2]}${s[3]}${s[4]}0000${s[5]}"
        }
        return "$ns$body$check"
    }

    private fun completeUpcE(digits: String): BarcodeResult {
        val withSystem = if (digits.length == 6) "0$digits" else digits
        val body = expandUpcE(withSystem + "0")?.dropLast(1)
            ?: return BarcodeResult.Invalid("Not a valid UPC-E code")
        return BarcodeResult.Valid((body + checkDigit(body)).padStart(14, '0'))
    }

    private fun normalizeEight(digits: String, hint: BarcodeHint): BarcodeResult {
        val asUpcA = expandUpcE(digits)?.takeIf { hasValidCheckDigit(it) }
        val asEan8 = digits.takeIf { hasValidCheckDigit(it) }
        // Without a scanner hint, prefer UPC-E: EAN-8 codes starting with 0/1 are store-internal.
        val chosen = when (hint) {
            BarcodeHint.UPC_E -> asUpcA
            BarcodeHint.EAN_8 -> asEan8
            else -> asUpcA ?: asEan8
        }
        return chosen?.let { BarcodeResult.Valid(it.padStart(14, '0')) }
            ?: BarcodeResult.Invalid("Check digit doesn't match. Re-scan or re-type the code")
    }
}
