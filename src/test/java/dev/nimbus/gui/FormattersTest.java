package dev.nimbus.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormattersTest {
    @Test void compassBoundaries() {
        assertEquals("N",Formatters.compass(0.0)); assertEquals("N",Formatters.compass(22.4));
        assertEquals("NE",Formatters.compass(22.5)); assertEquals("N",Formatters.compass(359.0));
        assertEquals("SW",Formatters.compass(220.0)); assertEquals("–",Formatters.compass(null));
    }
    @Test void values() { assertEquals("12.3 °C",Formatters.value(12.3,"°C")); assertEquals("–",Formatters.value(null,"mph")); }
}
