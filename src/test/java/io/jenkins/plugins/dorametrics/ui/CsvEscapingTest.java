package io.jenkins.plugins.dorametrics.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Spreadsheets in many locales split CSV on semicolons, so a value with one in it has to be
 * quoted like one with a comma, or the text after it lands in a cell of its own, where the
 * guard against a leading "=" never looked.
 */
public class CsvEscapingTest {

    @Test
    public void aValueWithASemicolonIsQuoted() {
        assertEquals("\"a;=1+1\"", DoraApiAction.escapeCsv("a;=1+1"));
        assertEquals("\"release;1\"", DoraApiAction.escapeCsv("release;1"));
    }

    @Test
    public void plainValuesAreLeftAlone() {
        assertEquals("main", DoraApiAction.escapeCsv("main"));
        assertEquals("'=1+1", DoraApiAction.escapeCsv("=1+1"));
    }
}
