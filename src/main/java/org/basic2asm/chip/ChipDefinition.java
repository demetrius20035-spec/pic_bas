package org.basic2asm.chip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable description of a single PIC12/PIC16 microcontroller: its core,
 * memory geometry, peripherals, ports and default fuse configuration. New chips
 * are added by registering one of these in {@link ChipRegistry} – no code-generator
 * changes are required for a chip that fits the existing peripheral model.
 */
public final class ChipDefinition {

    private final String name;
    private final CoreType core;
    private final String mpasmProcessor;   // e.g. "16f84a" for  LIST p=16f84a
    private final String includeFile;      // e.g. "p16f84a.inc"
    private final int programWords;        // flash size in instruction words
    private final int gprStart;            // first general-purpose RAM address (bank 0)
    private final int gprEnd;              // last general-purpose RAM address (bank 0)
    private final int stackDepth;          // hardware call-stack levels
    private final Set<Peripheral> peripherals;
    private final List<PortInfo> ports;
    private final int adcChannels;
    private final int adcResolutionBits;
    private final String usartTxRegister;  // TXREG / null
    private final String usartRxRegister;  // RCREG / null

    // Fuse tokens (names as found in the MPASM include for this part).
    private final Map<String, String> oscTokens; // key: XT/HS/LP/INTRC/EXTRC
    private final String baseConfigTokens;        // e.g. "_WDT_OFF & _PWRTE_ON & _CP_OFF"
    private final int configAddress;              // e.g. 0x2007 (informational)

    private ChipDefinition(Builder b) {
        this.name = b.name;
        this.core = b.core;
        this.mpasmProcessor = b.mpasmProcessor;
        this.includeFile = b.includeFile;
        this.programWords = b.programWords;
        this.gprStart = b.gprStart;
        this.gprEnd = b.gprEnd;
        this.stackDepth = b.stackDepth;
        this.peripherals = Collections.unmodifiableSet(EnumSet.copyOf(b.peripherals));
        this.ports = Collections.unmodifiableList(new ArrayList<>(b.ports));
        this.adcChannels = b.adcChannels;
        this.adcResolutionBits = b.adcResolutionBits;
        this.usartTxRegister = b.usartTxRegister;
        this.usartRxRegister = b.usartRxRegister;
        this.oscTokens = Collections.unmodifiableMap(new LinkedHashMap<>(b.oscTokens));
        this.baseConfigTokens = b.baseConfigTokens;
        this.configAddress = b.configAddress;
    }

    public String getName()            { return name; }
    public CoreType getCore()          { return core; }
    public String getMpasmProcessor()  { return mpasmProcessor; }
    public String getIncludeFile()     { return includeFile; }
    public int getProgramWords()       { return programWords; }
    public int getGprStart()           { return gprStart; }
    public int getGprEnd()             { return gprEnd; }
    public int getGprBytes()           { return gprEnd - gprStart + 1; }
    public int getStackDepth()         { return stackDepth; }
    public List<PortInfo> getPorts()   { return ports; }
    public int getAdcChannels()        { return adcChannels; }
    public int getAdcResolutionBits()  { return adcResolutionBits; }
    public String getUsartTxRegister() { return usartTxRegister; }
    public String getUsartRxRegister() { return usartRxRegister; }
    public Map<String, String> getOscTokens() { return oscTokens; }
    public String getBaseConfigTokens()       { return baseConfigTokens; }
    public int getConfigAddress()             { return configAddress; }

    public boolean has(Peripheral p) { return peripherals.contains(p); }
    public Set<Peripheral> getPeripherals() { return peripherals; }

    /** Find the port that owns a given pin prefix (e.g. "RB" -> PORTB). */
    public PortInfo portForPinPrefix(String prefix) {
        for (PortInfo p : ports) {
            if (p.pinPrefix.equalsIgnoreCase(prefix)) {
                return p;
            }
        }
        return null;
    }

    /** Find a port by its data-register name (e.g. "PORTB"). */
    public PortInfo portByRegister(String register) {
        for (PortInfo p : ports) {
            if (p.register.equalsIgnoreCase(register)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name + " (" + core + ", " + programWords + "w flash, "
                + getGprBytes() + "B RAM, " + peripherals + ")";
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static final class Builder {
        private String name;
        private CoreType core = CoreType.MIDRANGE;
        private String mpasmProcessor;
        private String includeFile;
        private int programWords;
        private int gprStart;
        private int gprEnd;
        private int stackDepth = 8;
        private final Set<Peripheral> peripherals = EnumSet.of(Peripheral.GPIO);
        private final List<PortInfo> ports = new ArrayList<>();
        private int adcChannels = 0;
        private int adcResolutionBits = 0;
        private String usartTxRegister;
        private String usartRxRegister;
        private final Map<String, String> oscTokens = new LinkedHashMap<>();
        private String baseConfigTokens = "";
        private int configAddress = 0x2007;

        public Builder(String name, CoreType core) {
            this.name = name;
            this.core = core;
            this.stackDepth = core.isBaseline() ? 2 : 8;
        }

        public Builder processor(String p)     { this.mpasmProcessor = p; return this; }
        public Builder include(String inc)      { this.includeFile = inc; return this; }
        public Builder program(int words)       { this.programWords = words; return this; }
        public Builder gpr(int start, int end)  { this.gprStart = start; this.gprEnd = end; return this; }
        public Builder stack(int depth)         { this.stackDepth = depth; return this; }
        public Builder add(Peripheral... ps)    { Collections.addAll(this.peripherals, ps); return this; }
        public Builder port(PortInfo p)         { this.ports.add(p); return this; }
        public Builder adc(int channels, int bits) {
            this.adcChannels = channels; this.adcResolutionBits = bits;
            this.peripherals.add(Peripheral.ADC); return this;
        }
        public Builder usart(String tx, String rx) {
            this.usartTxRegister = tx; this.usartRxRegister = rx;
            this.peripherals.add(Peripheral.UART_HW); return this;
        }
        public Builder osc(String kind, String token) { this.oscTokens.put(kind, token); return this; }
        public Builder baseConfig(String tokens)       { this.baseConfigTokens = tokens; return this; }
        public Builder configAddress(int addr)         { this.configAddress = addr; return this; }

        public ChipDefinition build() {
            if (mpasmProcessor == null) {
                mpasmProcessor = name.toLowerCase().replace("pic", "");
            }
            if (includeFile == null) {
                includeFile = "p" + mpasmProcessor + ".inc";
            }
            return new ChipDefinition(this);
        }
    }
}
