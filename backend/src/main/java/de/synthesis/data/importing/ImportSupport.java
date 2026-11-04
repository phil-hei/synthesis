package de.synthesis.data.importing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Format-independent, deterministic import primitives shared with company tours. */
public final class ImportSupport {
    private ImportSupport() { }
    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String value(TabularReader.Row row,int column) {
        return column >= 0 && column < row.cells().size() ? row.cells().get(column) : "";
    }
    public static String structure(TabularReader.Table table,int headerRow) {
        var row=table.rows().stream().filter(r->r.sourceRow()==headerRow).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Kopfzeile nicht gefunden"));
        return hash(table.id()+"\n"+headerRow+"\n"+String.join("\u001f",row.cells()));
    }
    public static void validateColumns(Map<String,Integer> columns,Set<String> allowed,int width) {
        if(columns==null)throw new IllegalArgumentException("Spaltenzuordnung fehlt");
        Set<Integer> used=new HashSet<>();
        for(var e:columns.entrySet())if(!allowed.contains(e.getKey())||e.getValue()==null||e.getValue()<0||e.getValue()>=width||!used.add(e.getValue()))
            throw new IllegalArgumentException("Ungültige oder doppelte Spaltenzuordnung: "+e.getKey());
    }
}
