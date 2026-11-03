package dev.nimbus.gui;

import dev.nimbus.metrics.MetricsSnapshot;
import dev.nimbus.proxy.Outcome;
import java.awt.*;
import javax.swing.*;

public final class StatsPanel extends JPanel {
    private final JLabel connection = new JLabel("● connecting");
    private final JTextArea stats = new JTextArea();
    public StatsPanel() {
        super(new BorderLayout(0,8)); setBorder(BorderFactory.createTitledBorder("Proxy stats"));
        add(connection,BorderLayout.NORTH); stats.setEditable(false); stats.setOpaque(false); stats.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12)); add(stats,BorderLayout.CENTER);
    }
    public void unreachable() { connection.setText("● unreachable"); }
    public void update(MetricsSnapshot m) {
        connection.setText("● connected"); var l=m.latencyMs().all(); var cache=m.cache();
        stats.setText(String.format(java.util.Locale.ROOT,
            "Requests           %,d%nHits / Misses      %,d / %,d%nStale (no call)    %,d%nStale (after err)  %,d%nHit ratio          %s%nUpstream calls     %,d%nCalls saved        %,d%nRate limited       %,d%nCache  %,d · %.1f/%.1f MiB%n%nLatency p50/p95/p99 (ms)%n  %s / %s / %s",
            m.requests().total(),m.outcome(Outcome.HIT),m.outcome(Outcome.MISS),m.outcome(Outcome.STALE_WITHOUT_UPSTREAM),m.outcome(Outcome.STALE_AFTER_UPSTREAM_FAILURE),
            m.derived().cacheHitRatio()==null ? "–" : String.format(java.util.Locale.ROOT,"%.1f %%",m.derived().cacheHitRatio()*100),m.upstream().calls(),m.derived().upstreamCallsSaved(),m.outcome(Outcome.RATE_LIMITED),cache.entries(),cache.bytes()/1048576.0,cache.maxBytes()/1048576.0,Formatters.number(l.p50()),Formatters.number(l.p95()),Formatters.number(l.p99())));
    }
}
