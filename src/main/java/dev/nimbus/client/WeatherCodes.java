package dev.nimbus.client;

public final class WeatherCodes {
    private WeatherCodes() { }
    public static String describe(Integer code) {
        if (code == null) return "–";
        return switch (code) {
            case 0 -> "Clear sky"; case 1 -> "Mainly clear"; case 2 -> "Partly cloudy"; case 3 -> "Overcast";
            case 45 -> "Fog"; case 48 -> "Depositing rime fog";
            case 51 -> "Light drizzle"; case 53 -> "Moderate drizzle"; case 55 -> "Dense drizzle";
            case 56 -> "Light freezing drizzle"; case 57 -> "Dense freezing drizzle";
            case 61 -> "Slight rain"; case 63 -> "Moderate rain"; case 65 -> "Heavy rain";
            case 66 -> "Light freezing rain"; case 67 -> "Heavy freezing rain";
            case 71 -> "Slight snowfall"; case 73 -> "Moderate snowfall"; case 75 -> "Heavy snowfall";
            case 77 -> "Snow grains"; case 80 -> "Slight rain showers"; case 81 -> "Moderate rain showers";
            case 82 -> "Violent rain showers"; case 85 -> "Slight snow showers"; case 86 -> "Heavy snow showers";
            case 95 -> "Thunderstorm"; case 96 -> "Thunderstorm with slight hail";
            case 99 -> "Thunderstorm with heavy hail"; default -> "Unknown (code " + code + ")";
        };
    }
}
