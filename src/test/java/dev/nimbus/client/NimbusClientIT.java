package dev.nimbus.client;

import dev.nimbus.bench.FakeUpstreamServer;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.proxy.Outcome;
import dev.nimbus.server.ProxyServer;
import dev.nimbus.testutil.ProxyFixture;
import dev.nimbus.upstream.HttpUpstreamClient;
import java.net.URI;
import java.time.InstantSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NimbusClientIT {
    @Test void searchWeatherAndMetricsThroughProxy() throws Exception {
        try (var fake=FakeUpstreamServer.start(0,FakeUpstreamServer.Options.defaults())) {
            var config=NimbusConfig.withOverrides(Map.of("nimbus.port","0", "nimbus.forecast.upstream",fake.forecastUrl().toString(), "nimbus.geocoding.upstream",fake.searchUrl().toString()));
            try (var upstream=new HttpUpstreamClient(config.upstreamConnectTimeout(),config.upstreamDeadline(),config.upstreamMaxResponseBytes())) {
                var fixture=ProxyFixture.create(config,upstream,InstantSource.system());
                var server=new ProxyServer(config,fixture.service(),fixture.metrics(),fixture.rateLimiter()); server.start();
                try {
                    var client=new NimbusClient(URI.create("http://127.0.0.1:"+server.port()));
                    var location=client.search("Berlin").value().getFirst();
                    assertEquals("MISS",client.weather(location,false).cache());
                    assertEquals("HIT",client.weather(location,false).cache());
                    assertEquals("MISS",client.weather(location,true).cache());
                    assertEquals("HIT",client.weather(location,true).cache());
                    assertEquals(2,client.metrics().outcome(Outcome.HIT));
                } finally { server.stop(); }
            }
        }
    }
}
