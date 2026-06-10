package org.basic2asm.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Loads a {@link Config} from a JSON file. Command-line options layered on top
 * (see {@code ArgParser}) take precedence over file values, so a config file can
 * provide project defaults that individual invocations override.
 */
public final class ConfigLoader {

    private ConfigLoader() { }

    public static void applyJsonFile(String path, Config config) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        Object root = Json.parse(text);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("config file root must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) root;

        if (m.containsKey("chip"))       config.chip = str(m.get("chip"));
        if (m.containsKey("input"))      config.inputFile = str(m.get("input"));
        if (m.containsKey("output"))     config.outputFile = str(m.get("output"));
        if (m.containsKey("list"))       config.listFile = str(m.get("list"));
        if (m.containsKey("map"))        config.mapFile = str(m.get("map"));
        if (m.containsKey("clock"))      config.clockHz = Config.parseClock(str(m.get("clock")));
        if (m.containsKey("osc"))        config.oscMode = str(m.get("osc")).toUpperCase();
        if (m.containsKey("optimize"))   config.optimize = "speed".equalsIgnoreCase(str(m.get("optimize")))
                ? Config.Optimize.SPEED : Config.Optimize.SIZE;
        if (m.containsKey("comments"))   config.comments = bool(m.get("comments"));
        if (m.containsKey("uartMode"))   config.uartMode = "hardware".equalsIgnoreCase(str(m.get("uartMode")))
                ? Config.UartMode.HARDWARE : Config.UartMode.SOFTWARE;
        if (m.containsKey("uartBaud"))   config.uartBaud = (int) dbl(m.get("uartBaud"));
        if (m.containsKey("uartTxPin"))  config.uartTxPin = str(m.get("uartTxPin"));
        if (m.containsKey("uartRxPin"))  config.uartRxPin = str(m.get("uartRxPin"));
        if (m.containsKey("adcResolution")) config.adcResolution = (int) dbl(m.get("adcResolution"));
        if (m.containsKey("configWord")) config.rawConfig = str(m.get("configWord"));
    }

    private static String str(Object o)  { return o == null ? null : String.valueOf(o); }
    private static double dbl(Object o)  { return o instanceof Number ? ((Number) o).doubleValue()
            : Double.parseDouble(String.valueOf(o)); }
    private static boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
