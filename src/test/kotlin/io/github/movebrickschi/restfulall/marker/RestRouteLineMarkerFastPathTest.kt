package io.github.movebrickschi.restfulall.marker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestRouteLineMarkerFastPathTest {

    @Test
    fun `accepts at sign annotation marker`() {
        assertTrue(isPotentialRouteAnchorText("@"))
    }

    @Test
    fun `accepts annotation identifier like GetMapping`() {
        assertTrue(isPotentialRouteAnchorText("GetMapping"))
        assertTrue(isPotentialRouteAnchorText("RequestMapping"))
        assertTrue(isPotentialRouteAnchorText("Controller"))
    }

    @Test
    fun `accepts lowercase express style identifiers`() {
        assertTrue(isPotentialRouteAnchorText("app"))
        assertTrue(isPotentialRouteAnchorText("router"))
        assertTrue(isPotentialRouteAnchorText("get"))
    }

    @Test
    fun `accepts underscore prefixed identifiers`() {
        assertTrue(isPotentialRouteAnchorText("_route"))
    }

    @Test
    fun `accepts custom user defined identifiers`() {
        assertTrue(isPotentialRouteAnchorText("myApp"))
        assertTrue(isPotentialRouteAnchorText("apiRouter"))
        assertTrue(isPotentialRouteAnchorText("UserController"))
    }

    @Test
    fun `rejects null and empty`() {
        assertFalse(isPotentialRouteAnchorText(null))
        assertFalse(isPotentialRouteAnchorText(""))
    }

    @Test
    fun `rejects whitespace only`() {
        assertFalse(isPotentialRouteAnchorText(" "))
        assertFalse(isPotentialRouteAnchorText("\t"))
        assertFalse(isPotentialRouteAnchorText("\n"))
    }

    @Test
    fun `rejects pure punctuation`() {
        assertFalse(isPotentialRouteAnchorText("("))
        assertFalse(isPotentialRouteAnchorText(")"))
        assertFalse(isPotentialRouteAnchorText("{"))
        assertFalse(isPotentialRouteAnchorText("}"))
        assertFalse(isPotentialRouteAnchorText(";"))
        assertFalse(isPotentialRouteAnchorText(","))
        assertFalse(isPotentialRouteAnchorText("."))
        assertFalse(isPotentialRouteAnchorText("="))
        assertFalse(isPotentialRouteAnchorText("+"))
    }

    @Test
    fun `rejects digit literals`() {
        assertFalse(isPotentialRouteAnchorText("0"))
        assertFalse(isPotentialRouteAnchorText("123"))
        assertFalse(isPotentialRouteAnchorText("3.14"))
    }

    @Test
    fun `rejects string literals`() {
        assertFalse(isPotentialRouteAnchorText("\"/api/users\""))
        assertFalse(isPotentialRouteAnchorText("'hello'"))
        assertFalse(isPotentialRouteAnchorText("`template`"))
    }

    @Test
    fun `rejects tokens that exceed the length ceiling`() {
        val tooLong = "a".repeat(65)
        assertFalse(isPotentialRouteAnchorText(tooLong))
    }

    @Test
    fun `accepts tokens at the length boundary`() {
        val rightAtCeiling = "a".repeat(64)
        assertTrue(isPotentialRouteAnchorText(rightAtCeiling))
    }

    @Test
    fun `rejects tokens that start with punctuation other than at sign`() {
        assertFalse(isPotentialRouteAnchorText("/path"))
        assertFalse(isPotentialRouteAnchorText("#tag"))
        assertFalse(isPotentialRouteAnchorText("\$var"))
    }
}
