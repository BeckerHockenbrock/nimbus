package dev.nimbus.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientParsingTest {
    @Test void locationsPreserveCoordinatesAndAllowMissingFields() throws Exception {
        var locations = NimbusClient.parseLocations("{\"results\":[{\"name\":\"Berlin\",\"latitude\":52.52000,\"longitude\":-1.2300e+01}]}");
        assertEquals("52.52000",locations.getFirst().latitude());
        assertEquals("-1.2300e+01",locations.getFirst().longitude());
        assertNull(locations.getFirst().admin1());
        assertTrue(NimbusClient.parseLocations("{\"generationtime_ms\":0.1}").isEmpty());
    }
    @Test void weatherAllowsOptionalFields() throws Exception {
        var w=NimbusClient.parseWeather("{\"timezone\":\"Europe/Berlin\",\"current\":{\"temperature_2m\":12.3,\"weather_code\":3}}");
        assertEquals(12.3,w.temperature()); assertNull(w.humidity()); assertNull(w.temperatureUnit());
        assertThrows(java.io.IOException.class, () -> NimbusClient.parseWeather("{}"));
    }
    @Test void weatherCodes() {
        assertEquals("Overcast",WeatherCodes.describe(3)); assertEquals("Thunderstorm",WeatherCodes.describe(95));
        assertEquals("Unknown (code 123)",WeatherCodes.describe(123)); assertEquals("–",WeatherCodes.describe(null));
    }
}
