package dev.nimbus.gui;

import dev.nimbus.client.*;
import java.awt.*;
import javax.swing.*;

public final class WeatherPanel extends JPanel {
    private final JLabel title = new JLabel("Select a location"), temperature = new JLabel("–"), conditions = new JLabel("–"),
            feels = new JLabel("–"), humidity = new JLabel("–"), wind = new JLabel("–"), observed = new JLabel("–"),
            day = new JLabel("–"), served = new JLabel("Served: –");
    private final JButton refresh = new JButton("Refresh");
    public WeatherPanel() {
        super(new GridBagLayout()); setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        GridBagConstraints c = new GridBagConstraints(); c.gridx=0; c.gridy=0; c.anchor=GridBagConstraints.WEST; c.fill=GridBagConstraints.HORIZONTAL; c.weightx=1; c.insets=new Insets(4,4,4,4);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f)); temperature.setFont(temperature.getFont().deriveFont(Font.BOLD, 24f));
        for (JLabel label : new JLabel[]{title,temperature,conditions,feels,humidity,wind,observed,day,served}) { add(label,c); c.gridy++; }
        c.weighty=1; c.anchor=GridBagConstraints.NORTHWEST; add(refresh,c); refresh.setEnabled(false);
    }
    public JButton refreshButton() { return refresh; }
    public void showWeather(Location location, ClientResult<CurrentWeather> result) {
        CurrentWeather w=result.value(); title.setText(location.toString()); temperature.setText(Formatters.value(w.temperature(),w.temperatureUnit()));
        conditions.setText(WeatherCodes.describe(w.weatherCode())); feels.setText("Feels like " + Formatters.value(w.apparentTemperature(),w.temperatureUnit()));
        humidity.setText("Humidity   " + Formatters.value(w.humidity(), "%"));
        wind.setText("Wind   " + Formatters.value(w.windSpeed(),w.windSpeedUnit()) + (w.windDirection() == null ? "" : " " + Formatters.compass(w.windDirection()) + " (" + Formatters.number(w.windDirection()) + "°)"));
        observed.setText("Observed   " + Formatters.text(w.time()).replace('T',' ') + (w.timezone()==null ? "" : " ("+w.timezone()+")"));
        day.setText(w.isDay()==null ? "–" : w.isDay()==1 ? "Day" : "Night");
        served.setText("Served: " + result.cache() + " · " + result.elapsedMs() + " ms");
    }
}
