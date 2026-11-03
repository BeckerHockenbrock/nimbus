# Nimbus

Nimbus is a caching weather proxy and Swing client for Open-Meteo. Build with Java 21 and Maven:

```sh
mvn -B verify
java -jar target/nimbus.jar all
```

Use `server` to run only the proxy, or `gui` to connect the desktop client to an existing proxy. The GUI proxy URL defaults to `http://127.0.0.1:8080` and can be set with `--nimbus.gui.proxyUrl=http://host:port`. Run `java -jar target/nimbus.jar --help` for other modes and options. Weather data is provided by [Open-Meteo](https://open-meteo.com/) under CC BY 4.0.

## Desktop verification checklist

These checks require a graphical desktop and a running proxy:

- [ ] Search for Berlin, select a match, confirm MISS, then refresh and confirm HIT and updated statistics.
- [ ] Switch to °F, confirm MISS, then refresh and confirm HIT.
- [ ] In `gui` mode, stop the server and confirm the stats show unreachable and the UI remains responsive.
- [ ] Resize the window and confirm it remains usable.
