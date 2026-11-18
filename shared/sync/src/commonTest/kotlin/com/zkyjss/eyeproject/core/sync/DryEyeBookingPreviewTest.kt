package com.zkyjss.eyeproject.core.sync

import kotlin.test.*

class DryEyeBookingPreviewTest {
    private val dry = ChatEntry(Chats.USER, "最近常常眼睛干涩", 1)
    private val summary = ChatEntry(Chats.ASSISTANT, "本次问诊总结", 2, inquiryComplete = true)

    @Test fun dryEyeSummaryOffersBookingPreview() {
        assertTrue(DryEyeBookingPreview.shouldOffer(listOf(dry, summary)))
        assertFalse(DryEyeBookingPreview.shouldOffer(listOf(dry, summary.copy(inquiryComplete = false))))
        assertFalse(DryEyeBookingPreview.shouldOffer(listOf(dry, summary, dry)))
    }

    @Test fun urgentStoppedAndOtherInquiriesDoNotOfferDryEyeBooking() {
        val other = dry.copy(text = "读小字模糊")
        assertFalse(DryEyeBookingPreview.shouldOffer(listOf(other, summary)))
        assertFalse(DryEyeBookingPreview.shouldOffer(listOf(dry, summary.copy(urgent = true))))
        assertFalse(DryEyeBookingPreview.shouldOffer(listOf(dry, dry.copy(urgent = true), summary)))
        assertFalse(DryEyeBookingPreview.shouldOffer(listOf(dry, dry.copy(text = Chats.STOP_INQUIRY), summary)))
        assertFalse(DryEyeBookingPreview.shouldOffer(listOf(dry, summary, other, summary)))
    }
}
