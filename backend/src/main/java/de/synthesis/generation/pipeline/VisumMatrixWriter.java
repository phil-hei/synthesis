package de.synthesis.generation.pipeline;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes matrices in the Visum "$V;D3" text format, byte-compatible with the
 * serializer/parser in mobiTopp (edu.kit.ifv.mobitopp.visum.VisumMatrixParser).
 * Files ending in .bz2 are compressed transparently, matching the
 * *.mtx.bz2 layout of the mobiTopp tutorial.
 */
public final class VisumMatrixWriter {

    private static final String EOL = System.lineSeparator();

    private VisumMatrixWriter() {
    }

    public static void write(Path file, List<Zone> zones, float[][] values) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = open(file)) {
            writeHeader(writer);
            writeZones(writer, zones);
            writeValues(writer, zones, values);
            writeZoneNames(writer, zones);
        }
    }

    private static Writer open(Path file) throws IOException {
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
        if (file.getFileName().toString().endsWith(".bz2")) {
            out = new BZip2CompressorOutputStream(out);
        }
        return new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }

    private static void writeHeader(Writer w) throws IOException {
        w.write("$V;D3" + EOL
                + "* Von  Bis" + EOL
                + "0.00 0.00" + EOL
                + "* Faktor" + EOL
                + "1.00" + EOL
                + "*  " + EOL
                + "* SyntheSis Szenariogenerator" + EOL
                + "* synthetisch erzeugte Matrix" + EOL);
    }

    private static void writeZones(Writer w, List<Zone> zones) throws IOException {
        w.write("* Anzahl Netzobjekte" + EOL);
        w.write(zones.size() + EOL);
        w.write("* Netzobjekt-Nummern" + EOL);
        int written = 0;
        StringBuilder ids = new StringBuilder();
        for (Zone zone : zones) {
            if (written % 10 == 0 && written != 0) {
                ids.append(EOL);
            }
            ids.append(' ').append(zone.id);
            written++;
        }
        ids.append(EOL);
        w.write(ids.toString());
        w.write("*" + EOL);
    }

    private static void writeValues(Writer w, List<Zone> zones, float[][] values) throws IOException {
        for (Zone from : zones) {
            double sum = 0;
            for (Zone to : zones) {
                sum += values[from.index][to.index];
            }
            w.write("* Obj " + from.index + " Summe = " + sum + EOL);
            StringBuilder row = new StringBuilder();
            int written = 0;
            for (Zone to : zones) {
                if (written % 10 == 0 && written != 0) {
                    row.append(EOL);
                }
                row.append(' ').append(format(values[from.index][to.index]));
                written++;
            }
            row.append(EOL);
            w.write(row.toString());
        }
    }

    private static void writeZoneNames(Writer w, List<Zone> zones) throws IOException {
        w.write("* Netzobjektnamen" + EOL);
        w.write("$NAMES" + EOL);
        for (Zone zone : zones) {
            w.write(zone.id + " \"" + zone.index + "\"" + EOL);
        }
        w.write(" ");
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
