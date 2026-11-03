package dev.nimbus.client;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Geocoding result. Coordinates retain the JSON number's original text. */
public record Location(String name, String latitude, String longitude, String admin1, String country, String countryCode) {
    @Override public String toString() {
        return Stream.of(name, admin1, country).filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(", "));
    }
}
