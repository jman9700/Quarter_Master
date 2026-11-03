package io.github.jman9700.quartermaster.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergeTest {
    @Test
    fun newerWins() {
        assertTrue(Merge.incomingWins(2, "a", 1, "b"))
        assertFalse(Merge.incomingWins(1, "z", 2, "a"))
    }

    @Test
    fun tiesGoToTheLargerDeviceIdTheSameWayEverywhere() {
        assertTrue(Merge.incomingWins(5, "b", 5, "a"))
        assertFalse(Merge.incomingWins(5, "a", 5, "b"))
    }

    @Test
    fun theSameChangeTwiceIsANoOp() {
        assertFalse(Merge.incomingWins(5, "a", 5, "a"))
    }

    @Test
    fun imageNamesCantBePaths() {
        assertTrue(Api.IMAGE_NAME.matches("763746ce-edd0-4dc9-a9b0-5c1776cef0d5.jpg"))
        assertFalse(Api.IMAGE_NAME.matches("../../etc/passwd"))
        assertFalse(Api.IMAGE_NAME.matches("a/b.jpg"))
        assertFalse(Api.IMAGE_NAME.matches("photo.png"))
    }
}
