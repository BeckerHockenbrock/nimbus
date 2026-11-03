package dev.nimbus.client;

/** Current conditions; upstream fields can be absent. */
public record CurrentWeather(String time, String timezone, Double temperature, Double apparentTemperature,
                             Double humidity, Integer weatherCode, Double windSpeed, Double windDirection,
                             Integer isDay, String temperatureUnit, String windSpeedUnit) { }
