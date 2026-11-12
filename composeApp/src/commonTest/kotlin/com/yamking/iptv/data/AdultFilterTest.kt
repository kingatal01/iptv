package com.yamking.iptv.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdultFilterTest {
    @Test
    fun recognisesAdultNamesAndCategories() {
        assertTrue(AdultFilter.isAdult(Channel("Canal XXX", "http://h/1")))
        assertTrue(AdultFilter.isAdult(Channel("Night TV", "http://h/2", groups = listOf("Adult"))))
        assertTrue(AdultFilter.isAdult(Channel("Ciné +18", "http://h/3")))
        assertTrue(AdultFilter.isAdult(Channel("Playboy TV", "http://h/4")))
        assertTrue(AdultFilter.isAdult(Channel("Chaîne érotique", "http://h/5")))
    }

    @Test
    fun leavesOrdinaryChannelsAlone() {
        assertFalse(AdultFilter.isAdult(Channel("Adult Swim", "http://h/1", groups = listOf("Animation"))))
        assertFalse(AdultFilter.isAdult(Channel("Essex TV", "http://h/2")))
        assertFalse(AdultFilter.isAdult(Channel("BBC Sussex", "http://h/3", groups = listOf("News"))))
        assertFalse(AdultFilter.isAdult(Channel("Canal 18", "http://h/4")))
        assertFalse(AdultFilter.isAdult(Channel("Sexta TV", "http://h/5")))
        assertFalse(AdultFilter.isAdult(Channel("France 24", "http://h/6", groups = listOf("News", "General"))))
    }

    @Test
    fun recognisesAdultSearchKeywords() {
        assertTrue(AdultFilter.hasAdultKeyword(listOf("sport", " adulte ")))
        assertTrue(AdultFilter.hasAdultKeyword(listOf("iptv xxx")))
        assertFalse(AdultFilter.hasAdultKeyword(listOf("sport", "cinéma", "info")))
        assertFalse(AdultFilter.hasAdultKeyword(emptyList()))
    }
}
