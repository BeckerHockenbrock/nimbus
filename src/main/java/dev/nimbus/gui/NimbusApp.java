package dev.nimbus.gui;

import dev.nimbus.client.*;
import dev.nimbus.metrics.MetricsSnapshot;
import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.*;

/** Swing UI; all proxy requests run in workers and all widget updates on the EDT. */
public final class NimbusApp {
    private final NimbusClient client;
    private final JFrame frame = new JFrame("Nimbus Weather Proxy");
    private final JTextField city = new JTextField(20);
    private final JButton search = new JButton("Search");
    private final JComboBox<String> units = new JComboBox<>(new String[]{"°C", "°F"});
    private final SearchPanel matches = new SearchPanel();
    private final WeatherPanel weather = new WeatherPanel();
    private final StatsPanel stats = new StatsPanel();
    private final JLabel status = new JLabel("Last error: –");
    private final Timer timer;
    private boolean polling;
    private long weatherGeneration;
    private NimbusApp(URI url, Duration refresh, Runnable onClose) {
        client=new NimbusClient(url);
        JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT)); top.add(new JLabel("City:")); top.add(city); top.add(search); top.add(new JLabel("Units:")); top.add(units);
        JPanel center=new JPanel(new GridBagLayout()); GridBagConstraints c=new GridBagConstraints(); c.gridy=0; c.weighty=1; c.fill=GridBagConstraints.BOTH;
        c.gridx=0; c.weightx=.25; center.add(matches,c); c.gridx=1; c.weightx=.48; center.add(weather,c); c.gridx=2; c.weightx=.27; center.add(stats,c);
        JPanel bottom=new JPanel(new BorderLayout()); bottom.add(new JLabel("Weather data by Open-Meteo.com (CC BY 4.0)"),BorderLayout.WEST); bottom.add(status,BorderLayout.EAST);
        frame.add(top,BorderLayout.NORTH); frame.add(center,BorderLayout.CENTER); frame.add(bottom,BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); frame.setMinimumSize(new Dimension(800,400)); frame.setSize(1150,600); frame.setLocationByPlatform(true);
        search.addActionListener(e -> search()); city.addActionListener(e -> search());
        matches.locationList().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && matches.locationList().getSelectedValue()!=null) loadWeather(); });
        weather.refreshButton().addActionListener(e -> loadWeather()); units.addActionListener(e -> { if (matches.locationList().getSelectedValue()!=null) loadWeather(); });
        timer=new Timer((int)Math.min(Integer.MAX_VALUE,refresh.toMillis()),e -> poll());
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { timer.stop(); onClose.run(); } });
    }
    public static void open(URI url, Duration refresh, Runnable onClose) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { /* use default */ }
        NimbusApp app=new NimbusApp(url,refresh,onClose); app.frame.setVisible(true); app.poll(); app.timer.start();
    }
    private void search() {
        if (!search.isEnabled() || city.getText().isBlank()) return;
        String name=city.getText(); search.setEnabled(false); ++weatherGeneration; weather.refreshButton().setEnabled(false); matches.setLocations(List.of());
        new SwingWorker<ClientResult<List<Location>>,Void>() {
            protected ClientResult<List<Location>> doInBackground() throws Exception { return client.search(name); }
            protected void done() { search.setEnabled(true); try { var locations=get().value(); matches.setLocations(locations); if (locations.isEmpty()) status.setText("No matches"); else { status.setText("Last error: –"); if (locations.size()==1) matches.locationList().setSelectedIndex(0); } } catch (Exception e) { error(e); } }
        }.execute();
    }
    private void loadWeather() {
        Location location=matches.locationList().getSelectedValue(); if (location==null) return;
        boolean fahrenheit=units.getSelectedIndex()==1; long generation=++weatherGeneration; weather.refreshButton().setEnabled(false);
        new SwingWorker<ClientResult<CurrentWeather>,Void>() {
            protected ClientResult<CurrentWeather> doInBackground() throws Exception { return client.weather(location,fahrenheit); }
            protected void done() { if (generation!=weatherGeneration) return; weather.refreshButton().setEnabled(true); try { weather.showWeather(location,get()); status.setText("Last error: –"); } catch (Exception e) { error(e); } }
        }.execute();
    }
    private void poll() {
        if (polling) return; polling=true;
        new SwingWorker<MetricsSnapshot,Void>() {
            protected MetricsSnapshot doInBackground() throws Exception { return client.metrics(); }
            protected void done() { polling=false; try { stats.update(get()); } catch (Exception e) { stats.unreachable(); } }
        }.execute();
    }
    private void error(Exception e) { Throwable cause=e instanceof ExecutionException && e.getCause()!=null ? e.getCause() : e; status.setText("Last error: " + cause.getMessage()); }
}
