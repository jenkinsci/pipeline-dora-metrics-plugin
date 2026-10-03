package io.jenkins.plugins.dorametrics.dora;

import io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraBand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The band badges carry white text, which has to stay readable (WCAG AA, 4.5:1). */
class BandColourTest {

    private static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    @Test
    void whiteTextIsReadableOnEveryBand() {
        for (DoraBand band : DoraBand.values()) {
            double ratio = 1.05 / (luminance(band.color) + 0.05);
            assertTrue(ratio >= 4.5, band + " " + band.color + " gives " + ratio + ":1");
        }
    }
}
